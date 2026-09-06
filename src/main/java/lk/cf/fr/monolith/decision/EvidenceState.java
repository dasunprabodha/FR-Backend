package lk.cf.fr.monolith.decision;

/**
 * What one evidence group says about the claim.
 *
 * <p>{@link #ABSENT} and {@link #CONTRADICTS} are deliberately distinct, and keeping them apart is
 * the point of this enum. A document whose number disagrees with the claim is strong evidence of
 * fraud; a document that was never uploaded is no evidence at all. A rule that maps both to
 * "not passed" - as a boolean conjunction must - cannot tell an attacker from an applicant with a
 * broken scanner, and will assign them the same outcome.
 */
public enum EvidenceState {

    /** Comfortably above threshold: supports the claim. */
    SUPPORTS,

    /** Within the margin band around the threshold. Measured, but too weak to decide on. */
    MARGINAL,

    /** Comfortably below threshold: positive evidence against the claim. */
    CONTRADICTS,

    /** Nothing was measured - no scan supplied, no face detected, no number read. */
    ABSENT,

    /**
     * This channel does not exist for this run at all, as distinct from being absent for this
     * sample. Offline corpus replay has no device liveness session; excluding the group is the
     * same treatment the baseline gate already gives it, so both rules stay comparable.
     */
    NOT_APPLICABLE
}
