package com.hamstrack.common.mail;

import com.hamstrack.common.observability.ProductMetrics.EmailType;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>A rule about what a log line may SAY has to be checked against what it may CARRY.</strong>
 *
 * <p>Every {@code log.*} on the mail-failure path passes the mail kind and
 * {@link MailAddresses#domainOf(String) the domain}, never the address — and the address reached a
 * shipped log anyway, as an <em>argument</em>. {@code ThreadPoolTaskExecutor.execute} wraps a
 * rejection as {@code TaskRejectedException(executor, task, cause)}, whose message ends in
 * {@code task.toString()}; {@link MailTask} is a record, so the generated {@code toString} rendered
 * the recipient and the subject. {@link MailDispatcher} rethrows that exception whenever nothing
 * durable was written — every best-effort refusal, and every critical message over the hourly cap —
 * and {@code AfterCommit.runQuietly} logs it with its stack trace.
 *
 * <p>So a grep over format strings certifies a clean pass over this class of leak. This file
 * asserts the other half: the redaction on the object, and the message the framework actually
 * builds out of it.
 *
 * <p>{@code MailExecutorShutdownRefusalTest} proves the same property end to end, through the real
 * dispatcher and the real logger. This one is here because it fails in milliseconds and says
 * exactly which method is wrong.
 */
class MailTaskRedactionTest {

    private static final String LOCAL_PART = "victim.reads.this";
    private static final String ADDRESS = LOCAL_PART + "@example.test";
    private static final String SUBJECT = "Reset your Hamstrack password";

    private final MailTask task =
            new MailTask(EmailType.PASSWORD_RESET, ADDRESS, SUBJECT, () -> {});

    @Test
    void toStringCarriesTheKindAndTheDomainAndNothingElse() {
        assertThat(task.toString())
                .as("the local part is what makes an address personal data, and this string is one "
                    + "interpolation away from a retained, shipped log")
                .doesNotContain(LOCAL_PART)
                .doesNotContain(ADDRESS)
                .as("the subject is not personal data by itself, but it has a home "
                    + "(failed_email.subject) and no business in a log line")
                .doesNotContain(SUBJECT)
                .as("and it still has to be worth reading: which kind of mail, and roughly where "
                    + "it was going. A redaction that empties the string loses the loss a second "
                    + "way")
                .contains("PASSWORD_RESET")
                .contains("example.test");
    }

    /**
     * The exact mechanism, not a re-statement of the method above: this is the string Spring builds
     * and hands to the logger. It is asserted through the framework's own constructor so that a
     * future Spring version which formats the message differently — or a change that made
     * {@code MailDispatcher} submit rather than execute — is not silently covered by a test of
     * {@code toString} alone.
     */
    @Test
    void theRejectionSpringBuildsOutOfItIsRedactedToo() {
        Executor executor = Runnable::run;
        var rejected = new TaskRejectedException(executor, task,
                new RejectedExecutionException("queue full"));

        assertThat(rejected.getMessage())
                .as("this message is logged verbatim, with its stack trace, by "
                    + "AfterCommit.runQuietly on every refusal that wrote nothing down")
                .doesNotContain(LOCAL_PART)
                .doesNotContain(ADDRESS)
                .doesNotContain(SUBJECT)
                .contains("PASSWORD_RESET");
    }

    @Test
    void aMalformedRecipientDoesNotLeakItselfAsTheDomain() {
        var malformed = new MailTask(EmailType.VERIFICATION, "not-an-address", "s", () -> {});

        assertThat(malformed.toString())
                .as("MailAddresses.domainOf answers \"unknown\" rather than echoing the input, so "
                    + "a value with no @ cannot reach the log by being unparseable")
                .doesNotContain("not-an-address")
                .contains("unknown");
    }
}
