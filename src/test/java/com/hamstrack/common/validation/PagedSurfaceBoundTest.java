package com.hamstrack.common.validation;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.LabelTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-163 AC-1, AC-2, AC-3 and AC-15 — Sweep A, one row per surface that takes a page index
 * from the caller.</strong>
 *
 * <p>All six answered <strong>500</strong> before this ticket, verified by execution against a
 * running instance (spec §3.2): search overflowed its {@code int} offset to a negative
 * {@code firstResult} ({@code IllegalArgumentException}) and the other five hit Spring Data's own
 * offset conversion, which <em>refuses</em> rather than truncating
 * ({@code InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE}). Both are
 * fixed by the same declarative bound, and both are pinned here — an observation dates, a test does
 * not.
 *
 * <p><strong>The row set is a category, not a list of six endpoints.</strong> The membership rule is
 * "a request value that becomes a JPA offset": a surface that fixes the index at 0 (board and
 * backlog section fetches, the notification inbox, the picklist typeaheads, velocity's sprint
 * sample, the rank neighbour lookups) has no offset to overflow however large its limit is, and
 * needs no row. Any NEW handler that lets a caller name a page index owes both a
 * {@code @Max(Paging.MAX_PAGE)} and a row below.
 *
 * <p><strong>Three values are asserted per row, and each removes a different way to be wrong:</strong>
 * {@code MAX_PAGE + 1} must be refused (the crash), {@code MAX_PAGE} must be accepted (a bound one
 * too tight is a behavioural change, not a fix), and a NEGATIVE index must still answer 200 with
 * page 0 (§11 Q1 deliberately left the coercion alone — this ticket refuses only requests that
 * already failed). A fourth, {@code page} beyond {@code Integer.MAX_VALUE}, closes the gap between
 * the two refusal mechanisms: Spring's binding and Jackson refuse it before validation is reached,
 * so there is no value between {@code MAX_PAGE + 1} and infinity that gets through.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class PagedSurfaceBoundTest extends LabelTestBase {

    /** The tripwire under the row table: six client-controlled page indexes exist today. */
    private static final int SWEEP_A_ROWS = 6;

    /** Larger than any {@code int}, so binding refuses before validation is consulted. */
    private static final String BEYOND_INT = "9999999999";

    @Autowired EntityManagerFactory entityManagerFactory;

    private Fixture fixture;

    /**
     * How a row travels. A page index arrives either in a query parameter or in a JSON body, and
     * the two raise <em>different</em> exceptions ({@code HandlerMethodValidationException} vs
     * {@code MethodArgumentNotValidException}) that must produce the same refusal — which is the
     * whole of ADR-0019 and is why both shapes are in one table rather than two files.
     */
    private enum Carrier { QUERY_PARAM, JSON_BODY }

    private record Row(String id, Carrier carrier, Function<Fixture, String> url,
                       Function<Fixture, String> token) {}

    private List<Row> rows() {
        return List.of(
                new Row("A1 POST …/search (SearchRequest.page, body)", Carrier.JSON_BODY,
                        f -> "/api/workspaces/" + f.ctx.wsId() + "/search", f -> f.ctx.token()),
                new Row("A2 GET …/projects/{p}/issues", Carrier.QUERY_PARAM,
                        f -> issues(f.ctx) + "?size=10", f -> f.ctx.token()),
                new Row("A3 GET …/issues/{n}/history", Carrier.QUERY_PARAM,
                        f -> issues(f.ctx) + "/" + f.issueNumber + "/history?size=10",
                        f -> f.ctx.token()),
                new Row("A4 GET …/issues/{n}/comments", Carrier.QUERY_PARAM,
                        f -> issues(f.ctx) + "/" + f.issueNumber + "/comments?size=10",
                        f -> f.ctx.token()),
                new Row("A5 GET …/projects/{p}/sprints", Carrier.QUERY_PARAM,
                        f -> "/api/workspaces/" + f.ctx.wsId() + "/projects/" + f.ctx.projectId()
                             + "/sprints?size=10", f -> f.ctx.token()),
                // Not workspace-scoped — behind /api/admin/** and hasRole("ADMIN") — and bounded by
                // the same annotation for the same reason. A page index is a hazard because a
                // REQUEST supplies it, not because of who is allowed to send it.
                new Row("A6 GET /api/admin/users", Carrier.QUERY_PARAM,
                        f -> "/api/admin/users?size=10", f -> f.adminToken));
    }

    @BeforeEach
    void setUp() throws Exception {
        if (fixture == null) {
            fixture = buildFixture();
        }
    }

    // ============================================================ AC-1 + AC-3: the refusal

    /**
     * AC-1 and the refusal half of AC-3. Every row answers 400 at {@code MAX_PAGE + 1}, names
     * {@code page} in {@code detail}, and carries it as a key in the {@code errors} map — the
     * structural half the SPA reads (HD-171), which a bare "Validation failure" does not give it.
     */
    @Test
    void everyPagedSurfaceRefusesOnePastTheBoundNamingTheField() throws Exception {
        assertThat(rows())
                .as("""
                        The Sweep-A row table has shrunk. Every claim in this class is "no surface \
                        offends", so a row that stops running is a paged endpoint with NO guarantee, \
                        reported as a pass. Find the row that left rather than lowering this — and \
                        if a paged surface was legitimately deleted, delete its row in the same \
                        commit and say so.""")
                .hasSizeGreaterThanOrEqualTo(SWEEP_A_ROWS);

        var offenders = new ArrayList<String>();
        for (var row : rows()) {
            var result = perform(row, String.valueOf((long) Paging.MAX_PAGE + 1));
            if (result.getStatus() != 400) {
                offenders.add(row.id() + " → " + result.getStatus()
                              + " (a page index one past the arithmetic ceiling must be a 400; "
                              + "before HD-163 every one of these was a 500)");
                continue;
            }
            var body = result.getContentAsString();
            if (!body.contains("\"page\"")) {
                offenders.add(row.id() + " → 400 but nothing in the body names `page`: " + body);
            }
        }

        assertThat(offenders)
                .as("""
                        A CALLER-SUPPLIED PAGE INDEX IS NOT BOUNDED AT THE DOOR.

                        Add @Max(Paging.MAX_PAGE) to whatever carries the index into the request — \
                        the @RequestParam, or the field on the request DTO. It must be the \
                        ANNOTATION and not a clamp inside the service: the annotation fires during \
                        argument resolution, so the refusal costs zero statements and happens before \
                        membership is resolved, while a service-side check runs after the count \
                        query has already been paid for and produces a body with no `errors` map.""")
                .isEmpty();
    }

    /**
     * The acceptance half of AC-3, which is what stops the fix from being "refuse anything big".
     * {@code MAX_PAGE} is a legal index: it runs, and legitimately returns an empty page, because
     * an in-range index past the end of a result set is an empty page and not an error.
     */
    @Test
    void everyPagedSurfaceStillAcceptsTheBoundItself() throws Exception {
        var offenders = new ArrayList<String>();
        for (var row : rows()) {
            var status = perform(row, String.valueOf(Paging.MAX_PAGE)).getStatus();
            if (status != 200) {
                offenders.add(row.id() + " → " + status);
            }
        }
        assertThat(offenders)
                .as("""
                        MAX_PAGE itself must be ACCEPTED. A bound one value too tight refuses \
                        requests that are arithmetically fine, which is a behavioural change for \
                        callers rather than a crash fix — and it would be invisible to a test that \
                        only checked that something large is refused.""")
                .isEmpty();
    }

    /**
     * AC-15. A negative index answered 200 with page 0 before this ticket and still does, on all
     * six. HD-163 refuses only requests that already failed; converting a working request into a
     * 400 is a separate, caller-visible decision (§11 Q1) and is not smuggled in here.
     */
    @Test
    void everyPagedSurfaceStillCoercesANegativeIndex() throws Exception {
        var offenders = new ArrayList<String>();
        for (var row : rows()) {
            var status = perform(row, "-1").getStatus();
            if (status != 200) {
                offenders.add(row.id() + " → " + status);
            }
        }
        assertThat(offenders)
                .as("""
                        A negative page index must still be coerced to 0 and answer 200. Paging is \
                        deliberately asymmetric — coerce below, refuse above — because adding \
                        @Min(0) would turn requests that work today into 400s on six endpoints at \
                        once. That is a consistency ticket with the whole API in view, not a side \
                        effect of an overflow fix.""")
                .isEmpty();
    }

    /**
     * The gap between the two refusal mechanisms, closed by measurement rather than by reasoning.
     * A value larger than {@code Integer.MAX_VALUE} never reaches Bean Validation at all: Spring's
     * type conversion (query parameter) and Jackson (body) fail to produce an {@code Integer} first.
     * So the {@code @Max} covers {@code (MAX_PAGE, Integer.MAX_VALUE]} and binding covers everything
     * above, with nothing in between that answers 5xx.
     */
    @Test
    void aPageIndexTooLargeForAnIntIsRefusedByBindingBeforeValidation() throws Exception {
        var offenders = new ArrayList<String>();
        for (var row : rows()) {
            var status = perform(row, BEYOND_INT).getStatus();
            if (status != 400) {
                offenders.add(row.id() + " → " + status);
            }
        }
        assertThat(offenders)
                .as("""
                        A page index above Integer.MAX_VALUE must be a 400 too. The @Max cannot see \
                        it — binding and Jackson refuse before validation runs — so this asserts \
                        that the two mechanisms between them leave no value that answers 5xx.""")
                .isEmpty();
    }

    // ============================================================ AC-2: the refusal is free

    /**
     * <strong>AC-2 — the refusal issues no statement of its own.</strong>
     *
     * <p>This is the criterion that decided where the bound goes, so it is asserted rather than
     * assumed. {@code SearchService} runs its count query before it computes the offset: a clamp or
     * a refusal inside the service would answer after the expensive half of the request had already
     * been paid for, on a surface with a 120/min budget. The {@code @Max} fires during argument
     * resolution instead, so the handler is never entered.
     *
     * <p><strong>"Zero" is measured against a baseline, because an authenticated request is never
     * free.</strong> {@code JwtAuthenticationFilter} loads the principal, which is one statement
     * before any handler exists to run. The baseline is therefore an authenticated request to a
     * route that has no handler at all — everything it costs is authentication, by construction —
     * and the claim is that the refusal costs exactly that and nothing more. The well-formed search
     * beside it is the control: without it, two equal measurements of a dead counter would pass.
     */
    @Test
    void theSearchPageRefusalCostsNoStatementBeyondAuthenticating() throws Exception {
        var f = fixture;
        // Warm: a first execution also compiles statements and fills caches.
        searchWithPage(f, "0");
        unhandledRoute(f);

        long authenticationOnly = countStatements(() -> unhandledRoute(f));
        long refusal = countStatements(() -> searchWithPage(f, String.valueOf((long) Paging.MAX_PAGE + 1)));
        long wellFormed = countStatements(() -> searchWithPage(f, "0"));

        assertThat(authenticationOnly)
                .as("""
                        Baseline sanity: an authenticated request that reaches no handler must still \
                        cost the one statement that loads the principal. If this is 0 the counter is \
                        asleep and the equality below proves nothing.""")
                .isGreaterThanOrEqualTo(1);
        assertThat(wellFormed)
                .as("""
                        Control: a search that is ACCEPTED must cost more than one that is refused, \
                        or the counter cannot see the queries this test claims are absent.""")
                .isGreaterThan(authenticationOnly);
        assertThat(refusal)
                .as("""
                        A REFUSED PAGE INDEX MUST COST NO QUERY. It cost %d statements against a \
                        %d-statement authentication baseline, so the handler was entered — which \
                        means the bound has moved from the request edge into the service, and the \
                        count query at SearchService is being paid for before the request is \
                        refused. Put the refusal back on the DTO field: @Max(Paging.MAX_PAGE) on \
                        SearchRequest.page fires during argument resolution, before the handler and \
                        before membership resolution.""", refusal, authenticationOnly)
                .isEqualTo(authenticationOnly);
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code Ctx.issuesBase()} is package-private to the issue suites; this is the same path. */
    private static String issues(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/issues";
    }

    private org.springframework.mock.web.MockHttpServletResponse perform(Row row, String page)
            throws Exception {
        var url = row.url().apply(fixture);
        var token = row.token().apply(fixture);
        var request = switch (row.carrier()) {
            case QUERY_PARAM -> get(url + (url.contains("?") ? "&" : "?") + "page=" + page);
            case JSON_BODY -> post(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\":\"\",\"page\":" + page + ",\"size\":100}");
        };
        return mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Object searchWithPage(Fixture f, String page) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + f.ctx.wsId() + "/search")
                        .header("Authorization", "Bearer " + f.ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"page\":" + page + ",\"size\":100}"))
                .andReturn();
    }

    /** Authenticated, but there is no handler — so everything it costs is authentication. */
    private Object unhandledRoute(Fixture f) throws Exception {
        return mockMvc.perform(get("/api/__no-such-route-" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + f.ctx.token()))
                .andReturn();
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

    // ------------------------------------------------------------------ fixture

    private static final class Fixture {
        Ctx ctx;
        long issueNumber;
        String adminToken;
    }

    private Fixture buildFixture() throws Exception {
        var f = new Fixture();
        f.ctx = newProject();
        f.issueNumber = createIssue(f.ctx, "paged surface bound").get("number").asLong();

        var admin = new User();
        admin.setEmail(("paged-admin-" + System.nanoTime() + "-"
                        + UUID.randomUUID().toString().substring(0, 6) + "@example.com").toLowerCase());
        admin.setDisplayName("Paged Admin");
        admin.setPasswordHash(passwordEncoder.encode("test-password-1"));
        admin.setStatus(UserStatus.ACTIVE);
        admin.setSystemRole(SystemRole.ADMIN);
        f.adminToken = login(userRepository.save(admin));

        // A well-formed search must return something, or the AC-2 control ("an accepted search
        // costs more than a refused one") could pass on an empty result set for the wrong reason.
        mockMvc.perform(post("/api/workspaces/" + f.ctx.wsId() + "/search")
                        .header("Authorization", "Bearer " + f.ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\",\"page\":0,\"size\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isNotEmpty());
        return f;
    }
}
