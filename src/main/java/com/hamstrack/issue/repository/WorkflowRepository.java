package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<Workflow> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<Workflow> findBySystemDefaultTrue();

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);
}
