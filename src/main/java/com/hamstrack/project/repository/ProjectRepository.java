package com.hamstrack.project.repository;

import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByWorkspace(Workspace workspace);

    Optional<Project> findByIdAndWorkspace(UUID id, Workspace workspace);

    boolean existsByWorkspaceAndKey(Workspace workspace, String key);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Project p SET p.issueSeq = p.issueSeq + 1 WHERE p.id = :id")
    void incrementIssueSeq(@Param("id") UUID id);
}
