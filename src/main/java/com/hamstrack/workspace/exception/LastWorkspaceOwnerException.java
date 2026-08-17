package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * A workspace must never lose its last OWNER (HD-132) — neither by a role change that
 * demotes them nor by a removal, and neither when an admin acts on someone else nor when
 * an owner acts on themselves through the admin path.
 *
 * <p><strong>409, not 403.</strong> The caller is entitled to do this to any other member;
 * what fails is the workspace's own invariant ("someone must be able to administer it"),
 * which is a state conflict rather than a permission failure. Answering 403 would tell an
 * owner they lack a permission they demonstrably hold.
 *
 * <p>The guard lives in {@code WorkspaceMemberService.requireNotLastOwner} and is called
 * from both mutation paths; HD-126 (S3) re-points it at the permission model rather than
 * writing a second copy.
 */
public class LastWorkspaceOwnerException extends AppException {
    public LastWorkspaceOwnerException() {
        super("This is the workspace's last owner — promote another member to owner first",
                HttpStatus.CONFLICT);
    }
}
