package com.hamstrack.issue.dto;

import com.hamstrack.issue.entity.Label;

import java.util.UUID;

/**
 * A label's display summary as embedded in an {@link IssueResponse} (HD-30, §3.7).
 * Deliberately minimal — id/name/color plus {@code archived} so the SPA can render a
 * still-attached archived label dimmed without a second fetch.
 */
public record LabelRef(UUID id, String name, String color, boolean archived) {

    public static LabelRef of(Label label) {
        return new LabelRef(label.getId(), label.getName(), label.getColor(), label.isArchived());
    }
}
