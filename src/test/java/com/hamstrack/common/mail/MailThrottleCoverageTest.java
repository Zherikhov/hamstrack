package com.hamstrack.common.mail;

import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.MailAsyncProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailOutcome;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * <strong>The mail axis of {@code ThrottleCoverageTest}: every kind of outbound mail is either
 * recipient-throttled or exempt with a written reason</strong> (HD-190 section 9.3).
 *
 * <h2>Why a second seal, on a different axis</h2>
 * {@code ThrottleCoverageTest} seals the set of throttled <em>paths</em>, and its failure message
 * says "do not add a check inside the service, which is the line the next endpoint forgets". That
 * sentence is right about path-shaped, principal-keyed budgets and <strong>wrong about
 * victim-keyed ones</strong>, which is why the invitation ceilings deliberately sit inside
 * {@code WorkspaceService.inviteMember}: a recipient-keyed refusal spent in an interceptor would
 * answer a cross-tenant question to a non-member — a 429 where this project requires a 404. No new
 * path pattern was registered, so that test stays green and is not weakened; the structural
 * guarantee it would otherwise have provided lives here instead.
 *
 * <p>The right generalisation of <em>"a throttle is earned by the work a handler does"</em> is that
 * the work here is <em>sending mail to an address the caller chose</em>. So the seal is on the
 * <strong>mailer</strong>, not on the URL: a fourth kind of outbound mail fails one test that names
 * what to do, at the commit that adds it.
 *
 * <h2>What is asserted, and what makes it more than bookkeeping</h2>
 * The mailer-to-type mapping is not a hand-written list — it is <em>observed</em>. Every public
 * {@code send*} method on {@link MailService} is invoked against a stub {@link JavaMailSender} and a
 * mock {@link ProductMetrics}, and the {@link EmailType} it reports is read back off
 * {@code metrics.emailSent(...)}. A fourth mailer that forgets to declare a type therefore fails
 * because it records nothing, not because somebody forgot to update a constant.
 *
 * <p>{@link RecipientMailThrottle#throttledTypes()} is {@code public} for this file and for no
 * other caller. It is asked of the assembled application — the policy map is built from every
 * {@code MailThrottlePolicy} bean in the context — so a policy bean that is written but never
 * registered does not count as coverage.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class MailThrottleCoverageTest {

    /**
     * Kinds of mail that may reach a stranger's address with no recipient-keyed ceiling in front of
     * them.
     *
     * <p><strong>These two are a documented open gap, not a decision that they are safe.</strong>
     * Verification and password-reset mail are today bounded only by the per-IP auth budget on
     * {@code /api/auth/*} — an IP budget is defeated by a phone tether, and neither is keyed on the
     * <em>address</em> that receives the mail, which is the key that matters when the harm lands on
     * somebody who never asked for an account. <strong>HD-202 moves both onto this mechanism</strong>:
     * two more {@code MailThrottlePolicy} beans, two {@code requireAndRecord} calls in
     * {@code AuthService}, two properties — and the deletion of these two entries, which is what
     * makes that merge verifiable rather than intended. {@code mail_send_events} already carries
     * {@code email_type} so it needs no migration.
     *
     * <p>Anything added here needs a reason that survives the same question
     * ({@code ThrottleCoverageTest.EXEMPT}'s framing): <em>who chooses the address, and what does
     * this instance send to it if they choose a stranger's?</em> "It is rate-limited somewhere else"
     * is only an answer if that limiter is keyed on the recipient.
     */
    private static final Set<EmailType> EXEMPT =
            EnumSet.of(EmailType.VERIFICATION, EmailType.PASSWORD_RESET);

    /**
     * <strong>The checklist, printed by the failure that needs it.</strong> Same mechanism as
     * {@code ThrottleCoverageTest.PROPAGATION_CHECKLIST} and deliberately not a comment: a comment
     * cannot fire.
     */
    private static final String PROPAGATION_CHECKLIST = """

            A NEW KIND OF OUTBOUND MAIL. Every mailer lets an authenticated — or, for the auth \
            flows, an anonymous — caller name an address and have this instance send to it. That is \
            the whole of the abuse surface HD-190 exists to bound, so a new one is a decision, \
            never an omission. Do one of these two things:

              THROTTLE IT. Add a MailThrottlePolicy bean for the new EmailType (the shape to copy \
              is InviteMailThrottleConfig: cooldown, per-recipient daily cap, two RateLimitKind \
              tags and the wording), then call RecipientMailThrottle.requireAndRecord from the \
              sending path — AFTER tenancy and authorization have been resolved, never before, and \
              above anything that can still roll the transaction back. Counts are per EmailType, so \
              the new budget cannot consume the invitation allowance or the other way round, and \
              mail_send_events needs no migration.

              OR EXEMPT IT, with a reason written in EXEMPT above that answers: who chooses the \
              address, and what reaches a stranger if they choose one? An IP budget is not an \
              answer — it is defeated by a tether and is not keyed on the recipient.

            Then propagate, the way ThrottleCoverageTest's own checklist requires for its axis:

              1. src/main/java/.../common/config/InviteProperties.java  — the per-EmailType numbers
                 and their ranges live on a @ConfigurationProperties record of their own
              2. src/main/resources/application.properties              — the property block AND the
                 numbered list above app.rate-limit.enabled, which enumerates the limiter families
              3. .env.prod.example                                      — the operator block, in the
                 house style (leave the line COMMENTED OUT; an empty value aborts the boot)
              4. docs/self-hosting.md                                   — the operator table, the
                 RATE_LIMIT_ENABLED row, and the node-local prose (the recipient ceilings are the
                 exception: their state is in Postgres, so they are cluster-wide and exact)
              5. docs/api-dc.md AND docs/api-cloud.md                   — the env-var table and the
                 429 note on the affected endpoint
              6. src/main/frontend/public/openapi.yaml                  — the 429 + Retry-After
              7. docs/adr/0015-recipient-keyed-mail-throttle-persisted.md — it is written as one
                 mechanism for every mail path; a second mechanism contradicts it

            Two claims to re-check rather than copy, because both are counts and a count goes stale \
            one entry before the list does: "the invitation ceilings" (this mechanism is not \
            invitation-specific and never was — that is why the table is mail_send_events) and "429 \
            here follows the tenancy check" (true of the invite endpoint because it is spent inside \
            the service; a future mailer on an anonymous path has no tenancy check to follow).
            """;

    @Autowired RecipientMailThrottle recipientMailThrottle;
    @Autowired AppProperties appProperties;
    @Autowired MailAsyncProperties mailAsyncProperties;
    @Autowired FailedEmailWriter failedEmailWriter;

    /**
     * The seal itself. Polarity is coverage-by-default, like {@code ThrottleCoverageTest}: a new
     * {@code EmailType} constant is <em>in</em> unless somebody exempts it, so forgetting is not an
     * option and an exemption is a reviewable edit.
     */
    @Test
    void everyEmailTypeIsThrottledOrExemptWithAWrittenReason() {
        var throttled = recipientMailThrottle.throttledTypes();
        var unaccounted = EnumSet.allOf(EmailType.class);
        unaccounted.removeAll(throttled);
        unaccounted.removeAll(EXEMPT);

        assertThat(unaccounted)
                .as("these kinds of mail can be sent to any address on the internet by whoever "
                    + "names it, with no recipient-keyed ceiling and no written reason why that is "
                    + "acceptable." + PROPAGATION_CHECKLIST)
                .isEmpty();
    }

    /**
     * <strong>An exemption must be deleted the moment it stops being one.</strong> This is what
     * turns HD-202 from an intention into a check: the two policy beans it adds make this test fail
     * until {@code VERIFICATION} and {@code PASSWORD_RESET} come out of {@link #EXEMPT}, so the
     * documented gap cannot survive its own closing. Without it, an exemption is a claim nobody
     * ever re-reads.
     */
    @Test
    void nothingIsBothThrottledAndExempt() {
        var contradiction = EnumSet.copyOf(EXEMPT);
        contradiction.retainAll(recipientMailThrottle.throttledTypes());

        assertThat(contradiction)
                .as("these types now have a MailThrottlePolicy bean, so the EXEMPT entry above is "
                    + "stale — it still describes them as an open gap that HD-202 will close, and "
                    + "HD-202 has evidently landed for them. Delete the entry; that deletion is "
                    + "the deliverable, and this assertion is why it cannot be forgotten.")
                .isEmpty();
    }

    /**
     * The invitation mailer is throttled. A separate, positive assertion because the two above are
     * both "nothing is unaccounted for", and an empty policy map satisfies both of them the moment
     * every type is exempted — the way this feature would most plausibly be lost is somebody
     * exempting {@code INVITE} during an incident and not putting it back.
     */
    @Test
    void theInvitationMailerIsThrottledAndNotMerelyAccountedFor() {
        assertThat(recipientMailThrottle.throttledTypes())
                .as("INVITE is the mailer HD-190 exists for: an authenticated stranger typing an "
                    + "address into the invite box of a workspace they created seconds earlier. "
                    + "It must be throttled, not exempted." + PROPAGATION_CHECKLIST)
                .contains(EmailType.INVITE);
        assertThat(EXEMPT)
                .as("INVITE may never be exempt")
                .doesNotContain(EmailType.INVITE);
    }

    /**
     * <strong>Every public mailer declares which kind of mail it is</strong> (acceptance criterion
     * 15) — and the mapping is <em>observed at runtime</em>, not listed here.
     *
     * <p>Each {@code send*} method is driven against a stub sender and a mock
     * {@link ProductMetrics}; the {@link EmailType} it reports through
     * {@code metrics.emailSent(type, outcome)} is the answer. So the failure modes this catches are
     * the real ones: a mailer that reports no type at all (invisible to the coverage assertion
     * above, and to {@code hamstrack.email.sent}, and to the daily-volume alert built on it), and a
     * mailer that reports two.
     *
     * <p>The retry count is forced to 1 for the probe. The real value is 3 with a two-second
     * backoff, and a mailer that fails under these stubs would otherwise spend six seconds doing it
     * before the assertion below could say so.
     */
    @Test
    void everyPublicMailServiceSendMethodMapsToAnEmailType() {
        var metrics = mock(ProductMetrics.class);
        // The dispatcher runs the task on THIS thread (Runnable::run), because the observation is
        // the point: driving a mailer through the real mailExecutor would record the EmailType on a
        // pool thread and turn this seal into a race. What is being measured is which type each
        // mailer names, not where it names it from. Nothing is ever rejected by an inline executor,
        // so the UndeliverableMail arm is unreachable here and a mock is honest about that.
        var probe = new MailService(stubSender(), appProperties, metrics,
                new MailAsyncProperties(mailAsyncProperties.async(),
                        new MailAsyncProperties.Critical(1, 0L),
                        mailAsyncProperties.deadLetter()),
                failedEmailWriter,
                new MailDispatcher(Runnable::run, mock(UndeliverableMail.class)));

        var mailers = Arrays.stream(MailService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .filter(m -> m.getName().startsWith("send"))
                .toList();

        assertThat(mailers)
                .as("no public send* method was found on MailService at all, so this test is "
                    + "guarding an empty set — the class moved or the naming convention changed, "
                    + "and with it the only handle this seal has on the set of mailers")
                .isNotEmpty();

        var observed = new LinkedHashMap<String, Set<EmailType>>();
        var failed = new LinkedHashSet<String>();
        for (var mailer : mailers) {
            var types = driveAndCaptureType(probe, metrics, mailer, failed);
            observed.put(mailer.getName(), types);
        }

        assertThat(failed)
                .as("these mailers could not be driven to a successful send by this file's stubs, "
                    + "so the type they report is the FAILURE path's and this seal is measuring "
                    + "the wrong thing. Extend stubSender()/argumentFor() until they succeed — do "
                    + "not relax the assertion, which is the only thing making the mapping below "
                    + "an observation rather than a list somebody maintains.")
                .isEmpty();

        assertThat(observed)
                .as("every public mailer must record exactly one EmailType. A mailer that records "
                    + "none is invisible to the coverage seal above, to hamstrack.email.sent, and "
                    + "therefore to MailDailyVolumeHigh — the one alert that sees an abuser who "
                    + "stays under every ceiling." + PROPAGATION_CHECKLIST)
                .allSatisfy((name, types) -> assertThat(types)
                        .as("MailService.%s", name)
                        .hasSize(1));
    }

    // ------------------------------------------------------------------ probe machinery

    /**
     * Invokes one mailer and returns the {@link EmailType}s it reported. The mock is reset between
     * mailers so each answer is that mailer's alone.
     */
    private Set<EmailType> driveAndCaptureType(MailService probe, ProductMetrics metrics,
                                               Method mailer, Set<String> failed) {
        clearInvocations(metrics);
        var args = new Object[mailer.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            args[i] = argumentFor(mailer, mailer.getParameterTypes()[i]);
        }
        try {
            mailer.invoke(probe, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not invoke MailService." + mailer.getName()
                                     + " — extend argumentFor() for its parameter types", e);
        }

        // Read off the mock rather than verify(...), so a mailer that records nothing produces
        // THIS file's message rather than Mockito's "Wanted but not invoked" — the failure is that
        // a new kind of outbound mail exists and declares nothing about itself, and the reader
        // needs the checklist, not an invocation trace.
        var recorded = mockingDetails(metrics).getInvocations().stream()
                .filter(invocation -> "emailSent".equals(invocation.getMethod().getName()))
                .toList();

        assertThat(recorded)
                .as("MailService.%s sends mail and records no EmailType. It is therefore invisible "
                    + "to the coverage seal in this file, to hamstrack.email.sent{type}, and so to "
                    + "MailDailyVolumeHigh — the one alert that sees an abuser who stays under "
                    + "every ceiling. Route it through send()/sendHtml() with a type."
                    + PROPAGATION_CHECKLIST, mailer.getName())
                .isNotEmpty();

        var types = new LinkedHashSet<EmailType>();
        for (var invocation : recorded) {
            types.add((EmailType) invocation.getArgument(0));
            if (invocation.getArgument(1) != EmailOutcome.SUCCESS) {
                failed.add(mailer.getName() + " -> " + invocation.getArgument(1));
            }
        }
        return types;
    }

    /**
     * A {@link JavaMailSender} that accepts everything and delivers nothing.
     *
     * <p>{@code createMimeMessage()} returns a real {@link MimeMessage} rather than a mock's
     * {@code null}: {@code MimeMessageHelper} would throw on the null, the throw would be counted
     * as a FAILURE, and the mapping this test reads would then come from the failure path — which
     * happens to record the same type today and would not have to tomorrow.
     */
    private static JavaMailSender stubSender() {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage())
                .thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        return sender;
    }

    /**
     * A harmless value of the right type for a mailer's parameter. Every mailer takes strings
     * today; an unknown type fails loudly rather than passing {@code null}, because a
     * {@code null} that produced a NullPointerException would be reported as an un-drivable mailer
     * with no hint as to why.
     */
    private static Object argumentFor(Method mailer, Class<?> parameterType) {
        if (parameterType == String.class) {
            return "throttle-coverage@example.test";
        }
        throw new AssertionError("MailService." + mailer.getName() + " takes a "
                                 + parameterType.getName() + ", which this probe cannot supply. "
                                 + "Add a case to argumentFor() — a new mailer must still be "
                                 + "drivable, or the seal silently stops covering it.");
    }
}
