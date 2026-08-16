package com.hamstrack.issue.dto;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * Start a sprint (HD-22, agile-sprints-proposal §4.4). The whole body is
 * <strong>optional</strong> — {@code POST} with no body means "start it now with the
 * default length", which is the common case.
 *
 * @param startAt defaults to now (UTC). A start date in the past is allowed:
 *                backfilling a sprint that actually began on Monday is normal.
 * @param endAt   defaults to {@code startAt + app.agile.default-sprint-length-days};
 *                {@code endAt <= startAt} is a 422 (and {@code sprints_dates_ck}
 *                catches it in the DB too)
 * @param goal    an optional last-minute goal; null leaves the stored one
 */
public record StartSprintRequest(
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        @Size(max = 500) String goal
) {}
