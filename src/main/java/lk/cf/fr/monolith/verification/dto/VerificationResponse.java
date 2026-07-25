package lk.cf.fr.monolith.verification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mirrors the verification-relevant subset of cf-fr-server Api-Gateway.Dto.GatewayResponse - see
 * VERIFICATION_PATH_ARCHITECTURE.md section 15/§18. Field names are kept unchanged so the
 * existing Angular ShiftInformationComponent (which reads faceVsFaceMatch,
 * faceVsFaceSimilarityScore, livenessPassed, livenessScore, referenceId,
 * overallSimilarityDecision) can consume this response without modification.
 *
 * <p>Unlike GatewayResponse, this DTO does not need Jackson's flexible/aliased setters -
 * those existed only to paper over inconsistent field names produced by different Kafka hops;
 * in the monolith there is exactly one producer of this object.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationResponse {

    private String referenceId;
    private Boolean status;
    private String overallSimilarityDecision;

    private Boolean faceVsFaceMatch;
    private Double faceVsFaceSimilarityScore;

    private Boolean livenessPassed;
    private Double livenessScore;
    private String livenessSessionId;

    private String message;
    private String reason;
}
