package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.IssuePriority;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateIssueRequest(
        @Size(min = 1, max = 500) String title,
        String description,
        UUID typeId,
        UUID statusId,
        IssuePriority priority,
        UUID assigneeId,
        LocalDate dueDate,
        // Optimistic lock check — optional so clients that don't send it keep working
        Integer version
) {}
