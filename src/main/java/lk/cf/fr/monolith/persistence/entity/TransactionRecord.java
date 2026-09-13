package lk.cf.fr.monolith.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mirrors cf-fr-server Transaction/models/Transaction_Record.java (table transaction_record).
 * The face image is kept as a plain string reference on this row instead of a separate
 * transaction_images join table (see IMPLEMENTATION_PROGRESS.md - "Temporary assumptions").
 */
@Entity
@Table(name = "transaction_record")
@Getter
@Setter
public class TransactionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nic;

    private String userId;

    private String cifNo;

    @Column(nullable = false, unique = true)
    private String referenceId;

    @Column(nullable = false)
    private String actionType;

    private String deviceId;

    private Double similarity;

    private Boolean match;

    private Boolean livenessPassed;

    private Double livenessScore;

    @Column(length = 64)
    private String livenessSessionId;

    @Lob
    @Column(name = "compare_json")
    private String compareJson;

    @Lob
    @Column(name = "face_image_ref")
    private String faceImageRef;

    private String state;

    private String errorMessage;

    private LocalDateTime reqTime;

    private LocalDateTime resTime;
}
