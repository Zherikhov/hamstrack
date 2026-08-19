package com.hamstrack.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.SprintTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared scaffolding for the HD-28 / R1 (Flow report) suites. Fifth link in the issue
 * epics' base chain — {@code LabelTestBase} → {@code ComponentTestBase} →
 * {@code VersionTestBase} → {@code SprintTestBase} → here — so the whole user /
 * workspace / project / issue bootstrap, {@code siblingProject}, {@code actorWith} and
 * {@code markDone} come for free and only the report's own surface lives here.
 *
 * <p><strong>Why the timestamps are written with raw SQL.</strong> A flow report is a
 * statement about the past, and the API can only create issues <em>now</em>. Every
 * fixture therefore creates the issue through the real endpoints — so the report is
 * always reading rows the application itself wrote, {@code closed_at} included — and
 * then <em>backdates</em> the two columns the report reads. Fixed absolute dates in a
 * long-past year are used deliberately: a window expressed relative to "today" would
 * make the assertions depend on the day the suite runs, and a weekly-bucket assertion
 * would depend on the day of the week.
 */
public abstract class FlowReportTestBase extends SprintTestBase {

    @Autowired protected JdbcTemplate jdbcTemplate;

    // ============================================================ report HTTP

    protected String reportsBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/reports";
    }

    protected ResultActions getFlow(Ctx ctx, String token, String query) throws Exception {
        return mockMvc.perform(get(reportsBase(ctx) + "/flow" + (query == null ? "" : query))
                .header("Authorization", "Bearer " + token));
    }

    /** The flow report as the ctx owner; asserts 200 and returns the parsed body. */
    protected JsonNode flow(Ctx ctx, String query) throws Exception {
        var body = getFlow(ctx, ctx.token(), query)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** The bucket dated {@code date}, or an assertion failure naming what was there. */
    protected static JsonNode bucket(JsonNode report, String date) {
        for (var b : report.get("buckets")) {
            if (b.get("date").asText().equals(date)) return b;
        }
        throw new AssertionError("no bucket dated " + date + " in " + report.get("buckets"));
    }


    /**
     * The id of one of the project's offered issue types. {@code Ctx.typeId} itself is
     * package-private to {@code com.hamstrack.issue}; the config node it reads is not.
     */
    protected static UUID typeId(Ctx ctx, String name) {
        for (var t : ctx.config().get("issueTypes")) {
            if (t.get("name").asText().equals(name)) return UUID.fromString(t.get("id").asText());
        }
        throw new AssertionError("type not offered: " + name);
    }

    // ============================================================ backdating

    /**
     * Move an issue's {@code created_at} to an instant in the past. Written straight to
     * the column the report reads; nothing else about the issue changes.
     */
    protected void createdAt(UUID issueId, String instant) {
        int rows = jdbcTemplate.update(
                "UPDATE issues SET created_at = CAST(? AS timestamptz) WHERE id = CAST(? AS uuid)",
                instant, issueId.toString());
        if (rows != 1) throw new AssertionError("backdating created_at hit " + rows + " rows");
    }

    /**
     * Close an issue <em>through the API</em> (so {@code closed_at} is stamped by
     * {@code IssueService}, not by the test) and then move that stamp to {@code instant}.
     */
    protected void closedAt(Ctx ctx, long number, UUID issueId, String instant) throws Exception {
        markDone(ctx, number);
        int rows = jdbcTemplate.update(
                "UPDATE issues SET closed_at = CAST(? AS timestamptz) WHERE id = CAST(? AS uuid)",
                instant, issueId.toString());
        if (rows != 1) throw new AssertionError("backdating closed_at hit " + rows + " rows");
    }

    /** Create an issue and place its {@code created_at} at {@code instant}. */
    protected JsonNode issueCreatedAt(Ctx ctx, String title, String instant) throws Exception {
        var issue = createIssue(ctx, title);
        createdAt(UUID.fromString(issue.get("id").asText()), instant);
        return issue;
    }
}
