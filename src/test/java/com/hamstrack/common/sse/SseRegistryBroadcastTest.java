package com.hamstrack.common.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HD-74 regression: {@link SseRegistry#broadcast} must survive a single dead
 * emitter. The send path used to catch only {@link IOException}; the unchecked
 * {@link IllegalStateException} that {@code SseEmitter.send} throws once the
 * emitter has completed/timed out escaped the loop and aborted the whole
 * broadcast, starving every still-live subscriber. The catch was broadened to
 * {@code Exception} — a dead emitter is now completed-with-error (which fires
 * its onError cleanup) and the loop continues.
 */
class SseRegistryBroadcastTest {

    @Test
    @SuppressWarnings("unchecked")
    void broadcastSurvivesAnEmitterThatThrowsAndDeliversToTheRest() throws Exception {
        var registry = new SseRegistry();
        var workspaceId = UUID.randomUUID();

        // First emitter throws the unchecked IllegalStateException that a
        // completed/timed-out SseEmitter raises on send.
        var deadCompleted = new AtomicBoolean(false);
        var dead = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }

            @Override
            public synchronized void completeWithError(Throwable ex) {
                deadCompleted.set(true);
                super.completeWithError(ex);
            }
        };

        // Second emitter records that it still received the event.
        var liveReceived = new AtomicBoolean(false);
        var live = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                liveReceived.set(true);
            }
        };

        // Populate the private connections map: dead FIRST so its throw would
        // abort the loop before the live one under the old IOException-only catch.
        Field connectionsField = SseRegistry.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        var connections = (Map<UUID, List<SseRegistry.UserEmitter>>) connectionsField.get(registry);
        var list = new CopyOnWriteArrayList<SseRegistry.UserEmitter>();
        list.add(new SseRegistry.UserEmitter(UUID.randomUUID(), dead));
        list.add(new SseRegistry.UserEmitter(UUID.randomUUID(), live));
        connections.put(workspaceId, list);

        // No active transaction here → afterCommit runs the action immediately.
        registry.broadcast(workspaceId, "issue.updated", Map.of("id", "x"));

        assertTrue(liveReceived.get(),
                "The live emitter must still receive the event after the dead one throws");
        assertTrue(deadCompleted.get(),
                "The dead emitter must be completed-with-error (its onError cleanup removes it)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyLiveEmitterReceivesEventDespiteMultipleDeadOnes() throws Exception {
        // Interleave dead emitters among live ones: the loop must not be aborted
        // by any of them, and each must be completed-with-error (which, on a
        // handler-bound emitter in production, fires the onError cleanup that
        // subscribe() registers to drop it from the connection list).
        var registry = new SseRegistry();
        var workspaceId = UUID.randomUUID();

        var deadCount = new java.util.concurrent.atomic.AtomicInteger();
        var liveCount = new java.util.concurrent.atomic.AtomicInteger();

        java.util.function.Supplier<SseEmitter> deadEmitter = () -> new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
            @Override
            public synchronized void completeWithError(Throwable ex) {
                deadCount.incrementAndGet();
                super.completeWithError(ex);
            }
        };
        java.util.function.Supplier<SseEmitter> liveEmitter = () -> new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                liveCount.incrementAndGet();
            }
        };

        Field connectionsField = SseRegistry.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        var connections = (Map<UUID, List<SseRegistry.UserEmitter>>) connectionsField.get(registry);
        var list = new CopyOnWriteArrayList<SseRegistry.UserEmitter>();
        // dead, live, dead, live, dead — first entry dead so any early abort shows
        list.add(new SseRegistry.UserEmitter(UUID.randomUUID(), deadEmitter.get()));
        list.add(new SseRegistry.UserEmitter(UUID.randomUUID(), liveEmitter.get()));
        list.add(new SseRegistry.UserEmitter(UUID.randomUUID(), deadEmitter.get()));
        list.add(new SseRegistry.UserEmitter(UUID.randomUUID(), liveEmitter.get()));
        list.add(new SseRegistry.UserEmitter(UUID.randomUUID(), deadEmitter.get()));
        connections.put(workspaceId, list);

        registry.broadcast(workspaceId, "issue.updated", Map.of("id", "x"));

        assertEquals(2, liveCount.get(), "both live emitters received the event");
        assertEquals(3, deadCount.get(), "all three dead emitters were completed-with-error");
    }
}
