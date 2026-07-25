package lk.cf.fr.monolith.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mirrors cf-fr-server Transaction/models/Registration_Record.java (table registration_record).
 *
 * <p>Originally added (see VERIFICATION_PATH_ARCHITECTURE.md §9/§28 Q8) as a minimal read-mostly
 * table so verification could answer "does an approved, active enrollment exist for this
 * customer?". Extended here to carry the full registration write path - see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §15/§20.
 *
 * <p>Simplification vs. legacy: the captured images are kept as plain string references on this
 * row instead of a separate {@code registration_images} join table, consistent with the same
 * simplification already made for {@code TransactionRecord}/verification - see
 * REGISTRATION_IMPLEMENTATION_PROGRESS.md "Temporary assumptions".
 */
@Entity
@Table(name = "registration_record")
@Getter
@Setter
public class RegistrationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nic;

    private String userId;

    @Column(name = "cif_no")
    private String cifNo;

    @Column(unique = true)
    private String referenceId;

    @Column(nullable = false)
    private String actionType;

    /** Expected values: AWS_APPROVED, PENDING, APPROVED, REJECTED, ... */
    private String status;

    /** Expected values: ACTIVE, INACTIVE */
    private String activeStatus;

    private String deviceId;

    // --- comparison 1: device NIC photo vs. live face capture (informational, not part of the S3/pass gate) ---
    private Boolean match;
    private Double similarity;

    // --- comparison 2: device NIC photo vs. self-with-NIC capture (gated) ---
    private Boolean match2;
    private Double secondSimilarity;

    // --- comparison 3: live face capture vs. self-with-NIC capture (gated) ---
    private Boolean match3;
    private Double thirdSimilarity;

    // --- comparison 4: optional uploaded scanned-NIC file vs. live face capture (gated only if provided) ---
    private Boolean match4;
    private Double fourthSimilarity;

    private String validNicStatus;

    private Boolean livenessPassed;
    private Double livenessScore;

    @Column(length = 64)
    private String livenessSessionId;

    @Lob
    @Column(name = "nic_image_ref")
    private String nicImageRef;

    @Lob
    @Column(name = "face_image_ref")
    private String faceImageRef;

    @Lob
    @Column(name = "self_image_ref")
    private String selfImageRef;

    private String errorMessage;

    private LocalDateTime reqTime;

    private LocalDateTime resTime;

    /** Set when RegistrationApprovalService.changeStatus is called - a separate, later request. */
    private LocalDateTime actionDate;
}
