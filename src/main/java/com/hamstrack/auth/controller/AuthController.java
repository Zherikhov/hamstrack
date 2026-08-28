package com.hamstrack.auth.controller;

import com.hamstrack.auth.dto.*;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.service.AuthService;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.seed.DemoDataService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Authentication endpoints: registration, email verification, login, token
 * refresh, logout and password recovery. All endpoints are public except
 * {@code GET /me}. Successful authentication returns a short-lived JWT access
 * token in the body and sets the {@code refresh_token} HttpOnly cookie
 * (scoped to {@code /api/auth}); every authentication response also triggers
 * one-time demo data seeding for the user.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final DemoDataService demoDataService;
    private final AppProperties appProperties;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@Valid @RequestBody RegisterRequest req) {
        authService.register(req);
        return Map.of("message", "Registration successful. Please check your email to verify your account.");
    }

    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest req,
                                    HttpServletResponse response) {
        return withDemoSeed(authService.verifyEmail(req.token(), response));
    }

    // Mail scanners follow GET links and would burn the one-time token, so the
    // email links to the SPA page, which POSTs the token. This redirect keeps
    // links from already-sent emails working.
    //
    // THE TOKEN IS BOUNDED HERE FOR THE SAME REASON IT IS BOUNDED ON VerifyEmailRequest, and
    // it is the ticket's own argument applied to a token instead of a column: A RULE ON ONE OF
    // TWO DOORS IS NOT A RULE. HD-171 put @Size(max = 64) on the POST body's token and left this
    // one — the same value, on the same flow, into the same lookup — unbounded, which is the
    // asymmetry the whole sweep exists to find. Concretely: an ~8 KB token of characters
    // URLEncoder expands 3x builds a ~24 KB Location header, past Tomcat's 8 KB response-header
    // limit, so an unauthenticated caller gets a 500 and an ERROR line for free. No injection
    // risk, since the value is URL-encoded into the header.
    //
    // AND THIS PATH IS NOT RATE LIMITED — the @Size IS THE ONLY THING BOUNDING IT. RateLimitConfig
    // lists /api/auth/verify-email among the per-IP patterns, so it LOOKS covered, but
    // AuthRateLimitFilter.shouldNotFilter returns true for every non-POST method and its comment
    // names this endpoint as the reason ("the GET /verify-email legacy redirect and CORS
    // preflights shouldn't burn it"). That exemption is deliberate and stays. The consequence is
    // that the only other bound on this handler is Tomcat's 8 KB request line, which is a cap on
    // the ATTEMPT and not on the number of attempts — so a comment claiming the limiter would be
    // naming a control that is switched off, which is worse than naming none, because the next
    // reader stops checking.
    //
    // NO @Validated ON THIS CLASS, deliberately: it would move validation from Spring MVC's
    // built-in method validation (HandlerMethodValidationException -> 400 from Boot's advice) to
    // the AOP proxy, which throws jakarta.validation.ConstraintViolationException — a type
    // GlobalExceptionHandler.sqlStateOf's javadoc explicitly records as falling to an UNCHANGED
    // 500. Adding it would trade one 500 for another. HandlerMethod.shouldValidateArguments()
    // returns false exactly when the class carries @Validated, so leaving it off is what makes
    // this @Size fire at all.
    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmailLink(@RequestParam @Size(max = 64) String token) {
        var location = "/verify-email?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req,
                              HttpServletResponse response) {
        return withDemoSeed(authService.login(req, response));
    }

    // Refresh also seeds: after a test-mode data reset, users with a live
    // session never pass through /login, only /refresh.
    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        return withDemoSeed(authService.refresh(request, response));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
    }

    @PostMapping("/resend-verification")
    public Map<String, String> resendVerification(@Valid @RequestBody ResendVerificationRequest req) {
        authService.resendVerification(req.email());
        return Map.of("message", "If this email is registered and unverified, a new verification link has been sent.");
    }

    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req);
        return Map.of("message", "If this email is registered, a reset link has been sent.");
    }

    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
        return Map.of("message", "Password has been reset. You can now log in.");
    }

    // Runs after the auth transaction has committed, so a seeding failure can
    // never fail (or roll back) a successful authentication — it is logged and
    // retried on the next auth because the claim rolls back with it.
    private AuthResponse withDemoSeed(AuthResponse auth) {
        // With onboarding enabled (Cloud) the demo workspace is provisioned only
        // when the user picks "Create a team" on the welcome screen (see
        // OnboardingController) — users who join an existing team get no demo.
        // Without onboarding (DC) it still seeds on first authentication.
        if (appProperties.onboarding().enabled()) {
            return auth;
        }
        try {
            demoDataService.seedOnFirstLogin(auth.userId());
        } catch (Exception e) {
            log.error("Demo data seeding failed for user {}", auth.userId(), e);
        }
        return auth;
    }

    public record MeResponse(UUID id, String email, String displayName, String avatarUrl, String systemRole,
                             boolean needsOnboarding) {}

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal User user) {
        // Cloud only (app.onboarding.enabled): true until the user creates or
        // joins their first team, or skips the welcome screen.
        boolean needsOnboarding = appProperties.onboarding().enabled() && user.getOnboardedAt() == null;
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getAvatarUrl(),
                user.getSystemRole().name(), needsOnboarding);
    }
}
