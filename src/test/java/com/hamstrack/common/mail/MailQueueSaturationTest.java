package com.hamstrack.common.mail;

import com.hamstrack.auth.dto.ForgotPasswordRequest;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.auth.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * <strong>A full mail queue drops the message; it does not send it on the request thread</strong>
 * (HD-208).
 *
 * <h2>The behaviour this replaces</h2>
 * The pool ran {@code CallerRunsPolicy}, so once the queue filled, an "async" dispatch became a
 * synchronous SMTP send on the calling thread — with connect 5 s / read 10 s and three attempts
 * with backoff for critical mail, up to ~34 s of a Tomcat worker per request, from
 * {@code POST /api/auth/register} among others (unauthenticated, 15/min/IP, critical mail). It was
 * self-amplifying: a slower mail host fills the queue faster, which puts more workers on SMTP.
 *
 * <h2>What is asserted, and why not the wall clock</h2>
 * <strong>The property, not the timing.</strong> A test that measures milliseconds and asserts two
 * numbers are close is flaky by construction, and a flaky test here is worse than none because it
 * will be muted. So the assertion is the thing that <em>causes</em> the timing: with the queue
 * saturated, no further SMTP round trip is attempted at all — the invocation count on the mail
 * sender does not move — and the message that could not be queued leaves a {@code failed_email}
 * row instead.
 *
 * <p>The wall clock appears only as a loose upper bound, and only in the shape that excludes a
 * send rather than comparing two branches: the blocking mock holds its one worker for the whole
 * test, so a caller-runs regression would park the calling thread for tens of seconds, and five
 * seconds is far above anything the real path can cost while being far below that.
 *
 * <h2>The forgot-password timing oracle, which is the same property read from outside</h2>
 * With a full queue, a <em>known</em> address cost an inline send with retries while an
 * <em>unknown</em> one returned immediately, because the unknown branch does no work at all — a
 * several-second difference between "this account exists" and "it does not", on an endpoint whose
 * entire design is that the two are indistinguishable. Asserting that neither branch reaches SMTP
 * is a stronger statement than asserting their durations are similar, and it does not depend on a
 * clock.
 */
@SpringBootTest(properties = {
        // One worker, one queue slot: the third dispatch is refused. shutdown-drain-seconds is
        // lowered only so tearing this context down does not wait fifteen seconds on the blocked
        // worker.
        "app.mail.async.core-pool-size=1",
        "app.mail.async.max-pool-size=1",
        "app.mail.async.queue-capacity=1",
        "app.mail.async.shutdown-drain-seconds=1",
        "app.mail.critical.max-attempts=1",
        "app.mail.critical.retry-backoff-ms=0",
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MailQueueSaturationTest {

    @Autowired MailService mailService;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired FailedEmailRepository failedEmailRepository;
    @Autowired @Qualifier("mailExecutor") TaskExecutor mailExecutor;

    @MockitoBean JavaMailSender mailSender;

    /** Released in teardown. Bounded so a regression fails the test rather than hanging CI. */
    private final CountDownLatch release = new CountDownLatch(1);
    /** Counted down by the first send that actually reaches the mock, i.e. by the pool thread. */
    private final CountDownLatch occupied = new CountDownLatch(1);

    @BeforeEach
    void saturate() throws InterruptedException {
        failedEmailRepository.deleteAll();
        Mockito.doAnswer(invocation -> {
            occupied.countDown();
            // Never completes during the test: the single worker stays busy, so everything after
            // the first dispatch is either queued or refused.
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));

        // 1: taken by the only worker and blocked there.
        mailService.sendPasswordResetEmail(address("occupier"), token());
        assertThat(occupied.await(10, TimeUnit.SECONDS))
                .as("premise: the pool's one worker really is inside a send")
                .isTrue();
        // 2: fills the only queue slot.
        mailService.sendPasswordResetEmail(address("queued"), token());
    }

    /**
     * Release the worker <em>and wait for the pool to go quiet</em>. Returning while the queued
     * message is still in flight would let it land on the next test's freshly reset mock and move
     * an invocation count that test reasons about — the kind of cross-test leak that shows up as
     * one flaky method and gets blamed on the wrong thing.
     */
    @AfterEach
    void releaseAndDrain() {
        release.countDown();
        var pool = ((ThreadPoolTaskExecutor) mailExecutor).getThreadPoolExecutor();
        await().atMost(Duration.ofSeconds(20))
                .until(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
    }

    // ================================================================ the dispatch itself

    @Test
    void aRefusedCriticalDispatchIsDeadLetteredAndNeverSentOnTheCallingThread() {
        var refused = address("refused");

        var elapsed = timed(() -> mailService.sendPasswordResetEmail(refused, token()));

        // The property. Under CallerRunsPolicy this call performed the send itself, so the
        // invocation count would have moved — and this thread would still be inside the mock.
        verify(mailSender, Mockito.times(1)).send(any(SimpleMailMessage.class));

        var rows = failedEmailRepository.findAll();
        assertThat(rows)
                .as("dropping is only better than backpressure because the drop is written down. "
                    + "A refused account-critical message with no row is the loss CallerRunsPolicy "
                    + "was chosen to prevent, arriving by another door")
                .hasSize(1);
        var row = rows.getFirst();
        assertThat(row.getRecipient()).isEqualTo(refused);
        assertThat(row.getEmailType()).isEqualTo("PASSWORD_RESET");
        assertThat(row.getAttempts())
                .as("attempts = 0 is half of how the table says WE NEVER TRIED, as opposed to the "
                    + "we-tried-and-failed rows MailService writes. A re-drive treats them "
                    + "differently: a message that never reached SMTP may well succeed first time")
                .isZero();
        assertThat(row.getLastError())
                .as("and the other half, so the distinction survives being read by eye in psql")
                .startsWith("NEVER ATTEMPTED")
                .as("""
                        the Reason CONSTANT, not only its sentence. The EmailFailures alert tells a \
                        paged operator to separate a deploy burst (SHUTDOWN_RESIDUE) from a \
                        saturated pool (QUEUE_FULL) by this token and to GROUP BY \
                        left(last_error, 40) — an alert that names a string nothing writes sends \
                        them looking for something that is not there, which is worse than saying \
                        nothing.""")
                .contains("[QUEUE_FULL]")
                .as("and the sentence stays, for whoever is reading one row rather than a hundred")
                .contains("queue was full");

        assertThat(elapsed)
                .as("a loose ceiling that excludes a send, NOT a comparison between two branches. "
                    + "The mock holds its worker for the whole test, so a caller-runs regression "
                    + "parks this thread for tens of seconds; the real path is microseconds")
                .isLessThan(Duration.ofSeconds(5));
    }

    // ================================================================ and what it means for the oracle

    /**
     * Both branches of the enumeration-safe endpoint, under saturation.
     *
     * <p>The known branch does strictly more work than the unknown one — it writes a
     * {@code password_resets} row and then a dead-letter row — and that is fine and is not what an
     * oracle is made of. What made it an oracle was that the extra work was an SMTP conversation
     * with retries. Neither branch attempts one here, which is asserted directly.
     */
    @Test
    void withTheQueueFullNeitherAKnownNorAnUnknownAddressReachesSmtpOnTheRequestThread() {
        var known = address("known");
        userRepository.save(activeUser(known));

        var knownElapsed = timed(() -> authService.forgotPassword(new ForgotPasswordRequest(known)));
        var unknownElapsed = timed(() ->
                authService.forgotPassword(new ForgotPasswordRequest(address("unknown"))));

        verify(mailSender, Mockito.times(1))
                .send(any(SimpleMailMessage.class));

        // The known branch is the one that owed a mail, so it is the one that must have left an
        // artefact. The unknown branch owes nothing and writes nothing — which is the endpoint's
        // whole contract and is unchanged.
        await().atMost(Duration.ofSeconds(10)).until(() -> failedEmailRepository.count() == 1);
        assertThat(failedEmailRepository.findAll().getFirst().getRecipient()).isEqualTo(known);

        assertThat(List.of(knownElapsed, unknownElapsed))
                .as("the loose ceiling again, applied to each branch separately rather than to "
                    + "their difference. Comparing the two durations to each other is the flaky "
                    + "test this deliberately is not: what is claimed is that neither can contain "
                    + "an SMTP round trip, and the mock's held worker is what makes 5s prove it")
                .allSatisfy(d -> assertThat(d).isLessThan(Duration.ofSeconds(5)));
    }

    // ================================================================ fixture

    private static Duration timed(Runnable action) {
        long start = System.nanoTime();
        assertThatCode(action::run)
                .as("a refusal is recorded, never raised at the caller: every mailer is dispatched "
                    + "from an AfterCommit effect where a throw would be swallowed anyway, and for "
                    + "forgot-password a throw would additionally be an oracle of its own")
                .doesNotThrowAnyException();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private static String token() {
        return "token-" + UUID.randomUUID();
    }

    private static User activeUser(String email) {
        var u = new User();
        u.setEmail(email);
        u.setDisplayName("Queue saturation");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return u;
    }
}
