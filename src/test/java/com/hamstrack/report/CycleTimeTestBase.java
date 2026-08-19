package com.hamstrack.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared scaffolding for the HD-138 / R3 suites (cycle &amp; lead time, aging WIP). Sixth link in
 * the issue epics' base chain, after {@code FlowReportTestBase}, so the whole user / workspace /
 * project / issue bootstrap and R1's backdating helpers come for free.
 *
 * <h2>Why the fixtures go through the API and are then backdated</h2>
 * A cycle-time report is a statement about the past and the API can only act <em>now</em>. Every
 * fixture therefore drives the real endpoints — so {@code started_at} and {@code closed_at} are
 * stamped by {@code IssueService}, exactly as they are in production — and only then moves those
 * stamps with SQL. Fixed absolute dates in a past year are used deliberately: a window expressed
 * relative to "today" would make assertions depend on the day the suite runs.
 *
 * <p>The one exception is {@link #clearStartedAt}, which writes a NULL no endpoint can produce.
 * That is the point: it reproduces R2's <em>backfill gap</em> — an issue closed before this
 * release whose start could not be recovered from {@code issue_history} because its status had
 * been renamed. Those rows exist on every upgraded install and are what the honesty rule is
 * about, so the suite must be able to make one.
 */
public abstract class CycleTimeTestBase extends FlowReportTestBase {

    // ============================================================ report HTTP

    protected ResultActions getCycleTime(Ctx ctx, String token, String query) throws Exception {
        return mockMvc.perform(get(reportsBase(ctx) + "/cycle-time" + (query == null ? "" : query))
                .header("Authorization", "Bearer " + token));
    }

    /** The cycle-time report as the ctx owner; asserts 200 and returns the parsed body. */
    protected JsonNode cycleTime(Ctx ctx, String query) throws Exception {
        var body = getCycleTime(ctx, ctx.token(), query)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected ResultActions getAging(Ctx ctx, String token) throws Exception {
        return mockMvc.perform(get(reportsBase(ctx) + "/aging")
                .header("Authorization", "Bearer " + token));
    }

    /** The aging report as the ctx owner; asserts 200 and returns the parsed body. */
    protected JsonNode aging(Ctx ctx) throws Exception {
        var body = getAging(ctx, ctx.token())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    // ============================================================ fixtures

    /**
     * An issue taken through the whole lifecycle by the API — filed, started, finished — and then
     * placed in the past. {@code started_at} and {@code closed_at} are stamped by
     * {@code IssueService} on the two status moves, so this fixture exercises the same columns
     * production writes.
     *
     * @return the created issue node (its {@code id} and {@code number} are the useful parts)
     */
    protected JsonNode completed(Ctx ctx, String title, String createdAt, String startedAt,
                                 String closedAt) throws Exception {
        var issue = createIssue(ctx, title);
        long number = issue.get("number").asLong();
        var id = UUID.fromString(issue.get("id").asText());

        patchIssue(ctx, ctx.token(), number,
                "{\"statusId\":\"" + inProgressStatusId(ctx) + "\"}").andExpect(status().isOk());
        markDone(ctx, number);

        update("UPDATE issues SET created_at = CAST(? AS timestamptz), "
               + "started_at = CAST(? AS timestamptz), closed_at = CAST(? AS timestamptz) "
               + "WHERE id = CAST(? AS uuid)", createdAt, startedAt, closedAt, id.toString());
        return issue;
    }

    /** An issue that is in progress and has been since {@code startedAt}. */
    protected JsonNode inProgressSince(Ctx ctx, String title, String createdAt, String startedAt)
            throws Exception {
        var issue = createIssue(ctx, title);
        patchIssue(ctx, ctx.token(), issue.get("number").asLong(),
                "{\"statusId\":\"" + inProgressStatusId(ctx) + "\"}").andExpect(status().isOk());
        update("UPDATE issues SET created_at = CAST(? AS timestamptz), "
               + "started_at = CAST(? AS timestamptz) WHERE id = CAST(? AS uuid)",
                createdAt, startedAt, issue.get("id").asText());
        return issue;
    }

    /** An issue nobody has picked up, filed at {@code createdAt} — {@code started_at} stays NULL. */
    protected JsonNode neverStarted(Ctx ctx, String title, String createdAt) throws Exception {
        return issueCreatedAt(ctx, title, createdAt);
    }

    /**
     * Erase an issue's {@code started_at} — R2's backfill gap, which no endpoint can produce and
     * every upgraded install has. See the class javadoc.
     */
    protected void clearStartedAt(JsonNode issue) {
        update("UPDATE issues SET started_at = NULL WHERE id = CAST(? AS uuid)",
                issue.get("id").asText());
    }

    /** The first IN_PROGRESS-category status of the ctx project's workflow. */
    protected static UUID inProgressStatusId(Ctx ctx) {
        for (var s : ctx.config().get("statuses")) {
            if (s.get("category").asText().equals("IN_PROGRESS")) {
                return UUID.fromString(s.get("id").asText());
            }
        }
        throw new AssertionError("no IN_PROGRESS-category status in the workflow");
    }

    // ============================================================ reading a report

    /** The item whose {@code key} ends in {@code -number}, or an assertion failure. */
    protected static JsonNode item(JsonNode items, long number) {
        for (var i : items) {
            if (i.get("key").asText().endsWith("-" + number)) return i;
        }
        throw new AssertionError("no item for issue #" + number + " in " + items);
    }

    /** The aging column named {@code name}, or an assertion failure naming what was there. */
    protected static JsonNode column(JsonNode report, String name) {
        for (var c : report.get("columns")) {
            if (c.get("name").asText().equals(name)) return c;
        }
        throw new AssertionError("no column named '" + name + "' in " + report.get("columns"));
    }

    /** The keys of a column's items, in the order the server returned them. */
    protected static List<String> itemKeys(JsonNode column) {
        var keys = new ArrayList<String>();
        for (var i : column.get("items")) keys.add(i.get("key").asText());
        return keys;
    }

    private void update(String sql, Object... args) {
        int rows = jdbcTemplate.update(sql, args);
        if (rows != 1) {
            throw new AssertionError("fixture update hit " + rows + " rows: " + sql);
        }
    }
}
