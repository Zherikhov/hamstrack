package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CspProperties} held to the {@link SearchPropertiesTest} standard (HD-264).
 *
 * <p>One of these constraints is unlike every other bound in this family, and it is the reason
 * this class matters more than a defaults check: {@code app.csp.policy} is written
 * <strong>verbatim into a response header on every response the instance serves</strong>. A value
 * carrying a carriage return does not break the policy — it splits the response. So the assertion
 * that a control character aborts the boot is a response-splitting guard, not a syntax check, and
 * a syntactically broken policy is deliberately still allowed to boot (browsers ignore a bad
 * directive and an operator can see that; nobody can see a header injection).
 *
 * <p>An {@link ApplicationContextRunner} rather than {@code @SpringBootTest}, because a context
 * that must NOT start cannot be asserted on by an annotation that starts it.
 */
class CspPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    /**
     * The base defaults, which {@code application.properties}, {@code .env.prod.example} and the
     * operator tables all promise. The sink default is the <em>base</em> one (off): the cloud
     * profile is what flips it, and that split is asserted below.
     */
    @Test
    void theDefaultsAreTheDocumentedOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(CspProperties.class);
            assertThat(properties.reportOnlyEnabled())
                    .as("the header ships in BOTH modes — a report-only policy with no sink still "
                        + "writes to the browser console, which is a self-hoster's evidence")
                    .isTrue();
            assertThat(properties.sinkEnabled())
                    .as("the BASE default is off; application-cloud.properties turns it on. An "
                        + "unauthenticated public endpoint must not arrive with an upgrade")
                    .isFalse();
            assertThat(properties.policy())
                    .as("empty means the built-in policy, which contains no deployment-specific "
                        + "value and is therefore correct everywhere without an override")
                    .isEmpty();
            assertThat(properties.reportsPerMinutePerIp()).isEqualTo(60);
            assertThat(properties.reportsPerMinute()).isEqualTo(600);
        });
    }

    /**
     * <strong>The one that is not about ranges.</strong> The policy string is emitted verbatim in a
     * header, so CR, LF and NUL in an operator's value are a response-splitting primitive handed to
     * whoever set the variable — and, once the value has been carried into a running instance,
     * there is nothing downstream that could refuse it. It fails the boot instead.
     */
    @Test
    void aPolicyCarryingAControlCharacterRefusesToStart() {
        assertRejected("app.csp.policy=default-src 'self'\r\nSet-Cookie: a=b");
        assertRejected("app.csp.policy=default-src 'self'\nX-Injected: 1");
        assertRejected("app.csp.policy=default-src\t'self'");
        assertRejected("app.csp.policy=default-src 'self'" + (char) 0x7f);
        // NUL is deliberately NOT among these. It cannot reach a running instance through the door
        // this guard is about — a POSIX environment variable's value is a NUL-terminated string, so
        // there is no way to put one in CSP_POLICY — and Spring's test property machinery drops it
        // before binding, so an assertion here would be a claim about the harness rather than
        // about the application. Verified rather than assumed: the DEL above is refused.
    }

    /**
     * And the direction that must still boot, so the guard above is not quietly a ban on
     * overriding the policy at all: an ordinary override binds, and so does a <em>syntactically
     * wrong</em> one. A bad directive is visible to the operator who wrote it and is ignored by
     * browsers; refusing it here would put this file in the business of validating a grammar four
     * engines disagree about.
     */
    @Test
    void anOrdinaryOverrideBindsAndSoDoesASyntacticallyWrongOne() {
        runner.withPropertyValues("app.csp.policy=default-src 'none'; img-src 'self'")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CspProperties.class).policy())
                            .isEqualTo("default-src 'none'; img-src 'self'");
                });
        runner.withPropertyValues("app.csp.policy=deflaut-src selff")
                .run(context -> assertThat(context)
                        .as("a typo in a directive name is silently ignored by browsers and is the "
                            + "operator's to see; this class guards the response, not the grammar")
                        .hasNotFailed());
    }

    /**
     * A whitespace-only value is a typo, not a policy, and must read as "no override" — otherwise
     * an operator who left {@code CSP_POLICY=" "} in an env file ships a header with an empty
     * policy and no report-uri, which measures nothing and looks configured.
     */
    @Test
    void aBlankOverrideMeansNoOverride() {
        runner.withPropertyValues("app.csp.policy=   ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CspProperties.class).policy()).isEmpty();
                });
    }

    @Test
    void aPolicyPastTheDocumentedLengthRefusesToStart() {
        assertRejected("app.csp.policy=" + "a".repeat(2001));
        runner.withPropertyValues("app.csp.policy=" + "a".repeat(2000))
                .run(context -> assertThat(context)
                        .as("the documented bound is inclusive — an operator who follows it to the "
                            + "letter must be able to boot")
                        .hasNotFailed());
    }

    @Test
    void theBudgetRangesAreInclusiveAndOneOverRefusesToStart() {
        for (var value : new int[]{1, 10_000}) {
            runner.withPropertyValues("app.csp.reports-per-minute-per-ip=" + value)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(CspProperties.class).reportsPerMinutePerIp())
                                .isEqualTo(value);
                    });
        }
        assertRejected("app.csp.reports-per-minute-per-ip=0");
        assertRejected("app.csp.reports-per-minute-per-ip=10001");
        assertRejected("app.csp.reports-per-minute=0");
        assertRejected("app.csp.reports-per-minute=100001");
    }

    /**
     * The blank arriving the way it actually arrives in production — through the real placeholder.
     * "Absent" and "present and empty" are different, and only the first may take the default.
     */
    @Test
    void aBlankBudgetEnvVarStopsTheBootInsteadOfQuietlyMeaningTheDefault() {
        var withPlaceholder = runner.withPropertyValues(
                "app.csp.reports-per-minute-per-ip=${CSP_REPORTS_PER_MINUTE_PER_IP:60}");

        withPlaceholder.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CspProperties.class).reportsPerMinutePerIp()).isEqualTo(60);
        });
        withPlaceholder.withPropertyValues("CSP_REPORTS_PER_MINUTE_PER_IP=")
                .run(context -> assertThat(context)
                        .as("present-but-empty resolves to \"\", the :60 default never applies, and "
                            + "an operator who blanked the line would otherwise be running with a "
                            + "budget they think they removed")
                        .hasFailed());
    }

    /**
     * Everything above proves how <em>a</em> placeholder behaves. This pins that the shipped file
     * is wired with exactly those variable names and fallbacks — the ones {@code .env.prod.example}
     * and {@code docs/self-hosting.md} promise — because a differently-named variable makes every
     * assertion above true of nothing.
     */
    @Test
    void theBasePropertiesFileWiresTheDocumentedPlaceholders() throws Exception {
        var content = read("application.properties");
        assertThat(content)
                .contains("app.csp.report-only-enabled=${CSP_REPORT_ONLY_ENABLED:true}")
                .contains("app.csp.sink-enabled=${CSP_REPORT_SINK_ENABLED:false}")
                .contains("app.csp.policy=${CSP_POLICY:}")
                .contains("app.csp.reports-per-minute-per-ip=${CSP_REPORTS_PER_MINUTE_PER_IP:60}")
                .contains("app.csp.reports-per-minute=${CSP_REPORTS_PER_MINUTE:600}");
    }

    /**
     * <strong>The DC/Cloud invariant, in the shape this feature actually has it.</strong> Exactly
     * ONE key is profile-defaulted — the sink — and the other four must not be, because the header,
     * the policy and the budgets are product properties. Asserted per key rather than as "no
     * mention of app.csp", which would be the wrong assertion here: a profile file that carried the
     * policy string would be a version of ADR-0035 nobody agreed to.
     */
    @Test
    void onlyTheSinkIsProfileDefaulted() throws Exception {
        for (var profile : new String[]{"dc", "cloud"}) {
            var file = "application-" + profile + ".properties";
            var content = read(file);
            assertThat(content)
                    .as("%s must default the sink — that is the one value this feature varies by "
                        + "deployment model, and it varies by PROPERTY rather than by a branch", file)
                    .contains("app.csp.sink-enabled=");
            // Assignments only, not mentions: these files explain themselves in prose, and a
            // comment naming the property it is NOT setting is the opposite of a violation.
            assertThat(assignedKeys(content))
                    .as("%s must not vary the header, the policy or the budgets: a policy is a "
                        + "statement about the JS bundle, which is the same bundle in both modes "
                        + "(ADR-0035), and the budgets are the same door's", file)
                    .doesNotContain("app.csp.report-only-enabled", "app.csp.policy",
                            "app.csp.reports-per-minute", "app.csp.reports-per-minute-per-ip");
        }
        assertThat(read("application-dc.properties"))
                .as("off on dc: an unauthenticated public endpoint must not arrive with an upgrade")
                .contains("app.csp.sink-enabled=${CSP_REPORT_SINK_ENABLED:false}");
        assertThat(read("application-cloud.properties"))
                .as("on in cloud: a header with no sink produces console-only evidence on an "
                    + "instance whose console nobody is watching")
                .contains("app.csp.sink-enabled=${CSP_REPORT_SINK_ENABLED:true}");
    }

    /**
     * The mechanism behind the blank test, asserted directly so a later "tidy it to {@code Integer}"
     * fails here loudly instead of turning that test into a test of nothing. Same reason
     * {@code SearchProperties} pins its own.
     */
    @Test
    void theBudgetComponentsArePrimitivesBecauseThatIsWhatCatchesTheBlank() throws Exception {
        for (var field : new String[]{"reportsPerMinutePerIp", "reportsPerMinute"}) {
            assertThat(CspProperties.class.getDeclaredField(field).getType())
                    .as("%s must stay a primitive int — boxed, a blank env var converts to null, "
                        + "the binder reads it as unbound, and @DefaultValue silently applies the "
                        + "documented default", field)
                    .isEqualTo(int.class);
        }
    }

    /** The property keys a file actually SETS — enabled assignments, never prose. */
    private static java.util.List<String> assignedKeys(String content) {
        return content.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#") && line.contains("="))
                .map(line -> line.substring(0, line.indexOf('=')))
                .toList();
    }

    private static String read(String classpathFile) throws Exception {
        return new String(new ClassPathResource(classpathFile).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private void assertRejected(String property) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context).as(property).hasFailed());
    }

    /** Every message in the failure chain, for an assertion that wants to look at all of them. */
    @SuppressWarnings("unused")
    private String failureOf(AssertableApplicationContext context) {
        var out = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            out.append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CspProperties.class)
    static class TestConfig {}
}
