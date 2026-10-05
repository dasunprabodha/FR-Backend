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
 * <h2>The gate, and the one switch on it</h2>
 * <p>{@link #evaluateGate} reproduces the original conjunctive rule exactly - comparison 1
 * informational, comparison 4 conditional on a scanned NIC having been supplied, liveness
 * required - and adds identity binding as an <em>optional</em> conjunct controlled by
 * {@code verification.binding-gated}.
 *
 * <p>That switch defaults to <b>false</b>, and the default is load-bearing. With it off, the
 * decision behaviour is byte-identical to the rule this system shipped with, which is what makes
 * that rule a legitimate frozen baseline rather than a strawman: the comparison is against real
 * deployed behaviour, measured on the same attempts. Turn it on and the baseline is gone - there
 * is no longer anything to compare the proposed rule against, and the Evidence Dashboard's
 * rule-comparison panel has no contrast left to show.
 *
 * <p>So: record the baseline on a labelled corpus first, then flip the switch. Both rules are
 * reachable by configuration alone, which is what lets an ablation run them over identical data
 * without a code change between rungs.
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
     * Whether identity binding participates in the gate. False is the frozen baseline (B0) and is
     * the shipped default; see {@code application.yml} for why, and {@link #evaluateGate}.
     */
    @Value("${verification.binding-gated:false}")
    private boolean bindingGated;

    /** When gated, whether absent binding evidence counts as a failure rather than being skipped. */
    @Value("${verification.binding-required:false}")
    private boolean bindingRequired;

    /** Bottom of the positive match band in {@code IdentityBindingService}. */
    @Value("${verification.binding-threshold:0.80}")
    private double bindingThreshold;

    /**
     * Whether OCR falls back to the device-captured card when no scan was uploaded.
     *
     * <p>False is the frozen baseline: with no upload there is no OCR, so identity binding reports
     * itself UNAVAILABLE and the claimed number is never checked against any document at all. That
     * is the gap this flag closes - an applicant who simply declines to upload a scan currently
     * defeats the strongest signal in the system by omission.
     *
     * <p>The fallback reads the same physical card the applicant held up to the camera, so the
     * number is genuinely available; it is only the officer's upload that was missing. What it
     * cannot do is preserve the cross-channel independence of the two document sources: with one
     * image, the number and the card come from the same place, so comparison 5 has nothing to
     * compare. That trade is the reason this is a switch rather than the default.
     */
    @Value("${document.ocr-fallback-to-capture:false}")
    private boolean ocrFallbackToCapture;

    /**
     * Whether the scan-versus-presented-card comparison participates in the gate.
     *
     * <p>False is the frozen baseline. Comparison 5 asks whether the uploaded scan and the card
     * physically presented are the same document - the only signal that dissents when an applicant
     * pairs someone else's card with a clean scan of their own. Identity binding cannot see that
     * attack: the number on the uploaded scan is genuinely theirs, so binding reports a match and
     * the gate approves. Measured on the derived corpus, that attack passed the gate 5 times out
     * of 6 with binding already switched on.
     *
     * <p>Like comparison 4, an absent measurement is excluded rather than treated as a failure -
     * with no upload there is no second channel, and that is a configuration fact, not a mismatch.
     */
    @Value("${verification.channel-gated:false}")
    private boolean channelGated;

    /**
     * OCR the supplied document and bind the number it carries to the claimed NIC.
     *
     * <p>Preference order is upload first, device capture second. The upload is preferred because
     * it is the higher-quality image and because it is an independent second channel; the capture
     * is used only when there is no upload, and only when
     * {@code document.ocr-fallback-to-capture} is on.
     *
     * @param scannedNicBytes the uploaded document, or {@code null} if none was supplied
     * @param deviceNicBytes  the card photographed by the device, used only as the fallback source
     */
    public DocumentAnalysis analyseDocument(String referenceId, String claimedNic,
                                             byte[] scannedNicBytes, byte[] deviceNicBytes,
                                             Boolean mockNicValid) {
        long start = System.currentTimeMillis();

        if (scannedNicBytes != null) {
            log.info("[Analysis][Document] referenceId={} nic={} scannedNIC supplied ({} bytes) - running OCR",
                    referenceId, claimedNic, scannedNicBytes.length);
            return finish(referenceId, claimedNic,
                    documentProcessingService.validateNic(scannedNicBytes, mockNicValid), start);
        }

        if (!ocrFallbackToCapture || deviceNicBytes == null) {
            log.info("[Analysis][Document] referenceId={} nic={} no scannedNIC supplied -> NOT_PROVIDED "
                            + "(fallback {}) ",
                    referenceId, claimedNic, ocrFallbackToCapture ? "enabled but no device capture" : "disabled");
            NicOcrResult ocr = NicOcrResult.notProvided();
            return new DocumentAnalysis(ocr, identityBindingService.bind(claimedNic, ocr),
                    System.currentTimeMillis() - start);
        }

        // Crop first. OCR on the raw frame reads the room as well as the card, and the detector
        // that already crops for the face comparisons answers exactly the question we need here.
        // A failed crop falls back to the full frame rather than abandoning the read.
        byte[] crop = cardDetectorService.cropCard(deviceNicBytes, "DeviceNIC-OCR", referenceId);
        byte[] ocrInput = crop != null ? crop : deviceNicBytes;
        log.info("[Analysis][Document] referenceId={} nic={} no scannedNIC - falling back to device capture "
                        + "(crop {}, {} bytes)",
                referenceId, claimedNic, crop != null ? "succeeded" : "FAILED, using full frame", ocrInput.length);

        NicOcrResult ocr = documentProcessingService.validateNic(ocrInput, mockNicValid)
                .withSource(NicOcrResult.OcrSource.DEVICE_CAPTURE);
        return finish(referenceId, claimedNic, ocr, start);
    }

    private DocumentAnalysis finish(String referenceId, String claimedNic, NicOcrResult ocr, long start) {
        IdentityBindingResult binding = identityBindingService.bind(claimedNic, ocr);

        log.info("[Analysis][Document] referenceId={} source={} outcome={} extractedNic={} binding={} score={}",
                referenceId, ocr.source(), ocr.statusName(), ocr.extractedNicNumber(),
                binding.outcome(), binding.score());

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

        // One line stating exactly what is about to be pushed to AWS, and how much of it.
        //
        // The five comparisons below are sequential, synchronous and inside the operator's HTTP
        // request, so their combined payload is the single best predictor of how long registration
        // takes on a given link. Note which inputs are card crops (small) and which are full device
        // captures (large): cmp3 compares two uncropped captures and is usually the heaviest call
        // by a wide margin.
        logComparisonPayloads(referenceId, nicForComparison, faceImage, selfNicForComparison,
                selfImage, scannedNicForComparison);

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
     * The decision rule.
     *
     * <p>Comparison 1 (device NIC vs. live face) is informational and excluded from the gate;
     * comparison 4 only participates when a scanned NIC was actually supplied; liveness is a
     * required conjunct.
     *
     * <p><b>Identity binding participates only when {@code verification.binding-gated} is true.</b>
     * The default is false, which reproduces the frozen baseline byte-for-byte - see the class doc
     * for why that default matters. Flipping it is a one-line configuration change, not a code
     * change, precisely so the two rules can be run against the same corpus and compared.
     *
     * @param liveness may be {@code null} in batch mode, where no device liveness session exists.
     *                 {@link GateResult#allPassed()} is then {@code null} - unknown, not failed -
     *                 while {@link GateResult#similarityPassed()} remains fully determined.
     * @param binding  may be {@code null} when no document analysis ran at all. Treated exactly
     *                 like {@code UNAVAILABLE}: no evidence, rather than negative evidence.
     */
    public GateResult evaluateGate(FaceAnalysis faces, LivenessOutcome liveness, IdentityBindingResult binding) {
        boolean similarityPassed = faces.cmp2().match()
                && faces.cmp3().match()
                && (faces.cmp4() == null || faces.cmp4().match());

        // Comparison 5 is conditional in exactly the way comparison 4 is: it decides only when it
        // was measurable. A null here means no scan was uploaded, not that the channels disagree.
        boolean channelBlocks = channelGated
                && faces.cmp5() != null
                && faces.cmp5().hasMeasurement()
                && !faces.cmp5().match();

        Boolean bindingPassed = evaluateBinding(binding);

        // Absent binding evidence is not a failure unless binding-required says so, mirroring the
        // way an absent comparison 4 is skipped rather than failed.
        boolean bindingBlocks = bindingGated && Boolean.FALSE.equals(bindingPassed);

        if (liveness == null) {
            boolean passedSoFar = similarityPassed && !bindingBlocks && !channelBlocks;
            return new GateResult(similarityPassed, null,
                    passedSoFar ? null : buildFailureReason(similarityPassed, true, bindingBlocks, channelBlocks, binding),
                    bindingPassed, bindingGated, channelBlocks, channelGated);
        }

        boolean allPassed = similarityPassed && liveness.passed() && !bindingBlocks && !channelBlocks;
        return new GateResult(similarityPassed, allPassed,
                buildFailureReason(similarityPassed, liveness.passed(), bindingBlocks, channelBlocks, binding),
                bindingPassed, bindingGated, channelBlocks, channelGated);
    }

    /**
     * Whether the document number binds to the claimed NIC.
     *
     * <p>Returns {@code null} for "no evidence either way" - the distinction
     * {@link IdentityBindingResult} exists to preserve. A caller must not collapse that into
     * false: a document whose number contradicts the claim is strong negative evidence, while a
     * missing document is no evidence at all, and only the first should ever block on its own.
     * When {@code binding-required} is set, missing evidence is mapped to false deliberately and
     * reported under its own reason code.
     */
    private Boolean evaluateBinding(IdentityBindingResult binding) {
        if (binding == null || !binding.hasEvidence()) {
            return bindingRequired ? Boolean.FALSE : null;
        }
        return binding.score() != null && binding.score() >= bindingThreshold;
    }

    /** Null when everything passed, otherwise comma-joined reason codes. */
    private String buildFailureReason(boolean similarityPassed, boolean livenessPassed,
                                      boolean bindingBlocks, boolean channelBlocks,
                                      IdentityBindingResult binding) {
        if (similarityPassed && livenessPassed && !bindingBlocks && !channelBlocks) {
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
        if (bindingBlocks) {
            if (reason.length() > 0) {
                reason.append(",");
            }
            // Two distinct codes: a contradicted number and an absent one are different findings
            // and an operator triaging the review queue needs to tell them apart.
            reason.append(binding == null || !binding.hasEvidence()
                    ? "BINDING_UNAVAILABLE" : "BINDING_MISMATCH");
        }
        if (channelBlocks) {
            if (reason.length() > 0) {
                reason.append(",");
            }
            // Without this a cmp5 block was the one way to fail the gate with a null reason, which
            // read downstream as "nothing failed".
            reason.append("CHANNEL_MISMATCH");
        }
        return reason.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Result types
    // ---------------------------------------------------------------------------------------

    public record DocumentAnalysis(NicOcrResult ocr, IdentityBindingResult binding, long latencyMs) {
    }

    /**
     * Reports the per-comparison upload sizes before any of them run, so a slow or timed-out
     * registration can be read against what it was actually asked to transfer.
     */
    private void logComparisonPayloads(String referenceId, byte[] deviceNic, byte[] face,
                                        byte[] selfNic, byte[] self, byte[] scannedNic) {
        long cmp1 = size(deviceNic) + size(face);
        long cmp2 = size(deviceNic) + size(selfNic);
        long cmp3 = size(face) + size(self);
        long cmp4 = scannedNic != null ? size(scannedNic) + size(face) : 0;
        long cmp5 = scannedNic != null ? size(scannedNic) + size(deviceNic) : 0;
        long total = cmp1 + cmp2 + cmp3 + cmp4 + cmp5;

        log.info("[Analysis][Payloads] referenceId={} inputs: deviceNic={} face={} selfNic={} self={} scannedNic={}",
                referenceId, human(size(deviceNic)), human(size(face)), human(size(selfNic)),
                human(size(self)), scannedNic != null ? human(size(scannedNic)) : "none");
        log.info("[Analysis][Payloads] referenceId={} uploads: cmp1={} cmp2={} cmp3={} cmp4={} cmp5={} | total={} across 5 sequential AWS calls",
                referenceId, human(cmp1), human(cmp2), human(cmp3),
                cmp4 > 0 ? human(cmp4) : "skipped", cmp5 > 0 ? human(cmp5) : "skipped", human(total));
    }

    private static long size(byte[] bytes) {
        return bytes != null ? bytes.length : 0;
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + "KB";
        return String.format("%.2fMB", bytes / (1024.0 * 1024.0));
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

    /**
     * @param bindingPassed whether the document number bound to the claimed NIC. {@code null}
     *                      means no evidence either way - not a failure. Reported regardless of
     *                      whether binding was gated, so an evaluation run can measure the rule
     *                      that was not in force.
     * @param bindingGated  whether binding actually participated in this decision. Snapshotted
     *                      onto the attempt so an explanation rendered months later describes the
     *                      rule that was applied, not the one currently configured.
     */
    public record GateResult(boolean similarityPassed, Boolean allPassed, String failureReason,
                              Boolean bindingPassed, boolean bindingGated,
                              boolean channelBlocked, boolean channelGated) {

        /** True when binding was gated and refused the claim - the conjunct that blocked it. */
        public boolean bindingBlocked() {
            return bindingGated && Boolean.FALSE.equals(bindingPassed);
        }

        /**
         * The gate's verdict with liveness set aside.
         *
         * <p>{@link #allPassed()} is null whenever there is no liveness channel, which is every
         * sample in an offline replay. Reporting only {@link #similarityPassed()} in that case
         * hides the binding conjunct entirely, so a reader of results.csv sees a gate that binding
         * never touched and concludes it changed nothing. This is the honest column for a replay:
         * everything the gate decided, minus the one input the replay cannot supply.
         */
        public boolean passedExcludingLiveness() {
            return similarityPassed && !bindingBlocked() && !channelBlocked;
        }
    }
}
