package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.PrioritySet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrioritySetRepository extends JpaRepository<PrioritySet, UUID> {

    List<PrioritySet> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<PrioritySet> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<PrioritySet> findBySystemDefaultTrue();

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);
}
