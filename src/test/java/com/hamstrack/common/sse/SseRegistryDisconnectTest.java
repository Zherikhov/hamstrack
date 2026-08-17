package com.hamstrack.common.sse;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-132: {@link SseRegistry#disconnectUser} — the offboarding half of revocation.
 *
 * <p>{@code SseController} checks membership when a stream is <em>opened</em> and never
 * again, so before this method a removed member kept receiving live workspace activity
 * until their emitter hit the 30-minute timeout. What leaked was metadata, not content
 * ({@code {projectId, issueNumber}} only, and any REST fetch it prompted 404'd) — but
 * "access revoked" being false for half an hour is exactly the claim that matters when
 * someone is offboarded during an incident.
 *
 * <p>The three properties that make it safe to call from the removal path: it closes
 * <em>only</em> the named user's emitters, it survives an emitter that is already dead
 * (one broken pipe must not spare the user's other tabs), and it is a no-op — not an error
 * — for a user with no open stream, which is the overwhelmingly common case.
 *
 * <p>Populates the private connection map by reflection like
 * {@link SseRegistryBroadcastTest}, and observes closure by overriding {@code complete()}
 * rather than registering an {@code onCompletion} callback: {@code ResponseBodyEmitter}
 * holds only ONE completion callback, so an observer registered that way would displace
 * the cleanup callback {@code subscribe()} installs.
 */
class SseRegistryDisconnectTest {

    @Test
    void closesOnlyTheNamedUsersStreamsAndLeavesEveryoneElseConnected() throws Exception {
        var registry = new SseRegistry();
        var workspaceId = UUID.randomUUID();
        var removed = UUID.randomUUID();
        var bystander = UUID.randomUUID();

        var firstTab = flagged();
        var secondTab = flagged();
        var otherUser = flagged();
        seed(registry, workspaceId,
                new SseRegistry.UserEmitter(removed, firstTab.emitter()),
                new SseRegistry.UserEmitter(bystander, otherUser.emitter()),
                new SseRegistry.UserEmitter(removed, secondTab.emitter()));

        assertThat(registry.disconnectUser(workspaceId, removed))
                .as("both of the removed user's tabs are closed").isEqualTo(2);
        assertThat(firstTab.closed()).isTrue();
        assertThat(secondTab.closed()).isTrue();
        assertThat(otherUser.closed()).as("a bystander keeps their stream").isFalse();
    }

    /**
     * The removed user's own emitters are independent: an emitter that has already
     * completed (browser closed, broken pipe) throws from {@code complete()}, and that must
     * not spare the tab sitting next to it.
     */
    @Test
    void survivesAnAlreadyDeadEmitterAndKeepsClosingTheRest() throws Exception {
        var registry = new SseRegistry();
        var workspaceId = UUID.randomUUID();
        var removed = UUID.randomUUID();

        var dead = new SseEmitter() {
            @Override
            public void complete() {
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
        };
        var live = flagged();
        seed(registry, workspaceId,
                new SseRegistry.UserEmitter(removed, dead),
                new SseRegistry.UserEmitter(removed, live.emitter()));

        assertThat(registry.disconnectUser(workspaceId, removed)).isEqualTo(2);
        assertThat(live.closed()).as("the live tab is still closed after the dead one threw").isTrue();
    }

    /** Removing someone who has no open stream — the normal case — is a quiet 0, not a failure. */
    @Test
    void isANoOpForAUserWithNoOpenStream() throws Exception {
        var registry = new SseRegistry();
        var workspaceId = UUID.randomUUID();
        var somebodyElse = flagged();
        seed(registry, workspaceId, new SseRegistry.UserEmitter(UUID.randomUUID(), somebodyElse.emitter()));

        assertThat(registry.disconnectUser(workspaceId, UUID.randomUUID())).isZero();
        assertThat(registry.disconnectUser(UUID.randomUUID(), UUID.randomUUID()))
                .as("a workspace with no connections at all").isZero();
        assertThat(somebodyElse.closed()).isFalse();
    }

    // ============================================================ helpers

    private record Flagged(SseEmitter emitter, AtomicBoolean flag) {
        boolean closed() {
            return flag.get();
        }
    }

    private static Flagged flagged() {
        var flag = new AtomicBoolean(false);
        var emitter = new SseEmitter() {
            @Override
            public void complete() {
                flag.set(true);
                super.complete();
            }
        };
        return new Flagged(emitter, flag);
    }

    @SuppressWarnings("unchecked")
    private static void seed(SseRegistry registry, UUID workspaceId, SseRegistry.UserEmitter... entries)
            throws Exception {
        Field connectionsField = SseRegistry.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        var connections = (Map<UUID, List<SseRegistry.UserEmitter>>) connectionsField.get(registry);
        var list = new CopyOnWriteArrayList<SseRegistry.UserEmitter>();
        list.addAll(List.of(entries));
        connections.put(workspaceId, list);
    }
}
