package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-22 §3.7 — the four {@code app.agile.*} knobs must <strong>fail fast, never clamp</strong>,
 * the same posture {@link ClassificationProperties} takes (and this test mirrors
 * {@code ClassificationPropertiesTest}'s {@link ApplicationContextRunner} shape: a context
 * that must NOT start cannot be asserted on by an annotation that starts it).
 *
 * <p>Two things are pinned here that nothing else in the suite covers:
 *
 * <ul>
 *   <li>the <strong>documented defaults</strong>. {@code application.properties},
 *       {@code .env.prod.example}, {@code docs/self-hosting.md}, {@code docs/api-dc.md} and
 *       {@code openapi.yaml} all promise operators 300 / 20 / 14 / 100, and two of them
 *       ({@code sectionCap}, {@code bulkMoveCap}) are echoed to clients in the planning
 *       view — a drifted default silently changes a documented API response.</li>
 *   <li>the <strong>cross-field</strong> {@code @AssertTrue isPlanningViewBounded()}. It is
 *       the codebase's only multi-field property rule, the per-field maxima are not
 *       composable (2000 × 101 ≈ 202 000 assembled issues in ONE unpaged
 *       {@code GET …/backlog}), and a cross-field {@code @AssertTrue} is exactly the kind
 *       of validation that stops firing without a compile error after a Boot /
 *       Hibernate-Validator upgrade.</li>
 * </ul>
 */
class AgilePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultsAreTheDocumentedOnes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var props = context.getBean(AgileProperties.class);
            // Echoed to clients as `sectionCap` in GET …/backlog.
            assertThat(props.sectionMaxIssues()).isEqualTo(300);
            assertThat(props.maxOpenSprintsPerProject()).isEqualTo(20);
            assertThat(props.defaultSprintLengthDays()).isEqualTo(14);
            // Echoed to clients as `bulkMoveCap` so they chunk at the instance's value.
            assertThat(props.maxIssuesPerBulkMove()).isEqualTo(100);
            // …and the shipped combination is comfortably inside the planning-view bound
            // (300 × 21 = 6300 of 20000), so the defaults can never be the thing that
            // refuses to start.
            assertThat(props.isPlanningViewBounded()).isTrue();
        });
    }

    @Test
    void everyBoundaryRefusesToStart() {
        // section-max-issues: 1…2000
        assertRejected("app.agile.section-max-issues=0", "sectionMaxIssues");
        assertRejected("app.agile.section-max-issues=2001", "sectionMaxIssues");
        // max-open-sprints-per-project: 1…100
        assertRejected("app.agile.max-open-sprints-per-project=0", "maxOpenSprintsPerProject");
        assertRejected("app.agile.max-open-sprints-per-project=101", "maxOpenSprintsPerProject");
        // default-sprint-length-days: 1…90. A 0 here would make every bare
        // POST …/start produce endAt == startAt — a sprint that is over the moment it
        // begins — with nothing in the logs.
        assertRejected("app.agile.default-sprint-length-days=0", "defaultSprintLengthDays");
        assertRejected("app.agile.default-sprint-length-days=91", "defaultSprintLengthDays");
        // max-issues-per-bulk-move: 1…500. A silently bound 0 would 400 every
        // "move to sprint" for the whole install ("At most 0 issues per request").
        assertRejected("app.agile.max-issues-per-bulk-move=0", "maxIssuesPerBulkMove");
        assertRejected("app.agile.max-issues-per-bulk-move=501", "maxIssuesPerBulkMove");
    }

    @Test
    void inRangeOverridesBindNormally() {
        runner.withPropertyValues(
                        "app.agile.section-max-issues=50",
                        "app.agile.max-open-sprints-per-project=3",
                        "app.agile.default-sprint-length-days=7",
                        "app.agile.max-issues-per-bulk-move=25")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var props = context.getBean(AgileProperties.class);
                    assertThat(props.sectionMaxIssues()).isEqualTo(50);
                    assertThat(props.maxOpenSprintsPerProject()).isEqualTo(3);
                    assertThat(props.defaultSprintLengthDays()).isEqualTo(7);
                    assertThat(props.maxIssuesPerBulkMove()).isEqualTo(25);
                });
    }

    /**
     * The cross-field rule (security review L7): each field's own maximum is legal, so
     * only the PRODUCT can catch "both dialled to the top". 2000 × (100 + 1) = 202 000
     * assembled {@code IssueResponse}s in one unpaged response — a self-inflicted OOM
     * that "fail fast, never clamp" would otherwise accept without a word.
     *
     * <p>The message must name the bound, because the operator's only clue at startup is
     * that message: an error that says "invalid" without saying WHICH two knobs conflict
     * and at what number is a support ticket.
     */
    @Test
    void theProductOfTheTwoWidthKnobsRefusesToStartEvenThoughEachIsInRange() {
        // Each on its own is a legal value…
        runner.withPropertyValues(
                        "app.agile.section-max-issues=2000",
                        "app.agile.max-open-sprints-per-project=9")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.agile.max-open-sprints-per-project=100",
                        "app.agile.section-max-issues=100")
                .run(context -> assertThat(context).hasNotFailed());

        // …but together they blow the planning-view budget and abort startup.
        runner.withPropertyValues(
                        "app.agile.section-max-issues=2000",
                        "app.agile.max-open-sprints-per-project=100")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        // the violation message lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining("20000")
                        .hasMessageContaining("section-max-issues")
                        .hasMessageContaining("max-open-sprints-per-project"));
    }

    /**
     * The bound is inclusive ({@code <=}), and exactly ON it must still start — otherwise
     * the documented "must not exceed 20000" would be off by one section-worth of issues
     * and an operator who followed the docs to the letter could not boot.
     */
    @Test
    void exactlyAtThePlanningViewBoundStillStarts() {
        // 2000 × (9 + 1) = 20 000 — the largest legal planning view.
        runner.withPropertyValues(
                        "app.agile.section-max-issues=2000",
                        "app.agile.max-open-sprints-per-project=9")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgileProperties.class).isPlanningViewBounded()).isTrue();
                });
        // …and one issue per section more is not.
        runner.withPropertyValues(
                        "app.agile.section-max-issues=2000",
                        "app.agile.max-open-sprints-per-project=10")
                .run(context -> assertThat(context).hasFailed());
    }

    private void assertRejected(String property, String fieldName) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context)
                        .as(property)
                        .hasFailed()
                        .getFailure()
                        // the field name lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining(fieldName));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgileProperties.class)
    static class TestConfig {}
}
