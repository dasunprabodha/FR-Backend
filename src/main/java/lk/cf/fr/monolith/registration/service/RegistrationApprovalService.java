package lk.cf.fr.monolith.registration.service;

import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

/**
 * Direct-call replacement for cf-fr-server
 * Transaction/services/impl/RegistrationManagementRecordImpl.changeStatus - see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.5 point 4/§14/§16.
 *
 * <p>Kept as an explicitly separate service from {@code RegistrationService}, invoked by a
 * different, later request (an admin approving/rejecting a registration) - not folded into the
 * synchronous {@code startRegistration} call, per the architecture doc's explicit
 * recommendation (§28: "if naively merged into the synchronous registration call, the whole
 * request would need to block on a human action, which is clearly wrong").
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RegistrationApprovalService {

    private final RegistrationRecordRepository registrationRecordRepository;

    @Transactional
    public RegistrationRecord changeStatus(String referenceId, String newStatus) {
        RegistrationRecord record = registrationRecordRepository.findByReferenceId(referenceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No registration record found for referenceId=" + referenceId));

        record.setStatus(newStatus.toUpperCase());
        record.setActionDate(LocalDateTime.now());
        RegistrationRecord saved = registrationRecordRepository.save(record);

        if ("APPROVED".equalsIgnoreCase(newStatus)) {
            // Legacy behavior: on APPROVED, Transaction.RegistrationManagementRecordImpl.changeStatus
            // sends the 3 registration images to Face_Recognition for final, permanent S3
            // persistence (face-S3-save-topic). No external file-storage/S3 is configured for this
            // MVP (see "no external server, keep it as is" decision) - logged here as a clearly
            // marked no-op instead of silently doing nothing.
            log.info("[MOCK] Final S3 persistence skipped for referenceId={} - no external S3/file-storage "
                    + "service configured for this MVP; image refs remain the local placeholders set at capture time.", referenceId);
        }

        log.info("[Registration] status changed referenceId={} -> {}", referenceId, newStatus);
        return saved;
    }
}
