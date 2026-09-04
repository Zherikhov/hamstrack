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

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(found(ctx, "component = \"Billing\""))
                .as("component = resolves a component name to exactly the issues carrying it")
                .isEqualTo(Set.of("on-billing"));
        // != is "does not carry it" — the compiler ORs in `component IS NULL`, so the
        // component-less issue matches too (the same semantics `label !=` has).
        assertThat(found(ctx, "component != \"Billing\""))
                .as("component != is 'does not carry it': the compiler ORs in component IS NULL, so the component-less issue matches too")
                .isEqualTo(Set.of("on-ingest", "bare"));
        assertThat(found(ctx, "component IN (\"Billing\", \"Ingest\")"))
                .as("IN unions the named components and nothing else")
                .isEqualTo(Set.of("on-billing", "on-ingest"));
        assertThat(found(ctx, "component IS EMPTY"))
                .as("component IS EMPTY is the issues carrying no component at all")
                .isEqualTo(Set.of("bare"));
        assertThat(found(ctx, "component IS NOT EMPTY"))
                .as("component IS NOT EMPTY is the complement of IS EMPTY over the same rows")
                .isEqualTo(Set.of("on-billing", "on-ingest"));
        // name resolution is case-insensitive, and the plural alias is the same field
        assertThat(found(ctx, "components = \"BILLING\""))
                .as("name resolution is case-insensitive and the plural alias is the same field")
                .isEqualTo(Set.of("on-billing"));
        // composes with the rest of the language
        assertThat(found(ctx, "component = \"Billing\" AND text ~ \"on-\""))
                .as("component composes with the rest of the language instead of being a special case")
                .isEqualTo(Set.of("on-billing"));
        assertThat(found(ctx, "component IS EMPTY OR component = \"Ingest\""))
                .as("IS EMPTY composes under OR, so a query can ask for 'unassigned or this one'")
                .isEqualTo(Set.of("bare", "on-ingest"));
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
        assertThat(ascending)
                .as("an INNER join here would silently drop the component-less issues, got " + ascending)
                .isEqualTo(Set.of("on-billing", "on-ingest", "bare 1", "bare 2"));
        assertThat(found(ctx, "ORDER BY component DESC"))
                .as("DESC drops no row either — the sort direction is not a filter")
                .hasSize(4);
        // …and the sort still actually sorts the rows that DO have a component.
        var ordered = orderedTitles(ctx, "component IS NOT EMPTY ORDER BY component ASC");
        assertThat(ordered).as("%s", ordered).isEqualTo(List.of("on-billing", "on-ingest"));
        assertThat(orderedTitles(ctx, "component IS NOT EMPTY ORDER BY component DESC"))
                .as("the sort still actually sorts the rows that DO have a component, the other way round")
                .isEqualTo(List.of("on-ingest", "on-billing"));
        // Sorting composes with a filter that keeps component-less rows in play.
        assertThat(found(ctx, "component != \"Billing\" ORDER BY component ASC"))
                .as("sorting composes with a filter that keeps component-less rows in play")
                .isEqualTo(Set.of("on-ingest", "bare 1", "bare 2"));
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

        assertThat(found(ctx, "component = \"Billing\""))
                .as("a name owned by two visible projects must match issues in both")
                .isEqualTo(Set.of("here", "there"));
    }

    @Test
    void aComponentNameOnlyOwnedByAnInvisibleProjectDoesNotResolve() throws Exception {
        var ctx = newProject();
        var hidden = siblingProject(ctx);
        var secret = createComponent(hidden, "Secret Module");
        createIssue(hidden, "hidden issue", "\"componentId\":\"" + secret + "\"");
        createIssue(ctx, "visible issue");

        // While the project is visible the name resolves and its issue is found…
        assertThat(found(ctx, "component = \"Secret Module\""))
                .as("while the project is visible the component name resolves and its issue is found")
                .isEqualTo(Set.of("hidden issue"));

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
        assertThat(found(ctx, "component IS NOT EMPTY"))
                .as("the issues of an invisible project are gone from EVERY query, not just the one that named the component")
                .isEmpty();
        assertThat(found(ctx, "ORDER BY component ASC"))
                .as("an ordered query over the invisible project leaves only the visible issue")
                .isEqualTo(Set.of("visible issue"));
    }

    @Test
    void hqlComponentQueriesNeverCrossTheWorkspaceBoundary() throws Exception {
        var a = newProject();
        var b = newProject();
        createIssue(a, "a-issue", "\"componentId\":\"" + createComponent(a, "shared-name") + "\"");
        createIssue(b, "b-issue", "\"componentId\":\"" + createComponent(b, "shared-name") + "\"");

        // The same component name exists in both tenants; each side sees only its own.
        assertThat(found(a, "component = \"shared-name\""))
                .as("the same component name exists in both tenants and each side resolves its own")
                .isEqualTo(Set.of("a-issue"));
        assertThat(found(b, "component = \"shared-name\""))
                .as("the other tenant resolves the shared name to its own component, never across")
                .isEqualTo(Set.of("b-issue"));
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
        assertThat(found(ctx, "component IS NOT EMPTY"))
                .as("an archived component drops out of NAME resolution, but the issue still HAS it, so IS NOT EMPTY still finds it")
                .isEqualTo(Set.of("carrier"));
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

        assertThat(found(ctx, created.get("hql").asText()))
                .as("a saved filter round-trips: the stored HQL runs and finds what it found when it was saved")
                .isEqualTo(Set.of("on-billing"));
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
        assertThat(component.get("type").asText()).as("%s", component).isEqualTo("ENUM_REF");
        assertThat(component.get("valueSuggest").asText()).as("%s", component).isEqualTo("COMPONENT");
        assertThat(component.get("sortable").asBoolean()).withFailMessage("component IS sortable (unlike label)").isTrue();
        assertThat(component.get("nullable").asBoolean()).withFailMessage("component supports IS [NOT] EMPTY").isTrue();
        var ops = new java.util.ArrayList<String>();
        for (var o : component.get("operators")) ops.add(o.asText());
        assertThat(ops).as("%s", ops).isEqualTo(List.of("=", "!=", "IN", "IS EMPTY", "IS NOT EMPTY"));

        var values = new java.util.HashSet<String>();
        for (var v : schema.get("values").get("COMPONENT")) values.add(v.get("label").asText());
        assertThat(values)
                .as("the COMPONENT picklist spans the visible projects, got " + values)
                .isEqualTo(Set.of("Billing", "Ingest", "Ledger"));
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
        assertThat(suggestions(ctx, "axb"))
                .as("an underscore in a stored name is a literal character, so a wildcard-shaped query must not match it")
                .isEqualTo(List.of("axb"));
        // … and q=a_b must NOT match axb.
        assertThat(suggestions(ctx, "a_b"))
                .as("…and the query underscore is literal too, so it must not match the any-character spelling")
                .isEqualTo(List.of("a_b"));
        // `%` is a literal percent, not "match everything".
        assertThat(suggestions(ctx, "%"))
                .as("q=% must match only components containing a literal %, got " + suggestions(ctx, "%"))
                .isEqualTo(List.of("100% done"));
        // an empty q lists the visible projects' components, never another tenant's
        var all = suggestions(ctx, "");
        assertThat(all).as("%s", all).isEqualTo(List.of("100% done", "a_b", "axb", "plain", "sibling module"));
        assertThat(all).as("suggest must never cross the workspace boundary").doesNotContain("foreign");
        // case-insensitive substring match
        assertThat(suggestions(ctx, "PLA")).as("suggest is a case-insensitive substring match").isEqualTo(List.of("plain"));

        // archived components drop out of suggest (they are excluded from resolution too)
        UUID plainId = null;
        for (var c : listComponents(ctx, ctx.token(), null)) {
            if (c.get("name").asText().equals("plain")) plainId = UUID.fromString(c.get("id").asText());
        }
        archiveComponent(ctx, ctx.token(), plainId).andExpect(status().isOk());
        assertThat(suggestions(ctx, "")).as("an archived component drops out of suggest").doesNotContain("plain");

        // the alias resolves to the same suggester
        assertThat(suggestions(ctx, "components", "axb"))
                .as("the plural alias resolves to the same suggester")
                .isEqualTo(List.of("axb"));
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
        assertThat(suggestions).as("case-insensitively de-duplicated, got " + suggestions).hasSize(1);
        assertThat(suggestions.get(0)).as("%s", suggestions).isEqualToIgnoringCase("billing");
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
