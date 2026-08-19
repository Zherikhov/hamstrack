package com.hamstrack.workspace.dto;

import java.util.List;

/**
 * <strong>Which PROJECT roles the caller may make a default, and why not for the rest</strong>
 * (HD-130, S7 §7.1) — the compose-time face of the grant ceiling on the two default-role
 * pickers, so an admin sees the bound while choosing rather than as a 403 afterwards.
 *
 * <p>Derived <strong>server-side</strong> with {@code PermissionSet.firstNotCovered} — the
 * same call the runtime ceiling makes, which is the only reason the greyed-out tooltip and
 * the refusal can quote one value. <strong>It is not re-derived in TypeScript</strong>: the
 * own-only/unrestricted asymmetry is subtle, and a second implementation of a server
 * predicate in the SPA is the HD-98/HD-116 bug class by construction.
 *
 * <p>Same shape and same argument as S4's {@link RoleAssignmentView}, and deliberately not
 * the same code path: that block compares a <em>role</em> against its peers, this one
 * compares the <em>actor's</em> comparand — and the comparand differs by scope (§3.1). At
 * workspace scope it is the built-in Contributor's set ∪ the actor's workspace-wide project
 * grants, a <em>constant</em> baseline and the only ceiling in the product measured against
 * one; at project scope it is the actor's real effective set in that project.
 *
 * <p><strong>Tenancy.</strong> Every role named here is one the caller can already list
 * through {@code GET /api/workspaces/{ws}/roles} — a built-in template or one of this
 * workspace's own ({@code RoleRepository.findAvailableWithPermissions}, workspace-filtered).
 *
 * @param canSet    roles the ceiling admits, in the catalog's display order
 * @param cannotSet roles it refuses, each with the <em>first</em> permission that is why —
 *                  the identical string the runtime 403 carries for that role
 */
public record SettableRolesView(List<RoleRef> canSet, List<RoleBlocker> cannotSet) {
}
