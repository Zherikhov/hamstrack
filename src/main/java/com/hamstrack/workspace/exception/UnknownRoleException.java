package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>422</strong> — a request named a role this build cannot assign in that scope
 * (roles-permissions-proposal §11.4, §12).
 *
 * <p><strong>Why 422 and not 404 or 400.</strong> 404 would be a statement about
 * existence, and once S4 ships custom roles the same code path resolves ids that may
 * belong to <em>another workspace</em> — answering "not found" for a foreign id and
 * something else for a nonsense one is a two-value oracle for whether a role id exists in
 * another tenant, which is precisely the leak §12 forbids. 400 is wrong for the opposite
 * reason: the request is perfectly well-formed, it is the <em>value</em> that cannot be
 * honoured, which is the same shape as "Unknown label" and "Unknown sprint" elsewhere.
 * One answer for every unusable role reference, leaking nothing.
 *
 * <p>The detail echoes the key the caller sent, which is theirs already, and never says
 * whether some role by that name exists somewhere they cannot see.
 */
public class UnknownRoleException extends AppException {

    public UnknownRoleException(String role) {
        super("Unknown role: " + role, HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
