package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>422</strong> — an assignment request named its role in neither of the two
 * accepted ways, or in both.
 *
 * <p>Every membership door ({@code POST /invites}, {@code PATCH /members/{userId}},
 * {@code POST /projects/{p}/members}, {@code PATCH /projects/{p}/members/{userId}}) accepts
 * a legacy {@code role} <strong>key</strong> and a new {@code roleId}, and requires exactly
 * one. Both fields exist transitionally because S5 has already shipped and the invite screen
 * is live: a slice must be independently deployable, and breaking the running SPA's invite
 * flow between S4 and S6 would be a production regression for the sake of one DTO field.
 *
 * <p><strong>Why "both" is refused rather than resolved by precedence.</strong> The two
 * fields do not mean the same thing — the legacy key resolves built-ins only and still
 * applies {@code ProjectService}'s {@code VIEWER → Contributor} translation, while
 * {@code roleId} addresses any assignable role verbatim. A silent winner would therefore
 * store a role the caller did not ask for, in the one part of the product where that is a
 * privilege change.
 *
 * <p>422 rather than 400 for {@link UnknownRoleException}'s reason: the request is
 * well-formed JSON and every field validates on its own; it is the <em>combination</em>
 * that cannot be honoured.
 */
public class RoleSelectionException extends AppException {

    private RoleSelectionException(String detail) {
        super(detail, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    public static RoleSelectionException required() {
        return new RoleSelectionException("Name the role with either roleId or role");
    }

    public static RoleSelectionException ambiguous() {
        return new RoleSelectionException(
                "Send either roleId or role, not both — they do not always mean the same role");
    }
}
