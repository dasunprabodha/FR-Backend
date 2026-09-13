package lk.cf.fr.monolith.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchRequest;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression cover for corpus scanning.
 *
 * <p>The harness used to raise an error, with a full stack trace, for every directory that was not
 * a sample. That mattered because card-crop diagnostics were being written into the same directory
 * the default corpus points at, so each run left behind crop-only folders that the next run then
 * tried to read as samples - a self-amplifying failure. Crops now live in their own directory, and
 * a directory carrying none of the required captures is skipped rather than failed.
 */
class BatchCorpusScanTest {

    /**
     * Only the scan/classify path is exercised, so the analysis service is never reached. The
     * repository is still consulted though - a dry run resolves the claimed NIC by reference ID -
     * so it needs a stub rather than a null. The progress tracker is real, not a mock: it holds no
     * collaborators of its own and the run loop calls into it for every sample, so a null there
     * would fail every test for reasons that have nothing to do with what is being tested.
     */
    private BatchEvaluationService serviceFor(Path root) {
        EvaluationRunArchive archive = new EvaluationRunArchive(new ObjectMapper());
        ReflectionTestUtils.setField(archive, "outputRoot", root.resolve("out").toString());

        BatchEvaluationService service = new BatchEvaluationService(
                null, null, mock(RegistrationRecordRepository.class), new ObjectMapper(),
                new EvaluationProgressTracker(), archive);
        ReflectionTestUtils.setField(service, "corpusRoot", root.toString());
        ReflectionTestUtils.setField(service, "outputRoot", root.resolve("out").toString());
        return service;
    }

    private void writeSample(Path dir, String... files) throws IOException {
        Files.createDirectories(dir);
        for (String f : files) {
            Files.write(dir.resolve(f), new byte[] {1, 2, 3});
        }
    }

    @Test
    @DisplayName("a real run leaves behind the metadata a reload needs")
    void realRunWritesRunMeta(@TempDir Path root) throws IOException {
        // Every capture present, so the sample is attempted rather than skipped. The analysis
        // service is null here, so it fails immediately - which is fine: what is under test is
        // that a finished run records the figures its rows cannot carry, not that it succeeded.
        writeSample(root.resolve("corpus").resolve("subject-G01"),
                "nicImage.jpg", "faceImage.jpg", "selfImage.jpg");

        BatchRequest request = new BatchRequest();
        request.setCorpusDir("corpus");
        request.setDryRun(false);
        request.setPersistEvidence(false);

        BatchSummary summary = serviceFor(root).run(request);

        Path meta = root.resolve("out").resolve(summary.runId()).resolve("run-meta.json");
        assertTrue(Files.exists(meta), "run-meta.json must sit beside results.csv/json");

        var written = new ObjectMapper().readTree(meta.toFile());
        assertEquals(summary.runId(), written.get("runId").asText());
        assertEquals(summary.corpusDir(), written.get("corpusDir").asText());
        assertEquals(1, written.get("samplesFound").asInt());
        assertTrue(written.get("totalDurationMs").asLong() >= 0);
        assertFalse(written.get("completedAt").asText().isBlank(), "the archive lists runs by this");
    }

    @Test
    @DisplayName("Crop-only leftovers are skipped, not reported as failures")
    void cropOnlyFoldersAreSkipped(@TempDir Path root) throws IOException {
        Path corpus = root.resolve("corpus");
        // Exactly the shape the old bug produced.
        writeSample(corpus.resolve("eval-run-123-sample-a"), "DeviceNIC-cardCrop.jpg", "SelfNIC-cardCrop.jpg");
        writeSample(corpus.resolve("eval-run-123-sample-b"), "DeviceNIC-cardCrop.jpg");

        BatchRequest request = new BatchRequest();
        request.setCorpusDir("corpus");
        request.setDryRun(true);

        BatchSummary summary = serviceFor(root).run(request);

        assertEquals(2, summary.samplesFound(), "both directories are seen");
        assertEquals(2, summary.samplesSkipped(), "neither is a sample");
        assertEquals(0, summary.samplesFailed(), "skipping must not be counted as failure");
        assertTrue(summary.results().isEmpty(), "junk folders must not pollute the results table");
    }

    @Test
    @DisplayName("Real samples are still processed alongside skipped folders")
    void realSamplesSurviveTheFilter(@TempDir Path root) throws IOException {
        Path corpus = root.resolve("corpus");
        writeSample(corpus.resolve("genuine-001"), "nicImage.jpg", "faceImage.jpg", "selfImage.jpg");
        writeSample(corpus.resolve("eval-run-123-leftover"), "DeviceNIC-cardCrop.jpg");

        BatchRequest request = new BatchRequest();
        request.setCorpusDir("corpus");
        request.setDryRun(true);

        BatchSummary summary = serviceFor(root).run(request);

        assertEquals(1, summary.samplesSkipped());
        assertEquals(1, summary.samplesProcessed());
        assertEquals("genuine-001", summary.results().get(0).sampleId());
        assertNull(summary.results().get(0).error(), "a complete sample reports no problem");
    }

    @Test
    @DisplayName("A partial sample is a real error, not silently skipped")
    void partialSampleIsReported(@TempDir Path root) throws IOException {
        Path corpus = root.resolve("corpus");
        // Has a face but no NIC capture - someone's collection went wrong and they need to know.
        writeSample(corpus.resolve("broken-001"), "faceImage.jpg", "selfImage.jpg");

        BatchRequest request = new BatchRequest();
        request.setCorpusDir("corpus");
        request.setDryRun(true);

        BatchSummary summary = serviceFor(root).run(request);

        assertEquals(0, summary.samplesSkipped(), "a half-built sample must not be quietly ignored");
        assertEquals(1, summary.samplesProcessed());
        assertNotNull(summary.results().get(0).error());
        assertTrue(summary.results().get(0).error().contains("nicImage.jpg"));
    }

    @Test
    @DisplayName("Paths outside the configured corpus root are refused")
    void pathTraversalIsRefused(@TempDir Path root) {
        BatchRequest request = new BatchRequest();
        request.setCorpusDir("../../../etc");
        request.setDryRun(true);

        assertThrows(Exception.class, () -> serviceFor(root).run(request));
    }
}
