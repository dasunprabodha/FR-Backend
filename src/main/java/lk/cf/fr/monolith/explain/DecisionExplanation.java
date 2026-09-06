package lk.cf.fr.monolith.explain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A structured account of why one verification attempt reached the decision it did.
 *
 * <p>This is <b>decision-level explanation over opaque component models</b>, and the distinction
 * matters. The face-comparison and liveness scores come from hosted AWS APIs whose internals are
 * not available here, so nothing in this record explains <em>how</em> a similarity score was
 * produced. What it explains is how the scores that came back were combined into an outcome:
 * which evidence was consulted, which was actually allowed to affect the result, how close each
 * item sat to its threshold, and what minimal change would have flipped the decision. It should
 * never be described as explainable anti-spoofing.
 *
 * <p>The {@link #hypothetical()} block is the research payload: the same attempt evaluated under
 * both the rule that decided it and the other one, so the baseline-versus-proposed comparison is
 * computed on real evidence rather than argued in the abstract. Which rule is which depends on
 * {@code verification.binding-gated} at the time the attempt ran - the block carries its own
 * labels so a panel never has to guess.
 */
public record DecisionExplanation(

        String referenceId,
        String nic,
        String cifNo,

        /** AWS_APPROVED | PENDING_APPROVAL | APPROVED | REJECTED. */
        String status,

        /** True when the attempt cleared the gate without human review. */
        boolean passed,

        /** One sentence naming the deciding factor. */
        String primaryReason,

        /** The decision rule in force, spelled out so the panel reads what was actually applied. */
        String decisionRule,

        /** All evidence, strongest influence first: decisive items, then gated, then the rest. */
        List<EvidenceItem> evidence,

        /** The smallest change that would have flipped the outcome. */
        Counterfactual counterfactual,

        /** What the same attempt would have produced under an alternative rule. */
        Hypothetical hypothetical,

        /** Free-text notes about evidence that was collected but not allowed to count. */
        List<String> notes,

        LocalDateTime requestedAt,
        LocalDateTime respondedAt,
        Long durationMs
) {

    /**
     * @param wouldFlip     whether any single change could flip the decision
     * @param summary       one sentence a non-specialist can read
     * @param requirements  per-item changes needed; empty when the attempt passed
     * @param closestItem   for a passing attempt, the evidence that came nearest to failing
     * @param closestMargin how much headroom that item had
     */
    public record Counterfactual(
            boolean wouldFlip,
            String summary,
            List<Requirement> requirements,
            String closestItem,
            Double closestMargin
    ) {
        public record Requirement(String evidenceId, String label, Double actual, Double required, Double shortfall) {
        }
    }

    /**
     * The same attempt evaluated under the <em>other</em> decision rule.
     *
     * <p>Which rule that is depends on what was in force when the attempt ran, and the panel reads
     * the labels off this record rather than assuming. When binding was not gated, the alternative
     * is the binding-aware rule - what the proposed decision layer would have done with evidence
     * the gate ignored. When binding <em>was</em> gated, the alternative is the conjunctive
     * baseline without it, so the same panel keeps answering the same question in reverse: what
     * did adding this evidence actually change?
     *
     * <p>The interesting case is unchanged either way - an attempt where the two rules disagree,
     * shown on real evidence rather than argued in the abstract.
     *
     * @param actualRuleLabel       short name of the rule that decided this attempt
     * @param alternativeRuleLabel  short name of the rule being compared against
     * @param alternativeDecision   PASS | FAIL | UNCHANGED_NO_EVIDENCE
     * @param differsFromActual     true when the two rules reach different outcomes
     * @param explanation           why they differ, or why they do not
     */
    public record Hypothetical(
            String actualRuleLabel,
            String alternativeRuleLabel,
            String alternativeDecision,
            boolean differsFromActual,
            Double bindingScore,
            Double bindingThreshold,
            String explanation
    ) {
    }
}
