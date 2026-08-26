package lk.cf.fr.monolith.document;

import lk.cf.fr.monolith.identity.SriLankanNicFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectTextRequest;
import software.amazon.awssdk.services.rekognition.model.DetectTextResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.TextDetection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.DoubleStream;

/**
 * Real NIC/document OCR validation - ported from cf-fr-server
 * Face_Recognition/utils/NICValidationUtils.containsNationalIdentityCard (AWS Rekognition
 * DetectText + regex/keyword classification of the detected text lines). Active only when
 * {@code aws.enabled=true}; see {@link MockDocumentProcessingService} for the MVP default.
 *
 * <p><b>The classification ladder below is unchanged.</b> Every flag, every regex, every keyword
 * fragment and the final VALID/INVALID/UNKNOWN decision behave exactly as before - this matters
 * because that decision is the frozen registration baseline. What changed is that the evidence
 * the ladder computes on its way to that decision is now <em>returned</em> rather than only
 * logged: the matched NIC number, whether digit-shape correction was needed to match it, and the
 * per-line confidences. See {@link NicOcrResult} for why.
 *
 * <p>The whitespace-compaction and digit-correction steps now delegate to
 * {@link SriLankanNicFormat} so that identity binding normalises both sides of its comparison the
 * same way this classifier does; the operations themselves are identical to the inline versions
 * they replace.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "true")
public class RekognitionDocumentProcessingService implements DocumentProcessingService {

    private final RekognitionClient rekognitionClient;

    public RekognitionDocumentProcessingService(RekognitionClient rekognitionClient) {
        this.rekognitionClient = rekognitionClient;
    }

    @Override
    public NicOcrResult validateNic(byte[] imageBytes, Boolean overrideValid) {
        try {
            DetectTextResponse response = rekognitionClient.detectText(DetectTextRequest.builder()
                    .image(Image.builder().bytes(SdkBytes.fromByteArray(imageBytes)).build())
                    .build());

            List<TextDetection> detections = response.textDetections();
            if (detections == null) {
                log.info("[NIC-OCR] DetectText returned no textDetections at all -> UNKNOWN");
                return NicOcrResult.unknown();
            }

            boolean oldNationalId = false;
            boolean newNationalId = false;
            boolean studentId = false;
            boolean drivingLicense = false;
            List<String> detectedLines = new ArrayList<>();
            List<NicOcrResult.DetectedLine> lines = new ArrayList<>();

            // Candidate NIC numbers, insertion-ordered and de-duplicated. Uncorrected matches are
            // added before corrected ones for any given line, so the first entry is the
            // highest-trust reading. A new-format card that also prints the holder's original
            // 9-digit number legitimately contributes two candidates.
            Set<String> uncorrectedCandidates = new LinkedHashSet<>();
            Set<String> correctedCandidates = new LinkedHashSet<>();
            NicOcrResult.NicNumberFormat primaryFormat = NicOcrResult.NicNumberFormat.NONE;

            for (TextDetection detection : detections) {
                if (!"LINE".equals(detection.typeAsString())) {
                    continue;
                }
                String text = detection.detectedText().toUpperCase();
                detectedLines.add(text);
                lines.add(new NicOcrResult.DetectedLine(text,
                        detection.confidence() == null ? null : detection.confidence().doubleValue()));
                log.info("[NIC-OCR] Detected line (confidence={}): \"{}\"", detection.confidence(), text);

                // Rekognition sometimes detects the digit block and the trailing V/X check-letter as
                // one LINE but with a stray space/tab between them (e.g. "935560233 V" instead of
                // "935560233V") - strip whitespace before running the digit-shape regexes so that
                // doesn't cause a false UNKNOWN. Keyword contains() checks below don't need this since
                // a substring match already tolerates extra characters elsewhere in the line.
                String compact = SriLankanNicFormat.compact(text);

                if (compact.length() == 12 && compact.matches("[0-9]+")) {
                    log.info("[NIC-OCR]   -> matched 12-digit NIC number pattern (old+new NIC): \"{}\" (normalized=\"{}\")", text, compact);
                    newNationalId = true;
                    oldNationalId = true;
                    uncorrectedCandidates.add(compact);
                    if (primaryFormat == NicOcrResult.NicNumberFormat.NONE) {
                        primaryFormat = NicOcrResult.NicNumberFormat.NEW_12_DIGIT;
                    }
                }
                if (compact.matches("[0-9]{9}[VX]") || compact.equals("V") || compact.equals("X")) {
                    log.info("[NIC-OCR]   -> matched old-NIC suffix pattern (9 digits + V/X): \"{}\" (normalized=\"{}\")", text, compact);
                    oldNationalId = true;
                    // The bare "V"/"X" arm sets the flag but carries no number - only record a
                    // candidate when the full 9-digit + letter shape actually matched.
                    if (SriLankanNicFormat.isOldFormat(compact)) {
                        uncorrectedCandidates.add(compact);
                        if (primaryFormat == NicOcrResult.NicNumberFormat.NONE) {
                            primaryFormat = NicOcrResult.NicNumberFormat.OLD_9_PLUS_LETTER;
                        }
                    }
                }

                // Extra fallback pass (does not replace the exact checks above): Rekognition
                // sometimes reads a digit as a visually similar letter, e.g. "555510552V" comes back
                // as "55551O552V" or "55551L552V"/"55551I552V" (1 misread as O/L/I, 0 misread as O,
                // 5 misread as S, 8 misread as B, 2 misread as Z). Re-run the same two digit-shape
                // checks against a version of the line with those substitutions undone, so a single
                // misread character doesn't sink an otherwise-good NIC number line. This only ever
                // *adds* a match on top of the checks above - it can't override or weaken them, and a
                // real English keyword line (LICENSE, STUDENT, UNIVERSITY, ...) still has letters
                // outside this small substitution set, so it can never accidentally become all-digit.
                String digitCorrected = SriLankanNicFormat.correctDigitConfusions(compact);
                if (!digitCorrected.equals(compact)) {
                    if (digitCorrected.length() == 12 && digitCorrected.matches("[0-9]+")) {
                        log.info("[NIC-OCR]   -> matched 12-digit NIC number pattern after OCR digit-correction: \"{}\" (corrected=\"{}\")", text, digitCorrected);
                        newNationalId = true;
                        oldNationalId = true;
                        correctedCandidates.add(digitCorrected);
                        if (primaryFormat == NicOcrResult.NicNumberFormat.NONE) {
                            primaryFormat = NicOcrResult.NicNumberFormat.NEW_12_DIGIT;
                        }
                    }
                    if (digitCorrected.matches("[0-9]{9}[VX]")) {
                        log.info("[NIC-OCR]   -> matched old-NIC suffix pattern after OCR digit-correction: \"{}\" (corrected=\"{}\")", text, digitCorrected);
                        oldNationalId = true;
                        correctedCandidates.add(digitCorrected);
                        if (primaryFormat == NicOcrResult.NicNumberFormat.NONE) {
                            primaryFormat = NicOcrResult.NicNumberFormat.OLD_9_PLUS_LETTER;
                        }
                    }
                }
                if (text.contains("IDENT") || text.contains("NATION") || text.contains("DENTITY") || text.contains("TIONAL")) {
                    log.info("[NIC-OCR]   -> matched \"NATIONAL IDENTITY\" keyword fragment: \"{}\"", text);
                    newNationalId = true;
                }
                if (text.contains("LICEN") || text.contains("DRIVI")) {
                    log.info("[NIC-OCR]   -> matched driving-license keyword fragment: \"{}\"", text);
                    drivingLicense = true;
                }
                if (text.contains("UNI") || text.contains("UNIVER") || text.contains("VERSIT")
                        || text.contains("STUD") || text.contains("TUDENT") || text.contains("UNIVERS")) {
                    log.info("[NIC-OCR]   -> matched student-ID keyword fragment: \"{}\"", text);
                    studentId = true;
                }

                // Extra pass alongside the student-ID check above (not a replacement for it):
                // catches other institutional/library ID cards that don't say "STUDENT" or
                // "UNIVERSITY" at all - e.g. a SLIIT "STUDENT ID" card already trips the check above,
                // but "COLLEGE"/"SCHOOL"/"INSTITUTE"/"CAMPUS"/"FACULTY"/"LIBRARY" cover school IDs,
                // vocational institute IDs, and library membership cards that would otherwise slip
                // through even though they carry a NIC-shaped number printed on them (as this SLIIT
                // card does, in its "NIC No: 940124166V" field). Deliberately does NOT include
                // "REG"/"REGISTRATION" - genuine Sri Lankan NICs can legitimately carry "DEPARTMENT
                // FOR REGISTRATION OF PERSONS" wording, so that would misclassify a real NIC.
                if (text.contains("COLLEGE") || text.contains("COLLEG")
                        || text.contains("SCHOOL") || text.contains("SCHO")
                        || text.contains("INSTITUTE") || text.contains("INSTI")
                        || text.contains("CAMPUS")
                        || text.contains("FACULTY") || text.contains("FACUL")
                        || text.contains("LIBRARY") || text.contains("LIBRAR")) {
                    log.info("[NIC-OCR]   -> matched institution/library-ID keyword fragment: \"{}\"", text);
                    studentId = true;
                }
            }

            NicValidationOutcome outcome;
            if ((newNationalId || oldNationalId) && !(studentId || drivingLicense)) {
                outcome = NicValidationOutcome.VALID;
            } else if (studentId || drivingLicense) {
                outcome = NicValidationOutcome.INVALID;
            } else {
                outcome = NicValidationOutcome.UNKNOWN;
            }

            List<String> candidates = new ArrayList<>(uncorrectedCandidates);
            correctedCandidates.stream().filter(c -> !uncorrectedCandidates.contains(c)).forEach(candidates::add);

            String primary = candidates.isEmpty() ? null : candidates.get(0);
            boolean correctionApplied = primary != null && !uncorrectedCandidates.contains(primary);
            String canonical = primary == null ? null : SriLankanNicFormat.canonicalise(primary);

            OptionalDouble mean = confidences(lines).average();
            OptionalDouble min = confidences(lines).min();
            Double meanConfidence = mean.isPresent() ? mean.getAsDouble() : null;
            Double minConfidence = min.isPresent() ? min.getAsDouble() : null;

            log.info("[NIC-OCR] Classification result={} | flags: newNationalId={}, oldNationalId={}, studentId={}, drivingLicense={} | all detected lines: {}",
                    outcome, newNationalId, oldNationalId, studentId, drivingLicense, detectedLines);
            log.info("[NIC-OCR] Extracted number={} (canonical={}, format={}, digitCorrected={}, candidates={}) | lineConfidence mean={} min={}",
                    primary, canonical, primaryFormat, correctionApplied, candidates, meanConfidence, minConfidence);

            return new NicOcrResult(outcome, primary, canonical, List.copyOf(candidates), primaryFormat,
                    correctionApplied, meanConfidence, minConfidence, List.copyOf(lines),
                    newNationalId, studentId, drivingLicense);
        } catch (Exception e) {
            log.error("[NIC-OCR] DetectText failed -> UNKNOWN", e);
            return NicOcrResult.unknown();
        }
    }

    private static DoubleStream confidences(List<NicOcrResult.DetectedLine> lines) {
        return lines.stream()
                .map(NicOcrResult.DetectedLine::confidence)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue);
    }
}
