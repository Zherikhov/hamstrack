package com.hamstrack.common.mail;

import com.hamstrack.common.mail.UndeliverableMail.Reason;
import lombok.Getter;

import java.util.concurrent.RejectedExecutionException;

/**
 * The mail pool refusing a dispatch, carrying <strong>which</strong> refusal it was (HD-208).
 *
 * <p>{@code mailExecutor}'s rejection handler always throws — it never runs the task on the calling
 * thread — so this is the only thing a rejected dispatch produces. {@code ThreadPoolTaskExecutor}
 * wraps it as {@code TaskRejectedException} on the way out, which is why
 * {@link MailDispatcher#dispatch} unwraps rather than catches this directly.
 *
 * <p>The reason travels with the throw because the two refusals mean different things to whoever
 * reads the {@code failed_email} row afterwards — a full queue is an overload, a shut-down pool is
 * a deploy — and a handler that only threw a message would force the reader to parse English.
 */
@Getter
public class MailRejectedException extends RejectedExecutionException {

    private final transient Reason reason;

    public MailRejectedException(Reason reason) {
        super(reason.detail());
        this.reason = reason;
    }
}
