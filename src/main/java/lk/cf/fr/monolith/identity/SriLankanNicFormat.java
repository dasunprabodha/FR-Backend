package lk.cf.fr.monolith.identity;

import java.util.regex.Pattern;

/**
 * Sri Lankan NIC number normalisation, format conversion and fuzzy comparison primitives.
 *
 * <p>Extracted from the inline logic that previously lived only inside
 * {@code RekognitionDocumentProcessingService} so that the same normalisation is applied on both
 * sides of an identity-binding comparison. {@link #compact(String)} and
 * {@link #correctDigitConfusions(String)} reproduce that service's original behaviour exactly
 * (uppercase + whitespace strip; then the additive OCR digit-shape substitution pass) - the OCR
 * classification ladder must keep behaving identically, so these two methods are deliberately not
 * "improved" while being moved.
 *
 * <h2>The two NIC formats</h2>
 * <ul>
 *   <li><b>Old format</b> (issued until 2015): 9 digits + a {@code V} or {@code X} check letter,
 *       structured {@code YY DDD SSSS} - 2-digit birth year, 3-digit day-of-year (+500 for
 *       female), 4-digit serial.</li>
 *   <li><b>New format</b> (from 1 January 2016): 12 digits, structured {@code YYYY DDD SSSSS} -
 *       4-digit birth year, the same 3-digit day-of-year, 5-digit serial.</li>
 * </ul>
 *
 * <p>A holder of an old-format card has a deterministic new-format equivalent, which is why
 * {@link #oldToNew(String)} exists: a customer may present a card bearing one format while the
 * bank record carries the other, and that must not read as an identity mismatch. Many
 * new-format cards additionally print <em>both</em> numbers, which is why callers should treat
 * OCR output as a list of candidates rather than a single value.
 */
public final class SriLankanNicFormat {

    private SriLankanNicFormat() {
    }

    /** 9 digits followed by the V/X check letter. */
    public static final Pattern OLD_FORMAT = Pattern.compile("[0-9]{9}[VX]");

    /** Exactly 12 digits. */
    public static final Pattern NEW_FORMAT = Pattern.compile("[0-9]{12}");

    /**
     * Nine bare digits - an old-format number whose V/X check letter never reached us.
     *
     * <p>Rekognition returns the digit block and the check letter as separate LINE detections
     * whenever the letter is set apart on the card, and on worn cards it frequently omits the
     * letter altogether: a Sinhala-only 2013 card in this corpus returned {@code "970910409"} at
     * 97.5% confidence with no letter anywhere in the response. Requiring the letter therefore
     * discards a complete, high-confidence number over a character the OCR never had, which is
     * why the letterless shape is recognised here as a lower-trust reading rather than not at all.
     */
    public static final Pattern OLD_FORMAT_NO_LETTER = Pattern.compile("[0-9]{9}");

    /**
     * Uppercase and strip all whitespace. Rekognition sometimes splits the digit block and the
     * trailing check letter with a stray space or tab ("935560233 V"), so the shape regexes must
     * run against a whitespace-free string.
     */
    public static String compact(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.toUpperCase().replaceAll("\\s+", "");
    }

    /**
     * Undo the digit-shape misreads OCR commonly makes on printed NIC numbers. Purely additive:
     * callers run their checks against both the original and the corrected string and never let
     * the corrected form override a match already found on the original.
     */
    public static String correctDigitConfusions(String compact) {
        if (compact == null) {
            return null;
        }
        return compact
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1')
                .replace('S', '5')
                .replace('B', '8')
                .replace('Z', '2');
    }

    public static boolean isOldFormat(String compact) {
        return compact != null && OLD_FORMAT.matcher(compact).matches();
    }

    public static boolean isNewFormat(String compact) {
        return compact != null && NEW_FORMAT.matcher(compact).matches();
    }

    /**
     * True for nine bare digits whose day-of-year field is structurally possible.
     *
     * <p>The structure check is what keeps this from matching any nine-digit string that happens
     * to appear on a document - a serial, an account number, a phone number without its leading
     * zero. Positions 3-5 are the day of birth within the year, 001-366, with 500 added for
     * female holders, so 367-500 and 867-999 are impossible and reject roughly a quarter of
     * arbitrary nine-digit strings outright.
     */
    public static boolean isOldFormatWithoutCheckLetter(String compact) {
        if (compact == null || !OLD_FORMAT_NO_LETTER.matcher(compact).matches()) {
            return false;
        }
        int dayOfYear = Integer.parseInt(compact.substring(2, 5));
        return (dayOfYear >= 1 && dayOfYear <= 366) || (dayOfYear >= 501 && dayOfYear <= 866);
    }

    /**
     * Convert an old-format number to its 12-digit equivalent:
     * {@code YY DDD SSSS + V/X}  ->  {@code 19YY DDD 0SSSS}.
     *
     * <p>Example: {@code 751932523V} -> {@code 197519302523}.
     *
     * <p>The {@code 19} century prefix is safe rather than assumed: the old format was withdrawn
     * at the end of 2015, and anyone born from 2000 onward was only ever issued a new-format
     * card, so every old-format number in circulation belongs to a 19xx birth year.
     *
     * @return the 12-digit equivalent, or {@code null} if the input is not a valid old-format number.
     */
    public static String oldToNew(String compact) {
        if (!isOldFormat(compact)) {
            return null;
        }
        String yy = compact.substring(0, 2);
        String dayOfYear = compact.substring(2, 5);
        String serial = compact.substring(5, 9);
        return "19" + yy + dayOfYear + "0" + serial;
    }

    /**
     * Reduce either format to the 12-digit canonical form so two numbers written in different
     * formats can be compared directly.
     *
     * @return the canonical 12-digit form, or {@code null} when the input matches neither format.
     */
    public static String canonicalise(String compact) {
        if (isNewFormat(compact)) {
            return compact;
        }
        String fromOld = oldToNew(compact);
        return fromOld != null ? fromOld : oldToNewWithoutCheckLetter(compact);
    }

    /**
     * Convert a letterless nine-digit reading to its 12-digit equivalent. The check letter plays
     * no part in {@link #oldToNew(String)} - it is dropped, not used - so a number read without
     * it canonicalises to exactly the same 12 digits as the same number read with it. That is
     * what lets a letterless OCR reading bind against a claimed NIC written in either format.
     *
     * @return the 12-digit equivalent, or {@code null} if the input is not a plausible letterless
     *         old-format number.
     */
    public static String oldToNewWithoutCheckLetter(String compact) {
        if (!isOldFormatWithoutCheckLetter(compact)) {
            return null;
        }
        return "19" + compact.substring(0, 2) + compact.substring(2, 5) + "0" + compact.substring(5, 9);
    }

    /**
     * Standard Levenshtein edit distance, used to grade how badly a non-matching pair differs -
     * a single transposed digit is very different evidence from a completely unrelated number.
     */
    public static int levenshtein(String a, String b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        if (a.equals(b)) {
            return 0;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                current[j] = Math.min(substitution, Math.min(deletion, insertion));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Edit distance expressed as a 0..1 similarity over the longer of the two strings. */
    public static double normalisedSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int distance = levenshtein(a, b);
        int longest = Math.max(a.length(), b.length());
        return Math.max(0.0, 1.0 - (distance / (double) longest));
    }
}
