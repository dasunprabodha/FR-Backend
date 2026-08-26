package lk.cf.fr.monolith.explain;

import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.registration.model.RegistrationApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DecisionExplanationServiceTest {

    private DecisionExplanationService service;

    @BeforeEach
    void setUp() {
        // The repository is only used by the lookup-by-id entry points; explain(record) is direct.
        service = new DecisionExplanationService(null);
        ReflectionTestUtils.setField(service, "bindingThreshold", 0.80);
    }

    /** A clean auto-approved attempt: everything comfortably over threshold, document bound. */
    private RegistrationRecord passingRecord() {
        RegistrationRecord r = new RegistrationRecord();
        r.setReferenceId("ref-pass");
        r.setNic("199012345678");
        r.setCifNo("CIF1001");
        r.setStatus(RegistrationApprovalStatus.AWS_APPROVED.name());
        r.setSimilarityThreshold(80.0);
        r.setLivenessThreshold(65.0);
        r.setMatch(true);
        r.setSimilarity(90.0);
        r.setMatch2(true);
        r.setSecondSimilarity(94.0);
        r.setMatch3(true);
        r.setThirdSimilarity(83.0);
        r.setLivenessPassed(true);
        r.setLivenessScore(91.0);
        r.setValidNicStatus("VALID");
        r.setExtractedNicNumber("199012345678");
        r.setNicBindingOutcome("EXACT_MATCH");
        r.setNicBindingScore(1.0);
        r.setNicBindingEditDistance(0);
        r.setOcrMeanLineConfidence(97.2);
        r.setReqTime(LocalDateTime.now().minusSeconds(12));
        r.setResTime(LocalDateTime.now());
        return r;
    }

    private EvidenceItem find(DecisionExplanation explanation, String id) {
        return explanation.evidence().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no evidence item with id " + id));
    }

    @Test
    @DisplayName("A passing attempt reports the narrowest-margin item as decisive")
    void passingAttemptMarksClosestCallDecisive() {
        DecisionExplanation x = service.explain(passingRecord());

        assertTrue(x.passed());
        // cmp3 at 83 has 3 points of headroom; cmp2 at 94 has 14; liveness at 91 has 26.
        assertTrue(find(x, "face.cmp3").decisive(), "cmp3 was the closest call and should be decisive");
        assertFalse(find(x, "face.cmp2").decisive());
        assertEquals(3.0, find(x, "face.cmp3").margin());
        assertTrue(x.counterfactual().summary().contains("not comfortably"));
    }

    @Test
    @DisplayName("Margins are signed: positive above threshold, negative below")
    void marginsAreSigned() {
        DecisionExplanation x = service.explain(passingRecord());

        assertEquals(14.0, find(x, "face.cmp2").margin());
        assertEquals(26.0, find(x, "liveness").margin());
        assertEquals(0.2, find(x, "identity.binding").margin(), 1e-9);
    }

    @Test
    @DisplayName("Comparison 1 and identity binding are reported as outside the decision rule")
    void informationalEvidenceIsMarkedUngated() {
        DecisionExplanation x = service.explain(passingRecord());

        assertFalse(find(x, "face.cmp1").gated(), "comparison 1 is informational");
        assertFalse(find(x, "identity.binding").gated(), "binding is deliberately held out");
        assertTrue(find(x, "face.cmp2").gated());
        assertTrue(find(x, "face.cmp3").gated());
        assertTrue(find(x, "liveness").gated());
    }

    @Test
    @DisplayName("A failing attempt names every failed item and what each needed")
    void failingAttemptProducesRequirements() {
        RegistrationRecord r = passingRecord();
        r.setReferenceId("ref-fail");
        r.setStatus(RegistrationApprovalStatus.PENDING_APPROVAL.name());
        r.setMatch3(false);
        r.setThirdSimilarity(71.5);
        r.setFailureReason("LOW_SIMILARITY");

        DecisionExplanation x = service.explain(r);

        assertFalse(x.passed());
        assertTrue(find(x, "face.cmp3").decisive());
        assertEquals("FAIL", find(x, "face.cmp3").status());
        assertEquals(-8.5, find(x, "face.cmp3").margin());

        assertEquals(1, x.counterfactual().requirements().size());
        var requirement = x.counterfactual().requirements().get(0);
        assertEquals("face.cmp3", requirement.evidenceId());
        assertEquals(8.5, requirement.shortfall(), 1e-9);
        assertTrue(x.counterfactual().summary().contains("8.5"));
    }

    @Test
    @DisplayName("Multiple failures are all decisive, and the summary says the rule needs all of them")
    void multipleFailuresAreAllDecisive() {
        RegistrationRecord r = passingRecord();
        r.setStatus(RegistrationApprovalStatus.PENDING_APPROVAL.name());
        r.setMatch3(false);
        r.setThirdSimilarity(70.0);
        r.setLivenessPassed(false);
        r.setLivenessScore(40.0);

        DecisionExplanation x = service.explain(r);

        assertTrue(find(x, "face.cmp3").decisive());
        assertTrue(find(x, "liveness").decisive());
        assertEquals(2, x.counterfactual().requirements().size());
        assertTrue(x.counterfactual().summary().contains("every one of them"));
    }

    @Test
    @DisplayName("KEY CASE: a passing attempt with a binding mismatch is where the two rules diverge")
    void bindingMismatchOnPassingAttemptDiverges() {
        RegistrationRecord r = passingRecord();
        // Genuine card, genuine face, but the card belongs to somebody else.
        r.setExtractedNicNumber("200155566677");
        r.setNicBindingOutcome("MISMATCH");
        r.setNicBindingScore(0.15);
        r.setNicBindingEditDistance(9);

        DecisionExplanation x = service.explain(r);

        assertTrue(x.passed(), "the deployed gate approves this attempt");
        assertEquals("FAIL", x.hypothetical().bindingGatedDecision());
        assertTrue(x.hypothetical().differsFromActual(),
                "adding binding to the rule must change this outcome - this is the whole comparison");
        assertTrue(x.hypothetical().explanation().contains("would have been rejected"));

        EvidenceItem binding = find(x, "identity.binding");
        assertEquals("FAIL", binding.status());
        assertFalse(binding.gated(), "still not gated - the baseline stays unmodified");
    }

    @Test
    @DisplayName("Binding that succeeds does not change the outcome either way")
    void bindingMatchDoesNotDiverge() {
        DecisionExplanation x = service.explain(passingRecord());

        assertEquals("PASS", x.hypothetical().bindingGatedDecision());
        assertFalse(x.hypothetical().differsFromActual());
    }

    @Test
    @DisplayName("With no document, binding is unavailable and that absence is called out")
    void noDocumentIsReportedAsAGapNotAPass() {
        RegistrationRecord r = passingRecord();
        r.setValidNicStatus("NOT_PROVIDED");
        r.setExtractedNicNumber(null);
        r.setNicBindingOutcome("UNAVAILABLE");
        r.setNicBindingScore(null);
        r.setNicBindingEditDistance(null);
        r.setOcrMeanLineConfidence(null);

        DecisionExplanation x = service.explain(r);

        assertEquals("UNCHANGED_NO_EVIDENCE", x.hypothetical().bindingGatedDecision());
        assertFalse(x.hypothetical().differsFromActual());
        assertEquals("UNAVAILABLE", find(x, "identity.binding").status());
        assertTrue(x.hypothetical().explanation().contains("nothing ties"),
                "a missing document must be reported as a gap, not silently as a pass");
        assertTrue(x.notes().stream().anyMatch(n -> n.contains("No scanned document")));
    }

    @Test
    @DisplayName("Comparison 4 is unavailable rather than failed when no document was uploaded")
    void comparison4AbsentIsUnavailable() {
        RegistrationRecord r = passingRecord();
        r.setMatch4(null);
        r.setFourthSimilarity(null);

        EvidenceItem cmp4 = find(service.explain(r), "face.cmp4");

        assertEquals("UNAVAILABLE", cmp4.status());
        assertFalse(cmp4.gated(), "an absent comparison must not be gated as a failure");
    }

    @Test
    @DisplayName("Evidence is ordered decisive-first so the reason is at the top")
    void evidenceIsRankedByInfluence() {
        RegistrationRecord r = passingRecord();
        r.setStatus(RegistrationApprovalStatus.PENDING_APPROVAL.name());
        r.setMatch3(false);
        r.setThirdSimilarity(60.0);

        DecisionExplanation x = service.explain(r);

        assertEquals("face.cmp3", x.evidence().get(0).id());
        assertTrue(x.evidence().get(0).decisive());
    }

    @Test
    @DisplayName("Stored per-attempt thresholds are used, not current configuration")
    void usesThresholdsSnapshottedOnTheAttempt() {
        RegistrationRecord r = passingRecord();
        r.setSimilarityThreshold(70.0);
        r.setLivenessThreshold(50.0);

        DecisionExplanation x = service.explain(r);

        assertEquals(70.0, find(x, "face.cmp2").threshold());
        assertEquals(50.0, find(x, "liveness").threshold());
        assertEquals(24.0, find(x, "face.cmp2").margin());
    }

    @Test
    @DisplayName("The explanation always states that component model internals are out of scope")
    void alwaysCarriesTheOpaqueModelCaveat() {
        DecisionExplanation x = service.explain(passingRecord());

        assertTrue(x.notes().stream().anyMatch(n -> n.contains("hosted AWS APIs")),
                "must not be presented as explaining how the scores were produced");
    }
}
