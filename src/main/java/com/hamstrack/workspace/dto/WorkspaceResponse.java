package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceRole;
import com.hamstrack.workspace.service.RoleView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param myRole        the caller's workspace role. <strong>Display only from S5
 *                      onwards</strong> — no UI gate may read it, because
 *                      {@code myRole === 'ADMIN'} cannot express a custom role and is
 *                      therefore wrong by construction the moment custom roles exist
 *                      (§5.3). Still the legacy enum in S1 so the wire format is
 *                      byte-identical and the SPA needs no change; it widens to a plain
 *                      string in S5.
 * @param myPermissions the caller's effective workspace permissions, as flat keys
 *                      ({@code ["workspace.edit", "label.manage:own"]} — §6.4). This is
 *                      the <strong>only</strong> permitted input to a UI gate. It rides a
 *                      response the SPA already fetches, so gating costs zero extra
 *                      requests, and it is <strong>never absent</strong>: an empty list
 *                      is a real answer, a missing field would make a client fall back to
 *                      permissive rendering (§12).
 */
public record WorkspaceResponse(
        UUID id,
        String slug,
        String name,
        WorkspaceRole myRole,
        List<String> myPermissions,
        Instant createdAt
) {
    @SuppressWarnings("deprecation") // myRole stays the legacy enum until S5
    public static WorkspaceResponse of(Workspace w, RoleView role) {
        return new WorkspaceResponse(w.getId(), w.getSlug(), w.getName(),
                role.asWorkspaceRole(), role.permissions().asWireStrings(), w.getCreatedAt());
    }
}
