package lk.cf.fr.monolith.controller;

import lk.cf.fr.monolith.registration.dto.ApprovalActionRequest;
import lk.cf.fr.monolith.registration.dto.RegistrationApprovalDto;
import lk.cf.fr.monolith.registration.service.RegistrationApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for the Approval Dashboard (Registration Approval Workflow). Placed under {@code /api}
 * - unlike the legacy {@code /records} controller - so the frontend's existing
 * {@code apiBaseUrl}-based convention and dev proxy cover it without any extra configuration.
 *
 * <p>No authentication/authorization is applied yet, matching this MVP's explicit scope
 * limitation - see {@link RegistrationApprovalService} class doc for how {@code reviewedBy} is
 * still threaded through so a future auth layer only needs to populate it.
 */
@RestController
@RequestMapping("/api/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final RegistrationApprovalService registrationApprovalService;

    @GetMapping("/pending")
    public ResponseEntity<List<RegistrationApprovalDto>> pending() {
        return ResponseEntity.ok(registrationApprovalService.listPending());
    }

    @GetMapping("/history")
    public ResponseEntity<List<RegistrationApprovalDto>> history() {
        return ResponseEntity.ok(registrationApprovalService.listHistory());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RegistrationApprovalDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(registrationApprovalService.getById(id));
    }

    @GetMapping(value = "/{id}/image/{type}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable String type) {
        return ResponseEntity.ok(registrationApprovalService.readImage(id, type));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<RegistrationApprovalDto> approve(@PathVariable Long id,
                                                             @RequestBody(required = false) ApprovalActionRequest body) {
        ApprovalActionRequest request = body != null ? body : new ApprovalActionRequest();
        return ResponseEntity.ok(registrationApprovalService.approve(id, request.getReviewedBy(), request.getRemarks()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<RegistrationApprovalDto> reject(@PathVariable Long id,
                                                            @RequestBody(required = false) ApprovalActionRequest body) {
        ApprovalActionRequest request = body != null ? body : new ApprovalActionRequest();
        return ResponseEntity.ok(registrationApprovalService.reject(id, request.getReviewedBy(), request.getRemarks()));
    }
}
