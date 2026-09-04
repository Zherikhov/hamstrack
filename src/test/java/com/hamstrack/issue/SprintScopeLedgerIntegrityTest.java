package com.hamstrack.issue;

import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.SprintRepository;
import com.hamstrack.issue.service.SprintScopeLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scope ledger's INTEGRITY rules (HD-137 / R2, round 2) — everything that keeps a row
 * true, as opposed to {@code SprintScopeLedgerDoorsTest}, which keeps every door writing
 * one.
 *
 * <p>All five properties here share a failure mode and it is the reason they are tested
 * at all: <strong>none of them has a symptom</strong>. A ledger row filed under the wrong
 * tenant, a commitment dated a month in the future, a scope step that vanished with its
 * issue, a partial write from a caller with no transaction, a membership change lost in a
 * race with a bulk UPDATE — none throws, none 500s, none shows up in any endpoint. They
 * surface, months later, as a chart whose numbers are wrong, and a chart nobody can check
 * is a chart nobody trusts (§1.2).
 *
 * <ol>
 *   <li><strong>Tenancy.</strong> The three ids on a row must belong to one workspace,
 *       and the DATABASE must be what says so — {@code idx_sprint_scope_events_ws} exists
 *       precisely so R4's velocity sweep can filter by workspace alone.</li>
 *   <li><strong>Time.</strong> A commitment cannot have occurred in the future, and a
 *       started sprint's {@code startAt} cannot move afterwards. Either one makes
 *       {@code count(ADDED <= T) - count(REMOVED <= T)} go negative or read zero.</li>
 *   <li><strong>Survival, and door 8.</strong> Deleting an issue must not silently
 *       re-draw a completed sprint's review — and must record the departure when the
 *       sprint is still running. This is the door {@code SprintScopeLedgerDoorsTest}'s
 *       source scan cannot see (a DELETE writes no {@code sprint_id}), so this is where
 *       it is enforced.</li>
 *   <li><strong>Atomicity.</strong> The single writer refuses to run outside its caller's
 *       transaction.</li>
 *   <li><strong>Snapshot.</strong> The two bulk doors record what their UPDATE actually
 *       moved, not what a SELECT saw a moment earlier.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
@Import(SprintScopeLedgerIntegrityTest.Interleaving.class)
class SprintScopeLedgerIntegrityTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired SprintScopeLedger ledger;
    // Only for theCommitmentStampIsClampedNoMatterWhichCallerAsks, which calls the single
    // writer directly because no HTTP door can hand it a forward date any more.
    @Autowired SprintRepository sprintRepository;
    @Autowired IssueRepository issueRepository;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach
    void clearHooks() {
        Interleaving.HOOKS.clear();
    }

    /**
     * The seam that makes a race deterministic: a decorator around {@link IssueRepository}
     * that runs a one-shot hook immediately BEFORE a named method reaches the real
     * repository. That is exactly the window a bulk statement's write-list has to be
     * correct across.
     *
     * <p>A plain JDK proxy rather than a Mockito spy, on purpose: a Spring Data repository
     * is an interface proxy, so {@code invocation.callRealMethod()} throws ("Cannot call
     * abstract real method"), and how a spy delegates instead is a Mockito/Spring internal
     * that a test should not be built on.
     */
    @TestConfiguration
    static class Interleaving {

        /** {@code methodName -> what to run once, just before the real call}. */
        static final Map<String, Runnable> HOOKS = new ConcurrentHashMap<>();

        @Bean
        static BeanPostProcessor interleavingIssueRepository() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof IssueRepository)) return bean;
                    return Proxy.newProxyInstance(
                            IssueRepository.class.getClassLoader(),
                            ClassUtils.getAllInterfacesForClass(bean.getClass()),
                            (proxy, method, args) -> {
                                var hook = HOOKS.remove(method.getName());
                                if (hook != null) hook.run();
                                try {
                                    return method.invoke(bean, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
            };
        }
    }

    // ============================================================ 1) tenancy

    /**
     * <strong>Under two-tenant interleaving, each sprint's ledger is EXACTLY what its own
     * doors wrote</strong> — the right rows, in the right order, filed under the right
     * workspace, and no others.
     *
     * <p>The exactness is the assertion, and it is a deliberate replacement for the "every
     * row carries its own workspace" form this test used to have. That form was
     * unfalsifiable: {@code workspace_id} is part of BOTH composite FKs since V18, so a
     * row assembled from two tenants is refused by the INSERT and never reaches the
     * assert. Making it fail would have meant deleting the constraint, i.e. testing the
     * assertion instead of the code — which is exactly the outcome V8 §3.8 asked for, so
     * the property is genuinely proven, just by
     * {@link #theDatabaseRefusesALedgerRowAssembledFromTwoTenants()} and the migration
     * test rather than here.
     *
     * <p>What the FKs still permit, and what this therefore checks: <strong>a door writing
     * NO row at all, or writing one tenant's event while another tenant's request is in
     * flight.</strong> Both are ordinary code mutations, and both are invisible to a
     * {@code !rows.isEmpty()} precondition.
     *
     * <p>The FKs also permit a row filed under the right workspace but against the WRONG
     * SPRINT of that workspace — the FK is satisfied, the burn-up is not. That is NOT
     * covered here, and saying so is the point: each tenant in this fixture owns exactly
     * one sprint, so there is no other sprint of the same workspace for a row to land on
     * and the branch cannot fail. Giving each tenant a second sprint would have to be done
     * sequentially ({@code sprints_one_active_per_project_uk} forbids two ACTIVE ones),
     * which buys a fixture twice the size for a property that is already pinned:
     * {@link #deletingAnIssueEndsItsMembershipOnlyWhereTheRecordIsNotFrozen()} holds two
     * sprints of one project at once and asserts each one's ledger exactly.
     * The two tenants run the same doors against the same schema at the same time, so an
     * event that lands on the neighbour's sprint shows up as a count of 3 here and 0
     * there, not as a silent pass.
     */
    @Test
    void eachTenantsSprintCarriesExactlyItsOwnLedger() throws Exception {
        var one = newProject();
        var two = newProject();          // a different user, a different workspace
        assertThat(one.wsId()).as("the fixture is not two tenants").isNotEqualTo(two.wsId());

        var sprintOne = startedSprint(one, "Sprint 1");
        var sprintTwo = startedSprint(two, "Sprint 1");
        var issueOne = createIssue(one, "tenant one's work");
        var issueTwo = createIssue(two, "tenant two's work");
        addIssuesToSprint(one, one.token(), sprintOne, idOf(issueOne)).andExpect(status().isOk());
        addIssuesToSprint(two, two.token(), sprintTwo, idOf(issueTwo)).andExpect(status().isOk());
        removeIssueFromSprint(one, one.token(), sprintOne, idOf(issueOne))
                .andExpect(status().isNoContent());

        // Tenant one opened two doors (an add and a remove), tenant two exactly one.
        assertLedgerIs(sprintOne, one.wsId(), List.of("ADDED", "REMOVED"));
        assertLedgerIs(sprintTwo, two.wsId(), List.of("ADDED"));
    }

    /**
     * The whole ledger of one sprint, compared element-by-element — not "at least one row"
     * and not a count, so a row that went to the wrong sprint, a door that wrote nothing,
     * and a doubled write are three different failures with three different messages.
     */
    private void assertLedgerIs(UUID sprintId, UUID workspace, List<String> expected) {
        var rows = jdbc.queryForList(
                "SELECT event, workspace_id FROM sprint_scope_events WHERE sprint_id = ? "
                + "ORDER BY occurred_at, created_at", sprintId);
        var actual = rows.stream().map(r -> (String) r.get("event")).toList();
        assertThat(actual)
                .as(() -> """
                Sprint %s's ledger is %s, expected %s.

                Each tenant's doors were opened a known number of times, so this is an \
                EXACT comparison: too few means a door wrote nothing (or wrote onto the \
                other tenant's sprint, which both composite FKs happily allow — they \
                constrain the workspace, not which sprint of it); too many means a door \
                wrote twice; a different order means the arithmetic reads a different \
                scope at every T in between. None of these has a symptom outside a \
                chart.""".formatted(sprintId, actual, expected))
                .isEqualTo(expected);
        for (var row : rows) {
            assertThat(workspace)
                    .as(() -> """
                    A scope event of sprint %s is filed under workspace %s, not %s.

                    Note this is NOT what enforces tenancy — the DATABASE does, via the \
                    two composite FKs on (sprint_id, workspace_id) and (issue_id, \
                    workspace_id); a mismatched row cannot be inserted at all. This is a \
                    corroboration, and it is here because workspace_id is denormalised so \
                    a velocity sweep can filter by tenant WITHOUT joining through projects \
                    (idx_sprint_scope_events_ws).""".formatted(
                            sprintId, row.get("workspace_id"), workspace))
                    .isEqualTo(row.get("workspace_id"));
        }
    }

    /**
     * …and the invariant is held by the DATABASE, not by the service that happens to pass
     * the right id today. V8 §3.8 made this the rule for every workspace-denormalised
     * table: attaching one tenant's row to another's must be UNREPRESENTABLE, not
     * checked, because "checked" is one forgotten {@code AndWorkspace} away from a leak.
     *
     * <p>Both composite FKs are exercised, because they fail independently: a wrong
     * sprint and a wrong issue are two different mistakes.
     */
    @Test
    void theDatabaseRefusesALedgerRowAssembledFromTwoTenants() throws Exception {
        var one = newProject();
        var two = newProject();
        var sprintOne = startedSprint(one, "Sprint 1");
        var sprintTwo = startedSprint(two, "Sprint 1");
        var issueOne = idOf(createIssue(one, "tenant one's issue"));
        var issueTwo = idOf(createIssue(two, "tenant two's issue"));

        // Sanity: the same statement with three ids from ONE tenant is accepted, so the
        // refusals below are about the tenant mismatch and not about the statement.
        insertEvent(one.wsId(), sprintOne, issueOne);

        assertThat(refused(() -> insertEvent(one.wsId(), sprintTwo, issueOne)))
                .withFailMessage("""
                sprint_scope_events accepted a row whose sprint belongs to another \
                workspace. sprint_scope_events_sprint_fk must be the COMPOSITE
                (sprint_id, workspace_id) -> sprints (id, workspace_id).""")
                .isTrue();
        assertThat(refused(() -> insertEvent(one.wsId(), sprintOne, issueTwo)))
                .withFailMessage("""
                sprint_scope_events accepted a row whose issue belongs to another \
                workspace. sprint_scope_events_issue_fk must be the COMPOSITE
                (issue_id, workspace_id) -> issues (id, workspace_id).""")
                .isTrue();
    }

    private void insertEvent(UUID workspaceId, UUID sprintId, UUID issueId) {
        jdbc.update("INSERT INTO sprint_scope_events "
                    + "(id, workspace_id, sprint_id, issue_id, issue_key, event, occurred_at, created_at) "
                    + "VALUES (?, ?, ?, ?, 'X-1', 'ADDED', now(), now())",
                UUID.randomUUID(), workspaceId, sprintId, issueId);
    }

    private static boolean refused(Runnable write) {
        try {
            write.run();
            return false;
        } catch (DataIntegrityViolationException expected) {
            return true;
        }
    }

    // ============================================================ 2) time

    /**
     * A sprint cannot be STARTED into the future (HD-137 R3): 422 at the door.
     *
     * <p>R2 only clamped the ledger's stamp, which kept the scope line non-negative but
     * left {@code sprints.start_at} a month ahead of the commitment rows that describe it.
     * Three things follow, and they compound: a burn-up computed over the sprint's own
     * WINDOW reads 0 committed (only the cumulative form survives); {@link #update}'s new
     * freeze makes the mistyped date <em>unrecoverable</em> except by complete-and-recreate;
     * and nothing relates {@code completed_at} to {@code start_at}, so the sprint can
     * complete before it started and velocity reads a negative-length iteration.
     *
     * <p>Backdating stays legal, and so does PLANNING a future start — only the act that
     * turns a plan into history is refused. The refusal must therefore prescribe something
     * its reader can actually do, which is the whole assertion on the message: they hold a
     * FUTURE sprint, so "leave it planned and start it when it starts" is reachable and
     * "fix the date" would not be.
     */
    @Test
    void aSprintCannotBeStartedIntoTheFuture() throws Exception {
        var ctx = newProject();
        var sprint = createSprint(ctx, "Sprint 1");
        var one = createIssue(ctx, "committed one");
        var two = createIssue(ctx, "committed two");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(one), idOf(two))
                .andExpect(status().isOk());

        var future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);
        var refusal = startSprint(ctx, ctx.token(), sprint,
                "{\"startAt\":\"" + iso(future) + "\",\"endAt\":\"" + iso(future.plusDays(14)) + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();
        assertThat(refusal)
                .as(() -> """
                The refusal must name an action its reader can perform. They are holding a \
                FUTURE sprint, so telling them to start it when it begins is reachable; \
                "fix the date" is not, because the date they typed is exactly the one the \
                server just rejected. Body was: %s""".formatted(refusal))
                .contains("start it when it actually begins");

        // Nothing half-applied — and the recovery the message promises really exists.
        assertThat(sprintNode(ctx, sprint).get("state").asText())
                .as("a rejected start must leave the sprint planned")
                .isEqualTo("FUTURE");
        assertThat(eventsOf(sprint)).as(() -> "a rejected start must commit nothing: " + eventsOf(sprint)).isEmpty();
        patchSprint(ctx, ctx.token(), sprint, "{\"startAt\":\"" + iso(future) + "\","
                                              + "\"endAt\":\"" + iso(future.plusDays(14)) + "\"}")
                .andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        assertThat(eventsOf(sprint)).as("starting it for real commits both members").hasSize(2);

        // A client clock a couple of minutes fast is skew, not an intention: START_CLOCK_SKEW
        // exists so the most ordinary action in the feature does not 422 on an unsynced laptop.
        // What must NOT follow is that the fast reading gets PERSISTED — which is what this
        // asserts, and what a bare 200 used to hide. A start_at ahead of the commitment rows
        // that describe it is failure (1) of requireStartHasArrived's list in miniature: the
        // ledger stamps "now", `update`'s freeze then makes the discrepancy permanent, and a
        // burn-up windowed on `occurred_at >= start_at` reads a committed scope of ZERO. The
        // effect is not proportional to the skew — bounded in probability, total in effect —
        // and it is the ordinary case, because the tolerance exists precisely for clients
        // whose clock runs fast. So the admitted skew is clamped to now.
        completeToBacklog(ctx, ctx.token(), sprint).andExpect(status().isOk());
        var next = createSprint(ctx, "Sprint 2");
        var onAFastClock = createIssue(ctx, "committed by a client running a minute ahead");
        addIssuesToSprint(ctx, ctx.token(), next, idOf(onAFastClock)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), next, "{\"startAt\":\""
                + iso(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)) + "\"}")
                .andExpect(status().isOk());

        var persisted = OffsetDateTime.parse(sprintNode(ctx, next).get("startAt").asText());
        assertThat(persisted.isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
                .withFailMessage(() -> """
                A start admitted inside START_CLOCK_SKEW persisted start_at = %s, which is \
                still in the future. The tolerance is for a CLOCK: "a couple of minutes fast \
                means now", not "a couple of minutes fast means a sprint that starts after \
                its own commitment". Clamp the value you PERSIST, not only the one you \
                stamp.""".formatted(persisted))
                .isFalse();
        assertThat(eventsOf(next))
                .as(() -> "precondition: the fast-clock start committed its one member, else the "
                  + "window check below is vacuous: " + eventsOf(next))
                .hasSize(1);
        Integer beforeItsOwnStart = jdbc.queryForObject("""
                SELECT count(*) FROM sprint_scope_events e
                  JOIN sprints s ON s.id = e.sprint_id
                 WHERE e.sprint_id = ? AND e.occurred_at < s.start_at
                """, Integer.class, next);
        assertThat(beforeItsOwnStart != null && beforeItsOwnStart == 0)
                .withFailMessage(() -> """
                %d of this sprint's commitment rows are dated BEFORE its own start_at, so a \
                burn-up windowed on occurred_at >= start_at reads a committed scope of 0 — \
                all of it, not a bit of it. start_at and the commitment stamp must be the \
                same instant for every door, including the one that was a minute \
                fast.""".formatted(beforeItsOwnStart))
                .isTrue();
    }

    /**
     * …and the clamp inside {@link SprintScopeLedger} stays anyway, belt-and-braces.
     *
     * <p>{@link #aSprintCannotBeStartedIntoTheFuture()} closes the only door that can hand
     * the ledger a forward date today, so this exercises the writer DIRECTLY — otherwise
     * the clamp becomes untested code that a future ninth caller (a scheduled starter, an
     * import) would silently discover was load-bearing. Unclamped, N {@code ADDED} rows
     * dated {@code now + 30d} plus one ordinary {@code REMOVED} dated today make
     * {@code count(ADDED <= T) - count(REMOVED <= T)} equal <strong>-1</strong> for a
     * month, and a scope line that goes negative is a chart that visibly lies.
     */
    @Test
    void theCommitmentStampIsClampedNoMatterWhichCallerAsks() throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "committed");
        addIssuesToSprint(ctx, ctx.token(), running, idOf(issue)).andExpect(status().isOk());

        var sprint = sprintRepository.findById(running).orElseThrow();
        var far = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);
        new TransactionTemplate(txManager).executeWithoutResult(tx ->
                ledger.recordCommitment(ctx.wsId(), "X", sprint,
                        issueRepository.findRefsBySprint(sprint), null, far));

        Integer ahead = jdbc.queryForObject(
                "SELECT count(*) FROM sprint_scope_events WHERE sprint_id = ? AND occurred_at > now()",
                Integer.class, running);
        assertThat(ahead != null && ahead == 0)
                .withFailMessage(() -> """
                %d scope events are dated in the FUTURE. SprintScopeLedger.recordCommitment \
                must clamp its stamp to now regardless of what its caller passes: it is the \
                single writer, and being correct only for the door that happens to call it \
                today is exactly the property this class exists to refuse.""".formatted(ahead))
                .isTrue();
    }

    /**
     * …and the other half of the same problem: {@code start_at} is an ordinary
     * entity-writable column, so without a guard a PATCH moves a RUNNING sprint's start
     * while its commitment rows keep the original stamp. Pull the start earlier and
     * "committed scope at start" reads 0, because every {@code ADDED} row now sits after
     * it.
     *
     * <p>422, mirroring the existing "an active sprint must keep a start date". The plan
     * is editable exactly as long as it is a plan.
     */
    @Test
    void theStartDateOfASprintThatHasLeftTheFutureCannotBeMoved() throws Exception {
        var ctx = newProject();
        var sprint = createSprint(ctx, "Sprint 1");

        // FUTURE: a plan, freely re-planned.
        var planned = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3);
        patchSprint(ctx, ctx.token(), sprint, "{\"startAt\":\"" + iso(planned) + "\","
                                              + "\"endAt\":\"" + iso(planned.plusDays(14)) + "\"}")
                .andExpect(status().isOk());

        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());
        var startedAt = OffsetDateTime.parse(sprintNode(ctx, sprint).get("startAt").asText());

        patchSprint(ctx, ctx.token(), sprint,
                "{\"startAt\":\"" + iso(startedAt.minusDays(7)) + "\"}")
                .andExpect(status().isUnprocessableContent());
        patchSprint(ctx, ctx.token(), sprint, "{\"clearStartAt\":true}")
                .andExpect(status().isUnprocessableContent());

        // The SAME instant in another offset is not a change, and must not 422 a PATCH
        // that only meant to edit the goal next to it.
        patchSprint(ctx, ctx.token(), sprint,
                "{\"startAt\":\"" + iso(startedAt.withOffsetSameInstant(ZoneOffset.ofHours(2)))
                + "\",\"goal\":\"ship it\"}")
                .andExpect(status().isOk());

        completeToBacklog(ctx, ctx.token(), sprint).andExpect(status().isOk());
        patchSprint(ctx, ctx.token(), sprint,
                "{\"startAt\":\"" + iso(startedAt.plusDays(1)) + "\"}")
                .andExpect(status().isUnprocessableContent());
        // …while everything that is not the delivery record stays editable.
        patchSprint(ctx, ctx.token(), sprint, "{\"goal\":\"retro done\"}")
                .andExpect(status().isOk());
    }

    private static String iso(OffsetDateTime at) {
        return at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // ============================================================ 3) survival

    /**
     * <strong>Door 8</strong> — {@code DELETE /issues/{n}} — and it is deliberately
     * CONDITIONAL, so both directions are asserted here. This is also the one door
     * {@code SprintScopeLedgerDoorsTest}'s source scan structurally cannot enforce: a
     * DELETE ends a membership without ever writing {@code sprint_id}, so there is no
     * {@code setSprint(…)} and no {@code SET sprint_id =} to match (see that class's
     * {@code UNSCANNABLE_DOORS}). This test is the enforcement.
     *
     * <p>Two things must be true, and only one of them was before HD-137 R3:
     *
     * <ol>
     *   <li><strong>A COMPLETED sprint's record is untouched by a later deletion.</strong>
     *       {@code SprintService.requireDetachable} refuses (422) to let even a curator
     *       pull a DONE issue out of a closed sprint, so "17 of 21 points delivered"
     *       cannot quietly become another number. If the delete appended a
     *       {@code REMOVED}, the actor explicitly refused the detach would get its exact
     *       effect by deleting the issue instead. The existing rows survive the issue —
     *       {@code issue_id} goes NULL (V18's {@code ON DELETE SET NULL (issue_id)}),
     *       {@code issue_key} and {@code story_points} keep the review readable — and
     *       nothing is added.</li>
     *   <li><strong>An ACTIVE sprint's scope comes back down.</strong> Here there is no
     *       freeze to protect: the same removal is permitted through the API. A delete
     *       that wrote nothing would leave an {@code ADDED} with no matching
     *       {@code REMOVED}, and the burn-up would go on counting work that no longer
     *       exists — forever, since nothing ever revisits a past sprint's ledger.</li>
     * </ol>
     */
    @Test
    void deletingAnIssueEndsItsMembershipOnlyWhereTheRecordIsNotFrozen() throws Exception {
        var ctx = newProject();

        // ---- 1) COMPLETED: frozen. The delete must write NOTHING. ----
        var closed = startedSprint(ctx, "Sprint 1");
        var delivered = createIssue(ctx, "delivered, then deleted", "\"storyPoints\":5");
        addIssuesToSprint(ctx, ctx.token(), closed, idOf(delivered)).andExpect(status().isOk());
        markDone(ctx, numberOf(delivered));
        // A DONE issue keeps its sprint_id through completion — that IS the delivery record.
        completeToBacklog(ctx, ctx.token(), closed).andExpect(status().isOk());
        var before = eventsOf(closed);
        assertThat(before).as("precondition: the delivered issue was recorded once: " + before).hasSize(1);
        assertThat(before.get(0).get("event"))
                .as("precondition: the delivered issue was recorded once: " + before)
                .isEqualTo("ADDED");
        var key = (String) before.get(0).get("issue_key");
        assertThat(key).as("the row must snapshot the issue key, was " + key).isNotNull();
        assertThat(key).as("the row must snapshot the issue key, was " + key).endsWith("-" + numberOf(delivered));

        deleteIssue(ctx, numberOf(delivered));

        var after = eventsOf(closed);
        assertThat(after)
                .as(() -> """
                Deleting an issue changed a COMPLETED sprint's review record (%s). Either \
                the row was cascaded away or a REMOVED was appended — both re-draw a \
                delivered fact, and appending one hands the actor requireDetachable \
                refuses (422) exactly the effect it refuses.""".formatted(after))
                .hasSize(1);
        assertThat(after.get(0).get("event")).as("the surviving row must be the ADDED: " + after).isEqualTo("ADDED");
        assertThat(after.get(0).get("issue_id")).as("issue_id must be NULL, not dangling: " + after).isNull();
        assertThat(key)
                .as("the snapshot is what makes the surviving row readable: " + after)
                .isEqualTo(after.get(0).get("issue_key"));
        assertThat(after.get(0).get("story_points"))
                .as("the estimate at the moment of the event must survive too: " + after)
                .isNotNull();
        assertThat(scopeOf(closed))
                .as("the completed sprint's scope must keep the step the deleted issue caused")
                .isEqualTo(1);

        // ---- 2) ACTIVE: nothing is frozen, so the scope must come back down. ----
        var running = startedSprint(ctx, "Sprint 2");
        var inFlight = createIssue(ctx, "in flight, then deleted", "\"storyPoints\":3");
        addIssuesToSprint(ctx, ctx.token(), running, idOf(inFlight)).andExpect(status().isOk());
        assertThat(scopeOf(running)).as("precondition: the add raised the scope to 1").isEqualTo(1);

        deleteIssue(ctx, numberOf(inFlight));

        var live = eventsOf(running);
        assertThat(live.stream().map(e -> e.get("event")).toList())
                .as(() -> """
                Deleting an issue out of a RUNNING sprint recorded %s. requireDetachable \
                PERMITS this removal through the API, so there is no frozen record to \
                protect — and with no REMOVED the sprint's scope line never comes back \
                down: the burn-up keeps counting work that no longer exists, with no \
                error and nothing anywhere for a user to see.""".formatted(
                        live.stream().map(e -> e.get("event")).toList()))
                .isEqualTo(List.of("ADDED", "REMOVED"));
        assertThat(scopeOf(running))
                .as(() -> """
                The running sprint's scope is still %d after its only issue was deleted. \
                ADDED minus REMOVED has to come back out even, exactly as it does when the \
                issue is removed through DELETE /sprints/{id}/issues/{issueId}.""".formatted(
                        scopeOf(running)))
                .isEqualTo(0);
        for (var row : live) {
            assertThat(row.get("issue_id")).as("issue_id must be NULL, not dangling: " + live).isNull();
            assertThat(key(row))
                    .as("both rows must stay readable by their snapshotted key: " + live)
                    .endsWith("-" + numberOf(inFlight));
        }
    }

    private static String key(Map<String, Object> row) {
        return String.valueOf(row.get("issue_key"));
    }

    // ============================================================ 4) atomicity

    /**
     * The single writer refuses to start its own transaction.
     *
     * <p>All seven doors are {@code @Transactional} today, so a partial write is
     * impossible right now — this is about the eighth. A caller from a scheduled job, an
     * {@code @Async} fan-out or a controller shortcut would get an auto-commit
     * transaction per {@code saveAll} and could commit a scope event while the caller's
     * history write rolled back: the ledger and the audit log would then disagree, which
     * is the one divergence this whole design exists to prevent.
     *
     * <p>Arguments are all null on purpose — {@code MANDATORY} is enforced by the
     * transaction interceptor, i.e. BEFORE the method body, so a
     * {@code NullPointerException} here would mean the annotation is missing.
     */
    @Test
    void theLedgerRefusesToWriteOutsideItsCallersTransaction() {
        for (var call : List.<Runnable>of(
                () -> ledger.recordMove(null, null, null, null, null, null),
                () -> ledger.recordDepartureBeforeDelete(null, null, null, null, null),
                () -> ledger.recordBulkMove(null, null, List.of(), null, null, null),
                () -> ledger.recordCommitment(null, null, null, List.of(), null, null))) {
            try {
                call.run();
                throw new AssertionError("a ledger method ran with no transaction at all");
            } catch (IllegalTransactionStateException expected) {
                // exactly right: @Transactional(propagation = MANDATORY)
            } catch (RuntimeException other) {
                throw new AssertionError("""
                        A ledger method entered its body without a transaction (it threw \
                        %s). Every method here must be \
                        @Transactional(propagation = MANDATORY), or a future caller \
                        outside a transaction commits a scope event while the membership \
                        change that caused it rolls back."""
                        .formatted(other.getClass().getSimpleName()), other);
            }
        }
    }

    // ============================================================ 5) snapshot

    /**
     * Door 6: a membership change that commits DURING a completion is still recorded.
     *
     * <p>The write-list used to be a SELECT run before the bulk UPDATE. Under READ
     * COMMITTED those are two snapshots, and an issue added in between is moved by the
     * UPDATE but missing from both the history rows and the ledger: an {@code ADDED} with
     * no matching {@code REMOVED}, so it stays in the completed sprint's scope forever
     * while its {@code sprint_id} points at the backlog. Nothing errors, and the two
     * records agree with each other — they are simply both wrong.
     *
     * <p>The race is made deterministic by firing the concurrent request from inside the
     * spy on the bulk statement itself, on another thread so it is a real second
     * transaction that COMMITS before the UPDATE runs.
     */
    @Test
    void anIssueAddedDuringACompletionIsCarriedOverAndRecorded() throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Sprint 1");
        var present = createIssue(ctx, "in the sprint all along");
        var latecomer = createIssue(ctx, "added while the sprint was being completed");
        addIssuesToSprint(ctx, ctx.token(), running, idOf(present)).andExpect(status().isOk());

        Interleaving.HOOKS.put("moveUnfinishedOutOfSprint",
                () -> concurrently(() -> addIssuesToSprint(ctx, ctx.token(), running, idOf(latecomer))
                        .andExpect(status().isOk())));

        completeToBacklog(ctx, ctx.token(), running).andExpect(status().isOk());

        assertThat(Interleaving.HOOKS)
                .as("the interleaving never happened — this test proved nothing")
                .doesNotContainKey("moveUnfinishedOutOfSprint");
        assertThat(sprintIdOf(idOf(latecomer)))
                .as("precondition: the completion did carry the latecomer out of the sprint")
                .isNull();
        assertThat(carryOverHistory(idOf(latecomer)))
                .as("""
                The completion moved the late issue out of the sprint and wrote NO history \
                row for it — after this request nothing records that it was ever in the \
                sprint (security review L2). The write-list must come from the UPDATE's own \
                RETURNING, not from a SELECT taken before it.""")
                .isEqualTo(1);
        assertThat(netScopeOf(running, idOf(latecomer)))
                .as("""
                The late issue is still counted in the completed sprint's scope (net %d) \
                while its sprint_id points at the backlog. An ADDED with no matching \
                REMOVED is a burn-up that never comes back down.""".formatted(
                        netScopeOf(running, idOf(latecomer))))
                .isEqualTo(0);
        assertThat(netScopeOf(running, idOf(present))).as("the ordinary member must still net out too").isEqualTo(0);
    }

    /**
     * Door 7, the same window: an issue added while a sprint is being force-deleted must
     * still get its {@code sprint} history row, or after the request nothing anywhere
     * records that it was ever in that sprint — the sprint row itself is gone.
     *
     * <p>A FUTURE sprint on purpose: a COMPLETED one refuses new issues (422), and
     * planning writes no ledger rows anyway, so the property under test here is the audit
     * trail rather than the scope line. Same statement, same window, same fix.
     */
    @Test
    void anIssueAddedDuringAForceDeleteIsStillAudited() throws Exception {
        var ctx = newProject();
        var planned = createSprint(ctx, "Sprint 1");
        var present = createIssue(ctx, "planned all along");
        var latecomer = createIssue(ctx, "planned while the sprint was being deleted");
        addIssuesToSprint(ctx, ctx.token(), planned, idOf(present)).andExpect(status().isOk());

        Interleaving.HOOKS.put("clearSprint",
                () -> concurrently(() -> addIssuesToSprint(ctx, ctx.token(), planned, idOf(latecomer))
                        .andExpect(status().isOk())));

        deleteSprint(ctx, ctx.token(), planned, true).andExpect(status().isNoContent());

        assertThat(Interleaving.HOOKS)
                .as("the interleaving never happened — this test proved nothing")
                .doesNotContainKey("clearSprint");
        assertThat(sprintIdOf(idOf(latecomer))).as("precondition: the force-delete detached it").isNull();
        assertThat(carryOverHistory(idOf(latecomer)))
                .as("""
                The force-delete detached the late issue and wrote no history row for it. \
                The sprint is gone, so that row was the last thing that could ever say the \
                issue had been in it (security review L2).""")
                .isEqualTo(1);
    }

    // ============================================================ plumbing

    /** Runs {@code body} on another thread — a real second transaction — and waits. */
    private void concurrently(ThrowingRunnable body) {
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "ledger-race");
        thread.start();
        try {
            thread.join(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the concurrent request", e);
        }
        assertThat(thread.isAlive()).withFailMessage("the concurrent request never finished — deadlock?").isFalse();
        if (failure.get() != null) {
            throw new AssertionError("the concurrent request failed", failure.get());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void deleteIssue(Ctx ctx, long number) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(ctx.issuesBase() + "/" + number)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());
    }

    private List<Map<String, Object>> eventsOf(UUID sprintId) {
        return jdbc.queryForList(
                "SELECT event, story_points, issue_id, issue_key FROM sprint_scope_events "
                + "WHERE sprint_id = ? ORDER BY occurred_at, created_at", sprintId);
    }

    /** {@code count(ADDED) - count(REMOVED)} — "scope now", the way a report computes it. */
    private int scopeOf(UUID sprintId) {
        Integer scope = jdbc.queryForObject(
                "SELECT coalesce(sum(CASE WHEN event = 'ADDED' THEN 1 ELSE -1 END), 0) "
                + "FROM sprint_scope_events WHERE sprint_id = ?", Integer.class, sprintId);
        return scope == null ? 0 : scope;
    }

    /** The same arithmetic for ONE issue — is it still counted in this sprint's scope? */
    private int netScopeOf(UUID sprintId, UUID issueId) {
        Integer scope = jdbc.queryForObject(
                "SELECT coalesce(sum(CASE WHEN event = 'ADDED' THEN 1 ELSE -1 END), 0) "
                + "FROM sprint_scope_events WHERE sprint_id = ? AND issue_id = ?",
                Integer.class, sprintId, issueId);
        return scope == null ? 0 : scope;
    }

    private UUID sprintIdOf(UUID issueId) {
        return jdbc.queryForObject("SELECT sprint_id FROM issues WHERE id = ?", UUID.class, issueId);
    }

    /** {@code sprint} history rows recording a move OUT of a sprint (new_value IS NULL). */
    private int carryOverHistory(UUID issueId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM issue_history "
                + "WHERE issue_id = ? AND field = 'sprint' AND new_value IS NULL",
                Integer.class, issueId);
        return n == null ? 0 : n;
    }
}
