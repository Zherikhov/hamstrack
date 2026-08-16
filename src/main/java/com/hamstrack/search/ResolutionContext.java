package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.workspace.entity.Workspace;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-request resolution state shared by {@link HqlValidator} and {@link HqlCompiler}
 * — assembled ONCE per search from the resolved workspace + actor, so name→id
 * resolution and text matching happen inside the request transaction, scoped to the
 * workspace (Advanced Search proposal §6). Everything here is derived server-side;
 * none of it is influenced by query text.
 *
 * @param actor             the authenticated caller (backs {@code currentUser()})
 * @param workspace         the resolved workspace (the tenant boundary)
 * @param visibleProjectIds non-archived project ids the actor may read
 * @param statusIdsByName   lowercase status name → catalog ids reachable by any
 *                          visible project (§6.1); a name maps to ≥1 id
 * @param typeIdsByName     lowercase issue-type name → reachable catalog ids
 * @param priorityIdsByName lowercase priority name → reachable catalog ids
 * @param prioritiesByName  lowercase priority name → the reachable {@link Priority}
 *                          rows (for the position-based {@code >}/{@code <} on §5.3)
 * @param members           workspace members (id + email + displayName), for
 *                          USER_REF resolution without a cross-tenant lookup
 * @param labelIdsByName    lowercase label name → label ids of THIS WORKSPACE (HD-30).
 *                          Labels are workspace-scoped, so the workspace <em>is</em>
 *                          the boundary — no per-project narrowing applies. Archived
 *                          labels are excluded from name resolution; issues already
 *                          carrying one still match by id (§6.1 of the search proposal)
 * @param componentIdsByName lowercase component name → component ids across the
 *                          <em>visible projects</em> only (HD-31). Components are
 *                          project-owned, so two visible projects may each have a
 *                          "Billing" and the name maps to BOTH ids — exactly how
 *                          statuses already behave. A project the actor cannot see
 *                          never contributes a name. Archived components are excluded
 *                          from name resolution; issues carrying one still match by id
 * @param versionIdsByName  lowercase version name → version ids across the
 *                          <em>visible projects</em> only (HD-32). Versions are
 *                          project-owned, so two visible projects may each ship a
 *                          "2.4.0" and the name maps to BOTH ids. A project the actor
 *                          cannot see never contributes a name. Archived versions are
 *                          excluded from name resolution; issues linked to one still
 *                          match by id. Shared by {@code fixVersion} and
 *                          {@code affectsVersion} — the role is applied by the
 *                          compiler's {@code link_type} filter, not by resolution
 * @param statusNames       distinct status display names reachable by visible
 *                          projects, original casing (for the {@code /schema}
 *                          picklist); {@code typeNames}/{@code priorityNames} likewise
 * @param labelNames        non-archived label display names of the workspace, original
 *                          casing (for the {@code /schema} {@code LABEL} picklist)
 * @param componentNames    non-archived component display names across the visible
 *                          projects, original casing, de-duplicated
 *                          case-insensitively (for the {@code /schema}
 *                          {@code COMPONENT} picklist)
 * @param versionNames      non-archived version display names across the visible
 *                          projects, original casing, de-duplicated
 *                          case-insensitively (for the {@code /schema}
 *                          {@code VERSION} picklist)
 * @param customFieldsByKey non-archived custom fields (M2) reachable by any visible
 *                          project, keyed by their machine {@code key} (== HQL field
 *                          name). System field names always win — a key here is only
 *                          consulted when it is NOT a system field (HD-52). A custom
 *                          field a caller can't see simply isn't in this map (no leak).
 */
public record ResolutionContext(
        User actor,
        Workspace workspace,
        List<UUID> visibleProjectIds,
        Map<String, List<UUID>> statusIdsByName,
        Map<String, List<UUID>> typeIdsByName,
        Map<String, List<UUID>> priorityIdsByName,
        Map<String, List<Priority>> prioritiesByName,
        Map<String, List<UUID>> labelIdsByName,
        Map<String, List<UUID>> componentIdsByName,
        Map<String, List<UUID>> versionIdsByName,
        List<Member> members,
        List<String> statusNames,
        List<String> typeNames,
        List<String> priorityNames,
        List<String> labelNames,
        List<String> componentNames,
        List<String> versionNames,
        Map<String, CustomFieldMeta> customFieldsByKey
) {
    /** A workspace member's identity for USER_REF resolution. */
    public record Member(UUID id, String email, String displayName) {}

    /** A visible custom field by its HQL name (its {@code key}), case-insensitively. */
    public java.util.Optional<CustomFieldMeta> customField(String key) {
        if (key == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(
                customFieldsByKey.get(key.toLowerCase(java.util.Locale.ROOT)));
    }
}
