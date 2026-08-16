package com.hamstrack.issue.dto;

/** How many issues currently carry a component (HD-31, §5.3). */
public record ComponentUsageResponse(int issueCount) {}
