package lk.cf.fr.monolith.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.EOFException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * O(1) deviceId -> session routing with a single-active-session-per-deviceId policy and a
 * busy-slot lock keyed by referenceId, so two verification requests can't collide on one
 * physical device. Ported near-verbatim from
 * cf-fr-server Device_Management/ws/DeviceSessionRegistry.java
 * (see VERIFICATION_PATH_ARCHITECTURE.md section 7/§12 - this component is explicitly
 * recommended to be lifted into the monolith "largely as-is").
 *
 * <p>Difference from the legacy version: {@link #unregister} now also force-releases the
 * device's busy slot on disconnect, fixing the gap called out in the architecture doc section
 * 6/§27 ("a mid-verification disconnect leaves the device busy until the next request forces
 * the slot open").
 */
@Component
@Slf4j
public class DeviceSessionRegistry {

    private final ConcurrentHashMap<String, String> activeRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>> byDevice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionInfo> infoBySessionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> sendLocks = new ConcurrentHashMap<>();

    public record SessionInfo(String deviceId, String clientType) {}

    public void register(String deviceId, WebSocketSession session, String clientType) {
        if (deviceId == null || deviceId.isBlank() || session == null) return;

        ConcurrentHashMap<String, WebSocketSession> byType =
                byDevice.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>());
        WebSocketSession prev = byType.put(clientType, session);
        infoBySessionId.put(session.getId(), new SessionInfo(deviceId, clientType));
        sendLocks.put(session.getId(), new ReentrantLock());

        if (prev != null && prev != session && prev.isOpen()) {
            try {
                prev.close();
            } catch (Exception e) {
                log.warn("[Registry] Failed closing previous session for deviceId={}: {}", deviceId, e.toString());
            }
        }
        log.info("[Registry] Session {} registered for deviceId={} clientType={}", session.getId(), deviceId, clientType);
    }

    public void unregister(WebSocketSession session) {
        if (session == null) return;
        SessionInfo info = infoBySessionId.remove(session.getId());
        if (info == null) return;

        sendLocks.remove(session.getId());

        String deviceId = info.deviceId();
        String clientType = info.clientType();

        byDevice.computeIfPresent(deviceId, (dev, byType) -> {
            byType.compute(clientType, (t, existing) -> (existing == session) ? null : existing);
            return byType.isEmpty() ? null : byType;
        });

        // Fix vs. legacy: release any busy slot this device held, since it can no longer respond.
        forceReleaseSlot(deviceId);

        log.info("[Registry] Session {} unregistered for deviceId={} clientType={}", session.getId(), deviceId, clientType);
    }

    public WebSocketSession getSession(String deviceId, String clientType) {
        ConcurrentHashMap<String, WebSocketSession> byType = byDevice.get(deviceId);
        return byType != null ? byType.get(clientType) : null;
    }

    public boolean send(String deviceId, String clientType, String json, String referenceId) throws Exception {
        WebSocketSession session = getSession(deviceId, clientType);
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("No open session for deviceId=" + deviceId);
        }
        if (!tryAcquire(deviceId, referenceId)) {
            log.info("[Registry] Slot busy - rejecting referenceId={} device={}", referenceId, deviceId);
            return false;
        }
        safeSend(session, new TextMessage(json));
        return true;
    }

    /**
     * Sends a message that is <b>not</b> part of a verification/registration request, bypassing the
     * per-device busy slot entirely.
     *
     * <p>{@link #send} exists to guarantee one transaction at a time per physical device, and it
     * claims the slot keyed by {@code referenceId}. Out-of-band control traffic - currently the
     * screen-preview start/stop commands - has no referenceId and must never occupy that slot:
     * doing so would make an idle device look busy to the next verification, or (worse) let a
     * preview command steal the slot from a verification already using it. Such a message is
     * therefore written straight to the session, still under the per-session send lock so it can
     * never interleave with a concurrent transactional write on the same socket.
     */
    public void sendDirect(String deviceId, String clientType, String json) throws Exception {
        WebSocketSession session = getSession(deviceId, clientType);
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("No open session for deviceId=" + deviceId);
        }
        safeSend(session, new TextMessage(json));
    }

    /** The (deviceId, clientType) a session registered under, or null if it never sent "hello". */
    public SessionInfo getSessionInfo(WebSocketSession session) {
        return session == null ? null : infoBySessionId.get(session.getId());
    }

    private void safeSend(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        ReentrantLock lock = sendLocks.get(session.getId());
        if (lock == null) {
            throw new IllegalStateException("No send lock for session " + session.getId() + " - was register() called?");
        }
        lock.lock();
        try {
            if (!session.isOpen()) {
                throw new IllegalStateException("Session closed before send could complete");
            }
            session.sendMessage(message);
        } catch (EOFException e) {
            log.info("[Registry] Client disconnected during send sid={}", session.getId());
        } finally {
            lock.unlock();
        }
    }

    /** Atomically claims the busy slot for (deviceId, referenceId); idempotent for repeat sends of the same referenceId. */
    private boolean tryAcquire(String deviceId, String referenceId) {
        if (referenceId == null) return false;
        boolean[] acquired = {false};
        activeRequests.compute(deviceId, (id, existing) -> {
            if (existing != null && !existing.equals(referenceId)) {
                return existing; // busy with a different verification
            }
            acquired[0] = true;
            return referenceId;
        });
        return acquired[0];
    }

    public void completeRequest(String deviceId, String referenceId) {
        if (deviceId == null || referenceId == null) return;
        activeRequests.compute(deviceId, (id, existing) -> {
            if (existing == null || !existing.equals(referenceId)) return existing;
            return null;
        });
    }

    /** Force-releases the active slot for a device, regardless of which referenceId holds it. */
    public void forceReleaseSlot(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return;
        activeRequests.remove(deviceId);
    }

    public boolean isOnline(String deviceId, String clientType) {
        WebSocketSession session = getSession(deviceId, clientType);
        return session != null && session.isOpen();
    }
}
