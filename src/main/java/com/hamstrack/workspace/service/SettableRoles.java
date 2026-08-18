package com.hamstrack.workspace.service;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionSet;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.dto.RoleBlocker;
import com.hamstrack.workspace.dto.RoleRef;
import com.hamstrack.workspace.dto.SettableRolesView;
import com.hamstrack.workspace.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <strong>The grant ceiling on the two default-role pickers, evaluated and published</strong>
 * (HD-130, S7 §3 and §7.1) — one implementation shared by {@code GET …/project-access} (W1),
 * {@code GET …/default-role} (P1) and the runtime refusals both writes raise.
 *
 * <p>One implementation is the entire point. The tooltip that greys a role out and the 403 a
 * client gets if it sends it anyway quote the <em>same</em> permission key, because they come
 * from the same {@link PermissionSet#firstNotCovered} call over the same role list. Two
 * implementations of one predicate — especially one re-derived in TypeScript — is the
 * HD-98/HD-116 bug class by construction.
 *
 * <p><strong>The comparand is the caller's, and it differs by scope</strong> (§3.1); this
 * class does not choose it, it applies it:
 * <ul>
 *   <li><strong>workspace</strong> ({@code workspaces.default_project_role_id}):
 *       {@code effectiveProjectPermissions(ctx.permissions(), builtInContributor)} — the
 *       product's floor for a project, plus whatever the actor's <em>workspace</em> role grants
 *       in every project. A <strong>constant</strong> baseline, and the only ceiling in the
 *       product measured against one. The two obvious alternatives are both wrong: the actor's
 *       workspace-derived project set (the curator four) would refuse an Admin setting the
 *       default to <em>Contributor</em>, the value the product ships — a ceiling that forbids
 *       the current state is a bug, not a ceiling; and "the actor's set under the current
 *       default" moves when the thing it bounds moves, which makes it a one-way ratchet (narrow
 *       the default once and you cannot put it back). {@link #ownerExempt} covers the built-in
 *       workspace Owner, in their own workspace;</li>
 *   <li><strong>project</strong> ({@code projects.default_project_role_id}):
 *       {@code ctx.permissions()}, the actor's real, already-resolved effective set in that
 *       project. Nobody is exempt.</li>
 * </ul>
 *
 * <p><strong>{@code ProjectService.requireGrantable}'s §4 escape does NOT reach here.</strong>
 * That escape lets a holder of {@code project.member.manage} hand the built-in Project admin
 * to <em>another member</em>, and its {@code target != actor} constraint is load-bearing. A
 * default has no target — or rather its target is everyone, the actor included — so extending
 * it would hand all twenty project permissions to the whole workspace on the authority of one.
 * The escape is checked at the membership doors and nowhere else; the default-role doors call
 * the plain ceiling.
 *
 * <p>Tenancy: {@link RoleRepository#findAvailableWithPermissions} is workspace-filtered, so
 * every role named is a built-in template or one of this workspace's own — exactly the set the
 * caller can already list through {@code GET /roles}.
 */
@Service
@RequiredArgsConstructor
public class SettableRoles {

    private final RoleRepository roleRepository;
    private final RoleCatalog roleCatalog;
    private final WorkspaceAccessService workspaceAccess;

    /**
     * The PROJECT-scoped roles available in this workspace, partitioned by whether
     * {@code comparand} covers them.
     *
     * <p>One statement (the roles, with their grants {@code JOIN FETCH}ed) and then bit tests
     * over cached {@link PermissionSet}s. Linear in the number of roles, not quadratic like
     * S4's {@code assignment} block: there is one comparand here, not one per role.
     */
    @Transactional(readOnly = true)
    public SettableRolesView projectRoles(UUID workspaceId, PermissionSet comparand, boolean exempt) {
        var canSet = new ArrayList<RoleRef>();
        var cannotSet = new ArrayList<RoleBlocker>();
        for (var role : roleRepository.findAvailableWithPermissions(
                workspaceId, EnumSet.of(RoleScope.PROJECT))) {
            var view = RoleView.of(role);
            var missing = exempt ? Optional.<Permission>empty()
                                 : comparand.firstNotCovered(view.permissions());
            if (missing.isEmpty()) {
                canSet.add(new RoleRef(view.id(), view.name()));
            } else {
                cannotSet.add(new RoleBlocker(view.id(), view.name(), missing.get().key()));
            }
        }
        return new SettableRolesView(List.copyOf(canSet), List.copyOf(cannotSet));
    }

    /**
     * The workspace-scope comparand (§3.1): the built-in Contributor's set, unioned with
     * whatever the actor's workspace role grants in <em>every</em> project
     * ({@code project.curate.all} / {@code project.administer.all}).
     *
     * <p>Explicable in one sentence — <em>"you may set the default to anything a workspace
     * member already gets by default; anything beyond that needs an Owner"</em> — stable,
     * un-gameable by moving the default around, and it keeps the shipped value settable. Its
     * entire practical effect: an Admin may set the workspace default to Viewer, Commenter,
     * Contributor or any custom role within Contributor's set, and may not set Team lead,
     * Project admin, or anything carrying {@code project.member.manage},
     * {@code project.archive}, {@code project.taxonomy.manage}, {@code issue.delete} or
     * unrestricted {@code attachment.delete}.
     */
    public PermissionSet workspaceComparand(PermissionSet actorWorkspacePermissions) {
        return workspaceAccess.effectiveProjectPermissions(
                actorWorkspacePermissions, roleCatalog.defaultProjectRole());
    }

    /**
     * The one exemption (§3.1): the <strong>built-in workspace Owner</strong>, in their own
     * workspace, is the root of trust and may name any assignable PROJECT role as the default.
     *
     * <p>Keyed on the built-in role <em>id</em> and never the key string, for
     * {@code RoleView.isBuiltIn}'s reason: {@code roles_scope_key_uk} is
     * {@code UNIQUE NULLS NOT DISTINCT}, so a workspace may legally own a custom role keyed
     * {@code OWNER} and that role is not this guardrail.
     */
    public boolean ownerExempt(RoleView workspaceRole) {
        return workspaceRole.isBuiltIn(com.hamstrack.workspace.entity.BuiltInRoles.WORKSPACE_OWNER);
    }
}
