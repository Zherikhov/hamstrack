package com.hamstrack.common.security;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;

    /**
     * Every value this project has ever published in a place an operator copies from as
     * <em>production</em> configuration — the {@code .env.prod.example} placeholder and the
     * {@code docs/self-hosting.md} quick-start compose. Both are long enough to pass the
     * length check below, which is how one of them shipped for months: the guard fails on
     * absence, and a placeholder is the one thing that is not absent (HD-200).
     *
     * <p><strong>This is not a strength check and must never grow into one.</strong> A
     * denylist of weak secrets invites "mine is not on the list" reasoning and goes stale
     * by the week. What justifies these two entries is narrower and does not generalise:
     * they are strings <em>we</em> published under this variable's own name, so we can name
     * them exactly, and an instance running one is not weakly protected — it is publicly
     * signable by anyone who can read the repository. Emptying the template stops the next
     * install; refusing the value here also stops the ones that already happened, which no
     * edit to a template can reach.
     *
     * <p>Deliberately <em>not</em> listed: {@code dev-only-…}, {@code ci-only-…} and
     * {@code drill-only-…}, which this repository also publishes. Those name themselves as
     * throwaways in their own text, are used by documented local/CI/restore-drill commands,
     * and refusing them would break those commands to protect nobody.
     *
     * <p>The same shape exists for the seeded administrator's password
     * ({@code DataSeeder.PUBLISHED_PASSWORDS}); both sets are pinned to their size by a test
     * that states the evidentiary standard, so neither can drift into a strength checker.
     * Package-private for exactly that pin.
     */
    static final Set<String> PUBLISHED_PLACEHOLDERS = Set.of(
            "REPLACE_WITH_64_CHAR_RANDOM_STRING",
            "change-me-to-a-random-string-of-32-plus-bytes");

    /** Named once so the refusal and the template say the same thing. */
    private static final String GENERATE = "Generate one with: openssl rand -base64 48";

    /**
     * What rotating the key actually does — stated here because this is the sentence an
     * operator acts on at the moment they learn their signing key is public, and it was
     * wrong until HD-200: it said rotation signs out every session.
     *
     * <p>It does not. Refresh tokens are opaque 32-byte random values stored as SHA-256 and
     * independent of this secret, and {@code /api/auth/refresh} is {@code permitAll} — so a
     * new key rejects every access token minted under the old one immediately, and the SPA
     * takes a single 401 and continues from its refresh cookie. Reading that as a purge is
     * expensive twice over: it overstates the cost (a reason to defer the rotation), and it
     * tells the operator the follow-up steps are already done, when those steps are what
     * actually cut an attacker who turned a forged token into a durable foothold.
     *
     * <p><strong>It names the same three steps as the two prose copies, in the same words.</strong>
     * This one said "audit … recent password resets" where {@code .env.prod.example} and
     * {@code docs/self-hosting.md} said {@code DELETE FROM password_resets WHERE used_at IS
     * NULL} — a difference that matters, because an unused admin-issued setup link is the
     * cheapest durable foothold a forged admin token buys: a row with a seven-day life that
     * is neither a session nor an access token, so neither the rotation nor the
     * {@code refresh_tokens} delete reaches it. "Audit" is a thing to look at; the statement
     * is a thing to run. Parity across all three copies is asserted by
     * {@code JwtSecretValidationTest.everyCopyOfTheRotationSentenceSaysTheSameThing}.
     */
    private static final String ROTATION =
            "Rotating it rejects every access token signed with the old key immediately, but it does NOT end "
            + "sessions: refresh tokens are random values independent of this secret, so clients silently "
            + "re-issue from their refresh cookie. If you believe the published value was used against this "
            + "instance, also revoke every refresh session (DELETE FROM refresh_tokens), delete every unused "
            + "invite/reset link (DELETE FROM password_resets WHERE used_at IS NULL — an admin session forged "
            + "with the old key can issue one, and it outlives both the rotation and the line before it) and "
            + "audit your admin accounts.";

    // HMAC-SHA256 requires a >= 256-bit key (RFC 7518 §3.2). Checking at startup turns
    // a misconfigured JWT_SECRET into a clear boot failure instead of a 500 on first login.
    @PostConstruct
    void validateSecret() {
        var secret = jwtProperties.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "jwt.secret (JWT_SECRET) must be at least 32 bytes for HMAC-SHA256; current value is "
                    + (secret == null ? "missing" : secret.getBytes(StandardCharsets.UTF_8).length + " bytes")
                    + ". " + GENERATE);
        }
        if (PUBLISHED_PLACEHOLDERS.contains(secret)) {
            throw new IllegalStateException(
                    "jwt.secret (JWT_SECRET) is a value published in Hamstrack's own documentation, so it is "
                    + "known to everyone who can read the repository: anyone could mint an access token for "
                    + "this instance, including one claiming to be an administrator. It is long enough to pass "
                    + "the length check, which is why it is refused by name. " + GENERATE + ". " + ROTATION);
        }
    }

    public String generateAccessToken(User user) {
        var now = new Date();
        var expiry = new Date(now.getTime() + jwtProperties.accessTokenExpiration().toMillis());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey())
                .compact();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // Generates a cryptographically random refresh token (raw value — never stored as-is)
    public String generateRawRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }
}
