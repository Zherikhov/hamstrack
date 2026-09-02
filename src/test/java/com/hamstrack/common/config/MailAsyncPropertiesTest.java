package com.hamstrack.common.config;

import com.hamstrack.common.config.MailAsyncProperties.Async;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * <strong>The mail shutdown is three numbers, and a YAML file that has to agree with one of
 * them</strong> (HD-207).
 *
 * <h2>What the startup assertion is for, and what it deliberately is not</h2>
 * HD-207's complaint was that {@code queueCapacity} (100) and the shutdown drain (a hard-coded 15 s)
 * were inconsistent by an order of magnitude and the gap was a <em>silent</em> loss: the drain
 * flushes five to seven messages against a degraded SMTP host, and the rest were abandoned with one
 * generic line from Spring naming none of them.
 *
 * <p>The ticket asked for one of two remedies — shrink the queue to what the drain can flush, or
 * make the residue durable — and then asked that "raising either setting without the other turns a
 * build red". <strong>Those two halves are in tension, and this file takes the second remedy and
 * declines the first half of the third sentence.</strong> Once {@code MailTaskExecutor} dead-letters
 * the residue, raising {@code queueCapacity} alone is <em>safe</em>: it buys more burst absorption
 * and costs at most more rows on a deploy. An assertion that failed on it would be theatre, and
 * worse than theatre — it would train the next reader that the pair is coupled in a direction it is
 * not, which is how a real coupling gets ignored.
 *
 * <p>What IS dangerous is the pair against the <em>stop grace</em>: shutdown waits the drain and
 * only then writes the residue, so if the two together outrun the grace the platform gives the
 * process, SIGKILL lands mid-drain, the residue path never runs, and HD-207 is back in full — with
 * a mechanism in the code that makes a reader believe otherwise. That is the assertion, and all
 * three knobs appear in it.
 *
 * <h2>Both halves, because an {@code @AssertTrue} on a record is an assumption until a context
 * refuses to start</h2>
 * The predicate is tested directly (cheap, and it is the only way to reach combinations the binder
 * cannot produce) <em>and</em> through a real {@link ApplicationContextRunner} that binds
 * properties and asserts the boot fails with a message naming the grace. Without the second half,
 * "the constraint is wired in at all" is a belief about Hibernate Validator's getter discovery on a
 * non-component method of a record — and a guard that never fires looks exactly like one that
 * works, because both are silent in the happy path. Same standard as {@link InvitePropertiesTest}.
 */
class MailAsyncPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    // ============================================================ the startup cross-check

    @Test
    void theShippedDefaultsFitInsideTheStopGrace() {
        assertThat(async(15, 100, 30).isShutdownWithinTheStopGrace())
                .as("the values in application.properties must boot. 15s drain + a batch write of "
                    + "at most 100 rows against a 30s grace leaves roughly half the window spare")
                .isTrue();
    }

    @Test
    void aDrainThatFillsTheStopGraceOnItsOwnIsRefused() {
        assertThat(async(30, 100, 30).isShutdownWithinTheStopGrace())
                .as("a 30s drain uses the whole grace and leaves nothing for the residue write, so "
                    + "the process is killed at the moment it would have started recording what it "
                    + "could not send — the worst possible outcome, because the code says it "
                    + "records and it does not")
                .isFalse();
    }

    /**
     * <strong>The triple, which is why this is a relationship and not three independent
     * bounds.</strong> Neither 20 s nor 10 000 is refused on its own; together they are — and
     * raising the grace admits both.
     */
    @Test
    void valuesThatAreEachFineTogetherAreNot() {
        assertThat(async(20, 100, 30).isShutdownWithinTheStopGrace())
                .as("premise: a 20s drain alone fits")
                .isTrue();
        assertThat(async(15, 10_000, 30).isShutdownWithinTheStopGrace())
                .as("premise: the deepest permitted queue alone fits at the default drain")
                .isTrue();
        assertThat(async(20, 10_000, 30).isShutdownWithinTheStopGrace())
                .as("but 20s of drain plus a 10 000-row residue write does not fit in 30s. Raising "
                    + "either knob to a value that is individually defensible, without looking at "
                    + "the other, is exactly the edit this assertion exists to refuse")
                .isFalse();
        assertThat(async(20, 10_000, 45).isShutdownWithinTheStopGrace())
                .as("and the same pair fits in a 45s grace. THIS is why the grace is a property: "
                    + "the answer to 'I need a longer drain' is a value the operator can set, not "
                    + "a Java constant they would have to rebuild the image to change")
                .isTrue();
    }

    /**
     * <strong>The drain's {@code @Max} must not be a range no value can reach.</strong> Paired with
     * a hard-coded 30 s grace, {@code @Max(120)} made 29..120 permanently refused by the other
     * bound — a false affordance in the one place an operator looks for the permitted range. Now
     * the top of the annotation's range is reachable, by raising the grace, which is the honest
     * relationship.
     */
    @Test
    void theTopOfTheDrainsDeclaredRangeIsReachable() {
        assertThat(async(120, 100, 30).isShutdownWithinTheStopGrace())
                .as("premise: the declared maximum drain does NOT fit the default grace")
                .isFalse();
        assertThat(async(120, 100, 130).isShutdownWithinTheStopGrace())
                .as("a 120s drain is permitted by @Max and must be reachable by SOME grace, or the "
                    + "annotation is documenting a value that can never be set")
                .isTrue();
    }

    // ============================================================ the constraint, actually wired in

    @Test
    void theDefaultsBindAndAreTheDocumentedOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var async = context.getBean(MailAsyncProperties.class).async();
            assertThat(async.corePoolSize()).isEqualTo(2);
            assertThat(async.maxPoolSize()).isEqualTo(5);
            assertThat(async.queueCapacity()).isEqualTo(100);
            assertThat(async.shutdownDrainSeconds()).isEqualTo(15);
            assertThat(async.stopGraceSeconds())
                    .as("the same 30 the compose files put in stop_grace_period")
                    .isEqualTo(30);

            var deadLetter = context.getBean(MailAsyncProperties.class).deadLetter();
            assertThat(deadLetter.retentionDays()).isEqualTo(90);
            assertThat(deadLetter.maxNeverAttemptedPerHour()).isEqualTo(500);
        });
    }

    /**
     * <strong>The half that proves the {@code @AssertTrue} is more than an opinion.</strong> The
     * predicate tests above construct the record by hand; they would pass identically if
     * Hibernate Validator never discovered the method, if {@code @Validated} were removed from the
     * class, or if the nested record lost its {@code @Valid} on the enclosing component. Only a
     * context that refuses to start proves the constraint is in force — and this project's
     * documented failure shape is exactly a guard that never fires, because a guard that never
     * fires and one that works are both silent in the happy path.
     */
    @Test
    void aDrainThatOutrunsTheGraceStopsTheBoot() {
        runner.withPropertyValues("app.mail.async.shutdown-drain-seconds=29")
                .run(context -> {
                    assertThat(context)
                            .as("29s of drain plus the residue write does not fit the default 30s "
                                + "grace. If this context starts, the cross-check is not wired in "
                                + "and the app will be SIGKILLed part-way through a drain it "
                                + "believes it has time for")
                            .hasFailed();
                    assertThat(failureOf(context))
                            .as("the refusal must name the OTHER properties, or an operator sees a "
                                + "bare bound and has no idea which lever to pull")
                            .contains("shutdown-drain-seconds")
                            .contains("queue-capacity")
                            .contains("stop-grace-seconds");
                });
    }

    /**
     * <strong>The refusal must prescribe an action its reader can perform.</strong> Its previous
     * wording said "raise {@code stop_grace_period} in BOTH the compose file and
     * {@code STOP_GRACE_MS} here" — and nobody running a published image can edit a Java constant,
     * so for a self-hoster the message named a remedy that did not exist. It now names environment
     * variables, all three of which are settable from a {@code .env} file.
     */
    @Test
    void theRefusalNamesOnlyRemediesAnOperatorCanApply() {
        runner.withPropertyValues("app.mail.async.shutdown-drain-seconds=29")
                .run(context -> {
                    var message = failureOf(context);
                    assertThat(message)
                            .contains("MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS")
                            .contains("MAIL_ASYNC_QUEUE_CAPACITY")
                            .contains("APP_STOP_GRACE_SECONDS");
                    assertThat(message)
                            .as("a source-only remedy is not a remedy for the reader of this "
                                + "message. STOP_GRACE_MS was a private constant in a Java file")
                            .doesNotContain("STOP_GRACE_MS");
                });
    }

    /**
     * The same pair, made legal by the one edit the message recommends. A refusal that cannot be
     * cleared by following its own instruction is worse than none.
     */
    @Test
    void raisingTheGraceIsWhatMakesTheLongerDrainBoot() {
        runner.withPropertyValues(
                        "app.mail.async.shutdown-drain-seconds=29",
                        "app.mail.async.stop-grace-seconds=60")
                .run(context -> assertThat(context).hasNotFailed());
    }

    // ============================================================ the two claims the docs make

    /**
     * <strong>The blank, arriving the way it actually arrives in production</strong> — through the
     * real {@code ${VAR:default}} placeholders.
     *
     * <p>The whole risk is in the difference between "absent" and "present and empty": the first
     * takes the placeholder default, the second resolves to {@code ""} and the default never
     * applies. {@code .env.prod.example} and {@code docs/self-hosting.md} both promise, in bold,
     * that blanking one of these lines stops the boot rather than quietly restoring the default —
     * and until this test the promise rested on the components happening to be primitives.
     * {@link InvitePropertiesTest} has carried the same seal for its own ceilings since HD-190.
     *
     * <p>All seven rather than a representative one, because the mechanism is per-component: a
     * single boxed field would fail silently and only for its own variable. {@code @Min} does not
     * catch it either — a boxed component binds to {@code null}, the binder reads that as unbound,
     * and {@code @DefaultValue} supplies a number the operator never wrote.
     */
    @Test
    void aBlankEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        assertBlankRefused("MAIL_ASYNC_CORE_POOL", "app.mail.async.core-pool-size", "2", 3);
        assertBlankRefused("MAIL_ASYNC_MAX_POOL", "app.mail.async.max-pool-size", "5", 8);
        assertBlankRefused("MAIL_ASYNC_QUEUE_CAPACITY", "app.mail.async.queue-capacity", "100", 200);
        assertBlankRefused("MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS",
                "app.mail.async.shutdown-drain-seconds", "15", 20);
        assertBlankRefused("APP_STOP_GRACE_SECONDS", "app.mail.async.stop-grace-seconds", "30", 45);
        assertBlankRefused("MAIL_FAILED_EMAIL_RETENTION_DAYS",
                "app.mail.dead-letter.retention-days", "90", 30);
        assertBlankRefused("MAIL_NEVER_ATTEMPTED_MAX_PER_HOUR",
                "app.mail.dead-letter.max-never-attempted-per-hour", "500", 1000);
    }

    /**
     * The mechanism behind the test above, asserted directly so that a future "tidy these to
     * {@code Integer}" fails here loudly instead of turning that test into a test of nothing.
     *
     * <p>By iteration over the records' own components, so a knob added later is covered without
     * anybody remembering to come back here.
     */
    @Test
    void theComponentsArePrimitivesBecauseThatIsWhatCatchesTheBlank() {
        for (var group : MailAsyncProperties.class.getRecordComponents()) {
            for (var component : group.getType().getRecordComponents()) {
                assertThat(component.getType().isPrimitive())
                        .as("MailAsyncProperties.%s.%s must stay a primitive — boxed, a blank env "
                            + "var converts to null, the binder reads it as unbound, and "
                            + "@DefaultValue silently applies the documented default. Two of these "
                            + "numbers are the app's belief about its own stop grace and the "
                            + "ceiling on a table that has no other bound, so 'silently applied "
                            + "the default' is the one outcome an operator must never get from a "
                            + "line they edited",
                                group.getName(), component.getName())
                        .isTrue();
            }
        }
    }

    /**
     * <strong>The DC/Cloud invariant, asserted where a properties edit cannot hide.</strong> Four
     * rows of {@code docs/self-hosting.md}'s variable table now end "Identical in {@code dc} and
     * {@code cloud}", and nothing but a grep stood behind them.
     *
     * <p>Asserted as <em>absence of the key</em> rather than as a bound value, on purpose: a
     * profile file setting the same numbers would still be the deployment-shaped seam the
     * documentation promises does not exist, would still pass a value assertion, and would be one
     * character away from divergence. The mail pool's shape is a property of the SMTP host and the
     * box, never of which of the two products is running — and the stop grace least of all, since
     * its whole job is to be one number two halves of one deployment agree on.
     */
    @Test
    void noProfileFileOverridesTheMailPoolOrTheStopGrace() throws IOException {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            var content = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content)
                    .as("%s must not mention app.mail.* — the mail pool, the drain, the stop grace "
                        + "and both dead-letter bounds are identical in dc and cloud by design, "
                        + "and if a deployment ever needs a different posture it is by setting an "
                        + "environment variable, never by a second code path", file)
                    .doesNotContain("app.mail.")
                    .doesNotContain("MAIL_")
                    .doesNotContain("APP_STOP_GRACE_SECONDS");
        }
    }

    // ============================================================ the drift it cannot catch

    /**
     * <strong>The two ends of {@code APP_STOP_GRACE_SECONDS} must default to the same number.</strong>
     *
     * <p>This used to be a much sharper seal, on a hand-copied constant, and the whole point of
     * binding the grace as a property was to make it this dull: Docker Compose interpolates
     * {@code ${APP_STOP_GRACE_SECONDS:-30}} into {@code stop_grace_period} and Spring binds
     * {@code ${APP_STOP_GRACE_SECONDS:30}} into {@code app.mail.async.stop-grace-seconds}, so an
     * operator who sets the variable moves both at once and there is nothing to drift. What is left
     * is the two <em>defaults</em>, which are still two literals in two files: leave the compose
     * default at 10 and the app still believes 30, and every deploy kills the JVM mid-drain while
     * the app's own startup check passes.
     *
     * <p>Also asserted: that the compose value really is the variable form. A literal there would
     * be a value no {@code .env} could reach — {@code mem_limit} in the same file spells out that
     * rule — and it would put the deployment's grace back out of reach of the application.
     */
    @Test
    void theStopGraceDefaultsAgreeInTheComposeFileAndTheApplicationProperties() throws IOException {
        var declared = String.valueOf(prodComposeAppService().get("stop_grace_period"));
        var properties = applicationProperties();

        assertThat(declared)
                .as("""
                        docker-compose.prod.yml -> services.app.stop_grace_period must stay the \
                        ${APP_STOP_GRACE_SECONDS:-N}s form. A literal is a value the operator's \
                        .env cannot reach, and worse, a number only this file knows: the app would \
                        go on believing its own default while the container was killed at another. \
                        Found: %s""", declared)
                .matches("\\$\\{APP_STOP_GRACE_SECONDS:-\\d+}s");

        assertThat(properties)
                .as("application.properties must bind the SAME variable, or the app is not being "
                    + "told the grace the platform is giving it")
                .contains("app.mail.async.stop-grace-seconds=${APP_STOP_GRACE_SECONDS:30}");

        assertThat(secondsOf(declared))
                .as("""
                        The container's stop grace and the application's copy of it have drifted \
                        IN THEIR DEFAULTS:

                          docker-compose.prod.yml -> services.app.stop_grace_period = %s
                          application.properties  -> app.mail.async.stop-grace-seconds default = %d

                        That default is what isShutdownWithinTheStopGrace() measures \
                        app.mail.async.shutdown-drain-seconds and app.mail.async.queue-capacity \
                        against for every operator who never sets APP_STOP_GRACE_SECONDS -- which \
                        is most of them. While they disagree, that check certifies a budget the \
                        process does not get: the app starts, the assertion passes, and the next \
                        deploy SIGKILLs the JVM part-way through the mail drain. Everything still \
                        queued -- password resets and verifications whose rows are COMMITTED and \
                        whose users have already been told to check their inbox -- is then lost \
                        with no failed_email row and no log line, which is precisely the HD-207 \
                        loss this whole path exists to have removed.

                        Fix BOTH, and .env.prod.example, docs/self-hosting.md and the Quick-start \
                        compose snippet with them.""",
                        declared, propertyDefault(properties, "app.mail.async.stop-grace-seconds"))
                .isEqualTo(propertyDefault(properties, "app.mail.async.stop-grace-seconds"));
    }

    // ============================================================ fixture

    private static Async async(int drainSeconds, int queueCapacity, int stopGraceSeconds) {
        return new Async(2, 5, queueCapacity, drainSeconds, stopGraceSeconds);
    }

    /**
     * The three states of one env var, through the real placeholder: absent (the default applies),
     * present-but-empty (boot refused), and set (bound). Same helper shape as
     * {@link InvitePropertiesTest}, deliberately — this is one property of a family, not a fact
     * about any one variable.
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
                                + "operator who blanked the line is running on a number they "
                                + "believe they removed — and .env.prod.example promises them in "
                                + "bold that it stops the boot instead", envVar, fallback)
                            .hasFailed();
                    assertThat(failureOf(context)).contains(key);
                });

        withPlaceholder.withPropertyValues(envVar + "=" + set)
                .run(context -> assertThat(context)
                        .as("%s=%s must bind — a seal on the blank is worthless if the same "
                            + "wiring refuses a value an operator is told to set", envVar, set)
                        .hasNotFailed());
    }

    /**
     * Compose's duration grammar, not just the one spelling we happen to ship.
     *
     * <p>{@code stop_grace_period} accepts {@code 90s}, {@code 1m30s}, {@code 1m} and a bare number
     * of seconds. A parser that understood only {@code 30s} and {@code 30} turned a valid — and
     * <em>safer</em> — {@code 1m} into a raw {@code NumberFormatException}, which is a failure with
     * none of the explanation the assertion above spent a screen writing. Also resolves the
     * {@code ${VAR:-default}} wrapper, since the default is what this file compares.
     */
    private static long secondsOf(String graceValue) {
        var value = graceValue.trim();
        var interpolated = Pattern.compile("^\\$\\{[A-Z0-9_]+:-([^}]*)}(.*)$").matcher(value);
        if (interpolated.matches()) {
            value = interpolated.group(1) + interpolated.group(2);
        }
        if (value.matches("\\d+")) {
            return Long.parseLong(value);
        }
        var duration = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matcher(value);
        if (!duration.matches() || value.isEmpty()) {
            return fail("stop_grace_period is \"%s\", which this test cannot read. Compose accepts "
                        + "h/m/s combinations and a bare number of seconds; if the value is a new "
                        + "and valid spelling, teach this parser rather than changing the file",
                    graceValue);
        }
        return 3600 * group(duration, 1) + 60 * group(duration, 2) + group(duration, 3);
    }

    private static long group(java.util.regex.Matcher matcher, int index) {
        var value = matcher.group(index);
        return value == null ? 0 : Long.parseLong(value);
    }

    /** The literal default in a {@code key=${VAR:default}} line. */
    private static long propertyDefault(String properties, String key) {
        var matcher = Pattern.compile(Pattern.quote(key) + "=\\$\\{[A-Z0-9_]+:(\\d+)}")
                .matcher(properties);
        if (!matcher.find()) {
            return fail("application.properties no longer declares %s as ${VAR:default}. That form "
                        + "is what lets one .env variable feed both the container and the app", key);
        }
        return Long.parseLong(matcher.group(1));
    }

    private static String applicationProperties() throws IOException {
        return new String(new ClassPathResource("application.properties").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> prodComposeAppService() throws IOException {
        Map<String, Object> root = new Yaml().load(
                Files.readString(Path.of("docker-compose.prod.yml"), StandardCharsets.UTF_8));
        var services = (Map<String, Object>) root.get("services");
        return (Map<String, Object>) services.get("app");
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
    @EnableConfigurationProperties(MailAsyncProperties.class)
    static class TestConfig {}
}
