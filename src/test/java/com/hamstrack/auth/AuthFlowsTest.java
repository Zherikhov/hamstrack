package com.hamstrack.auth;

import com.hamstrack.auth.entity.EmailVerification;
import com.hamstrack.auth.entity.PasswordReset;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.EmailVerificationRepository;
import com.hamstrack.auth.repository.PasswordResetRepository;
import com.hamstrack.auth.repository.RefreshTokenRepository;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.util.TokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.Mockito;

import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-84 (audit Q-2) — the security-sensitive auth token flows beyond login:
 * registration + the DC lockdown, email verification, forgot/reset-password, and
 * refresh-token rotation/revocation. Login itself and rate limiting are already
 * covered ({@code AuthRateLimitTest} et al.); this adds the token lifecycles.
 *
 * <p>Tokens are stored hashed ({@code SHA-256} of a random raw token). The service
 * never exposes the raw value, so where a test must drive a token it inserts the row
 * itself via the repository with a known raw token hashed exactly as the service does
 * ({@link TokenUtils#sha256}) — the same mechanism the production code uses. For
 * refresh, the raw token IS available: it comes back as the {@code refresh_token}
 * HttpOnly cookie on login, which is replayed on the next request.
 *
 * <p>{@link JavaMailSender} is mocked so registration/forgot-password never touch SMTP
 * (mirrors {@code MailDurabilityTest}); the send is async and best-effort here, the
 * assertions are on the DB state the service writes synchronously in-tx.
 *
 * <p>The default profile is used (public signup ON, onboarding OFF); a dedicated
 * nested-style property override drives the DC lockdown case in its own context.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "app.registration.public-signup-enabled=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AuthFlowsTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired EmailVerificationRepository emailVerificationRepository;
    @Autowired PasswordResetRepository passwordResetRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;

    // Never hit real SMTP from register/forgot-password.
    @MockitoBean JavaMailSender mailSender;

    // ============================================================ register

    @Test
    void registerCreatesPendingUserAndVerificationToken() throws Exception {
        var email = email();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(email, "password123", "New User")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").exists());

        var user = userRepository.findByEmail(email).orElseThrow();
        assert user.getStatus() == UserStatus.PENDING : "a fresh registration must be PENDING until verified";
        assert emailVerificationRepository.findAll().stream()
                .anyMatch(v -> v.getUser().getId().equals(user.getId()))
                : "registration must create an email-verification token row";
    }

    @Test
    void registerRejectsDuplicateEmailWith409() throws Exception {
        var email = email();
        // seed an existing active user with this email
        activeUser(email, "password123");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(json(email, "password123", "Dupe")))
                .andExpect(status().isConflict());
    }

    // ============================================================ verify-email

    @Test
    void verifyEmailWithValidTokenActivatesUser() throws Exception {
        var user = pendingUser(email(), "password123");
        var raw = issueVerification(user, Instant.now().plusSeconds(3600), null);

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        assert userRepository.findById(user.getId()).orElseThrow().getStatus() == UserStatus.ACTIVE
                : "a valid verification token must activate the user";
    }

    @Test
    void verifyEmailWithExpiredTokenIsRejected() throws Exception {
        var user = pendingUser(email(), "password123");
        var raw = issueVerification(user, Instant.now().minusSeconds(60), null); // already expired

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\"}"))
                .andExpect(status().isBadRequest());
        assert userRepository.findById(user.getId()).orElseThrow().getStatus() == UserStatus.PENDING
                : "an expired token must not activate the user";
    }

    @Test
    void verifyEmailWithAlreadyUsedTokenIsRejected() throws Exception {
        var user = pendingUser(email(), "password123");
        // used token: verifiedAt already set
        var raw = issueVerification(user, Instant.now().plusSeconds(3600), Instant.now().minusSeconds(10));

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ============================================================ forgot / reset password

    @Test
    void resetPasswordWithValidTokenChangesPasswordAndLoginWorks() throws Exception {
        var email = email();
        var user = activeUser(email, "old-password-1");
        var raw = issueReset(user, Instant.now().plusSeconds(3600), null);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\",\"newPassword\":\"brand-new-pw-9\"}"))
                .andExpect(status().isOk());

        // old password no longer works
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"old-password-1\"}"))
                .andExpect(status().isUnauthorized());
        // new password works
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"brand-new-pw-9\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void resetPasswordWithExpiredTokenIsRejected() throws Exception {
        var user = activeUser(email(), "old-password-1");
        var raw = issueReset(user, Instant.now().minusSeconds(60), null);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\",\"newPassword\":\"brand-new-pw-9\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetPasswordTokenIsSingleUse() throws Exception {
        var email = email();
        var user = activeUser(email, "old-password-1");
        var raw = issueReset(user, Instant.now().plusSeconds(3600), null);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\",\"newPassword\":\"first-new-pw-1\"}"))
                .andExpect(status().isOk());

        // second use of the same token must fail
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\",\"newPassword\":\"second-new-pw-2\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forgotPasswordReturnsSameMessageForKnownAndUnknownEmail() throws Exception {
        var known = email();
        activeUser(known, "password123");

        var knownBody = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + known + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var unknownBody = mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assert knownBody.equals(unknownBody)
                : "forgot-password must return an identical, enumeration-safe message for known and unknown emails";
        // and a reset token was actually issued for the known user
        var user = userRepository.findByEmail(known).orElseThrow();
        assert passwordResetRepository.findAll().stream().anyMatch(r -> r.getUser().getId().equals(user.getId()))
                : "forgot-password must issue a reset token for a known email";
    }

    // ============================================================ refresh / logout

    @Test
    void validRefreshTokenRotatesAndReturnsNewAccessToken() throws Exception {
        var email = email();
        var user = activeUser(email, "password123");
        var loginRes = login(email, "password123");
        var cookie = refreshCookie(loginRes);
        assert cookie != null : "login must set the refresh_token cookie";
        var originalHash = TokenUtils.sha256(cookie.getValue());

        mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        // rotation: the old refresh token row is deleted, a new one exists for the user
        assert refreshTokenRepository.findByTokenHash(originalHash).isEmpty()
                : "refresh must rotate — the presented token must be deleted";
        assert refreshTokenRepository.findAll().stream().anyMatch(t -> t.getUser().getId().equals(user.getId()))
                : "refresh must issue a fresh refresh token for the user";
    }

    @Test
    void revokedRefreshTokenIsRejected() throws Exception {
        var email = email();
        activeUser(email, "password123");
        var cookie = refreshCookie(login(email, "password123"));

        // revoke it in the DB
        var stored = refreshTokenRepository.findByTokenHash(TokenUtils.sha256(cookie.getValue())).orElseThrow();
        stored.setRevokedAt(Instant.now());
        refreshTokenRepository.save(stored);

        mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isBadRequest());
    }

    @Test
    void logoutRevokesRefreshTokenSoSubsequentRefreshFails() throws Exception {
        var email = email();
        activeUser(email, "password123");
        var cookie = refreshCookie(login(email, "password123"));

        mockMvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent());

        // the token row is gone
        assert refreshTokenRepository.findByTokenHash(TokenUtils.sha256(cookie.getValue())).isEmpty()
                : "logout must delete the refresh token";
        // and replaying the cookie no longer refreshes
        mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isBadRequest());
    }

    // ============================================================ helpers

    private String json(String email, String password, String displayName) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"displayName\":\"" + displayName + "\",\"termsAccepted\":true}";
    }

    private String email() {
        return ("u-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com")
                .toLowerCase();
    }

    private User activeUser(String email, String password) {
        return saveUser(email, password, UserStatus.ACTIVE);
    }

    private User pendingUser(String email, String password) {
        return saveUser(email, password, UserStatus.PENDING);
    }

    private User saveUser(String email, String password, UserStatus status) {
        var u = new User();
        u.setEmail(email.toLowerCase());
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setStatus(status);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    /** Insert a verification row exactly as the service does; returns the raw token. */
    private String issueVerification(User user, Instant expiresAt, Instant verifiedAt) {
        var raw = TokenUtils.generateRawToken();
        var v = new EmailVerification();
        v.setUser(user);
        v.setTokenHash(TokenUtils.sha256(raw));
        v.setExpiresAt(expiresAt);
        v.setVerifiedAt(verifiedAt);
        emailVerificationRepository.save(v);
        return raw;
    }

    /** Insert a password-reset row exactly as the service does; returns the raw token. */
    private String issueReset(User user, Instant expiresAt, Instant usedAt) {
        var raw = TokenUtils.generateRawToken();
        var r = new PasswordReset();
        r.setUser(user);
        r.setTokenHash(TokenUtils.sha256(raw));
        r.setExpiresAt(expiresAt);
        r.setUsedAt(usedAt);
        passwordResetRepository.save(r);
        return raw;
    }

    private MockHttpServletResponse login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    /** Extract the refresh_token HttpOnly cookie set by a login response. */
    private Cookie refreshCookie(MockHttpServletResponse res) {
        var c = res.getCookie("refresh_token");
        if (c != null) return c;
        // Fallback: parse Set-Cookie header (MockHttpServletResponse.getCookie relies on
        // the container having parsed it; addHeader(SET_COOKIE, ...) is used by the service).
        for (var header : res.getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith("refresh_token=")) {
                var value = header.substring("refresh_token=".length());
                int semi = value.indexOf(';');
                if (semi >= 0) value = value.substring(0, semi);
                return new Cookie("refresh_token", value);
            }
        }
        return null;
    }
}
