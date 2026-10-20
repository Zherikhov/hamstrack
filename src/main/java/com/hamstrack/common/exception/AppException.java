package com.hamstrack.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public abstract class AppException extends RuntimeException {

    private final HttpStatus status;

    protected AppException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /**
     * <strong>The document this refusal renders as, in one place because two paths render it</strong>
     * (HD-305). {@link GlobalExceptionHandler#handleAppException} answers one that reached a handler;
     * {@code AuthRateLimitFilter} answers a 429 raised before the dispatcher and has to write the
     * bytes itself through {@link ProblemJson}. A second copy of this line in the filter is how the
     * two renderings of one refusal come to disagree — which is exactly what happened while the
     * filter built its JSON by hand.
     *
     * <p>A handler that adds an {@code errorType} or any other extension member sets it on the
     * returned document rather than building a second one.
     */
    public ProblemDetail problemDetail() {
        return ProblemDetail.forStatusAndDetail(status, getMessage());
    }
}
