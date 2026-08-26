package lk.cf.fr.monolith.document;

import lk.cf.fr.monolith.identity.SriLankanNicFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TEMPORARY MVP WORKAROUND - not a port of any legacy class.
 *
 * <p>Stands in for AWS Rekognition DetectText-based NIC/document validation so the registration
 * path can be demonstrated without real AWS credentials. Active by default
 * ({@code aws.enabled=false}); see REGISTRATION_IMPLEMENTATION_PROGRESS.md "Blockers" and
 * "Temporary assumptions".
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "false", matchIfMissing = true)
public class MockDocumentProcessingService implements DocumentProcessingService {

    private final boolean defaultValid;
    private final String mockNicNumber;

    public MockDocumentProcessingService(@Value("${registration.mock.nic-valid:true}") boolean defaultValid,
                                          @Value("${registration.mock.nic-number:}") String mockNicNumber) {
        this.defaultValid = defaultValid;
        this.mockNicNumber = mockNicNumber;
    }

    /**
     * No OCR happens here, so there is genuinely no number to extract. Set
     * {@code registration.mock.nic-number} to make the mock claim it read a specific number -
     * that is the only way to exercise the identity-binding path (match, mismatch, or
     * old/new-format equivalence) without AWS. Left blank by default, in which case binding
     * correctly reports itself UNAVAILABLE rather than inventing evidence.
     */
    @Override
    public NicOcrResult validateNic(byte[] imageBytes, Boolean overrideValid) {
        boolean valid = overrideValid != null ? overrideValid : defaultValid;
        NicValidationOutcome outcome = valid ? NicValidationOutcome.VALID : NicValidationOutcome.INVALID;

        String number = (mockNicNumber == null || mockNicNumber.isBlank())
                ? null
                : SriLankanNicFormat.compact(mockNicNumber);
        NicOcrResult.NicNumberFormat format = NicOcrResult.NicNumberFormat.NONE;
        if (SriLankanNicFormat.isNewFormat(number)) {
            format = NicOcrResult.NicNumberFormat.NEW_12_DIGIT;
        } else if (SriLankanNicFormat.isOldFormat(number)) {
            format = NicOcrResult.NicNumberFormat.OLD_9_PLUS_LETTER;
        }

        log.info("[MOCK] validateNic imageBytes={} -> {} (extractedNic={}, AWS Rekognition disabled)",
                imageBytes.length, outcome, number);

        return new NicOcrResult(outcome, number,
                number == null ? null : SriLankanNicFormat.canonicalise(number),
                number == null ? List.of() : List.of(number),
                format, false, null, null, List.of(),
                outcome == NicValidationOutcome.VALID, false, false);
    }
}
