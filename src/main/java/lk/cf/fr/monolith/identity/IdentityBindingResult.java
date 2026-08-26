package lk.cf.fr.monolith.identity;

/**
 * Outcome of comparing the NIC number read off the presented document against the NIC number the
 * applicant claimed.
 *
 * <p>{@link #score()} is deliberately a nullable {@link Double}. When binding is
 * {@link BindingOutcome#UNAVAILABLE} the score is {@code null}, not {@code 0.0}: no document was
 * supplied, or OCR read no NIC-shaped number, so there is <em>no evidence</em> about the binding.
 * That is a fundamentally different state from a document whose number contradicts the claim,
 * which is strong <em>negative</em> evidence. Collapsing the two into a single zero is exactly the
 * kind of information loss that makes conjunctive gating brittle, and any fusion rule consuming
 * this record must be able to tell them apart.
 */
public record IdentityBindingResult(

        BindingOutcome outcome,

        /** 0.0-1.0 graded strength of the binding, or null when {@code outcome == UNAVAILABLE}. */
        Double score,

        /** The claimed NIC after normalisation. */
        String claimed,

        /** The document number that produced this outcome, after normalisation; null if none. */
        String extracted,

        /** Levenshtein distance between the two canonical forms; null when unavailable. */
        Integer editDistance,

        /** Short human-readable explanation, suitable for an audit log or an evidence panel. */
        String detail
) {

    public enum BindingOutcome {
        /** Normalised numbers are identical. */
        EXACT_MATCH,
        /** Identical once OCR digit-shape confusions (O/0, I/1, S/5, ...) are undone. */
        CONFUSION_CORRECTED_MATCH,
        /** Identical once old 9+V/X and new 12-digit forms are reduced to a common canonical form. */
        FORMAT_EQUIVALENT_MATCH,
        /** Close but not equal - typically OCR damage on a genuine document. */
        PARTIAL,
        /** Substantially different numbers: the document does not belong to the claimed identity. */
        MISMATCH,
        /** No document, or no NIC-shaped number read. No evidence either way. */
        UNAVAILABLE
    }

    /** True for the three outcomes that positively bind the document to the claimed identity. */
    public boolean isMatch() {
        return outcome == BindingOutcome.EXACT_MATCH
                || outcome == BindingOutcome.CONFUSION_CORRECTED_MATCH
                || outcome == BindingOutcome.FORMAT_EQUIVALENT_MATCH;
    }

    /** True when evidence exists at all - i.e. this record should participate in a decision. */
    public boolean hasEvidence() {
        return outcome != BindingOutcome.UNAVAILABLE;
    }

    public static IdentityBindingResult unavailable(String claimed, String reason) {
        return new IdentityBindingResult(BindingOutcome.UNAVAILABLE, null, claimed, null, null, reason);
    }
}
