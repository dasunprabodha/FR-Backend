package lk.cf.fr.monolith.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds and serves the images behind one attempt, so the Evidence Dashboard can show what was
 * actually compared next to the score it produced.
 *
 * <p>Three sets, all already on disk - nothing is recomputed and no API is called:
 * <ol>
 *   <li><b>Captures</b> - the original images the attempt was run on. For a live registration
 *       these are the pending-image files recorded on the row. For an evaluation replay they are
 *       the corpus sample the harness read, located from the row's own refs when present, and
 *       otherwise from the run's {@code run-meta.json}.</li>
 *   <li><b>Card crops</b> - what the card detector cut out of each capture
 *       ({@code fr.carddetector.dump-dir}).</li>
 *   <li><b>Comparison pairs</b> - the exact source/target face crops each comparison sent to
 *       Rekognition ({@code registration.comparison-dump.base-dir}), keyed to the evidence row
 *       they explain.</li>
 * </ol>
 *
 * <p>Every image is addressed by a fixed key from {@link #CATALOGUE}, never by a caller-supplied
 * path, and every resolved file must sit under one of the configured roots. The endpoint has no
 * authentication yet, so a key-to-path map is what stops it being an arbitrary file read.
 */
@Service
@Slf4j
public class EvidenceImageService {

    private static final String STATUS_EVALUATION = "EVALUATION";

    /** One servable image: its group, its display label, and where it lives. */
    private record Spec(String group, String label, String evidenceId, String role, Locator locator) {
    }

    @FunctionalInterface
    private interface Locator {
        Optional<Path> find(EvidenceImageService self, RegistrationRecord record);
    }

    /** Ordered so the dashboard can render groups straight off the list. */
    private static final Map<String, Spec> CATALOGUE = new LinkedHashMap<>();

    static {
        capture("capture.nic", "Card presented to the camera", "nicImage.jpg", RegistrationRecord::getNicImageRef);
        capture("capture.face", "Live face", "faceImage.jpg", RegistrationRecord::getFaceImageRef);
        capture("capture.self", "Selfie holding the card", "selfImage.jpg", RegistrationRecord::getSelfImageRef);
        capture("capture.scan", "Uploaded scan of the card", "scannedNic.jpg", null);

        crop("crop.deviceNic", "Card crop — presented card", "DeviceNIC-cardCrop.jpg");
        crop("crop.selfNic", "Card crop — card in the selfie", "SelfNIC-cardCrop.jpg");
        crop("crop.scannedNic", "Card crop — uploaded scan", "ScannedNIC-cardCrop.jpg");
        crop("crop.ocr", "Card crop used for OCR", "DeviceNIC-OCR-cardCrop.jpg");

        pair("face.cmp1", "cmp1-deviceNicVsFace", "Photo on presented card", "Live face");
        pair("face.cmp2", "cmp2-deviceNicVsSelfNic", "Photo on presented card", "Photo on card in selfie");
        pair("face.cmp3", "cmp3-faceVsSelfFace", "Live face", "Face in selfie");
        pair("face.cmp4", "cmp4-scannedNicVsFace", "Photo on uploaded scan", "Live face");
        pair("face.cmp5", "cmp5-scannedNicVsDeviceNic", "Photo on uploaded scan", "Photo on presented card");
    }

    private static void capture(String key, String label, String corpusFile,
                                java.util.function.Function<RegistrationRecord, String> ref) {
        CATALOGUE.put(key, new Spec("CAPTURE", label, null, null,
                (self, record) -> self.findCapture(record, corpusFile, ref)));
    }

    private static void crop(String key, String label, String file) {
        CATALOGUE.put(key, new Spec("CROP", label, null, null,
                (self, record) -> self.under(self.cropRoot, record.getReferenceId(), file)));
    }

    private static void pair(String evidenceId, String dumpLabel, String sourceLabel, String targetLabel) {
        String cmp = evidenceId.substring("face.".length());
        CATALOGUE.put(cmp + ".source", new Spec("PAIR", sourceLabel, evidenceId, "SOURCE",
                (self, record) -> self.under(self.comparisonRoot, record.getReferenceId(), dumpLabel + "-source.jpg")));
        CATALOGUE.put(cmp + ".target", new Spec("PAIR", targetLabel, evidenceId, "TARGET",
                (self, record) -> self.under(self.comparisonRoot, record.getReferenceId(), dumpLabel + "-target.jpg")));
    }

    private final RegistrationRecordRepository registrationRecordRepository;
    private final ObjectMapper objectMapper;
    private final Path pendingRoot;
    private final Path cropRoot;
    private final Path comparisonRoot;
    private final Path corpusRoot;
    private final Path runsRoot;

    public EvidenceImageService(
            RegistrationRecordRepository registrationRecordRepository,
            ObjectMapper objectMapper,
            @Value("${registration.pending-images.base-dir:./data/pending-registrations}") String pendingDir,
            @Value("${fr.carddetector.dump-dir:./data/card-crops}") String cropDir,
            @Value("${registration.comparison-dump.base-dir:./data/comparison-analysis}") String comparisonDir,
            @Value("${evaluation.corpus-root:./data}") String corpusDir,
            @Value("${evaluation.output-root:./data/evaluation-runs}") String runsDir) {
        this.registrationRecordRepository = registrationRecordRepository;
        this.objectMapper = objectMapper;
        this.pendingRoot = normalise(pendingDir);
        this.cropRoot = normalise(cropDir);
        this.comparisonRoot = normalise(comparisonDir);
        this.corpusRoot = normalise(corpusDir);
        this.runsRoot = normalise(runsDir);
    }

    /** Every image that exists for this attempt, in catalogue order. */
    public List<EvidenceImage> list(String referenceId) {
        RegistrationRecord record = recordOrThrow(referenceId);
        List<EvidenceImage> images = new ArrayList<>();
        CATALOGUE.forEach((key, spec) -> spec.locator().find(this, record).ifPresent(path ->
                images.add(new EvidenceImage(key, spec.group(), spec.label(), spec.evidenceId(), spec.role()))));
        return images;
    }

    public byte[] read(String referenceId, String key) {
        Spec spec = CATALOGUE.get(key);
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown image key: " + key);
        }
        RegistrationRecord record = recordOrThrow(referenceId);
        Path path = spec.locator().find(this, record)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No " + key + " image for referenceId=" + referenceId));
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read " + key + " for referenceId=" + referenceId, e);
        }
    }

    // ---------------------------------------------------------------------------------------

    private RegistrationRecord recordOrThrow(String referenceId) {
        return registrationRecordRepository.findByReferenceId(referenceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No registration record found for referenceId=" + referenceId));
    }

    /**
     * A capture: the row's own ref when it has one, otherwise - for a replay - the file of that
     * name in the sample directory the harness read.
     */
    private Optional<Path> findCapture(RegistrationRecord record, String corpusFile,
                                       java.util.function.Function<RegistrationRecord, String> ref) {
        if (ref != null) {
            String stored = ref.apply(record);
            if (stored != null && !stored.isBlank()) {
                Optional<Path> direct = safe(Paths.get(stored));
                if (direct.isPresent()) {
                    return direct;
                }
            }
        }
        if (!STATUS_EVALUATION.equals(record.getStatus())) {
            return Optional.empty();
        }
        return replaySampleDir(record).flatMap(dir -> safe(dir.resolve(corpusFile)));
    }

    /**
     * The corpus sample a replay row was produced from.
     *
     * <p>Preferred source is the row's own nicImageRef, which the harness now writes. Older rows
     * have none, so the sample is recovered from the reference itself - {@code eval-<runId>-<sampleId>}
     * - and that run's {@code run-meta.json}. Run ids contain hyphens, so the split is made by
     * matching against the run directories that actually exist, longest first.
     */
    private Optional<Path> replaySampleDir(RegistrationRecord record) {
        String nicRef = record.getNicImageRef();
        if (nicRef != null && !nicRef.isBlank()) {
            Path parent = Paths.get(nicRef).toAbsolutePath().normalize().getParent();
            if (parent != null && Files.isDirectory(parent) && parent.startsWith(corpusRoot)) {
                return Optional.of(parent);
            }
        }

        String ref = record.getReferenceId();
        if (ref == null || !ref.startsWith("eval-") || !Files.isDirectory(runsRoot)) {
            return Optional.empty();
        }

        List<String> runIds;
        try (Stream<Path> runs = Files.list(runsRoot)) {
            runIds = runs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> ref.startsWith("eval-" + id + "-"))
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("[EvidenceImages] cannot list runs under {}: {}", runsRoot, e.getMessage());
            return Optional.empty();
        }

        for (String runId : runIds) {
            String sampleId = ref.substring(("eval-" + runId + "-").length());
            Optional<Path> dir = corpusDirOf(runId).map(c -> c.resolve(sampleId).normalize())
                    .filter(Files::isDirectory)
                    .filter(d -> d.startsWith(corpusRoot));
            if (dir.isPresent()) {
                return dir;
            }
        }
        return Optional.empty();
    }

    /**
     * The corpus a run read, from its metadata. The recorded path is absolute and may come from a
     * different mount point than the one the backend runs on now, so when it does not exist the
     * same folder name is looked up under the configured corpus root instead.
     */
    private Optional<Path> corpusDirOf(String runId) {
        Path meta = runsRoot.resolve(runId).resolve("run-meta.json");
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(meta.toFile()).get("corpusDir");
            if (node == null || node.isNull() || node.asText().isBlank()) {
                return Optional.empty();
            }
            Path recorded = Paths.get(node.asText()).toAbsolutePath().normalize();
            if (Files.isDirectory(recorded) && recorded.startsWith(corpusRoot)) {
                return Optional.of(recorded);
            }
            Path byName = corpusRoot.resolve(recorded.getFileName().toString()).normalize();
            return Files.isDirectory(byName) && byName.startsWith(corpusRoot) ? Optional.of(byName) : Optional.empty();
        } catch (IOException e) {
            log.warn("[EvidenceImages] unreadable {}: {}", meta, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Path> under(Path root, String referenceId, String file) {
        if (referenceId == null) {
            return Optional.empty();
        }
        Path path = root.resolve(referenceId).resolve(file).normalize();
        return path.startsWith(root) && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** An existing regular file under one of the roots images may be served from. */
    private Optional<Path> safe(Path candidate) {
        Path path = candidate.toAbsolutePath().normalize();
        boolean allowed = path.startsWith(pendingRoot) || path.startsWith(corpusRoot)
                || path.startsWith(cropRoot) || path.startsWith(comparisonRoot);
        return allowed && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    private static Path normalise(String dir) {
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
