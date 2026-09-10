package com.hamstrack.auth;

import com.hamstrack.auth.dto.RegisterRequest;
import com.hamstrack.auth.dto.ResetPasswordRequest;
import com.hamstrack.auth.exception.PasswordTooLongException;
import com.hamstrack.auth.exception.PublishedPasswordException;
import com.hamstrack.auth.exception.RegistrationDisabledException;
import com.hamstrack.auth.exception.TermsNotAcceptedException;
import com.hamstrack.auth.repository.EmailVerificationRepository;
import com.hamstrack.auth.repository.PasswordResetRepository;
import com.hamstrack.auth.repository.RefreshTokenRepository;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.auth.service.AuthService;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.JwtProperties;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.SignupRefusal;
import com.hamstrack.common.ratelimit.RateLimitService;
import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import com.hamstrack.common.security.JwtService;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.repository.WorkspaceStorageUsageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <strong>Every refusal {@code AuthService.register} makes before it reads the address counts
 * why</strong> (HD-261; widened to the whole category in the HD-298 fix loop). The closed door is
 * the DC default and answered 403 for months with no witness at all. The status codes are unchanged
 * — {@code RegistrationLockdownTest} holds the 403 at the real door and reads the same counter
 * there; this class holds the other three branches with the collaborators mocked, plus the claim
 * that the reset door, which shares the two password refusals, moves no signup counter.
 */
class SignupRefusalCounterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProductMetrics metrics = new ProductMetrics(registry, mock(UserRepository.class),
            mock(WorkspaceRepository.class), mock(ProjectRepository.class), mock(IssueRepository.class),
            mock(WorkspaceStorageUsageRepository.class));
    private final AppProperties app = mock(AppProperties.class);
    private final AuthService service = new AuthService(mock(UserRepository.class),
            mock(RefreshTokenRepository.class), mock(EmailVerificationRepository.class),
            mock(PasswordResetRepository.class), mock(PasswordEncoder.class), mock(JwtService.class),
            mock(JwtProperties.class), app, mock(MailService.class), mock(RateLimitService.class),
            mock(RecipientMailThrottle.class), metrics);

    @Test
    void aClosedDoorCountsSignupClosed() {
        when(app.registration()).thenReturn(new AppProperties.Registration(false));

        assertThatThrownBy(() -> service.register(request(true)))
                .isInstanceOf(RegistrationDisabledException.class);

        assertThat(refused(SignupRefusal.SIGNUP_CLOSED)).isEqualTo(1);
        assertThat(refused(SignupRefusal.TERMS_NOT_ACCEPTED)).isZero();
    }

    @Test
    void missingTermsCountsTermsNotAccepted() {
        when(app.registration()).thenReturn(new AppProperties.Registration(true));
        var legal = mock(AppProperties.Legal.class);
        when(legal.termsAcceptanceRequired()).thenReturn(true);
        when(app.legal()).thenReturn(legal);

        assertThatThrownBy(() -> service.register(request(false)))
                .isInstanceOf(TermsNotAcceptedException.class);

        assertThat(refused(SignupRefusal.TERMS_NOT_ACCEPTED)).isEqualTo(1);
        assertThat(refused(SignupRefusal.SIGNUP_CLOSED)).isZero();
    }

    @Test
    void aPublishedPasswordCountsPublishedPassword() {
        doorOpen();

        assertThatThrownBy(() -> service.register(request("SEED_ADMIN_PASSWORD")))
                .isInstanceOf(PublishedPasswordException.class);

        assertThat(refused(SignupRefusal.PUBLISHED_PASSWORD)).isEqualTo(1);
        assertThat(refused(SignupRefusal.UNENCODABLE_PASSWORD)).isZero();
    }

    @Test
    void aPasswordBcryptCannotHashCountsUnencodablePassword() {
        doorOpen();
        // 37 Cyrillic characters: passes @Size(max = 72) and is 74 UTF-8 bytes (HD-171).
        var cyrillic = "а".repeat(37);

        assertThatThrownBy(() -> service.register(request(cyrillic)))
                .isInstanceOf(PasswordTooLongException.class);

        assertThat(refused(SignupRefusal.UNENCODABLE_PASSWORD)).isEqualTo(1);
        assertThat(refused(SignupRefusal.PUBLISHED_PASSWORD)).isZero();
    }

    /** The two password helpers are shared with the reset door; a reset is not a signup. */
    @Test
    void theResetDoorSharesTheRefusalAndMovesNoSignupCounter() {
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest("token", "SEED_ADMIN_PASSWORD")))
                .isInstanceOf(PublishedPasswordException.class);
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest("token", "а".repeat(37))))
                .isInstanceOf(PasswordTooLongException.class);

        for (SignupRefusal reason : SignupRefusal.values()) {
            assertThat(refused(reason)).as("signup_refused{reason=%s} after two reset refusals", reason.tag()).isZero();
        }
    }

    private void doorOpen() {
        when(app.registration()).thenReturn(new AppProperties.Registration(true));
        var legal = mock(AppProperties.Legal.class);
        when(legal.termsAcceptanceRequired()).thenReturn(false);
        when(app.legal()).thenReturn(legal);
    }

    private double refused(SignupRefusal reason) {
        var counter = registry.find("hamstrack.auth.signup_refused").tag("reason", reason.tag()).counter();
        return counter == null ? 0 : counter.count();
    }

    private static RegisterRequest request(boolean termsAccepted) {
        return new RegisterRequest("person@example.com", "password123", "Person", termsAccepted);
    }

    private static RegisterRequest request(String password) {
        return new RegisterRequest("person@example.com", password, "Person", true);
    }
}
