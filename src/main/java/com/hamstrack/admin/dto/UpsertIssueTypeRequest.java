package com.hamstrack.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertIssueTypeRequest(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color,
        @Size(max = 50) String icon,
        Short position
) {}
