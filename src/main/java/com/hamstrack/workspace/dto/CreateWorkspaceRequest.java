package com.hamstrack.workspace.dto;

import com.hamstrack.common.util.DisplayText;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * <p>{@code name} carries {@link DisplayText#SINGLE_LINE} — a workspace name is a display
 * string that reaches invite emails, notification subjects and the audit trail, none of
 * which this application renders. See {@code RegisterRequest} for the reasoning; the
 * pattern rejects only invisible and reordering characters, never a legitimate name.
 */
public record CreateWorkspaceRequest(
        @NotBlank @Size(min = 2, max = 255) @Pattern(regexp = DisplayText.SINGLE_LINE,
                message = "Name must not contain control characters") String name
) {}
