package com.hamstrack.project.service;

import com.hamstrack.auth.entity.User;
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
import com.hamstrack.workspace.repository.RoleRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.service.RoleCatalog;
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
        for (var project : projects) {
            var row = byProject.get(project.getId());
            if (row != null && !adoptionRole.permissions().firstNotCovered(
                    roleCatalog.view(row.getRole().getId()).permissions()).isEmpty()) {
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
        if (!blocked.isEmpty()) {
            // Refuse the WHOLE removal rather than adopt what we can: skipping a blocked
            // project and proceeding would strand it, which is the outcome this class
            // exists to prevent — and the actor's own row is the reason it cannot be
            // rescued automatically, so they are exactly the right person to be told.
            throw StrandedProjectsException.cannotAdopt(blocked, adoptionRole.name());
        }
        projectMemberRepository.saveAll(rows);
        return projects.stream()
                .map(ProjectRef::of)
                .sorted(Comparator.comparing(ProjectRef::key))
                .toList();
    }
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
