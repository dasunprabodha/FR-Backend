package lk.cf.fr.monolith.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.analysis.RegistrationAnalysisService;
import lk.cf.fr.monolith.decision.DecisionOutcome;
import lk.cf.fr.monolith.decision.DependencyAwareDecisionService;
import lk.cf.fr.monolith.decision.EvidenceGroup;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchRequest;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchSummary;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.ProposedDecision;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.RunMeta;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.SampleLabels;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.SampleResult;
import lk.cf.fr.monolith.persistence.entity.RegistrationRecord;
import lk.cf.fr.monolith.persistence.repository.RegistrationRecordRepository;
import lk.cf.fr.monolith.verification.model.ComparisonResult;
import lk.cf.fr.monolith.verification.model.LivenessOutcome;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Runs the registration analysis pipeline over a directory of stored image sets, with no device
 * and no WebSocket involved.
 *
 * <h2>What this unblocks</h2>
 * <p>Before this existed, every trip through the pipeline required a human at an Android device
 * completing three sequential captures. Evaluating a few hundred samples was therefore not merely
 * slow but structurally impossible, and so was any regression test over real images. This service
 * calls exactly the same {@link RegistrationAnalysisService} the live path calls, so measurements
 * taken here describe the deployed pipeline rather than a parallel reimplementation of it.
 *
 * <h2>Expected corpus layout</h2>
 * <pre>
 *   &lt;corpusDir&gt;/
 *     &lt;sampleId&gt;/
 *       nicImage.jpg      (required)
 *       faceImage.jpg     (required)
 *       selfImage.jpg     (required)
 *       scannedNic.jpg    (optional - enables OCR, identity binding and comparison 4)
 *       sample.json       (optional - see SampleLabels)
 * </pre>
 *
 * <p>Those three required filenames are exactly the tags {@code RegistrationService} already
 * writes through {@code LocalPendingImageStorageService}, so
 * {@code ./data/pending-registrations} is itself a valid corpus: every registration ever captured
 * on this machine can be replayed without re-collecting anything. When a sample directory name
 * matches a known {@code referenceId}, the claimed NIC is recovered from the database, so those
 * replays need no {@code sample.json} at all.
 *
 * <h2>Output</h2>
 * <p>A summary is returned to the caller; the full per-sample table is written to
 * {@code <output-root>/<runId>/} as both {@code results.json} and {@code results.csv}. The CSV is
 * the file to load into pandas/R for score distributions, ROC/DET curves and EER - which is only
 * meaningful now that raw similarity scores are no longer clipped at the decision threshold.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BatchEvaluationService {

    private static final String NIC_IMAGE = "nicImage.jpg";
    private static final String FACE_IMAGE = "faceImage.jpg";
    private static final String SELF_IMAGE = "selfImage.jpg";
    private static final String SCANNED_NIC = "scannedNic.jpg";
    private static final String LABELS = "sample.json";

    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Status and action type for persisted evaluation attempts. Deliberately values no operational
     * query matches: the approval queue filters on PENDING_APPROVAL, its history on
     * APPROVED/REJECTED, and the verification enrolment check on APPROVED/AWS_APPROVED plus an
     * ACTIVE flag. Evaluation rows satisfy none of those, so they stay invisible to the running
     * system while remaining fully explainable.
     */
    private static final String EVAL_STATUS = "EVALUATION";
    private static final String EVAL_ACTION_TYPE = "EVALUATION";

    private final RegistrationAnalysisService registrationAnalysisService;
    private final DependencyAwareDecisionService dependencyAwareDecisionService;
    private final RegistrationRecordRepository registrationRecordRepository;
    private final ObjectMapper objectMapper;
    private final EvaluationProgressTracker progressTracker;
    private final EvaluationRunArchive runArchive;

    @Value("${evaluation.corpus-root:./data}")
    private String corpusRoot;

    @Value("${evaluation.output-root:./data/evaluation-runs}")
    private String outputRoot;

    // Snapshotted onto each persisted attempt so the Evidence Dashboard reports the thresholds
    // that were actually in force for the run, exactly as the live path does.
    @Value("${verification.similarity-threshold:80}")
    private Double similarityThreshold;

    @Value("${verification.liveness-confidence-threshold:65}")
    private Double livenessThreshold;

    @Value("${verification.binding-threshold:0.80}")
    private Double bindingThreshold;

    public BatchSummary run(BatchRequest request) {
        long start = System.currentTimeMillis();

        Path corpus = resolveCorpusDir(request.getCorpusDir());
        String runId = (request.getRunId() == null || request.getRunId().isBlank())
                ? "run-" + LocalDateTime.now().format(RUN_STAMP)
                : sanitise(request.getRunId());

        List<Path> samples = listSampleDirs(corpus);
        int found = samples.size();
        if (request.getLimit() != null && request.getLimit() > 0 && samples.size() > request.getLimit()) {
            samples = samples.subList(0, request.getLimit());
        }

        log.info("[Evaluation] runId={} corpus={} samplesFound={} processing={} dryRun={}",
                runId, corpus, found, samples.size(), request.isDryRun());

        // Published before the first sample so a client polling /api/v2/evaluate/progress sees
        // 0% of a known total straight away, rather than a 404 for the first several seconds.
        EvaluationProgressTracker.RunProgress progress =
                progressTracker.begin(runId, corpus.toString(), samples.size(), request.isDryRun());

        List<SampleResult> results = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int failed = 0;
        String abortedReason = null;

        for (Path sampleDir : samples) {
            String sampleId = sampleDir.getFileName().toString();

            // A directory carrying none of the three required captures is not a sample at all -
            // a diagnostics folder, a stray export, an unrelated subdirectory. Skip it quietly
            // instead of raising an error per folder. A directory with *some* of them is a real
            // problem and still surfaces as an error below.
            if (!looksLikeSample(sampleDir)) {
                skipped.add(sampleId);
                progress.sampleSkipped();
                continue;
            }

            progress.startSample(sampleId);
            long sampleStart = System.currentTimeMillis();

            try {
                SampleResult result = request.isDryRun()
                        ? describe(sampleDir, sampleId)
                        : evaluate(sampleDir, sampleId, runId, request.persistEvidenceOrDefault());
                results.add(result);
                if (result.error() != null) {
                    failed++;
                }
                progress.sampleFinished(result.error() != null, System.currentTimeMillis() - sampleStart);
            } catch (Throwable e) {
                // Throwable, not Exception. A corpus run is long and unattended, and the failures
                // that actually end one are not all Exceptions: a NoClassDefFoundError left by a
                // partial build killed a 53-sample run after three paid Rekognition calls, because
                // an Exception-only handler never saw it. One bad sample should cost one sample,
                // never the whole run.
                //
                // VirtualMachineError is the deliberate exception. Once the heap or the stack is
                // gone every remaining sample fails the same way and no bookkeeping here can be
                // trusted, so let it end the run rather than logging 50 more of itself.
                if (e instanceof VirtualMachineError vme) {
                    progress.finish("Run ended by " + vme.getClass().getSimpleName());
                    throw vme;
                }
                failed++;
                String message = rootMessage(e);

                if (isCredentialFailure(e)) {
                    // No point grinding through the rest of the corpus: every remaining sample will
                    // fail the same way, each after several seconds of local detector work. Stop
                    // here and say so plainly instead of emitting one stack trace per sample.
                    abortedReason = credentialAdvice(e);
                    log.error("[Evaluation] runId={} ABORTED at sample={}: {}", runId, sampleId, abortedReason);
                    log.debug("[Evaluation] underlying credential failure", e);
                    results.add(errorResult(sampleId, summarise(message)));
                    progress.sampleFinished(true, System.currentTimeMillis() - sampleStart);
                    break;
                }

                log.error("[Evaluation] runId={} sample={} failed", runId, sampleId, e);
                results.add(errorResult(sampleId, summarise(message)));
                progress.sampleFinished(true, System.currentTimeMillis() - sampleStart);
            }
        }

        progress.finish(abortedReason);

        if (!skipped.isEmpty()) {
            log.info("[Evaluation] runId={} skipped {} directory/directories with none of the required "
                    + "captures (not samples): {}", runId, skipped.size(), preview(skipped));
        }

        Path outputDir = Paths.get(outputRoot).resolve(runId);
        if (!request.isDryRun()) {
            writeResults(outputDir, results);
        }

        BatchSummary summary = new BatchSummary(
                runId,
                corpus.toString(),
                request.isDryRun() ? null : outputDir.toString(),
                found,
                results.size(),
                failed,
                skipped.size(),
                System.currentTimeMillis() - start,
                countBy(results, SampleResult::bindingOutcome),
                countBy(results, SampleResult::ocrOutcome),
                results.stream().filter(SampleResult::similarityPassed).count(),
                countBy(results, r -> r.proposed() == null ? null : r.proposed().decision()),
                abortedReason,
                results);

        // Beside the results, so reopening this run later shows the same header figures it shows
        // now. Only what the rows cannot reproduce is stored - see EvaluationRunArchive.
        if (!request.isDryRun()) {
            runArchive.writeMeta(outputDir, new RunMeta(
                    runId,
                    summary.corpusDir(),
                    summary.samplesFound(),
                    summary.samplesProcessed(),
                    summary.samplesFailed(),
                    summary.samplesSkipped(),
                    summary.totalDurationMs(),
                    summary.abortedReason(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        }

        log.info("[Evaluation] runId={} complete: processed={} failed={} skipped={} durationMs={} "
                        + "binding={} proposedRule={}",
                runId, summary.samplesProcessed(), summary.samplesFailed(), summary.samplesSkipped(),
                summary.totalDurationMs(), summary.bindingOutcomeCounts(),
                summary.proposedDecisionCounts());

        return summary;
    }

    // ---------------------------------------------------------------------------------------

    private SampleResult evaluate(Path sampleDir, String sampleId, String runId, boolean persistEvidence) throws IOException {
        byte[] nicImage = readRequired(sampleDir, NIC_IMAGE, sampleId);
        byte[] faceImage = readRequired(sampleDir, FACE_IMAGE, sampleId);
        byte[] selfImage = readRequired(sampleDir, SELF_IMAGE, sampleId);
        byte[] scannedNic = readOptional(sampleDir, SCANNED_NIC);

        SampleLabels labels = readLabels(sampleDir);
        String claimedNic = resolveClaimedNic(labels, sampleId);

        // Namespaced so card crops and comparison dumps from an evaluation run land in their own
        // folders instead of mutating the corpus being measured.
        String analysisRef = "eval-" + runId + "-" + sampleId;

        RegistrationAnalysisService.DocumentAnalysis document =
                registrationAnalysisService.analyseDocument(analysisRef, claimedNic, scannedNic, nicImage, null);

        RegistrationAnalysisService.FaceAnalysis faces = registrationAnalysisService.analyseFaces(
                analysisRef, nicImage, faceImage, selfImage, scannedNic,
                labels == null ? null : labels.getMockSimilarity());

        LivenessOutcome liveness = toLiveness(labels);
        RegistrationAnalysisService.GateResult gate =
                registrationAnalysisService.evaluateGate(faces, liveness, document.binding());

        // The proposed rule, over the identical inputs the baseline just consumed. Computed here
        // rather than inside the analysis service so that the live registration path cannot reach
        // it even by accident: the baseline must stay the frozen control condition.
        DecisionOutcome proposed =
                dependencyAwareDecisionService.decide(faces, liveness, document.binding());

        if (persistEvidence) {
            persistAttempt(analysisRef, sampleDir, claimedNic, labels, document, faces, liveness, gate);
        }

        return new SampleResult(
                sampleId,
                persistEvidence ? analysisRef : null,
                labels == null ? null : labels.getSubjectId(),
                labels == null ? null : labels.getGroundTruth(),
                labels == null ? null : labels.getAttackType(),
                isConstructed(labels),
                labels == null ? null : labels.getDevice(),
                labels == null ? null : labels.getLighting(),
                claimedNic,

                document.ocr().statusName(),
                document.ocr().source() == null ? null : document.ocr().source().name(),
                document.ocr().extractedNicNumber(),
                document.ocr().meanLineConfidence(),

                document.binding().outcome().name(),
                document.binding().score(),
                document.binding().editDistance(),

                match(faces.cmp1()), similarity(faces.cmp1()),
                match(faces.cmp2()), similarity(faces.cmp2()),
                match(faces.cmp3()), similarity(faces.cmp3()),
                match(faces.cmp4()), similarity(faces.cmp4()),
                match(faces.cmp5()), similarity(faces.cmp5()),

                faces.consistency() == null ? null : faces.consistency().status(),
                faces.consistency() == null ? null : faces.consistency().delta(),

                faces.nicCardCropped(),
                faces.selfNicCardCropped(),
                faces.scannedNicCardCropped(),

                liveness == null ? null : liveness.confidence(),
                liveness == null ? null : liveness.passed(),

                gate.similarityPassed(),
                gate.allPassed(),
                gate.bindingBlocked(),
                gate.channelBlocked(),
                gate.passedExcludingLiveness(),
                gate.failureReason(),

                document.latencyMs(),
                faces.latencyMs(),
                null,
                toProposed(proposed));
    }

    /**
     * Flatten the group verdicts into the shape the results table carries.
     *
     * <p>Group lookup is by name rather than by position so that adding a group to the rule does
     * not silently shift every column one place to the left in an existing analysis notebook.
     */
    private static ProposedDecision toProposed(DecisionOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        Map<String, EvidenceGroup> byName = outcome.groups().stream()
                .collect(Collectors.toMap(EvidenceGroup::name, g -> g, (a, b) -> a, LinkedHashMap::new));

        return new ProposedDecision(
                outcome.decision().name(),
                outcome.driverNames(),
                outcome.reason(),
                stateOf(byName, "DOCUMENT_PORTRAIT"), scoreOf(byName, "DOCUMENT_PORTRAIT"),
                stateOf(byName, "CO_PRESENCE"), scoreOf(byName, "CO_PRESENCE"),
                stateOf(byName, "CHANNEL_AGREEMENT"), scoreOf(byName, "CHANNEL_AGREEMENT"),
                stateOf(byName, "IDENTITY_BINDING"), scoreOf(byName, "IDENTITY_BINDING"),
                stateOf(byName, "LIVENESS"), scoreOf(byName, "LIVENESS"));
    }

    private static String stateOf(Map<String, EvidenceGroup> groups, String name) {
        EvidenceGroup group = groups.get(name);
        return group == null ? null : group.state().name();
    }

    private static Double scoreOf(Map<String, EvidenceGroup> groups, String name) {
        EvidenceGroup group = groups.get(name);
        return group == null ? null : group.score();
    }

    /** Dry run: resolve and report the labels without spending a single Rekognition call. */
    private SampleResult describe(Path sampleDir, String sampleId) throws IOException {
        SampleLabels labels = readLabels(sampleDir);
        String claimedNic = resolveClaimedNic(labels, sampleId);
        String missing = missingRequiredFiles(sampleDir);

        return new SampleResult(sampleId, null,
                labels == null ? null : labels.getSubjectId(),
                labels == null ? null : labels.getGroundTruth(),
                labels == null ? null : labels.getAttackType(),
                isConstructed(labels),
                labels == null ? null : labels.getDevice(),
                labels == null ? null : labels.getLighting(),
                claimedNic,
                Files.exists(sampleDir.resolve(SCANNED_NIC)) ? "WOULD_RUN_OCR" : "NOT_PROVIDED",
                null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null,
                false, false, false, null, null, false, null, null, null, null, null, 0, 0,
                missing.isEmpty() ? null : "Missing required file(s): " + missing,
                null);
    }

    /**
     * The claimed NIC comes from {@code sample.json} when present. Otherwise, if the directory
     * name is a {@code referenceId} from a real registration, it is recovered from the database -
     * which is what makes {@code ./data/pending-registrations} replayable as-is.
     */
    private String resolveClaimedNic(SampleLabels labels, String sampleId) {
        if (labels != null && labels.getClaimedNic() != null && !labels.getClaimedNic().isBlank()) {
            return labels.getClaimedNic();
        }
        return registrationRecordRepository.findByReferenceId(sampleId)
                .map(record -> {
                    log.info("[Evaluation] sample={} claimedNic recovered from registration_record: {}",
                            sampleId, record.getNic());
                    return record.getNic();
                })
                .orElse(null);
    }

    private LivenessOutcome toLiveness(SampleLabels labels) {
        if (labels == null || (labels.getLivenessPassed() == null && labels.getLivenessScore() == null)) {
            // No liveness was recorded for this sample - the gate reports allPassed as unknown
            // rather than silently treating a missing challenge as a failed one.
            return null;
        }
        boolean passed = labels.getLivenessPassed() != null
                ? labels.getLivenessPassed()
                : labels.getLivenessScore() != null && labels.getLivenessScore() > 0;
        return new LivenessOutcome(passed, labels.getLivenessScore());
    }

    /**
     * Store one analysed sample as an attempt row so {@code DecisionExplanationService} can explain
     * it later with no special-casing - the evidence view then works identically for a live
     * registration and for a corpus sample.
     */
    private void persistAttempt(String analysisRef, Path sampleDir, String claimedNic, SampleLabels labels,
                                 RegistrationAnalysisService.DocumentAnalysis document,
                                 RegistrationAnalysisService.FaceAnalysis faces,
                                 LivenessOutcome liveness,
                                 RegistrationAnalysisService.GateResult gate) {
        RegistrationRecord record = registrationRecordRepository.findByReferenceId(analysisRef)
                .orElseGet(RegistrationRecord::new);

        record.setReferenceId(analysisRef);
        record.setNic(claimedNic != null ? claimedNic : "UNKNOWN");
        record.setCifNo(labels == null || labels.getSubjectId() == null ? "EVAL" : labels.getSubjectId());
        record.setUserId("evaluation-harness");
        record.setActionType(EVAL_ACTION_TYPE);
        record.setStatus(EVAL_STATUS);
        record.setActiveStatus("INACTIVE");

        record.setMatch(faces.cmp1().match());
        record.setSimilarity(faces.cmp1().similarity());
        record.setMatch2(faces.cmp2().match());
        record.setSecondSimilarity(faces.cmp2().similarity());
        record.setMatch3(faces.cmp3().match());
        record.setThirdSimilarity(faces.cmp3().similarity());
        if (faces.cmp4() != null) {
            record.setMatch4(faces.cmp4().match());
            record.setFourthSimilarity(faces.cmp4().similarity());
        }
        if (faces.cmp5() != null) {
            record.setMatch5(faces.cmp5().match());
            record.setFifthSimilarity(faces.cmp5().similarity());
        }
        if (faces.consistency() != null) {
            record.setCrossChannelStatus(faces.consistency().status());
            record.setCrossChannelDelta(faces.consistency().delta());
        }

        record.setLivenessPassed(liveness == null ? null : liveness.passed());
        record.setLivenessScore(liveness == null ? null : liveness.confidence());
        record.setValidNicStatus(document.ocr().statusName());
        record.setExtractedNicNumber(document.ocr().extractedNicNumber());
        record.setOcrMeanLineConfidence(document.ocr().meanLineConfidence());
        record.setNicBindingOutcome(document.binding().outcome().name());
        record.setNicBindingScore(document.binding().score());
        record.setNicBindingEditDistance(document.binding().editDistance());

        record.setSimilarityThreshold(similarityThreshold);
        record.setLivenessThreshold(livenessThreshold);
        record.setNicBindingThreshold(bindingThreshold);
        record.setNicBindingGated(gate.bindingGated());
        record.setFailureReason(gate.failureReason());
        record.setImagesUploadedToS3(false);

        // Where the captures came from, so the Evidence Dashboard can show them. These rows never
        // reach the approval workflow (status EVALUATION), so nothing will try to upload them.
        Path dir = sampleDir.toAbsolutePath().normalize();
        record.setNicImageRef(dir.resolve(NIC_IMAGE).toString());
        record.setFaceImageRef(dir.resolve(FACE_IMAGE).toString());
        record.setSelfImageRef(dir.resolve(SELF_IMAGE).toString());

        long elapsed = document.latencyMs() + faces.latencyMs();
        LocalDateTime now = LocalDateTime.now();
        record.setReqTime(now.minusNanos(elapsed * 1_000_000L));
        record.setResTime(now);

        registrationRecordRepository.save(record);
    }

    // ---------------------------------------------------------------------------------------
    // Corpus / IO
    // ---------------------------------------------------------------------------------------

    /**
     * Resolve the requested directory under {@code evaluation.corpus-root} and refuse anything
     * that escapes it. The endpoint takes a caller-supplied path and the API has no authentication
     * yet, so without this check it would be an arbitrary-directory read primitive.
     */
    private Path resolveCorpusDir(String requested) {
        Path root = Paths.get(corpusRoot).toAbsolutePath().normalize();
        Path target = (requested == null || requested.isBlank())
                ? root
                : root.resolve(requested).toAbsolutePath().normalize();

        if (!target.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "corpusDir must resolve inside evaluation.corpus-root (" + root + ")");
        }
        if (!Files.isDirectory(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a directory: " + target);
        }
        return target;
    }

    private List<Path> listSampleDirs(Path corpus) {
        try (var stream = Files.list(corpus)) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to list corpus directory " + corpus, e);
        }
    }

    /**
     * Recognises the failures that mean "your credentials are wrong or expired" rather than
     * "this particular sample is bad". These are worth aborting on, because they apply to every
     * remaining sample equally.
     */
    private static boolean isCredentialFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AwsServiceException aws) {
                String code = aws.awsErrorDetails() == null ? "" : aws.awsErrorDetails().errorCode();
                if (code != null && (code.contains("Token") || code.contains("Signature")
                        || code.contains("Auth") || code.contains("AccessDenied")
                        || code.contains("UnrecognizedClient"))) {
                    return true;
                }
            }
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("security token") || lower.contains("expired token")
                        || lower.contains("credential") || lower.contains("not authorized")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Turns a credential failure into advice that fits the actual problem. The two cases need
     * different fixes and must not be described the same way:
     *
     * <ul>
     *   <li><b>Nothing to send.</b> The SDK searched its provider chain and found no credentials at
     *       all. AWS was never contacted, so saying it "rejected" anything is simply wrong.</li>
     *   <li><b>Sent and refused.</b> Credentials existed but AWS turned them down - almost always an
     *       expired STS session token, occasionally a revoked key.</li>
     * </ul>
     */
    private static String credentialAdvice(Throwable e) {
        String message = rootMessage(e);
        boolean nothingToSend = message != null
                && message.contains("Unable to load credentials from any of the providers");

        if (nothingToSend) {
            return "No AWS credentials are configured, so nothing was sent to AWS. Open "
                    + "FR-Backend/application-local.yml and fill in access-key and secret-key "
                    + "(add session-token too if the key starts with ASIA). Alternatively set "
                    + "aws.enabled=false in that file to run against the mock services, which needs "
                    + "no credentials at all. Nothing further was attempted.";
        }

        return "AWS rejected the credentials that were sent. If your key starts with ASIA it is a "
                + "temporary STS credential and has most likely expired - generate a fresh one and "
                + "update application-local.yml. Details: " + summarise(message)
                + " Nothing further was attempted.";
    }

    /** Keeps a provider-chain dump out of the results table; the full text stays in the log. */
    private static String summarise(String message) {
        if (message == null) {
            return "Unknown error.";
        }
        String single = message.replaceAll("\\s+", " ").trim();
        return single.length() <= 240 ? single : single.substring(0, 237) + "…";
    }

    /** Innermost message - AWS wraps its real explanation several layers down. */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? e.toString() : root.getMessage();
    }

    /** True when at least one of the three required captures is present. */
    private boolean looksLikeSample(Path sampleDir) {
        return Files.isRegularFile(sampleDir.resolve(NIC_IMAGE))
                || Files.isRegularFile(sampleDir.resolve(FACE_IMAGE))
                || Files.isRegularFile(sampleDir.resolve(SELF_IMAGE));
    }

    private static String preview(List<String> names) {
        int shown = Math.min(names.size(), 5);
        String head = String.join(", ", names.subList(0, shown));
        return names.size() > shown ? head + ", … (" + (names.size() - shown) + " more)" : head;
    }

    private String missingRequiredFiles(Path sampleDir) {
        List<String> missing = new ArrayList<>();
        for (String name : List.of(NIC_IMAGE, FACE_IMAGE, SELF_IMAGE)) {
            if (!Files.isRegularFile(sampleDir.resolve(name))) {
                missing.add(name);
            }
        }
        return String.join(", ", missing);
    }

    private byte[] readRequired(Path dir, String name, String sampleId) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Sample '" + sampleId + "' is missing required file " + name);
        }
        return Files.readAllBytes(file);
    }

    private byte[] readOptional(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    private SampleLabels readLabels(Path dir) throws IOException {
        Path file = dir.resolve(LABELS);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return objectMapper.readValue(Files.readAllBytes(file), SampleLabels.class);
    }

    private void writeResults(Path outputDir, List<SampleResult> results) {
        try {
            Files.createDirectories(outputDir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(outputDir.resolve("results.json").toFile(), results);
            Files.writeString(outputDir.resolve("results.csv"), toCsv(results));
            log.info("[Evaluation] Wrote {} result rows to {}", results.size(), outputDir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to write evaluation results to " + outputDir, e);
        }
    }

    /** Flat CSV, one row per sample - the file to load for ROC/DET/EER analysis. */
    private String toCsv(List<SampleResult> results) {
        StringBuilder csv = new StringBuilder();
        csv.append("sampleId,analysisRef,subjectId,groundTruth,attackType,constructed,device,lighting,claimedNic,")
                .append("ocrOutcome,ocrSource,extractedNic,ocrMeanLineConfidence,")
                .append("bindingOutcome,bindingScore,bindingEditDistance,")
                .append("cmp1Match,cmp1Similarity,cmp2Match,cmp2Similarity,")
                .append("cmp3Match,cmp3Similarity,cmp4Match,cmp4Similarity,cmp5Match,cmp5Similarity,")
                .append("crossChannelStatus,crossChannelDelta,")
                .append("nicCardCropped,selfNicCardCropped,scannedNicCardCropped,livenessScore,livenessPassed,")
                .append("similarityPassed,allPassed,bindingBlocked,channelBlocked,decisionWithoutLiveness,failureReason,documentLatencyMs,faceLatencyMs,error,")
                // The proposed rule. Both verdicts sit on one row so a paired test needs no join.
                .append("proposedDecision,proposedDrivers,proposedReason,")
                .append("gDocumentPortraitState,gDocumentPortraitScore,gCoPresenceState,gCoPresenceScore,")
                .append("gChannelAgreementState,gChannelAgreementScore,")
                .append("gIdentityBindingState,gIdentityBindingScore,gLivenessState,gLivenessScore\n");

        for (SampleResult r : results) {
            csv.append(String.join(",",
                    q(r.sampleId()), q(r.analysisRef()), q(r.subjectId()), q(r.groundTruth()), q(r.attackType()),
                    n(r.constructed()), q(r.device()), q(r.lighting()), q(r.claimedNic()),
                    q(r.ocrOutcome()), q(r.ocrSource()), q(r.extractedNic()), n(r.ocrMeanLineConfidence()),
                    q(r.bindingOutcome()), n(r.bindingScore()), n(r.bindingEditDistance()),
                    n(r.cmp1Match()), n(r.cmp1Similarity()), n(r.cmp2Match()), n(r.cmp2Similarity()),
                    n(r.cmp3Match()), n(r.cmp3Similarity()), n(r.cmp4Match()), n(r.cmp4Similarity()),
                    n(r.cmp5Match()), n(r.cmp5Similarity()),
                    q(r.crossChannelStatus()), n(r.crossChannelDelta()),
                    String.valueOf(r.nicCardCropped()), String.valueOf(r.selfNicCardCropped()),
                    String.valueOf(r.scannedNicCardCropped()),
                    n(r.livenessScore()), n(r.livenessPassed()),
                    String.valueOf(r.similarityPassed()), n(r.allPassed()), n(r.bindingBlocked()),
                    n(r.channelBlocked()), n(r.decisionWithoutLiveness()), q(r.failureReason()),
                    String.valueOf(r.documentLatencyMs()), String.valueOf(r.faceLatencyMs()), q(r.error()),
                    q(p(r, ProposedDecision::decision)), q(p(r, ProposedDecision::drivers)),
                    q(p(r, ProposedDecision::reason)),
                    q(p(r, ProposedDecision::documentPortraitState)), n(pd(r, ProposedDecision::documentPortraitScore)),
                    q(p(r, ProposedDecision::coPresenceState)), n(pd(r, ProposedDecision::coPresenceScore)),
                    q(p(r, ProposedDecision::channelAgreementState)), n(pd(r, ProposedDecision::channelAgreementScore)),
                    q(p(r, ProposedDecision::identityBindingState)), n(pd(r, ProposedDecision::identityBindingScore)),
                    q(p(r, ProposedDecision::livenessState)), n(pd(r, ProposedDecision::livenessGroupScore))))
                    .append("\n");
        }
        return csv.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------

    private static Boolean match(ComparisonResult c) {
        return c == null ? null : c.match();
    }

    private static Double similarity(ComparisonResult c) {
        return c == null ? null : c.similarity();
    }

    private static Map<String, Long> countBy(List<SampleResult> results,
                                              java.util.function.Function<SampleResult, String> key) {
        return results.stream().map(key).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(k -> k, LinkedHashMap::new, Collectors.counting()));
    }

    private static String sanitise(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Read a field off a possibly-absent proposed verdict without repeating the null check. */
    private static String p(SampleResult r,
                           java.util.function.Function<ProposedDecision, String> field) {
        return r.proposed() == null ? null : field.apply(r.proposed());
    }

    private static Double pd(SampleResult r,
                             java.util.function.Function<ProposedDecision, Double> field) {
        return r.proposed() == null ? null : field.apply(r.proposed());
    }

    /** CSV-quote a nullable string. */
    private static String q(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** Render a nullable number/boolean as an empty cell rather than the string "null". */
    private static String n(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Whether the labels mark this sample as assembled rather than captured. Absent means captured:
     * every sample predates the flag, and a missing marker must not silently imply "constructed".
     */
    private static Boolean isConstructed(SampleLabels labels) {
        if (labels == null || labels.getExtra() == null) {
            return null;
        }
        String v = labels.getExtra().get("constructed");
        return v == null ? null : Boolean.valueOf("true".equalsIgnoreCase(v.trim()));
    }

    private static SampleResult errorResult(String sampleId, String message) {
        return new SampleResult(sampleId, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null,
                false, false, false, null, null, false, null, null, null, null, null, 0, 0, message, null);
    }
}
