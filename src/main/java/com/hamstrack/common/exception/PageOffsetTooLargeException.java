package com.hamstrack.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The row offset a caller's {@code page}/{@code size} pair implies does not fit in an
 * {@code int}, so it cannot be handed to a JPA {@code setFirstResult} (HD-163). Raised by
 * {@link com.hamstrack.common.dto.Paging#offsetOf}.
 *
 * <p>400, deliberately the same status the {@code @Max(Paging.MAX_PAGE)} at the door
 * answers: this is a belt behind that annotation, not a second rule, and a belt that
 * changed the status class would make relaxing the bound look like a new failure mode.
 * The annotation stays the authoritative refusal because it fires during argument
 * resolution and costs no queries; this one is what remains if it is ever loosened.
 */
public class PageOffsetTooLargeException extends AppException {
    public PageOffsetTooLargeException() {
        super("Page index is too large", HttpStatus.BAD_REQUEST);
    }
}
