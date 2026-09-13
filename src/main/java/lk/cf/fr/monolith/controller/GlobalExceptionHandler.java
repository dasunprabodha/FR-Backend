package lk.cf.fr.monolith.controller;

import lk.cf.fr.monolith.registration.dto.RegistrationResponse;
import lk.cf.fr.monolith.registration.model.RegistrationException;
import lk.cf.fr.monolith.verification.dto.VerificationResponse;
import lk.cf.fr.monolith.verification.model.VerificationException;
import lk.cf.fr.monolith.verification.model.VerificationState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Replaces the Kafka error-payload / apiGateway-reply-topic mechanism (ErrorHandling.errorPayloadSend,
 * DetermineErrorInfoService in the legacy Api-Gateway) with direct exception -> HTTP mapping - see
 * VERIFICATION_PATH_ARCHITECTURE.md section 13 ("Java exception propagation up the call stack to
 * VerificationController, mapped to an HTTP error response by a @RestControllerAdvice").
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(VerificationException.class)
    public ResponseEntity<VerificationResponse> handleVerificationException(VerificationException e) {
        HttpStatus status = switch (e.getState()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case PROCESSING_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST; // FACE_NOT_MATCHED, LIVENESS_FAILED, DEVICE_BUSY, DEVICE_UNAVAILABLE, CAMERA_ERROR, FAILED
        };
        log.warn("[Verification] {} -> HTTP {} : {}", e.getState(), status.value(), e.getMessage());
        return ResponseEntity.status(status).body(e.getResponse());
    }

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<RegistrationResponse> handleRegistrationException(RegistrationException e) {
        HttpStatus status = switch (e.getState()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case PROCESSING_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST; // DEVICE_BUSY, DEVICE_UNAVAILABLE, CAMERA_ERROR, DOCUMENT_INVALID, FAILED
        };
        log.warn("[Registration] {} -> HTTP {} : {}", e.getState(), status.value(), e.getMessage());
        return ResponseEntity.status(status).body(e.getResponse());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("status", false, "message", e.getMessage()));
    }

    /**
     * Without this, an oversized {@code scannedNIC} upload reaches the generic {@code Exception}
     * handler below and is reported as HTTP 500 "An unexpected error occurred." - which tells the
     * operator nothing and looks like a server fault rather than a file they can re-save smaller.
     * Thrown by the servlet container while parsing the multipart body, so it happens before
     * {@code RegistrationController} runs and cannot be handled at the call site.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("[Upload] rejected oversized multipart upload: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "status", false,
                "message", "The uploaded file is too large. The maximum accepted size is 10MB - "
                        + "please re-save the scan at a smaller size or lower resolution and try again."));
    }

    /**
     * Without this, {@code ResponseStatusException} (thrown by {@code RegistrationApprovalService}
     * for 404/409/502 cases) would fall through to the generic {@code Exception} handler below and
     * lose its intended status code, since {@code @ExceptionHandler(Exception.class)} is broad
     * enough to match it first.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException e) {
        String message = e.getReason() != null ? e.getReason() : e.getMessage();
        log.warn("{} -> HTTP {} : {}", e.getClass().getSimpleName(), e.getStatusCode().value(), message);
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("status", false, "message", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError().body(Map.of("status", false, "message", "An unexpected error occurred."));
    }
}
