package com.hamstrack.issue.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record CreateIssueRequest(
        @NotBlank @Size(max = 500) String title,
        String description,
        @NotNull UUID typeId,
        @NotNull UUID statusId,
        // Null = the default priority of the project's priority set
        UUID priorityId,
        UUID assigneeId,
        UUID parentId,
        LocalDate dueDate,
        // Custom field values keyed by field id; shapes per field type
        Map<UUID, JsonNode> fields
) {}
