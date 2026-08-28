package com.hamstrack.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code @Size(max = 255)} on the address is not decoration: see
 * {@link com.hamstrack.admin.dto.CreateUserRequest} and {@code EmailLengthBoundTest} for why
 * every door that carries an address needs it, including the ones that only read.
 *
 * <p><strong>{@code password} carries {@code max} and deliberately NO {@code min}</strong>
 * (HD-171 §4.4), and the asymmetry with {@code RegisterRequest} is the point rather than an
 * oversight — do not "tidy" it into symmetry. A {@code min} here would answer <strong>400</strong>
 * where this endpoint must answer <strong>401</strong>, which hands an attacker an oracle
 * distinguishing "too short to be anyone's password" from "wrong password" — a free filter over
 * a credential list, on an unauthenticated door.
 *
 * <p><strong>This is a READING door, and that is what sets the number.</strong> BCrypt's
 * {@code matches} takes the {@code for_check} branch, which truncates at 72 bytes instead of
 * throwing (spring-security-crypto 7.1.0, {@code BCrypt.hashpw}) — so verification cannot be made
 * to fail or to cost more by a long submission, and this bound's entire job is that the field is
 * <em>finite</em>. It is deliberately far above anything any door could produce: 100 and 1024 cost
 * exactly the same to verify, so the number buys nothing by being small. A bound stated as "finite,
 * and far above anything any door can produce" is a claim about a <em>category</em>, and it does
 * not go stale when a new writing door opens.
 *
 * <p><strong>A story that used to be here has been deleted, and the deletion is the lesson.</strong>
 * This javadoc justified the number by naming a member — a specific administrator, seeded with a
 * 128-character {@code SEED_ADMIN_PASSWORD}, whom a tighter bound would have locked out forever.
 * <em>That instance cannot exist.</em> {@code DataSeeder} hashes with the same BCrypt, which refuses
 * to <em>create</em> a hash above 72 bytes, so no account on this codebase has ever held a password
 * longer than that and no bound of 100 or more could have locked anyone out. The category claim
 * ("a reading door needs only to be finite") survived being wrong about its own example, while the
 * member claim attached to it had to be deleted outright — which is exactly the rule this ticket is
 * named for, caught in the text the ticket itself wrote.
 *
 * <p>The literal stays a bare numeral so that a <em>future</em> column-width scanner can read it —
 * nothing reads this one today. {@code EmailLengthBoundTest} does read {@code max\s*=\s*(\d+)}, but
 * only on declarations it reaches from an {@code @Email} (so it reads the address bound above and
 * not this one). Keeping the form it reads costs nothing and is what makes the field scannable
 * later; a symbolic reference would read as no bound at all. What is meant to police the
 * <em>value</em> is a behavioural test (HD-171 §5.3), not a source scan. It is paired with
 * nothing — the <em>writing</em> doors ({@code RegisterRequest.password},
 * {@code ResetPasswordRequest.newPassword}, {@code DataSeeder.MAX_SEED_PASSWORD_BYTES}) are pinned
 * to the encoder's 72 bytes and are what {@link com.hamstrack.common.security.PasswordLimits}
 * governs; this door must simply accept whatever any of them produced, and 1024 does.
 */
public record LoginRequest(
        @Email @NotBlank @Size(max = 255) String email,
        @NotBlank @Size(max = 1024) String password
) {}
