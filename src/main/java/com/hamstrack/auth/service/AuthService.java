package com.hamstrack.auth.service;

import com.hamstrack.auth.dto.*;
import com.hamstrack.auth.entity.*;
import com.hamstrack.auth.exception.*;
import com.hamstrack.auth.repository.*;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.JwtProperties;
import com.hamstrack.common.mail.MailService;
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

    @Transactional
    public void register(RegisterRequest req) {
        if (!appProperties.registration().publicSignupEnabled()
                && userRepository.count() > 0) {
            throw new RegistrationDisabledException();
        }
        var email = req.email().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        var user = new User();
        user.setEmail(email);
        user.setDisplayName(req.displayName());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setStatus(UserStatus.PENDING);
        userRepository.save(user);

        sendVerificationEmail(user);
    }

    @Transactional
    public void verifyEmail(String rawToken) {
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
    }

    @Transactional
    public AuthResponse login(LoginRequest req, HttpServletResponse response) {
        var user = userRepository.findByEmail(req.email().toLowerCase())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (user.getStatus() == UserStatus.PENDING) {
            throw new EmailNotVerifiedException();
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw new InvalidCredentialsException();
        }
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
        userRepository.findByEmail(email.toLowerCase())
                .filter(user -> user.getStatus() == UserStatus.PENDING)
                .ifPresent(this::sendVerificationEmail);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        // Always return success to prevent email enumeration
        userRepository.findByEmail(req.email().toLowerCase()).ifPresent(user -> {
            var raw = generateRawToken();
            var reset = new PasswordReset();
            reset.setUser(user);
            reset.setTokenHash(sha256(raw));
            reset.setExpiresAt(Instant.now().plusSeconds(3600)); // 1 hour
            passwordResetRepository.save(reset);
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
