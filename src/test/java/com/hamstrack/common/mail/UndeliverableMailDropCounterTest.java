package com.hamstrack.common.mail;

import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.MailAsyncProperties;
import com.hamstrack.common.mail.UndeliverableMail.Reason;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.observability.ProductMetrics.MailDropReason;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.repository.WorkspaceStorageUsageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <strong>Every branch of {@link UndeliverableMail} that ends without a row counts why</strong>
 * (HD-234) — the behavioural half of {@code OpsWitnessContractTest}'s drop-site scan, which sees
 * that the call is inside the branch but not that the branch is reachable or that the reason is
 * the right one. Plain JUnit with a {@link SimpleMeterRegistry}: the question is about six branches
 * of one class, and a seal that costs a context start is a seal somebody eventually moves.
 */
class UndeliverableMailDropCounterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProductMetrics metrics = new ProductMetrics(registry, mock(UserRepository.class),
            mock(WorkspaceRepository.class), mock(ProjectRepository.class), mock(IssueRepository.class),
            mock(WorkspaceStorageUsageRepository.class));
    private final FailedEmailWriter writer = mock(FailedEmailWriter.class);
    private final MailAsyncProperties properties = mock(MailAsyncProperties.class);
    private UndeliverableMail undeliverable;

    @BeforeEach
    void setUp() {
        when(properties.deadLetter()).thenReturn(new MailAsyncProperties.DeadLetter(7, 1));
        undeliverable = new UndeliverableMail(writer, metrics, properties);
    }

    @Test
    void bestEffortMailCountsBestEffortAndGetsNoRow() {
        assertThat(undeliverable.record(task(EmailType.INVITE), Reason.QUEUE_FULL)).isFalse();

        assertThat(dropped(MailDropReason.BEST_EFFORT)).isEqualTo(1);
        verify(writer, never()).write(any());
    }

    @Test
    void aContendedPoolCountsPoolContended() {
        when(writer.poolIsStarved()).thenReturn(true);

        assertThat(undeliverable.record(task(EmailType.VERIFICATION), Reason.QUEUE_FULL)).isFalse();

        assertThat(dropped(MailDropReason.POOL_CONTENDED)).isEqualTo(1);
        assertThat(dropped(MailDropReason.HOURLY_CAP)).as("the pool is asked first and spends no budget").isZero();
        verify(writer, never()).write(any());
    }

    @Test
    void theHourlyCapCountsHourlyCapOnceTheBudgetIsSpent() {
        assertThat(undeliverable.record(task(EmailType.VERIFICATION), Reason.QUEUE_FULL))
                .as("the first row of the hour is within a cap of 1").isTrue();
        assertThat(undeliverable.record(task(EmailType.PASSWORD_RESET), Reason.QUEUE_FULL)).isFalse();

        assertThat(dropped(MailDropReason.HOURLY_CAP)).isEqualTo(1);
        verify(writer, times(1)).write(any());
    }

    @Test
    void aFailedWriteCountsWriteFailed() {
        doThrow(new IllegalStateException("database gone")).when(writer).write(any());

        assertThat(undeliverable.record(task(EmailType.VERIFICATION), Reason.POOL_SHUT_DOWN)).isFalse();

        assertThat(dropped(MailDropReason.WRITE_FAILED)).isEqualTo(1);
    }

    @Test
    void recordAllCountsBestEffortPerMessageAndWriteFailedPerRow() {
        doThrow(new IllegalStateException("database gone")).when(writer).writeAll(any());

        int written = undeliverable.recordAll(
                List.of(task(EmailType.INVITE), task(EmailType.VERIFICATION), task(EmailType.PASSWORD_RESET)),
                Reason.SHUTDOWN_RESIDUE);

        assertThat(written).isZero();
        assertThat(dropped(MailDropReason.BEST_EFFORT)).isEqualTo(1);
        assertThat(dropped(MailDropReason.WRITE_FAILED)).as("one per critical row the batch lost").isEqualTo(2);
    }

    @Test
    void recordAllWithNothingCriticalCountsOnlyBestEffortAndWritesNothing() {
        assertThat(undeliverable.recordAll(List.of(task(EmailType.INVITE)), Reason.SHUTDOWN_RESIDUE)).isZero();

        assertThat(dropped(MailDropReason.BEST_EFFORT)).isEqualTo(1);
        assertThat(dropped(MailDropReason.WRITE_FAILED)).isZero();
        verify(writer, never()).writeAll(any());
    }

    private double dropped(MailDropReason reason) {
        var counter = registry.find("hamstrack.mail.dead_letter_skipped").tag("reason", reason.tag()).counter();
        return counter == null ? 0 : counter.count();
    }

    private static MailTask task(EmailType type) {
        return new MailTask(type, "person@example.com", "subject", () -> { });
    }
}
