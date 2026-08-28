package com.hamstrack.common.ratelimit;

/**
 * What each recipient-keyed refusal <em>says</em>, per kind of mail (HD-190 §8.1).
 *
 * <p>Wording is per {@code EmailType} rather than shared, because the mechanism is shared and the
 * sentence is not: "you already invited this person" and "a reset link is already on its way" are
 * the same ceiling and different remedies. Making the wording part of the policy is what keeps
 * HD-202 down to two policy beans and two call sites instead of a second half-overlapping limiter.
 *
 * <p><strong>The standing rule these implementations are held to: a refusal may only prescribe an
 * action its reader can perform.</strong> This project has shipped an unperformable refusal three
 * times. Before adding a sentence here, name the reader and check they can do the thing — and if
 * the sentence makes a claim about a row, the caller must have checked that row (see the
 * {@code addendum} parameter below, which exists for exactly one such claim).
 */
public interface MailThrottleWording {

    /**
     * The per-(sender, recipient) cooldown refusal.
     *
     * @param recipient the address the caller just typed — their own past action, never anybody
     *                  else's, which is why naming it here discloses nothing. The workspace is
     *                  deliberately <strong>not</strong> named: the earlier send may have come from
     *                  one the caller can no longer see
     * @param wait      a human phrase from {@link RetryWait#describe}
     * @param addendum  an extra sentence, already checked against reality by the caller, or
     *                  {@code null}. Evaluated only when the cooldown actually fires
     */
    String cooldown(String recipient, String wait, String addendum);

    /**
     * The global per-recipient daily refusal.
     *
     * <p>Terse on purpose, and the terseness is the security property. "Wait" is performable, and
     * every richer remedy ("ask them to accept the one they already have") converts the single bit
     * of cross-tenant disclosure this ceiling accepts into a description of another tenant's
     * activity. Where the prescription rule and the disclosure rule pull against each other,
     * disclosure wins — and here they barely pull, because waiting <em>is</em> the remedy.
     *
     * @param wait a human phrase from {@link RetryWait#describe}
     */
    String recipientDaily(String wait);
}
