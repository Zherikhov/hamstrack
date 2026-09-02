package com.hamstrack.common.config;

import com.hamstrack.common.web.MetaController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-193 — {@code app.privacy.contact-email} and the {@code privacyContactEmail} it puts on
 * the public {@code GET /api/meta}.
 *
 * <p><strong>The contract is "empty string when unset, never null", and it is load-bearing on
 * the client.</strong> The Account page renders its deletion section whether or not an address
 * is configured (proposal section 9.3) and chooses between a {@code mailto:} link and "your
 * installation's administrator handles this" by looking at that string. A {@code null} there is
 * not a smaller version of an empty string: it is a 500 from this endpoint if the nested record
 * is unbound, and a rendered "null" or a crashed branch if it reaches the SPA.
 *
 * <p>Two things could take it away, neither of which any other test notices, because an
 * unconfigured install is the DC <em>default</em> and nothing else in the suite asks this
 * endpoint what it says when nobody set the property:
 * <ul>
 *   <li>dropping {@code @DefaultValue} from the {@code Privacy privacy} component — the binder
 *       then leaves the whole nested record {@code null} on an install that sets nothing, and
 *       {@code meta()} throws;</li>
 *   <li>dropping the canonical constructor's coalesce — {@code PRIVACY_CONTACT_EMAIL=} in an
 *       env file binds an empty value, but a whitespace-only one would then count as
 *       "configured" and publish a {@code mailto:} link to nowhere.</li>
 * </ul>
 *
 * <h2>And the constraint, watched rejecting something</h2>
 * The value is published verbatim on an unauthenticated endpoint and interpolated into a
 * {@code mailto:} by the SPA, so {@code AppProperties.Privacy.contactEmail} carries {@code @Email},
 * a {@code @Pattern} and a {@code @Size}, and {@code AppProperties} carries the {@code @Validated}
 * that makes any of them fire at all.
 *
 * <p><strong>Every part of that chain is watched refusing something, because a validation nobody
 * has seen reject anything is a belief rather than a guard</strong> — a constraint on a class with
 * no {@code @Validated}, or on a nested record no cascade reaches, is silent in exactly the happy
 * path the other tests here cover, and looks identical to one that works. So the malformed cases
 * assert a context that <em>fails to start</em> and a message naming the environment variable, and
 * the legal ones assert the boot is untouched: what is refused is a value meant to be an address
 * and not one, never the operator who published none.
 *
 * <p><strong>Each constraint gets its own method because they catch disjoint sets, which is only
 * obvious once you have watched one of them let a value through.</strong> {@code @Email} catches
 * the typo; it does <em>not</em> catch {@code privacy@example.org&bcc=victim}, which is a valid
 * RFC 5322 address and a two-header {@code mailto:} — that one was observed passing here against
 * {@code @Email} alone, and is what the {@code @Pattern} exists for; and it does not catch a
 * perfectly formed 300-character address either, which is {@code @Size}'s case and the standing
 * rule of {@code EmailLengthBoundTest}. One merged method would let a future edit delete one
 * constraint and keep a green test.
 */
class PrivacyContactEmailTest {

    /**
     * {@code app.legal.*} and {@code app.registration.*} are set on every run because
     * {@code meta()} dereferences them and neither carries a default of its own — they are
     * the endpoint's existing fields, not this ticket's, and are supplied here so the
     * assertions below are about the privacy one alone.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "app.legal.public-landing-enabled=true",
                    "app.legal.terms-acceptance-required=true",
                    "app.registration.public-signup-enabled=true");

    /** The DC default: nobody set the property, and the endpoint still answers a string. */
    @Test
    void unsetIsAnEmptyStringAndNeverNull() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var privacy = context.getBean(AppProperties.class).privacy();
            assertThat(privacy)
                    .as("the nested record must bind from defaults when no app.privacy.* property "
                        + "exists — unbound it is null and GET /api/meta 500s on an install that "
                        + "simply never configured an address, i.e. every fresh DC one")
                    .isNotNull();
            assertThat(privacy.contactEmail()).isEmpty();
            assertThat(context.getBean(MetaController.class).meta().privacyContactEmail())
                    .as("empty string when unset, never null — the SPA branches on it to decide "
                        + "between a mailto: link and the administrator fallback")
                    .isEmpty();
        });
    }

    /** What an operator who set it gets: their address, published verbatim. */
    @Test
    void aConfiguredAddressIsPublishedVerbatim() {
        runner.withPropertyValues("app.privacy.contact-email=privacy@example.org")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MetaController.class).meta().privacyContactEmail())
                            .isEqualTo("privacy@example.org");
                });
    }

    /**
     * A whitespace-only value is a typo, and counting it as configured would replace the
     * fallback sentence with a mail link to nowhere — a dead end in place of an instruction.
     */
    @Test
    void aBlankValueIsUnsetRatherThanConfigured() {
        runner.withPropertyValues("app.privacy.contact-email=   ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MetaController.class).meta().privacyContactEmail()).isEmpty();
                });
    }

    // ==================================================== the constraint, seen to refuse

    /**
     * <strong>A value that is not an address must stop the process, not ship.</strong> The
     * ordinary typo: an operator who meant to publish a contact address and published a string
     * nobody can write to. This is {@code @Email}'s case.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "privacy.example.org",
            "privacy@",
            "@example.org",
            "privacy@@example.org"
    })
    void anAddressThatIsNotOneAbortsStartup(String configured) {
        runner.withPropertyValues("app.privacy.contact-email=" + configured)
                .run(context -> {
                    assertThat(context)
                            .as("\"%s\" is not an address, and it would be served unauthenticated "
                                + "on GET /api/meta and pasted into a mailto:. @Email on the "
                                + "component plus @Validated on AppProperties must refuse it at "
                                + "binding time — if this passes, one of the two is missing and "
                                + "the other is decoration", configured)
                            .hasFailed();
                    assertThat(failureOf(context))
                            .as("the refusal must name the variable the operator actually set, or "
                                + "they get a bound on a property name they never typed")
                            .contains("PRIVACY_CONTACT_EMAIL");
                });
    }

    /**
     * <strong>A value that IS an address by {@code @Email}'s reckoning and still is not one by the
     * time a browser reads it.</strong> This is the case the review actually asked about, and it
     * is the reason {@code @Email} alone is not the fix: Hibernate Validator's email domain is an
     * RFC 5322 atom, whose character set includes {@code ? & # % =}, so
     * {@code privacy@example.org&bcc=victim} is a <em>valid address</em> that {@code @Email}
     * passes — observed, not assumed: it started green here against {@code @Email} alone. The SPA
     * builds {@code mailto:<value>}, where those separators introduce extra mail headers, so a
     * user who clicks "email us about deleting my account" silently copies a third party. The
     * {@code @Pattern} is what refuses these, and this method is why removing it cannot be quiet.
     *
     * <p>No privilege boundary is crossed — it is the operator's own instance and their own
     * value — which is exactly why nothing downstream would ever notice, and why the boot is
     * where it has to be noticed.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "privacy@example.org&bcc=victim@example.net",
            "privacy@example.org?cc=victim@example.net",
            "privacy@example.org?subject=hi",
            "privacy@example.org,victim@example.net",
            "privacy@example.org%0Abcc:victim@example.net",
            "Privacy Team <privacy@example.org>"
    })
    void anAddressCarryingMailtoSeparatorsAbortsStartup(String configured) {
        runner.withPropertyValues("app.privacy.contact-email=" + configured)
                .run(context -> {
                    assertThat(context)
                            .as("\"%s\" survives @Email (or would, for the ones with no second @) "
                                + "and becomes a mailto: with more than one header in it. The "
                                + "@Pattern is the constraint that has to refuse this; if this "
                                + "passes, the value ships and the link composes the wrong "
                                + "message", configured)
                            .hasFailed();
                    assertThat(failureOf(context))
                            .as("the refusal must name the variable the operator actually set")
                            .contains("PRIVACY_CONTACT_EMAIL");
                });
    }

    /**
     * <strong>The third constraint, which neither of the other two implies.</strong>
     * {@code @Email} bounds the local part at 64 and the domain at 255, so it accepts roughly 320
     * characters and a well-formed 300-character address passes both it and the mailto: pattern.
     * {@code EmailLengthBoundTest} makes that the rule for every address field in the product;
     * this is the same rule reaching a value that lands in no column, which is exactly the case
     * that reads as exempt and is not.
     */
    @Test
    void anOverlongButWellFormedAddressAbortsStartup() {
        var label = "b".repeat(59);
        var overlong = "a".repeat(60) + "@" + label + "." + label + "." + label + "."
                       + "c".repeat(55) + ".com";
        assertThat(overlong).hasSize(300);

        runner.withPropertyValues("app.privacy.contact-email=" + overlong)
                .run(context -> {
                    assertThat(context)
                            .as("a 300-character address is well-formed, passes @Email and carries "
                                + "no mailto: separator — @Size is the only thing that refuses it")
                            .hasFailed();
                    assertThat(failureOf(context)).contains("PRIVACY_CONTACT_EMAIL");
                });
    }

    /**
     * The other half of the same guard: the constraints may not cost the documented "unset" state.
     * Empty is what every default DC install binds ({@code app.privacy.contact-email=} resolves
     * the placeholder to nothing), and a whitespace-only value is stripped to empty by the
     * canonical constructor <em>before</em> validation runs, so it is unset rather than malformed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "privacy@example.org", "privacy+deletion@sub.example.co.uk"})
    void everyLegalValueStillBoots(String configured) {
        runner.withPropertyValues("app.privacy.contact-email=" + configured)
                .run(context -> assertThat(context)
                        .as("\"%s\" must bind. A constraint that also refuses the unset state "
                            + "would abort every install that never configured an address, which "
                            + "is the default one", configured)
                        .hasNotFailed());
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
    @EnableConfigurationProperties(AppProperties.class)
    @Import(MetaController.class)
    static class TestConfig {}
}
