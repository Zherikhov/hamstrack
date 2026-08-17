package com.hamstrack.common.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class SseRegistry {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules(); // registers JavaTimeModule for Instant serialization

    // workspaceId -> list of connected (userId, emitter) pairs
    private final Map<UUID, List<UserEmitter>> connections = new ConcurrentHashMap<>();

    public record UserEmitter(UUID userId, SseEmitter emitter) {}

    // Finite timeout: the browser's EventSource reconnects automatically, and each
    // reconnect re-runs the membership check in SseController — so a member removed
    // from the workspace loses the stream within this window instead of never
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    public SseEmitter subscribe(UUID workspaceId, UUID userId) {
        var emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        var entry = new UserEmitter(userId, emitter);

        connections.computeIfAbsent(workspaceId, k -> new CopyOnWriteArrayList<>()).add(entry);

        Runnable cleanup = () -> {
            var list = connections.get(workspaceId);
            if (list != null) list.remove(entry);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Send initial ping so the browser knows the connection is live
        send(emitter, "ping", Map.of("ok", true));
        return emitter;
    }

    /**
     * Send an event to every user connected to the workspace, immediately.
     *
     * <p>Since HD-80 the after-commit timing is owned by the caller: the domain-event
     * {@code @TransactionalEventListener(AFTER_COMMIT)} in {@code common.event} only
     * invokes this once the publishing transaction has committed (or, for the
     * non-transactional attachment-upload path, via {@code fallbackExecution}). So this
     * method no longer wraps the send in its own {@code afterCommit} deferral — it just
     * delivers to the connected emitters now.
     */
    public void broadcast(UUID workspaceId, String event, Object data) {
        var list = connections.get(workspaceId);
        if (list == null || list.isEmpty()) return;
        for (var ue : list) {
            send(ue.emitter(), event, data);
        }
    }

    /**
     * Close every stream one user holds on one workspace, now — the offboarding half of
     * revocation (HD-132).
     *
     * <p>Membership is checked at <em>subscribe</em> time and never again, so without this a
     * removed member keeps receiving live workspace events until their emitter hits
     * {@link #EMITTER_TIMEOUT_MS} — up to 30 minutes. What leaks is activity metadata (which
     * projects are live, which issues are moving), not content: the payload is only
     * {@code {projectId, issueNumber}} and any REST fetch it prompts 404s at the tenancy
     * gate. But "we revoked their access" being false for half an hour is exactly the claim
     * that matters when someone is offboarded during an incident. Completing the emitter also
     * costs them nothing they should keep: the browser's {@code EventSource} reconnects, and
     * that reconnect re-runs {@code SseController}'s membership check and gets the 404.
     *
     * <p><strong>Call this AFTER COMMIT, never inline</strong> — see
     * {@code SseEventListener.onWorkspaceMemberRemoved}. Dropping a live stream for a removal
     * that then rolls back would be an unexplainable disconnect for a member who is still a
     * member.
     *
     * <p>Iterating while {@code complete()} triggers {@code onCompletion} → {@code cleanup} →
     * {@code list.remove(entry)} is safe: the list is a {@link CopyOnWriteArrayList}, whose
     * iterator walks an immutable snapshot.
     *
     * @return how many emitters were closed (0 when the user had no open stream — the
     *         overwhelmingly common case, and not an error)
     */
    public int disconnectUser(UUID workspaceId, UUID userId) {
        var list = connections.get(workspaceId);
        if (list == null || list.isEmpty()) return 0;
        int closed = 0;
        for (var ue : list) {
            if (!ue.userId().equals(userId)) continue;
            closed++;
            try {
                ue.emitter().complete();
            } catch (Exception e) {
                // Already completed/timed out, or the pipe is broken. Either way this
                // emitter is finished, which is the outcome we wanted — and one dead
                // stream must never abort the loop for the user's other tabs.
                log.debug("SSE emitter for user {} in workspace {} was already closed", userId, workspaceId, e);
            }
        }
        if (closed > 0) {
            log.info("SSE: closed {} stream(s) for user {} removed from workspace {}",
                    closed, userId, workspaceId);
        }
        return closed;
    }

    /** Send an event only to a specific user's connections in the workspace, immediately (see {@link #broadcast}). */
    public void sendToUser(UUID workspaceId, UUID userId, String event, Object data) {
        var list = connections.get(workspaceId);
        if (list == null || list.isEmpty()) return;
        for (var ue : list) {
            if (ue.userId().equals(userId)) {
                send(ue.emitter(), event, data);
            }
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(json));
        } catch (Exception e) {
            // IOException on a broken pipe, but also the unchecked
            // IllegalStateException SseEmitter.send throws once the emitter has
            // completed/timed out. Swallow both here so a single dead emitter
            // never aborts the broadcast loop for the remaining subscribers —
            // completeWithError re-fires onError, whose cleanup removes it.
            emitter.completeWithError(e);
        }
    }
}
