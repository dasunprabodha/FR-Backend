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

    // --- comparison 5: uploaded scanned NIC vs. the card physically presented to the camera ------
    // Cross-channel document check: are the uploaded scan and the presented card even the same
    // document? Nothing else in the pipeline asks this - OCR and authenticity only ever inspect the
    // upload, while the gate only inspects the captures. Recorded, not gated.
    private Boolean match5;
    private Double fifthSimilarity;

    // --- derived: agreement between the two document channels (cmp1 vs cmp4) --------------------
    /** CONSISTENT | MARGINAL | DIVERGENT | UNAVAILABLE. */
    private String crossChannelStatus;

    /** Absolute difference in similarity points between the device-card and uploaded-scan channels. */
    private Double crossChannelDelta;

    private String validNicStatus;

    // --- Identity-binding evidence (document NIC number vs. claimed NIC) ---------------------
    // Recorded for every attempt but NOT part of the pass/fail gate, so the existing conjunctive
    // decision stays a valid frozen baseline. These columns are what an offline analysis reads to
    // measure what binding would have been worth had it been gated.

    /** NIC number OCR read off the presented document, normalised. Null when none was read. */
    private String extractedNicNumber;

    /** EXACT_MATCH | CONFUSION_CORRECTED_MATCH | FORMAT_EQUIVALENT_MATCH | PARTIAL | MISMATCH | UNAVAILABLE. */
    private String nicBindingOutcome;

    /** 0.0-1.0 binding strength; null when UNAVAILABLE (no evidence, as opposed to zero evidence-strength). */
    private Double nicBindingScore;

    /** Levenshtein distance between the canonical claimed and extracted numbers. */
    private Integer nicBindingEditDistance;

    /**
     * Snapshot of verification.binding-threshold at the time this attempt ran.
     *
     * <p>Same reason as the similarity and liveness snapshots below: an explanation rendered later
     * must report the threshold the decision actually used, not whatever configuration currently
     * says. Null on rows written before binding carried a threshold.
     */
    private Double nicBindingThreshold;

    /**
     * Whether identity binding participated in this attempt's decision rule.
     *
     * <p>Null on rows written before the switch existed, which is correctly read as false: binding
     * was never gated then. Without this snapshot, turning {@code verification.binding-gated} on
     * would silently rewrite the history of every past attempt in the evidence panel.
     */
    private Boolean nicBindingGated;

    /** Mean Rekognition per-line OCR confidence (0-100) - a document-quality term for later fusion. */
    private Double ocrMeanLineConfidence;

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

    /** Set when RegistrationApprovalService.changeStatus/approve/reject is called - a separate, later request; doubles as "reviewedDate". */
    private LocalDateTime actionDate;

    // --- Approval workflow fields (Registration Approval Workflow feature) ---

    /** Snapshot of verification.similarity-threshold at the time this attempt ran, for audit even if config changes later. */
    private Double similarityThreshold;

    /** Snapshot of verification.liveness-confidence-threshold at the time this attempt ran. */
    private Double livenessThreshold;

    /** Why this attempt landed in PENDING_APPROVAL, e.g. "LOW_SIMILARITY", "LIVENESS_FAILED", or both joined. Null for AWS_APPROVED attempts. */
    private String failureReason;

    /** Officer who approved/rejected this record. Nullable placeholder until an auth module exists. */
    private String reviewedBy;

    /** Optional free-text note left by the reviewing officer, mainly used on reject. */
    @Lob
    private String remarks;

    /** Guards against re-uploading images to S3 if approve is somehow invoked twice for the same record. */
    @Column(name = "images_uploaded_to_s3", nullable = false)
    private boolean imagesUploadedToS3;
}
