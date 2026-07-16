package com.hamstrack.issue.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.entity.FieldType;

import java.util.List;
import java.util.UUID;

/**
 * The effective taxonomy of a project — everything the SPA needs to render
 * the board, backlog filters and the issue create/edit forms.
 */
public record ProjectConfigResponse(
        List<StatusResponse> statuses,          // board-column order
        List<TransitionRule> transitions,       // empty = all moves allowed
        List<PriorityOption> priorities,        // display order
        List<IssueTypeResponse> issueTypes,
        List<FieldOption> fields                // custom fields, display order
) {
    /** fromStatusId NULL = "from any status" */
    public record TransitionRule(UUID fromStatusId, UUID toStatusId) {}

    public record PriorityOption(UUID id, String name, String color, String icon, boolean isDefault) {}

    /** config: {"options":[{id,label,color}], "min", "max"} depending on type */
    public record FieldOption(UUID id, String key, String name, FieldType type,
                              JsonNode config, String description,
                              boolean required, boolean showOnCreate) {}
}
