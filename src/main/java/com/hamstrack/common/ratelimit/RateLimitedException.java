package com.hamstrack.common.ratelimit;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class RateLimitedException extends AppException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Too many requests — try again later", HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
