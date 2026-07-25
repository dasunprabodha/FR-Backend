package lk.cf.fr.monolith.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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

    public MockDocumentProcessingService(@Value("${registration.mock.nic-valid:true}") boolean defaultValid) {
        this.defaultValid = defaultValid;
    }

    @Override
    public NicValidationOutcome validateNic(byte[] imageBytes, Boolean overrideValid) {
        boolean valid = overrideValid != null ? overrideValid : defaultValid;
        NicValidationOutcome outcome = valid ? NicValidationOutcome.VALID : NicValidationOutcome.INVALID;
        log.info("[MOCK] validateNic imageBytes={} -> {} (AWS Rekognition disabled)", imageBytes.length, outcome);
        return outcome;
    }
}
