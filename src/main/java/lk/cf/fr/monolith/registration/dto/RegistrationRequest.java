package lk.cf.fr.monolith.registration.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Mirrors the "data" object inside cf-fr-server Api-Gateway.GatewayController.forwardFaceRecognition's
 * incoming multipart payload for {@code POST /api/facial-auth} - see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.1/§16. Field names and the request shape are
 * kept unchanged so the existing Angular frontend requires no changes (see §19). The optional
 * {@code scannedNIC} file part is handled separately by the controller (multipart file, not JSON).
 */
@Getter
@Setter
public class RegistrationRequest {

    private String cifNo;
    private String nic;
    private String oldNic;
    private String userId;
    private String branchId;
    private String deviceNickname;
    private String prefLang;

    /**
     * MVP-only demo hooks (not present in the legacy contract): when the mock document/face/liveness
     * services are active, force the corresponding outcome instead of the configured default, so a
     * Postman client can demonstrate every branch without restarting the app. Ignored once
     * aws.enabled=true.
     */
    private Boolean mockNicValid;
    private Double mockSimilarity;
    private Boolean mockLivenessPassed;
}
