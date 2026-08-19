package com.hamstrack.issue.dto;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * Start a sprint (HD-22, agile-sprints-proposal §4.4). The whole body is
 * <strong>optional</strong> — {@code POST} with no body means "start it now with the
 * default length", which is the common case.
 *
 * @param startAt defaults to now (UTC). A start date in the PAST is allowed: backfilling
 *                a sprint that actually began on Monday is normal. A start date in the
 *                FUTURE is a 422 (HD-137 R3) — planning one is fine, but the act that
 *                turns a plan into history cannot be dated ahead of now, or the sprint's
 *                start disagrees with its own commitment rows and {@code update} then
 *                freezes the wrong date in place. A few minutes of client clock skew are
 *                tolerated and <strong>recorded as now</strong> (HD-137 R4): the tolerance
 *                is for an unsynchronised clock, so the value stored is the server's
 *                instant, never the fast one — otherwise the sprint would start after its
 *                own commitment rows, which is the very thing the 422 above prevents.
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
