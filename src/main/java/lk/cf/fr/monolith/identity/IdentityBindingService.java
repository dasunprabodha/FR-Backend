package lk.cf.fr.monolith.identity;

import lk.cf.fr.monolith.document.NicOcrResult;
import lk.cf.fr.monolith.identity.IdentityBindingResult.BindingOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Binds the presented document to the claimed identity by comparing the NIC number OCR read off
 * the card against the NIC number the applicant supplied on the request.
 *
 * <h2>Why this exists</h2>
 * <p>Document validation before this service established only that the uploaded image
 * <em>looks like a Sri Lankan NIC</em> - the classifier's VALID/INVALID/UNKNOWN verdict says
 * nothing about <em>whose</em> NIC it is. The number was extracted by the OCR regex ladder and
 * then discarded a few lines later, so an attacker presenting any genuine NIC belonging to anyone
 * passed the document check unconditionally. This service closes that gap: it is the missing
 * edge between "claimed identity" and "document evidence".
 *
 * <h2>Grading, not a boolean</h2>
 * <p>The result is a graded score rather than a pass/fail, in two clearly separated bands:
 *
 * <pre>
 *   1.00  EXACT_MATCH                 normalised numbers identical
 *   0.85  CONFUSION_CORRECTED_MATCH   identical after undoing OCR digit-shape misreads
 *   0.80  FORMAT_EQUIVALENT_MATCH     identical after old 9+V/X -> new 12-digit conversion
 *   ----  match / non-match boundary ------------------------------------------------
 *   0.60  best possible graded score  (PARTIAL, heavy OCR damage on a genuine document)
 *   0.00  completely unrelated numbers (MISMATCH)
 * </pre>
 *
 * <p>The non-match band is scaled into {@code [0, 0.6]} so a fuzzy near-miss can never reach the
 * match band no matter how close it gets. The gap between 0.60 and 0.80 is what keeps
 * "a genuine card OCR read badly" separable from "a different person's card".
 *
 * <h2>Candidate handling</h2>
 * <p>New-format NICs commonly print both the 12-digit number and the holder's original 9-digit +
 * V/X number, and OCR may additionally emit a digit-corrected reading of either. Every candidate
 * {@link NicOcrResult} produced is evaluated and the strongest outcome wins, so a card that shows
 * the claimed number in <em>either</em> format binds successfully.
 *
 * <h2>Scope</h2>
 * <p><b>This service is evidence-producing only - it does not gate any decision.</b> Its output is
 * computed, logged, persisted and returned, but the registration pass/fail rule is deliberately
 * left exactly as it was, so the existing conjunctive gate stays valid as a frozen baseline for
 * comparison. Wiring binding into the decision is a separate, later change.
 */
@Service
@Slf4j
public class IdentityBindingService {

    private static final double SCORE_EXACT = 1.00;
    private static final double SCORE_CONFUSION_CORRECTED = 0.85;
    private static final double SCORE_FORMAT_EQUIVALENT = 0.80;

    /** Ceiling for the graded non-match band, keeping it strictly below the match band. */
    private static final double NON_MATCH_CEILING = 0.60;

    /** Normalised similarity at or above which a non-match is reported as PARTIAL, not MISMATCH. */
    private static final double PARTIAL_THRESHOLD = 0.70;

    /**
     * @param claimedNic the NIC the applicant asserts is theirs (from the registration request)
     * @param ocr        the OCR result for the presented document; may be {@code null}
     */
    public IdentityBindingResult bind(String claimedNic, NicOcrResult ocr) {
        String claimed = SriLankanNicFormat.compact(claimedNic);

        if (claimed == null || claimed.isBlank()) {
            return IdentityBindingResult.unavailable(null, "No NIC number was claimed on the request.");
        }
        if (ocr == null || !ocr.hasExtractedNumber()) {
            return IdentityBindingResult.unavailable(claimed,
                    ocr == null || ocr.outcome() == null
                            ? "No document was supplied, so the claimed NIC could not be bound to one."
                            : "OCR read no NIC-shaped number from the document.");
        }

        List<String> candidates = ocr.candidateNumbers().isEmpty()
                ? List.of(ocr.extractedNicNumber())
                : ocr.candidateNumbers();

        IdentityBindingResult best = null;
        for (String candidate : candidates) {
            IdentityBindingResult result = compareOne(claimed, SriLankanNicFormat.compact(candidate));
            if (best == null || isStronger(result, best)) {
                best = result;
            }
            // Nothing can beat an exact match, so stop as soon as one is found.
            if (best.outcome() == BindingOutcome.EXACT_MATCH) {
                break;
            }
        }

        log.info("[NIC-Binding] claimed={} extracted={} candidates={} -> outcome={} score={} ({})",
                claimed, best.extracted(), candidates, best.outcome(), best.score(), best.detail());

        return best;
    }

    private IdentityBindingResult compareOne(String claimed, String extracted) {
        if (extracted == null || extracted.isBlank()) {
            return IdentityBindingResult.unavailable(claimed, "Empty candidate number.");
        }

        // 1. Exact match on the normalised strings.
        if (claimed.equals(extracted)) {
            return new IdentityBindingResult(BindingOutcome.EXACT_MATCH, SCORE_EXACT, claimed, extracted, 0,
                    "Document number matches the claimed NIC exactly.");
        }

        // 2. Match once OCR digit-shape confusions are undone on both sides. Applying the
        //    correction to the claimed number too is intentional and harmless - a claimed NIC
        //    contains no O/I/L/S/B/Z to substitute, so this only ever normalises the OCR side.
        String claimedCorrected = SriLankanNicFormat.correctDigitConfusions(claimed);
        String extractedCorrected = SriLankanNicFormat.correctDigitConfusions(extracted);
        if (claimedCorrected.equals(extractedCorrected)) {
            return new IdentityBindingResult(BindingOutcome.CONFUSION_CORRECTED_MATCH, SCORE_CONFUSION_CORRECTED,
                    claimed, extracted, SriLankanNicFormat.levenshtein(claimed, extracted),
                    "Document number matches the claimed NIC after correcting OCR digit-shape misreads.");
        }

        // 3. Match once both are reduced to the 12-digit canonical form - covers a customer whose
        //    bank record holds one NIC format while the card in hand shows the other.
        String claimedCanonical = SriLankanNicFormat.canonicalise(claimedCorrected);
        String extractedCanonical = SriLankanNicFormat.canonicalise(extractedCorrected);
        if (claimedCanonical != null && claimedCanonical.equals(extractedCanonical)) {
            return new IdentityBindingResult(BindingOutcome.FORMAT_EQUIVALENT_MATCH, SCORE_FORMAT_EQUIVALENT,
                    claimed, extracted, SriLankanNicFormat.levenshtein(claimed, extracted),
                    "Document number is the old/new-format equivalent of the claimed NIC.");
        }

        // 4. No match. Grade how far apart they are, comparing canonical forms when both are
        //    derivable so a format difference isn't counted as character-level distance.
        String left = claimedCanonical != null ? claimedCanonical : claimedCorrected;
        String right = extractedCanonical != null ? extractedCanonical : extractedCorrected;

        int distance = SriLankanNicFormat.levenshtein(left, right);
        double similarity = SriLankanNicFormat.normalisedSimilarity(left, right);
        double score = similarity * NON_MATCH_CEILING;

        boolean partial = similarity >= PARTIAL_THRESHOLD;
        String detail = partial
                ? "Document number is close to the claimed NIC (" + distance
                        + " character difference) but does not match - likely OCR damage or a transcription error."
                : "Document number does not match the claimed NIC (" + distance + " character difference).";

        return new IdentityBindingResult(partial ? BindingOutcome.PARTIAL : BindingOutcome.MISMATCH,
                score, claimed, extracted, distance, detail);
    }

    /** Higher score wins; an available result always beats an unavailable one. */
    private boolean isStronger(IdentityBindingResult candidate, IdentityBindingResult incumbent) {
        if (candidate.score() == null) {
            return false;
        }
        if (incumbent.score() == null) {
            return true;
        }
        return candidate.score() > incumbent.score();
    }
}
