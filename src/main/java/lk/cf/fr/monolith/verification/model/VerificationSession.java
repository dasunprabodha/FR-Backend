package lk.cf.fr.monolith.verification.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory verification session, replacing the legacy SharedFlowState (Device_Management,
 * Face_Recognition, Transaction each had their own copy) + Aggregate + latch/TTL-sweeper
 * machinery with a single object holding the two async results directly - see
 * VERIFICATION_PATH_ARCHITECTURE.md section 9/§14.
 *
 * <p>Not persisted across restarts (see IMPLEMENTATION_PROGRESS.md - open question §28 Q2);
 * the durable record of a verification attempt is the {@code TransactionRecord} row.
 *
 * <p>Does not hold the device-capture/liveness {@code CompletableFuture}s itself - those are
 * owned by {@code DeviceCommunicationService}, keyed by {@code referenceId}, so that service can
 * be shared unchanged with the registration path (see DeviceCommunicationService's class doc).
 */
@Getter
public class VerificationSession {

    private final String referenceId;
    private final String nic;
    private final String userId;
    private final String branchId;
    private final String cifNo;
    private final String deviceId;
    private final Instant createdAt = Instant.now();

    private final AtomicReference<VerificationState> state = new AtomicReference<>(VerificationState.CREATED);

    @Setter
    private String livenessSessionId;

    @Setter
    private ComparisonResult comparisonResult;

    @Setter
    private LivenessOutcome livenessResult;

    @Setter
    private String errorMessage;

    public VerificationSession(String referenceId, String nic, String userId, String branchId,
                                String cifNo, String deviceId) {
        this.referenceId = referenceId;
        this.nic = nic;
        this.userId = userId;
        this.branchId = branchId;
        this.cifNo = cifNo;
        this.deviceId = deviceId;
    }

    public VerificationState getState() {
        return state.get();
    }

    public void setState(VerificationState newState) {
        state.set(newState);
    }
}
