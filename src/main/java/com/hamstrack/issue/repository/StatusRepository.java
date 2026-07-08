package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Status;
import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatusRepository extends JpaRepository<Status, UUID> {

    List<Status> findAllByWorkspaceOrderByPosition(Workspace workspace);

    Optional<Status> findByIdAndWorkspace(UUID id, Workspace workspace);

    boolean existsByWorkspaceAndName(Workspace workspace, String name);
}
