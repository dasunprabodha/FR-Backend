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

    private Boolean livenessPassed;
    private Double livenessScore;
    private String livenessSessionId;

    private String validNicStatus;

    private String message;
    private String reason;
}
