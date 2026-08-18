package com.hamstrack.workspace.dto;

import java.util.UUID;

/**
 * The smallest useful reference to a role: enough to render a chip and enough to send it
 * back as an assignment. Used by the derived {@code assignment} block.
 *
 * <p><strong>Tenancy.</strong> Every role named in one of these is a role the caller can
 * already list through {@code GET /api/workspaces/{ws}/roles} — a built-in template or one
 * of this workspace's own. Do not reuse it to name a role resolved from somewhere else;
 * the read-side degrade exists precisely because a foreign role's name must never be
 * rendered.
 */
public record RoleRef(UUID roleId, String name) {
}
