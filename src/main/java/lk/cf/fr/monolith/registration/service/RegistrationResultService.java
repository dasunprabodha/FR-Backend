package lk.cf.fr.monolith.registration.service;

import lk.cf.fr.monolith.analysis.RegistrationAnalysisService;
import lk.cf.fr.monolith.document.NicOcrResult;
import lk.cf.fr.monolith.identity.IdentityBindingResult;
import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lk.cf.fr.monolith.registration.dto.RegistrationRequest;
import lk.cf.fr.monolith.registration.model.RegistrationException;
import lk.cf.fr.monolith.registration.model.RegistrationState;
import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Direct-call replacement for the registration-specific parts of cf-fr-server
 * Transaction/services/DatabaseListenerService.handleCreateRegistrationRecord (CIF dedup +
 * create) and DatabaseWriteService.handleUpdateMultipleImage/handleUpdateRegistrationResult - see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.2/§3.5/§14.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RegistrationResultService {

    private final RegistrationRecordRepository registrationRecordRepository;

    /**
     * Mirrors Transaction.DatabaseListenerService.handleCreateRegistrationRecord: a new
     * registration for a CIF that already has an active record supersedes it (deactivates it,
     * does not reject it); a second registration for a CIF that already has a REGISTRATION-type
     * record is reclassified as UPDATE.
     */
    @Transactional
    public RegistrationRecord createOrSupersede(RegistrationRequest request, String referenceId) {
        String cifNo = request.getCifNo();

        List<RegistrationRecord> activeExisting = registrationRecordRepository.findByCifNoAndActiveStatus(cifNo, "ACTIVE");
        activeExisting.forEach(r -> r.setActiveStatus("INACTIVE"));
        registrationRecordRepository.saveAll(activeExisting);
        if (!activeExisting.isEmpty()) {
            log.info("[Registration] Superseded {} prior active record(s) for cifNo={}", activeExisting.size(), cifNo);
        }

        boolean alreadyRegistered = registrationRecordRepository.findByCifNoAndActionType(cifNo, "REGISTRATION").isPresent();

        RegistrationRecord record = new RegistrationRecord();
        record.setNic(request.getNic());
        record.setUserId(request.getUserId());
        record.setCifNo(cifNo);
        record.setReferenceId(referenceId);
        record.setActionType(alreadyRegistered ? "UPDATE" : "REGISTRATION");
        record.setActiveStatus("ACTIVE");
        record.setReqTime(LocalDateTime.now());
        return registrationRecordRepository.save(record);
    }

    /** Records the local disk paths written by {@code LocalPendingImageStorageService} at capture time - see the Registration Approval Workflow. */
    @Transactional
    public void persistImages(String referenceId, String nicImagePath, String faceImagePath, String selfImagePath) {
        RegistrationRecord record = getOrThrow(referenceId);
        record.setNicImageRef(nicImagePath);
        record.setFaceImageRef(faceImagePath);
        record.setSelfImageRef(selfImagePath);
        registrationRecordRepository.save(record);
    }

    /**
     * Takes the whole {@link RegistrationAnalysisService.FaceAnalysis} rather than each comparison
     * separately - the parameter list had already reached the point where adding the two new
     * cross-channel signals would have made it unreadable, and every caller has the analysis object
     * to hand anyway.
     */
    @Transactional
    public void persistResult(String referenceId, RegistrationAnalysisService.FaceAnalysis faces,
                               LivenessOutcome liveness, String validNicStatus, String registrationStatus,
                               Double similarityThreshold, Double livenessThreshold, String failureReason,
                               NicOcrResult ocr, IdentityBindingResult binding) {
        RegistrationRecord record = getOrThrow(referenceId);
        record.setMatch(faces.cmp1().match());
        record.setSimilarity(faces.cmp1().similarity());
        record.setMatch2(faces.cmp2().match());
        record.setSecondSimilarity(faces.cmp2().similarity());
        record.setMatch3(faces.cmp3().match());
        record.setThirdSimilarity(faces.cmp3().similarity());
        if (faces.cmp4() != null) {
            record.setMatch4(faces.cmp4().match());
            record.setFourthSimilarity(faces.cmp4().similarity());
        }
        if (faces.cmp5() != null) {
            record.setMatch5(faces.cmp5().match());
            record.setFifthSimilarity(faces.cmp5().similarity());
        }
        if (faces.consistency() != null) {
            record.setCrossChannelStatus(faces.consistency().status());
            record.setCrossChannelDelta(faces.consistency().delta());
        }
        record.setLivenessPassed(liveness.passed());
        record.setLivenessScore(liveness.confidence());
        record.setValidNicStatus(validNicStatus);
        record.setStatus(registrationStatus);
        record.setSimilarityThreshold(similarityThreshold);
        record.setLivenessThreshold(livenessThreshold);
        record.setFailureReason(failureReason);

        // Identity-binding evidence - recorded, not gated.
        if (ocr != null) {
            record.setExtractedNicNumber(ocr.extractedNicNumber());
            record.setOcrMeanLineConfidence(ocr.meanLineConfidence());
        }
        if (binding != null) {
            record.setNicBindingOutcome(binding.outcome().name());
            record.setNicBindingScore(binding.score());
            record.setNicBindingEditDistance(binding.editDistance());
        }

        record.setResTime(LocalDateTime.now());
        registrationRecordRepository.save(record);
        log.info("[Registration] Persisted final result referenceId={} status={} nicBinding={} bindingScore={} crossChannel={}",
                referenceId, registrationStatus,
                binding != null ? binding.outcome() : null, binding != null ? binding.score() : null,
                faces.consistency() != null ? faces.consistency().status() : null);
    }

    /** Mirrors Transaction.DatabaseWriteService.handleRemoveDbRecord - rolls back an incomplete record on hard failure. */
    @Transactional
    public void removeIncompleteRecord(String referenceId) {
        registrationRecordRepository.findByReferenceId(referenceId).ifPresent(registrationRecordRepository::delete);
    }

    /** Used by RegistrationService to hand the just-persisted record to RegistrationFinalizationService after an auto-pass. */
    public RegistrationRecord getByReferenceId(String referenceId) {
        return getOrThrow(referenceId);
    }

    private RegistrationRecord getOrThrow(String referenceId) {
        return registrationRecordRepository.findByReferenceId(referenceId)
                .orElseThrow(() -> new RegistrationException(RegistrationState.PROCESSING_ERROR,
                        "No registration_record row found for referenceId=" + referenceId,
                        "An unexpected error occurred during registration."));
    }
}
