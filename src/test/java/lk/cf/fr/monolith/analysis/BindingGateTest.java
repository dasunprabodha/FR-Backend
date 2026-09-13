package lk.cf.fr.monolith.analysis;

import lk.cf.fr.monolith.analysis.RegistrationAnalysisService.FaceAnalysis;
import lk.cf.fr.monolith.analysis.RegistrationAnalysisService.GateResult;
import lk.cf.fr.monolith.identity.IdentityBindingResult;
import lk.cf.fr.monolith.identity.IdentityBindingResult.BindingOutcome;
import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one switch on the decision rule: {@code verification.binding-gated}.
 *
 * <p>These tests exist mainly to pin the default. With the switch off the gate must behave exactly
 * as the rule this system shipped with, because that behaviour is the frozen baseline every later
 * measurement is compared against - if it drifts, the comparison is meaningless and nothing else
 * in the test suite would notice.
 */
class BindingGateTest {

    private RegistrationAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationAnalysisService(null, null, null, null, null);
        ReflectionTestUtils.setField(service, "bindingThreshold", 0.80);
        gate(false, false);
    }

    private void gate(boolean gated, boolean required) {
        ReflectionTestUtils.setField(service, "bindingGated", gated);
        ReflectionTestUtils.setField(service, "bindingRequired", required);
    }

    private static ComparisonResult pass(double similarity) {
        return new ComparisonResult(true, similarity, null, null, null);
    }

    private static ComparisonResult fail(double similarity) {
        return new ComparisonResult(false, similarity, null, null, null);
    }

    /** Every face comparison passing, with an optional comparison 4. */
    private static FaceAnalysis facesAllPassing() {
        return new FaceAnalysis(pass(88.0), pass(94.0), pass(91.0), pass(87.0), pass(90.0),
                null, true, true, true, 120L);
    }

    private static IdentityBindingResult bound() {
        return new IdentityBindingResult(BindingOutcome.EXACT_MATCH, 1.0,
                "199012345678", "199012345678", 0, "exact");
    }

    private static IdentityBindingResult mismatched() {
        return new IdentityBindingResult(BindingOutcome.MISMATCH, 0.15,
                "199012345678", "200155566677", 9, "different numbers");
    }

    private static IdentityBindingResult noEvidence() {
        return IdentityBindingResult.unavailable("199012345678", "no document supplied");
    }

    private static final LivenessOutcome LIVE = new LivenessOutcome(true, 91.0);

    @Nested
    @DisplayName("Default: binding not gated - the frozen baseline")
    class Baseline {

        @Test
        @DisplayName("A mismatched document number does not block approval")
        void mismatchedBindingStillApproves() {
            GateResult result = service.evaluateGate(facesAllPassing(), LIVE, mismatched());

            assertTrue(result.allPassed(),
                    "the shipped rule approves this - that is the finding, not a bug to fix here");
            assertNull(result.failureReason());
            assertFalse(result.bindingGated());
        }

        @Test
        @DisplayName("Binding is still measured and reported even though it did not count")
        void bindingIsReportedRegardless() {
            GateResult result = service.evaluateGate(facesAllPassing(), LIVE, mismatched());

            assertEquals(Boolean.FALSE, result.bindingPassed(),
                    "an evaluation run has to be able to measure the rule that was not in force");
        }
    }

    @Nested
    @DisplayName("Switched on: binding gated")
    class Gated {

        @BeforeEach
        void on() {
            gate(true, false);
        }

        @Test
        @DisplayName("A mismatched document number now routes the attempt for review")
        void mismatchedBindingBlocks() {
            GateResult result = service.evaluateGate(facesAllPassing(), LIVE, mismatched());

            assertFalse(result.allPassed());
            assertEquals("BINDING_MISMATCH", result.failureReason());
            assertTrue(result.bindingGated());
        }

        @Test
        @DisplayName("Similarity is still reported as passing - only the rule's verdict changed")
        void similarityVerdictIsUnaffected() {
            GateResult result = service.evaluateGate(facesAllPassing(), LIVE, mismatched());

            assertTrue(result.similarityPassed(),
                    "the faces did match; conflating that with the overall verdict would lose the finding");
        }

        @Test
        @DisplayName("A bound document approves exactly as before")
        void boundDocumentApproves() {
            GateResult result = service.evaluateGate(facesAllPassing(), LIVE, bound());

            assertTrue(result.allPassed());
            assertNull(result.failureReason());
        }

        @Test
        @DisplayName("Missing evidence is skipped, not failed, unless it is required")
        void absentBindingDoesNotBlockByDefault() {
            GateResult result = service.evaluateGate(facesAllPassing(), LIVE, noEvidence());

            assertTrue(result.allPassed(),
                    "absent binding must behave like an absent comparison 4 - skipped, not failed");
            assertNull(result.bindingPassed(), "no evidence is not the same as negative evidence");
        }

        @Test
        @DisplayName("Reason codes distinguish a contradicted number from a missing one")
        void requiredBindingBlocksOnAbsence() {
            gate(true, true);

            GateResult result = service.evaluateGate(facesAllPassing(), LIVE, noEvidence());

            assertFalse(result.allPassed());
            assertEquals("BINDING_UNAVAILABLE", result.failureReason(),
                    "an operator triaging the queue needs to tell these two apart");
        }

        @Test
        @DisplayName("Binding failing alongside something else reports both reasons")
        void reasonsCombine() {
            FaceAnalysis faces = new FaceAnalysis(pass(88.0), fail(60.0), pass(91.0), pass(87.0),
                    pass(90.0), null, true, true, true, 120L);

            GateResult result = service.evaluateGate(faces, LIVE, mismatched());

            assertEquals("LOW_SIMILARITY,BINDING_MISMATCH", result.failureReason());
        }
    }

    @Nested
    @DisplayName("Batch mode, where no liveness session exists")
    class NoLiveness {

        @Test
        @DisplayName("allPassed stays unknown, but binding can still block the similarity verdict")
        void bindingStillAppliesWithoutLiveness() {
            gate(true, false);

            GateResult result = service.evaluateGate(facesAllPassing(), null, mismatched());

            assertNull(result.allPassed(), "unknown, not failed - there was no liveness to check");
            assertEquals("BINDING_MISMATCH", result.failureReason());
        }
    }
}
