package lk.cf.fr.monolith.decision;

/**
 * Three-way outcome of the dependency-aware rule.
 *
 * <p>The conjunctive baseline has only two states, which forces every uncertain case into one of
 * them: an applicant who supplied no scanned document is either approved on incomplete evidence or
 * rejected for something they may not have done wrong. Neither is correct, and the choice between
 * them is not a threshold question - it is a missing state.
 *
 * <p>{@link #REVIEW} is that state. It is the difference between <em>contradicted</em> evidence and
 * <em>absent</em> evidence, and making it explicit is what allows manual-review load to be measured
 * at all rather than being silently folded into the rejection rate.
 */
public enum Decision {

    /** Every evidence group returned positive support with margin. Safe to auto-approve. */
    APPROVE,

    /**
     * No group contradicts the claim, but at least one is absent or too close to its threshold to
     * carry the decision. A human should look. This is a cost, not a failure, and it is reported
     * as its own rate.
     */
    REVIEW,

    /** At least one group carries positive evidence <em>against</em> the claim. */
    REJECT
}
