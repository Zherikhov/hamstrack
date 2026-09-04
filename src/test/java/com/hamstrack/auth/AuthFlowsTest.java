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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(user.getStatus()).as("a fresh registration must be PENDING until verified").isEqualTo(UserStatus.PENDING);
        assertThat(emailVerificationRepository.findAll().stream()
                .anyMatch(v -> v.getUser().getId().equals(user.getId())))
                .withFailMessage("registration must create an email-verification token row")
                .isTrue();
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

        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus())
                .as("a valid verification token must activate the user")
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void verifyEmailWithExpiredTokenIsRejected() throws Exception {
        var user = pendingUser(email(), "password123");
        var raw = issueVerification(user, Instant.now().minusSeconds(60), null); // already expired

        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getStatus())
                .as("an expired token must not activate the user")
                .isEqualTo(UserStatus.PENDING);
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

    /**
     * HD-183 — <strong>a link is single-use; the SET of live links was not.</strong>
     *
     * <p>The shipped ceilings allow five simultaneously valid one-hour links per address and the
     * "Send another link" button produces them, so an unused sibling copied out of the inbox used
     * to survive the very reset performed to defeat it. Asserted on the refusal the API actually
     * returns — not on a row or a constraint, because a reset marks {@code used_at} rather than
     * deleting and a flush-deferred violation would be raised outside any assertion here anyway.
     */
    @Test
    void completingAResetRetiresEveryOtherOutstandingLinkForThatUser() throws Exception {
        var email = email();
        var user = activeUser(email, "old-password-1");
        var first = issueReset(user, Instant.now().plusSeconds(3600), null);
        var second = issueReset(user, Instant.now().plusSeconds(3600), null);
        // a bystander's live link, to prove the sweep is scoped to one user
        var bystander = activeUser(email(), "old-password-1");
        var bystanderLink = issueReset(bystander, Instant.now().plusSeconds(3600), null);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + second + "\",\"newPassword\":\"second-new-pw-2\"}"))
                .andExpect(status().isOk());

        // the sibling nobody spent must now be refused
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + first + "\",\"newPassword\":\"attacker-pw-3\"}"))
                .andExpect(status().isBadRequest());

        // and it really did not take the account: the password set through the spent link stands
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"attacker-pw-3\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"second-new-pw-2\"}"))
                .andExpect(status().isOk());

        // another account's outstanding link is untouched
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + bystanderLink + "\",\"newPassword\":\"bystander-pw-4\"}"))
                .andExpect(status().isOk());
    }

    /**
     * HD-183 — the sweep is blind to which door minted the row, <strong>on purpose</strong>, so an
     * administrator's 7-day setup link ({@code AdminUserService.generateSetupLink}, same table,
     * longer TTL) dies with the rest. The admin re-issues one from the console; the reasoning is
     * on {@code PasswordResetRepository.invalidateOtherOutstanding}. If that ever becomes a
     * deliberate exemption, this test is the place that has to say so first.
     */
    @Test
    void completingAResetAlsoRetiresALongLivedAdminSetupLink() throws Exception {
        var user = activeUser(email(), "old-password-1");
        var setupLink = issueReset(user, Instant.now().plus(Duration.ofDays(7)), null);
        var selfService = issueReset(user, Instant.now().plusSeconds(3600), null);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + selfService + "\",\"newPassword\":\"self-service-pw-5\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + setupLink + "\",\"newPassword\":\"setup-pw-6\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * HD-183 — <strong>the counterpart prohibition</strong>: the sweep is earned by proving
     * possession of a token, so it lives only on the redeem path. Doing it at MINT time reads like
     * the same fix and is a worse bug — {@code /api/auth/forgot-password} is unauthenticated, so
     * an attacker naming a victim's address could void the link the victim is holding, which is a
     * free denial-of-recovery. This test fails the moment somebody "improves" it into the mint
     * path.
     */
    @Test
    void requestingAnotherLinkDoesNotKillTheOneAlreadySent() throws Exception {
        var email = email();
        var user = activeUser(email, "old-password-1");
        var alreadySent = issueReset(user, Instant.now().plusSeconds(3600), null);

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + alreadySent + "\",\"newPassword\":\"still-valid-pw-7\"}"))
                .andExpect(status().isOk());
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

        assertThat(knownBody)
                .as("forgot-password must return an identical, enumeration-safe message for known and unknown emails")
                .isEqualTo(unknownBody);
        // and a reset token was actually issued for the known user
        var user = userRepository.findByEmail(known).orElseThrow();
        assertThat(passwordResetRepository.findAll().stream().anyMatch(r -> r.getUser().getId().equals(user.getId())))
                .withFailMessage("forgot-password must issue a reset token for a known email")
                .isTrue();
    }

    // ============================================================ refresh / logout

    @Test
    void validRefreshTokenRotatesAndReturnsNewAccessToken() throws Exception {
        var email = email();
        var user = activeUser(email, "password123");
        var loginRes = login(email, "password123");
        var cookie = refreshCookie(loginRes);
        assertThat(cookie).as("login must set the refresh_token cookie").isNotNull();
        var originalHash = TokenUtils.sha256(cookie.getValue());

        mockMvc.perform(post("/api/auth/refresh").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());

        // rotation: the old refresh token row is deleted, a new one exists for the user
        assertThat(refreshTokenRepository.findByTokenHash(originalHash))
                .as("refresh must rotate — the presented token must be deleted")
                .isEmpty();
        assertThat(refreshTokenRepository.findAll().stream().anyMatch(t -> t.getUser().getId().equals(user.getId())))
                .withFailMessage("refresh must issue a fresh refresh token for the user")
                .isTrue();
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
        assertThat(refreshTokenRepository.findByTokenHash(TokenUtils.sha256(cookie.getValue())))
                .as("logout must delete the refresh token")
                .isEmpty();
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
