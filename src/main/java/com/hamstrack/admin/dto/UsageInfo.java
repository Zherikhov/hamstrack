package com.hamstrack.admin.dto;

/**
 * Where a catalog entry is used — powers the usage-first admin UX ("used in
 * N workflows · M projects"). Counts irrelevant to the entity type are 0.
 */
public record UsageInfo(long workflows, long sets, long projects, long issues) {}
