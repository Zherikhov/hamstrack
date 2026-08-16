package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Label;

import java.time.Instant;
import java.util.UUID;

/**
 * A workspace label (HD-30, §4.3). {@code issueCount} is {@code null} unless the
 * caller asked for usage ({@code ?withUsage=true} or the {@code /usage} endpoint) —
 * the count is a grouped query the picker doesn't need to pay for.
 */
public record LabelResponse(
        UUID id,
        String name,
        String color,
        String description,
        boolean archived,
        UUID createdById,
        String createdByName,
        Integer issueCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static LabelResponse of(Label l, Integer issueCount) {
        var creator = l.getCreatedBy();
        return new LabelResponse(
                l.getId(), l.getName(), l.getColor(), l.getDescription(),
                l.isArchived(),
                creator == null ? null : creator.getId(),
                creator == null ? null : creator.getDisplayName(),
                issueCount,
                l.getCreatedAt(), l.getUpdatedAt());
    }
}
