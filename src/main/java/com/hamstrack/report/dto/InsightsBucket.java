package com.hamstrack.report.dto;

import java.util.UUID;

/**
 * One value of a dimension — a bar on the x axis, or an entry in the legend.
 *
 * <h2>Buckets are keyed by id, not by name, and that has a visible consequence</h2>
 * A workspace's visible projects each own their own statuses, types, priorities, components and
 * sprints, so two projects can both have an "In Progress" — two different catalog rows, two
 * buckets, one label. The panel will show both. The alternative (group by name) was rejected
 * because it breaks in a worse and quieter way: a name that no longer resolves — a COMPLETED
 * sprint, an archived component or label, all of which are deliberately excluded from HQL name
 * resolution — would produce a bar whose own filter fragment 422s when clicked.
 *
 * @param id    the catalog/user/project id, or {@code null} for the "has no value" bucket
 *              (unassigned, no component, not in a sprint, no labels). Cells reference buckets by
 *              this id, so {@code null} is a real key and must be matched as one.
 * @param label the display name, or {@code null} for the null bucket — the client names it
 *              ("Unassigned", "No component"), because that wording is UI copy and belongs where
 *              the rest of the UI copy is.
 * @param hql   an HQL fragment that selects <strong>exactly the issues in this bucket</strong>, for
 *              the click-to-narrow affordance (§2.6: the panel is a navigation device as much as a
 *              chart) — or {@code null} when no such fragment exists, in which case the bar is not
 *              clickable.
 *              <p>It is {@code null} in three cases, and the rule behind all three is
 *              <strong>the fragment reproduces this bucket exactly or there is no fragment</strong>:
 *              <ul>
 *                <li>the dimension has no HQL field at all ({@code PROJECT});</li>
 *                <li>the bucket's name does not resolve — a completed sprint, an archived
 *                    component or label. HQL deliberately drops those from name resolution, so a
 *                    fragment naming one would 422 the moment it was clicked;</li>
 *                <li>the name resolves to <em>more than one</em> id — the cross-project "In
 *                    Progress" above. The fragment would be strictly wider than the bar, and a
 *                    filter that returns more issues than the bar you clicked is precisely the
 *                    "these numbers don't match" failure (§1.5).</li>
 *              </ul>
 *              A client must therefore treat this as optional rather than assuming every bar
 *              navigates.
 */
public record InsightsBucket(UUID id, String label, String hql) {}
