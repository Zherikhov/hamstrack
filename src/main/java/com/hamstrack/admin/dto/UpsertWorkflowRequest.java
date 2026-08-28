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
        // 10000 = FieldValueService.MAX_TEXTAREA_LENGTH, the one bound for a block of prose
        // (HD-171 §4.3). workflows.description is TEXT, so this is a payload guard. Being
        // admin-only lowers the severity and changes nothing about the defect: a delegated
        // admin tier widens who can reach this door without touching this file.
        //
        // Bare literal so that a FUTURE column-width scanner CAN read it — no scanner reads
        // this field today. The one that exists, EmailLengthBoundTest, applies its
        // `max\s*=\s*(\d+)` regex only to declarations it reaches from an @Email, so it never
        // looks here. Keeping the form it reads costs nothing and is what makes the field
        // scannable later; `10_000` would read as 10 and a symbolic constant as no bound at
        // all. What is meant to police the VALUE is a behavioural test (HD-171 §5.3), not a
        // source scan.
        @Size(max = 10000) String description,
        @NotEmpty List<UUID> statusIds,
        @Valid List<TransitionRule> transitions
) {
    /** fromStatusId NULL = "from any status" */
    public record TransitionRule(UUID fromStatusId, @NotNull UUID toStatusId) {}
}
