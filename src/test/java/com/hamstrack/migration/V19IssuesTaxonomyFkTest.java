package com.hamstrack.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V19__issues_taxonomy_fk.sql} (HD-13) — the two restored foreign keys on
 * {@code issues.type_id} / {@code issues.status_id}, and the one behaviour the migration
 * could plausibly have changed.
 *
 * <p><strong>Why a test and not {@code ddl-auto=validate}.</strong> Hibernate's
 * {@code SchemaValidator} checks tables, columns, column types and sequences. It does
 * <strong>not</strong> validate foreign keys, indexes or check constraints — adding or
 * removing an FK is completely invisible to it. So a green {@code validate} is not evidence
 * that this migration did anything, and the only honest check queries {@code pg_constraint}
 * directly, which is what {@link #theConstraintsExistAreValidatedAndAreNoAction()} does.
 *
 * <p><strong>Why a throwaway schema.</strong> The suite's own database is already migrated
 * to head, so the only way to observe the <em>difference</em> V19 makes — and the whole of
 * the cascade measurement below depends on comparing before with after — is to build the
 * schema from scratch. Flyway points its entire run at {@code v19_migration_test}, no
 * migration in the repository is schema-qualified, and the schema is dropped again
 * afterwards, so this never touches the shared {@code public} schema and needs no
 * {@code CREATE DATABASE} privilege. It also means the constraint-creation order is the
 * <em>real</em> one, which the cascade test depends on.
 *
 * <p>Fixture rows are raw SQL on purpose — the same exception the V11/V18 migration tests
 * take. A stranded reference is by construction something no service can produce.
 */
class V19IssuesTaxonomyFkTest {

    /** Dropped and recreated on every run — never the schema the suite itself uses. */
    private static final String SCHEMA = "v19_migration_test";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    /** PostgreSQL {@code foreign_key_violation}. */
    private static final String FK_VIOLATION = "23503";

    // ===================================================================== AC-1

    /**
     * AC-1. Both constraints exist, both are <em>validated</em> (so PostgreSQL will trust
     * them in planning and will not let an unchecked row through), and both are
     * {@code NO ACTION} on delete and on update.
     *
     * <p>{@code confdeltype = 'a'} is the assertion that matters most and is the easiest to
     * regress silently. {@code 'c'} (CASCADE) there would delete every issue in the product
     * the first time anybody removed a shared status — the exact inversion of what this
     * migration is for, and it would look like a working migration until the day it ran.
     */
    @Test
    void theConstraintsExistAreValidatedAndAreNoAction() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                flyway("19").migrate();
                exec(conn, "SET search_path TO " + SCHEMA);

                assertConstraint(conn, "issues_type_id_fkey", "type_id", "issue_types");
                assertConstraint(conn, "issues_status_id_fkey", "status_id", "statuses");
            } finally {
                dropSchema(conn);
            }
        }
    }

    private void assertConstraint(Connection conn, String name, String column, String parent)
            throws SQLException {
        try (var st = conn.createStatement();
             var rs = st.executeQuery("""
                     SELECT convalidated, confdeltype, confupdtype, condeferrable, conkey,
                            confrelid::regclass::text AS parent, pg_get_constraintdef(oid) AS def
                       FROM pg_constraint
                      WHERE conrelid = '%s.issues'::regclass AND conname = '%s'
                     """.formatted(SCHEMA, name))) {
            assertThat(rs.next())
                    .withFailMessage("%s", name + " does not exist. V19 is the migration that adds it; note that "
                      + "ddl-auto=validate does NOT check foreign keys, so nothing else in the "
                      + "suite would have noticed.")
                    .isTrue();
            assertThat(rs.getBoolean("convalidated"))
                    .withFailMessage("%s", name + " exists but is NOT VALID — existing rows were never checked, so a "
                      + "stranded reference could already be sitting in the table. V19 uses the "
                      + "plain ADD CONSTRAINT form deliberately (HD-93: no rolling deploys).")
                    .isTrue();
            assertThat(rs.getString("confdeltype"))
                    .as("%s", name + " has ON DELETE '" + rs.getString("confdeltype") + "', expected 'a' "
                      + "(NO ACTION). 'c' would CASCADE — deleting one shared status would delete "
                      + "every issue using it, across every tenant.")
                    .isEqualTo("a");
            assertThat(rs.getString("confupdtype")).as("%s", name + " has a non-default ON UPDATE action").isEqualTo("a");
            assertThat(rs.getBoolean("condeferrable"))
                    .withFailMessage("%s", name + " is DEFERRABLE. It must not be: issues_priority_id_fkey is "
                      + "immediate, so deferring these two would fix only two thirds of any "
                      + "project purge while moving genuine violations to commit time.")
                    .isFalse();
            assertThat(parent)
                    .as("%s", name + " points at " + rs.getString("parent") + ", expected " + parent)
                    .isEqualTo(stripSchema(rs.getString("parent")));
            // Single-column, and that is FORCED, not chosen — see V19's header. A composite
            // (id, workspace_id) shape would carry tenancy the way issues_component_fk does,
            // and is impossible here: statuses/issue_types have no workspace_id, and one
            // global catalog row is referenced by issues in every workspace at once.
            assertThat(rs.getString("def"))
                    .as("%s", name + " is not the expected single-column shape: " + rs.getString("def"))
                    .isEqualTo("FOREIGN KEY (" + column + ") REFERENCES " + parent + "(id)");
        }
    }

    // ===================================================================== AC-3

    /**
     * AC-3. The point of the whole ticket: a <em>stranded</em> reference is now impossible.
     * Both directions are checked, because they fail in different places — repointing an
     * issue at a status that does not exist is refused on the child, and deleting a status
     * an issue still uses is refused on the parent.
     *
     * <p>Both are driven as raw SQL rather than through a service, which is the argument for
     * the constraint existing at all: the application already refuses these, and the schema
     * is what makes a path written next year — or a {@code psql} session — refuse them too.
     */
    @Test
    void aStrandedReferenceIsRefusedByTheDatabase() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                flyway("19").migrate();
                exec(conn, "SET search_path TO " + SCHEMA);
                var f = seedFixture(conn);

                // --- child side: point an issue at a catalog row that does not exist -----
                assertFkViolation(conn,
                        "UPDATE issues SET status_id = gen_random_uuid() WHERE id = '" + f.issue() + "'",
                        "an issue could be repointed at a status_id naming no row — it would "
                        + "render as a blank board column, vanish from every status filter, and "
                        + "be unrepairable through the UI, because no screen can name a status "
                        + "that is not there");
                assertFkViolation(conn,
                        "UPDATE issues SET type_id = gen_random_uuid() WHERE id = '" + f.issue() + "'",
                        "an issue could be repointed at a type_id naming no row");

                // --- parent side: delete a catalog row an issue still uses ---------------
                assertFkViolation(conn,
                        "DELETE FROM statuses WHERE id = '" + f.status() + "'",
                        "a status still used by issues could be deleted, stranding them. The "
                        + "admin console remaps first and would never do this; the constraint is "
                        + "here for every path that is not the admin console");
                assertFkViolation(conn,
                        "DELETE FROM issue_types WHERE id = '" + f.type() + "'",
                        "an issue type still used by issues could be deleted, stranding them");
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ===================================================================== AC-7

    /**
     * AC-7 — <strong>measured, not predicted</strong>. The proposal (§4.2) reasoned from
     * PostgreSQL's trigger-firing rules that a project delete <em>might</em> abort with
     * {@code 23503}: {@code statuses.scope_project_id} and {@code issue_types.scope_project_id}
     * both cascade, each cascade runs its child {@code DELETE} through SPI as its own
     * statement, and an immediate {@code NO ACTION} check queued by that inner statement was
     * expected to fire before the sibling {@code issues} cascade had removed the referencing
     * rows. It also predicted the abort was already reachable via {@code priorities}, whose
     * FK has existed since V1.
     *
     * <p><strong>The prediction is falsified, and this test is what falsifies it.</strong>
     * The ordinary shape cascades cleanly, before and after V19. The reason is that the outer
     * {@code DELETE FROM projects} queues all of its {@code AFTER ROW} cascade events up
     * front, and the {@code NO ACTION} check queued by an inner taxonomy {@code DELETE} is
     * appended to the <em>tail</em> of that same queue — so it runs after the {@code issues}
     * cascade whatever order the cascade triggers themselves fire in. Trigger-name/OID order,
     * which the proposal treated as the deciding factor, turns out to be irrelevant to this
     * case.
     *
     * <p>There <em>is</em> one shape that aborts, and it is a narrower and more interesting
     * one: an issue <strong>outside</strong> the deleted project referencing that project's
     * project-scoped catalog row. {@link #aProjectDeleteAbortsOnlyOnAForeignReference()} pins
     * it, and pins that it is not new.
     */
    @Test
    void deletingAProjectWithItsOwnScopedTaxonomyCascadesCleanly() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                flyway("19").migrate();
                exec(conn, "SET search_path TO " + SCHEMA);
                var f = seedFixture(conn);

                conn.setAutoCommit(false);
                try {
                    exec(conn, "DELETE FROM projects WHERE id = '" + f.project() + "'");

                    assertThat(rowsOf(conn, "SELECT count(*) FROM issues WHERE id = '" + f.issue() + "'"))
                            .as("the issues cascade did not run")
                            .isEqualTo(0);
                    assertThat(rowsOf(conn, "SELECT count(*) FROM statuses WHERE id = '" + f.status() + "'"))
                            .as("the project-scoped status was not cascaded away")
                            .isEqualTo(0);
                    assertThat(rowsOf(conn, "SELECT count(*) FROM issue_types WHERE id = '" + f.type() + "'"))
                            .as("the project-scoped issue type was not cascaded away")
                            .isEqualTo(0);
                    assertThat(rowsOf(conn, "SELECT count(*) FROM priorities WHERE id = '" + f.priority() + "'"))
                            .as("the project-scoped priority was not cascaded away")
                            .isEqualTo(0);
                } catch (SQLException e) {
                    throw new AssertionError("""
                            Deleting a project whose own issues use its own project-scoped status, \
                            type and priority raised %s (%s). When HD-13 was built this cascaded \
                            CLEANLY, and V19's header records that measurement as fact. If this \
                            now aborts, the after-trigger queue ordering the measurement relied \
                            on has changed (a PostgreSQL upgrade, or a new constraint on issues), \
                            and V19's comment plus \
                            docs/design/issues-taxonomy-fk-proposal.md 4.2 are both now wrong. \
                            Fix the documents, do not delete this test.""".formatted(
                            e.getSQLState(), e.getMessage()), e);
                } finally {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * AC-7, second half. The one shape that <em>does</em> abort a project delete, and the
     * proof that V19 did not invent it.
     *
     * <p><strong>Where this data comes from, which is the closure worth carrying:</strong> an
     * issue in project B referencing project A's <em>project-scoped</em> catalog row is exactly
     * what the unscoped {@code replaceWithId} lookup in {@code AdminCatalogService} used to be
     * able to mint (proposal §4.3). Delete project A and the taxonomy cascade removes a row
     * project B's issue still points at, so the {@code NO ACTION} check fires for real. That
     * lookup is scoped as of this same ticket, so <strong>there is no longer any API route to
     * the only data shape that can abort a project delete</strong> — the two halves of HD-13
     * turn out to be one fix seen from two ends, and neither is safe to revert alone. What
     * remains reachable is raw SQL and a database restored from before the fix, which is why the
     * constraint and this test still matter after §4.3 is closed.
     *
     * <p>The second half of the test is the part that matters for blame: the same abort happens
     * with V19 <em>rolled back</em>, raised by {@code issues_priority_id_fkey}, which has been
     * in the schema since V1. V19 therefore adds two more constraint names that can report the
     * condition, not a new condition. And deleting the offending issues first makes the project
     * delete succeed — which is the rule V19's header gives an operator and any future
     * project-delete feature.
     */
    @Test
    void aProjectDeleteAbortsOnlyOnAForeignReference() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            dropSchema(conn);
            try {
                flyway("19").migrate();
                exec(conn, "SET search_path TO " + SCHEMA);
                var f = seedFixture(conn);
                var foreign = seedForeignReferrer(conn, f);

                // --- with V19: statuses reports it (lowest RI trigger OID of the three) ---
                assertFkViolation(conn,
                        "DELETE FROM projects WHERE id = '" + f.project() + "'",
                        "deleting a project whose project-scoped taxonomy is referenced from "
                        + "ANOTHER project succeeded. It must not: the taxonomy cascade would "
                        + "remove a row that outside issue still points at");

                // --- without V19, the same abort, from a V1 constraint --------------------
                exec(conn, "ALTER TABLE issues DROP CONSTRAINT issues_type_id_fkey,"
                           + " DROP CONSTRAINT issues_status_id_fkey");
                assertFkViolation(conn,
                        "DELETE FROM projects WHERE id = '" + f.project() + "'",
                        "with V19's two constraints dropped, the same project delete SUCCEEDED — "
                        + "which would mean V19 introduced this failure mode rather than merely "
                        + "adding two more constraint names that can report it. "
                        + "issues_priority_id_fkey has been in the schema since V1 and is what "
                        + "should refuse here");

                exec(conn, "ALTER TABLE issues"
                           + " ADD CONSTRAINT issues_type_id_fkey FOREIGN KEY (type_id) REFERENCES issue_types(id),"
                           + " ADD CONSTRAINT issues_status_id_fkey FOREIGN KEY (status_id) REFERENCES statuses(id)");

                // --- and the documented remedy actually works ----------------------------
                conn.setAutoCommit(false);
                try {
                    exec(conn, "DELETE FROM issues WHERE id = '" + foreign + "'");
                    exec(conn, "DELETE FROM projects WHERE id = '" + f.project() + "'");
                } catch (SQLException e) {
                    throw new AssertionError("""
                            Deleting the referencing issues first did NOT make the project delete \
                            succeed (%s). That is the remedy V19's header prescribes to an \
                            operator purging by hand and to any future project-delete feature; \
                            if it no longer works, the header prescribes an action its reader \
                            cannot perform.""".formatted(e.getSQLState()), e);
                } finally {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ================================================================ the fixture

    private record Fixture(UUID user, UUID workspace, UUID project, UUID status, UUID type,
                           UUID priority, UUID issue) {}

    /**
     * One workspace, one project, a project-scoped status <em>and</em> issue type
     * <em>and</em> priority, and one issue using all three. All three scoped rows matter:
     * each carries its own {@code ON DELETE CASCADE} to {@code projects} and its own
     * {@code NO ACTION} check from {@code issues}, and the cascade measurement is only
     * meaningful if every one of them is in the race.
     */
    private Fixture seedFixture(Connection conn) throws SQLException {
        var user = UUID.randomUUID();
        var workspace = UUID.randomUUID();
        var project = UUID.randomUUID();
        var status = UUID.randomUUID();
        var type = UUID.randomUUID();
        var priority = UUID.randomUUID();
        var issue = UUID.randomUUID();

        exec(conn, """
                INSERT INTO users (id, email, display_name, status)
                VALUES ('%s', 'v19@example.test', 'V19 fixture', 'ACTIVE')
                """.formatted(user));
        exec(conn, """
                INSERT INTO workspaces (id, slug, name, created_by)
                VALUES ('%s', 'v19-fixture', 'V19 fixture', '%s')
                """.formatted(workspace, user));
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, created_by)
                VALUES ('%s', '%s', 'V19 fixture', 'V19', '%s')
                """.formatted(project, workspace, user));
        exec(conn, """
                INSERT INTO statuses (id, name, category, scope_project_id)
                VALUES ('%s', 'V19 Status', 'TODO', '%s')
                """.formatted(status, project));
        exec(conn, """
                INSERT INTO issue_types (id, name, scope_project_id)
                VALUES ('%s', 'V19 Type', '%s')
                """.formatted(type, project));
        exec(conn, """
                INSERT INTO priorities (id, name, scope_project_id)
                VALUES ('%s', 'V19 Priority', '%s')
                """.formatted(priority, project));
        exec(conn, """
                INSERT INTO issues (id, workspace_id, project_id, number, title,
                                    type_id, status_id, priority_id, reporter_id)
                VALUES ('%s', '%s', '%s', 1, 'V19 fixture issue', '%s', '%s', '%s', '%s')
                """.formatted(issue, workspace, project, type, status, priority, user));

        return new Fixture(user, workspace, project, status, type, priority, issue);
    }

    /**
     * A second project holding one issue that points at the FIRST project's project-scoped
     * status, type and priority — the cross-scope shape the admin remap hole used to be able
     * to produce.
     */
    private UUID seedForeignReferrer(Connection conn, Fixture f) throws SQLException {
        var otherProject = UUID.randomUUID();
        var otherIssue = UUID.randomUUID();
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, created_by)
                VALUES ('%s', '%s', 'V19 other', 'V19B', '%s')
                """.formatted(otherProject, f.workspace(), f.user()));
        exec(conn, """
                INSERT INTO issues (id, workspace_id, project_id, number, title,
                                    type_id, status_id, priority_id, reporter_id)
                VALUES ('%s', '%s', '%s', 1, 'points at another project''s scoped taxonomy',
                        '%s', '%s', '%s', '%s')
                """.formatted(otherIssue, f.workspace(), otherProject, f.type(), f.status(),
                f.priority(), f.user()));
        return otherIssue;
    }

    // ================================================================ assertions

    /**
     * Runs {@code sql} in a transaction that is always rolled back, and requires it to fail
     * with SQLSTATE {@code 23503}. Rolled back either way: a statement that unexpectedly
     * <em>succeeds</em> must not leave its effect behind for the next assertion in the same
     * test to trip over.
     */
    private void assertFkViolation(Connection conn, String sql, String whatItMeans)
            throws SQLException {
        conn.setAutoCommit(false);
        try {
            exec(conn, sql);
            throw new AssertionError(
                    "expected SQLSTATE " + FK_VIOLATION + " from: " + sql + "\n  but it succeeded — "
                    + whatItMeans);
        } catch (SQLException e) {
            assertThat(e.getSQLState())
                    .as(() -> "expected SQLSTATE " + FK_VIOLATION + " from: " + sql + "\n  but got "
                      + e.getSQLState() + ": " + e.getMessage())
                    .isEqualTo(FK_VIOLATION);
        } finally {
            conn.rollback();
            conn.setAutoCommit(true);
        }
    }

    // ================================================================ plumbing

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

    private static long rowsOf(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).withFailMessage("expected a row from: " + sql).isTrue();
            return rs.getLong(1);
        }
    }

    /** {@code regclass} renders as {@code schema.table} when the schema is off search_path. */
    private static String stripSchema(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
