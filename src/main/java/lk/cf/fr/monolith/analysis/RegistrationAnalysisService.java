package lk.cf.fr.monolith.analysis;

import lk.cf.fr.monolith.card.CardDetectorService;
import lk.cf.fr.monolith.document.DocumentProcessingService;
import lk.cf.fr.monolith.document.NicOcrResult;
import lk.cf.fr.monolith.identity.IdentityBindingResult;
import lk.cf.fr.monolith.identity.IdentityBindingService;
import lk.cf.fr.monolith.recognition.FaceRecognitionService;
import lk.cf.fr.monolith.registration.service.ComparisonImageDumpService;
import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The registration pipeline's analysis stage, extracted verbatim from
 * {@code RegistrationService.runRegistration} so it can run without a physical device attached.
 *
 * <h2>Why this extraction matters</h2>
 * <p>Previously the four face comparisons, the card cropping that feeds them and the pass/fail
 * gate were interleaved with three blocking WebSocket captures inside one method. That coupling
 * made the analysis unreachable except by a human standing at an Android device completing a live
 * capture sequence with 120-second timeouts - which in turn made repeatable experiments,
 * cross-condition sweeps and regression tests impossible. Separating "obtain the images" from
 * "analyse the images" gives the same logic two callers:
 *
 * <ul>
 *   <li>{@code RegistrationService}, which still drives the live device capture and then calls in
 *       here, in exactly the original order.</li>
 *   <li>{@code BatchEvaluationService}, which reads stored image sets off disk and calls the same
 *       methods, with no device involved.</li>
 * </ul>
 *
 * <p>Both paths therefore exercise identical analysis code - an evaluation run measures the real
 * pipeline, not a reimplementation of it.
 *
 * <h2>Ordering constraint</h2>
 * <p>Document analysis and face analysis are separate entry points rather than one call, because
 * the live path cannot reorder them: the device blocks in its NIC-waiting state until the backend
 * reports the document-check result, so OCR has to run between the first and second captures,
 * while the face comparisons can only run once all three captures are in hand. The batch path,
 * having every image up front, simply calls both in sequence.
 *
 * <p><b>The gate is unchanged.</b> {@link #evaluateGate} reproduces the original conjunctive rule
 * exactly, including comparison 1 being informational and comparison 4 being conditional on a
 * scanned NIC having been supplied. Identity binding is computed and returned but deliberately
 * left out of the gate, so the existing decision behaviour remains a valid frozen baseline.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RegistrationAnalysisService {

    private final CardDetectorService cardDetectorService;
    private final FaceRecognitionService faceRecognitionService;
    private final DocumentProcessingService documentProcessingService;
    private final IdentityBindingService identityBindingService;
    private final ComparisonImageDumpService comparisonImageDumpService;

    /**
     * How far apart, in similarity points, the two document channels may sit before their
     * agreement is called into question. Deliberately generous by default: a printed card
     * photographed by a device camera and a flatbed scan of the same card legitimately differ,
     * so this flags gross divergence rather than ordinary capture variation.
     */
    @Value("${analysis.cross-channel-tolerance:20}")
    private double crossChannelTolerance;

    /**
     * OCR the optionally-supplied scanned NIC and bind the number it carries to the claimed NIC.
     *
     * @param scannedNicBytes the uploaded document, or {@code null} if none was supplied
     */
    public DocumentAnalysis analyseDocument(String referenceId, String claimedNic,
                                             byte[] scannedNicBytes, Boolean mockNicValid) {
        long start = System.currentTimeMillis();

        if (scannedNicBytes == null) {
            log.info("[Analysis][Document] referenceId={} nic={} no scannedNIC supplied -> NOT_PROVIDED (skipping OCR)",
                    referenceId, claimedNic);
            NicOcrResult ocr = NicOcrResult.notProvided();
            return new DocumentAnalysis(ocr, identityBindingService.bind(claimedNic, ocr),
                    System.currentTimeMillis() - start);
        }

        log.info("[Analysis][Document] referenceId={} nic={} scannedNIC supplied ({} bytes) - running OCR",
                referenceId, claimedNic, scannedNicBytes.length);

        NicOcrResult ocr = documentProcessingService.validateNic(scannedNicBytes, mockNicValid);
        IdentityBindingResult binding = identityBindingService.bind(claimedNic, ocr);

        log.info("[Analysis][Document] referenceId={} outcome={} extractedNic={} binding={} score={}",
                referenceId, ocr.statusName(), ocr.extractedNicNumber(), binding.outcome(), binding.score());

        return new DocumentAnalysis(ocr, binding, System.currentTimeMillis() - start);
    }

    /**
     * Crop the card regions and run the four pairwise face comparisons.
     *
     * <p>Cropping fixes a real failure mode where the device-captured {@code nicImage} frames the
     * user's live face more prominently than the card, causing Rekognition to compare against the
     * live face instead of the small printed photo. Comparison 2 is a card-vs-card comparison so
     * both sides are cropped; comparison 3 intentionally keeps the full uncropped {@code selfImage}
     * because it needs the live face there, not the card. A failed detection falls back to the
     * original bytes, so a missed crop degrades accuracy rather than blocking the run.
     */
    public FaceAnalysis analyseFaces(String referenceId, byte[] nicImage, byte[] faceImage,
                                      byte[] selfImage, byte[] scannedNicBytes, Double mockSimilarity) {
        long start = System.currentTimeMillis();

        byte[] nicCardCrop = cardDetectorService.cropCard(nicImage, "DeviceNIC", referenceId);
        byte[] nicForComparison = nicCardCrop != null ? nicCardCrop : nicImage;
        log.info("[Analysis][Card-Crop] referenceId={} DeviceNIC crop {} ({} bytes -> {} bytes)",
                referenceId, nicCardCrop != null ? "succeeded" : "FAILED (falling back to full image)",
                nicImage.length, nicForComparison.length);

        byte[] selfNicCardCrop = cardDetectorService.cropCard(selfImage, "SelfNIC", referenceId);
        byte[] selfNicForComparison = selfNicCardCrop != null ? selfNicCardCrop : selfImage;
        log.info("[Analysis][Card-Crop] referenceId={} SelfNIC crop {} ({} bytes -> {} bytes)",
                referenceId, selfNicCardCrop != null ? "succeeded" : "FAILED (falling back to full image)",
                selfImage.length, selfNicForComparison.length);

        // The uploaded scan is now cropped too. It previously went into comparison 4 as raw bytes
        // while both device captures were cropped - an omission carried over from the port, not a
        // decision: CardDetectorService's own NIC aspect-ratio gate already recognises a
        // "ScannedNIC" tag, so the legacy pipeline cropped it. Leaving it uncropped exposed
        // comparison 4 to exactly the failure cropping exists to prevent, where Rekognition locks
        // onto the wrong face region in a wide scan instead of the small printed portrait.
        byte[] scannedNicCardCrop = scannedNicBytes != null
                ? cardDetectorService.cropCard(scannedNicBytes, "ScannedNIC", referenceId)
                : null;
        byte[] scannedNicForComparison = scannedNicCardCrop != null ? scannedNicCardCrop : scannedNicBytes;
        if (scannedNicBytes != null) {
            log.info("[Analysis][Card-Crop] referenceId={} ScannedNIC crop {} ({} bytes -> {} bytes)",
                    referenceId, scannedNicCardCrop != null ? "succeeded" : "FAILED (falling back to full image)",
                    scannedNicBytes.length, scannedNicForComparison.length);
        }

        ComparisonResult cmp1 = faceRecognitionService.compareFacesInMemory(nicForComparison, faceImage, mockSimilarity);
        comparisonImageDumpService.dump(referenceId, "cmp1-deviceNicVsFace", nicForComparison, faceImage, cmp1);

        ComparisonResult cmp2 = faceRecognitionService.compareFacesInMemory(nicForComparison, selfNicForComparison, mockSimilarity);
        comparisonImageDumpService.dump(referenceId, "cmp2-deviceNicVsSelfNic", nicForComparison, selfNicForComparison, cmp2);

        ComparisonResult cmp3 = faceRecognitionService.compareFacesInMemory(faceImage, selfImage, mockSimilarity);
        comparisonImageDumpService.dump(referenceId, "cmp3-faceVsSelfFace", faceImage, selfImage, cmp3);

        ComparisonResult cmp4 = scannedNicBytes != null
                ? faceRecognitionService.compareFacesInMemory(scannedNicForComparison, faceImage, mockSimilarity)
                : null;
        if (cmp4 != null) {
            comparisonImageDumpService.dump(referenceId, "cmp4-scannedNicVsFace", scannedNicForComparison, faceImage, cmp4);
        }

        // Comparison 5 - the edge that did not exist. Nothing previously compared the uploaded scan
        // against the card physically presented to the camera: the scan was only ever checked
        // against the live face (cmp4), and the presented card only ever against itself (cmp2).
        // Since OCR and document authenticity also only ever inspect the upload, a forged or
        // substituted physical card paired with a clean scan of a genuine one satisfied every gated
        // check. This asks whether the two channels are even showing the same document.
        ComparisonResult cmp5 = scannedNicBytes != null
                ? faceRecognitionService.compareFacesInMemory(scannedNicForComparison, nicForComparison, mockSimilarity)
                : null;
        if (cmp5 != null) {
            comparisonImageDumpService.dump(referenceId, "cmp5-scannedNicVsDeviceNic",
                    scannedNicForComparison, nicForComparison, cmp5);
            log.info("[Analysis][Cross-Channel] referenceId={} scannedNIC vs deviceNIC match={} similarity={}",
                    referenceId, cmp5.match(), cmp5.similarity());
        }

        CrossChannelConsistency consistency = assessCrossChannel(cmp1, cmp4);
        log.info("[Analysis][Cross-Channel] referenceId={} document-portrait channels -> {}",
                referenceId, consistency.status());

        return new FaceAnalysis(cmp1, cmp2, cmp3, cmp4, cmp5, consistency,
                nicCardCrop != null, selfNicCardCrop != null, scannedNicCardCrop != null,
                System.currentTimeMillis() - start);
    }

    /**
     * Compares the two independent measurements of the same relationship.
     *
     * <p>Comparison 1 and comparison 4 ask an identical question - does the portrait printed on the
     * document match the live face? - of two different document channels: the card held up to the
     * camera, and the file uploaded by the officer. One is gated and the other is not, purely for
     * historical reasons. Read together, their <em>disagreement</em> is a signal neither provides
     * alone: two different documents that both contain a plausible face will separate here while
     * each comparison on its own looks unremarkable.
     *
     * <p>Derived from measurements already taken, so it costs no additional API call.
     */
    private CrossChannelConsistency assessCrossChannel(ComparisonResult deviceChannel, ComparisonResult scanChannel) {
        if (deviceChannel == null || scanChannel == null
                || !deviceChannel.hasMeasurement() || !scanChannel.hasMeasurement()) {
            return new CrossChannelConsistency("UNAVAILABLE", null,
                    deviceChannel == null ? null : deviceChannel.similarity(),
                    scanChannel == null ? null : scanChannel.similarity(),
                    "Only one document channel produced a measurement, so the two cannot be compared. "
                            + "Without an uploaded scan there is nothing to cross-check the presented card against.");
        }

        double delta = Math.abs(deviceChannel.similarity() - scanChannel.similarity());
        boolean verdictsDisagree = deviceChannel.match() != scanChannel.match();

        String status;
        String detail;
        if (verdictsDisagree) {
            status = "DIVERGENT";
            detail = String.format("The two document channels disagree outright: the presented card scored %.1f%% "
                            + "against the live face while the uploaded scan scored %.1f%%, landing on opposite "
                            + "sides of the threshold. That is the signature of two different documents.",
                    deviceChannel.similarity(), scanChannel.similarity());
        } else if (delta > crossChannelTolerance) {
            status = "MARGINAL";
            detail = String.format("Both document channels reached the same verdict but %.1f points apart "
                            + "(%.1f%% presented vs %.1f%% uploaded), beyond the %.0f-point tolerance. Either the two "
                            + "images show different documents, or one is markedly poorer quality.",
                    delta, deviceChannel.similarity(), scanChannel.similarity(), crossChannelTolerance);
        } else {
            status = "CONSISTENT";
            detail = String.format("Both document channels agree within %.1f points (%.1f%% presented vs %.1f%% "
                            + "uploaded), consistent with the same document having been presented and uploaded.",
                    delta, deviceChannel.similarity(), scanChannel.similarity());
        }

        return new CrossChannelConsistency(status, round(delta),
                deviceChannel.similarity(), scanChannel.similarity(), detail);
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    /**
     * The frozen baseline decision rule, moved but not modified.
     *
     * <p>Comparison 1 (device NIC vs. live face) is informational and excluded from the gate;
     * comparison 4 only participates when a scanned NIC was actually supplied; liveness is a
     * required conjunct. Identity binding is intentionally absent - see the class doc.
     *
     * @param liveness may be {@code null} in batch mode, where no device liveness session exists.
     *                 {@link GateResult#allPassed()} is then {@code null} - unknown, not failed -
     *                 while {@link GateResult#similarityPassed()} remains fully determined.
     */
    public GateResult evaluateGate(FaceAnalysis faces, LivenessOutcome liveness) {
        boolean similarityPassed = faces.cmp2().match()
                && faces.cmp3().match()
                && (faces.cmp4() == null || faces.cmp4().match());

        if (liveness == null) {
            String reason = similarityPassed ? null : "LOW_SIMILARITY";
            return new GateResult(similarityPassed, null, reason);
        }

        boolean allPassed = similarityPassed && liveness.passed();
        return new GateResult(similarityPassed, allPassed, buildFailureReason(similarityPassed, liveness.passed()));
    }

    /** Null when everything passed, otherwise the original comma-joined reason codes. */
    private String buildFailureReason(boolean similarityPassed, boolean livenessPassed) {
        if (similarityPassed && livenessPassed) {
            return null;
        }
        StringBuilder reason = new StringBuilder();
        if (!similarityPassed) {
            reason.append("LOW_SIMILARITY");
        }
        if (!livenessPassed) {
            if (reason.length() > 0) {
                reason.append(",");
            }
            reason.append("LIVENESS_FAILED");
        }
        return reason.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Result types
    // ---------------------------------------------------------------------------------------

    public record DocumentAnalysis(NicOcrResult ocr, IdentityBindingResult binding, long latencyMs) {
    }

    /**
     * @param cmp5        uploaded scan vs. physically presented card - null when no scan was supplied
     * @param consistency derived cross-channel agreement between cmp1 and cmp4
     */
    public record FaceAnalysis(ComparisonResult cmp1, ComparisonResult cmp2, ComparisonResult cmp3,
                                ComparisonResult cmp4, ComparisonResult cmp5,
                                CrossChannelConsistency consistency,
                                boolean nicCardCropped, boolean selfNicCardCropped, boolean scannedNicCardCropped,
                                long latencyMs) {
    }

    /**
     * Agreement between the two document channels that each independently measure the document
     * portrait against the live face.
     *
     * @param status CONSISTENT | MARGINAL | DIVERGENT | UNAVAILABLE
     * @param delta  absolute difference in similarity points between the two channels
     */
    public record CrossChannelConsistency(String status, Double delta,
                                           Double deviceChannelSimilarity, Double scanChannelSimilarity,
                                           String detail) {

        public boolean isDivergent() {
            return "DIVERGENT".equals(status);
        }

        public boolean hasEvidence() {
            return !"UNAVAILABLE".equals(status);
        }
    }

    public record GateResult(boolean similarityPassed, Boolean allPassed, String failureReason) {
    }
}
