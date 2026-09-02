package com.hamstrack.common.mail;

import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.MailAsyncProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.EmailOutcome;
import com.hamstrack.common.observability.ProductMetrics.EmailType;
import com.hamstrack.common.ratelimit.MailThrottlePolicy;
import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

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
     * <p><strong>Empty since HD-202, and the emptying was the deliverable.</strong> It used to hold
     * {@code VERIFICATION} and {@code PASSWORD_RESET} as a documented open gap — bounded only by the
     * per-IP auth budget, which is defeated by a phone tether and is not keyed on the address that
     * receives the mail. Both are now on {@code RecipientMailThrottle} through
     * {@code AuthMailThrottleConfig}, and {@link #nothingIsBothThrottledAndExempt()} is what made
     * removing them from here unavoidable rather than merely intended.
     *
     * <p>Anything added here needs a reason that survives the same question
     * ({@code ThrottleCoverageTest.EXEMPT}'s framing): <em>who chooses the address, and what does
     * this instance send to it if they choose a stranger's?</em> "It is rate-limited somewhere else"
     * is only an answer if that limiter is keyed on the recipient.
     *
     * <p><strong>An exemption is not the only shape a gap can take, and this file structurally
     * cannot see the other one.</strong> The seal is on <em>mailers</em>, so a throttled
     * {@link EmailType} sent from a path that never spends its budget passes every assertion here.
     * {@code POST /api/auth/register} was exactly that: it mailed {@code VERIFICATION} without
     * consulting the ceiling, on a written exemption that turned out to be false, and this file was
     * green throughout — the note that used to stand here said so and then called it a decision,
     * which is a blessing in all but name.
     *
     * <p>So the rule the note should have carried: <strong>a written exemption on an axis this test
     * cannot measure is not covered by this test, whatever it says about itself.</strong> The path
     * axis belongs to {@code AuthMailDoorsTest} (which door, from which call site) and to each
     * flow's own behavioural test. Register is on the mechanism now, through
     * {@code requireAndRecordWhereEndpointDiscloses}, sealed by {@code AuthMailRegisterThrottleTest}.
     */
    private static final Set<EmailType> EXEMPT = EnumSet.noneOf(EmailType.class);

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

              THROTTLE IT. Add a MailThrottlePolicy bean for the new EmailType (the shapes to copy \
              are InviteMailThrottleConfig and AuthMailThrottleConfig: cooldown, ceiling window, \
              per-recipient volume cap, two RateLimitKind tags — named by the convention step 8 \
              below depends on — the refusal shape, and the wording unless that shape is SILENT). \
              Then spend it from EVERY sending path, through whichever door the policy declares. \
              Note the plural: a budget spent by one of a type's endpoints and not the others is \
              not a ceiling, it is a detour, and this file cannot see the difference:

                RecipientMailThrottle.requireAndRecord, which answers 429, AFTER tenancy and \
                authorization have been resolved and above anything that can still roll the \
                transaction back; or

                RecipientMailThrottle.allowAnonymousSend, which drops the mail in silence, BEFORE \
                any lookup that could make the refusal depend on whether the address exists. This \
                is the door for a path whose response must be uniform — where a 429 would answer, \
                to anybody, a question about somebody else's traffic; or

                RecipientMailThrottle.requireAndRecordWhereEndpointDiscloses, which answers 429 \
                for a type that is silent elsewhere, and ONLY from an endpoint that already \
                publishes what the refusal would (register's own 409). Its call sites are sealed \
                by AuthMailDoorsTest — adding one is an edit there, and that test's failure \
                message is the checklist for it.

              Counts are per EmailType, so the new budget cannot consume the invitation allowance \
              or the other way round, and mail_send_events needs no migration.

              OR EXEMPT IT, with a reason written in EXEMPT above that answers: who chooses the \
              address, and what reaches a stranger if they choose one? An IP budget is not an \
              answer — it is defeated by a tether and is not keyed on the recipient.

            Then propagate, the way ThrottleCoverageTest's own checklist requires for its axis:

              1. src/main/java/.../common/config/                       — the per-EmailType numbers
                 and their ranges live on a @Validated @ConfigurationProperties record of their own
                 (InviteProperties, AuthMailProperties), never on a shared one
              2. src/main/resources/application.properties              — the property block AND the
                 numbered list above app.rate-limit.enabled, which enumerates the limiter families
              3. .env.prod.example                                      — the operator block, in the
                 house style (leave the line COMMENTED OUT; an empty value aborts the boot)
              4. docs/self-hosting.md                                   — the operator table, the
                 RATE_LIMIT_ENABLED row, and the node-local prose (the recipient ceilings are the
                 exception: their state is in Postgres, so they are cluster-wide and exact)
              5. docs/api-dc.md AND docs/api-cloud.md                   — the env-var table, and the
                 refusal on the affected endpoint: a 429 note where the policy RESPONDS_429, or a
                 sentence saying the mail is dropped where it is SILENT (the status does not move,
                 and a reader who is not told that will read the uniform 200 as proof it was sent)
              6. src/main/frontend/public/openapi.yaml                  — the 429 + Retry-After, if
                 and only if the refusal is visible. A SILENT policy adds no response
              7. docs/adr/0015-recipient-keyed-mail-throttle-persisted.md — it is written as one
                 mechanism for every mail path; a second mechanism contradicts it
              8. observability/grafana/provisioning/alerting/rules.yml    — THE SHARPEST OMISSION ON
                 THIS LIST, and the easiest to make, because nothing else fails when you skip it.
                 Where a refusal is SILENT, the metric is the ONLY evidence the refusal happened at
                 all, so an unmonitored new kind is silent twice over. The rules there select by
                 NAMING CONVENTION rather than by a list of kinds, so a kind tagged
                 <thing>_recipient_cooldown / _recipient_window / _recipient_daily lands inside them
                 automatically — and everyRecipientKindIsSelectedByTheAlertRules() in this file
                 fails if yours does not. That test is the reason this step is checkable rather than
                 hopeful. Also ask the harder question it cannot: does a refusal COUNTER see your
                 attacker at all? For the auth mailers it does not — an attacker who spends the cap
                 as slots age out is never refused — which is why a QUANTITY gauge
                 (hamstrack.mail.anonymous_recipient_max) had to be added beside it

            Two claims to re-check rather than copy, because both are counts and a count goes stale \
            one entry before the list does: "the invitation ceilings" (this mechanism is not \
            invitation-specific and never was — that is why the table is mail_send_events) and "429 \
            here follows the tenancy check" (true of the invite endpoint because it is spent inside \
            the service; a future mailer on an anonymous path has no tenancy check to follow).
            """;

    @Autowired RecipientMailThrottle recipientMailThrottle;
    @Autowired List<MailThrottlePolicy> policies;
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
     * turns HD-202 from an intention into a check: the policy beans it adds make this test fail
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

    /**
     * <strong>Every recipient-keyed refusal counter is selected by a rule in
     * {@code rules.yml}</strong> — the propagation target above, made checkable.
     *
     * <p><strong>Why this is worth a test and the other propagation targets are not.</strong> A
     * missing line in a document is found by the next person who reads it. A missing alert is found
     * by nobody, ever, and for these ceilings it is worse than that: two of the three endpoints
     * refuse in <em>silence</em>, so there is no {@code 429}, no log line and no user complaint.
     * The counter is the only evidence the refusal exists at all, and a rule that does not select it
     * makes the evidence unreadable. Silent squared.
     *
     * <p><strong>It asserts a naming convention, not a list.</strong> The expressions used to name
     * the four kinds this feature shipped with, so a fifth would have tripped nothing and failed
     * nothing — the exact "anchored to today's members" shape this project's own rule warns about.
     * They now match {@code .*_recipient_(cooldown|window|daily)}, and this asserts that every kind
     * a policy actually declares is matched by a selector that really appears in that file. Both
     * halves matter: reading the selectors OUT OF the file is what stops this test from certifying
     * a convention nobody enforces.
     *
     * <p><strong>Both selector spellings are harvested, and the second one is the fix to a refusal
     * nobody could act on.</strong> The failure message below tells a reader whose kind fits no
     * suffix to add a rule for it — and the natural spelling for a one-off kind is
     * {@code kind="my_exact_kind"}, which the {@code =~}-only harvest did not see. The test then
     * failed anyway, printing the same instruction to somebody who had just followed it. Fail-safe
     * in direction, unperformable in content, which is this project's own third recorded instance
     * of that mistake.
     */
    @Test
    void everyRecipientKindIsSelectedByTheAlertRules() throws Exception {
        var rules = Files.readString(Path.of("observability", "grafana", "provisioning",
                "alerting", "rules.yml"), StandardCharsets.UTF_8);

        // BOTH selector spellings, =~ and =. Harvesting only the regex form made this test tell a
        // reader who had just written kind="my_exact_kind" - the natural spelling for a one-off
        // kind that fits no suffix, and the very thing the failure message invites - to "add a
        // rule" for a kind they had already added a rule for. Fail-safe, but a refusal may only
        // prescribe an action its reader can perform. An exact match is quoted so it cannot then
        // be read as a pattern.
        var selectors = Pattern.compile("""
                hamstrack_ratelimit_hit_total\\{kind=(~?)"([^"]+)"}""")
                .matcher(rules).results()
                .map(m -> m.group(1).isEmpty()
                        ? Pattern.compile(Pattern.quote(m.group(2)))
                        : Pattern.compile(m.group(2)))
                .toList();

        assertThat(selectors)
                .as("no kind selector was found in rules.yml at all, so this test is guarding "
                    + "nothing — the metric was renamed, or the rules moved, and either way the "
                    + "refusal counters are now unmonitored")
                .isNotEmpty();

        var unmatched = new LinkedHashSet<String>();
        for (var kind : recipientKinds()) {
            if (selectors.stream().noneMatch(s -> s.matcher(kind).matches())) {
                unmatched.add(kind);
            }
        }

        assertThat(unmatched)
                .as("these recipient-keyed refusal counters are selected by NO alert rule, so "
                    + "nothing watches them. Where the refusal is silent that counter is the only "
                    + "evidence the refusal happened at all, which makes an unmonitored kind "
                    + "invisible twice over. The rules select by naming convention — name the tag "
                    + "<thing>_recipient_cooldown / _recipient_window / _recipient_daily and it is "
                    + "covered without editing rules.yml. If your kind genuinely cannot take one of "
                    + "those suffixes, add a rule for it in the same commit; do NOT widen the "
                    + "regexes to a list of names, which is the shape this test exists to prevent."
                    + PROPAGATION_CHECKLIST)
                .isEmpty();
    }

    /** The metric tags every registered policy declares — read off the beans, never listed here. */
    private Set<String> recipientKinds() throws Exception {
        var tag = ProductMetrics.RateLimitKind.class.getDeclaredField("tag");
        tag.setAccessible(true);
        var kinds = new LinkedHashSet<String>();
        for (var policy : policies) {
            for (var kind : new ProductMetrics.RateLimitKind[]{
                    policy.cooldownKind(), policy.recipientVolumeKind()}) {
                kinds.add((String) tag.get(kind));
            }
        }
        assertThat(kinds)
                .as("no MailThrottlePolicy bean is registered, so this test is guarding an empty "
                    + "set")
                .isNotEmpty();
        return kinds;
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
