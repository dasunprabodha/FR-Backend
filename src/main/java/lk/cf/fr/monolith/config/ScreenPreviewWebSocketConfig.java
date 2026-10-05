package lk.cf.fr.monolith.config;

import lk.cf.fr.monolith.preview.ScreenPreviewWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the operator console's screen-preview endpoint. Kept separate from
 * {@link DeviceWebSocketConfig} on purpose: {@code /device/ws-endpoint} is a contract with the
 * physical device firmware that must not be touched (see that class's note), whereas this path is
 * ours to change.
 *
 * <p>{@code screen-preview.allowed-origins} defaults to {@code *} to match the device endpoint and
 * to keep `ng serve` on :4200 working against a backend on :8090 without extra setup; narrow it to
 * the console's real origin for any non-local deployment.
 *
 * <p>The 30 MB text buffer configured by {@link DeviceWebSocketTuningConfig} is a container-wide
 * setting, so it covers this endpoint too - which matters, since a frame is base64 like a capture.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ScreenPreviewWebSocketConfig implements WebSocketConfigurer {

    private final ScreenPreviewWebSocketHandler screenPreviewWebSocketHandler;

    @Value("${screen-preview.allowed-origins:*}")
    private String[] allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(screenPreviewWebSocketHandler, "/ws/screen-preview")
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
