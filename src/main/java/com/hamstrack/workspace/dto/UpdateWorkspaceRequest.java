package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.ProjectAccessMode;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * <strong>{@code PATCH /api/workspaces/{ws}}</strong> (HD-130, S7 §7.1 W3) — the workspace
 * General settings write, and the same body {@code POST …/project-access/preview} takes.
 *
 * <p>Every field is optional and every one is a separate decision, so a body naming only
 * {@code name} must not disturb the access mode and a body naming only the mode must not
 * disturb the name. A body naming <em>none</em> of them is a 400 rather than a silent 200:
 * "I asked for nothing and something might have happened" is not an answer.
 *
 * @param name                    trimmed by the canonical constructor; 1..255 after
 *                                trimming. The product's first rename affordance
 * @param projectAccessMode       {@code OPEN} | {@code STRICT}. An unknown value is a 400 at
 *                                the Jackson boundary, which is the right failure: there is
 *                                no third mode to fall back to
 * @param defaultProjectRoleId    the workspace's default project role. Resolved through
 *                                {@code RoleCatalog.requireAssignable(PROJECT, wsId, id)} —
 *                                422 for unknown, foreign or WORKSPACE-scoped, and
 *                                <strong>PROJECT scope is not negotiable</strong>: a
 *                                WORKSPACE role accepted here would put {@code workspace.edit}
 *                                into every member's {@code ProjectContext} in every project
 *                                of the workspace, the wrong-scope escalation with the widest
 *                                blast radius in the product
 * @param clearDefaultProjectRole write {@code NULL}, i.e. fall through to the built-in
 *                                Contributor. <strong>{@code Boolean}, never
 *                                {@code boolean}</strong> — Jackson 3 enables
 *                                {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive here
 *                                would 400 every partial body that omits it (the
 *                                {@code UpdateIssueRequest} clear-flag bug). Coalesced
 *                                null → false below. Sending it <em>and</em>
 *                                {@code defaultProjectRoleId} is a 422, not a precedence
 *                                rule: two fields that mean different things must not be
 *                                resolved by picking a winner
 */
public record UpdateWorkspaceRequest(
        @Size(min = 1, max = 255) String name,
        ProjectAccessMode projectAccessMode,
        UUID defaultProjectRoleId,
        Boolean clearDefaultProjectRole
) {
    public UpdateWorkspaceRequest {
        name = name == null ? null : name.trim();
        clearDefaultProjectRole = clearDefaultProjectRole != null && clearDefaultProjectRole;
    }

    /** Whether this body asks for anything at all (§7.1 rule 1). */
    public boolean isEmpty() {
        return name == null && projectAccessMode == null
                && defaultProjectRoleId == null && !clearDefaultProjectRole;
    }

    /** Whether it names the default project role in either of the two accepted ways. */
    public boolean touchesDefaultProjectRole() {
        return defaultProjectRoleId != null || clearDefaultProjectRole;
    }

    /** Both ways at once — refused rather than resolved by precedence. */
    public boolean namesDefaultProjectRoleAmbiguously() {
        return defaultProjectRoleId != null && clearDefaultProjectRole;
    }
}
