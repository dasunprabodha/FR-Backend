package lk.cf.fr.monolith.verification.model;

import lk.cf.fr.monolith.verification.dto.VerificationResponse;
import lombok.Getter;
import lombok.Setter;

/**
 * Single exception type carrying the {@link VerificationState} the failure corresponds to,
 * used uniformly across the verification path instead of the Kafka error-payload mechanism
 * (ErrorHandling.errorPayloadSend) that the legacy modules relied on.
 *
 * <p>{@link #response} is attached by VerificationService before the exception reaches the
 * controller layer, so that even a failed verification (face mismatch, liveness failure, device
 * busy, ...) returns the full response body the legacy GatewayController produced - not just an
 * error message.
 */
@Getter
public class VerificationException extends RuntimeException {

    private final VerificationState state;
    private final String reason;

    @Setter
    private VerificationResponse response;

    public VerificationException(VerificationState state, String message, String reason) {
        super(message);
        this.state = state;
        this.reason = reason;
    }

    public VerificationException(VerificationState state, String message, String reason, Throwable cause) {
        super(message, cause);
        this.state = state;
        this.reason = reason;
    }
}
