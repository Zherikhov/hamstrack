package com.hamstrack.workspace.event;

import java.util.UUID;

/**
 * A role's cached snapshot is stale — its permissions, its name or the whole row is gone
 * (HD-127, S4). Consumed by {@code RoleCacheEvictionListener}, which evicts
 * {@code roleView} <strong>after the publishing transaction commits</strong>.
 *
 * <p><strong>Deliberately not a {@code common.event.DomainEvent}.</strong> That interface
 * is sealed, requires a {@code workspaceId} and exists for the SSE/notification fan-out;
 * this is a process-local cache signal with no wire side effect and no broadcast, and a
 * built-in role has no workspace at all. Widening the sealed hierarchy to carry it would
 * put a cache concern into the backplane contract HD-81 will serialise.
 *
 * <p>Published on <strong>PATCH</strong> (permissions or not — a rename changes
 * {@code RoleView.name}, which the ceiling exception and the People tab render) and on
 * <strong>DELETE</strong>. Not on duplicate: a new id was never cached.
 *
 * <p><strong>HD-81 will need a {@code workspaceId} here.</strong> The shared pub/sub
 * backplane (`docs/design/p2-scaleout-proposal.md`) is expected to route by workspace, and
 * this record carries only the role id — deliberately, because in-process eviction needs
 * nothing else and a cache signal should not import the backplane's contract before it
 * exists. The field is available whenever it is wanted: both publish sites are
 * {@code RoleService.update} and {@code RoleService.delete}, both refuse a built-in
 * ({@code requireCustom}), and a custom role always has a non-null {@code workspace_id} —
 * so adding {@code UUID workspaceId} is a two-line change with no nullable case to reason
 * about. Recorded here so HD-81 does not have to re-derive it.
 */
public record RolePermissionsChanged(UUID roleId) {
}
