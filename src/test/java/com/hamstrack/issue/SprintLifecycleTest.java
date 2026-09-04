package com.hamstrack.issue;

import com.hamstrack.issue.repository.SprintRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-22 §4.1 / §4.9 — the sprint state machine: FUTURE → ACTIVE → COMPLETED, one-way,
 * with <strong>at most one ACTIVE sprint per project</strong>.
 *
 * <p>The two properties that matter most here and are easy to regress:
 * <ul>
 *   <li>both transitions are conditional bulk UPDATEs checked on their affected-row
 *       count, so a <strong>double submit is a 409</strong> — never an
 *       idempotent-looking success. Completion moves issues; running that twice
 *       silently is exactly the failure that must not be hidden;</li>
 *   <li>the one-active invariant is enforced <strong>by the database</strong>
 *       ({@code sprints_one_active_per_project_uk}), not only by Java — verified here
 *       with raw SQL that bypasses the service entirely.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintLifecycleTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SprintRepository sprintRepository;
    @Autowired TransactionTemplate txTemplate;

    // ===================================================== the happy path, once each

    @Test
    void futureThenActiveThenCompletedAndEveryStepIsOneWay() throws Exception {
        var ctx = newProject();

        // ---- created FUTURE, named from its sequence when the payload omits a name ----
        var sprintId = createSprint(ctx, ctx.token(), "{}");
        var created = sprintNode(ctx, sprintId);
        assertThat(created.get("state").asText()).as("%s", created).isEqualTo("FUTURE");
        assertThat(created.get("sequence").asInt()).as("%s", created).isEqualTo(1);
        assertThat(created.get("name").asText()).as("%s", created).isEqualTo("Sprint 1");
        assertThat(created.get("startAt").isNull()).withFailMessage("%s", created).isTrue();
        assertThat(created.get("endAt").isNull()).withFailMessage("%s", created).isTrue();
        assertThat(created.get("completedAt").isNull()).withFailMessage("%s", created).isTrue();
        assertThat(created.get("daysRemaining").isNull()).withFailMessage("a FUTURE sprint counts nothing down").isTrue();

        // ---- start with no body: now + the default length (14d) ----
        startSprint(ctx, ctx.token(), sprintId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.startAt").isNotEmpty())
                .andExpect(jsonPath("$.endAt").isNotEmpty());
        var active = sprintNode(ctx, sprintId);
        int days = active.get("daysRemaining").asInt();
        assertThat(days == 13 || days == 14)
                .withFailMessage("endAt must default to startAt + app.agile.default-sprint-length-days; got " + days)
                .isTrue();

        // ---- a double submit is a 409, not a silent success ----
        startSprint(ctx, ctx.token(), sprintId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("future sprint")));

        // ---- complete, then complete again ----
        completeToBacklog(ctx, ctx.token(), sprintId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sprint.state").value("COMPLETED"))
                .andExpect(jsonPath("$.sprint.completedAt").isNotEmpty());
        completeToBacklog(ctx, ctx.token(), sprintId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("active sprint")));

        // ---- and it can never go back ----
        startSprint(ctx, ctx.token(), sprintId).andExpect(status().isConflict());
        var completed = sprintNode(ctx, sprintId);
        assertThat(completed.get("state").asText()).as("%s", completed).isEqualTo("COMPLETED");
        assertThat(completed.get("daysRemaining").isNull())
                .withFailMessage("a COMPLETED sprint counts nothing down")
                .isTrue();
    }

    /**
     * Re-open is not merely "no endpoint": a PATCH must not be able to smuggle the state
     * back either. Whatever the request body claims, {@code state}/{@code completedAt}
     * are {@code updatable = false} on the entity and the response's state must not move.
     */
    @Test
    void aPatchCannotReopenACompletedSprint() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint A");
        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isOk());

        // Deliberately NOT asserting the status code: an unknown property may be either
        // ignored or rejected. What is non-negotiable is the state afterwards.
        patchSprint(ctx, ctx.token(), sprintId,
                "{\"name\":\"Renamed\",\"state\":\"FUTURE\",\"completedAt\":null}");

        var after = sprintNode(ctx, sprintId);
        assertThat(after.get("state").asText()).as("a PATCH re-opened a completed sprint: " + after).isEqualTo("COMPLETED");
        assertThat(after.get("completedAt").isNull())
                .withFailMessage("completedAt was cleared by a PATCH: " + after)
                .isFalse();
    }

    // ===================================================== the one-active invariant

    @Test
    void onlyOneSprintPerProjectMayBeActive() throws Exception {
        var ctx = newProject();
        var first = createSprint(ctx, "Sprint one");
        var second = createSprint(ctx, "Sprint two");

        startSprint(ctx, ctx.token(), first).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already active")));

        // The loser stayed FUTURE — the failed start must not have half-applied.
        assertThat(sprintNode(ctx, second).get("state").asText()).as("half-applied start").isEqualTo("FUTURE");

        // A sibling project has its own active slot: the invariant is per PROJECT.
        var sibling = siblingProject(ctx);
        startSprint(sibling, sibling.token(), createSprint(sibling, "Sprint one"))
                .andExpect(status().isOk());

        // …and once the first sprint is completed, the second may start.
        completeToBacklog(ctx, ctx.token(), first).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), second).andExpect(status().isOk());
    }

    /**
     * The invariant is in the DATABASE, not only in the service: a hand-written INSERT
     * that bypasses {@code SprintService} entirely must still be rejected by
     * {@code sprints_one_active_per_project_uk} (§4.9's "verify with raw SQL").
     */
    @Test
    void theDatabaseItselfRejectsASecondActiveRow() throws Exception {
        var ctx = newProject();
        startedSprint(ctx, "Sprint one");

        try {
            jdbcTemplate.update("""
                    INSERT INTO sprints (id, workspace_id, project_id, name, state, sequence,
                                         start_at, created_at, updated_at)
                    VALUES (?, ?, ?, 'Raw active', 'ACTIVE', 99, NOW(), NOW(), NOW())
                    """, UUID.randomUUID(), ctx.wsId(), ctx.projectId());
            throw new AssertionError(
                    "the DB accepted a second ACTIVE sprint — sprints_one_active_per_project_uk is gone");
        } catch (DataIntegrityViolationException expected) {
            assertThat(String.valueOf(expected.getMessage()))
                    .as(() -> "rejected, but not by the partial unique index: " + expected.getMessage())
                    .contains("sprints_one_active_per_project_uk");
        }
    }

    /**
     * The composite FK {@code (sprint_id, workspace_id) → sprints (id, workspace_id)}
     * makes a cross-tenant assignment <em>unrepresentable</em>, not merely rejected in
     * Java (§3.1): even raw SQL cannot put one tenant's issue into another tenant's
     * sprint.
     */
    @Test
    void aHandCraftedCrossTenantSprintAssignmentIsRejectedByTheFk() throws Exception {
        var ctx = newProject();
        var other = newProject();
        var foreignSprint = createSprint(other, "Their sprint");
        var issue = createIssue(ctx, "ours");

        try {
            jdbcTemplate.update("UPDATE issues SET sprint_id = ? WHERE id = ?",
                    foreignSprint, idOf(issue));
            throw new AssertionError("the DB accepted a cross-tenant sprint_id — issues_sprint_fk is gone");
        } catch (DataIntegrityViolationException expected) {
            assertThat(String.valueOf(expected.getMessage()))
                    .as(() -> "rejected, but not by the composite FK: " + expected.getMessage())
                    .contains("issues_sprint_fk");
        }
    }

    // ===================================================== delete

    @Test
    void deleteRefusesAnActiveSprintAndAPopulatedOneWithoutForce() throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Running");
        deleteSprint(ctx, ctx.token(), running, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("active")));
        completeToBacklog(ctx, ctx.token(), running).andExpect(status().isOk());

        var planned = createSprint(ctx, "Planned");
        var issue = createIssue(ctx, "committed work");
        addIssuesToSprint(ctx, ctx.token(), planned, idOf(issue)).andExpect(status().isOk());

        deleteSprint(ctx, ctx.token(), planned, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("still holds")));

        deleteSprint(ctx, ctx.token(), planned, true).andExpect(status().isNoContent());
        getSprint(ctx, ctx.token(), planned).andExpect(status().isNotFound());

        // The issue survives, detached — in the API AND in the row (the ON DELETE SET
        // NULL trap: a stale managed copy flushed later must not write the old id back).
        assertThat(sprintName(getIssue(ctx, numberOf(issue)))).as("sprint not cleared in the API").isNull();
        var raw = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM issues WHERE id = ? AND sprint_id IS NULL",
                Integer.class, idOf(issue));
        assertThat(raw).as("sprint_id not cleared in the DB").isNotNull();
        assertThat(raw).as("sprint_id not cleared in the DB").isEqualTo(1);

        // An empty COMPLETED sprint deletes without force.
        deleteSprint(ctx, ctx.token(), running, false).andExpect(status().isNoContent());
    }

    /**
     * The row is removed by a CONDITIONAL delete keyed by {@code (id, project)} and
     * checked on its affected-row count (0.13.0 review) — not by
     * {@code repository.delete(entity)}.
     *
     * <p>Two curators clicking "delete" at the same moment both pass
     * {@code requireSprint}; the loser would have {@code delete(entity)} MERGE a row that
     * no longer exists and 500, where the SPA expects a 404. The race itself cannot be
     * staged deterministically from an HTTP test, so what is pinned here is the property
     * that MAKES it a 404: the statement is scoped to the project and reports zero
     * affected rows instead of throwing.
     */
    @Test
    void theDeleteIsConditionalOnItsProjectSoALostRaceReportsZeroRowsRatherThanThrowing()
            throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Doomed");
        var sibling = siblingProject(ctx);

        // Scoped to the project: even called directly, it cannot delete another project's
        // sprint — and it says so with an affected-row count, not an exception.
        Integer strayed = txTemplate.execute(s ->
                sprintRepository.deleteByIdAndProject(sprintId, sibling.projectId()));
        assertThat(strayed).as("a delete keyed by the wrong project affected " + strayed + " rows").isNotNull();
        assertThat(strayed).as("a delete keyed by the wrong project affected " + strayed + " rows").isEqualTo(0);
        getSprint(ctx, ctx.token(), sprintId).andExpect(status().isOk());

        // Now play the winner of the race: the row goes away underneath the request…
        Integer won = txTemplate.execute(s ->
                sprintRepository.deleteByIdAndProject(sprintId, ctx.projectId()));
        assertThat(won).as("the conditional delete did not remove its own row").isNotNull();
        assertThat(won).as("the conditional delete did not remove its own row").isEqualTo(1);

        // …and the loser gets a 404, never a 500.
        deleteSprint(ctx, ctx.token(), sprintId, false).andExpect(status().isNotFound());
        deleteSprint(ctx, ctx.token(), sprintId, true).andExpect(status().isNotFound());
    }

    /**
     * {@code goal} is an ORDINARY field and must be written through the entity, never by
     * the lifecycle UPDATE (migration review M-Low1): {@code markActive} now sets only the
     * lifecycle columns, and {@code start} flushes the goal before the flip.
     *
     * <p>The regression this guards is silent: with {@code goal} in the bulk UPDATE's SET
     * clause, starting a sprint with no goal in the body would blank a goal a curator had
     * already planned.
     */
    @Test
    void startingASprintNeitherBlanksNorIgnoresItsGoal() throws Exception {
        var ctx = newProject();

        // ---- a goal set at planning time survives a start that does not mention it ----
        var planned = createSprint(ctx, ctx.token(),
                "{\"name\":\"Sprint 1\",\"goal\":\"Ship the beta\"}");
        startSprint(ctx, ctx.token(), planned).andExpect(status().isOk());
        assertThat(sprintNode(ctx, planned).get("goal").asText())
                .as("the lifecycle UPDATE blanked a goal it should never have written")
                .isEqualTo("Ship the beta");

        // …and it is still editable while the sprint runs.
        patchSprint(ctx, ctx.token(), planned, "{\"goal\":\"Ship the beta, minus billing\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value("Ship the beta, minus billing"));
        completeToBacklog(ctx, ctx.token(), planned).andExpect(status().isOk());

        // ---- a goal sent WITH the start replaces the planned one ----
        var next = createSprint(ctx, ctx.token(),
                "{\"name\":\"Sprint 2\",\"goal\":\"Planned goal\"}");
        startSprint(ctx, ctx.token(), next, "{\"goal\":\"Decided at planning\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.goal").value("Decided at planning"));
        // Read back through a fresh request: the response above is built after the flip
        // cleared the persistence context, so this is what actually landed in the row.
        assertThat(sprintNode(ctx, next).get("goal").asText())
                .as("the goal sent with the start did not persist")
                .isEqualTo("Decided at planning");
    }

    /**
     * Deleting the project takes its sprints with it (the cascade, §4.5).
     *
     * <p>Nothing in the <em>application</em> deletes a project — both projects and workspaces
     * are archived — so every project delete in the tree is a test fixture, and this is one of
     * them ({@code V19IssuesTaxonomyFkTest} runs several more, deliberately). That makes each of
     * them a place where the foreign keys {@code V19__issues_taxonomy_fk.sql} added can be met by
     * a cascade. This one is
     * unaffected, and measurably so rather than hopefully: AC-7 (`V19IssuesTaxonomyFkTest`)
     * showed that a project delete cascades cleanly even when the project's own issues use its
     * own project-scoped status, type and priority, and this fixture has neither
     * project-scoped taxonomy nor any issue. The one shape that does abort with {@code 23503}
     * is an issue <em>outside</em> the deleted project pointing at that project's scoped
     * catalog row, which {@code newProject()} cannot produce.
     */
    @Test
    void deletingAProjectCascadesItsSprints() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Doomed");

        projectRepository.deleteById(ctx.projectId());

        var rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sprints WHERE id = ?", Integer.class, sprintId);
        assertThat(rows).as("sprints did not cascade with the project").isNotNull();
        assertThat(rows).as("sprints did not cascade with the project").isEqualTo(0);
    }

    // ===================================================== list ordering

    @Test
    void theListIsActiveThenFutureBySequenceThenCompletedNewestFirst() throws Exception {
        var ctx = newProject();
        var one = createSprint(ctx, "Sprint one");     // sequence 1
        var two = createSprint(ctx, "Sprint two");     // sequence 2
        var three = createSprint(ctx, "Sprint three"); // sequence 3
        startSprint(ctx, ctx.token(), one).andExpect(status().isOk());
        completeToBacklog(ctx, ctx.token(), one).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), two).andExpect(status().isOk());

        var page = listSprints(ctx, ctx.token(), null);
        var names = new java.util.ArrayList<String>();
        for (var s : page.get("content")) names.add(s.get("name").asText());
        assertThat(names)
                .as("ACTIVE, then FUTURE by sequence, then COMPLETED; got " + names)
                .isEqualTo(java.util.List.of("Sprint two", "Sprint three", "Sprint one"));

        // ?state= is a repeatable filter.
        var open = listSprints(ctx, ctx.token(), "?state=ACTIVE&state=FUTURE");
        assertThat(open.get("content")).as("%s", open).hasSize(2);
        var completedOnly = listSprints(ctx, ctx.token(), "?state=COMPLETED");
        assertThat(completedOnly.get("content")).as("%s", completedOnly).hasSize(1);
        assertThat(completedOnly.get("content").get(0).get("id").asText())
                .as("the COMPLETED filter returns the sprint that was actually completed, not merely one row")
                .isEqualTo(one.toString());
        assertThat(three)
                .as("the FUTURE sprint was created; the ordering assertion above is what proves where it lands")
                .isNotNull();
    }
}
