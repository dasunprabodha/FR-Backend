package lk.cf.fr.monolith.registry.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mirrors cf-fr-server Transaction/models/DeviceRecords.java (table DEVICE_RECORDS).
 * This is the only place a device's business authorization is checked
 * (status == "ACTIVE") - see VERIFICATION_PATH_ARCHITECTURE.md section 3.2/§8.
 */
@Entity
@Table(name = "device_records")
@Getter
@Setter
public class DeviceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userId;

    /** Internal UUID identifying the device, used to key the WebSocket session registry. */
    @Column(nullable = false, unique = true)
    private String deviceId;

    private String appInstanceId;

    /** Expected values: ACTIVE, INACTIVE */
    private String status;

    private String branchId;

    private String deviceNickname;

    private String model;

    private String osVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
