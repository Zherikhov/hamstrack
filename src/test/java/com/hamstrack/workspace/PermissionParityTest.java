package com.hamstrack.workspace;

import com.hamstrack.admin.scope.ScopeResolver;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.exception.AppException;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;
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

/**
 * <strong>The proof that HD-123 does not change anyone's abilities</strong> — and the
 * harness that makes slices S2 and S3 mechanical (roles-permissions-proposal §15.3, §18).
 *
 * <h2>Why this exists</h2>
 * §18 names the epic's highest-risk assumption: that the built-in roles plus the
 * project-access default reproduce today's effective permissions <em>exactly</em>, at
 * every one of the ~42 authorization sites. The failure mode is silent — a control that
 * stops appearing, with no error and no log — so it cannot be caught by "the suite is
 * green". This test is the tripwire, and it lands in S1 <em>before any call site moves</em>.
 *
 * <h2>What it asserts</h2>
 * For every (call site × actor archetype) cell: <strong>today's predicate and the
 * permission that will replace it return the same verdict</strong>. A cell that disagrees
 * fails the build, naming the file, the line, the archetype and both verdicts — unless the
 * row declares that divergence in {@code accepted}, with a reason. A declared divergence
 * that <em>stops</em> diverging also fails, so the table cannot rot into a list of stale
 * excuses.
 *
 * <h2>What it deliberately does not assert</h2>
 * That the V14 backfill maps the legacy {@code role} strings to the right roles. It
 * cannot: V15 has dropped those columns, so "today's role" is only readable through the
 * migrated {@code role_id}. That half of the proof is
 * {@link com.hamstrack.migration.V15RoleBackfillMigrationTest}, which replays the upgrade
 * against a real pre-V13 database. The two together are the §8.4 no-op proof; neither is
 * sufficient alone.
 *
 * <h2>Fidelity of the "today" column</h2>
 * Where today's gate is a public bean it is <strong>invoked for real</strong>
 * ({@link ScopeResolver}'s three methods). Where it is a private helper the predicate is
 * re-expressed here, verbatim, with the file and line it was copied from — see
 * {@link #legacyProjectRoleAtLeast} and friends. Every such transcription is one line, so
 * a reviewer can diff it against the source by eye.
 *
 * <p><em>The {@code File.java:line} labels are maintained by hand and drift whenever the
 * files above them are edited</em>; they were last re-grepped against the tree at the end
 * of <strong>S2</strong>. They are navigation aids, not assertions — nothing reads them,
 * and a stale one costs a reader ten seconds rather than correctness. Refresh them when a
 * slice moves a call site.
 *
 * <p><strong>After S2 the project-scoped rows are no longer a prediction.</strong> Their
 * {@code current} column is the predicate the service now runs: the legacy column is the
 * thing that has become hypothetical, kept because it is the only remaining statement of
 * what the product did before the epic. Do not delete a row when its call site converts —
 * that is exactly when it starts earning its keep.
 *
 * <h2>Two kinds of row, and the difference matters</h2>
 * Every row is {@link State#LIVE} or {@link State#PENDING}, and a green run means
 * different things for each. A LIVE row compares today's transcribed predicate against
 * <em>code that runs</em>. A PENDING row compares it against the mapping S3 <em>intends</em>
 * to write, so it can only ever prove that the built-in seed and the plan agree — the call
 * site still behaves the old way. The distinction exists because it was missed once: all
 * five {@code CommentService} rows were green, including the table's single accepted
 * divergence (unrestricted {@code comment.delete}), which read as "moderation ships" while
 * no code implemented it and comment creation was ungated entirely. A safety net that
 * reports success for code that does not exist is worse than no net.
 * {@link #pendingRowsStillDescribeCodeThatHasNotBeenWrittenYet} keeps the two honest.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@SuppressWarnings("deprecation") // the whole point of this test is the legacy predicates
class PermissionParityTest {

    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired WorkspaceAccessService workspaceAccess;
    @Autowired ScopeResolver scopeResolver;
    @Autowired RoleCatalog roleCatalog;

    private Workspace ws;
    private Project project;

    // ======================================================== the archetypes (§15.3)

    /**
     * @param label      how a failure message names this actor
     * @param wsRole     their workspace role
     * @param projectRole their explicit {@code project_members} role, or {@code null} for
     *                   "member of the workspace, not of the project" — which is the state
     *                   of <em>nearly every real user today</em> (§2.3: the SPA has no
     *                   project-members UI, so almost nobody has a row)
     */
    private record Archetype(String label, WorkspaceRole wsRole, ProjectRole projectRole) {}

    private static final Archetype WS_OWNER =
            new Archetype("workspace OWNER, no project row", WorkspaceRole.OWNER, null);
    private static final Archetype WS_ADMIN =
            new Archetype("workspace ADMIN, no project row", WorkspaceRole.ADMIN, null);
    private static final Archetype WS_MEMBER =
            new Archetype("workspace MEMBER, no project row", WorkspaceRole.MEMBER, null);
    private static final Archetype P_MANAGER =
            new Archetype("project MANAGER", WorkspaceRole.MEMBER, ProjectRole.MANAGER);
    private static final Archetype P_MEMBER =
            new Archetype("project MEMBER", WorkspaceRole.MEMBER, ProjectRole.MEMBER);
    /**
     * The migrated Viewer. V14 rewrites every existing {@code VIEWER} project row to
     * {@code MEMBER} because a VIEWER row grants <em>everything</em> today (§2.2:
     * {@code isAtLeast(VIEWER)} is true for all three roles), so this archetype is
     * deliberately seeded with the built-in MEMBER — reproducing what a real pre-upgrade
     * VIEWER row becomes. Seeding it as the new, empty VIEWER would be testing a state the
     * migration never produces.
     */
    private static final Archetype P_VIEWER_MIGRATED =
            new Archetype("project VIEWER as migrated (-> MEMBER)", WorkspaceRole.MEMBER, ProjectRole.MEMBER);

    private static final List<Archetype> ARCHETYPES =
            List.of(WS_OWNER, WS_ADMIN, WS_MEMBER, P_MANAGER, P_MEMBER, P_VIEWER_MIGRATED);

    private final Map<Archetype, User> actors = new LinkedHashMap<>();

    // ============================================================== the table

    /**
     * What a row's {@code current} column actually <em>is</em>, which is not the same
     * question for every row and must not look the same in a report.
     *
     * <p>A green table that mixes the two silently claims verification it does not have —
     * the failure the HD-125 review caught on the {@code CommentService} rows, whose
     * single accepted divergence read as "moderation ships" when moderation is not
     * implemented anywhere. A safety net that reports success for code that does not exist
     * is worse than no net.
     */
    private enum State {
        /**
         * The call site <strong>runs</strong> the {@code current} predicate. The legacy
         * column is the historical transcription; the comparison is a real regression
         * test of live code.
         */
        LIVE,
        /**
         * The call site still runs the <strong>legacy</strong> predicate. {@code current}
         * is a <em>specification</em> of what S3 will make it do, so the comparison proves
         * only that the built-in seed agrees with the intended mapping — never that any
         * code behaves that way. Guarded by
         * {@link #pendingRowsStillDescribeCodeThatHasNotBeenWrittenYet}.
         */
        PENDING
    }

    /**
     * One authorization site.
     *
     * @param site     {@code File.java:line} — grep-able from the source you are migrating
     * @param today    the gate as it is enforced right now
     * @param becomes  the permission (and ownership qualifier) that replaces it
     * @param legacy   today's verdict for an actor
     * @param current  the new model's verdict for the same actor
     * @param accepted archetype label → why this cell is <em>expected</em> to diverge.
     *                 Empty for the overwhelming majority; every entry is a decision the
     *                 owner has to have made, not a convenience
     * @param state    whether {@code current} is live code or a specification
     */
    private record Site(String site, String today, String becomes,
                        Predicate<User> legacy, Predicate<User> current,
                        Map<String, String> accepted, State state) {

        Site(String site, String today, String becomes, Predicate<User> legacy, Predicate<User> current) {
            this(site, today, becomes, legacy, current, Map.of(), State.LIVE);
        }

        Site(String site, String today, String becomes, Predicate<User> legacy, Predicate<User> current,
             Map<String, String> accepted) {
            this(site, today, becomes, legacy, current, accepted, State.LIVE);
        }

        /** This call site has not been converted yet — {@code current} is intent, not code. */
        Site pending() {
            return new Site(site, today, becomes, legacy, current, accepted, State.PENDING);
        }
    }

    @Test
    @Transactional
    void everyAuthorizationSiteKeepsItsVerdictForEveryArchetype() {
        seedFixture();

        var table = table();
        var failures = new ArrayList<String>();
        int cells = 0;
        int verifiedCells = 0;
        int specifiedCells = 0;

        for (var site : table) {
            for (var archetype : ARCHETYPES) {
                var actor = actors.get(archetype);
                cells++;
                if (site.state() == State.LIVE) verifiedCells++; else specifiedCells++;
                boolean before = site.legacy().test(actor);
                boolean after = site.current().test(actor);
                String reason = site.accepted().get(archetype.label());

                if (before != after && reason == null) {
                    failures.add(("%s  %s%n"
                            + "    actor      : %s%n"
                            + "    today      : %s -> %s%n"
                            + "    after      : %s -> %s%n"
                            + "    %s")
                            .formatted(site.state() == State.LIVE
                                            ? "PARITY BROKEN" : "SPECIFICATION MISMATCH",
                                    site.site(), archetype.label(),
                                    site.today(), verdict(before),
                                    site.becomes(), verdict(after),
                                    site.state() == State.LIVE
                                            ? "This is the silent failure mode of §18: a control that "
                                              + "stops appearing (or a gate that stops biting) with no "
                                              + "error anywhere. Either the built-in role's permission "
                                              + "set is wrong, or this row's mapping is."
                                            : "This call site is NOT converted yet, so this compares "
                                              + "the legacy predicate with the INTENDED one. A "
                                              + "mismatch means the built-in seed and the planned "
                                              + "mapping disagree — fix it before the slice that "
                                              + "converts the site, not after."));
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
        assert cells == table.size() * ARCHETYPES.size() : "cell count mismatch";
        assert table.size() >= 40
                : "the parity table has shrunk to " + table.size() + " sites. The inventory is "
                  + "42 authorization matches across 13 files; rows are not to be removed until "
                  + "their call site is gone.";
        // Read this number, not just the green tick: only the LIVE half is a regression
        // test of code that exists. The PENDING half is a specification for S3.
        assert verifiedCells > 0 && specifiedCells > 0
                : "every row is now " + (specifiedCells == 0 ? "LIVE" : "PENDING")
                  + " — if that is real, update this assertion and the class javadoc "
                  + "deliberately rather than letting the distinction rot.";

        assert failures.isEmpty()
                : "\n\n" + String.join("\n\n", failures) + "\n\n"
                  + failures.size() + " of " + cells + " (call site x archetype) cells disagree "
                  + "(" + verifiedCells + " verified against live code, "
                  + specifiedCells + " specifying behaviour not yet implemented).\n";
    }

    /**
     * <strong>The tripwire on the PENDING half of the table</strong> (HD-125 review).
     *
     * <p>Five {@code CommentService} rows — and the workspace-scoped rows with them —
     * describe behaviour <em>nobody has written</em>. That is a deliberate, agreed state:
     * converting {@code CommentService} in S2 would ship the one intended widening
     * (unrestricted {@code comment.delete} = moderation) in a slice whose contract is
     * invisibility, so it belongs to S3/HD-126. What must not happen is the table quietly
     * claiming those rows are verified.
     *
     * <p>So this fails in both directions the state can rot:
     * <ul>
     *   <li>a pending service <strong>has been converted</strong> and its rows were not
     *       promoted to {@link State#LIVE} — the harness would then be reporting a
     *       specification where it could be reporting a regression test;</li>
     *   <li><strong>S3 has landed</strong> ({@code ScopeResolver} is gone, §10.4) while
     *       rows are still pending — i.e. the slice that was supposed to convert them
     *       shipped without doing it, which is exactly how a "we'll do it next slice" row
     *       becomes permanent.</li>
     * </ul>
     *
     * <p><strong>It asserts on the IMPORT, not on a call shape</strong>, and that is the
     * whole reliability of it. The first version looked for the literal
     * {@code "permissions().require"} — which {@code IssueService.update}, the most
     * converted method in this slice, does not contain: it holds the set in a local
     * ({@code var permissions = ctx.permissions();}) first. A tripwire defeated by the
     * house style two files away is not a tripwire. A converted file cannot avoid naming
     * {@link Permission}, and no local-variable trick dodges an import.
     */
    @Test
    void pendingRowsStillDescribeCodeThatHasNotBeenWrittenYet() throws Exception {
        for (var source : PENDING_SOURCES) {
            var path = java.nio.file.Path.of(source);
            assert java.nio.file.Files.exists(path)
                    : "cannot find " + source + " — this tripwire reads the source tree and must "
                      + "run from the project root.";
            assert !java.nio.file.Files.readString(path).contains(PERMISSION_IMPORT)
                    : source + " now names " + PERMISSION_IMPORT + ", so it has been converted and "
                      + "its rows in this table are no longer a specification. Promote them from "
                      + "PENDING to LIVE (remove the file from PENDING_SITE_PREFIXES and "
                      + "PENDING_SOURCES) so the harness starts testing the code instead of "
                      + "describing it — and re-read the accepted divergences while you are there: "
                      + "CommentService.delete's is the moderation WIDENING, which becomes real "
                      + "user-visible behaviour the moment that site converts, and needs a release "
                      + "note the same day.";
        }

        // ProjectService.java cannot be watched as a whole — it is already converted for
        // five of its six rows — so its one remaining PENDING row is watched by method.
        // A file-level assertion would be vacuous here, which is worse than absent.
        assert !methodBody("src/main/java/com/hamstrack/project/service/ProjectService.java",
                "public ProjectResponse create(").contains("PROJECT_CREATE")
                : "ProjectService.create now checks project.create, so its row is live. Drop "
                  + "\"ProjectService.java:43\" from PENDING_SITE_PREFIXES and this assertion.";

        // The split itself is an assertion, so that promoting rows (or adding unconverted
        // ones) is a deliberate edit here rather than a number nobody reads.
        long pending = table().stream().filter(s -> s.state() == State.PENDING).count();
        long live = table().size() - pending;
        assert pending == PENDING_ROWS && live == LIVE_ROWS
                : "the table is now " + live + " LIVE / " + pending + " PENDING, not "
                  + LIVE_ROWS + " / " + PENDING_ROWS + ". If a slice converted call sites, "
                  + "update these two numbers with it; if it did not, a row moved by accident.";

        boolean s3Landed = !classExists("com.hamstrack.admin.scope.ScopeResolver");
        assert !s3Landed
                : "ScopeResolver is gone, so S3 has landed — but rows in this table are still "
                  + "PENDING, meaning S3 shipped without converting the call sites it owns "
                  + "(§10.4 deletes ScopeResolver only once its three methods have become "
                  + "permission checks). Convert them or explain, in the table, why not.";
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * One method's source, from its signature to the first line that closes it at method
     * indentation. Crude on purpose — it is a tripwire, not a parser, and the failure mode
     * of getting it wrong is a slightly larger haystack, never a missed conversion.
     */
    private static String methodBody(String source, String signature) throws java.io.IOException {
        var text = java.nio.file.Files.readString(java.nio.file.Path.of(source));
        int start = text.indexOf(signature);
        assert start >= 0 : signature + " is gone from " + source
                + " — this tripwire watches that method and can no longer find it.";
        int end = text.indexOf("\n    }", start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    /**
     * The split as of S2 (HD-125): 38 rows compare against code that runs, 21 describe
     * what S3 will write. Every slice that converts a call site moves these two numbers,
     * and the assertion above makes that an edit somebody has to mean.
     */
    private static final long LIVE_ROWS = 38;
    private static final long PENDING_ROWS = 21;

    /** What a converted file cannot avoid naming — see the tripwire's javadoc. */
    private static final String PERMISSION_IMPORT = "com.hamstrack.common.security.Permission";

    /**
     * The files behind the {@link State#PENDING} rows — <strong>all</strong> of them, not
     * just the services: the four admin call sites in {@code ScopedProjectAdminService},
     * {@code WorkspaceAdminController} and {@code ProjectAdminController} are separate
     * rows in the table and convert independently of {@code ScopeResolver} itself, so an
     * unwatched one is a row that can go live in silence.
     *
     * <p>Source paths rather than class references because what is being asserted is
     * "this code has not been written", which no API can answer.
     */
    private static final List<String> PENDING_SOURCES = List.of(
            "src/main/java/com/hamstrack/issue/service/CommentService.java",
            "src/main/java/com/hamstrack/issue/service/LabelService.java",
            "src/main/java/com/hamstrack/workspace/service/WorkspaceService.java",
            "src/main/java/com/hamstrack/admin/scope/ScopeResolver.java",
            "src/main/java/com/hamstrack/admin/service/ScopedProjectAdminService.java",
            "src/main/java/com/hamstrack/admin/controller/WorkspaceAdminController.java",
            "src/main/java/com/hamstrack/admin/controller/ProjectAdminController.java");

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
            assert workspace.has(Permission.PROJECT_CURATE_ALL)
                    : archetype.label() + " lost project.curate.all — that IS today's "
                      + "requireProjectCurator bypass (16 call sites), so without it a workspace "
                      + "Owner can no longer edit project settings, components, versions or "
                      + "sprints in a project they are not a member of.";
            assert !workspace.has(Permission.PROJECT_ADMINISTER_ALL)
                    : archetype.label() + " holds project.administer.all. No built-in role may: "
                      + "it grants all 20 project permissions, and today's bypass grants 4. It "
                      + "stays in the catalog for a custom 'Program manager' role (§17.2) and is "
                      + "seeded on nothing.";

            var inProject = projectPermissions(actor);
            for (var p : Permission.projectCuration()) {
                assert inProject.has(p)
                        : archetype.label() + " does not hold " + p.key() + " in a project they "
                          + "have no row in. requireProjectCurator lets them through today.";
            }
            // The five gates that are MANAGER-only today and must stay shut.
            for (var p : List.of(Permission.PROJECT_ARCHIVE, Permission.PROJECT_MEMBER_MANAGE,
                    Permission.PROJECT_TAXONOMY_MANAGE, Permission.ISSUE_DELETE,
                    Permission.ATTACHMENT_DELETE, Permission.COMMENT_DELETE)) {
                assert !inProject.has(p)
                        : archetype.label() + " gained unrestricted " + p.key() + " in a project "
                          + "they are not a member of. That is a widening: it is project-MANAGER-"
                          + "only today and a workspace Owner with no project_members row is "
                          + "refused it. §18 calls a too-loose mapping the worse failure mode.";
            }
        }
    }

    /**
     * The other seed correction: {@code LabelService.requireEditor} returns early for the
     * label's creator <em>before</em> the workspace role is consulted, so a plain member
     * can already rename a label they made. Member holds {@code label.manage} own-only, and
     * the pair of assertions below is the proof that one key with two arities expresses
     * "rename yes, archive no" exactly — no catalog split needed.
     */
    @Test
    @Transactional
    void aWorkspaceMemberMayEditTheirOwnLabelAndStillNotCurateIt() {
        seedFixture();
        var member = wsPermissions(actors.get(WS_MEMBER));

        assert member.has(Permission.LABEL_MANAGE, true)
                : "a workspace Member cannot rename a label they created. They can TODAY "
                  + "(LabelService.java:560-564 short-circuits for the creator), so this is a "
                  + "silent capability loss on upgrade — the §18 failure mode.";
        assert !member.has(Permission.LABEL_MANAGE)
                : "a workspace Member holds label.manage UNRESTRICTED. The grant must be own-only: "
                  + "renaming someone else's label is ADMIN-only today.";
        assert !member.has(Permission.LABEL_MANAGE, false)
                : "the curator sites (archive/unarchive/merge/delete) ask for the unrestricted "
                  + "grant with no ownership argument, and an own-only grant must never satisfy "
                  + "one. If this fails, `own` has stopped meaning what §6.4 says it means.";
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
     * head-on here, and assert separately (in {@code ProjectMembershipGuardsTest}) that
     * {@code POST /projects/{p}/members} cannot write one — because until S4 replaces that
     * DTO with a role id, an operator asking for "Viewer" means the old, permissive one.
     */
    @Test
    @Transactional
    void aFreshlyWrittenProjectViewerGrantsNothingAtAll() {
        seedFixture();
        var viewer = roleCatalog.view(BuiltInRoles.PROJECT_VIEWER).permissions();

        assert viewer.isEmpty()
                : "the built-in project Viewer grants " + viewer + ". It must grant NOTHING (§7.2): "
                  + "'read-only' is the one thing the legacy ladder could not express, and it is "
                  + "why VIEWER changes meaning under this epic.";
        for (var p : List.of(Permission.ISSUE_EDIT, Permission.ISSUE_TRANSITION,
                Permission.ISSUE_ASSIGNABLE, Permission.COMMENT_CREATE)) {
            assert !viewer.hasAtAll(p) : "the built-in Viewer holds " + p.key();
        }
        // Not even as an own-only grant: `own` narrows a grant, it does not create one.
        assert !viewer.has(Permission.ISSUE_EDIT, true)
                : "the built-in Viewer may edit its own issues — `own` is a qualifier on a grant "
                  + "the role holds, never a grant of its own (§6.4).";
    }

    // ============================================================ the sites

    /**
     * Site labels whose call site has <strong>not</strong> been converted yet, marked
     * {@link State#PENDING} on the way out of {@link #table()} rather than row by row —
     * the property belongs to the file, and a per-row marker is one copy-paste away from
     * being wrong. Everything not listed here is live code as of S2 (HD-125).
     *
     * <p>{@code CommentService} is on this list <em>by decision, not by oversight</em>: its
     * {@code delete} row carries the epic's one accepted divergence (unrestricted
     * {@code comment.delete} = moderation, §10.3.5), and converting it would make S2
     * user-visible. It converts in S3/HD-126 together with the workspace-scoped sites.
     */
    private static final List<String> PENDING_SITE_PREFIXES = List.of(
            "WorkspaceService.java",
            "ProjectService.java:43",          // create a project — the only ungated ProjectService row
            "LabelService.java",
            "ScopeResolver.java",
            "WorkspaceAdminController",
            "ProjectAdminController",
            "ScopedProjectAdminService",
            "CommentService.java");

    private List<Site> table() {
        return rows().stream()
                .map(s -> PENDING_SITE_PREFIXES.stream().anyMatch(p -> s.site().startsWith(p))
                        ? s.pending()
                        : s)
                .toList();
    }

    private List<Site> rows() {
        var t = new ArrayList<Site>();

        // ---------------------------------------------------------- workspace scope (§10.1)

        t.add(new Site("WorkspaceService.java:137 (invite a member)",
                "isAtLeast(ADMIN)", "workspace.member.manage",
                a -> legacyWorkspaceRoleAtLeast(a, WorkspaceRole.ADMIN),
                a -> wsPermissions(a).has(Permission.WORKSPACE_MEMBER_MANAGE)));

        // WorkspaceService.java:132 — the grant ceiling ("OWNER is never grantable, and
        // nobody grants above their own role") is DELIBERATELY ABSENT from this table.
        // §10.1 classifies it as a constraint on assignment, not a permission: it compares
        // the requested role against the actor's, which no single permission can express.
        // S3 reimplements it as the §11.2 rule and must test it separately.

        t.add(new Site("ProjectService.java:43 (create a project)",
                "any workspace member", "project.create",
                a -> true,
                a -> wsPermissions(a).has(Permission.PROJECT_CREATE)));

        t.add(new Site("LabelService.java:130 (create a label)",
                "any workspace member", "label.create",
                a -> true,
                a -> wsPermissions(a).has(Permission.LABEL_CREATE)));

        t.add(new Site("LabelService.java:553 requireCurator (archive/unarchive/merge/delete)",
                "isAtLeast(ADMIN)", "label.manage",
                a -> legacyWorkspaceRoleAtLeast(a, WorkspaceRole.ADMIN),
                a -> wsPermissions(a).has(Permission.LABEL_MANAGE)));

        t.add(new Site("LabelService.java:560 requireEditor — SOMEONE ELSE's label",
                "isAtLeast(ADMIN) or creator; not the creator here", "label.manage",
                a -> legacyWorkspaceRoleAtLeast(a, WorkspaceRole.ADMIN),
                a -> wsPermissions(a).has(Permission.LABEL_MANAGE, false)));

        // Δ-FREE SINCE THE V13 SEED FIX. The built-in workspace Member holds
        // `label.manage` OWN-ONLY, so every workspace member keeps the right to rename a
        // label they created — and, because the curator row above asks for the
        // UNRESTRICTED grant, still cannot archive/merge/delete it. Two rows, one key, two
        // arities: that pair is the whole proof that `label.manage:own` expresses today's
        // creator right without a catalog split. Seeding Member without it (as §7.1
        // originally read) made this cell DENY — a silent narrowing, §18's failure mode,
        // and what this harness was built to catch.
        t.add(new Site("LabelService.java:560 requireEditor — the actor's OWN label",
                "isAtLeast(ADMIN) or creator; the creator here", "label.manage (own)",
                // LabelService.java:560-564: the creator branch short-circuits before the
                // role is consulted at all, so today EVERY workspace member passes this.
                a -> true,
                a -> wsPermissions(a).has(Permission.LABEL_MANAGE, true)));

        t.add(new Site("ScopeResolver.java:49 requireWorkspaceAdmin",
                "isAtLeast(ADMIN)", "workspace.taxonomy.manage",
                this::legacyRequireWorkspaceAdmin,
                a -> wsPermissions(a).has(Permission.WORKSPACE_TAXONOMY_MANAGE)));

        for (var caller : List.of(
                "WorkspaceAdminController.java:45",
                "ScopedProjectAdminService.java:51",
                "ScopedProjectAdminService.java:59",
                "ScopedProjectAdminService.java:67")) {
            t.add(new Site(caller + " -> requireWorkspaceAdmin",
                    "isAtLeast(ADMIN)", "workspace.taxonomy.manage",
                    this::legacyRequireWorkspaceAdmin,
                    a -> wsPermissions(a).has(Permission.WORKSPACE_TAXONOMY_MANAGE)));
        }

        // ---------------------------------------------------------- project scope (§10.2)

        // requireProjectAdmin: MANAGER only, and it 404s a caller who is a workspace member
        // but not a project member. Both halves collapse into one boolean here — "did the
        // call succeed" — because the 404-vs-403 question is a separate decision (§10.3.2)
        // that a parity table cannot express; it is called out in the divergence reasons.
        t.add(new Site("ScopeResolver.java:65 requireProjectAdmin",
                "project MANAGER (404 for a non-project-member)", "project.taxonomy.manage",
                this::legacyRequireProjectAdmin,
                a -> projectPermissions(a).has(Permission.PROJECT_TAXONOMY_MANAGE)));

        for (var caller : List.of(
                "ProjectAdminController.java:45",
                "ScopedProjectAdminService.java:77",
                "ScopedProjectAdminService.java:83",
                "ScopedProjectAdminService.java:90")) {
            t.add(new Site(caller + " -> requireProjectAdmin",
                    "project MANAGER (404 for a non-project-member)", "project.taxonomy.manage",
                    this::legacyRequireProjectAdmin,
                    a -> projectPermissions(a).has(Permission.PROJECT_TAXONOMY_MANAGE)));
        }

        t.add(new Site("ProjectService.java:153 update",
                "project MANAGER or workspace OWNER/ADMIN", "project.edit",
                this::legacyRequireProjectCurator,
                a -> projectPermissions(a).has(Permission.PROJECT_EDIT)));

        // Δ-FREE SINCE THE V13 SEED FIX, and this is the row that proves the fix was
        // needed: archiving a project is project-MANAGER-only, and a workspace OWNER with
        // no project row is refused it TODAY. Seeding the built-ins with
        // `project.administer.all` opened it for them; `project.curate.all` does not.
        t.add(new Site("ProjectService.java:171 archive",
                "requireRole(MANAGER)", "project.archive",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.PROJECT_ARCHIVE)));

        t.add(new Site("ProjectService.java:180 unarchive",
                "requireRole(MANAGER)", "project.archive",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.PROJECT_ARCHIVE)));

        t.add(new Site("ProjectService.java:202 listMembers",
                "requireRole(VIEWER) — a gate everyone passes", "no gate (§10.3.1)",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.VIEWER),
                a -> true));

        t.add(new Site("ProjectService.java:212 addMember",
                "requireRole(MANAGER)", "project.member.manage",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.PROJECT_MEMBER_MANAGE)));

        t.add(new Site("ProjectService.java:234 removeMember",
                "requireRole(MANAGER)", "project.member.manage",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.PROJECT_MEMBER_MANAGE)));

        for (var line : List.of(122, 168, 240, 266)) {
            t.add(new Site("ComponentService.java:" + line + " (requireProjectCurator)",
                    "project MANAGER or workspace OWNER/ADMIN", "component.manage",
                    this::legacyRequireProjectCurator,
                    a -> projectPermissions(a).has(Permission.COMPONENT_MANAGE)));
        }

        for (var line : List.of(132, 184, 251, 305, 334, 364)) {
            t.add(new Site("VersionService.java:" + line + " (requireProjectCurator)",
                    "project MANAGER or workspace OWNER/ADMIN", "version.manage",
                    this::legacyRequireProjectCurator,
                    a -> projectPermissions(a).has(Permission.VERSION_MANAGE)));
        }

        for (var line : List.of(188, 246, 310, 370, 451)) {
            t.add(new Site("SprintService.java:" + line + " (requireProjectCurator)",
                    "project MANAGER or workspace OWNER/ADMIN", "sprint.manage",
                    this::legacyRequireProjectCurator,
                    a -> projectPermissions(a).has(Permission.SPRINT_MANAGE)));
        }

        t.add(new Site("SprintService.java:537 addIssues",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("SprintService.java:623 removeIssue",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.java:147 create",
                "workspace membership only", "issue.create",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_CREATE)));

        // The create path is a door for two more permissions, which §10.2 does not list:
        // POST /issues carrying assigneeId is the same act as POST followed by PATCH
        // assigneeId, and the same goes for sprintId. Gating only the PATCH would leave
        // the bypass SHORTER than the guarded path — the §6.5 failure exactly. Δ-free for
        // the same reason as the rank door: the built-in Contributor holds all three.
        t.add(new Site("IssueService.java:148 create — carrying assigneeId (§6.5)",
                "workspace membership only", "issue.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_ASSIGN)));

        t.add(new Site("IssueService.java:149 create — carrying sprintId (§6.5)",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.java:365 update — plain fields",
                "workspace membership only", "issue.edit",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_EDIT)));

        t.add(new Site("IssueService.java:365 update — statusId changes (§10.3.3)",
                "workspace membership only", "issue.transition",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_TRANSITION)));

        t.add(new Site("IssueService.java:365 update — assignee changes (§10.3.3)",
                "workspace membership only", "issue.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_ASSIGN)));

        t.add(new Site("IssueService.java:365 update — sprintId changes (double door, §6.5)",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.java:712 rank",
                "workspace membership only", "issue.rank",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_RANK)));

        // The THIRD door for sprint.assign, which §6.5 does not list: POST
        // /issues/{n}/rank carries sprintId/clearSprint and moves an issue between the
        // backlog and a sprint exactly as the two doors §6.5 does name. Leaving it
        // unchecked would have made the permission bypassable with a one-line curl.
        // Δ-free: the built-in Contributor holds issue.rank AND sprint.assign, so no
        // actor's verdict on a rank-with-sprint-change request moves.
        t.add(new Site("IssueService.java:747 rank — sprintId changes (third door, §6.5)",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.java:805 delete — someone else's issue",
                "requireProjectRole(MANAGER)", "issue.delete",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.ISSUE_DELETE, false)));

        t.add(new Site("IssueService.java:805 delete — the actor's OWN issue",
                "requireProjectRole(MANAGER) — reporting it is irrelevant today", "issue.delete (own)",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.ISSUE_DELETE, true)));

        t.add(new Site("IssueService.java:524 requireAssignable — the TARGET's side (§6.3)",
                "target is a workspace member", "target holds issue.assignable here",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_ASSIGNABLE)));

        t.add(new Site("AttachmentService.java:83 upload",
                "workspace membership only", "attachment.create",
                a -> true,
                a -> projectPermissions(a).has(Permission.ATTACHMENT_CREATE)));

        t.add(new Site("AttachmentService.java:192 delete — the actor UPLOADED it",
                "uploader or project MANAGER", "attachment.delete (own)",
                a -> true /* uploader */,
                a -> projectPermissions(a).has(Permission.ATTACHMENT_DELETE, true)));

        t.add(new Site("AttachmentService.java:192 delete — someone else's file",
                "uploader or project MANAGER; not the uploader here", "attachment.delete",
                a -> legacyHasProjectRole(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.ATTACHMENT_DELETE, false)));

        t.add(new Site("CommentService.java:44 create",
                "workspace membership only", "comment.create",
                a -> true,
                a -> projectPermissions(a).has(Permission.COMMENT_CREATE)));

        t.add(new Site("CommentService.java:68 update — the actor's OWN comment",
                "author only; the author here", "comment.edit (own only, §17.3)",
                a -> true,
                a -> projectPermissions(a).has(Permission.COMMENT_EDIT, true)));

        t.add(new Site("CommentService.java:68 update — someone else's comment",
                "author only; not the author here", "comment.edit (never unrestricted)",
                a -> false,
                a -> projectPermissions(a).has(Permission.COMMENT_EDIT, false)));

        t.add(new Site("CommentService.java:90 delete — the actor's OWN comment",
                "author only; the author here", "comment.delete (own)",
                a -> true,
                a -> projectPermissions(a).has(Permission.COMMENT_DELETE, true)));

        // The ONLY declared divergence left in this table, and the only one the owner has
        // signed off as intended (§10.3.5). Note it is now a PROJECT ADMIN's alone: a
        // workspace Owner/Admin with no project row does NOT gain moderation, because
        // `project.curate.all` does not carry `comment.delete` — before the seed fix,
        // `project.administer.all` did, and this row needed two more excuses.
        t.add(new Site("CommentService.java:90 delete — someone else's comment",
                "author only; nobody can moderate today", "comment.delete (unrestricted)",
                a -> false,
                a -> projectPermissions(a).has(Permission.COMMENT_DELETE, false),
                Map.of(P_MANAGER.label(), COMMENT_MODERATION_WIDENING)));

        return t;
    }

    /**
     * §10.3.5 — acknowledged in the spec and slated for the release notes.
     *
     * <p><strong>It is a divergence on a {@link State#PENDING} row</strong>, i.e. a
     * decision that has been taken and not yet implemented: {@code CommentService} still
     * gates deletion on authorship alone, so nobody can moderate anything today. When S3
     * converts that site the row becomes LIVE and this text becomes a description of
     * shipped behaviour — which is the moment the release note has to exist.
     */
    private static final String COMMENT_MODERATION_WIDENING =
            "Intended widening, §10.3.5: nobody can delete another person's comment today; "
            + "unrestricted comment.delete is moderation, the #1 request for comment permissions. "
            + "Ship it and note it in the release notes.";

    // ==================================================== legacy predicates (today)

    /** Verbatim from {@code WorkspaceService.java:106} / {@code LabelService.java:554}. */
    private boolean legacyWorkspaceRoleAtLeast(User actor, WorkspaceRole required) {
        return workspaceRoleOf(actor).isAtLeast(required);
    }

    /**
     * Verbatim from {@code ProjectService.java:260-264} and {@code IssueService.java:969-973}:
     * the explicit row's role, <strong>falling back to VIEWER</strong> when there is none —
     * which is why {@code requireRole(VIEWER)} passes for everybody.
     */
    private boolean legacyProjectRoleAtLeast(User actor, ProjectRole required) {
        var role = explicitProjectRole(actor);
        return (role == null ? ProjectRole.VIEWER : role).isAtLeast(required);
    }

    /**
     * Verbatim from {@code AttachmentService.java:261-265}. Subtly different from
     * {@link #legacyProjectRoleAtLeast}: it {@code orElse(false)}s instead of falling back
     * to VIEWER. Same answer for MANAGER, different for anything below it — which is why it
     * gets its own transcription rather than sharing one.
     */
    private boolean legacyHasProjectRole(User actor, ProjectRole required) {
        var role = explicitProjectRole(actor);
        return role != null && role.isAtLeast(required);
    }

    /** The real bean. */
    private boolean legacyRequireWorkspaceAdmin(User actor) {
        return succeeds(() -> scopeResolver.requireWorkspaceAdmin(actor, ws.getId()));
    }

    /** The real bean. */
    private boolean legacyRequireProjectAdmin(User actor) {
        return succeeds(() -> scopeResolver.requireProjectAdmin(actor, ws.getId(), project.getId()));
    }

    /** The real bean — the 26-call-site curator predicate. */
    private boolean legacyRequireProjectCurator(User actor) {
        return succeeds(() -> scopeResolver.requireProjectCurator(actor, ws.getId(), project.getId()));
    }

    /**
     * Only an {@link AppException} counts as a denial. Anything else (an NPE, a lazy-init
     * failure, a broken fixture) must blow up the test rather than be silently recorded as
     * DENY — a harness that turns bugs into "no permission" would hide exactly what it is
     * here to find.
     */
    private boolean succeeds(Runnable gate) {
        try {
            gate.run();
            return true;
        } catch (AppException e) {
            return false;
        }
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
            var u = user(archetype.label());
            var m = new WorkspaceMember();
            m.setWorkspace(ws);
            m.setUser(u);
            m.setRole(roleCatalog.reference(archetype.wsRole()));
            workspaceMemberRepository.save(m);
            if (archetype.projectRole() != null) {
                var pm = new ProjectMember();
                pm.setProject(project);
                pm.setUser(u);
                pm.setRole(roleCatalog.reference(archetype.projectRole()));
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

    private WorkspaceRole workspaceRoleOf(User actor) {
        var m = workspaceMemberRepository.findByWorkspaceAndUser(ws, actor).orElseThrow();
        return roleCatalog.view(m.getRole().getId()).asWorkspaceRole();
    }

    private ProjectRole explicitProjectRole(User actor) {
        return projectMemberRepository.findByProjectAndUser(project, actor)
                .map(m -> roleCatalog.view(m.getRole().getId()).asProjectRole())
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
