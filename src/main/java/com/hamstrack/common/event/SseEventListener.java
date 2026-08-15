package com.hamstrack.common.event;

import com.hamstrack.common.sse.SseRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Performs the SSE side effect of every {@link DomainEvent} AFTER the publishing
 * transaction commits (HD-80). This replaces the inline
 * {@code sseRegistry.broadcast(...)}/{@code sendToUser(...)} calls that used to be
 * scattered across {@code IssueService}/{@code CommentService}/{@code AttachmentService}/
 * {@code NotificationService}, and replaces the manual {@code afterCommit} deferral
 * that {@code SseRegistry} used to do itself.
 *
 * <p>The wire contract is byte-for-byte preserved: each handler emits the same SSE
 * event name and the same {@code {projectId, issueNumber}} JSON payload as before.
 *
 * <p><b>{@code fallbackExecution = true} is load-bearing.</b> A plain
 * {@code @TransactionalEventListener} silently DROPS events published when no
 * transaction is in progress. {@code AttachmentService.upload} is intentionally
 * non-transactional (blob I/O runs off the tx, HD-76), so its {@link AttachmentAdded}
 * is published with no active tx — {@code fallbackExecution = true} makes the listener
 * run inline in that case, exactly reproducing the old
 * {@code SseRegistry.afterCommit}'s {@code else { action.run(); }} branch. Without it,
 * ATTACHMENT_ADDED SSE would never fire. For every other event a transaction is
 * active, so the listener fires only on commit (and never on rollback).
 */
@Component
@RequiredArgsConstructor
public class SseEventListener {

    private final SseRegistry sseRegistry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onIssueCreated(IssueCreated e) {
        broadcastIssueEvent(e.workspaceId(), "ISSUE_CREATED", e.projectId(), e.issueNumber());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onIssueUpdated(IssueUpdated e) {
        // changeSet is intentionally ignored here — the SSE payload is unchanged.
        broadcastIssueEvent(e.workspaceId(), "ISSUE_UPDATED", e.projectId(), e.issueNumber());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onIssueDeleted(IssueDeleted e) {
        broadcastIssueEvent(e.workspaceId(), "ISSUE_DELETED", e.projectId(), e.issueNumber());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCommentAdded(CommentAdded e) {
        broadcastIssueEvent(e.workspaceId(), "COMMENT_ADDED", e.projectId(), e.issueNumber());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCommentUpdated(CommentUpdated e) {
        broadcastIssueEvent(e.workspaceId(), "COMMENT_UPDATED", e.projectId(), e.issueNumber());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onCommentDeleted(CommentDeleted e) {
        broadcastIssueEvent(e.workspaceId(), "COMMENT_DELETED", e.projectId(), e.issueNumber());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onAttachmentAdded(AttachmentAdded e) {
        // upload() is non-transactional → this fires via the fallbackExecution path.
        broadcastIssueEvent(e.workspaceId(), "ATTACHMENT_ADDED", e.projectId(), e.issueNumber());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onAttachmentDeleted(AttachmentDeleted e) {
        broadcastIssueEvent(e.workspaceId(), "ATTACHMENT_DELETED", e.projectId(), e.issueNumber());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onNotificationRaised(NotificationRaised e) {
        sseRegistry.sendToUser(e.workspaceId(), e.recipientUserId(), "NOTIFICATION", e.payload());
    }

    // Identical wire payload to the old inline calls: {projectId: string, issueNumber: long}.
    private void broadcastIssueEvent(java.util.UUID workspaceId, String event,
                                     java.util.UUID projectId, long issueNumber) {
        sseRegistry.broadcast(workspaceId, event,
                Map.of("projectId", projectId.toString(), "issueNumber", issueNumber));
    }
}
