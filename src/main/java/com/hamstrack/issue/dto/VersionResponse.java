package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A project version (HD-32, §6.3). This is the Releases page's data source, so the
 * progress counters are <strong>always present</strong> — unlike
 * {@code ComponentResponse.issueCount} / {@code LabelResponse.issueCount}, which
 * are opt-in. They cost ONE grouped query for the whole list, not one per row
 * (§6.1), so there is nothing to opt out of.
 *
 * @param issueCount        issues with a <strong>FIX</strong> link to this version
 * @param doneIssueCount    of those, the ones in a DONE-category status (the
 *                          progress bar's numerator: "18 / 24")
 * @param affectsIssueCount issues with an AFFECTS link (triage, not progress)
 */
public record VersionResponse(
        UUID id,
        String name,
        String description,
        LocalDate releaseDate,
        boolean released,
        OffsetDateTime releasedAt,
        boolean archived,
        int issueCount,
        int doneIssueCount,
        int affectsIssueCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static VersionResponse of(Version v, int issueCount, int doneIssueCount,
                                     int affectsIssueCount) {
        return new VersionResponse(
                v.getId(), v.getName(), v.getDescription(),
                v.getReleaseDate(), v.isReleased(), v.getReleasedAt(), v.isArchived(),
                issueCount, doneIssueCount, affectsIssueCount,
                v.getCreatedAt(), v.getUpdatedAt());
    }
}
