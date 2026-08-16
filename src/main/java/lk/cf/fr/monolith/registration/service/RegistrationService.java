package lk.cf.fr.monolith.registration.service;

import lk.cf.fr.monolith.card.CardDetectorService;
import lk.cf.fr.monolith.device.DeviceCommunicationException;
import lk.cf.fr.monolith.device.DeviceCommunicationService;
import lk.cf.fr.monolith.document.DocumentProcessingService;
import lk.cf.fr.monolith.document.NicValidationOutcome;
import lk.cf.fr.monolith.liveness.LivenessService;
import lk.cf.fr.monolith.recognition.FaceRecognitionService;
import lk.cf.fr.monolith.storage.ImageStorageException;
import lk.cf.fr.monolith.storage.LocalPendingImageStorageService;
import lk.cf.fr.monolith.registration.model.RegistrationApprovalStatus;
import lk.cf.fr.monolith.registry.DeviceResolutionService;
import lk.cf.fr.monolith.registry.entity.DeviceRecord;
import lk.cf.fr.monolith.registration.dto.RegistrationRequest;
import lk.cf.fr.monolith.registration.dto.RegistrationResponse;
import lk.cf.fr.monolith.registration.model.RegistrationException;
import lk.cf.fr.monolith.registration.model.RegistrationSession;
import lk.cf.fr.monolith.registration.model.RegistrationState;
import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import lk.cf.fr.monolith.verification.model.LivenessSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates the registration path end-to-end in a single call stack, replacing the Kafka
 * request/reply chain traced in REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3/§11: Api-Gateway ->
 * Transaction -> Device_Management (x3 captures + NIC-retry loop) -> Face_Recognition (OCR, 4x
 * compare, liveness) -> Transaction -> Api-Gateway collapses to direct method calls on this
 * service, its collaborators, and three sequential WebSocket round trips to the physical device
 * (see §24, "Target Sequence Diagram").
 *
 * <p>Unlike {@code VerificationService}, a face/liveness mismatch does NOT throw here - it is
 * just data in a normal 200 response, matching the legacy {@code GatewayController.forwardFaceRecognition}'s
 * own behavior (§3.5 point 2: that endpoint only checks the reply's {@code status} field, which
 * {@code finalizeAndPublish} sets to "success" on any completed run regardless of match/liveness
 * outcome). {@link RegistrationException} is reserved for genuine processing failures (device
 * unavailable/busy, camera error, NIC document invalid after retries, timeout, unexpected error).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RegistrationService {

    private final DeviceResolutionService deviceResolutionService;
    private final RegistrationResultService registrationResultService;
    private final DeviceCommunicationService deviceCommunicationService;
    private final DocumentProcessingService documentProcessingService;
    private final FaceRecognitionService faceRecognitionService;
    private final LivenessService livenessService;
    private final LocalPendingImageStorageService localPendingImageStorageService;
    private final RegistrationFinalizationService registrationFinalizationService;
    private final ComparisonImageDumpService comparisonImageDumpService;
    private final CardDetectorService cardDetectorService;

    @Value("${registration.device-image-timeout-seconds:120}")
    private long captureTimeoutSeconds;

    @Value("${registration.liveness-wait-timeout-seconds:60}")
    private long livenessWaitTimeoutSeconds;

    @Value("${verification.similarity-threshold:80}")
    private double similarityThreshold;

    @Value("${verification.liveness-confidence-threshold:65}")
    private double livenessConfidenceThreshold;

    private final Map<String, RegistrationSession> activeSessions = new ConcurrentHashMap<>();

    public RegistrationResponse startRegistration(RegistrationRequest request, byte[] scannedNicBytes) {
        validateRequest(request);

        String referenceId = UUID.randomUUID().toString();
        RegistrationSession session = null;

        try {
            DeviceRecord device = deviceResolutionService.resolveActiveDeviceOrFail(
                    request.getBranchId(), request.getDeviceNickname(), request.getUserId());

            registrationResultService.createOrSupersede(request, referenceId);

            session = new RegistrationSession(referenceId, request.getNic(), request.getUserId(),
                    request.getCifNo(), request.getBranchId(), device.getDeviceId());
            activeSessions.put(referenceId, session);

            return runRegistration(session, request, scannedNicBytes);
        } catch (RegistrationException e) {
            if (e.getResponse() == null) {
                if (session == null) {
                    e.setResponse(RegistrationResponse.builder()
                            .referenceId(referenceId)
                            .status(false)
                            .overallSimilarityDecision("unsuccess")
                            .reason(e.getReason())
                            .build());
                } else {
                    session.setState(e.getState());
                    session.setErrorMessage(e.getReason());
                    registrationResultService.removeIncompleteRecord(referenceId);
                    localPendingImageStorageService.deleteAll(referenceId);
                    e.setResponse(buildErrorResponse(session, e.getReason()));
                }
            }
            throw e;
        } catch (Exception e) {
            log.error("[Registration] Unexpected error referenceId={}", referenceId, e);
            RegistrationResponse minimal;
            if (session != null) {
                session.setState(RegistrationState.PROCESSING_ERROR);
                session.setErrorMessage(e.getMessage());
                registrationResultService.removeIncompleteRecord(referenceId);
                localPendingImageStorageService.deleteAll(referenceId);
                minimal = buildErrorResponse(session, "An unexpected error occurred during registration.");
            } else {
                minimal = RegistrationResponse.builder()
                        .referenceId(referenceId)
                        .status(false)
                        .overallSimilarityDecision("unsuccess")
                        .reason("An unexpected error occurred during registration.")
                        .build();
            }
            RegistrationException wrapped = new RegistrationException(RegistrationState.PROCESSING_ERROR,
                    e.getMessage(), "An unexpected error occurred during registration.", e);
            wrapped.setResponse(minimal);
            throw wrapped;
        } finally {
            if (session != null) {
                deviceCommunicationService.releaseDevice(session.getDeviceId(), referenceId);
                activeSessions.remove(referenceId);
            }
        }
    }

    private RegistrationResponse runRegistration(RegistrationSession session, RegistrationRequest request, byte[] scannedNicBytes) {
        LivenessSession livenessSession = livenessService.createSession();
        session.setLivenessSessionId(livenessSession.sessionId());
        deviceCommunicationService.notifyLivenessSessionCreated(
                session.getDeviceId(), session.getReferenceId(), session.getLivenessSessionId());
        CompletableFuture<Void> livenessCompleteFuture = deviceCommunicationService.awaitLivenessCompletion(session.getReferenceId());

        session.setState(RegistrationState.DEVICE_REQUESTED);
        session.setState(RegistrationState.DOCUMENT_CAPTURE);
        byte[] nicImage = capture(session, "nicImage", request.getPrefLang());
        session.setState(RegistrationState.DOCUMENT_VALIDATED);

        validateScannedNic(session, request, scannedNicBytes);
        // NicWaitingActivity on the device blocks on exactly this message shape (see
        // DeviceCommunicationService#notifyNicCheckResult) - without it the device sits until its
        // own 4-minute client-side timeout even though the backend has already moved on. Fired
        // here (VALID/NOT_PROVIDED both count as "valid" from the device's perspective) since
        // validateScannedNic() only returns normally in those two cases; the DOCUMENT_INVALID
        // case is notified from within validateScannedNic() itself before it throws.
        deviceCommunicationService.notifyNicCheckResult(session.getDeviceId(), session.getReferenceId(), true);

        session.setState(RegistrationState.FACE_CAPTURE);
        byte[] faceImage = capture(session, "faceImage", request.getPrefLang());
        byte[] selfImage = capture(session, "selfImage", request.getPrefLang());

        // Written to local disk (not just referenced) so a PENDING_APPROVAL record's images survive
        // past this request for the Approval Dashboard to review - see LocalPendingImageStorageService.
        String nicImagePath = localPendingImageStorageService.save(session.getReferenceId(), "nicImage", nicImage);
        String faceImagePath = localPendingImageStorageService.save(session.getReferenceId(), "faceImage", faceImage);
        String selfImagePath = localPendingImageStorageService.save(session.getReferenceId(), "selfImage", selfImage);
        registrationResultService.persistImages(session.getReferenceId(), nicImagePath, faceImagePath, selfImagePath);

        session.setState(RegistrationState.LIVENESS_PROCESSING);
        awaitLivenessSignal(livenessCompleteFuture);
        LivenessOutcome liveness = livenessService.getResults(session.getLivenessSessionId(), request.getMockLivenessPassed());

        session.setState(RegistrationState.FACE_PROCESSING);

        // Crop the physical card region out of nicImage/selfImage before comparing - fixes a real
        // failure mode where the device-captured nicImage frames the user's live face more
        // prominently than the card (user holding the NIC up close to the camera), causing
        // Rekognition to compare against the live face instead of the small printed photo on the
        // card. cmp2 ("Device NIC vs Self NIC") is specifically a card-vs-card comparison, so both
        // sides are cropped to their NIC region; cmp3 (face vs self face) intentionally keeps using
        // the full uncropped selfImage since it needs the live face there, not the card. Falls back
        // to the original uncropped bytes whenever no card is detected, so a missed detection never
        // blocks registration - it just reverts to today's (pre-crop) behavior for that attempt.
        byte[] nicCardCrop = cardDetectorService.cropCard(nicImage, "DeviceNIC", session.getReferenceId());
        byte[] nicForComparison = nicCardCrop != null ? nicCardCrop : nicImage;
        log.info("[Registration][Card-Crop] referenceId={} DeviceNIC crop {} ({} bytes -> {} bytes)",
                session.getReferenceId(), nicCardCrop != null ? "succeeded" : "FAILED (falling back to full image)",
                nicImage.length, nicForComparison.length);

        byte[] selfNicCardCrop = cardDetectorService.cropCard(selfImage, "SelfNIC", session.getReferenceId());
        byte[] selfNicForComparison = selfNicCardCrop != null ? selfNicCardCrop : selfImage;
        log.info("[Registration][Card-Crop] referenceId={} SelfNIC crop {} ({} bytes -> {} bytes)",
                session.getReferenceId(), selfNicCardCrop != null ? "succeeded" : "FAILED (falling back to full image)",
                selfImage.length, selfNicForComparison.length);

        ComparisonResult cmp1 = faceRecognitionService.compareFacesInMemory(nicForComparison, faceImage, request.getMockSimilarity());
        comparisonImageDumpService.dump(session.getReferenceId(), "cmp1-deviceNicVsFace", nicForComparison, faceImage, cmp1);

        ComparisonResult cmp2 = faceRecognitionService.compareFacesInMemory(nicForComparison, selfNicForComparison, request.getMockSimilarity());
        comparisonImageDumpService.dump(session.getReferenceId(), "cmp2-deviceNicVsSelfNic", nicForComparison, selfNicForComparison, cmp2);

        ComparisonResult cmp3 = faceRecognitionService.compareFacesInMemory(faceImage, selfImage, request.getMockSimilarity());
        comparisonImageDumpService.dump(session.getReferenceId(), "cmp3-faceVsSelfFace", faceImage, selfImage, cmp3);

        ComparisonResult cmp4 = scannedNicBytes != null
                ? faceRecognitionService.compareFacesInMemory(scannedNicBytes, faceImage, request.getMockSimilarity())
                : null;
        if (cmp4 != null) {
            comparisonImageDumpService.dump(session.getReferenceId(), "cmp4-scannedNicVsFace", scannedNicBytes, faceImage, cmp4);
        }

        // Gate mirrors legacy exactly: comparison 1 (device NIC vs face) is informational only and
        // excluded from the pass/fail gate - see REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.4
        // ("allPassed = sim2>=cutoff && sim3>=cutoff && sim4>=cutoff"). Deviation from legacy:
        // comparison 4 is only required in the gate if a scannedNIC file was actually supplied,
        // since legacy's hard requirement of it (throwing when absent) contradicts the same
        // document's own description of scannedNIC as optional - see
        // REGISTRATION_IMPLEMENTATION_PROGRESS.md "Temporary assumptions". Liveness is now also part
        // of the gate (Registration Approval Workflow): it used to be computed but never checked here.
        boolean similarityPassed = cmp2.match() && cmp3.match() && (cmp4 == null || cmp4.match());
        boolean allPassed = similarityPassed && liveness.passed();
        String registrationStatus = allPassed
                ? RegistrationApprovalStatus.AWS_APPROVED.name()
                : RegistrationApprovalStatus.PENDING_APPROVAL.name();
        String overallDecision = allPassed ? "success" : "unsuccess";
        String failureReason = allPassed ? null : buildFailureReason(similarityPassed, liveness.passed());

        session.setState(RegistrationState.REGISTRATION_PROCESSING);
        registrationResultService.persistResult(session.getReferenceId(), cmp1, cmp2, cmp3, cmp4, liveness,
                session.getValidNicStatus(), registrationStatus, similarityThreshold, livenessConfidenceThreshold, failureReason);

        boolean pendingApproval = !allPassed;
        String message;
        if (allPassed) {
            // S3 hiccups here are logged-and-continued rather than failing the response, preserving
            // S3FaceImageStorageService's original swallow behavior - see its class doc.
            try {
                var record = registrationResultService.getByReferenceId(session.getReferenceId());
                registrationFinalizationService.finalizeApproved(record, nicImage, faceImage, selfImage);
            } catch (ImageStorageException e) {
                log.error("[Registration] Image upload failed after auto-pass referenceId={} - continuing anyway", session.getReferenceId(), e);
            }
            message = "Registration completed.";
        } else {
            message = "Registration pending manual approval.";
        }

        session.setState(RegistrationState.COMPLETED);

        return RegistrationResponse.builder()
                .referenceId(session.getReferenceId())
                .status(true)
                .overallSimilarityDecision(overallDecision)
                .pendingApproval(pendingApproval)
                .deviceNicVsFaceMatch(cmp1.match())
                .deviceNicVsFaceSimilarityScore(cmp1.similarity())
                .deviceNicVsSelfNicMatch(cmp2.match())
                .deviceNicVsSelfNicSimilarityScore(cmp2.similarity())
                .faceVsSelfFaceMatch(cmp3.match())
                .faceVsSelfFaceSimilarityScore(cmp3.similarity())
                .scannedNicVsFaceMatch(cmp4 != null ? cmp4.match() : null)
                .scannedNicVsFaceSimilarityScore(cmp4 != null ? cmp4.similarity() : null)
                .livenessPassed(liveness.passed())
                .livenessScore(liveness.confidence())
                .livenessSessionId(session.getLivenessSessionId())
                .validNicStatus(session.getValidNicStatus())
                .message(message)
                .build();
    }

    private String buildFailureReason(boolean similarityPassed, boolean livenessPassed) {
        StringBuilder reason = new StringBuilder();
        if (!similarityPassed) {
            reason.append("LOW_SIMILARITY");
        }
        if (!livenessPassed) {
            if (reason.length() > 0) {
                reason.append(",");
            }
            reason.append("LIVENESS_FAILED");
        }
        return reason.toString();
    }

    private void validateRequest(RegistrationRequest request) {
        requireNonBlank(request.getNic(), "nic");
        requireNonBlank(request.getUserId(), "userId");
        requireNonBlank(request.getBranchId(), "branchId");
        requireNonBlank(request.getDeviceNickname(), "deviceNickname");
        requireNonBlank(request.getCifNo(), "cifNo");
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The field '" + field + "' is required.");
        }
    }

    /**
     * OCR/document validation now runs only against the optionally-uploaded {@code scannedNIC}
     * file, not the live device-captured {@code nicImage} - the device capture is used solely for
     * face comparisons 1/2. No-op (leaves {@code validNicStatus} as {@code NOT_PROVIDED}) when no
     * file was uploaded, since scannedNIC is optional.
     */
    private void validateScannedNic(RegistrationSession session, RegistrationRequest request, byte[] scannedNicBytes) {
        if (scannedNicBytes == null) {
            log.info("[Registration][NIC-Check] referenceId={} nic={} no scannedNIC file uploaded -> NOT_PROVIDED (skipping OCR)",
                    session.getReferenceId(), session.getNic());
            session.setValidNicStatus("NOT_PROVIDED");
            return;
        }
        log.info("[Registration][NIC-Check] referenceId={} nic={} scannedNIC uploaded ({} bytes) - running OCR validation",
                session.getReferenceId(), session.getNic(), scannedNicBytes.length);
        NicValidationOutcome outcome = documentProcessingService.validateNic(scannedNicBytes, request.getMockNicValid());
        log.info("[Registration][NIC-Check] referenceId={} nic={} OCR outcome={}", session.getReferenceId(), session.getNic(), outcome);
        session.setValidNicStatus(outcome.name());
        if (outcome != NicValidationOutcome.VALID) {
            log.warn("[Registration][NIC-Check] referenceId={} nic={} rejecting registration - scanned document did not classify as a valid NIC (outcome={})",
                    session.getReferenceId(), session.getNic(), outcome);
            deviceCommunicationService.notifyNicCheckResult(session.getDeviceId(), session.getReferenceId(), false);
            throw new RegistrationException(RegistrationState.DOCUMENT_INVALID,
                    "Scanned NIC/document validation failed: " + outcome,
                    "Could not validate the uploaded National Identity Card/Document. Please upload a clearer scan.");
        }
    }

    private byte[] capture(RegistrationSession session, String tag, String prefLang) {
        CompletableFuture<byte[]> future;
        try {
            future = deviceCommunicationService.requestCapture(session.getDeviceId(), session.getReferenceId(),
                    session.getNic(), session.getUserId(), session.getBranchId(), "registration", tag, prefLang);
        } catch (DeviceCommunicationException e) {
            throw translateDeviceException(e);
        }

        try {
            return future.get(captureTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RegistrationException(RegistrationState.TIMEOUT,
                    "Timed out waiting for device to capture " + tag,
                    "Camera timeout - no face/document detected within " + captureTimeoutSeconds + " seconds (tag=" + tag + ").");
        } catch (ExecutionException e) {
            String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            String reason = "USER_PRESSED_BACK".equals(cause)
                    ? "Registration cancelled by user (tag=" + tag + ")."
                    : "Camera error while capturing " + tag + ".";
            throw new RegistrationException(RegistrationState.CAMERA_ERROR, "Camera error capturing " + tag + ": " + cause, reason, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegistrationException(RegistrationState.PROCESSING_ERROR,
                    "Interrupted while waiting for " + tag, "Registration interrupted.", e);
        }
    }

    private void awaitLivenessSignal(CompletableFuture<Void> livenessCompleteFuture) {
        try {
            livenessCompleteFuture.get(livenessWaitTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RegistrationException(RegistrationState.TIMEOUT,
                    "Timed out waiting for the device to report liveness completion",
                    "Liveness challenge timed out.");
        } catch (Exception e) {
            throw new RegistrationException(RegistrationState.PROCESSING_ERROR,
                    "Error waiting for liveness completion signal", "Registration interrupted.", e);
        }
    }

    private RegistrationException translateDeviceException(DeviceCommunicationException e) {
        return switch (e.getReason()) {
            case DEVICE_UNAVAILABLE -> new RegistrationException(RegistrationState.DEVICE_UNAVAILABLE,
                    e.getMessage(), "Device is not connected.");
            case DEVICE_BUSY -> new RegistrationException(RegistrationState.DEVICE_BUSY,
                    e.getMessage(), "Device is busy. Please try again.");
        };
    }

    private RegistrationResponse buildErrorResponse(RegistrationSession session, String reason) {
        return RegistrationResponse.builder()
                .referenceId(session.getReferenceId())
                .status(false)
                .overallSimilarityDecision("unsuccess")
                .validNicStatus(session.getValidNicStatus())
                .livenessSessionId(session.getLivenessSessionId())
                .reason(reason)
                .build();
    }
}
