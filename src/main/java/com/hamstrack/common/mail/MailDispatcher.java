package com.hamstrack.common.mail;

import com.hamstrack.common.mail.UndeliverableMail.Reason;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/**
 * The one door between a mailer and the mail pool (HD-208).
 *
 * <h2>What it guarantees, which is the whole of HD-208</h2>
 * <strong>The calling thread hands the message over and returns.</strong> It never performs an SMTP
 * round trip, whatever the queue depth and whatever the mail host is doing. Before this, the pool
 * ran {@code CallerRunsPolicy}, so a full queue turned an {@code @Async} dispatch into a
 * synchronous send on the caller — up to the full retry budget of a Tomcat worker, reachable from
 * the unauthenticated {@code POST /api/auth/register}, and self-amplifying: the slower the mail
 * host, the fuller the queue, the more workers doing SMTP instead of serving. The rejection handler
 * now always throws and this class turns that throw into a durable record.
 *
 * <p>It also closes a quieter consequence of the same property. With the queue full, a
 * <em>known</em> address on {@code forgot-password} cost an inline send with retries while an
 * <em>unknown</em> address returned at once, because the unknown branch does no work at all — a
 * several-second timing difference on an endpoint whose entire design is that the two are
 * indistinguishable. Dispatch is now a queue offer or a bounded refusal on either branch.
 *
 * <h2>Why a refusal is allowed to be silent for critical mail and not for best-effort</h2>
 * {@link UndeliverableMail#record} answers whether the loss was written down. If it was, the caller
 * is not told: the {@code failed_email} row is a better artefact than an exception nobody can
 * catch, since every mailer is dispatched from an {@code AfterCommit} effect where a throw is
 * swallowed by construction. If it was not — best-effort mail earns no row — the throw is
 * rethrown so it reaches that effect's ERROR line, which carries the description its call site
 * wrote and therefore names the row an operator has to open. The rule is one sentence: <em>refuse
 * loudly what cannot be written down, and write down what can.</em>
 */
@Component
public class MailDispatcher {

    private final TaskExecutor mailExecutor;
    private final UndeliverableMail undeliverable;

    // Qualified explicitly. mailExecutor is the only TaskExecutor bean today, so by-type wiring
    // would work — and the day a second bounded executor is declared for another concern (which is
    // exactly how AsyncConfig's javadoc says a new asynchronous concern must be added) by-type
    // wiring becomes ambiguous, having been silently right until then.
    public MailDispatcher(@Qualifier("mailExecutor") TaskExecutor mailExecutor,
                          UndeliverableMail undeliverable) {
        this.mailExecutor = mailExecutor;
        this.undeliverable = undeliverable;
    }

    /**
     * Hand {@code task} to the mail pool.
     *
     * @throws RejectedExecutionException (as {@code TaskRejectedException}) only when the pool
     *         refused the message <em>and</em> nothing durable was written about it
     */
    public void dispatch(MailTask task) {
        try {
            // execute, never submit: submit() wraps the task in a FutureTask, and the queue would
            // then hold something neither the rejection handler nor shutdownNow() can name. See
            // MailTask for why that identity is the point.
            mailExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            if (!undeliverable.record(task, reasonOf(e))) {
                throw e;
            }
        }
    }

    /**
     * The pool's handler throws {@link MailRejectedException}; {@code ThreadPoolTaskExecutor} wraps
     * it as {@code TaskRejectedException}, so the reason is one cause down.
     *
     * <p>A rejection that is not ours cannot happen through this method today, and is still given a
     * reason rather than a crash — an undeliverable message with a vague reason is worth more than
     * an exception thrown while recording one.
     */
    private static Reason reasonOf(RejectedExecutionException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MailRejectedException mail) {
                return mail.getReason();
            }
        }
        return Reason.QUEUE_FULL;
    }
}
