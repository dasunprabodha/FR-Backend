package lk.cf.fr.monolith.liveness;

import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import lk.cf.fr.monolith.verification.model.LivenessSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.*;

/**
 * Real liveness implementation - ported from cf-fr-server Face_Recognition/service/LivenessService.
 * Active only when {@code aws.enabled=true}; see {@link MockLivenessService} for the MVP
 * default. Shared by both the verification and registration paths.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "true")
public class RekognitionLivenessService implements LivenessService {

    private final RekognitionClient rekognitionClient;
    private final double confidenceThreshold;

    public RekognitionLivenessService(RekognitionClient rekognitionClient,
                                       @Value("${verification.liveness-confidence-threshold:65}") double confidenceThreshold) {
        this.rekognitionClient = rekognitionClient;
        this.confidenceThreshold = confidenceThreshold;
    }

    @Override
    public LivenessSession createSession() {
        ChallengePreference preference = ChallengePreference.builder().type("FaceMovementChallenge").build();
        CreateFaceLivenessSessionRequestSettings settings = CreateFaceLivenessSessionRequestSettings.builder()
                .challengePreferences(preference)
                .auditImagesLimit(2)
                .build();

        CreateFaceLivenessSessionResponse response = rekognitionClient.createFaceLivenessSession(
                CreateFaceLivenessSessionRequest.builder().settings(settings).build());

        String region = rekognitionClient.serviceClientConfiguration().region().id();
        return new LivenessSession(response.sessionId(), region);
    }

    @Override
    public LivenessOutcome getResults(String sessionId, Boolean passedOverride) {
        GetFaceLivenessSessionResultsResponse response = rekognitionClient.getFaceLivenessSessionResults(
                GetFaceLivenessSessionResultsRequest.builder().sessionId(sessionId).build());

        Float confidence = response.confidence();
        boolean passed = confidence != null && confidence > confidenceThreshold;
        return new LivenessOutcome(passed, confidence == null ? null : confidence.doubleValue());
    }
}
