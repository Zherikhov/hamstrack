package com.hamstrack.common.async;

import com.hamstrack.common.config.MailAsyncProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * Dedicated bounded executor for {@code @Async} mail (HD-78). {@code @EnableAsync}
 * with no configured executor falls back to Spring's unbounded, thread-per-task
 * {@code SimpleAsyncTaskExecutor} — under an SMTP stall (now bounded by HD-76 to
 * ≤10s/send) that spawns a thread per queued email. A bounded pool gives an
 * explicit queue + rejection policy: while the pool is running, a full queue means
 * <em>backpressure</em> — the calling thread sends inline — rather than a dropped
 * message. Named {@code mailExecutor} and referenced by {@code @Async("mailExecutor")}
 * so mail can't starve any other future {@code @Async} work.
 *
 * <p><strong>"Rather than dropping mail" is true of a full queue and of nothing else.</strong>
 * Two other losses are real and neither is closed here: a submission arriving after
 * {@code shutdown()} has no pool to run on, and the queue's whole contents are
 * abandoned when the 15s drain below expires. See
 * {@link #callerRunsRefusingAfterShutdown()} for which of the two the rejection
 * handler covers and which it does not.
 *
 * <p><strong>{@code @Async} on this executor is not a promise that anything is
 * asynchronous</strong> (HD-181). Backpressure is the same statement read from the
 * caller's side: when the queue is full the dispatch <em>becomes</em> a synchronous
 * send on the calling thread. So a caller may not treat {@code @Async} as proof that
 * something slow is off its own thread — under load it is not — and in particular may
 * not rely on it to keep a send out of a transaction or a lock. That is what
 * {@code com.hamstrack.common.tx.AfterCommit} is for, and why every mailer is now
 * registered there rather than called inline.
 *
 * <p><strong>An unqualified {@code @Async} anywhere in the tree lands on this pool</strong>, and
 * it is never what its author meant. This bean is an {@code Executor}, so Boot's
 * {@code applicationTaskExecutor} backs off ({@code @ConditionalOnMissingBean(Executor.class)},
 * short of {@code spring.task.execution.mode=force}) and {@code mailExecutor} is left the unique
 * {@code TaskExecutor} that {@code @EnableAsync} resolves an unqualified {@code @Async} to. An
 * unrelated concern would then queue behind mail, inherit the caller-runs stall when the mail queue
 * fills, and meet the {@code RejectedExecutionException} below with no {@code AfterCommit} effect
 * around it to swallow the throw. Declaring a second executor does not repair it either: the lookup
 * becomes ambiguous, and with no bean named {@code taskExecutor} the interceptor falls back to a
 * fresh unbounded {@code SimpleAsyncTaskExecutor} — the thread-per-task behaviour HD-78 exists to
 * have removed. <strong>Qualify every {@code @Async}</strong>, and give a new asynchronous concern
 * its own bounded executor under its own name.
 */
@Configuration
@RequiredArgsConstructor
public class AsyncConfig {

    private final MailAsyncProperties properties;

    @Bean("mailExecutor")
    public TaskExecutor mailExecutor() {
        var async = properties.async();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(async.corePoolSize());
        executor.setMaxPoolSize(async.maxPoolSize());
        executor.setQueueCapacity(async.queueCapacity());
        executor.setThreadNamePrefix("mail-");
        // Backpressure while running (send on the caller thread when the queue is full, bounded by
        // HD-76 SMTP timeouts); a refusal the caller can see once the pool is shut down.
        executor.setRejectedExecutionHandler(callerRunsRefusingAfterShutdown());
        // Flush in-flight mail on graceful shutdown, bounded so we never hang. NOT a guarantee that
        // the queue drains — see the handler's javadoc for what this loses.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    /**
     * {@code CallerRunsPolicy}'s two branches, with the silent one replaced by a refusal the caller
     * can name (HD-181).
     *
     * <h2>What this handler is reached for, which is not what shutdown mostly loses</h2>
     * A {@code RejectedExecutionHandler} runs on <strong>submission</strong> and on nothing else. So
     * the only shutdown loss it can see is the narrow one: a request still in flight during Tomcat's
     * drain dispatches a send after {@code shutdown()} has been called, and there is no longer a pool
     * to take it.
     *
     * <p><strong>The larger loss happens where no handler is invoked, and this does not cover
     * it.</strong> {@code setWaitForTasksToCompleteOnShutdown(true)} keeps the queue and waits
     * {@code awaitTerminationSeconds}; when that expires the remaining tasks are abandoned as a
     * block. At 10–34s per send a handful of workers flush a handful of messages in 15s, so a queue
     * that is anywhere near its capacity loses the rest — already committed, already announced to
     * their users, and gone with one generic line from Spring naming none of them. Sizing the queue
     * to what the drain can actually flush, or dead-lettering what {@code shutdownNow()} hands back,
     * is the fix for that and is filed separately. Nothing below helps with it.
     *
     * <h2>Why a throw rather than a discard</h2>
     * {@code CallerRunsPolicy} discards silently once the pool is shut down. That mattered less when
     * a lost dispatch and a rolled-back transaction failed together; now the row is committed and
     * the caller has already been told the invite was sent, so the drop has to leave a trace — and a
     * trace written <em>here</em> can only be anonymous, because a handler sees a {@code Runnable}
     * and knows neither recipient nor mail type. Throwing gives the trace a name for free:
     * {@code ThreadPoolTaskExecutor} wraps this as {@code TaskRejectedException}, which propagates
     * back through the {@code @Async} proxy on the calling thread into
     * {@code AfterCommit.runQuietly}, whose ERROR line already carries the description of the effect
     * that was lost. Every mail dispatch is registered there, so what reaches this handler is
     * swallowed rather than becoming a 500 — which is a property of how the submitters are written,
     * not of the pool: an {@code @Async} that arrives here from outside an {@code AfterCommit}
     * effect gets the throw raw, and the class javadoc says why an unqualified one would.
     *
     * <h2>Why both branches are written out rather than delegated</h2>
     * Checking {@code isShutdown()} and then handing the task to the policy asks the same question
     * twice and can get two answers: with shutdown starting in between, the policy discards the task
     * on its own check and the branch that exists to report it has already been skipped — silence in
     * exactly the case the report was for. Deciding once removes that. The surviving race resolves
     * the safe way: a shutdown that begins after the check runs the send on the caller instead of
     * dropping it, which for an already-committed message is the outcome to want.
     *
     * <p>The running branch is byte-identical to {@code CallerRunsPolicy}: the task runs on the
     * calling thread. That is the backpressure the pool is configured for, and it is also why
     * {@code @Async} is not a promise of asynchrony — see the class javadoc.
     */
    private RejectedExecutionHandler callerRunsRefusingAfterShutdown() {
        return (task, executor) -> {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException(
                        "mailExecutor is shut down — this email cannot be queued and will never be "
                        + "sent. Any database row it was announcing is committed.");
            }
            task.run();
        };
    }
}
