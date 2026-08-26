package lk.cf.fr.monolith.controller;

import lk.cf.fr.monolith.explain.DecisionExplanation;
import lk.cf.fr.monolith.explain.DecisionExplanationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only explanation API backing the Evidence Dashboard.
 *
 * <p>Additive surface under {@code /api/v2}; the existing registration, verification and approval
 * contracts are untouched, so the Angular console's current screens and the Android client keep
 * working unchanged.
 *
 * <p>Both lookups return the same {@link DecisionExplanation}: by {@code referenceId} for someone
 * holding a receipt from a registration response, and by numeric {@code id} so the Approval
 * Dashboard - which already works in terms of record ids - can link straight through to the
 * evidence behind a queued record.
 *
 * <p>Inherits this application's MVP-wide lack of authentication. It exposes NIC numbers and
 * decision detail, so it belongs behind auth alongside the approval image endpoint.
 */
@RestController
@RequestMapping("/api/v2/attempts")
@RequiredArgsConstructor
public class ExplanationController {

    private final DecisionExplanationService decisionExplanationService;

    @GetMapping(value = "/{referenceId}/evidence", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DecisionExplanation> byReferenceId(@PathVariable String referenceId) {
        return ResponseEntity.ok(decisionExplanationService.explainByReferenceId(referenceId));
    }

    @GetMapping(value = "/by-id/{id}/evidence", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DecisionExplanation> byId(@PathVariable Long id) {
        return ResponseEntity.ok(decisionExplanationService.explainById(id));
    }
}
