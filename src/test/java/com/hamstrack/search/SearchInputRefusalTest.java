package com.hamstrack.search;

import com.hamstrack.issue.LabelTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-214 AC-5, AC-10 and the two-constraints half of AC-8 — the parameter defect on
 * {@code GET …/search/suggest}.</strong>
 *
 * <p>{@code q} has carried {@code @Size(max = 100)} since HD-3 and the 101st character answered
 * <strong>500</strong> for the whole of that time, because the annotation was routed through the
 * AOP proxy that {@code @Validated} on the controller installed. The annotation looked like the
 * thing enabling validation and was the thing breaking it; nothing noticed because the bound and the
 * annotation shipped in one commit and no test ever sent an over-long value. {@code field} carried
 * no bound at all.
 *
 * <p><strong>Both boundaries are asserted on both parameters, and the ordinary values with them.</strong>
 * A test that only sends the over-long value proves that something large is refused — which was
 * already true when the refusal was a crash. What has to be pinned is that 100 is accepted, 101 is
 * not, and that a short, sensible value is untouched: the bound refuses the absurd, not the
 * mistaken.
 *
 * <p><strong>{@code field} carries a second claim: the refusal is cheap.</strong> An unknown field
 * name is not rejected on sight — {@code FieldResolver} falls through to {@code FieldRegistry.suggest},
 * which runs Levenshtein against every registry entry, so the input's LENGTH multiplies work the
 * caller does not pay for. And {@code suggest} builds a full {@code ResolutionContext} (a workspace
 * resolution, a label projection, a member scan) BEFORE it ever looks at the name. Tomcat's ~8 KB
 * request line was the only bound on either, on a surface budgeted per minute. So AC-10 asserts not
 * just the status but that no statement is issued at all.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class SearchInputRefusalTest extends LabelTestBase {

    /**
     * The bound on both parameters. Derived rather than chosen for {@code field} — the widest name
     * that can legitimately resolve is a tenant custom field's key, and {@code field_defs.key} is
     * {@code VARCHAR(50)} — and inherited from HD-3 for {@code q}. Written here as the one number
     * this file compares against, so "at the bound" and "one past it" cannot drift apart.
     */
    private static final int BOUND = 100;

    private static final String AT_BOUND = "a".repeat(BOUND);
    private static final String PAST_BOUND = "a".repeat(BOUND + 1);

    @Autowired EntityManagerFactory entityManagerFactory;

    private Ctx ctx;

    @BeforeEach
    void setUp() throws Exception {
        if (ctx == null) {
            ctx = newProject();
            createIssue(ctx, "suggest fixture");
        }
    }

    // ============================================================ AC-5: q

    /** {@code q} at 101 characters: 400, named in {@code detail} and keyed in {@code errors}. */
    @Test
    void anOverLongQueryPrefixIsRefusedNamingTheParameter() throws Exception {
        suggest("assignee", PAST_BOUND)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.q").exists())
                .andExpect(jsonPath("$.detail", containsString("q")))
                // The regression this file exists for: it was a 500, and the ProblemDetail is the
                // shared validation shape rather than Boot's bare "Validation failure".
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * …and 100 characters, and two characters, both still answer 200. Without these the bound could
     * be off by one, or could have become "refuse anything with a q", and the test above would not
     * notice either.
     */
    @Test
    void aQueryPrefixAtOrUnderTheBoundStillAnswers() throws Exception {
        suggest("assignee", AT_BOUND).andExpect(status().isOk());
        suggest("assignee", "ab").andExpect(status().isOk());
    }

    // ============================================================ AC-10: field

    /**
     * {@code field} at 101 characters is refused <strong>before the workspace is resolved</strong>.
     * The status is the small half of this claim; the statement count is the point. The bound has to
     * fire during argument resolution, or the ~8 statements of the context build and the registry
     * scan are both paid for before the answer.
     *
     * <p>The baseline is an authenticated request to a route with no handler at all, so everything
     * it costs is authentication ({@code JwtAuthenticationFilter} loads the principal). The accepted
     * suggest beside it is the control that proves the counter can see the work being claimed
     * absent.
     */
    @Test
    void anOverLongFieldIsRefusedBeforeAnyQueryRuns() throws Exception {
        suggest(PAST_BOUND, "a").andExpect(status().isBadRequest());          // warm
        unhandledRoute();

        long authenticationOnly = countStatements(this::unhandledRoute);
        long refusal = countStatements(() -> suggest(PAST_BOUND, "a"));
        long accepted = countStatements(() -> suggest("assignee", "a"));

        assertThat(authenticationOnly)
                .as("baseline sanity: an authenticated request that reaches no handler still loads "
                    + "the principal. If this is 0 the counter is asleep.")
                .isGreaterThanOrEqualTo(1);
        assertThat(accepted)
                .as("""
                        Control: an ACCEPTED suggest resolves the workspace and builds a full \
                        ResolutionContext, so it must cost more than a refusal. If it does not, the \
                        counter cannot see the statements this test claims are absent and the \
                        equality below is meaningless.""")
                .isGreaterThan(authenticationOnly);
        assertThat(refusal)
                .as("""
                        AN OVER-LONG `field` MUST COST NOTHING. It issued %d statements against a \
                        %d-statement authentication baseline, so the handler ran: the bound has \
                        moved out of the @Size on the parameter and into the service, and the \
                        context build the refusal exists to avoid is being paid for anyway.""",
                        refusal, authenticationOnly)
                .isEqualTo(authenticationOnly);

        suggest(PAST_BOUND, "a")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.field").exists())
                .andExpect(jsonPath("$.detail", containsString("field")));
    }

    /** A real field name is untouched. */
    @Test
    void aKnownFieldStillAnswers() throws Exception {
        suggest("assignee", "a").andExpect(status().isOk());
    }

    /**
     * <strong>The bound refuses the absurd, not the mistaken.</strong> A misspelt name that fits
     * inside 100 characters must still reach {@code FieldResolver} and come back as the existing
     * field-anchored 422 carrying its "did you mean" hint — the behaviour a user actually meets.
     * A bound that swallowed this would trade a helpful 422 for an opaque 400 and would look, from
     * the status code alone, exactly like a working fix.
     */
    @Test
    void anUnknownButInRangeFieldStillGetsTheFieldAnchored422WithItsHint() throws Exception {
        suggest("assigne", "a")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("assigne"))
                .andExpect(jsonPath("$.detail", containsString("Did you mean 'assignee'?")));
    }

    // ============================================================ AC-8: two at once

    /**
     * <strong>Two parameter constraints failing in one request report both</strong>, ordered and
     * rendered by the same routine a body refusal uses (§10 case 12).
     *
     * <p>This is the case where a hand-rolled second copy of the rendering would visibly differ:
     * one failure looks identical whichever code produced it, two do not. {@code detail} must carry
     * the entries sorted and joined with {@code "; "}, and {@code errors} must carry the same pair —
     * which is the {@code handleValidation} contract the API docs describe in four bullets, now
     * claimed for parameters as well.
     */
    @Test
    void aRequestFailingTwoParameterConstraintsReportsBoth() throws Exception {
        suggest(PAST_BOUND, PAST_BOUND)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.field").exists())
                .andExpect(jsonPath("$.errors.q").exists())
                // sorted, "; "-joined, both named — "field: …; q: …", never one of the two
                .andExpect(jsonPath("$.detail", matchesPattern("(?s)field: .*; q: .*")));
    }

    // ------------------------------------------------------------------ plumbing

    private ResultActions suggest(String field, String q) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                .param("field", field)
                .param("q", q)
                .header("Authorization", "Bearer " + ctx.token()));
    }

    /** Authenticated, but there is no handler — so everything it costs is authentication. */
    private Object unhandledRoute() throws Exception {
        return mockMvc.perform(get("/api/__no-such-route-" + UUID.randomUUID())
                .header("Authorization", "Bearer " + ctx.token())).andReturn();
    }

    private long countStatements(ThrowingRunnable body) throws Exception {
        var stats = statistics();
        stats.clear();
        body.run();
        return stats.getPrepareStatementCount();
    }

    private Statistics statistics() {
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        @SuppressWarnings("UnusedReturnValue")
        Object run() throws Exception;
    }
}
