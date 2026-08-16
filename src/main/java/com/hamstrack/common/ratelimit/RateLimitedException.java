package com.hamstrack.common.ratelimit;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

public class RateLimitedException extends AppException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        this("Too many requests — try again later", retryAfterSeconds);
    }

    /**
     * Same 429 + {@code Retry-After} envelope with a caller-specific detail. Used where
     * the generic wording would leave the user guessing what is throttled — e.g. the
     * per-project backlog-rank rebalance ({@code IssueRankService}), which is not an
     * auth limit and has a very different remedy ("wait, then drag again").
     */
    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message, HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
