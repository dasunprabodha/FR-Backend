package lk.cf.fr.monolith.decision;

import java.util.List;

/**
 * One group of mutually dependent evidence sources, reduced to a single verdict.
 *
 * <h2>Why group at all</h2>
 * <p>Comparisons 1, 2, 4 and 5 are not independent observations. Comparisons 1 and 4 both measure
 * a <em>document portrait</em> against the live face, and when both exist they are two photographs
 * of the same physical card. Sum-rule fusion, weighted voting and Dempster-Shafer combination all
 * assume the sources they combine are independent; applied here they would count one piece of
 * evidence twice and report a confidence the data does not support. Two blurry views of the same
 * card do not corroborate each other.
 *
 * <h2>Why the minimum</h2>
 * <p>Within a group the combined score is the <em>minimum</em> of the available measurements. With
 * unknown dependence between sources, the minimum is the Fréchet lower bound on their conjunction -
 * the strongest claim that can be made without assuming an independence structure that does not
 * hold. It is also the conservative choice in the direction that matters: if either view of the
 * card fails to match the face, the group does not support the claim, and no averaging can wash
 * that away.
 *
 * @param sources     the comparison names actually measured, for the audit trail
 * @param score       the combined (minimum) score, or null when nothing was measured
 * @param threshold   the cutoff this group was judged against, in the group's own units
 * @param state       the verdict
 * @param detail      short human-readable explanation for the evidence panel
 */
public record EvidenceGroup(
        String name,
        List<String> sources,
        Double score,
        Double threshold,
        EvidenceState state,
        String detail
) {

    /** True when this group carries positive evidence against the claim. */
    public boolean contradicts() {
        return state == EvidenceState.CONTRADICTS;
    }

    /**
     * True when this group cannot carry a decision either way - measured but too close to call, or
     * not measured at all. {@link EvidenceState#NOT_APPLICABLE} is excluded: a channel that does
     * not exist for the run is not an unresolved question about this sample.
     */
    public boolean needsHuman() {
        return state == EvidenceState.MARGINAL || state == EvidenceState.ABSENT;
    }
}
