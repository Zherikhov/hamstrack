package com.hamstrack.common.event;

import com.hamstrack.common.sse.SseRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * HD-80 regression for the domain-event bus. Services publish typed
 * {@link DomainEvent}s and a single {@link SseEventListener} performs the SSE
 * side effect via {@code @TransactionalEventListener(AFTER_COMMIT,
 * fallbackExecution = true)}. This test pins the two timing invariants the
 * refactor hinges on, at the event-bus boundary (publishing events directly and
 * verifying the listener's downstream {@link SseRegistry} call), so it stays
 * fast and independent of the HTTP/repository bootstrap.
 *
 * <p>{@link SseRegistry} is a Mockito bean so we can verify exactly which
 * {@code broadcast}/{@code sendToUser} calls the listener makes and how many
 * times — the emitter registry itself is exercised by
 * {@code SseRegistryBroadcastTest}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class SseEventListenerTransactionalTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate txTemplate;

    // Replaces the real SseRegistry so we can assert the listener's downstream
    // broadcast/sendToUser calls (count + args) without any live emitter.
    @MockitoBean SseRegistry sseRegistry;

    /**
     * THE risky behavior the refactor introduces. {@code AttachmentService.upload}
     * is intentionally non-transactional (blob I/O off the tx, HD-76), so its
     * {@link AttachmentAdded} is published with NO active transaction. A plain
     * {@code @TransactionalEventListener} would SILENTLY DROP that event; only
     * {@code fallbackExecution = true} makes the listener run inline. This test
     * publishes {@code AttachmentAdded} outside any transaction and asserts the
     * broadcast still fires — it fails loudly if anyone regresses the listener to a
     * plain {@code @TransactionalEventListener} (drop) and thus kills ATTACHMENT_ADDED SSE.
     */
    @Test
    void attachmentAddedPublishedWithNoActiveTransactionStillBroadcasts() {
        reset(sseRegistry);
        var ws = UUID.randomUUID();
        var project = UUID.randomUUID();
        long issueNumber = 42L;

        // No TransactionTemplate wrapper here → published with no active tx.
        publisher.publishEvent(new AttachmentAdded(ws, project, issueNumber));

        // fallbackExecution = true must have run the listener inline (not dropped it).
        verify(sseRegistry, times(1)).broadcast(eq(ws), eq("ATTACHMENT_ADDED"),
                eq(Map.of("projectId", project.toString(), "issueNumber", issueNumber)));
    }

    /**
     * Sanity-check the normal {@code @Transactional} path: an event published inside
     * a committing transaction is delivered EXACTLY ONCE (guards against a double-fire
     * from the refactor — e.g. leftover inline broadcast + listener, or the listener
     * firing both on the fallback path and on commit).
     */
    @Test
    void issueCreatedPublishedInTransactionFiresExactlyOnceOnCommit() {
        reset(sseRegistry);
        var ws = UUID.randomUUID();
        var project = UUID.randomUUID();
        long issueNumber = 7L;

        txTemplate.executeWithoutResult(status ->
                publisher.publishEvent(new IssueCreated(ws, project, issueNumber)));

        verify(sseRegistry, times(1)).broadcast(eq(ws), eq("ISSUE_CREATED"),
                eq(Map.of("projectId", project.toString(), "issueNumber", issueNumber)));
    }

    /**
     * AFTER_COMMIT semantics must survive the refactor: an event published inside a
     * transaction that ROLLS BACK must NOT broadcast (the side effect is bound to
     * commit, not to publish). Together with the two tests above this proves the
     * listener fires once-and-only-once on commit, inline on no-tx, and never on rollback.
     */
    @Test
    void issueCreatedPublishedInRolledBackTransactionDoesNotBroadcast() {
        reset(sseRegistry);
        var ws = UUID.randomUUID();
        var project = UUID.randomUUID();

        txTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new IssueCreated(ws, project, 99L));
            status.setRollbackOnly();
        });

        verify(sseRegistry, never()).broadcast(eq(ws), eq("ISSUE_CREATED"), org.mockito.ArgumentMatchers.any());
    }
}
