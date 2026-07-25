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
                return NicValidationOutcome.UNKNOWN;
            }

            boolean oldNationalId = false;
            boolean newNationalId = false;
            boolean studentId = false;
            boolean drivingLicense = false;

            for (TextDetection detection : detections) {
                if (!"LINE".equals(detection.typeAsString())) {
                    continue;
                }
                String text = detection.detectedText().toUpperCase();
                log.debug("[NIC-OCR] Detected line: {}", text);

                if (text.length() == 12 && text.matches("[0-9]+")) {
                    newNationalId = true;
                    oldNationalId = true;
                }
                if (text.matches("[0-9]{9}[VX]") || text.equals("V") || text.equals("X")) {
                    oldNationalId = true;
                }
                if (text.contains("IDENT") || text.contains("NATION") || text.contains("DENTITY") || text.contains("TIONAL")) {
                    newNationalId = true;
                }
                if (text.contains("LICEN") || text.contains("DRIVI")) {
                    drivingLicense = true;
                }
                if (text.contains("UNI") || text.contains("UNIVER") || text.contains("VERSIT")
                        || text.contains("STUD") || text.contains("TUDENT") || text.contains("UNIVERS")) {
                    studentId = true;
                }
            }

            if ((newNationalId || oldNationalId) && !(studentId || drivingLicense)) {
                return NicValidationOutcome.VALID;
            } else if (studentId || drivingLicense) {
                return NicValidationOutcome.INVALID;
            } else {
                return NicValidationOutcome.UNKNOWN;
            }
        } catch (Exception e) {
            log.error("[NIC-OCR] DetectText failed", e);
            return NicValidationOutcome.UNKNOWN;
        }
    }
}
