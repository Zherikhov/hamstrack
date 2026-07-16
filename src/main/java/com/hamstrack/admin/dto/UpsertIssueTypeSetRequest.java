package com.hamstrack.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Full replacement: typeIds in display order. A set can never be empty. */
public record UpsertIssueTypeSetRequest(
        @NotBlank @Size(max = 100) String name,
        @NotEmpty List<UUID> typeIds
) {}
