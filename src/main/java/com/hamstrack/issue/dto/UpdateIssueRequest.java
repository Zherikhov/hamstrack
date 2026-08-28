package com.hamstrack.issue.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record UpdateIssueRequest(
        @Size(min = 1, max = 500) String title,
        // Same bound and same reasoning as CreateIssueRequest.description (HD-171 §4.3):
        // a payload guard agreeing with FieldValueService.MAX_TEXTAREA_LENGTH, spelled as a
        // bare literal so that a FUTURE column-width scanner CAN read it — no scanner reads
        // this field today (EmailLengthBoundTest's `max\s*=\s*(\d+)` regex is only ever
        // applied to declarations it reaches from an @Email). What is meant to police the
        // VALUE is a behavioural test (§5.3), not a source scan. This is the door
        // where the history amplification actually happens.
        @Size(max = 10000) String description,
        UUID typeId,
        UUID statusId,
        UUID priorityId,
        UUID assigneeId,
        LocalDate dueDate,
        // Jackson can't distinguish an absent field from an explicit null, so
        // clearing a nullable core field is an explicit flag. A non-null assigneeId
        // /dueDate still just sets it; the flag only matters when unsetting.
        // NOTE: these are boxed Boolean (not primitive) on purpose — Boot 4 /
        // Jackson 3 enable FAIL_ON_NULL_FOR_PRIMITIVES, so a primitive boolean here
        // makes EVERY partial PATCH that omits the flag 400 "Failed to read request".
        // The canonical constructor coalesces null → false so callers keep the
        // primitive-accessor contract.
        Boolean clearAssignee,
        Boolean clearDueDate,
        // Parent: a non-null parentId sets/changes it; clearParent (when no id)
        // detaches. Mirrors the clearAssignee/clearDueDate convention above.
        UUID parentId,
        Boolean clearParent,
        // Labels (HD-30) are a FULL-REPLACEMENT set: present → the issue ends up with
        // exactly these labels ([] clears them all); absent/null → unchanged. The
        // picker always holds the whole set, so a delta API would only invite drift.
        // No clear-flag needed: [] is unambiguous for a collection.
        // @Size is a payload guard (see CreateIssueRequest): the real per-issue cap is
        // app.classification.max-labels-per-issue, enforced after de-duplication.
        @Size(max = 100) List<UUID> labelIds,
        // Component (HD-31) is a nullable SCALAR, so it follows the assigneeId /
        // clearAssignee convention rather than the labels one: a non-null id sets it,
        // clearComponent: true unsets it, absent = unchanged. clearComponent is a boxed
        // Boolean for the same Jackson-3 reason as the flags above.
        UUID componentId,
        Boolean clearComponent,
        // Fix / affects versions (HD-32) are FULL-REPLACEMENT sets per role, exactly
        // like labelIds: present → the issue ends up with exactly these versions in
        // that role ([] clears them); absent/null → that role is unchanged. The two
        // roles are independent, so replacing one never touches the other. No
        // clear-flag needed: [] is unambiguous for a collection.
        // @Size is a payload guard (see CreateIssueRequest); the real per-role cap is
        // app.classification.max-version-links-per-issue, enforced after de-duplication.
        @Size(max = 100) List<UUID> fixVersionIds,
        @Size(max = 100) List<UUID> affectsVersionIds,
        // Sprint (HD-22) is a nullable SCALAR, so it follows the assigneeId /
        // clearAssignee convention rather than the labels one: a non-null id sets it,
        // clearSprint: true returns the issue to the backlog, absent = unchanged. A
        // foreign/unknown id is a 422 "Unknown sprint"; a COMPLETED sprint is a 422 too.
        // clearSprint is a boxed Boolean for the same Jackson-3 reason as the flags above.
        UUID sprintId,
        Boolean clearSprint,
        // Story points (HD-22 §3.4), same nullable-scalar convention: a number sets it,
        // clearStoryPoints: true marks the issue unestimated again. 0…999 with at most
        // 2 decimals — 422 otherwise. A change writes one `storyPoints` history row; a
        // no-op writes none.
        BigDecimal storyPoints,
        Boolean clearStoryPoints,
        // Partial: only listed field ids change; JSON null clears a value
        Map<UUID, JsonNode> fields,
        // Optimistic lock check — optional so clients that don't send it keep working
        Integer version
) {
    // Canonical constructor coalesces an absent/null flag to FALSE so the boxed
    // Boolean accessors never hand back null to the service.
    public UpdateIssueRequest {
        clearAssignee = clearAssignee != null && clearAssignee;
        clearDueDate = clearDueDate != null && clearDueDate;
        clearParent = clearParent != null && clearParent;
        clearComponent = clearComponent != null && clearComponent;
        clearSprint = clearSprint != null && clearSprint;
        clearStoryPoints = clearStoryPoints != null && clearStoryPoints;
    }
}
