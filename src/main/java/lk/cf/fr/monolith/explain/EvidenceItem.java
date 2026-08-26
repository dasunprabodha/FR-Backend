package lk.cf.fr.monolith.explain;

/**
 * One piece of evidence considered during a verification attempt, with everything the frontend
 * needs to show not just <em>what</em> it measured but <em>whether and how much it mattered</em>.
 *
 * <p>Three flags carry most of the meaning and are easy to conflate:
 * <ul>
 *   <li>{@link #gated()} - did this evidence participate in the decision rule at all? Comparison 1
 *       and identity binding are both computed on every attempt and neither is gated, for entirely
 *       different reasons (one is legacy-informational, the other is deliberately held out as a
 *       frozen-baseline control).</li>
 *   <li>{@link #decisive()} - did this item determine the outcome? On a failure, every gated item
 *       that failed is decisive. On a pass, the gated item with the least headroom is decisive,
 *       because it is the one that came closest to changing the answer.</li>
 *   <li>{@link #margin()} - signed distance from the threshold. Negative means it failed by that
 *       much; a small positive number means it only just passed. This is the number that turns a
 *       green tick into an informative statement.</li>
 * </ul>
 */
public record EvidenceItem(

        /** Stable identifier, e.g. {@code face.cmp2}, {@code identity.binding}, {@code liveness}. */
        String id,

        /** Human label for the UI. */
        String label,

        /** DOCUMENT | IDENTITY | FACE | LIVENESS | QUALITY. */
        String category,

        /** PASS | FAIL | UNAVAILABLE | INFORMATIONAL. */
        String status,

        /** The measured value, or null when unavailable. */
        Double value,

        /** Decision threshold for this item, or null when it has none. */
        Double threshold,

        /** {@code value - threshold}. Negative = failed by this much. Null when either is absent. */
        Double margin,

        /** SIMILARITY_PERCENT | CONFIDENCE_PERCENT | SCORE_UNIT | CATEGORICAL. */
        String unit,

        /** Categorical reading where a number would mislead, e.g. {@code EXACT_MATCH}, {@code VALID}. */
        String categoricalValue,

        /** Whether this evidence participates in the decision rule. */
        boolean gated,

        /** Whether this evidence determined the outcome. */
        boolean decisive,

        /** Which captured images produced this measurement. */
        String source,

        /** One sentence explaining what this item means and what it found. */
        String detail
) {

    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_INFORMATIONAL = "INFORMATIONAL";

    public boolean isFailure() {
        return STATUS_FAIL.equals(status);
    }
}
