package lk.cf.fr.monolith.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.BatchSummary;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.RunListing;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.RunMeta;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.SampleResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reads runs back off disk, so a finished evaluation can be looked at again without paying for it
 * again.
 *
 * <p>A batch run is expensive and irreversible in the only sense that matters here: several
 * Rekognition calls per sample, billed, against a corpus that may have moved on since. The results
 * were already being written to {@code data/evaluation-runs/<runId>/}, but nothing could read them
 * back - reviewing last week's numbers meant re-running last week's experiment. This turns that
 * directory into an archive the console can browse.
 *
 * <h2>Rebuilding a summary from rows</h2>
 * <p>Most of a {@link BatchSummary} is a fold over the result rows, so it is recomputed on load
 * rather than stored: the outcome counts and pass count that come back are derived from the same
 * rows the table is showing, and cannot drift away from them. What genuinely cannot be recovered
 * from the rows - which corpus was read, how long the run took, how many directories were skipped
 * for not being samples - is read from {@code run-meta.json}.
 *
 * <p>Runs written before that file existed are still readable; they simply report those few fields
 * as unknown rather than inventing them. {@link RunListing#hasMeta()} says which kind a run is.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EvaluationRunArchive {

    static final String RESULTS_JSON = "results.json";
    static final String RESULTS_CSV = "results.csv";
    static final String RUN_META = "run-meta.json";

    private static final DateTimeFormatter ISO_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ObjectMapper objectMapper;

    @Value("${evaluation.output-root:./data/evaluation-runs}")
    private String outputRoot;

    /**
     * Every saved run, newest first.
     *
     * <p>Deliberately does not open the result files: a listing of a few dozen runs would mean
     * parsing several megabytes of JSON to render a dropdown. Sample counts come from the meta
     * file, or from counting rows in the CSV, which is a fraction of the cost.
     */
    public List<RunListing> list() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(this::describe)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(RunListing::savedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to list evaluation runs under " + root, e);
        }
    }

    /** One saved run, rebuilt into the same shape a live run returns. */
    public BatchSummary load(String runId) {
        Path dir = resolveRunDir(runId);
        return summarise(runId, dir.toString(), readResults(dir, runId), readMeta(dir));
    }

    /**
     * The same thing, for a results file that is not in the archive at all.
     *
     * <p>Exists so a CSV can be opened from anywhere - a colleague's export, a copy pulled off a
     * backup - without first being filed under the output root. The format is sniffed rather than
     * taken from the filename, because a file that has been renamed on its way through email is
     * exactly the case this is for.
     *
     * <p>Parsing happens here rather than in the browser so that both routes into the console -
     * picking an archived run and opening a loose file - go through one reader. A second parser in
     * TypeScript would be a second thing to keep in step with the writer.
     */
    public BatchSummary parse(String name, String content) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");
        }

        String label = (name == null || name.isBlank()) ? "uploaded" : name;
        String trimmed = content.stripLeading();
        List<SampleResult> results;

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                results = objectMapper.readValue(content, new TypeReference<List<SampleResult>>() {});
            } catch (JsonProcessingException e) {
                // getOriginalMessage() drops Jackson's source-location suffix, which quotes a slab
                // of the file back at the user and is noise in a toast.
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Not an evaluation results JSON file: " + e.getOriginalMessage(), e);
            }
        } else {
            results = EvaluationCsv.parse(content);
        }

        return summarise(label, null, results, null);
    }

    /**
     * Folds result rows back into a summary.
     *
     * <p>The counts are recomputed from the rows rather than stored, so what the header claims and
     * what the table below it shows cannot disagree.
     */
    private BatchSummary summarise(String runId, String outputDir,
                                   List<SampleResult> results, RunMeta meta) {
        return new BatchSummary(
                runId,
                meta == null ? null : meta.corpusDir(),
                outputDir,
                // Without meta, "found" is unknowable - skipped directories left no trace in the
                // rows. Reporting the row count is the honest floor rather than a guess.
                meta == null ? results.size() : meta.samplesFound(),
                results.size(),
                meta == null ? countErrors(results) : meta.samplesFailed(),
                meta == null ? 0 : meta.samplesSkipped(),
                meta == null ? 0L : meta.totalDurationMs(),
                countBy(results, SampleResult::bindingOutcome),
                countBy(results, SampleResult::ocrOutcome),
                results.stream().filter(r -> Boolean.TRUE.equals(r.similarityPassed())).count(),
                countBy(results, r -> r.proposed() == null ? null : r.proposed().decision()),
                meta == null ? null : meta.abortedReason(),
                results);
    }

    /** Writes the handful of figures a reload cannot derive. Failure here must not fail the run. */
    public void writeMeta(Path outputDir, RunMeta meta) {
        try {
            Files.createDirectories(outputDir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(outputDir.resolve(RUN_META).toFile(), meta);
        } catch (IOException e) {
            // The results themselves are already safely on disk. Losing the metadata costs the
            // archive a corpus path and a duration, which is not worth failing a paid run over.
            log.warn("[Evaluation] Could not write {} to {}: {}", RUN_META, outputDir, e.toString());
        }
    }

    // -------------------------------------------------------------------------------------------

    /** Null for a directory that holds no results at all - a stray folder, not a run. */
    private RunListing describe(Path dir) {
        Path json = dir.resolve(RESULTS_JSON);
        Path csv = dir.resolve(RESULTS_CSV);
        boolean hasJson = Files.isRegularFile(json);
        boolean hasCsv = Files.isRegularFile(csv);
        if (!hasJson && !hasCsv) {
            return null;
        }

        RunMeta meta = readMeta(dir);
        String savedAt = meta != null && meta.completedAt() != null
                ? meta.completedAt()
                : fileTime(hasJson ? json : csv);

        int sampleCount = meta != null ? meta.samplesProcessed() : countCsvRows(csv);

        return new RunListing(
                dir.getFileName().toString(),
                savedAt,
                sampleCount,
                sizeOf(dir),
                hasCsv,
                hasJson,
                meta != null);
    }

    private List<SampleResult> readResults(Path dir, String runId) {
        Path json = dir.resolve(RESULTS_JSON);
        if (Files.isRegularFile(json)) {
            try {
                return objectMapper.readValue(json.toFile(), new TypeReference<List<SampleResult>>() {});
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Could not read " + RESULTS_JSON + " for run " + runId + ": " + e.getMessage(), e);
            }
        }

        // A run whose JSON was deleted or never written still has the CSV, which carries the same
        // rows. It is the file a supervisor is handed, so it has to be readable on its own.
        Path csv = dir.resolve(RESULTS_CSV);
        if (Files.isRegularFile(csv)) {
            return EvaluationCsv.parse(read(csv));
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Run " + runId + " holds neither " + RESULTS_JSON + " nor " + RESULTS_CSV);
    }

    private RunMeta readMeta(Path dir) {
        Path meta = dir.resolve(RUN_META);
        if (!Files.isRegularFile(meta)) {
            return null;
        }
        try {
            return objectMapper.readValue(meta.toFile(), RunMeta.class);
        } catch (IOException e) {
            // Unreadable metadata is not a reason to refuse the results; fall back to deriving.
            log.warn("[Evaluation] Ignoring unreadable {} in {}: {}", RUN_META, dir, e.toString());
            return null;
        }
    }

    /**
     * Confines a run id to a single directory directly under the output root.
     *
     * <p>The id reaches this from a URL path segment, so it is treated the same way the corpus
     * directory is: resolved, normalised, and rejected unless it is still inside the root. A single
     * segment is required as well, so neither {@code ../} nor a nested path can be reached even if
     * the containment check were somehow satisfied.
     */
    private Path resolveRunDir(String runId) {
        if (runId == null || runId.isBlank()
                || runId.contains("/") || runId.contains("\\") || runId.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid run id: " + runId);
        }

        Path root = root();
        Path dir = root.resolve(runId).toAbsolutePath().normalize();
        if (!dir.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "runId must resolve inside evaluation.output-root (" + root + ")");
        }
        if (!Files.isDirectory(dir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved run named " + runId);
        }
        return dir;
    }

    private Path root() {
        return Paths.get(outputRoot).toAbsolutePath().normalize();
    }

    private static int countErrors(List<SampleResult> results) {
        return (int) results.stream().filter(r -> r.error() != null).count();
    }

    private static Map<String, Long> countBy(List<SampleResult> results,
                                             java.util.function.Function<SampleResult, String> key) {
        return results.stream().map(key).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(k -> k, LinkedHashMap::new, Collectors.counting()));
    }

    /** Data rows in a CSV, counted without materialising it - quoted newlines included. */
    private static int countCsvRows(Path csv) {
        if (!Files.isRegularFile(csv)) {
            return 0;
        }
        try {
            return Math.max(0, EvaluationCsv.countRows(read(csv)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read " + file, e);
        }
    }

    private static long sizeOf(Path dir) {
        try (var stream = Files.list(dir)) {
            long total = 0;
            List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            for (Path f : files) {
                total += Files.size(f);
            }
            return total;
        } catch (IOException e) {
            return 0;
        }
    }

    private static String fileTime(Path file) {
        try {
            return ISO_LOCAL.format(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis())
                    .atZone(ZoneId.systemDefault()));
        } catch (IOException e) {
            return "";
        }
    }
}
