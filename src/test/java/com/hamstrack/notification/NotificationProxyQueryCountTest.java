package com.hamstrack.notification;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.notification.entity.Notification;
import com.hamstrack.notification.repository.NotificationRepository;
import com.hamstrack.notification.service.NotificationService;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.UUID;

/**
 * <strong>The bell's feed costs one statement, however many workspaces it spans.</strong>
 *
 * <p>{@code Notification.workspace} is a {@code LAZY @ManyToOne} with no {@code JOIN FETCH}
 * anywhere, and {@code NotificationResponse.of} reads {@code n.getWorkspace().getId()} for
 * every row on the page. Whether that read is free or is a SELECT is not a detail — it is the
 * difference between a fixed-cost feed and one whose cost is the number of distinct workspaces
 * the reader has notifications from, on an endpoint the notification bell calls on every mount
 * for every logged-in user. Two claims in this codebase disagreed about it in review, so it is
 * settled by measurement rather than by reading Hibernate.
 *
 * <p><strong>Why the rows are written in a prior transaction, and why that is the whole
 * design of this test.</strong> A fixture that creates its workspaces and then measures in the
 * same persistence context proves nothing whatsoever: the {@code Workspace} is already
 * managed, so the proxy resolves out of the first-level cache and no SELECT is issued even in
 * the world where the claim is false. That is the most likely way to observe a green result
 * for the wrong reason. So everything below is committed and its persistence context closed
 * before the measurement, and the measured call opens its own read-only transaction with an
 * empty one. The fixture is structurally incapable of supplying the answer.
 *
 * <p>Three workspaces, two rows each, so a leak has somewhere to show up: rows outnumber
 * workspaces, workspaces outnumber one, and the answer cannot be right by coincidence. The
 * assertion is {@code == 1} rather than an upper bound because the interesting question is
 * binary — the feed query, and nothing else. <strong>Do not read the excess as a count of
 * initialisations</strong>: measured against a deliberately broken build (a probe that made
 * the DTO touch {@code getName()}), three workspaces cost <em>one</em> extra statement, not
 * three, because Hibernate batch-fetches the outstanding proxies. The number tells you
 * <em>that</em> initialisation happened, never how much.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class NotificationProxyQueryCountTest {

    private static final int WORKSPACES = 3;
    private static final int ROWS_EACH = 2;

    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired TransactionTemplate txTemplate;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationService notificationService;
    @Autowired RoleCatalog roleCatalog;

    @Test
    void listingTheFeedReadsTheWorkspaceIdOffTheProxyAndCostsOneStatement() {
        var reader = seed();

        // Warm: nothing here is cached across calls (there is no second-level cache), but a
        // first call in a fresh process can prepare statements that are not part of the feed.
        notificationService.list(reader);

        long statements = count(() -> {
            var feed = notificationService.list(reader);
            assert feed.size() == WORKSPACES * ROWS_EACH
                    : "the fixture stopped producing the rows this measures: " + feed.size();
            assert feed.stream().allMatch(r -> r.workspaceId() != null)
                    : "a response came back with no workspaceId, so nothing read the proxy at all "
                      + "and the number below measures the wrong thing";
        });

        assert statements == 1
                : """
                GET /api/notifications took %d statements for %d rows across %d workspaces, not 1. \
                One statement is the feed query; anything above it is Notification.workspace being \
                INITIALISED rather than answered from the proxy's identifier. The excess is not a \
                count of workspaces — Hibernate batch-fetches the outstanding proxies, so three \
                workspaces cost one extra statement, not three. Two places in the codebase \
                document a belief about this — \
                NotificationResponse.of ("reads the id off the LAZY proxy, no SELECT") and \
                Notification.workspace's javadoc ("nothing here wants a JOIN FETCH") — and this \
                number is the only thing that knows which way it actually went. If it has moved, \
                either the entity stopped exposing a getter for its identifier (Hibernate resolves \
                a getter METHOD even under field access and short-circuits getId() through it; \
                lose the Lombok @Getter on the id and the short-circuit goes with it), or \
                hibernate.jpa.compliance.proxy was switched on, which disables the short-circuit \
                by specification. Fix the cause, or switch the DTO to a projection and correct \
                BOTH javadocs — do not just raise this number.\
                """.formatted(statements, WORKSPACES * ROWS_EACH, WORKSPACES);
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A reader who is a member of {@link #WORKSPACES} workspaces with {@link #ROWS_EACH}
     * notifications in each — committed, and its persistence context closed, before the caller
     * measures anything.
     */
    private User seed() {
        return txTemplate.execute(status -> {
            var reader = user();
            var workspaces = new ArrayList<Workspace>();
            for (int w = 0; w < WORKSPACES; w++) {
                var ws = workspace(reader);
                member(ws, reader);
                workspaces.add(ws);
            }
            for (Workspace ws : workspaces) {
                for (int i = 0; i < ROWS_EACH; i++) {
                    var n = new Notification();
                    n.setUser(reader);
                    n.setWorkspace(ws);
                    n.setType("MENTIONED");
                    n.setTitle("Ada Ampere mentioned you");
                    n.setBody("excerpt");
                    n.setLink("/w/" + ws.getId() + "/p/" + UUID.randomUUID() + "?issue=" + i);
                    notificationRepository.save(n);
                }
            }
            return reader;
        });
    }

    private User user() {
        var u = new User();
        u.setEmail(("np-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName("Proxy count");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("NP");
        w.setSlug("np-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User user) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "MEMBER"));
        workspaceMemberRepository.save(m);
    }

    // ------------------------------------------------------------------ plumbing

    private long count(Runnable body) {
        var stats = statistics();
        stats.clear();
        body.run();
        return stats.getPrepareStatementCount();
    }

    private Statistics statistics() {
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }

}
