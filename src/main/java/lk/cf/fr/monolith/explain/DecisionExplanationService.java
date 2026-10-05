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
     * Fallback threshold for rows written before the per-attempt snapshot existed.
     *
     * <p>Reads the same key the gate reads, so the panel can never report a threshold the decision
     * did not use. Set to the bottom of the match band in {@code IdentityBindingService}
     * (EXACT 1.00 / CONFUSION 0.85 / FORMAT 0.80), so "bound" means "bound by one of the three
     * positive outcomes".
     */
    @Value("${verification.binding-threshold:0.80}")
    private double bindingThreshold;

    /**
     * The threshold this attempt was actually held to, preferring its own snapshot.
     *
     * <p>Falling back to current configuration is only correct for rows predating the snapshot;
     * for everything since, the stored value is what the decision used and current config is
     * irrelevant to explaining it.
     */
    private double bindingThresholdFor(RegistrationRecord record) {
        return orDefault(record.getNicBindingThreshold(), bindingThreshold);
    }

    /**
     * Whether binding participated in this attempt's rule.
     *
     * <p>Null means the row predates the switch, when binding was never gated - so false is the
     * historically accurate reading, not merely a safe default.
     */
    private boolean bindingWasGated(RegistrationRecord record) {
        return Boolean.TRUE.equals(record.getNicBindingGated());
    }

    /**
     * Whether comparison 5 participates in the gate. Read from the same key the gate reads; unlike
     * binding it has no per-attempt snapshot, so current configuration is the best available record.
     */
    @Value("${verification.channel-gated:false}")
    private boolean channelGated;

    /** Status the batch harness stores on replayed attempts - see {@code BatchEvaluationService}. */
    private static final String STATUS_EVALUATION = "EVALUATION";

    /**
     * Whether this row is an offline evaluation replay rather than a live registration.
     *
     * <p>A replay never reaches the approval workflow, so its {@code status} is a fixed marker and
     * says nothing about the verdict. It also has no liveness channel at all. Both facts change how
     * the row has to be read: the verdict must come from the recorded evidence, and liveness must
     * be excluded exactly as the gate excluded it, not reported as a missing result.
     */
    private static boolean isReplay(RegistrationRecord record) {
        return STATUS_EVALUATION.equals(record.getStatus());
    }

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

        List<EvidenceItem> gatedFailures = evidence.stream()
                .filter(EvidenceItem::gated).filter(EvidenceItem::isFailure).toList();
        boolean passed = isPassing(record, gatedFailures);

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
                decisionRule(record),
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
        double threshold = bindingThresholdFor(record);
        boolean gated = bindingWasGated(record);

        if (outcome == null || "UNAVAILABLE".equals(outcome)) {
            // Absent evidence is UNAVAILABLE even under a gating rule. Whether it *blocked* is a
            // separate question answered by binding-required, and the gate's own failure reason
            // records that; rendering "no document number" as a measured failure would misstate
            // what happened.
            return new EvidenceItem("identity.binding", "Document number vs. claimed NIC", CAT_IDENTITY,
                    EvidenceItem.STATUS_UNAVAILABLE, null, threshold, null, UNIT_SCORE,
                    outcome == null ? "UNAVAILABLE" : outcome, gated, false,
                    "OCR text + claimed NIC",
                    "No document number was available to compare against the claimed NIC, so this "
                            + "attempt carries no evidence that the document belongs to the applicant.");
        }

        boolean bound = score != null && score >= threshold;
        String distance = record.getNicBindingEditDistance() != null
                ? " (" + record.getNicBindingEditDistance() + " character difference)" : "";

        String detail = bound
                ? "The NIC number printed on the document matches the NIC the applicant claimed"
                        + ("EXACT_MATCH".equals(outcome) ? " exactly." : ", after normalisation.")
                : "The NIC number read from the document does NOT match the claimed NIC" + distance
                        + ". This is the signature of a genuine card belonging to someone else"
                        + (gated ? " - and this attempt was held to it." : " - and it is not part of the decision rule.");

        return new EvidenceItem("identity.binding", "Document number vs. claimed NIC", CAT_IDENTITY,
                bound ? EvidenceItem.STATUS_PASS : EvidenceItem.STATUS_FAIL,
                score, threshold,
                score == null ? null : round(score - threshold),
                UNIT_SCORE, outcome, gated, false,
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
                record.getMatch5(), record.getFifthSimilarity(), threshold, channelGated,
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

        if (score == null && passed == null && isReplay(record)) {
            return new EvidenceItem("liveness", "Face liveness challenge", CAT_LIVENESS,
                    EvidenceItem.STATUS_UNAVAILABLE, null, threshold, null, UNIT_CONFIDENCE, null,
                    false, false, "AWS Face Liveness session",
                    "Offline evaluation replay: there is no device liveness session, so liveness is "
                            + "excluded from this decision - exactly as the gate excluded it.");
        }
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

    /** The rule actually in force for this attempt, spelled out so the panel reads what was applied. */
    private String decisionRule(RegistrationRecord record) {
        boolean replay = isReplay(record);
        String base = "All of: document-vs-self comparison, live-face-vs-self comparison, "
                + "scanned-document comparison (when a document was supplied)"
                + (channelGated ? ", scan-vs-presented-card comparison (when a scan was supplied)" : "")
                + (replay ? "" : ", and liveness")
                + " must independently clear their thresholds. ";
        String rule = bindingWasGated(record)
                ? base + "The document number must also bind to the claimed NIC. Comparison 1 is "
                        + "recorded but excluded from this rule."
                : base + "Comparison 1 and identity binding are recorded but excluded from this rule.";
        return replay
                ? rule + " This is an offline evaluation replay: there is no liveness channel, so the "
                        + "verdict shown is the rule's decision on the remaining evidence, and no "
                        + "approval workflow was involved."
                : rule;
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
            return isReplay(record)
                    ? "All gated evidence cleared its threshold, so the rule would approve this attempt "
                            + "automatically (offline replay, liveness excluded)."
                    : "All gated evidence cleared its threshold, so the attempt was approved automatically.";
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
     * What the rule in force and the other rule would each have decided.
     *
     * <p>Reads in both directions. With binding ungated, the alternative adds it, and the
     * interesting case is a pass that the binding-aware rule would have rejected. With binding
     * gated, the alternative removes it, and the interesting case is a rejection the old
     * conjunctive baseline would have waved through - the same finding, seen from the other side.
     */
    private Hypothetical hypothetical(RegistrationRecord record, boolean passed) {
        Double score = record.getNicBindingScore();
        String outcome = record.getNicBindingOutcome();
        double threshold = bindingThresholdFor(record);
        boolean gated = bindingWasGated(record);

        String actualLabel = gated ? "Deployed rule (with binding)" : "Deployed rule";
        String alternativeLabel = gated ? "Without identity binding" : "+ identity binding";

        if (score == null || outcome == null || "UNAVAILABLE".equals(outcome)) {
            return new Hypothetical(actualLabel, alternativeLabel, "UNCHANGED_NO_EVIDENCE", false,
                    null, threshold,
                    "No identity-binding evidence exists for this attempt, so the two rules cannot "
                            + "differ on it. Note that this is itself a finding: without a scanned document, "
                            + "nothing ties the presented identity to the claimed NIC.");
        }

        boolean bindingWouldPass = score >= threshold;

        boolean alternativePasses;
        if (gated) {
            // Alternative = the baseline without binding. When binding passed it contributed
            // nothing, so the recorded outcome already is the baseline's. When it failed it was
            // sufficient on its own, and the baseline's verdict has to come from the rest.
            alternativePasses = bindingWouldPass ? passed : noGatedFailuresOtherThanBinding(record);
        } else {
            alternativePasses = passed && bindingWouldPass;
        }
        boolean differs = alternativePasses != passed;

        String explanation;
        if (differs && gated) {
            explanation = String.format(
                    "This attempt was rejected because the document number did not bind to the claimed NIC "
                            + "(%.2f against a %.2f threshold, %s). The conjunctive baseline this system "
                            + "shipped with would have approved it: the face comparisons all passed, because "
                            + "the photograph genuinely matches - it is the number that does not.",
                    score, threshold, outcome);
        } else if (differs) {
            explanation = String.format(
                    "The deployed rule approved this attempt on face and liveness evidence alone. Had identity "
                            + "binding been gated, it would have been rejected: the document number scored %.2f "
                            + "against a %.2f threshold (%s). The face comparisons cannot detect this, because the "
                            + "photograph genuinely matches - it is the number that does not.",
                    score, threshold, outcome);
        } else if (!passed && !bindingWouldPass) {
            explanation = String.format(
                    "Identity binding failed (%.2f, %s) and this attempt was routed for review, so both rules "
                            + "reach the same outcome - one of them by an additional route.",
                    score, outcome);
        } else {
            explanation = String.format(
                    "Identity binding succeeded (%.2f, %s), so it makes no difference to this outcome either way.",
                    score, outcome);
        }

        return new Hypothetical(actualLabel, alternativeLabel,
                bindingWouldPass ? "PASS" : "FAIL", differs, score, threshold, explanation);
    }

    /**
     * Whether every gated item other than binding cleared its threshold.
     *
     * <p>Needed only when binding was gated and failed: the recorded outcome is then a rejection
     * regardless of the rest, so the baseline's verdict has to be reconstructed from the other
     * evidence rather than inferred from the result.
     */
    private boolean noGatedFailuresOtherThanBinding(RegistrationRecord record) {
        double similarityThreshold = orDefault(record.getSimilarityThreshold(), 80.0);
        boolean cmp2 = clears(record.getSecondSimilarity(), similarityThreshold, record.getMatch2());
        boolean cmp3 = clears(record.getThirdSimilarity(), similarityThreshold, record.getMatch3());
        boolean cmp4 = record.getMatch4() == null && record.getFourthSimilarity() == null
                || clears(record.getFourthSimilarity(), similarityThreshold, record.getMatch4());
        // A replay has no liveness channel; the gate excluded it, so the baseline does too.
        boolean liveness = isReplay(record) && record.getLivenessPassed() == null
                || Boolean.TRUE.equals(record.getLivenessPassed());
        boolean cmp5 = !channelGated || record.getFifthSimilarity() == null
                || clears(record.getFifthSimilarity(), similarityThreshold, record.getMatch5());
        return cmp2 && cmp3 && cmp4 && cmp5 && liveness;
    }

    /** Prefer the recorded match flag; fall back to comparing the score when it is absent. */
    private boolean clears(Double value, double threshold, Boolean match) {
        if (match != null) {
            return match;
        }
        return value != null && value >= threshold;
    }

    private List<String> notes(RegistrationRecord record, boolean passed) {
        List<String> notes = new ArrayList<>();

        notes.add("Face similarity and liveness scores come from hosted AWS APIs. This explanation accounts "
                + "for how those scores were combined, not for how they were produced.");

        if (record.getNicBindingOutcome() != null && !"UNAVAILABLE".equals(record.getNicBindingOutcome())) {
            notes.add(bindingWasGated(record)
                    ? "Identity binding was part of the decision rule for this attempt. The conjunctive "
                            + "baseline that excludes it is shown alongside, for comparison."
                    : "Identity binding is recorded on every attempt but deliberately excluded from the decision "
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

    private boolean isPassing(RegistrationRecord record, List<EvidenceItem> gatedFailures) {
        if (isReplay(record)) {
            // The status of a replay is a fixed marker, so read the verdict off what the gate
            // recorded: no failure reason and no gated item below its threshold. Both are checked
            // because rows written before CHANNEL_MISMATCH existed carry no reason for a cmp5 block.
            return record.getFailureReason() == null && gatedFailures.isEmpty();
        }
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
