package com.hamstrack.project.repository;

import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByWorkspace(Workspace workspace);

    List<Project> findAllByWorkspaceAndArchivedAtIsNull(Workspace workspace);

    Optional<Project> findByIdAndWorkspace(UUID id, Workspace workspace);

    boolean existsByWorkspaceAndKey(Workspace workspace, String key);

    // UPDATE ... RETURNING gives each concurrent transaction its own value — an
    // increment followed by a separate read lets two creates observe the same seq
    @Query(value = "UPDATE projects SET issue_seq = issue_seq + 1 WHERE id = :id RETURNING issue_seq",
           nativeQuery = true)
    long incrementAndGetIssueSeq(@Param("id") UUID id);
}
