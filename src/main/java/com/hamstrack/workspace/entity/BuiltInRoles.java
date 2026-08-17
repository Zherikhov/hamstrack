package com.hamstrack.workspace.entity;

import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.ProjectRole;

import java.util.Map;
import java.util.UUID;

/**
 * The fixed identities of the seven built-in role templates seeded by
 * {@code V13__roles.sql} (roles-permissions-proposal §7).
 *
 * <p><strong>Why the ids are literals and not looked up.</strong> Built-in roles are
 * product constants, not data: the same seven rows exist in every install and every
 * environment. Hard-coding their ids here — mirroring the migration exactly — means the
 * resolver can name "the built-in Contributor role" with <em>zero</em> queries, which is
 * what keeps §9.2's constant authorization cost honest. It also makes the V14 backfill a
 * plain {@code CASE} instead of a correlated subquery, and lets a test fixture assert on
 * a recognisable value. {@code com.hamstrack.workspace.RoleIdsMatchMigrationTest} pins the
 * two lists together so they cannot drift: it resolves every constant below through the
 * catalog and asserts the row's key, scope, {@code built_in} flag and — for the built-ins
 * whose contents are load-bearing — its <strong>exact permission key set</strong>, own
 * qualifiers included. Exact sets rather than counts, because a count cannot see a
 * <em>swap</em>: drop {@code issue.rank} from Contributor and add {@code project.edit} and
 * the total is unchanged while every member in every install silently loses backlog
 * ranking and gains project settings.
 *
 * <p>The ids are valid UUID v7s <em>by shape</em> (version nibble 7, variant 8) so
 * nothing downstream that assumes v7 is surprised, but they are deliberately
 * human-readable rather than time-ordered: a constant a human has to recognise in a
 * migration, a test fixture and a psql session is worth more than a sortable one.
 *
 * <p><strong>Keys are the legacy enum names on purpose.</strong> That is what makes
 * {@code myRole} wire-compatible with today's SPA across S1–S4, and what lets the ~42
 * not-yet-migrated call sites keep using {@code WorkspaceRole}/{@code ProjectRole} as a
 * bridge. Both enums are deleted in S3; this class outlives them, with the string keys.
 */
public final class BuiltInRoles {

    // ---- workspace scope (§7.1) ------------------------------------------------
    public static final UUID WORKSPACE_OWNER  = UUID.fromString("00000000-0000-7000-8000-000000000001");
    public static final UUID WORKSPACE_ADMIN  = UUID.fromString("00000000-0000-7000-8000-000000000002");
    public static final UUID WORKSPACE_MEMBER = UUID.fromString("00000000-0000-7000-8000-000000000003");

    // ---- project scope (§7.2) --------------------------------------------------
    /** "Project admin" — all 20 project permissions. */
    public static final UUID PROJECT_MANAGER   = UUID.fromString("00000000-0000-7000-8000-000000000011");
    /**
     * "Contributor" — <strong>the load-bearing row</strong>. Its permission set is,
     * verbatim, everything a workspace member can do in a project today, which is what
     * makes the upgrade a provable no-op (§8.4). It is also the fallback default project
     * role when neither the project nor the workspace names one.
     */
    public static final UUID PROJECT_MEMBER    = UUID.fromString("00000000-0000-7000-8000-000000000012");
    /** "Commenter" — comment + attach + assignable, nothing else. */
    public static final UUID PROJECT_COMMENTER = UUID.fromString("00000000-0000-7000-8000-000000000013");
    /** "Viewer" — grants nothing. Note this CHANGES MEANING versus the legacy enum (§7.2). */
    public static final UUID PROJECT_VIEWER    = UUID.fromString("00000000-0000-7000-8000-000000000014");

    private static final Map<String, UUID> WORKSPACE_BY_KEY = Map.of(
            "OWNER", WORKSPACE_OWNER,
            "ADMIN", WORKSPACE_ADMIN,
            "MEMBER", WORKSPACE_MEMBER);

    private static final Map<String, UUID> PROJECT_BY_KEY = Map.of(
            "MANAGER", PROJECT_MANAGER,
            "MEMBER", PROJECT_MEMBER,
            "COMMENTER", PROJECT_COMMENTER,
            "VIEWER", PROJECT_VIEWER);

    private BuiltInRoles() {
    }

    /** The id of the built-in role with this key in this scope. */
    public static UUID id(RoleScope scope, String key) {
        var id = (scope == RoleScope.WORKSPACE ? WORKSPACE_BY_KEY : PROJECT_BY_KEY).get(key);
        if (id == null) {
            throw new IllegalArgumentException("No built-in " + scope + " role with key '" + key + "'");
        }
        return id;
    }

    /**
     * Legacy bridge for the call sites that still speak {@link WorkspaceRole}. Deleted
     * with the enum in S3.
     */
    @Deprecated(since = "HD-123 S1")
    public static UUID id(WorkspaceRole role) {
        return id(RoleScope.WORKSPACE, role.name());
    }

    /**
     * Legacy bridge for the call sites that still speak {@link ProjectRole}. Deleted with
     * the enum in S3.
     */
    @Deprecated(since = "HD-123 S1")
    public static UUID id(ProjectRole role) {
        return id(RoleScope.PROJECT, role.name());
    }

    /** All seven, for tests and for the seed-integrity assertions. */
    public static Map<RoleScope, Map<String, UUID>> all() {
        return Map.of(RoleScope.WORKSPACE, WORKSPACE_BY_KEY, RoleScope.PROJECT, PROJECT_BY_KEY);
    }
}
