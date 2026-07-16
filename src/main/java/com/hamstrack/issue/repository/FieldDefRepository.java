package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.FieldDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldDefRepository extends JpaRepository<FieldDef, UUID> {

    List<FieldDef> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<FieldDef> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<FieldDef> findByScopeWorkspaceIdIsNullAndKey(String key);

    boolean existsByScopeWorkspaceIdIsNullAndKey(String key);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);
}
