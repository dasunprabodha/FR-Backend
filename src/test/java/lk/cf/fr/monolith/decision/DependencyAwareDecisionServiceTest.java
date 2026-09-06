package lk.cf.fr.monolith.decision;

import lk.cf.fr.monolith.analysis.RegistrationAnalysisService.FaceAnalysis;
import lk.cf.fr.monolith.identity.IdentityBindingResult;
import lk.cf.fr.monolith.identity.IdentityBindingResult.BindingOutcome;
import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the proposed rule's verdict on every collection recipe in the field protocol.
 *
 * <h2>Why this test exists before the corpus does</h2>
 * <p>A decision rule written after seeing the data it is evaluated on measures nothing. These cases
 * fix the expected behaviour of each recipe in advance, so that when the corpus arrives the only
 * open question is how often each outcome occurs - not what the rule does. A change to the rule
 * that alters any expectation here is a change to the experiment, and should be argued for
 * explicitly rather than absorbed as a passing build.
 *
 * <p>Thresholds are the shipped ones (similarity 80, binding 0.80, liveness 65) with the default
 * margins, so these are the operating points the baseline is compared against.
 */
class DependencyAwareDecisionServiceTest {

    private DependencyAwareDecisionService service;

    @BeforeEach
    void setUp() {
        service = new DependencyAwareDecisionService();
        ReflectionTestUtils.setField(service, "similarityThreshold", 80.0);
        ReflectionTestUtils.setField(service, "bindingThreshold", 0.80);
        ReflectionTestUtils.setField(service, "livenessThreshold", 65.0);
        ReflectionTestUtils.setField(service, "similarityMargin", 5.0);
        ReflectionTestUtils.setField(service, "bindingMargin", 0.10);
        ReflectionTestUtils.setField(service, "livenessMargin", 10.0);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private static ComparisonResult cmp(Double similarity) {
        return similarity == null ? null
                : new ComparisonResult(similarity >= 80.0, similarity, null, null, null);
    }

    /** A comparison that ran but produced no measurement - no comparable face was detected. */
    private static ComparisonResult unmeasured() {
        return new ComparisonResult(false, null, null, null, null);
    }

    private static FaceAnalysis faces(Double c1, Double c2, Double c3, Double c4, Double c5) {
        return new FaceAnalysis(cmp(c1), cmp(c2), cmp(c3), cmp(c4), cmp(c5),
                null, true, true, c5 != null, 0L);
    }

    private static IdentityBindingResult bound() {
        return new IdentityBindingResult(BindingOutcome.EXACT_MATCH, 1.0, "1", "1", 0, "exact");
    }

    private static IdentityBindingResult mismatched() {
        return new IdentityBindingResult(BindingOutcome.MISMATCH, 0.15, "1", "2", 9, "mismatch");
    }

    private static IdentityBindingResult unavailable() {
        return IdentityBindingResult.unavailable("1", "no scan");
    }

    private static void assertGroup(DecisionOutcome outcome, String name, EvidenceState expected) {
        EvidenceGroup group = outcome.groups().stream()
                .filter(g -> g.name().equals(name)).findFirst().orElseThrow();
        assertEquals(expected, group.state(), name + " state");
    }

    // -----------------------------------------------------------------------------------------
    // Recipes
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("genuine, scan supplied: every group supports, so it auto-approves")
    void genuineApproves() {
        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, 98.9, 100.0), new LivenessOutcome(true, 90.0), bound());

        assertEquals(Decision.APPROVE, outcome.decision());
    }

    @Test
    @DisplayName("genuine with no scan: absent document evidence sends it to review, never to reject")
    void genuineWithoutScanGoesToReview() {
        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, null, null), new LivenessOutcome(true, 90.0), unavailable());

        assertEquals(Decision.REVIEW, outcome.decision());
        assertGroup(outcome, "CHANNEL_AGREEMENT", EvidenceState.ABSENT);
        assertGroup(outcome, "IDENTITY_BINDING", EvidenceState.ABSENT);
        // The distinction the baseline cannot express: nothing here argues against the applicant.
        assertTrue(outcome.contradicting().isEmpty());
    }

    @Test
    @DisplayName("CLAIM_MISMATCH: faces all pass honestly, binding contradicts, so it rejects")
    void claimMismatchRejects() {
        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, 98.9, 100.0), new LivenessOutcome(true, 90.0), mismatched());

        assertEquals(Decision.REJECT, outcome.decision());
        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.SUPPORTS);
        assertEquals("IDENTITY_BINDING", outcome.driverNames());
    }

    @Test
    @DisplayName("card swap with the partner's scan: rejected by portrait and by binding, independently")
    void swapRejects() {
        DecisionOutcome outcome = service.decide(
                faces(12.0, 99.9, 99.9, 11.0, 99.9), new LivenessOutcome(true, 90.0), mismatched());

        assertEquals(Decision.REJECT, outcome.decision());
        assertEquals(2, outcome.contradicting().size());
    }

    @Test
    @DisplayName("card swap with no scan: baseline approves on cmp2+cmp3; cmp1 alone rejects here")
    void swapWithoutScanRejectsOnComparisonOneAlone() {
        // The baseline gate is cmp2 && cmp3 && (cmp4 == null), so all three of its conjuncts hold
        // and it approves. cmp1 - measured, persisted, and excluded from that rule - is the only
        // signal that dissents.
        DecisionOutcome outcome = service.decide(
                faces(12.0, 99.9, 99.9, null, null), new LivenessOutcome(true, 90.0), unavailable());

        assertEquals(Decision.REJECT, outcome.decision());
        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.CONTRADICTS);
    }

    @Test
    @DisplayName("substituted card with own genuine scan: only cmp1 and cmp5 dissent, and both count")
    void substitutedCardRejects() {
        // Every gated conjunct of the baseline passes and binding reads EXACT_MATCH, because the
        // document channel is entirely honest - it is the physical card that was substituted.
        DecisionOutcome outcome = service.decide(
                faces(12.0, 99.9, 99.9, 98.9, 14.0), new LivenessOutcome(true, 90.0), bound());

        assertEquals(Decision.REJECT, outcome.decision());
        assertGroup(outcome, "CHANNEL_AGREEMENT", EvidenceState.CONTRADICTS);
        // cmp4 is high but cmp1 is not, and they are one card observed twice: the minimum governs.
        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.CONTRADICTS);
    }

    @Test
    @DisplayName("print of own card with genuine scan: nothing dissents, so both rules approve it")
    void printAttackIsNotDetected() {
        // Stated as an expectation, not a defect. The only PAD in the system inspects the face,
        // not the document, and no decision rule over these comparisons can see this attack.
        DecisionOutcome outcome = service.decide(
                faces(97.0, 99.0, 99.5, 97.5, 96.0), new LivenessOutcome(true, 90.0), bound());

        assertEquals(Decision.APPROVE, outcome.decision());
    }

    // -----------------------------------------------------------------------------------------
    // Properties of the rule itself
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a score just inside the margin is marginal, not a pass - the magnitude is kept")
    void marginBandCatchesBorderlinePasses() {
        // 82 clears the shipped cutoff of 80 and the baseline records it as an unqualified match.
        DecisionOutcome outcome = service.decide(
                faces(98.9, 82.0, 99.9, 98.9, 100.0), new LivenessOutcome(true, 90.0), bound());

        assertEquals(Decision.REVIEW, outcome.decision());
        assertGroup(outcome, "CO_PRESENCE", EvidenceState.MARGINAL);
    }

    @Test
    @DisplayName("dependent sources combine by minimum, so a strong cmp4 cannot rescue a weak cmp1")
    void minimumGovernsWithinAGroup() {
        DecisionOutcome outcome = service.decide(
                faces(20.0, 99.9, 99.9, 99.9, 99.9), new LivenessOutcome(true, 90.0), bound());

        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.CONTRADICTS);
        assertEquals(Decision.REJECT, outcome.decision());
    }

    @Test
    @DisplayName("an unmeasured comparison is skipped, never scored zero")
    void unmeasuredComparisonIsNotScoredZero() {
        FaceAnalysis analysis = new FaceAnalysis(unmeasured(), cmp(99.9), cmp(99.9), cmp(98.9),
                cmp(100.0), null, true, true, true, 0L);

        DecisionOutcome outcome = service.decide(analysis, new LivenessOutcome(true, 90.0), bound());

        // Scoring the undetectable face as 0.0 would contradict the group and reject the sample.
        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.SUPPORTS);
        assertEquals(Decision.APPROVE, outcome.decision());
    }

    @Test
    @DisplayName("offline replay has no liveness channel, so the group is excluded rather than absent")
    void missingLivenessChannelDoesNotForceEverySampleIntoReview() {
        // Corpus samples carry no liveness. Treating that as absent evidence would put the entire
        // corpus in REVIEW and make the comparison meaningless; the baseline gate already reports
        // allPassed as null in the same situation, so both rules exclude it identically.
        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, 98.9, 100.0), null, bound());

        assertEquals(Decision.APPROVE, outcome.decision());
        assertGroup(outcome, "LIVENESS", EvidenceState.NOT_APPLICABLE);
    }
}
