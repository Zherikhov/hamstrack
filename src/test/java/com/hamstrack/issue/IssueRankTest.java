package com.hamstrack.issue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-22 §3.3 / §4.9 — the backlog rank: {@code POST …/issues/{number}/rank}.
 *
 * <p>{@code issues.position} is a single project-wide order shared by the board and the
 * backlog, and it is <strong>server-written only</strong>: the client sends the
 * neighbours it dropped between, never a rank value, and {@code position} is not even
 * exposed on {@code IssueResponse}. The properties this suite pins down:
 *
 * <ul>
 *   <li>midpoints are computed from the anchors, with the missing neighbour filled in
 *       from the <em>target section</em>; a new issue appends at the bottom;</li>
 *   <li>an anchor outside the target section / from another project / equal to the moved
 *       issue is a <strong>422</strong>, stale anchors are a <strong>409</strong>
 *       ("the list changed — refresh"), and a request that asks for nothing is a 400;</li>
 *   <li>when a gap is exhausted the whole project is renumbered in one native statement
 *       that changes <strong>no {@code updated_at} and no {@code @Version}</strong> —
 *       the {@code hamstrack.skip_updated_at} GUC guard — and the GUC does not survive
 *       the transaction.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class IssueRankTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbcTemplate;

    /** {@code IssueRankService.RANK_STEP} — the spacing every placement is built on. */
    private static final long RANK_STEP = 67_108_864L;

    // ===================================================== placement

    /**
     * §3.3.1 / open question 4: filing an issue is not a priority statement, so a new
     * issue lands at the <strong>bottom</strong> — even after the list has been dragged
     * around.
     */
    @Test
    void aNewIssueLandsAtTheBottomOfTheBacklog() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "alpha");
        var b = createIssue(ctx, "bravo");
        var c = createIssue(ctx, "charlie");
        assert backlogKeys(backlogView(ctx)).equals(keys(a, b, c)) : "creation order is the initial rank";

        // Drag charlie to the very top, then file a fourth issue.
        rankBefore(ctx, numberOf(c), idOf(a)).andExpect(status().isOk());
        var d = createIssue(ctx, "delta");
        assert backlogKeys(backlogView(ctx)).equals(keys(c, a, b, d))
                : "a newly filed issue must append at the bottom, whatever the current order";
    }

    /** Every anchor combination places the issue exactly where the drop indicator said. */
    @Test
    void afterOnlyBeforeOnlyBothAndNeitherAllPlaceTheIssueCorrectly() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "a");
        var b = createIssue(ctx, "b");
        var c = createIssue(ctx, "c");
        var d = createIssue(ctx, "d");

        // after only → straight below the anchor (the server fills in the "before")
        rankAfter(ctx, numberOf(d), idOf(a)).andExpect(status().isOk());
        assert backlogKeys(backlogView(ctx)).equals(keys(a, d, b, c)) : backlogView(ctx);

        // before only → straight above the anchor
        rankBefore(ctx, numberOf(c), idOf(d)).andExpect(status().isOk());
        assert backlogKeys(backlogView(ctx)).equals(keys(a, c, d, b)) : backlogView(ctx);

        // both → exactly between them
        rankBetween(ctx, numberOf(b), idOf(a), idOf(c)).andExpect(status().isOk());
        assert backlogKeys(backlogView(ctx)).equals(keys(a, b, c, d)) : backlogView(ctx);

        // neither, plus a sprint change → appended to the (empty) sprint section
        var sprintId = createSprint(ctx, "Sprint 1");
        rank(ctx, ctx.token(), numberOf(b), "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sprint.id").value(sprintId.toString()));
        var view = backlogView(ctx);
        assert sprintSectionKeys(view, sprintId).equals(keys(b)) : view;
        assert backlogKeys(view).equals(keys(a, c, d)) : view;

        // …and back to the backlog in one request, keeping its relative place (rank is
        // preserved across sections — they share one order key).
        var cleared = json.readTree(rank(ctx, ctx.token(), numberOf(b), "{\"clearSprint\":true}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assert sprintName(cleared) == null : "clearSprint left the issue in a sprint: " + cleared;
        assert backlogKeys(backlogView(ctx)).equals(keys(a, c, d, b))
                : "clearSprint with no anchor appends to the backlog";
    }

    /** Dragging into a sprint sets {@code sprint_id} AND the rank in ONE request (§4.9). */
    @Test
    void oneRequestSetsBothTheSprintAndTheRank() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var first = createIssue(ctx, "first");
        var second = createIssue(ctx, "second");
        var third = createIssue(ctx, "third");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(first), idOf(second))
                .andExpect(status().isOk());

        // Drop `third` between the two rows already in the sprint.
        rank(ctx, ctx.token(), numberOf(third),
                "{\"sprintId\":\"" + sprintId + "\",\"afterIssueId\":\"" + idOf(first)
                        + "\",\"beforeIssueId\":\"" + idOf(second) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sprint.id").value(sprintId.toString()));

        var view = backlogView(ctx);
        assert sprintSectionKeys(view, sprintId).equals(keys(first, third, second)) : view;
        assert backlogKeys(view).isEmpty() : view;
    }

    // ===================================================== rejections

    @Test
    void badAnchorsAre422() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "a");
        var b = createIssue(ctx, "b");
        var sprintId = createSprint(ctx, "Sprint 1");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(a)).andExpect(status().isOk());

        // an anchor that is in a DIFFERENT section (the sprint, while b stays in the backlog)
        rankAfter(ctx, numberOf(b), idOf(a))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("target list")));

        // the moved issue used as its own anchor
        rankAfter(ctx, numberOf(b), idOf(b))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("against itself")));

        // an anchor from ANOTHER project — 422 "Unknown anchor issue", never a 404
        var other = newProject();
        var theirs = createIssue(other, "theirs");
        rankAfter(ctx, numberOf(b), idOf(theirs))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Unknown anchor")));

        // …and an anchor that does not exist at all
        rankAfter(ctx, numberOf(b), UUID.randomUUID())
                .andExpect(status().isUnprocessableContent());
    }

    /** {@code after.position >= before.position} means the caller's view is stale — 409, not a guess. */
    @Test
    void staleAnchorsAre409() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "a");
        var b = createIssue(ctx, "b");
        var c = createIssue(ctx, "c");

        // "put c between b and a" — but a is ABOVE b, so the anchors contradict the list.
        rank(ctx, ctx.token(), numberOf(c),
                "{\"afterIssueId\":\"" + idOf(b) + "\",\"beforeIssueId\":\"" + idOf(a) + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("list changed")));

        // Nothing moved.
        assert backlogKeys(backlogView(ctx)).equals(keys(a, b, c)) : "a rejected drag half-applied";
    }

    @Test
    void aRequestThatAsksForNothingIs400() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "a");
        var sprintId = createSprint(ctx, "Sprint 1");

        rank(ctx, ctx.token(), numberOf(a), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("anchor or a sprint change")));

        rank(ctx, ctx.token(), numberOf(a),
                "{\"sprintId\":\"" + sprintId + "\",\"clearSprint\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("not both")));
    }

    /** {@code version} is optional: sent and stale → 409; absent → the move applies (§3.3.3). */
    @Test
    void versionIsOptionalButCheckedWhenItIsSent() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "a");
        var b = createIssue(ctx, "b");
        int current = getIssue(ctx, numberOf(b)).get("version").asInt();

        rank(ctx, ctx.token(), numberOf(b),
                "{\"beforeIssueId\":\"" + idOf(a) + "\",\"version\":" + (current + 7) + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("modified by someone else")));
        assert backlogKeys(backlogView(ctx)).equals(keys(a, b)) : "the stale-version drag half-applied";

        rank(ctx, ctx.token(), numberOf(b),
                "{\"beforeIssueId\":\"" + idOf(a) + "\",\"version\":" + current + "}")
                .andExpect(status().isOk());
        assert backlogKeys(backlogView(ctx)).equals(keys(b, a)) : "the matching-version drag did not apply";

        // …and with no version at all it just applies (last drag wins).
        rankAfter(ctx, numberOf(b), idOf(a)).andExpect(status().isOk());
        assert backlogKeys(backlogView(ctx)).equals(keys(a, b));
    }

    /** A rank move writes no history row — positional churn would drown the log (§4.5). */
    @Test
    void aPureRankMoveWritesNoHistoryButASprintChangeDoes() throws Exception {
        var ctx = newProject();
        var a = createIssue(ctx, "a");
        var b = createIssue(ctx, "b");
        var sprintId = createSprint(ctx, "Sprint 1");

        rankBefore(ctx, numberOf(b), idOf(a)).andExpect(status().isOk());
        assert historyFields(ctx, numberOf(b)).isEmpty()
                : "a rank move wrote history: " + historyFields(ctx, numberOf(b));

        rank(ctx, ctx.token(), numberOf(b), "{\"sprintId\":\"" + sprintId + "\"}")
                .andExpect(status().isOk());
        assert historyFields(ctx, numberOf(b)).contains("sprint")
                : "a sprint change must be audited: " + historyFields(ctx, numberOf(b));
    }

    // ===================================================== the rebalance

    /**
     * §3.3.4 / §4.9's hardest criterion. Successive midpoints into the SAME gap exhaust
     * it after ~26 drops; the service then renumbers the whole project in one native
     * statement. That statement must:
     * <ul>
     *   <li>preserve the order exactly and produce no ties;</li>
     *   <li>bump <strong>no {@code @Version}</strong> — otherwise one person's drag
     *       would invalidate everybody else's optimistic lock on an unrelated edit;</li>
     *   <li>change <strong>no {@code updated_at}</strong> — otherwise one drag would
     *       mark every issue in the project as recently updated and poison Home /
     *       My work / {@code ORDER BY updated} (the {@code hamstrack.skip_updated_at}
     *       GUC guard).</li>
     * </ul>
     */
    @Test
    void exhaustingAGapRebalancesTheProjectWithoutTouchingUpdatedAtOrVersion() throws Exception {
        var ctx = newProject();
        var issues = new ArrayList<JsonNode>();
        for (int i = 0; i < 32; i++) issues.add(createIssue(ctx, "issue " + i));

        var top = idOf(issues.get(0));          // the fixed upper anchor
        var previous = idOf(issues.get(1));     // the lower anchor, halving the gap each round
        int rebalances = 0;

        for (int i = 2; i < issues.size(); i++) {
            var moved = issues.get(i);
            var before = snapshot(ctx.projectId());

            rankBetween(ctx, numberOf(moved), top, previous).andExpect(status().isOk());

            var after = snapshot(ctx.projectId());
            var movedOthers = new ArrayList<UUID>();
            for (var id : before.keySet()) {
                if (!id.equals(idOf(moved)) && before.get(id).position() != after.get(id).position()) {
                    movedOthers.add(id);
                }
            }
            if (!movedOthers.isEmpty()) {
                rebalances++;
                // ---- THE assertions ----
                // Every row except the one that was dragged must keep BOTH its
                // optimistic-lock version and its updated_at: the renumber is native SQL
                // that touches neither, guarded by hamstrack.skip_updated_at.
                for (var id : before.keySet()) {
                    if (id.equals(idOf(moved))) continue;
                    assert before.get(id).version() == after.get(id).version()
                            : "the rebalance bumped @Version on an untouched issue " + id;
                    assert before.get(id).updatedAt().equals(after.get(id).updatedAt())
                            : "the rebalance changed updated_at on an untouched issue " + id
                              + " — the hamstrack.skip_updated_at guard is not in force";
                }
                // The whole project is renumbered at row_number() * RANK_STEP — every row
                // except the freshly-placed one lands exactly on a step boundary, so the
                // gaps are wide open again.
                var order = orderOf(after);
                for (var id : order) {
                    if (id.equals(idOf(moved))) continue;
                    long p = after.get(id).position();
                    assert p > 0 && p % RANK_STEP == 0
                            : "issue " + id + " sits at " + p + ", not on a RANK_STEP boundary — "
                              + "the renumber is not row_number() * RANK_STEP";
                }
                // Everything the drag did NOT touch keeps its relative order.
                assert without(orderOf(before), idOf(moved)).equals(without(order, idOf(moved)))
                        : "the rebalance reordered the project";
                var positions = new java.util.HashSet<Long>();
                after.values().forEach(r -> positions.add(r.position()));
                assert positions.size() == after.size() : "the rebalance produced ties";
            }
            previous = idOf(moved);
        }

        assert rebalances == 1
                : "expected exactly one whole-project rebalance over 30 midpoints into one gap, got "
                  + rebalances;

        // The list still reads correctly end-to-end after the renumber.
        var keys = backlogKeys(backlogView(ctx));
        assert keys.size() == 32 : keys;
        assert keys.get(0).equals(issues.get(0).get("key").asText()) : "the top anchor moved: " + keys;
        assert keys.get(1).equals(issues.get(31).get("key").asText())
                : "the last-dropped issue must sit directly under the top anchor: " + keys;
    }

    /**
     * The rebalance throttle (security review M1). A whole-project renumber is
     * {@code O(issues in project)} under row locks and is reachable by any plain member —
     * ~26 cheap midpoint requests into one gap buy one full-table rewrite. So the SECOND
     * rebalance of the same project inside the 60 s cooldown is a <strong>429 with
     * {@code Retry-After}</strong>, not a second rewrite.
     *
     * <p>Three things are pinned here:
     * <ul>
     *   <li>the FIRST rebalance still happens (the throttle must not make the gap
     *       unusable — that would break dragging outright);</li>
     *   <li>the second one is refused with 429 + {@code Retry-After} and applies
     *       <strong>nothing</strong>;</li>
     *   <li>the refusal is scoped to the PROJECT: an ordinary drag in the same project
     *       still works, and a sibling project may still rebalance while this one is in
     *       its cooldown.</li>
     * </ul>
     */
    @Test
    void aSecondWholeProjectRebalanceInsideTheCooldownIs429() throws Exception {
        var ctx = newProject();
        var top = createIssue(ctx, "the fixed upper anchor");
        var a = createIssue(ctx, "mover a");
        var b = createIssue(ctx, "mover b");

        // Two issues dropped alternately into the SAME gap halve it every round, so the
        // gap is exhausted after ~26 rounds — exactly the cheap-request run the throttle
        // exists for. Ask for TWO rebalances: the first must land, the second must not.
        var run = driveMidpointsIntoOneGap(ctx, top, a, b, 2);
        assert run.rebalances() == 1
                : "expected the first whole-project rebalance to succeed and the second to be "
                  + "refused; observed " + run.rebalances() + " rebalances";
        assert run.status() == 429
                : "a second rebalance inside the cooldown must be a 429, got " + run.status()
                  + " — " + run.body();
        assert run.body().contains("re-spaced")
                : "the 429 must explain what was throttled and for how long: " + run.body();

        // Retry-After is the whole point of using RateLimitedException rather than a bare
        // ResponseStatusException: without it the SPA can only hammer.
        var retryAfter = run.retryAfter();
        assert retryAfter != null : "the 429 carried no Retry-After header";
        long seconds = Long.parseLong(retryAfter);
        assert seconds >= 1 && seconds <= 60 : "implausible Retry-After: " + retryAfter;

        // Nothing half-applied: the refused drag left every rank exactly where it was.
        assert run.before().equals(snapshot(ctx.projectId()))
                : "the throttled rebalance still rewrote ranks";

        // An ordinary drag — one that needs no rebalance — is NOT throttled. The cooldown
        // guards the whole-project rewrite, not ranking. Moving the top row to the very
        // bottom needs no midpoint at all, so it must still apply.
        var rows = backlogView(ctx).get("backlog").get("issues");
        rankAfter(ctx, rows.get(0).get("number").asLong(),
                idOf(rows.get(rows.size() - 1))).andExpect(status().isOk());

        // …and the cooldown is keyed by PROJECT: a sibling project in the same workspace
        // may still exhaust a gap and be renumbered while this one is blocked.
        var sibling = siblingProject(ctx);
        var siblingTop = createIssue(sibling, "the fixed upper anchor");
        var siblingA = createIssue(sibling, "mover a");
        var siblingB = createIssue(sibling, "mover b");
        var siblingRun = driveMidpointsIntoOneGap(sibling, siblingTop, siblingA, siblingB, 1);
        assert siblingRun.rebalances() == 1 && siblingRun.status() == 200
                : "a sibling project was blocked by another project's cooldown: "
                  + siblingRun.status() + " after " + siblingRun.rebalances() + " rebalances";
    }

    /**
     * The GUC is {@code SET LOCAL} (via {@code set_config(..., true)}): it is reverted at
     * commit and can never leak back into the pooled connection — so an ordinary edit
     * right after a rebalance still stamps {@code updated_at}.
     */
    @Test
    void theSkipUpdatedAtGucDoesNotSurviveTheTransaction() throws Exception {
        var ctx = newProject();
        var issues = new ArrayList<JsonNode>();
        for (int i = 0; i < 30; i++) issues.add(createIssue(ctx, "issue " + i));

        var top = idOf(issues.get(0));
        var previous = idOf(issues.get(1));
        for (int i = 2; i < issues.size(); i++) {
            rankBetween(ctx, numberOf(issues.get(i)), top, previous).andExpect(status().isOk());
            previous = idOf(issues.get(i));
        }

        // Nothing is left set on the connection this test now borrows from the pool.
        var leaked = jdbcTemplate.queryForObject(
                "SELECT coalesce(current_setting('hamstrack.skip_updated_at', true), '')", String.class);
        assert leaked == null || leaked.isEmpty()
                : "hamstrack.skip_updated_at leaked out of its transaction as '" + leaked + "'";

        // …and the trigger is demonstrably back on: an ordinary PATCH still bumps updated_at.
        var victim = issues.get(5);
        var before = snapshot(ctx.projectId()).get(idOf(victim));
        Thread.sleep(5);
        patchIssue(ctx, ctx.token(), numberOf(victim), "{\"title\":\"edited after a rebalance\"}")
                .andExpect(status().isOk());
        var after = snapshot(ctx.projectId()).get(idOf(victim));
        assert after.updatedAt().after(before.updatedAt())
                : "a normal edit no longer stamps updated_at — the GUC survived its transaction";
    }

    // ===================================================== helpers

    /**
     * The outcome of a run of midpoint drops into one gap: how many whole-project
     * rebalances were observed, and the response that ended the run (either the drop that
     * asked for one rebalance too many, or the one that completed the requested count).
     */
    private record GapRun(int rebalances, int status, String body, String retryAfter,
                          Map<UUID, Row> before) {}

    /**
     * Drop {@code a} and {@code b} alternately between {@code top} and each other — each
     * round halves the same gap — until either the server refuses or
     * {@code stopAfterRebalances} whole-project renumbers have been observed.
     *
     * <p>A rebalance is detected the way a user would notice it: a request that moved
     * <em>other</em> rows' positions, not just the dragged one.
     */
    private GapRun driveMidpointsIntoOneGap(Ctx ctx, JsonNode top, JsonNode a, JsonNode b,
                                            int stopAfterRebalances) throws Exception {
        int rebalances = 0;
        for (int i = 0; i < 200; i++) {
            var moved = i % 2 == 0 ? a : b;
            var lower = i % 2 == 0 ? b : a;
            var before = snapshot(ctx.projectId());

            var response = rankBetween(ctx, numberOf(moved), idOf(top), idOf(lower))
                    .andReturn().getResponse();
            if (response.getStatus() != 200) {
                return new GapRun(rebalances, response.getStatus(), response.getContentAsString(),
                        response.getHeader("Retry-After"), before);
            }

            var after = snapshot(ctx.projectId());
            boolean renumbered = before.keySet().stream()
                    .anyMatch(id -> !id.equals(idOf(moved))
                            && before.get(id).position() != after.get(id).position());
            if (renumbered && ++rebalances == stopAfterRebalances) {
                return new GapRun(rebalances, response.getStatus(), response.getContentAsString(),
                        response.getHeader("Retry-After"), before);
            }
        }
        throw new AssertionError("200 midpoint drops into one gap never exhausted it — the gap "
                + "arithmetic changed (RANK_STEP or the midpoint rule)");
    }

    /** One issue row as the DATABASE holds it — never through the JPA session under test. */
    private record Row(long position, int version, Timestamp updatedAt) {}

    private Map<UUID, Row> snapshot(UUID projectId) {
        var rows = jdbcTemplate.query(
                "SELECT id, position, version, updated_at FROM issues WHERE project_id = ? "
                        + "ORDER BY position ASC, created_at DESC",
                (rs, i) -> Map.entry((UUID) rs.getObject("id"), new Row(
                        rs.getLong("position"), rs.getInt("version"), rs.getTimestamp("updated_at"))),
                projectId);
        var out = new LinkedHashMap<UUID, Row>();
        for (var row : rows) out.put(row.getKey(), row.getValue());
        return out;
    }

    /** The project's issue ids in rank order (the snapshot is already ordered). */
    private static List<UUID> orderOf(Map<UUID, Row> snapshot) {
        return List.copyOf(snapshot.keySet());
    }

    private static List<UUID> without(List<UUID> ids, UUID excluded) {
        var out = new ArrayList<>(ids);
        out.remove(excluded);
        return out;
    }

    private List<String> historyFields(Ctx ctx, long number) throws Exception {
        var out = new ArrayList<String>();
        for (var h : issueHistory(ctx, number).get("content")) out.add(h.get("field").asText());
        return out;
    }

    private static List<String> keys(JsonNode... issues) {
        var out = new ArrayList<String>();
        for (var i : issues) out.add(i.get("key").asText());
        return out;
    }
}
