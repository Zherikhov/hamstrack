package com.hamstrack.workspace.entity;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A standing offer of workspace access, redeemable once, by exact address match.
 *
 * <p><strong>At most one unaccepted row per {@code (workspace_id, lower(email))}</strong>, enforced
 * by the PARTIAL unique index {@code workspace_invites_pending_email_uk} (V22, HD-133). Partial
 * matters twice over: accepted rows keep no slot, so invited → joined → removed → invited again is
 * a normal sequence; and <strong>expired rows DO keep theirs</strong>, because a partial index
 * predicate must be {@code IMMUTABLE} and {@code now()} is not. A lapsed invitation therefore
 * blocks a fresh one until somebody withdraws it — the same rule this schema applies to archived
 * labels, components, versions and sprints.
 *
 * <p><strong>The constraint is NOT mirrored as {@code @Table(uniqueConstraints = ...)}, and must
 * not be.</strong> JPA cannot express a partial index; a full unique constraint declared here
 * would describe a rule the schema does not have, and {@code ddl-auto=validate} would not catch
 * the difference.
 *
 * <p><strong>The index is the invariant; the pre-check in {@code WorkspaceService.inviteMember} is
 * only the sentence.</strong> Two concurrent inserts both pass that check and the database
 * arbitrates. Any new writer of this table — a bulk-invite endpoint, SSO auto-provisioning, an
 * admin import — therefore cannot create a duplicate, but will report the refusal as a 500 unless
 * it runs the same pre-check ABOVE the mail throttle; see {@code WorkspaceInviteRepository}'s type
 * javadoc for why the order and not merely the check is what is owed.
 */
@Entity
@Table(name = "workspace_invites")
@Getter
@Setter
public class WorkspaceInvite extends CreatedOnlyEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 255)
    private String email;

    /** The workspace role the invitee gets on acceptance (HD-123 §8.2). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by", nullable = false)
    private User invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }
}
