package lk.cf.fr.monolith.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.registry.DeviceResolutionService;
import lk.cf.fr.monolith.registry.entity.DeviceRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * Browser-facing end of the screen preview, served at {@code /ws/screen-preview}. The operator
 * console opens one of these per preview panel; frames are pushed down it by
 * {@link ScreenPreviewService}.
 *
 * <p>The console addresses a device the same way the verification request does - by
 * {@code branchId} + {@code deviceNickname} (+ {@code userId} for the legacy fallback) - so the
 * UI never has to learn the internal {@code deviceId}, and a preview can only ever target a
 * device that {@link DeviceResolutionService} would accept for a real verification. A raw
 * {@code deviceId} query parameter is also accepted for debugging against a known device.
 *
 * <p>Handshake: {@code /ws/screen-preview?branchId=BR001&deviceNickname=DemoDevice&userId=dasun}.
 * The resolved {@code deviceId} comes back in the first {@code status} message.
 *
 * <p>No authentication is applied, matching every other endpoint in this MVP (see
 * {@code VerificationController}). Note that this channel carries a live view of the customer-
 * facing device screen, so it must sit behind the same network boundary as the console itself
 * before this is deployed anywhere real.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScreenPreviewWebSocketHandler extends TextWebSocketHandler {

    private final DeviceResolutionService deviceResolutionService;
    private final ScreenPreviewService screenPreviewService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        Map<String, String> params = queryParams(session.getUri());
        String deviceId = params.get("deviceId");

        if (deviceId == null || deviceId.isBlank()) {
            try {
                DeviceRecord device = deviceResolutionService.resolveActiveDeviceOrFail(
                        params.get("branchId"), params.get("deviceNickname"), params.get("userId"));
                deviceId = device.getDeviceId();
            } catch (Exception e) {
                log.warn("[Preview] Could not resolve a device for viewer {}: {}", session.getId(), e.toString());
                rejectQuietly(session, "Device isn't found or device isn't activated.");
                return;
            }
        }

        log.info("[Preview] Viewer {} connected for deviceId={}", session.getId(), deviceId);
        screenPreviewService.attach(session, deviceId);
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String deviceId = screenPreviewService.deviceIdForViewer(session);
        if (deviceId == null) return;

        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.hasNonNull("type") ? root.get("type").asText().toLowerCase() : "";
            switch (type) {
                case "start" -> screenPreviewService.requestStart(deviceId);
                case "stop" -> screenPreviewService.requestStop(deviceId);
                case "ping" -> session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
                default -> log.debug("[Preview] Ignoring viewer message type={} sid={}", type, session.getId());
            }
        } catch (Exception e) {
            log.warn("[Preview] Bad viewer message on sid={}: {}", session.getId(), e.toString());
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.warn("[Preview] Transport error on viewer sid={}: {}", session.getId(), exception.toString());
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        screenPreviewService.detach(session);
        log.info("[Preview] Viewer {} disconnected: {}", session.getId(), status);
    }

    /**
     * Sends a readable reason before closing, so the console can show "device not activated"
     * rather than a bare socket error - a close frame's reason text is not exposed to browser
     * JavaScript, only the code is.
     */
    private void rejectQuietly(WebSocketSession session, String reason) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    Map.of("type", "error", "message", reason))));
        } catch (Exception ignored) {
            // falling through to the close below is enough
        }
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (Exception ignored) {
            // nothing further to do
        }
    }

    private static Map<String, String> queryParams(URI uri) {
        if (uri == null) return Map.of();
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
    }
}
