package lk.cf.fr.monolith.registration.service;

import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lk.cf.fr.monolith.storage.FaceImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared "make this registration's images permanent and verification-eligible" step, used by both
 * the automatic success path ({@code RegistrationService}, immediately after the comparison/liveness
 * gate passes) and manual approval ({@code RegistrationApprovalService.approve}, after an officer
 * reviews a PENDING_APPROVAL record) - the single upload code path the Registration Approval
 * Workflow feature was designed to share instead of duplicating.
 *
 * <p>Throws {@link lk.cf.fr.monolith.storage.ImageStorageException} on upload failure rather than
 * swallowing it; it is each caller's job to decide what that means for them (see
 * {@link lk.cf.fr.monolith.storage.S3FaceImageStorageService} class doc).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RegistrationFinalizationService {

    private final FaceImageStorageService faceImageStorageService;
    private final RegistrationRecordRepository registrationRecordRepository;

    /** No-ops if images were already uploaded for this record (avoids a duplicate upload, e.g. a repeated approve call). */
    @Transactional
    public void finalizeApproved(RegistrationRecord record, byte[] nicImage, byte[] faceImage, byte[] selfImage) {
        if (record.isImagesUploadedToS3()) {
            log.info("[Registration] Images already uploaded referenceId={}, skipping duplicate upload", record.getReferenceId());
            return;
        }
        faceImageStorageService.saveEnrolledFace(record.getNic(), faceImage);
        faceImageStorageService.saveFaceWithNic(record.getNic(), selfImage);
        faceImageStorageService.saveNicImage(record.getNic(), nicImage);
        record.setImagesUploadedToS3(true);
        registrationRecordRepository.save(record);
    }
}
