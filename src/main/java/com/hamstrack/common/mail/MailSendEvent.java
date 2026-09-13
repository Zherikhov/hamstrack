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
     * The recipient as submitted, lower-cased by the caller, <strong>cut to this width at the site
     * that writes it</strong> ({@code MailAddresses.fitStoredRecipient}). <strong>Nothing counts this
     * column</strong> — it is what an operator needs when working an alert, so it stays as faithful to
     * what was typed as the width allows.
     *
     * <p>320 rather than the invite path's 255 to match {@code failed_email.recipient}: this table
     * is shared by every outbound-mail flow, and a shared column should not be sized by whichever
     * request DTO happens to be narrowest today.
     *
     * <p><strong>What that extra room is NOT is a bound, and this javadoc used to say it was</strong>
     * (HD-306 fix loop). It read: "each writing flow is bounded by its own DTO — {@code
     * InviteMemberRequest}, {@code ForgotPasswordRequest}, {@code ResendVerificationRequest} are all
     * {@code @Size(max = 255)} — so the extra room is margin, not a licence to store unbounded input."
     * MEASURED FALSE on 2026-09-13: a {@code @Size} bounds the RAW text and every door folds the
     * address with {@code toLowerCase(Locale.ROOT)} before handing it over, and that fold APPENDS
     * (U+0130 → two code points). An address {@code ForgotPasswordRequest} accepts with zero violations
     * at 255 characters arrives here at <strong>347</strong>. The two anonymous doors have no bound
     * above them at all — deliberately, because their contract is one uniform response — so the DTO was
     * never what protected this column. What does is the truncation at the write site, counted on
     * {@code hamstrack.mail.stored_address_truncated{column="recipient_email"}}, and the seal is
     * {@code MailAddressesThrottleKeyTest#theWidestAddressEveryWritingDtoStoresFitsTheForensicColumn}
     * plus its sibling that proves the write really goes through the fit. The general lesson, since it
     * cost two rounds on two columns of one table: <em>a column that holds a value the server DERIVED
     * is bounded by the derivation site or by nothing.</em>
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
     * not transfer to this one.</strong> Punycode LENGTHENS what it is given, and it lengthens
     * generously: a domain of a couple of hundred non-ASCII characters converts to several times that
     * many {@code xn--} characters. So "every writing flow is {@code @Size(max = 255)}" says nothing
     * at all about how long a key derived from a 255-character address can be. Part of what bounds it
     * lives inside {@code @Email}, which each of those flows also carries: Hibernate Validator refuses
     * a local part longer than 64 characters, and refuses a domain whose <em>ASCII</em> form exceeds
     * 255 — it runs {@code IDN.toASCII} itself, before measuring. Hence 64 + {@code "@"} + 255 = 320,
     * which is this width.
     *
     * <p><strong>That arithmetic is a property of an ASCII local part only, and this javadoc used to
     * state it as a property of the column</strong> (HD-306). It said punycode was "the one step in
     * {@code throttleKey} that can LENGTHEN what it is given (every other step strips)", and that a
     * "future fold that ever APPENDS to a key rather than only stripping from it needs a wider column
     * first" — a hypothetical describing a defect that was already shipped. {@code toLowerCase} runs on
     * the LOCAL PART in {@code throttleKey}'s first line and it appends: U+0130 becomes two code
     * points, so Hibernate Validator's 64 buys 128 characters of key and the arithmetic is
     * 128 + 1 + 255 = <strong>384</strong> for every writing DTO that does not force an ASCII local
     * part — i.e. every one of them except {@code InviteMemberRequest}. MEASURED 2026-09-13: an
     * 85-character address {@code RegisterRequest} accepts with zero violations produced a 384-character
     * key. The old claim was certified against {@code InviteMemberRequest} alone, which is a uniqueness
     * claim proved on one member.
     *
     * <p><strong>So the general bound on this column is not a DTO's, it is the truncation.</strong>
     * {@code MailAddresses.throttleKey} cuts a key to this width at the site that produces it and
     * counts the cut ({@code hamstrack.mail.stored_address_truncated{column="recipient_key"}}). The
     * door bound the three storing doors carry is <em>not</em> a second belt under it and an earlier
     * round of HD-306 claimed it was ("makes 384 hard to reach rather than impossible"): the register
     * door's own worst key is 384 produced by an address that folds to 149, i.e. 106 characters inside
     * the 255 it refuses at, so the door bound is simply not evidence about this column.
     * Widening this column (and {@code failed_email.recipient} with it)
     * would buy back a bound resting on third-party invariants nothing here states, which is what
     * failed the first time. {@code MailAddressesThrottleKeyTest} certifies the worst case of
     * <em>every</em> writing DTO against the width read off this field.
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
