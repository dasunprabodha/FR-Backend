package lk.cf.fr.monolith.liveness;

import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import lk.cf.fr.monolith.verification.model.LivenessSession;

/**
 * Liveness-challenge capability. Mirrors cf-fr-server Face_Recognition/service/LivenessService -
 * a thin wrapper over AWS Rekognition Face Liveness (CreateFaceLivenessSession /
 * GetFaceLivenessSessionResults). See VERIFICATION_PATH_ARCHITECTURE.md section 3.4.
 */
public interface LivenessService {

    LivenessSession createSession();

    /**
     * @param passedOverride MVP-only demo hook: when the mock implementation is active, forces the
     *                       returned liveness verdict instead of the configured default. Ignored by
     *                       the real (AWS) implementation.
     */
    LivenessOutcome getResults(String sessionId, Boolean passedOverride);
}
