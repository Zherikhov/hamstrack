package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code issues.started_at} (HD-137 / R2, reports-proposal §5.1) — when work actually
 * began, which is the {@code x} in every cycle time the R3 report will draw.
 *
 * <p>The rule is one sentence with one deliberate exception, and both halves are asserted
 * here because both are easy to "tidy" into a wrong number later:
 *
 * <ul>
 *   <li><strong>First</strong> entry into an {@code IN_PROGRESS} <em>or</em> {@code DONE}
 *       status stamps it. DONE counts: an issue dragged straight to Done was started and
 *       finished in one move, and excluding those drops exactly the fastest work out of
 *       every percentile.</li>
 *   <li><strong>Never cleared, never re-stamped</strong> — the asymmetry with
 *       {@code closed_at}, which IS cleared on leaving DONE (see {@code IssueClosedAtTest}).
 *       "Is it closed" is a current-state question; "when did work start" is not. A
 *       re-open that moved the mark would make the cycle time of exactly the work that
 *       went badly shrink.</li>
 * </ul>
 *
 * <p>Read straight from the column rather than from a response body on purpose: R2 lands
 * <strong>dark</strong>. There is no endpoint, no DTO field and no UI for this yet — the
 * data starts accumulating a release before the report that needs it, so the first chart
 * is not drawn over an empty window.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class IssueStartedAtTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void filingIntoATodoStatusLeavesStartedAtNull() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "not picked up yet");

        assertThat(startedAt(idOf(issue)))
                .as("an unstarted issue must have no start date — createdAt is NOT a fallback")
                .isNull();
    }

    @Test
    void filingStraightIntoAnInProgressStatusStampsIt() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "filed while already being worked on",
                "\"statusId\":\"" + statusOf(ctx, "IN_PROGRESS") + "\"");

        assertThat(startedAt(idOf(issue)))
                .as("creating an issue directly in an in-progress status must stamp started_at")
                .isNotNull();
    }

    /** The "dragged straight to Done" case — started and finished in one move. */
    @Test
    void filingStraightIntoADoneStatusStampsItToo() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "already done when filed",
                "\"statusId\":\"" + doneStatusId(ctx) + "\"");

        assertThat(startedAt(idOf(issue)))
                .as("DONE implies started: excluding these would drop the fastest work from "
                  + "every cycle-time percentile")
                .isNotNull();
    }

    @Test
    void movingIntoInProgressStampsIt() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "picked up later");
        assertThat(startedAt(idOf(issue))).as("precondition: not started").isNull();

        moveTo(ctx, numberOf(issue), statusOf(ctx, "IN_PROGRESS"));

        assertThat(startedAt(idOf(issue))).as("entering an in-progress status must stamp it").isNotNull();
    }

    /** Straight from To Do to Done, never passing through an in-progress column. */
    @Test
    void movingStraightToDoneStampsIt() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "closed without ever being in progress");

        markDone(ctx, numberOf(issue));

        assertThat(startedAt(idOf(issue))).as("an issue dragged straight to Done was started, then finished").isNotNull();
    }

    /**
     * THE asymmetry with {@code closed_at}. Reopening clears the close date (that is a
     * current-state fact) and must leave the start date exactly where it was.
     */
    @Test
    void reopeningNeverClearsOrMovesTheStartDate() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "done, reopened, done again");
        moveTo(ctx, numberOf(issue), statusOf(ctx, "IN_PROGRESS"));
        var firstStart = startedAt(idOf(issue));
        assertThat(firstStart).as("precondition: started").isNotNull();

        markDone(ctx, numberOf(issue));
        moveTo(ctx, numberOf(issue), ctx.todoStatusId());
        moveTo(ctx, numberOf(issue), statusOf(ctx, "IN_PROGRESS"));

        assertThat(firstStart)
                .as("""
                started_at moved on a re-start. It must record the FIRST time work began: \
                re-stamping it makes the cycle time of exactly the work that went badly \
                shrink retroactively, and a number that improves when reality worsens is \
                how a report loses its readers.""")
                .isEqualTo(startedAt(idOf(issue)));
    }

    @Test
    void anUnrelatedUpdateNeverStampsIt() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, "renamed only");

        patchIssue(ctx, ctx.token(), numberOf(issue), "{\"title\":\"renamed\"}")
                .andExpect(status().isOk());

        assertThat(startedAt(idOf(issue))).as("only a status change into IN_PROGRESS/DONE may stamp started_at").isNull();
    }

    // ============================================================ helpers

    /** R2 is dark: the column is the only place this value exists. */
    private Object startedAt(UUID issueId) {
        return jdbc.queryForObject("SELECT started_at FROM issues WHERE id = ?",
                Object.class, issueId);
    }

    private void moveTo(Ctx ctx, long number, UUID statusId) throws Exception {
        patchIssue(ctx, ctx.token(), number, "{\"statusId\":\"" + statusId + "\"}")
                .andExpect(status().isOk());
    }

    /** The first status of the project's workflow in the given category. */
    private static UUID statusOf(Ctx ctx, String category) {
        for (var s : ctx.config().get("statuses")) {
            if (s.get("category").asText().equals(category)) {
                return UUID.fromString(s.get("id").asText());
            }
        }
        throw new AssertionError("no " + category + "-category status in the workflow");
    }
}
