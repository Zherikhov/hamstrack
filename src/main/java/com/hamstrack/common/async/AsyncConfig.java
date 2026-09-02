package com.hamstrack.common.async;

import com.hamstrack.common.config.MailAsyncProperties;
import com.hamstrack.common.mail.MailRejectedException;
import com.hamstrack.common.mail.UndeliverableMail;
import com.hamstrack.common.mail.UndeliverableMail.Reason;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;

/**
 * Dedicated bounded executor for mail (HD-78, HD-181, HD-207, HD-208).
 *
 * <p>Without an explicit executor, {@code @EnableAsync} falls back to Spring's unbounded,
 * thread-per-task {@code SimpleAsyncTaskExecutor} — under an SMTP stall (bounded by HD-76 to ≤10 s
 * per attempt) that spawns a thread per queued email. A bounded pool gives an explicit queue and an
 * explicit rejection policy, and gives mail a pool of its own so it cannot starve other
 * asynchronous work.
 *
 * <h2>A dispatch never becomes a send on the calling thread (HD-208)</h2>
 * The pool used to run {@code CallerRunsPolicy}. That was chosen as backpressure — better to slow a
 * caller down than to drop account-critical mail — and it was the right trade <em>at the time it
 * was made</em>, when a dropped dispatch left nothing behind at all. It is no longer, for two
 * reasons that both arrived after it:
 *
 * <ul>
 *   <li><strong>Dropping stopped meaning losing.</strong> HD-78 gave mail retries and a
 *       {@code failed_email} dead-letter table, so a refusal can now be <em>written down</em>. The
 *       comparison is no longer "slow request versus lost email" but "slow request versus
 *       dead-letter row", and the row is the better artefact of the two.</li>
 *   <li><strong>What backpressure actually bought was negative.</strong> The pushback landed on
 *       Tomcat workers, from {@code POST /api/auth/register} among others — unauthenticated, 15 per
 *       minute per IP, critical mail — for up to a full retry budget each. It was self-amplifying
 *       (a slower host fills the queue faster, which puts more workers on SMTP) and it was reachable
 *       by anyone with a handful of source IPs or by one genuine SMTP outage meeting organic
 *       traffic. On this deployment's box the cost is worse than the raw seconds suggest: measured
 *       capacity is ~45 concurrent users on a 512 MB SerialGC heap, so held workers are scarce.</li>
 * </ul>
 *
 * <p>So the handler below always throws, and {@code MailDispatcher} turns the throw into a durable
 * record. The decision and its alternatives are ADR-0021
 * ({@code docs/adr/0021-mail-rejection-dead-letters-instead-of-caller-runs.md}); it replaced a
 * deliberate choice and was replaced deliberately.
 *
 * <p>One consequence is worth stating positively, because the old javadoc had to state its
 * negation: <strong>a mail dispatch is now genuinely asynchronous</strong>. It used to be that
 * {@code @Async} was not a promise of asynchrony — under exactly the load that mattered, it was a
 * synchronous send — so no caller could rely on it to keep SMTP out of a transaction or a lock.
 * That reliance is still not the mechanism to reach for ({@code common.tx.AfterCommit} is, and
 * every mailer is registered there), but the dispatch itself no longer betrays it.
 *
 * <h2>What the queue's contents are worth at shutdown (HD-207)</h2>
 * {@link MailTaskExecutor} is a {@code ThreadPoolTaskExecutor} that, once the drain window has
 * expired, takes back what the drain did not reach and dead-letters it instead of abandoning it
 * silently. That is why the queue can stay large enough to absorb a burst: its residue is durable,
 * so its size no longer has to be an estimate of what fifteen seconds can flush.
 *
 * <h2>An unqualified {@code @Async} anywhere in the tree lands on this pool</h2>
 * and it is never what its author meant. This bean is an {@code Executor}, so Boot's
 * {@code applicationTaskExecutor} backs off ({@code @ConditionalOnMissingBean(Executor.class)},
 * short of {@code spring.task.execution.mode=force}) and {@code mailExecutor} is left the unique
 * {@code TaskExecutor} that {@code @EnableAsync} resolves an unqualified {@code @Async} to. An
 * unrelated concern would then queue behind mail, meet {@link MailRejectedException} with no
 * {@code AfterCommit} effect around it to swallow the throw, and — because it is not a
 * {@code MailTask} — be uncountable and unrecordable at shutdown, where it produces an ERROR saying
 * only that it existed. Declaring a second executor does not repair it either: the lookup becomes
 * ambiguous, and with no bean named {@code taskExecutor} the interceptor falls back to a fresh
 * unbounded {@code SimpleAsyncTaskExecutor} — the thread-per-task behaviour HD-78 exists to have
 * removed. <strong>Qualify every {@code @Async}</strong>, and give a new asynchronous concern its
 * own bounded executor under its own name.
 */
@Configuration
public class AsyncConfig {

    /**
     * @param undeliverable injected as a constructor argument, not looked up, and not through an
     *                      {@code ObjectProvider}: the injection is what tells the container this
     *                      bean depends on it, which is what makes this bean be destroyed
     *                      <em>before</em> the writer and its {@code EntityManagerFactory}. The
     *                      shutdown residue is written to the database from inside
     *                      {@link MailTaskExecutor#shutdown()}, so that ordering is load-bearing
     *                      rather than tidy.
     */
    @Bean("mailExecutor")
    public TaskExecutor mailExecutor(MailAsyncProperties properties, UndeliverableMail undeliverable) {
        var async = properties.async();
        var executor = new MailTaskExecutor(undeliverable, async.shutdownDrainSeconds());
        executor.setCorePoolSize(async.corePoolSize());
        executor.setMaxPoolSize(async.maxPoolSize());
        executor.setQueueCapacity(async.queueCapacity());
        executor.setThreadNamePrefix("mail-");
        executor.setRejectedExecutionHandler(refuseAlways());
        executor.initialize();
        return executor;
    }

    /**
     * <strong>Refuse; never run on the caller.</strong>
     *
     * <p>The two refusals are distinguished because they mean different things to whoever reads the
     * {@code failed_email} row afterwards — a full queue is an overload, a shut-down pool is a
     * deploy — and {@code MailDispatcher} is what turns either into that row. The reason travels on
     * the exception rather than in its message so no reader has to parse English.
     *
     * <p><strong>The question is asked once.</strong> Checking {@code isShutdown()} and then
     * delegating to a policy asks it twice and can get two answers; that mattered when one branch
     * ran the task, and the habit is kept now that neither does, because a handler with one exit is
     * a handler with nothing to get wrong.
     *
     * <p><strong>What this handler is reached for, and what it cannot see.</strong> A
     * {@code RejectedExecutionHandler} runs on submission and on nothing else, so the only shutdown
     * loss it observes is the narrow one — a request still in flight during the container's drain,
     * dispatching after {@code shutdown()} has been called. The larger loss, a full queue abandoned
     * when the drain expires, happens where nothing is submitted and therefore where no handler is
     * invoked; {@link MailTaskExecutor#shutdown()} is what covers that, and the two paths meet again
     * at {@link UndeliverableMail}.
     */
    private RejectedExecutionHandler refuseAlways() {
        return (task, executor) -> {
            throw new MailRejectedException(
                    executor.isShutdown() ? Reason.POOL_SHUT_DOWN : Reason.QUEUE_FULL);
        };
    }
}
