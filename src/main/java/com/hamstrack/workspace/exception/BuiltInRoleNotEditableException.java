package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409</strong> — built-in roles are product metadata shared by every workspace, so
 * they can be neither edited nor deleted.
 *
 * <p>409 rather than 403: the caller's permissions are fine ({@code workspace.role.manage}
 * is exactly what got them here) and what refuses is the nature of the resource, not their
 * authority over it. And rather than 422, because nothing about the request is
 * unprocessable — the identical body succeeds against a custom role.
 *
 * <p>Keyed on {@code roles.built_in}, <strong>never on the key string</strong>: once this
 * slice ships, {@code UNIQUE NULLS NOT DISTINCT (workspace_id, scope, key)} lets a workspace
 * own a custom role keyed {@code ADMIN} beside the built-in one, and that role is editable.
 *
 * <p>The message names the remedy, because a refusal whose way forward is not stated is
 * read as a bug: duplicate it and customise the copy. That is also the product's only
 * creation door, so the sentence does double duty.
 */
public class BuiltInRoleNotEditableException extends AppException {

    public BuiltInRoleNotEditableException() {
        super("Built-in roles cannot be edited or deleted — duplicate it to customise",
                HttpStatus.CONFLICT);
    }
}
