package com.hamstrack.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>{@code V27__taxonomy_palette_alignment.sql} (HD-176) replayed against a real pre-V27
 * database that ALREADY HAS TENANT DATA</strong> — which is the only fixture on which this file
 * can prove anything, because everything V27 must NOT do is about rows a customer owns.
 *
 * <h2>What V27 is, and what it is not</h2>
 * It is <strong>palette alignment</strong>: DESIGN.md declares a catalog palette, V1 seeded a
 * different one, and {@code priorities.color} still defaulted to {@code #8B8680}, a warm grey from
 * the retired visual language. It is <strong>not</strong> an accessibility fix, and several of the
 * values it writes measure a <em>lower</em> contrast ratio than the ones they replace — deliberately,
 * because the owner's decision (ADR-0027) is that a stored colour is an identity hue and that the
 * readable foreground is derived from it at render time. So no case below asserts a ratio; asserting
 * one here would encode the opposite decision and quietly re-open it.
 *
 * <h2>Why the file has to exist at all</h2>
 * Nothing in the application notices if a statement in V27 stops matching. {@code ddl-auto=validate}
 * compares tables and columns and looks at neither seed rows nor column defaults; there is no
 * constraint to violate and no index to miss; the SPA renders whatever colour it is handed. A
 * mis-scoped {@code UPDATE} is therefore <em>completely silent</em> — the migration succeeds, the
 * suite is green, and the only symptom is a customer's status having changed colour overnight, on
 * one deploy, with no log line naming the row.
 *
 * <p><strong>{@link #aScopedRowHoldingASeededHexIsUntouched} is the case that matters</strong>
 * (acceptance criterion 18). Its fixture is the shape that defeats every predicate except the right
 * one: a status named exactly {@code In Progress} holding exactly {@code #3B82F6}, i.e. the seed's
 * own name AND the seed's own colour, carried at BOTH tenant scopes because V27's scope predicate
 * is a conjunction and a row at one scope leaves the other conjunct undefended. A statement guarded
 * on colour alone rewrites it. A statement guarded on name and colour rewrites it. Only the scope
 * predicate leaves it standing — and each of the other two guards has a sibling case of its own,
 * {@link #anOperatorsDeliberatelyRecolouredGlobalSeedSurvives} for the colour predicate and
 * {@link #aGlobalSeedRecolouredOntoAnotherSeedsLiteralIsNotSweptUp} for the name predicate, because
 * a single case that only checked the outcome could not say which guard was doing the work. Every
 * predicate this file's header calls load-bearing therefore has a fixture that goes red without it.
 *
 * <p><strong>How it runs</strong> — the shape {@code V23EmailUniquenessMigrationTest} and
 * {@code V26StorageQuotaMigrationTest} established: the suite's own database is already at head, so
 * a migration can only be observed by building a pre-V27 database from scratch in a THROWAWAY
 * SCHEMA of the same database. No migration in this repository is schema-qualified, so Flyway can
 * point a whole run at one; it is dropped before and after every case, so the shared {@code public}
 * schema is never touched. Nothing this file writes outlives it.
 */
class V27TaxonomyPaletteMigrationTest {

    private static final String SCHEMA = "v27_migration_test";

    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:15432/hamstrack");
    private static final String USER = env("DB_USERNAME", "hamstrack");
    private static final String PASSWORD = env("DB_PASSWORD", "hamstrack");

    /** The palette DESIGN.md declares, which is what V27 aligns the global seeds to (§5.3). */
    private static final Map<String, String> ALIGNED_STATUSES = Map.of(
            "To Do", "#667085", "In Progress", "#F79009", "Done", "#0EA5A4");
    private static final Map<String, String> ALIGNED_PRIORITIES = Map.of(
            "Urgent", "#F04438", "High", "#F79009", "Medium", "#EAB308",
            "Low", "#667085", "None", "#667085");
    private static final Map<String, String> ALIGNED_TYPES = Map.of(
            "Bug", "#F04438", "Task", "#3B5BFD", "Story", "#7C6CF5", "Epic", "#12B981");

    /** The one default all three colour columns land on. */
    private static final String DEFAULT_COLOR = "#667085";

    // ============================================================== what it MUST change

    /**
     * <strong>Every global seed ends on its declared hue, and all three column defaults move</strong>
     * (AC 16).
     *
     * <p>The defaults are read from {@code information_schema.columns.column_default} rather than
     * inferred from an inserted row, because that is the only place the DB-level default is visible:
     * the entities carry their own Java initialiser and Hibernate always sends a non-null property,
     * so an INSERT through the application never consults the column default at all. The two are
     * kept equal by hand — {@code validate} compares neither — and the entity half is asserted by
     * {@code AdminCatalogService}'s own tests, not here.
     */
    @Test
    void everyGlobalSeedAndAllThreeDefaultsLandOnTheDeclaredPalette() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt26(conn);
            try {
                flyway("27").migrate();

                assertAligned(conn, "statuses", ALIGNED_STATUSES);
                assertAligned(conn, "priorities", ALIGNED_PRIORITIES);
                assertAligned(conn, "issue_types", ALIGNED_TYPES);

                for (var table : new String[]{"statuses", "priorities", "issue_types"}) {
                    var def = columnDefault(conn, table);
                    assertThat(def != null && def.contains(DEFAULT_COLOR))
                            .withFailMessage(() -> """
                            %s.color still defaults to %s. The column default governs RAW-SQL \
                            writers — a support script, a data import, anything inserting outside \
                            JPA — and priorities.color's old one (#8B8680) is a warm grey from the \
                            RETIRED visual language, i.e. a colour the product no longer uses \
                            anywhere. It is not what the application uses (that is the entity's \
                            field initialiser, kept equal by hand because ddl-auto=validate \
                            compares neither defaults nor widths), which is exactly why nothing \
                            else would ever notice this being wrong."""
                            .formatted(table, def))
                            .isTrue();
                }
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * <strong>The migration writes {@code color} and NOTHING else</strong> (AC 19).
     *
     * <p>Every other column of every global catalog row is snapshotted before and compared after.
     * {@code position} and {@code category} are the two that would be genuinely expensive to get
     * wrong — {@code statuses.category} is authoritative for board grouping and {@code position}
     * orders every picker in the product — and an {@code UPDATE} that touched them would produce no
     * error whatsoever.
     */
    @Test
    void nothingButTheColourMoves() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt26(conn);
            try {
                var before = catalogWithoutColour(conn);

                flyway("27").migrate();

                var after = catalogWithoutColour(conn);
                assertThat(before)
                        .as(() -> """
                        V27 changed something other than a colour. It is a data-only palette \
                        alignment: no id, name, category, icon, position or archived_at may move, \
                        and no row may be created or deleted. Before: %s After: %s"""
                        .formatted(before, after))
                        .isEqualTo(after);
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ============================================================== what it MUST NOT change

    /**
     * <strong>ACCEPTANCE CRITERION 18, AND THE REASON THIS FILE EXISTS.</strong>
     *
     * <p>A scoped status named {@code In Progress} holding {@code #3B82F6} — the seed's own name
     * and the seed's own colour, which is not a contrived collision but the overwhelmingly likely
     * one: an admin duplicating the global status they already use gets both for free. It must come
     * out byte-identical, and the only predicate that achieves that is the scope predicate, because
     * the other two match it exactly.
     *
     * <p><strong>That predicate is a CONJUNCTION, so pinning it takes a row at each scope.</strong>
     * {@code scope_workspace_id IS NULL AND scope_project_id IS NULL} — a workspace-scoped row is
     * refused by the second conjunct alone, and a project-scoped row by the first alone, because
     * <em>a project-scoped row carries {@code scope_workspace_id = NULL} itself</em>
     * ({@code ScopeContext.project} passes null for the workspace id, and {@code
     * ProjectAdminController} is the live door that writes those rows). A fixture holding only
     * workspace-scoped rows therefore proves the first conjunct and says nothing whatsoever about
     * the second: drop {@code AND scope_project_id IS NULL} from every statement and such a suite
     * stays green while every project-private status, priority and issue type in the product is
     * repainted, in every tenant, on every install that upgrades. Hence a sibling at each scope
     * below — and in that order, because when this case fails the rows already asserted are what
     * tells the next reader which conjunct held.
     *
     * <p>The {@code <table>_scope_ck} constraint is not a second line of defence and must not be
     * read as one: it forbids <em>both</em> columns being set, and it says nothing about one being
     * set while the other is null — that state <em>is</em> the ordinary tenant row. These tables
     * carry no {@code workspace_id} column of their own, so the tenancy boundary here is the
     * <strong>pair</strong>, not a column, and neither half of it is spare.
     *
     * <p>The rule underneath is ADR-0022: <em>a migration may correct a value the product chose; it
     * may never correct a value a customer chose.</em> A global row is a product choice — no
     * tenant's console can even reach one, since {@code findByIdAtScope} never matches it — while
     * every scoped row here was picked by a human who is still using it.
     *
     * <p>All three tables carry a colliding row at each scope, because the three sets of statements
     * were written separately and a guard is easy to complete on two of them.
     */
    @Test
    void aScopedRowHoldingASeededHexIsUntouched() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt26(conn);
            try {
                var workspace = workspace(conn);
                var project = project(conn, workspace);
                // Name AND colour identical to a global seed. Only the scope differs.
                var status = workspaceScoped(conn, "statuses", workspace, "In Progress", "#3B82F6");
                var priority = workspaceScoped(conn, "priorities", workspace, "Medium", "#B45309");
                var type = workspaceScoped(conn, "issue_types", workspace, "Task", "#3B82F6");
                // The same three rows one scope down, where scope_workspace_id is NULL by design.
                var projectStatus =
                        projectScoped(conn, "statuses", project, "In Progress", "#3B82F6");
                var projectPriority =
                        projectScoped(conn, "priorities", project, "Medium", "#B45309");
                var projectType = projectScoped(conn, "issue_types", project, "Task", "#3B82F6");

                var statusBefore = rowTuple(conn, "statuses", status);
                var priorityBefore = rowTuple(conn, "priorities", priority);
                var typeBefore = rowTuple(conn, "issue_types", type);
                var projectStatusBefore = rowTuple(conn, "statuses", projectStatus);
                var projectPriorityBefore = rowTuple(conn, "priorities", projectPriority);
                var projectTypeBefore = rowTuple(conn, "issue_types", projectType);

                flyway("27").migrate();

                assertUntouched(conn, "statuses", status, statusBefore);
                assertUntouched(conn, "priorities", priority, priorityBefore);
                assertUntouched(conn, "issue_types", type, typeBefore);
                assertUntouched(conn, "statuses", projectStatus, projectStatusBefore);
                assertUntouched(conn, "priorities", projectPriority, projectPriorityBefore);
                assertUntouched(conn, "issue_types", projectType, projectTypeBefore);
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * <strong>A global seed an operator deliberately recoloured keeps that colour</strong> (AC 17)
     * — the colour predicate, pinned separately from the scope predicate above.
     *
     * <p>This is the DC self-hoster who decided their "Done" should be purple. Nobody else can have
     * done it: a global row is unreachable from every scoped console, so the only writer is the
     * instance operator's system admin, and their edit is as deliberate as an edit gets.
     *
     * <p>The row is also left in the state that makes this file's own guarantee weaker on purpose:
     * after V27 this database holds two aligned statuses and one that is not, which is a consistent
     * and defensible state precisely because every statement is independently guarded.
     */
    @Test
    void anOperatorsDeliberatelyRecolouredGlobalSeedSurvives() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt26(conn);
            try {
                exec(conn, """
                        UPDATE statuses SET color = '#123456'
                         WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
                           AND name = 'Done'
                        """);

                flyway("27").migrate();

                assertThat(globalColour(conn, "statuses", "Done"))
                        .as("""
                        a global seed an operator had already recoloured was overwritten. Every \
                        UPDATE in V27 is guarded on the EXACT V1 literal for this reason: a row \
                        holding anything else was changed by the one party who can change it — the \
                        instance operator's system admin, since no tenant console can reach a \
                        global row — and re-imposing the product's choice over that is the \
                        migration rewriting a decision it did not make.""")
                        .isEqualTo("#123456");
                assertThat(globalColour(conn, "statuses", "To Do"))
                        .as("""
                        the untouched siblings must still align. A partially-edited database has \
                        to end in a defensible state EITHER WAY, which is what independently \
                        guarded statements buy — one operator edit must not disable the rest of \
                        the alignment.""")
                        .isEqualTo("#667085");
            } finally {
                dropSchema(conn);
            }
        }
    }

    /**
     * <strong>A global seed an operator recoloured onto ANOTHER seed's literal is not swept up by
     * that other seed's statement</strong> — the name predicate, pinned by its own case.
     *
     * <p>The fixture is the only shape that can distinguish that predicate from decoration: the
     * global {@code Low} pre-set to {@code #B45309}, which is V1's {@code Medium}. The Medium
     * statement's scope predicate matches that row and its colour predicate matches that row, so
     * the name is the single thing stopping it — drop {@code AND name = 'Medium'} and Low comes out
     * {@code #EAB308}, an operator's deliberate choice replaced by a colour meant for a different
     * row entirely.
     *
     * <p>Note what this case does <em>not</em> claim. On a stock V1 database the predicate changes
     * no outcome at all: no two global seeds share a hex within any one table, and an {@code UPDATE}
     * names one table, so a cross-table collision cannot arise however the hexes fall. It is
     * defence in depth against an operator edit, and it is pinned here because a reader is
     * otherwise entitled to conclude it does nothing and delete it.
     */
    @Test
    void aGlobalSeedRecolouredOntoAnotherSeedsLiteralIsNotSweptUp() throws Exception {
        try (var conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            freshAt26(conn);
            try {
                // The operator repainted Low with the literal V1 gave Medium.
                exec(conn, """
                        UPDATE priorities SET color = '#B45309'
                         WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
                           AND name = 'Low'
                        """);

                flyway("27").migrate();

                assertThat(globalColour(conn, "priorities", "Low"))
                        .as("""
                        the 'Medium' statement repainted 'Low'. Both rows are global and both held \
                        #B45309, so the scope and colour predicates matched each of them — only \
                        AND name = 'Medium' keeps one statement to one row. The row it hit was an \
                        operator's deliberate edit (a global row is unreachable from every tenant \
                        console), and it is now holding a colour the palette declares for a \
                        different priority, with nothing in the application able to notice.""")
                        .isEqualTo("#B45309");
                assertThat(globalColour(conn, "priorities", "Medium"))
                        .as("""
                        'Medium' itself did not align. The name predicate must narrow a statement \
                        to its own row, not disable it.""")
                        .isEqualTo("#EAB308");
            } finally {
                dropSchema(conn);
            }
        }
    }

    // ============================================================== assertions

    private void assertAligned(Connection conn, String table, Map<String, String> expected)
            throws SQLException {
        for (var e : expected.entrySet()) {
            var actual = globalColour(conn, table, e.getKey());
            assertThat(actual)
                    .as(() -> """
                    global %s '%s' is %s, expected %s. THE VALUE IS NOT A CONTRAST TARGET — several \
                    of these measure WORSE than the V1 colours they replace, and that is the \
                    decision (ADR-0027: the stored colour is an identity hue and the readable ink \
                    is derived from it at render time), not a defect. THIS SEED OWES NO RATIO AT \
                    ALL: the swatch's 3:1 hairline is derived from the hue by ringOn at render \
                    time, exactly like the ink, so a value that measures 1.92 on its own is as \
                    correct as one that measures 5. What it owes DESIGN.md is being the hue that \
                    document declares. If you are here because a colour "looks unreadable", the \
                    fix belongs in the renderer."""
                    .formatted(table, e.getKey(), actual, e.getValue()))
                    .isEqualTo(e.getValue());
        }
    }

    /**
     * The scoped row comes out of the migration exactly as it went in — every column, not just
     * {@code color}, so "byte-identical" is what is actually checked and a statement that dragged a
     * position or a category along with the colour is caught by the same case.
     */
    private void assertUntouched(Connection conn, String table, UUID id, String before)
            throws SQLException {
        var after = rowTuple(conn, table, id);
        assertThat(before)
                .as(() -> """
                A CUSTOMER'S %s WAS CHANGED BY V27. The row is tenant-scoped and carries the same \
                name AND the same hex as a global seed, so the name predicate and the colour \
                predicate both match it — the SCOPE predicate \
                (scope_workspace_id IS NULL AND scope_project_id IS NULL) is the only thing \
                standing between this migration and a tenant's data, and it is missing or wrong on \
                the statement for this table. THAT PREDICATE IS A CONJUNCTION AND NEITHER HALF IS \
                SPARE: a workspace-scoped row is turned away by the second conjunct and a \
                project-scoped row by the first, since a project-scoped row carries \
                scope_workspace_id = NULL of its own. The scope columns are in the tuples below — \
                whichever one is set on this row, the conjunct naming the OTHER column is the one \
                that went missing. Nothing in the application would have told you: there is no \
                constraint to violate (<table>_scope_ck forbids both columns being set and \
                permits exactly the tenant row this case pins), validate does not look at rows, \
                and the only symptom is a customer's colour changing overnight. \
                Before: %s After: %s"""
                .formatted(table, before, after))
                .isEqualTo(after);
    }

    /** Every column of one row, rendered as text — the full tuple {@link #assertUntouched} pins. */
    private String rowTuple(Connection conn, String table, UUID id) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE id = '" + id + "'")) {
            assertThat(rs.next()).withFailMessage("the fixture row disappeared from " + table + " (id " + id + ")").isTrue();
            var meta = rs.getMetaData();
            var tuple = new StringBuilder();
            for (var i = 1; i <= meta.getColumnCount(); i++) {
                tuple.append(meta.getColumnLabel(i)).append('=').append(rs.getString(i)).append('|');
            }
            return tuple.toString();
        }
    }

    // ============================================================== fixture

    /** Rebuilds the throwaway schema at exactly V26 — the moment before HD-176. */
    private void freshAt26(Connection conn) throws SQLException {
        dropSchema(conn);
        flyway("26").migrate();
        exec(conn, "SET search_path TO " + SCHEMA);
        assertThat(globalColour(conn, "statuses", "To Do"))
                .as("""
                the pre-V27 schema does not carry V1's seeded colours, so every assertion below \
                would be measuring something other than this migration.""")
                .isEqualTo("#6B7280");
    }

    private UUID workspace(Connection conn) throws SQLException {
        var user = UUID.randomUUID();
        var workspace = UUID.randomUUID();
        exec(conn, """
                INSERT INTO users (id, email, display_name, status)
                VALUES ('%s', 'v27@example.test', 'V27 fixture', 'ACTIVE')
                """.formatted(user));
        exec(conn, """
                INSERT INTO workspaces (id, slug, name, created_by)
                VALUES ('%s', 'v27-fixture', 'V27 fixture', '%s')
                """.formatted(workspace, user));
        return workspace;
    }

    /**
     * A project to hang project-scoped catalog rows off. It reuses the workspace's creator rather
     * than inventing a second user, because nothing here depends on who made it.
     */
    private UUID project(Connection conn, UUID workspace) throws SQLException {
        var id = UUID.randomUUID();
        exec(conn, """
                INSERT INTO projects (id, workspace_id, name, key, created_by)
                SELECT '%s', '%s', 'V27 fixture', 'V27F', created_by
                  FROM workspaces WHERE id = '%s'
                """.formatted(id, workspace, workspace));
        return id;
    }

    /** A workspace-scoped catalog row — {@code scope_project_id} is left NULL. */
    private UUID workspaceScoped(Connection conn, String table, UUID workspace, String name,
                                 String colour) throws SQLException {
        return scoped(conn, table, "scope_workspace_id", workspace, name, colour);
    }

    /**
     * A project-scoped catalog row — {@code scope_workspace_id} is left NULL, which is the whole
     * point of the sibling cases: this row satisfies the first conjunct of V27's scope predicate on
     * its own, and only {@code AND scope_project_id IS NULL} keeps the migration off it.
     */
    private UUID projectScoped(Connection conn, String table, UUID project, String name,
                               String colour) throws SQLException {
        return scoped(conn, table, "scope_project_id", project, name, colour);
    }

    /**
     * A catalog row at one scope, stamped into EITHER scope column — the two are alternatives, and
     * a row that set both would be refused by {@code <table>_scope_ck}. {@code statuses}
     * additionally needs a category, which is supplied through a table-specific column list rather
     * than by widening every insert.
     */
    private UUID scoped(Connection conn, String table, String scopeColumn, UUID scope, String name,
                        String colour) throws SQLException {
        var id = UUID.randomUUID();
        var extraColumns = "statuses".equals(table) ? ", category" : "";
        var extraValues = "statuses".equals(table) ? ", 'IN_PROGRESS'" : "";
        exec(conn, """
                INSERT INTO %s (id, %s, name, color, position%s)
                VALUES ('%s', '%s', '%s', '%s', 7%s)
                """.formatted(table, scopeColumn, extraColumns, id, scope, name, colour,
                              extraValues));
        return id;
    }

    private String globalColour(Connection conn, String table, String name) throws SQLException {
        return scalar(conn, "SELECT color FROM " + table + " WHERE name = '" + name + "'"
                            + " AND scope_workspace_id IS NULL AND scope_project_id IS NULL");
    }

    private String columnDefault(Connection conn, String table) throws SQLException {
        return scalar(conn, "SELECT column_default FROM information_schema.columns"
                            + " WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table
                            + "' AND column_name = 'color'");
    }

    /** Every global catalog row, every column except {@code color} — the AC 19 snapshot. */
    private Map<String, String> catalogWithoutColour(Connection conn) throws SQLException {
        var rows = new LinkedHashMap<String, String>();
        collect(conn, rows, """
                SELECT 'status:' || name, id || '|' || category || '|' || position || '|'
                       || coalesce(archived_at::text, '-')
                  FROM statuses WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
                """);
        collect(conn, rows, """
                SELECT 'priority:' || name, id || '|' || coalesce(icon, '-') || '|' || position
                       || '|' || coalesce(archived_at::text, '-')
                  FROM priorities WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
                """);
        collect(conn, rows, """
                SELECT 'type:' || name, id || '|' || coalesce(icon, '-') || '|' || position || '|'
                       || coalesce(archived_at::text, '-')
                  FROM issue_types WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
                """);
        return rows;
    }

    private static void collect(Connection conn, Map<String, String> into, String sql)
            throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) into.put(rs.getString(1), rs.getString(2));
        }
    }

    // ============================================================== plumbing

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

    /** {@code null} when there is no row. */
    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) return null;
            var value = rs.getString(1);
            assertThat(rs.next()).withFailMessage("expected at most one row from: " + sql).isFalse();
            return value;
        }
    }

    private static String env(String key, String fallback) {
        var value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
