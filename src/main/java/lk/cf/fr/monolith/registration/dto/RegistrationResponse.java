package lk.cf.fr.monolith.registration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mirrors the registration-relevant subset of cf-fr-server Api-Gateway.Dto.GatewayResponse - see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §16/§19. Field names are kept unchanged so the
 * existing Angular RosterInformationComponent (which reads deviceNicVsFaceMatch,
 * deviceNicVsSelfNicMatch, faceVsSelfFaceMatch, scannedNicVsFaceMatch and their *SimilarityScore
 * counterparts, livenessPassed, livenessScore, validNicStatus, referenceId,
 * overallSimilarityDecision) can consume this response without modification.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationResponse {

    private String referenceId;
    private Boolean status;
    private String overallSimilarityDecision;

    /** True when this attempt failed only on similarity/liveness and was routed to the Approval Dashboard instead of being discarded. */
    private Boolean pendingApproval;

    /** Comparison 1: device NIC photo vs. live face capture (informational, not part of the pass gate). */
    private Boolean deviceNicVsFaceMatch;
    private Double deviceNicVsFaceSimilarityScore;

    /** Comparison 2: device NIC photo vs. self-with-NIC capture (gated). */
    private Boolean deviceNicVsSelfNicMatch;
    private Double deviceNicVsSelfNicSimilarityScore;

    /** Comparison 3: live face capture vs. self-with-NIC capture (gated). */
    private Boolean faceVsSelfFaceMatch;
    private Double faceVsSelfFaceSimilarityScore;

    /** Comparison 4: optional uploaded scanned-NIC file vs. live face capture (gated only if provided). */
    private Boolean scannedNicVsFaceMatch;
    private Double scannedNicVsFaceSimilarityScore;

    /**
     * Comparison 5: uploaded scanned NIC vs. the card physically presented to the camera. Asks
     * whether the two document channels are even showing the same document — a question nothing
     * else in the pipeline poses, since OCR and authenticity only inspect the upload while the
     * gate only inspects the captures. Recorded, not gated.
     */
    private Boolean scannedNicVsDeviceNicMatch;
    private Double scannedNicVsDeviceNicSimilarityScore;

    /**
     * Derived agreement between the two independent measurements of document-portrait vs. live
     * face (comparisons 1 and 4): CONSISTENT | MARGINAL | DIVERGENT | UNAVAILABLE, with the
     * absolute similarity-point gap between the channels.
     */
    private String crossChannelStatus;
    private Double crossChannelDelta;

    private Boolean livenessPassed;
    private Double livenessScore;
    private String livenessSessionId;

    private String validNicStatus;

    /**
     * Identity-binding evidence: does the NIC number printed on the presented document actually
     * match the NIC the applicant claimed? Additive fields - the existing Angular console and the
     * Android client both ignore unknown JSON properties, so no client change is required.
     *
     * <p>{@code nicBindingScore} is 0.0-1.0 and null when {@code nicBindingOutcome} is
     * {@code UNAVAILABLE} (no document supplied, or no NIC-shaped number read) - absence of
     * evidence, as distinct from a score of zero meaning contradicting evidence.
     *
     * <p>These values are reported but do not yet influence {@code overallSimilarityDecision}.
     */
    private String extractedNicNumber;
    private String nicBindingOutcome;
    private Double nicBindingScore;
    private String nicBindingDetail;

    private String message;
    private String reason;
}
