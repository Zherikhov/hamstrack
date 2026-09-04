package com.hamstrack.migration;

import com.hamstrack.issue.ComponentTestBase;
import com.hamstrack.notification.entity.Notification;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code V20__notifications_workspace_scope.sql} (HD-135) — the backfill, the backstop
 * delete, the foreign key and its cascade.
 *
 * <p><strong>The hole this file exists to close.</strong> V20 adds a {@code NOT NULL}
 * column to a populated table by parsing the workspace id out of {@code link}. The
 * development database holds no notifications at all and the suite migrates a fresh schema,
 * so in every environment the authors can observe, that {@code UPDATE} and the {@code DELETE}
 * behind it run against <em>zero rows</em> and cannot fail. They would first meet real data
 * in production, unobserved. So the coupling has to be instrumented rather than asserted
 * about: the link format is built in {@code CommentService} (Java) and parsed by a regex in
 * V20 (SQL), the two live in different files in different languages, and nothing in the
 * build relates them.
 *
 * <p><strong>How the coupling is instrumented.</strong> The links below are not literals.
 * {@link #producedLinks()} posts real comments that mention a real member through the real
 * HTTP surface and reads back what the producer actually stored; those strings are then
 * replayed into a schema migrated to V19 and V20 is run over them for real. A hand-written
 * link literal here would test the copy rather than the original and would keep passing
 * through exactly the change that breaks the migration.
 *
 * <p><strong>Why a throwaway schema.</strong> The suite's database is already at head, so
 * the only way to watch V20 <em>happen</em> is to build the schema from scratch. Flyway
 * points its whole run at {@code v20_migration_test} (no migration in the repository is
 * schema-qualified), and the schema is dropped again afterwards — the shared {@code public}
 * schema is never touched and no {@code CREATE DATABASE} privilege is needed. Same shape as
 * {@code V19IssuesTaxonomyFkTest}.
 *
 * <p>Fixture rows in that schema are raw SQL for the same reason V11/V18/V19 use it: a
 * pre-migration row is by construction something no current service can produce.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class V20NotificationsWorkspaceScopeTest extends ComponentTestBase {

    /** Dropped and recreated on every run — never the schema the suite itself uses. */
    private static final String SCHEMA = "v20_migration_test";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    private static final String MENTIONED = "Zoe Quartz";

    // ============================================================ AC-3, and the backstop

    /**
     * AC-3. Two notifications produced by the real mention path in two different workspaces
     * are replayed into a V19 schema and land, after V20, in the workspace whose issue the
     * comment was on — <strong>not merely non-null</strong>. Two workspaces because one
     * cannot distinguish "parses the id" from "assigns the only workspace there is".
     *
     * <p>Three unresolvable rows ride along, and their removal is asserted rather than
     * described in a comment. They are the shapes the backstop {@code DELETE} exists for: a
     * well-formed link naming a workspace that does not exist (the join rejects it — an id
     * that parses is not an id that exists), a {@code NULL} link, and a link whose first path
     * segment is not a UUID at all (the strict regex is what stops {@code ::uuid} raising
     * {@code 22P02} and aborting the deploy). Each must leave, because a row whose workspace
     * cannot be recovered can never be shown again and nothing anywhere holds the information
     * to repair it — and if one stayed, {@code SET NOT NULL} would abort the migration.
     */
    @Test
    void theBackfillAttributesEachRowToTheWorkspaceItsLinkNames() throws Exception {
        var produced = producedLinks();

        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                flyway("19").migrate();
                exec(conn, "SET search_path TO " + SCHEMA);

                var user = seedUser(conn);
                for (var p : produced) seedWorkspace(conn, p.workspaceId(), user);

                var rowA = seedNotification(conn, user, quoted(produced.get(0).link()));
                var rowB = seedNotification(conn, user, quoted(produced.get(1).link()));
                var ghost = seedNotification(conn, user,
                        "'/w/" + UUID.randomUUID() + "/p/" + UUID.randomUUID() + "?issue=7'");
                var noLink = seedNotification(conn, user, "NULL");
                var junk = seedNotification(conn, user, "'/w/not-a-uuid/p/17?issue=7'");

                flyway("20").migrate();

                assertThat(produced.get(0).workspaceId().equals(workspaceOf(conn, rowA))
                       && produced.get(1).workspaceId().equals(workspaceOf(conn, rowB)))
                        .withFailMessage("""
                        V20's backfill did not recover the workspace from a link that \
                        CommentService actually produced. The link format and V20's regex are \
                        one coupling spanning two languages and nothing in the build relates \
                        them — this test is the relation. If the link shape changed, V20 is now \
                        silently deleting every mention notification on any instance that \
                        upgrades; fix the regex (in a NEW migration if V20 has been applied \
                        anywhere) rather than this assertion.""")
                        .isTrue();

                assertThat(exists(conn, ghost))
                        .withFailMessage("a notification whose link names a workspace that does not exist survived "
                          + "the backfill. It cannot have a workspace_id, so SET NOT NULL would "
                          + "abort the deploy — the DELETE is what keeps the migration runnable.")
                        .isFalse();
                assertThat(exists(conn, noLink))
                        .withFailMessage("a notification with a NULL link survived. Nothing can recover its "
                          + "workspace, so it is a permanently invisible row under the new rule.")
                        .isFalse();
                assertThat(exists(conn, junk))
                        .withFailMessage("a notification whose link is not UUID-shaped survived. The strict regex "
                          + "is also what stops ::uuid raising 22P02 and aborting the migration.")
                        .isFalse();
                assertThat(count(conn, "SELECT count(*) FROM notifications"))
                        .as("the backstop DELETE removed rows it should have kept — it is a backstop, "
                          + "not a data-loss plan")
                        .isEqualTo(2);
                assertThat(count(conn, "SELECT count(*) FROM notifications WHERE workspace_id IS NULL"))
                        .as("a row survived with a NULL workspace_id, which SET NOT NULL should have "
                          + "made impossible")
                        .isEqualTo(0);

                // --- the quarantine copy -----------------------------------------
                assertThat(quarantineExists(conn))
                        .withFailMessage("three rows were deleted and notifications_unresolvable_v20 was not "
                          + "created. The copy is the only thing that keeps 'how many, and which "
                          + "ones?' answerable AFTER the deploy — without it the answer exists for "
                          + "exactly as long as the pre-flight window, and an operator who read "
                          + "about this afterwards has nothing to read.")
                        .isTrue();
                assertThat(count(conn, "SELECT count(*) FROM notifications_unresolvable_v20"))
                        .as("the quarantine table does not hold every deleted row. A partial copy is "
                          + "worse than none: it answers the question wrongly.")
                        .isEqualTo(3);
                assertThat(count(conn, "SELECT count(*) FROM notifications_unresolvable_v20 "
                                   + "WHERE id = '" + noLink + "' AND link IS NULL"))
                        .as("the NULL-link row was deleted but not quarantined — the shapes hardest "
                          + "to diagnose are exactly the ones the copy exists for")
                        .isEqualTo(1);
                assertThat(count(conn, "SELECT count(*) FROM notifications_unresolvable_v20 "
                                   + "WHERE body = 'excerpt'"))
                        .as("the quarantine kept the rows but not their content, so it cannot answer "
                          + "what was lost")
                        .isEqualTo(3);
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ============================================================ AC-2, AC-12

    /**
     * AC-2 and AC-12. {@code ddl-auto=validate} checks tables, columns and types and is
     * <strong>blind to foreign keys and indexes</strong>, so a green startup is no evidence
     * that this half of V20 ran at all; {@code pg_constraint} is the only honest witness.
     *
     * <p>{@code confdeltype = 'c'} is the assertion most easily regressed in silence. A
     * notification whose workspace is gone is unrenderable and unreachable, so {@code 'a'}
     * (NO ACTION) here would make a future workspace purge fail on a table nobody would think
     * to look at — and the cascade half below is measured rather than assumed.
     *
     * <p>The shape is single-column, and <strong>not</strong> for V19's reason: V19's
     * statuses/types could not be composite because their parent has no {@code workspace_id}
     * and one global row is referenced from every tenant. Here the parent <em>is</em>
     * {@code workspaces}, so {@code (workspace_id) → workspaces(id)} already carries tenancy
     * exactly and there is no second tenancy fact to force agreement with.
     */
    @Test
    void theForeignKeyCascadesFromWorkspacesAndTakesNothingElse() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                flyway("20").migrate();
                exec(conn, "SET search_path TO " + SCHEMA);

                assertConstraint(conn);
                assertThat(count(conn, """
                        SELECT count(*) FROM pg_indexes
                         WHERE schemaname = '%s' AND indexname = 'idx_notifications_workspace'
                        """.formatted(SCHEMA)))
                        .as("idx_notifications_workspace is missing. It backs the CASCADE's RI scan "
                          + "and the per-workspace queries the column exists to make possible; it "
                          + "is NOT the index the read filter uses (that is idx_notifications_user).")
                        .isEqualTo(1);

                // --- and a clean install inherits no artefact of a condition it never met ---
                assertThat(quarantineExists(conn))
                        .withFailMessage("notifications_unresolvable_v20 exists on a schema that was migrated from "
                          + "scratch with no notifications in it. The copy has to be conditional on "
                          + "the count: an unconditional CREATE leaves every clean install with a "
                          + "permanent empty table documenting nothing, which is how a backstop "
                          + "turns into schema noise.")
                        .isFalse();

                // --- cascade, measured -------------------------------------------
                var user = seedUser(conn);
                var doomed = UUID.randomUUID();
                var kept = UUID.randomUUID();
                seedWorkspace(conn, doomed, user);
                seedWorkspace(conn, kept, user);
                var doomedRow = seedNotification(conn, user, "NULL", doomed);
                var keptRow = seedNotification(conn, user, "NULL", kept);

                conn.setAutoCommit(false);
                try {
                    exec(conn, "DELETE FROM workspaces WHERE id = '" + doomed + "'");
                    assertThat(exists(conn, doomedRow))
                            .withFailMessage("deleting a workspace left its notifications behind")
                            .isFalse();
                    assertThat(exists(conn, keptRow))
                            .withFailMessage("deleting one workspace took another workspace's notifications with "
                              + "it — the cascade is following the wrong column")
                            .isTrue();
                    assertThat(count(conn, "SELECT count(*) FROM users WHERE id = '" + user + "'"))
                            .as("the cascade reached the recipient's account. A notification's "
                              + "workspace and its user are separate parents.")
                            .isEqualTo(1);
                } finally {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            } finally {
                dropSchema(conn);
            }
        }
    }

    private void assertConstraint(Connection conn) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("""
                     SELECT convalidated, confdeltype, pg_get_constraintdef(oid) AS def
                       FROM pg_constraint
                      WHERE conrelid = '%s.notifications'::regclass
                        AND conname = 'notifications_workspace_id_fkey'
                     """.formatted(SCHEMA))) {
            assertThat(rs.next())
                    .withFailMessage("notifications_workspace_id_fkey does not exist. V20 adds it, and "
                      + "ddl-auto=validate does not check foreign keys, so nothing else in the "
                      + "suite would have noticed its absence.")
                    .isTrue();
            assertThat(rs.getBoolean("convalidated"))
                    .withFailMessage("the constraint is NOT VALID — existing rows were never checked, so a "
                      + "notification pointing at no workspace could already be sitting there")
                    .isTrue();
            assertThat(rs.getString("confdeltype"))
                    .as("ON DELETE is '" + rs.getString("confdeltype") + "', expected 'c' (CASCADE). "
                      + "A notification whose workspace is gone is unrenderable and unreachable, so "
                      + "NO ACTION here would make a future workspace purge fail on a table nobody "
                      + "would think to look at. Cascade is the norm for children of workspaces but "
                      + "it is NOT universal — issues.workspace_id deliberately does not cascade — "
                      + "which is why this row's choice is asserted rather than inherited from a "
                      + "category.")
                    .isEqualTo("c");
            assertThat(("FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE"))
                    .as("not the expected single-column shape: " + rs.getString("def"))
                    .isEqualTo(rs.getString("def"));
        }
    }

    // ============================================================ the real producer

    /** One workspace id and the link the producer actually wrote for it. */
    private record Produced(UUID workspaceId, String link) {}

    /**
     * Two mention notifications, in two workspaces, raised the way the product raises them:
     * a member is added, a comment mentioning them is posted over HTTP, and the row the
     * producer stored is read back. Everything this test knows about the link format it
     * learned from that row.
     */
    private List<Produced> producedLinks() throws Exception {
        var a = newProject();
        var b = newProject();
        var recipient = user();
        recipient.setDisplayName(MENTIONED);
        recipient = userRepository.save(recipient);

        for (var ctx : List.of(a, b)) {
            member(workspaceRepository.findById(ctx.wsId()).orElseThrow(), recipient, "MEMBER");
            projectMember(projectRepository.findById(ctx.projectId()).orElseThrow(), recipient, "MEMBER");
            long number = createIssue(ctx, "Something to discuss").get("number").asLong();
            mockMvc.perform(post(commentsBase(ctx, number))
                            .header("Authorization", "Bearer " + ctx.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"@" + MENTIONED + " please take a look\"}"))
                    .andExpect(status().isCreated());
        }

        var recipientId = recipient.getId();
        var produced = txTemplate.execute(s -> entityManager.createQuery(
                        "SELECT n FROM Notification n WHERE n.user.id = :uid ORDER BY n.createdAt",
                        Notification.class)
                .setParameter("uid", recipientId)
                .getResultList().stream()
                .map(n -> new Produced(n.getWorkspace().getId(), n.getLink()))
                .toList());

        assertThat(produced)
                .as("the fixture did not produce two mention notifications — nothing below is "
                  + "measuring the producer any more")
                .isNotNull();
        assertThat(produced)
                .as("the fixture did not produce two mention notifications — nothing below is "
                  + "measuring the producer any more")
                .hasSize(2);
        for (var p : produced) {
            assertThat(p.link())
                    .as("the producer stored a notification with no link at all. V20's backfill has "
                      + "nothing to parse for such a row and deletes it, so this is not a test "
                      + "failure to paper over: it means mention notifications written by THIS "
                      + "build would not survive the migration.")
                    .isNotNull();
            assertThat(p.link())
                    .as("the producer stored a notification with no link at all. V20's backfill has "
                      + "nothing to parse for such a row and deletes it, so this is not a test "
                      + "failure to paper over: it means mention notifications written by THIS "
                      + "build would not survive the migration.")
                    .isNotBlank();
        }
        return produced;
    }

    // ============================================================ the V19 fixture

    private UUID seedUser(Connection conn) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO users (id, email, display_name, status)
                VALUES ('%s', 'v20-%s@example.test', 'V20 fixture', 'ACTIVE')
                """.formatted(id, id));
        return id;
    }

    private void seedWorkspace(Connection conn, UUID id, UUID creator) throws SQLException {
        exec(conn, """
                INSERT INTO workspaces (id, slug, name, created_by)
                VALUES ('%s', 'v20-%s', 'V20 fixture', '%s')
                """.formatted(id, id, creator));
    }

    /** A pre-V20 notification row: no workspace_id column exists yet, only {@code link}. */
    private UUID seedNotification(Connection conn, UUID user, String linkLiteral) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO notifications (id, user_id, type, title, body, link)
                VALUES ('%s', '%s', 'MENTIONED', 'Ada Ampere mentioned you', 'excerpt', %s)
                """.formatted(id, user, linkLiteral));
        return id;
    }

    /** A post-V20 notification row, written straight into a named workspace. */
    private UUID seedNotification(Connection conn, UUID user, String linkLiteral, UUID workspace)
            throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO notifications (id, user_id, workspace_id, type, title, body, link)
                VALUES ('%s', '%s', '%s', 'MENTIONED', 'Ada Ampere mentioned you', 'excerpt', %s)
                """.formatted(id, user, workspace, linkLiteral));
        return id;
    }

    private static String quoted(String literal) {
        return "'" + literal.replace("'", "''") + "'";
    }

    // ============================================================ plumbing

    /**
     * The workspace V20 attributed the row to, or {@code null} if the row is no longer there
     * — which is what a link the regex cannot parse looks like from the outside, since such a
     * row is swept by the backstop {@code DELETE}. Returning null rather than asserting here
     * keeps the diagnosis with the caller, whose failure message explains the coupling.
     */
    private UUID workspaceOf(Connection conn, UUID notificationId) throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT workspace_id FROM notifications WHERE id = '" + notificationId + "'")) {
            return rs.next() ? (UUID) rs.getObject(1) : null;
        }
    }

    /** Whether V20 left a quarantine copy behind — it does so only when it deleted something. */
    private boolean quarantineExists(Connection conn) throws SQLException {
        return count(conn, """
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = '%s' AND table_name = 'notifications_unresolvable_v20'
                """.formatted(SCHEMA)) == 1;
    }

    private boolean exists(Connection conn, UUID notificationId) throws SQLException {
        return count(conn, "SELECT count(*) FROM notifications WHERE id = '" + notificationId + "'") == 1;
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private void dropSchema(Connection conn) throws SQLException {
        exec(conn, "SET search_path TO public");
        exec(conn, "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).withFailMessage("expected a row from: " + sql).isTrue();
            return rs.getLong(1);
        }
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }


    private static String commentsBase(Ctx ctx, long number) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
               + "/issues/" + number + "/comments";
    }
}
