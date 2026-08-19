package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.common.security.Permission;
import org.springframework.http.HttpStatus;

/**
 * <strong>403</strong> — this role <em>is</em> a default, so editing its contents is a grant to
 * everybody who inherits it (HD-130 S7, security review round 2 — the Critical).
 *
 * <p><strong>What it stops.</strong> S7 introduced a second way to hold a PROJECT role: the
 * §5.2 default chain, which hands the named role to every workspace member with no explicit
 * {@code project_members} row. {@code RoleService.requireNotSelfWidening} tests holding with
 * {@code ProjectMemberRepository.existsHolderInWorkspace} — <em>explicit rows only</em> — so
 * that second way is invisible to it, and the PROJECT-scope definition ceiling is deliberately
 * absent (§11.3). Three calls, all previously 200, took a workspace Admin to all twenty project
 * permissions for themselves and for every member without a row:
 * <ol>
 *   <li>duplicate Contributor into a custom PROJECT role {@code R} (no definition ceiling at
 *       PROJECT scope — that is the product's primary recipe and stays legal);</li>
 *   <li>{@code PATCH /workspaces/{ws}} {@code {"defaultProjectRoleId": R}} — the S7 assignment
 *       ceiling passes, because {@code R} is narrow <em>at that moment</em>;</li>
 *   <li>{@code PATCH /workspaces/{ws}/roles/{R}} with all twenty project keys — and nothing
 *       looked again.</li>
 * </ol>
 * The five gates {@code V13__roles.sql} withholds from Owner and Admin on purpose
 * ({@code issue.delete}, {@code project.archive}, {@code project.taxonomy.manage},
 * unrestricted {@code attachment.delete}, {@code project.member.manage}) all arrive that way.
 * The project-scope twin is the same three calls through
 * {@code PATCH …/projects/{p}/default-role} for anyone holding {@code project.member.manage}
 * there — self-granting all twenty, which the §4 escape's {@code target != actor} constraint
 * exists to forbid.
 *
 * <p><strong>The rule: a default's contents stay inside the ceiling that let it become one.</strong>
 * "Is a default" is a holding mechanism, so the guard has to recognise it — the same set
 * comparison {@code SettableRoles} applies at the picker, re-applied to the <em>post-edit</em>
 * set. {@link #workspaceDefault} is that check, against the very comparand
 * {@code SettableRoles.workspaceComparand} publishes, so the greyed-out picker entry and this
 * refusal quote one value.
 *
 * <p><strong>Why a project default is refused outright</strong> ({@link #projectDefaults}).
 * The project-scope comparand is {@code ctx.permissions()} — the actor's real effective set
 * <em>in that project</em> — and {@code RoleService} has no {@code ProjectContext}, nor one
 * project: a role may be the declared default of many, in each of which the actor holds
 * something different. Inventing a workspace-level comparand is the mistake §11.3 documents at
 * length. So the refusal is stated about the transition instead — <em>a role that is a project
 * default may not be widened</em> — which is exactly the escalation and nothing else: narrowing
 * it, renaming it and re-describing it stay legal, because none of them hands anybody a
 * permission they did not already inherit.
 *
 * <p>Both refusals are satisfiable by the person refused, and in one move: stop the role being
 * a default (point the picker elsewhere, or clear the project override), then edit it freely.
 * The workspace Owner is exempt, in their own workspace, as at every other ceiling.
 *
 * <p>The detail names the offending permission, like every ceiling refusal in the product: the
 * rule is a <strong>subset</strong> rule, and "insufficient role" is not an answer to a subset
 * failure.
 */
public class DefaultRoleCeilingException extends AppException {

    private DefaultRoleCeilingException(String detail) {
        super(detail, HttpStatus.FORBIDDEN);
    }

    /**
     * The role is {@code workspaces.default_project_role_id}, and its post-edit set leaves the
     * workspace comparand (built-in Contributor ∪ the actor's own {@code project.curate.all} /
     * {@code project.administer.all}).
     */
    public static DefaultRoleCeilingException workspaceDefault(String roleName, Permission missing) {
        return new DefaultRoleCeilingException(
                "\"" + roleName + "\" is this workspace's default project role, so its grants "
                + "reach every member with no explicit project membership. This edit would give "
                + "them " + missing.key() + ", which you cannot hand out. Point the workspace "
                + "default at another role first, or ask an Owner");
    }

    /**
     * The role is the declared default of one or more projects, and this edit widens it. No
     * comparand exists here — see the class javadoc — so the transition itself is the refusal.
     */
    public static DefaultRoleCeilingException projectDefaults(String roleName, Permission added,
                                                              long projects) {
        return new DefaultRoleCeilingException(
                "\"" + roleName + "\" is the default role of " + projects + " project"
                + (projects == 1 ? "" : "s") + ", so widening it would grant " + added.key()
                + " to everyone in " + (projects == 1 ? "it" : "them") + " without going through "
                + "that project's ceiling. Clear those defaults first, narrow the edit, or ask "
                + "an Owner");
    }
}
