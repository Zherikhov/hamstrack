package com.hamstrack.workspace.repository;

import com.hamstrack.workspace.entity.WorkspaceInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
