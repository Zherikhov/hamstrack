package com.hamstrack.common.event;

import java.util.UUID;

/**
 * A member was removed from a workspace (HD-132). The SSE side effect is not a broadcast
 * but a <em>disconnect</em>: {@code SseEventListener} completes that user's open streams on
 * this workspace, because membership is only checked when a stream is opened.
 *
 * <p>Published from {@code WorkspaceMemberService.remove} so the drop happens strictly
 * AFTER COMMIT — a removal that rolls back must not kill a live stream belonging to
 * someone who is still a member.
 */
public record WorkspaceMemberRemoved(UUID workspaceId, UUID userId) implements DomainEvent {
}
