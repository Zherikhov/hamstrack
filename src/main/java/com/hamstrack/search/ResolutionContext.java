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
 * @param statusNames       distinct status display names reachable by visible
 *                          projects, original casing (for the {@code /schema}
 *                          picklist); {@code typeNames}/{@code priorityNames} likewise
 */
public record ResolutionContext(
        User actor,
        Workspace workspace,
        List<UUID> visibleProjectIds,
        Map<String, List<UUID>> statusIdsByName,
        Map<String, List<UUID>> typeIdsByName,
        Map<String, List<UUID>> priorityIdsByName,
        Map<String, List<Priority>> prioritiesByName,
        List<Member> members,
        List<String> statusNames,
        List<String> typeNames,
        List<String> priorityNames
) {
    /** A workspace member's identity for USER_REF resolution. */
    public record Member(UUID id, String email, String displayName) {}
}
