package com.hamstrack.issue.dto;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * Rename / re-goal / re-plan a sprint (HD-22, agile-sprints-proposal §4.4). Partial:
 * an absent field is left alone.
 *
 * <p><strong>The lifecycle deliberately does NOT move through here</strong> — there
 * is no {@code state} field, so a PATCH can neither start, complete nor re-open a
 * sprint. Those are the dedicated conditional-UPDATE endpoints.
 *
 * <p>{@code clearStartAt}/{@code clearEndAt} follow the {@code assigneeId} /
 * {@code clearAssignee} convention: a non-null date sets it, the flag unsets it. They
 * are boxed {@link Boolean} on purpose — Boot 4 / Jackson 3 enable
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive here would make EVERY partial
 * PATCH that omits the flag 400 "Failed to read request". The canonical constructor
 * coalesces null → false so the service keeps a primitive-like contract.
 */
public record UpdateSprintRequest(
        @Size(max = 60) String name,
        @Size(max = 500) String goal,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        Boolean clearStartAt,
        Boolean clearEndAt
) {
    public UpdateSprintRequest {
        clearStartAt = clearStartAt != null && clearStartAt;
        clearEndAt = clearEndAt != null && clearEndAt;
    }
}
