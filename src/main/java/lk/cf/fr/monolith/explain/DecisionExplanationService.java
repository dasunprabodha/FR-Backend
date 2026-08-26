package lk.cf.fr.monolith.explain;

import lk.cf.fr.monolith.explain.DecisionExplanation.Counterfactual;
import lk.cf.fr.monolith.explain.DecisionExplanation.Hypothetical;
import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lk.cf.fr.monolith.registration.model.RegistrationApprovalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reconstructs the reasoning behind a stored registration decision.
 *
 * <p>Everything here is derived from the persisted {@code registration_record} row rather than
 * recomputed, so an attempt can be explained months later without re-running any model or
 * spending an API call - and so the explanation necessarily describes the decision that was
 * actually made, including under thresholds that have since been changed in configuration
 * (the per-attempt threshold snapshots exist precisely for this).
 *
 * <p>No scores are invented. There is no calibration layer and no fusion weight in the system
 * yet, so this service reports raw values, thresholds and signed margins - not probabilities or
 * contributions. When calibration and fusion are added, the extra fields belong on
 * {@link EvidenceItem} and this service grows to populate them; nothing in the frontend contract
 * has to change shape.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DecisionExplanationService {

    private static final String CAT_DOCUMENT = "DOCUMENT";
    private static final String CAT_IDENTITY = "IDENTITY";
    private static final String CAT_FACE = "FACE";
    private static final String CAT_LIVENESS = "LIVENESS";
    private static final String CAT_QUALITY = "QUALITY";

    private static final String UNIT_SIMILARITY = "SIMILARITY_PERCENT";
    private static final String UNIT_CONFIDENCE = "CONFIDENCE_PERCENT";
    private static final String UNIT_SCORE = "SCORE_UNIT";
    private static final String UNIT_CATEGORICAL = "CATEGORICAL";

    private final RegistrationRecordRepository registrationRecordRepository;

    /**
     * Threshold identity binding would be held to <em>if</em> it were gated. Set to the bottom of
     * the match band in {@code IdentityBindingService} (EXACT 1.00 / CONFUSION 0.85 / FORMAT 0.80),
     * so "would have passed" means "bound by one of the three positive outcomes".
     */
    @Value("${explain.binding-threshold:0.80}")
    private double bindingThreshold;

    public DecisionExplanation explainByReferenceId(String referenceId) {
        RegistrationRecord record = registrationRecordRepository.findByReferenceId(referenceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No registration record found for referenceId=" + referenceId));
        return explain(record);
    }

    public DecisionExplanation explainById(Long id) {
        RegistrationRecord record = registrationRecordRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No registration record found for id=" + id));
        return explain(record);
    }

    public DecisionExplanation explain(RegistrationRecord record) {
        double similarityThreshold = orDefault(record.getSimilarityThreshold(), 80.0);
        double livenessThreshold = orDefault(record.getLivenessThreshold(), 65.0);

        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(documentPresence(record));
        ocrQuality(record).ifPresent(evidence::add);
        evidence.add(identityBinding(record));
        evidence.add(comparison1(record, similarityThreshold));
        evidence.add(comparison(record, "face.cmp2", "Document photo vs. self-with-document",
                "Same physical card seen in two independent captures - the co-presence check.",
                record.getMatch2(), record.getSecondSimilarity(), similarityThreshold, true,
                "nicImage (cropped) + selfImage (cropped)"));
        evidence.add(comparison(record, "face.cmp3", "Live face vs. face in self-with-document",
                "The person in the live capture is the person holding the card.",
                record.getMatch3(), record.getThirdSimilarity(), similarityThreshold, true,
                "faceImage + selfImage"));
        evidence.add(comparison4(record, similarityThreshold));
        evidence.add(comparison5(record, similarityThreshold));
        evidence.add(crossChannel(record));
        evidence.add(liveness(record, livenessThreshold));

        boolean passed = isPassing(record);
        List<EvidenceItem> gatedFailures = evidence.stream()
                .filter(EvidenceItem::gated).filter(EvidenceItem::isFailure).toList();

        Optional<EvidenceItem> closest = evidence.stream()
                .filter(EvidenceItem::gated)
                .filter(e -> e.margin() != null)
                .filter(e -> !e.isFailure())
                .min(Comparator.comparingDouble(EvidenceItem::margin));

        List<EvidenceItem> ranked = rank(evidence, gatedFailures, closest.orElse(null));

        return new DecisionExplanation(
                record.getReferenceId(),
                record.getNic(),
                record.getCifNo(),
                record.getStatus(),
                passed,
                primaryReason(record, passed, gatedFailures),
                "All of: document-vs-self comparison, live-face-vs-self comparison, "
                        + "scanned-document comparison (when a document was supplied), and liveness "
                        + "must independently clear their thresholds. Comparison 1 and identity binding "
                        + "are recorded but excluded from this rule.",
                ranked,
                counterfactual(passed, gatedFailures, closest.orElse(null)),
                hypothetical(record, passed),
                notes(record, passed),
                record.getReqTime(),
                record.getResTime(),
                durationMs(record));
    }

    // ---------------------------------------------------------------------------------------
    // Evidence construction
    // ---------------------------------------------------------------------------------------

    private EvidenceItem documentPresence(RegistrationRecord record) {
        String status = record.getValidNicStatus();
        boolean notProvided = status == null || "NOT_PROVIDED".equals(status);
        boolean valid = "VALID".equals(status);

        String detail = notProvided
                ? "No scanned document was supplied, so document validation was skipped entirely and "
                        + "registration continued on face evidence alone."
                : valid
                        ? "OCR found Sri Lankan NIC text evidence and no competing institutional-card keywords."
                        : "OCR could not confirm the image as a Sri Lankan NIC (outcome " + status + ").";

        return new EvidenceItem("document.presence", "Document classification", CAT_DOCUMENT,
                notProvided ? EvidenceItem.STATUS_UNAVAILABLE
                        : valid ? EvidenceItem.STATUS_PASS : EvidenceItem.STATUS_FAIL,
                null, null, null, UNIT_CATEGORICAL,
                status == null ? "NOT_PROVIDED" : status,
                false, false, "scannedNIC upload", detail);
    }

    private Optional<EvidenceItem> ocrQuality(RegistrationRecord record) {
        Double confidence = record.getOcrMeanLineConfidence();
        if (confidence == null) {
            return Optional.empty();
        }
        return Optional.of(new EvidenceItem("document.ocrConfidence", "OCR text confidence", CAT_QUALITY,
                EvidenceItem.STATUS_INFORMATIONAL, confidence, null, null, UNIT_CONFIDENCE, null,
                false, false, "scannedNIC upload",
                "Mean OCR confidence across all detected text lines. A quality signal for the document "
                        + "evidence above; it does not currently weight any decision."));
    }

    private EvidenceItem identityBinding(RegistrationRecord record) {
        String outcome = record.getNicBindingOutcome();
        Double score = record.getNicBindingScore();

        if (outcome == null || "UNAVAILABLE".equals(outcome)) {
            return new EvidenceItem("identity.binding", "Document number vs. claimed NIC", CAT_IDENTITY,
                    EvidenceItem.STATUS_UNAVAILABLE, null, bindingThreshold, null, UNIT_SCORE,
                    outcome == null ? "UNAVAILABLE" : outcome, false, false,
                    "OCR text + claimed NIC",
                    "No document number was available to compare against the claimed NIC, so this "
                            + "attempt carries no evidence that the document belongs to the applicant.");
        }

        boolean bound = score != null && score >= bindingThreshold;
        String distance = record.getNicBindingEditDistance() != null
                ? " (" + record.getNicBindingEditDistance() + " character difference)" : "";

        String detail = bound
                ? "The NIC number printed on the document matches the NIC the applicant claimed"
                        + ("EXACT_MATCH".equals(outcome) ? " exactly." : ", after normalisation.")
                : "The NIC number read from the document does NOT match the claimed NIC" + distance
                        + ". This is the signature of a genuine card belonging to someone else - and it is "
                        + "not part of the decision rule.";

        return new EvidenceItem("identity.binding", "Document number vs. claimed NIC", CAT_IDENTITY,
                bound ? EvidenceItem.STATUS_PASS : EvidenceItem.STATUS_FAIL,
                score, bindingThreshold,
                score == null ? null : round(score - bindingThreshold),
                UNIT_SCORE, outcome, false, false,
                "OCR text + claimed NIC", detail);
    }

    private EvidenceItem comparison1(RegistrationRecord record, double threshold) {
        return comparison(record, "face.cmp1", "Document photo vs. live face",
                "Kept for continuity with the original pipeline, where it was explicitly informational. "
                        + "It compares a small printed photo against a live capture, which is the hardest "
                        + "of the four comparisons and the most prone to false rejection.",
                record.getMatch(), record.getSimilarity(), threshold, false,
                "nicImage (cropped) + faceImage");
    }

    private EvidenceItem comparison4(RegistrationRecord record, double threshold) {
        if (record.getMatch4() == null && record.getFourthSimilarity() == null) {
            return new EvidenceItem("face.cmp4", "Uploaded document photo vs. live face", CAT_FACE,
                    EvidenceItem.STATUS_UNAVAILABLE, null, threshold, null, UNIT_SIMILARITY, null,
                    false, false, "scannedNIC + faceImage",
                    "No scanned document was uploaded, so this comparison did not run and was excluded "
                            + "from the decision rule.");
        }
        return comparison(record, "face.cmp4", "Uploaded document photo vs. live face",
                "The uploaded document's portrait against the live capture.",
                record.getMatch4(), record.getFourthSimilarity(), threshold, true,
                "scannedNIC + faceImage");
    }

    /**
     * Comparison 5 - the uploaded scan against the card physically presented to the camera.
     * Recorded on every attempt that supplied a scan, never gated.
     */
    private EvidenceItem comparison5(RegistrationRecord record, double threshold) {
        if (record.getMatch5() == null && record.getFifthSimilarity() == null) {
            return new EvidenceItem("face.cmp5", "Uploaded scan vs. presented card", CAT_DOCUMENT,
                    EvidenceItem.STATUS_UNAVAILABLE, null, threshold, null, UNIT_SIMILARITY, null,
                    false, false, "scannedNIC (cropped) + nicImage (cropped)",
                    "No scanned document was uploaded, so the card presented to the camera could not be "
                            + "cross-checked against one. Nothing in this attempt establishes that the presented "
                            + "card is the document whose text was validated.");
        }
        return comparison(record, "face.cmp5", "Uploaded scan vs. presented card",
                "Are the uploaded scan and the card held up to the camera the same document? Nothing else "
                        + "asks this: OCR and authenticity inspect only the upload, while the gate inspects only "
                        + "the captures.",
                record.getMatch5(), record.getFifthSimilarity(), threshold, false,
                "scannedNIC (cropped) + nicImage (cropped)");
    }

    /**
     * Derived agreement between comparisons 1 and 4 - two independent measurements of the same
     * relationship, taken through different document channels. Categorical rather than numeric on
     * purpose: the underlying value is a gap where <em>smaller is better</em>, and rendering that
     * on a threshold bar alongside scores where larger is better would read backwards.
     */
    private EvidenceItem crossChannel(RegistrationRecord record) {
        String status = record.getCrossChannelStatus();
        Double delta = record.getCrossChannelDelta();

        if (status == null || "UNAVAILABLE".equals(status)) {
            return new EvidenceItem("document.crossChannel", "Agreement between document channels", CAT_DOCUMENT,
                    EvidenceItem.STATUS_UNAVAILABLE, null, null, null, UNIT_CATEGORICAL,
                    status == null ? "UNAVAILABLE" : status, false, false,
                    "comparison 1 + comparison 4",
                    "Only one document channel produced a measurement, so the presented card and the uploaded "
                            + "scan could not be cross-checked against each other.");
        }

        boolean healthy = "CONSISTENT".equals(status);
        String detail = switch (status) {
            case "CONSISTENT" -> String.format("The presented card and the uploaded scan agree to within %.1f "
                    + "similarity points about the live face, consistent with both showing the same document.", delta);
            case "MARGINAL" -> String.format("The two document channels reached the same verdict but %.1f similarity "
                    + "points apart — wide enough to be worth a look. Either they show different documents, or one "
                    + "capture is markedly poorer quality.", delta);
            default -> String.format("The two document channels disagree outright, %.1f similarity points apart and "
                    + "on opposite sides of the threshold. This is what a substituted document looks like: a clean "
                    + "scan of a genuine card uploaded alongside a different card presented to the camera. The gated "
                    + "checks cannot see it, because each is internally consistent.", delta);
        };

        return new EvidenceItem("document.crossChannel", "Agreement between document channels", CAT_DOCUMENT,
                healthy ? EvidenceItem.STATUS_PASS : EvidenceItem.STATUS_FAIL,
                null, null, null, UNIT_CATEGORICAL, status, false, false,
                "comparison 1 + comparison 4", detail);
    }

    private EvidenceItem comparison(RegistrationRecord record, String id, String label, String description,
                                     Boolean match, Double similarity, double threshold, boolean gated,
                                     String source) {
        if (similarity == null) {
            // The comparison ran but produced no measurement - no comparable face was detected.
            // Distinct from "did not run". A gated edge with no measurement still fails the gate,
            // and must say so or it will not be picked up as the reason for the outcome.
            return new EvidenceItem(id, label, CAT_FACE,
                    gated ? EvidenceItem.STATUS_FAIL : EvidenceItem.STATUS_UNAVAILABLE,
                    null, threshold, null, UNIT_SIMILARITY, "NO_FACE_DETECTED", gated, false, source,
                    description + " No comparable face was detected, so no similarity could be measured"
                            + (gated ? " - which the decision rule treats as a failure." : "."));
        }

        boolean passed = Boolean.TRUE.equals(match);
        String verdict = passed
                ? String.format(" Scored %.1f%%, clearing the %.0f%% threshold by %.1f points.",
                        similarity, threshold, similarity - threshold)
                : String.format(" Scored %.1f%%, short of the %.0f%% threshold by %.1f points.",
                        similarity, threshold, threshold - similarity);

        // Status reports the measurement, never whether the measurement counted. Those are two
        // independent facts and the `gated` flag already carries the second one. Collapsing them -
        // as this method previously did, reporting every ungated edge as INFORMATIONAL - meant a
        // *failing* comparison 1 or 5 rendered identically to a passing one, hiding precisely the
        // signal those edges exist to provide. A red row tagged "not in decision rule" is the
        // honest rendering of "this failed and we ignored it".
        return new EvidenceItem(id, label, CAT_FACE,
                passed ? EvidenceItem.STATUS_PASS : EvidenceItem.STATUS_FAIL,
                round(similarity), threshold, round(similarity - threshold), UNIT_SIMILARITY, null,
                gated, false, source, description + verdict);
    }

    private EvidenceItem liveness(RegistrationRecord record, double threshold) {
        Double score = record.getLivenessScore();
        Boolean passed = record.getLivenessPassed();

        if (score == null && passed == null) {
            return new EvidenceItem("liveness", "Face liveness challenge", CAT_LIVENESS,
                    EvidenceItem.STATUS_UNAVAILABLE, null, threshold, null, UNIT_CONFIDENCE, null,
                    true, false, "AWS Face Liveness session", "No liveness result was recorded.");
        }

        boolean ok = Boolean.TRUE.equals(passed);
        String detail = "Hosted challenge-response check that the capture came from a live person. "
                + (score != null
                        ? String.format("Confidence %.1f%% against a %.0f%% threshold.", score, threshold)
                        : "No confidence value was returned.")
                + " The underlying model is a hosted API, so this score cannot be decomposed further.";

        return new EvidenceItem("liveness", "Face liveness challenge", CAT_LIVENESS,
                ok ? EvidenceItem.STATUS_PASS : EvidenceItem.STATUS_FAIL,
                score == null ? null : round(score), threshold,
                score == null ? null : round(score - threshold),
                UNIT_CONFIDENCE, null, true, false, "AWS Face Liveness session", detail);
    }

    // ---------------------------------------------------------------------------------------
    // Ranking, reasons, counterfactual
    // ---------------------------------------------------------------------------------------

    /** Decisive first, then gated, then informational/unavailable - most influential at the top. */
    private List<EvidenceItem> rank(List<EvidenceItem> evidence, List<EvidenceItem> failures, EvidenceItem closest) {
        List<EvidenceItem> marked = evidence.stream().map(item -> {
            boolean decisive = failures.contains(item) || (failures.isEmpty() && item.equals(closest));
            return decisive ? withDecisive(item) : item;
        }).toList();

        return marked.stream().sorted(Comparator
                .comparing(EvidenceItem::decisive).reversed()
                .thenComparing(Comparator.comparing(EvidenceItem::gated).reversed())
                .thenComparing(item -> item.margin() == null ? Double.MAX_VALUE : item.margin()))
                .toList();
    }

    private EvidenceItem withDecisive(EvidenceItem item) {
        return new EvidenceItem(item.id(), item.label(), item.category(), item.status(), item.value(),
                item.threshold(), item.margin(), item.unit(), item.categoricalValue(), item.gated(),
                true, item.source(), item.detail());
    }

    private String primaryReason(RegistrationRecord record, boolean passed, List<EvidenceItem> failures) {
        if (passed) {
            return "All gated evidence cleared its threshold, so the attempt was approved automatically.";
        }
        if (failures.isEmpty()) {
            String reason = record.getFailureReason();
            return reason == null
                    ? "Routed for human review; no single evidence item is recorded as failing."
                    : "Routed for human review (" + reason + ").";
        }
        if (failures.size() == 1) {
            return failures.get(0).label() + " fell below its threshold, so the attempt was routed for human review.";
        }
        return failures.size() + " evidence items fell below their thresholds ("
                + failures.stream().map(EvidenceItem::label).reduce((a, b) -> a + "; " + b).orElse("")
                + "), so the attempt was routed for human review.";
    }

    private Counterfactual counterfactual(boolean passed, List<EvidenceItem> failures, EvidenceItem closest) {
        if (!passed && !failures.isEmpty()) {
            List<Counterfactual.Requirement> requirements = failures.stream()
                    .filter(item -> item.value() != null && item.threshold() != null)
                    .map(item -> new Counterfactual.Requirement(item.id(), item.label(),
                            item.value(), item.threshold(), round(item.threshold() - item.value())))
                    .toList();

            String summary = requirements.isEmpty()
                    ? "This attempt would have passed only if the failing evidence had been recorded."
                    : requirements.size() == 1
                            ? String.format("This attempt would have been approved automatically if %s had scored "
                                    + "%.1f points higher (%.1f instead of %.1f).",
                                    requirements.get(0).label(), requirements.get(0).shortfall(),
                                    requirements.get(0).required(), requirements.get(0).actual())
                            : "This attempt would have been approved automatically only if all "
                                    + requirements.size() + " failing items had reached their thresholds - "
                                    + "the conjunctive rule requires every one of them independently.";

            return new Counterfactual(true, summary, requirements, null, null);
        }

        if (passed && closest != null && closest.margin() != null) {
            return new Counterfactual(true,
                    String.format("This attempt passed, but not comfortably: %s had only %.1f points of headroom. "
                            + "A drop of that much would have sent it to human review.",
                            closest.label(), closest.margin()),
                    List.of(), closest.label(), closest.margin());
        }

        return new Counterfactual(false,
                "No single change to the recorded evidence would have altered this outcome.",
                List.of(), null, null);
    }

    /**
     * What the deployed rule and the binding-aware rule would each have decided. The interesting
     * case is a pass with a binding mismatch: the deployed gate approves, the binding-aware rule
     * does not, and the difference is visible on a real attempt rather than asserted.
     */
    private Hypothetical hypothetical(RegistrationRecord record, boolean passed) {
        Double score = record.getNicBindingScore();
        String outcome = record.getNicBindingOutcome();

        if (score == null || outcome == null || "UNAVAILABLE".equals(outcome)) {
            return new Hypothetical("UNCHANGED_NO_EVIDENCE", false, null, bindingThreshold,
                    "No identity-binding evidence exists for this attempt, so gating it would change nothing. "
                            + "Note that this is itself a finding: without a scanned document, nothing ties the "
                            + "presented identity to the claimed NIC.");
        }

        boolean bindingWouldPass = score >= bindingThreshold;
        boolean wouldPassOverall = passed && bindingWouldPass;
        boolean differs = wouldPassOverall != passed;

        String explanation;
        if (differs) {
            explanation = String.format(
                    "The deployed rule approved this attempt on face and liveness evidence alone. Had identity "
                            + "binding been gated, it would have been rejected: the document number scored %.2f "
                            + "against a %.2f threshold (%s). The face comparisons cannot detect this, because the "
                            + "photograph genuinely matches - it is the number that does not.",
                    score, bindingThreshold, outcome);
        } else if (!passed && !bindingWouldPass) {
            explanation = String.format(
                    "This attempt was already routed for review, and identity binding also failed (%.2f, %s), so "
                            + "gating binding would have reached the same outcome by an additional route.",
                    score, outcome);
        } else {
            explanation = String.format(
                    "Identity binding succeeded (%.2f, %s), so gating it would not have changed this outcome.",
                    score, outcome);
        }

        return new Hypothetical(bindingWouldPass ? "PASS" : "FAIL", differs, score, bindingThreshold, explanation);
    }

    private List<String> notes(RegistrationRecord record, boolean passed) {
        List<String> notes = new ArrayList<>();

        notes.add("Face similarity and liveness scores come from hosted AWS APIs. This explanation accounts "
                + "for how those scores were combined, not for how they were produced.");

        if (record.getNicBindingOutcome() != null && !"UNAVAILABLE".equals(record.getNicBindingOutcome())) {
            notes.add("Identity binding is recorded on every attempt but deliberately excluded from the decision "
                    + "rule, so the deployed gate remains an unmodified baseline for comparison.");
        }

        if (record.getValidNicStatus() == null || "NOT_PROVIDED".equals(record.getValidNicStatus())) {
            notes.add("No scanned document was supplied. Document classification, identity binding and comparison 4 "
                    + "were all skipped, and the attempt was decided on face and liveness evidence alone.");
        }

        if (passed && record.getSimilarity() != null && Boolean.FALSE.equals(record.getMatch())) {
            notes.add("Comparison 1 failed but is informational, so it did not prevent automatic approval.");
        }

        return notes;
    }

    // ---------------------------------------------------------------------------------------

    private boolean isPassing(RegistrationRecord record) {
        String status = record.getStatus();
        return RegistrationApprovalStatus.AWS_APPROVED.name().equals(status)
                || RegistrationApprovalStatus.APPROVED.name().equals(status);
    }

    private Long durationMs(RegistrationRecord record) {
        if (record.getReqTime() == null || record.getResTime() == null) {
            return null;
        }
        return Duration.between(record.getReqTime(), record.getResTime()).toMillis();
    }

    private static double orDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }
}
