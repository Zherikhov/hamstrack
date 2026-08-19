package com.hamstrack.workspace.dto;

import java.util.UUID;

/**
 * Where a role is currently in play — the body of {@code GET /roles/{id}/usage}, the
 * optional {@code usage} block on the roles list, and the extension on the
 * {@code ROLE_IN_USE} 409 so a client can render the remap dialog straight from the
 * refusal.
 *
 * <p><strong>Every count is filtered to one workspace.</strong> Built-in roles are shared
 * rows ({@code workspace_id IS NULL}), so an unscoped {@code COUNT(*) WHERE role_id = …}
 * on {@code workspace_members} would publish every other tenant's headcount from a
 * workspace-scoped endpoint. That is the single most likely tenancy defect in this slice
 * and it has its own acceptance test.
 *
 * @param inUse the disjunction the delete guard itself uses, published so the client cannot
 *              compute a different answer from the parts and then be surprised by a 409
 */
public record RoleUsageResponse(
        UUID roleId,
        long members,
        long invites,
        long projectMembers,
        long projects,
        long defaultForProjects,
        boolean defaultForWorkspace,
        boolean inUse
) {
    public static RoleUsageResponse of(UUID roleId, long members, long invites,
                                       long projectMembers, long projects,
                                       long defaultForProjects, boolean defaultForWorkspace) {
        var inUse = members > 0 || invites > 0 || projectMembers > 0
                    || defaultForProjects > 0 || defaultForWorkspace;
        return new RoleUsageResponse(roleId, members, invites, projectMembers, projects,
                defaultForProjects, defaultForWorkspace, inUse);
    }

    /**
     * A role in play nowhere — <strong>an explicit zero, never {@code null}</strong>.
     *
     * <p>The grouped queries behind {@code includeUsage=true} only yield rows for roles that
     * appear somewhere, so an unused role used to come back with {@code usage: null} while
     * {@code GET /roles/{id}/usage} answered a full zeroed object for the same role. Two
     * endpoints disagreeing about the same fact is the HD-98/HD-116 bug class, and the
     * consumer is the role editor, which shows "0 members, 0 invites" before a destructive
     * edit — where {@code null} reads as <em>unknown</em>, which is the one thing it is not.
     * {@code inUse} is correspondingly {@code false}, so the client's precondition for
     * "delete needs no reassign target" matches the server's.
     */
    public static RoleUsageResponse unused(UUID roleId) {
        return new RoleUsageResponse(roleId, 0, 0, 0, 0, 0, false, false);
    }
}
