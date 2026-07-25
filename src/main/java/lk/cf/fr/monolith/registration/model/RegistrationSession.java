package lk.cf.fr.monolith.registration.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory registration session, replacing the legacy's two separate SharedFlowState
 * implementations (Device_Management's and Face_Recognition's) + Aggregate + latch/TTL-sweeper
 * machinery with a single object - see REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §14/§15.
 *
 * <p>Not persisted across restarts; the durable record of a registration attempt is the
 * {@code RegistrationRecord} row. Does not hold device-capture/liveness
 * {@code CompletableFuture}s itself - those are owned by the shared {@code DeviceCommunicationService},
 * keyed by {@code referenceId} (see its class doc for why this is shared with verification).
 */
@Getter
public class RegistrationSession {

    private final String referenceId;
    private final String nic;
    private final String userId;
    private final String cifNo;
    private final String branchId;
    private final String deviceId;
    private final Instant createdAt = Instant.now();

    private final AtomicReference<RegistrationState> state = new AtomicReference<>(RegistrationState.CREATED);

    @Setter
    private String livenessSessionId;

    @Setter
    private String validNicStatus;

    @Setter
    private int nicRetryCount;

    @Setter
    private String errorMessage;

    public RegistrationSession(String referenceId, String nic, String userId, String cifNo,
                                String branchId, String deviceId) {
        this.referenceId = referenceId;
        this.nic = nic;
        this.userId = userId;
        this.cifNo = cifNo;
        this.branchId = branchId;
        this.deviceId = deviceId;
    }

    public RegistrationState getState() {
        return state.get();
    }

    public void setState(RegistrationState newState) {
        state.set(newState);
    }
}
