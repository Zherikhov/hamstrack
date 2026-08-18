package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.workspace.dto.RoleUsageResponse;
import org.springframework.http.HttpStatus;

/**
 * <strong>409 {@code ROLE_IN_USE}</strong> — the role still has holders and the request
 * named no {@code reassignToRoleId}.
 *
 * <p>The full {@link RoleUsageResponse} rides along as a problem-detail extension, so the
 * client can render the remap dialog <em>from the refusal</em> rather than issuing a second
 * request to find out what it just collided with. Naming the counts discloses nothing: every
 * one of them is scoped to the workspace the caller is already administering, and they could
 * read the same numbers from {@code GET /roles/{id}/usage} with the permission they must
 * hold to have reached this at all.
 *
 * <p>409 rather than 422 for {@code LastWorkspaceOwnerException}'s reason: the request is
 * perfectly processable and the same request succeeds unchanged once the role is empty —
 * what refuses is a state invariant, not the input.
 */
public class RoleInUseException extends AppException {

    /** @see #getErrorType() */
    public static final String ROLE_IN_USE = "ROLE_IN_USE";

    private final transient RoleUsageResponse usage;

    public RoleInUseException(RoleUsageResponse usage) {
        super("This role is still assigned — pass reassignToRoleId to move its holders to "
              + "another role, or change them individually first", HttpStatus.CONFLICT);
        this.usage = usage;
    }

    /**
     * The {@code errorType} extension, following the convention
     * {@code StrandedProjectsException} and {@code HqlParseException} already use: a client
     * branches on a stable code, never on a sentence.
     */
    public String getErrorType() {
        return ROLE_IN_USE;
    }

    public RoleUsageResponse getUsage() {
        return usage;
    }
}
