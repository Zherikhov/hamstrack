package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Priority;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriorityRepository extends JpaRepository<Priority, UUID> {

    List<Priority> findAllByScopeWorkspaceIdIsNullOrderByPosition();

    Optional<Priority> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<Priority> findByScopeWorkspaceIdIsNullAndName(String name);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);
}
