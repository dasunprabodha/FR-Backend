package lk.cf.fr.monolith.verification.model;

/**
 * Verification session state machine, taken directly from
 * VERIFICATION_PATH_ARCHITECTURE.md section 14 - no states beyond the ones traced from the
 * legacy source (SharedFlowState/Aggregate/latches) are introduced.
 */
public enum VerificationState {
    CREATED,
    DEVICE_REQUESTED,
    CAPTURING,
    PROCESSING,
    VERIFIED,
    FACE_NOT_MATCHED,
    LIVENESS_FAILED,
    DEVICE_UNAVAILABLE,
    DEVICE_BUSY,
    CAMERA_ERROR,
    TIMEOUT,
    PROCESSING_ERROR,
    FAILED
}
