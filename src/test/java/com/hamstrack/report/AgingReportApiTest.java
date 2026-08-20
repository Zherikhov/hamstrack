package com.hamstrack.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET …/reports/aging} — aging work in progress (reports-proposal §2.2): the half that
 * <strong>names the rotting item</strong>, and the half this epic ships instead of a cumulative
 * flow diagram.
 *
 * <p>Two properties get most of the attention below, because both are places a reasonable
 * implementation quietly does the wrong thing:
 * <ul>
 *   <li><strong>nothing is dropped.</strong> An issue stranded in a status that has left the
 *       project's workflow is exactly the item this report exists to surface, and it is also the
 *       item a "group by workflow column" implementation loses without a trace;</li>
 *   <li><strong>the age has provenance.</strong> Age falls back to {@code created_at} — unlike
 *       cycle time, and legitimately — so the response returns the nullable {@code startedAt}
 *       beside it. "40 days in progress" and "40 days nobody picked it up" are different facts
 *       and the reader must be able to tell which one they are being shown.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AgingReportApiTest extends CycleTimeTestBase {

    // ============================================================ columns

    /**
     * Columns are the project's effective workflow minus DONE — <strong>including the empty
     * ones</strong>. A report that only draws columns it found rows for redraws its own axis every
     * day, so two screenshots of the same board are not comparable.
     */
    @Test
    void everyNonDoneWorkflowStatusIsAColumnEvenWhenEmpty() throws Exception {
        var ctx = newProject();
        inProgressSince(ctx, "the only open issue", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");

        var report = aging(ctx);
        var names = new ArrayList<String>();
        var categories = new ArrayList<String>();
        for (var c : report.get("columns")) {
            names.add(c.get("name").asText());
            categories.add(c.get("category").asText());
        }

        assertThat(names).contains("To Do", "In Progress");
        assertThat(categories)
                .as("this half is about unfinished work; a DONE column would be a different report")
                .doesNotContain("DONE");
        assertThat(column(report, "To Do").get("items"))
                .as("an empty column is a fact about the board, not a reason to hide it")
                .isEmpty();
        assertThat(itemKeys(column(report, "In Progress"))).hasSize(1);
    }

    /** Columns arrive in board order, so the report reads left-to-right like the board does. */
    @Test
    void columnsAreInBoardOrder() throws Exception {
        var ctx = newProject();

        var names = new ArrayList<String>();
        for (var c : aging(ctx).get("columns")) {
            names.add(c.get("name").asText());
        }

        assertThat(names.indexOf("To Do"))
                .as("the workflow's own position ordering, not insertion or alphabetical order")
                .isLessThan(names.indexOf("In Progress"));
    }

    /**
     * The stranded issue. Its status is real and its issue is real; only the <em>workflow</em>
     * moved on. It goes into a trailing "Not on this board" column rather than out of the report.
     */
    @Test
    void anIssueInAStatusOutsideTheWorkflowGetsTheTrailingColumn() throws Exception {
        var ctx = newProject();
        var normal = inProgressSince(ctx, "on the board",
                "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");
        var stranded = inProgressSince(ctx, "left behind by a workflow change",
                "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z");
        strandOutsideTheWorkflow(ctx, stranded);

        var report = aging(ctx);
        var columns = report.get("columns");
        var last = columns.get(columns.size() - 1);

        assertThat(last.get("name").asText()).isEqualTo("Not on this board");
        assertThat(last.get("statusId").isNull())
                .as("the null is the client's signal that this is not a board column")
                .isTrue();
        assertThat(last.get("category").isNull()).isTrue();
        assertThat(itemKeys(last)).containsExactly(key(ctx, stranded));
        assertThat(itemKeys(column(report, "In Progress"))).containsExactly(key(ctx, normal));
        assertThat(report.get("meta").get("basedOnIssues").asLong())
                .as("a dropped row would be invisible in the columns AND in the count; it is in "
                    + "neither")
                .isEqualTo(2);
    }

    /** No stranded issues, no awkward column. */
    @Test
    void theTrailingColumnIsAbsentWhenNothingIsStranded() throws Exception {
        var ctx = newProject();
        inProgressSince(ctx, "on the board", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");

        for (var c : aging(ctx).get("columns")) {
            assertThat(c.get("name").asText()).isNotEqualTo("Not on this board");
        }
    }

    // ============================================================ the items

    /** Oldest first, because the first item a reader's eye lands on should be the worst one. */
    @Test
    void itemsAreOldestFirstWithinTheirColumn() throws Exception {
        var ctx = newProject();
        var newest = inProgressSince(ctx, "just started", "2025-06-01T00:00:00Z", "2025-06-02T00:00:00Z");
        var oldest = inProgressSince(ctx, "rotting", "2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z");
        var middle = inProgressSince(ctx, "middling", "2025-01-01T00:00:00Z", "2025-01-02T00:00:00Z");

        var column = column(aging(ctx), "In Progress");

        assertThat(itemKeys(column))
                .containsExactly(key(ctx, oldest), key(ctx, middle), key(ctx, newest));
        assertThat(column.get("items").get(0).get("ageDays").asDouble())
                .isGreaterThan(column.get("items").get(2).get("ageDays").asDouble());
    }

    /**
     * The fallback, and its provenance. Both items are aged; only one of them was ever started,
     * and the response says which — otherwise "40 days" is a number with no account of itself.
     */
    @Test
    void ageFallsBackToCreationAndSaysSo() throws Exception {
        var ctx = newProject();
        var started = inProgressSince(ctx, "in progress for a while",
                "2025-01-01T00:00:00Z", "2025-06-01T00:00:00Z");
        var untouched = neverStarted(ctx, "filed and forgotten", "2025-01-01T00:00:00Z");

        var report = aging(ctx);
        var startedItem = itemFor(report, key(ctx, started));
        var untouchedItem = itemFor(report, key(ctx, untouched));

        assertThat(startedItem.get("startedAt").asText()).startsWith("2025-06-01");
        assertThat(untouchedItem.get("startedAt").isNull())
                .as("the null IS the disclosure: this age is measured from filing, not from work")
                .isTrue();
        assertThat(untouchedItem.get("ageDays").asDouble())
                .as("both were filed on the same day, and the never-started one is older in "
                    + "aging terms precisely because nothing has happened to it")
                .isGreaterThan(startedItem.get("ageDays").asDouble());
    }

    /** Closed work is not work in progress. */
    @Test
    void completedIssuesAreNotInAnyColumn() throws Exception {
        var ctx = newProject();
        completed(ctx, "finished", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z",
                "2025-03-03T00:00:00Z");

        var report = aging(ctx);

        for (var c : report.get("columns")) {
            assertThat(c.get("items")).isEmpty();
        }
        assertThat(report.get("meta").get("basedOnIssues").asLong()).isZero();
    }

    /** The assignee is labelled, per item — and never aggregated (§4.2). */
    @Test
    void anItemNamesItsAssigneeWithoutTheReportBreakingDownByPerson() throws Exception {
        var ctx = newProject();
        var issue = inProgressSince(ctx, "unassigned", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");

        var report = aging(ctx);

        assertThat(itemFor(report, key(ctx, issue)).get("assigneeId").isNull()).isTrue();
        assertThat(report.toString())
                .as("no per-person aggregate anywhere in the response")
                .doesNotContain("byAssignee");
    }

    // ============================================================ the lines

    /**
     * The p50/p85 drawn across the columns are the project's <strong>completed</strong> work — the
     * line's claim is "older than 85% of everything the team has ever finished", and that is a
     * statement about finished issues, not about the ones in the columns.
     */
    @Test
    void thePercentilesComeFromFinishedWorkNotFromTheColumns() throws Exception {
        var ctx = newProject();
        // Five finished issues, each one day of cycle time.
        for (int day = 1; day <= 5; day++) {
            completed(ctx, "done " + day,
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + (day + 1) + "T00:00:00Z");
        }
        // …and one very old open issue, which must not touch the lines it is measured against.
        inProgressSince(ctx, "far past p85", "2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z");

        var report = aging(ctx);
        var percentiles = report.get("percentiles");

        assertThat(percentiles.get("p50").asDouble()).isCloseTo(1.0, within(0.001));
        assertThat(percentiles.get("p85").asDouble()).isCloseTo(1.0, within(0.001));
        assertThat(column(report, "In Progress").get("items").get(0).get("ageDays").asDouble())
                .as("the whole point of the report: this item is visibly past the p85 line")
                .isGreaterThan(percentiles.get("p85").asDouble());
    }

    /** Same threshold as the other half — one page must not disagree with itself about evidence. */
    @Test
    void thePercentilesAreSuppressedBelowFiveCompletedIssuesAndTheColumnsStillRender()
            throws Exception {
        var ctx = newProject();
        completed(ctx, "the only finished one", "2025-03-01T00:00:00Z", "2025-03-01T00:00:00Z",
                "2025-03-02T00:00:00Z");
        inProgressSince(ctx, "open", "2025-03-01T00:00:00Z", "2025-03-02T00:00:00Z");

        var report = aging(ctx);

        assertThat(report.get("percentiles").get("p50").isNull()).isTrue();
        assertThat(report.get("percentiles").get("p85").isNull()).isTrue();
        assertThat(itemKeys(column(report, "In Progress")))
                .as("the lines are an overlay, not a precondition — the columns render regardless")
                .hasSize(1);
    }

    /**
     * The honesty rule reaches this half too: an issue whose start was never recorded contributes
     * nothing to the lines, because a cycle time it does not have cannot be one.
     */
    @Test
    void completedIssuesWithNoStartDoNotFeedTheLines() throws Exception {
        var ctx = newProject();
        for (int day = 1; day <= 6; day++) {
            var issue = completed(ctx, "done " + day,
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + day + "T00:00:00Z",
                    "2025-03-0" + (day + 1) + "T00:00:00Z");
            if (day > 2) {
                clearStartedAt(issue);
            }
        }

        assertThat(aging(ctx).get("percentiles").get("p50").isNull())
                .as("six completed issues but only two with a start — that is a sample of two")
                .isTrue();
    }

    /**
     * <strong>{@code meta.firstIssueAt} is the project's earliest issue, not its earliest OPEN
     * one</strong> (round 3).
     *
     * <p>The field was {@code min(created_at)} over open issues here, project-wide on flow and
     * window-wide on cycle time — one name on one shared {@code meta} record, three populations,
     * every answer a plausible date. It is aligned rather than renamed because the fact it used
     * to state is already in the body: items come back oldest first, so the earliest open issue
     * is the report's first item, with its key and its age. What is NOT otherwise derivable is
     * how much history the p50/p85 lines were drawn from — those cover the project's entire
     * completed history, so this is their provenance.
     *
     * <p>The fixture makes the two answers differ on purpose: the oldest issue in the project is
     * long CLOSED, so it appears nowhere in this report's columns.
     */
    @Test
    void firstIssueAtIsTheProjectsEarliestIssueNotItsEarliestOpenOne() throws Exception {
        var ctx = newProject();
        completed(ctx, "ancient, and long finished", "2023-01-15T00:00:00Z",
                "2023-01-16T00:00:00Z", "2023-02-01T00:00:00Z");
        inProgressSince(ctx, "the oldest OPEN one", "2025-06-01T00:00:00Z", "2025-06-02T00:00:00Z");

        var meta = aging(ctx).get("meta");

        assertThat(meta.get("firstIssueAt").asText())
                .as("the project's history reaches back to the closed issue, which is what the "
                    + "reference lines were computed over — not to the oldest open item, which "
                    + "the reader can already read off the first column")
                .startsWith("2023-01-15");
        assertThat(meta.get("basedOnIssues").asLong())
                .as("and the open count is still only the open work")
                .isEqualTo(1);
    }

    // ============================================================ shape

    /** An empty project answers with its board, not with a blank panel (§6). */
    @Test
    void anEmptyProjectStillReturnsItsColumns() throws Exception {
        var ctx = newProject();

        var report = aging(ctx);

        assertThat(report.get("columns")).isNotEmpty();
        assertThat(report.get("meta").get("basedOnIssues").asLong()).isZero();
        assertThat(report.get("meta").get("truncated").asBoolean()).isFalse();
        assertThat(report.get("percentiles").get("p50").isNull()).isTrue();
    }

    /**
     * The endpoint has no window of its own, so there is nothing a caller can state wrongly:
     * query parameters it does not declare — including the pair that 400s on every other report
     * on this base path — are ignored rather than refused.
     *
     * <p>Renamed in round 2 from {@code theEndpointHasNoWindowAndCannotBe400ed}, which claimed
     * more than it tested. This proves the <em>query-parameter</em> case only; the path case is
     * the test below, and it goes the other way.
     */
    @Test
    void unknownQueryParametersAreIgnoredRatherThanRefused() throws Exception {
        var ctx = newProject();

        mockMvc.perform(get(reportsBase(ctx) + "/aging?from=2025-12-31&to=2025-01-01")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());
    }

    /**
     * <strong>A malformed path id is a 400 here too</strong> — the correction to the class note
     * that called this "the only endpoint that cannot return a 400".
     *
     * <p>It is raised by argument binding ({@code MethodArgumentTypeMismatchException}) before
     * the handler method runs, so it is not the endpoint's own refusal and no report code is
     * reached. It discloses nothing either: a string that is not a UUID names no resource, so
     * there is no existence to confirm and the answer cannot differ between a member and a
     * stranger. Asserted for both path variables and for an authenticated caller and an outsider,
     * because "400 before anything is resolved" is only safe if it is genuinely independent of
     * who is asking.
     */
    @Test
    void aMalformedPathIdIs400BeforeTheHandlerRuns() throws Exception {
        var ctx = newProject();
        var stranger = newProject();

        var badWorkspace = "/api/workspaces/not-a-uuid/projects/" + ctx.projectId() + "/reports/aging";
        var badProject = "/api/workspaces/" + ctx.wsId() + "/projects/not-a-uuid/reports/aging";

        for (var url : new String[]{badWorkspace, badProject}) {
            mockMvc.perform(get(url).header("Authorization", "Bearer " + ctx.token()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(url).header("Authorization", "Bearer " + stranger.token()))
                    .andExpect(status().isBadRequest());
        }
    }

    // ============================================================ helpers

    /** The project-scoped key of a created issue, as the report prints it. */
    private static String key(Ctx ctx, JsonNode issue) {
        return issue.get("key").asText();
    }

    /** The item with this key, from whichever column holds it. */
    private static JsonNode itemFor(JsonNode report, String key) {
        for (var column : report.get("columns")) {
            for (var item : column.get("items")) {
                if (item.get("key").asText().equals(key)) return item;
            }
        }
        throw new AssertionError("no item " + key + " in " + report.get("columns"));
    }

    /**
     * Move an issue into a status that exists but is not in the project's workflow — what a
     * workflow swap leaves behind. The status is <em>project-scoped</em>, so this fixture cannot
     * leak into any other suite's view of the catalog.
     */
    private void strandOutsideTheWorkflow(Ctx ctx, JsonNode issue) {
        var statusId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO statuses (id, scope_project_id, name, category, color, position, created_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, 'IN_PROGRESS', '#6B7280', 9, now())
                """, statusId.toString(), ctx.projectId().toString(), "Retired column");
        jdbcTemplate.update("UPDATE issues SET status_id = CAST(? AS uuid) WHERE id = CAST(? AS uuid)",
                statusId.toString(), issue.get("id").asText());
    }
}
