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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
    private final FailedEmailRepository failedEmailRepository;

    // Account-critical mail (verification + reset): a lost one leaves a user unable
    // to complete signup/recovery, so these retry then dead-letter. INVITE is
    // best-effort (log-only) by design.
    private static boolean isCritical(EmailType type) {
        return type == EmailType.VERIFICATION || type == EmailType.PASSWORD_RESET;
    }

    @Async("mailExecutor")
    public void sendVerificationEmail(String to, String token) {
        // Links to the SPA page (not the API): mail scanners prefetch GET links,
        // which would consume the one-time token before the user clicks
        var link = appProperties.baseUrl() + "/verify-email?token=" + token;
        var text = "Confirm your email address to activate your Hamstrack account:\n\n" + link
                + "\n\nThis link expires in 24 hours."
                + " If you didn't create a Hamstrack account, you can safely ignore this email.";
        sendHtml(EmailType.VERIFICATION, to, "Confirm your Hamstrack email", text, verificationHtml(link));
    }

    @Async("mailExecutor")
    public void sendPasswordResetEmail(String to, String token) {
        var link = appProperties.baseUrl() + "/reset-password?token=" + token;
        send(EmailType.PASSWORD_RESET, to, "Reset your Hamstrack password",
                "Click the link to reset your password:\n\n" + link
                + "\n\nThis link expires in 1 hour.");
    }

    /**
     * <strong>This {@code @Async} is load-bearing for something other than latency: do not remove
     * it.</strong> Its only caller is {@code WorkspaceService.inviteMember}, which is still holding
     * {@code pg_advisory_xact_lock(hashtext(recipient_key))} — taken by
     * {@code RecipientMailThrottle}, held until that transaction commits. A recipient address is
     * something two tenants legitimately share, so making this synchronous would move an SMTP round
     * trip inside a lock one tenant can make another tenant queue behind, simply by inviting the
     * same person. Nothing in the throttle would have to change for that to happen, which is why the
     * warning is here rather than only there.
     */
    @Async("mailExecutor")
    public void sendWorkspaceInviteEmail(String to, String workspaceName, String token) {
        var link = appProperties.baseUrl() + "/accept-invite?token=" + token;
        send(EmailType.INVITE, to, "You've been invited to " + workspaceName + " on Hamstrack",
                "You've been invited to join \"" + workspaceName + "\".\n\nAccept the invite:\n\n"
                + link + "\n\nThis link expires in 7 days.");
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
     * unchanged behaviour. Runs on the {@code mailExecutor}; the exception is
     * observed and swallowed at the end (nothing awaits the async result).
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
            log.error("Critical {} email to {} failed after {} attempt(s) — dead-lettering",
                    type, to, maxAttempts, last);
            deadLetter(type, to, subject, maxAttempts, last);
        } else {
            log.warn("Best-effort {} email to {} failed", type, to, last);
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
            failedEmailRepository.save(row);
        } catch (RuntimeException persistError) {
            // A dead-letter write failure must not propagate onto the async executor
            // (nothing awaits it); the ERROR log above + metric already captured it.
            log.error("Failed to persist dead-letter row for {} email to {}", type, to, persistError);
        }
    }

    private static String truncate(String s, int max) {
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
