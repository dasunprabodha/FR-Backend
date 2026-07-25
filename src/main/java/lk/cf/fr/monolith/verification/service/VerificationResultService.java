package lk.cf.fr.monolith.verification.service;

import lk.cf.fr.monolith.persistence.entity.TransactionRecord;
import lk.cf.fr.monolith.persistence.repository.TransactionRecordRepository;
import lk.cf.fr.monolith.verification.model.VerificationSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Direct-call replacement for cf-fr-server Transaction/services/DatabaseWriteService +
 * DatabaseListenerService.handleCreateValidation - see
 * VERIFICATION_PATH_ARCHITECTURE.md section 13 ("Face_Recognition -> transaction-db-record ->
 * Transaction hop" / "VerificationResultService.persist()").
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VerificationResultService {

    private final TransactionRecordRepository transactionRecordRepository;

    @Transactional
    public TransactionRecord createPending(VerificationSession session) {
        TransactionRecord record = new TransactionRecord();
        record.setNic(session.getNic());
        record.setUserId(session.getUserId());
        record.setCifNo(session.getCifNo());
        record.setReferenceId(session.getReferenceId());
        record.setActionType("VALIDATION");
        record.setDeviceId(session.getDeviceId());
        record.setState(session.getState().name());
        record.setReqTime(LocalDateTime.now());
        return transactionRecordRepository.save(record);
    }

    /** Stores a short reference for the captured image instead of the real bytes - see "Temporary assumptions" in IMPLEMENTATION_PROGRESS.md. */
    @Transactional
    public void recordCapturedImage(String referenceId, byte[] imageBytes) {
        transactionRecordRepository.findByReferenceId(referenceId).ifPresent(record -> {
            record.setFaceImageRef("captured:" + referenceId + ":" + imageBytes.length + "-bytes (mock local storage, no external file-storage service configured)");
            transactionRecordRepository.save(record);
        });
    }

    @Transactional
    public void persistFinalResult(VerificationSession session) {
        TransactionRecord record = transactionRecordRepository.findByReferenceId(session.getReferenceId())
                .orElseThrow(() -> new IllegalStateException("No transaction_record row found for referenceId=" + session.getReferenceId()));

        if (session.getComparisonResult() != null) {
            record.setMatch(session.getComparisonResult().match());
            record.setSimilarity(session.getComparisonResult().similarity());
            record.setCompareJson(session.getComparisonResult().rawJson());
        }
        if (session.getLivenessResult() != null) {
            record.setLivenessPassed(session.getLivenessResult().passed());
            record.setLivenessScore(session.getLivenessResult().confidence());
        }
        record.setLivenessSessionId(session.getLivenessSessionId());
        record.setState(session.getState().name());
        record.setErrorMessage(session.getErrorMessage());
        record.setResTime(LocalDateTime.now());
        transactionRecordRepository.save(record);
        log.info("[Result] Persisted final result referenceId={} state={}", session.getReferenceId(), session.getState());
    }

    /** Mirrors DatabaseWriteService.handleRemoveDbRecord - rolls back the incomplete record on hard failure. */
    @Transactional
    public void removeIncompleteRecord(String referenceId) {
        transactionRecordRepository.findByReferenceId(referenceId).ifPresent(transactionRecordRepository::delete);
    }
}
