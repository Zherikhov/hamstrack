package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatusRepository extends JpaRepository<Status, UUID> {

    // Global catalog (scope IS NULL); workspace-scoped rows are a future feature
    List<Status> findAllByScopeWorkspaceIdIsNullOrderByPosition();

    Optional<Status> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<Status> findByScopeWorkspaceIdIsNullAndName(String name);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);
}
