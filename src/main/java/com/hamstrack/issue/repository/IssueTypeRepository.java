package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.IssueType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueTypeRepository extends JpaRepository<IssueType, UUID> {

    List<IssueType> findAllByScopeWorkspaceIdIsNullOrderByPosition();

    Optional<IssueType> findByIdAndScopeWorkspaceIdIsNull(UUID id);

    Optional<IssueType> findByScopeWorkspaceIdIsNullAndName(String name);

    boolean existsByScopeWorkspaceIdIsNullAndName(String name);
}
