package lk.cf.fr.monolith.config;

import lk.cf.fr.monolith.device.DeviceWebSocketHandler;
import lk.cf.fr.monolith.device.TlsConnectionLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the device WebSocket endpoint at the same path used by the legacy
 * Device_Management module ({@code /device/ws-endpoint}), pending confirmation with whoever
 * controls the physical device firmware/app before ever renaming it - see
 * VERIFICATION_PATH_ARCHITECTURE.md section 12.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class DeviceWebSocketConfig implements WebSocketConfigurer {

    private final DeviceWebSocketHandler deviceWebSocketHandler;
    private final TlsConnectionLogger tlsConnectionLogger;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(deviceWebSocketHandler, "/device/ws-endpoint")
                .addInterceptors(tlsConnectionLogger)
                .setAllowedOrigins("*");
    }
}
