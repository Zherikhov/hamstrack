package com.hamstrack.common.ratelimit;

import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;

import java.time.Duration;

/**
 * One kind of outbound mail's recipient-keyed ceilings (HD-190 §6.6).
 *
 * <p><strong>One policy bean per {@link EmailType}, and that is the extension point.</strong> The
 * mechanism ({@link RecipientMailThrottle}) is deliberately ignorant of what kind of mail it is
 * throttling: it knows how to lock an address, count two windows, refuse, and record. Everything
 * that differs between invitation mail, reset mail and verification mail is here — the numbers, the
 * metric tags and the sentences. HD-202 is therefore two more beans of this type plus two
 * {@code require} calls, not a second limiter that half-overlaps this one.
 *
 * <p><strong>Separate budgets, one table.</strong> Counts are always taken per {@code type}, so a
 * reset flood cannot consume an invitation allowance or the other way round.
 *
 * <p>The numbers are read from {@code @ConfigurationProperties} at bean creation, which is the same
 * lifetime the properties themselves have.
 *
 * @param type                 which mail this governs; also the {@code email_type} written to
 *                             {@code mail_send_events}
 * @param cooldown             the same sender may not send to the same address twice inside this,
 *                             <em>across every workspace in the instance</em>
 * @param maxPerRecipientPerDay the global daily ceiling on one recipient key, spent on two things
 *                             at once: how many of this kind of mail <em>you</em> may send to that
 *                             key in a day, and how many <em>other</em> senders may reach it —
 *                             other senders count once each however much they sent, so no single
 *                             account can consume a stranger's whole allowance. See
 *                             {@code RecipientMailThrottle#refuseIfRecipientDailyCapReached}
 * @param cooldownKind         metric tag for a cooldown refusal — harassment-shaped
 * @param recipientDailyKind   metric tag for a daily-cap refusal — coordinated-harassment-shaped,
 *                             and the sharpest signal of the three because it needs several accounts
 * @param wording              what each refusal says
 */
public record MailThrottlePolicy(EmailType type,
                                 Duration cooldown,
                                 int maxPerRecipientPerDay,
                                 RateLimitKind cooldownKind,
                                 RateLimitKind recipientDailyKind,
                                 MailThrottleWording wording) {
}
