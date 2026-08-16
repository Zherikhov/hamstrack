package com.hamstrack.issue.dto;

/**
 * How many issues currently carry a label (HD-30, §4.3) — backs the settings usage
 * chip and the delete-confirm dialog ("used on N issues").
 */
public record LabelUsageResponse(int issueCount) {}
