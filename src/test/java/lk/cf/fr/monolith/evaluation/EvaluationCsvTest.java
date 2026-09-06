package lk.cf.fr.monolith.evaluation;

import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.SampleResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover for reading {@code results.csv} back.
 *
 * <p>Verified once against the real 52-sample {@code pilot-52-ocrfix} run: every row parsed from
 * the CSV was equal to the same row parsed from {@code results.json}, which is what these cases
 * hold in place. The null-versus-empty case below is the one that failed that comparison first.
 */
class EvaluationCsvTest {

    @Test
    @DisplayName("an unquoted empty cell is a missing value; a quoted empty cell is an empty string")
    void nullAndEmptyStringAreDifferentValues() {
        // This is exactly how the writer renders them: q(null) -> nothing, q("") -> "".
        List<SampleResult> rows = EvaluationCsv.parse(
                "sampleId,attackType,proposedDecision,proposedDrivers\n"
                        + "\"a\",,\"APPROVE\",\"\"\n");

        SampleResult r = rows.get(0);
        assertNull(r.attackType(), "a genuine sample has no attack type at all");
        assertNotNull(r.proposed());
        assertEquals("", r.proposed().drivers(),
                "an APPROVE with nothing driving it has an empty driver list, not a missing one");
    }

    @Test
    @DisplayName("quoted fields may carry commas, quotes and newlines")
    void quotingIsHonoured() {
        List<SampleResult> rows = EvaluationCsv.parse(
                "sampleId,failureReason,error\n"
                        + "\"s1\",\"similarity 41.2, below threshold 80\",\"said \"\"no match\"\"\"\n"
                        + "\"s2\",\"line one\nline two\",\n");

        assertEquals(2, rows.size(), "the newline inside quotes does not end the row");
        assertEquals("similarity 41.2, below threshold 80", rows.get(0).failureReason());
        assertEquals("said \"no match\"", rows.get(0).error());
        assertEquals("line one\nline two", rows.get(1).failureReason());
        assertNull(rows.get(1).error());
    }

    @Test
    @DisplayName("columns are matched by name, so an older CSV missing columns still loads")
    void unknownAndMissingColumnsAreTolerated() {
        List<SampleResult> rows = EvaluationCsv.parse(
                // No proposed-rule columns at all, and one column this build knows nothing about.
                "sampleId,someFutureColumn,cmp3Similarity,groundTruth\n"
                        + "\"s1\",\"ignored\",93.5,\"GENUINE\"\n");

        SampleResult r = rows.get(0);
        assertEquals("s1", r.sampleId());
        assertEquals(93.5, r.cmp3Similarity());
        assertEquals("GENUINE", r.groundTruth());
        assertNull(r.proposed(), "no verdict recorded is not a verdict full of nulls");
        assertNull(r.cmp1Similarity());
    }

    @Test
    @DisplayName("primitive columns fall back to their zero rather than failing the row")
    void primitivesDegradeSafely() {
        SampleResult r = EvaluationCsv.parse(
                "sampleId,similarityPassed,nicCardCropped,documentLatencyMs\n\"s1\",,,\n").get(0);

        assertTrue(!r.similarityPassed());
        assertTrue(!r.nicCardCropped());
        assertEquals(0L, r.documentLatencyMs());
    }

    @Test
    @DisplayName("a trailing newline does not become an extra empty row")
    void trailingNewlineIsNotARow() {
        assertEquals(2, EvaluationCsv.parse("sampleId\n\"a\"\n\"b\"\n").size());
        assertEquals(2, EvaluationCsv.parse("sampleId\n\"a\"\n\"b\"").size(), "nor does its absence lose one");
        assertEquals(2, EvaluationCsv.countRows("sampleId\n\"a\"\n\"b\"\n"));
    }

    @Test
    @DisplayName("a file that is not an evaluation export is refused rather than half-read")
    void nonResultsFileIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> EvaluationCsv.parse("date,amount\n\"2026-01-01\",42\n"));
        assertThrows(ResponseStatusException.class, () -> EvaluationCsv.parse(""));
    }
}
