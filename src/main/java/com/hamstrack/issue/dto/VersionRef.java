package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Version;

import java.util.UUID;

/**
 * A version's display summary as embedded in an {@link IssueResponse} (HD-32,
 * §3.7). Deliberately minimal — id/name plus {@code released} (the picker marks
 * released options) and {@code archived} so the SPA can render a still-linked
 * archived version dimmed without a second fetch.
 */
public record VersionRef(UUID id, String name, boolean released, boolean archived) {

    public static VersionRef of(Version version) {
        return new VersionRef(version.getId(), version.getName(),
                version.isReleased(), version.isArchived());
    }
}
