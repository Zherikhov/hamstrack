package com.hamstrack.issue;

import com.hamstrack.common.sse.SseRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two BULK membership moves — a sprint completion's carry-over and a force-delete's
 * detach — write the same {@code sprint} history row the single-issue paths do, and
 * publish {@code IssueUpdated} only while the fan-out stays bounded (0.13.0 review,
 * security-officer L2 / §4.8).
 *
 * <p>Why it matters: both statements are bulk JPQL UPDATEs that can touch hundreds of
 * issues, and before the fix they were the only membership changes in the feature that
 * left <em>no trace at all</em> — after a force-delete nothing anywhere recorded that
 * those issues had ever been in that sprint. The rows also have to be written in the one
 * order that works: {@code clearSprint} carries {@code clearAutomatically}, so history
 * queued before it would be evicted as a pending insert (the documented "workspace
 * vanishes" trap), and the history rows must be built from {@code getReferenceById}
 * proxies rather than loaded entities, or the bulk UPDATE would leave stale managed
 * copies behind.
 *
 * <p>{@link SseRegistry} is a Mockito bean so the event fan-out can be counted exactly —
 * the same technique as {@code SseEventListenerTransactionalTest}. That is also why this
 * lives in its own class: the mock changes the context, and the rest of the sprint suites
 * must keep running against the real registry.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintBulkDetachAuditTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbcTemplate;

    /** Stands in for the real registry so every ISSUE_UPDATED broadcast can be counted. */
    @MockitoBean SseRegistry sseRegistry;

    /** {@code SprintService.COMPLETE_EVENT_FANOUT_THRESHOLD} — the §4.8 bound. */
    private static final int FANOUT_THRESHOLD = 50;

    // ==================================================== the completion's carry-over

    @Test
    void completingASprintAuditsEveryCarriedOverIssueAndLeavesTheDeliveredOnesAlone()
            throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Sprint 1");
        var next = createSprint(ctx, "Sprint 2");
        var delivered = createIssue(ctx, "delivered");
        var carried = createIssue(ctx, "carried over");
        var alsoCarried = createIssue(ctx, "carried over too");
        addIssuesToSprint(ctx, ctx.token(), running, idOf(delivered), idOf(carried),
                idOf(alsoCarried)).andExpect(status().isOk());
        markDone(ctx, numberOf(delivered));
        reset(sseRegistry);

        completeSprint(ctx, ctx.token(), running,
                "{\"moveUnfinishedTo\":\"SPRINT\",\"targetSprintId\":\"" + next + "\"}")
                .andExpect(status().isOk());

        // One row per issue that actually MOVED, naming both ends of the move.
        for (var issue : List.of(carried, alsoCarried)) {
            var moves = sprintHistory(ctx, numberOf(issue));
            assert moves.size() == 2
                    : "expected the bulk carry-over to be audited like a single-issue move: " + moves;
            assert moves.get(1).equals("Sprint 1 → Sprint 2")
                    : "the carry-over row must name where the issue came from and went: " + moves;
        }
        // The DONE issue did not move, so nothing was written about it.
        assert sprintHistory(ctx, numberOf(delivered)).equals(List.of("null → Sprint 1"))
                : "a delivered issue was audited as if it had been moved: "
                  + sprintHistory(ctx, numberOf(delivered));

        // Under the fan-out bound every moved issue gets its event, so open boards refresh.
        verify(sseRegistry, times(2)).broadcast(any(), eq("ISSUE_UPDATED"), any());
    }

    /** Completing to the BACKLOG audits the same way, with no destination. */
    @Test
    void completingToTheBacklogAuditsTheMoveOutOfTheSprint() throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Sprint 1");
        var carried = createIssue(ctx, "carried over");
        addIssuesToSprint(ctx, ctx.token(), running, idOf(carried)).andExpect(status().isOk());

        completeToBacklog(ctx, ctx.token(), running).andExpect(status().isOk());

        assert sprintHistory(ctx, numberOf(carried)).equals(
                List.of("null → Sprint 1", "Sprint 1 → null"))
                : "the move back to the backlog was not audited: "
                  + sprintHistory(ctx, numberOf(carried));
    }

    // ==================================================== the force-delete's detach

    @Test
    void forceDeletingASprintAuditsEveryDetachedIssueAndEmitsOneEventEach() throws Exception {
        var ctx = newProject();
        var doomed = createSprint(ctx, "Sprint 1");
        var committed = new ArrayList<UUID>();
        var numbers = new ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            var issue = createIssue(ctx, "committed " + i);
            committed.add(idOf(issue));
            numbers.add(numberOf(issue));
        }
        addIssuesToSprint(ctx, ctx.token(), doomed, committed.toArray(new UUID[0]))
                .andExpect(status().isOk());
        reset(sseRegistry);

        deleteSprint(ctx, ctx.token(), doomed, true).andExpect(status().isNoContent());

        // The sprint row is gone, so this history IS the only record that these issues
        // were ever in it — written from the membership read BEFORE the detach.
        for (var number : numbers) {
            assert sprintHistory(ctx, number).equals(List.of("null → Sprint 1", "Sprint 1 → null"))
                    : "a force-detached issue kept no record of the sprint it was in: "
                      + sprintHistory(ctx, number);
        }
        verify(sseRegistry, times(3)).broadcast(any(), eq("ISSUE_UPDATED"), any());
    }

    /**
     * Above the fan-out bound the audit still covers EVERY issue — it is the record, not
     * a nicety — while the events stop: one request must stay bounded, and the clients
     * that care refetch on their next poll/navigation.
     */
    @Test
    void aForceDeleteAboveTheFanOutBoundStillAuditsEveryIssueButPublishesNoEvents()
            throws Exception {
        var ctx = newProject();
        var doomed = createSprint(ctx, "Big sprint");
        var committed = new ArrayList<UUID>();
        for (int i = 0; i <= FANOUT_THRESHOLD; i++) {   // 51 — one past the bound
            committed.add(idOf(createIssue(ctx, "committed " + i)));
        }
        addIssuesToSprint(ctx, ctx.token(), doomed, committed.toArray(new UUID[0]))
                .andExpect(status().isOk());
        reset(sseRegistry);

        deleteSprint(ctx, ctx.token(), doomed, true).andExpect(status().isNoContent());

        // Counted in the DB rather than over 51 HTTP reads: what matters is that the
        // batched insert covered the whole detach, not how any single row renders.
        var audited = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM issue_history h
                  JOIN issues i ON i.id = h.issue_id
                 WHERE i.project_id = ? AND h.field = 'sprint'
                   AND h.old_value = 'Big sprint' AND h.new_value IS NULL
                """, Integer.class, ctx.projectId());
        assert audited != null && audited == committed.size()
                : "the bulk detach audited " + audited + " of " + committed.size() + " issues";

        verify(sseRegistry, never()).broadcast(any(), eq("ISSUE_UPDATED"), any());
    }

    // ==================================================== helpers

    /** This issue's {@code sprint} history as {@code "old → new"}, oldest first. */
    private List<String> sprintHistory(Ctx ctx, long number) throws Exception {
        var out = new ArrayList<String>();
        for (var row : issueHistory(ctx, number).get("content")) {
            if (!"sprint".equals(row.get("field").asText())) continue;
            out.add(text(row.get("oldValue")) + " → " + text(row.get("newValue")));
        }
        return out;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null || node.isNull() ? "null" : node.asText();
    }
}
