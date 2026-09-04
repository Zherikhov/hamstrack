package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The proof that HD-123 did not change anyone's abilities</strong> — the successor
 * to {@code PermissionParityTest}, renamed in HD-126 (S3) to say what it actually proves
 * (roles-permissions-proposal §15.3, §18).
 *
 * <h2>Why it was renamed, and what changed underneath</h2>
 * The old harness had two columns: "today" and "after". S3 deleted the left one. Its
 * three highest-fidelity predicates were the real {@code ScopeResolver} bean, invoked for
 * real; {@code ScopeResolver}, {@code WorkspaceRole} and {@code ProjectRole} are gone, so
 * that column could only survive as a transcription. And the right column never invoked a
 * call site at all — it asked {@code permissions().has(X)} of the resolver. A table that
 * kept the old name would have claimed to be a call-site regression test while comparing
 * <em>the seed</em> against <em>a copy of the seed's own rules</em>: a tautology in the
 * costume of a safety net, which is worse than no net. This harness has had two blind
 * spots that mattered already; it does not get a third.
 *
 * <p>So the epic's coverage was split in two, and both halves exist:
 * <ul>
 *   <li><strong>this file</strong> — <em>does the built-in role seed reproduce the
 *       pre-HD-123 verdict, at every call site, for every actor archetype?</em> That is
 *       §18's highest-risk assumption and nothing else asserts it. 59 sites × 6
 *       archetypes;</li>
 *   <li><strong>the enforcement suites</strong> — <em>is the permission wired to the
 *       endpoint, and with the shape §10.3 specifies?</em>
 *       {@code IssuePermissionEnforcementTest}, {@code IssueOwnGrantAndDoorTest},
 *       {@code ProjectMembershipGuardsTest} (S2), and
 *       {@code CommentPermissionEnforcementTest},
 *       {@code WorkspacePermissionEnforcementTest},
 *       {@code WorkspaceMemberManagementTest} (S3). Those make real requests.</li>
 * </ul>
 * Neither is sufficient alone, and the third leg — that the V14 backfill maps the legacy
 * {@code role} strings onto the right roles — is
 * {@link com.hamstrack.migration.V15RoleBackfillMigrationTest}, which replays the upgrade
 * against a real pre-V13 database.
 *
 * <h2>Why the left column is still an independent oracle</h2>
 * {@link LegacyModel} is a <strong>frozen transcription of deleted code</strong>: the two
 * ordinal ladders and the six predicates that ranked them, each carrying the file and line
 * it was copied from. It is a constant. No production change can move it — only a
 * deliberate edit to this file can, which is exactly the property the old harness got from
 * invoking a bean that was on its way out.
 *
 * <p>And the two columns read <em>different parts</em> of the same model, which is what
 * keeps the comparison from being circular: the left reads a role's {@link
 * com.hamstrack.workspace.service.RoleView#key() identity} and applies the frozen ladder;
 * the right reads its {@link PermissionSet grants}. A seed edit — the failure §18 names,
 * and the one this harness has actually caught twice — moves the right column and not the
 * left.
 *
 * <h2>What it does not assert, stated so nobody assumes otherwise</h2>
 * That any call site checks anything. A row here is green whether or not the service
 * behind it ever calls {@code require(...)}. That was the old {@code PENDING} state's
 * whole point, and rather than keep a two-valued flag that only one reader in three
 * noticed, S3 removed the ambiguity: <em>every</em> row of this table is a statement about
 * the seed, and enforcement is asserted elsewhere, by tests that make requests.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class BuiltInRoleSeedParityTest {

    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired WorkspaceAccessService workspaceAccess;
    @Autowired RoleCatalog roleCatalog;

    private Workspace ws;
    private Project project;

    // ================================================== the frozen legacy model (§18)

    /**
     * <strong>The authorization model Hamstrack had before HD-123, transcribed verbatim
     * and frozen.</strong> This is the left-hand column of the table below and the whole
     * reason it means anything: it cannot be moved by a production change, a seed edit or
     * a refactor — only by someone editing this file on purpose.
     *
     * <p>Every member names the file and line it was copied from, as of commit
     * {@code 7d83b7f} (the last before S1). Those files no longer contain that code; that
     * is the point. <strong>Do not "modernise" anything in here.</strong> A transcription
     * that drifts towards the new model turns 354 assertions into 354 tautologies without
     * a single test going red.
     *
     * <p>Note the inverted-ordinal convention both ladders used: the privileged value sat
     * at ordinal 0, so {@code isAtLeast} compared {@code <=}. Reproduced exactly.
     */
    private static final class LegacyModel {

        /** {@code workspace/entity/WorkspaceRole.java} — OWNER, ADMIN, MEMBER, in that order. */
        private static final List<String> WORKSPACE_LADDER = List.of("OWNER", "ADMIN", "MEMBER");

        /** {@code project/entity/ProjectRole.java} — MANAGER, MEMBER, VIEWER, in that order. */
        private static final List<String> PROJECT_LADDER = List.of("MANAGER", "MEMBER", "VIEWER");

        /**
         * {@code isAtLeast}: {@code this.ordinal() <= required.ordinal()}.
         *
         * <p><strong>An unranked key is an error, not a rank.</strong> The transcription
         * is {@code indexOf} rather than a real ordinal, and {@code indexOf} answers
         * {@code -1} for anything the ladder does not contain — which compares as
         * <em>more privileged than OWNER</em>. A frozen oracle that fails open turns a
         * broken fixture into a column of ALLOWs, i.e. into agreement with whatever the
         * seed happens to say, which is the one failure mode this class cannot tolerate.
         * The enums it transcribes could not be handed an unknown value at all; this
         * reproduces that by refusing.
         */
        static boolean isAtLeast(List<String> ladder, String actual, String required) {
            int a = ladder.indexOf(actual);
            int r = ladder.indexOf(required);
            if (a < 0 || r < 0) {
                throw new IllegalArgumentException(
                        "The frozen legacy ladder " + ladder + " cannot rank '"
                        + (a < 0 ? actual : required) + "'. The pre-HD-123 enums could not be "
                        + "handed a value outside themselves, so neither may this transcription "
                        + "— and indexOf would otherwise rank the unknown key ABOVE OWNER, "
                        + "quietly turning this column into ALLOW everywhere.");
            }
            return a <= r;
        }

        private LegacyModel() {
        }
    }

    // ======================================================== the archetypes (§15.3)

    /**
     * @param label       how a failure message names this actor
     * @param wsRole      their workspace role key
     * @param projectRole their explicit {@code project_members} role key, or {@code null}
     *                    for "member of the workspace, not of the project" — which is the
     *                    state of <em>nearly every real user today</em> (§2.3: the SPA has
     *                    no project-members UI, so almost nobody has a row)
     */
    private record Archetype(String label, String wsRole, String projectRole) {}

    private static final Archetype WS_OWNER =
            new Archetype("workspace OWNER, no project row", "OWNER", null);
    private static final Archetype WS_ADMIN =
            new Archetype("workspace ADMIN, no project row", "ADMIN", null);
    private static final Archetype WS_MEMBER =
            new Archetype("workspace MEMBER, no project row", "MEMBER", null);
    private static final Archetype P_MANAGER =
            new Archetype("project MANAGER", "MEMBER", "MANAGER");
    private static final Archetype P_MEMBER =
            new Archetype("project MEMBER", "MEMBER", "MEMBER");
    /**
     * The migrated Viewer. V14 rewrites every existing {@code VIEWER} project row to
     * {@code MEMBER} because a VIEWER row grants <em>everything</em> today (§2.2:
     * {@code isAtLeast(VIEWER)} is true for all three roles), so this archetype is
     * deliberately seeded with the built-in MEMBER — reproducing what a real pre-upgrade
     * VIEWER row becomes. Seeding it as the new, empty VIEWER would be testing a state the
     * migration never produces; that role is asserted head-on by
     * {@link #aFreshlyWrittenProjectViewerGrantsNothingAtAll} instead.
     */
    private static final Archetype P_VIEWER_MIGRATED =
            new Archetype("project VIEWER as migrated (-> MEMBER)", "MEMBER", "MEMBER");

    private static final List<Archetype> ARCHETYPES =
            List.of(WS_OWNER, WS_ADMIN, WS_MEMBER, P_MANAGER, P_MEMBER, P_VIEWER_MIGRATED);

    private final Map<Archetype, User> actors = new LinkedHashMap<>();

    // ============================================================== the table

    /**
     * One authorization site.
     *
     * @param site     {@code File.java} plus what it does — grep-able from the source. Line
     *                 numbers were dropped in S3: they were maintained by hand, drifted on
     *                 every edit, and nothing read them
     * @param today    the gate as it was enforced before HD-123
     * @param becomes  the permission (and ownership qualifier) that replaced it
     * @param legacy   the frozen model's verdict for an actor
     * @param current  the seed's verdict for the same actor
     * @param accepted archetype label → why this cell is <em>expected</em> to diverge.
     *                 Empty for the overwhelming majority; every entry is a decision the
     *                 owner has to have made, not a convenience
     */
    private record Site(String site, String today, String becomes,
                        Predicate<User> legacy, Predicate<User> current,
                        Map<String, String> accepted) {

        Site(String site, String today, String becomes, Predicate<User> legacy, Predicate<User> current) {
            this(site, today, becomes, legacy, current, Map.of());
        }
    }

    @Test
    @Transactional
    void everyAuthorizationSiteKeepsItsVerdictForEveryArchetype() {
        seedFixture();

        var table = table();
        var failures = new ArrayList<String>();
        int cells = 0;

        for (var site : table) {
            for (var archetype : ARCHETYPES) {
                var actor = actors.get(archetype);
                cells++;
                boolean before = site.legacy().test(actor);
                boolean after = site.current().test(actor);
                String reason = site.accepted().get(archetype.label());

                if (before != after && reason == null) {
                    failures.add(("PARITY BROKEN  %s%n"
                            + "    actor      : %s%n"
                            + "    before     : %s -> %s%n"
                            + "    after      : %s -> %s%n"
                            + "    This is the silent failure mode of §18: a control that stops "
                            + "appearing (or a gate that stops biting) with no error anywhere. "
                            + "Either the built-in role's permission set is wrong, or this row's "
                            + "mapping is. It is NOT fixed by editing the frozen legacy column.")
                            .formatted(site.site(), archetype.label(),
                                    site.today(), verdict(before),
                                    site.becomes(), verdict(after)));
                } else if (before == after && reason != null) {
                    failures.add(("STALE DIVERGENCE  %s%n"
                            + "    actor  : %s%n"
                            + "    The table says this cell diverges (%s), but both models now "
                            + "answer %s. Delete the entry — a list of excuses that are no longer "
                            + "true is worse than no list.")
                            .formatted(site.site(), archetype.label(), reason, verdict(before)));
                }
            }
        }

        // A table that silently shrank would make this test pass by testing nothing.
        assertThat(cells).as("cell count mismatch").isEqualTo(table.size() * ARCHETYPES.size());
        assertThat(table)
                .as(() -> "the parity table is now " + table.size() + " sites, not " + SITES + ". The "
                  + "inventory is the ~42 authorization matches across 13 files, expanded to one "
                  + "row per distinct verdict. Rows are not removed until their call site is gone, "
                  + "and a new authorization site needs one.")
                .hasSize(SITES);

        assertThat(failures)
                .as("\n\n" + String.join("\n\n", failures) + "\n\n"
                  + failures.size() + " of " + cells + " (call site x archetype) cells disagree "
                  + "with the pre-HD-123 model.\n")
                .isEmpty();
    }

    /**
     * <strong>The tripwire that replaced the PENDING machinery.</strong>
     *
     * <p>The old harness distinguished rows whose call site had converted from rows that
     * merely described an intention, and watched the unconverted files for the moment they
     * started naming {@link Permission}. S3 converted the last of them, so that flag is
     * gone. What remains worth guarding is the thing the rename was about: this table's
     * left-hand column must stay a <em>frozen transcription</em> and must never quietly
     * become a second read of the live model — which is what would happen if someone
     * "restored" the deleted classes and pointed the predicates back at them.
     */
    @Test
    void theLegacyModelStaysDeletedAndTranscribed() {
        for (var gone : List.of("com.hamstrack.admin.scope.ScopeResolver",
                "com.hamstrack.workspace.entity.WorkspaceRole",
                "com.hamstrack.project.entity.ProjectRole")) {
            assertThat(classExists(gone))
                    .withFailMessage("%s", gone + " is back. HD-126 (§10.4) deleted it because two authorization "
                      + "predicates for one question is the condition HD-123 exists to remove — "
                      + "and because an ordinal ladder cannot rank a custom role, which S4 ships. "
                      + "If it is back for a good reason, this table's left column has to be "
                      + "re-examined too: it is a TRANSCRIPTION of that code, and a transcription "
                      + "that can be replaced by a live read stops being an independent oracle.")
                    .isFalse();
        }
        // The ladders are the transcription's load-bearing half — a reorder inverts every
        // legacy verdict in the table, silently, in the permissive direction for some rows
        // and the restrictive direction for others.
        assertThat(List.of("OWNER", "ADMIN", "MEMBER"))
                .as("the frozen ordinal ladders have been edited. They are a historical record of "
                  + "deleted code, not a model anybody may improve.")
                .isEqualTo(LegacyModel.WORKSPACE_LADDER);
        assertThat(List.of("MANAGER", "MEMBER", "VIEWER"))
                .as("the frozen ordinal ladders have been edited. They are a historical record of "
                  + "deleted code, not a model anybody may improve.")
                .isEqualTo(LegacyModel.PROJECT_LADDER);
    }

    /**
     * <strong>The frozen column must never read the live one</strong> (HD-126 audit).
     *
     * <p>The ladder assertion above guards the ordinals; nothing guarded the <em>predicates</em>,
     * and they are where the tautology actually gets in. Changing one row's {@code legacy}
     * lambda from {@code a -> true} to {@code a -> projectPermissions(a).has(...)} makes that
     * row compare the seed against itself: 6 cells that can never fail again, no compiler
     * warning, no tripwire, green build. Verified by mutation — dropping {@code issue.rank}
     * from the built-in Contributor <em>and</em> pointing that row's frozen predicate at the
     * resolver leaves this whole class passing. It is also the single most likely "fix"
     * somebody reaches for when the table goes red, which is why the failure message above
     * says not to and why this makes saying it stick.
     *
     * <p>So: the live resolver may be called exactly once per row — in the {@code current}
     * column — and {@link #table()} is where that is counted. The two constants move only
     * when a row is added or removed, the same maintenance contract {@link #SITES} already
     * has.
     */
    @Test
    void theFrozenColumnNeverReadsTheLiveModel() throws Exception {
        var source = java.nio.file.Path.of(
                "src/test/java/com/hamstrack/workspace/BuiltInRoleSeedParityTest.java");
        assertThat(java.nio.file.Files.exists(source))
                .withFailMessage("cannot find " + source + " — this tripwire reads the source tree and must run "
                  + "from the project root.")
                .isTrue();
        var text = java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);
        // Both markers are split so they cannot match anything in THIS method: written out
        // whole, the file's first occurrence of each would be the search string itself, and
        // the tripwire would measure a window around its own source and report nonsense.
        // Keep them split, and keep the marker text out of the comments here.
        int from = text.indexOf("private List<" + "Site> table()");
        int to = text.indexOf("private static final String COMMENT_MODERATION" + "_WIDENING", from);
        assertThat(from).as("table() is no longer where this tripwire looks for it.").isGreaterThan(0);
        assertThat(to).as("table() is no longer where this tripwire looks for it.").isGreaterThan(from);
        var body = text.substring(from, to);

        int rows = count(body, "new Site(");
        int resolverCalls = count(body, "wsPermissions(a)") + count(body, "projectPermissions(a)");

        assertThat(rows)
                .as("table() now builds " + rows + " Site expressions, not " + SITE_CONSTRUCTIONS
                  + " (39 literal rows + 2 built in a loop). Update both constants together, and "
                  + "while you are here check the new row's LEFT column is a frozen predicate.")
                .isEqualTo(SITE_CONSTRUCTIONS);
        assertThat(resolverCalls)
                .as("table() calls the live permission resolver " + resolverCalls + " times across "
                  + rows + " rows. It must be called exactly once per row — in the `current` "
                  + "column — except ProjectService.listMembers, whose `current` is the constant "
                  + "`a -> true` (no gate at all). A second call in a row means its FROZEN column "
                  + "now reads the live model, which turns that row's 6 cells into a tautology "
                  + "that can never fail: the left column is a transcription of DELETED code and "
                  + "may only be `a -> true`, `a -> false` or one of the legacy* predicates.")
                .isEqualTo(rows - 1);
    }

    /** 39 rows written out, plus the two built inside {@code for (var caller : …)} loops. */
    private static final int SITE_CONSTRUCTIONS = 41;

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 59 rows: 11 workspace-scoped, 48 project-scoped. */
    private static final int SITES = 59;

    private static String verdict(boolean allowed) {
        return allowed ? "ALLOW" : "DENY";
    }

    /**
     * The seed correction the table above proved necessary, asserted head-on so a
     * regression names itself instead of surfacing as two dozen parity cells.
     *
     * <p>A workspace Owner's bypass is <strong>the curator set and nothing more</strong>.
     * Widening it is a one-word edit in {@code V13} ({@code curate} → {@code administer}),
     * it looks tidier, and it silently opens five gates that are project-MANAGER-only in
     * every install today.
     */
    @Test
    @Transactional
    void theWorkspaceAdminBypassIsTheCuratorSetAndNothingMore() {
        seedFixture();

        for (var archetype : List.of(WS_OWNER, WS_ADMIN)) {
            var actor = actors.get(archetype);
            var workspace = wsPermissions(actor);
            assertThat(workspace.has(Permission.PROJECT_CURATE_ALL))
                    .withFailMessage(() -> String.valueOf(archetype.label() + " lost project.curate.all — that IS the pre-HD-123 "
                      + "requireProjectCurator bypass (16 call sites), so without it a workspace "
                      + "Owner can no longer edit project settings, components, versions or "
                      + "sprints in a project they are not a member of."))
                    .isTrue();
            assertThat(workspace.has(Permission.PROJECT_ADMINISTER_ALL))
                    .withFailMessage(() -> String.valueOf(archetype.label() + " holds project.administer.all. No built-in role may: "
                      + "it grants all 20 project permissions, and the old bypass granted 4. It "
                      + "stays in the catalog for a custom 'Program manager' role (§17.2) and is "
                      + "seeded on nothing."))
                    .isFalse();

            var inProject = projectPermissions(actor);
            for (var p : Permission.projectCuration()) {
                assertThat(inProject.has(p))
                        .withFailMessage(() -> String.valueOf(archetype.label() + " does not hold " + p.key() + " in a project they "
                          + "have no row in. requireProjectCurator let them through."))
                        .isTrue();
            }
            // The gates that were MANAGER-only and must stay shut.
            for (var p : List.of(Permission.PROJECT_ARCHIVE, Permission.PROJECT_MEMBER_MANAGE,
                    Permission.PROJECT_TAXONOMY_MANAGE, Permission.ISSUE_DELETE,
                    Permission.ATTACHMENT_DELETE, Permission.COMMENT_DELETE)) {
                assertThat(inProject.has(p))
                        .withFailMessage(() -> String.valueOf(archetype.label() + " gained unrestricted " + p.key() + " in a project "
                          + "they are not a member of. That is a widening: it was project-MANAGER-"
                          + "only and a workspace Owner with no project_members row was refused "
                          + "it. §18 calls a too-loose mapping the worse failure mode. For "
                          + "comment.delete specifically: moderation is a PROJECT admin's, and "
                          + "deliberately not a workspace admin's (§10.3.5)."))
                        .isFalse();
            }
        }
    }

    /**
     * The other seed correction: {@code LabelService.requireEditor} returned early for the
     * label's creator <em>before</em> the workspace role was consulted, so a plain member
     * could already rename a label they made. Member holds {@code label.manage} own-only,
     * and the pair of assertions below is the proof that one key with two arities expresses
     * "rename yes, archive no" exactly — no catalog split needed. Enforced end to end by
     * {@code WorkspacePermissionEnforcementTest}.
     */
    @Test
    @Transactional
    void aWorkspaceMemberMayEditTheirOwnLabelAndStillNotCurateIt() {
        seedFixture();
        var member = wsPermissions(actors.get(WS_MEMBER));

        assertThat(member.has(Permission.LABEL_MANAGE, true))
                .withFailMessage("a workspace Member cannot rename a label they created. They could before "
                  + "HD-123 (LabelService.requireEditor short-circuited for the creator), so this "
                  + "is a silent capability loss on upgrade — the §18 failure mode.")
                .isTrue();
        assertThat(member.has(Permission.LABEL_MANAGE))
                .withFailMessage("a workspace Member holds label.manage UNRESTRICTED. The grant must be own-only: "
                  + "renaming someone else's label was ADMIN-only.")
                .isFalse();
        assertThat(member.has(Permission.LABEL_MANAGE, false))
                .withFailMessage("the curator sites (archive/unarchive/merge/delete) ask for the unrestricted "
                  + "grant with no ownership argument, and an own-only grant must never satisfy "
                  + "one. If this fails, `own` has stopped meaning what §6.4 says it means.")
                .isFalse();
    }

    /**
     * <strong>The empty Viewer, and why it is not a parity archetype</strong> (HD-125
     * review, M1).
     *
     * <p>{@link #P_VIEWER_MIGRATED} is seeded as Contributor because that is what V14 makes
     * of an existing {@code VIEWER} row. The review asked, correctly, what happens to a
     * <em>freshly written</em> one: the built-in role keyed {@code VIEWER} grants nothing,
     * so a row carrying it is a state the table pretended could not exist.
     *
     * <p>It cannot be an archetype, though. Its whole purpose is to differ from the legacy
     * model — {@code isAtLeast(VIEWER)} was true for everybody — so adding it to
     * {@link #ARCHETYPES} would mean declaring a divergence on nearly every row, which
     * would invert what this table means. The honest split is: assert its semantics
     * head-on here, assert that {@code POST /projects/{p}/members} cannot write one in
     * {@code ProjectMembershipGuardsTest}, and assert what it can actually do over HTTP in
     * {@code IssuePermissionEnforcementTest} and {@code CommentPermissionEnforcementTest} —
     * which is where a Viewer and a Commenter are exercised against real endpoints.
     */
    @Test
    @Transactional
    void aFreshlyWrittenProjectViewerGrantsNothingAtAll() {
        seedFixture();
        var viewer = roleCatalog.view(BuiltInRoles.PROJECT_VIEWER).permissions();

        assertThat(viewer.isEmpty())
                .withFailMessage("the built-in project Viewer grants " + viewer + ". It must grant NOTHING (§7.2): "
                  + "'read-only' is the one thing the legacy ladder could not express, and it is "
                  + "why VIEWER changes meaning under this epic.")
                .isTrue();
        for (var p : List.of(Permission.ISSUE_EDIT, Permission.ISSUE_TRANSITION,
                Permission.ISSUE_ASSIGNABLE, Permission.COMMENT_CREATE)) {
            assertThat(viewer.hasAtAll(p)).withFailMessage(() -> "the built-in Viewer holds " + p.key()).isFalse();
        }
        // Not even as an own-only grant: `own` narrows a grant, it does not create one.
        assertThat(viewer.has(Permission.ISSUE_EDIT, true))
                .withFailMessage("the built-in Viewer may edit its own issues — `own` is a qualifier on a grant "
                  + "the role holds, never a grant of its own (§6.4).")
                .isFalse();
    }

    // ============================================================ the sites

    private List<Site> table() {
        var t = new ArrayList<Site>();

        // ---------------------------------------------------------- workspace scope (§10.1)

        t.add(new Site("WorkspaceService.inviteMember",
                "isAtLeast(ADMIN)", "workspace.member.manage",
                a -> legacyWorkspaceRoleAtLeast(a, "ADMIN"),
                a -> wsPermissions(a).has(Permission.WORKSPACE_MEMBER_MANAGE)));

        // The grant ceiling ("OWNER is never grantable, and nobody grants above their own
        // role") is DELIBERATELY ABSENT from this table. §10.1 classifies it as a
        // constraint on assignment, not a permission: it compares the requested role
        // against the actor's, which no single permission can express — and after S3 it is
        // set containment plus the built-in-Owner guardrail. WorkspaceMemberManagementTest
        // owns it.

        t.add(new Site("ProjectService.create (create a project)",
                "any workspace member", "project.create",
                a -> true,
                a -> wsPermissions(a).has(Permission.PROJECT_CREATE)));

        t.add(new Site("LabelService.create (create a label)",
                "any workspace member", "label.create",
                a -> true,
                a -> wsPermissions(a).has(Permission.LABEL_CREATE)));

        t.add(new Site("LabelService.requireCurator (archive/unarchive/merge/delete)",
                "isAtLeast(ADMIN)", "label.manage",
                a -> legacyWorkspaceRoleAtLeast(a, "ADMIN"),
                a -> wsPermissions(a).has(Permission.LABEL_MANAGE)));

        t.add(new Site("LabelService.requireEditor — SOMEONE ELSE's label",
                "isAtLeast(ADMIN) or creator; not the creator here", "label.manage",
                a -> legacyWorkspaceRoleAtLeast(a, "ADMIN"),
                a -> wsPermissions(a).has(Permission.LABEL_MANAGE, false)));

        // Δ-FREE SINCE THE V13 SEED FIX. The built-in workspace Member holds
        // `label.manage` OWN-ONLY, so every workspace member keeps the right to rename a
        // label they created — and, because the curator row above asks for the
        // UNRESTRICTED grant, still cannot archive/merge/delete it. Two rows, one key, two
        // arities: that pair is the whole proof that `label.manage:own` expresses the
        // creator right without a catalog split. Seeding Member without it (as §7.1
        // originally read) made this cell DENY — a silent narrowing, §18's failure mode,
        // and what this harness was built to catch.
        t.add(new Site("LabelService.requireEditor — the actor's OWN label",
                "isAtLeast(ADMIN) or creator; the creator here", "label.manage (own)",
                // The creator branch short-circuited before the role was consulted at all,
                // so EVERY workspace member passed this.
                a -> true,
                a -> wsPermissions(a).has(Permission.LABEL_MANAGE, true)));

        t.add(new Site("ScopeResolver.requireWorkspaceAdmin",
                "isAtLeast(ADMIN)", "workspace.taxonomy.manage",
                this::legacyRequireWorkspaceAdmin,
                a -> wsPermissions(a).has(Permission.WORKSPACE_TAXONOMY_MANAGE)));

        for (var caller : List.of(
                "WorkspaceAdminController.scope",
                "ScopedProjectAdminService.workspaceMatrix",
                "ScopedProjectAdminService.workspaceBindingOptions",
                "ScopedProjectAdminService.updateWorkspaceProjectBindings")) {
            t.add(new Site(caller + " -> workspace taxonomy administration",
                    "isAtLeast(ADMIN)", "workspace.taxonomy.manage",
                    this::legacyRequireWorkspaceAdmin,
                    a -> wsPermissions(a).has(Permission.WORKSPACE_TAXONOMY_MANAGE)));
        }

        // ---------------------------------------------------------- project scope (§10.2)

        // requireProjectAdmin: MANAGER only, and it 404'd a caller who was a workspace
        // member but not a project member. Both halves collapse into one boolean here —
        // "did the call succeed" — because the 404-vs-403 question is a separate decision
        // (§10.3.2, now 403) that a parity table cannot express. It is asserted by
        // WorkspacePermissionEnforcementTest instead.
        t.add(new Site("ScopeResolver.requireProjectAdmin",
                "project MANAGER (404 for a non-project-member)", "project.taxonomy.manage",
                this::legacyRequireProjectAdmin,
                a -> projectPermissions(a).has(Permission.PROJECT_TAXONOMY_MANAGE)));

        for (var caller : List.of(
                "ProjectAdminController.scope",
                "ScopedProjectAdminService.projectBindings",
                "ScopedProjectAdminService.projectBindingOptions",
                "ScopedProjectAdminService.updateProjectBindings")) {
            t.add(new Site(caller + " -> project taxonomy administration",
                    "project MANAGER (404 for a non-project-member)", "project.taxonomy.manage",
                    this::legacyRequireProjectAdmin,
                    a -> projectPermissions(a).has(Permission.PROJECT_TAXONOMY_MANAGE)));
        }

        t.add(new Site("ProjectService.update",
                "project MANAGER or workspace OWNER/ADMIN", "project.edit",
                this::legacyRequireProjectCurator,
                a -> projectPermissions(a).has(Permission.PROJECT_EDIT)));

        // Δ-FREE SINCE THE V13 SEED FIX, and this is the row that proves the fix was
        // needed: archiving a project was project-MANAGER-only, and a workspace OWNER with
        // no project row was refused it. Seeding the built-ins with
        // `project.administer.all` opened it for them; `project.curate.all` does not.
        t.add(new Site("ProjectService.archive",
                "requireRole(MANAGER)", "project.archive",
                a -> legacyProjectRoleAtLeast(a, "MANAGER"),
                a -> projectPermissions(a).has(Permission.PROJECT_ARCHIVE)));

        t.add(new Site("ProjectService.unarchive",
                "requireRole(MANAGER)", "project.archive",
                a -> legacyProjectRoleAtLeast(a, "MANAGER"),
                a -> projectPermissions(a).has(Permission.PROJECT_ARCHIVE)));

        t.add(new Site("ProjectService.listMembers",
                "requireRole(VIEWER) — a gate everyone passes", "no gate (§10.3.1)",
                a -> legacyProjectRoleAtLeast(a, "VIEWER"),
                a -> true));

        t.add(new Site("ProjectService.addMember",
                "requireRole(MANAGER)", "project.member.manage",
                a -> legacyProjectRoleAtLeast(a, "MANAGER"),
                a -> projectPermissions(a).has(Permission.PROJECT_MEMBER_MANAGE)));

        t.add(new Site("ProjectService.removeMember",
                "requireRole(MANAGER)", "project.member.manage",
                a -> legacyProjectRoleAtLeast(a, "MANAGER"),
                a -> projectPermissions(a).has(Permission.PROJECT_MEMBER_MANAGE)));

        for (var op : List.of("create", "update", "archive", "delete")) {
            t.add(new Site("ComponentService." + op + " (was requireProjectCurator)",
                    "project MANAGER or workspace OWNER/ADMIN", "component.manage",
                    this::legacyRequireProjectCurator,
                    a -> projectPermissions(a).has(Permission.COMPONENT_MANAGE)));
        }

        for (var op : List.of("create", "update", "release", "unrelease", "archive", "delete")) {
            t.add(new Site("VersionService." + op + " (was requireProjectCurator)",
                    "project MANAGER or workspace OWNER/ADMIN", "version.manage",
                    this::legacyRequireProjectCurator,
                    a -> projectPermissions(a).has(Permission.VERSION_MANAGE)));
        }

        for (var op : List.of("create", "update", "start", "complete", "delete")) {
            t.add(new Site("SprintService." + op + " (was requireProjectCurator)",
                    "project MANAGER or workspace OWNER/ADMIN", "sprint.manage",
                    this::legacyRequireProjectCurator,
                    a -> projectPermissions(a).has(Permission.SPRINT_MANAGE)));
        }

        t.add(new Site("SprintService.addIssues",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("SprintService.removeIssue",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.create",
                "workspace membership only", "issue.create",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_CREATE)));

        // The create path is a door for two more permissions, which §10.2 does not list:
        // POST /issues carrying assigneeId is the same act as POST followed by PATCH
        // assigneeId, and the same goes for sprintId. Gating only the PATCH would leave
        // the bypass SHORTER than the guarded path — the §6.5 failure exactly. Δ-free for
        // the same reason as the rank door: the built-in Contributor holds all three.
        t.add(new Site("IssueService.create — carrying assigneeId (§6.5)",
                "workspace membership only", "issue.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_ASSIGN)));

        t.add(new Site("IssueService.create — carrying sprintId (§6.5)",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.update — plain fields",
                "workspace membership only", "issue.edit",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_EDIT)));

        t.add(new Site("IssueService.update — statusId changes (§10.3.3)",
                "workspace membership only", "issue.transition",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_TRANSITION)));

        t.add(new Site("IssueService.update — assignee changes (§10.3.3)",
                "workspace membership only", "issue.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_ASSIGN)));

        t.add(new Site("IssueService.update — sprintId changes (double door, §6.5)",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.rank",
                "workspace membership only", "issue.rank",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_RANK)));

        // The THIRD door for sprint.assign, which §6.5 does not list: POST
        // /issues/{n}/rank carries sprintId/clearSprint and moves an issue between the
        // backlog and a sprint exactly as the two doors §6.5 does name. Leaving it
        // unchecked would have made the permission bypassable with a one-line curl.
        t.add(new Site("IssueService.rank — sprintId changes (third door, §6.5)",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.delete — someone else's issue",
                "requireProjectRole(MANAGER)", "issue.delete",
                a -> legacyProjectRoleAtLeast(a, "MANAGER"),
                a -> projectPermissions(a).has(Permission.ISSUE_DELETE, false)));

        t.add(new Site("IssueService.delete — the actor's OWN issue",
                "requireProjectRole(MANAGER) — reporting it was irrelevant", "issue.delete (own)",
                a -> legacyProjectRoleAtLeast(a, "MANAGER"),
                a -> projectPermissions(a).has(Permission.ISSUE_DELETE, true)));

        t.add(new Site("IssueService.requireAssignable — the TARGET's side (§6.3)",
                "target is a workspace member", "target holds issue.assignable here",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_ASSIGNABLE)));

        t.add(new Site("AttachmentService.upload",
                "workspace membership only", "attachment.create",
                a -> true,
                a -> projectPermissions(a).has(Permission.ATTACHMENT_CREATE)));

        t.add(new Site("AttachmentService.delete — the actor UPLOADED it",
                "uploader or project MANAGER", "attachment.delete (own)",
                a -> true /* uploader */,
                a -> projectPermissions(a).has(Permission.ATTACHMENT_DELETE, true)));

        t.add(new Site("AttachmentService.delete — someone else's file",
                "uploader or project MANAGER; not the uploader here", "attachment.delete",
                a -> legacyHasProjectRole(a, "MANAGER"),
                a -> projectPermissions(a).has(Permission.ATTACHMENT_DELETE, false)));

        t.add(new Site("CommentService.create",
                "workspace membership only", "comment.create",
                a -> true,
                a -> projectPermissions(a).has(Permission.COMMENT_CREATE)));

        t.add(new Site("CommentService.update — the actor's OWN comment",
                "author only; the author here", "comment.edit (own only, §17.3)",
                a -> true,
                a -> projectPermissions(a).has(Permission.COMMENT_EDIT, true)));

        t.add(new Site("CommentService.update — someone else's comment",
                "author only; not the author here", "comment.edit (never unrestricted)",
                a -> false,
                a -> projectPermissions(a).has(Permission.COMMENT_EDIT, false)));

        t.add(new Site("CommentService.delete — the actor's OWN comment",
                "author only; the author here", "comment.delete (own)",
                a -> true,
                a -> projectPermissions(a).has(Permission.COMMENT_DELETE, true)));

        // The ONLY declared divergence in this table, and the only one the owner has
        // signed off as intended (§10.3.5). Note it is a PROJECT ADMIN's alone: a
        // workspace Owner/Admin with no project row does NOT gain moderation, because
        // `project.curate.all` does not carry `comment.delete`.
        t.add(new Site("CommentService.delete — someone else's comment",
                "author only; nobody could moderate", "comment.delete (unrestricted)",
                a -> false,
                a -> projectPermissions(a).has(Permission.COMMENT_DELETE, false),
                Map.of(P_MANAGER.label(), COMMENT_MODERATION_WIDENING)));

        return t;
    }

    /**
     * §10.3.5 — <strong>shipped in HD-126 (S3)</strong>, and the epic's only accepted
     * divergence.
     *
     * <p>It was a divergence on an unimplemented row until this slice: {@code CommentService}
     * gated deletion on authorship alone, so nobody could moderate anything and the marker
     * described a decision rather than behaviour. S3 converted that call site, so it now
     * describes shipped behaviour — which is why the release note is owed the day this
     * lands, and why {@code CommentPermissionEnforcementTest} asserts it end to end
     * (including its boundary: a Contributor and a workspace Owner with no project row are
     * both still refused).
     */
    private static final String COMMENT_MODERATION_WIDENING =
            "Intended widening, §10.3.5, SHIPPED in HD-126: nobody could delete another person's "
            + "comment before; unrestricted comment.delete is moderation, the #1 request for "
            + "comment permissions. In the release notes; enforced by "
            + "CommentPermissionEnforcementTest.aProjectAdminModeratesAndNobodyElseDoes.";

    // ============================ the frozen predicates (the pre-HD-123 model)

    /** {@code WorkspaceService:106} / {@code LabelService:554}: {@code role.isAtLeast(required)}. */
    private boolean legacyWorkspaceRoleAtLeast(User actor, String required) {
        return LegacyModel.isAtLeast(LegacyModel.WORKSPACE_LADDER, workspaceRoleOf(actor), required);
    }

    /**
     * {@code ProjectService:260-264} and {@code IssueService:969-973}: the explicit row's
     * role, <strong>falling back to VIEWER</strong> when there is none — which is why
     * {@code requireRole(VIEWER)} passed for everybody.
     */
    private boolean legacyProjectRoleAtLeast(User actor, String required) {
        var role = explicitProjectRole(actor);
        return LegacyModel.isAtLeast(LegacyModel.PROJECT_LADDER,
                role == null ? "VIEWER" : role, required);
    }

    /**
     * {@code AttachmentService:261-265}. Subtly different from
     * {@link #legacyProjectRoleAtLeast}: it {@code orElse(false)}d instead of falling back
     * to VIEWER. Same answer for MANAGER, different for anything below it — which is why it
     * gets its own transcription rather than sharing one.
     */
    private boolean legacyHasProjectRole(User actor, String required) {
        var role = explicitProjectRole(actor);
        return role != null && LegacyModel.isAtLeast(LegacyModel.PROJECT_LADDER, role, required);
    }

    /** {@code ScopeResolver:49}: {@code ctx.role().isAtLeast(WorkspaceRole.ADMIN)}. */
    private boolean legacyRequireWorkspaceAdmin(User actor) {
        return legacyWorkspaceRoleAtLeast(actor, "ADMIN");
    }

    /**
     * {@code ScopeResolver:65}: the caller must have a {@code project_members} row
     * ({@code orElseThrow}, a 404) <em>and</em> it must be at least MANAGER. No
     * workspace-admin bypass — that was {@code requireProjectCurator}'s, and the difference
     * between the two is the whole reason both are transcribed.
     */
    private boolean legacyRequireProjectAdmin(User actor) {
        var role = explicitProjectRole(actor);
        return role != null && LegacyModel.isAtLeast(LegacyModel.PROJECT_LADDER, role, "MANAGER");
    }

    /**
     * {@code ScopeResolver:113}: workspace role ≥ ADMIN → pass; else explicit project role
     * ≥ MANAGER → pass; else 403. The 16-call-site curator predicate.
     */
    private boolean legacyRequireProjectCurator(User actor) {
        if (legacyWorkspaceRoleAtLeast(actor, "ADMIN")) return true;
        var role = explicitProjectRole(actor);
        return role != null && LegacyModel.isAtLeast(LegacyModel.PROJECT_LADDER, role, "MANAGER");
    }

    // ==================================================== new predicates (after)

    private PermissionSet wsPermissions(User actor) {
        return workspaceAccess.requireMember(actor, ws.getId()).permissions();
    }

    private PermissionSet projectPermissions(User actor) {
        return workspaceAccess.resolveProject(actor, ws.getId(), project.getId()).permissions();
    }

    // ==================================================== fixture

    private void seedFixture() {
        var creator = user("parity-creator");
        ws = new Workspace();
        ws.setName("Parity");
        ws.setSlug("parity-" + UUID.randomUUID().toString().substring(0, 12));
        ws.setCreatedBy(creator);
        ws = workspaceRepository.save(ws);

        project = new Project();
        project.setWorkspace(ws);
        project.setName("Parity project");
        project.setKey("PAR" + (Math.abs(UUID.randomUUID().hashCode()) % 10000));
        project.setCreatedBy(creator);
        project = projectRepository.save(project);

        for (var archetype : ARCHETYPES) {
            requireSeedIdentity(RoleScope.WORKSPACE, archetype.wsRole());
            if (archetype.projectRole() != null) {
                requireSeedIdentity(RoleScope.PROJECT, archetype.projectRole());
            }
            var u = user(archetype.label());
            var m = new WorkspaceMember();
            m.setWorkspace(ws);
            m.setUser(u);
            m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, archetype.wsRole()));
            workspaceMemberRepository.save(m);
            if (archetype.projectRole() != null) {
                var pm = new ProjectMember();
                pm.setProject(project);
                pm.setUser(u);
                pm.setRole(roleCatalog.reference(RoleScope.PROJECT, archetype.projectRole()));
                projectMemberRepository.save(pm);
            }
            actors.put(archetype, u);
        }
        // The fixture is built through the repositories on purpose: ProjectService.create
        // would make its caller a MANAGER, which is one of the six archetypes and would
        // quietly stop this from testing the "workspace OWNER with NO project row" case —
        // the case that is nearly every real user today (§2.3).
        workspaceMemberRepository.flush();
        projectMemberRepository.flush();
    }

    /**
     * <strong>The one seed edit that could move both columns at once</strong> (HD-126 audit).
     *
     * <p>An archetype names a key; {@code BuiltInRoles} turns it into an id; the fixture
     * writes that id; and the left column reads the key back <em>out of the row</em>. So a
     * seed that swapped two built-ins' identities — key <em>and</em> grants together, e.g. id
     * {@code …002} carrying MEMBER's key and MEMBER's grants — would move both columns in step
     * and leave all 354 cells green while every workspace ADMIN in every install silently
     * became a Member. Verified by mutation: the table alone does not see it.
     *
     * <p>{@code RoleIdsMatchMigrationTest} does see it, and remains the owner of that
     * assertion. This is the local guard that stops <em>this</em> table from quietly reporting
     * parity about roles it has misidentified, because a green parity run is the sentence
     * people quote.
     */
    private void requireSeedIdentity(RoleScope scope, String key) {
        var view = roleCatalog.view(BuiltInRoles.id(scope, key));
        assertThat(view.key())
                .as(() -> "the archetype asks for the built-in " + scope + " role '" + key + "', but the "
                  + "row BuiltInRoles points at carries key '" + view.key() + "'. The constants and "
                  + "V13 have drifted, so this table's LEFT column is describing a different role "
                  + "from the one it seeded — see RoleIdsMatchMigrationTest, which owns this rule.")
                .isEqualTo(key);
        assertThat(view.builtIn())
                .withFailMessage(() -> "the archetype asks for the built-in " + scope + " role '" + key + "', but the "
                  + "row BuiltInRoles points at carries key '" + view.key() + "'. The constants and "
                  + "V13 have drifted, so this table's LEFT column is describing a different role "
                  + "from the one it seeded — see RoleIdsMatchMigrationTest, which owns this rule.")
                .isTrue();
    }

    /** The archetype's role KEY — the identity half of the model, not its grants. */
    private String workspaceRoleOf(User actor) {
        var m = workspaceMemberRepository.findByWorkspaceAndUser(ws, actor).orElseThrow();
        return roleCatalog.view(m.getRole().getId()).key();
    }

    private String explicitProjectRole(User actor) {
        return projectMemberRepository.findByProjectAndUser(project, actor)
                .map(m -> roleCatalog.view(m.getRole().getId()).key())
                .orElse(null);
    }

    private User user(String label) {
        var u = new User();
        u.setEmail(("parity-" + UUID.randomUUID() + "@example.com").toLowerCase());
        u.setDisplayName(label);
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }
}
