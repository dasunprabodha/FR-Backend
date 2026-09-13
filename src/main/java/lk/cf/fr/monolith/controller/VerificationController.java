package lk.cf.fr.monolith.controller;

import lk.cf.fr.monolith.verification.dto.VerificationRequestEnvelope;
import lk.cf.fr.monolith.verification.dto.VerificationResponse;
import lk.cf.fr.monolith.verification.service.VerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct-call replacement for cf-fr-server Api-Gateway/controller/GatewayController.forwardTransaction
 * (POST /api/validation) - see VERIFICATION_PATH_ARCHITECTURE.md section 15. The endpoint path
 * and request/response shape are kept unchanged from the legacy contract so the existing
 * Angular frontend (ShiftInformationComponent) needs no changes beyond the base URL.
 *
 * <p>No authentication/authorisation is applied, matching this MVP's explicit scope limitation
 * (and, incidentally, matching the legacy system's own current state - Api-Gateway
 * {@code permitAll()}s this endpoint too, see architecture doc section 1/§27 open question 1).
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class VerificationController {

    private final VerificationService verificationService;

    @PostMapping(value = "/api/validation",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VerificationResponse> verify(@RequestBody VerificationRequestEnvelope envelope) {
        if (envelope == null || envelope.getData() == null) {
            throw new IllegalArgumentException("The request body must contain a 'data' object.");
        }
        VerificationResponse response = verificationService.startVerification(envelope.getData());
        return ResponseEntity.ok(response);
    }
}
