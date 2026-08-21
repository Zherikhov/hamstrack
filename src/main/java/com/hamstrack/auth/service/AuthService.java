package com.hamstrack.auth.service;

import com.hamstrack.auth.dto.*;
import com.hamstrack.auth.entity.*;
import com.hamstrack.auth.exception.*;
import com.hamstrack.auth.repository.*;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.JwtProperties;
import com.hamstrack.common.mail.MailService;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.LoginOutcome;
import com.hamstrack.common.observability.ProductMetrics.LoginReason;
import com.hamstrack.common.observability.ProductMetrics.PasswordResetPhase;
import com.hamstrack.common.ratelimit.RateLimitService;
import com.hamstrack.common.security.JwtService;
import com.hamstrack.common.util.TokenUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    static final String REFRESH_COOKIE = "refresh_token";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final AppProperties appProperties;
    private final MailService mailService;
    private final RateLimitService rateLimitService;
    private final ProductMetrics metrics;

    @Transactional
    public void register(RegisterRequest req) {
        // When public signup is off (DC default), self-registration is fully
        // closed — no first-user bootstrap. Accounts are created by the system
        // admin (Admin console → Users); the initial admin comes from SEED_ADMIN_*.
        if (!appProperties.registration().publicSignupEnabled()) {
            throw new RegistrationDisabledException();
        }
        if (appProperties.legal().termsAcceptanceRequired() && !req.hasAcceptedTerms()) {
            throw new TermsNotAcceptedException();
        }
        // Locale.ROOT, never the JVM default. This fold IS the account identity: users.email
        // carries a byte-exact UNIQUE and every lookup is an exact match, so a fold that varies
        // with the container locale varies which address a person owns - a Turkish JVM stores
        // Ivan@x.com as a dotless-i address that exists nowhere, and the same typed address
        // stops resolving to the same row the day the locale changes. The rule is the category,
        // not this line: any fold whose result is stored, mailed, or used as a lookup key names
        // its locale (HD-120).
        var email = req.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        var user = new User();
        user.setEmail(email);
        user.setDisplayName(req.displayName());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setStatus(UserStatus.PENDING);
        if (req.hasAcceptedTerms()) {
            // recorded whenever the box was ticked, even when not required
            user.setTermsAcceptedAt(Instant.now());
        }
        userRepository.save(user);
        metrics.userRegistered();

        sendVerificationEmail(user);
    }

    @Transactional
    public AuthResponse verifyEmail(String rawToken, HttpServletResponse response) {
        var hash = sha256(rawToken);
        var verification = emailVerificationRepository.findByTokenHash(hash)
                .orElseThrow(InvalidTokenException::new);
        if (verification.isExpired() || verification.isUsed()) {
            throw new InvalidTokenException();
        }
        verification.setVerifiedAt(Instant.now());
        emailVerificationRepository.save(verification);

        var user = verification.getUser();
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
        metrics.emailVerified();

        // The one-time token proves email ownership — same trust level as a
        // password reset link — so the user is logged in directly
        return issueTokens(user, response);
    }

    @Transactional
    public AuthResponse login(LoginRequest req, HttpServletResponse response) {
        var email = req.email().toLowerCase(Locale.ROOT);
        // Exponential backoff after consecutive failures — throws 429 while
        // blocked. Applied to unknown emails too, so the limiter itself can't
        // be used to probe which addresses are registered.
        rateLimitService.checkLoginAllowed(email);

        var user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            rateLimitService.recordLoginFailure(email);
            metrics.recordLogin(LoginOutcome.FAILURE, LoginReason.BAD_CREDENTIALS);
            throw new InvalidCredentialsException();
        }
        if (user.getStatus() == UserStatus.PENDING) {
            metrics.recordLogin(LoginOutcome.FAILURE, LoginReason.NOT_VERIFIED);
            throw new EmailNotVerifiedException();
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            metrics.recordLogin(LoginOutcome.FAILURE, LoginReason.DISABLED);
            throw new InvalidCredentialsException();
        }
        rateLimitService.resetLoginFailures(email);
        metrics.recordLogin(LoginOutcome.SUCCESS, LoginReason.OK);
        return issueTokens(user, response);
    }

    @Transactional
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        var rawToken = extractRefreshCookie(request);
        var hash = sha256(rawToken);
        var stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(InvalidTokenException::new);
        if (!stored.isValid()) {
            throw new InvalidTokenException();
        }
        if (stored.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new InvalidTokenException();
        }
        // Rotate: delete old token, issue new pair
        refreshTokenRepository.delete(stored);
        return issueTokens(stored.getUser(), response);
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            var rawToken = extractRefreshCookie(request);
            var hash = sha256(rawToken);
            refreshTokenRepository.findByTokenHash(hash)
                    .ifPresent(refreshTokenRepository::delete);
        } catch (InvalidTokenException ignored) {
            // no cookie — already logged out
        }
        clearRefreshCookie(response);
    }

    @Transactional
    public void resendVerification(String email) {
        // Silently no-op for unknown or already-verified emails — no enumeration
        userRepository.findByEmail(email.toLowerCase(Locale.ROOT))
                .filter(user -> user.getStatus() == UserStatus.PENDING)
                .ifPresent(this::sendVerificationEmail);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        // Always return success to prevent email enumeration
        userRepository.findByEmail(req.email().toLowerCase(Locale.ROOT)).ifPresent(user -> {
            var raw = generateRawToken();
            var reset = new PasswordReset();
            reset.setUser(user);
            reset.setTokenHash(sha256(raw));
            reset.setExpiresAt(Instant.now().plusSeconds(3600)); // 1 hour
            passwordResetRepository.save(reset);
            metrics.passwordReset(PasswordResetPhase.REQUESTED);
            mailService.sendPasswordResetEmail(user.getEmail(), raw);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        var hash = sha256(req.token());
        var reset = passwordResetRepository.findByTokenHash(hash)
                .orElseThrow(InvalidTokenException::new);
        if (reset.isExpired() || reset.isUsed()) {
            throw new InvalidTokenException();
        }
        reset.setUsedAt(Instant.now());
        passwordResetRepository.save(reset);

        var user = reset.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);

        // Invalidate all existing refresh tokens after password change
        refreshTokenRepository.deleteAllByUser(user);
        metrics.passwordReset(PasswordResetPhase.COMPLETED);
    }

    // --- helpers ---

    private AuthResponse issueTokens(User user, HttpServletResponse response) {
        var accessToken = jwtService.generateAccessToken(user);
        var rawRefresh = jwtService.generateRawRefreshToken();

        var token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(sha256(rawRefresh));
        token.setExpiresAt(Instant.now().plus(jwtProperties.refreshTokenExpiration()));
        refreshTokenRepository.save(token);

        setRefreshCookie(response, rawRefresh);

        var expiresIn = jwtProperties.accessTokenExpiration().toSeconds();
        return new AuthResponse(accessToken, expiresIn, user.getId(), user.getEmail(), user.getDisplayName());
    }

    private void sendVerificationEmail(User user) {
        var raw = generateRawToken();
        var verification = new EmailVerification();
        verification.setUser(user);
        verification.setTokenHash(sha256(raw));
        verification.setExpiresAt(Instant.now().plusSeconds(86400)); // 24 hours
        emailVerificationRepository.save(verification);
        mailService.sendVerificationEmail(user.getEmail(), raw);
    }

    private String extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) throw new InvalidTokenException();
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_COOKIE.equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElseThrow(InvalidTokenException::new);
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                refreshCookie(rawToken, jwtProperties.refreshTokenExpiration()).toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                // Secure follows the deployment scheme: Cloud/HTTPS gets it, a self-hosted
                // DC instance on plain HTTP still gets a working refresh flow
                .secure(appProperties.baseUrl().startsWith("https"))
                .path("/api/auth")
                .maxAge(maxAge)
                .sameSite("Strict")
                .build();
    }

    private String generateRawToken() {
        return TokenUtils.generateRawToken();
    }

    private String sha256(String input) {
        return TokenUtils.sha256(input);
    }
}
