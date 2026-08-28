package com.hamstrack.common.security;

import java.nio.charset.StandardCharsets;

/**
 * <strong>The ceiling every door that WRITES a password shares, because the encoder refuses
 * above it</strong> (HD-171 §4.4).
 *
 * <p>{@link SecurityConfig#passwordEncoder()} is {@code BCryptPasswordEncoder}, and
 * {@code BCrypt.hashpw} throws {@code IllegalArgumentException("password cannot be more than 72
 * bytes")} for anything longer — spring-security-crypto 7.1.0, {@code BCrypt.java:615},
 * {@code if (!for_check && passwordb.length > 72)}. Verification takes the other branch
 * ({@code for_check = true}, reached through {@code hashpwforcheck}) and truncates silently, which
 * is why a <em>reading</em> door such as {@code LoginRequest} needs no bound of this kind and a
 * <em>writing</em> one cannot do without it: {@code encode} raises an exception no handler
 * translates, i.e. a 500 on plain user input.
 *
 * <p><strong>The unit is the whole point, and it is the reason a {@code @Size} cannot do this job
 * alone.</strong> {@code @Size} counts UTF-16 code units; BCrypt counts UTF-8 bytes. Latin text
 * costs one byte per character, Cyrillic and Greek two, CJK three, most emoji four — so 72
 * characters of Cyrillic is 144 bytes and passes {@code @Size(max = 72)} while the encoder still
 * refuses it. The annotation bounds the obvious half (an over-long ASCII password never reaches
 * the service) and {@link #exceedsEncoderLimit} bounds the half it cannot see. Both are needed;
 * neither is redundant.
 *
 * <p>Deliberately <strong>not</strong> a strength policy and not a place to put one: this number is
 * a property of the algorithm the application uses, so it changes only if the encoder does.
 *
 * <p>Three readers, one number: the two writing DTOs ({@code RegisterRequest.password},
 * {@code ResetPasswordRequest.newPassword}, whose {@code @Size} literal stays a bare numeral so a
 * <em>future</em> column-width scanner can read it — nothing reads those two today;
 * {@code EmailLengthBoundTest} reads {@code max\s*=\s*(\d+)} only on declarations it reaches from an
 * {@code @Email}), {@code AuthService}'s
 * refusal on both of those paths, and {@code DataSeeder}, which encodes {@code seed.admin.password}
 * at boot.
 */
public final class PasswordLimits {

    /** The most UTF-8 bytes {@code BCryptPasswordEncoder.encode} will hash; above it, it throws. */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** How many UTF-8 bytes this password costs — the unit the encoder actually measures in. */
    public static int byteLength(String password) {
        return password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Would {@code encode} refuse this value? A null or empty password never does. */
    public static boolean exceedsEncoderLimit(String password) {
        return byteLength(password) > MAX_PASSWORD_BYTES;
    }

    private PasswordLimits() {}
}
