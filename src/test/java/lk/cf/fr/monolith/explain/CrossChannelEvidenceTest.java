package lk.cf.fr.monolith.explain;

import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.registration.model.RegistrationApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the cross-channel document evidence: comparison 5 (uploaded scan vs. presented card) and
 * the derived agreement between the two channels that each measure document portrait against live
 * face.
 */
class CrossChannelEvidenceTest {

    private DecisionExplanationService service;

    @BeforeEach
    void setUp() {
        service = new DecisionExplanationService(null);
        ReflectionTestUtils.setField(service, "bindingThreshold", 0.80);
    }

    /** Everything passes, both document channels agree, scan and presented card are the same. */
    private RegistrationRecord consistentRecord() {
        RegistrationRecord r = new RegistrationRecord();
        r.setReferenceId("ref-consistent");
        r.setNic("199012345678");
        r.setStatus(RegistrationApprovalStatus.AWS_APPROVED.name());
        r.setSimilarityThreshold(80.0);
        r.setLivenessThreshold(65.0);
        r.setMatch(true);
        r.setSimilarity(90.0);
        r.setMatch2(true);
        r.setSecondSimilarity(94.0);
        r.setMatch3(true);
        r.setThirdSimilarity(88.0);
        r.setMatch4(true);
        r.setFourthSimilarity(91.0);
        r.setMatch5(true);
        r.setFifthSimilarity(96.0);
        r.setCrossChannelStatus("CONSISTENT");
        r.setCrossChannelDelta(1.0);
        r.setLivenessPassed(true);
        r.setLivenessScore(91.0);
        r.setValidNicStatus("VALID");
        r.setExtractedNicNumber("199012345678");
        r.setNicBindingOutcome("EXACT_MATCH");
        r.setNicBindingScore(1.0);
        r.setReqTime(LocalDateTime.now().minusSeconds(14));
        r.setResTime(LocalDateTime.now());
        return r;
    }

    private EvidenceItem find(DecisionExplanation x, String id) {
        return x.evidence().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no evidence item " + id));
    }

    @Test
    @DisplayName("Comparison 5 and the channel-agreement signal both appear, and neither is gated")
    void newEvidenceIsPresentAndUngated() {
        DecisionExplanation x = service.explain(consistentRecord());

        EvidenceItem cmp5 = find(x, "face.cmp5");
        EvidenceItem cross = find(x, "document.crossChannel");

        assertEquals("PASS", cmp5.status());
        assertEquals(96.0, cmp5.value());
        assertFalse(cmp5.gated(), "cmp5 must not change the frozen baseline decision");

        assertEquals("PASS", cross.status());
        assertEquals("CONSISTENT", cross.categoricalValue());
        assertFalse(cross.gated());
    }

    @Test
    @DisplayName("KEY CASE: a substituted document is caught only by the cross-channel evidence")
    void substitutedDocumentIsInvisibleToTheGate() {
        RegistrationRecord r = consistentRecord();
        // Applicant presents card A to the camera but uploads a clean scan of card B. Every gated
        // check is internally consistent: the presented card matches itself across captures, the
        // face matches itself, and the uploaded scan matches the live face.
        r.setMatch(false);          // cmp1: presented card portrait vs live face - NOT gated
        r.setSimilarity(41.0);
        r.setMatch5(false);         // cmp5: the two documents are not the same card
        r.setFifthSimilarity(23.0);
        r.setCrossChannelStatus("DIVERGENT");
        r.setCrossChannelDelta(50.0);

        DecisionExplanation x = service.explain(r);

        assertTrue(x.passed(), "the deployed gate still approves - that is the finding");
        assertEquals(0, x.evidence().stream().filter(EvidenceItem::gated).filter(EvidenceItem::isFailure).count(),
                "no gated evidence failed");

        assertEquals("FAIL", find(x, "face.cmp5").status());
        assertEquals("FAIL", find(x, "document.crossChannel").status());
        assertEquals("DIVERGENT", find(x, "document.crossChannel").categoricalValue());
        assertTrue(find(x, "document.crossChannel").detail().contains("substituted document"));
    }

    @Test
    @DisplayName("A wide but same-verdict gap is reported as MARGINAL, not a failure to ignore")
    void marginalGapIsSurfaced() {
        RegistrationRecord r = consistentRecord();
        r.setCrossChannelStatus("MARGINAL");
        r.setCrossChannelDelta(27.5);

        EvidenceItem cross = find(service.explain(r), "document.crossChannel");

        assertEquals("FAIL", cross.status(), "MARGINAL is not healthy and must not read as a pass");
        assertTrue(cross.detail().contains("27.5"));
    }

    @Test
    @DisplayName("Without an uploaded scan, both new items report the absence rather than a pass")
    void noScanIsReportedAsAGap() {
        RegistrationRecord r = consistentRecord();
        r.setMatch4(null);
        r.setFourthSimilarity(null);
        r.setMatch5(null);
        r.setFifthSimilarity(null);
        r.setCrossChannelStatus("UNAVAILABLE");
        r.setCrossChannelDelta(null);
        r.setValidNicStatus("NOT_PROVIDED");

        DecisionExplanation x = service.explain(r);

        assertEquals("UNAVAILABLE", find(x, "face.cmp5").status());
        assertEquals("UNAVAILABLE", find(x, "document.crossChannel").status());
        assertTrue(find(x, "face.cmp5").detail().contains("could not be cross-checked"));
    }

    @Test
    @DisplayName("A comparison that ran but detected no face is a gated FAIL, not silently absent")
    void noFaceDetectedOnAGatedEdgeIsAFailure() {
        RegistrationRecord r = consistentRecord();
        r.setStatus(RegistrationApprovalStatus.PENDING_APPROVAL.name());
        r.setMatch3(false);
        r.setThirdSimilarity(null);   // ran, but no comparable face found

        EvidenceItem cmp3 = find(service.explain(r), "face.cmp3");

        assertEquals("FAIL", cmp3.status());
        assertTrue(cmp3.gated());
        assertNull(cmp3.value(), "no measurement must stay null rather than becoming a spurious 0.0");
        assertEquals("NO_FACE_DETECTED", cmp3.categoricalValue());
    }

    @Test
    @DisplayName("The channel-agreement item carries no bar, since smaller is better on its scale")
    void channelAgreementIsCategorical() {
        EvidenceItem cross = find(service.explain(consistentRecord()), "document.crossChannel");

        assertEquals("CATEGORICAL", cross.unit());
        assertNull(cross.value(), "a lower-is-better gap must not render on a higher-is-better bar");
        assertNull(cross.threshold());
    }
}
