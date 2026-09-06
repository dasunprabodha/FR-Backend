package lk.cf.fr.monolith.decision;

import lk.cf.fr.monolith.analysis.RegistrationAnalysisService.FaceAnalysis;
import lk.cf.fr.monolith.identity.IdentityBindingResult;
import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The proposed decision rule: dependency-aware, magnitude-aware, and able to abstain.
 *
 * <h2>What it changes relative to the baseline</h2>
 * <p>The conjunctive baseline is {@code cmp2 && cmp3 && (cmp4 == null || cmp4)} plus liveness.
 * Three properties of that rule are what this one is designed to test:
 *
 * <ol>
 *   <li><b>It discards magnitude.</b> Each comparison is reduced to a boolean at a fixed cutoff
 *       before the rule sees it, so a score of 80.1 and a score of 99.9 are indistinguishable, and
 *       so are 79.9 and 12.0. Here each group keeps its score and is judged against a margin band,
 *       so a decision that only just cleared its threshold is visibly different from one that
 *       cleared it comfortably.</li>
 *   <li><b>It discards evidence it has already computed.</b> Comparison 1 (presented card against
 *       the live face) and comparison 5 (uploaded scan against the presented card) are measured,
 *       persisted, shown in the evidence panel - and excluded from the rule. An applicant
 *       presenting someone else's card with no scan fails comparison 1 and is approved anyway.
 *       Both are included here.</li>
 *   <li><b>It cannot abstain.</b> Absent evidence and contradicted evidence both become "not
 *       passed", so a missing scan is treated as a wrong number. See {@link Decision#REVIEW}.</li>
 * </ol>
 *
 * <h2>Why grouping is the mechanism</h2>
 * <p>The obvious alternative - sum-rule or Dempster-Shafer fusion over the five comparisons - is
 * not available, because the comparisons are not independent. cmp1, cmp2, cmp4 and cmp5 all
 * descend from images of the same physical card, so their errors are correlated: a glare-damaged
 * card degrades every one of them together. Fusing them as independent sources would report a
 * confidence the evidence cannot support. Instead, dependent sources are grouped and reduced by
 * the minimum (see {@link EvidenceGroup}), and only the resulting group states - never a fabricated
 * fused scalar - reach the decision.
 *
 * <h2>Scope</h2>
 * <p>This service decides nothing in production. It is computed alongside the baseline gate so both
 * verdicts can be recorded for the same sample, and it reads exactly the same inputs the baseline
 * reads. Face recognition, liveness and OCR remain untouched AWS black boxes; nothing here claims
 * to improve any of them.
 */
@Service
@Slf4j
public class DependencyAwareDecisionService {

    /** Same cutoff the baseline gate uses, so the two rules are compared at the same operating point. */
    @Value("${verification.similarity-threshold:80}")
    private double similarityThreshold;

    @Value("${verification.binding-threshold:0.80}")
    private double bindingThreshold;

    @Value("${verification.liveness-confidence-threshold:65}")
    private double livenessThreshold;

    /**
     * Half-width of the band around each threshold inside which a measurement is treated as too
     * close to call. Set once, before any corpus was analysed, and deliberately not tuned per
     * dataset: a review band fitted to the data it is evaluated on measures nothing.
     */
    @Value("${verification.decision.similarity-margin:5.0}")
    private double similarityMargin;

    @Value("${verification.decision.binding-margin:0.10}")
    private double bindingMargin;

    @Value("${verification.decision.liveness-margin:10.0}")
    private double livenessMargin;

    /**
     * @param liveness null in offline corpus replay, where no device liveness session exists. The
     *                 group is then {@link EvidenceState#NOT_APPLICABLE} and excluded, which is the
     *                 same treatment the baseline gate gives it when it reports
     *                 {@code allPassed == null}. Handling it identically in both rules is what
     *                 keeps the comparison honest.
     */
    public DecisionOutcome decide(FaceAnalysis faces, LivenessOutcome liveness,
                                  IdentityBindingResult binding) {
        List<EvidenceGroup> groups = new ArrayList<>();

        // Both comparisons measure a document portrait against the live face. When a scan was
        // supplied they are two photographs of the same card, so they are one source observed
        // twice, not two corroborating sources.
        groups.add(similarityGroup("DOCUMENT_PORTRAIT",
                "the card portrait matches the live face",
                List.of(named("cmp1", faces.cmp1()), named("cmp4", faces.cmp4()))));

        // The card was physically with the person at capture time: the presented card matches the
        // card visible in the self-with-document photo, and that photo's face matches the live one.
        groups.add(similarityGroup("CO_PRESENCE",
                "the card and the person were in frame together",
                List.of(named("cmp2", faces.cmp2()), named("cmp3", faces.cmp3()))));

        // Are the two document channels even showing the same document? Excluded from the baseline
        // rule entirely, and the only signal that dissents when a substituted physical card is
        // paired with a genuine scan.
        groups.add(similarityGroup("CHANNEL_AGREEMENT",
                "the uploaded scan and the presented card are the same document",
                List.of(named("cmp5", faces.cmp5()))));

        groups.add(bindingGroup(binding));
        groups.add(livenessGroup(liveness));

        List<EvidenceGroup> contradicting = groups.stream().filter(EvidenceGroup::contradicts).toList();
        if (!contradicting.isEmpty()) {
            return new DecisionOutcome(Decision.REJECT, groups,
                    "contradicted by " + join(contradicting));
        }

        List<EvidenceGroup> unresolved = groups.stream().filter(EvidenceGroup::needsHuman).toList();
        if (!unresolved.isEmpty()) {
            return new DecisionOutcome(Decision.REVIEW, groups,
                    "no group contradicts the claim, but " + join(unresolved)
                            + " cannot carry the decision");
        }

        return new DecisionOutcome(Decision.APPROVE, groups, "every evidence group supports the claim");
    }

    // ---------------------------------------------------------------------------------------

    private record Named(String name, ComparisonResult result) {
    }

    private static Named named(String name, ComparisonResult result) {
        return new Named(name, result);
    }

    /**
     * Reduce a group of dependent similarity comparisons to one verdict.
     *
     * <p>A comparison with no measurement is skipped rather than scored zero. Recording an
     * undetectable face as 0.0 would place it at the bottom of the distribution as though it had
     * been measured and found maximally dissimilar - the same corruption {@code ComparisonResult}
     * already guards against for the score distributions.
     */
    private EvidenceGroup similarityGroup(String name, String detail, List<Named> members) {
        List<Named> measured = members.stream()
                .filter(m -> m.result() != null && m.result().hasMeasurement())
                .toList();

        List<String> sources = measured.stream().map(Named::name).toList();

        if (measured.isEmpty()) {
            return new EvidenceGroup(name, sources, null, similarityThreshold, EvidenceState.ABSENT,
                    "not measured - " + detail + " could not be established");
        }

        double score = measured.stream().mapToDouble(m -> m.result().similarity()).min().orElseThrow();
        EvidenceState state = band(score, similarityThreshold, similarityMargin);

        return new EvidenceGroup(name, sources, round(score), similarityThreshold, state,
                describe(state, detail, sources, score));
    }

    private EvidenceGroup bindingGroup(IdentityBindingResult binding) {
        String detail = "the document number matches the claimed NIC";
        if (binding == null || !binding.hasEvidence()) {
            return new EvidenceGroup("IDENTITY_BINDING", List.of(), null, bindingThreshold,
                    EvidenceState.ABSENT,
                    "no document number was read, so the claim is unbound - absent evidence, not a mismatch");
        }

        double score = binding.score() == null ? 0.0 : binding.score();
        EvidenceState state = band(score, bindingThreshold, bindingMargin);

        return new EvidenceGroup("IDENTITY_BINDING", List.of("binding"), round(score), bindingThreshold,
                state, describe(state, detail, List.of(binding.outcome().name()), score));
    }

    private EvidenceGroup livenessGroup(LivenessOutcome liveness) {
        if (liveness == null) {
            return new EvidenceGroup("LIVENESS", List.of(), null, livenessThreshold,
                    EvidenceState.NOT_APPLICABLE,
                    "no liveness channel in this run - excluded from the decision, exactly as the "
                            + "baseline gate excludes it");
        }
        if (liveness.confidence() == null) {
            // A recorded pass/fail with no score: honour the verdict, but it carries no magnitude.
            return new EvidenceGroup("LIVENESS", List.of("liveness"), null, livenessThreshold,
                    liveness.passed() ? EvidenceState.SUPPORTS : EvidenceState.CONTRADICTS,
                    "liveness " + (liveness.passed() ? "passed" : "failed") + " with no confidence score");
        }

        double score = liveness.confidence();
        EvidenceState state = band(score, livenessThreshold, livenessMargin);
        return new EvidenceGroup("LIVENESS", List.of("liveness"), round(score), livenessThreshold, state,
                describe(state, "the face presented was live", List.of("liveness"), score));
    }

    /**
     * Place a score relative to its threshold. The band is what makes the rule magnitude-aware:
     * a score inside it is treated as measured-but-inconclusive rather than being forced to a side
     * by a cutoff it happens to sit next to.
     */
    private static EvidenceState band(double score, double threshold, double margin) {
        if (score >= threshold + margin) {
            return EvidenceState.SUPPORTS;
        }
        if (score <= threshold - margin) {
            return EvidenceState.CONTRADICTS;
        }
        return EvidenceState.MARGINAL;
    }

    private static String describe(EvidenceState state, String detail, List<String> sources, double score) {
        String from = sources.isEmpty() ? "" : " (" + String.join(", ", sources) + ")";
        return switch (state) {
            case SUPPORTS -> "supports: " + detail + from;
            case MARGINAL -> "too close to call at " + round(score) + ": " + detail + from;
            case CONTRADICTS -> "contradicted at " + round(score) + ": " + detail + from;
            case ABSENT -> "not measured: " + detail + from;
            case NOT_APPLICABLE -> "not applicable: " + detail;
        };
    }

    private static String join(List<EvidenceGroup> groups) {
        return groups.stream().map(EvidenceGroup::name).collect(Collectors.joining(", "));
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    /** Every group name, in evaluation order - used to build stable CSV headers. */
    public static List<String> groupNames() {
        return Arrays.asList("DOCUMENT_PORTRAIT", "CO_PRESENCE", "CHANNEL_AGREEMENT",
                "IDENTITY_BINDING", "LIVENESS");
    }
}
