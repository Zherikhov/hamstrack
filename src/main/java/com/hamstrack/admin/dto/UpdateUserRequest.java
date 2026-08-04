package com.hamstrack.admin.dto;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.UserStatus;

/**
 * Partial update of an account from the Admin console. Any null field is left
 * unchanged. {@code status} only accepts ACTIVE/DISABLED (PENDING is managed by
 * the verification flow, not set manually here).
 */
public record UpdateUserRequest(
        SystemRole systemRole,
        UserStatus status
) {}
