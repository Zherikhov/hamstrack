package com.hamstrack.workspace.dto;

import java.util.List;

/**
 * <strong>What this role could hand out, derived by the server</strong> (§5) — the
 * compose-time feedback that makes the grant ceiling visible while the checkboxes are being
 * ticked rather than as a 403 six weeks later.
 *
 * <p>Computed with {@code PermissionSet.firstNotCovered} over the roles of the same scope
 * available in this workspace (built-ins plus custom), excluding the role itself. It is the
 * <em>same call</em> the runtime ceiling makes, which is the only reason the preview and
 * the refusal cannot disagree. <strong>Re-implementing it in TypeScript is not an
 * option</strong>: the own-only/unrestricted asymmetry is subtle, and a second
 * implementation of a server predicate in the SPA is the HD-98/HD-116 bug class by
 * construction.
 *
 * <p><strong>It is a LOWER BOUND, and the copy must say so.</strong> A real actor's
 * effective set at project scope is their project role unioned with
 * {@code project.curate.all} / {@code project.administer.all} from their <em>workspace</em>
 * role, so a holder may in practice assign more than {@link #canAssign} lists. The block is
 * computed from the role alone because that is the conservative direction and the one
 * {@link #warnings} is about; S6 renders it as "on its own, this role can assign: …".
 *
 * @param managesMembers whether the role holds {@code workspace.member.manage} /
 *                       {@code project.member.manage} <em>unrestricted</em> for its own
 *                       scope — i.e. whether it can assign anybody at all
 * @param warnings       machine-readable codes, so S6 renders copy rather than re-deriving
 *                       the condition. {@link #MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING} fires
 *                       when {@code managesMembers} and every role in {@link #canAssign}
 *                       grants nothing — a member-manager who can add people to a project
 *                       and cannot give any of them the ability to do a single thing. It is
 *                       deliberately <em>not</em> the literal {@code canAssign.isEmpty()}:
 *                       the built-in Viewer grants the empty set and the empty set is
 *                       covered by every set, so every role can assign Viewer and that
 *                       condition would be unreachable — a warning that never fires. The
 *                       cause is the same one either way: the ceiling is a <strong>subset
 *                       </strong> rule and not a ladder, so a role built from scratch
 *                       holding only member management is a superset of nothing useful.
 *                       <strong>Warn, never block:</strong> a role granting nothing is legal
 *                       (§11.4) and an intermediate save is legitimate.
 */
public record RoleAssignmentView(
        boolean managesMembers,
        List<RoleRef> canAssign,
        List<RoleBlocker> cannotAssign,
        List<String> warnings
) {
    /** @see #warnings */
    public static final String MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING =
            "MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING";
}
