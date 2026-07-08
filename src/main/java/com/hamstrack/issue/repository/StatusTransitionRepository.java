package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusTransition;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatusTransitionRepository extends JpaRepository<StatusTransition, UUID> {
    List<StatusTransition> findAllByWorkspaceOrderByCreatedAtAsc(Workspace workspace);
    List<StatusTransition> findAllByWorkspaceAndFromStatus(Workspace workspace, Status fromStatus);
    boolean existsByWorkspace(Workspace workspace);
}
