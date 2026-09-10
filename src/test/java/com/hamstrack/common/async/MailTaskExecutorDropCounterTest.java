package com.hamstrack.common.async;

import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.mail.MailTask;
import com.hamstrack.common.mail.UndeliverableMail;
import com.hamstrack.common.mail.UndeliverableMail.Reason;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.observability.ProductMetrics.MailDropReason;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.repository.WorkspaceStorageUsageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * <strong>The two drops the shutdown drain cannot dead-letter are counted</strong> (HD-234): a send
 * a worker was already inside, and a task on the pool that is not a {@link MailTask}. Neither can
 * get a {@code failed_email} row, which is exactly why the counter is their only witness. The queued
 * {@link MailTask} goes to {@link UndeliverableMail#recordAll} as before — that path counts inside
 * {@code UndeliverableMail} and is sealed there.
 */
class MailTaskExecutorDropCounterTest {

    @Test
    void theDrainCountsInterruptedSendsAndForeignTasks() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new ProductMetrics(registry, mock(UserRepository.class), mock(WorkspaceRepository.class),
                mock(ProjectRepository.class), mock(IssueRepository.class), mock(WorkspaceStorageUsageRepository.class));
        var undeliverable = mock(UndeliverableMail.class);
        var executor = new MailTaskExecutor(undeliverable, metrics, 0);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();

        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // A foreign runnable holding the one worker: in flight when the drain expires.
        executor.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, TimeUnit.SECONDS)).as("the worker is inside the first task").isTrue();
        executor.execute(() -> { });                                            // foreign, queued
        var queuedMail = new MailTask(EmailType.VERIFICATION, "person@example.com", "subject", () -> { });
        executor.execute(queuedMail);                                           // mail, queued

        executor.shutdown();
        release.countDown();

        assertThat(dropped(registry, MailDropReason.IN_FLIGHT_INTERRUPTED))
                .as("up to one send was in progress when the drain expired").isEqualTo(1);
        assertThat(dropped(registry, MailDropReason.FOREIGN_TASK))
                .as("one abandoned task was not a MailTask").isEqualTo(1);
        verify(undeliverable).recordAll(List.of(queuedMail), Reason.SHUTDOWN_RESIDUE);
    }

    private static double dropped(SimpleMeterRegistry registry, MailDropReason reason) {
        var counter = registry.find("hamstrack.mail.dead_letter_skipped").tag("reason", reason.tag()).counter();
        return counter == null ? 0 : counter.count();
    }
}
