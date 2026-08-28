package com.hamstrack.workspace.dto;

import com.hamstrack.workspace.entity.WorkspaceInvite;
import com.hamstrack.workspace.service.RoleView;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of <strong>a workspace's own outstanding invitations</strong> — the administrator's
 * view, behind {@code workspace.member.manage} (HD-158 §8.1).
 *
 * <p><strong>This is deliberately not a reuse of {@link PendingInviteResponse}</strong>, which is
 * the <em>invitee's</em> view of <em>their</em> invitations across every workspace: it carries a
 * {@code workspaceName}, no address and no inviter id, because its audience is somebody who is not
 * yet a member of the workspace that owns the row. Different audience, different disclosure.
 * Sharing one record would put a workspace's submitted addresses one field-addition away from the
 * onboarding screen.
 *
 * @param roleId       the invited role's <strong>identity</strong>; {@code null} in the degraded
 *                     form below.
 * @param role         the invited role's <em>key</em> ({@code "MEMBER"}). <strong>Both this and
 *                     {@code roleId} are {@code null} together</strong> when the row's
 *                     {@code role_id} fails the scope/ownership assertion — the same degrade
 *                     {@code listMembers} and {@code listPendingInvites} apply, for the same
 *                     reason: one corrupt row must not empty an admin screen, and emitting the id
 *                     of a role whose key was withheld hands the name back by proxy (the client
 *                     would look it up in the catalog and print it).
 * @param invitedByName the inviter's display name, rendered even if that account has since left
 *                     the workspace or been deactivated. Historical attribution stays, as it does
 *                     on every other surface in this product.
 * @param status       <strong>server-computed, though a client could derive it from
 *                     {@code expiresAt}</strong>. Expiry is decided by the server clock
 *                     ({@link WorkspaceInvite#isExpired()}), and a browser with a skewed clock must
 *                     not disagree with the endpoint that will accept or refuse the acceptance.
 *                     Note that it is a <em>label</em>, not a filter: an {@code EXPIRED} row is
 *                     listed and is withdrawable, because nothing else in this product ever
 *                     removes one.
 */
public record WorkspaceInviteResponse(
        UUID id,
        String email,
        UUID roleId,
        String role,
        UUID invitedById,
        String invitedByName,
        Instant createdAt,
        Instant expiresAt,
        Status status
) {

    /** Whether this invitation can still be accepted. There is no {@code ACCEPTED}: the list
     *  never carries an accepted row (it is history, and the roster is where that person is). */
    public enum Status { PENDING, EXPIRED }

    /**
     * @param role the resolved role, or {@code null} for the degraded form. Passed in rather than
     *     read off {@code invite.getRole()} because the caller is the one that ran — or refused —
     *     the scope/ownership assertion, exactly as {@code WorkspaceMemberResponse.of} does.
     */
    public static WorkspaceInviteResponse of(WorkspaceInvite invite, RoleView role) {
        var inviter = invite.getInvitedBy();
        return new WorkspaceInviteResponse(
                invite.getId(),
                invite.getEmail(),
                role == null ? null : role.id(),
                role == null ? null : role.key(),
                inviter.getId(),
                inviter.getDisplayName(),
                invite.getCreatedAt(),
                invite.getExpiresAt(),
                invite.isExpired() ? Status.EXPIRED : Status.PENDING);
    }
}
