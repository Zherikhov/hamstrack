package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueTypeRepository extends JpaRepository<IssueType, UUID> {

    List<IssueType> findAllByWorkspaceOrderByPosition(Workspace workspace);

    Optional<IssueType> findByIdAndWorkspace(UUID id, Workspace workspace);

    boolean existsByWorkspaceAndName(Workspace workspace, String name);
}
