package lk.cf.fr.monolith.device;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Ported near-verbatim from cf-fr-server Device_Management/logging/TlsConnectionLogger.java.
 * Purely diagnostic - logs whatever TLS metadata the container exposes for the incoming device
 * handshake, but never rejects a connection (matching legacy behaviour: the real handshake
 * validation happens in {@link DeviceWebSocketHandler#afterConnectionEstablished}, not here).
 *
 * <p>Note: neither this class nor the legacy Device_Management module terminates TLS itself -
 * if the physical device dials {@code wss://}, TLS termination must happen in front of this
 * app (e.g. a reverse proxy) or via {@code server.ssl.*} Spring Boot properties; this class only
 * reports what it sees.
 */
@Component
@Slf4j
public class TlsConnectionLogger implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                    @NonNull ServerHttpResponse response,
                                    @NonNull WebSocketHandler wsHandler,
                                    @NonNull Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpReq = servletRequest.getServletRequest();

            String cipher = (String) httpReq.getAttribute("javax.servlet.request.cipher_suite");
            String tlsVersion = (String) httpReq.getAttribute("org.apache.tomcat.util.net.secure_protocol_version");
            Object keySize = httpReq.getAttribute("javax.servlet.request.key_size");

            log.info("[WS Handshake] scheme={} secure={} cipher={} tlsVersion={} keySize={}",
                    httpReq.getScheme(), httpReq.isSecure(),
                    cipher != null ? cipher : "not exposed",
                    tlsVersion != null ? tlsVersion : "not exposed",
                    keySize);
        }
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                                @NonNull WebSocketHandler wsHandler, Exception exception) {
    }
}
