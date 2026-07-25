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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError().body(Map.of("status", false, "message", "An unexpected error occurred."));
    }
}
