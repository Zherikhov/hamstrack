package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.IssueTypeSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueTypeSetRepository extends JpaRepository<IssueTypeSet, UUID> {

    List<IssueTypeSet> findAllByScopeWorkspaceIdIsNullOrderByName();

    Optional<IssueTypeSet> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<IssueTypeSet> findBySystemDefaultTrue();

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);
}
