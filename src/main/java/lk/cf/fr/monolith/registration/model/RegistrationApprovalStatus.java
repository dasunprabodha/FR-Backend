package lk.cf.fr.monolith.registration.model;

/**
 * Valid values for {@code RegistrationRecord.status} once the Registration Approval Workflow is
 * involved. {@code AWS_APPROVED} is set only by {@code RegistrationService} itself (automatic
 * pass); the other three are the manual-review state machine driven by
 * {@code RegistrationApprovalService}: {@code PENDING_APPROVAL -> APPROVED | REJECTED}.
 *
 * <p>The DB column stays a plain String (matches this codebase's existing convention, and keeps
 * {@code VerificationService}'s literal-string eligibility check working unchanged) - this enum
 * exists purely so new service code validates transitions instead of accepting any string, unlike
 * the original {@code RegistrationApprovalService.changeStatus} skeleton.
 */
public enum RegistrationApprovalStatus {
    AWS_APPROVED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}
