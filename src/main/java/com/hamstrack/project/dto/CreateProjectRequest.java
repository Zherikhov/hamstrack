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
        // 10000 = FieldValueService.MAX_TEXTAREA_LENGTH, the one bound for a block of prose
        // (HD-171 §4.3). projects.description is TEXT, so this is a payload guard — and this
        // one is also unbounded EGRESS without it: the description ships in every project list
        // response. Bare literal so that a FUTURE column-width scanner CAN read it — no
        // scanner reads this field today (EmailLengthBoundTest's `max\s*=\s*(\d+)` regex is
        // only ever applied to declarations it reaches from an @Email). Keeping the form it
        // reads costs nothing; 10_000 would read as 10 and a constant reference as no bound at
        // all. What is meant to police the VALUE is a behavioural test (§5.3), not a scan.
        @Size(max = 10000) String description,
        @Valid DeliveryRequest delivery
) {}
