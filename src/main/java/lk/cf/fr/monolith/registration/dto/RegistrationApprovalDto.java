package lk.cf.fr.monolith.registration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Read-model for the Approval Dashboard - covers the pending list, history list, and get-by-id
 * views (history rows simply have {@code reviewedDate}/{@code reviewedBy}/{@code remarks}
 * populated). Deliberately a single DTO rather than three near-identical ones, matching how
 * {@code RegistrationResponse} already covers every registration outcome in one shape.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationApprovalDto {

    private Long id;
    private String referenceId;
    private String nic;
    private String cifNo;
    private String userId;
    private String status;
    private String failureReason;

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

    private Double similarityThreshold;

    private Boolean livenessPassed;
    private Double livenessScore;
    private Double livenessThreshold;

    private String validNicStatus;

    private LocalDateTime reqTime;
    private LocalDateTime reviewedDate;
    private String reviewedBy;
    private String remarks;

    private String nicImageUrl;
    private String faceImageUrl;
    private String selfieImageUrl;
}
