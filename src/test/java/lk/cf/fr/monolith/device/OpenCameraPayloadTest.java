package lk.cf.fr.monolith.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asserts the JSON that actually goes out on the device WebSocket, not just the DTO in front of
 * it - the registration path must carry {@code data.mode}, and the verification path must remain
 * byte-identical to what it emitted before capture modes existed (no {@code mode} key at all, not
 * even a null one).
 */
class OpenCameraPayloadTest {

    private DeviceSessionRegistry sessionRegistry;
    private DeviceCommunicationService service;

    @BeforeEach
    void setUp() throws Exception {
        sessionRegistry = mock(DeviceSessionRegistry.class);
        when(sessionRegistry.isOnline(anyString(), anyString())).thenReturn(true);
        when(sessionRegistry.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        service = new DeviceCommunicationService(sessionRegistry, new ObjectMapper());
    }

    private String captureSentJson() throws Exception {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).send(eq("88806537"), eq("fr"), json.capture(), any());
        return json.getValue();
    }

    @Test
    void registrationPayloadCarriesManualMode() throws Exception {
        service.requestCapture("88806537", "ref-1", "200012345678", "dasun", "BR001",
                "registration", "nicImage", "EN", "Manual");

        String json = captureSentJson();
        var data = new ObjectMapper().readTree(json).get("data");

        assertThat(data.get("mode").asText()).isEqualTo("Manual");
        assertThat(data.get("flow").asText()).isEqualTo("registration");
        assertThat(data.get("tag").asText()).isEqualTo("nicImage");
        // The pre-existing fields must all survive alongside the new one.
        assertThat(data.get("nic").asText()).isEqualTo("200012345678");
        assertThat(data.get("userId").asText()).isEqualTo("dasun");
        assertThat(data.get("referenceId").asText()).isEqualTo("ref-1");
        assertThat(data.get("branchId").asText()).isEqualTo("BR001");
        assertThat(data.get("deviceId").asText()).isEqualTo("88806537");
        assertThat(data.get("reverifyTag").isNull()).isTrue();
        assertThat(data.get("prefLang").asText()).isEqualTo("EN");
    }

    @Test
    void registrationPayloadCarriesAutoMode() throws Exception {
        service.requestCapture("88806537", "ref-2", "200012345678", "dasun", "BR001",
                "registration", "faceImage", "EN", "Auto");

        var data = new ObjectMapper().readTree(captureSentJson()).get("data");
        assertThat(data.get("mode").asText()).isEqualTo("Auto");
    }

    @Test
    void verificationPayloadHasNoModeKeyAtAll() throws Exception {
        // Exactly the call VerificationService makes - the 8-arg overload.
        service.requestCapture("88806537", "ref-3", "200012345678", "dasun", "BR001",
                "verify", "faceImage", "EN");

        String json = captureSentJson();
        assertThat(json).doesNotContain("mode");
        assertThat(new ObjectMapper().readTree(json).get("data").has("mode")).isFalse();
        // ...and is otherwise the historical payload, verbatim.
        assertThat(json).isEqualTo("{\"command\":\"open-camera\",\"data\":{"
                + "\"nic\":\"200012345678\",\"userId\":\"dasun\",\"flow\":\"verify\",\"tag\":\"faceImage\","
                + "\"referenceId\":\"ref-3\",\"branchId\":\"BR001\",\"deviceId\":\"88806537\","
                + "\"reverifyTag\":null,\"prefLang\":\"EN\"}}");
    }
}
