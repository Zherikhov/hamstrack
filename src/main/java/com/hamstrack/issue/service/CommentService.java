package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.event.CommentAdded;
import com.hamstrack.common.event.CommentDeleted;
import com.hamstrack.common.event.CommentUpdated;
import com.hamstrack.issue.dto.CommentResponse;
import com.hamstrack.issue.dto.CreateCommentRequest;
import com.hamstrack.issue.entity.CommentMention;
import com.hamstrack.issue.entity.IssueComment;
import com.hamstrack.issue.exception.CommentNotFoundException;
import com.hamstrack.issue.repository.CommentMentionRepository;
import com.hamstrack.issue.repository.IssueCommentRepository;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.notification.service.NotificationService;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final WorkspaceAccessService workspaceAccess;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final IssueCommentRepository commentRepository;
    private final CommentMentionRepository mentionRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CommentResponse create(User actor, UUID workspaceId, UUID projectId, long issueNumber, CreateCommentRequest req) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
        requireNotArchived(issue);
        var comment = new IssueComment();
        comment.setIssue(issue);
        comment.setAuthor(actor);
        comment.setBody(req.body());
        commentRepository.save(comment);

        // Notify @mentioned members (none previously mentioned on a new comment)
        applyMentions(comment, req.body(), actor, workspaceId, projectId, issueNumber, Set.of());

        eventPublisher.publishEvent(new CommentAdded(workspaceId, projectId, issueNumber));
        return CommentResponse.of(comment);
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(User actor, UUID workspaceId, UUID projectId,
                                              long issueNumber, org.springframework.data.domain.Pageable pageable) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
        return PageResponse.of(commentRepository.findForIssueWithAuthor(issue, pageable).map(CommentResponse::of));
    }

    @Transactional
    public CommentResponse update(User actor, UUID workspaceId, UUID projectId, long issueNumber,
                                  UUID commentId, CreateCommentRequest req) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
        requireNotArchived(issue);
        var comment = findCommentOnIssue(commentId, issue);
        if (!comment.getAuthor().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        comment.setBody(req.body());
        commentRepository.save(comment);

        // Re-parse @mentions: notify anyone newly mentioned by the edit (skip those
        // already mentioned so an edit can't re-notify the same person)
        var already = mentionRepository.findAllByComment(comment).stream()
                .map(m -> m.getUser().getId()).collect(Collectors.toSet());
        applyMentions(comment, req.body(), actor, workspaceId, projectId, issueNumber, already);

        eventPublisher.publishEvent(new CommentUpdated(workspaceId, projectId, issueNumber));
        return CommentResponse.of(comment);
    }

    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long issueNumber, UUID commentId) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
        requireNotArchived(issue);
        var comment = findCommentOnIssue(commentId, issue);
        if (!comment.getAuthor().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        comment.setDeletedAt(Instant.now());
        commentRepository.save(comment);

        eventPublisher.publishEvent(new CommentDeleted(workspaceId, projectId, issueNumber));
    }

    /**
     * Extract mentioned users by prefix-matching member display names after each '@'.
     * A regex over "word chars and spaces" can't work here: it grabs the longest run
     * ("@John Doe thanks" → "John Doe thanks"), which then matches no member. Instead,
     * at each '@' the longest matching display name wins, so "@John Doe" prefers the
     * member "John Doe" over "John".
     */
    /**
     * Parse @mentions in {@code body} and, for each mentioned member who isn't the
     * actor and wasn't {@code alreadyMentioned}, record a {@link CommentMention}
     * and send a notification. Shared by create (empty already-set) and edit
     * (already-set = existing mentions, so an edit only notifies the newly added).
     */
    private void applyMentions(IssueComment comment, String body, User actor,
                               UUID workspaceId, UUID projectId, long issueNumber,
                               Set<UUID> alreadyMentioned) {
        var members = workspaceMemberRepository.findAllByWorkspaceWithUser(comment.getIssue().getWorkspace());
        for (var mentioned : parseMentions(body, members)) {
            if (mentioned.getId().equals(actor.getId()) || alreadyMentioned.contains(mentioned.getId())) {
                continue;
            }
            var m = new CommentMention();
            m.setComment(comment);
            m.setUser(mentioned);
            mentionRepository.save(m);

            String link = "/w/" + workspaceId + "/p/" + projectId + "?issue=" + issueNumber;
            notificationService.create(
                    mentioned, workspaceId, "MENTIONED",
                    actor.getDisplayName() + " mentioned you",
                    body.length() > 120 ? body.substring(0, 120) + "…" : body,
                    link);
        }
    }

    private List<User> parseMentions(String body, List<WorkspaceMember> members) {
        var result = new java.util.LinkedHashSet<User>();
        var lowerBody = body.toLowerCase();
        for (int at = lowerBody.indexOf('@'); at >= 0; at = lowerBody.indexOf('@', at + 1)) {
            User best = null;
            int bestLen = 0;
            for (var member : members) {
                var name = member.getUser().getDisplayName();
                if (name == null || name.isBlank() || name.length() <= bestLen) continue;
                var lowerName = name.toLowerCase();
                if (!lowerBody.startsWith(lowerName, at + 1)) continue;
                // Require a non-alphanumeric boundary so "@JohnDoe2" doesn't mention "JohnDoe"
                int end = at + 1 + lowerName.length();
                if (end < lowerBody.length() && Character.isLetterOrDigit(lowerBody.charAt(end))) continue;
                best = member.getUser();
                bestLen = lowerName.length();
            }
            if (best != null) result.add(best);
        }
        return new java.util.ArrayList<>(result);
    }

    private void requireNotArchived(com.hamstrack.issue.entity.Issue issue) {
        if (issue.getProject().isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
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

}
