package com.hamstrack.project.dto;

import com.hamstrack.common.util.DisplayText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * <p>{@code name} carries {@link DisplayText#SINGLE_LINE} — a project name is a display
 * string that reaches notification subjects, the audit trail and CSV exports, none of
 * which this application renders. See {@code RegisterRequest} for the reasoning; the
 * pattern rejects only invisible and reordering characters, never a legitimate name.
 *
 * @param delivery HD-102 — how this team will deliver, chosen once in the creation
 *                 picker (§12). <strong>Optional</strong>: omitted (or with null
 *                 members) it yields the lean defaults — {@code KANBAN}, releases
 *                 off, estimation off (§7 / open question 2). Choosing Scrum in the
 *                 picker sends {@code board = SCRUM} <em>and</em>
 *                 {@code estimation = true} in this one request; there is no
 *                 server-side "Scrum implies estimation" rule, because the three
 *                 capabilities are independent by design and any combination is legal.
 *                 {@code @Valid} is what makes the nested constraints (notably
 *                 {@code preset}'s {@code @Null}) actually run — bean validation does
 *                 not descend into a nested component without it.
 */
public record CreateProjectRequest(
        @NotBlank @Size(min = 2, max = 255) @Pattern(regexp = DisplayText.SINGLE_LINE,
                message = "Name must not contain control characters") String name,
        @NotBlank @Size(min = 1, max = 10) @Pattern(regexp = "[A-Z0-9]+", message = "Key must be uppercase letters and digits only") String key,
        String description,
        @Valid DeliveryRequest delivery
) {}
