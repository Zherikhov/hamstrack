package com.hamstrack.issue.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record UpdateIssueRequest(
        @Size(min = 1, max = 500) String title,
        String description,
        UUID typeId,
        UUID statusId,
        UUID priorityId,
        UUID assigneeId,
        LocalDate dueDate,
        // Jackson can't distinguish an absent field from an explicit null, so
        // clearing a nullable core field is an explicit flag. A non-null assigneeId
        // /dueDate still just sets it; the flag only matters when unsetting.
        boolean clearAssignee,
        boolean clearDueDate,
        // Partial: only listed field ids change; JSON null clears a value
        Map<UUID, JsonNode> fields,
        // Optimistic lock check — optional so clients that don't send it keep working
        Integer version
) {}
