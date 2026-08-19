package com.hamstrack.project.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409</strong> — a project that has any explicit administrator must not lose its
 * last one. Mirrors {@code LastWorkspaceOwnerException} (HD-132) in shape and in reasoning:
 * the caller's permissions are fine, so answering 403 would tell a project admin they lack
 * a permission they demonstrably hold; what refuses is the project's own invariant.
 *
 * <p><strong>This is the one user-visible change in the HD-125 review round</strong>, and
 * it is narrow: {@code DELETE /projects/{p}/members/{u}} on the <em>last</em> member
 * holding {@code project.member.manage} used to succeed. It stranded the project —
 * {@code project.member.manage} is not part of the workspace-admin curator bypass
 * ({@code project.curate.all}), so once the last project admin was gone <em>nobody</em>
 * could add members back, including a workspace Owner. A project with no explicit members
 * at all is still perfectly normal (§8.4 deliberately does not backfill a row per member);
 * what is refused is only the transition from "one administrator" to "none".
 */
public class LastProjectAdminException extends AppException {
    public LastProjectAdminException() {
        super("This is the project's last administrator — add another one first",
                HttpStatus.CONFLICT);
    }
}
