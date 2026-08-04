package com.hamstrack.admin.dto;

/**
 * Result of creating a user (or regenerating a setup link): the account plus a
 * one-time {@code setupLink} the admin copies and sends to the person. The link
 * points at the SPA {@code /reset-password?token=} page (no email is sent).
 */
public record CreatedUserResponse(
        AdminUserResponse user,
        String setupLink
) {}
