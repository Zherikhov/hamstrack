package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.ComponentTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HD-31's HQL surface (proposal §3.5, §5.6 "HQL"): {@code component} is a live
 * {@code ENUM_REF} compiled through the plain id-set path
 * ({@code entityPath = "component.id"}), with the plural {@code components} alias, a
 * capped {@code COMPONENT} picklist on {@code /schema} and a bounded {@code /suggest}
 * typeahead.
 *
 * <p>Two things here are more than smoke tests:
 * <ul>
 *   <li><strong>{@code ORDER BY component}</strong> sorts by the component's NAME
 *       through an <em>explicit LEFT join</em>. The natural-looking
 *       {@code root.get("component").get("name")} implies an INNER join and silently
 *       drops every component-less issue from the sorted result — a data-loss bug that
 *       looks like a sort bug. Asserted below on a project that deliberately mixes both
 *       kinds of issue.</li>
 *   <li><strong>name resolution is built from the VISIBLE PROJECT set</strong>, not from
 *       the workspace, so a component name owned only by a project the actor cannot see
 *       must not resolve at all (422) — the tenancy rule expressed inside the query
 *       language.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ComponentSearchTest extends ComponentTestBase {

    // ==================================================== compilation & semantics

    @Test
    void componentEqualsNotEqualsInAndIsEmptyAllCompileAndReturnTheRightRows() throws Exception {
        var ctx = newProject();
        var billing = createComponent(ctx, "Billing");
        var ingest = createComponent(ctx, "Ingest");
        createIssue(ctx, "on-billing", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "on-ingest", "\"componentId\":\"" + ingest + "\"");
        createIssue(ctx, "bare");

        assert found(ctx, "component = \"Billing\"").equals(Set.of("on-billing"));
        // != is "does not carry it" — the compiler ORs in `component IS NULL`, so the
        // component-less issue matches too (the same semantics `label !=` has).
        assert found(ctx, "component != \"Billing\"").equals(Set.of("on-ingest", "bare"));
        assert found(ctx, "component IN (\"Billing\", \"Ingest\")")
                .equals(Set.of("on-billing", "on-ingest"));
        assert found(ctx, "component IS EMPTY").equals(Set.of("bare"));
        assert found(ctx, "component IS NOT EMPTY").equals(Set.of("on-billing", "on-ingest"));
        // name resolution is case-insensitive, and the plural alias is the same field
        assert found(ctx, "components = \"BILLING\"").equals(Set.of("on-billing"));
        // composes with the rest of the language
        assert found(ctx, "component = \"Billing\" AND text ~ \"on-\"").equals(Set.of("on-billing"));
        assert found(ctx, "component IS EMPTY OR component = \"Ingest\"")
                .equals(Set.of("bare", "on-ingest"));
    }

    /**
     * The LEFT-join guard on sorting: {@code ORDER BY component} must not decide which
     * issues exist, only in which order they come back.
     */
    @Test
    void orderByComponentKeepsComponentLessIssuesInTheResult() throws Exception {
        var ctx = newProject();
        var billing = createComponent(ctx, "Billing");
        var ingest = createComponent(ctx, "Ingest");
        createIssue(ctx, "on-billing", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "on-ingest", "\"componentId\":\"" + ingest + "\"");
        createIssue(ctx, "bare 1");
        createIssue(ctx, "bare 2");

        var ascending = found(ctx, "ORDER BY component ASC");
        assert ascending.equals(Set.of("on-billing", "on-ingest", "bare 1", "bare 2"))
                : "an INNER join here would silently drop the component-less issues, got " + ascending;
        assert found(ctx, "ORDER BY component DESC").size() == 4;
        // …and the sort still actually sorts the rows that DO have a component.
        var ordered = orderedTitles(ctx, "component IS NOT EMPTY ORDER BY component ASC");
        assert ordered.equals(List.of("on-billing", "on-ingest")) : ordered;
        assert orderedTitles(ctx, "component IS NOT EMPTY ORDER BY component DESC")
                .equals(List.of("on-ingest", "on-billing"));
        // Sorting composes with a filter that keeps component-less rows in play.
        assert found(ctx, "component != \"Billing\" ORDER BY component ASC")
                .equals(Set.of("on-ingest", "bare 1", "bare 2"));
    }

    /** Components are project-owned, so one name can legitimately mean two rows. */
    @Test
    void aNameOwnedByTwoVisibleProjectsMatchesIssuesInBoth() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var here = createComponent(ctx, "Billing");
        var there = createComponent(sibling, "billing");     // same name, different case
        createIssue(ctx, "here", "\"componentId\":\"" + here + "\"");
        createIssue(sibling, "there", "\"componentId\":\"" + there + "\"");
        createIssue(ctx, "bare");

        assert found(ctx, "component = \"Billing\"").equals(Set.of("here", "there"))
                : "a name owned by two visible projects must match issues in both";
    }

    @Test
    void aComponentNameOnlyOwnedByAnInvisibleProjectDoesNotResolve() throws Exception {
        var ctx = newProject();
        var hidden = siblingProject(ctx);
        var secret = createComponent(hidden, "Secret Module");
        createIssue(hidden, "hidden issue", "\"componentId\":\"" + secret + "\"");
        createIssue(ctx, "visible issue");

        // While the project is visible the name resolves and its issue is found…
        assert found(ctx, "component = \"Secret Module\"").equals(Set.of("hidden issue"));

        // …but an ARCHIVED project leaves the visible set (SearchScope), so the name is
        // no longer resolvable at all — a 422, not an empty result that would still
        // confirm the name exists somewhere.
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + hidden.projectId()
                        + "/archive").header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().is2xxSuccessful());

        search(ctx.wsId(), ctx.token(), "component = \"Secret Module\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("component"))
                .andExpect(jsonPath("$.detail", containsString("No component named 'Secret Module'")));
        // The issues of an invisible project are gone from every query, not just this one.
        assert found(ctx, "component IS NOT EMPTY").isEmpty();
        assert found(ctx, "ORDER BY component ASC").equals(Set.of("visible issue"));
    }

    @Test
    void hqlComponentQueriesNeverCrossTheWorkspaceBoundary() throws Exception {
        var a = newProject();
        var b = newProject();
        createIssue(a, "a-issue", "\"componentId\":\"" + createComponent(a, "shared-name") + "\"");
        createIssue(b, "b-issue", "\"componentId\":\"" + createComponent(b, "shared-name") + "\"");

        // The same component name exists in both tenants; each side sees only its own.
        assert found(a, "component = \"shared-name\"").equals(Set.of("a-issue"));
        assert found(b, "component = \"shared-name\"").equals(Set.of("b-issue"));
    }

    @Test
    void anUnknownComponentNameIs422AndArchivedComponentsDropOutOfResolution() throws Exception {
        var ctx = newProject();
        var stale = createComponent(ctx, "stale");
        createIssue(ctx, "carrier", "\"componentId\":\"" + stale + "\"");

        search(ctx.wsId(), ctx.token(), "component = \"nope\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("No component named 'nope'")));

        archiveComponent(ctx, ctx.token(), stale).andExpect(status().isOk());
        // Resolution excludes archived rows (§3.5) → the documented run-time 422…
        search(ctx.wsId(), ctx.token(), "component = \"stale\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("No component named 'stale'")));
        // …but the issue still HAS a component, so IS NOT EMPTY still finds it.
        assert found(ctx, "component IS NOT EMPTY").equals(Set.of("carrier"));
    }

    @Test
    void aSavedFilterUsingComponentSavesLoadsAndRuns() throws Exception {
        var ctx = newProject();
        var billing = createComponent(ctx, "Billing");
        createIssue(ctx, "on-billing", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "bare");

        var created = json.readTree(mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/filters")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"billing work\",\"hql\":\"component = \\\"Billing\\\"\","
                                + "\"shared\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/filters/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hql", containsString("component")));

        assert found(ctx, created.get("hql").asText()).equals(Set.of("on-billing"));
    }

    // ==================================================== /schema

    @Test
    void schemaAdvertisesComponentExactlyOnceWithTheAliasDeduplicated() throws Exception {
        var ctx = newProject();
        createComponent(ctx, "Billing");
        createComponent(ctx, "Ingest");
        // A component in a sibling project shows up too (the picklist is the visible set).
        createComponent(siblingProject(ctx), "Ledger");

        var schema = json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                                .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                // listed once under its canonical name; `components` is an alias sharing
                // the same descriptor instance, de-duplicated by availableFields()
                .andExpect(jsonPath("$.fields[?(@.name == 'component')]", hasSize(1)))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("components"))))
                .andReturn().getResponse().getContentAsString());

        var component = fieldNamed(schema, "component");
        assert component.get("type").asText().equals("ENUM_REF") : component;
        assert component.get("valueSuggest").asText().equals("COMPONENT") : component;
        assert component.get("sortable").asBoolean() : "component IS sortable (unlike label)";
        assert component.get("nullable").asBoolean() : "component supports IS [NOT] EMPTY";
        var ops = new java.util.ArrayList<String>();
        for (var o : component.get("operators")) ops.add(o.asText());
        assert ops.equals(List.of("=", "!=", "IN", "IS EMPTY", "IS NOT EMPTY")) : ops;

        var values = new java.util.HashSet<String>();
        for (var v : schema.get("values").get("COMPONENT")) values.add(v.get("label").asText());
        assert values.equals(Set.of("Billing", "Ingest", "Ledger"))
                : "the COMPONENT picklist spans the visible projects, got " + values;
    }

    // ==================================================== /suggest

    @Test
    void suggestIsScopedToVisibleProjectsAndTreatsPercentAndUnderscoreAsLiteralCharacters()
            throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var otherTenant = newProject();
        createComponent(ctx, "a_b");             // the LIKE-wildcard trap
        createComponent(ctx, "axb");
        createComponent(ctx, "100% done");
        createComponent(ctx, "plain");
        createComponent(sibling, "sibling module");
        createComponent(otherTenant, "foreign");

        // `_` is a literal underscore, not "any character": q=axb must NOT match a_b …
        assert suggestions(ctx, "axb").equals(List.of("axb"));
        // … and q=a_b must NOT match axb.
        assert suggestions(ctx, "a_b").equals(List.of("a_b"));
        // `%` is a literal percent, not "match everything".
        assert suggestions(ctx, "%").equals(List.of("100% done"))
                : "q=% must match only components containing a literal %, got " + suggestions(ctx, "%");
        // an empty q lists the visible projects' components, never another tenant's
        var all = suggestions(ctx, "");
        assert all.equals(List.of("100% done", "a_b", "axb", "plain", "sibling module")) : all;
        assert !all.contains("foreign") : "suggest must never cross the workspace boundary";
        // case-insensitive substring match
        assert suggestions(ctx, "PLA").equals(List.of("plain"));

        // archived components drop out of suggest (they are excluded from resolution too)
        UUID plainId = null;
        for (var c : listComponents(ctx, ctx.token(), null)) {
            if (c.get("name").asText().equals("plain")) plainId = UUID.fromString(c.get("id").asText());
        }
        archiveComponent(ctx, ctx.token(), plainId).andExpect(status().isOk());
        assert !suggestions(ctx, "").contains("plain");

        // the alias resolves to the same suggester
        assert suggestions(ctx, "components", "axb").equals(List.of("axb"));
        // a non-member gets 404, never a peek at the vocabulary
        var outsider = login(user());
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                        .param("field", "component").param("q", "")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
    }

    /** Two projects may each own a "Billing" — the suggestion list is about NAMES. */
    @Test
    void suggestDeduplicatesTheSameNameOwnedByTwoProjects() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        createComponent(ctx, "Billing");
        createComponent(sibling, "billing");

        var suggestions = suggestions(ctx, "bill");
        assert suggestions.size() == 1 : "case-insensitively de-duplicated, got " + suggestions;
        assert suggestions.get(0).equalsIgnoreCase("billing") : suggestions;
    }

    // ==================================================== helpers

    private JsonNode fieldNamed(JsonNode schema, String name) {
        for (var f : schema.get("fields")) {
            if (f.get("name").asText().equals(name)) return f;
        }
        throw new AssertionError("/schema does not advertise '" + name + "'");
    }

    private List<String> suggestions(Ctx ctx, String q) throws Exception {
        return suggestions(ctx, "component", q);
    }

    private List<String> suggestions(Ctx ctx, String field, String q) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                        .param("field", field).param("q", q)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.field").value(field))
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        for (var s : json.readTree(body).get("suggestions")) out.add(s.get("value").asText());
        return out;
    }

    /** Run an HQL query and return the matched issue titles (unordered). */
    private Set<String> found(Ctx ctx, String hql) throws Exception {
        return new java.util.HashSet<>(orderedTitles(ctx, hql));
    }

    /** Run an HQL query and return the matched issue titles in RESULT order. */
    private List<String> orderedTitles(Ctx ctx, String hql) throws Exception {
        var body = search(ctx.wsId(), ctx.token(), hql)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        for (var row : json.readTree(body).get("content")) out.add(row.get("issue").get("title").asText());
        return out;
    }

    private ResultActions search(UUID wsId, String token, String hql) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + wsId + "/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + json.writeValueAsString(hql) + ",\"size\":100}"));
    }
}
