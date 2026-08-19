package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.common.security.Permission;
import com.hamstrack.project.dto.ProjectRef;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <strong>403</strong> — restoring {@link com.hamstrack.workspace.entity.ProjectAccessMode#OPEN}
 * would make a <em>per-project</em> declared default live again, and that default grants more
 * than the actor may grant (HD-130, S7 — security review round 3, the second Low).
 *
 * <p><strong>Why this is not {@link WorkspaceGrantCeilingException}.</strong> The generic
 * ceiling refusal names a role and nothing else, which is exactly the wrong two facts here:
 * the reader is flipping one switch on the workspace's General page and the obstacle is a
 * column on <em>some other screen</em>, in one of possibly hundreds of projects, none of which
 * the refusal identified. Its implied remedy — narrow the offending default, "you may set it
 * to anything inside your own baseline" — is a workspace-default remedy, and it is false for a
 * project override: clearing one needs {@link Permission#PROJECT_MEMBER_MANAGE} <em>in that
 * project</em>, which is deliberately outside {@link Permission#projectCuration()}, so no
 * workspace-scoped role grants it and not even an Owner can clear it through the API without a
 * membership row there.
 *
 * <p>The trace it closes: a project administrator sets project P's default to the built-in
 * Project admin (legal — the project ceiling is the actor's own effective set there); the
 * workspace goes {@code STRICT} (legal, and correctly unbounded — taking inheritance away
 * grants nothing); a non-Owner holding only {@code workspace.edit} can then never flip back.
 * They got a 403 naming {@code issue.delete} on "Project admin", with no indication of
 * <em>which</em> project declared it, no ability to clear that override, and no ability to
 * narrow the role either, since {@code RoleService} refuses edits to a built-in.
 *
 * <p><strong>So the remedy is stated honestly and in three parts</strong>, because which one is
 * available depends on facts this class cannot see: narrow the role (only if it is a custom one
 * the reader may edit), ask an administrator of each named project to clear its default (they
 * hold {@code project.member.manage} there by definition), or ask a workspace Owner, who is
 * exempt from this ceiling everywhere. At least the last is always available, which is what
 * makes this a guard rather than a lock-out — the rule three refusals in
 * {@code StrandedProjectsException} already wrote down: <em>a refusal may only prescribe an
 * action its reader can perform.</em>
 *
 * <p>Fail-closed either way: nothing is written, and the mode stays {@code STRICT}. What
 * changes is only whether the reader can find out why.
 *
 * <p><strong>Tenancy:</strong> {@link ProjectRef} names projects of the workspace the caller is
 * already administering (they hold {@code workspace.edit} or they never reached this), all of
 * which they can list through {@code GET /api/workspaces/{ws}/projects}. Nothing is disclosed.
 */
public class ReactivatedProjectDefaultsException extends AppException {

    /**
     * <strong>{@code errorType: "REACTIVATED_DEFAULT_ABOVE_CEILING"}</strong> — the
     * discriminator, published as a ProblemDetail extension exactly as
     * {@code StrandedProjectsException} and {@code RoleInUseException} publish theirs.
     *
     * <p>A code rather than prose because the client's <em>next action</em> is distinct from
     * every other 403 in this area: the plain ceiling refusals are cleared by choosing a
     * different role in the picker the client is already showing, while this one is cleared in
     * another project's settings, by somebody who may not be the reader. A client that cannot
     * tell them apart cannot render the project links this body carries.
     */
    public static final String ERROR_TYPE = "REACTIVATED_DEFAULT_ABOVE_CEILING";

    /** How many projects the sentence names before it summarises the rest. */
    private static final int NAMED_IN_DETAIL = 3;

    private final List<ProjectRef> projects;
    private final String roleName;
    private final String missing;

    public ReactivatedProjectDefaultsException(List<ProjectRef> projects, String roleName,
                                               Permission missing) {
        super(detailFor(projects, roleName, missing), HttpStatus.FORBIDDEN);
        this.projects = List.copyOf(projects);
        this.roleName = roleName;
        this.missing = missing.key();
    }

    /**
     * <strong>Every</strong> project whose default declares the offending role — the
     * machine-readable half, rendered by the SPA as links. The sentence is capped at
     * {@link #NAMED_IN_DETAIL}; this is not.
     */
    public List<ProjectRef> getProjects() {
        return projects;
    }

    /** The role those projects declare, for a client that wants to name it in its own copy. */
    public String getRoleName() {
        return roleName;
    }

    /**
     * The first permission the actor's baseline does not cover, straight from
     * {@code PermissionSet.firstNotCovered} — the same key the picker greys the equivalent role
     * out with, because it is the same call over the same comparand.
     */
    public String getMissing() {
        return missing;
    }

    public String getErrorType() {
        return ERROR_TYPE;
    }

    private static String detailFor(List<ProjectRef> projects, String roleName, Permission missing) {
        var one = projects.size() == 1;
        return "Restoring open project access would make \u201C" + roleName + "\u201D the default in "
               + named(projects) + ", and it includes " + missing.key()
               + " \u2014 a permission you do not hold in this workspace. Narrow that role if it is "
               + "one you can edit, ask an administrator of " + (one ? "that project" : "each of those projects")
               + " to clear its default, or ask a workspace Owner to make this change.";
    }

    private static String named(List<ProjectRef> projects) {
        var named = projects.stream()
                .limit(NAMED_IN_DETAIL)
                .map(p -> p.name() + " (" + p.key() + ")")
                .collect(Collectors.joining(", "));
        var overflow = projects.size() - Math.min(projects.size(), NAMED_IN_DETAIL);
        return overflow > 0 ? named + " and " + overflow + " more" : named;
    }
}
