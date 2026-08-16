package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * A project component (HD-31, §5.3). {@code issueCount} is {@code null} unless the
 * caller asked for usage ({@code ?withUsage=true} or the {@code /usage} endpoint) —
 * the count is a grouped query the picker doesn't need to pay for.
 *
 * <p>The lead is echoed even when they are no longer a workspace member (the row
 * survives, §5.4); the SPA marks them and auto-assign silently skips.
 */
public record ComponentResponse(
        UUID id,
        String name,
        String description,
        UUID leadId,
        String leadName,
        String leadAvatarUrl,
        boolean autoAssign,
        boolean archived,
        Integer issueCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static ComponentResponse of(Component c, Integer issueCount) {
        var lead = c.getLead();
        return new ComponentResponse(
                c.getId(), c.getName(), c.getDescription(),
                lead == null ? null : lead.getId(),
                lead == null ? null : lead.getDisplayName(),
                lead == null ? null : lead.getAvatarUrl(),
                c.isAutoAssign(), c.isArchived(),
                issueCount,
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
