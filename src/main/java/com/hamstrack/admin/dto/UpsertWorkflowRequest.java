package com.hamstrack.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Full replacement: statusIds in board-column order; transitions may be empty (open workflow). */
public record UpsertWorkflowRequest(
        @NotBlank @Size(max = 100) String name,
        String description,
        @NotEmpty List<UUID> statusIds,
        @Valid List<TransitionRule> transitions
) {
    /** fromStatusId NULL = "from any status" */
    public record TransitionRule(UUID fromStatusId, @NotNull UUID toStatusId) {}
}
