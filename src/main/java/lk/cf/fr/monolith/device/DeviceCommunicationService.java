package lk.cf.fr.monolith.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces the Kafka-fronted Device_Management module's device-communication responsibilities
 * for BOTH the verification and registration paths: builds and sends the "open-camera" command
 * over the device's existing WebSocket session, and resolves the pending capture/liveness
 * futures when the device replies. Ported in spirit from cf-fr-server
 * Device_Management/ws/CaptureBroker.java and services/ListenerService (handleWithdrawal +
 * handleRegistration share this exact mechanism in the legacy code too).
 *
 * <p>Deliberately session-agnostic (keyed only by {@code referenceId}, not by a
 * VerificationSession/RegistrationSession object) so it can be shared without duplication -
 * see VERIFICATION_PATH_ARCHITECTURE.md §18 / REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §18's
 * explicit recommendation that {@code device}/{@code recognition}/{@code liveness} be single,
 * shared packages rather than duplicated per path.
 *
 * <p>Simplification vs. legacy: correlation is done purely by {@code referenceId} (one pending
 * capture in flight per referenceId at a time) instead of the legacy's
 * {@code nic|deviceId|flow|tag} composite key. This is safe because both paths' orchestrators
 * only ever have one outstanding capture per referenceId at a time - registration's three tags
 * (nicImage/faceImage/selfImage) are requested sequentially, matching the legacy orchestration's
 * own sequencing (see REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.3).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeviceCommunicationService {

    private static final String CLIENT_TYPE = "fr";

    private final DeviceSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    private final Map<String, CompletableFuture<byte[]>> pendingImageByReferenceId = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingLivenessByReferenceId = new ConcurrentHashMap<>();

    /**
     * Sends the "open-camera" command to the device for the given capture tag and returns a
     * future that resolves with the captured image bytes (or fails with a camera/device error).
     * Replaces any previously pending capture for this referenceId (registration issues this
     * sequentially for nicImage, then faceImage, then selfImage).
     *
     * <p>This overload emits the historical payload with no {@code mode} key at all, and is what
     * the verification path calls - keeping that path's wire format byte-identical to what it was
     * before capture modes existed.
     */
    public CompletableFuture<byte[]> requestCapture(String deviceId, String referenceId, String nic, String userId,
                                                      String branchId, String flow, String tag, String prefLang) {
        return requestCapture(deviceId, referenceId, nic, userId, branchId, flow, tag, prefLang, null);
    }

    /**
     * As above, plus the registration-only {@code mode} field ("Auto"/"Manual"), which tells the
     * device whether to capture automatically or to show its manual capture button for this step.
     *
     * <p>{@code mode} is only written into the payload when non-null, so no unrelated device
     * command ever gains a {@code "mode"} key (not even {@code "mode": null}) - the isolation
     * requirement in §11 of the change brief. Only {@code RegistrationService} passes a value.
     */
    public CompletableFuture<byte[]> requestCapture(String deviceId, String referenceId, String nic, String userId,
                                                      String branchId, String flow, String tag, String prefLang,
                                                      String mode) {
        if (!sessionRegistry.isOnline(deviceId, CLIENT_TYPE)) {
            throw deviceUnavailable(deviceId);
        }

        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingImageByReferenceId.put(referenceId, future);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nic", nic);
        data.put("userId", userId);
        data.put("flow", flow);
        data.put("tag", tag);
        data.put("referenceId", referenceId);
        data.put("branchId", branchId);
        data.put("deviceId", deviceId);
        data.put("reverifyTag", null);
        data.put("prefLang", prefLang);
        if (mode != null) {
            data.put("mode", mode);
            log.info("[Device] open-camera payload built with mode={} flow={} tag={} referenceId={}",
                    mode, flow, tag, referenceId);
        }

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("command", "open-camera");
        command.put("data", data);

        sendOrFail(deviceId, command, referenceId);
        return future;
    }

    /** Best-effort notification so the device can display the AWS liveness challenge session it should join. */
    public void notifyLivenessSessionCreated(String deviceId, String referenceId, String livenessSessionId) {
        if (!sessionRegistry.isOnline(deviceId, CLIENT_TYPE)) {
            log.warn("[Device] Could not notify liveness-session-created, device {} offline", deviceId);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", livenessSessionId);
        data.put("referenceId", referenceId);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "liveness-session-created");
        message.put("data", data);

        try {
            sessionRegistry.send(deviceId, CLIENT_TYPE, objectMapper.writeValueAsString(message), referenceId);
        } catch (Exception e) {
            log.warn("[Device] Failed to send liveness-session-created to {}: {}", deviceId, e.toString());
        }
    }

    /**
     * Best-effort notification so the device's NicWaitingActivity (which blocks on exactly this
     * message shape) learns the outcome of the server-side NIC/document OCR check, instead of
     * sitting until its own 4-minute client-side timeout. The device has no other way to learn
     * this - unlike the legacy Kafka pipeline, this monolith's registration result is otherwise
     * only returned via the synchronous HTTP response to whichever caller invoked
     * POST /api/facial-auth, not pushed back to the capture device itself.
     */
    public void notifyNicCheckResult(String deviceId, String referenceId, boolean valid) {
        if (!sessionRegistry.isOnline(deviceId, CLIENT_TYPE)) {
            log.warn("[Device] Could not notify nic-check-result, device {} offline", deviceId);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", valid ? "valid" : "invalid");
        data.put("referenceId", referenceId);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "nic-check-result");
        message.put("data", data);

        try {
            sessionRegistry.send(deviceId, CLIENT_TYPE, objectMapper.writeValueAsString(message), referenceId);
        } catch (Exception e) {
            log.warn("[Device] Failed to send nic-check-result to {}: {}", deviceId, e.toString());
        }
    }

    /**
     * Returns a future that resolves once the device reports its on-device liveness challenge
     * has finished. Safe to call before or after the device's message actually arrives - whichever
     * happens first creates the shared future (see {@link #onLivenessComplete}).
     */
    public CompletableFuture<Void> awaitLivenessCompletion(String referenceId) {
        return pendingLivenessByReferenceId.computeIfAbsent(referenceId, k -> new CompletableFuture<>());
    }

    /** Called by {@link DeviceWebSocketHandler} when the device replies with the captured image. */
    void onImageReceived(String referenceId, String base64Content) {
        CompletableFuture<byte[]> future = pendingImageByReferenceId.get(referenceId);
        if (future == null) {
            log.warn("[Device] Received image for unknown/expired referenceId={}", referenceId);
            return;
        }
        try {
            future.complete(Base64.getDecoder().decode(base64Content));
        } catch (IllegalArgumentException e) {
            future.completeExceptionally(e);
        }
    }

    /** Called by {@link DeviceWebSocketHandler} for camera-timeout-no-face / camera-cancelled-by-user. */
    void onCameraError(String referenceId, String reason) {
        CompletableFuture<byte[]> future = pendingImageByReferenceId.get(referenceId);
        if (future == null) {
            log.warn("[Device] Received camera error for unknown/expired referenceId={}", referenceId);
            return;
        }
        future.completeExceptionally(new RuntimeException(reason));
    }

    /** Called by {@link DeviceWebSocketHandler} when the device reports its on-device liveness challenge finished. */
    void onLivenessComplete(String referenceId) {
        pendingLivenessByReferenceId.computeIfAbsent(referenceId, k -> new CompletableFuture<>()).complete(null);
    }

    /** Releases the device's busy slot once a verification/registration has reached a terminal state (fixes the legacy gap, see §6/§27). */
    public void releaseDevice(String deviceId, String referenceId) {
        pendingImageByReferenceId.remove(referenceId);
        pendingLivenessByReferenceId.remove(referenceId);
        sessionRegistry.completeRequest(deviceId, referenceId);
    }

    private void sendOrFail(String deviceId, Map<String, Object> command, String referenceId) {
        try {
            String json = objectMapper.writeValueAsString(command);
            boolean sent = sessionRegistry.send(deviceId, CLIENT_TYPE, json, referenceId);
            if (!sent) {
                pendingImageByReferenceId.remove(referenceId);
                throw deviceBusy(deviceId);
            }
        } catch (DeviceCommunicationException e) {
            throw e;
        } catch (Exception e) {
            pendingImageByReferenceId.remove(referenceId);
            throw deviceUnavailable(deviceId);
        }
    }

    private DeviceCommunicationException deviceUnavailable(String deviceId) {
        return new DeviceCommunicationException(
                DeviceCommunicationException.Reason.DEVICE_UNAVAILABLE,
                "No session assigned to device " + deviceId);
    }

    private DeviceCommunicationException deviceBusy(String deviceId) {
        return new DeviceCommunicationException(
                DeviceCommunicationException.Reason.DEVICE_BUSY,
                "Device " + deviceId + " is busy with another request");
    }
}
