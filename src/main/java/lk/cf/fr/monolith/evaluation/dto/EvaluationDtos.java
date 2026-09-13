package lk.cf.fr.monolith.evaluation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Request/label/result shapes for the offline evaluation harness.
 *
 * <p>Grouped in one file because they are small, mutually-referential and only ever used together
 * by {@code BatchEvaluationService} and {@code EvaluationController}.
 */
public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    /** Body of {@code POST /api/v2/evaluate/batch}. */
    @Getter
    @Setter
    public static class BatchRequest {

        /**
         * Directory containing one sub-directory per sample. Resolved relative to the configured
         * {@code evaluation.corpus-root} and rejected if it escapes it. Defaults to the corpus root
         * itself when omitted.
         */
        private String corpusDir;

        /** Free-text label for this run; also used in the output directory name. Optional. */
        private String runId;

        /** Stop after this many samples. Useful for a smoke run. 0/absent means no limit. */
        private Integer limit;

        /**
         * When true the harness only lists what it would process and reports the resolved labels,
         * without calling Rekognition. Costs nothing and verifies the corpus layout first.
         */
        private boolean dryRun;

        /**
         * Write an attempt row per sample so each result can be opened in the Evidence Dashboard.
         * Rows are stored with status {@code EVALUATION} and {@code activeStatus=INACTIVE}, which
         * every operational query filters out by exact status match - they never reach the approval
         * queue and never satisfy the verification enrolment check. Default on; the whole point of
         * a run is being able to click into a result and see why it went that way.
         */
        private Boolean persistEvidence;

        public boolean persistEvidenceOrDefault() {
            return persistEvidence == null || persistEvidence;
        }
    }

    /**
     * Optional {@code sample.json} inside each sample directory. Every field is optional: the
     * harness falls back to the {@code registration_record} row when the directory name is a known
     * {@code referenceId}, which makes already-captured registrations replayable with no extra
     * setup at all.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SampleLabels {

        /** The NIC the applicant claimed - the left-hand side of the identity-binding comparison. */
        private String claimedNic;

        /** Pseudonymous subject identifier. Splits must be subject-disjoint, never image-disjoint. */
        private String subjectId;

        /** GENUINE | ATTACK. */
        private String groundTruth;

        /** e.g. PRINT_LASER, PRINT_INKJET, SCREEN_LCD, SCREEN_OLED, PHOTOCOPY, TAMPERED, MISMATCHED_GENUINE_NIC. */
        private String attackType;

        private String device;
        private String lighting;
        private String distance;

        /** Liveness recorded at capture time, if any. Absent means the gate reports allPassed=null. */
        private Double livenessScore;
        private Boolean livenessPassed;

        /**
         * Mock-mode only: forces the similarity this sample's face comparisons return. Ignored
         * entirely once aws.enabled=true, where real scores come from Rekognition.
         *
         * <p>Exists so the harness, the curves and the evidence views can be exercised end to end
         * without AWS credentials or spend - otherwise every mock sample scores identically and no
         * metric can separate the classes, which makes the whole pipeline untestable offline.
         */
        private Double mockSimilarity;

        /** Anything else you want carried through to the results CSV. */
        private Map<String, String> extra;
    }

    /**
     * The dependency-aware rule's verdict for one sample, recorded beside the baseline's.
     *
     * <p>Nested rather than flattened into {@link SampleResult} so that adding a group later does
     * not renumber thirty-odd positional constructor arguments. Null on samples that errored
     * before analysis, and on dry runs.
     *
     * <p>Per-group state and score are both carried: the state is what the rule acted on, the
     * score is what lets a threshold sweep re-derive the decision at other operating points
     * without re-running Rekognition.
     */
    public record ProposedDecision(
            String decision,
            String drivers,
            String reason,
            String documentPortraitState, Double documentPortraitScore,
            String coPresenceState, Double coPresenceScore,
            String channelAgreementState, Double channelAgreementScore,
            String identityBindingState, Double identityBindingScore,
            String livenessState, Double livenessGroupScore
    ) {
    }

    /** One row of the results table - one sample, fully analysed. */
    public record SampleResult(
            String sampleId,

            /* Reference ID of the persisted attempt row, for deep-linking into the evidence view. */
            String analysisRef,
            String subjectId,
            String groundTruth,
            String attackType,

            /*
             * True when the sample was assembled from photographs collected for other samples
             * rather than captured as its own session. Read from sample.json's extra.constructed.
             *
             * Surfaced as a first-class column because a corpus may hold both kinds at once, and a
             * pooled accuracy figure across them would mix field measurement with mechanism check.
             * Without this the two are distinguishable only by naming convention, which is not a
             * guarantee.
             */
            Boolean constructed,

            String device,
            String lighting,
            String claimedNic,

            String ocrOutcome,

            /* Which image the number was read off: SCANNED_UPLOAD, DEVICE_CAPTURE or NONE. */
            String ocrSource,
            String extractedNic,
            Double ocrMeanLineConfidence,

            String bindingOutcome,
            Double bindingScore,
            Integer bindingEditDistance,

            Boolean cmp1Match, Double cmp1Similarity,
            Boolean cmp2Match, Double cmp2Similarity,
            Boolean cmp3Match, Double cmp3Similarity,
            Boolean cmp4Match, Double cmp4Similarity,
            Boolean cmp5Match, Double cmp5Similarity,

            /* Derived agreement between the two document channels (cmp1 vs cmp4). */
            String crossChannelStatus,
            Double crossChannelDelta,

            boolean nicCardCropped,
            boolean selfNicCardCropped,
            boolean scannedNicCardCropped,

            Double livenessScore,
            Boolean livenessPassed,

            boolean similarityPassed,
            Boolean allPassed,

            /* Binding was gated and refused the claim. */
            Boolean bindingBlocked,

            /* The scan-vs-presented-card comparison was gated and refused the claim. */
            Boolean channelBlocked,

            /*
             * What the deployed rule decides, with liveness set aside. Named for what it is rather
             * than for what it omits: a replay has no liveness channel, so allPassed is null on
             * every row and this is the only column that states the rule's actual verdict.
             */
            Boolean decisionWithoutLiveness,
            String failureReason,

            long documentLatencyMs,
            long faceLatencyMs,
            String error,

            /* The proposed rule's verdict on identical inputs. Never feeds the live path. */
            ProposedDecision proposed
    ) {
    }

    /** Aggregate returned to the caller; the per-sample detail is written to disk. */
    public record BatchSummary(
            String runId,
            String corpusDir,
            String outputDir,
            int samplesFound,
            int samplesProcessed,
            int samplesFailed,

            /* Directories carrying none of the required captures - not samples, quietly ignored. */
            int samplesSkipped,

            long totalDurationMs,
            Map<String, Long> bindingOutcomeCounts,
            Map<String, Long> ocrOutcomeCounts,
            long similarityPassedCount,

            /* APPROVE / REVIEW / REJECT counts under the proposed rule, for the review-load figure. */
            Map<String, Long> proposedDecisionCounts,

            /* Set when the run stopped early - currently only on a credentials failure. */
            String abortedReason,

            List<SampleResult> results
    ) {
    }

    /**
     * Live state of the most recent batch run, returned by {@code GET /api/v2/evaluate/progress}.
     *
     * <p>Carries the pre-formatted {@code elapsedText} and {@code etaText} beside their raw
     * millisecond values so the console can render a duration without reimplementing the h:mm:ss
     * rules, while still having the numbers available for anything that needs to compute on them.
     */
    /**
     * One saved run as it appears in the archive picker - enough to choose between runs without
     * opening any of them.
     *
     * <p>A run is only ever written to disk by a real (non-dry) batch, so everything listed here
     * has results behind it.
     */
    public record RunListing(
            String runId,

            /* ISO-8601, from run-meta.json where present and the results file's timestamp otherwise. */
            String savedAt,

            /* Rows in the results file. Counted from the CSV when no meta file was written. */
            int sampleCount,

            /* Bytes on disk across the run's files - a rough proxy for how big the table will be. */
            long sizeBytes,

            boolean hasCsv,
            boolean hasJson,

            /*
             * False for runs written before run-meta.json existed. Those still load - the summary
             * is rebuilt from the rows - but corpus, duration and skipped count are unrecoverable.
             */
            boolean hasMeta
    ) {
    }

    /**
     * The parts of a {@link BatchSummary} that cannot be recovered from the result rows.
     *
     * <p>Written beside results.csv/json so a saved run reopens with the same header figures it
     * showed when it finished. Everything else - the outcome counts, the pass count - is derived
     * from the rows themselves and so is never stored twice.
     */
    public record RunMeta(
            String runId,
            String corpusDir,
            int samplesFound,
            int samplesProcessed,
            int samplesFailed,
            int samplesSkipped,
            long totalDurationMs,
            String abortedReason,

            /* ISO-8601 local time the run finished. */
            String completedAt
    ) {
    }

    public record ProgressSnapshot(
            String runId,

            /*
             * Monotonic per-JVM counter, incremented once per run. Run *names* are reused all the
             * time - a corpus is re-run under the same name after a fix - so runId alone cannot
             * tell a fresh run from the finished one still held by the tracker. This can.
             */
            long runSeq,

            String corpusDir,
            boolean dryRun,

            /* RUNNING | COMPLETE | ABORTED. */
            String state,

            /* Loop units: every sample directory the run will visit, skipped ones included. */
            int samplesTotal,
            int samplesDone,

            int samplesProcessed,
            int samplesFailed,
            int samplesSkipped,

            double percentComplete,

            /* The sample currently in the pipeline - names the culprit when a run appears stuck. */
            String currentSample,

            long elapsedMs,
            String elapsedText,

            /* Null until at least one sample has been timed: unknown, not zero. */
            Long etaMs,
            String etaText,

            Long averageSampleMs,

            String abortedReason
    ) {
    }
}
