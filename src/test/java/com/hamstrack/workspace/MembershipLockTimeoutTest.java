package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.persistence.LockTimeout;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.project.service.ProjectAdminGuard;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-136 review round 4 — <strong>a membership mutation that cannot get its row lock gives
 * up; it does not wait for ever.</strong>
 *
 * <p>The locking reads in {@code WorkspaceMemberService} and {@code ProjectAdminGuard} are
 * all ordered ({@code ORDER BY m.id}) so that overlapping transactions <em>queue</em>
 * instead of cycling. That is the right design and it is also why the original safety
 * argument — "overlapping removals deadlock, and a deadlock is safe because PostgreSQL
 * rolls one back" — was defending the branch that does not normally happen. The ordinary
 * outcome is a plain lock wait, and PostgreSQL's default wait is <strong>unbounded</strong>:
 * a client that gives up and disconnects does not abort the server-side transaction, and
 * Hikari's {@code connectionTimeout} bounds only connection checkout, never a query already
 * in flight. {@code WorkspaceMemberService.remove} holds two lock sets across the knowingly
 * unbounded unassign-history write, so concurrent removals of a member with a lot of
 * assigned work could pin connections until the pool was gone — for every tenant of a
 * Cloud instance, from one workspace's offboarding.
 *
 * <p>The fix is {@code SET LOCAL lock_timeout} inside the mutation's own transaction (never
 * a Hikari {@code connection-init-sql}: Flyway shares that datasource, and a migration
 * waiting for a lock on a large table is legitimate where a membership edit is not). These
 * tests pin the three things that can silently undo it: that the bound is really reached
 * and surfaces as the documented 409 rather than a 500 or a hang, that it is transaction-
 * scoped and cannot leak into a pooled connection, and that it refuses to be applied where
 * {@code SET LOCAL} would be a no-op.
 *
 * <p><strong>The timeout is lowered to {@link #TIMEOUT_MS} here</strong> so the wait is
 * measured in a fraction of a second. Deterministic in both directions: the lock holder
 * releases on a fixed schedule far longer than the bound, so a regression that removes the
 * bound does not hang the suite — it makes the removal succeed with 204 and fails the
 * assertion instead.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.locking.lock-timeout-ms=" + MembershipLockTimeoutTest.TIMEOUT_MS
})
@AutoConfigureMockMvc
class MembershipLockTimeoutTest {

    /** Far below {@link #HOLD_MS}, so "gave up" and "the holder let go" are never confusable. */
    static final int TIMEOUT_MS = 250;

    /** How long the competing transaction sits on the owner rows. */
    private static final long HOLD_MS = 4_000;

    /**
     * Comfortably more transactions than {@code spring.datasource.hikari.maximum-pool-size}
     * (10), so a leak-carrying connection cannot hide behind the pool handing out a
     * different one — and the test fails rather than passes if it somehow does.
     */
    private static final int POOL_SWEEP = 40;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RoleCatalog roleCatalog;
    @Autowired TransactionTemplate txTemplate;
    @Autowired EntityManager entityManager;
    @Autowired LockTimeout lockTimeout;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired ProjectAdminGuard projectAdminGuard;

    /**
     * <strong>The headline.</strong> One transaction holds the workspace's owner rows;
     * a removal arrives, queues behind them, and is answered {@code 409 + Retry-After}
     * after the bound rather than when the other transaction happens to finish.
     *
     * <p>Three assertions carry it, and each fails differently: the status proves the
     * SQLSTATE actually lands on {@code GlobalExceptionHandler.handlePessimisticLock}
     * (a 500 would mean {@code 55P03} is being translated to something else and the
     * "no new handler needed" claim is false); the elapsed time proves the request gave up
     * on its own rather than being released by the holder; and the intact membership proves
     * a refused removal is not a partial one.
     */
    @Test
    @Timeout(60)
    void aRemovalThatCannotGetTheOwnerLockAnswers409InsteadOfWaitingForEver() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");

        var holding = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var holder = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(s -> {
                    var workspace = workspaceRepository.findById(ws.workspace().getId()).orElseThrow();
                    // Exactly the read WorkspaceMemberService takes first, unconditionally.
                    workspaceMemberRepository.lockAllByWorkspaceAndRoleId(
                            workspace, BuiltInRoles.WORKSPACE_OWNER);
                    holding.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {   // NOSONAR — re-asserted on the main thread
                failure.set(t);
            }
        }, "owner-lock-holder");
        holder.start();
        assertThat(holding.await(20, TimeUnit.SECONDS))
                .as("the competing transaction never reached its lock")
                .isTrue();

        var startedAt = System.nanoTime();
        var response = mockMvc.perform(delete("/api/workspaces/" + ws.workspace().getId()
                                              + "/members/" + mia.getId())
                        .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isConflict())
                .andReturn().getResponse();
        var waitedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(response.getHeader("Retry-After"))
                .as("a lost lock race is the one failure whose entire contract is 'try again'")
                .isEqualTo("1");
        assertThat(response.getContentAsString())
                .as("the message must stay mechanism-free — a human is reading it")
                .contains("try again in a moment");
        assertThat(waitedMs)
                .as("it waited %d ms; the holder does not let go for %d ms, so anything near "
                    + "that means the wait was bounded by the OTHER transaction ending — i.e. "
                    + "not bounded at all", waitedMs, HOLD_MS)
                .isLessThan(HOLD_MS - 500);
        assertThat(waitedMs)
                .as("it returned in %d ms, faster than the bound — then it never queued on the "
                    + "lock and this test is proving nothing", waitedMs)
                .isGreaterThanOrEqualTo(TIMEOUT_MS - 50L);

        holder.join();
        assertThat(failure.get()).isNull();
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), mia))
                .as("a refused removal is not a partial removal")
                .isTrue();
    }

    /**
     * <strong>The pairing invariant, as far as it can honestly be asserted</strong> (review
     * round 7). {@code WorkspaceMemberService.lockOwners} and {@code ProjectAdminGuard
     * .lockAdmins} are public {@code MANDATORY} methods, and nothing in the code stops a
     * transaction from taking either lock without first calling
     * {@link LockTimeout#applyToCurrentTransaction()}; round 6 could only state that in a
     * comment at both helpers. Nothing readable tells a method at runtime whether
     * {@code SET LOCAL lock_timeout} has already run — {@code SHOW lock_timeout} is a
     * round-trip nobody wants on every acquisition — so the invariant cannot be asserted
     * from inside the helper.
     *
     * <p>What CAN be asserted, soundly and without parsing source, is the invariant's effect
     * at every place that takes a lock today: the request gives up on its own. The headline
     * test above does that for the workspace member <em>removal</em>; this one covers the
     * other two locking transactions — the role change (owner-set lock) and the project
     * member removal (the {@code lockAdmins} side, otherwise untested against the bound).
     * Together the three transactions that exist are demonstrably bounded.
     *
     * <p><strong>What it deliberately does not do:</strong> fail for a lock helper a
     * <em>future</em> transaction calls without the bound. That needs either a
     * source/bytecode scan (brittle, and silently green the moment a caller moves) or a
     * transaction-scoped marker set by {@code applyToCurrentTransaction} and asserted inside
     * the two helpers — a product change, not a test. Until one exists, a new locking path
     * needs a new half here.
     */
    @Test
    @Timeout(60)
    void theOtherTwoLockingTransactionsGiveUpOnTheBoundToo() throws Exception {
        // ---- the role change: WorkspaceMemberService.updateRole, owner-set lock.
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        assertGivesUpOnTheBound(
                () -> workspaceMemberRepository.lockAllByWorkspaceAndRoleId(
                        workspaceRepository.findById(ws.workspace().getId()).orElseThrow(),
                        BuiltInRoles.WORKSPACE_OWNER),
                patch("/api/workspaces/" + ws.workspace().getId() + "/members/" + mia.getId())
                        .header("Authorization", "Bearer " + ws.ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"));
        assertThat(workspaceMemberRepository.findByWorkspaceAndUser(ws.workspace(), mia)
                .orElseThrow().getRole().getId())
                .as("a refused role change is not a partial one")
                .isEqualTo(BuiltInRoles.WORKSPACE_MEMBER);

        // ---- the project member removal: ProjectService.removeMember -> lockAdmins.
        var project = project(ws);
        // The actor has to hold project.member.manage IN the project (it is outside the
        // workspace-admin curator bypass on purpose), so the owner's own row is also the
        // administrator row the guard locks.
        projectMember(project, ws.owner(), "MANAGER");
        var nina = addMember(ws, "MEMBER");
        projectMember(project, nina, "MEMBER");

        assertGivesUpOnTheBound(
                () -> projectAdminGuard.lockAdmins(
                        projectRepository.findById(project.getId()).orElseThrow()),
                delete("/api/workspaces/" + ws.workspace().getId() + "/projects/" + project.getId()
                       + "/members/" + nina.getId())
                        .header("Authorization", "Bearer " + ws.ownerToken()));
        assertThat(projectMemberRepository.findByProjectAndUser(project, nina))
                .as("a refused project-member removal is not a partial one")
                .isPresent();
    }

    /**
     * <strong>{@code SET LOCAL}, and only local.</strong> The bound exists for the length of
     * the transaction that asked for it and is gone afterwards — which is the entire reason
     * it is not a datasource-level setting: Flyway shares this pool, and a migration
     * inheriting a 3 s lock bound would start failing on tables that legitimately take
     * longer to lock.
     *
     * <p><strong>The pooled connection has to be pinned, or the test proves nothing</strong>
     * (review round 7). A leak is a property of a <em>server connection</em>: only the
     * backend that ran the {@code SET} can still be carrying the bound. The first version of
     * this test opened a second transaction and asserted {@code SHOW lock_timeout = 0} — but
     * with {@code minimum-idle=5} that transaction is free to check out a different
     * connection, one that never had the bound set on it, where the assertion is vacuously
     * true no matter how the bound was applied. So the backend pid is captured on both sides
     * and at least one later transaction must be proved to have landed on the <em>same</em>
     * backend; if the sweep never does, the test fails loudly instead of passing empty.
     */
    @Test
    void theBoundIsScopedToItsOwnTransactionAndNeverLeaksIntoThePool() {
        var boundedBackend = new AtomicReference<Integer>();
        txTemplate.executeWithoutResult(s -> {
            assertThat(lockTimeoutGuc())
                    .as("PostgreSQL's default is no bound at all — that is the problem")
                    .isEqualTo("0");
            lockTimeout.applyToCurrentTransaction();
            assertThat(lockTimeoutGuc()).isEqualTo(TIMEOUT_MS + "ms");
            boundedBackend.set(backendPid());
        });

        // More transactions than the pool can hold, so the connection the bound was set on
        // must come round again; every one of them is checked, and the one that matters is
        // the one that lands back on that backend.
        var revisited = false;
        for (var attempt = 0; attempt < POOL_SWEEP; attempt++) {
            revisited |= Boolean.TRUE.equals(txTemplate.execute(s -> {
                var pid = backendPid();
                assertThat(lockTimeoutGuc())
                        .as("backend %d still carries a bound after the commit that set it: "
                            + "that bound is on the CONNECTION, and would follow it into "
                            + "whatever ran next — Flyway included", pid)
                        .isEqualTo("0");
                return pid.equals(boundedBackend.get());
            }));
        }
        assertThat(revisited)
                .as("none of the %d follow-up transactions was served by backend %d — the "
                    + "assertion above then only ever looked at connections the bound was "
                    + "never set on, and could not have seen a leak", POOL_SWEEP,
                    boundedBackend.get())
                .isTrue();
    }

    /**
     * Outside a transaction PostgreSQL downgrades {@code SET LOCAL} to a warning and the
     * guard silently stops guarding, so the misuse is refused loudly instead — the same
     * posture {@code ProjectAdminGuard}'s {@code MANDATORY} lock methods take.
     */
    @Test
    void theBoundRefusesToBeAppliedWhereItWouldBeANoOp() {
        assertThatThrownBy(() -> lockTimeout.applyToCurrentTransaction())
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ==================================================== helpers

    /**
     * Hold whatever {@code takeTheLock} locks for {@link #HOLD_MS}, fire {@code request} into
     * it, and require the request to answer 409 <em>on its own bound</em> — early enough that
     * the holder cannot have released it, late enough that it really queued. The three
     * failure modes stay separable: a 500 means the SQLSTATE is not reaching
     * {@code handlePessimisticLock}; a wait near {@code HOLD_MS} means the wait was bounded by
     * the other transaction ending, i.e. not bounded at all; a wait under the bound means the
     * request never queued and the half proves nothing.
     */
    private void assertGivesUpOnTheBound(Runnable takeTheLock, RequestBuilder request)
            throws Exception {
        var holding = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var holder = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(s -> {
                    takeTheLock.run();
                    holding.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {   // NOSONAR — re-asserted on the main thread
                failure.set(t);
            }
        }, "lock-holder");
        holder.start();
        assertThat(holding.await(20, TimeUnit.SECONDS))
                .as("the competing transaction never reached its lock")
                .isTrue();

        var startedAt = System.nanoTime();
        var response = mockMvc.perform(request)
                .andExpect(status().isConflict())
                .andReturn().getResponse();
        var waitedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(response.getHeader("Retry-After"))
                .as("a lost lock race is the one failure whose entire contract is 'try again'")
                .isEqualTo("1");
        assertThat(waitedMs)
                .as("it waited %d ms and the holder does not let go for %d ms — anything near "
                    + "that means this transaction never had a bound of its own", waitedMs, HOLD_MS)
                .isLessThan(HOLD_MS - 500);
        assertThat(waitedMs)
                .as("it returned in %d ms, faster than the bound — then it never queued on the "
                    + "lock and this half is proving nothing", waitedMs)
                .isGreaterThanOrEqualTo(TIMEOUT_MS - 50L);

        holder.join();
        assertThat(failure.get()).isNull();
    }

    private String lockTimeoutGuc() {
        return (String) entityManager.createNativeQuery("SHOW lock_timeout").getSingleResult();
    }

    /**
     * Which server connection this transaction is on. The identity is what makes the leak
     * assertion non-vacuous: {@code SET} without {@code LOCAL} outlives the transaction on
     * <em>this</em> backend and nowhere else.
     */
    private Integer backendPid() {
        return ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()")
                .getSingleResult()).intValue();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================================================== bootstrap

    private record Ws(Workspace workspace, User owner, String ownerToken) {}

    private Ws newWorkspace() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, "OWNER");
        return new Ws(ws, owner, login(owner));
    }

    private User addMember(Ws ws, String role) {
        var u = user();
        member(ws.workspace(), u, role);
        return u;
    }

    private User user() {
        var u = new User();
        u.setEmail(("lt-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 8)
                    + "@example.com").toLowerCase());
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("lt-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User user, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
    }

    private Project project(Ws ws) {
        var p = new Project();
        p.setWorkspace(ws.workspace());
        p.setName("Apollo");
        p.setKey("P" + (Math.abs(UUID.randomUUID().hashCode()) % 1000000));
        p.setCreatedBy(ws.owner());
        return projectRepository.save(p);
    }

    private void projectMember(Project project, User user, String role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, role));
        projectMemberRepository.save(m);
    }

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
