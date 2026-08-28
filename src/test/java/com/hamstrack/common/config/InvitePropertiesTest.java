package com.hamstrack.common.config;

import com.hamstrack.common.ratelimit.RecipientMailThrottle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@link InviteProperties} held to the {@link SearchPropertiesTest} standard (HD-190 section 11.2,
 * acceptance criterion 16) — there are eight sibling classes in this codebase for exactly this, and
 * these five properties arrived with bounds in annotations, promises in five documents and nothing
 * joining them.
 *
 * <p>The stakes here are higher than for the search budget, and in a direction that is easy to
 * misread. These numbers are the <strong>only</strong> thing standing between an authenticated
 * stranger and our verified sending domain. Wrong in the generous direction the ceiling is
 * decorative; wrong in the strict direction the first thing a large new customer meets is a refusal.
 * Both failures are silent, which is why nothing here is clamped: an out-of-range value stops the
 * boot rather than being corrected behind the operator's back.
 *
 * <p>An {@link ApplicationContextRunner} rather than {@code @SpringBootTest}, because a context that
 * must NOT start cannot be asserted on by an annotation that starts it.
 */
class InvitePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    /**
     * The five documented defaults, in one assertion. Every one of them is quoted verbatim by
     * {@code application.properties}, {@code .env.prod.example}, {@code docs/self-hosting.md} and
     * both {@code docs/api-*.md}; a drifted default is a documented number that is not the one
     * running, and an operator sizing their instance against it is sizing against fiction.
     */
    @Test
    void theDefaultsAreTheDocumentedOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(InviteProperties.class);
            assertThat(properties.maxPerSenderPerHour()).as("bounds a burst").isEqualTo(20);
            assertThat(properties.maxPerSenderPerDay())
                    .as("THE quota control — 100/day against a 3000/month provider quota is ~1/30 "
                        + "of the month per day, which an operator has a month to notice")
                    .isEqualTo(100);
            assertThat(properties.recipientCooldownMinutes())
                    .as("the ticket's attack and its control")
                    .isEqualTo(60);
            assertThat(properties.maxPerRecipientPerDay())
                    .as("the number most likely to need adjusting after launch, and the only one "
                        + "whose false positive lands on an innocent third party")
                    .isEqualTo(5);
            assertThat(properties.eventRetentionDays()).isEqualTo(7);
        });
    }

    /**
     * <strong>There is deliberately no "unlimited", and {@code 0} is out of range on every one of
     * them</strong> (acceptance criterion 16). {@code 0} is the value an operator writes to mean
     * "turn it off"; here it would mean "refuse everything" on four of the five and "keep nothing"
     * on the fifth, so it aborts the boot instead. The off switch is {@code app.rate-limit.enabled},
     * which turns off a whole family rather than silently leaving one surface open, and an operator
     * who wants no practical ceiling writes the top of the range.
     */
    @Test
    void zeroIsOutOfRangeOnEveryOneOfThem() {
        assertRejected("app.invites.max-per-sender-per-hour=0", "maxPerSenderPerHour");
        assertRejected("app.invites.max-per-sender-per-day=0", "maxPerSenderPerDay");
        assertRejected("app.invites.recipient-cooldown-minutes=0", "recipientCooldownMinutes");
        assertRejected("app.invites.max-per-recipient-per-day=0", "maxPerRecipientPerDay");
        assertRejected("app.invites.event-retention-days=0", "eventRetentionDays");

        assertRejected("app.invites.max-per-sender-per-hour=-1", "maxPerSenderPerHour");
        assertRejected("app.invites.event-retention-days=-1", "eventRetentionDays");
    }

    /**
     * The documented ranges are inclusive at both ends — an operator who follows the published
     * numbers to the letter must be able to boot — and one over is a refusal, never a clamp.
     *
     * <p>{@code recipient-cooldown-minutes} tops out at 1440 for a stated reason: at one day the
     * cooldown window never reaches further back than the fixed daily window, and nothing above a
     * day would be a cooldown anyway. {@code event-retention-days} starts at <strong>2</strong>, not
     * 1, and that floor is the subject of {@link #theRetentionCrossCheckRefusesAZeroMarginRetention()}.
     */
    @Test
    void theDocumentedRangesBootAndOneOverRefusesToStart() {
        assertBoots("app.invites.max-per-sender-per-hour", 1, 1000);
        assertRejected("app.invites.max-per-sender-per-hour=1001", "maxPerSenderPerHour");

        assertBoots("app.invites.max-per-sender-per-day", 1, 10_000);
        assertRejected("app.invites.max-per-sender-per-day=10001", "maxPerSenderPerDay");

        assertBoots("app.invites.recipient-cooldown-minutes", 1, 1440);
        assertRejected("app.invites.recipient-cooldown-minutes=1441", "recipientCooldownMinutes");

        assertBoots("app.invites.max-per-recipient-per-day", 1, 1000);
        assertRejected("app.invites.max-per-recipient-per-day=1001", "maxPerRecipientPerDay");

        assertBoots("app.invites.event-retention-days", 2, 90);
        assertRejected("app.invites.event-retention-days=1", "eventRetentionDays");
        assertRejected("app.invites.event-retention-days=91", "eventRetentionDays");
    }

    /**
     * <strong>The startup cross-check between two properties that live in different files</strong>
     * (section 7.4). {@code MailSendEventRetention} deletes by {@code created_at} and both recipient
     * ceilings count by {@code created_at}, so a retention that falls inside a live ceiling window
     * makes that ceiling quietly become the retention: nothing fails, the count simply comes back
     * lower, and the throttle hands out a send it meant to refuse.
     *
     * <p><strong>The case that matters is the zero-margin one, and it was the hole.</strong> The
     * first cut compared the retention only against the <em>configurable</em> cooldown, so
     * {@code retention=1 cooldown=1439} passed — one day of retention against the fixed 24-hour
     * window of {@code max-per-recipient-per-day}, which no property can lower. Exactly equal is not
     * cover: two replicas whose clocks differ by a second are enough for one node's sweep to delete
     * a row the other node's daily count still needs. Hence {@code max(cooldown, fixed window)}, and
     * hence the {@code @Min(2)} floor, which is derived from this predicate rather than chosen.
     *
     * <p>The record is constructed directly, as its own javadoc prescribes: at today's annotation
     * values no bindable combination can falsify the predicate, so the {@code false} branch is
     * unreachable through the binder. That is not dead code — it is the guard that becomes
     * load-bearing the moment either of two independently defensible edits happens in a different
     * file: raise the {@code @Max} on the cooldown past a day, or lower the {@code @Min} on the
     * retention back to one. Neither has any reason to visit the other.
     *
     * <p><strong>Widening {@code RecipientMailThrottle.DAY} is a THIRD drift and this test cannot
     * see it</strong> — saying otherwise would restate, one level up, the exact defect the
     * {@code max(cooldown, fixed window)} widening removed. The fixed window reaches this predicate
     * as {@code InviteProperties.FIXED_CEILING_WINDOW_MINUTES}, a hand-copied 1440; widen
     * {@code DAY} alone and the copy does not move, so every assertion here goes on passing against
     * the old window while the running system counts over a wider one. Only an equality check
     * between the two constants catches that, and it is
     * {@link #theFixedCeilingWindowIsTheSameNumberInBothFiles()} — not this test, and not the
     * {@code @AssertTrue}.
     */
    @Test
    void theRetentionCrossCheckRefusesAZeroMarginRetention() {
        assertThat(properties(20, 100, 1440, 5, 1).isRetentionLongerThanWidestCeilingWindow())
                .as("one day of retention against a 1440-minute cooldown is EXACTLY equal, and "
                    + "exact is not cover")
                .isFalse();

        assertThat(properties(20, 100, 1439, 5, 1).isRetentionLongerThanWidestCeilingWindow())
                .as("THE HOLE THIS WIDENING CLOSED: a 1439-minute cooldown is inside one day, so a "
                    + "check against the cooldown alone passed — while the FIXED 24h window of "
                    + "max-per-recipient-per-day, which no property can lower, was exactly equal "
                    + "to the retention. The predicate must compare against the WIDER of the two "
                    + "windows, not against the configurable one")
                .isFalse();

        assertThat(properties(20, 100, 1440, 5, 2).isRetentionLongerThanWidestCeilingWindow())
                .as("two days covers both windows with a day to spare — the documented minimum, "
                    + "and the reason the minimum is 2 rather than 1")
                .isTrue();

        assertThat(properties(20, 100, 1, 5, 2).isRetentionLongerThanWidestCeilingWindow())
                .as("a one-minute cooldown does not lower the floor: the fixed daily window is "
                    + "still 24 hours, so the retention still has to outlast a day")
                .isTrue();
    }

    /**
     * <strong>The seal on the one drift the startup cross-check cannot police</strong> — an
     * equality check between two constants that are the same number written twice, in two packages,
     * with nothing but a pair of javadoc paragraphs joining them.
     *
     * <p>{@code InviteProperties.FIXED_CEILING_WINDOW_MINUTES} exists because
     * {@code isRetentionLongerThanWidestCeilingWindow} has to know the width of the window
     * {@code max-per-recipient-per-day} is counted over, and that width is
     * {@code RecipientMailThrottle.DAY} — a {@code private} constant in another package, so it is
     * restated rather than imported. A restatement is only as good as something that compares it,
     * and what makes this one dangerous is the <em>direction</em> it fails in: widening
     * {@code DAY} to, say, 48 hours leaves the copy at 1440, so the assertion keeps comparing the
     * retention against a day, keeps <strong>passing</strong>, and keeps promising cover it is no
     * longer computing. A constraint cannot notice, because the value it would have to read is the
     * one that did not change.
     *
     * <p>Read reflectively for the same reason {@code MailSendEventRepositorySealTest} does it:
     * the question is about the constant a future edit will actually touch, and neither of these is
     * visible from here. Renaming or deleting either one fails this test rather than skipping it.
     */
    @Test
    void theFixedCeilingWindowIsTheSameNumberInBothFiles() {
        long copied = ((Number) constant(InviteProperties.class, "FIXED_CEILING_WINDOW_MINUTES"))
                .longValue();
        long actual = ((Duration) constant(RecipientMailThrottle.class, "DAY")).toMinutes();

        assertThat(copied)
                .as("""
                        These two are one number written twice and they have drifted:

                          src/main/java/com/hamstrack/common/config/InviteProperties.java \
                        (FIXED_CEILING_WINDOW_MINUTES = %d)
                          src/main/java/com/hamstrack/common/ratelimit/RecipientMailThrottle.java \
                        (DAY = %d minutes)

                        DAY is the window app.invites.max-per-recipient-per-day is counted over. \
                        FIXED_CEILING_WINDOW_MINUTES is the copy that startup validation measures \
                        the retention sweep against, and it is the WIDER of the two ceiling \
                        windows, so it is the one that decides whether \
                        isRetentionLongerThanWidestCeilingWindow passes.

                        While they disagree, that check certifies cover it is not computing. Take \
                        DAY = 2 days and the copy left at 1440: the documented minimum retention of \
                        2 days clears the stale copy comfortably and is EXACTLY EQUAL to the real \
                        window, which this file argues at length is not cover -- two replicas whose \
                        clocks differ by a second are enough for one node's sweep to delete a row \
                        the other node's daily count still needs. The cap then under-counts and \
                        hands out a send it meant to refuse, with no exception, no 429 and no log \
                        line. That silence is the whole reason this is an assertion and not a \
                        comment.

                        Fix it in whichever file is wrong -- they must be the same number -- and \
                        do NOT delete this test to make the red go away.""",
                        copied, actual)
                .isEqualTo(actual);
    }

    /**
     * The cross-check does not merely exist — it <em>reports</em>, and its message is the one that
     * explains a refusal the {@code @Min(2)} alone would leave cryptic ("must be at least 2" says
     * nothing about which other property it is in tension with). Hibernate Validator evaluates every
     * constraint, so {@code =1} comes back carrying both.
     */
    @Test
    void theCrossCheckExplainsItselfWhenTheRetentionFloorIsBreached() {
        runner.withPropertyValues("app.invites.event-retention-days=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureOf(context))
                            .as("the refusal must name the OTHER property, or an operator sees a "
                                + "bare bound and lowers it back")
                            .contains("event-retention-days")
                            .contains("recipient-cooldown-minutes")
                            .contains("max-per-recipient-per-day");
                });
    }

    /**
     * The blank, arriving the way it actually arrives in production: through the real
     * {@code ${INVITE_*:default}} placeholders. The whole risk lives in the difference between
     * "absent" and "present and empty" — the first takes the default, the second must stop the boot,
     * which is exactly what {@code .env.prod.example} promises operators ("leave the line COMMENTED
     * OUT — an empty value aborts the boot rather than restoring the default").
     *
     * <p>Asserted on all five rather than on a representative one, because the mechanism is
     * per-component: a single boxed field would fail silently and only for its own variable.
     */
    @Test
    void aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        assertBlankRefused("INVITE_MAX_PER_SENDER_PER_HOUR",
                "app.invites.max-per-sender-per-hour", "20", 7);
        assertBlankRefused("INVITE_MAX_PER_SENDER_PER_DAY",
                "app.invites.max-per-sender-per-day", "100", 250);
        assertBlankRefused("INVITE_RECIPIENT_COOLDOWN_MINUTES",
                "app.invites.recipient-cooldown-minutes", "60", 30);
        assertBlankRefused("INVITE_MAX_PER_RECIPIENT_PER_DAY",
                "app.invites.max-per-recipient-per-day", "5", 9);
        assertBlankRefused("INVITE_EVENT_RETENTION_DAYS",
                "app.invites.event-retention-days", "7", 14);
    }

    /**
     * The test above hardcodes the placeholders, so on its own it proves only that <em>a</em>
     * {@code ${VAR:default}} behaves that way. This pins that the real {@code application.properties}
     * is still wired exactly that way — same variable names, same fallbacks — because everything the
     * blank test asserts is worthless if production reads differently-named variables, and because
     * these five names are what {@code .env.prod.example} and both operator tables tell people to
     * set.
     */
    @Test
    void theBasePropertiesFileWiresTheDocumentedPlaceholders() throws Exception {
        var content = new String(new ClassPathResource("application.properties")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content)
                .as("the variable names and fallbacks are what the blank test simulates, and what "
                    + ".env.prod.example and the operator tables promise")
                .contains("app.invites.max-per-sender-per-hour=${INVITE_MAX_PER_SENDER_PER_HOUR:20}")
                .contains("app.invites.max-per-sender-per-day=${INVITE_MAX_PER_SENDER_PER_DAY:100}")
                .contains("app.invites.recipient-cooldown-minutes=${INVITE_RECIPIENT_COOLDOWN_MINUTES:60}")
                .contains("app.invites.max-per-recipient-per-day=${INVITE_MAX_PER_RECIPIENT_PER_DAY:5}")
                .contains("app.invites.event-retention-days=${INVITE_EVENT_RETENTION_DAYS:7}");
    }

    /**
     * The mechanism behind the blank test, asserted directly so a future "tidy them to
     * {@code Integer}" fails here loudly instead of turning that test into a test of nothing.
     * {@code @Min(1)} does <em>not</em> catch the blank: a boxed component binds to {@code null}, the
     * binder treats that as unbound, {@code @DefaultValue} supplies the default, and validation then
     * passes on a value the operator never wrote.
     *
     * <p>All five, by iteration over the record's own components, so a sixth property added later is
     * covered without anybody remembering to come back here.
     */
    @Test
    void theComponentsArePrimitivesBecauseThatIsWhatCatchesTheBlank() {
        for (var component : InviteProperties.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("InviteProperties.%s must stay a primitive int — boxed, a blank env var "
                        + "converts to null, the binder reads it as unbound, and @DefaultValue "
                        + "silently applies the documented default. Every one of these numbers is "
                        + "a ceiling on outbound mail, so 'silently applied the default' is the "
                        + "one outcome an operator must never get from a line they edited",
                        component.getName())
                    .isEqualTo(int.class);
        }
    }

    /**
     * <strong>The DC/Cloud invariant, asserted where a properties edit cannot hide.</strong>
     * {@code InviteProperties}' own javadoc promises this five times and nothing joined it until
     * now.
     *
     * <p>The DC abuse profile genuinely differs — a self-hoster runs their own SMTP and
     * {@code PUBLIC_SIGNUP_ENABLED} defaults to {@code false} there — and looser DC defaults would
     * still be wrong, because <strong>the risk tracks the wrong variable</strong>: a DC install with
     * public signup switched on has exactly the Cloud abuse profile, so a profile-keyed default
     * would protect Cloud and leave the actually exposed install on the loose numbers.
     *
     * <p>Asserted as <em>absence of the key</em> rather than as a bound value, on purpose: a profile
     * file setting the same 20/100/60/5/7 would still be the deployment-shaped seam the
     * documentation promises does not exist, would still pass a value assertion, and would be one
     * character away from divergence.
     */
    @Test
    void noProfileFileOverridesTheInvitationCeilings() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            var content = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content)
                    .as("%s must not mention app.invites.* — the invitation ceilings are identical "
                        + "in dc and cloud by design, and if a deployment ever needs a different "
                        + "posture it is by lowering a number, never by a second code path", file)
                    .doesNotContain("app.invites.")
                    .doesNotContain("INVITE_");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void assertBoots(String key, int... values) {
        for (var value : values) {
            runner.withPropertyValues(key + "=" + value)
                    .run(context -> assertThat(context)
                            .as("%s=%s is inside the documented range and must boot", key, value)
                            .hasNotFailed());
        }
    }

    private void assertRejected(String property, String component) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context)
                        .as(property)
                        .hasFailed()
                        .getFailure()
                        // the component name lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining(component));
    }

    /**
     * The three states of one env var, through the real placeholder: absent (default applies),
     * present-but-empty (boot refused), and set (bound).
     */
    private void assertBlankRefused(String envVar, String key, String fallback, int set) {
        var withPlaceholder = runner.withPropertyValues(key + "=${" + envVar + ":" + fallback + "}");

        withPlaceholder.run(context -> assertThat(context)
                .as("%s is simply absent — the placeholder default applies", envVar)
                .hasNotFailed());

        withPlaceholder.withPropertyValues(envVar + "=")
                .run(context -> {
                    assertThat(context)
                            .as("%s= is present-but-empty, so the placeholder RESOLVES (to \"\") "
                                + "and the :%s default never applies. If this context starts, an "
                                + "operator who blanked the line is running with a ceiling they "
                                + "think they removed — on a control whose whole job is to be the "
                                + "number somebody chose", envVar, fallback)
                            .hasFailed();
                    assertThat(failureOf(context)).contains(key);
                });

        withPlaceholder.withPropertyValues(envVar + "=" + set)
                .run(context -> assertThat(context)
                        .as("%s=%s must bind", envVar, set)
                        .hasNotFailed());
    }

    /**
     * A {@code private static final} constant, by name. Absence is a failure and not a skip: a
     * renamed or deleted constant is precisely the edit this file has to notice, and a reflective
     * lookup that swallowed it would leave the seal passing over nothing.
     */
    private static Object constant(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (NoSuchFieldException e) {
            return fail("%s.%s no longer exists. It is one half of a hand-copied constant pair "
                        + "(the other is in %s) — if the pair has been replaced by a real "
                        + "compile-time link, delete this test and say so; if it was merely "
                        + "renamed, rename it here too",
                    owner.getSimpleName(), name,
                    owner == InviteProperties.class ? "RecipientMailThrottle" : "InviteProperties");
        } catch (IllegalAccessException e) {
            return fail("cannot read %s.%s reflectively: %s", owner.getSimpleName(), name, e);
        }
    }

    private static InviteProperties properties(int perHour, int perDay, int cooldownMinutes,
                                               int perRecipientPerDay, int retentionDays) {
        return new InviteProperties(perHour, perDay, cooldownMinutes, perRecipientPerDay,
                retentionDays);
    }

    /** Every message in the failure chain, so an assertion can look at all of them. */
    private String failureOf(AssertableApplicationContext context) {
        var out = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            out.append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InviteProperties.class)
    static class TestConfig {}
}
