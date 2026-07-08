package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.StatusCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateStatusRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull StatusCategory category,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color
) {}
