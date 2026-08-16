package com.hamstrack.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Create a project component (HD-31, §5.3). Requires the project-curator role
 * (project MANAGER, or an OWNER/ADMIN of the enclosing workspace).
 *
 * <p>{@code name} is normalized server-side <em>before</em> the length/uniqueness
 * checks (NFC, control chars stripped, whitespace collapsed) — the {@code @Size}
 * here is only a cheap first line of defence against an absurd payload.
 *
 * <p>{@code autoAssign} is a <strong>boxed</strong> {@code Boolean}: Boot 4 /
 * Jackson 3 enable {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive would make
 * every payload that omits the flag 400 "Failed to read request". The compact
 * constructor coalesces {@code null → false}.
 */
public record CreateComponentRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 500) String description,
        UUID leadId,
        Boolean autoAssign
) {
    public CreateComponentRequest {
        autoAssign = autoAssign != null && autoAssign;
    }
}
