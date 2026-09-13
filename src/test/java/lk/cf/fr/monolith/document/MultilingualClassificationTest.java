package lk.cf.fr.monolith.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectTextRequest;
import software.amazon.awssdk.services.rekognition.model.DetectTextResponse;
import software.amazon.awssdk.services.rekognition.model.TextDetection;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The safety property of the multilingual pass: it may only ever <em>add</em> evidence.
 *
 * <p>This is the whole reason it can be switched on without re-validating the classifier. The
 * Latin ladder decides UNKNOWN / VALID / INVALID from the English third of the card; the local
 * Sinhala and Tamil pass is allowed to turn one flag on and nothing else. If that ever stops being
 * true, a student card with Sinhala wording on it could be promoted to a valid NIC, which is
 * precisely the misclassification the ladder is built to avoid.
 */
class MultilingualClassificationTest {

    /** A stub that reports whatever the local pass is supposed to have found. */
    private MultilingualNicTextExtractor extractorReturning(MultilingualNicText text) {
        MultilingualNicTextExtractor stub = mock(MultilingualNicTextExtractor.class);
        when(stub.extract(any())).thenReturn(text);
        return stub;
    }

    private MultilingualNicText found(boolean sinhala, boolean tamil) {
        return new MultilingualNicText(true, List.of(
                new MultilingualNicText.ScriptLine("ලංකා ජාතික හැඳුනුම්පත",
                        MultilingualNicText.Script.SINHALA, 82.0)),
                sinhala, tamil, true, List.of(), 82.0, 900L);
    }

    private RekognitionClient rekognitionReturning(String... lines) {
        RekognitionClient client = mock(RekognitionClient.class);
        List<TextDetection> detections = Arrays.stream(lines)
                .map(l -> TextDetection.builder().type("LINE").detectedText(l).confidence(95f).build())
                .toList();
        Answer<DetectTextResponse> answer =
                invocation -> DetectTextResponse.builder().textDetections(detections).build();
        when(client.detectText(any(DetectTextRequest.class))).thenAnswer(answer);
        return client;
    }

    private NicOcrResult classify(MultilingualNicText local, String... latinLines) {
        return new RekognitionDocumentProcessingService(
                rekognitionReturning(latinLines), extractorReturning(local))
                .validateNic(new byte[] {1, 2, 3}, null);
    }

    @Test
    @DisplayName("a card whose English third is unreadable is rescued by its Sinhala and Tamil thirds")
    void localScriptsPromoteAnUnknownCard() {
        // Glare across the top of the card: neither the English title nor the number survived, so
        // the Latin ladder has nothing to go on. The Sinhala and Tamil titles are lower down and
        // did read. This is the case the whole component exists for.
        String[] glared = {"UDUWIYA VALAGE DASUN", "1994/01/12"};

        NicOcrResult withoutLocal = classify(MultilingualNicText.unavailable(), glared);
        assertEquals(NicValidationOutcome.UNKNOWN, withoutLocal.outcome(),
                "from the English third alone this document cannot be identified at all");

        NicOcrResult withLocal = classify(found(true, true), glared);
        assertEquals(NicValidationOutcome.VALID, withLocal.outcome(),
                "the card says 'national identity card' twice in scripts DetectText cannot read");
        assertTrue(withLocal.nationalIdKeyword());
    }

    @Test
    @DisplayName("a card the Latin ladder already reads is left exactly as it was")
    void alreadyReadableCardsAreUntouched() {
        // A bare 12-digit number is itself national-ID evidence to the Latin ladder, so this card
        // is VALID with or without the local pass. Nothing about the reading may shift.
        NicOcrResult withoutLocal = classify(MultilingualNicText.unavailable(), "199401204166");
        NicOcrResult withLocal = classify(found(true, true), "199401204166");

        assertEquals(NicValidationOutcome.VALID, withoutLocal.outcome());
        assertEquals(withoutLocal.outcome(), withLocal.outcome());
        assertEquals(withoutLocal.extractedNicNumber(), withLocal.extractedNicNumber());
        assertEquals(withoutLocal.numberFormat(), withLocal.numberFormat());
        assertEquals("199401204166", withLocal.extractedNicNumber(), "the number reading is untouched");
    }

    @Test
    @DisplayName("it cannot rescue a student card, however much Sinhala is printed on it")
    void localScriptsNeverOverrideARejection() {
        NicOcrResult result = classify(found(true, true),
                "199401204166", "SLIIT STUDENT IDENTITY CARD");

        assertEquals(NicValidationOutcome.INVALID, result.outcome(),
                "the student-ID rejection does not consult the multilingual pass, by design");
    }

    @Test
    @DisplayName("it cannot rescue a driving licence either")
    void drivingLicenceStaysRejected() {
        assertEquals(NicValidationOutcome.INVALID,
                classify(found(true, true), "199401204166", "DRIVING LICENCE").outcome());
    }

    @Test
    @DisplayName("with the pass off, classification is bit-for-bit what it was before")
    void disabledChangesNothing() {
        String[] lines = {"NATIONAL IDENTITY CARD", "199401204166"};

        NicOcrResult off = classify(MultilingualNicText.unavailable(), lines);
        NicOcrResult on = classify(found(true, true), lines);

        assertEquals(off.outcome(), on.outcome());
        assertEquals(off.extractedNicNumber(), on.extractedNicNumber());
        assertEquals(off.nationalIdKeyword(), on.nationalIdKeyword());
        assertFalse(off.multilingual().available(), "and the reading records which pass ran");
        assertTrue(on.multilingual().available());
    }

    @Test
    @DisplayName("the local reading travels with the result, kept apart from Rekognition's")
    void theLocalReadingIsCarriedSeparately() {
        NicOcrResult result = classify(found(true, false), "199401204166");

        assertTrue(result.multilingual().available());
        assertTrue(result.multilingual().sinhalaNationalIdKeyword());
        assertFalse(result.multilingual().tamilNationalIdKeyword());
        assertEquals(1, result.multilingual().lines().size(),
                "which engine saw what is the measurement; merging them would lose it");
    }
}
