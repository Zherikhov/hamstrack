package com.hamstrack.report;

import com.hamstrack.report.csv.CsvCells;
import com.hamstrack.report.csv.CsvWriter;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fixture for the {@code .csv} exports (HD-141 R7): the six report names, one request helper, and
 * the three ways a test wants to read a CSV back — the comment header, the column line and the
 * data rows.
 *
 * <p>{@link #REPORTS} is the list the tenancy and throttle tests loop over. It is spelled out here
 * once so that adding a seventh export and forgetting to check it is a change to <em>this</em>
 * constant rather than a silent omission in three separate files — and it is held to the
 * application's real CSV surface by {@code ReportCsvSurfaceTest}, which derives that surface from
 * the handler mapping rather than from a list anybody maintains. A size assertion cannot do that
 * job: a seventh handler added without touching this constant leaves it at six, so "six" stays
 * true while the loops below stop covering the surface.
 */
public abstract class ReportCsvTestBase extends SprintReportTestBase {

    /** Every exported report, and a query string that is valid for it. */
    public static final List<String[]> REPORTS = List.of(
            new String[]{"flow.csv", "?from=2025-03-01&to=2025-03-05"},
            new String[]{"cycle-time.csv", "?from=2025-03-01&to=2025-03-05"},
            new String[]{"aging.csv", ""},
            new String[]{"sprint-burnup.csv", ""},
            new String[]{"sprint-review.csv", ""},
            new String[]{"velocity.csv", ""});

    protected ResultActions getCsv(Ctx ctx, String token, String report, String query)
            throws Exception {
        return getCsvAt(ctx.wsId().toString(), ctx.projectId().toString(), token, report, query);
    }

    protected ResultActions getCsvAt(String workspaceId, String projectId, String token,
                                     String report, String query) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspaceId + "/projects/" + projectId
                                   + "/reports/" + report + (query == null ? "" : query))
                .header("Authorization", "Bearer " + token));
    }

    /** The body of a successful export, BOM and all. */
    protected String csv(Ctx ctx, String report, String query) throws Exception {
        return getCsv(ctx, ctx.token(), report, query)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** The file's lines, with the leading BOM removed so every other helper can ignore it. */
    protected static List<String> lines(String csv) {
        var body = csv.startsWith(CsvWriter.BOM) ? csv.substring(CsvWriter.BOM.length()) : csv;
        return Arrays.asList(body.split("\r\n", -1));
    }

    /**
     * Whether a line is part of the comment header.
     *
     * <p>Matched on {@link CsvWriter#COMMENT_PREFIX} rather than on {@code #}: a comment line is a
     * quoted cell, {@code #} included, so that a comma in a project or sprint name cannot open a
     * second cell on a header line (round 2, item 1). No data row can collide with it — the first
     * column of all six reports is a server-generated id, a date or a fixed list name.
     */
    protected static boolean isComment(String line) {
        return line.startsWith(CsvWriter.COMMENT_PREFIX);
    }

    /** The raw, still-quoted {@code # key: value} line; fails if there is no such line. */
    protected static String commentLine(String csv, String key) {
        var prefix = "# " + key + ": ";
        for (var line : lines(csv)) {
            if (isComment(line) && unquote(line).startsWith(prefix)) {
                return line;
            }
        }
        throw new AssertionError("no '" + prefix + "' line in\n" + csv);
    }

    /** The value of a {@code # key: value} header line; fails if there is no such line. */
    protected static String comment(String csv, String key) {
        return unquote(commentLine(csv, key)).substring(("# " + key + ": ").length());
    }

    protected static boolean hasComment(String csv, String key) {
        return lines(csv).stream()
                .anyMatch(line -> isComment(line) && unquote(line).startsWith("# " + key + ": "));
    }

    /** Every comment line, exactly as written — quoted, so {@link #cells} can be asked its arity. */
    protected static List<String> commentLines(String csv) {
        return lines(csv).stream().filter(ReportCsvTestBase::isComment).toList();
    }

    /** The same lines unquoted, each still carrying its leading {@code "# "}. */
    protected static List<String> commentTexts(String csv) {
        return commentLines(csv).stream().map(ReportCsvTestBase::unquote).toList();
    }

    /** The single column-name row: the first line that is neither a comment nor empty. */
    protected static String columnLine(String csv) {
        for (var line : lines(csv)) {
            if (!isComment(line) && !line.isEmpty()) {
                return line;
            }
        }
        throw new AssertionError("no column row in\n" + csv);
    }

    /** Every data row after the column line. */
    protected static List<String> dataRows(String csv) {
        var rows = new ArrayList<String>();
        boolean pastColumnLine = false;
        for (var line : lines(csv)) {
            if (line.isEmpty() || isComment(line)) {
                continue;
            }
            if (!pastColumnLine) {
                pastColumnLine = true;
                continue;
            }
            rows.add(line);
        }
        return rows;
    }

    /**
     * One CSV line split into its <strong>cells</strong>, quoting respected and unescaped — which
     * is the only way to ask the questions this round added: how many cells does a line actually
     * have (a comment line must have exactly one; a data row must have as many as there are
     * columns), and what is in them. {@code split(",")} answers a different question and answers
     * it wrongly the moment a title contains a comma.
     */
    protected static List<String> cells(String line) {
        return CsvCells.of(line);
    }

    /** A single quoted field's value: outer quotes removed, doubled quotes undoubled. */
    protected static String unquote(String field) {
        return CsvCells.unquote(field);
    }

    /** The first data row whose text contains {@code needle}. */
    protected static String rowContaining(String csv, String needle) {
        for (var row : dataRows(csv)) {
            if (row.contains(needle)) {
                return row;
            }
        }
        throw new AssertionError("no data row containing '" + needle + "' in\n" + csv);
    }

    protected String projectKey(Ctx ctx) {
        return projectRepository.findById(ctx.projectId()).orElseThrow().getKey();
    }
}
