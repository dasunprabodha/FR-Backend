package lk.cf.fr.monolith.identity;

import lk.cf.fr.monolith.document.NicOcrResult;
import lk.cf.fr.monolith.document.NicValidationOutcome;
import lk.cf.fr.monolith.identity.IdentityBindingResult.BindingOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdentityBindingServiceTest {

    private final IdentityBindingService service = new IdentityBindingService();

    /** Minimal OCR result carrying just the candidate numbers, which is all binding reads. */
    private static NicOcrResult ocrWith(String... candidates) {
        List<String> list = List.of(candidates);
        String primary = list.isEmpty() ? null : list.get(0);
        return new NicOcrResult(NicValidationOutcome.VALID, primary,
                primary == null ? null : SriLankanNicFormat.canonicalise(primary),
                list, NicOcrResult.NicNumberFormat.NEW_12_DIGIT, false,
                97.5, 91.0, List.of(), true, false, false);
    }

    @Test
    @DisplayName("Identical numbers bind exactly")
    void exactMatch() {
        IdentityBindingResult result = service.bind("199012345678", ocrWith("199012345678"));

        assertEquals(BindingOutcome.EXACT_MATCH, result.outcome());
        assertEquals(1.0, result.score());
        assertEquals(0, result.editDistance());
        assertTrue(result.isMatch());
    }

    @Test
    @DisplayName("Whitespace and case differences do not affect binding")
    void normalisesBothSides() {
        assertEquals(BindingOutcome.EXACT_MATCH,
                service.bind(" 751932523 v ", ocrWith("751932523V")).outcome());
    }

    @Test
    @DisplayName("An OCR digit-shape misread still binds, at a reduced score")
    void confusionCorrectedMatch() {
        // Rekognition read the 5 as an S.
        IdentityBindingResult result = service.bind("199012345678", ocrWith("19901234S678"));

        assertEquals(BindingOutcome.CONFUSION_CORRECTED_MATCH, result.outcome());
        assertEquals(0.85, result.score());
        assertTrue(result.isMatch());
    }

    @Test
    @DisplayName("Old-format card binds to a new-format claim (and the reverse)")
    void formatEquivalentMatch() {
        IdentityBindingResult cardIsOld = service.bind("197519302523", ocrWith("751932523V"));
        assertEquals(BindingOutcome.FORMAT_EQUIVALENT_MATCH, cardIsOld.outcome());
        assertEquals(0.80, cardIsOld.score());
        assertTrue(cardIsOld.isMatch());

        IdentityBindingResult cardIsNew = service.bind("751932523V", ocrWith("197519302523"));
        assertEquals(BindingOutcome.FORMAT_EQUIVALENT_MATCH, cardIsNew.outcome());
    }

    @Test
    @DisplayName("A different person's genuine NIC is a MISMATCH - the attack the gate could not see")
    void mismatchedGenuineNic() {
        IdentityBindingResult result = service.bind("199012345678", ocrWith("200155566677"));

        assertEquals(BindingOutcome.MISMATCH, result.outcome());
        assertFalse(result.isMatch());
        assertTrue(result.hasEvidence(), "a contradicting document is evidence, not absence of it");
        assertTrue(result.score() < 0.6, "mismatch must stay well below the match band: " + result.score());
    }

    @Test
    @DisplayName("A near-miss is PARTIAL, and still cannot reach the match band")
    void partialNeverReachesMatchBand() {
        IdentityBindingResult result = service.bind("199012345678", ocrWith("199012345679"));

        assertEquals(BindingOutcome.PARTIAL, result.outcome());
        assertFalse(result.isMatch());
        assertEquals(1, result.editDistance());
        assertTrue(result.score() <= 0.60,
                "graded band is capped at 0.60 so it can never be confused with a match: " + result.score());
        assertTrue(result.score() > 0.4, "a one-character difference should still score highly within its band");
    }

    @Test
    @DisplayName("No document means UNAVAILABLE with a null score, not a zero")
    void noDocumentIsUnavailable() {
        IdentityBindingResult result = service.bind("199012345678", NicOcrResult.notProvided());

        assertEquals(BindingOutcome.UNAVAILABLE, result.outcome());
        assertNull(result.score(), "absence of evidence must be distinguishable from a score of zero");
        assertFalse(result.hasEvidence());
    }

    @Test
    @DisplayName("A document OCR could not read a number from is also UNAVAILABLE")
    void unreadableDocumentIsUnavailable() {
        assertEquals(BindingOutcome.UNAVAILABLE,
                service.bind("199012345678", NicOcrResult.unknown()).outcome());
    }

    @Test
    @DisplayName("A missing claimed NIC is UNAVAILABLE rather than an exception")
    void missingClaimIsUnavailable() {
        assertEquals(BindingOutcome.UNAVAILABLE, service.bind(null, ocrWith("199012345678")).outcome());
        assertEquals(BindingOutcome.UNAVAILABLE, service.bind("  ", ocrWith("199012345678")).outcome());
    }

    @Test
    @DisplayName("A card printing both formats binds on whichever one matches")
    void picksTheBestCandidate() {
        // Real new-format cards often show the holder's original 9-digit number too. Here the
        // claim matches the second candidate, not the first.
        IdentityBindingResult result = service.bind("751932523V",
                ocrWith("200155566677", "751932523V"));

        assertEquals(BindingOutcome.EXACT_MATCH, result.outcome());
        assertEquals("751932523V", result.extracted());
    }

    @Test
    @DisplayName("With no matching candidate, the closest one is reported")
    void reportsStrongestOfSeveralNonMatches() {
        IdentityBindingResult result = service.bind("199012345678",
                ocrWith("888888888888", "199012345679"));

        assertEquals(BindingOutcome.PARTIAL, result.outcome());
        assertEquals("199012345679", result.extracted());
    }

    @Test
    @DisplayName("Score ordering encodes decreasing confidence and never crosses the band boundary")
    void scoreBandsAreOrdered() {
        double exact = service.bind("199012345678", ocrWith("199012345678")).score();
        double confusion = service.bind("199012345678", ocrWith("19901234S678")).score();
        double formatEquivalent = service.bind("197519302523", ocrWith("751932523V")).score();
        double partial = service.bind("199012345678", ocrWith("199012345679")).score();
        double mismatch = service.bind("199012345678", ocrWith("200155566677")).score();

        assertTrue(exact > confusion, "exact > confusion-corrected");
        assertTrue(confusion > formatEquivalent, "confusion-corrected > format-equivalent");
        assertTrue(formatEquivalent > partial, "every match outcome outranks every non-match");
        assertTrue(partial > mismatch, "partial > mismatch");
        assertTrue(formatEquivalent - partial >= 0.2, "a clear gap separates the match and non-match bands");
    }
}
