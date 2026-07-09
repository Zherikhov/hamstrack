package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.sse.SseRegistry;
import com.hamstrack.issue.dto.CommentResponse;
import com.hamstrack.issue.dto.CreateCommentRequest;
import com.hamstrack.issue.entity.CommentMention;
import com.hamstrack.issue.entity.IssueComment;
import com.hamstrack.issue.exception.CommentNotFoundException;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.repository.CommentMentionRepository;
import com.hamstrack.issue.repository.IssueCommentRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.notification.service.NotificationService;
import com.hamstrack.project.exception.ProjectNotFoundException;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CommentService {

    private static final Pattern MENTION_PATTERN = Pattern.compile("@([\\w](?:[\\w\\s]*[\\w])?)");

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ProjectRepository projectRepository;
    private final IssueRepository issueRepository;
    private final IssueCommentRepository commentRepository;
    private final CommentMentionRepository mentionRepository;
    private final NotificationService notificationService;
    private final SseRegistry sseRegistry;

    @Transactional
    public CommentResponse create(User actor, UUID workspaceId, UUID projectId, long issueNumber, CreateCommentRequest req) {
        var issue = resolveIssue(actor, workspaceId, projectId, issueNumber);
        var comment = new IssueComment();
        comment.setIssue(issue);
        comment.setAuthor(actor);
        comment.setBody(req.body());
        commentRepository.save(comment);

        // Parse @mentions and notify mentioned workspace members
        var members = workspaceMemberRepository
                .findAllByWorkspace(issue.getWorkspace());
        parseMentions(req.body(), members).forEach(mentioned -> {
            if (mentioned.getId().equals(actor.getId())) return; // don't notify yourself
            var m = new CommentMention();
            m.setComment(comment);
            m.setUser(mentioned);
            mentionRepository.save(m);

            String link = "/w/" + workspaceId + "/p/" + projectId + "?issue=" + issueNumber;
            notificationService.create(
                    mentioned, workspaceId,
                    "MENTIONED",
                    actor.getDisplayName() + " mentioned you",
                    req.body().length() > 120 ? req.body().substring(0, 120) + "…" : req.body(),
                    link
            );
        });

        sseRegistry.broadcast(workspaceId, "COMMENT_ADDED",
                Map.of("projectId", projectId.toString(), "issueNumber", issueNumber));
        return CommentResponse.of(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> list(User actor, UUID workspaceId, UUID projectId, long issueNumber) {
        var issue = resolveIssue(actor, workspaceId, projectId, issueNumber);
        return commentRepository.findAllByIssueAndDeletedAtIsNullOrderByCreatedAtAsc(issue).stream()
                .map(CommentResponse::of)
                .toList();
    }

    @Transactional
    public CommentResponse update(User actor, UUID workspaceId, UUID projectId, long issueNumber,
                                  UUID commentId, CreateCommentRequest req) {
        var issue = resolveIssue(actor, workspaceId, projectId, issueNumber);
        var comment = findCommentOnIssue(commentId, issue);
        if (!comment.getAuthor().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        comment.setBody(req.body());
        commentRepository.save(comment);
        return CommentResponse.of(comment);
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long issueNumber, UUID commentId) {
        var issue = resolveIssue(actor, workspaceId, projectId, issueNumber);
        var comment = findCommentOnIssue(commentId, issue);
        if (!comment.getAuthor().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        comment.setDeletedAt(Instant.now());
        commentRepository.save(comment);
    }

    /** Extract mentioned users by matching @Name against workspace member display names. */
    private List<User> parseMentions(String body, List<WorkspaceMember> members) {
        var matcher = MENTION_PATTERN.matcher(body);
        var result = new java.util.ArrayList<User>();
        while (matcher.find()) {
            var name = matcher.group(1).trim();
            members.stream()
                    .filter(m -> m.getUser().getDisplayName().equalsIgnoreCase(name))
                    .map(WorkspaceMember::getUser)
                    .findFirst()
                    .ifPresent(result::add);
        }
        return result;
    }

    // The comment must belong to the issue resolved from the URL — a global findById
    // would let an author edit their comments in workspaces they were removed from
    private IssueComment findCommentOnIssue(UUID commentId, com.hamstrack.issue.entity.Issue issue) {
        var comment = commentRepository.findById(commentId)
                .filter(c -> c.getIssue().getId().equals(issue.getId()))
                .orElseThrow(CommentNotFoundException::new);
        if (comment.isDeleted()) throw new CommentNotFoundException();
        return comment;
    }

    private com.hamstrack.issue.entity.Issue resolveIssue(User actor, UUID workspaceId, UUID projectId, long issueNumber) {
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        var project = projectRepository.findByIdAndWorkspace(projectId, workspace)
                .orElseThrow(ProjectNotFoundException::new);
        return issueRepository.findByProjectAndNumber(project, issueNumber)
                .orElseThrow(IssueNotFoundException::new);
    }
}
