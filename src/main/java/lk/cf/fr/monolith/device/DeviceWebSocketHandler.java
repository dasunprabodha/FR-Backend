package lk.cf.fr.monolith.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.preview.ScreenPreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Terminates the device's WebSocket connection. Ported in spirit from
 * cf-fr-server Device_Management/security/WebSocketConfig.java (the anonymous
 * WebSocketHandler registered there) - see VERIFICATION_PATH_ARCHITECTURE.md section 6/§12.
 *
 * <p>Only the message types actually used by the verification path are handled: {@code hello},
 * {@code image}, {@code camera-timeout-no-face}, {@code camera-cancelled-by-user}, and the
 * liveness-completion signals, plus the screen-preview stream
 * ({@code screen-frame}, {@code screen-preview-started/-stopped/-error}).
 * Registration/activation-only message types
 * ({@code activate}, {@code request-liveness-session}, {@code liveness-cancelled}) are out of
 * scope for this MVP.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeviceWebSocketHandler implements WebSocketHandler {

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[0-9]{8}$");
    private static final long TIMESTAMP_SKEW_MS = 30_000;
    private static final long MESSAGE_DEDUP_TTL_MS = 30_000;

    /** Screen-preview traffic, routed on the fast path below rather than through the switch. */
    private static final Set<String> PREVIEW_TYPES = Set.of(
            "screen-frame", "screen-preview-started", "screen-preview-stopped", "screen-preview-error");

    private final DeviceSessionRegistry sessionRegistry;
    private final DeviceCommunicationService deviceCommunicationService;
    private final ScreenPreviewService screenPreviewService;
    private final ObjectMapper objectMapper;

    /** messageId -> received-at epoch millis, cheap replay protection matching legacy WebSocketConfig.seenMessageIds. */
    private final Map<String, Long> seenMessageIds = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        if (!handshakeIsValid(session)) {
            log.warn("[WS] Invalid device handshake, closing sid={}", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        log.info("[WS] Device connection established sid={}", session.getId());
    }

    @Override
    public void handleMessage(@NonNull WebSocketSession session, @NonNull WebSocketMessage<?> message) {
        String payload = String.valueOf(message.getPayload());
        try {
            JsonNode root = objectMapper.readTree(payload);

            String type = root.hasNonNull("type") ? root.get("type").asText().toLowerCase() : "";
            JsonNode data = root.has("data") ? root.get("data") : objectMapper.createObjectNode();

            // Screen-preview traffic skips the freshness and replay gates below, deliberately.
            // Those gates CLOSE the session on a violation, which is right for a transactional
            // message but catastrophic here: frames arrive several times a second, so one late
            // burst after a network stall would tear down the very session an in-flight
            // verification is waiting on for its capture image. A stale or repeated frame is
            // worth nothing anyway - dropping it costs the viewer a fraction of a second.
            if (PREVIEW_TYPES.contains(type)) {
                handlePreviewMessage(session, type, data);
                return;
            }

            if (!root.hasNonNull("timestamp")) {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
            long age = Math.abs(System.currentTimeMillis() - root.get("timestamp").asLong());
            if (age > TIMESTAMP_SKEW_MS) {
                log.warn("[WS] Stale message rejected, age={}ms sid={}", age, session.getId());
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }

            if (!root.hasNonNull("messageId")) {
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
            String messageId = root.get("messageId").asText();
            if (isDuplicate(messageId)) {
                log.warn("[WS] Duplicate messageId={} dropped sid={}", messageId, session.getId());
                return;
            }

            switch (type) {
                case "hello" -> handleHello(session, data);
                case "image" -> handleImage(data);
                case "camera-timeout-no-face" -> deviceCommunicationService.onCameraError(
                        textOrNull(data, "referenceId"), "NO_FACE_UNTIL_TIMEOUT");
                case "camera-cancelled-by-user" -> deviceCommunicationService.onCameraError(
                        textOrNull(data, "referenceId"), "USER_PRESSED_BACK");
                case "liveness-result", "liveness-complete", "liveness-finished" ->
                        deviceCommunicationService.onLivenessComplete(textOrNull(data, "referenceId"));
                default -> log.info("[WS] Unhandled message type={} sid={}", type, session.getId());
            }
        } catch (Exception e) {
            log.error("[WS] Failed to process message sid={}", session.getId(), e);
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.warn("[WS] Transport error sid={}: {}", session.getId(), exception.toString());
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) {
        // Read the deviceId first: unregister() drops the mapping, and the preview hub needs it to
        // tell any watching console the device has gone offline.
        String deviceId = deviceIdOf(session, null);
        sessionRegistry.unregister(session);
        screenPreviewService.onDeviceDisconnected(deviceId);
        log.info("[WS] Connection closed sid={} status={}", session.getId(), closeStatus);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private void handleHello(WebSocketSession session, JsonNode data) {
        String deviceId = textOrNull(data, "deviceId");
        String clientType = data.hasNonNull("clientType") ? data.get("clientType").asText() : "fr";
        sessionRegistry.register(deviceId, session, clientType);
        log.info("[WS] hello sid={} deviceId={} clientType={}", session.getId(), deviceId, clientType);
        // Only now is the device routable, so this is the earliest point a preview can be resumed
        // for a console that was already waiting on it.
        screenPreviewService.onDeviceConnected(deviceId);
    }

    private void handlePreviewMessage(WebSocketSession session, String type, JsonNode data) {
        String deviceId = deviceIdOf(session, data);
        if (deviceId == null) {
            log.debug("[WS] Preview message type={} from an unregistered session sid={}, ignored",
                    type, session.getId());
            return;
        }
        if ("screen-frame".equals(type)) {
            screenPreviewService.onDeviceFrame(deviceId, data);
        } else {
            screenPreviewService.onDeviceStatus(deviceId, type, data);
        }
    }

    /**
     * The deviceId this session registered under at "hello", falling back to the one carried in
     * the message body for a session that has not said hello yet.
     */
    private String deviceIdOf(WebSocketSession session, JsonNode data) {
        DeviceSessionRegistry.SessionInfo info = sessionRegistry.getSessionInfo(session);
        if (info != null && info.deviceId() != null) {
            return info.deviceId();
        }
        return data == null ? null : textOrNull(data, "deviceId");
    }

    private void handleImage(JsonNode data) {
        String referenceId = textOrNull(data, "referenceId");
        String content = textOrNull(data, "content");
        deviceCommunicationService.onImageReceived(referenceId, content);
    }

    private boolean handshakeIsValid(WebSocketSession session) {
        HttpHeaders headers = session.getHandshakeHeaders();
        String deviceId = headers.getFirst("X-Device-Id");
        String androidId = headers.getFirst("X-Android-Id");
        String packageName = headers.getFirst("X-Package-Name");
        String widevineId = headers.getFirst("X-Widevine-Id");

        if (deviceId == null || androidId == null || packageName == null || widevineId == null) {
            log.warn("[WS] Missing required handshake headers sid={}", session.getId());
            return false;
        }
        if (!DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
            log.warn("[WS] Invalid X-Device-Id format sid={}", session.getId());
            return false;
        }
        if (!packageName.equalsIgnoreCase("com.example.helloonepage")) {
            log.warn("[WS] Invalid X-Package-Name sid={}", session.getId());
            return false;
        }
        return true;
    }

    private boolean isDuplicate(String messageId) {
        long now = System.currentTimeMillis();
        seenMessageIds.entrySet().removeIf(e -> now - e.getValue() > MESSAGE_DEDUP_TTL_MS);
        return seenMessageIds.putIfAbsent(messageId, now) != null;
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
        }
    }
}
