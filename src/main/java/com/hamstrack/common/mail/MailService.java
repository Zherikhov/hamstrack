package com.hamstrack.common.mail;

import com.hamstrack.common.config.AppProperties;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailService {

    // Email clients don't load web fonts — system stack approximates the app's look
    private static final String FONT = "-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    @Async
    public void sendVerificationEmail(String to, String token) {
        // Links to the SPA page (not the API): mail scanners prefetch GET links,
        // which would consume the one-time token before the user clicks
        var link = appProperties.baseUrl() + "/verify-email?token=" + token;
        var text = "Confirm your email address to activate your Hamstrack account:\n\n" + link
                + "\n\nThis link expires in 24 hours."
                + " If you didn't create a Hamstrack account, you can safely ignore this email.";
        sendHtml(to, "Confirm your Hamstrack email", text, verificationHtml(link));
    }

    @Async
    public void sendPasswordResetEmail(String to, String token) {
        var link = appProperties.baseUrl() + "/reset-password?token=" + token;
        send(to, "Reset your Hamstrack password",
                "Click the link to reset your password:\n\n" + link
                + "\n\nThis link expires in 1 hour.");
    }

    @Async
    public void sendWorkspaceInviteEmail(String to, String workspaceName, String token) {
        var link = appProperties.baseUrl() + "/accept-invite?token=" + token;
        send(to, "You've been invited to " + workspaceName + " on Hamstrack",
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

    private void send(String to, String subject, String text) {
        var message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        message.setFrom(appProperties.mailFrom());
        mailSender.send(message);
    }

    private void sendHtml(String to, String subject, String plainText, String html) {
        try {
            var message = mailSender.createMimeMessage();
            // multipart/alternative: HTML for normal clients, plain text as fallback
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setFrom(appProperties.mailFrom());
            helper.setText(plainText, html);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailPreparationException("Failed to build email", e);
        }
    }
}
