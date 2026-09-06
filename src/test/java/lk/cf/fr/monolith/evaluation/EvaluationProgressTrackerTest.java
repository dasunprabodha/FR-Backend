package lk.cf.fr.monolith.evaluation;

import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.ProgressSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the run identity the Evaluation page's progress bar depends on.
 *
 * <p>The page reads the tracker once before launching a run so it can ignore the previous run's
 * numbers until its own begin. That filter used to key on {@code runId} - which broke the moment a
 * run was repeated under the same name, the normal way of working a corpus: re-run {@code test},
 * fix, re-run {@code test}. Both runs answered to the same id, so every poll of the second one was
 * discarded as a stale reading of the first, and the bar showed no percentage, no counts and no
 * time remaining for the whole run. {@code runSeq} is what distinguishes them now.
 */
class EvaluationProgressTrackerTest {

    @Test
    @DisplayName("re-running under the same name still yields a distinguishable, later run")
    void reusedRunNameGetsANewSequence() {
        EvaluationProgressTracker tracker = new EvaluationProgressTracker();

        EvaluationProgressTracker.RunProgress first = tracker.begin("test", "/corpus", 2, true);
        first.sampleFinished(false, 1000);
        first.sampleFinished(false, 1000);
        first.finish(null);
        long finishedSeq = tracker.snapshot().runSeq();

        // Same name, same corpus - the case that used to make the bar stick at "Starting…".
        tracker.begin("test", "/corpus", 2, true);
        ProgressSnapshot fresh = tracker.snapshot();

        assertEquals("test", fresh.runId(), "the name is genuinely reused");
        assertTrue(fresh.runSeq() > finishedSeq, "but the run is still identifiable as the later one");
        assertEquals("RUNNING", fresh.state());
        assertEquals(0, fresh.samplesDone(), "and it starts from zero, not the previous run's totals");
    }

    @Test
    @DisplayName("ETA appears once a sample has been timed, and is null before that")
    void etaIsUnknownUntilSomethingHasBeenTimed() {
        EvaluationProgressTracker tracker = new EvaluationProgressTracker();
        EvaluationProgressTracker.RunProgress run = tracker.begin("eta", "/corpus", 4, false);

        assertNull(tracker.snapshot().etaMs(), "unknown, not zero, before any sample completes");
        assertNull(tracker.snapshot().etaText());

        run.sampleFinished(false, 2_000);

        ProgressSnapshot after = tracker.snapshot();
        assertEquals(25.0, after.percentComplete());
        assertNotNull(after.etaMs());
        assertEquals(6_000L, after.etaMs(), "mean 2s across the 3 samples still to go");
        assertEquals("00:06", after.etaText());
    }

    @Test
    @DisplayName("skipped directories advance the bar but never distort the estimate")
    void skippedSamplesAreCountedNotTimed() {
        EvaluationProgressTracker tracker = new EvaluationProgressTracker();
        EvaluationProgressTracker.RunProgress run = tracker.begin("skip", "/corpus", 4, false);

        run.sampleSkipped();
        run.sampleFinished(false, 4_000);

        ProgressSnapshot snapshot = tracker.snapshot();
        assertEquals(2, snapshot.samplesDone());
        assertEquals(1, snapshot.samplesSkipped());
        assertEquals(50.0, snapshot.percentComplete());
        assertEquals(4_000L, snapshot.averageSampleMs(), "the skip is not averaged in at 0 ms");
        assertEquals(8_000L, snapshot.etaMs());
    }

    @Test
    @DisplayName("no run has started yet: an empty state, not an error")
    void snapshotIsNullBeforeAnyRun() {
        assertNull(new EvaluationProgressTracker().snapshot());
    }
}
