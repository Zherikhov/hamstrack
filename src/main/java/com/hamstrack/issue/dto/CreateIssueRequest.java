package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.IssuePriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record CreateIssueRequest(
        @NotBlank @Size(max = 500) String title,
        String description,
        @NotNull UUID typeId,
        @NotNull UUID statusId,
        IssuePriority priority,
        UUID assigneeId,
        UUID parentId,
        LocalDate dueDate
) {}
