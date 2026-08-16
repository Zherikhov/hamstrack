package com.hamstrack.issue.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Partially update a project version (HD-32, §6.3) — {@code null} leaves a field
 * alone. Curator only.
 *
 * <p>{@code releaseDate} is a nullable scalar, so it follows the
 * {@code assigneeId}/{@code clearAssignee} convention: a non-null date sets it,
 * {@code clearReleaseDate: true} unsets it, absent = unchanged.
 *
 * <p>{@code clearReleaseDate} is a <strong>boxed</strong> {@code Boolean}: Boot 4 /
 * Jackson 3 enable {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive would make
 * every partial PATCH that omits the flag 400 "Failed to read request" — the exact
 * bug that bit {@code UpdateIssueRequest.clearAssignee}. The compact constructor
 * coalesces {@code null → false}.
 *
 * <p>There is deliberately no {@code released} field here: the lifecycle moves only
 * through {@code POST …/release} and {@code POST …/unrelease}, which are conditional
 * (409 on a double release) and carry the optional unresolved-issue move.
 */
public record UpdateVersionRequest(
        @Size(max = 200) String name,
        @Size(max = 500) String description,
        LocalDate releaseDate,
        Boolean clearReleaseDate
) {
    public UpdateVersionRequest {
        clearReleaseDate = clearReleaseDate != null && clearReleaseDate;
    }
}
