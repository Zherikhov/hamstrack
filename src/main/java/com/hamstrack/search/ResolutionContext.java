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
 * @param projectIdsByKey   canonical project KEY → the id of the visible project with that
 *                          key (HD-101) — the ONLY namespace {@code project} operands
 *                          resolve through. A key is unique within a workspace
 *                          ({@code UNIQUE(workspace_id, key)}) so this maps to exactly one
 *                          id; it is a list because the compiler's id-set contract takes
 *                          one, not because two projects could share a key. Project
 *                          <em>names</em> deliberately do NOT resolve — they carry no
 *                          uniqueness constraint, so a name operand would denote a set —
 *                          they only label suggestions and the "did you mean" hint
 * @param members           workspace members (id + email + displayName), for
 *                          USER_REF resolution without a cross-tenant lookup
 * @param projects          the visible projects as (id, key, name), ordered by key — the
 *                          source of the {@code /schema} PROJECT picklist, the
 *                          {@code /suggest?field=project} typeahead and the name hint on a
 *                          failed resolve (HD-101). It is a re-shaping of
 *                          {@code visibleProjectIds}, whose entities this context already
 *                          loads, so none of those surfaces costs a query
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
 * @param sprintIdsByName   lowercase sprint name → sprint ids across the
 *                          <em>visible projects</em> only (HD-22 §4.7). Sprints are
 *                          project-owned, so two visible projects may each run a
 *                          "Sprint 7" and the name maps to BOTH ids. COMPLETED sprints
 *                          are excluded from name resolution — years of history would
 *                          flood the namespace and a name resolving to 40 ids is
 *                          useless — but issues still carrying one match by id
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
 *                          projects <em>that have the {@code releases} capability
 *                          on</em>, original casing, de-duplicated
 *                          case-insensitively (for the {@code /schema}
 *                          {@code VERSION} picklist, HD-107 §9.1). The capability
 *                          narrows SUGGESTIONS only — {@link #versionIdsByName}
 *                          above still spans every visible project, so a saved
 *                          filter never stops resolving because a curator flipped a
 *                          toggle
 * @param sprintNames       open sprint display names across the visible projects
 *                          <em>that have the {@code board = SCRUM} capability</em>,
 *                          original casing, de-duplicated case-insensitively (for the
 *                          {@code /schema} {@code SPRINT} picklist). Same
 *                          suggestion-only narrowing as {@code versionNames}
 * @param customFieldsByKey non-archived custom fields (M2) reachable by any visible
 *                          project, keyed by their machine {@code key} (== HQL field
 *                          name). System field names always win — a key here is only
 *                          consulted when it is NOT a system field (HD-52). A custom
 *                          field a caller can't see simply isn't in this map (no leak).
 * @param capabilities      which delivery capabilities the visible projects declare
 *                          (HD-107 §9.1). Used ONLY to decide what {@code /schema}
 *                          <em>suggests</em>; never consulted by the validator, the
 *                          compiler or the scope predicate
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
        Map<String, List<UUID>> sprintIdsByName,
        Map<String, List<UUID>> projectIdsByKey,
        List<Member> members,
        List<ProjectRef> projects,
        List<String> statusNames,
        List<String> typeNames,
        List<String> priorityNames,
        List<String> labelNames,
        List<String> componentNames,
        List<String> versionNames,
        List<String> sprintNames,
        Map<String, CustomFieldMeta> customFieldsByKey,
        Capabilities capabilities
) {
    /** A workspace member's identity for USER_REF resolution. */
    public record Member(UUID id, String email, String displayName) {}

    /**
     * A visible project's identity for the {@code project} field (HD-101): both the things
     * a caller may write as an operand, plus the id both resolve to.
     *
     * <p>The two are not interchangeable, and only one of them resolves. A {@code key} is
     * unique within the workspace and is what the UI shows and what users type; a
     * {@code name} is a label with no uniqueness of its own, so it never denotes a project
     * in the query language. Value suggestions therefore offer the name as the LABEL and
     * the key as the VALUE, so what a client pastes back is the identifier — the same
     * split {@code /suggest} already makes for a member (display name shown, email
     * inserted), and for the same reason.
     */
    public record ProjectRef(UUID id, String key, String name) {}

    /**
     * The delivery capabilities (delivery-paths proposal §2.3) declared by the actor's
     * visible projects, as the id subsets that have each capability ON.
     *
     * <p><strong>Suggestion-only, by contract (§9.1).</strong> A capability is a
     * presentation preference, so it may narrow what {@code /search/schema} offers and
     * nothing else: {@code sprint}, {@code fixVersion}, {@code affectsVersion} and
     * {@code storyPoints} keep parsing, compiling and running on every project
     * regardless of what is stored here. A saved filter must never break because a
     * colleague flipped a toggle — that is the same class of failure the delivery-paths
     * work exists to fix. Nothing in {@link HqlValidator}, {@link HqlCompiler} or
     * {@link SearchScope} reads this record.
     *
     * <p>Kept as id subsets rather than plain booleans so the value picklists can be
     * built from exactly the capability-on projects; the booleans are derived. Each
     * list is always a SUBSET of {@code visibleProjectIds} — narrowing only, so it can
     * never widen the tenant boundary.
     *
     * @param iterationProjectIds visible projects with {@code board = SCRUM}
     * @param releaseProjectIds   visible projects with {@code releases} on
     * @param estimationProjectIds visible projects with {@code estimation} on
     */
    public record Capabilities(
            List<UUID> iterationProjectIds,
            List<UUID> releaseProjectIds,
            List<UUID> estimationProjectIds
    ) {
        /** At least one visible project plans in sprints. */
        public boolean iterations() {
            return !iterationProjectIds.isEmpty();
        }

        /** At least one visible project uses releases. */
        public boolean releases() {
            return !releaseProjectIds.isEmpty();
        }

        /** At least one visible project estimates. */
        public boolean estimation() {
            return !estimationProjectIds.isEmpty();
        }
    }

    /** A visible custom field by its HQL name (its {@code key}), case-insensitively. */
    public java.util.Optional<CustomFieldMeta> customField(String key) {
        if (key == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(
                customFieldsByKey.get(key.toLowerCase(java.util.Locale.ROOT)));
    }
}
