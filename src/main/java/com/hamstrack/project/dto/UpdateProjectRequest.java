package com.hamstrack.project.dto;

import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @Size(min = 2, max = 255) String name,
        String description
) {}
