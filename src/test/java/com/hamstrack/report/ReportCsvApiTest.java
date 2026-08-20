package com.hamstrack.report;

import com.hamstrack.report.csv.CsvWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET …/reports/{report}.csv} — the export of the <strong>plotted series</strong>
 * (HD-141 R7, §4.4).
 *
 * <p>The distinction that whole slice exists for is asserted first and by name: §1.6 found that
 * where this button exists elsewhere it hands back "flat issue lists without burndown or velocity
 * context", so people go back to screenshotting. Every test below is about the file being the
 * chart — its data points, its window, its measure, its provenance and its caveats — rather than
 * the rows the chart was computed from.
 *
 * <p>Tenancy is {@code ReportCsvTenancyTest}, the budget is {@code ReportCsvThrottleTest}, the
 * escaping rules are {@code CsvWriterTest}, and velocity's refusals are in
 * {@code VelocityRefusalTest} where the rest of them live.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ReportCsvApiTest extends ReportCsvTestBase {

    // ------------------------------------------------------------------ the file itself

    @Test
    void theExportIsTheSeriesWithAProvenanceHeaderAndOneRowPerDataPoint() throws Exception {
        var ctx = newProject();
        issueCreatedAt(ctx, "one", "2025-03-02T10:00:00Z");
        issueCreatedAt(ctx, "two", "2025-03-03T10:00:00Z");

        var csv = csv(ctx, "flow.csv", "?from=2025-03-01&to=2025-03-05&interval=DAY");

        assertThat(comment(csv, "Hamstrack report")).isEqualTo("flow");
        assertThat(comment(csv, "Project")).isEqualTo(projectKey(ctx) + " - Proj");
        assertThat(comment(csv, "Project id")).isEqualTo(ctx.projectId().toString());
        assertThat(comment(csv, "Window")).startsWith("2025-03-01 to 2025-03-05");
        assertThat(comment(csv, "Interval")).isEqualTo("DAY");
        assertThat(comment(csv, "Totals")).isEqualTo("created=2, resolved=0, net=2");
        assertThat(comment(csv, "Computed at")).startsWith("20").endsWith("Z");
        assertThat(comment(csv, "Based on issues")).isEqualTo("2");
        assertThat(comment(csv, "Truncated")).isEqualTo("no (row cap 20000)");

        assertThat(cells(columnLine(csv)))
                .containsExactly("date", "created", "resolved", "openAtEnd", "partial");
        assertThat(dataRows(csv))
                .as("one row per plotted point: five days in the window, no more and no fewer")
                .hasSize(5);
        assertThat(dataRows(csv).get(1)).isEqualTo("2025-03-02,1,0,1,false");
    }

    /**
     * <strong>The refusal that is the feature.</strong> The flow chart plots counts per bucket, so
     * the file is counts per bucket — an issue key or title in it would mean the export had
     * quietly become the issue list §1.6 says people are disappointed by.
     */
    @Test
    void theSeriesExportCarriesNoIssueRows() throws Exception {
        var ctx = newProject();
        issueCreatedAt(ctx, "a distinctive title", "2025-03-02T10:00:00Z");

        var csv = csv(ctx, "flow.csv", "?from=2025-03-01&to=2025-03-05");

        assertThat(csv)
                .as("flow.csv is the plotted series. 'Download matching issues' is a different, "
                    + "separately-labelled thing that hands off to the search export, and the two "
                    + "must never be served by one endpoint")
                .doesNotContain("a distinctive title");
    }

    @Test
    void theResponseIsAnAttachmentNamedForTheProjectAndCacheableOnlyByTheCallerThatAskedFor()
            throws Exception {
        var ctx = newProject();

        getCsv(ctx, ctx.token(), "velocity.csv", "")
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Type", containsString("UTF-8")))
                .andExpect(header().string("Content-Disposition",
                        startsWith("attachment; filename=\"" + projectKey(ctx) + "-velocity-")))
                .andExpect(header().string("Content-Disposition", containsString(".csv\"")))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Vary", containsString("Authorization")));
    }

    @Test
    void theFileOpensWithAByteOrderMarkSoExcelDoesNotMangleTitles() throws Exception {
        var ctx = newProject();

        assertThat(csv(ctx, "aging.csv", "")).startsWith(CsvWriter.BOM);
    }

    // ------------------------------------------------------------------ escaping, over HTTP

    /**
     * The formula guard end to end. {@code CsvWriterTest} owns the rule; this asserts that a title
     * a member typed reaches the file through it, because the guard being present in a helper
     * nobody calls is the failure that would not otherwise show up.
     */
    @Test
    void anIssueTitleThatWouldBeASpreadsheetFormulaIsNeutralisedInTheFile() throws Exception {
        var ctx = newProject();
        completed(ctx, "=HYPERLINK(\"https://evil/\",\"Q3\")",
                "2025-03-01T00:00:00Z", "2025-03-01T00:00:00Z", "2025-03-03T00:00:00Z");

        var csv = csv(ctx, "cycle-time.csv", "?from=2025-03-01&to=2025-03-05");

        assertThat(rowContaining(csv, "HYPERLINK"))
                .as("an issue title is attacker-controlled by anyone who can create an issue; a "
                    + "leading = is a live formula in the reader's spreadsheet")
                .contains("\"'=HYPERLINK(\"\"https://evil/\"\",\"\"Q3\"\")\"");
    }

    /**
     * <strong>The header block is cells too, and a name with a comma cannot open a second
     * one</strong> (round 2, item 1).
     *
     * <p>Round 1 exempted the comment lines from the formula guard on the grounds that "the line
     * already begins with {@code #}, so the whole cell is text" — true of the first cell and of
     * nothing else. A sprint name is attacker-controlled by anyone who can plan a sprint and is
     * constrained by nothing but {@code @Size(max = 60)}; one comma in it used to end the
     * {@code #} cell and open a second one that starts with {@code =}, next to the sprint id and
     * the report's numbers. Asserted over HTTP rather than only in {@code CsvWriterTest} because
     * the value has to travel from the database into the header for this to be the live path.
     */
    @Test
    void aSprintNameWithACommaCannotOpenASecondCellInTheHeader() throws Exception {
        var ctx = newProject();
        var sprint = completedSprint(ctx, "S1, =2+2", "2025-03-01T00:00:00Z",
                "2025-03-14T00:00:00Z", 1, 1, null);

        var csv = csv(ctx, "sprint-review.csv", "?sprintId=" + sprint);

        assertThat(comment(csv, "Sprint")).isEqualTo("S1, =2+2");
        assertThat(cells(commentLine(csv, "Sprint")))
                .as("a comment line is one cell. A second one here begins with '=' and is a live "
                    + "formula in the reader's spreadsheet, with the sprint id in the cell beside "
                    + "it to exfiltrate")
                .containsExactly("# Sprint: S1, =2+2");
        assertThat(commentLines(csv)).allSatisfy(line ->
                assertThat(cells(line)).hasSize(1));
    }

    @Test
    void aTitleWithACommaDoesNotShiftTheRow() throws Exception {
        var ctx = newProject();
        completed(ctx, "fix login, then logout",
                "2025-03-01T00:00:00Z", "2025-03-01T00:00:00Z", "2025-03-03T00:00:00Z");

        var csv = csv(ctx, "cycle-time.csv", "?from=2025-03-01&to=2025-03-05");

        assertThat(rowContaining(csv, "fix login")).contains("\"fix login, then logout\"");
        assertThat(dataRows(csv)).hasSize(1);
    }

    /**
     * <strong>Every row has as many cells as there are columns, on every export</strong> (round 2,
     * item 5).
     *
     * <p>Nothing asserted this before: a seventh value handed to {@code csv.row(...)} under six
     * column names ships a file that opens misaligned, with each column's numbers under the wrong
     * heading — the "these numbers don't match" failure the whole epic is organised around, and
     * one no single-report assertion notices because each looks at one row it already knows.
     *
     * <p>Counted with a real cell parser rather than {@code split(",")}: half these rows carry
     * titles, and a title with a comma is exactly the row an arity check must get right.
     */
    @Test
    void everyRowCarriesExactlyAsManyCellsAsTheColumnRowNames() throws Exception {
        var ctx = newProject();
        completed(ctx, "done, with a comma", "2025-03-01T00:00:00Z", "2025-03-01T00:00:00Z",
                "2025-03-03T00:00:00Z");
        inProgressSince(ctx, "still open", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");
        var sprint = completedSprint(ctx, "S1", "2025-03-01T00:00:00Z", "2025-03-14T00:00:00Z",
                2, 1, 3);

        for (var report : REPORTS) {
            var query = report[1].isEmpty() ? "?sprintId=" + sprint : report[1];
            var csv = csv(ctx, report[0], query);
            var columns = cells(columnLine(csv));

            assertThat(columns)
                    .as("%s has no column row", report[0])
                    .isNotEmpty();
            assertThat(dataRows(csv))
                    .as("%s exported no rows, so the arity check below is guarding nothing — "
                        + "the fixture stopped producing data for it", report[0])
                    .isNotEmpty();
            for (var row : dataRows(csv)) {
                assertThat(cells(row))
                        .as("%s: a row with a different number of cells than the %d columns above "
                            + "it opens misaligned in every spreadsheet, silently, with each "
                            + "column's numbers under the wrong heading: %s",
                                report[0], columns.size(), row)
                        .hasSameSizeAs(columns);
            }
        }
    }

    // ------------------------------------------------------------------ the honesty rules travel

    /**
     * Cycle time is only defined where a start exists, and the export says so twice: an empty
     * {@code cycleDays} cell (never backfilled from {@code created_at}) and a header line counting
     * how many rows that is.
     */
    @Test
    void cycleTimeLeavesTheCellEmptyWhereThereIsNoStartAndSaysHowMany() throws Exception {
        var ctx = newProject();
        var noStart = completed(ctx, "never started",
                "2025-03-01T00:00:00Z", "2025-03-01T00:00:00Z", "2025-03-04T00:00:00Z");
        clearStartedAt(noStart);

        var csv = csv(ctx, "cycle-time.csv", "?from=2025-03-01&to=2025-03-05");

        assertThat(comment(csv, "Completed issues with no start")).isEqualTo("1");
        assertThat(commentTexts(csv))
                .anyMatch(line -> line.startsWith("# cycleDays is empty for those rows"));
        var row = rowContaining(csv, "never started");
        assertThat(cells(row))
                .as("cycleDays empty, leadDays present: created_at is never substituted for a "
                    + "missing start")
                .satisfies(cells -> {
                    assertThat(cells.get(cells.size() - 2)).isEmpty();
                    assertThat(cells.get(cells.size() - 1)).isNotEmpty();
                });
    }

    @Test
    void aSuppressedPercentilePairIsSuppressedInTheFileAndSaysWhy() throws Exception {
        var ctx = newProject();
        completed(ctx, "only one", "2025-03-01T00:00:00Z", "2025-03-01T00:00:00Z",
                "2025-03-03T00:00:00Z");

        var csv = csv(ctx, "cycle-time.csv", "?from=2025-03-01&to=2025-03-05");

        assertThat(comment(csv, "Cycle time percentiles (days)"))
                .as("an empty percentile column reads as zero; a stated suppression does not")
                .startsWith("suppressed - fewer than 5");
    }

    @Test
    void aSuppressedVelocityBandIsSuppressedInTheFileAndTheBarsAreStillExported() throws Exception {
        var ctx = newProject();
        completedSprint(ctx, "S1", "2025-03-01T00:00:00Z", "2025-03-14T00:00:00Z", 2, 1, null);

        var csv = csv(ctx, "velocity.csv", "");

        assertThat(comment(csv, "Forecast"))
                .startsWith("suppressed - the sample is 1 completed sprints");
        assertThat(dataRows(csv))
                .as("the bars are facts and are exported even when the band is not")
                .hasSize(1);
        assertThat(cells(columnLine(csv)))
                .containsExactly("sprintId", "name", "startAt", "completedAt", "committed",
                        "completed", "addedAfterStart", "carriedOver", "unestimatedCount");
    }

    @Test
    void aFilterThatMatchedNothingIsNamedSoAnEmptyFileIsNeverAmbiguous() throws Exception {
        var ctx = newProject();
        issueCreatedAt(ctx, "one", "2025-03-02T10:00:00Z");

        var csv = csv(ctx, "flow.csv",
                "?from=2025-03-01&to=2025-03-05&labelId=" + java.util.UUID.randomUUID());

        assertThat(comment(csv, "Filters that matched nothing")).startsWith("labelId");
    }

    /**
     * Aging has no window, and the file says that rather than leaving the reader to assume one was
     * applied — the same disclosure the JSON makes by omitting {@code from}/{@code to}.
     */
    @Test
    void agingStatesThatItHasNoWindowAndFlattensTheColumnsOntoItsRows() throws Exception {
        var ctx = newProject();
        inProgressSince(ctx, "rotting", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");

        var csv = csv(ctx, "aging.csv", "");

        assertThat(comment(csv, "Window")).startsWith("none - aging is a current-state report");
        assertThat(cells(columnLine(csv)))
                .containsExactly("statusId", "status", "category", "issueId", "key", "title",
                        "ageDays", "assigneeId", "startedAt");
        assertThat(rowContaining(csv, "rotting")).contains("IN_PROGRESS");
    }

    // ------------------------------------------------------------------ the sprint pair

    /**
     * "There is no sprint running" is a 200 on the JSON endpoints, so it is a readable line in the
     * file too. A zero-row CSV with no explanation is exactly the ambiguity the JSON avoided.
     */
    @Test
    void aSprintExportWithNoActiveSprintIsAnAnswerRatherThanAnError() throws Exception {
        var ctx = newProject();

        var burnup = csv(ctx, "sprint-burnup.csv", "");
        var review = csv(ctx, "sprint-review.csv", "");

        assertThat(commentTexts(burnup)).anyMatch(line -> line.startsWith("# No sprint:"));
        assertThat(commentTexts(review)).anyMatch(line -> line.startsWith("# No sprint:"));
        assertThat(dataRows(burnup)).isEmpty();
        assertThat(dataRows(review)).isEmpty();
    }

    @Test
    void theReviewRecordLabelsEveryRowWithTheListItBelongsTo() throws Exception {
        var ctx = newProject();
        var sprint = completedSprint(ctx, "S1", "2025-03-01T00:00:00Z", "2025-03-14T00:00:00Z",
                2, 1, 3);

        var csv = csv(ctx, "sprint-review.csv", "?sprintId=" + sprint);

        assertThat(cells(columnLine(csv))).startsWith("list", "issueId", "key", "title");
        assertThat(comment(csv, "Sprint")).isEqualTo("S1");
        assertThat(dataRows(csv)).anyMatch(row -> row.startsWith("\"committed\","));
        assertThat(dataRows(csv)).anyMatch(row -> row.startsWith("\"completed\","));
        assertThat(dataRows(csv)).anyMatch(row -> row.startsWith("\"carriedOver\","));
    }

    @Test
    void theBurnupExportsTheSeriesAndCountsTheScopeLogRatherThanFoldingItIn() throws Exception {
        var ctx = newProject();
        var sprint = completedSprint(ctx, "S1", "2025-03-01T00:00:00Z", "2025-03-05T00:00:00Z",
                2, 1, null);

        var csv = csv(ctx, "sprint-burnup.csv", "?sprintId=" + sprint + "&measure=COUNT");

        assertThat(cells(columnLine(csv))).containsExactly("date", "scope", "completed");
        assertThat(comment(csv, "Measure")).isEqualTo("COUNT");
        assertThat(comment(csv, "Scope changes"))
                .as("two tables in one CSV is a file no spreadsheet opens correctly, so the log is "
                    + "counted and left on the JSON endpoint")
                .matches("\\d+ - the scope log is a different shape.*JSON endpoint");
        assertThat(dataRows(csv)).isNotEmpty();
    }

    // ------------------------------------------------------------------ refusals

    /**
     * A refusal is the JSON endpoint's, unchanged: the window cap is not silently clamped for a
     * download either.
     */
    @Test
    void aBadWindowIsRefusedExactlyAsItIsOnTheJsonEndpoint() throws Exception {
        var ctx = newProject();

        getCsv(ctx, ctx.token(), "flow.csv", "?from=2025-03-05&to=2025-03-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("is after to")));
        getCsv(ctx, ctx.token(), "velocity.csv", "?sprints=99")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("between 1 and 12")));
    }

    /**
     * <strong>{@code produces = "text/csv"} does not break error rendering.</strong> Spring stores
     * the producible media types on the request and clears them before the exception resolver
     * runs; if that ever stopped being true, every refusal on these six paths would turn into a
     * 406 with an empty body and the SPA would show a blank banner — the exact failure this
     * project already fixed once by enabling {@code problemdetails}. Cheap to pin, expensive to
     * debug.
     */
    @Test
    void anErrorOnACsvPathIsStillAProblemDetailRatherThanA406() throws Exception {
        var ctx = newProject();

        getCsv(ctx, ctx.token(), "flow.csv", "?from=2025-03-05&to=2025-03-01")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("json")))
                .andExpect(jsonPath("$.detail").exists());
    }

    /**
     * <strong>…and also to a caller that pins {@code Accept: text/csv}</strong> (round 2, item 5).
     *
     * <p>Raised in review as a live risk: clearing the producible-types attribute was thought to
     * hand the refusal back to content negotiation against the <em>request's</em> {@code Accept},
     * which for a {@code curl} download script says {@code text/csv} — and a {@code ProblemDetail}
     * is only written as JSON, so the caller would get an empty 406 and the blank-banner failure
     * in a different client. <strong>It does not reproduce, and the reason is worth recording so
     * nobody re-derives the fear:</strong> the problem-detail response carries an explicit
     * {@code Content-Type: application/problem+json}, and {@code AbstractMessageConverterMethod
     * Processor} takes a concrete content type already set on the response as the selected media
     * type instead of negotiating. The {@code Accept} header never gets a vote.
     *
     * <p>Pinned because the property is a happy accident of that ordering rather than something
     * this code asks for, and because the failure it would replace is silent.
     */
    @Test
    void aRefusalReachesAClientThatPinsAcceptTextCsvToo() throws Exception {
        var ctx = newProject();

        mockMvc.perform(get(reportsBase(ctx) + "/flow.csv?from=2025-03-05&to=2025-03-01")
                        .header("Authorization", "Bearer " + ctx.token())
                        .header("Accept", "text/csv"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("problem+json")))
                .andExpect(jsonPath("$.detail", containsString("is after to")));
    }
}
