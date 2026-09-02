package com.hamstrack.common.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamstrack.common.async.MailTaskExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>What the shutdown drain does not manage to send is written down, per message</strong>
 * (HD-207).
 *
 * <h2>The loss</h2>
 * {@code setWaitForTasksToCompleteOnShutdown(true)} keeps the queue and waits
 * {@code awaitTerminationSeconds} for it; when that expires, a plain {@code ThreadPoolTaskExecutor}
 * abandons everything still queued as a block and Spring logs one line naming no message, no
 * recipient and no count. Since HD-181 the dispatch happens <em>after</em> the commit, so by the
 * time a message sits in that queue its {@code password_resets} / {@code email_verifications} /
 * {@code workspace_invites} row is committed and the user has already been told to check their
 * inbox. A rolling deploy during any backlog therefore stranded users mid-recovery, silently, with
 * a database that said the mail was sent.
 *
 * <h2>Why the fix is not "make the queue smaller"</h2>
 * The ticket offered that as an alternative and it is the wrong half of the trade: a queue exists to
 * absorb a burst, and the drain window exists to bound how long a container takes to stop. Sizing
 * the first to the second would dead-letter a burst of signups that a healthy host clears in a
 * second. So the queue keeps its size and its residue becomes durable — which is also what makes
 * this one mechanism with HD-208's refusal path rather than two.
 *
 * <h2>Configured small on purpose</h2>
 * One worker, held inside a send; a drain of one second; a mix of account-critical and best-effort
 * mail queued behind it. The numbers are the smallest that reproduce the shape, and the shape is
 * what is asserted — no assertion here counts messages except the one that has to, which is the
 * warning's own count.
 */
@SpringBootTest(properties = {
        "app.mail.async.core-pool-size=1",
        "app.mail.async.max-pool-size=1",
        "app.mail.async.queue-capacity=10",
        "app.mail.async.shutdown-drain-seconds=1",
        "app.mail.critical.max-attempts=1",
        "app.mail.critical.retry-backoff-ms=0",
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@DirtiesContext
class MailShutdownResidueTest {

    @Autowired MailService mailService;
    @Autowired FailedEmailRepository failedEmailRepository;
    @Autowired @Qualifier("mailExecutor") TaskExecutor mailExecutor;
    @Autowired ConfigurableListableBeanFactory beanFactory;

    @MockitoBean JavaMailSender mailSender;

    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch occupied = new CountDownLatch(1);

    private final ListAppender<ILoggingEvent> executorLog = new ListAppender<>();

    @BeforeEach
    void resetState() {
        failedEmailRepository.deleteAll();
        executorLog.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MailTaskExecutor.class))
                .addAppender(executorLog);
    }

    /**
     * Called from the test that needs it rather than from {@code @BeforeEach}: that test shuts the
     * pool down irreversibly, and a setup method that dispatched into an already-dead pool would
     * make the sibling below fail for a reason that has nothing to do with what it asserts.
     */
    private void occupyTheOnlyWorker() throws InterruptedException {
        Mockito.doAnswer(invocation -> {
            occupied.countDown();
            try {
                // Held past the drain. shutdownNow() interrupts this thread; swallowing the
                // interrupt (rather than failing the send) keeps the in-flight message out of the
                // row count, so what the assertions see is exactly the RESIDUE.
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(mailSender).send(Mockito.any(SimpleMailMessage.class));

        mailService.sendPasswordResetEmail(address("in-flight"), token());
        assertThat(occupied.await(10, TimeUnit.SECONDS))
                .as("premise: the pool's one worker really is inside a send, so nothing else can "
                    + "leave the queue")
                .isTrue();
    }

    @AfterEach
    void releaseAndDetach() {
        release.countDown();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MailTaskExecutor.class))
                .detachAppender(executorLog);
        executorLog.stop();
    }

    @Test
    void theQueueThatTheDrainCannotFlushIsDeadLetteredAndCounted() throws InterruptedException {
        occupyTheOnlyWorker();

        var stranded = List.of(address("stranded-1"), address("stranded-2"), address("stranded-3"));
        stranded.forEach(to -> mailService.sendPasswordResetEmail(to, token()));
        // A best-effort message in the same residue: it must NOT get a row, and must not stop the
        // critical ones from getting theirs. The fork is MailService.isCritical, shared with the
        // retry path so a new EmailType lands on one side exactly once.
        mailService.sendWorkspaceInviteEmail(address("best-effort"), "Acme", token());

        ((ThreadPoolTaskExecutor) mailExecutor).shutdown();

        var rows = failedEmailRepository.findAll();
        assertThat(rows.stream().map(FailedEmail::getRecipient))
                .as("""
                        every account-critical message the drain could not send must leave a \
                        durable record. Before HD-207 these were abandoned as a block, and the only \
                        trace was Spring's "Timed out while waiting for executor to terminate" — \
                        no recipient, no type, not even a count. Their password_resets rows are \
                        COMMITTED and their users have already been told to check their inbox.""")
                .containsExactlyInAnyOrderElementsOf(stranded);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getAttempts())
                    .as("attempts = 0: this message was never tried, as opposed to the "
                        + "tried-and-failed rows MailService writes. A re-drive treats them "
                        + "differently")
                    .isZero();
            assertThat(row.getLastError())
                    .startsWith("NEVER ATTEMPTED")
                    .as("""
                            the Reason constant, because this is the one the EmailFailures alert \
                            names by hand: "a single burst at a deploy timestamp, all \
                            never_attempted, all NEVER ATTEMPTED [SHUTDOWN_RESIDUE] in \
                            last_error, is that -- it is real but it is not an outage". That \
                            sentence was true of nothing until the token was written, and the \
                            paged operator could not separate the two never-tried causes at all.""")
                    .contains("[SHUTDOWN_RESIDUE]")
                    .contains("shutdown drain expired");
        });

        // Spring's own "Timed out while waiting for executor 'mailExecutor' to terminate" lands on
        // THIS logger too — ExecutorConfigurationSupport takes LogFactory.getLog(getClass()), and
        // getClass() is MailTaskExecutor. That line is the one HD-207 was filed about: it is all an
        // operator used to get, and it names no message, no recipient and no count. It is still
        // there; what is asserted is the line that now stands next to it.
        var counted = executorLog.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("still queued"))
                .toList();
        assertThat(counted)
                .as("""
                        the warning has to NAME HOW MANY. It is emitted before the write is \
                        attempted and not after, deliberately: a shutdown is exactly when the \
                        database may already be gone, and when it is, this line is the entire \
                        record. "Up to the queue capacity" was all the old one really said.""")
                .hasSize(1);
        assertThat(counted.getFirst())
                .contains("4 email(s) still queued")
                .contains("COMMITTED");
    }

    /**
     * <strong>The residue write happens during shutdown, so the writer must still be alive when it
     * does</strong> — and that is held by a bean dependency, not by luck.
     *
     * <p>{@code AsyncConfig} takes {@link UndeliverableMail} as a constructor argument of the
     * {@code mailExecutor} bean precisely so the container records the edge and destroys the
     * executor <em>first</em>; the writer, its repository and the {@code EntityManagerFactory} then
     * follow. Switch that injection to an {@code ObjectProvider} or a lookup and the edge
     * disappears: destruction order falls back to reverse registration, the {@code DataSource} can
     * go first, and the batch write fails at the one moment when nobody is watching — leaving only
     * the count in the WARN. Nothing about the code would look wrong, and the test above would
     * still pass, because it shuts the pool down by hand while the context is fully alive.
     */
    @Test
    void theExecutorIsDestroyedBeforeTheWriterItNeedsAtShutdown() {
        assertThat(beanFactory.getDependentBeans("undeliverableMail"))
                .as("mailExecutor must be registered as DEPENDENT on undeliverableMail, which is "
                    + "what makes the container destroy it first. If this is empty, the executor "
                    + "stopped taking the recorder as a constructor argument and the shutdown "
                    + "residue write is now racing the datasource's own destruction")
                .contains("mailExecutor");
    }

    // ================================================================ fixture

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private static String token() {
        return "token-" + UUID.randomUUID();
    }
}
