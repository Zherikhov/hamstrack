package com.hamstrack.workspace.repository;

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
}
