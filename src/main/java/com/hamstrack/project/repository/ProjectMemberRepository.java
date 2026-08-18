package com.hamstrack.project.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

    /**
     * The project half of the authorization resolution (HD-123 §9.2) — one indexed
     * lookup on the {@code (project_id, user_id)} unique key. Empty is a normal,
     * expected answer: a member with no explicit row inherits the project's default role
     * in an {@code OPEN} workspace, and today almost nobody has a row at all (§2.3).
     *
     * <p>{@code JOIN FETCH m.role} for the same reason as on the workspace side: the
     * resolver reads the role id immediately, and a lazy proxy would cost an extra SELECT
     * on every project-scoped request.
     */
    @Query("SELECT m FROM ProjectMember m JOIN FETCH m.role WHERE m.project = :project AND m.user = :user")
    Optional<ProjectMember> findByProjectAndUser(Project project, User user);

    List<ProjectMember> findAllByProject(Project project);

    // Listing renders m.user and the role name for every row — fetch both in one query,
    // not one per member
    @Query("SELECT m FROM ProjectMember m JOIN FETCH m.user JOIN FETCH m.role WHERE m.project = :project")
    List<ProjectMember> findAllByProjectWithUser(Project project);

    boolean existsByProjectAndUser(Project project, User user);

    /**
     * The project's members holding <em>any</em> of a set of roles, <strong>locked for the
     * rest of the transaction</strong> — the last-administrator guard's read
     * ({@code ProjectAdminGuard.lockAdmins}).
     *
     * <p>Straight from the HD-132 workspace twin, including its lesson: the lock is taken
     * <em>unconditionally and first</em>, never decided from an unlocked read of the
     * target's role, because that decision is itself the stale read the lock exists to
     * prevent — two concurrent removals would each see two admins and each remove one.
     * {@code ORDER BY m.id} so concurrent transactions take the rows in the same order and
     * <strong>queue</strong> instead of interleaving. Queueing is the ordinary outcome, and
     * it is precisely why the wait needs a bound ({@code LockTimeout}) — PostgreSQL waits
     * for a lock for ever. A cycle, and therefore a deadlock, is the rare branch; it is
     * safe when it happens only because PostgreSQL rolls one side back with nothing half
     * done. Rows, not {@code count(*)}: Postgres will not
     * attach {@code FOR UPDATE} to an aggregate, and a project has a handful of admins.
     *
     * <p><strong>A set of role ids, not one</strong> (HD-136). It was written keyed by role
     * <em>id</em> rather than by the legacy enum precisely so that this fix would be a
     * change of argument; the caller now passes every role id that grants
     * {@code project.member.manage} — built-in and custom alike — instead of the hardcoded
     * built-in Project admin. A guard that looks only for one role id does not protect a
     * project whose sole administrator holds a custom role, which is the same as not
     * protecting it at all.
     *
     * <p><strong>ACTIVE holders only.</strong> A DISABLED account cannot log in, so it can
     * no longer manage anybody: counting one would let a project whose sole administrator
     * has been deactivated pass a guard whose whole claim is that somebody can still
     * administer it — and disabling the account, which is the ordinary revocation step,
     * would not clear the refusal either. This does not merely over-refuse, it checks a
     * different invariant from the one it states, so it is filtered here rather than at the
     * call site. Expressed as a subquery, not a join, so {@code FOR UPDATE} does not also
     * lock the {@code users} rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ProjectMember m WHERE m.project = :project AND m.role.id IN :roleIds "
            + "AND m.user IN (SELECT u FROM User u WHERE u.status = com.hamstrack.auth.entity.UserStatus.ACTIVE) "
            + "ORDER BY m.id")
    List<ProjectMember> lockAllByProjectAndRoleIdIn(@Param("project") Project project,
                                                    @Param("roleIds") Collection<UUID> roleIds);

    /**
     * The same read across <strong>every project of one workspace</strong> in one statement
     * — what a workspace member removal has to know before it drops that member's
     * {@code project_members} rows (HD-136): which projects would be left with nobody able
     * to manage their membership.
     *
     * <p>One query rather than one per project, because the alternative is an N+1 of
     * <em>locking</em> reads on an endpoint that already fans out over a whole workspace.
     * The workspace is expressed as a subquery over {@code Project} rather than the path
     * {@code m.project.workspace} for a second reason beyond the one on
     * {@link #deleteAllByUserInWorkspace}: an implicit join would put {@code projects} into
     * the {@code FOR UPDATE}, locking every project row of the workspace against ordinary
     * project edits for the rest of the transaction.
     *
     * <p><strong>Lock order matches {@link #lockAllByProjectAndRoleIdIn} exactly</strong>
     * ({@code ORDER BY m.id}), so a workspace-wide removal and a single-project one take
     * the rows they share in the same order — the two <strong>queue</strong> and cannot
     * interleave into a bypass. That queue is the outcome to plan for, and the reason the
     * caller bounds its wait with {@code LockTimeout}; a deadlock is the rare branch, safe
     * when it does occur because PostgreSQL rolls one side back with nothing half done.
     *
     * <p>Two filters carried in the subquery, both for the reason its twin above gives:
     * <strong>ACTIVE</strong> members only (a disabled account administers nothing), and
     * <strong>live</strong> projects only — an archived project is frozen, so there is
     * nothing left to administer and it must not 409 somebody's offboarding a year later.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ProjectMember m WHERE m.role.id IN :roleIds "
            + "AND m.user IN (SELECT u FROM User u WHERE u.status = com.hamstrack.auth.entity.UserStatus.ACTIVE) "
            + "AND m.project IN (SELECT p FROM Project p WHERE p.workspace = :workspace "
            + "                   AND p.archivedAt IS NULL) "
            + "ORDER BY m.id")
    List<ProjectMember> lockAllByWorkspaceAndRoleIdIn(@Param("workspace") Workspace workspace,
                                                      @Param("roleIds") Collection<UUID> roleIds);

    /**
     * One membership query for a whole project list ({@code ProjectService.list}), which
     * is why that endpoint stays at a constant query count however many projects a
     * workspace has. {@code JOIN FETCH m.role} keeps it constant: without it the role of
     * each row would be a separate SELECT — the N+1 the batch exists to avoid.
     */
    @Query("SELECT m FROM ProjectMember m JOIN FETCH m.role WHERE m.user = :user AND m.project IN :projects")
    List<ProjectMember> findAllByUserAndProjectIn(User user, List<Project> projects);

    /**
     * Drop every explicit project membership one user holds inside ONE workspace — the
     * second half of a workspace member removal (HD-132). Removing the
     * {@code workspace_members} row alone would leave {@code project_members} rows behind
     * that grant a project role to somebody who can no longer resolve the workspace at
     * all; they would become live again the moment that person was re-invited, silently
     * restoring an access level nobody re-granted.
     *
     * <p><strong>The workspace scope is the whole point.</strong> A {@code User} is
     * global, so a {@code DELETE … WHERE m.user = :user} would evict them from every
     * tenant they belong to. The workspace is expressed as a subquery over
     * {@code Project} rather than the path {@code m.project.workspace}, because a bulk
     * JPQL DELETE cannot carry an implicit join.
     *
     * <p>Plain {@code @Modifying} — and since HD-136 the reason has changed, so do not
     * re-derive it from the old one. It used to be "nothing in this transaction has
     * materialized a {@code ProjectMember}"; that premise is now false, because
     * {@code ProjectAdminGuard} materialises the workspace’s administrator rows under lock
     * and its adoption path mutates or inserts one. The reason it is still right is the
     * other half: {@code clearAutomatically} would evict the history rows and the adopted
     * membership this transaction has yet to flush (the documented pending-insert trap), and
     * nothing here re-reads a {@code ProjectMember} after the delete — the adopted row
     * belongs to the ACTOR and this statement only ever removes the TARGET’s.
     */
    @Modifying
    @Query("DELETE FROM ProjectMember m WHERE m.user = :user "
            + "AND m.project IN (SELECT p FROM Project p WHERE p.workspace = :workspace)")
    int deleteAllByUserInWorkspace(@Param("user") User user, @Param("workspace") Workspace workspace);
}
