package com.hamstrack.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Create a project version (HD-32, §6.3). Requires the project-curator role
 * (project MANAGER, or an OWNER/ADMIN of the enclosing workspace).
 *
 * <p>{@code name} is normalized server-side <em>before</em> the length/uniqueness
 * checks (NFC, control chars stripped, whitespace collapsed) — the {@code @Size}
 * here is only a cheap first line of defence against an absurd payload.
 *
 * <p>A version is always created <strong>unreleased</strong>; {@code releaseDate}
 * is only the plan. Releasing is a separate, explicit lifecycle call so a
 * destructive {@code moveUnresolvedToVersionId} can never ride along on a create.
 */
public record CreateVersionRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 500) String description,
        LocalDate releaseDate
) {}
