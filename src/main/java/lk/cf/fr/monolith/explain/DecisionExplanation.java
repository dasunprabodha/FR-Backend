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
 * <p>The {@link #hypothetical()} block is the research payload. Identity binding is recorded but
 * deliberately excluded from the live decision rule, so the system can report - on real attempts -
 * what the outcome would have been had that evidence been gated. That is the baseline-versus-
 * proposed comparison, computed on the same attempt rather than argued in the abstract.
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
     * The same attempt evaluated under a rule that also gates identity binding - i.e. what the
     * proposed decision layer would have done with evidence the deployed gate ignores.
     *
     * @param bindingGatedDecision  PASS | FAIL | UNCHANGED_NO_EVIDENCE
     * @param differsFromActual     true when adding binding to the gate changes the outcome
     * @param explanation           why it differs, or why it does not
     */
    public record Hypothetical(
            String bindingGatedDecision,
            boolean differsFromActual,
            Double bindingScore,
            Double bindingThreshold,
            String explanation
    ) {
    }
}
