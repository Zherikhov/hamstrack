package com.hamstrack.admin.dto;

import com.hamstrack.common.util.ColorFormat;
import com.hamstrack.issue.entity.StatusCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertStatusRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull StatusCategory category,
        @Pattern(regexp = ColorFormat.SIX_DIGIT_REGEX,
                message = ColorFormat.SIX_DIGIT_MESSAGE) String color,
        Short position
) {}
