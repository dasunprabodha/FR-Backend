package lk.cf.fr.monolith.controller;

import lk.cf.fr.monolith.evaluation.BatchEvaluationService;
import lk.cf.fr.monolith.evaluation.EvaluationProgressTracker;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchRequest;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchSummary;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.ProgressSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offline evaluation harness - runs the registration analysis pipeline over stored image sets
 * with no device attached.
 *
 * <p>New surface under {@code /api/v2}; nothing about the existing {@code /api/facial-auth} or
 * {@code /api/validation} contracts changes, so the Angular console and the Android client are
 * unaffected.
 *
 * <p><b>This is a research/operations endpoint, not a customer-facing one.</b> Like every other
 * controller in this application it currently carries no authentication - the same MVP scope
 * limitation documented across the codebase. It is more sensitive than the others, though,
 * because it reads image directories chosen by the caller and drives paid Rekognition calls, so
 * two guards are already in place: paths are confined to {@code evaluation.corpus-root} by
 * {@code BatchEvaluationService}, and {@code dryRun} lets a corpus be validated for free before
 * any AWS spend. It should be among the first endpoints placed behind auth.
 */
@RestController
@RequestMapping("/api/v2/evaluate")
@Slf4j
@RequiredArgsConstructor
public class EvaluationController {

    private final BatchEvaluationService batchEvaluationService;
    private final EvaluationProgressTracker progressTracker;

    /**
     * Analyse every sample directory under {@code corpusDir}.
     *
     * <p>Runs synchronously and can take a long time - each sample costs up to five card-detector
     * inference passes per image plus three or four Rekognition CompareFaces calls. Use
     * {@code limit} for a smoke run and {@code dryRun} to verify the corpus layout first.
     */
    @PostMapping(value = "/batch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchSummary> batch(@RequestBody(required = false) BatchRequest request) {
        BatchRequest effective = request != null ? request : new BatchRequest();
        log.info("[Evaluation] Batch requested corpusDir={} runId={} limit={} dryRun={}",
                effective.getCorpusDir(), effective.getRunId(), effective.getLimit(), effective.isDryRun());
        return ResponseEntity.ok(batchEvaluationService.run(effective));
    }

    /**
     * How far along the current (or most recent) batch run is.
     *
     * <p>{@code /batch} blocks for the whole run, so this is deliberately a separate GET on a
     * separate connection - it is the only way to see inside a run that is still going. Cheap
     * enough to poll once a second: it reads counters already held in memory and touches neither
     * the database nor AWS.
     *
     * <p>204 No Content when no run has started since the application booted - an empty state, not
     * an error, so a watcher can poll while waiting for a run to be kicked off.
     */
    @GetMapping(value = "/progress", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProgressSnapshot> progress() {
        ProgressSnapshot snapshot = progressTracker.snapshot();
        return snapshot == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(snapshot);
    }
}
