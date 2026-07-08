package com.hamstrack.common.mail;

import com.hamstrack.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    @Async
    public void sendVerificationEmail(String to, String token) {
        var link = appProperties.baseUrl() + "/api/auth/verify-email?token=" + token;
        send(to, "Verify your Hamstrack email",
                "Click the link to verify your email address:\n\n" + link
                + "\n\nThis link expires in 24 hours.");
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

    private void send(String to, String subject, String text) {
        var message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        message.setFrom("noreply@hamstrack.com");
        mailSender.send(message);
    }
}
