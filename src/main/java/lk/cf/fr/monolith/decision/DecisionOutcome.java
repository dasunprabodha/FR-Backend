package lk.cf.fr.monolith.decision;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The dependency-aware rule's verdict, with the group-level evidence that produced it.
 *
 * <p>Reported alongside the baseline's own verdict rather than replacing it. Both rules read the
 * same {@code FaceAnalysis}, the same binding result and the same liveness outcome, so when the
 * two disagree the decision rule is the only thing that differs - which is the entire experimental
 * design. Nothing here feeds the live approval path.
 *
 * @param groups every group considered, in evaluation order, including those that were absent
 */
public record DecisionOutcome(Decision decision, List<EvidenceGroup> groups, String reason) {

    /** Groups that carried positive evidence against the claim. Empty unless REJECT. */
    public List<EvidenceGroup> contradicting() {
        return groups.stream().filter(EvidenceGroup::contradicts).toList();
    }

    /** Groups that could not carry a decision. Non-empty on REVIEW. */
    public List<EvidenceGroup> unresolved() {
        return groups.stream().filter(EvidenceGroup::needsHuman).toList();
    }

    /** Comma-joined group names behind the verdict, for the results CSV. */
    public String driverNames() {
        List<EvidenceGroup> drivers = decision == Decision.REJECT ? contradicting()
                : decision == Decision.REVIEW ? unresolved()
                : List.of();
        return drivers.stream().map(EvidenceGroup::name).collect(Collectors.joining("|"));
    }
}
