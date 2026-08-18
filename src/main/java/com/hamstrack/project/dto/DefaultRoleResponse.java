package com.hamstrack.project.dto;

import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.entity.Workspace;

import java.util.UUID;

/**
 * The <strong>default project role</strong> a workspace member inherits in this project
 * when they have no {@code project_members} row (HD-129, S6) — both links of §5.2's
 * chain, so a client can say not only <em>what</em> the default is but <em>where it came
 * from</em>:
 * {@code projects.default_project_role_id} → {@code workspaces.default_project_role_id} →
 * the built-in <strong>Contributor</strong>.
 *
 * <p>It rides {@link ProjectResponse} for the same reason {@link DeliveryResponse} does:
 * every surface that needs it already fetches that response, so the Project People
 * default-access card costs no extra request. And for most workspaces this is the
 * <em>primary</em> grant mechanism — almost nobody has an explicit {@code project_members}
 * row (§2.3) — so a card that can count who is covered but cannot name what they get is
 * describing the mechanism with the answer missing.
 *
 * <p><strong>Read-only, and deliberately so. There is no write path here, and this slice
 * must not grow one.</strong> The picker and its enforcement are S7's, because the write
 * needs a bound this response cannot supply: {@code defaultProjectRole} hands the named
 * role to <em>every</em> workspace member without an explicit row, so an unbounded picker
 * lets an Admin grant permissions they do not hold themselves — the §11.2 grant ceiling
 * has to apply to it exactly as it applies to handing a role to one person. Accepting a
 * default-role write anywhere before that ceiling exists is the escalation door S7 exists
 * to close. (Same shape as {@code delivery}: configuration state on the read, the write
 * lives elsewhere — except that here "elsewhere" does not exist yet.)
 *
 * <p><strong>Not exposed here:</strong> {@code workspaces.project_access_mode}. It is
 * S7's too, and publishing it before the switch exists would invite a client to render a
 * control that does nothing. Note the consequence, honestly: in a {@code STRICT}
 * workspace there is no inheritance at all, so these ids describe a chain nobody walks —
 * unreachable today (V14 leaves every workspace {@code OPEN} and no write path exists),
 * and S7 ships the mode and the caveat together.
 *
 * @param projectRoleId this project's own override, or {@code null} when it inherits the
 *                      workspace default. Emitted verbatim from the column: it is a plain
 *                      FK to {@code roles(id)}, which the database cannot constrain to
 *                      "…and a PROJECT role of THIS workspace", so a client that cannot
 *                      find the id in {@code GET /workspaces/{id}/roles} must render a
 *                      placeholder rather than a guess. No name or permission ever leaves
 *                      through this field — only an id — and
 *                      {@code WorkspaceAccessService.defaultProjectRole} runs the
 *                      scope/ownership assertion on the value that actually decides access.
 * @param workspaceRoleId the workspace-level default, or {@code null} when the chain falls
 *                      through to the built-in Contributor. Same column semantics.
 */
public record DefaultRoleResponse(
        UUID projectRoleId,
        UUID workspaceRoleId
) {
    /**
     * Zero queries: both are plain UUID columns on entities the caller has already
     * resolved (the workspace through {@code requireMember}, the project inside it).
     * Neither {@code Project.defaultProjectRole} nor {@code Workspace.defaultProjectRole}
     * — the read-only navigation properties beside them — is touched, because
     * dereferencing either would fire a lazy load per row on {@code GET /projects}.
     *
     * <p><strong>Package-private on purpose.</strong> It joins two entities and nothing in
     * the signature says they belong together: a mismatched pair would report another
     * workspace's default project role as this project's inherited one. The single caller
     * that may make that join is {@link ProjectResponse#of}, which asserts the pair first —
     * so the unchecked join is not merely unused, it is unreachable. Widening this back to
     * {@code public} re-opens it.
     */
    static DefaultRoleResponse of(Project p, Workspace w) {
        return new DefaultRoleResponse(p.getDefaultProjectRoleId(), w.getDefaultProjectRoleId());
    }
}
