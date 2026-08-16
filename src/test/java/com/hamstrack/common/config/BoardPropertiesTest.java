package com.hamstrack.common.config;

import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-92 — {@link BoardProperties} must <strong>fail fast, never clamp</strong>, the posture
 * {@link ClassificationProperties} and {@link AgileProperties} already take (and this test
 * mirrors their {@link ApplicationContextRunner} shape: a context that must NOT start cannot
 * be asserted on by an annotation that starts it).
 *
 * <p>The bug this pins: {@code BOARD_MAX_ISSUES=0} bound silently and emptied EVERY board in
 * the install — each request returned {@code {issues: [], truncated: true}} with nothing in
 * the logs pointing at the typo, so the symptom ("the board is blank for everyone") looked
 * like a data or permissions failure rather than a one-character config mistake. An
 * out-of-range value now aborts startup with the property named.
 *
 * <p>Also pinned here:
 * <ul>
 *   <li>the <strong>documented default</strong>. {@code application.properties},
 *       {@code .env.prod.example}, {@code docs/self-hosting.md}, {@code docs/api-dc.md} and
 *       {@code openapi.yaml} all promise operators 500, and it is echoed to clients as
 *       {@code cap} in the board response — a drifted default silently changes a documented
 *       API response;</li>
 *   <li>the <strong>upper bound's coupling</strong> to {@code AgileProperties}' planning-view
 *       budget. Both numbers answer the same question ("how many assembled issues may ONE
 *       unpaged response build?"), so they must not silently diverge into two contradictory
 *       budgets for the same work — see
 *       {@link #theUpperBoundIsTheSameBudgetTheAgilePlanningViewIsHeldTo()}.</li>
 * </ul>
 */
class BoardPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultIsTheDocumentedOne() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Unset must still yield the documented 500 — promised by application.properties,
            // .env.prod.example, docs/self-hosting.md, docs/api-dc.md and openapi.yaml, and
            // echoed back to clients as `cap`.
            assertThat(context.getBean(BoardProperties.class).maxIssues()).isEqualTo(500);
        });
    }

    /**
     * The regression itself. {@code 0} is the value an operator actually typed; a negative
     * one is the same class of typo. Both must abort startup, and the failure must NAME the
     * property — at startup that message is the operator's only clue, and "invalid value" on
     * its own is a support ticket.
     */
    @Test
    void zeroOrNegativeRefusesToStart() {
        assertRejected("app.board.max-issues=0");
        assertRejected("app.board.max-issues=-1");
    }

    /**
     * The upper bound (HD-92) is a refusal point, not a recommendation: past it a single
     * unpaged board response assembles more {@code IssueResponse}s than any one request is
     * budgeted for. The bound is inclusive, so exactly ON it must still start — otherwise an
     * operator who followed the documented "range 1–20000" to the letter could not boot.
     */
    @Test
    void theUpperBoundIsInclusiveAndOneOverRefusesToStart() {
        runner.withPropertyValues("app.board.max-issues=20000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BoardProperties.class).maxIssues()).isEqualTo(20_000);
                });
        assertRejected("app.board.max-issues=20001");
    }

    /**
     * The board's ceiling is not an independently chosen number: it is
     * {@code AgileProperties.MAX_PLANNING_VIEW_ROWS}, the budget already declared for the most
     * assembled issues ONE unpaged response may build. A board is exactly that animal — a
     * single unpaged list, and a cheaper one than the backlog — so the two must move together
     * or not at all. Asserted mechanically (rather than left to the javadoc) because a future
     * change to either number would otherwise leave the codebase holding two different answers
     * to the same question, with only a comment claiming they agree.
     */
    @Test
    void theUpperBoundIsTheSameBudgetTheAgilePlanningViewIsHeldTo() throws Exception {
        Field boardMaxIssues = BoardProperties.class.getDeclaredField("maxIssues");
        Max boardCeiling = boardMaxIssues.getAnnotation(Max.class);
        assertThat(boardCeiling)
                .as("BoardProperties.maxIssues must carry an @Max — without it BOARD_MAX_ISSUES "
                        + "is unbounded above and one board request can build an unbounded response")
                .isNotNull();

        Field planningViewRows = AgileProperties.class.getDeclaredField("MAX_PLANNING_VIEW_ROWS");
        planningViewRows.setAccessible(true);
        int agileBudget = planningViewRows.getInt(null);

        assertThat(boardCeiling.value())
                .as("app.board.max-issues' @Max and AgileProperties.MAX_PLANNING_VIEW_ROWS are the "
                        + "same budget — the most assembled issues one unpaged response may build. "
                        + "If one moved, move the other (and the docs), or write down why a board "
                        + "and a planning view now have different ceilings")
                .isEqualTo(agileBudget);
    }

    @Test
    void inRangeOverrideBindsNormally() {
        runner.withPropertyValues("app.board.max-issues=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 3 is the value BoardCapTest drives the real board with; 200 is
                    // LabelQueryCountTest's. Both must stay bindable.
                    assertThat(context.getBean(BoardProperties.class).maxIssues()).isEqualTo(3);
                });
        runner.withPropertyValues("app.board.max-issues=200")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BoardProperties.class).maxIssues()).isEqualTo(200);
                });
    }

    private void assertRejected(String property) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context)
                        .as(property)
                        .hasFailed()
                        .getFailure()
                        // the field name lives on the deepest cause (BindValidationException)
                        .rootCause()
                        .hasMessageContaining("maxIssues"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BoardProperties.class)
    static class TestConfig {}
}
