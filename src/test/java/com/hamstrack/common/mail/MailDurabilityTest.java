package com.hamstrack.common.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import jakarta.mail.internet.MimeMessage;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;

/**
 * Failure-injection regression for HD-78 (P2 cluster 1). Account-critical mail
 * (verification + password reset) retries then writes a {@code failed_email}
 * dead-letter row on final failure; best-effort mail (invite) is dropped with NO
 * row. The dead-letter row must carry NO raw token.
 *
 * <p>The {@link JavaMailSender} is mocked to always fail its {@code send(...)}.
 * The send methods are {@code @Async("mailExecutor")}, so the invoked bean runs
 * off the calling thread; assertions await the async completion (row appears, or
 * the sender is invoked the expected number of times).
 *
 * <p>Retry backoff is set to 0ms so the three critical attempts don't stall the
 * test. Tests {@link MailService} through its real bean (async proxy intact) with
 * the mocked sender — the exact web send path is awkward to reach deterministically
 * (enumeration-safe resend, async), so this drives the send path directly.
 */
@SpringBootTest(properties = {
        "app.mail.critical.max-attempts=3",
        "app.mail.critical.retry-backoff-ms=0",
        "app.base-url=https://app.example.com",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class MailDurabilityTest {

    @Autowired MailService mailService;
    @Autowired FailedEmailRepository failedEmailRepository;

    @MockitoBean JavaMailSender mailSender;

    @BeforeEach
    void resetState() {
        failedEmailRepository.deleteAll();
        Mockito.reset(mailSender);
        // A real MimeMessage so MimeMessageHelper (verification HTML path) can build it;
        // the failure is injected at send(...), not at creation.
        Mockito.when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new org.springframework.mail.javamail.JavaMailSenderImpl().createMimeMessage());
        Mockito.doThrow(new MailSendException("injected SMTP failure"))
                .when(mailSender).send(any(MimeMessage.class));
        Mockito.doThrow(new MailSendException("injected SMTP failure"))
                .when(mailSender).send(any(SimpleMailMessage.class));
    }

    // ============================================================ tests

    @Test
    void criticalVerificationFailureDeadLettersWithNoToken() {
        var token = "super-secret-verification-token-abc123";
        mailService.sendVerificationEmail("user@example.com", token);

        // After retries exhaust, exactly one dead-letter row is written.
        await().atMost(Duration.ofSeconds(10)).until(() -> failedEmailRepository.count() == 1);

        var row = failedEmailRepository.findAll().getFirst();
        assert row.getEmailType().equals("VERIFICATION")
                : "email_type must record the critical type, got " + row.getEmailType();
        assert row.getRecipient().equals("user@example.com") : "recipient recorded";
        assert row.getAttempts() == 3 : "attempts must equal max-attempts (3), got " + row.getAttempts();

        // No token must leak into ANY column of the dead-letter row (subject/error/etc.).
        assertNoToken(row, token);
    }

    @Test
    void criticalPasswordResetFailureDeadLettersWithNoToken() {
        var token = "super-secret-reset-token-xyz789";
        mailService.sendPasswordResetEmail("reset@example.com", token);

        await().atMost(Duration.ofSeconds(10)).until(() -> failedEmailRepository.count() == 1);

        var row = failedEmailRepository.findAll().getFirst();
        assert row.getEmailType().equals("PASSWORD_RESET")
                : "email_type must be PASSWORD_RESET, got " + row.getEmailType();
        assert row.getAttempts() == 3 : "attempts must equal max-attempts (3)";
        assertNoToken(row, token);
    }

    @Test
    void bestEffortInviteFailureWritesNoRow() {
        mailService.sendWorkspaceInviteEmail("invitee@example.com", "Acme", "invite-token-123");

        // Invite is best-effort: a single attempt (no retry), so wait for that one
        // send(...) invocation, then assert NOTHING was dead-lettered.
        verify(mailSender, timeout(10_000)).send(any(SimpleMailMessage.class));
        // Give any (erroneous) async dead-letter a moment to appear; it must not.
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> failedEmailRepository.count() == 0);
    }

    // ============================================================ helpers

    private void assertNoToken(FailedEmail row, String token) {
        assert row.getSubject() == null || !row.getSubject().contains(token)
                : "subject must not contain the raw token";
        assert row.getLastError() == null || !row.getLastError().contains(token)
                : "last_error must not contain the raw token";
        assert row.getRecipient() == null || !row.getRecipient().contains(token)
                : "recipient must not contain the raw token";
        assert row.getEmailType() == null || !row.getEmailType().contains(token)
                : "email_type must not contain the raw token";
    }
}
