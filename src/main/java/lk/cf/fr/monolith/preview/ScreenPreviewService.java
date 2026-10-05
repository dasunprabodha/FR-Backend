package lk.cf.fr.monolith.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lk.cf.fr.monolith.device.DeviceCommunicationService;
import lk.cf.fr.monolith.device.DeviceSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relay hub for the device screen preview ("screen casting") shown on the verification page.
 *
 * <p>Two WebSocket channels meet here and nowhere else:
 * <ul>
 *   <li><b>Device side</b> - the existing {@code /device/ws-endpoint} session. The device pushes
 *       {@code screen-frame} messages; {@code DeviceWebSocketHandler} hands them to
 *       {@link #onDeviceFrame}. Start/stop commands go back out over the same session.</li>
 *   <li><b>Browser side</b> - the operator console's {@code /ws/screen-preview} session, owned by
 *       {@link ScreenPreviewWebSocketHandler}, which attaches/detaches viewers here.</li>
 * </ul>
 *
 * <p>The device only streams while somebody is watching: the first viewer to attach to a device
 * triggers {@code start-screen-preview}, and the last one to detach triggers
 * {@code stop-screen-preview}. Nothing about a preview touches the verification pipeline - the
 * commands deliberately bypass {@link DeviceSessionRegistry}'s per-device busy slot (see
 * {@code sendDirect}), so watching a screen can never make a concurrent verification look like a
 * busy device, and losing the preview can never fail a verification.
 *
 * <p><b>Backpressure.</b> Frames arrive on the device's WebSocket receive thread. Writing them
 * straight through to browser sessions from that thread would let one slow viewer stall the
 * device channel - and that channel also carries the capture image the verification is blocking
 * on. So each viewer holds a single-slot mailbox ({@link Viewer#pending}) that a relay pool
 * drains: a frame that arrives while the previous one is still being written simply replaces it.
 * Preview is disposable by nature, so dropping intermediate frames is the correct behaviour, and
 * the device thread never blocks on a browser.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScreenPreviewService {

    /** Same clientType the verification/registration paths use for the device's session. */
    private static final String CLIENT_TYPE = "fr";

    private final DeviceSessionRegistry sessionRegistry;
    private final DeviceCommunicationService deviceCommunicationService;
    private final ObjectMapper objectMapper;

    @Value("${screen-preview.enabled:true}")
    private boolean enabled;

    /** Frames per second requested from the device. Advisory - the device may send fewer. */
    @Value("${screen-preview.fps:6}")
    private int fps;

    /** Longest edge, in pixels, the device should downscale each frame to before encoding. */
    @Value("${screen-preview.max-width:480}")
    private int maxWidth;

    /** JPEG quality (1-100) the device should encode each frame at. */
    @Value("${screen-preview.quality:50}")
    private int quality;

    /** deviceId -> (browser sessionId -> viewer) */
    private final Map<String, Map<String, Viewer>> viewersByDevice = new ConcurrentHashMap<>();
    /** browser sessionId -> viewer, so detach() only needs the session. */
    private final Map<String, Viewer> viewersBySessionId = new ConcurrentHashMap<>();
    /** deviceId -> whether a start-screen-preview command is currently outstanding/active. */
    private final Map<String, Boolean> streamingByDevice = new ConcurrentHashMap<>();

    private final ExecutorService relayPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "screen-preview-relay");
        t.setDaemon(true);
        return t;
    });

    /** One browser watching one device. */
    private static final class Viewer {
        final WebSocketSession session;
        final String deviceId;
        /** Single-slot mailbox: a newer frame overwrites an undelivered older one. */
        final AtomicReference<String> pending = new AtomicReference<>();
        final AtomicBoolean draining = new AtomicBoolean(false);
        final AtomicLong sent = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();

        Viewer(WebSocketSession session, String deviceId) {
            this.session = session;
            this.deviceId = deviceId;
        }
    }

    // ---------------------------------------------------------------------
    // Browser side
    // ---------------------------------------------------------------------

    /**
     * Registers a browser session as a viewer of {@code deviceId} and, if it is the first one,
     * asks the device to start streaming. Safe to call for a device that is currently offline -
     * the viewer just receives a status saying so and stays attached, so the preview lights up
     * by itself once the device reconnects and a later start succeeds.
     */
    public void attach(WebSocketSession session, String deviceId) {
        Viewer viewer = new Viewer(session, deviceId);
        viewersBySessionId.put(session.getId(), viewer);
        viewersByDevice.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>())
                .put(session.getId(), viewer);

        log.info("[Preview] Viewer {} attached to deviceId={} (viewers={})",
                session.getId(), deviceId, viewerCount(deviceId));

        if (!enabled) {
            sendToViewer(viewer, statusJson(deviceId, "Screen preview is disabled on this server."));
            return;
        }
        ensureStreaming(deviceId);
        sendToViewer(viewer, statusJson(deviceId, null));
    }

    /** Unregisters a browser session; stops the device stream once the last viewer has gone. */
    public void detach(WebSocketSession session) {
        Viewer viewer = viewersBySessionId.remove(session.getId());
        if (viewer == null) return;

        String deviceId = viewer.deviceId;
        viewersByDevice.computeIfPresent(deviceId, (id, viewers) -> {
            viewers.remove(session.getId());
            return viewers.isEmpty() ? null : viewers;
        });

        log.info("[Preview] Viewer {} detached from deviceId={} (sent={} dropped={} remaining={})",
                session.getId(), deviceId, viewer.sent.get(), viewer.dropped.get(), viewerCount(deviceId));

        if (viewerCount(deviceId) == 0) {
            stopStreaming(deviceId);
        }
    }

    /** The device a browser session is currently watching, or null if it never attached. */
    public String deviceIdForViewer(WebSocketSession session) {
        Viewer viewer = session == null ? null : viewersBySessionId.get(session.getId());
        return viewer == null ? null : viewer.deviceId;
    }

    /** Explicit "resume" from a viewer that had paused, or a retry after the device was offline. */
    public void requestStart(String deviceId) {
        if (!enabled || viewerCount(deviceId) == 0) return;
        ensureStreaming(deviceId);
        broadcastStatus(deviceId, null);
    }

    /**
     * Explicit "pause" from a viewer. Stops the device stream for everyone watching this device -
     * acceptable for a single-operator console, and the alternative (per-viewer stream state on a
     * device that only has one screen) would buy nothing.
     */
    public void requestStop(String deviceId) {
        stopStreaming(deviceId);
        broadcastStatus(deviceId, null);
    }

    // ---------------------------------------------------------------------
    // Device side
    // ---------------------------------------------------------------------

    /**
     * Called by {@code DeviceWebSocketHandler} for every {@code screen-frame} the device pushes.
     * Serialises the browser-facing envelope once and hands the same string to every viewer, so a
     * large base64 payload is not re-encoded per viewer.
     */
    public void onDeviceFrame(String deviceId, JsonNode data) {
        Map<String, Viewer> viewers = deviceId == null ? null : viewersByDevice.get(deviceId);
        if (viewers == null || viewers.isEmpty()) {
            // Nobody is watching any more but the device has not processed the stop yet.
            stopStreaming(deviceId);
            return;
        }

        String content = text(data, "content");
        if (content == null || content.isBlank()) {
            log.debug("[Preview] Dropping empty screen-frame from deviceId={}", deviceId);
            return;
        }

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "frame");
        frame.put("deviceId", deviceId);
        frame.put("format", data.hasNonNull("format") ? data.get("format").asText() : "jpeg");
        frame.put("width", data.hasNonNull("width") ? data.get("width").asInt() : null);
        frame.put("height", data.hasNonNull("height") ? data.get("height").asInt() : null);
        frame.put("seq", data.hasNonNull("seq") ? data.get("seq").asLong() : null);
        frame.put("ts", System.currentTimeMillis());
        frame.put("content", content);

        String json;
        try {
            json = objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.warn("[Preview] Failed to serialise frame for deviceId={}: {}", deviceId, e.toString());
            return;
        }

        streamingByDevice.put(deviceId, Boolean.TRUE);
        for (Viewer viewer : viewers.values()) {
            sendToViewer(viewer, json);
        }
    }

    /**
     * Called for the device's {@code screen-preview-started} / {@code -stopped} / {@code -error}
     * acknowledgements, so the console can distinguish "the device refused/stopped" from "frames
     * just have not arrived yet".
     */
    public void onDeviceStatus(String deviceId, String type, JsonNode data) {
        if (deviceId == null) return;
        String message = text(data, "message");

        switch (type) {
            case "screen-preview-started" -> streamingByDevice.put(deviceId, Boolean.TRUE);
            case "screen-preview-stopped" -> streamingByDevice.remove(deviceId);
            case "screen-preview-error" -> {
                streamingByDevice.remove(deviceId);
                if (message == null) message = "The device could not start screen preview.";
                log.warn("[Preview] Device {} reported a preview error: {}", deviceId, message);
            }
            default -> { /* unreachable - the handler only routes the three types above */ }
        }
        broadcastStatus(deviceId, message);
    }

    /**
     * Called once the device has registered its session ("hello"). If a console was already
     * waiting on this device - the operator opened the verification page before the device was up,
     * or the device dropped and came back mid-session - the stream is (re)started here, so the
     * preview recovers without the operator having to toggle it.
     */
    public void onDeviceConnected(String deviceId) {
        if (!enabled || deviceId == null || viewerCount(deviceId) == 0) return;
        // A previous session may have left a stale TRUE behind; the new session is not streaming.
        streamingByDevice.remove(deviceId);
        ensureStreaming(deviceId);
        broadcastStatus(deviceId, null);
    }

    /** Called when the device's WebSocket session closes, so viewers see it go offline at once. */
    public void onDeviceDisconnected(String deviceId) {
        if (deviceId == null) return;
        streamingByDevice.remove(deviceId);
        if (viewerCount(deviceId) > 0) {
            broadcastStatus(deviceId, "The device disconnected.");
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void ensureStreaming(String deviceId) {
        if (Boolean.TRUE.equals(streamingByDevice.get(deviceId))) return;
        if (!sessionRegistry.isOnline(deviceId, CLIENT_TYPE)) {
            log.info("[Preview] Not starting preview - deviceId={} is offline", deviceId);
            return;
        }
        boolean sent = deviceCommunicationService.startScreenPreview(deviceId, fps, maxWidth, quality);
        if (sent) {
            // Optimistic: flipped to a confirmed TRUE by screen-preview-started or the first frame.
            streamingByDevice.put(deviceId, Boolean.TRUE);
            log.info("[Preview] start-screen-preview sent to deviceId={} fps={} maxWidth={} quality={}",
                    deviceId, fps, maxWidth, quality);
        }
    }

    private void stopStreaming(String deviceId) {
        if (deviceId == null) return;
        if (streamingByDevice.remove(deviceId) == null) return;
        deviceCommunicationService.stopScreenPreview(deviceId);
        log.info("[Preview] stop-screen-preview sent to deviceId={}", deviceId);

        // Narrow race: a viewer can attach between the last detach reading viewerCount()==0 and
        // this stop going out, and its own ensureStreaming() would have seen the not-yet-cleared
        // streaming flag and skipped. Without this re-check that viewer waits for frames forever.
        if (viewerCount(deviceId) > 0) {
            log.info("[Preview] A viewer attached while stopping deviceId={}, restarting", deviceId);
            ensureStreaming(deviceId);
        }
    }

    private int viewerCount(String deviceId) {
        Map<String, Viewer> viewers = deviceId == null ? null : viewersByDevice.get(deviceId);
        return viewers == null ? 0 : viewers.size();
    }

    private void broadcastStatus(String deviceId, String message) {
        Map<String, Viewer> viewers = viewersByDevice.get(deviceId);
        if (viewers == null) return;
        String json = statusJson(deviceId, message);
        for (Viewer viewer : viewers.values()) {
            sendToViewer(viewer, json);
        }
    }

    private String statusJson(String deviceId, String message) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("type", "status");
        status.put("deviceId", deviceId);
        status.put("enabled", enabled);
        status.put("deviceOnline", sessionRegistry.isOnline(deviceId, CLIENT_TYPE));
        status.put("streaming", Boolean.TRUE.equals(streamingByDevice.get(deviceId)));
        status.put("viewers", viewerCount(deviceId));
        status.put("fps", fps);
        status.put("message", message);
        try {
            return objectMapper.writeValueAsString(status);
        } catch (Exception e) {
            // A hand-built fallback rather than dropping the update - the console keys off "type".
            return "{\"type\":\"status\",\"deviceOnline\":false,\"streaming\":false,\"message\":null}";
        }
    }

    /**
     * Queues {@code json} for one viewer and makes sure exactly one relay thread is draining that
     * viewer's mailbox. The {@code draining} flag is the whole concurrency contract: whoever wins
     * the compareAndSet owns the write loop until the mailbox is empty.
     */
    private void sendToViewer(Viewer viewer, String json) {
        if (viewer.pending.getAndSet(json) != null) {
            viewer.dropped.incrementAndGet();
        }
        if (!viewer.draining.compareAndSet(false, true)) {
            return; // a relay thread already owns this viewer and will pick the newer payload up
        }
        try {
            relayPool.execute(() -> drain(viewer));
        } catch (Exception e) {
            viewer.draining.set(false);
            log.warn("[Preview] Could not schedule relay for viewer {}: {}", viewer.session.getId(), e.toString());
        }
    }

    private void drain(Viewer viewer) {
        try {
            String payload;
            while ((payload = viewer.pending.getAndSet(null)) != null) {
                if (!viewer.session.isOpen()) {
                    return;
                }
                // No lock needed: only the thread holding `draining` ever writes to this session.
                viewer.session.sendMessage(new TextMessage(payload));
                viewer.sent.incrementAndGet();
            }
        } catch (Exception e) {
            log.info("[Preview] Relay to viewer {} failed, closing: {}", viewer.session.getId(), e.toString());
            closeQuietly(viewer.session);
        } finally {
            viewer.draining.set(false);
            // A frame may have landed between the loop exiting and the flag clearing; re-arm if so.
            if (viewer.pending.get() != null && viewer.draining.compareAndSet(false, true)) {
                try {
                    relayPool.execute(() -> drain(viewer));
                } catch (Exception ignored) {
                    viewer.draining.set(false);
                }
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // detach() still runs via afterConnectionClosed
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    @PreDestroy
    void shutdown() {
        relayPool.shutdownNow();
        try {
            relayPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
