package lk.cf.fr.monolith.document;

import java.util.List;

/**
 * What the local multilingual pass read off a card, alongside the Latin-script text Rekognition
 * returns.
 *
 * <p>A Sri Lankan NIC is trilingual: every field is printed in Sinhala, Tamil and English. Amazon
 * Rekognition's {@code DetectText} reads Latin script only, so two thirds of the card - and the two
 * scripts a Sri Lankan cardholder actually reads - were invisible to the system. This carries what
 * the second, local engine recovered.
 *
 * <p>Nothing here replaces a Rekognition reading. The NIC number is digits, which Rekognition
 * already reads well; what the local pass adds is <em>corroboration</em> of document type from
 * scripts the primary engine cannot see at all.
 */
public record MultilingualNicText(

        /* False when the engine is switched off, its language data is missing, or the read failed. */
        boolean available,

        /* Every text line recovered, tagged with the script it is written in. */
        List<ScriptLine> lines,

        /* A Sinhala fragment identifying the card as a national identity card was found. */
        boolean sinhalaNationalIdKeyword,

        /* A Tamil fragment identifying the card as a national identity card was found. */
        boolean tamilNationalIdKeyword,

        /*
         * A Sinhala or Tamil NIC field label was read - "number", "born", "name". Evidence that
         * the pass recovered real card text, but NOT that the document is an NIC: those labels are
         * printed on driving licences and student cards too. Reported, never classified on.
         */
        boolean nicFieldLabelsRead,

        /*
         * NIC-shaped numbers the local engine read. Kept separate from Rekognition's candidates
         * rather than merged: which engine saw a number is itself a measurement, and merging them
         * would make the two impossible to tell apart afterwards.
         */
        List<String> numberCandidates,

        Double meanLineConfidence,

        long latencyMs
) {

    /** One line of recovered text and the script it is written in. */
    public record ScriptLine(String text, Script script, Double confidence) {
    }

    /**
     * Which script a line is written in.
     *
     * <p>Determined from the Unicode block the line's characters fall in, not from the language
     * Tesseract was asked for: with {@code sin+tam+eng} loaded together, any line may come back in
     * any of the three, and a line of Sinhala mislabelled as English would be worse than no label.
     */
    public enum Script {
        SINHALA,
        TAMIL,
        LATIN,
        /** Digits, punctuation and separators - the NIC number line is often this. */
        NEUTRAL,
        MIXED
    }

    public static MultilingualNicText unavailable() {
        return new MultilingualNicText(false, List.of(), false, false, false, List.of(), null, 0L);
    }

    /** True when either local script corroborated that this is a national identity card. */
    public boolean anyNationalIdKeyword() {
        return sinhalaNationalIdKeyword || tamilNationalIdKeyword;
    }

    /** Which scripts were actually recovered - the coverage figure worth reporting per run. */
    public boolean sawSinhala() {
        return lines.stream().anyMatch(l -> l.script() == Script.SINHALA || l.script() == Script.MIXED);
    }

    public boolean sawTamil() {
        return lines.stream().anyMatch(l -> l.script() == Script.TAMIL || l.script() == Script.MIXED);
    }
}
