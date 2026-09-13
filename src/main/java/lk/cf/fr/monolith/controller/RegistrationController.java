package lk.cf.fr.monolith.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.image.RekognitionImageNormaliser;
import lk.cf.fr.monolith.registration.dto.RegistrationRequest;
import lk.cf.fr.monolith.registration.dto.RegistrationResponse;
import lk.cf.fr.monolith.registration.service.RegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Direct-call replacement for cf-fr-server Api-Gateway/controller/GatewayController's
 * {@code forwardFaceRecognition} handler (POST /api/facial-auth) - see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §16. The endpoint path, multipart request shape
 * (JSON {@code data} part + optional {@code scannedNIC} file part), and response shape are kept
 * unchanged from the legacy contract so the existing Angular frontend (RosterInformationComponent)
 * needs no changes beyond the base URL.
 *
 * <p>No authentication/authorisation is applied, matching this MVP's explicit scope limitation
 * (and, incidentally, matching the legacy system's own current state - Api-Gateway
 * {@code permitAll()}s this endpoint too).
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/api/facial-auth",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RegistrationResponse> register(
            @RequestParam("data") String dataJson,
            @RequestParam(value = "scannedNIC", required = false) MultipartFile scannedNic) throws Exception {

        RegistrationRequest request = objectMapper.readValue(dataJson, RegistrationRequest.class);

        // Normalised at the boundary rather than at the Rekognition call sites: these same bytes
        // reach DetectText (NIC OCR), CardDetectorService's crop, and CompareFaces (comparison 4),
        // so converting once here keeps all three from having to care about the upload's container
        // format - and turns an unsupported upload into a 400 naming the format, instead of an
        // opaque InvalidImageFormatException raised several layers down.
        byte[] scannedNicBytes = (scannedNic != null && !scannedNic.isEmpty())
                ? RekognitionImageNormaliser.normalise(scannedNic.getBytes(), "scannedNIC")
                : null;

        RegistrationResponse response = registrationService.startRegistration(request, scannedNicBytes);
        return ResponseEntity.ok(response);
    }
}
