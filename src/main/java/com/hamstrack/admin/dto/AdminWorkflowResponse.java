package com.hamstrack.admin.dto;

import com.hamstrack.issue.dto.StatusResponse;

import java.util.List;
import java.util.UUID;

public record AdminWorkflowResponse(
        UUID id, String name, String description, boolean systemDefault,
        List<StatusResponse> statuses,
        List<TransitionRule> transitions,
        long projectsUsing
) {
    /** fromStatusId NULL = "from any status" */
    public record TransitionRule(UUID fromStatusId, UUID toStatusId) {}
}
