package com.hamstrack.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create a workspace label (HD-30, §4.3). Any workspace member may create one
 * (self-serve tagging is the point of the feature).
 *
 * <p>{@code name} is normalized server-side <em>before</em> the length/uniqueness
 * checks (NFC, control chars stripped, whitespace collapsed) — the {@code @Size} here
 * is only a cheap first line of defence against an absurd payload.
 * {@code color} is optional: omitted → a deterministic swatch derived from the name.
 */
public record CreateLabelRequest(
        @NotBlank @Size(max = 200) String name,
        @Pattern(regexp = "^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$",
                message = "must be #RRGGBB or #RRGGBBAA") String color,
        @Size(max = 200) String description
) {}
