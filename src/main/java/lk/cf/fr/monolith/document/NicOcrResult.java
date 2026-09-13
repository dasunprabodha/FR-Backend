package lk.cf.fr.monolith.document;

import java.util.List;

/**
 * Structured output of NIC/document OCR, replacing the bare {@link NicValidationOutcome} the
 * {@code DocumentProcessingService} used to return.
 *
 * <p>The classification {@link #outcome()} is unchanged and still drives the existing
 * accept/retry behaviour in {@code RegistrationService}. What is new is that the evidence the
 * classifier already computed - the NIC number it matched, whether it needed OCR digit-shape
 * correction to match it, and the per-line confidences Rekognition returns - is now carried out
 * of the service instead of being logged and dropped. Two consumers need it:
 *
 * <ul>
 *   <li>{@code IdentityBindingService}, which compares the extracted number against the NIC the
 *       applicant claimed. Without the number leaving this class, that check cannot exist - the
 *       system could only establish "this is <em>a</em> Sri Lankan NIC", never "this is
 *       <em>your</em> NIC".</li>
 *   <li>Evidence fusion, which needs OCR confidence as a per-source quality term rather than a
 *       three-valued enum.</li>
 * </ul>
 *
 * <p>{@link #candidateNumbers()} is a list, not a single value, because new-format cards commonly
 * print both the 12-digit number and the holder's original 9-digit + V/X number. Either may be
 * the one on file, so binding is evaluated against all of them and the best match wins.
 */
public record NicOcrResult(

        /* The unchanged three-way classification: VALID | INVALID | UNKNOWN. */
        NicValidationOutcome outcome,

        /* Best NIC number read off the document, compacted; null when none matched a NIC shape. */
        String extractedNicNumber,

        /* 12-digit canonical form of {@link #extractedNicNumber}, or null if not derivable. */
        String canonicalNicNumber,

        /* Every NIC-shaped string found, in discovery order, uncorrected matches first. */
        List<String> candidateNumbers,

        NicNumberFormat numberFormat,

        /* True when the number only matched after OCR digit-shape correction (O->0, S->5, ...). */
        boolean digitCorrectionApplied,

        /* Mean/min Rekognition per-line confidence across all detected LINE blocks, 0-100. */
        Double meanLineConfidence,
        Double minLineConfidence,

        List<DetectedLine> lines,

        /* The classifier flags, retained so a decision can be explained without re-running OCR. */
        boolean nationalIdKeyword,
        boolean studentIdKeyword,
        boolean drivingLicenseKeyword,

        /* Which image this reading came from. See {@link OcrSource}. */
        OcrSource source,

        /*
         * What the local Sinhala/Tamil pass read, or MultilingualNicText.unavailable().
         *
         * Held alongside the Rekognition reading rather than folded into it: which engine saw what
         * is itself the measurement, and merging them would make the two impossible to separate
         * afterwards. The only field above the multilingual pass may change is nationalIdKeyword,
         * and only ever from false to true.
         */
        MultilingualNicText multilingual
) {

    /**
     * Which image the number was read off.
     *
     * <p>This matters for reporting, not for the arithmetic. A number read from a flatbed-quality
     * upload and a number read from a phone photograph of a card held at arm's length are not
     * equally reliable, and an evaluation that pools them silently would overstate the fallback.
     * Every result therefore carries its provenance so the two can be reported separately.
     */
    public enum OcrSource {
        /** The officer-uploaded {@code scannedNIC} file - the original and preferred source. */
        SCANNED_UPLOAD,
        /** The device-captured {@code nicImage}, used only when no upload was supplied. */
        DEVICE_CAPTURE,
        /** No OCR ran. */
        NONE
    }

    /**
     * Backwards-compatible constructor for the call sites that predate {@link #source}. Everything
     * that constructed a result before the fallback existed was, by definition, reading the
     * uploaded scan, so that is the default rather than {@code NONE}.
     */
    public NicOcrResult(NicValidationOutcome outcome, String extractedNicNumber, String canonicalNicNumber,
                        List<String> candidateNumbers, NicNumberFormat numberFormat,
                        boolean digitCorrectionApplied, Double meanLineConfidence, Double minLineConfidence,
                        List<DetectedLine> lines, boolean nationalIdKeyword, boolean studentIdKeyword,
                        boolean drivingLicenseKeyword) {
        this(outcome, extractedNicNumber, canonicalNicNumber, candidateNumbers, numberFormat,
                digitCorrectionApplied, meanLineConfidence, minLineConfidence, lines,
                nationalIdKeyword, studentIdKeyword, drivingLicenseKeyword, OcrSource.SCANNED_UPLOAD,
                MultilingualNicText.unavailable());
    }

    /** Same reading, re-tagged with the image it actually came from. */
    public NicOcrResult withSource(OcrSource newSource) {
        return new NicOcrResult(outcome, extractedNicNumber, canonicalNicNumber, candidateNumbers,
                numberFormat, digitCorrectionApplied, meanLineConfidence, minLineConfidence, lines,
                nationalIdKeyword, studentIdKeyword, drivingLicenseKeyword, newSource, multilingual);
    }

    /** The same Rekognition reading, with what the local multilingual pass recovered attached. */
    public NicOcrResult withMultilingual(MultilingualNicText text) {
        return new NicOcrResult(outcome, extractedNicNumber, canonicalNicNumber, candidateNumbers,
                numberFormat, digitCorrectionApplied, meanLineConfidence, minLineConfidence, lines,
                nationalIdKeyword, studentIdKeyword, drivingLicenseKeyword, source, text);
    }

    /** One Rekognition {@code LINE} detection: the uppercased text and its confidence (0-100). */
    public record DetectedLine(String text, Double confidence) {
    }

    public enum NicNumberFormat {
        /** 9 digits + V/X check letter (issued until 2015). */
        OLD_9_PLUS_LETTER,
        /** 12 digits (issued from 2016). */
        NEW_12_DIGIT,
        /** 9 digits with no check letter - the letter was set apart on the card, or unread. */
        OLD_9_NO_LETTER,
        /** No NIC-shaped number was read off the document. */
        NONE
    }

    /** True when a NIC number was actually read - i.e. identity binding is possible at all. */
    public boolean hasExtractedNumber() {
        return extractedNicNumber != null && !extractedNicNumber.isBlank();
    }

    /**
     * Result for the "no scanned NIC file was uploaded" case. Distinct from {@code UNKNOWN}
     * (which means OCR ran and could not classify the image) - here OCR never ran, so there is no
     * evidence either way and binding must report itself unavailable rather than failed.
     */
    public static NicOcrResult notProvided() {
        return new NicOcrResult(null, null, null, List.of(), NicNumberFormat.NONE, false,
                null, null, List.of(), false, false, false, OcrSource.NONE,
                MultilingualNicText.unavailable());
    }

    /** Fallback for an OCR call that threw - same shape as {@code UNKNOWN}, no evidence carried. */
    public static NicOcrResult unknown() {
        return new NicOcrResult(NicValidationOutcome.UNKNOWN, null, null, List.of(),
                NicNumberFormat.NONE, false, null, null, List.of(), false, false, false);
    }

    /** {@code outcome.name()}, or {@code "NOT_PROVIDED"} when no document was supplied. */
    public String statusName() {
        return outcome == null ? "NOT_PROVIDED" : outcome.name();
    }

    /** True when the number was recovered from the device capture rather than an upload. */
    public boolean fromDeviceCapture() {
        return source == OcrSource.DEVICE_CAPTURE;
    }
}
