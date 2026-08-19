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
 *
 * <h2>The message names the caller's OWN fields</h2>
 *
 * <p>S7 reused the two no-argument factories for two request bodies that have neither of
 * those fields (docs review, round 2): {@code PATCH /workspaces/{ws}} answered a
 * {@code defaultProjectRoleId} + {@code clearDefaultProjectRole} collision with <em>"Send
 * either roleId or role"</em>, and {@code PATCH …/projects/{p}/default-role} told a caller
 * who had sent neither {@code roleId} nor {@code inherit} to send a {@code role} field that
 * does not exist on that body. Both statuses were right and both sentences sent the reader
 * looking for a field they cannot send.
 *
 * <p>So the general factories take the field names, and the {@code because} clause too: what
 * makes two fields different is different per body — {@code roleId}/{@code role} are two ways
 * of naming one role, {@code roleId}/{@code clearDefaultProjectRole} are set-versus-remove,
 * {@code roleId}/{@code inherit} are own-versus-inherited. One shared sentence cannot be true
 * of all three, and a fixed sentence that is wrong for two of them is how copy outlives the
 * code that produced it. The {@code roleId}/{@code role} pair keeps its no-argument factories
 * because four membership doors share it verbatim and {@code docs/api-*.md} quotes both
 * strings.
 *
 * <p>Nothing branches on the wording — the documented contract is the status — so a call site
 * may reword its own message without a client change.
 */
public class RoleSelectionException extends AppException {

    private RoleSelectionException(String detail) {
        super(detail, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** The membership doors' pair: a legacy {@code role} key beside the new {@code roleId}. */
    public static RoleSelectionException required() {
        return new RoleSelectionException("Name the role with either roleId or role");
    }

    /** @see #required() */
    public static RoleSelectionException ambiguous() {
        return new RoleSelectionException(
                "Send either roleId or role, not both — they do not always mean the same role");
    }

    /**
     * Neither field was sent, on a body whose two fields are its own.
     *
     * @param first  a field name of <strong>this</strong> request body
     * @param second likewise — never a field the caller cannot send
     */
    public static RoleSelectionException required(String first, String second) {
        return new RoleSelectionException("Send exactly one of " + first + " or " + second);
    }

    /**
     * Both fields were sent, on a body whose two fields are its own.
     *
     * @param because completes "…not both — ", and is the call site's own: it says what the
     *                two fields actually mean, which is the whole reason they are not resolved
     *                by precedence
     */
    public static RoleSelectionException ambiguous(String first, String second, String because) {
        return new RoleSelectionException(
                "Send either " + first + " or " + second + ", not both — " + because);
    }
}
