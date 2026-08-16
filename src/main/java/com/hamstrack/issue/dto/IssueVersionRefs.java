package com.hamstrack.issue.dto;

import java.util.List;

/**
 * One issue's version links split by role (HD-32, §3.7) — the unit
 * {@code VersionService.versionsByIssue} hands back per issue and
 * {@link IssueResponse} renders. Kept as a pair rather than two separate maps
 * because BOTH roles come out of the same batched query.
 *
 * @param fixVersions     "the change ships in these releases"
 * @param affectsVersions "the defect exists in those releases"
 */
public record IssueVersionRefs(List<VersionRef> fixVersions, List<VersionRef> affectsVersions) {

    public static final IssueVersionRefs EMPTY = new IssueVersionRefs(List.of(), List.of());
}
