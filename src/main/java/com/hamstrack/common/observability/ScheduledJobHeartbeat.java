package com.hamstrack.common.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <strong>One heartbeat for every scheduled task in the process, with no edit to any of them</strong>
 * (HD-298, epic HD-294).
 *
 * <h2>The silence this closes</h2>
 * A {@code @Scheduled} method that throws is logged once by Spring's error handler and scheduled
 * again; one that stops being scheduled — a wedged pool, a lost registration, a Boot upgrade that
 * moved a hook — is logged nowhere at all. Two of this product's jobs published a freshness gauge of
 * their own after the outage that taught them to ({@code AnonymousMailConcentration},
 * {@code WorkspaceStorageReconciler}); the other eleven had no witness. Adding a gauge to each would
 * have been eleven copies of the same four lines, which is the shape the 2026-09 retrospective
 * forbids.
 *
 * <h2>How it sees every task without touching one</h2>
 * <strong>Measured 2026-09-09 on Boot 4.1.0 / Spring 7.0.8</strong>, not remembered:
 * {@code spring-boot-micrometer-observation}'s {@code ScheduledTasksObservationAutoConfiguration}
 * registers a {@code SchedulingConfigurer} that hands the {@code ObservationRegistry} to the
 * {@code ScheduledTaskRegistrar}, and every annotated task's {@link ScheduledMethodRunnable} then
 * wraps each run in a {@code tasks.scheduled.execution} observation — package-private
 * {@code void sweep()} included ({@code /actuator/prometheus} showed
 * {@code tasks_scheduled_execution_seconds_count{code_function="sweep",...}} for every limiter).
 * This class is an {@link ObservationHandler} for that observation's context: {@link #onStop} stamps
 * the task's last run and counts a run whose context carries an error.
 *
 * <p>What that mechanism does <em>not</em> see is a plain {@code Runnable} handed to the registrar
 * ({@code StorageReconcileSchedule} does that, for a reason its javadoc gives) — the probe showed
 * zero series for it. Such a task is wrapped explicitly, once, at the site: {@link #wrap}. Those are
 * the two ways a task can be registered, and {@code ScheduledJobHeartbeatTest} refuses a registered
 * task that is neither.
 *
 * <h2>Seeded at boot, never zero</h2>
 * Every task's {@code last_run} is registered in {@link #afterSingletonsInstantiated()} — before the
 * scheduler starts — with the process start time, so a task that never runs once ages from boot and
 * {@code ScheduledJobStale} fires after {@code min(3 * period + 900, period + 4500)} seconds — three
 * periods plus a deploy allowance for the frequent sweeps, one period plus 75 minutes for the hourly
 * and daily ones (a daily task at three periods is 72 h, longer than any gap between deploys, so it
 * would never fire; the rule's comment block carries the per-period table). That is the
 * {@code ProductMetrics.anonymousMailConcentrationRefreshedAt} lesson (HD-202): a sentinel below the
 * rule's own threshold makes the worst state the silent one.
 *
 * <p><strong>Not seen:</strong> a task handed to a bare {@code TaskScheduler} or run by
 * {@code @Async} (neither is a {@code ScheduledTask}); the alert's arithmetic in Grafana; whether the
 * Boot observation survives the next upgrade — if it does not, every annotated task goes stale
 * together, which is exactly what the rule's {@code NoData → Alerting} exists to say.
 */
@Slf4j
@Component
public class ScheduledJobHeartbeat
        implements ObservationHandler<ScheduledTaskObservationContext>, SmartInitializingSingleton {

    /**
     * Published as the period of a task whose trigger this class cannot read (a custom
     * {@code Trigger}). Zero on purpose: {@code ScheduledJobStale}'s bound collapses to its deploy
     * allowance ({@code min(0 + 900, 0 + 4500)} = 900 s), so such a task pages within fifteen minutes
     * of not running — loud, never silent — and the WARN at registration says why.
     */
    static final long UNKNOWN_PERIOD_SECONDS = 0;

    private final ObjectProvider<ProductMetrics> metrics;
    private final ObjectProvider<ScheduledTaskHolder> holders;
    private final ConcurrentMap<String, AtomicLong> lastRunEpochMillis = new ConcurrentHashMap<>();

    public ScheduledJobHeartbeat(ObjectProvider<ProductMetrics> metrics,
                                 ObjectProvider<ScheduledTaskHolder> holders) {
        this.metrics = metrics;
        this.holders = holders;
    }

    /** The label value for a task: {@code SimpleName#method}, the {@code Doors.Job} spelling. */
    public static String id(Class<?> owner, String method) {
        return ClassUtils.getUserClass(owner).getSimpleName() + "#" + method;
    }

    /**
     * <strong>The one call a task registered outside the annotation mechanism makes.</strong>
     * Returns a {@link CronTask} whose runnable stamps this heartbeat around {@code body} and
     * registers the task's gauges now, seeded with the current time. An invalid cron still fails the
     * boot, exactly as the bare {@code addCronTask} did.
     */
    public CronTask wrap(String task, Runnable body, String cron) {
        register(task, periodOf(CronExpression.parse(cron)));
        return new CronTask(new Wrapped(task, body), cron);
    }

    /**
     * The id a registered runnable will be stamped under, if the heartbeat can see it at all:
     * annotated tasks by their {@link ScheduledMethodRunnable}, wrapped ones by {@link #wrap}.
     * Empty for anything else — a bare lambda on the registrar — which is the finding.
     *
     * <p><strong>Measured on Spring 7.0.8:</strong> {@code Task.getRunnable()} does not return the
     * runnable that was registered but a private {@code Task$OutcomeTrackingRunnable} around it
     * (the one that feeds {@code getLastExecutionOutcome()}), so the registered runnable is one
     * decorator down. {@link #unwrap} peels such decorators by their single {@code Runnable} field;
     * without it every annotated task read as "registered outside both mechanisms", and this class
     * registered nothing at boot — {@code ScheduledJobHeartbeatTest} is what found that.
     */
    public Optional<String> idOf(Runnable runnable) {
        Runnable registered = unwrap(runnable);
        if (registered instanceof Wrapped wrapped) {
            return Optional.of(wrapped.task);
        }
        if (registered instanceof ScheduledMethodRunnable annotated) {
            return Optional.of(id(annotated.getTarget().getClass(), annotated.getMethod()));
        }
        return Optional.empty();
    }

    /** Peels framework decorators (each holding one {@code Runnable} field) down to the runnable that was registered. */
    private static Runnable unwrap(Runnable runnable) {
        Runnable current = runnable;
        for (int depth = 0; depth < 4; depth++) {
            if (current instanceof Wrapped || current instanceof ScheduledMethodRunnable) {
                return current;
            }
            Field delegate = delegateField(current.getClass());
            if (delegate == null) {
                return current;
            }
            ReflectionUtils.makeAccessible(delegate);
            if (!(ReflectionUtils.getField(delegate, current) instanceof Runnable inner)) {
                return current;
            }
            current = inner;
        }
        return current;
    }

    private static Field delegateField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (Runnable.class.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) {
                return field;
            }
        }
        return null;
    }

    /** Every task registered so far, by id. */
    public Set<String> registered() {
        return Set.copyOf(lastRunEpochMillis.keySet());
    }

    /**
     * Registers every annotated task's gauges before the scheduler starts. Runs after all
     * singletons exist, so every {@code @Scheduled} bean has been post-processed and its tasks sit
     * on the {@link ScheduledTaskHolder}; tasks a {@code SchedulingConfigurer} adds arrive later, at
     * context refresh, and register themselves through {@link #wrap}.
     */
    @Override
    public void afterSingletonsInstantiated() {
        holders.orderedStream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(ScheduledTask::getTask)
                .forEach(task -> idOf(task.getRunnable()).ifPresent(id -> register(id, periodOf(task))));
    }

    // ---------------------------------------------------------------- the observation handler

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    /** One run ended — the task's last run moves whether it succeeded or threw. */
    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        stamp(id(context.getTargetClass(), context.getMethod()), context.getError() != null);
    }

    // ---------------------------------------------------------------- internals

    private static String id(Class<?> owner, Method method) {
        return id(owner, method.getName());
    }

    /** The period of a task the registrar holds, or {@link #UNKNOWN_PERIOD_SECONDS}. */
    static long periodOf(Task task) {
        if (task instanceof IntervalTask interval) {
            return interval.getIntervalDuration().toSeconds();
        }
        if (task instanceof CronTask cron) {
            return periodOf(CronExpression.parse(cron.getExpression()));
        }
        return UNKNOWN_PERIOD_SECONDS;
    }

    /** The gap between the next two fires — a daily cron reads as 86 400 whatever the hour. */
    static long periodOf(CronExpression expression) {
        var first = expression.next(LocalDateTime.now());
        var second = first == null ? null : expression.next(first);
        if (first == null || second == null) {
            return UNKNOWN_PERIOD_SECONDS;
        }
        return Duration.between(first, second).toSeconds();
    }

    private void register(String task, long periodSeconds) {
        lastRunEpochMillis.computeIfAbsent(task, id -> {
            var seeded = new AtomicLong(System.currentTimeMillis());
            metrics.getObject().registerScheduledJob(id, seeded, periodSeconds);
            if (periodSeconds == UNKNOWN_PERIOD_SECONDS) {
                log.warn("scheduled task {} has a trigger ScheduledJobHeartbeat cannot read, so its "
                         + "period is published as 0 and ScheduledJobStale will fire within its "
                         + "deploy allowance of not running. Teach ScheduledJobHeartbeat.periodOf the "
                         + "trigger, or register the task through heartbeat.wrap", id);
            }
            return seeded;
        });
    }

    private void stamp(String task, boolean failed) {
        // A task the registration pass did not see still gets a heartbeat — under the loud period.
        var at = lastRunEpochMillis.get(task);
        if (at == null) {
            register(task, UNKNOWN_PERIOD_SECONDS);
            at = lastRunEpochMillis.get(task);
        }
        at.set(System.currentTimeMillis());
        if (failed) {
            metrics.getObject().scheduledJobFailed(task);
        }
    }

    /** The runnable {@link #wrap} registers: stamps around the body and rethrows what it threw. */
    private final class Wrapped implements Runnable {

        private final String task;
        private final Runnable body;

        private Wrapped(String task, Runnable body) {
            this.task = task;
            this.body = body;
        }

        @Override
        public void run() {
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                stamp(task, true);
                throw e;
            }
            stamp(task, false);
        }

        @Override
        public String toString() {
            return "heartbeat(" + task + ")";
        }
    }
}
