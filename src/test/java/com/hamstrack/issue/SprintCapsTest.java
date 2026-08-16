package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.ArrayList;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-22 §3.7 / §4.9 — the four {@code app.agile.*} bounds, all shrunk here so the caps
 * are exercised with a handful of rows instead of hundreds.
 *
 * <p>The distinction the status codes encode: an <strong>oversized bulk payload is a
 * 400</strong> (a malformed request — the client is expected to chunk), while
 * <strong>reaching the open-sprint cap is a 422</strong> (a well-formed request that the
 * project's state rejects). The section cap is neither: it truncates and says so, the
 * HD-79 pattern — and the section's stats stay computed over the WHOLE section, so a
 * truncated section still shows honest totals.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.agile.section-max-issues=2",
        "app.agile.max-open-sprints-per-project=2",
        "app.agile.max-issues-per-bulk-move=2",
        "app.agile.default-sprint-length-days=7"
})
@AutoConfigureMockMvc
class SprintCapsTest extends SprintTestBase {

    @Test
    void theOpenSprintCapIs422AndCompletedSprintsDoNotCountAgainstIt() throws Exception {
        var ctx = newProject();
        var first = createSprint(ctx, "Sprint 1");
        createSprint(ctx, "Sprint 2");

        postSprint(ctx, ctx.token(), "{\"name\":\"Sprint 3\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("maximum of 2 open sprints")));

        // Completing one frees a slot: the cap is a concurrency bound, not a lifetime quota.
        startSprint(ctx, ctx.token(), first).andExpect(status().isOk());
        completeToBacklog(ctx, ctx.token(), first).andExpect(status().isOk());
        postSprint(ctx, ctx.token(), "{\"name\":\"Sprint 3\"}").andExpect(status().isCreated());

        // …and the cap is per PROJECT.
        var sibling = siblingProject(ctx);
        postSprint(sibling, sibling.token(), "{\"name\":\"Sprint 1\"}").andExpect(status().isCreated());
    }

    @Test
    void anOversizedBulkMoveIs400() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) ids.add(idOf(createIssue(ctx, "issue " + i)));

        addIssuesToSprint(ctx, ctx.token(), sprintId, ids.toArray(new UUID[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("At most 2 issues")));

        // Nothing from the rejected batch landed: all three issues are still in the
        // backlog section (whose stats span the whole section, cap or no cap).
        var rejected = backlogView(ctx);
        assert rejected.get("backlog").get("stats").get("issueCount").asInt() == 3
                : "a rejected bulk move moved issues out of the backlog";
        assert sprintSection(rejected, sprintId).get("stats").get("issueCount").asInt() == 0
                : "a rejected bulk move applied anyway";

        // At the cap exactly it still works, and de-duplication happens BEFORE the check.
        addIssuesToSprint(ctx, ctx.token(), sprintId,
                "{\"issueIds\":[\"" + ids.get(0) + "\",\"" + ids.get(0) + "\",\"" + ids.get(1) + "\"]}")
                .andExpect(status().isOk());
        assert sprintSection(backlogView(ctx), sprintId).get("stats").get("issueCount").asInt() == 2;

        // An empty list is a payload-level 400 (@NotEmpty), not a silent success.
        addIssuesToSprint(ctx, ctx.token(), sprintId, "{\"issueIds\":[]}")
                .andExpect(status().isBadRequest());
    }

    /**
     * The planning view advertises the bulk cap it will enforce (0.13.0 review,
     * dc-cloud-guard). {@code app.agile.max-issues-per-bulk-move} and
     * {@code app.agile.section-max-issues} are INDEPENDENT properties whose stock defaults
     * disagree (100 vs 300), so a client that chunks by the section cap — or by a
     * hardcoded constant of its own — sends a 400 the moment an operator retunes either
     * one. {@code bulkMoveCap} is how the client learns the real number for THIS install.
     */
    @Test
    void thePlanningViewAdvertisesTheBulkCapItEnforces() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) ids.add(idOf(createIssue(ctx, "issue " + i)));

        var view = backlogView(ctx);
        int cap = view.get("bulkMoveCap").asInt();
        assert cap == 2
                : "bulkMoveCap must report app.agile.max-issues-per-bulk-move (2 here), got " + cap;
        assert view.has("sectionCap")
                : "the view must keep advertising BOTH caps — they are independent knobs and a "
                  + "client that chunks by the section cap sends a 400 as soon as they differ: "
                  + view;

        // The advertised number is the one the server actually enforces: a chunk OF that
        // size is accepted, one issue more is the 400 the client is expected to avoid.
        addIssuesToSprint(ctx, ctx.token(), sprintId, ids.subList(0, cap).toArray(new UUID[0]))
                .andExpect(status().isOk());
        addIssuesToSprint(ctx, ctx.token(), sprintId, ids.toArray(new UUID[0]))
                .andExpect(status().isBadRequest());
    }

    /**
     * A truncated section reports {@code truncated}/{@code totalAvailable} and still
     * shows HONEST totals — the stats come from a grouped query that never sees the cap.
     */
    @Test
    void aTruncatedSectionStillReportsWholeSectionStats() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var committed = new ArrayList<UUID>();
        for (int i = 0; i < 4; i++) {
            committed.add(idOf(createIssue(ctx, "sprint issue " + i, "\"storyPoints\":2")));
        }
        // The bulk cap is 2 here, so the batch is chunked — exactly what the 400 asks for.
        addIssuesToSprint(ctx, ctx.token(), sprintId, committed.get(0), committed.get(1))
                .andExpect(status().isOk());
        addIssuesToSprint(ctx, ctx.token(), sprintId, committed.get(2), committed.get(3))
                .andExpect(status().isOk());
        for (int i = 0; i < 5; i++) createIssue(ctx, "backlog issue " + i);

        var view = backlogView(ctx);
        assert view.get("sectionCap").asInt() == 2 : view.get("sectionCap");

        var section = sprintSection(view, sprintId);
        assert section.get("issues").size() == 2 : "the section was not capped: " + section;
        assert section.get("truncated").asBoolean() : "truncation was not reported: " + section;
        assert section.get("totalAvailable").asInt() == 4 : section.get("totalAvailable");
        assert section.get("stats").get("issueCount").asInt() == 4
                : "stats were computed over the truncated page, not the whole section";
        assert section.get("stats").get("points").asDouble() == 8.0
                : "point sums must span the whole section: " + section.get("stats");

        var backlog = view.get("backlog");
        assert backlog.get("issues").size() == 2 : backlog;
        assert backlog.get("truncated").asBoolean() : backlog;
        assert backlog.get("totalAvailable").asInt() == 5 : backlog.get("totalAvailable");
        assert backlog.get("stats").get("unestimatedCount").asInt() == 5
                : "the backlog's unestimated count must span the whole section";
    }

    /** The default sprint length is an operator setting, not a constant. */
    @Test
    void theDefaultSprintLengthComesFromTheProperty() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");

        int days = sprintNode(ctx, sprintId).get("daysRemaining").asInt();
        assert days == 6 || days == 7
                : "endAt must default to startAt + app.agile.default-sprint-length-days (7), got " + days;
    }
}
