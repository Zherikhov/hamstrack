package com.hamstrack.common.observability;

import com.hamstrack.common.testsupport.Doors;
import com.hamstrack.workspace.service.WorkspaceStorageReconciler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hamstrack.common.observability.ProductMetrics.SCHEDULED_JOB_FAILURES;
import static com.hamstrack.common.observability.ProductMetrics.SCHEDULED_JOB_LAST_RUN;
import static com.hamstrack.common.observability.ProductMetrics.SCHEDULED_JOB_PERIOD;
import static com.hamstrack.common.observability.ProductMetrics.SCHEDULED_JOB_TAG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>HD-298 rule 3 — every registered scheduled task has a heartbeat before it first runs, one
 * run advances it, and a run that throws is counted.</strong>
 *
 * <p>A {@code @SpringBootTest}, and the only one of the ops-witness pair: the predicate is about what
 * the <em>registrar</em> registered and what the <em>registry</em> exposes, neither of which a source
 * scan can see. The population is every {@link ScheduledTask} on every {@link ScheduledTaskHolder}
 * in the context — annotation-registered and registrar-registered alike — and its floor is
 * {@value #MIN_TASKS}. Parity with {@code Doors.scheduledJobs()} (the annotation half) is asserted
 * too, so a task Doors sees that the context does not register is named rather than exempted.
 *
 * <p>The throwing task is a {@code @TestConfiguration} bean whose schedule never fires on its own;
 * the test runs its registered runnable by hand, through the same {@code tasks.scheduled.execution}
 * observation the scheduler would use, and reads the counter back. That is the negative control
 * proving the handler fires, not a check that a field was set.
 *
 * <p><strong>Every conditional job is switched ON here, never exempted.</strong> A job on a
 * {@code @Conditional} class whose condition is off in this context is reported as missing against
 * Doors, naming the class; the remedy is to add its enabling property to the list below so the
 * population stays whole ({@code CspReportBudget} lives behind {@code app.csp.sink-enabled}, off by
 * default, and was the first such finding).
 *
 * <p><strong>Not seen:</strong> a task handed to a bare {@code TaskScheduler} or run by
 * {@code @Async} (neither is a {@code ScheduledTask}); the alert rule's arithmetic in Grafana.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        // Every @Scheduled bean behind a switch, switched on: the population must be whole here.
        "app.csp.sink-enabled=true"
})
class ScheduledJobHeartbeatTest {

    /** Below today's count (14: 13 annotated + the reconciler) on purpose — a floor on the scan. */
    static final int MIN_TASKS = 10;

    /** Each task is a sweep; one that takes longer than this in an empty test database is a finding. */
    static final long RUN_BOUND_SECONDS = 10;

    static final String PLANTED = ScheduledJobHeartbeat.id(ThrowingJob.class, "explode");

    static final String RECONCILER = ScheduledJobHeartbeat.id(WorkspaceStorageReconciler.class, "reconcile");

    @TestConfiguration
    static class Planted {
        @Bean
        ThrowingJob throwingJob() {
            return new ThrowingJob();
        }
    }

    /** Annotated so the ordinary mechanism registers it; scheduled a day out so only the test runs it. */
    static class ThrowingJob {
        @Scheduled(fixedDelay = 86_400_000L, initialDelay = 86_400_000L)
        void explode() {
            throw new IllegalStateException("planted by ScheduledJobHeartbeatTest");
        }
    }

    private static final String BARE_TASK = """

            Every task Spring schedules is covered by ScheduledJobHeartbeat in one of two ways: a \
            @Scheduled method through Boot's tasks.scheduled.execution observation (no edit needed), \
            or a runnable handed to the registrar through heartbeat.wrap("<Class#method>", runnable, \
            cron) - see StorageReconcileSchedule for the one call. A task that is neither has no \
            hamstrack_scheduled_job_last_run_timestamp_seconds{task}, so ScheduledJobStale can never \
            say it stopped. If a task that IS annotated appears here, the heartbeat lost its \
            ObservationRegistry hook - read ScheduledJobHeartbeat's class javadoc (branch note).""";

    @Autowired ScheduledJobHeartbeat heartbeat;
    @Autowired MeterRegistry registry;
    @Autowired ObjectProvider<ScheduledTaskHolder> holders;

    @Test
    void everyRegisteredTaskExposesItsHeartbeatBeforeItFirstRuns() {
        long processStart = ManagementFactory.getRuntimeMXBean().getStartTime() / 1000;
        long now = Instant.now().getEpochSecond();
        var ids = new TreeSet<String>();
        var offenders = new ArrayList<String>();

        for (ScheduledTask scheduled : registeredTasks()) {
            var runnable = scheduled.getTask().getRunnable();
            var id = heartbeat.idOf(runnable);
            if (id.isEmpty()) {
                offenders.add("registered outside both mechanisms: " + runnable + " ("
                        + runnable.getClass().getName() + ")");
                continue;
            }
            ids.add(id.get());
            Gauge lastRun = gauge(SCHEDULED_JOB_LAST_RUN, id.get());
            if (lastRun == null) {
                offenders.add(id.get() + " exposes no " + SCHEDULED_JOB_LAST_RUN + "{" + SCHEDULED_JOB_TAG
                        + "=\"" + id.get() + "\"} before its first run");
            } else if (lastRun.value() < processStart || lastRun.value() > now + 1) {
                offenders.add(id.get() + " last_run is " + (long) lastRun.value() + ", expected process start "
                        + processStart + " <= value <= now " + now + " (seeded at boot, never zero)");
            }
            Gauge period = gauge(SCHEDULED_JOB_PERIOD, id.get());
            if (period == null) {
                offenders.add(id.get() + " exposes no " + SCHEDULED_JOB_PERIOD);
            } else if (period.value() <= 0) {
                offenders.add(id.get() + " publishes period " + period.value() + " - a trigger the heartbeat "
                        + "cannot read; teach ScheduledJobHeartbeat.periodOf its trigger type");
            }
            if (registry.find(SCHEDULED_JOB_FAILURES).tag(SCHEDULED_JOB_TAG, id.get()).counter() == null) {
                offenders.add(id.get() + " has no " + SCHEDULED_JOB_FAILURES + " counter registered at zero");
            }
        }

        for (Doors.Job job : Doors.scheduledJobs().floor(MIN_TASKS)) {
            String expected = ScheduledJobHeartbeat.id(job.owner(), job.method().getName());
            if (!ids.contains(expected)) {
                offenders.add(expected + " carries @Scheduled (" + job.schedule() + ") and no ScheduledTask of "
                        + "it is registered in this context - a bean behind a switch that is off here (add "
                        + "its enabling property to this test's @SpringBootTest list, as app.csp.sink-enabled "
                        + "is), or a class Spring never registers (then the annotation is dead: register the "
                        + "bean or delete the method). Neither is exempt");
            }
        }
        if (!ids.contains(RECONCILER)) {
            offenders.add(RECONCILER + " is not registered through heartbeat.wrap - the one task outside the "
                    + "annotation mechanism (StorageReconcileSchedule) must be wrapped, or set "
                    + "app.storage.quota.reconcile-cron in this test if it was deliberately emptied");
        }

        System.out.println("[heartbeat] registered tasks: " + ids.size() + " (floor " + MIN_TASKS + ") | "
                + String.join(", ", ids));
        assertThat(offenders).withFailMessage(BARE_TASK + "\n\nOffenders:\n  " + String.join("\n  ", offenders)).isEmpty();
        assertThat(ids)
                .withFailMessage("Only %d scheduled task(s) are registered in this context, below the floor "
                        + "of %d - the enumeration collapsed", ids.size(), MIN_TASKS)
                .hasSizeGreaterThanOrEqualTo(MIN_TASKS);
    }

    @Test
    void oneRunAdvancesLastRunForEveryRegisteredTask() {
        var offenders = new ArrayList<String>();
        var pool = Executors.newSingleThreadExecutor();
        int ran = 0;
        try {
            for (ScheduledTask scheduled : registeredTasks()) {
                var runnable = scheduled.getTask().getRunnable();
                var id = heartbeat.idOf(runnable).orElse(null);
                if (id == null || id.equals(PLANTED)) {
                    continue;
                }
                long startMillis = System.currentTimeMillis();
                var run = pool.submit(runnable);
                try {
                    run.get(RUN_BOUND_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    run.cancel(true);
                    offenders.add(id + " did not finish within " + RUN_BOUND_SECONDS + " s against the test database");
                    continue;
                } catch (ExecutionException e) {
                    offenders.add(id + " threw when run once: " + e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                ran++;
                long afterMillis = Math.round(gauge(SCHEDULED_JOB_LAST_RUN, id).value() * 1000);
                if (afterMillis < startMillis) {
                    offenders.add(id + " ran and " + SCHEDULED_JOB_LAST_RUN + " did not advance (still "
                            + afterMillis + " ms, run started at " + startMillis + " ms) - the observation "
                            + "handler did not see the run");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(offenders).withFailMessage(BARE_TASK + "\n\nOffenders:\n  " + String.join("\n  ", offenders)).isEmpty();
        assertThat(ran)
                .withFailMessage("Only %d task(s) were run, below the floor of %d", ran, MIN_TASKS)
                .isGreaterThanOrEqualTo(MIN_TASKS);
    }

    @Test
    void aRunThatThrowsIsCountedAndStillAdvancesLastRun() {
        Runnable planted = registeredTasks().stream()
                .map(t -> t.getTask().getRunnable())
                .filter(r -> heartbeat.idOf(r).filter(PLANTED::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError(PLANTED + " is not registered - the @TestConfiguration "
                        + "bean was not picked up, so this control proves nothing"));
        Counter failures = registry.find(SCHEDULED_JOB_FAILURES).tag(SCHEDULED_JOB_TAG, PLANTED).counter();
        assertThat(failures).withFailMessage("no failures counter for %s", PLANTED).isNotNull();
        double before = failures.count();
        long startMillis = System.currentTimeMillis();

        assertThatThrownBy(planted::run)
                .as("the scheduled runnable rethrows, as it does under the scheduler")
                .hasMessageContaining("planted by ScheduledJobHeartbeatTest");

        assertThat(failures.count())
                .as("%s counts the run that threw", SCHEDULED_JOB_FAILURES)
                .isEqualTo(before + 1);
        assertThat(Math.round(gauge(SCHEDULED_JOB_LAST_RUN, PLANTED).value() * 1000))
                .as("a failed run still advances last_run - 'ran and threw' and 'did not run' have different first moves")
                .isGreaterThanOrEqualTo(startMillis);
    }

    // ------------------------------------------------------------------ enumeration

    private List<ScheduledTask> registeredTasks() {
        return holders.orderedStream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .distinct()
                .toList();
    }

    private Gauge gauge(String name, String task) {
        return registry.find(name).tag(SCHEDULED_JOB_TAG, task).gauge();
    }
}
