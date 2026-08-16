package com.hamstrack.issue.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Merge {@code sourceIds} INTO the label addressed by the path (HD-30, §4.4) — the
 * one deliberate bulk operation of this slice, because it is the only cure for the
 * tag-soup failure mode. Every source must live in the same workspace and differ from
 * the target (422 otherwise); OWNER/ADMIN only.
 */
public record MergeLabelsRequest(
        @NotEmpty @Size(max = 50) List<UUID> sourceIds
) {}
