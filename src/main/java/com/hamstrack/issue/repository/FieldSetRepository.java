package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.FieldSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldSetRepository extends JpaRepository<FieldSet, UUID> {

    List<FieldSet> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<FieldSet> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<FieldSet> findBySystemDefaultTrue();

    Optional<FieldSet> findByScopeWorkspaceIdIsNullAndName(String name);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);
}
