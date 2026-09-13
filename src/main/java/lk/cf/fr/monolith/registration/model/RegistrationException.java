package lk.cf.fr.monolith.registration.model;

import lk.cf.fr.monolith.registration.dto.RegistrationResponse;
import lombok.Getter;
import lombok.Setter;

/**
 * Single exception type carrying the {@link RegistrationState} a genuine registration failure
 * corresponds to. Unlike verification, comparison mismatches and liveness failure are NOT
 * exceptions here - they are just data in a normal 200 response (see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.5 point 2: the legacy
 * {@code GatewayController.forwardFaceRecognition} only returns non-2xx on a genuine processing
 * error, never on a comparison/liveness mismatch). This exception is reserved for
 * device/camera/document/timeout/processing failures.
 */
@Getter
public class RegistrationException extends RuntimeException {

    private final RegistrationState state;
    private final String reason;

    @Setter
    private RegistrationResponse response;

    public RegistrationException(RegistrationState state, String message, String reason) {
        super(message);
        this.state = state;
        this.reason = reason;
    }

    public RegistrationException(RegistrationState state, String message, String reason, Throwable cause) {
        super(message, cause);
        this.state = state;
        this.reason = reason;
    }
}
