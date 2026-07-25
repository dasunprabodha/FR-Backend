package lk.cf.fr.monolith.verification.service;

import lk.cf.fr.monolith.device.DeviceCommunicationException;
import lk.cf.fr.monolith.device.DeviceCommunicationService;
import lk.cf.fr.monolith.liveness.LivenessService;
import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lk.cf.fr.monolith.recognition.FaceRecognitionService;
import lk.cf.fr.monolith.registry.DeviceResolutionService;
import lk.cf.fr.monolith.registry.entity.DeviceRecord;
import lk.cf.fr.monolith.verification.dto.VerificationRequest;
import lk.cf.fr.monolith.verification.dto.VerificationResponse;
import lk.cf.fr.monolith.verification.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Orchestrates the verification path end-to-end in a single call stack, replacing the five
 * blocking Kafka request/reply hops traced in VERIFICATION_PATH_ARCHITECTURE.md section 3/§10:
 * Api-Gateway -> Transaction -> Device_Management -> Face_Recognition -> Transaction ->
 * Api-Gateway collapses to direct method calls on this service, its collaborators, and one
 * WebSocket round trip to the physical device (see section 23, "Target Sequence Diagram").
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VerificationService {

    private final DeviceResolutionService deviceResolutionService;
    private final RegistrationRecordRepository registrationRecordRepository;
    private final VerificationResultService verificationResultService;
    private final DeviceCommunicationService deviceCommunicationService;
    private final FaceRecognitionService faceRecognitionService;
    private final LivenessService livenessService;

    @Value("${verification.device-image-timeout-seconds:120}")
    private long imageTimeoutSeconds;

    @Value("${verification.liveness-wait-timeout-seconds:60}")
    private long livenessWaitTimeoutSeconds;

    private final Map<String, VerificationSession> activeSessions = new ConcurrentHashMap<>();

    public VerificationResponse startVerification(VerificationRequest request) {
        validateRequest(request);

        String referenceId = UUID.randomUUID().toString();
        VerificationSession session = null;

        try {
            DeviceRecord device = deviceResolutionService.resolveActiveDeviceOrFail(
                    request.getBranchId(), request.getDeviceNickname(), request.getUserId());
            ensureEnrollmentExists(request.getCifNo());

            session = new VerificationSession(referenceId, request.getNic(), request.getUserId(),
                    request.getBranchId(), request.getCifNo(), device.getDeviceId());
            activeSessions.put(referenceId, session);

            return runVerification(session, request);
        } catch (VerificationException e) {
            if (e.getResponse() == null) {
                if (session == null) {
                    // Failed before a session/DB row existed (device not found/inactive, no enrollment) ->
                    // nothing to roll back, just report the referenceId + reason.
                    e.setResponse(VerificationResponse.builder()
                            .referenceId(referenceId)
                            .status(false)
                            .overallSimilarityDecision("unsuccess")
                            .reason(e.getReason())
                            .build());
                } else {
                    // Failed after the pending row was created but before any comparison result existed
                    // (device unavailable/busy/timeout/etc.) -> roll back, mirroring
                    // Transaction.DatabaseWriteService.handleRemoveDbRecord.
                    session.setState(e.getState());
                    session.setErrorMessage(e.getReason());
                    verificationResultService.removeIncompleteRecord(referenceId);
                    e.setResponse(buildResponse(session, e.getReason()));
                }
            } else if (session != null) {
                session.setState(e.getState());
                session.setErrorMessage(e.getReason());
            }
            throw e;
        } catch (Exception e) {
            log.error("[Verification] Unexpected error referenceId={}", referenceId, e);
            VerificationResponse minimal;
            if (session != null) {
                session.setState(VerificationState.PROCESSING_ERROR);
                session.setErrorMessage(e.getMessage());
                verificationResultService.removeIncompleteRecord(referenceId);
                minimal = buildResponse(session, "An unexpected error occurred during verification.");
            } else {
                minimal = VerificationResponse.builder()
                        .referenceId(referenceId)
                        .status(false)
                        .overallSimilarityDecision("unsuccess")
                        .reason("An unexpected error occurred during verification.")
                        .build();
            }
            VerificationException wrapped = new VerificationException(VerificationState.PROCESSING_ERROR,
                    e.getMessage(), "An unexpected error occurred during verification.", e);
            wrapped.setResponse(minimal);
            throw wrapped;
        } finally {
            if (session != null) {
                deviceCommunicationService.releaseDevice(session.getDeviceId(), referenceId);
                activeSessions.remove(referenceId);
            }
        }
    }

    private VerificationResponse runVerification(VerificationSession session, VerificationRequest request) throws Exception {
        verificationResultService.createPending(session);

        LivenessSession livenessSession = livenessService.createSession();
        session.setLivenessSessionId(livenessSession.sessionId());

        session.setState(VerificationState.DEVICE_REQUESTED);
        var imageFuture = requestCaptureOrFail(session, request.getPrefLang());
        deviceCommunicationService.notifyLivenessSessionCreated(
                session.getDeviceId(), session.getReferenceId(), session.getLivenessSessionId());
        var livenessCompleteFuture = deviceCommunicationService.awaitLivenessCompletion(session.getReferenceId());

        byte[] imageBytes = awaitImage(imageFuture);
        session.setState(VerificationState.CAPTURING);
        verificationResultService.recordCapturedImage(session.getReferenceId(), imageBytes);

        session.setState(VerificationState.PROCESSING);
        ComparisonResult comparisonResult = faceRecognitionService.compareFaces(
                session.getNic(), imageBytes, request.getMockSimilarity());
        session.setComparisonResult(comparisonResult);

        awaitLivenessSignal(livenessCompleteFuture);
        LivenessOutcome livenessOutcome = livenessService.getResults(
                session.getLivenessSessionId(), request.getMockLivenessPassed());
        session.setLivenessResult(livenessOutcome);

        VerificationState finalState = determineFinalState(comparisonResult, livenessOutcome);
        session.setState(finalState);
        verificationResultService.persistFinalResult(session);

        String reason = switch (finalState) {
            case FACE_NOT_MATCHED -> "Faces are not matching.";
            case LIVENESS_FAILED -> "Liveness verification failed.";
            default -> null;
        };
        VerificationResponse response = buildResponse(session, reason);

        if (finalState != VerificationState.VERIFIED) {
            VerificationException notVerified = new VerificationException(finalState, reason, reason);
            notVerified.setResponse(response);
            throw notVerified;
        }
        return response;
    }

    private void validateRequest(VerificationRequest request) {
        requireNonBlank(request.getNic(), "nic");
        requireNonBlank(request.getUserId(), "userId");
        requireNonBlank(request.getBranchId(), "branchId");
        requireNonBlank(request.getDeviceNickname(), "deviceNickname");
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The field '" + field + "' is required.");
        }
    }

    /**
     * Mirrors Transaction.DatabaseListenerService.handleCreateValidation: verification requires
     * a pre-existing enrollment record for the customer, approved and active.
     */
    private void ensureEnrollmentExists(String cifNo) {
        if (cifNo == null || cifNo.isBlank()) {
            throw new VerificationException(VerificationState.FAILED,
                    "cifNo is required to look up an existing enrollment",
                    "No record found for this NIC. Please register the customer first.");
        }
        List<RegistrationRecord> records = registrationRecordRepository.findByCifNo(cifNo);
        boolean approvedAndActive = records.stream().anyMatch(r ->
                r.getStatus() != null
                        && (r.getStatus().equalsIgnoreCase("APPROVED") || r.getStatus().equalsIgnoreCase("AWS_APPROVED"))
                        && "ACTIVE".equalsIgnoreCase(r.getActiveStatus()));
        if (!approvedAndActive) {
            throw new VerificationException(VerificationState.FAILED,
                    "No approved+active enrollment found for cifNo=" + cifNo,
                    "No record found for this NIC. Please register the customer first.");
        }
    }

    private CompletableFuture<byte[]> requestCaptureOrFail(VerificationSession session, String prefLang) {
        try {
            return deviceCommunicationService.requestCapture(session.getDeviceId(), session.getReferenceId(),
                    session.getNic(), session.getUserId(), session.getBranchId(), "verify", "faceImage", prefLang);
        } catch (DeviceCommunicationException e) {
            throw translateDeviceException(e);
        }
    }

    private VerificationException translateDeviceException(DeviceCommunicationException e) {
        return switch (e.getReason()) {
            case DEVICE_UNAVAILABLE -> new VerificationException(VerificationState.DEVICE_UNAVAILABLE,
                    e.getMessage(), "Device is not connected.");
            case DEVICE_BUSY -> new VerificationException(VerificationState.DEVICE_BUSY,
                    e.getMessage(), "Device is busy. Please try again.");
        };
    }

    private byte[] awaitImage(CompletableFuture<byte[]> imageFuture) {
        try {
            return imageFuture.get(imageTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new VerificationException(VerificationState.TIMEOUT,
                    "Timed out waiting for the device to capture an image",
                    "Camera timeout - no face detected within " + imageTimeoutSeconds + " seconds.");
        } catch (ExecutionException e) {
            String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            String reason = "USER_PRESSED_BACK".equals(cause)
                    ? "Verification cancelled by user."
                    : "Camera timeout - no face detected.";
            throw new VerificationException(VerificationState.CAMERA_ERROR, "Camera error: " + cause, reason, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerificationException(VerificationState.PROCESSING_ERROR,
                    "Interrupted while waiting for the device image", "Verification interrupted.", e);
        }
    }

    private void awaitLivenessSignal(CompletableFuture<Void> livenessCompleteFuture) {
        try {
            livenessCompleteFuture.get(livenessWaitTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new VerificationException(VerificationState.TIMEOUT,
                    "Timed out waiting for the device to report liveness completion",
                    "Liveness challenge timed out.");
        } catch (Exception e) {
            throw new VerificationException(VerificationState.PROCESSING_ERROR,
                    "Error waiting for liveness completion signal", "Verification interrupted.", e);
        }
    }

    private VerificationState determineFinalState(ComparisonResult comparison, LivenessOutcome liveness) {
        if (!comparison.match()) return VerificationState.FACE_NOT_MATCHED;
        if (!liveness.passed()) return VerificationState.LIVENESS_FAILED;
        return VerificationState.VERIFIED;
    }

    private VerificationResponse buildResponse(VerificationSession session, String reason) {
        ComparisonResult comparison = session.getComparisonResult();
        LivenessOutcome liveness = session.getLivenessResult();
        boolean success = session.getState() == VerificationState.VERIFIED;

        return VerificationResponse.builder()
                .referenceId(session.getReferenceId())
                .status(success)
                .overallSimilarityDecision(success ? "success" : "unsuccess")
                .faceVsFaceMatch(comparison != null ? comparison.match() : null)
                .faceVsFaceSimilarityScore(comparison != null ? comparison.similarity() : null)
                .livenessPassed(liveness != null ? liveness.passed() : null)
                .livenessScore(liveness != null ? liveness.confidence() : null)
                .livenessSessionId(session.getLivenessSessionId())
                .message(success ? "Verification successful." : null)
                .reason(reason)
                .build();
    }
}
