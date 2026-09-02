package com.hamstrack.common.mail;

import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.MailAsyncProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailOutcome;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Builds the account's outbound mail and hands it to the pool.
 *
 * <h2>Every public {@code send*} method returns before any SMTP happens</h2>
 * They construct a {@link MailTask} and give it to {@link MailDispatcher}; the socket work happens
 * on a {@code mail-} thread, or never happens and is written down. This is a stronger statement
 * than the {@code @Async} these methods used to carry, and the difference is the whole of HD-208:
 * with {@code CallerRunsPolicy} on a bounded pool, a full queue turned the "async" dispatch into a
 * synchronous send <em>on the caller's thread</em>, retries and all — up to ~34 s of a Tomcat
 * worker, reachable from an unauthenticated endpoint. There is no such branch any more.
 *
 * <p>{@code @Async} could not have stayed even if the policy had: the identity of a queued message
 * has to survive into the rejection handler and into {@code shutdownNow()}'s returned list for
 * either loss to be recordable, and the {@code @Async} interceptor submits a {@code FutureTask}
 * wrapping an opaque lambda. See {@link MailTask}.
 *
 * <p>What has <em>not</em> changed is where a send must be published from. Asynchrony is not what
 * keeps SMTP out of a transaction or a cross-tenant lock — ordering is, and every mailer here is
 * registered on {@code common.tx.AfterCommit} by its caller, sealed by
 * {@code MailerAfterCommitCoverageTest}. Do not restore an argument that rests on the dispatch
 * being asynchronous, even now that it reliably is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    // Email clients don't load web fonts — system stack approximates the app's look
    private static final String FONT = "-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;
    private final ProductMetrics metrics;
    private final MailAsyncProperties mailAsyncProperties;
    private final FailedEmailWriter failedEmailWriter;
    private final MailDispatcher dispatcher;

    // Account-critical mail (verification + reset): a lost one leaves a user unable
    // to complete signup/recovery, so these retry then dead-letter. INVITE is
    // best-effort (log-only) by design.
    //
    // Package-private rather than private: UndeliverableMail forks on the same question for mail
    // that never reaches a send at all, and it has to be the SAME question. A new EmailType must
    // land on one side or the other exactly once — two copies of this method would let it be
    // critical when a send fails and best-effort when a send never happens.
    static boolean isCritical(EmailType type) {
        return type == EmailType.VERIFICATION || type == EmailType.PASSWORD_RESET;
    }

    public void sendVerificationEmail(String to, String token) {
        var subject = "Confirm your Hamstrack email";
        dispatcher.dispatch(new MailTask(EmailType.VERIFICATION, to, subject, () -> {
            // Links to the SPA page (not the API): mail scanners prefetch GET links,
            // which would consume the one-time token before the user clicks
            var link = appProperties.baseUrl() + "/verify-email?token=" + token;
            var text = "Confirm your email address to activate your Hamstrack account:\n\n" + link
                    + "\n\nThis link expires in 24 hours."
                    + " If you didn't create a Hamstrack account, you can safely ignore this email.";
            sendHtml(EmailType.VERIFICATION, to, subject, text, verificationHtml(link));
        }));
    }

    public void sendPasswordResetEmail(String to, String token) {
        var subject = "Reset your Hamstrack password";
        dispatcher.dispatch(new MailTask(EmailType.PASSWORD_RESET, to, subject, () -> {
            var link = appProperties.baseUrl() + "/reset-password?token=" + token;
            send(EmailType.PASSWORD_RESET, to, subject,
                    "Click the link to reset your password:\n\n" + link
                    + "\n\nThis link expires in 1 hour.");
        }));
    }

    /**
     * <strong>What keeps an SMTP round trip out of a cross-tenant lock is the ordering, not the
     * hand-off</strong> (HD-181, correcting what this javadoc once claimed). The only caller is
     * {@code WorkspaceService.inviteMember}, which takes
     * {@code pg_advisory_xact_lock(hashtext(recipient_key))} in {@code RecipientMailThrottle} and
     * holds it until commit; a recipient address is something two tenants legitimately share, so a
     * send performed inside that section is a wait one tenant can impose on another simply by
     * inviting the same person. The old {@code @Async} was said to prevent that and did not — the
     * pool ran {@code CallerRunsPolicy}, so once the queue filled (under precisely the load that
     * makes the lock matter) the dispatch became an inline send on the caller's thread, inside the
     * lock, retries and all.
     *
     * <p>The caller registers this on {@code AfterCommit}, so it is dispatched after the commit
     * that releases the advisory lock. HD-208 has since removed the inline branch as well, so the
     * dispatch really is a hand-off now — but the reason the lock is safe is still the ordering, and
     * an argument resting on the hand-off would go stale the next time the pool's policy is
     * revisited.
     */
    public void sendWorkspaceInviteEmail(String to, String workspaceName, String token) {
        var subject = "You've been invited to " + workspaceName + " on Hamstrack";
        dispatcher.dispatch(new MailTask(EmailType.INVITE, to, subject, () -> {
            var link = appProperties.baseUrl() + "/accept-invite?token=" + token;
            send(EmailType.INVITE, to, subject,
                    "You've been invited to join \"" + workspaceName + "\".\n\nAccept the invite:\n\n"
                    + link + "\n\nThis link expires in 7 days.");
        }));
    }

    private String verificationHtml(String link) {
        return """
                <!doctype html>
                <html>
                <body style="margin:0;padding:0;background-color:#F7F6F3;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#F7F6F3;">
                    <tr>
                      <td align="center" style="padding:40px 16px;">
                        <table role="presentation" cellpadding="0" cellspacing="0" style="width:100%%;max-width:520px;">
                          <tr>
                            <td style="padding:0 8px 16px;font-family:%1$s;font-size:20px;font-weight:800;letter-spacing:-0.5px;color:#1C1B19;">Hamstrack</td>
                          </tr>
                          <tr>
                            <td style="background-color:#FFFFFF;border:1px solid #E4E1DA;border-radius:8px;padding:32px;">
                              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0">
                                <tr>
                                  <td style="font-family:%1$s;font-size:22px;font-weight:700;color:#1C1B19;padding-bottom:12px;">Confirm your email</td>
                                </tr>
                                <tr>
                                  <td style="font-family:%1$s;font-size:15px;line-height:22px;color:#5C5950;padding-bottom:28px;">One click left — confirm your email address and you'll land straight in your workspace, signed in and ready to go.</td>
                                </tr>
                                <tr>
                                  <td style="padding-bottom:28px;">
                                    <a href="%2$s" style="display:inline-block;background-color:#0F6E63;color:#FFFFFF;font-family:%1$s;font-size:15px;font-weight:600;text-decoration:none;padding:11px 28px;border-radius:4px;">Confirm email</a>
                                  </td>
                                </tr>
                                <tr>
                                  <td style="font-family:%1$s;font-size:13px;line-height:20px;color:#8B8680;padding-top:28px;border-top:1px solid #EFEDE7;">If the button doesn't work, paste this link into your browser:<br>
                                    <a href="%2$s" style="color:#0F6E63;word-break:break-all;">%2$s</a>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:16px 8px 0;font-family:%1$s;font-size:12px;line-height:18px;color:#8B8680;">This link expires in 24 hours. If you didn't create a Hamstrack account, you can safely ignore this email.</td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(FONT, link);
    }

    // A single SMTP send attempt. Throws on failure — the retry wrapper decides
    // whether to retry / dead-letter (critical) or drop (best-effort).
    private void send(EmailType type, String to, String subject, String text) {
        sendWithDurability(type, to, subject, () -> {
            var message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            message.setFrom(appProperties.mailFrom());
            mailSender.send(message);
        });
    }

    private void sendHtml(EmailType type, String to, String subject, String plainText, String html) {
        sendWithDurability(type, to, subject, () -> {
            var message = mailSender.createMimeMessage();
            try {
                // multipart/alternative: HTML for normal clients, plain text as fallback
                var helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setFrom(appProperties.mailFrom());
                helper.setText(plainText, html);
            } catch (MessagingException e) {
                throw new MailPreparationException("Failed to build email", e);
            }
            mailSender.send(message);
        });
    }

    @FunctionalInterface
    private interface SendAttempt {
        void run();
    }

    /**
     * Runs a send with criticality-aware durability. CRITICAL mail (verification +
     * reset) retries up to {@code app.mail.critical.max-attempts} with a fixed
     * backoff; on final failure it writes a {@code failed_email} dead-letter row (no
     * raw token) + ERROR log so the {@code EmailFailures} alert fires. Best-effort
     * mail (invite) records the FAILURE metric and logs — no retry, no dead-letter,
     * unchanged behaviour.
     *
     * <p><strong>Runs on a {@code mail-} thread and nowhere else</strong> (HD-208). It used to be
     * able to run on the calling thread — {@code CallerRunsPolicy} made the dispatch synchronous
     * under exactly the load that filled the queue, so this loop and its retries could cost a
     * request tens of seconds. The pool now refuses instead, and a refused message is recorded by
     * {@link UndeliverableMail} without ever reaching this method.
     *
     * <p>The exception is observed and swallowed at the end rather than returned, and "nothing
     * awaits the async result" is only half of why: there is no caller left to tell either, because
     * every send is dispatched from an {@code AfterCommit} effect, after the transaction it
     * announces has already committed. Which is what makes {@link FailedEmailWriter} the record of
     * a critical failure rather than the return value.
     *
     * <p>A row written from here always carries {@code attempts >= 1}. A row written by
     * {@link UndeliverableMail} carries {@code attempts = 0} and a {@code last_error} that says so
     * — the table holds "we tried and failed" and "we never tried" side by side, and a reader (or a
     * future re-drive job) has to be able to tell them apart.
     */
    private void sendWithDurability(EmailType type, String to, String subject, SendAttempt attempt) {
        boolean critical = isCritical(type);
        int maxAttempts = critical ? Math.max(1, mailAsyncProperties.critical().maxAttempts()) : 1;
        long backoffMs = mailAsyncProperties.critical().retryBackoffMs();

        RuntimeException last = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                attempt.run();
                metrics.emailSent(type, EmailOutcome.SUCCESS);
                return;
            } catch (RuntimeException e) {
                last = e;
                metrics.emailSent(type, EmailOutcome.FAILURE);
                if (i < maxAttempts) {
                    sleepQuietly(backoffMs);
                }
            }
        }

        // All attempts exhausted.
        if (critical) {
            // DOMAIN ONLY, never the whole address (HD-208 review). The full recipient is durable
            // on the failed_email row this line is announcing, which is the argument
            // UndeliverableMail makes for itself: a shipped, retained log must not carry the local
            // part, and a log line is not where anybody re-drives a message from. These three lines
            // predate that rule and were the last ones in the mail package still breaking it —
            // reached, now, on every deploy that catches mail in flight, where they used to be
            // reached only by a genuine SMTP failure.
            log.error("Critical {} email to a {} address failed after {} attempt(s) — dead-lettering",
                    type, MailAddresses.domainOf(to), maxAttempts, last);
            deadLetter(type, to, subject, maxAttempts, last);
        } else {
            log.warn("Best-effort {} email to a {} address failed",
                    type, MailAddresses.domainOf(to), last);
        }
    }

    private void deadLetter(EmailType type, String to, String subject, int attempts, RuntimeException error) {
        try {
            var row = new FailedEmail();
            row.setEmailType(type.name());
            row.setRecipient(truncate(to, 320));
            row.setSubject(truncate(subject, 255));
            row.setLastError(error == null ? null : truncate(String.valueOf(error.getMessage()), 1000));
            row.setAttempts(attempts);
            // Its OWN transaction, never the caller's — see FailedEmailWriter. This path always
            // runs on a pool thread now (HD-208), where nothing is bound, but the writer is shared
            // with UndeliverableMail, which is reached ON THE COMMITTING THREAD inside an
            // AfterCommit effect — and there a PROPAGATION_REQUIRED save joins an already-committed
            // transaction and is discarded at cleanup without throwing: no row, and not even the
            // catch below to say so.
            failedEmailWriter.write(row);
        } catch (RuntimeException persistError) {
            // A dead-letter write failure must not propagate onto the async executor
            // (nothing awaits it); the ERROR log above + metric already captured it.
            log.error("Failed to persist dead-letter row for {} email to a {} address",
                    type, MailAddresses.domainOf(to), persistError);
        }
    }

    /** Package-private: {@link UndeliverableMail} writes rows into the same columns. */
    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
