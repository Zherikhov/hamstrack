package com.hamstrack.workspace.repository;

import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceInviteRepository extends JpaRepository<WorkspaceInvite, UUID> {
    Optional<WorkspaceInvite> findByTokenHash(String tokenHash);

    // Pending invites addressed to a user's email (still filtered for expiry in
    // the service). Newest first so the invites screen shows recent ones on top.
    // JOIN FETCH the role (HD-123): the response renders its key on every row, and a
    // lazy @ManyToOne would make this list N+1.
    @Query("""
            SELECT i FROM WorkspaceInvite i JOIN FETCH i.role
             WHERE lower(i.email) = lower(:email) AND i.acceptedAt IS NULL
             ORDER BY i.createdAt DESC
            """)
    List<WorkspaceInvite> findByEmailIgnoreCaseAndAcceptedAtIsNullOrderByCreatedAtDesc(String email);

    /**
     * Revoke every UNACCEPTED invite for one address in ONE workspace — part of removing a
     * member (HD-132).
     *
     * <p><strong>Without this, removal does not remove.</strong> {@code inviteMember} only
     * refuses someone who is <em>already</em> a member and there is no
     * {@code UNIQUE(workspace_id, email)} on this table, so leftover pending invites are
     * normal (a re-send, a double submit). They are invisible while the person is a member
     * because {@code listPendingInvites} filters on {@code !existsByWorkspaceAndUser} — which
     * means the removal is exactly what makes them <em>re</em>appear, as a live join button on
     * the invitee's onboarding screen. {@code acceptInvite} likewise only blocks current
     * members, so one click puts them back in the workspace at the invite's role, with no
     * admin action and no notification. Offboarding has to close that door.
     *
     * <p>Deletes rather than expires: an invite is single-use and email-bound, an admin can
     * always re-send one, and {@code declineInvite} already establishes deletion as the way an
     * invite goes away. Accepted rows are left alone — they are the historical record of how
     * that person joined.
     *
     * <p>Matched case-insensitively because {@code workspace_invites.email} is stored
     * lower-cased by {@code inviteMember} but {@code users.email} is the authority here, and
     * {@code lower()} on both sides is the same comparison
     * {@code findByEmailIgnoreCaseAndAcceptedAtIsNullOrderByCreatedAtDesc} makes. Scoped by
     * workspace, so a removal in one tenant cannot cancel an invitation issued by another.
     *
     * <p>Plain {@code @Modifying}: the removal transaction holds no managed
     * {@code WorkspaceInvite}, and {@code clearAutomatically} would endanger the history rows
     * it still has to write.
     */
    @Modifying
    @Query("DELETE FROM WorkspaceInvite i WHERE i.workspace = :workspace "
            + "AND lower(i.email) = lower(:email) AND i.acceptedAt IS NULL")
    int deleteUnacceptedByWorkspaceAndEmail(@Param("workspace") Workspace workspace,
                                            @Param("email") String email);

    // ---------------------------------------------------- S4: role usage & reassign

    /**
     * Unaccepted invites of THIS workspace that would land on this role. Workspace-scoped
     * for the reason {@code WorkspaceMemberRepository.countByWorkspaceIdAndRoleId} gives:
     * built-in roles are shared rows.
     *
     * <p>Accepted invites are excluded — they are history, the membership row is the live
     * fact, and counting them would make a long-lived workspace's Member role permanently
     * "in use" no matter who is actually in it.
     */
    long countByWorkspaceIdAndRoleIdAndAcceptedAtIsNull(UUID workspaceId, UUID roleId);

    /** {@code roleId -> pending invites} for this workspace, in one statement. */
    @Query("""
            SELECT i.role.id, COUNT(i) FROM WorkspaceInvite i
             WHERE i.workspace.id = :workspaceId AND i.acceptedAt IS NULL
             GROUP BY i.role.id
            """)
    List<Object[]> countPendingByRole(@Param("workspaceId") UUID workspaceId);

    /**
     * Point <strong>every</strong> invite of this workspace at {@code toRole} before the old
     * role row is deleted — accepted ones included, and deliberately so.
     *
     * <p><strong>This disagrees with its sibling count on purpose, and the earlier javadoc
     * had the reason backwards</strong> (round-2 review). It used to claim accepted invites
     * were left alone because "rewriting history to name a role the invitee never got would
     * be a lie". That is a fair sentiment and an impossible one:
     * {@code workspace_invites.role_id} is a plain FK with {@code ON DELETE NO ACTION}
     * (V14), so an accepted row still pointing at the deleted role turns
     * {@code DELETE /roles/{id}?reassignToRoleId=} into a 500 the operator cannot escape
     * without SQL. The predicate was never in the query; only the claim was, and the claim
     * is what was wrong.
     *
     * <p>So the two methods answer two different questions, and both answers are right:
     * {@code countByWorkspaceIdAndRoleIdAndAcceptedAtIsNull} asks <em>what is live</em> (an
     * accepted invite is superseded by the membership row, and counting it would make a
     * long-lived workspace's Member role permanently "in use"), while this asks <em>what
     * still references the row</em>, which is every one of them. The consequence to accept
     * knowingly: an accepted invite's {@code role_id} is not a reliable record of what its
     * invitee was offered once the role it named is deleted. If that history ever needs to
     * be trustworthy it wants a denormalised role <em>key</em> column, not a live FK.
     *
     * <p>Plain {@code @Modifying}, and the rows are not materialized anywhere in this
     * transaction — see {@code WorkspaceMemberRepository.reassignRole}.
     */
    @Modifying
    @Query("""
            UPDATE WorkspaceInvite i SET i.role = :toRole
             WHERE i.workspace.id = :workspaceId AND i.role.id = :fromRoleId
            """)
    int reassignRole(@Param("workspaceId") UUID workspaceId,
                     @Param("fromRoleId") UUID fromRoleId,
                     @Param("toRole") Role toRole);
}
