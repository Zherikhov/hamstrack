package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-22 §4.5 / §4.9 — completing a sprint: what moves, what stays, and what the report
 * says.
 *
 * <p>The invariants under test:
 * <ul>
 *   <li>every issue whose status category is NOT DONE moves to the chosen destination
 *       (the backlog, or a FUTURE sprint of the same project); <strong>DONE issues keep
 *       their {@code sprint_id}</strong> — that is the sprint's record of what it
 *       delivered;</li>
 *   <li>the reported counts and point sums describe the sprint <em>as it closed</em>,
 *       and the read-only {@code completion-preview} reports exactly the same numbers
 *       (no drift between the dialog and the result);</li>
 *   <li>the rank is never rewritten, so carried-over items keep their relative order;</li>
 *   <li>an invalid carry-over target is a 422, and a double submit is a 409.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintCompletionTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * The whole report in one pass: 4 issues, 10 points, 2 of them (8 points) done.
     * Everything else moves to the backlog and the DONE pair stays behind.
     */
    @Test
    void doneIssuesStayAndTheReportMatchesTheSprintAsItClosed() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");

        var a = createIssue(ctx, "done three", "\"storyPoints\":3");
        var b = createIssue(ctx, "done five", "\"storyPoints\":5");
        var c = createIssue(ctx, "unfinished two", "\"storyPoints\":2");
        var d = createIssue(ctx, "unfinished unestimated");
        addIssuesToSprint(ctx, ctx.token(), sprintId,
                idOf(a), idOf(b), idOf(c), idOf(d)).andExpect(status().isOk());
        markDone(ctx, numberOf(a));
        markDone(ctx, numberOf(b));

        // ---- the dialog's numbers ----
        var preview = json.readTree(completionPreview(ctx, ctx.token(), sprintId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(preview.get("totalIssueCount").asInt()).as("%s", preview).isEqualTo(4);
        assertThat(preview.get("doneIssueCount").asInt()).as("%s", preview).isEqualTo(2);
        assertThat(preview.get("unfinishedIssueCount").asInt()).as("%s", preview).isEqualTo(2);
        assertThat(preview.get("totalPoints").asDouble()).as("%s", preview).isEqualTo(10.0);
        assertThat(preview.get("donePoints").asDouble()).as("%s", preview).isEqualTo(8.0);
        assertThat(preview.get("unfinishedPoints").asDouble()).as("%s", preview).isEqualTo(2.0);
        assertThat(preview.get("targetCandidates")).as("no FUTURE sprint exists yet: " + preview).isEmpty();

        // ---- and the result reports exactly the same ----
        var result = json.readTree(completeToBacklog(ctx, ctx.token(), sprintId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(result.get("completedIssueCount").asInt()).as("%s", result).isEqualTo(2);
        assertThat(result.get("carriedOverIssueCount").asInt()).as("%s", result).isEqualTo(2);
        assertThat(result.get("donePoints").asDouble()).as("%s", result).isEqualTo(8.0);
        assertThat(result.get("carriedOverPoints").asDouble()).as("%s", result).isEqualTo(2.0);
        assertThat(result.get("carriedOverToSprintId").isNull())
                .withFailMessage("BACKLOG carries over to nothing: " + result)
                .isTrue();
        assertThat(result.get("sprint").get("state").asText()).as("%s", result).isEqualTo("COMPLETED");

        // ---- what actually moved ----
        assertThat(sprintId.toString())
                .as("a DONE issue lost its sprint — that is the sprint's delivery record")
                .isEqualTo(sprintId(getIssue(ctx, numberOf(a))));
        assertThat(sprintId.toString())
                .as("the second DONE issue kept its sprint too, so the record is the sprint's and not one row's")
                .isEqualTo(sprintId(getIssue(ctx, numberOf(b))));
        assertThat(sprintName(getIssue(ctx, numberOf(c)))).as("an unfinished issue kept its sprint").isNull();
        assertThat(sprintName(getIssue(ctx, numberOf(d))))
                .as("the second unfinished issue was carried out of the sprint as well")
                .isNull();

        // The completed sprint still counts its delivery (2 issues / 8 points).
        var closed = sprintNode(ctx, sprintId);
        assertThat(closed.get("issueCount").asInt()).as("%s", closed).isEqualTo(2);
        assertThat(closed.get("doneIssueCount").asInt()).as("%s", closed).isEqualTo(2);
        assertThat(closed.get("points").asDouble()).as("%s", closed).isEqualTo(8.0);

        // A double submit is a 409, never a second (destructive) run.
        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isConflict());
    }

    /**
     * The other disposition: carry the unfinished work over to a named FUTURE sprint.
     * The rank is preserved — the carried-over items keep their relative order.
     */
    @Test
    void unfinishedWorkCanBeCarriedOverToAFutureSprintWithItsRankIntact() throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Sprint 1");
        var next = createSprint(ctx, "Sprint 2");

        var first = createIssue(ctx, "first", "\"storyPoints\":1");
        var second = createIssue(ctx, "second", "\"storyPoints\":2");
        var third = createIssue(ctx, "third", "\"storyPoints\":4");
        addIssuesToSprint(ctx, ctx.token(), running, idOf(first), idOf(second), idOf(third))
                .andExpect(status().isOk());
        markDone(ctx, numberOf(second));

        // The order the unfinished pair sits in BEFORE the completion.
        String doneKey = getIssue(ctx, numberOf(second)).get("key").asText();
        var before = new ArrayList<String>();
        for (var key : sprintSectionKeys(backlogView(ctx), running)) {
            if (!key.equals(doneKey)) before.add(key);
        }

        var preview = json.readTree(completionPreview(ctx, ctx.token(), running)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var candidates = preview.get("targetCandidates");
        assertThat(candidates).as("the only FUTURE sprint must be offered as a target: " + preview).hasSize(1);
        assertThat(candidates.get(0).get("id").asText())
                .as("the only FUTURE sprint must be offered as a target: " + preview)
                .isEqualTo(next.toString());

        var result = json.readTree(completeSprint(ctx, ctx.token(), running,
                "{\"moveUnfinishedTo\":\"SPRINT\",\"targetSprintId\":\"" + next + "\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(result.get("carriedOverIssueCount").asInt()).as("%s", result).isEqualTo(2);
        assertThat(result.get("carriedOverPoints").asDouble()).as("%s", result).isEqualTo(5.0);
        assertThat(next.toString()).as("%s", result).isEqualTo(result.get("carriedOverToSprintId").asText());

        assertThat(next.toString()).as("not carried over").isEqualTo(sprintId(getIssue(ctx, numberOf(first))));
        assertThat(next.toString()).as("not carried over").isEqualTo(sprintId(getIssue(ctx, numberOf(third))));
        assertThat(running.toString())
                .as("the DONE issue must stay in the sprint it was delivered in")
                .isEqualTo(sprintId(getIssue(ctx, numberOf(second))));

        // Rank preserved: same relative order, now inside the target section.
        var after = sprintSectionKeys(backlogView(ctx), next);
        assertThat(after).as("carry-over rewrote the rank: " + before + " → " + after).isEqualTo(before);
    }

    /** Carrying over to the backlog also preserves the rank (§4.5). */
    @Test
    void carryingOverToTheBacklogPreservesTheRelativeOrder() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");
        var a = createIssue(ctx, "alpha");
        var b = createIssue(ctx, "bravo");
        var c = createIssue(ctx, "charlie");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(a), idOf(b), idOf(c))
                .andExpect(status().isOk());
        var before = sprintSectionKeys(backlogView(ctx), sprintId);

        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isOk());

        assertThat(backlogKeys(backlogView(ctx)))
                .as("the backlog order after a completion must match the sprint order")
                .isEqualTo(before);
    }

    /**
     * Every invalid carry-over target is a <strong>422</strong> — an invalid field value
     * in a request the caller is entitled to make — and none of them half-applies.
     */
    @Test
    void anInvalidCarryOverTargetIs422() throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Sprint 1");
        var other = newProject();
        var foreign = createSprint(other, "Their future sprint");

        // SPRINT without a target
        completeSprint(ctx, ctx.token(), running, "{\"moveUnfinishedTo\":\"SPRINT\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("future sprint")));

        // …a sprint of ANOTHER TENANT (422 "Unknown sprint", never a 404 that confirms it exists)
        completeSprint(ctx, ctx.token(), running,
                "{\"moveUnfinishedTo\":\"SPRINT\",\"targetSprintId\":\"" + foreign + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown sprint"));

        // …a sprint of a SIBLING PROJECT in the same workspace
        var sibling = siblingProject(ctx);
        var siblingSprint = createSprint(sibling, "Sibling sprint");
        completeSprint(ctx, ctx.token(), running,
                "{\"moveUnfinishedTo\":\"SPRINT\",\"targetSprintId\":\"" + siblingSprint + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown sprint"));

        // …an unknown id
        completeSprint(ctx, ctx.token(), running,
                "{\"moveUnfinishedTo\":\"SPRINT\",\"targetSprintId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent());

        // …and itself
        completeSprint(ctx, ctx.token(), running,
                "{\"moveUnfinishedTo\":\"SPRINT\",\"targetSprintId\":\"" + running + "\"}")
                .andExpect(status().isUnprocessableContent());

        assertThat(sprintNode(ctx, running).get("state").asText())
                .as("a rejected completion must not half-apply")
                .isEqualTo("ACTIVE");
    }

    /** A COMPLETED sprint cannot receive carried-over work either (§4.5). */
    @Test
    void aCompletedSprintIsNotAValidCarryOverTarget() throws Exception {
        var ctx = newProject();
        var first = startedSprint(ctx, "Sprint 1");
        completeToBacklog(ctx, ctx.token(), first).andExpect(status().isOk());
        var second = startedSprint(ctx, "Sprint 2");

        completeSprint(ctx, ctx.token(), second,
                "{\"moveUnfinishedTo\":\"SPRINT\",\"targetSprintId\":\"" + first + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("future sprint")));
    }

    /** Completing a sprint that is not ACTIVE is a 409, whatever the disposition. */
    @Test
    void completingAFutureSprintIs409() throws Exception {
        var ctx = newProject();
        var planned = createSprint(ctx, "Not started");
        completeToBacklog(ctx, ctx.token(), planned).andExpect(status().isConflict());
    }

    /**
     * The preview is a READ: any project member may open the dialog, and it must not
     * move anything. (The completion itself is curator-only — see {@code SprintAuthzTest}.)
     */
    @Test
    void thePreviewIsReadableByAPlainMemberAndChangesNothing() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "work", "\"storyPoints\":8");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(issue)).andExpect(status().isOk());
        var member = addMember(ctx, "MEMBER");

        var preview = json.readTree(completionPreview(ctx, member.token(), sprintId)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(preview.get("totalIssueCount").asInt()).as("%s", preview).isEqualTo(1);
        assertThat(preview.get("unfinishedPoints").asDouble()).as("%s", preview).isEqualTo(8.0);

        assertThat(sprintNode(ctx, sprintId).get("state").asText())
                .as("a preview must not move the lifecycle")
                .isEqualTo("ACTIVE");
        assertThat(sprintId.toString())
                .as("a preview must not move issues")
                .isEqualTo(sprintId(getIssue(ctx, numberOf(issue))));
    }

    /** The DB row agrees with the API: the completion really rewrote {@code sprint_id}. */
    @Test
    void theRowsAgreeWithTheReport() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");
        var kept = createIssue(ctx, "delivered");
        var moved = createIssue(ctx, "carried over");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(kept), idOf(moved))
                .andExpect(status().isOk());
        markDone(ctx, numberOf(kept));
        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isOk());

        assertThat(sprintIdOfRow(idOf(kept))).as("the DONE issue lost sprint_id in the DB").isNotNull();
        assertThat(sprintIdOfRow(idOf(moved))).as("the unfinished issue kept sprint_id in the DB").isNull();
    }

    /** The sprint id as the DATABASE holds it — never through the JPA session under test. */
    private Object sprintIdOfRow(UUID issueId) {
        var rows = jdbcTemplate.query("SELECT sprint_id FROM issues WHERE id = ?",
                (rs, i) -> rs.getObject("sprint_id"), issueId);
        assertThat(rows).as("issue row vanished: " + issueId).hasSize(1);
        return rows.get(0);
    }
}
