package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.event.CommentAdded;
import com.hamstrack.common.event.CommentDeleted;
import com.hamstrack.common.event.CommentUpdated;
import com.hamstrack.common.security.Permission;
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
import java.util.Locale;
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

    /**
     * <strong>Permission: {@code comment.create}</strong> (HD-126 S3, §10.2). This was
     * <em>ungated entirely</em> until S3 — any workspace member could comment on any issue
     * in any project — so it is the first gate a project Viewer meets here. Δ-free for
     * every built-in that could comment before: Contributor and Commenter both hold it.
     */
    @Transactional
    public CommentResponse create(User actor, UUID workspaceId, UUID projectId, long issueNumber, CreateCommentRequest req) {
        var ctx = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber);
        // Permission first, project state second (§10.3.6): a 403 must never depend on
        // whether the project happens to be archived.
        ctx.permissions().require(Permission.COMMENT_CREATE);
        var issue = ctx.issue();
        requireNotArchived(issue);
        var comment = new IssueComment();
        comment.setIssue(issue);
        comment.setAuthor(actor);
        comment.setBody(req.body());
        commentRepository.save(comment);

        // Notify @mentioned members (none previously mentioned on a new comment)
        applyMentions(comment, req.body(), actor, projectId, issueNumber, Set.of());

        eventPublisher.publishEvent(new CommentAdded(workspaceId, projectId, issueNumber));
        return CommentResponse.of(comment);
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(User actor, UUID workspaceId, UUID projectId,
                                              long issueNumber, org.springframework.data.domain.Pageable pageable) {
        var issue = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber).issue();
        return PageResponse.of(commentRepository.findForIssueWithAuthor(issue, pageable).map(CommentResponse::of));
    }

    /**
     * <strong>Permission: {@code comment.edit}, own-only and never grantable
     * unrestricted</strong> (HD-126 S3, §10.2, §17.3). Putting words in someone else's
     * mouth is not a capability this product ships at any role, so the catalog entry is
     * {@code Own.REQUIRED} and even {@code project.administer.all} yields only the
     * own-only grant. Editing another person's comment therefore 403s for <em>everyone</em>,
     * exactly as it does today — the difference is that the refusal now says which
     * permission it wanted instead of being a bare, detail-less FORBIDDEN.
     */
    @Transactional
    public CommentResponse update(User actor, UUID workspaceId, UUID projectId, long issueNumber,
                                  UUID commentId, CreateCommentRequest req) {
        var ctx = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber);
        var issue = ctx.issue();
        // The comment has to be read before the permission can be evaluated — ownership is
        // a property of it — but the check still precedes the project-state check (§10.3.6)
        // and every mutation.
        var comment = findCommentOnIssue(commentId, issue);
        ctx.permissions().require(Permission.COMMENT_EDIT, isAuthor(comment, actor));
        requireNotArchived(issue);
        comment.setBody(req.body());
        commentRepository.save(comment);

        // Re-parse @mentions: notify anyone newly mentioned by the edit (skip those
        // already mentioned so an edit can't re-notify the same person)
        var already = mentionRepository.findAllByComment(comment).stream()
                .map(m -> m.getUser().getId()).collect(Collectors.toSet());
        applyMentions(comment, req.body(), actor, projectId, issueNumber, already);

        eventPublisher.publishEvent(new CommentUpdated(workspaceId, projectId, issueNumber));
        return CommentResponse.of(comment);
    }

    /**
     * <strong>Permission: {@code comment.delete} — own, or unrestricted</strong>
     * (HD-126 S3, §10.2, §10.3.5).
     *
     * <p><strong>This is the epic's one accepted widening, and it becomes real here.</strong>
     * Until S3 the rule was authorship alone, so <em>nobody</em> could delete another
     * person's comment — not a project admin, not a workspace owner. The built-in Project
     * admin holds {@code comment.delete} unrestricted, so from this slice on they can, and
     * that is moderation: the most-requested comment permission, and the only cell
     * {@code BuiltInRoleSeedParityTest} declares as an intended divergence. It needs a
     * release note the day it ships.
     *
     * <p>Note the asymmetry with {@link #update}: <em>deleting</em> someone's comment is
     * moderation, <em>editing</em> it is impersonation, so only the first is grantable
     * unrestricted (§17.3). A workspace Owner/Admin with no {@code project_members} row
     * still cannot moderate — {@code project.curate.all} does not carry
     * {@code comment.delete}.
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, long issueNumber, UUID commentId) {
        var ctx = workspaceAccess.requireIssue(actor, workspaceId, projectId, issueNumber);
        var issue = ctx.issue();
        var comment = findCommentOnIssue(commentId, issue);
        ctx.permissions().require(Permission.COMMENT_DELETE, isAuthor(comment, actor));
        requireNotArchived(issue);
        comment.setDeletedAt(Instant.now());
        commentRepository.save(comment);

        eventPublisher.publishEvent(new CommentDeleted(workspaceId, projectId, issueNumber));
    }

    /**
     * Ownership of a comment (§6.4) is its <strong>author</strong> — computed here and
     * never by {@code PermissionSet}, which knows nothing about domain objects.
     */
    private boolean isAuthor(IssueComment comment, User actor) {
        return comment.getAuthor().getId().equals(actor.getId());
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
                               UUID projectId, long issueNumber,
                               Set<UUID> alreadyMentioned) {
        // The workspace comes from the resolved issue, and it is what both the row and the
        // link are built from (HD-135 §4.7): a producer that re-derives the tenant from a
        // path variable is a producer whose two copies can one day disagree.
        var workspace = comment.getIssue().getWorkspace();
        var members = workspaceMemberRepository.findAllByWorkspaceWithUser(workspace);
        for (var mentioned : parseMentions(body, members)) {
            if (mentioned.getId().equals(actor.getId()) || alreadyMentioned.contains(mentioned.getId())) {
                continue;
            }
            var m = new CommentMention();
            m.setComment(comment);
            m.setUser(mentioned);
            mentionRepository.save(m);

            // V20's backfill parses the workspace id back out of this string, so the shape
            // is load-bearing across a language boundary that no compiler checks.
            // V20NotificationsWorkspaceScopeTest is what relates the two.
            String link = "/w/" + workspace.getId() + "/p/" + projectId + "?issue=" + issueNumber;
            notificationService.create(
                    mentioned, workspace, "MENTIONED",
                    actor.getDisplayName() + " mentioned you",
                    body.length() > 120 ? body.substring(0, 120) + "…" : body,
                    link);
        }
    }

    /** One workspace member, with their display name lowercased exactly once. */
    private record Mentionable(String lowerName, User user) {}

    /**
     * Longest-match {@code @mention} scan: at every {@code @} in the body, the longest member
     * display name that starts there and ends on a non-alphanumeric boundary wins.
     *
     * <p><strong>Cost, because this runs inside a {@code @Transactional} write holding a pooled
     * connection, on an endpoint any member can call.</strong> It is O(occurrences-of-{@code @} ×
     * members) by construction — {@code bestLen} prunes nothing on a body that matches nobody,
     * which is precisely the adversarial body. {@code CreateCommentRequest.body} is now bounded
     * at 10 000 (HD-171 §4.3), which caps the first factor; the second is the workspace's member
     * count, which no request bounds.
     *
     * <p><strong>The lowercasing is hoisted out of both loops</strong>, and that is the load-
     * bearing part rather than a tidy-up: it used to sit in the inner loop, so a body of 10 000
     * {@code @} characters in a 10 000-member workspace allocated a fresh lowercased copy of a
     * display name 10<sup>8</sup> times — seconds of CPU and gigabytes of transient garbage for
     * one comment. Lowercasing once per call makes the inner loop a prefix comparison with no
     * allocation at all.
     *
     * <p><strong>What the hoist actually leaves, measured rather than estimated</strong>, for the
     * adversarial 10 000-{@code @} body: ~3 ms at 100 members, ~78 ms at 1 000, and
     * <strong>~0.77 s at 10 000</strong>. An earlier round of this ticket said "roughly 0.1 s",
     * which was ~8× optimistic. That second is spent inside a {@code @Transactional} write holding
     * one of ten pooled connections ({@code maximum-pool-size} 10), and <strong>this endpoint is on
     * no rate-limit budget at all</strong> — so ten concurrent comment posts in a large workspace
     * hold the whole pool while they run. It is a member-only endpoint and the cost is
     * tenant-local, which is why the bound plus the hoist is where this stops for now.
     *
     * <p><strong>No mention-count cap on top, and the reason is which factor a cap would bound.</strong>
     * The cost is occurrences × members: a cap on {@code @} occurrences would bound the factor a
     * request already bounds (via the 10 000-character body) and leave untouched the one that
     * actually grows — the workspace's member count, which no request bounds and which a tenant
     * raises simply by hiring. The fix that does bound it is to bucket the candidate names by
     * length so each {@code @} compares against only the names that could match there, making the
     * scan independent of member count; that is filed separately and deliberately not done here.
     */
    private List<User> parseMentions(String body, List<WorkspaceMember> members) {
        var lowerBody = body.toLowerCase(Locale.ROOT);
        var candidates = new java.util.ArrayList<Mentionable>(members.size());
        for (var member : members) {
            var name = member.getUser().getDisplayName();
            if (name == null || name.isBlank()) continue;
            candidates.add(new Mentionable(name.toLowerCase(Locale.ROOT), member.getUser()));
        }
        var result = new java.util.LinkedHashSet<User>();
        for (int at = lowerBody.indexOf('@'); at >= 0; at = lowerBody.indexOf('@', at + 1)) {
            User best = null;
            int bestLen = 0;
            for (var candidate : candidates) {
                var lowerName = candidate.lowerName();
                if (lowerName.length() <= bestLen) continue;
                if (!lowerBody.startsWith(lowerName, at + 1)) continue;
                // Require a non-alphanumeric boundary so "@JohnDoe2" doesn't mention "JohnDoe"
                int end = at + 1 + lowerName.length();
                if (end < lowerBody.length() && Character.isLetterOrDigit(lowerBody.charAt(end))) continue;
                best = candidate.user();
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
