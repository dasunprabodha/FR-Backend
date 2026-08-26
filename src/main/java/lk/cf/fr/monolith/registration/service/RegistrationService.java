package lk.cf.fr.monolith.registration.service;

import lk.cf.fr.monolith.analysis.RegistrationAnalysisService;
import lk.cf.fr.monolith.device.DeviceCommunicationException;
import lk.cf.fr.monolith.device.DeviceCommunicationService;
import lk.cf.fr.monolith.document.NicValidationOutcome;
import lk.cf.fr.monolith.identity.IdentityBindingResult;
import lk.cf.fr.monolith.liveness.LivenessService;
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
    private final LivenessService livenessService;
    private final LocalPendingImageStorageService localPendingImageStorageService;
    private final RegistrationFinalizationService registrationFinalizationService;
    /** Document OCR, identity binding, card cropping, the four comparisons and the gate - shared with the offline batch harness. */
    private final RegistrationAnalysisService registrationAnalysisService;

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

        // Card cropping + the four pairwise comparisons now live in RegistrationAnalysisService so
        // the identical code can also be driven offline from stored images by the batch harness -
        // see that class for the cropping rationale and the ordering constraint that keeps OCR
        // where it is above rather than folding it in here.
        RegistrationAnalysisService.FaceAnalysis faces = registrationAnalysisService.analyseFaces(
                session.getReferenceId(), nicImage, faceImage, selfImage, scannedNicBytes, request.getMockSimilarity());

        ComparisonResult cmp1 = faces.cmp1();
        ComparisonResult cmp2 = faces.cmp2();
        ComparisonResult cmp3 = faces.cmp3();
        ComparisonResult cmp4 = faces.cmp4();

        // Gate mirrors legacy exactly: comparison 1 (device NIC vs face) is informational only and
        // excluded from the pass/fail gate - see REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.4
        // ("allPassed = sim2>=cutoff && sim3>=cutoff && sim4>=cutoff"). Deviation from legacy:
        // comparison 4 is only required in the gate if a scannedNIC file was actually supplied,
        // since legacy's hard requirement of it (throwing when absent) contradicts the same
        // document's own description of scannedNIC as optional - see
        // REGISTRATION_IMPLEMENTATION_PROGRESS.md "Temporary assumptions". Liveness is now also part
        // of the gate (Registration Approval Workflow): it used to be computed but never checked here.
        //
        // NOTE: identity binding (the OCR'd NIC number vs. the claimed NIC) is computed and
        // persisted below but deliberately NOT part of this gate, so the decision behaviour stays
        // byte-identical to the frozen baseline it is meant to be compared against.
        RegistrationAnalysisService.GateResult gate = registrationAnalysisService.evaluateGate(faces, liveness);
        boolean allPassed = Boolean.TRUE.equals(gate.allPassed());
        String registrationStatus = allPassed
                ? RegistrationApprovalStatus.AWS_APPROVED.name()
                : RegistrationApprovalStatus.PENDING_APPROVAL.name();
        String overallDecision = allPassed ? "success" : "unsuccess";
        String failureReason = gate.failureReason();

        RegistrationAnalysisService.DocumentAnalysis document = session.getDocumentAnalysis();
        IdentityBindingResult binding = document != null ? document.binding() : null;
        if (binding != null) {
            log.info("[Registration][NIC-Binding] referenceId={} claimed={} extracted={} outcome={} score={} - evidence only, not gated",
                    session.getReferenceId(), binding.claimed(), binding.extracted(), binding.outcome(), binding.score());
        }

        if (faces.consistency() != null && faces.consistency().isDivergent()) {
            log.warn("[Registration][Cross-Channel] referenceId={} the presented card and the uploaded scan disagree "
                    + "about the live face - possible document substitution. Evidence only, not gated. {}",
                    session.getReferenceId(), faces.consistency().detail());
        }

        session.setState(RegistrationState.REGISTRATION_PROCESSING);
        registrationResultService.persistResult(session.getReferenceId(), faces, liveness,
                session.getValidNicStatus(), registrationStatus, similarityThreshold, livenessConfidenceThreshold,
                failureReason, document != null ? document.ocr() : null, binding);

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
                // Comparison 5 + the derived channel-agreement signal. Additive, non-gated.
                .scannedNicVsDeviceNicMatch(faces.cmp5() != null ? faces.cmp5().match() : null)
                .scannedNicVsDeviceNicSimilarityScore(faces.cmp5() != null ? faces.cmp5().similarity() : null)
                .crossChannelStatus(faces.consistency() != null ? faces.consistency().status() : null)
                .crossChannelDelta(faces.consistency() != null ? faces.consistency().delta() : null)
                .livenessPassed(liveness.passed())
                .livenessScore(liveness.confidence())
                .livenessSessionId(session.getLivenessSessionId())
                .validNicStatus(session.getValidNicStatus())
                // Additive identity-binding evidence. Existing clients (Angular console, Android)
                // ignore unknown JSON fields, so surfacing these breaks nothing.
                .extractedNicNumber(document != null ? document.ocr().extractedNicNumber() : null)
                .nicBindingOutcome(binding != null ? binding.outcome().name() : null)
                .nicBindingScore(binding != null ? binding.score() : null)
                .nicBindingDetail(binding != null ? binding.detail() : null)
                .message(message)
                .build();
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
        RegistrationAnalysisService.DocumentAnalysis document = registrationAnalysisService.analyseDocument(
                session.getReferenceId(), session.getNic(), scannedNicBytes, request.getMockNicValid());

        session.setDocumentAnalysis(document);
        session.setValidNicStatus(document.ocr().statusName());

        if (scannedNicBytes == null) {
            return;
        }

        NicValidationOutcome outcome = document.ocr().outcome();
        log.info("[Registration][NIC-Check] referenceId={} nic={} OCR outcome={} extractedNic={} binding={}",
                session.getReferenceId(), session.getNic(), outcome,
                document.ocr().extractedNicNumber(), document.binding().outcome());

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
