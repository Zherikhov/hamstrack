package com.hamstrack.common.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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

    /** Send an event to every user connected to the workspace. */
    public void broadcast(UUID workspaceId, String event, Object data) {
        afterCommit(() -> {
            var list = connections.get(workspaceId);
            if (list == null || list.isEmpty()) return;
            for (var ue : list) {
                send(ue.emitter(), event, data);
            }
        });
    }

    /** Send an event only to a specific user's connections in the workspace. */
    public void sendToUser(UUID workspaceId, UUID userId, String event, Object data) {
        afterCommit(() -> {
            var list = connections.get(workspaceId);
            if (list == null || list.isEmpty()) return;
            for (var ue : list) {
                if (ue.userId().equals(userId)) {
                    send(ue.emitter(), event, data);
                }
            }
        });
    }

    // Callers broadcast from inside @Transactional services. Sending immediately would
    // race clients against the commit (they refetch before the data is visible) and
    // would announce changes that a rollback then undoes — so defer until after commit.
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(event).data(json));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
