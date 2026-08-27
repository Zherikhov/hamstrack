package com.hamstrack.common.mail;

import com.hamstrack.common.tx.AfterCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.mail.internet.MimeMessage;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * <strong>A dead-letter row written from inside an {@code AfterCommit} effect actually lands</strong>
 * (HD-181, fix round 1 — the regression the ticket itself shipped).
 *
 * <h2>Why this needs measuring rather than reading</h2>
 * "After the commit" and "outside the transaction" are not the same instant.
 * {@code AbstractPlatformTransactionManager.processCommit} runs
 * {@code doCommit → triggerAfterCommit → triggerAfterCompletion} and only then, in a
 * {@code finally}, {@code cleanupAfterCompletion} — and it is that last step which unbinds the
 * {@code EntityManagerHolder}. So while an effect runs, the persistence context is still bound and
 * still claims to be transaction-active over an {@code EntityTransaction} that has already
 * committed. A nested {@code PROPAGATION_REQUIRED} save — which every Spring Data {@code save} is —
 * therefore <strong>joins a dead transaction</strong>: the {@code persist} is accepted, no flush
 * ever follows, the context is discarded at cleanup, and <em>nothing throws</em>. Not a rollback,
 * not a {@code TransactionRequiredException} for {@code MailService.deadLetter}'s own catch to
 * report. Zero rows, zero log lines.
 *
 * <p>Spring ships a guard written for exactly this outcome — {@code SharedEntityManagerCreator}
 * lists {@code persist} among its {@code transactionRequiringMethods}, commented <em>"Otherwise, the
 * operation would get accepted but remain unflushed"</em> — and it is defeated here, because it
 * passes when {@code isActualTransactionActive()} is true and that flag is cleared only at cleanup,
 * i.e. after every callback. The one check designed to catch this is disarmed by the one window that
 * produces it. Hence a test rather than a reader.
 *
 * <p>What is at stake is not bookkeeping: the {@code failed_email} row is the only thing standing
 * between an SMTP outage and a user who can never finish signing up, and the caller-runs path this
 * probe reproduces is reached under precisely the load an outage produces.
 *
 * <h2>How the probe reproduces it</h2>
 * The <em>unproxied</em> {@link MailService} is driven from inside an {@link AfterCommit} effect
 * with SMTP forced to fail. Unproxied is what makes it faithful: when {@code mailExecutor}'s queue
 * is full its caller-runs policy turns the {@code @Async} dispatch into an inline send on the
 * committing thread, which is this, exactly. The {@code @Async} hop would otherwise move the send
 * to a pool thread with nothing bound to it — the one arrangement in which the bug cannot happen.
 *
 * <p>Measured before the fix: <strong>0</strong> rows from inside the effect, <strong>1</strong> for
 * the same failure outside a transaction. Both directions are kept below, because a single "1" says
 * nothing — the control is what makes the effect the variable.
 */
@SpringBootTest(properties = {
        "app.mail.critical.max-attempts=3",
        "app.mail.critical.retry-backoff-ms=0",
        "app.base-url=https://app.example.com",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class DeadLetterInsideAfterCommitTest {

    @Autowired MailService mailService;
    @Autowired FailedEmailRepository failedEmailRepository;
    @Autowired TransactionTemplate txTemplate;

    @MockitoBean JavaMailSender mailSender;

    @BeforeEach
    void failEverySend() {
        failedEmailRepository.deleteAll();
        Mockito.reset(mailSender);
        Mockito.when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new org.springframework.mail.javamail.JavaMailSenderImpl().createMimeMessage());
        Mockito.doThrow(new MailSendException("injected SMTP failure"))
                .when(mailSender).send(any(MimeMessage.class));
        Mockito.doThrow(new MailSendException("injected SMTP failure"))
                .when(mailSender).send(any(SimpleMailMessage.class));
    }

    // ================================================================ the seal

    /**
     * The regression itself. Fails with <strong>0</strong> the moment the dead-letter write goes
     * back to anything that merely joins what is bound — a {@code failedEmailRepository.save} inline
     * in {@code MailService}, a {@code @Transactional} private method on {@code MailService} (which
     * is proxy-applied and so is not applied at all), or {@link FailedEmailWriter#write} relaxed
     * from {@code REQUIRES_NEW}.
     */
    @Test
    void aCriticalSendFailingInsideAnEffectStillLeavesItsDeadLetterRow() {
        var address = address("inside-effect");

        txTemplate.executeWithoutResult(status ->
                AfterCommit.run("a critical send, dispatched caller-runs from inside an effect",
                        () -> unproxied().sendVerificationEmail(address, "token-" + UUID.randomUUID())));

        assertThat(rowsFor(address))
                .as("""
                    NO dead-letter row for a critical email that failed inside an AfterCommit \
                    effect — and nothing threw, nothing logged, which is why this needs a test at \
                    all. During afterCommit the persistence context is STILL BOUND and still marked \
                    transaction-active while its EntityTransaction is already committed, so a \
                    PROPAGATION_REQUIRED save joins a DEAD transaction: persist is accepted, no \
                    flush ever follows, the context is discarded at cleanup, and no exception is \
                    raised for MailService.deadLetter's catch to report. Even Spring's own guard \
                    for this (SharedEntityManagerCreator's transactionRequiringMethods) passes, \
                    because it tests isActualTransactionActive() and that flag is cleared only in \
                    cleanupAfterCompletion — after every callback.

                    The write must therefore be REQUIRES_NEW in a bean of its own \
                    (FailedEmailWriter), which suspends whatever is bound and commits on a \
                    transaction it owns. It cannot be a private @Transactional method on \
                    MailService: @Transactional is proxy-applied, so a same-class call bypasses it \
                    entirely and reinstates this bug in silence.

                    This row is the only thing between an SMTP outage and a user who can never \
                    finish signing up, and the caller-runs path that reaches this window is reached \
                    under exactly the load an outage produces.

                    MORE THAN ONE row is the opposite failure and does not belong to this write at \
                    all: it means the EFFECT was delivered twice, so the send was attempted twice. \
                    AfterCommitTest is where that is diagnosed.""")
                .isEqualTo(1);
    }

    /**
     * The control, and it is not decoration: without it, a run of the test above that passes proves
     * only that <em>something</em> wrote a row, and a run that fails could equally mean the SMTP
     * injection stopped working or that critical mail stopped dead-lettering at all. Same mailer,
     * same injected failure, no transaction anywhere — one row. The effect is the only variable
     * between the two.
     */
    @Test
    void theSameFailureOutsideAnyTransactionAlsoLeavesExactlyOneRow() {
        var address = address("no-transaction");

        unproxied().sendVerificationEmail(address, "token-" + UUID.randomUUID());

        assertThat(rowsFor(address))
                .as("the probe's own premise: this mailer, with this injected SMTP failure, "
                    + "dead-letters. If this is 0 the test above is measuring a broken probe "
                    + "rather than a broken write")
                .isEqualTo(1);
    }

    /**
     * <strong>The structural half of the same seal</strong>, kept because the behavioural one above
     * reports a missing row and this one reports the <em>reason</em>. The two failure modes it names
     * are the two ways this has actually been written wrongly: the write living on
     * {@code MailService} (where {@code @Transactional} is a same-class call and therefore a no-op),
     * and the propagation being "simplified" back to the default.
     */
    @Test
    void theDeadLetterWriteIsANeighbourBeanWithATransactionOfItsOwn() throws Exception {
        var write = FailedEmailWriter.class.getDeclaredMethod("write", FailedEmail.class);
        var tx = write.getAnnotation(Transactional.class);

        assertThat(tx)
                .as("FailedEmailWriter.write must carry @Transactional — without one it has no "
                    + "transaction of its own and joins whatever is bound, which inside an "
                    + "AfterCommit effect is an already-committed one that discards the row")
                .isNotNull();
        assertThat(tx.propagation())
                .as("REQUIRES_NEW is the whole point: it SUSPENDS whatever is bound (dead or "
                    + "alive), borrows a clean EntityManager and connection for one INSERT and "
                    + "commits it. REQUIRED joins the dead transaction and loses the row in "
                    + "silence; it also means a later rollback would unwrite the record of a mail "
                    + "that really was attempted and really did fail.")
                .isEqualTo(Propagation.REQUIRES_NEW);

        assertThat(Arrays.stream(MailService.class.getDeclaredFields())
                .map(f -> f.getType().getSimpleName()))
                .as("MailService must reach the dead-letter write through the FailedEmailWriter "
                    + "BEAN and must not hold the repository itself. A save called from inside "
                    + "MailService — or from a private @Transactional method on it, which is a "
                    + "same-class call and so is never advised — is exactly the write that "
                    + "vanishes inside an AfterCommit effect.")
                .contains("FailedEmailWriter")
                .doesNotContain("FailedEmailRepository");
    }

    // ================================================================ probe machinery

    /**
     * The real bean behind the {@code @Async} proxy. Calling through the proxy would hand the send
     * to {@code mailExecutor}, i.e. to a thread with nothing bound to it — the one arrangement in
     * which this bug is unreachable, and therefore the one that would make this file pass while
     * proving nothing.
     */
    private MailService unproxied() {
        return AopTestUtils.getTargetObject(mailService);
    }

    private long rowsFor(String address) {
        return failedEmailRepository.findAll().stream()
                .filter(row -> address.equals(row.getRecipient()))
                .count();
    }

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }
}
