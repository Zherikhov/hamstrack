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
 * of S1. They are navigation aids, not assertions — nothing reads them, and a stale one
 * costs a reader ten seconds rather than correctness. Refresh them when a slice moves a
 * call site.
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
                            + "    today      : %s -> %s%n"
                            + "    after      : %s -> %s%n"
                            + "    This is the silent failure mode of §18: a control that stops "
                            + "appearing (or a gate that stops biting) with no error anywhere. "
                            + "Either the built-in role's permission set is wrong, or this row's "
                            + "mapping is.")
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
        assert cells == table.size() * ARCHETYPES.size() : "cell count mismatch";
        assert table.size() >= 40
                : "the parity table has shrunk to " + table.size() + " sites. The inventory is "
                  + "42 authorization matches across 13 files; rows are not to be removed until "
                  + "their call site is gone.";

        assert failures.isEmpty()
                : "\n\n" + String.join("\n\n", failures) + "\n\n"
                  + failures.size() + " of " + cells + " (call site x archetype) cells disagree.\n";
    }

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

    // ============================================================ the sites

    private List<Site> table() {
        var t = new ArrayList<Site>();

        // ---------------------------------------------------------- workspace scope (§10.1)

        t.add(new Site("WorkspaceService.java:128 (invite a member)",
                "isAtLeast(ADMIN)", "workspace.member.manage",
                a -> legacyWorkspaceRoleAtLeast(a, WorkspaceRole.ADMIN),
                a -> wsPermissions(a).has(Permission.WORKSPACE_MEMBER_MANAGE)));

        // WorkspaceService.java:132 — the grant ceiling ("OWNER is never grantable, and
        // nobody grants above their own role") is DELIBERATELY ABSENT from this table.
        // §10.1 classifies it as a constraint on assignment, not a permission: it compares
        // the requested role against the actor's, which no single permission can express.
        // S3 reimplements it as the §11.2 rule and must test it separately.

        t.add(new Site("ProjectService.java:44 (create a project)",
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

        t.add(new Site("ProjectService.java:149 update (requireProjectCurator)",
                "project MANAGER or workspace OWNER/ADMIN", "project.edit",
                this::legacyRequireProjectCurator,
                a -> projectPermissions(a).has(Permission.PROJECT_EDIT)));

        // Δ-FREE SINCE THE V13 SEED FIX, and this is the row that proves the fix was
        // needed: archiving a project is project-MANAGER-only, and a workspace OWNER with
        // no project row is refused it TODAY. Seeding the built-ins with
        // `project.administer.all` opened it for them; `project.curate.all` does not.
        t.add(new Site("ProjectService.java:166 archive",
                "requireRole(MANAGER)", "project.archive",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.PROJECT_ARCHIVE)));

        t.add(new Site("ProjectService.java:175 unarchive",
                "requireRole(MANAGER)", "project.archive",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.PROJECT_ARCHIVE)));

        t.add(new Site("ProjectService.java:184 listMembers",
                "requireRole(VIEWER) — a gate everyone passes", "no gate (§10.3.1)",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.VIEWER),
                a -> true));

        t.add(new Site("ProjectService.java:194 addMember",
                "requireRole(MANAGER)", "project.member.manage",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.PROJECT_MEMBER_MANAGE)));

        t.add(new Site("ProjectService.java:215 removeMember",
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

        t.add(new Site("SprintService.java:510 addIssues",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("SprintService.java:594 removeIssue",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.java:151 create",
                "workspace membership only", "issue.create",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_CREATE)));

        t.add(new Site("IssueService.java:333 update — plain fields",
                "workspace membership only", "issue.edit",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_EDIT)));

        t.add(new Site("IssueService.java:333 update — statusId changes (§10.3.3)",
                "workspace membership only", "issue.transition",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_TRANSITION)));

        t.add(new Site("IssueService.java:333 update — assignee changes (§10.3.3)",
                "workspace membership only", "issue.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_ASSIGN)));

        t.add(new Site("IssueService.java:333 update — sprintId changes (double door, §6.5)",
                "workspace membership only", "sprint.assign",
                a -> true,
                a -> projectPermissions(a).has(Permission.SPRINT_ASSIGN)));

        t.add(new Site("IssueService.java:637 rank",
                "workspace membership only", "issue.rank",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_RANK)));

        t.add(new Site("IssueService.java:712 delete — someone else's issue",
                "requireProjectRole(MANAGER)", "issue.delete",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.ISSUE_DELETE, false)));

        t.add(new Site("IssueService.java:712 delete — the actor's OWN issue",
                "requireProjectRole(MANAGER) — reporting it is irrelevant today", "issue.delete (own)",
                a -> legacyProjectRoleAtLeast(a, ProjectRole.MANAGER),
                a -> projectPermissions(a).has(Permission.ISSUE_DELETE, true)));

        t.add(new Site("IssueService.java:956 resolveAssignee — the TARGET's side (§6.3)",
                "target is a workspace member", "target holds issue.assignable here",
                a -> true,
                a -> projectPermissions(a).has(Permission.ISSUE_ASSIGNABLE)));

        t.add(new Site("AttachmentService.java:76 upload",
                "workspace membership only", "attachment.create",
                a -> true,
                a -> projectPermissions(a).has(Permission.ATTACHMENT_CREATE)));

        t.add(new Site("AttachmentService.java:182 delete — the actor UPLOADED it",
                "uploader or project MANAGER", "attachment.delete (own)",
                a -> true /* uploader */,
                a -> projectPermissions(a).has(Permission.ATTACHMENT_DELETE, true)));

        t.add(new Site("AttachmentService.java:182 delete — someone else's file",
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

    /** §10.3.5 — acknowledged in the spec and slated for the release notes. */
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
