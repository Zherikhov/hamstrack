package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.RoleRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-127 (S4) <strong>review round 3</strong> — the sprawl cap's row lock: that it is really
 * emitted, that it is the <em>right mode</em>, and that it refuses to run where it would be a
 * no-op.
 *
 * <p>Round 2 made {@code app.roles.max-custom-per-workspace} exact by counting under a lock,
 * and three things about that lock were unverified:
 *
 * <ul>
 *   <li><strong>Nothing asserted it was emitted at all.</strong> {@code @Lock} on a
 *       <em>scalar projection</em> is a shape Hibernate treats differently from an entity
 *       query; if the {@code for … update} suffix were ever dropped the cap would silently
 *       revert to the advisory one it was and the whole suite would stay green.</li>
 *   <li><strong>The mode was one step too strong.</strong> {@code PESSIMISTIC_WRITE} is
 *       {@code FOR UPDATE}, the only row-lock mode that conflicts with {@code FOR KEY SHARE}
 *       — which PostgreSQL takes <em>itself</em> on the parent row for every INSERT into a
 *       table with an FK to {@code workspaces(id)}. Eighteen such FKs exist ({@code issues},
 *       {@code projects}, {@code labels}, {@code sprints}, {@code versions},
 *       {@code workspace_members}…), so while one duplication held the lock, <em>nothing
 *       could be created anywhere in that workspace</em> — and those waiters never called
 *       {@code LockTimeout}, so they waited unbounded. {@code FOR NO KEY UPDATE} still
 *       excludes a concurrent duplication and does not conflict with {@code FOR KEY
 *       SHARE}.</li>
 *   <li><strong>It was the only one of the four locks without
 *       {@code Propagation.MANDATORY}</strong>, so an external caller with no transaction
 *       would have got a Spring Data transaction of its own, released before the count it
 *       protects.</li>
 * </ul>
 *
 * <p>The three tests below are deliberately of different kinds — the text of the emitted SQL
 * is a tripwire, and mutual exclusion and FK-child non-conflict are the two behaviours that
 * text is supposed to buy. A test of only one of them passes for the wrong reason: the
 * behaviours are also satisfied by "no lock at all" (nothing blocks), and the SQL text alone
 * says nothing about what the mode does.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.locking.lock-timeout-ms=" + RoleCountLockTest.TIMEOUT_MS,
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.hamstrack.workspace.RoleCountLockTest$CapturedSql"
})
@AutoConfigureMockMvc
class RoleCountLockTest {

    /** Far below {@link #HOLD_MS}, so "gave up" and "the holder let go" stay separable. */
    static final int TIMEOUT_MS = 250;

    /** How long the competing transaction sits on the workspace row. */
    private static final long HOLD_MS = 4_000;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired RoleCatalog roleCatalog;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate txTemplate;

    /**
     * <strong>The tripwire.</strong> Hibernate's own view of what went to the database, so
     * "the lock is taken" cannot quietly become "a plain SELECT is taken". Asserted on the
     * exact mode too: {@code FOR UPDATE} would satisfy "a lock is emitted" and reintroduce
     * the FK-conflict the next test measures.
     */
    @Test
    void duplicatingARoleLocksTheWorkspaceRowWithForNoKeyUpdate() throws Exception {
        var ws = newWorkspace();

        CapturedSql.reset();
        duplicate(ws, BuiltInRoles.WORKSPACE_MEMBER, "Contractor")
                .andExpect(status().isCreated());
        var locking = CapturedSql.matching("workspaces");

        assertThat(locking)
                .as("no statement against `workspaces` carried a row-lock clause during a "
                    + "duplicate — the count is then unlocked and the cap is advisory again, "
                    + "which is exactly the regression that would otherwise keep the suite "
                    + "green. Statements seen: %s", locking)
                .anyMatch(sql -> sql.toLowerCase().contains("for no key update"));
        assertThat(locking)
                .as("FOR UPDATE conflicts with the FOR KEY SHARE PostgreSQL takes on this "
                    + "very row for every INSERT into the 18 tables that reference it — see "
                    + "the next test, which is the behaviour this string buys")
                .noneMatch(sql -> sql.toLowerCase().matches(".*\\bfor update\\b.*"));
    }

    /**
     * <strong>Mutual exclusion.</strong> One transaction holds the row; a duplication queues
     * behind it and gives up on its own bound with the documented retryable 409. Without a
     * lock the duplication would sail past — so this is what makes the cap exact.
     */
    @Test
    @Timeout(60)
    void aSecondDuplicationQueuesBehindTheLockAndGivesUpOnTheBound() throws Exception {
        var ws = newWorkspace();

        var holding = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var holder = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(s -> {
                    // Exactly the statement RoleService.duplicate takes first.
                    workspaceRepository.lockForRoleCount(ws.workspace().getId());
                    holding.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {   // NOSONAR — re-asserted on the main thread
                failure.set(t);
            }
        }, "role-count-lock-holder");
        holder.start();
        assertThat(holding.await(20, TimeUnit.SECONDS))
                .as("the competing transaction never reached its lock")
                .isTrue();

        var startedAt = System.nanoTime();
        var response = mockMvc.perform(duplicateRequest(ws, BuiltInRoles.WORKSPACE_MEMBER, "Late"))
                .andExpect(status().isConflict())
                .andReturn().getResponse();
        var waitedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(response.getHeader("Retry-After"))
                .as("a lost lock race is the one 409 whose whole contract is 'try again'")
                .isEqualTo("1");
        assertThat(waitedMs)
                .as("it returned in %d ms, faster than the %d ms bound — then it never queued "
                    + "on the lock and this test proves nothing", waitedMs, TIMEOUT_MS)
                .isGreaterThanOrEqualTo(TIMEOUT_MS - 50L);
        assertThat(waitedMs)
                .as("it waited %d ms and the holder does not let go for %d ms — anything near "
                    + "that means the wait was bounded by the other transaction ending",
                    waitedMs, HOLD_MS)
                .isLessThan(HOLD_MS - 500);

        holder.join();
        assertThat(failure.get()).isNull();
        assertThat(roleRepository.countByWorkspaceIdAndBuiltInFalse(ws.workspace().getId()))
                .as("a refused duplication is not a partial one")
                .isZero();
    }

    /**
     * <strong>…and it does not conflict with what PostgreSQL locks on its own.</strong>
     * Creating a label INSERTs a row whose FK points at {@code workspaces(id)}, for which
     * PostgreSQL takes {@code FOR KEY SHARE} on the parent. Under the round-2
     * {@code FOR UPDATE} that INSERT blocked for the whole time a duplication held the lock —
     * and unlike the duplication it has no {@code lock_timeout} of its own, so in production
     * it would have waited for as long as the holder took, on a pooled connection.
     *
     * <p>The assertion is the elapsed time, and it is deterministic in both directions: the
     * holder releases on a fixed schedule far longer than the bound, so a regression to
     * {@code FOR UPDATE} does not hang the suite — the label is created late and the elapsed
     * assertion fails.
     */
    @Test
    @Timeout(60)
    void holdingTheRoleCountLockDoesNotBlockAnInsertIntoTheWorkspacesChildren() throws Exception {
        var ws = newWorkspace();

        var holding = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var holder = new Thread(() -> {
            try {
                txTemplate.executeWithoutResult(s -> {
                    workspaceRepository.lockForRoleCount(ws.workspace().getId());
                    holding.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {   // NOSONAR — re-asserted on the main thread
                failure.set(t);
            }
        }, "role-count-lock-holder");
        holder.start();
        assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue();

        var startedAt = System.nanoTime();
        mockMvc.perform(post("/api/workspaces/" + ws.workspace().getId() + "/labels")
                        .header("Authorization", "Bearer " + ws.ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"needs-triage\"}"))
                .andExpect(status().isCreated());
        var waitedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(waitedMs)
                .as("creating a label took %d ms while a role-count lock was held, and the "
                    + "holder releases after %d ms — the workspace row is being locked in a "
                    + "mode that conflicts with the FOR KEY SHARE every FK insert takes, "
                    + "which freezes issue, project and label creation across the whole "
                    + "workspace for the duration", waitedMs, HOLD_MS)
                .isLessThan(HOLD_MS / 2);

        holder.join();
        assertThat(failure.get()).isNull();
    }

    /**
     * Outside a transaction Spring Data would open a read-only one of its own and commit it
     * on return, releasing the lock before the count it protects — the silent no-op the other
     * three lock helpers were hardened against in HD-136. Refused loudly instead.
     */
    @Test
    void theRoleCountLockRefusesToRunOutsideACallersTransaction() throws Exception {
        var ws = newWorkspace();
        assertThatThrownBy(() -> workspaceRepository.lockForRoleCount(ws.workspace().getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ==================================================== the inspector

    /**
     * Instantiated by Hibernate from the class name in {@code @SpringBootTest} above, so it
     * must be public with a no-arg constructor and its collection must be static — it is not
     * a Spring bean and the test cannot inject it.
     */
    public static class CapturedSql implements StatementInspector {

        private static final ConcurrentLinkedQueue<String> STATEMENTS = new ConcurrentLinkedQueue<>();

        static void reset() {
            STATEMENTS.clear();
        }

        /** Every captured statement mentioning {@code table}, in emission order. */
        static List<String> matching(String table) {
            return STATEMENTS.stream().filter(sql -> sql.toLowerCase().contains(table)).toList();
        }

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }

    // ==================================================== bootstrap

    private record Ws(Workspace workspace, User owner, String ownerToken) {}

    private Ws newWorkspace() throws Exception {
        var owner = user();
        var ws = new Workspace();
        ws.setName("Locks");
        ws.setSlug("locks-" + UUID.randomUUID().toString().substring(0, 12));
        ws.setCreatedBy(owner);
        workspaceRepository.save(ws);
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(owner);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(m);
        return new Ws(ws, owner, login(owner));
    }

    private org.springframework.test.web.servlet.ResultActions duplicate(
            Ws ws, UUID source, String name) throws Exception {
        return mockMvc.perform(duplicateRequest(ws, source, name));
    }

    private org.springframework.test.web.servlet.RequestBuilder duplicateRequest(
            Ws ws, UUID source, String name) {
        return post("/api/workspaces/" + ws.workspace().getId() + "/roles/" + source + "/duplicate")
                .header("Authorization", "Bearer " + ws.ownerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}");
    }

    private User user() {
        var u = new User();
        u.setEmail(("lock-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName("Lock");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
