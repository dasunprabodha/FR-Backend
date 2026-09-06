package lk.cf.fr.monolith.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchSummary;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.ProposedDecision;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.RunListing;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.RunMeta;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.SampleResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover for reading finished runs back off disk.
 *
 * <p>What matters here is that an archived run says the same thing the live run said. A batch is
 * minutes of inference and a few hundred billed Rekognition calls, so if the archive quietly
 * miscounts an outcome there is no cheap way to notice - the numbers simply look plausible and go
 * into a dissertation.
 */
class EvaluationRunArchiveTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private EvaluationRunArchive archiveAt(Path root) {
        EvaluationRunArchive archive = new EvaluationRunArchive(mapper);
        ReflectionTestUtils.setField(archive, "outputRoot", root.toString());
        return archive;
    }

    /** A row with just enough filled in to be counted, scored and classified. */
    private SampleResult row(String id, String truth, String binding, String ocr,
                             Double cmp3, boolean passed, String proposedDecision) {
        return new SampleResult(
                id, "eval-" + id, "subject-" + id, truth, null, "pixel", "L1", "199934510785",
                ocr, "SCANNED_UPLOAD", "199934510785", 97.5,
                binding, 1.0, 0,
                true, 91.25, true, 88.0, true, cmp3, null, null, null, null,
                "CONSISTENT", 3.25,
                true, true, false,
                72.0, true,
                passed, passed, false, passed, passed ? null : "similarity below threshold",
                120, 340, null,
                proposedDecision == null ? null : new ProposedDecision(
                        proposedDecision, "coPresence", "co-presence satisfied",
                        "PASS", 0.91, "PASS", 0.88, "PASS", 0.03, "PASS", 1.0, "PASS", 0.72));
    }

    private Path writeRun(Path root, String runId, List<SampleResult> results) throws IOException {
        Path dir = root.resolve(runId);
        Files.createDirectories(dir);
        mapper.writeValue(dir.resolve("results.json").toFile(), results);
        return dir;
    }

    @Test
    @DisplayName("a saved run reloads with the outcome counts recomputed from its own rows")
    void reloadRebuildsTheSummary(@TempDir Path root) throws IOException {
        writeRun(root, "pilot-52", List.of(
                row("a", "GENUINE", "MATCH", "NIC_DETECTED", 98.0, true, "ACCEPT"),
                row("b", "ATTACK", "MISMATCH", "NIC_DETECTED", 41.0, false, "REJECT"),
                row("c", "GENUINE", "MATCH", "NOT_PROVIDED", 95.0, true, "ACCEPT")));

        BatchSummary summary = archiveAt(root).load("pilot-52");

        assertEquals("pilot-52", summary.runId());
        assertEquals(3, summary.samplesProcessed());
        assertEquals(2L, summary.similarityPassedCount());
        assertEquals(2L, summary.bindingOutcomeCounts().get("MATCH"));
        assertEquals(1L, summary.bindingOutcomeCounts().get("MISMATCH"));
        assertEquals(2L, summary.ocrOutcomeCounts().get("NIC_DETECTED"));
        assertEquals(2L, summary.proposedDecisionCounts().get("ACCEPT"));
        assertEquals(3, summary.results().size(), "the rows themselves come back for the table");
    }

    @Test
    @DisplayName("run-meta.json restores the figures the rows cannot carry")
    void metaRestoresWhatRowsCannotHold(@TempDir Path root) throws IOException {
        Path dir = writeRun(root, "pilot-53", List.of(row("a", "GENUINE", "MATCH", "NIC_DETECTED", 98.0, true, "ACCEPT")));
        mapper.writeValue(dir.resolve("run-meta.json").toFile(), new RunMeta(
                "pilot-53", "/data/corpus-pilot", 9, 1, 0, 8, 123_456L, null, "2026-09-05T21:17:00"));

        BatchSummary summary = archiveAt(root).load("pilot-53");

        assertEquals("/data/corpus-pilot", summary.corpusDir());
        assertEquals(9, summary.samplesFound(), "eight directories were skipped, and that is recorded");
        assertEquals(8, summary.samplesSkipped());
        assertEquals(123_456L, summary.totalDurationMs());
    }

    @Test
    @DisplayName("a run written before run-meta.json existed still loads, reporting unknowns as zero")
    void legacyRunWithoutMetaStillLoads(@TempDir Path root) throws IOException {
        writeRun(root, "run-20260905-124400", List.of(
                row("a", "GENUINE", "MATCH", "NIC_DETECTED", 98.0, true, "ACCEPT"),
                row("b", "ATTACK", "MISMATCH", "NIC_DETECTED", 41.0, false, "REJECT")));

        BatchSummary summary = archiveAt(root).load("run-20260905-124400");

        assertEquals(2, summary.samplesProcessed());
        assertEquals(2, summary.samplesFound(), "the row count is the honest floor, not a guess");
        assertEquals(0, summary.samplesSkipped());
        assertEquals(0L, summary.totalDurationMs());
        assertNull(summary.corpusDir(), "which corpus was read is genuinely unrecoverable");
    }

    @Test
    @DisplayName("a run reduced to its CSV is still readable")
    void csvOnlyRunLoads(@TempDir Path root) throws IOException {
        Path dir = root.resolve("csv-only");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("results.csv"),
                "sampleId,groundTruth,bindingOutcome,cmp3Similarity,similarityPassed,proposedDecision\n"
                        + "\"buddhi-G01\",\"GENUINE\",\"MATCH\",98.5,true,\"ACCEPT\"\n"
                        + "\"buddhi-A01\",\"ATTACK\",\"MISMATCH\",41.25,false,\"REJECT\"\n");

        BatchSummary summary = archiveAt(root).load("csv-only");

        assertEquals(2, summary.samplesProcessed());
        assertEquals("buddhi-G01", summary.results().get(0).sampleId());
        assertEquals(98.5, summary.results().get(0).cmp3Similarity());
        assertEquals("ATTACK", summary.results().get(1).groundTruth());
        assertEquals(1L, summary.similarityPassedCount());
        assertEquals(1L, summary.proposedDecisionCounts().get("ACCEPT"));
    }

    @Test
    @DisplayName("the listing is newest first and ignores directories that hold no results")
    void listingSkipsNonRuns(@TempDir Path root) throws IOException {
        Path older = writeRun(root, "older", List.of(row("a", "GENUINE", "MATCH", "NIC_DETECTED", 98.0, true, "ACCEPT")));
        mapper.writeValue(older.resolve("run-meta.json").toFile(), new RunMeta(
                "older", "/c", 1, 1, 0, 0, 10L, null, "2026-09-01T10:00:00"));

        Path newer = writeRun(root, "newer", List.of(
                row("a", "GENUINE", "MATCH", "NIC_DETECTED", 98.0, true, "ACCEPT"),
                row("b", "ATTACK", "MATCH", "NIC_DETECTED", 44.0, false, "REJECT")));
        mapper.writeValue(newer.resolve("run-meta.json").toFile(), new RunMeta(
                "newer", "/c", 2, 2, 0, 0, 20L, null, "2026-09-06T10:00:00"));

        Files.createDirectories(root.resolve("card-crops"));

        List<RunListing> listing = archiveAt(root).list();

        assertEquals(2, listing.size(), "the crop folder is not a run");
        assertEquals("newer", listing.get(0).runId(), "newest first");
        assertEquals(2, listing.get(0).sampleCount());
        assertTrue(listing.get(0).hasMeta());
        assertTrue(listing.get(0).sizeBytes() > 0);
    }

    @Test
    @DisplayName("sample counts in the listing come from the CSV when no meta was written")
    void listingCountsCsvRowsWithoutMeta(@TempDir Path root) throws IOException {
        Path dir = root.resolve("legacy");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("results.csv"),
                "sampleId,groundTruth\n\"a\",\"GENUINE\"\n\"b\",\"ATTACK\"\n\"c\",\"GENUINE\"\n");

        RunListing only = archiveAt(root).list().get(0);

        assertEquals(3, only.sampleCount(), "three data rows, header excluded");
        assertTrue(only.hasCsv());
        assertTrue(!only.hasJson() && !only.hasMeta());
        assertNotNull(only.savedAt(), "falls back to the file's own timestamp");
    }

    @Test
    @DisplayName("a run id cannot climb out of the output root")
    void traversalIsRefused(@TempDir Path root) {
        EvaluationRunArchive archive = archiveAt(root);
        assertThrows(ResponseStatusException.class, () -> archive.load("../../etc"));
        assertThrows(ResponseStatusException.class, () -> archive.load("nested/path"));
        assertThrows(ResponseStatusException.class, () -> archive.load(""));
    }

    @Test
    @DisplayName("a results file opened from anywhere folds the same way an archived one does")
    void looseFilesAreParsedByFormatNotByName(@TempDir Path root) throws IOException {
        EvaluationRunArchive archive = archiveAt(root);
        List<SampleResult> rows = List.of(
                row("a", "GENUINE", "MATCH", "NIC_DETECTED", 98.0, true, "ACCEPT"),
                row("b", "ATTACK", "MISMATCH", "NIC_DETECTED", 41.0, false, "REJECT"));

        // Named .csv but holding JSON - exactly what a file renamed on its way through email looks
        // like. The content decides, not the extension.
        BatchSummary fromJson = archive.parse("someones-export.csv", mapper.writeValueAsString(rows));
        assertEquals(2, fromJson.samplesProcessed());
        assertEquals("someones-export.csv", fromJson.runId(), "the file names the loaded run");
        assertNull(fromJson.outputDir(), "nothing was read from, or written to, the archive");
        assertEquals(1L, fromJson.similarityPassedCount());

        BatchSummary fromCsv = archive.parse("results.csv",
                "sampleId,groundTruth,similarityPassed\n\"a\",\"GENUINE\",true\n\"b\",\"ATTACK\",false\n");
        assertEquals(2, fromCsv.samplesProcessed());
        assertEquals(1L, fromCsv.similarityPassedCount());
    }

    @Test
    @DisplayName("a file that is not results at all is refused with a reason, not a 500")
    void unparseableFilesAreRefused(@TempDir Path root) {
        EvaluationRunArchive archive = archiveAt(root);

        ResponseStatusException empty =
                assertThrows(ResponseStatusException.class, () -> archive.parse("x.csv", "   "));
        assertEquals(400, empty.getStatusCode().value());

        ResponseStatusException badJson =
                assertThrows(ResponseStatusException.class, () -> archive.parse("x.json", "{\"nope\":1}"));
        assertEquals(422, badJson.getStatusCode().value());

        ResponseStatusException badCsv = assertThrows(ResponseStatusException.class,
                () -> archive.parse("x.csv", "date,amount\n\"2026-01-01\",42\n"));
        assertEquals(422, badCsv.getStatusCode().value());
    }

    @Test
    @DisplayName("an output root that does not exist yet is an empty archive, not an error")
    void missingRootListsEmpty(@TempDir Path root) {
        assertEquals(List.of(), archiveAt(root.resolve("never-written")).list());
    }
}
