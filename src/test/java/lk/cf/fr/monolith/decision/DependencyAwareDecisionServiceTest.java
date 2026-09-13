package lk.cf.fr.monolith.decision;

import lk.cf.fr.monolith.analysis.RegistrationAnalysisService.CrossChannelConsistency;
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
        // Both repairs default off, so every expectation below still pins the recorded rule.
        ReflectionTestUtils.setField(service, "bindingCategorical", false);
        ReflectionTestUtils.setField(service, "divergenceInconclusive", false);
        ReflectionTestUtils.setField(service, "channelNaWithoutScan", false);
    }

    private void enable(String flag) {
        ReflectionTestUtils.setField(service, flag, true);
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

    /** As {@link #faces}, but with the cross-channel assessment the pipeline derives from cmp1/cmp4. */
    private static FaceAnalysis facesWithConsistency(Double c1, Double c2, Double c3, Double c4, Double c5) {
        boolean divergent = c1 != null && c4 != null && (c1 >= 80.0) != (c4 >= 80.0);
        Double delta = c1 == null || c4 == null ? null : Math.abs(c1 - c4);
        CrossChannelConsistency consistency = new CrossChannelConsistency(
                c1 == null || c4 == null ? "UNAVAILABLE" : divergent ? "DIVERGENT" : "CONSISTENT",
                delta, c1, c4, "test");
        return new FaceAnalysis(cmp(c1), cmp(c2), cmp(c3), cmp(c4), cmp(c5),
                consistency, true, true, c5 != null, 0L);
    }

    private static IdentityBindingResult formatEquivalent() {
        return new IdentityBindingResult(BindingOutcome.FORMAT_EQUIVALENT_MATCH, 0.80,
                "199934510785", "993451078V", 4, "old/new format equivalent");
    }

    private static IdentityBindingResult partial() {
        return new IdentityBindingResult(BindingOutcome.PARTIAL, 0.54, "1", "1", 1, "OCR damage");
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

    // -----------------------------------------------------------------------------------------
    // Ablation rung R3: verification.decision.binding-categorical
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("R3 off: a FORMAT_EQUIVALENT match scores exactly on the cutoff, so the band calls it marginal")
    void formatEquivalentMatchIsMarginalUnderTheBand() {
        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, 98.9, 100.0), null, formatEquivalent());

        // This is the defect, pinned: a documented positive match cannot support the decision,
        // because 0.80 sits inside a +/-0.10 band centred on 0.80. No threshold value fixes it.
        assertGroup(outcome, "IDENTITY_BINDING", EvidenceState.MARGINAL);
        assertEquals(Decision.REVIEW, outcome.decision());
    }

    @Test
    @DisplayName("R3 on: the outcome is read directly, so a format-equivalent match supports and approves")
    void formatEquivalentMatchSupportsWhenReadCategorically() {
        enable("bindingCategorical");

        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, 98.9, 100.0), null, formatEquivalent());

        assertGroup(outcome, "IDENTITY_BINDING", EvidenceState.SUPPORTS);
        assertEquals(Decision.APPROVE, outcome.decision());
    }

    @Test
    @DisplayName("R3 on: a mismatched number still contradicts - the repair loosens nothing that matters")
    void mismatchStillContradictsWhenReadCategorically() {
        enable("bindingCategorical");

        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, 98.9, 100.0), null, mismatched());

        assertGroup(outcome, "IDENTITY_BINDING", EvidenceState.CONTRADICTS);
        assertEquals(Decision.REJECT, outcome.decision());
    }

    @Test
    @DisplayName("R3 on: PARTIAL abstains rather than rejecting on OCR damage to the right number")
    void partialBindingAbstainsWhenReadCategorically() {
        enable("bindingCategorical");

        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, 98.9, 100.0), null, partial());

        assertGroup(outcome, "IDENTITY_BINDING", EvidenceState.MARGINAL);
        assertEquals(Decision.REVIEW, outcome.decision());
    }

    @Test
    @DisplayName("R3 on: an absent number is still absent, so the withheld-scan hole stays shut")
    void absentBindingStillBlocksWhenReadCategorically() {
        enable("bindingCategorical");

        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, null, null), null, unavailable());

        assertGroup(outcome, "IDENTITY_BINDING", EvidenceState.ABSENT);
        assertEquals(Decision.REVIEW, outcome.decision());
    }

    // -----------------------------------------------------------------------------------------
    // Ablation rung R4: verification.decision.divergence-inconclusive
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("R4 off: one poor document channel drags the group down and refuses an honest applicant")
    void divergentChannelsContradictByDefault() {
        // nipuna-G01: the presented card reads 93.83 against the live face, the uploaded scan 63.91.
        DecisionOutcome outcome = service.decide(
                facesWithConsistency(93.83, 99.9, 99.9, 63.91, 99.9), null, bound());

        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.CONTRADICTS);
        assertEquals(Decision.REJECT, outcome.decision());
    }

    @Test
    @DisplayName("R4 on: channels on opposite sides of the cutoff abstain, so it reviews instead of refusing")
    void divergentChannelsAbstainWhenEnabled() {
        enable("divergenceInconclusive");

        DecisionOutcome outcome = service.decide(
                facesWithConsistency(93.83, 99.9, 99.9, 63.91, 99.9), null, bound());

        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.MARGINAL);
        assertEquals(Decision.REVIEW, outcome.decision());
        // Abstaining is not approving: nothing here says the portrait matched.
        assertEquals("DOCUMENT_PORTRAIT", outcome.driverNames());
    }

    @Test
    @DisplayName("R4 on: two poor channels are agreement, not divergence, so a card swap still rejects")
    void bothChannelsPoorStillContradictsWhenEnabled() {
        enable("divergenceInconclusive");

        // A genuine card belonging to someone else, uploaded and presented: both channels fail.
        DecisionOutcome outcome = service.decide(
                facesWithConsistency(12.4, 99.9, 99.9, 8.7, 99.9), null, bound());

        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.CONTRADICTS);
        assertEquals(Decision.REJECT, outcome.decision());
    }

    @Test
    @DisplayName("R4 on: a divergent CLAIM_MISMATCH is still rejected, by the number check alone")
    void divergentClaimMismatchStillRejectsOnBinding() {
        enable("divergenceInconclusive");
        enable("bindingCategorical");

        // milan-A01-CLAIM and nipuna-A01-CLAIM are both divergent AND number-mismatched. The
        // portrait group steps back; binding carries the rejection on its own.
        DecisionOutcome outcome = service.decide(
                facesWithConsistency(97.37, 99.9, 99.9, 63.91, 99.9), null, mismatched());

        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.MARGINAL);
        assertEquals(Decision.REJECT, outcome.decision());
        assertEquals("IDENTITY_BINDING", outcome.driverNames());
    }

    @Test
    @DisplayName("R4 on: with no scan there is only one channel, so there is nothing to diverge")
    void singleChannelIsUnaffectedByTheDivergenceRepair() {
        enable("divergenceInconclusive");

        DecisionOutcome outcome = service.decide(
                facesWithConsistency(42.0, 99.9, 99.9, null, null), null, unavailable());

        assertGroup(outcome, "DOCUMENT_PORTRAIT", EvidenceState.CONTRADICTS);
        assertEquals(Decision.REJECT, outcome.decision());
    }

    // -----------------------------------------------------------------------------------------
    // Ablation rung R5: verification.decision.channel-na-without-scan
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("R5 off: no scan makes the channel group absent, which refers the sample to a human")
    void noScanLeavesChannelAgreementAbsentByDefault() {
        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, null, null), null, bound());

        assertGroup(outcome, "CHANNEL_AGREEMENT", EvidenceState.ABSENT);
        assertEquals(Decision.REVIEW, outcome.decision());
        assertEquals("CHANNEL_AGREEMENT", outcome.driverNames());
    }

    @Test
    @DisplayName("R5 on: with no scan there is no second channel, so the group is excluded and it approves")
    void noScanMakesChannelAgreementNotApplicable() {
        enable("channelNaWithoutScan");

        // The case the OCR fallback creates: no upload, but the number was read off the capture,
        // so binding carries real evidence and cmp5 is the only thing left with nothing to say.
        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, null, null), null, bound());

        assertGroup(outcome, "CHANNEL_AGREEMENT", EvidenceState.NOT_APPLICABLE);
        assertEquals(Decision.APPROVE, outcome.decision());
    }

    @Test
    @DisplayName("R5 on: a scan that was uploaded but unreadable is still ABSENT, not excluded")
    void unmeasurableChannelWithAScanIsStillAbsentUnderR5() {
        enable("channelNaWithoutScan");

        // The distinction R5 turns on: NOT_APPLICABLE means "this comparison does not exist here",
        // while ABSENT means "it should have been measurable and was not". A scan was supplied, so
        // cmp5 existed and failed - something went wrong, and a human should see it.
        FaceAnalysis analysis = new FaceAnalysis(cmp(98.9), cmp(99.9), cmp(99.9), cmp(98.9),
                unmeasured(), null, true, true, true, 0L);

        DecisionOutcome outcome = service.decide(analysis, null, bound());

        assertGroup(outcome, "CHANNEL_AGREEMENT", EvidenceState.ABSENT);
        assertEquals(Decision.REVIEW, outcome.decision());
    }

    @Test
    @DisplayName("R5 on: a scan-less card swap is still caught, because binding is what catches it")
    void noScanCardSwapStillRejectsUnderR5() {
        enable("channelNaWithoutScan");
        enable("bindingCategorical");

        // Someone else's card, no scan uploaded, but the OCR fallback read the card's own number:
        // it is not the number claimed, so binding contradicts and R5 has loosened nothing.
        DecisionOutcome outcome = service.decide(
                faces(12.4, 99.9, 99.9, null, null), null, mismatched());

        assertGroup(outcome, "CHANNEL_AGREEMENT", EvidenceState.NOT_APPLICABLE);
        assertEquals(Decision.REJECT, outcome.decision());
    }

    @Test
    @DisplayName("R5 on: no scan and no number read at all still goes to a human, not to approval")
    void noScanAndNoBindingStillReviewsUnderR5() {
        enable("channelNaWithoutScan");
        enable("bindingCategorical");

        // The withheld-evidence case with the fallback unavailable or unsuccessful. R5 removes the
        // channel group's objection, but binding is still absent, and that alone holds the sample.
        DecisionOutcome outcome = service.decide(
                faces(98.9, 99.9, 99.9, null, null), null, unavailable());

        assertGroup(outcome, "CHANNEL_AGREEMENT", EvidenceState.NOT_APPLICABLE);
        assertGroup(outcome, "IDENTITY_BINDING", EvidenceState.ABSENT);
        assertEquals(Decision.REVIEW, outcome.decision());
        assertEquals("IDENTITY_BINDING", outcome.driverNames());
    }
}
