package com.hamstrack.search;

import com.hamstrack.issue.ComponentTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The {@code project} field (HD-101) is served entirely from state the request already
 * loaded, and costs no statement of its own</strong> — in a query, in the {@code /schema}
 * picklist, or in the {@code /suggest} typeahead.
 *
 * <p>That is a claim about how the field is <em>built</em>, not an optimisation: the visible
 * project set is what {@link SearchScope} makes every search's predicate from, and
 * {@link ResolutionContextFactory} already fetches those rows to read their delivery
 * capabilities. A project catalog lookup added beside that would be both redundant <em>and</em>
 * the wrong shape — it could reach a project outside the visible set, which is precisely what
 * resolving through that set exists to prevent.
 *
 * <p>Every assertion is a <em>comparison against a surface with the same shape</em>, never a
 * magic absolute count: an absolute is a tripwire that fires on any unrelated query change,
 * whereas "costs what the context-only surface costs, and the catalog-backed one costs more" is
 * exactly the property being claimed. The {@code component} control is what proves the harness
 * can see an extra statement at all — without it, an equality between two broken measurements
 * would pass. It says nothing about the branches the assertions name, though; see the comment
 * beside it.
 *
 * <p><strong>Every check here is AssertJ, never the {@code assert} keyword.</strong> A bare
 * {@code assert} evaluates nothing unless the JVM was started with {@code -ea}; Surefire enables
 * assertions by default and a plain IDE run does not, so such a guard reports success in one
 * place and silently checks nothing in another. A counting guard is worth exactly as much as the
 * weakest environment it runs in, so it must not depend on a JVM flag to have an opinion.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class ProjectSearchQueryCountTest extends ComponentTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void projectValueSurfacesCostNoQueryOfTheirOwn() throws Exception {
        var ctx = newProject();
        createComponent(ctx, "Billing");
        createIssue(ctx, "alpha one");
        createIssue(ctx, "alpha two");

        // Warm every path once — a first execution also compiles statements and fills caches.
        suggest(ctx, "project");
        suggest(ctx, "assignee");
        suggest(ctx, "component");
        schema(ctx);

        long assignee = countStatements(() -> suggest(ctx, "assignee"));
        long project = countStatements(() -> suggest(ctx, "project"));
        long component = countStatements(() -> suggest(ctx, "component"));
        long schema = countStatements(() -> schema(ctx));

        // What the control establishes: the statement counter is awake, i.e. a surface that does
        // one more query than another measures as costing more. What it does NOT establish is
        // that a statement added on any of the paths the assertions below name would register —
        // it is measured on component's branch, and a branch that is never executed by the code
        // under an assertion cannot vouch for it. Only a probe planted inside the asserted path
        // (SearchService.projects / SearchService.projectOptions / HqlValueResolver.resolveProject)
        // demonstrates that, and doing so is how any future change to these counters is checked.
        assertThat(component)
                .withFailMessage("control failed: a catalog-backed suggester must cost MORE than a "
                                 + "context-only one, so an equality below would prove nothing (component="
                                 + component + ", assignee=" + assignee + ")")
                .isGreaterThan(assignee);
        assertThat(project)
                .withFailMessage("/suggest?field=project must be served from the ResolutionContext like the "
                                 + "member typeahead, but cost " + project + " statements vs " + assignee)
                .isEqualTo(assignee);
        assertThat(schema)
                .withFailMessage("/schema's PROJECT picklist must come out of the context that was built "
                                 + "anyway, but /schema cost " + schema + " statements vs " + assignee)
                .isEqualTo(assignee);
    }

    /**
     * Resolving a {@code project} operand adds no statement either: both queries below match
     * the same rows, so any difference is the resolution, not the result assembly.
     *
     * <p><strong>The unit of this assertion is two statements, not one.</strong> A search request
     * resolves its operands twice — once building the count query and once building the page
     * query — so a single statement introduced into operand resolution moves the difference by
     * <em>2</em>. Anyone re-checking this counter by adding a query and expecting the delta to be
     * 1 would read a working harness as a blind one.
     */
    @Test
    void resolvingAProjectOperandCostsNoExtraStatement() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "alpha one");
        createIssue(ctx, "alpha two");
        createIssue(ctx, "alpha three");
        var key = projectRepository.findById(ctx.projectId()).orElseThrow().getKey();

        String byText = "text ~ \"alpha\"";
        String byProject = "project = \"" + key + "\"";
        assertThat(matched(ctx, byText))
                .withFailMessage("the two queries must match the same rows, or the counts are not comparable")
                .isEqualTo(matched(ctx, byProject));

        long text = countStatements(() -> search(ctx, byText));
        long project = countStatements(() -> search(ctx, byProject));
        assertThat(project)
                .withFailMessage("resolving a project operand must not query anything — " + project
                                 + " statements vs " + text + " for the same rows")
                .isEqualTo(text);
    }

    // ---- helpers ----

    private java.util.Set<String> matched(Ctx ctx, String hql) throws Exception {
        var body = search(ctx, hql);
        var out = new java.util.HashSet<String>();
        for (var row : json.readTree(body).get("content")) {
            out.add(row.get("issue").get("title").asText());
        }
        return out;
    }

    private String search(Ctx ctx, String hql) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/search")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":" + json.writeValueAsString(hql) + ",\"size\":100}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String suggest(Ctx ctx, String field) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                        .param("field", field).param("q", "a")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String schema(Ctx ctx) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long countStatements(ThrowingSupplier body) throws Exception {
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
    private interface ThrowingSupplier {
        @SuppressWarnings("UnusedReturnValue")
        Object run() throws Exception;
    }

}
