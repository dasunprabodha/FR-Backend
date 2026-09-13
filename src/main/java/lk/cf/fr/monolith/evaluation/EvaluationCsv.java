package lk.cf.fr.monolith.evaluation;

import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.ProposedDecision;
import lk.cf.fr.monolith.evaluation.dto.EvaluationDtos.SampleResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads back the {@code results.csv} written by {@code BatchEvaluationService}.
 *
 * <p>The CSV is the file that leaves this system - it is what gets opened in a spreadsheet, handed
 * to a supervisor, or loaded into a notebook. That makes it the one artefact of a run that must
 * stay readable on its own, so this parses it rather than treating {@code results.json} as the
 * only real record.
 *
 * <h2>Columns are read by name, never by position</h2>
 * <p>The header is used to build a name-to-index map, so a CSV written by an older build - before
 * the proposed-rule columns existed, say - still loads, with the absent columns coming back null.
 * Reading by position would instead shift every value silently one column to the left, which is
 * exactly the kind of error that survives review and corrupts an analysis.
 */
final class EvaluationCsv {

    private EvaluationCsv() {
    }

    /** Number of data rows, without building any objects. */
    static int countRows(String csv) {
        List<List<String>> rows = split(csv);
        return rows.isEmpty() ? 0 : rows.size() - 1;
    }

    static List<SampleResult> parse(String csv) {
        List<List<String>> rows = split(csv);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "results.csv is empty");
        }

        Map<String, Integer> index = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i);
            if (name != null) {
                index.put(name.trim(), i);
            }
        }
        if (!index.containsKey("sampleId")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "results.csv has no sampleId column - is this an evaluation results file?");
        }

        List<SampleResult> results = new ArrayList<>(rows.size() - 1);
        for (int r = 1; r < rows.size(); r++) {
            results.add(toResult(new Row(rows.get(r), index)));
        }
        return results;
    }

    private static SampleResult toResult(Row row) {
        return new SampleResult(
                row.str("sampleId"),
                row.str("analysisRef"),
                row.str("subjectId"),
                row.str("groundTruth"),
                row.str("attackType"),
                row.bool("constructed"),
                row.str("device"),
                row.str("lighting"),
                row.str("claimedNic"),

                row.str("ocrOutcome"),
                row.str("ocrSource"),
                row.str("extractedNic"),
                row.dbl("ocrMeanLineConfidence"),

                row.str("bindingOutcome"),
                row.dbl("bindingScore"),
                row.integer("bindingEditDistance"),

                row.bool("cmp1Match"), row.dbl("cmp1Similarity"),
                row.bool("cmp2Match"), row.dbl("cmp2Similarity"),
                row.bool("cmp3Match"), row.dbl("cmp3Similarity"),
                row.bool("cmp4Match"), row.dbl("cmp4Similarity"),
                row.bool("cmp5Match"), row.dbl("cmp5Similarity"),

                row.str("crossChannelStatus"),
                row.dbl("crossChannelDelta"),

                Boolean.TRUE.equals(row.bool("nicCardCropped")),
                Boolean.TRUE.equals(row.bool("selfNicCardCropped")),
                Boolean.TRUE.equals(row.bool("scannedNicCardCropped")),

                row.dbl("livenessScore"),
                row.bool("livenessPassed"),

                Boolean.TRUE.equals(row.bool("similarityPassed")),
                row.bool("allPassed"),
                row.bool("bindingBlocked"),
                row.bool("channelBlocked"),
                // Older runs wrote this under its previous name; accept both so archived runs reload.
                row.bool("decisionWithoutLiveness") != null
                        ? row.bool("decisionWithoutLiveness") : row.bool("gatePassedExLiveness"),
                row.str("failureReason"),

                row.lng("documentLatencyMs"),
                row.lng("faceLatencyMs"),
                row.str("error"),

                toProposed(row));
    }

    /**
     * Null when the row carries no proposed verdict at all.
     *
     * <p>A run predating the proposed rule has none of these columns; a sample that errored out has
     * them empty. Both must come back as "no verdict" rather than as a ProposedDecision full of
     * nulls, because the console counts non-null verdicts to report how the rule behaved.
     */
    private static ProposedDecision toProposed(Row row) {
        String decision = row.str("proposedDecision");
        if (decision == null) {
            return null;
        }
        return new ProposedDecision(
                decision,
                row.str("proposedDrivers"),
                row.str("proposedReason"),
                row.str("gDocumentPortraitState"), row.dbl("gDocumentPortraitScore"),
                row.str("gCoPresenceState"), row.dbl("gCoPresenceScore"),
                row.str("gChannelAgreementState"), row.dbl("gChannelAgreementScore"),
                row.str("gIdentityBindingState"), row.dbl("gIdentityBindingScore"),
                row.str("gLivenessState"), row.dbl("gLivenessScore"));
    }

    // -------------------------------------------------------------------------------------------

    /** One row plus the header map, so cells are addressed by column name. */
    private record Row(List<String> cells, Map<String, Integer> index) {

        /**
         * Null for a column that is absent from this CSV or empty in this row; the empty string
         * only where the writer actually wrote one.
         *
         * <p>The two are distinguishable because the writer quotes strings and leaves nulls as a
         * bare empty cell, so {@code ""} and nothing at all are different tokens. Collapsing them
         * would turn every "no drivers" verdict - a real, empty list of reasons - into a missing
         * one, and the archived run would stop matching the run that produced it.
         */
        String str(String column) {
            Integer i = index.get(column);
            if (i == null || i >= cells.size()) {
                return null;
            }
            return cells.get(i);
        }

        Double dbl(String column) {
            String value = str(column);
            if (value == null) {
                return null;
            }
            try {
                return Double.valueOf(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Integer integer(String column) {
            Double value = dbl(column);
            return value == null ? null : value.intValue();
        }

        long lng(String column) {
            Double value = dbl(column);
            return value == null ? 0L : value.longValue();
        }

        Boolean bool(String column) {
            String value = str(column);
            if (value == null) {
                return null;
            }
            return "true".equalsIgnoreCase(value) ? Boolean.TRUE
                    : "false".equalsIgnoreCase(value) ? Boolean.FALSE
                    : null;
        }
    }

    /**
     * RFC 4180 split: quoted fields may contain commas, newlines and doubled quotes.
     *
     * <p>Written out rather than pulled in as a dependency because the writer is fifteen lines in
     * the same package and this has to match it exactly - a general-purpose CSV library would be
     * more code to configure than to read, and would still need this file to prove it round-trips.
     */
    private static List<List<String>> split(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean cellWasQuoted = false;

        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);

            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
                continue;
            }

            switch (c) {
                case '"' -> {
                    quoted = true;
                    cellWasQuoted = true;
                }
                case ',' -> {
                    row.add(finish(cell, cellWasQuoted));
                    cellWasQuoted = false;
                }
                case '\r' -> { /* half of a CRLF pair; the \n closes the row */ }
                case '\n' -> {
                    row.add(finish(cell, cellWasQuoted));
                    cellWasQuoted = false;
                    if (!isBlank(row)) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                }
                default -> cell.append(c);
            }
        }

        // A file with no trailing newline still has one row left in hand.
        row.add(finish(cell, cellWasQuoted));
        if (!isBlank(row)) {
            rows.add(row);
        }
        return rows;
    }

    /**
     * Closes the cell being built.
     *
     * <p>An unquoted empty cell is null - that is how the writer renders a missing value. A quoted
     * empty cell is the empty string, which is a value.
     */
    private static String finish(StringBuilder cell, boolean wasQuoted) {
        String value = cell.toString();
        cell.setLength(0);
        return !wasQuoted && value.isEmpty() ? null : value;
    }

    /** A row that is one empty cell is the artefact of a trailing newline, not a record. */
    private static boolean isBlank(List<String> row) {
        return row.size() == 1 && (row.get(0) == null || row.get(0).isEmpty());
    }
}
