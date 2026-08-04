package com.hamstrack.workspace.repository;

import com.hamstrack.workspace.entity.WorkspaceInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceInviteRepository extends JpaRepository<WorkspaceInvite, UUID> {
    Optional<WorkspaceInvite> findByTokenHash(String tokenHash);

    // Pending invites addressed to a user's email (still filtered for expiry in
    // the service). Newest first so the invites screen shows recent ones on top.
    List<WorkspaceInvite> findByEmailIgnoreCaseAndAcceptedAtIsNullOrderByCreatedAtDesc(String email);
}
