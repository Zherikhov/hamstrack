package com.hamstrack.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared scaffolding for the HD-29 / R4 suites (sprint burn-up, sprint review). Seventh link in the
 * issue epics' base chain, after {@code CycleTimeTestBase}, so the whole user / workspace / project
 * / issue bootstrap, the sprint helpers from {@code SprintTestBase} and R1's backdating come for
 * free.
 *
 * <h2>Why the ledger is backdated with SQL, exactly as the other report suites backdate issues</h2>
 * A sprint report is a statement about the past and the API can only act now: a sprint started in
 * this test started <em>this millisecond</em>, so its whole burn-up would be one point. Every
 * fixture therefore drives the real endpoints — {@code sprint_scope_events} rows are written by
 * {@code SprintScopeLedger} through the same doors production uses, never inserted by a test — and
 * only then are the timestamps moved.
 *
 * <p>{@link #startedAt} moves a sprint's start <strong>and its commitment batch together</strong>,
 * because they are the same instant by construction ({@code SprintService.start} hands one
 * timestamp to both {@code markActive} and the ledger) and a fixture that broke that would be
 * testing a state the application cannot produce.
 */
public abstract class SprintReportTestBase extends CycleTimeTestBase {

    // ============================================================ report HTTP

    protected ResultActions getBurnup(Ctx ctx, String token, String query) throws Exception {
        return mockMvc.perform(get(reportsBase(ctx) + "/sprint-burnup" + (query == null ? "" : query))
                .header("Authorization", "Bearer " + token));
    }

    /** The burn-up as the ctx owner; asserts 200 and returns the parsed body. */
    protected JsonNode burnup(Ctx ctx, String query) throws Exception {
        var body = getBurnup(ctx, ctx.token(), query)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected ResultActions getReview(Ctx ctx, String token, String query) throws Exception {
        return mockMvc.perform(get(reportsBase(ctx) + "/sprint-review" + (query == null ? "" : query))
                .header("Authorization", "Bearer " + token));
    }

    /** The sprint review as the ctx owner; asserts 200 and returns the parsed body. */
    protected JsonNode review(Ctx ctx, String query) throws Exception {
        var body = getReview(ctx, ctx.token(), query)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected ResultActions getVelocity(Ctx ctx, String token, String query) throws Exception {
        return mockMvc.perform(get(reportsBase(ctx) + "/velocity" + (query == null ? "" : query))
                .header("Authorization", "Bearer " + token));
    }

    /** The velocity report as the ctx owner; asserts 200 and returns the parsed body. */
    protected JsonNode velocity(Ctx ctx, String query) throws Exception {
        var body = getVelocity(ctx, ctx.token(), query)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    // ============================================================ fixtures

    /**
     * Move a started sprint's {@code start_at} to {@code instant}, taking its commitment batch with
     * it — the one fixture every multi-day assertion in these suites is built on.
     *
     * <p>The two updates are one fact: the commitment rows are stamped with the sprint's own
     * {@code start_at}, so moving one without the other would produce a sprint whose committed
     * scope is dated somewhere the application could never have put it.
     */
    protected void startedAt(UUID sprintId, String instant) {
        jdbcTemplate.update("""
                UPDATE sprint_scope_events
                   SET occurred_at = CAST(? AS timestamptz)
                 WHERE sprint_id = CAST(? AS uuid)
                   AND occurred_at = (SELECT start_at FROM sprints WHERE id = CAST(? AS uuid))
                """, instant, sprintId.toString(), sprintId.toString());
        int rows = jdbcTemplate.update(
                "UPDATE sprints SET start_at = CAST(? AS timestamptz) WHERE id = CAST(? AS uuid)",
                instant, sprintId.toString());
        if (rows != 1) throw new AssertionError("backdating start_at hit " + rows + " rows");
    }

    /** Move the sprint's end date, so a fixture can state a planned window without waiting for it. */
    protected void endsAt(UUID sprintId, String instant) {
        jdbcTemplate.update(
                "UPDATE sprints SET end_at = CAST(? AS timestamptz) WHERE id = CAST(? AS uuid)",
                instant, sprintId.toString());
    }

    /**
     * Move one issue's most recent ledger event for a sprint — how a fixture says "this was added
     * on day 3" after adding it through the API today.
     */
    protected void movedAt(UUID sprintId, UUID issueId, String event, String instant) {
        int rows = jdbcTemplate.update("""
                UPDATE sprint_scope_events
                   SET occurred_at = CAST(? AS timestamptz)
                 WHERE id = (SELECT id FROM sprint_scope_events
                              WHERE sprint_id = CAST(? AS uuid)
                                AND issue_id = CAST(? AS uuid)
                                AND event = ?
                              ORDER BY occurred_at DESC, id DESC
                              LIMIT 1)
                """, instant, sprintId.toString(), issueId.toString(), event);
        if (rows != 1) throw new AssertionError("no " + event + " event to move for " + issueId);
    }

    /**
     * Complete a sprint through the API and place the whole completion at {@code instant} —
     * {@code completed_at} <em>and</em> the carry-over ledger rows it wrote.
     *
     * <p>The one microsecond between them is the fixture's whole point, and it is production's
     * ordering rather than a trick: {@code SprintService.complete} stamps {@code completed_at} in
     * the conditional UPDATE that arbitrates the completion and only then writes the ledger rows
     * for the issues it moves out. The sprint review depends on exactly that — it separates "removed
     * before the end" from "carried over" by asking membership strictly before {@code completed_at},
     * where the carried-over issues are still members. A fixture that collapsed the two instants
     * would be testing a state the application cannot produce.
     *
     * <p>Every event later than {@code instant} is moved, which is precisely the completion's own:
     * everything else in these suites has already been backdated.
     */
    protected void completedAt(Ctx ctx, UUID sprintId, String instant) throws Exception {
        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isOk());
        int rows = jdbcTemplate.update(
                "UPDATE sprints SET completed_at = CAST(? AS timestamptz) WHERE id = CAST(? AS uuid)",
                instant, sprintId.toString());
        if (rows != 1) throw new AssertionError("backdating completed_at hit " + rows + " rows");
        jdbcTemplate.update("""
                UPDATE sprint_scope_events
                   SET occurred_at = CAST(? AS timestamptz) + INTERVAL '1 microsecond'
                 WHERE sprint_id = CAST(? AS uuid)
                   AND occurred_at > CAST(? AS timestamptz)
                """, instant, sprintId.toString(), instant);
    }

    /**
     * A whole finished sprint, built through the real doors and then placed in the past — the
     * fixture R5 is made of, because velocity samples nothing but COMPLETED sprints and a sprint
     * completed in this test completed <em>this millisecond</em>.
     *
     * <p>The order matters and is production's: the sprint is started (which writes the commitment
     * batch), backdated with it, some of its issues are closed a day in, and only then is it
     * completed — so the issues that were not finished are moved out by the completion itself, a
     * microsecond after {@code completed_at}, which is what makes them <em>carried over</em> rather
     * than <em>removed before end</em>. A fixture that wrote those rows itself would be testing a
     * state the application cannot produce.
     *
     * @param finished   how many of the issues are closed before the sprint completes
     * @param pointsEach story points on every issue, or {@code null} to leave them unestimated
     * @return the sprint id
     */
    protected UUID completedSprint(Ctx ctx, String name, String startAt, String completedAt,
                                   int issues, int finished, Integer pointsEach) throws Exception {
        var ids = new UUID[issues];
        var numbers = new long[issues];
        for (int i = 0; i < issues; i++) {
            var issue = pointsEach == null
                    ? createIssue(ctx, name + "-" + i)
                    : createIssue(ctx, name + "-" + i, "\"storyPoints\":" + pointsEach);
            ids[i] = idOf(issue);
            numbers[i] = numberOf(issue);
        }
        var sprint = createSprint(ctx, name);
        if (issues > 0) {
            addIssuesToSprint(ctx, ctx.token(), sprint, ids).andExpect(status().isOk());
        }
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        startedAt(sprint, startAt);
        var closedOn = OffsetDateTime.parse(startAt).plusDays(1).toString();
        for (int i = 0; i < finished; i++) {
            closedAt(ctx, numbers[i], ids[i], closedOn);
        }
        completedAt(ctx, sprint, completedAt);
        return sprint;
    }

    /** The bar names of a velocity report, in the order the server returned them. */
    protected static List<String> barNames(JsonNode report) {
        var names = new ArrayList<String>();
        for (var bar : report.get("sprints")) names.add(bar.get("name").asText());
        return names;
    }

    /** The bar for {@code name}, or an assertion failure naming what was there. */
    protected static JsonNode bar(JsonNode report, String name) {
        for (var bar : report.get("sprints")) {
            if (bar.get("name").asText().equals(name)) return bar;
        }
        throw new AssertionError("no bar named " + name + " in " + report.get("sprints"));
    }

    /** Delete an issue through the real endpoint — the ledger's door 8. */
    protected void deleteIssue(Ctx ctx, long number) throws Exception {
        mockMvc.perform(delete("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                        + "/issues/" + number)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());
    }

    // ============================================================ reading a report

    /** The series point dated {@code date}, or an assertion failure naming what was there. */
    protected static JsonNode point(JsonNode report, String date) {
        for (var p : report.get("series")) {
            if (p.get("date").asText().equals(date)) return p;
        }
        throw new AssertionError("no series point dated " + date + " in " + report.get("series"));
    }

    /** The keys of a review list, in the order the server returned them. */
    protected static List<String> listKeys(JsonNode report, String list) {
        var keys = new ArrayList<String>();
        for (var i : report.get(list).get("issues")) keys.add(i.get("key").asText());
        return keys;
    }

    /** The row of a review list carrying {@code key}, or an assertion failure. */
    protected static JsonNode listIssue(JsonNode report, String list, String key) {
        for (var i : report.get(list).get("issues")) {
            if (i.get("key").asText().equals(key)) return i;
        }
        throw new AssertionError("no row for " + key + " in " + list + ": " + report.get(list));
    }

    /** The keys named in the scope-change log, in order. */
    protected static List<String> changeKeys(JsonNode report) {
        var keys = new ArrayList<String>();
        for (var c : report.get("scopeChanges")) keys.add(c.get("key").asText());
        return keys;
    }
}
