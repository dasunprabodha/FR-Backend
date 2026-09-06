package lk.cf.fr.monolith.verification.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Mirrors the "data" object inside cf-fr-server Api-Gateway.GatewayController.forwardTransaction's
 * incoming payload for {@code POST /api/validation} - see
 * VERIFICATION_PATH_ARCHITECTURE.md section 3.1/§15. Field names and the request shape are kept
 * unchanged so the existing Angular frontend requires no changes (see §18).
 */
@Getter
@Setter
public class VerificationRequest {

    private String cifNo;
    private String nic;
    private String userId;
    private String branchId;
    private String deviceNickname;
    private String prefLang;

    /**
     * MVP-only demo hook (not present in the legacy contract): when the mock face/liveness
     * services are active, forces the comparison similarity score instead of the configured
     * default, so a Postman client can demonstrate a face-mismatch response without restarting
     * the app. Ignored once verification.aws.enabled=true.
     */
    private Double mockSimilarity;

    /** Same idea as {@link #mockSimilarity}, but for the liveness verdict. */
    private Boolean mockLivenessPassed;
}
