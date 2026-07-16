package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueFieldValue;
import org.springframework.data.jpa.repository.JpaRepository;

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

    void deleteAllByField(FieldDef field);
}
