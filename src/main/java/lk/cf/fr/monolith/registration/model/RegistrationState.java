package lk.cf.fr.monolith.registration.model;

/**
 * Registration session state machine, taken from REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md
 * §15 - no states beyond the ones traced from the legacy source (the two separate
 * SharedFlowState/Aggregate/latch implementations) are introduced. {@code APPROVED}/{@code REJECTED}
 * are reached only via a later, separate call to RegistrationApprovalService, not as part of the
 * original synchronous registration request's own lifecycle.
 */
public enum RegistrationState {
    CREATED,
    DEVICE_REQUESTED,
    DOCUMENT_CAPTURE,
    DOCUMENT_VALIDATED,
    FACE_CAPTURE,
    LIVENESS_PROCESSING,
    FACE_PROCESSING,
    REGISTRATION_PROCESSING,
    COMPLETED,
    APPROVED,
    REJECTED,
    DEVICE_UNAVAILABLE,
    DEVICE_BUSY,
    CAMERA_ERROR,
    DOCUMENT_INVALID,
    TIMEOUT,
    PROCESSING_ERROR,
    FAILED
}
