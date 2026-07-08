package com.hamstrack.workspace.repository;

import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    @Query("SELECT w FROM Workspace w JOIN WorkspaceMember m ON m.workspace = w WHERE m.user.id = :userId")
    List<Workspace> findAllByMemberId(UUID userId);

    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
