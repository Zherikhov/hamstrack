package com.hamstrack.auth.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.common.security.PasswordLimits;
import org.springframework.http.HttpStatus;

/**
 * <strong>HD-171 — the two doors that write a password may not promise a range the encoder
 * refuses.</strong>
 *
 * <p>{@code BCryptPasswordEncoder.encode} throws above
 * {@value com.hamstrack.common.security.PasswordLimits#MAX_PASSWORD_BYTES} UTF-8 bytes
 * ({@link PasswordLimits}), and that exception reaches no handler — so
 * {@code POST /api/auth/register} (unauthenticated) and {@code POST /api/auth/reset-password}
 * answered <strong>500</strong> to a password a person could plausibly choose: 73+ ASCII
 * characters, but only 37 Cyrillic ones or 25 CJK ones, because the limit is on bytes and the
 * {@code @Size} beside it counts UTF-16 units.
 *
 * <p><strong>422, like {@link PublishedPasswordException}, and for the same reason</strong>: the
 * request is well-formed and satisfies every syntactic constraint on the field — what is refused
 * is which values the application can store, which is a business rule.
 *
 * <p><strong>The message names the unit and the arithmetic</strong>, because "72 bytes" is not
 * something a person typing a passphrase can evaluate: a reader who is told only the number, having
 * typed 40 characters, cannot act. A refusal may only prescribe an action its reader can perform.
 */
public class PasswordTooLongException extends AppException {
    public PasswordTooLongException(int actualBytes) {
        super("That password is " + actualBytes + " bytes long and the maximum is "
              + PasswordLimits.MAX_PASSWORD_BYTES + " bytes. Characters outside the basic Latin set "
              + "count for more than one: accented letters, Greek and Cyrillic cost 2 bytes each, "
              + "most other scripts 3, and emoji 4 — so a passphrase can be well under 72 characters "
              + "and still be over the limit. Shorten it, or use fewer non-Latin characters.",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
