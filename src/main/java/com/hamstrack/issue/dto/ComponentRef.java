package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Component;

import java.util.UUID;

/**
 * A component's display summary as embedded in an {@link IssueResponse} (HD-31,
 * §3.7). Deliberately minimal — id/name plus {@code archived} so the SPA can render
 * a still-assigned archived component dimmed without a second fetch.
 */
public record ComponentRef(UUID id, String name, boolean archived) {

    public static ComponentRef of(Component component) {
        return component == null ? null
                : new ComponentRef(component.getId(), component.getName(), component.isArchived());
    }
}
