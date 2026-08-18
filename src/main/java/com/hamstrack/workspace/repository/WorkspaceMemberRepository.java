package com.hamstrack.workspace.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    /**
     * The single hottest query in the product — every authenticated request that names a
     * workspace runs it (HD-123 §9.2).
     *
     * <p>{@code JOIN FETCH m.role} is load-bearing, not tidiness: {@code role} is a lazy
     * {@code @ManyToOne} and the resolver reads its id immediately, so without the fetch
     * every request would pay an extra SELECT to initialise the proxy. Written as an
     * explicit {@code @Query} rather than a derived method for exactly that reason —
     * a derived {@code findByWorkspaceAndUser} cannot express the fetch.
     */
    @Query("SELECT m FROM WorkspaceMember m JOIN FETCH m.role WHERE m.workspace = :workspace AND m.user = :user")
    Optional<WorkspaceMember> findByWorkspaceAndUser(Workspace workspace, User user);

    /**
     * The membership row of an arbitrary user id inside an <em>already-resolved</em>
     * workspace — the target of {@code PATCH}/{@code DELETE /members/{userId}} (HD-132).
     *
     * <p>Takes a bare {@code userId} on purpose: the admin paths must NOT do a global
     * {@code UserRepository.findById} first. Resolving the account before the membership
     * would make "unknown id" and "known id, not a member here" two different code paths,
     * which is one refactor away from becoming two different status codes — i.e. a
     * cross-tenant user-enumeration oracle. Empty is the single answer to both, and the
     * caller turns it into {@code WorkspaceMemberNotFoundException} (404).
     *
     * <p>{@code JOIN FETCH m.user} because both callers render the member back (or need
     * the account for the removal cascade), {@code JOIN FETCH m.role} because both read
     * the role id immediately — same reason as {@link #findByWorkspaceAndUser}.
     */
    @Query("SELECT m FROM WorkspaceMember m JOIN FETCH m.user JOIN FETCH m.role "
            + "WHERE m.workspace = :workspace AND m.user.id = :userId")
    Optional<WorkspaceMember> findByWorkspaceAndUserId(Workspace workspace, UUID userId);

    List<WorkspaceMember> findAllByWorkspace(Workspace workspace);

    /**
     * <strong>Is there anybody who would actually inherit this project's default role?</strong>
     * — i.e. an ACTIVE member of the workspace with no explicit {@code project_members} row
     * in that project (roles-permissions-proposal §5.2, step 2).
     *
     * <p>The one query behind {@code ProjectAdminGuard}'s refinement: when the workspace is
     * OPEN and the project's fallback role grants {@code project.member.manage}, no removal
     * can strand that project — <em>provided somebody is actually standing on the fallback</em>.
     * That proviso is the whole reason this exists. "The default grants it" alone is not a
     * proof: inheritance applies only to members with no row of their own, so in a project
     * where every member has an explicit narrower row, nobody holds the permission and the
     * naive form of the rule would wave through exactly the stranding the guard is for.
     *
     * <p><strong>{@code excludedUserId} is the person being removed.</strong> They must not
     * be counted as the member who would inherit: they are about to lose their membership, so
     * a "somebody still holds it" proof resting on them proves nothing. It is unreachable
     * today — the target only reaches this check while holding an explicit row in the very
     * project being examined, which disqualifies them anyway — but that is a NON-LOCAL
     * invariant, held together by facts two classes away, and it stops holding the moment the
     * workspace path learns to consider inherited administrators (booked as an S7
     * prerequisite). One predicate now, rather than a correctness argument that has to be
     * re-derived then.
     *
     * <p><strong>It must never be null</strong>, and the query deliberately does not
     * tolerate one: {@code m.user.id <> :excludedUserId} is UNKNOWN for every row against a
     * null bind, so this would answer {@code false} unconditionally — "nobody inherits" —
     * while looking like "exclude nobody". Callers that have no member to exclude have
     * already answered the question and must not call at all
     * ({@code ProjectAdminGuard.lockAdmins}).
     *
     * <p>Runs only for a project that would otherwise be refused, which is rare by
     * construction — this is not on any hot path.
     */
    @Query("""
            SELECT COUNT(m) > 0 FROM WorkspaceMember m
             WHERE m.workspace = :workspace
               AND m.user.status = com.hamstrack.auth.entity.UserStatus.ACTIVE
               AND m.user.id <> :excludedUserId
               AND NOT EXISTS (SELECT 1 FROM ProjectMember pm
                                WHERE pm.project = :project AND pm.user = m.user)
            """)
    boolean existsActiveMemberWithoutProjectRole(@Param("workspace") Workspace workspace,
                                                 @Param("project") Project project,
                                                 @Param("excludedUserId") UUID excludedUserId);

    boolean existsByWorkspaceAndUser(Workspace workspace, User user);

    /**
     * The members of this workspace holding one particular role, <strong>locked</strong> —
     * the last-Owner guard (HD-132), which is the only thing standing between a workspace
     * and having nobody able to administer it.
     *
     * <p><strong>Why a locking read and not {@code count(*)}.</strong> A plain count is a
     * read-then-act TOCTOU: {@code WorkspaceMember} carries no {@code @Version} and the
     * database has no "at least one owner" constraint, so two concurrent removals (or
     * demotions) of two different owners both read {@code 2}, both pass the guard, and both
     * commit — leaving a workspace with zero owners. That is not a race worth tolerating,
     * because <strong>the resulting state cannot be repaired through the API</strong>:
     * minting an OWNER requires the grant ceiling to admit OWNER, which only an OWNER
     * satisfies, and a {@code SystemRole.ADMIN} has no path either. Recovery would be a
     * hand-written {@code UPDATE} in psql.
     *
     * <p>{@code PESSIMISTIC_WRITE} makes the second transaction block until the first
     * commits and then re-read the committed truth, so it sees {@code 1} and gets the 409.
     * It locks the owner <em>rows</em> rather than the {@code workspaces} row so the lock is
     * scoped to exactly the invariant at risk: removing an ordinary member — the common case,
     * and the one that gets done in bulk during offboarding — takes no lock at all, because
     * the caller only reaches this method when the target is an owner.
     *
     * <p>{@code ORDER BY m.id} is not cosmetic: two transactions locking the same set in
     * different orders is how you turn a race into a deadlock. A deterministic order means
     * they queue instead.
     *
     * <p>Returns the rows rather than a count because PostgreSQL cannot apply
     * {@code FOR UPDATE} to an aggregate — the caller sizes the list. That costs nothing: a
     * workspace has a handful of owners, not millions.
     *
     * <p>Keyed by role <em>id</em> rather than by the legacy enum so it outlives S3: the
     * caller passes {@code BuiltInRoles.WORKSPACE_OWNER} today and, once custom roles can
     * carry {@code workspace.member.manage}, whichever role ids the ownership rule then
     * names.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM WorkspaceMember m WHERE m.workspace = :workspace AND m.role.id = :roleId "
            + "ORDER BY m.id")
    List<WorkspaceMember> lockAllByWorkspaceAndRoleId(Workspace workspace, UUID roleId);

    // Member listing + @mention parsing both read m.user for every row — fetch it
    // in one query rather than a lazy load per member. m.role likewise: the listing
    // renders the role name on every row.
    @Query("SELECT m FROM WorkspaceMember m JOIN FETCH m.user JOIN FETCH m.role WHERE m.workspace = :workspace")
    List<WorkspaceMember> findAllByWorkspaceWithUser(Workspace workspace);

    @Query("SELECT m FROM WorkspaceMember m JOIN FETCH m.workspace JOIN FETCH m.role WHERE m.user.id = :userId")
    List<WorkspaceMember> findAllByUserIdWithWorkspace(UUID userId);

    // ---------------------------------------------------- S4: role usage & reassign
    //
    // Every one of these carries a workspace filter. Built-in roles are SHARED rows
    // (workspace_id IS NULL), so an unscoped `WHERE role_id = :roleId` on this table would
    // publish every other tenant's headcount from a workspace-scoped endpoint — the single
    // most likely tenancy defect in this slice (§7.1 R6).

    /** How many members of THIS workspace hold this role. */
    long countByWorkspaceIdAndRoleId(UUID workspaceId, UUID roleId);

    /**
     * Does the caller hold this role in this workspace? The workspace half of the
     * self-held delete refusal (§7.1 R5) — deleting a role you hold and reassigning it in
     * bulk is a self-escalation route no per-assignment ceiling can see.
     */
    boolean existsByWorkspaceIdAndUserIdAndRoleId(UUID workspaceId, UUID userId, UUID roleId);

    /** {@code roleId -> headcount} for every role in use in this workspace, in one statement. */
    @Query("""
            SELECT m.role.id, COUNT(m) FROM WorkspaceMember m
             WHERE m.workspace.id = :workspaceId
             GROUP BY m.role.id
            """)
    List<Object[]> countMembersByRole(@Param("workspaceId") UUID workspaceId);

    /**
     * <strong>{@code roleId -> ACTIVE headcount}</strong> for this workspace — the second of
     * the impact preview's four reads (S7 §4.2).
     *
     * <p><strong>A sibling of {@link #countMembersByRole}, not a reuse of it</strong>, and the
     * difference is the whole point: that one answers "is this role in <em>use</em>?" and so
     * deliberately counts everybody, including DISABLED accounts, because a role held by a
     * deactivated member is still held. This one answers "how many people <em>lose access</em>?"
     * and a deactivated account is not a person who loses access — it also cannot log in, so
     * counting one would inflate the number the whole decision is made on. Every other
     * administrator count in the codebase is ACTIVE-filtered
     * ({@code lockAllByProjectAndRoleIdIn}, {@code findProjectIdsAdministeredOnlyBy},
     * {@link #existsActiveMemberWithoutProjectRole}); this matches them.
     *
     * <p>Workspace-filtered, like every count on this table: built-in roles are shared rows
     * ({@code workspace_id IS NULL}), so an unscoped {@code GROUP BY role_id} would publish
     * another tenant's headcount (§8).
     */
    @Query("""
            SELECT m.role.id, COUNT(m) FROM WorkspaceMember m
             WHERE m.workspace.id = :workspaceId
               AND m.user.status = com.hamstrack.auth.entity.UserStatus.ACTIVE
             GROUP BY m.role.id
            """)
    List<Object[]> countActiveMembersByRole(@Param("workspaceId") UUID workspaceId);

    /**
     * Point this workspace's holders of {@code fromRoleId} at {@code toRole}, as one
     * statement, before the role row is deleted.
     *
     * <p><strong>Plain {@code @Modifying}</strong>: nothing in the deleting transaction
     * re-reads these rows, so {@code clearAutomatically} would buy nothing and
     * {@code em.clear()} mid-transaction is the documented way to lose pending inserts. It
     * is also safe as a bulk UPDATE precisely because the rows are NOT materialized here —
     * had they been loaded (to write history, say) the L1 cache would hold stale copies
     * that a later flush would write back.
     */
    @Modifying
    @Query("""
            UPDATE WorkspaceMember m SET m.role = :toRole
             WHERE m.workspace.id = :workspaceId AND m.role.id = :fromRoleId
            """)
    int reassignRole(@Param("workspaceId") UUID workspaceId,
                     @Param("fromRoleId") UUID fromRoleId,
                     @Param("toRole") Role toRole);
}
