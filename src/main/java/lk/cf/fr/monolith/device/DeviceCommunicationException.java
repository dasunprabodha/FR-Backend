package lk.cf.fr.monolith.device;

import lombok.Getter;

/**
 * Path-agnostic device-communication failure, thrown by {@link DeviceCommunicationService} and
 * caught by whichever orchestrator called it (VerificationService or RegistrationService), each
 * of which translates it into its own domain exception (VerificationException /
 * RegistrationException) with a path-appropriate user-facing message. Kept independent of both
 * so this device-layer service does not need to depend on either path's model classes.
 */
@Getter
public class DeviceCommunicationException extends RuntimeException {

    public enum Reason { DEVICE_UNAVAILABLE, DEVICE_BUSY }

    private final Reason reason;

    public DeviceCommunicationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
