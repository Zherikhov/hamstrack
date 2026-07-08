package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.CommentMention;
import com.hamstrack.issue.entity.IssueComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CommentMentionRepository extends JpaRepository<CommentMention, UUID> {
    List<CommentMention> findAllByComment(IssueComment comment);
}
