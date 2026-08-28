package com.hamstrack.common.mail;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One outbound message this instance decided to send (HD-190). The persisted state behind
 * the recipient-keyed mail ceilings — see {@code V21__mail_send_events.sql} for why it is a
 * table at all, and {@code docs/adr/0015-recipient-keyed-mail-throttle-persisted.md} for the
 * decision.
 *
 * <p><strong>Append-only.</strong> The row is written once and never updated, so every column
 * is {@code updatable = false} and the entity extends {@link CreatedOnlyEntity} ({@code id} +
 * {@code createdAt}). Nothing reads it back as an entity: the only reads are aggregate counts
 * on {@link MailSendEventRepository}, and the retention sweep deletes by {@code createdAt}.
 *
 * <p><strong>No associations, deliberately.</strong> {@code senderUserId} and {@code workspaceId}
 * are plain {@link UUID} fields rather than {@code @ManyToOne}s. An association would reintroduce
 * the foreign-key semantics the schema refuses on purpose — this row has to outlive the invite,
 * the workspace and the account it describes — and it would invite a {@code JOIN FETCH} that turns
 * an aggregate count into a row read of somebody else's tenant.
 *
 * <p><strong>{@code workspaceId} is written and never queried.</strong> It is a forensic
 * breadcrumb for an operator answering "who did this?" after an alert, because the metrics cannot
 * carry ids. It is not a tenancy scope and this table is not workspace-scoped.
 */
@Entity
@Table(name = "mail_send_events")
@Getter
@Setter
public class MailSendEvent extends CreatedOnlyEntity {

    /** {@code ProductMetrics.EmailType.name()} — validated app-side; VARCHAR, never a PG enum. */
    @Column(name = "email_type", nullable = false, updatable = false, length = 40)
    private String emailType;

    /**
     * The recipient exactly as submitted, lower-cased at the boundary. <strong>Nothing counts this
     * column</strong> — it is what a refusal echoes back to the caller and what an operator needs
     * when working an alert, so it stays faithful to what was typed.
     *
     * <p>320 rather than the invite path's 255 to match {@code failed_email.recipient}: this table
     * is shared by every outbound-mail flow, and a shared column should not be sized by whichever
     * request DTO happens to be narrowest today. Each writing flow is bounded by its own DTO —
     * {@code InviteMemberRequest}, and HD-202's {@code ForgotPasswordRequest} /
     * {@code ResendVerificationRequest}, are all {@code @Size(max = 255)} — so the extra room is
     * margin, not a licence to store unbounded input.
     */
    @Column(name = "recipient_email", nullable = false, updatable = false, length = 320)
    private String recipientEmail;

    /**
     * <strong>The value every ceiling counts</strong>: {@code MailAddresses.throttleKey(...)}, one
     * key per destination <em>inbox</em> rather than one per spelling of an address.
     *
     * <p>Comparing raw addresses made the whole feature decorative: {@code victim+1@},
     * {@code victim+2@} and {@code v.i.c.t.i.m@googlemail.com} are distinct strings that all reach
     * one human, so both persisted ceilings read zero on every request and the ticket's attack was
     * merely re-spelled. Over-folding is the fail-safe direction <em>for a ceiling</em> — an extra
     * match raises a count and refuses sooner — which is the reverse of the invite
     * <em>redemption</em> path (HD-120), where an extra match would let the wrong person accept and
     * addresses are therefore compared exactly. Full argument on {@code MailAddresses#throttleKey}.
     *
     * <p>Derived, never submitted, and never used as a recipient: mail goes to
     * {@link #recipientEmail}.
     *
     * <p><strong>Same 320 as above, different reason — the DTO bound that justifies that one does
     * not transfer to this one.</strong> Punycode is the one step in {@code throttleKey} that can
     * LENGTHEN what it is given (every other step strips), and it lengthens generously: a domain of
     * a couple of hundred non-ASCII characters converts to several times that many {@code xn--}
     * characters. So "every writing flow is {@code @Size(max = 255)}" says nothing at all about how
     * long a key derived from a 255-character address can be. What does bound it lives inside
     * {@code @Email}, which each of those flows also carries: Hibernate Validator refuses a local
     * part longer than 64 characters, and refuses a domain whose <em>ASCII</em> form exceeds 255 —
     * it runs {@code IDN.toASCII} itself, before measuring. The longest key that can reach this
     * column is therefore 64 + {@code "@"} + 255 = 320. It fits exactly, with no margin, and it
     * fits on a third-party invariant that nothing in this codebase states — which is why the
     * bound is worth an assertion rather than this paragraph. Also why a future fold that ever
     * APPENDS to a key rather than only stripping from it needs a wider column first.
     *
     * <p>({@code InviteMemberRequest}'s ASCII-only local part does not change this arithmetic: the
     * lengthening is in the DOMAIN, which stays deliberately internationalisable.)
     */
    @Column(name = "recipient_key", nullable = false, updatable = false, length = 320)
    private String recipientKey;

    /** NULL for anonymous senders — HD-202's forgot-password / resend-verification flows. */
    @Column(name = "sender_user_id", updatable = false)
    private UUID senderUserId;

    /** Forensic only. Written, never queried; see the class javadoc. */
    @Column(name = "workspace_id", updatable = false)
    private UUID workspaceId;
}
