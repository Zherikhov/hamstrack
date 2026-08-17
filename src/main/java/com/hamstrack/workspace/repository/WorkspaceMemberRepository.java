package com.hamstrack.workspace.repository;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
}
