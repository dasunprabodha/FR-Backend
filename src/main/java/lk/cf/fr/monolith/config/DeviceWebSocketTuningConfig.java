package lk.cf.fr.monolith.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Ported near-verbatim from cf-fr-server Device_Management/security/WebSocketTuningConfig.java.
 *
 * <p>The container's default text/binary message buffer (a few KB) is far too small for a
 * real device's base64-encoded face image ("image" message payload) - without this, a real
 * device's capture reply would be silently truncated or rejected. Required for testing against
 * a real physical device (see IMPLEMENTATION_PROGRESS.md).
 */
@Configuration
public class DeviceWebSocketTuningConfig {

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(30 * 1024 * 1024);   // 30 MB
        container.setMaxBinaryMessageBufferSize(30 * 1024 * 1024);  // 30 MB
        container.setMaxSessionIdleTimeout(0L);                    // no idle timeout - device sessions are long-lived
        return container;
    }
}
