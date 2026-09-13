package lk.cf.fr.monolith.recognition;

import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * TEMPORARY MVP WORKAROUND - not a port of any legacy class.
 *
 * <p>Stands in for AWS Rekognition CompareFaces so both the verification and registration paths
 * can be demonstrated end-to-end without real AWS credentials, an S3 bucket of enrolled face
 * images, or network access to AWS. Active by default ({@code aws.enabled=false}); see
 * IMPLEMENTATION_PROGRESS.md / REGISTRATION_IMPLEMENTATION_PROGRESS.md "Blockers" and "Temporary
 * assumptions".
 *
 * <p>Returns a configurable fixed similarity score for both {@link #compareFaces} and
 * {@link #compareFacesInMemory}, unless the caller supplies {@code similarityOverride} (wired
 * from an optional {@code mockSimilarity} field on the request) - a deliberate demo hook so a
 * Postman client can show both the success and mismatch paths without restarting the app.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "false", matchIfMissing = true)
public class MockFaceRecognitionService implements FaceRecognitionService {

    private final double defaultSimilarity;
    private final double similarityThreshold;

    public MockFaceRecognitionService(@Value("${verification.mock.similarity:92}") double defaultSimilarity,
                                       @Value("${verification.similarity-threshold:80}") double similarityThreshold) {
        this.defaultSimilarity = defaultSimilarity;
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public ComparisonResult compareFaces(String nic, byte[] targetImageBytes, Double similarityOverride) {
        ComparisonResult result = compare(similarityOverride);
        log.info("[MOCK] compareFaces nic={} imageBytes={} -> similarity={} match={} (AWS Rekognition disabled)",
                nic, targetImageBytes.length, result.similarity(), result.match());
        return result;
    }

    @Override
    public ComparisonResult compareFacesInMemory(byte[] sourceImageBytes, byte[] targetImageBytes, Double similarityOverride) {
        ComparisonResult result = compare(similarityOverride);
        log.info("[MOCK] compareFacesInMemory sourceBytes={} targetBytes={} -> similarity={} match={} (AWS Rekognition disabled)",
                sourceImageBytes.length, targetImageBytes.length, result.similarity(), result.match());
        return result;
    }

    private ComparisonResult compare(Double similarityOverride) {
        double similarity = similarityOverride != null ? similarityOverride : defaultSimilarity;
        boolean match = similarity >= similarityThreshold;
        // No real face detection happens in mock mode, so there's no bounding box to crop to -
        // ComparisonImageDumpService falls back to dumping the full uncropped image in that case.
        return new ComparisonResult(match, similarity, "{\"mock\":true,\"similarity\":" + similarity + "}", null, null);
    }
}
