package com.hamstrack.auth.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>HD-200 — the published value may not enter {@code users} through the application
 * either.</strong>
 *
 * <p>Startup refuses an administrator whose stored hash verifies the password this project
 * printed in its own {@code .env.prod.example}. Nothing refused it on the way <em>in</em>:
 * register and reset bounded the password only by length, and the literal is 19 characters,
 * so an administrator could type it into "choose a new password" and be accepted. Two
 * consequences, and the second is the worse one: the next boot refuses to start with a
 * message about a template nobody edited, and until that restart the instance is publicly
 * administrable by anyone who can read the repository.
 *
 * <p><strong>422 and not 400</strong>: the request is well-formed and the field satisfies
 * every syntactic constraint on it — this is a business rule about which value is
 * acceptable, which is what {@code UNPROCESSABLE_CONTENT} means here.
 *
 * <p>The predicate is {@code DataSeeder.isPublishedPassword}, shared with both startup
 * guards on purpose: three readers of one set cannot come to disagree about it.
 */
public class PublishedPasswordException extends AppException {
    public PublishedPasswordException() {
        super("That password is published in Hamstrack's own configuration template, so it is known to "
              + "everyone who can read the project's source. Choose a different one.",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
