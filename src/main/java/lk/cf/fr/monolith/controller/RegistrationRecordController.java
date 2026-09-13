package lk.cf.fr.monolith.controller;

import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lk.cf.fr.monolith.registration.service.RegistrationApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Direct-call replacement for the read/approval endpoints of cf-fr-server
 * Api-Gateway/controller/RegistrationRecordController.java - see
 * REGISTRATION_PATH_MONOLITH_ARCHITECTURE.md §3.5 point 4/5, §16.
 *
 * <p>{@code GET /records/getWithdrawalImage/{referenceId}} is NOT ported - it depends entirely
 * on the external file-storage HTTP service this MVP deliberately does not integrate (see
 * REGISTRATION_IMPLEMENTATION_PROGRESS.md "Blockers"). {@code GET /records/{nic}} returns the
 * JPA entities directly (no separate view/DTO layer) since these are admin/demo-only endpoints.
 */
@RestController
@RequiredArgsConstructor
public class RegistrationRecordController {

    private final RegistrationRecordRepository registrationRecordRepository;
    private final RegistrationApprovalService registrationApprovalService;

    @GetMapping("/records/{nic}")
    public ResponseEntity<List<RegistrationRecord>> getByNic(@PathVariable String nic) {
        return ResponseEntity.ok(registrationRecordRepository.findByNicOrderByReqTimeDesc(nic));
    }

    @PutMapping("/records/{referenceId}/{status}")
    public ResponseEntity<RegistrationRecord> changeStatus(@PathVariable String referenceId, @PathVariable String status) {
        return ResponseEntity.ok(registrationApprovalService.changeStatus(referenceId, status));
    }
}
