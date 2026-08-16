package com.hamstrack.project.dto;

import com.hamstrack.project.entity.BoardMode;
import jakarta.validation.constraints.Size;

/**
 * Partial project update — an absent field is left alone.
 *
 * <p>{@code boardMode} (HD-22 §3.5) flips the project between Kanban and Scrum. It is
 * a closed set, so an unknown value is a 400 from Jackson rather than a silent
 * fall-through; {@code null} leaves the current mode. Adding it here is what widened
 * this endpoint's gate from project MANAGER to project <em>curator</em> (§3.2) — see
 * {@code ProjectService.update}.
 */
public record UpdateProjectRequest(
        @Size(min = 2, max = 255) String name,
        String description,
        BoardMode boardMode
) {}
