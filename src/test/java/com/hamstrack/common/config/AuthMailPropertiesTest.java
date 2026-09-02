package com.hamstrack.common.config;

import com.hamstrack.common.ratelimit.MailThrottlePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthMailProperties} held to the {@link InvitePropertiesTest} standard (HD-202) — the two
 * numbers that bound how much mail one address can be made to receive from the login screen.
 *
 * <p><strong>What makes these worth a file of their own.</strong> Every endpoint they govern is
 * unauthenticated and open in every deployment, and on most of them — uniquely among this
 * project's limiters — <em>the refusal is invisible</em>. Nothing in the response, no 429, no log
 * line: those endpoints answer one uniform sentence, which is exactly what stops them being an
 * account-enumeration oracle. So a number set wrongly here produces no error anywhere. Too
 * generous and the ceiling is decorative; too strict and users silently stop receiving
 * password-reset links, which reads as a mail-delivery fault and is not one. That is the case for
 * asserting the defaults, the ranges and the wiring rather than trusting five documents to agree.
 */
class AuthMailPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    /**
     * The documented defaults, quoted verbatim by {@code application.properties},
     * {@code .env.prod.example} and {@code docs/self-hosting.md}. A drifted default is a documented
     * number that is not the one running.
     */
    @Test
    void theDefaultsAreTheDocumentedOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AuthMailProperties.class);
            assertThat(properties.recipientCooldownMinutes())
                    .as("one minute: what a person who mistyped their address twice needs, and "
                        + "what a script cannot use")
                    .isEqualTo(1);
            assertThat(properties.maxPerRecipientPerWindow())
                    .as("five per window, per kind of mail — enough for a spam folder, a typo and "
                        + "a second device, finite for a flood")
                    .isEqualTo(5);
        });
    }

    /**
     * <strong>The windows are constants, they must stay constants, and they are deliberately
     * DIFFERENT widths.</strong> A width here is not a capacity knob: on password reset it is how
     * long somebody who fills the cap can withhold a named person's own recovery, silently.
     *
     * <p>The asymmetry is the assertion worth having, because the obvious "tidy-up" is to collapse
     * them back into one constant. Reset gets the quarter-hour because sustained denial is
     * achievable at every width, so the width buys only the mail bound and costs only the length of
     * a hit-and-run lockout; verification keeps the hour because it locks nobody out of an account
     * they already hold, and it is the budget {@code register} spends — the flow most exposed to a
     * mail bomb aimed at one inbox through many spellings.
     *
     * <p>Both are also inside {@code MailThrottlePolicy.MAX_CEILING_WINDOW}, because the
     * {@code mail_send_events} retention is checked against that constant and a ceiling counting
     * over a wider window would count rows the sweep has already deleted.
     */
    @Test
    void theTwoCeilingWindowsAreFixedDifferentAndNotOperatorKnobs() {
        assertThat(AuthMailProperties.PASSWORD_RESET_CEILING_WINDOW)
                .as("a quarter of an hour: 20 reset mails/hour to one inbox stays a factor of 3 "
                    + "under the deliverability number, and 60 minutes of silent lockout bought "
                    + "for five requests does not")
                .isEqualTo(Duration.ofMinutes(15));

        assertThat(AuthMailProperties.VERIFICATION_CEILING_WINDOW)
                .as("an hour: nobody is locked out of an account they already hold, so the tighter "
                    + "MAIL bound is the half of the trade worth having here")
                .isEqualTo(Duration.ofHours(1));

        assertThat(AuthMailProperties.PASSWORD_RESET_CEILING_WINDOW)
                .as("the two must not be collapsed into one constant — the reasons they have the "
                    + "widths they do point in opposite directions, and a single window would be "
                    + "wrong for one of the two flows whichever value it took")
                .isNotEqualTo(AuthMailProperties.VERIFICATION_CEILING_WINDOW);

        for (var window : new Duration[]{AuthMailProperties.PASSWORD_RESET_CEILING_WINDOW,
                AuthMailProperties.VERIFICATION_CEILING_WINDOW,
                AuthMailProperties.ANONYMOUS_EVENT_RETENTION}) {
            assertThat(window).isPositive();
        }

        // A WIDTH, not the word "window": maxPerRecipientPerWindow is a count and is correctly a
        // property. What must never become one is a duration — spelled as a Duration, or as an int
        // of minutes/hours/days, or as a retention.
        assertThat(AuthMailProperties.class.getRecordComponents())
                .as("no WIDTH may become a property — an operator raising one to a day would be "
                    + "lengthening an account-recovery outage they cannot see, in the belief they "
                    + "were loosening a rate limit. The cap is a knob; the window is a decision")
                .noneMatch(component -> component.getType() == Duration.class
                                        || component.getName()
                                                .matches(".*[Ww]indow(Minutes|Hours|Days|Seconds)")
                                        || component.getName().toLowerCase().contains("retention"));
    }

    /**
     * <strong>The anonymous retention must outlast every ceiling window it feeds</strong>, or the
     * sweep silently shortens the ceiling to itself — the exact failure the invite retention has an
     * {@code @AssertTrue} for. It is a constant rather than a property, so nothing binds it and
     * nothing else would notice; {@code MailSendEventRetention} refuses to start on it, and this is
     * the cheap version of the same check.
     */
    @Test
    void theAnonymousRetentionOutlastsEveryCeilingItFeeds() {
        assertThat(AuthMailProperties.ANONYMOUS_EVENT_RETENTION)
                .as("anonymous mail_send_events rows are swept on this and counted by the "
                    + "recipient ceilings, so it must clear the widest width any ceiling is "
                    + "allowed to declare — not merely today's widest actual one")
                .isGreaterThan(MailThrottlePolicy.MAX_CEILING_WINDOW);
    }

    /**
     * <strong>There is deliberately no "unlimited", and {@code 0} is out of range on both.</strong>
     * {@code 0} is what an operator writes to mean "off"; here it would mean "refuse every reset
     * link on the instance", which is an outage with no error in it. The off switch is
     * {@code app.rate-limit.enabled}.
     */
    @Test
    void zeroIsOutOfRangeOnBothOfThem() {
        assertRejected("app.auth-mail.recipient-cooldown-minutes=0", "recipientCooldownMinutes");
        assertRejected("app.auth-mail.max-per-recipient-per-window=0", "maxPerRecipientPerWindow");
        assertRejected("app.auth-mail.recipient-cooldown-minutes=-1", "recipientCooldownMinutes");
    }

    /**
     * The documented ranges are inclusive at both ends and one over refuses to start, never clamps.
     *
     * <p>The cooldown tops out at <strong>14</strong> — one minute INSIDE the narrowest of the
     * fixed windows, since one property feeds every policy — for a stated reason: a cooldown as
     * wide as the window it sits inside refuses every send that would have counted towards that
     * window's volume cap, so the cap becomes unreachable and the second ceiling is decorative.
     * {@code MailThrottlePolicy} refuses to build such a policy, and this bound is what stops an
     * operator reaching it.
     *
     * <p><strong>15 must now be rejected, and that is the point of the number moving.</strong> The
     * bound was 15 against a guard that only refused a STRICTLY wider cooldown, so exactly 15 was
     * in range, accepted, and produced the unreachable cap the guard's own message says it exists
     * to prevent.
     */
    @Test
    void theDocumentedRangesBootAndOneOverRefusesToStart() {
        assertBoots("app.auth-mail.recipient-cooldown-minutes", 1, 14);
        assertRejected("app.auth-mail.recipient-cooldown-minutes=15", "recipientCooldownMinutes");
        assertRejected("app.auth-mail.recipient-cooldown-minutes=16", "recipientCooldownMinutes");

        assertBoots("app.auth-mail.max-per-recipient-per-window", 1, 1000);
        assertRejected("app.auth-mail.max-per-recipient-per-window=1001",
                "maxPerRecipientPerWindow");
    }

    /**
     * <strong>The {@code @Max} on the cooldown is a hand-copy of a {@link Duration}, and this is
     * what keeps the copy honest.</strong> An annotation cannot read {@code Duration.toMinutes()},
     * so the bound is a literal. Nothing keeps the two equal by construction — the policy
     * constructor catches a stale copy at bean creation, which is loud but late, and this catches
     * it here.
     *
     * <p>Read by reflection rather than restated, so that this file cannot itself become the third
     * hand-copy.
     */
    @Test
    void theCooldownsMaxIsOneMinuteInsideTheNarrowestCeilingWindow() throws Exception {
        // From the FIELD, not the record component: @Max declares no RECORD_COMPONENT target, so
        // it propagates to the field and the accessor and is invisible on the component itself.
        long max = AuthMailProperties.class
                .getDeclaredField("recipientCooldownMinutes")
                .getAnnotation(jakarta.validation.constraints.Max.class)
                .value();

        assertThat(max)
                .as("@Max on app.auth-mail.recipient-cooldown-minutes is %d, but the narrowest "
                    + "fixed ceiling window is %d minutes and the bound must sit one minute "
                    + "INSIDE it. A cooldown that merely EQUALS the window it sits inside already "
                    + "makes that window's volume cap unreachable — the first send starts a "
                    + "cooldown running to the far edge of the window, so nothing is left to "
                    + "count — and MailThrottlePolicy refuses to build such a policy. The bound "
                    + "was the window's own width until review, which put an in-range, accepted, "
                    + "unreachable setting one keystroke away. Move both together.",
                        max, AuthMailProperties.PASSWORD_RESET_CEILING_WINDOW.toMinutes())
                .isEqualTo(Math.min(AuthMailProperties.PASSWORD_RESET_CEILING_WINDOW.toMinutes(),
                        AuthMailProperties.VERIFICATION_CEILING_WINDOW.toMinutes()) - 1);
    }

    /**
     * <strong>The blank line.</strong> {@code AUTH_MAIL_MAX_PER_RECIPIENT_PER_WINDOW=} with nothing
     * after it is present-but-empty: the placeholder resolves to {@code ""} and the {@code :5}
     * default never gets a turn. Refusing the boot is the only safe answer — an operator who blanked
     * a line must not end up running a ceiling they believe they removed, least of all one whose
     * refusals are invisible.
     */
    @Test
    void aBlankEnvironmentVariableRefusesTheBootRatherThanApplyingTheDefault() {
        assertBlankRefused("AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES",
                "app.auth-mail.recipient-cooldown-minutes", "1", 5);
        assertBlankRefused("AUTH_MAIL_MAX_PER_RECIPIENT_PER_WINDOW",
                "app.auth-mail.max-per-recipient-per-window", "5", 20);
    }

    /**
     * The mechanism behind the blank test. A boxed component binds to {@code null}, the binder reads
     * that as unbound, {@code @DefaultValue} applies, and validation then passes on a value the
     * operator never wrote — so a future "tidy them to {@code Integer}" must fail here rather than
     * quietly turning the test above into a test of nothing. By iteration, so a third property is
     * covered without anybody remembering to come back.
     */
    @Test
    void theComponentsArePrimitivesBecauseThatIsWhatCatchesTheBlank() {
        for (var component : AuthMailProperties.class.getRecordComponents()) {
            assertThat(component.getType())
                    .as("AuthMailProperties.%s must stay a primitive int", component.getName())
                    .isEqualTo(int.class);
        }
    }

    @Test
    void theBasePropertiesFileWiresTheDocumentedPlaceholders() throws Exception {
        assertThat(resource("application.properties"))
                .as("the variable names and fallbacks are what the blank test simulates and what "
                    + ".env.prod.example and the operator table promise")
                .contains("app.auth-mail.recipient-cooldown-minutes="
                          + "${AUTH_MAIL_RECIPIENT_COOLDOWN_MINUTES:1}")
                .contains("app.auth-mail.max-per-recipient-per-window="
                          + "${AUTH_MAIL_MAX_PER_RECIPIENT_PER_WINDOW:5}");
    }

    /**
     * <strong>The DC/Cloud invariant.</strong> Asserted as <em>absence of the key</em> rather than
     * as a bound value: a profile file setting the same 1/5 would still be the deployment-shaped
     * seam this project forbids, would still pass a value assertion, and would be one character
     * away from divergence.
     *
     * <p>The argument is {@link InviteProperties}', and it is stronger here: these two endpoints are
     * open in <em>both</em> modes whatever {@code PUBLIC_SIGNUP_ENABLED} says, so there is not even
     * the appearance of a DC install with a smaller exposure.
     */
    @Test
    void noProfileFileOverridesTheAuthMailCeilings() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            assertThat(resource(file))
                    .as("%s must not mention app.auth-mail.* — if a deployment ever needs a "
                        + "different posture it is by lowering a number, never by a second code "
                        + "path", file)
                    .doesNotContain("app.auth-mail.")
                    .doesNotContain("AUTH_MAIL_");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String resource(String name) throws Exception {
        return new String(new ClassPathResource(name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

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
                                + "and the :%s default never applies", envVar, fallback)
                            .hasFailed();
                    assertThat(failureOf(context)).contains(key);
                });

        withPlaceholder.withPropertyValues(envVar + "=" + set)
                .run(context -> assertThat(context)
                        .as("%s=%s must bind", envVar, set)
                        .hasNotFailed());
    }

    private String failureOf(AssertableApplicationContext context) {
        var out = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            out.append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthMailProperties.class)
    static class TestConfig {}
}
