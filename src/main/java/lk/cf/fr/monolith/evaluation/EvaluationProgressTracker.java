package lk.cf.fr.monolith.evaluation;

import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.ProgressSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live progress for a batch evaluation run - how far along it is, and how long is left.
 *
 * <p>Exists because {@code POST /api/v2/evaluate/batch} is synchronous and slow: a full corpus is
 * several Rekognition round-trips plus up to five local card-detector inference passes per sample,
 * so a 53-sample run takes minutes and the HTTP client sees nothing at all until the very last
 * sample lands. There was no way to tell a run that is working from one that has wedged, and no
 * way to decide whether to wait or cancel. This makes the run observable while it is still going.
 *
 * <p>{@code GET /api/v2/evaluate/progress} publishes these counters as JSON. The Angular
 * Evaluation page polls it once a second for as long as its batch request is open and draws the
 * bar, the sample counts and the time remaining from what comes back.
 *
 * <p><b>ETA is a projection, not a promise.</b> It is the mean duration of the samples completed so
 * far multiplied by the number remaining. The mean (rather than the last sample's time) is used
 * deliberately: per-sample cost swings a lot - a sample with no {@code scannedNic.jpg} skips OCR
 * and identity binding entirely and finishes in a fraction of the time of one that runs the full
 * four-way comparison - so an estimate driven by the most recent sample would jump around too much
 * to be worth reading. The mean settles within a handful of samples and then moves slowly.
 *
 * <h2>Concurrency</h2>
 * <p>Each run gets its own {@link RunProgress} object and the tracker only holds a reference to the
 * most recent one. Two overlapping runs therefore cannot corrupt each other's counters - the
 * progress endpoint simply reports whichever started last. Mutation happens on the single thread
 * servicing the batch request; reads come from other threads (the progress endpoint), so the
 * mutators and the snapshot are synchronized on the run object itself.
 */
@Component
@Slf4j
public class EvaluationProgressTracker {

    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_COMPLETE = "COMPLETE";
    private static final String STATE_ABORTED = "ABORTED";

    /**
     * The most recent run, retained after it finishes so the last completed run's totals can still
     * be fetched. Null only before the first run of this JVM's lifetime.
     */
    private final AtomicReference<RunProgress> latest = new AtomicReference<>();

    /**
     * Stamps each run with a number that only ever goes up, so a client can tell "the run I just
     * launched" from "the finished run still sitting in the tracker" without comparing names.
     *
     * <p>Names are not usable for that. A run is normally re-run under the same name after a fix
     * - {@code test} gets used a dozen times in an afternoon - and a watcher that identified runs
     * by {@code runId} would discard every poll of the new run as a stale reading of the old one,
     * leaving its progress bar stuck at "starting" for the entire run.
     */
    private final AtomicLong runSequence = new AtomicLong();

    /** Opens a new run and makes it the one {@link #snapshot()} reports on. */
    public RunProgress begin(String runId, String corpusDir, int total, boolean dryRun) {
        RunProgress progress =
                new RunProgress(runId, runSequence.incrementAndGet(), corpusDir, total, dryRun);
        latest.set(progress);
        return progress;
    }

    /** Current state of the most recent run, or null if none has started in this JVM. */
    public ProgressSnapshot snapshot() {
        RunProgress progress = latest.get();
        return progress == null ? null : progress.snapshot();
    }

    // -------------------------------------------------------------------------------------------

    /**
     * One run's counters. Handed back to the caller by {@link #begin} so the service updates its
     * own run directly rather than addressing it by id.
     */
    public static final class RunProgress {

        private final String runId;
        private final long runSeq;
        private final String corpusDir;
        private final int total;
        private final boolean dryRun;
        private final long startedAtMs = System.currentTimeMillis();

        /** Loop iterations consumed - processed plus skipped. This is what the bar fills against. */
        private int done;
        private int processed;
        private int failed;
        private int skipped;

        /**
         * Only samples actually put through the pipeline are timed. Skipped directories return in
         * microseconds and would drag the mean toward zero, inflating the ETA's optimism.
         */
        private long timedDurationSumMs;
        private int timedCount;

        private String currentSample;
        private String state = STATE_RUNNING;
        private String abortedReason;
        private long finishedAtMs;

        private RunProgress(String runId, long runSeq, String corpusDir, int total, boolean dryRun) {
            this.runId = runId;
            this.runSeq = runSeq;
            this.corpusDir = corpusDir;
            this.total = total;
            this.dryRun = dryRun;
        }

        /** Records the sample about to be worked on, so a stalled run names the sample it stalled on. */
        public synchronized void startSample(String sampleId) {
            this.currentSample = sampleId;
        }

        /** A sample finished the pipeline - {@code failed} covers both thrown errors and error results. */
        public synchronized void sampleFinished(boolean failed, long durationMs) {
            done++;
            processed++;
            if (failed) {
                this.failed++;
            }
            timedDurationSumMs += durationMs;
            timedCount++;
        }

        /** A directory that carried none of the required captures - counted, never timed. */
        public synchronized void sampleSkipped() {
            done++;
            skipped++;
        }

        /** Closes the run. A non-null reason marks it aborted rather than complete. */
        public synchronized void finish(String abortedReason) {
            this.state = abortedReason == null ? STATE_COMPLETE : STATE_ABORTED;
            this.abortedReason = abortedReason;
            this.finishedAtMs = System.currentTimeMillis();
            this.currentSample = null;
        }

        public synchronized ProgressSnapshot snapshot() {
            long elapsedMs = (finishedAtMs > 0 ? finishedAtMs : System.currentTimeMillis()) - startedAtMs;
            double percent = total <= 0 ? 100.0 : (done * 100.0) / total;

            Long averageMs = timedCount == 0 ? null : timedDurationSumMs / timedCount;

            // Null rather than zero while nothing has been timed yet: "unknown" and "no time left"
            // are very different things to show a waiting operator, and a caller formatting a null
            // gets a dash instead of a confident 00:00 that is about to be wrong.
            Long etaMs = null;
            if (STATE_RUNNING.equals(state) && averageMs != null) {
                int remaining = Math.max(0, total - done);
                etaMs = averageMs * remaining;
            }

            return new ProgressSnapshot(
                    runId,
                    runSeq,
                    corpusDir,
                    dryRun,
                    state,
                    total,
                    done,
                    processed,
                    failed,
                    skipped,
                    round1(percent),
                    currentSample,
                    elapsedMs,
                    formatDuration(elapsedMs),
                    etaMs,
                    etaMs == null ? null : formatDuration(etaMs),
                    averageMs,
                    abortedReason);
        }
    }

    // -------------------------------------------------------------------------------------------

    /** {@code mm:ss} under an hour, {@code h:mm:ss} beyond it. */
    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
