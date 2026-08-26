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

    /** One row of the results table - one sample, fully analysed. */
    public record SampleResult(
            String sampleId,

            /* Reference ID of the persisted attempt row, for deep-linking into the evidence view. */
            String analysisRef,
            String subjectId,
            String groundTruth,
            String attackType,
            String device,
            String lighting,
            String claimedNic,

            String ocrOutcome,
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
            String failureReason,

            long documentLatencyMs,
            long faceLatencyMs,
            String error
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

            /* Set when the run stopped early - currently only on a credentials failure. */
            String abortedReason,

            List<SampleResult> results
    ) {
    }
}
