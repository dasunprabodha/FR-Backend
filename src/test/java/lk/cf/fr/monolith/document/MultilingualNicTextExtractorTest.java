package lk.cf.fr.monolith.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cover for the local Sinhala/Tamil pass.
 *
 * <p>The behaviour that matters most here is the failure behaviour. The language data is not in
 * version control, so on a fresh clone this component has nothing to work with, and it must say so
 * once and then get out of the way rather than failing a registration.
 */
class MultilingualNicTextExtractorTest {

    private static final Path TESSDATA = Path.of("data/tessdata");

    private MultilingualNicTextExtractor extractor(boolean enabled, String tessdataPath) {
        MultilingualNicTextExtractor extractor = new MultilingualNicTextExtractor();
        ReflectionTestUtils.setField(extractor, "enabled", enabled);
        ReflectionTestUtils.setField(extractor, "tessdataPath", tessdataPath);
        ReflectionTestUtils.setField(extractor, "languages", "sin+tam+eng");
        return extractor;
    }

    @Test
    @DisplayName("switched off, it does nothing at all")
    void disabledReturnsUnavailable() {
        MultilingualNicText text = extractor(false, TESSDATA.toString()).extract(new byte[] {1, 2, 3});

        assertFalse(text.available());
        assertFalse(text.anyNationalIdKeyword(), "a disabled pass must not vote on classification");
        assertTrue(text.lines().isEmpty());
    }

    @Test
    @DisplayName("missing language data disables the pass rather than failing the read")
    void missingLanguageDataDegradesQuietly() {
        MultilingualNicText text =
                extractor(true, "data/tessdata-does-not-exist").extract(new byte[] {1, 2, 3});

        assertFalse(text.available());
        assertFalse(text.anyNationalIdKeyword());
    }

    @Test
    @DisplayName("an undecodable image is a miss, not an exception")
    void rubbishBytesAreSurvivable() {
        assumeTrue(Files.isDirectory(TESSDATA), "language data not fetched - see tools/fetch-tessdata.sh");

        MultilingualNicText text = extractor(true, TESSDATA.toString())
                .extract("this is not an image".getBytes());

        assertFalse(text.available());
    }

    @Test
    @DisplayName("script is decided by Unicode block, not by the language list")
    void scriptDetection() {
        assertEquals(MultilingualNicText.Script.SINHALA,
                MultilingualNicTextExtractor.scriptOf("ජාතික හැඳුනුම්පත"));
        assertEquals(MultilingualNicText.Script.TAMIL,
                MultilingualNicTextExtractor.scriptOf("தேசிய அடையாள அட்டை"));
        assertEquals(MultilingualNicText.Script.LATIN,
                MultilingualNicTextExtractor.scriptOf("NATIONAL IDENTITY CARD"));
        assertEquals(MultilingualNicText.Script.NEUTRAL,
                MultilingualNicTextExtractor.scriptOf("199401204166"),
                "the NIC number is printed once for all three languages and belongs to none");
        assertEquals(MultilingualNicText.Script.MIXED,
                MultilingualNicTextExtractor.scriptOf("පුරුෂ / ஆண் / Male"),
                "several NIC fields put all three scripts on one physical line");
    }

    /**
     * The real thing, against a real card, when both the language data and the corpus are present.
     *
     * <p>Skipped rather than failed where either is missing: the corpus is personal data and is not
     * in version control, so this cannot run on a clean checkout.
     */
    @Test
    @DisplayName("reads the Sinhala and Tamil card title off a real NIC")
    void readsARealCard() throws IOException {
        assumeTrue(Files.isDirectory(TESSDATA), "language data not fetched - see tools/fetch-tessdata.sh");
        Path card = Path.of("data/corpus-derived/A-CHANNEL-01-dasun/scannedNic.jpg");
        assumeTrue(Files.isRegularFile(card), "corpus not present on this machine");

        MultilingualNicText text = extractor(true, TESSDATA.toString()).extract(Files.readAllBytes(card));

        assertTrue(text.available());
        assertTrue(text.sinhalaNationalIdKeyword(),
                "ජාතික හැඳුනුම්පත is printed on every Sri Lankan NIC and Rekognition cannot see it");
        assertTrue(text.tamilNationalIdKeyword(),
                "தேசிய அடையாள அட்டை likewise");
        assertTrue(text.sawSinhala() && text.sawTamil());
        assertTrue(text.numberCandidates().contains("199401204166"),
                "the number is printed once for all three languages, so the local pass sees it too");
    }
}
