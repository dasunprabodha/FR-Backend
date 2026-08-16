package lk.cf.fr.monolith.document;

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
import java.util.List;

/**
 * Real NIC/document OCR validation - ported from cf-fr-server
 * Face_Recognition/utils/NICValidationUtils.containsNationalIdentityCard (AWS Rekognition
 * DetectText + regex/keyword classification of the detected text lines). Active only when
 * {@code aws.enabled=true}; see {@link MockDocumentProcessingService} for the MVP default.
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
    public NicValidationOutcome validateNic(byte[] imageBytes, Boolean overrideValid) {
        try {
            DetectTextResponse response = rekognitionClient.detectText(DetectTextRequest.builder()
                    .image(Image.builder().bytes(SdkBytes.fromByteArray(imageBytes)).build())
                    .build());

            List<TextDetection> detections = response.textDetections();
            if (detections == null) {
                log.info("[NIC-OCR] DetectText returned no textDetections at all -> UNKNOWN");
                return NicValidationOutcome.UNKNOWN;
            }

            boolean oldNationalId = false;
            boolean newNationalId = false;
            boolean studentId = false;
            boolean drivingLicense = false;
            List<String> detectedLines = new ArrayList<>();

            for (TextDetection detection : detections) {
                if (!"LINE".equals(detection.typeAsString())) {
                    continue;
                }
                String text = detection.detectedText().toUpperCase();
                detectedLines.add(text);
                log.info("[NIC-OCR] Detected line (confidence={}): \"{}\"", detection.confidence(), text);

                // Rekognition sometimes detects the digit block and the trailing V/X check-letter as
                // one LINE but with a stray space/tab between them (e.g. "935560233 V" instead of
                // "935560233V") - strip whitespace before running the digit-shape regexes so that
                // doesn't cause a false UNKNOWN. Keyword contains() checks below don't need this since
                // a substring match already tolerates extra characters elsewhere in the line.
                String compact = text.replaceAll("\\s+", "");

                if (compact.length() == 12 && compact.matches("[0-9]+")) {
                    log.info("[NIC-OCR]   -> matched 12-digit NIC number pattern (old+new NIC): \"{}\" (normalized=\"{}\")", text, compact);
                    newNationalId = true;
                    oldNationalId = true;
                }
                if (compact.matches("[0-9]{9}[VX]") || compact.equals("V") || compact.equals("X")) {
                    log.info("[NIC-OCR]   -> matched old-NIC suffix pattern (9 digits + V/X): \"{}\" (normalized=\"{}\")", text, compact);
                    oldNationalId = true;
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
                String digitCorrected = compact
                        .replace('O', '0')
                        .replace('I', '1')
                        .replace('L', '1')
                        .replace('S', '5')
                        .replace('B', '8')
                        .replace('Z', '2');
                if (!digitCorrected.equals(compact)) {
                    if (digitCorrected.length() == 12 && digitCorrected.matches("[0-9]+")) {
                        log.info("[NIC-OCR]   -> matched 12-digit NIC number pattern after OCR digit-correction: \"{}\" (corrected=\"{}\")", text, digitCorrected);
                        newNationalId = true;
                        oldNationalId = true;
                    }
                    if (digitCorrected.matches("[0-9]{9}[VX]")) {
                        log.info("[NIC-OCR]   -> matched old-NIC suffix pattern after OCR digit-correction: \"{}\" (corrected=\"{}\")", text, digitCorrected);
                        oldNationalId = true;
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

            log.info("[NIC-OCR] Classification result={} | flags: newNationalId={}, oldNationalId={}, studentId={}, drivingLicense={} | all detected lines: {}",
                    outcome, newNationalId, oldNationalId, studentId, drivingLicense, detectedLines);
            return outcome;
        } catch (Exception e) {
            log.error("[NIC-OCR] DetectText failed -> UNKNOWN", e);
            return NicValidationOutcome.UNKNOWN;
        }
    }
}
