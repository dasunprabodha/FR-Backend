package lk.cf.fr.monolith.document;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.leptonica.PIX;
import org.bytedeco.tesseract.ResultIterator;
import org.bytedeco.tesseract.TessBaseAPI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.bytedeco.leptonica.global.leptonica.pixDestroy;
import static org.bytedeco.leptonica.global.leptonica.pixReadMem;
import static org.bytedeco.tesseract.global.tesseract.RIL_TEXTLINE;

/**
 * Reads the Sinhala and Tamil thirds of a Sri Lankan NIC, which the primary OCR cannot see.
 *
 * <h2>Why a second engine at all</h2>
 * <p>Every field on a Sri Lankan NIC is printed three times - Sinhala, Tamil, English. Amazon
 * Rekognition's {@code DetectText} supports Latin script only, so the classifier in
 * {@link RekognitionDocumentProcessingService} was deciding whether a document is a national
 * identity card from the English third alone. When that third is glared out, cropped short or
 * simply the least well printed of the three, the card became UNKNOWN despite two perfectly legible
 * statements elsewhere on it that it is a national identity card.
 *
 * <h2>Why Tesseract, locally, rather than another cloud OCR</h2>
 * <p>Google Cloud Vision reads both scripts and would have been less work. It was rejected on three
 * grounds, in order of weight:
 * <ul>
 *   <li><b>Consent.</b> Participants consented to their card being processed by Amazon Web Services
 *       in the United States, named. Adding a second processor in a second jurisdiction invalidates
 *       that consent and needs fresh ethics approval and re-consent from every subject already
 *       collected.</li>
 *   <li><b>Replayability.</b> The evaluation harness must be able to re-run a stored corpus and get
 *       the same answer. A local engine with pinned language data does that offline and for free;
 *       a second metered API does not.</li>
 *   <li><b>Cost.</b> A corpus re-run is already several hundred billed Rekognition calls.</li>
 * </ul>
 * <p>Tesseract ships on the classpath already, inside the {@code javacv-platform} dependency the
 * card detector pulls in, so this adds no dependency and no native install.
 *
 * <h2>Concurrency</h2>
 * <p>{@link TessBaseAPI} holds mutable per-recognition state and is not thread-safe, so extraction
 * is serialised on this component. That is a deliberate trade rather than an oversight: one engine
 * with three languages loaded costs tens of megabytes, a thread-local copy per Tomcat worker would
 * multiply that by the pool size, and this is a research console whose OCR runs either one sample
 * at a time inside the batch harness or once per registration. If throughput ever matters, a small
 * pool replaces the lock without changing callers.
 *
 * <h2>Failure is never fatal</h2>
 * <p>The language data lives under {@code data/}, which is not in version control, so a fresh clone
 * has none. Missing data, a failed init or a failed read all return
 * {@link MultilingualNicText#unavailable()} and leave the Rekognition result exactly as it was.
 * This is corroboration; it must never be the reason a registration cannot proceed.
 */
@Component
@Slf4j
public class MultilingualNicTextExtractor {

    /**
     * Sinhala fragments that identify the document as a <em>national identity card</em>.
     *
     * <p>Fragments rather than whole words, matching how the Latin ladder settles for {@code IDENT}
     * and {@code NATION}: OCR on a photographed card drops and mangles characters, and a whole-word
     * match on {@code ජාතික හැඳුනුම්පත} fails on text a human reads without difficulty.
     *
     * <p>These are the card's <b>title</b> wording only. Field labels are deliberately excluded -
     * see {@link #SINHALA_FIELD_FRAGMENTS}.
     */
    private static final List<String> SINHALA_IDENTITY_FRAGMENTS = List.of(
            "ජාතික",      // national
            "හැඳුනු",     // identity (stem of හැඳුනුම්පත)
            "ඳුනුම්"      // the same stem, when the leading character is lost
    );

    /** Tamil title wording, chosen on the same basis. */
    private static final List<String> TAMIL_IDENTITY_FRAGMENTS = List.of(
            "தேசிய",       // national
            "அடையாள",     // identity
            "அட்டை"        // card
    );

    /**
     * Field labels - evidence that the pass read the card, but <b>not</b> that it is an NIC.
     *
     * <p>Kept out of the classification decision on purpose. {@code අංකය} ("number") and
     * {@code උපන්} ("born") are printed on driving licences, student cards and passports too, so
     * promoting a document to VALID on the strength of them would be exactly the misclassification
     * the Latin ladder already goes to some trouble to avoid. They are reported instead as a
     * measure of how much of the card the local pass actually recovered.
     */
    private static final List<String> SINHALA_FIELD_FRAGMENTS = List.of(
            "අංකය",       // number - printed immediately before the NIC number
            "උපන්",       // born
            "නම"          // name
    );

    private static final List<String> TAMIL_FIELD_FRAGMENTS = List.of(
            "இலக்க",       // number
            "பிறந்த",      // born
            "பெயர்"        // name
    );

    /** The same NIC shapes the Latin ladder looks for, applied to what the local engine read. */
    private static final Pattern NEW_12_DIGIT = Pattern.compile("\\b[0-9]{12}\\b");
    private static final Pattern OLD_9_PLUS_LETTER = Pattern.compile("\\b[0-9]{9}[VXvx]\\b");

    @Value("${fr.ocr.multilingual.enabled:false}")
    private boolean enabled;

    @Value("${fr.ocr.multilingual.tessdata-path:./data/tessdata}")
    private String tessdataPath;

    @Value("${fr.ocr.multilingual.languages:sin+tam+eng}")
    private String languages;

    /** Null until the first successful init, and left null for good if init fails. */
    private TessBaseAPI api;
    private boolean initAttempted;

    /**
     * Reads what Rekognition could not.
     *
     * @param imageBytes the same bytes the primary OCR was given - already cropped to the card by
     *                   the caller where a crop was possible, because OCR on a raw frame reads the
     *                   room as well as the card
     */
    public synchronized MultilingualNicText extract(byte[] imageBytes) {
        if (!enabled || imageBytes == null || imageBytes.length == 0) {
            return MultilingualNicText.unavailable();
        }
        if (!ensureInitialised()) {
            return MultilingualNicText.unavailable();
        }

        long start = System.currentTimeMillis();
        PIX image = null;
        BytePointer buffer = null;
        try {
            buffer = new BytePointer(imageBytes.length);
            buffer.put(imageBytes, 0, imageBytes.length);
            image = pixReadMem(buffer, imageBytes.length);
            if (image == null) {
                log.warn("[NIC-OCR-ML] Could not decode the image for the multilingual pass");
                return MultilingualNicText.unavailable();
            }

            api.SetImage(image);
            api.Recognize(null);

            List<MultilingualNicText.ScriptLine> lines = readLines();
            return summarise(lines, System.currentTimeMillis() - start);

        } catch (RuntimeException e) {
            // Corroboration only. A failure here must not fail the registration or the sample.
            log.warn("[NIC-OCR-ML] Multilingual pass failed, continuing on Rekognition alone: {}", e.toString());
            return MultilingualNicText.unavailable();
        } finally {
            if (image != null) {
                pixDestroy(image);
            }
            if (buffer != null) {
                buffer.deallocate();
            }
        }
    }

    // -------------------------------------------------------------------------------------------

    private List<MultilingualNicText.ScriptLine> readLines() {
        List<MultilingualNicText.ScriptLine> lines = new ArrayList<>();
        ResultIterator it = api.GetIterator();
        if (it == null) {
            return lines;
        }
        try {
            do {
                BytePointer raw = it.GetUTF8Text(RIL_TEXTLINE);
                if (raw == null) {
                    continue;
                }
                String text = raw.getString().trim();
                raw.deallocate();
                if (text.isEmpty()) {
                    continue;
                }
                lines.add(new MultilingualNicText.ScriptLine(
                        text, scriptOf(text), (double) it.Confidence(RIL_TEXTLINE)));
            } while (it.Next(RIL_TEXTLINE));
        } finally {
            it.deallocate();
        }
        return lines;
    }

    private MultilingualNicText summarise(List<MultilingualNicText.ScriptLine> lines, long latencyMs) {
        boolean sinhala = false;
        boolean tamil = false;
        boolean fieldLabels = false;
        List<String> numbers = new ArrayList<>();

        for (MultilingualNicText.ScriptLine line : lines) {
            String text = line.text();
            if (!sinhala && SINHALA_IDENTITY_FRAGMENTS.stream().anyMatch(text::contains)) {
                log.info("[NIC-OCR-ML]   -> Sinhala national-identity title fragment in: \"{}\"", text);
                sinhala = true;
            }
            if (!tamil && TAMIL_IDENTITY_FRAGMENTS.stream().anyMatch(text::contains)) {
                log.info("[NIC-OCR-ML]   -> Tamil national-identity title fragment in: \"{}\"", text);
                tamil = true;
            }
            if (!fieldLabels && (SINHALA_FIELD_FRAGMENTS.stream().anyMatch(text::contains)
                    || TAMIL_FIELD_FRAGMENTS.stream().anyMatch(text::contains))) {
                fieldLabels = true;
            }
            // Digits survive OCR far better than either script, and the number is printed once for
            // all three languages, so a candidate found here is worth keeping whatever the script.
            String digits = text.replace(" ", "");
            NEW_12_DIGIT.matcher(digits).results().map(m -> m.group()).forEach(numbers::add);
            OLD_9_PLUS_LETTER.matcher(digits).results()
                    .map(m -> m.group().toUpperCase()).forEach(numbers::add);
        }

        Double meanConfidence = lines.isEmpty() ? null : lines.stream()
                .map(MultilingualNicText.ScriptLine::confidence)
                .filter(c -> c != null)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0);

        log.info("[NIC-OCR-ML] lines={} sinhalaTitle={} tamilTitle={} fieldLabels={} numberCandidates={} in {} ms",
                lines.size(), sinhala, tamil, fieldLabels, numbers, latencyMs);

        return new MultilingualNicText(true, List.copyOf(lines), sinhala, tamil, fieldLabels,
                List.copyOf(numbers), meanConfidence, latencyMs);
    }

    /**
     * Which script a line is in, by Unicode block.
     *
     * <p>A line carrying both Sinhala and Tamil is MIXED rather than either: the two scripts sit on
     * the same physical line on the card for several fields, and picking one would misreport the
     * coverage this whole component exists to provide.
     */
    static MultilingualNicText.Script scriptOf(String text) {
        boolean sinhala = text.codePoints().anyMatch(c -> c >= 0x0D80 && c <= 0x0DFF);
        boolean tamil = text.codePoints().anyMatch(c -> c >= 0x0B80 && c <= 0x0BFF);
        boolean latin = text.codePoints().anyMatch(c -> c < 128 && Character.isLetter(c));

        int scripts = (sinhala ? 1 : 0) + (tamil ? 1 : 0) + (latin ? 1 : 0);
        if (scripts > 1) {
            return MultilingualNicText.Script.MIXED;
        }
        if (sinhala) {
            return MultilingualNicText.Script.SINHALA;
        }
        if (tamil) {
            return MultilingualNicText.Script.TAMIL;
        }
        if (latin) {
            return MultilingualNicText.Script.LATIN;
        }
        return MultilingualNicText.Script.NEUTRAL;
    }

    /** One attempt, ever. A missing language pack will not be there on the next call either. */
    private boolean ensureInitialised() {
        if (api != null) {
            return true;
        }
        if (initAttempted) {
            return false;
        }
        initAttempted = true;

        Path dir = Path.of(tessdataPath).toAbsolutePath().normalize();
        List<String> missing = new ArrayList<>();
        for (String language : languages.split("\\+")) {
            if (!Files.isRegularFile(dir.resolve(language + ".traineddata"))) {
                missing.add(language);
            }
        }
        if (!missing.isEmpty()) {
            log.warn("[NIC-OCR-ML] DISABLED - no language data for {} under {}. The Sinhala and Tamil "
                            + "thirds of every card will be unread. Fetch the .traineddata files "
                            + "(see tools/fetch-tessdata.sh) or set fr.ocr.multilingual.enabled=false "
                            + "to silence this.",
                    missing, dir);
            return false;
        }

        TessBaseAPI candidate = new TessBaseAPI();
        if (candidate.Init(dir.toString(), languages) != 0) {
            log.error("[NIC-OCR-ML] DISABLED - Tesseract refused to initialise with languages=\"{}\" "
                    + "from {}", languages, dir);
            candidate.deallocate();
            return false;
        }

        this.api = candidate;
        log.info("[NIC-OCR-ML] READY. languages=\"{}\" tessdata={}", languages, dir);
        return true;
    }

    @PreDestroy
    synchronized void shutdown() {
        if (api != null) {
            api.End();
            api.deallocate();
            api = null;
        }
    }
}
