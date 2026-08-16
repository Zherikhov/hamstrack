package com.hamstrack.issue.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Partial update of a project component (HD-31, §5.3) — {@code null} means "leave
 * this field alone".
 *
 * <p>{@code leadId} follows the {@code assigneeId}/{@code clearAssignee} convention:
 * a non-null id sets the lead, {@code clearLead: true} unsets it (Jackson can't tell
 * an absent field from an explicit null).
 *
 * <p>Both booleans are <strong>boxed</strong> on purpose: Boot 4 / Jackson 3 enable
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive {@code boolean} here would make
 * every partial PATCH that omits the flag 400 "Failed to read request" (the exact bug
 * that bit {@code UpdateIssueRequest.clearAssignee}). {@code clearLead} is coalesced
 * {@code null → false}; {@code autoAssign} stays nullable — {@code null} is
 * meaningful there ("don't touch the switch").
 */
public record UpdateComponentRequest(
        @Size(max = 200) String name,
        @Size(max = 500) String description,
        UUID leadId,
        Boolean clearLead,
        Boolean autoAssign
) {
    public UpdateComponentRequest {
        clearLead = clearLead != null && clearLead;
    }
}
