package com.hamstrack.project.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.observability.ProductMetrics.RoleScopeViolationSource;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.dto.ProjectRef;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.exception.LastProjectAdminException;
import com.hamstrack.project.exception.StrandedProjectsException;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.RoleRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.service.ProjectAccessProposal;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.RoleView;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <strong>One implementation of the last-administrator invariant</strong> (HD-136), for
 * every endpoint that can break it.
 *
 * <p>The invariant is: <em>a project that has an administrator must keep one</em> — where
 * "administrator" means <strong>an ACTIVE member holding
 * {@link Permission#PROJECT_MEMBER_MANAGE}</strong>, not a member carrying one particular
 * role id. Getting that wrong is what this class exists to fix, and it left two holes:
 *
 * <ol>
 *   <li><strong>The guard only recognised the built-in Project admin.</strong> It asked for
 *       members whose {@code role_id} equals {@code BuiltInRoles.PROJECT_MANAGER}, so a
 *       project whose only holder of {@code project.member.manage} carries a
 *       <em>custom</em> role was not protected at all: their removal passed because the
 *       guard was not looking for them. That is why HD-136 ships <em>before</em> the S4
 *       role editor rather than after it. Here the role ids are resolved from the grant
 *       ({@link RoleRepository#findIdsGranting}), so a custom role that carries the
 *       permission is counted the day it can exist, with no further change.</li>
 *   <li><strong>A workspace removal stranded projects freely.</strong>
 *       {@code WorkspaceMemberService.remove} deletes every {@code project_members} row the
 *       departing member holds in the workspace, with no project-level check — so the rule
 *       {@code ProjectService.removeMember} enforces one project at a time could be broken
 *       for any number of projects at once through a different door.</li>
 * </ol>
 *
 * <h2>The doors — all nine of them</h2>
 *
 * <p><strong>This enumeration is deliberate, and an unlisted door is the bug.</strong> Two
 * are deletions of a {@code project_members} row (HD-136); three are HD-127's, and two of
 * those are not per-member operations at all; four are HD-130's (S7), and <em>none</em> of
 * those four touches a membership row.
 *
 * <ol>
 *   <li><strong>{@code DELETE /projects/{p}/members/{u}}</strong> — one row, one project.
 *       Locked, {@link #lockAdmins} + {@link #requireNotLastAdmin}.</li>
 *   <li><strong>{@code DELETE /workspaces/{ws}/members/{u}}</strong> — every row that member
 *       holds, across the workspace. Locked, {@link #lockStrandedProjects} +
 *       {@link #requireNothingStranded}, with {@link #adoptAll} as the satisfiable retry.</li>
 *   <li><strong>{@code PATCH /projects/{p}/members/{u}}</strong> — a <em>demotion</em>
 *       strands with no row removed at all. Locked, same pair as door 1, and skipped
 *       entirely when the requested role itself grants {@code project.member.manage},
 *       because a promotion cannot strand.</li>
 *   <li><strong>{@code PATCH /roles/{id}}</strong> that drops {@code project.member.manage}
 *       from a PROJECT-scoped role — demotes <em>every holder at once, in every project</em>.
 *       {@link #projectsAdministeredOnlyBy}. <em>Advisory.</em></li>
 *   <li><strong>{@code DELETE /roles/{id}?reassignToRoleId=}</strong> whose target role
 *       lacks {@code project.member.manage} — the same bulk demotion by another name.
 *       {@link #projectsAdministeredOnlyBy}. <em>Advisory.</em></li>
 *   <li><strong>{@code DELETE /workspaces/{ws}/members/{u}}, again — the
 *       <em>inherited</em> administrators</strong> (S7 §5.2). Door 2 builds its candidate set
 *       from explicit {@code project_members} rows only, so a project administered
 *       <em>solely through the §5.2 default</em> was never a candidate and
 *       {@link #cannotBeStranded} never ran for it: removing the last workspace member who
 *       had no row there left it permanently unmanageable, with no 409, no adoption and no
 *       log line. {@link #projectsAdministeredOnlyByInheritance}. <em>Advisory.</em></li>
 *   <li><strong>{@code PATCH /workspaces/{ws}} setting {@code projectAccessMode = STRICT}</strong>
 *       — the current fallback grants {@code project.member.manage} and the new one (none)
 *       does not. {@link #projectsLosingLastAdministrator}. <em>Advisory.</em></li>
 *   <li><strong>{@code PATCH /workspaces/{ws}} setting {@code defaultProjectRoleId}</strong>
 *       — for every project that does not override it.
 *       {@link #projectsLosingLastAdministrator}. <em>Advisory.</em></li>
 *   <li><strong>{@code PATCH /workspaces/{ws}/projects/{p}/default-role}</strong> — the same,
 *       for that one project. {@link #projectsLosingLastAdministrator}. <em>Advisory.</em></li>
 * </ol>
 *
 * <p>Doors 4–9 are guarded <strong>advisorily</strong>; doors 1–3 are locked. See
 * {@link #projectsAdministeredOnlyBy} for why, and for why that honesty is written down
 * rather than papered over. <strong>Doors 6–9 have no adoption path, deliberately</strong>
 * ({@link #projectsAdministeredOnlyByInheritance} carries the argument): {@link #adoptAll}
 * writes a Team lead row, which would <em>narrow</em> an adopter who currently inherits
 * something wider.
 *
 * <p><strong>In the shipped configuration every one of doors 6–9 is free.</strong> The
 * workspace default is NULL → Contributor, which does not grant
 * {@code project.member.manage}, and no built-in project role anyone would choose as a
 * default does except Team lead and Project admin. So until somebody uses S7's own picker,
 * the whole of §5 is a role-permission bit test over cached {@code RoleView}s and
 * <strong>zero</strong> {@code existsActiveMemberWithoutProjectRole} queries — which is why
 * it can be added to the removal path without argument.
 *
 * <p><strong>Why it matters more than it looks.</strong> {@code project.member.manage} is
 * deliberately not in {@link Permission#projectCuration()}, so the workspace-admin bypass
 * does not cover it: a project with no administrator can never get one back through the
 * API — not by a workspace Owner, not by anyone — and it can also never be archived, nor
 * have its taxonomy managed. Recovery would be a manual {@code UPDATE}.
 *
 * <p><strong>The refusal has to be satisfiable, and by the person being refused</strong>
 * (security review, H1). The 409's remedy — "give each project another administrator" —
 * itself needs {@code project.member.manage} <em>in that project</em>, which the workspace
 * Owner reading the error does not have. Left there, the guard turns an integrity bug into
 * something worse: an insider who removes every <em>other</em> administrator one at a time
 * (each of those removals passes — the guard only refuses when the <em>target</em> is the
 * last one) becomes unremovable, 409 on the workspace door and 403 on the project door.
 * So {@link #adoptAll} exists: the caller repeats the removal with explicit consent and, in
 * the same transaction, <em>becomes</em> the administrator of each project that would have
 * been stranded. Offboarding always completes in two calls, by the person who should be
 * able to complete it, and the invariant holds throughout.
 *
 * <p><strong>Locking, and the shape that took two attempts to get right</strong> (HD-132):
 * the administrator set is read <em>under a row lock</em>, and that lock is taken
 * <strong>before</strong> the decision it protects — trivially so here, because the lock
 * and that read are the same statement. Deciding whether to lock from an unlocked read is
 * itself the stale read the lock exists to prevent: two concurrent removals would each
 * count two administrators and each remove one. Both locking reads {@code ORDER BY m.id},
 * including the workspace-wide one, so overlapping transactions take shared rows in the
 * same order and <strong>queue</strong> instead of interleaving into a bypass — and if a
 * cycle does form anyway, PostgreSQL rolls one side back, which is safe because nothing is
 * half done.
 *
 * <p><strong>Which is precisely why the wait needs a bound</strong> (review round 4). The
 * ordering makes a deadlock the <em>rare</em> outcome and a plain lock wait the ordinary
 * one, and PostgreSQL waits for a lock for ever: a client that disconnects does not abort
 * the transaction, and Hikari's {@code connectionTimeout} bounds only checkout, never a
 * query already in flight. So an earlier reading of the paragraph above ("a deadlock is
 * safe, so contention is safe") was defending the branch that does not normally happen.
 * Every caller of these methods therefore opens its transaction with
 * {@code LockTimeout.applyToCurrentTransaction()}; the loser gets a 409 with
 * {@code Retry-After} instead of holding a pooled connection indefinitely.
 *
 * <p><strong>{@link Propagation#MANDATORY}, not the default.</strong> {@link
 * LockedProjectAdmins}' entire safety property is "the only way to obtain one is a method
 * that always takes the lock". Under {@code REQUIRED}, a caller with no surrounding
 * transaction would get one that commits when the method returns — releasing every
 * {@code FOR UPDATE} before the guard using the result runs, and turning the lock back into
 * the unlocked read it replaced while the type still claimed otherwise. {@code MANDATORY}
 * makes that misuse a loud {@code IllegalTransactionStateException} instead of a silent
 * one.
 *
 * <p><strong>Every over-approximation here is now a GRANT trigger, not only a refusal
 * trigger.</strong> This inverts the safety argument the class was originally built on. As
 * long as the only consequence of wrongly concluding "stranded" was a 409, erring that way
 * was free; {@link #adoptAll} turned the same wrong conclusion into <em>handing the caller a
 * role in somebody else’s project</em>. Three approximations are live below — uncounted
 * {@link Permission#PROJECT_ADMINISTER_ALL} holders, uncounted DISABLED holders, and
 * inherited holders counted only under {@link #cannotBeStranded} — and each is a way to
 * manufacture a stranding: notably, whoever can deactivate an account can manufacture a
 * takeover target. What makes that acceptable rather than alarming is the size of the grant
 * — <strong>and that size is configuration-dependent, so state it as a delta against the
 * §5.2 fallback that actually applies, never as a universal</strong>:
 * <ul>
 *   <li><em>OPEN workspace, default fallback</em> (the shipped configuration): the actor
 *       already inherits Contributor in every project, so an adoption adds exactly
 *       {@code project.member.manage} and nothing else.</li>
 *   <li><em>STRICT workspace, or an OPEN one whose fallback is narrower</em>: there is no
 *       inherited role, so an adoption is an <strong>absolute grant of all thirteen</strong>
 *       of Team lead’s permissions to an actor who previously held only
 *       {@code project.curate.all}’s four in that project. That is a real widening and it
 *       should be read as one.</li>
 * </ul>
 * What caps the severity in both cases is what the role does <em>not</em> carry: nothing
 * irreversible ({@code issue.delete}, unrestricted {@code attachment.delete}), nothing over
 * the project itself ({@code archive}, {@code edit}, {@code taxonomy.manage}), and — because
 * v1 deliberately has no {@code issue.view}/{@code project.view} (§3) — no new
 * <em>visibility</em> whatsoever: every workspace member could already read that project.
 * So the worst outcome of a manufactured stranding is write authority over one project’s
 * work and roster, never access to something previously unreadable. Any future widening of
 * {@link #ADOPTION_ROLE_KEY} has to re-argue this paragraph first, for BOTH bullets.
 *
 * <p><strong>And the DISABLED approximation cuts the other way too — that direction
 * strands rather than grants</strong> (review round 4). The paragraph above reads it as a
 * way to manufacture a <em>takeover</em>; the inverse is the one that leaves damage.
 * Deactivate a project's sole administrator and their {@code project_members} row stops
 * counting, so the workspace removal that follows sees nothing to strand: it succeeds with
 * <strong>no 409, no adoption and no receipt of any kind</strong>, and the project is left
 * permanently unmanageable — no {@code project.member.manage}, outside the curator bypass,
 * recoverable only by a hand-written {@code UPDATE}. It is recorded rather than fixed
 * because it needs {@link com.hamstrack.auth.entity.SystemRole#ADMIN}, who can already
 * delete the accounts outright; that is a trust boundary this guard was never the last
 * line of. Informational, not a hole — but the asymmetry belongs next to the claim it
 * qualifies, because the two halves are the same approximation and only one of them was
 * written down.
 *
 * <p><strong>What is deliberately not counted as an administrator</strong>, and why each is
 * safe:
 * <ul>
 *   <li><em>DISABLED / PENDING accounts</em> — filtered in the queries. A deactivated
 *       account cannot log in and therefore administers nothing; counting one would both
 *       claim an invariant the data does not support and make deactivation (the normal
 *       revocation step) fail to clear the 409. Its cost is the silent-stranding direction
 *       recorded two paragraphs up — deactivate first and the removal never sees a project
 *       to protect.</li>
 *   <li><em>Archived projects</em> — skipped by the <strong>workspace</strong> path only.
 *       They are frozen, so a project archived a year ago must not block somebody’s
 *       offboarding. It must NOT be skipped by {@link #lockAdmins}: applying the same
 *       exclusion there opened a one-actor, no-race door on
 *       {@code DELETE /projects/{p}/members/{u}} — a Project admin archives the project
 *       (they hold {@code project.archive}) and then removes themselves, leaving it frozen
 *       with no administrator, which no endpoint can undo because unarchiving needs
 *       {@code project.archive} and the adoption path deliberately ignores archived
 *       projects. Refusing "you are the last administrator of this archived project" on the
 *       project endpoint costs nobody an offboarding.</li>
 *   <li><em>Inherited (default-role) holders</em> — counted only when somebody can actually
 *       be standing on the fallback; see {@link #cannotBeStranded}.</li>
 *   <li><em>A workspace-scoped {@link Permission#PROJECT_ADMINISTER_ALL} holder</em>, who
 *       can administer every project without being a member of any. No built-in role holds
 *       it and detecting one costs a scan of {@code workspace_members} per removal, so it
 *       is not consulted: the consequence is a refusal such a holder could have absorbed,
 *       which {@link #adoptAll} now clears in one retry.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProjectAdminGuard {

    /**
     * The role {@link #adoptAll} hands the adopting caller: the built-in <strong>Team
     * lead</strong> (V16) — Contributor’s everyday delivery set plus
     * {@code project.member.manage}.
     *
     * <p><strong>Not the built-in Project admin</strong>, which the first cut used. That
     * handed over all 20 project permissions — {@code issue.delete} and unrestricted
     * {@code attachment.delete} among them — to satisfy an invariant about exactly one, and
     * the "it is less destructive than stranding" defence compares on the wrong axis:
     * stranding wrecked the project and gave the actor <em>nothing</em>, while adoption
     * gives them durable authority over somebody else’s project. Nothing this role grants
     * can destroy anything: no {@code issue.delete}, no unrestricted
     * {@code attachment.delete}, no archive, settings or taxonomy authority.
     *
     * <p><strong>Nor {@code project.member.manage} alone</strong>, which looks narrower and
     * is not: it is narrower than the §5.2 <em>fallback</em>, so writing it would demote the
     * adopter in the project they just rescued, and the grant ceiling would then refuse both
     * the removal of their own row and the appointment of an ordinary Contributor — a
     * one-way trap of exactly the shape this class exists to delete. V16 carries the full
     * argument.
     */
    private static final String ADOPTION_ROLE_KEY = "TEAM_LEAD";

    private final RoleRepository roleRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceAccessService workspaceAccess;
    private final RoleCatalog roleCatalog;

    // ------------------------------------------------------------ one project

    /**
     * The project's administrators, <strong>locked</strong> for the rest of the
     * transaction. Call this before reading anything about the member being removed — see
     * the class javadoc.
     *
     * <p>The ids are read off {@code getUser()} proxies, which does not load the user rows.
     *
     * <p>An empty answer means "no removal of a single member can strand this project" —
     * which covers a project that has no administrator at all as well as one that cannot
     * lose the ones it has (archived, or somebody inherits the permission anyway). All
     * three are the same answer to the only question this class asks, and none of them
     * needs a lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedProjectAdmins lockAdmins(Project project) {
        // INVARIANT, convention-only (HD-136 review round 6): every transaction that calls
        // this must ALREADY have called LockTimeout.applyToCurrentTransaction(). Nothing
        // here enforces it — this method is public and MANDATORY, so a future caller can
        // take the lock with no bound on the wait, and PostgreSQL then waits for ever. The
        // omission compiles, passes review and stays invisible until a production pile-up
        // exhausts the pool. Keep the two paired: bound first, then lock. Same invariant is
        // stated on WorkspaceMemberService.lockOwners, the other lock helper.
        var roleIds = administeringRoleIds(project.getWorkspace().getId());
        if (roleIds.isEmpty()) {
            // No role in this workspace grants project.member.manage — no project has an
            // administrator, so no removal can take its last one. Nothing to lock.
            return new LockedProjectAdmins(Set.of());
        }
        var userIds = userIdsOf(
                projectMemberRepository.lockAllByProjectAndRoleIdIn(project, roleIds));
        // Only worth asking when losing one row could actually empty the set. The lone
        // administrator is the only member whose removal this answer can be about, so they
        // are the one excluded from the "somebody else inherits it" proof.
        //
        // EXACTLY one, not "at most one" (review round 4). With an empty set the question
        // has already been answered — a project with no administrator cannot lose its last
        // one, and both branches return the same empty answer — so the only thing the call
        // added was a query, run with a NULL exclusion. That is worse than wasteful: in
        // JPQL `m.user.id <> :excludedUserId` is UNKNOWN for every row when the bind is
        // null, so the predicate silently means "nobody inherits" rather than "exclude
        // nobody", and the whole check answers false for a reason two classes away.
        // Skipping the call rather than making the query null-tolerant is the choice that
        // keeps the parameter to ONE meaning — "the member being removed" — instead of
        // adding an exclude-nobody mode that reads like a safe default and is not one.
        if (userIds.size() == 1
                && cannotBeStranded(project.getWorkspace(), project,
                                    userIds.iterator().next())) {
            return new LockedProjectAdmins(Set.of());
        }
        return new LockedProjectAdmins(userIds);
    }

    /**
     * Refuse the removal of {@code targetUserId} if it would take the project's last
     * administrator. 409 — the caller's permissions are fine; the project's invariant is
     * what refuses.
     */
    public void requireNotLastAdmin(LockedProjectAdmins admins, UUID targetUserId) {
        if (admins.wouldStrand(targetUserId)) {
            throw new LastProjectAdminException();
        }
    }

    // ---------------------------------------------------------- whole workspace

    /**
     * <strong>Every live project of {@code workspace} that removing {@code targetUserId}
     * from the workspace would leave without an administrator</strong>, with those
     * projects' administrator rows locked for the rest of the transaction.
     *
     * <p>The same lock-first shape and the same {@link LockedProjectAdmins#wouldStrand}
     * predicate as the single-project path — deliberately, because two implementations of
     * this rule would be two rules, and the one that disagrees is the one nobody is
     * looking at.
     *
     * <p>Cost is two statements plus, only when something is actually stranded, one load of
     * the affected projects (they have to be named) and one existence check each.
     *
     * @return an empty list when the removal strands nothing — the overwhelmingly common
     *         case, and the one that must not be made expensive
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StrandedProjects lockStrandedProjects(Workspace workspace, UUID targetUserId) {
        var roleIds = administeringRoleIds(workspace.getId());
        if (roleIds.isEmpty()) {
            return new StrandedProjects(List.of());
        }
        // The lock and the decision input are ONE statement, which is what makes the
        // "lock first" rule automatic here: there is no earlier unlocked read of this
        // member's project roles that the answer could be built from.
        var rows = projectMemberRepository.lockAllByWorkspaceAndRoleIdIn(workspace, roleIds);

        Map<UUID, Set<UUID>> adminsByProject = new HashMap<>();
        for (var row : rows) {
            // Both are uninitialised proxies; reading an id does not load the row.
            adminsByProject.computeIfAbsent(row.getProject().getId(), k -> new HashSet<>())
                    .add(row.getUser().getId());
        }
        var candidateIds = adminsByProject.entrySet().stream()
                .filter(e -> new LockedProjectAdmins(e.getValue()).wouldStrand(targetUserId))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (candidateIds.isEmpty()) {
            return new StrandedProjects(List.of());
        }
        return new StrandedProjects(projectRepository
                .findAllByWorkspaceAndIdInAndArchivedAtIsNull(workspace, candidateIds).stream()
                .filter(p -> !cannotBeStranded(workspace, p, targetUserId))
                .map(ProjectRef::of)
                // Stable, human-orderable output: the SPA renders this list verbatim.
                .sorted(Comparator.comparing(ProjectRef::key))
                .toList());
    }

    /**
     * Refuse a workspace member removal that would strand projects, naming <em>all</em> of
     * them so the admin can fix them rather than hunt for them.
     */
    public void requireNothingStranded(StrandedProjects stranded) {
        if (!stranded.isEmpty()) {
            throw new StrandedProjectsException(stranded.projects());
        }
    }

    /**
     * <strong>Make {@code actor} the administrator of every project that would otherwise be
     * stranded</strong>, so that the removal can proceed — the satisfiable half of the 409
     * (H1). Runs only when the caller asked for it explicitly.
     *
     * <p><strong>The client cannot lie about which projects it was refused</strong>, because
     * it never names any. The request carries a bare consent flag; the set adopted is
     * exactly the set {@link #lockStrandedProjects} computes <em>in this transaction, under
     * the same lock</em>, re-derived from the database rather than echoed back from the
     * refusal the client saw. A stale, forged or replayed "yes" can therefore only ever
     * adopt what is genuinely about to be stranded right now: a project that has since
     * gained a second administrator is no longer in the set and is not touched, and no
     * project the caller merely fancies can be added to it. The reload below is scoped to
     * the workspace and to live projects for the same reason — belt and braces on a set
     * that is already server-derived.
     *
     * <p><strong>The grant ceiling is deliberately not applied here</strong>, and that is a
     * real widening, so it is worth being precise about its bounds. §11.2 refuses handing
     * out a role granting something the actor does not hold; a workspace Owner holds only
     * {@code project.curate.all}'s four permissions in a project they are not a member of,
     * so applying it would refuse every adoption and leave H1 exactly where it was. What
     * bounds this instead:
     * <ul>
     *   <li>it is reachable only for projects that are <em>about to lose their last
     *       administrator</em> by this very request — never an arbitrary project;</li>
     *   <li>the actor already holds {@code workspace.member.manage} and has already passed
     *       the grant ceiling <em>against the departing member's workspace role</em>, so
     *       they could remove that person regardless;</li>
     *   <li>it is explicit (a flag the caller sets after being told what it will do) and
     *       logged per project, unlike the silent stranding it replaces.</li>
     * </ul>
     * The residual is that a workspace member-manager can take over a project by offboarding
     * its sole administrator. That is strictly less destructive than the alternative the
     * same actor already had — destroying the project's manageability outright — and it is
     * the trade the owner chose.
     *
     * @return the projects actually adopted, for the caller's audit line
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ProjectRef> adoptAll(User actor, Workspace workspace, StrandedProjects stranded) {
        if (stranded.isEmpty()) {
            return List.of();
        }
        var adoptionRole = roleCatalog.builtIn(RoleScope.PROJECT, ADOPTION_ROLE_KEY);
        var ids = stranded.projects().stream().map(ProjectRef::id).toList();
        // ---- reads first (the @Version rule), then every mutation in one saveAll ----
        var projects = projectRepository.findAllByWorkspaceAndIdInAndArchivedAtIsNull(workspace, ids);
        var existing = projects.isEmpty()
                ? List.<ProjectMember>of()
                : projectMemberRepository.findAllByUserAndProjectIn(actor, projects);
        var byProject = existing.stream()
                .collect(Collectors.toMap(m -> m.getProject().getId(), m -> m));

        var rows = new ArrayList<ProjectMember>(projects.size());
        var blocked = new ArrayList<ProjectRef>();
        var unreadable = new ArrayList<ProjectRef>();
        for (var project : projects) {
            var row = byProject.get(project.getId());
            var verdict = row == null ? Adoptability.ADOPTABLE : adoptability(workspace, adoptionRole, row);
            if (verdict == Adoptability.WOULD_DEMOTE) {
                // The actor's existing role here holds something Team lead does not, so
                // overwriting it would DEMOTE them — the one-way shape V16 exists to avoid,
                // reappearing from the other side. It cannot be reached with any built-in
                // (all four are covered by Team lead or carry project.member.manage, in
                // which case the project was never stranded), but a custom "QA lead" with
                // issue.delete and no member management is exactly it, and the grant ceiling
                // would then refuse to give them issue.delete back.
                blocked.add(ProjectRef.of(project));
                continue;
            }
            if (verdict == Adoptability.ROLE_UNREADABLE) {
                // Different refusal, different reader, different next action — see
                // StrandedProjectsException.ADOPTION_ROLE_UNREADABLE. Kept in its own list
                // rather than folded into `blocked`, which is what round 3 did: the two then
                // shared copy that asserts a cause this branch did not test and prescribes a
                // remedy that cannot clear it.
                unreadable.add(ProjectRef.of(project));
                continue;
            }
            if (row == null) {
                row = new ProjectMember();
                row.setProject(project);
                row.setUser(actor);
            }
            // Never wider than the role being adopted into, even when the actor already had
            // a narrower row: the adoption is not a promotion to Project admin.
            row.setRole(roleCatalog.reference(adoptionRole.id()));
            rows.add(row);
        }
        // Refuse the WHOLE removal rather than adopt what we can: skipping a blocked project
        // and proceeding would strand it, which is the outcome this class exists to prevent.
        //
        // The unreadable rows are reported FIRST when both lists are non-empty. Either
        // refusal alone ends the request, so one of them is necessarily reported second, and
        // this is the useful order: the demotion refusal is cleared by a colleague's action,
        // the unreadable one only by an operator repairing stored data — so leading with the
        // repairable-by-nobody-present one avoids sending the caller to arrange a handover
        // that will then hit a second 409 they cannot clear either.
        if (!unreadable.isEmpty()) {
            throw StrandedProjectsException.adoptionRoleUnreadable(unreadable);
        }
        if (!blocked.isEmpty()) {
            // The actor's own row is the reason this one cannot be rescued automatically, so
            // they are exactly the right person to be told.
            throw StrandedProjectsException.cannotAdopt(blocked, adoptionRole.name());
        }
        projectMemberRepository.saveAll(rows);
        return projects.stream()
                .map(ProjectRef::of)
                .sorted(Comparator.comparing(ProjectRef::key))
                .toList();
    }

    /** The three answers {@link #adoptability} can give about one existing membership row. */
    private enum Adoptability {
        /** Safe to overwrite with the adoption role. */
        ADOPTABLE,
        /** The row grants something the adoption role does not — overwriting it demotes. */
        WOULD_DEMOTE,
        /** The row's stored {@code role_id} failed the scope+ownership assertion. */
        ROLE_UNREADABLE
    }

    /**
     * Can this project be adopted by overwriting the actor's existing row? — the per-project
     * half of {@link #adoptAll}'s refusal.
     *
     * <p>The actor's OWN row, and this is a write path that decides what to overwrite it
     * with, so the role id goes through the scope+ownership assertion rather than a bare
     * cache read. A degrade here would read as "no permissions", i.e. "never a demotion",
     * and the adoption would silently overwrite whatever the corrupt row actually granted.
     *
     * <p><strong>The refusal is right; a bare 404 was the wrong shape for it</strong>
     * (round-3 review). {@code requireRole} answers a corrupt row with
     * {@link WorkspaceNotFoundException}, which on {@code DELETE /workspaces/{ws}/members/{u}}
     * reaches a caller who has demonstrably just resolved that workspace and passed
     * {@code workspace.member.manage} — the unactionable, tenancy-indistinguishable 404 H1
     * argued against. It becomes a 409 naming the projects instead, the shape of the
     * neighbouring branch. Nobody is blinded by that — {@code requireRole} has already logged
     * its ERROR and incremented {@code hamstrack.role.scope_violation} before throwing.
     *
     * <p><strong>Two verdicts, not one</strong> (round-4 review). Round 3 returned a bare
     * boolean and folded the corrupt row into the demotion refusal, which then told the
     * caller their own role was too wide (untested here, and unknowable — the row is what
     * could not be read) and asked them to arrange a handover that cannot clear it. The
     * conservative decision "do not overwrite this row" is the same for both; the sentence
     * the caller receives and the person who can act on it are not, so the distinction has to
     * survive as far as the exception.
     *
     * <p>Catching a tenancy exception is normally exactly wrong, so the bounds matter: the
     * project came from {@code lockStrandedProjects}, already scoped to this workspace, and
     * the row is the actor's own. The only question being swallowed is "is this stored
     * {@code role_id} usable", and both answers taken from it refuse.
     */
    private Adoptability adoptability(Workspace workspace, RoleView adoptionRole, ProjectMember row) {
        RoleView held;
        try {
            held = workspaceAccess.requireRole(row.getRole().getId(), RoleScope.PROJECT,
                    workspace.getId(), RoleScopeViolationSource.PROJECT_MEMBERS);
        } catch (WorkspaceNotFoundException corruptRow) {
            return Adoptability.ROLE_UNREADABLE;
        }
        return adoptionRole.permissions().firstNotCovered(held.permissions()).isEmpty()
                ? Adoptability.ADOPTABLE
                : Adoptability.WOULD_DEMOTE;
    }

    // ------------------------------------------------- bulk: a role's contents change

    /**
     * <strong>Every live project of {@code workspace} whose administrators ALL hold
     * {@code roleId}</strong> — doors 4 and 5 (HD-127 §6).
     *
     * <p>A {@code PATCH /roles/{id}} that drops {@code project.member.manage}, or a
     * {@code DELETE /roles/{id}?reassignToRoleId=} whose target lacks it, demotes every
     * holder <em>at once, in every project</em>. Neither existing guard sees that: door 1
     * watches one membership row and door 2 watches one departing member, and here no
     * membership row changes at all — only what the role its holders carry <em>means</em>.
     * The projects returned are exactly those the change would empty of administrators;
     * non-empty is a 409.
     *
     * <p>One aggregate, restricted to the role ids that actually grant the permission
     * ({@link RoleRepository#findIdsGranting}) and to the same ACTIVE-user filter the locked
     * paths use, so the three answers cannot disagree about who counts. It runs
     * <strong>only</strong> when the change actually removes {@code project.member.manage}
     * from a PROJECT-scoped role, so an ordinary rename or a workspace-scoped edit pays
     * nothing.
     *
     * <p><strong>Advisory, and that is stated rather than dressed up.</strong> An aggregate
     * cannot take {@code FOR UPDATE}: this is one {@code GROUP BY … HAVING} over the
     * workspace's {@code project_members}, so a membership change committing concurrently
     * can make the answer stale between the check and the write. It is a bulk-safety net for
     * an action a human performs a handful of times a year; <strong>the locked invariant
     * remains the per-row one</strong> (doors 1–3), which is what actually holds the line.
     * Do not paper this over with a lock that is not there — a {@code FOR UPDATE} on the
     * grouped rows would need the whole workspace's memberships locked for the duration of a
     * role edit, which is a far worse trade than a rare stale read on a rare operation.
     *
     * <p><strong>No adoption path, deliberately</strong> (contrast {@link #adoptAll}). The
     * H1 test is whether the person refused can satisfy the refusal themselves, and here
     * they plainly can: choose a replacement role that also grants
     * {@code project.member.manage}, or give those projects another administrator first.
     * Nothing has to be granted to anybody to clear it.
     *
     * @param roleId the role whose holders are about to lose the permission
     * @return the affected projects, ordered by key so the SPA renders a stable list
     */
    @Transactional(readOnly = true)
    public List<ProjectRef> projectsAdministeredOnlyBy(Workspace workspace, UUID roleId) {
        var roleIds = administeringRoleIds(workspace.getId());
        // The role in question must itself be an administering one, or nothing can be lost.
        if (!roleIds.contains(roleId)) {
            return List.of();
        }
        var projectIds = projectMemberRepository.findProjectIdsAdministeredOnlyBy(
                workspace.getId(), roleIds, roleId);
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return projectRepository
                .findAllByWorkspaceAndIdInAndArchivedAtIsNull(workspace, projectIds).stream()
                // The same "somebody else inherits it anyway" proof the locked paths apply.
                // Null is not an option for `leavingUserId` here — nobody is leaving — so
                // this asks the question for a random administrator of the project, which is
                // the right one: if some OTHER active member with no explicit row inherits
                // the permission from the §5.2 fallback, the project cannot be stranded.
                .filter(p -> !inheritedAdministratorSurvives(workspace, p))
                .map(ProjectRef::of)
                .sorted(Comparator.comparing(ProjectRef::key))
                .toList();
    }

    // ------------------------------------- S7: the inherited administrators (doors 6–9)

    /**
     * <strong>Door 6</strong> (S7 §5.2) — every live project of {@code workspace} whose
     * administrators exist <em>only by inheritance</em> and whose last such administrator
     * this workspace removal would take.
     *
     * <p>A project qualifies when all four hold:
     * <ol>
     *   <li>its <strong>mode-aware</strong> fallback ({@code defaultProjectRole}) is non-null
     *       and grants {@code project.member.manage} unrestricted — so in a {@code STRICT}
     *       workspace this never fires and costs one comparison per project;</li>
     *   <li>it has <strong>no</strong> explicit administering {@code project_members} row
     *       (ACTIVE users, administering role ids — the same filter the locked paths use);</li>
     *   <li>the leaving member has <strong>no</strong> explicit row there, so they were one of
     *       the inherited administrators. A project that already had none cannot lose its
     *       last;</li>
     *   <li>nobody else is standing on the fallback
     *       ({@link WorkspaceMemberRepository#existsActiveMemberWithoutProjectRole}) — the same
     *       both-halves proof {@link #cannotBeStranded} insists on, for the same reason: "the
     *       default grants it" alone is wrong in the dangerous direction.</li>
     * </ol>
     *
     * <p><strong>Mode-aware, and it must stay so.</strong> Condition 1 asks
     * {@code WorkspaceAccessService.defaultProjectRole}, never
     * {@code declaredDefaultProjectRole}: in {@code STRICT} nothing is inherited, so a
     * project's administrators are exactly its explicit administering rows and this door does
     * not exist.
     *
     * <p><strong>No adoption path, and — stated honestly, after round 2 got it wrong — the
     * actor cannot satisfy this refusal themselves.</strong>
     *
     * <p>The old wording claimed the H1 test was met "because the default that is about to
     * stop mattering still grants them {@code project.member.manage} today". It does not
     * grant it to <em>them</em>. Work the conditions through: condition 4 excludes only the
     * leaver, so if the actor had no explicit row here they would be standing on the fallback
     * and this door would not fire at all; and condition 2 says no explicit row administers.
     * Therefore <strong>whenever door 6 fires, the actor holds an explicit, non-administering
     * row in that project</strong> — the person still inheriting {@code project.member.manage}
     * is the member being removed, nobody else. So the actor cannot add an administrator
     * there, cannot change that project's default, and cannot adopt it; and
     * {@code project.member.manage} is deliberately outside {@link Permission#projectCuration()},
     * so being the workspace Owner does not help either. The one exception is a
     * <em>workspace</em> role carrying {@code project.administer.all}, which grants all twenty
     * project permissions everywhere — no built-in holds it.
     *
     * <p><strong>Two remedies exist, and neither is the actor's alone</strong>, which is worth
     * knowing before this arrives as "the member cannot be removed at all":
     * <ol>
     *   <li>anybody still standing on the fallback — by construction, the departing member —
     *       adds an explicit administrator to the project before the removal;</li>
     *   <li>an Owner mints {@code project.administer.all} onto a custom workspace role (they
     *       are exempt from the definition ceiling <em>precisely</em> so that permission is
     *       mintable) and grants it to the actor, who then holds
     *       {@code project.member.manage} in every project and can clear condition 2.</li>
     * </ol>
     * That is a real cost and it is not pretty. It is accepted here rather than papered over
     * because the alternative — extending {@link #adoptAll} to this door — is new
     * <em>self-grant</em> surface: adoption would have to UPDATE the actor's existing row
     * rather than insert one, and {@link StrandedProjects}' private constructor exists so that
     * {@code adoptAll} can never be handed a candidate set that was not derived under the lock,
     * which an aggregate like this one cannot be. Both are decisions for a reviewed slice, not
     * for a review round. Whoever takes them: the {@link Adoptability#WOULD_DEMOTE} argument
     * below does <strong>not</strong> apply to the adopter here — they hold an explicit row, so
     * adopting would widen them, not narrow them. It applies to the <em>leaver</em>, and the
     * leaver is not the one adopting.
     *
     * <p><strong>Advisory, like doors 4–5.</strong> Conditions 2 and 4 are aggregates and an
     * aggregate cannot take {@code FOR UPDATE}. The locked invariant remains the per-row one.
     *
     * <p>Cost on the shipped configuration: one role-id lookup and one project list, then a
     * bit test per project that answers "no" — no existence check is issued at all.
     */
    @Transactional(readOnly = true)
    public List<ProjectRef> projectsAdministeredOnlyByInheritance(Workspace workspace,
                                                                  UUID leavingUserId) {
        var candidates = projectRepository.findAllByWorkspaceAndArchivedAtIsNull(workspace).stream()
                // Condition 1, first because it is free: a cached RoleView and one bit test.
                .filter(p -> administersByInheritance(workspace, p))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        var roleIds = administeringRoleIds(workspace.getId());
        var withExplicitAdmin = roleIds.isEmpty()
                ? Set.<UUID>of()
                : Set.copyOf(projectMemberRepository.findProjectIdsWithAdministrator(workspace, roleIds));
        var leaversProjects = Set.copyOf(
                projectMemberRepository.findProjectIdsOfMemberInWorkspace(workspace, leavingUserId));
        return candidates.stream()
                .filter(p -> !withExplicitAdmin.contains(p.getId()))   // condition 2
                .filter(p -> !leaversProjects.contains(p.getId()))     // condition 3
                // Condition 4, last because it is the only one that costs a query per project.
                .filter(p -> !workspaceMemberRepository.existsActiveMemberWithoutProjectRole(
                        workspace, p, leavingUserId))
                .map(ProjectRef::of)
                .sorted(Comparator.comparing(ProjectRef::key))
                .toList();
    }

    /**
     * <strong>Doors 7, 8 and 9</strong> (S7 §5.3) — every live project of {@code workspace}
     * that {@code after} would leave with no administrator at all, because it had them only
     * by inheritance and the proposal takes that inheritance away.
     *
     * <p>A change to the mode or to either {@code default_project_role_id} demotes every
     * inherited administrator at once, in every affected project, with no membership row
     * touched — structurally doors 4 and 5, and invisible to every guard that predates S7.
     * One method serves all three writes and the impact preview, so the number a user is
     * shown and the refusal they then receive are the same computation (§4.3).
     *
     * <p>A project qualifies when its <strong>current effective</strong> fallback grants
     * {@code project.member.manage}, its <strong>proposed</strong> fallback does not, it has
     * no explicit administering row, and somebody is actually standing on the fallback.
     * Comparing current-effective against proposed makes the guard <strong>one-way by
     * construction</strong>: a flip from {@code STRICT} to {@code OPEN}, or any change that
     * widens, can never strand.
     *
     * <p>Advisory, for {@link #projectsAdministeredOnlyByInheritance}'s reason, and with no
     * adoption path for the same one. The remedy is satisfiable by the person refused, with
     * nothing granted to anybody.
     *
     * @param liveProjects the workspace's non-archived projects, already loaded. Taken rather
     *        than re-read so the impact preview stays at four statements however many projects
     *        the workspace has (§4.2, AC 28)
     */
    @Transactional(readOnly = true)
    public List<ProjectRef> projectsLosingLastAdministrator(Workspace workspace,
                                                            List<Project> liveProjects,
                                                            ProjectAccessProposal after) {
        // §4.2 statement 4. Unconditional so the preview's statement count is the same four
        // for every workspace; it is one indexed read of catalog data, and everything after
        // it is free in the shipped configuration.
        var roleIds = administeringRoleIds(workspace.getId());
        if (roleIds.isEmpty()) {
            return List.of();
        }
        var candidates = liveProjects.stream()
                .filter(p -> administersByInheritance(workspace, p))
                .filter(p -> {
                    var proposed = workspaceAccess.defaultProjectRoleUnder(workspace, p, after);
                    return proposed == null
                           || !proposed.permissions().has(Permission.PROJECT_MEMBER_MANAGE);
                })
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        var withExplicitAdmin = Set.copyOf(
                projectMemberRepository.findProjectIdsWithAdministrator(workspace, roleIds));
        return candidates.stream()
                .filter(p -> !withExplicitAdmin.contains(p.getId()))
                .filter(p -> inheritedAdministratorSurvives(workspace, p))
                .map(ProjectRef::of)
                .sorted(Comparator.comparing(ProjectRef::key))
                .toList();
    }

    /** {@link #projectsLosingLastAdministrator} for a caller that has not loaded the list. */
    @Transactional(readOnly = true)
    public List<ProjectRef> projectsLosingLastAdministrator(Workspace workspace,
                                                            ProjectAccessProposal after) {
        return projectsLosingLastAdministrator(workspace,
                projectRepository.findAllByWorkspaceAndArchivedAtIsNull(workspace), after);
    }

    /** Refuse doors 7–9 with the per-door sentence; see {@code StrandedProjectsException}. */
    public void requireNoInheritedAdministratorsLost(List<ProjectRef> stranded,
                                                     String change, String remedy) {
        if (!stranded.isEmpty()) {
            throw StrandedProjectsException.inheritedAdministratorsLost(stranded, change, remedy);
        }
    }

    /**
     * Condition 1 of doors 6–9: does this project's <strong>mode-aware</strong> §5.2 fallback
     * hand out {@code project.member.manage}? Zero queries — a cached {@link RoleView} and one
     * bit test — which is what makes the whole of S7 §5 free until somebody uses the picker.
     */
    private boolean administersByInheritance(Workspace workspace, Project project) {
        var fallback = workspaceAccess.defaultProjectRole(workspace, project);
        return fallback != null && fallback.permissions().has(Permission.PROJECT_MEMBER_MANAGE);
    }

    /**
     * {@link #cannotBeStranded} with nobody excluded — the bulk form, where no single member
     * is leaving.
     *
     * <p>Split out rather than passing {@code null} to the per-member predicate, for the
     * reason {@link #lockAdmins} spells out at length: in JPQL {@code m.user.id <>
     * :excludedUserId} is UNKNOWN for every row when the bind is null, so the existence check
     * would silently mean "nobody inherits" instead of "exclude nobody" — the answer would be
     * wrong, in the refusing direction, for a reason two classes away.
     */
    private boolean inheritedAdministratorSurvives(Workspace workspace, Project project) {
        var fallback = workspaceAccess.defaultProjectRole(workspace, project);
        return fallback != null
               && fallback.permissions().has(Permission.PROJECT_MEMBER_MANAGE)
               && workspaceMemberRepository.existsActiveMemberWithoutProjectRole(
                       workspace, project, NOBODY_EXCLUDED);
    }

    /**
     * A user id that cannot exist, so {@code m.user.id <> :excludedUserId} is TRUE for every
     * row rather than UNKNOWN. The all-zero UUID is not a valid v7 and is never generated by
     * {@code @UuidGenerator(TIME)}.
     */
    private static final UUID NOBODY_EXCLUDED = new UUID(0L, 0L);

    // ------------------------------------------------------------------ internals

    /**
     * The PROJECT-scoped role ids — built-in templates plus this workspace's own custom
     * roles — that actually grant {@code project.member.manage}.
     *
     * <p>Read before the lock on purpose, and safely: this is <em>catalog</em> data ("which
     * roles carry the permission"), not the membership state the lock protects. A role
     * edited concurrently to gain or lose the permission is the same snapshot semantics
     * {@code RolePermissionCache} documents for every other authorization decision.
     */
    private Set<UUID> administeringRoleIds(UUID workspaceId) {
        return Set.copyOf(roleRepository.findIdsGranting(
                workspaceId, RoleScope.PROJECT, Permission.PROJECT_MEMBER_MANAGE));
    }

    /**
     * <strong>Can this project be stranded at all?</strong> No, when its §5.2 fallback role
     * grants {@code project.member.manage} <em>and</em> somebody would actually inherit it —
     * an ACTIVE workspace member, <em>other than the one leaving</em>, with no explicit
     * {@code project_members} row of their own.
     * In a {@code STRICT} workspace there is no fallback and the answer is always "yes, it
     * can".
     *
     * <p><strong>Both halves are load-bearing.</strong> "The default grants it" alone is the
     * tempting, cheaper rule and it is wrong in the dangerous direction: inheritance applies
     * only to members with no row, so in a project where every member has an explicit
     * narrower row, nobody holds the permission and that rule would wave through precisely
     * the stranding this class exists to refuse. The existence check turns a plausible
     * approximation into a proof, and costs one query on a path that is rare by
     * construction — it is only consulted for a project that would otherwise be refused.
     *
     * @param leavingUserId the member being removed; <strong>never null</strong> — both
     *        call sites have a concrete member in hand, and a null bind would make the
     *        existence check answer {@code false} for every row (see {@link #lockAdmins})
     */
    private boolean cannotBeStranded(Workspace workspace, Project project, UUID leavingUserId) {
        var fallback = workspaceAccess.defaultProjectRole(workspace, project);
        return fallback != null
               && fallback.permissions().has(Permission.PROJECT_MEMBER_MANAGE)
               && workspaceMemberRepository.existsActiveMemberWithoutProjectRole(
                       workspace, project, leavingUserId);
    }

    private static Set<UUID> userIdsOf(List<ProjectMember> rows) {
        var userIds = new HashSet<UUID>(rows.size());
        rows.forEach(m -> userIds.add(m.getUser().getId()));
        return Set.copyOf(userIds);
    }

    /**
     * The projects one {@link #lockStrandedProjects} call found, with their administrator
     * rows locked for the rest of the transaction.
     *
     * <p><strong>A class with a private constructor, not a record</strong>, and that is the
     * whole point of it. The H1 safety argument is that the set {@link #adoptAll} grants
     * from is server-derived under the lock rather than whatever a client claims it was
     * refused — and a {@code record} cannot carry that argument, because the JLS requires a
     * record's canonical constructor to be at least as accessible as the record itself. As a
     * public record this was forgeable from client input in one line, and the type was
     * documented convention wearing a type's clothes. Now the only code that can make one is
     * the guard, whose every path takes the lock first.
     *
     * <p>Tests obtain one the same way production does: call {@link #lockStrandedProjects}
     * inside a transaction. There is deliberately no test seam — a factory that exists "just
     * for tests" is the same hole with an apologetic name.
     */
    public static final class StrandedProjects {

        private final List<ProjectRef> projects;

        private StrandedProjects(List<ProjectRef> projects) {
            this.projects = List.copyOf(projects);
        }

        public List<ProjectRef> projects() {
            return projects;
        }

        public boolean isEmpty() {
            return projects.isEmpty();
        }
    }

    /**
     * A project's administrators as of a {@link #lockAdmins} call, with those rows locked
     * for the rest of the transaction.
     *
     * <p>Private constructor for {@link StrandedProjects}' reason, and HD-132's
     * {@code LockedOwners} before it: the only way to obtain one is a method that always
     * takes the lock, so {@link #requireNotLastAdmin} cannot be handed an <em>unlocked</em>
     * answer — by accident or otherwise. See the class javadoc for why those methods are
     * {@link Propagation#MANDATORY}: without it the promise this type makes is one its
     * caller can still break, from the other end.
     */
    public static final class LockedProjectAdmins {

        private final Set<UUID> userIds;

        private LockedProjectAdmins(Set<UUID> userIds) {
            this.userIds = Set.copyOf(userIds);
        }

        public Set<UUID> userIds() {
            return userIds;
        }

        /**
         * <strong>The rule, in one place.</strong> Removing this member costs the project
         * its last administrator exactly when they are one and there is no other. A project
         * with no administrator at all is untouched by this — it is already in the state the
         * guard prevents and cannot be pushed further into it.
         */
        public boolean wouldStrand(UUID targetUserId) {
            return userIds.contains(targetUserId) && userIds.size() <= 1;
        }
    }
}
