package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409</strong> — the {@code version} the editor sent is not the row's current one,
 * so somebody else changed this role in between.
 *
 * <p>Follows the shipped {@code IssueService} convention: the check is explicit and
 * pre-flight when {@code version} is present, and {@code @Version} still catches a true
 * concurrent flush through {@code GlobalExceptionHandler.handleOptimisticLock} — also a
 * 409 — when it is not. Both matter: the explicit one turns a lost update into a message a
 * human can act on, the implicit one is the guarantee.
 *
 * <p>A permission set is exactly the kind of state where last-writer-wins is unacceptable:
 * two admins each unticking one box would otherwise silently restore the other's, and the
 * loser's revocation would look like it had been applied.
 */
public class RoleVersionConflictException extends AppException {

    public RoleVersionConflictException() {
        super("Role was modified by someone else — refresh and retry", HttpStatus.CONFLICT);
    }
}
