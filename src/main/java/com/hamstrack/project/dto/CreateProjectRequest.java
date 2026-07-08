package com.hamstrack.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(min = 2, max = 255) String name,
        @NotBlank @Size(min = 1, max = 10) @Pattern(regexp = "[A-Z0-9]+", message = "Key must be uppercase letters and digits only") String key,
        String description
) {}
