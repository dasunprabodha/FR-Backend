package lk.cf.fr.monolith.controller;

import lk.cf.fr.monolith.evaluation.BatchEvaluationService;
import lk.cf.fr.monolith.evaluation.EvaluationProgressTracker;
import lk.cf.fr.monolith.evaluation.EvaluationRunArchive;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchRequest;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchSummary;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.ProgressSnapshot;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.RunListing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final EvaluationRunArchive runArchive;

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

    /**
     * Every run already written under {@code evaluation.output-root}, newest first.
     *
     * <p>Runs are expensive - a full corpus is minutes of local inference and a few hundred billed
     * Rekognition calls - so results have to be re-readable without being re-earned. This is the
     * index the console's Saved Results page offers to choose from.
     */
    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<RunListing>> runs() {
        return ResponseEntity.ok(runArchive.list());
    }

    /**
     * One saved run, in the same shape {@code POST /batch} returns.
     *
     * <p>Identical shape on purpose: the page that renders a finished run and the page that
     * renders an archived one then read the same fields, and a stored run cannot start displaying
     * differently from the run that produced it.
     */
    @GetMapping(value = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchSummary> run(@PathVariable String runId) {
        return ResponseEntity.ok(runArchive.load(runId));
    }

    /**
     * Reads a results file that was posted rather than picked off disk.
     *
     * <p>For results that never went through this server's output folder - an export from another
     * machine, a copy off a backup. The body is the file itself, CSV or JSON; the format is
     * sniffed from the content, not the name.
     *
     * <p>Nothing is written and nothing is analysed: this parses and folds, so it costs no AWS
     * calls and leaves no trace on disk.
     */
    @PostMapping(value = "/runs/parse",
            consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchSummary> parse(@RequestParam(required = false) String name,
                                              @RequestBody String content) {
        return ResponseEntity.ok(runArchive.parse(name, content));
    }
}
