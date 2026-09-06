package lk.cf.fr.monolith.registration.service;

import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lk.cf.fr.monolith.registration.dto.RegistrationApprovalDto;
import lk.cf.fr.monolith.registration.model.RegistrationApprovalStatus;
import lk.cf.fr.monolith.storage.ImageStorageException;
import lk.cf.fr.monolith.storage.LocalPendingImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Direct-call replacement for cf-fr-server
 * Transaction/services/impl/RegistrationManagementRecordImpl.changeStatus - see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.5 point 4/§14/§16 - extended into the full
 * Registration Approval Workflow: {@code PENDING_APPROVAL} records (comparisons/liveness failed
 * at registration time, images retained on local disk) are listed on the Approval Dashboard and
 * decided here, one at a time, by an officer.
 *
 * <p>Kept as an explicitly separate service from {@code RegistrationService}, invoked by a
 * different, later request (an admin approving/rejecting a registration) - not folded into the
 * synchronous {@code startRegistration} call, per the architecture doc's explicit
 * recommendation (§28: "if naively merged into the synchronous registration call, the whole
 * request would need to block on a human action, which is clearly wrong").
 *
 * <p>No authentication/authorization is applied yet (matches this MVP's explicit scope
 * limitation), but every mutating method threads a nullable {@code reviewedBy} through end-to-end
 * so a future auth layer only needs to supply that value, not restructure the API.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RegistrationApprovalService {

    private final RegistrationRecordRepository registrationRecordRepository;
    private final LocalPendingImageStorageService localPendingImageStorageService;
    private final RegistrationFinalizationService registrationFinalizationService;

    public List<RegistrationApprovalDto> listPending() {
        return registrationRecordRepository
                .findByStatusOrderByReqTimeDesc(RegistrationApprovalStatus.PENDING_APPROVAL.name())
                .stream().map(this::toDto).toList();
    }

    public List<RegistrationApprovalDto> listHistory() {
        return registrationRecordRepository
                .findByStatusInOrderByActionDateDesc(List.of(
                        RegistrationApprovalStatus.APPROVED.name(), RegistrationApprovalStatus.REJECTED.name()))
                .stream().map(this::toDto).toList();
    }

    public RegistrationApprovalDto getById(Long id) {
        return toDto(getRecordOrThrow(id));
    }

    /** Streams a captured image back for the Approval Dashboard's preview - works before and after a decision, since images are never deleted. */
    public byte[] readImage(Long id, String type) {
        RegistrationRecord record = getRecordOrThrow(id);
        String path = switch (type) {
            case "nic" -> record.getNicImageRef();
            case "face" -> record.getFaceImageRef();
            case "selfie" -> record.getSelfImageRef();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown image type: " + type);
        };
        if (path == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + type + " image stored for id=" + id);
        }
        return localPendingImageStorageService.read(path);
    }

    /**
     * Uploads the three retained images to S3 and marks the record APPROVED - exactly the same
     * finalize step an automatic pass runs, via {@link RegistrationFinalizationService}. If the S3
     * upload fails, the record is left untouched (still PENDING_APPROVAL, retryable) rather than
     * being marked APPROVED.
     */
    @Transactional
    public RegistrationApprovalDto approve(Long id, String reviewedBy, String remarks) {
        RegistrationRecord record = requirePendingApproval(id);

        byte[] nicImage = localPendingImageStorageService.read(record.getNicImageRef());
        byte[] faceImage = localPendingImageStorageService.read(record.getFaceImageRef());
        byte[] selfImage = localPendingImageStorageService.read(record.getSelfImageRef());

        try {
            registrationFinalizationService.finalizeApproved(record, nicImage, faceImage, selfImage);
        } catch (ImageStorageException e) {
            log.error("[Approval] S3 upload failed while approving id={} referenceId={} - leaving PENDING_APPROVAL",
                    id, record.getReferenceId(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to upload images to S3 - registration was not approved. Please retry.", e);
        }

        record.setStatus(RegistrationApprovalStatus.APPROVED.name());
        record.setReviewedBy(reviewedBy);
        record.setRemarks(remarks);
        record.setActionDate(LocalDateTime.now());
        RegistrationRecord saved = registrationRecordRepository.save(record);
        log.info("[Approval] APPROVED id={} referenceId={} reviewedBy={}", id, record.getReferenceId(), reviewedBy);
        return toDto(saved);
    }

    /** Marks the record REJECTED - audit-only, no S3/image interaction, never becomes verification-eligible. */
    @Transactional
    public RegistrationApprovalDto reject(Long id, String reviewedBy, String remarks) {
        RegistrationRecord record = requirePendingApproval(id);
        record.setStatus(RegistrationApprovalStatus.REJECTED.name());
        record.setReviewedBy(reviewedBy);
        record.setRemarks(remarks);
        record.setActionDate(LocalDateTime.now());
        RegistrationRecord saved = registrationRecordRepository.save(record);
        log.info("[Approval] REJECTED id={} referenceId={} reviewedBy={}", id, record.getReferenceId(), reviewedBy);
        return toDto(saved);
    }

    /**
     * Legacy entry point ({@code PUT /records/{referenceId}/{status}}) - delegates into
     * {@link #approve}/{@link #reject} so both routes share one code path instead of two divergent
     * implementations.
     */
    @Transactional
    public RegistrationRecord changeStatus(String referenceId, String newStatus) {
        RegistrationRecord record = registrationRecordRepository.findByReferenceId(referenceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No registration record found for referenceId=" + referenceId));

        RegistrationApprovalStatus target;
        try {
            target = RegistrationApprovalStatus.valueOf(newStatus.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + newStatus);
        }

        RegistrationApprovalDto dto = switch (target) {
            case APPROVED -> approve(record.getId(), null, null);
            case REJECTED -> reject(record.getId(), null, null);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status must be APPROVED or REJECTED, got: " + newStatus);
        };
        return registrationRecordRepository.findById(dto.getId()).orElseThrow();
    }

    private RegistrationRecord requirePendingApproval(Long id) {
        RegistrationRecord record = getRecordOrThrow(id);
        if (!RegistrationApprovalStatus.PENDING_APPROVAL.name().equals(record.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Record id=" + id + " is not pending approval (current status=" + record.getStatus() + ")");
        }
        return record;
    }

    private RegistrationRecord getRecordOrThrow(Long id) {
        return registrationRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No registration record found for id=" + id));
    }

    private RegistrationApprovalDto toDto(RegistrationRecord r) {
        return RegistrationApprovalDto.builder()
                .id(r.getId())
                .referenceId(r.getReferenceId())
                .nic(r.getNic())
                .cifNo(r.getCifNo())
                .userId(r.getUserId())
                .status(r.getStatus())
                .failureReason(r.getFailureReason())
                .deviceNicVsFaceMatch(r.getMatch())
                .deviceNicVsFaceSimilarityScore(r.getSimilarity())
                .deviceNicVsSelfNicMatch(r.getMatch2())
                .deviceNicVsSelfNicSimilarityScore(r.getSecondSimilarity())
                .faceVsSelfFaceMatch(r.getMatch3())
                .faceVsSelfFaceSimilarityScore(r.getThirdSimilarity())
                .scannedNicVsFaceMatch(r.getMatch4())
                .scannedNicVsFaceSimilarityScore(r.getFourthSimilarity())
                .similarityThreshold(r.getSimilarityThreshold())
                .livenessPassed(r.getLivenessPassed())
                .livenessScore(r.getLivenessScore())
                .livenessThreshold(r.getLivenessThreshold())
                .validNicStatus(r.getValidNicStatus())
                .reqTime(r.getReqTime())
                .reviewedDate(r.getActionDate())
                .reviewedBy(r.getReviewedBy())
                .remarks(r.getRemarks())
                .nicImageUrl("/api/approval/" + r.getId() + "/image/nic")
                .faceImageUrl("/api/approval/" + r.getId() + "/image/face")
                .selfieImageUrl("/api/approval/" + r.getId() + "/image/selfie")
                .build();
    }
}
