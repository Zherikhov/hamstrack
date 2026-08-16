package com.hamstrack.issue.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Put a batch of issues into a sprint (HD-22, agile-sprints-proposal §4.4) — the row
 * kebab's "Move to sprint" action and the planning dialog's multi-select both hit this.
 *
 * <p>Issues are addressed by <strong>id</strong> here (the established DTO convention
 * for references — {@code parentId}, {@code assigneeId}, {@code componentId}); only
 * the moved issue of the rank endpoint travels by number, because that endpoint is
 * mounted under {@code /issues/{number}}.
 *
 * <p>{@code @Size} is a payload guard, deliberately looser than
 * {@code app.agile.max-issues-per-bulk-move} (which produces the real 400 after
 * de-duplication): it stops a multi-megabyte UUID array from being materialized
 * before that check ever runs.
 *
 * @param position TOP or BOTTOM of the target sprint; {@code null} → BOTTOM
 */
public record AddIssuesToSprintRequest(
        @NotEmpty @Size(max = 500) List<UUID> issueIds,
        SprintInsertPosition position
) {
    public AddIssuesToSprintRequest {
        position = position == null ? SprintInsertPosition.BOTTOM : position;
    }
}
