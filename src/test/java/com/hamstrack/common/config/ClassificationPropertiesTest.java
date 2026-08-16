package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClassificationProperties} must <strong>fail fast, never clamp</strong>
 * (proposal §3.9). A bogus {@code MAX_LABELS_PER_ISSUE=0} used to bind silently and
 * brick label attachment for the whole install — every payload 422ing "At most 0
 * labels per issue" with nothing in the logs. The {@code @Validated} + {@code @Min(1)}
 * pair now aborts startup instead, the same posture {@code JwtService} takes on a
 * too-short secret.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than {@code @SpringBootTest}: a
 * context that must NOT start can't be asserted on by an annotation that starts it.
 */
class ClassificationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultsAreTheDocumentedOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var props = context.getBean(ClassificationProperties.class);
            assertThat(props.maxLabelsPerIssue()).isEqualTo(20);
            assertThat(props.maxLabelsPerWorkspace()).isEqualTo(1000);
            // HD-31: application.properties, .env.prod.example, docs/self-hosting.md and
            // the docs/api-dc.md operator table all promise operators that
            // MAX_COMPONENTS_PER_PROJECT defaults to 500 — pinned here so the documented
            // default cannot drift away from the code.
            assertThat(props.maxComponentsPerProject()).isEqualTo(500);
            // HD-32: the same promise for the two version caps. maxVersionLinksPerIssue
            // is enforced PER LINK TYPE (an issue may carry 20 fix AND 20 affects
            // versions — independent budgets), and maxVersionsPerProject counts
            // ARCHIVED and RELEASED rows so the ceiling can't be walked around by
            // create → archive → repeat.
            assertThat(props.maxVersionLinksPerIssue()).isEqualTo(20);
            assertThat(props.maxVersionsPerProject()).isEqualTo(500);
        });
    }

    @Test
    void zeroLabelsPerIssueRefusesToStart() {
        runner.withPropertyValues("app.classification.max-labels-per-issue=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        // the field name lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining("maxLabelsPerIssue"));
    }

    @Test
    void outOfRangeValuesRefuseToStart() {
        runner.withPropertyValues("app.classification.max-labels-per-issue=101")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.classification.max-labels-per-workspace=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.classification.max-labels-per-workspace=100001")
                .run(context -> assertThat(context).hasFailed());
        // HD-31: the same fail-fast posture for the component catalog bound. A silently
        // clamped 0 would 422 every component create for the whole install, with
        // nothing in the logs — exactly the failure mode @Validated exists to prevent.
        runner.withPropertyValues("app.classification.max-components-per-project=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        // the field name lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining("maxComponentsPerProject"));
        runner.withPropertyValues("app.classification.max-components-per-project=100001")
                .run(context -> assertThat(context).hasFailed());

        // HD-32. max-version-links-per-issue is the only cap in this record whose
        // documented fail-fast contract had never been exercised: a silently bound 0
        // would 422 every fix/affects version attachment for the whole install ("At most
        // 0 fix versions per issue"), which is precisely the failure mode @Validated
        // exists to prevent — and it is invisible in the logs.
        runner.withPropertyValues("app.classification.max-version-links-per-issue=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        // the field name lives on the deepest cause (BindValidationException)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("maxVersionLinksPerIssue"));
        runner.withPropertyValues("app.classification.max-version-links-per-issue=101")
                .run(context -> assertThat(context).hasFailed());
        // …and the version catalog bound, same posture as the component one.
        runner.withPropertyValues("app.classification.max-versions-per-project=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("maxVersionsPerProject"));
        runner.withPropertyValues("app.classification.max-versions-per-project=100001")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void inRangeOverridesBindNormally() {
        runner.withPropertyValues(
                        "app.classification.max-labels-per-issue=2",
                        "app.classification.max-labels-per-workspace=5",
                        "app.classification.max-components-per-project=3",
                        "app.classification.max-version-links-per-issue=4",
                        "app.classification.max-versions-per-project=6")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var props = context.getBean(ClassificationProperties.class);
                    assertThat(props.maxLabelsPerIssue()).isEqualTo(2);
                    assertThat(props.maxLabelsPerWorkspace()).isEqualTo(5);
                    assertThat(props.maxComponentsPerProject()).isEqualTo(3);
                    assertThat(props.maxVersionLinksPerIssue()).isEqualTo(4);
                    assertThat(props.maxVersionsPerProject()).isEqualTo(6);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClassificationProperties.class)
    static class TestConfig {}
}
