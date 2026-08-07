package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueFieldValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IssueFieldValueRepository extends JpaRepository<IssueFieldValue, UUID> {

    List<IssueFieldValue> findAllByIssue(Issue issue);

    // One query for a whole board/backlog page instead of one per issue
    List<IssueFieldValue> findAllByIssueIn(Collection<Issue> issues);

    Optional<IssueFieldValue> findByIssueAndField(Issue issue, FieldDef field);

    long countByField(FieldDef field);

    // Scope-filtered value count for admin usage display (see IssueRepository).
    // wsId/projectId = null → whole install; a workspace/project id → delegated
    // console, so the figure never spans other tenants.
    @Query("select count(v) from IssueFieldValue v where v.field = :field "
            + "and (:wsId is null or v.issue.workspace.id = :wsId) "
            + "and (:projectId is null or v.issue.project.id = :projectId)")
    long countByFieldScoped(@Param("field") FieldDef field,
                            @Param("wsId") UUID wsId, @Param("projectId") UUID projectId);

    void deleteAllByField(FieldDef field);
}
