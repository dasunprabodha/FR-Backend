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
     * so it needs a stub rather than a null.
     */
    private BatchEvaluationService serviceFor(Path root) {
        BatchEvaluationService service = new BatchEvaluationService(
                null, mock(RegistrationRecordRepository.class), new ObjectMapper());
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
