package lk.cf.fr.monolith.liveness;

import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import lk.cf.fr.monolith.verification.model.LivenessSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * TEMPORARY MVP WORKAROUND - not a port of any legacy class.
 *
 * <p>Stands in for AWS Rekognition Face Liveness so both the verification and registration paths
 * can be demonstrated without real AWS credentials. Active by default ({@code aws.enabled=false});
 * see IMPLEMENTATION_PROGRESS.md / REGISTRATION_IMPLEMENTATION_PROGRESS.md "Blockers" and
 * "Temporary assumptions".
 *
 * <p>{@link #createSession()} fabricates a session id (no real AWS session exists); the device
 * WebSocket client used for the Postman demo simply needs to echo that same session id back in
 * its {@code liveness-complete} message, matching the real device's behaviour.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "aws.enabled", havingValue = "false", matchIfMissing = true)
public class MockLivenessService implements LivenessService {

    private final boolean defaultPassed;
    private final double defaultScore;

    public MockLivenessService(@Value("${verification.mock.liveness-passed:true}") boolean defaultPassed,
                                @Value("${verification.mock.liveness-score:90}") double defaultScore) {
        this.defaultPassed = defaultPassed;
        this.defaultScore = defaultScore;
    }

    @Override
    public LivenessSession createSession() {
        String sessionId = "mock-liveness-" + UUID.randomUUID();
        log.info("[MOCK] createSession -> {} (AWS Rekognition disabled)", sessionId);
        return new LivenessSession(sessionId, "mock-region");
    }

    @Override
    public LivenessOutcome getResults(String sessionId, Boolean passedOverride) {
        boolean passed = passedOverride != null ? passedOverride : defaultPassed;
        double score = passed ? defaultScore : Math.min(defaultScore, 40.0);
        log.info("[MOCK] getResults sessionId={} -> passed={} score={} (AWS Rekognition disabled)", sessionId, passed, score);
        return new LivenessOutcome(passed, score);
    }
}
