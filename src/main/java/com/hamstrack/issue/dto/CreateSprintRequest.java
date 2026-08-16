package com.hamstrack.issue.dto;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * Create a sprint (HD-22, agile-sprints-proposal §4.4). Everything is optional: a
 * blank/absent {@code name} becomes {@code "Sprint " + sequence}, which is why
 * default names can never collide.
 *
 * <p>{@code startAt}/{@code endAt} are a <strong>plan only</strong> — filling them in
 * does NOT start the sprint (that is {@code POST …/start}, and it is the transition
 * with the reporting consequences).
 */
public record CreateSprintRequest(
        @Size(max = 60) String name,
        @Size(max = 500) String goal,
        OffsetDateTime startAt,
        OffsetDateTime endAt
) {}
