package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * No {@code workspace_members} row for that user in <em>this</em> workspace (HD-132).
 *
 * <p><strong>It is a statement about the membership, never about the user.</strong> The
 * message deliberately says nothing about whether the account exists, so a workspace admin
 * cannot use {@code DELETE /members/{userId}} as a global user-enumeration oracle: an
 * unknown id, an id belonging to another tenant's user and a member who was removed a
 * second ago are one indistinguishable answer.
 *
 * <p>That also makes a repeated DELETE a clean, idempotent 404 rather than a 500.
 */
public class WorkspaceMemberNotFoundException extends AppException {
    public WorkspaceMemberNotFoundException() {
        super("Member not found in this workspace", HttpStatus.NOT_FOUND);
    }
}
