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
