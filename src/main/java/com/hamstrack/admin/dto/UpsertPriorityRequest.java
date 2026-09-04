package com.hamstrack.admin.dto;

import com.hamstrack.common.util.ColorFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertPriorityRequest(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = ColorFormat.SIX_DIGIT_REGEX,
                message = ColorFormat.SIX_DIGIT_MESSAGE) String color,
        @Size(max = 50) String icon,
        Short position
) {}
