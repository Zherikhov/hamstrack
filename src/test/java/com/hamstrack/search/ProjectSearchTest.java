package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.VersionTestBase;
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
 * HD-101's HQL surface: {@code project} is a live {@code ENUM_REF} resolved by project KEY,
 * compiled through the id-set path ({@code entityPath = "project.id"}), sortable on
 * {@code project.key}, with a capped {@code PROJECT} picklist on {@code /schema} and a bounded
 * {@code /suggest} typeahead. Spec: {@code docs/design/hql-project-field-proposal.md}.
 *
 * <p>Search is workspace-scoped by construction — {@link SearchScope} ANDs
 * {@code project.id IN :visibleProjectIds} onto every compiled query — so before this field
 * there was no way to narrow a search to one project, and no way to say which project's
 * {@code "2.4.0"} was meant. Four things here are more than smoke tests:
 *
 * <ul>
 *   <li><strong>A key resolves and a name does not.</strong> {@code projects.key} is unique per
 *       workspace and {@code projects.name} is not, so a name operand would denote a SET — which
 *       is the ambiguity this field exists to remove. The name still reaches the user, in the
 *       suggestion label and in the refusal's hint, where being wrong costs a dropdown row
 *       instead of a wrong result set.</li>
 *   <li><strong>A {@code project} clause can only narrow.</strong> It compiles inside the scope
 *       predicate, never beside it, so {@code project != "HD"} means "in a visible project other
 *       than HD" and cannot admit a row the caller could not already reach.</li>
 *   <li><strong>An unknown or invisible project is a 422, not an empty result.</strong> The two
 *       answers are distinguishable, so an empty result would turn this field into a probe for
 *       which projects exist in a workspace the caller cannot see.</li>
 *   <li><strong>A statement that could never be true is refused.</strong> Every issue has a
 *       project, so {@code project IS EMPTY} is a 422 rather than a silent nothing.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ProjectSearchTest extends VersionTestBase {

    // ==================================================== compilation & semantics

    @Test
    void projectResolvesByKeyForEveryOperator() throws Exception {
        var home = newProject();
        var hd = projectIn(home, "HD", "Hamstrack");
        var ops = projectIn(home, "OPS", "Operations");
        createIssue(hd, "hd-work");
        createIssue(ops, "ops-work");
        createIssue(home, "home-work");

        assertThat(found(home, "project = \"HD\""))
                .as("project = resolves by key to that project's issues only")
                .isEqualTo(Set.of("hd-work"));
        // case-insensitive, and canonically folded, so a pasted operand still lands
        assertThat(found(home, "project = \"hd\""))
                .as("key resolution is case-insensitive, so a pasted operand still lands")
                .isEqualTo(Set.of("hd-work"));
        assertThat(found(home, "project = \" HD \""))
                .as("the operand is canonically folded, so surrounding whitespace still lands")
                .isEqualTo(Set.of("hd-work"));
        // IN unions the ids
        assertThat(found(home, "project IN (\"HD\", \"OPS\")"))
                .as("IN unions the projects it names")
                .isEqualTo(Set.of("hd-work", "ops-work"));
        // a repeated key is idempotent, not a duplicate row
        assertThat(found(home, "project IN (\"HD\", \"hd\")"))
                .as("a key repeated at two casings is idempotent, not a duplicated row")
                .isEqualTo(Set.of("hd-work"));
        // composes with the rest of the language
        assertThat(found(home, "project = \"HD\" AND text ~ \"work\""))
                .as("project composes with the rest of the language instead of being a special case")
                .isEqualTo(Set.of("hd-work"));
        assertThat(found(home, "project = \"HD\" OR project = \"OPS\""))
                .as("OR over two projects is the same result set as IN over both")
                .isEqualTo(Set.of("hd-work", "ops-work"));
    }

    /**
     * {@code !=} and negated membership mean "in a visible project other than this one" — the
     * complement is taken <em>inside</em> the scope predicate, so it can never admit an issue
     * from a project the caller cannot see. The archived project below is the proof: its issue
     * is absent from the negation, not merely absent from the positive query.
     */
    @Test
    void negationIsComplementedInsideTheScopePredicate() throws Exception {
        var home = newProject();
        var hd = projectIn(home, "HD", "Hamstrack");
        var ops = projectIn(home, "OPS", "Operations");
        var gone = projectIn(home, "ARC", "Archived");
        createIssue(hd, "hd-work");
        createIssue(ops, "ops-work");
        createIssue(gone, "archived-work");
        var other = newProject();                       // a different workspace entirely
        createIssue(other, "other-tenant-work");

        archive(home, gone).andExpect(status().is2xxSuccessful());

        var negated = found(home, "project != \"HD\"");
        assertThat(negated).as("%s", negated).contains("ops-work");
        assertThat(negated)
                .as("the complement is taken inside the scope predicate, so an invisible "
                  + "project's issues must stay out of it — got " + negated)
                .doesNotContain("archived-work");
        assertThat(negated).as("%s", negated).doesNotContain("other-tenant-work");
        // Negated membership — spelled `NOT … IN (…)`, the grammar's only form for every field —
        // behaves the same way, and so does NOT over the comparison.
        assertThat(found(home, "NOT project IN (\"HD\")"))
                .as("negated membership is complemented inside the scope predicate, never outside it")
                .isEqualTo(negated);
        assertThat(found(home, "NOT (project = \"HD\")"))
                .as("NOT over the comparison complements exactly like NOT over the membership")
                .isEqualTo(negated);
        // Sanity: without any project term the visible set is exactly these two.
        assertThat(found(home, ""))
                .as("an archived project's issues leave every query, not just this one")
                .isEqualTo(Set.of("hd-work", "ops-work"));
    }

    /**
     * The acceptance query of the ticket: {@code fixVersion} resolves names ACROSS the visible
     * projects, so two projects shipping a "2.4.0" both match — and {@code project} is what
     * finally lets a caller say which one they meant.
     */
    @Test
    void projectDisambiguatesASameNamedVersionAcrossProjects() throws Exception {
        var home = newProject();
        var hd = projectIn(home, "HD", "Hamstrack");
        var ops = projectIn(home, "OPS", "Operations");
        var hdVersion = createVersion(hd, "2.4.0");
        var opsVersion = createVersion(ops, "2.4.0");
        createIssue(hd, "hd-shipped", fixVersionIdsJson(hdVersion));
        createIssue(ops, "ops-shipped", fixVersionIdsJson(opsVersion));
        createIssue(hd, "hd-unshipped");

        // The ambiguity this field exists to remove: one name, two projects, both match.
        assertThat(found(home, "fixVersion = \"2.4.0\""))
                .as("the ambiguity this field exists to remove: one version name, two projects, both match")
                .isEqualTo(Set.of("hd-shipped", "ops-shipped"));
        // The reported query now says which project is meant.
        assertThat(found(home, "project = \"HD\" AND fixVersion = \"2.4.0\""))
                .as("adding project says which project the version name meant")
                .isEqualTo(Set.of("hd-shipped"));
        assertThat(found(home, "project = \"OPS\" AND fixVersion = \"2.4.0\""))
                .as("the same query against the other project disambiguates the other way")
                .isEqualTo(Set.of("ops-shipped"));
    }

    /**
     * <strong>A name never denotes a project, and the refusal teaches the key instead.</strong>
     * Keys are conventionally acronyms, so a workspace holding one project keyed {@code OPS} and
     * another <em>named</em> {@code OPS} is an ordinary accident — under any form of name
     * matching that operand would silently mean two projects. Here it means exactly the one
     * whose key it is, and the reader who typed a name is told which key to type.
     */
    @Test
    void aNameDoesNotResolveAndTheRefusalNamesTheKey() throws Exception {
        var home = newProject();
        var hd = projectIn(home, "HD", "Hamstrack");
        var ops = projectIn(home, "OPS", "Operations");
        var impostor = projectIn(home, "IMP", "OPS");     // named exactly like the other's key
        createIssue(hd, "hd-work");
        createIssue(ops, "ops-work");
        createIssue(impostor, "impostor-work");

        // The collision resolves to one project — the one that OWNS the key — with no union.
        assertThat(found(home, "project = \"OPS\""))
                .as("a key is an identity; the project merely NAMED 'OPS' must not join the result")
                .isEqualTo(Set.of("ops-work"));
        assertThat(found(home, "project = \"IMP\""))
                .as("a key is an identity: the impostor project owning the key IMP is what IMP resolves to")
                .isEqualTo(Set.of("impostor-work"));

        // A name that no project's key spells resolves to nothing at all — but says what to type.
        refused(home, "project = \"Hamstrack\"", "project",
                "No project with key 'Hamstrack' that you can search.");
        refused(home, "project = \"hamstrack\"", "project", "Did you mean \"HD\" (Hamstrack)?");
    }

    /**
     * Two projects may legally share a name — nothing in the schema or the service forbids it —
     * which is the whole reason a name cannot denote a project. Each is still addressable, by
     * the thing that is unique.
     */
    @Test
    void twoProjectsMayShareANameAndNeitherIsReachableByIt() throws Exception {
        var home = newProject();
        var one = projectIn(home, "AAA", "Platform");
        var two = projectIn(home, "BBB", "Platform");
        createIssue(one, "one-work");
        createIssue(two, "two-work");

        refused(home, "project = \"Platform\"", "project", "No project with key 'Platform'");
        assertThat(found(home, "project = \"AAA\""))
                .as("two projects may share a name, and each is still reachable by its own key")
                .isEqualTo(Set.of("one-work"));
        assertThat(found(home, "project = \"BBB\""))
                .as("the second name-sharing project is reachable by its own key too")
                .isEqualTo(Set.of("two-work"));
        assertThat(found(home, "project IN (\"AAA\", \"BBB\")"))
                .as("IN over both keys unions them, even though the shared name reaches neither")
                .isEqualTo(Set.of("one-work", "two-work"));
    }

    // ==================================================== refusals

    @Test
    void anUnknownOrInvisibleProjectIs422AndNeverAnEmptyResult() throws Exception {
        var home = newProject();
        var hd = projectIn(home, "HD", "Hamstrack");
        createIssue(hd, "hd-work");
        var elsewhere = newProject();                    // another tenant
        projectIn(elsewhere, "SEC", "Secret Project");

        // never used by anybody — and the refusal names the rule, not this operand's fate
        refused(home, "project = \"NOPE\"", "project",
                "No project with key 'NOPE' that you can search. Archived projects are not searchable.");
        // a real project of ANOTHER workspace is indistinguishable from a typo
        refused(home, "project = \"SEC\"", "project", "No project with key 'SEC'");
        // an archived project leaves the visible set, so its key stops resolving …
        archive(home, hd).andExpect(status().is2xxSuccessful());
        refused(home, "project = \"HD\"", "project", "Archived projects are not searchable");
        // … and a blank operand is refused before it can address the empty map key
        refused(home, "project = \"   \"", "project", "expects a non-empty name");
        // an operator the field does not take is the ordinary refusal
        refused(home, "project ~ \"HD\"", "project", "is not allowed on field 'project'");
        refused(home, "project > \"HD\"", "project", "is not allowed on field 'project'");
    }

    @Test
    void projectIsEmptyIs422BecauseEveryIssueHasOne() throws Exception {
        var home = newProject();
        createIssue(home, "work");

        refused(home, "project IS EMPTY", "project", "Field 'project' cannot be empty");
        refused(home, "project IS NOT EMPTY", "project", "Field 'project' cannot be empty");
    }

    // ==================================================== ORDER BY

    /**
     * Sortable, on the key: an issue has exactly one project and a key is a total order, so the
     * two reasons the other reference fields are unsortable (a set per issue; no cross-project
     * order) do not apply. {@code issues.project_id} is NOT NULL, so the implicit inner join
     * drops nothing — the LEFT-join dance {@code ORDER BY component} needs is unnecessary here.
     */
    @Test
    void orderByProjectSortsByKeyAndDropsNoRows() throws Exception {
        var home = newProject();
        createIssue(projectIn(home, "MMM", "Middle"), "mmm-work");
        createIssue(projectIn(home, "ZZZ", "Last"), "zzz-work");
        createIssue(projectIn(home, "AAA", "First"), "aaa-work");

        assertThat(ordered(home, "ORDER BY project ASC"))
                .as("ORDER BY project sorts by KEY and drops no rows")
                .isEqualTo(List.of("aaa-work", "mmm-work", "zzz-work"));
        assertThat(ordered(home, "ORDER BY project DESC"))
                .as("DESC is the same ordering read the other way, still dropping no rows")
                .isEqualTo(List.of("zzz-work", "mmm-work", "aaa-work"));
        // sorting composes with a filter and still returns every row the filter keeps
        assertThat(ordered(home, "project != \"ZZZ\" ORDER BY project ASC"))
                .as("sorting composes with a filter and still drops no surviving row")
                .isEqualTo(List.of("aaa-work", "mmm-work"));
    }

    // ==================================================== saved filters

    @Test
    void aSavedFilterUsingProjectSavesLoadsAndRuns() throws Exception {
        var home = newProject();
        var hd = projectIn(home, "HD", "Hamstrack");
        var ops = projectIn(home, "OPS", "Operations");
        var hdVersion = createVersion(hd, "2.4.0");
        createVersion(ops, "2.4.0");
        createIssue(hd, "hd-shipped", fixVersionIdsJson(hdVersion));
        createIssue(ops, "ops-work");

        var hql = "project = \\\"HD\\\" AND fixVersion = \\\"2.4.0\\\"";
        var created = json.readTree(mockMvc.perform(post("/api/workspaces/" + home.wsId() + "/filters")
                        .header("Authorization", "Bearer " + home.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hd 2.4.0\",\"hql\":\"" + hql + "\",\"shared\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/workspaces/" + home.wsId() + "/filters/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + home.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hql", containsString("project")));

        assertThat(found(home, created.get("hql").asText()))
                .as("a saved filter round-trips: the stored HQL runs and finds what it found when it was saved")
                .isEqualTo(Set.of("hd-shipped"));

        // Archiving the project makes the RUN fail — save-time validation is structural only —
        // while the stored text is untouched, exactly as for a later-archived label.
        archive(home, hd).andExpect(status().is2xxSuccessful());
        refused(home, created.get("hql").asText(), "project", "Archived projects are not searchable");
        mockMvc.perform(get("/api/workspaces/" + home.wsId() + "/filters/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + home.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hql", containsString("HD")));
    }

    // ==================================================== /schema

    @Test
    void schemaAdvertisesProjectWithAKeyValuedPicklist() throws Exception {
        var home = newProject();
        projectIn(home, "HD", "Hamstrack");
        projectIn(home, "OPS", "Operations");
        var elsewhere = newProject();
        projectIn(elsewhere, "SEC", "Secret Project");

        var schema = json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + home.wsId() + "/search/schema")
                                .header("Authorization", "Bearer " + home.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name == 'project')]", hasSize(1)))
                // no plural alias: an issue has one project (the plurals elsewhere are plurals
                // of many-valued fields)
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("projects"))))
                .andReturn().getResponse().getContentAsString());

        var project = fieldNamed(schema, "project");
        assertThat(project.get("type").asText()).as("%s", project).isEqualTo("ENUM_REF");
        assertThat(project.get("valueSuggest").asText()).as("%s", project).isEqualTo("PROJECT");
        assertThat(project.get("sortable").asBoolean()).withFailMessage("project sorts by key").isTrue();
        assertThat(project.get("nullable").asBoolean()).withFailMessage("every issue has a project").isFalse();
        var ops = new java.util.ArrayList<String>();
        for (var o : project.get("operators")) ops.add(o.asText());
        assertThat(ops).as("no IS [NOT] EMPTY on project, got " + ops).isEqualTo(List.of("=", "!=", "IN"));

        // The picklist is the caller's visible set — the same set every search is scoped to —
        // and it offers the KEY as the value so what a client pastes back is what resolves.
        var values = new java.util.LinkedHashMap<String, String>();
        for (var v : schema.get("values").get("PROJECT")) {
            values.put(v.get("value").asText(), v.get("label").asText());
        }
        assertThat(values.keySet())
                .as("the PROJECT picklist is the visible project set, got " + values)
                .isEqualTo(Set.of("HD", "OPS", keyOf(home)));
        assertThat(values.get("HD"))
                .as("the label carries the human name, which is how a user finds the key: " + values)
                .isEqualTo("Hamstrack (HD)");
        assertThat(values).as("a picklist must never cross the workspace boundary").doesNotContainKey("SEC");
    }

    // ==================================================== /suggest

    @Test
    void suggestPrefixMatchesKeyAndNameAndAlwaysOffersTheKey() throws Exception {
        var home = newProject();
        projectIn(home, "HD", "Hamstrack");
        projectIn(home, "OPS", "Operations");
        var elsewhere = newProject();
        projectIn(elsewhere, "SEC", "Secret Project");

        // by key …
        assertThat(suggestValues(home, "hd")).as("suggest matches a key prefix").isEqualTo(List.of("HD"));
        // … and by name, both case-insensitively; the value is the key either way
        assertThat(suggestValues(home, "hamstr"))
                .as("suggest matches a name prefix and still offers the KEY as the value")
                .isEqualTo(List.of("HD"));
        assertThat(suggestValues(home, "OPERAT"))
                .as("name matching is case-insensitive, and the value is the key either way")
                .isEqualTo(List.of("OPS"));
        assertThat(suggestLabels(home, "hd"))
                .as("the label carries the human name alongside the key the value uses")
                .isEqualTo(List.of("Hamstrack (HD)"));
        // prefix, not substring — "amstrack" is inside the name but does not start it
        assertThat(suggestValues(home, "amstrack")).as("suggest is a prefix match, not a substring one").isEmpty();
        // an empty q lists the visible projects, ordered by key, and never another tenant's
        var all = suggestValues(home, "");
        assertThat(all).as("%s", all).contains("HD");
        assertThat(all).as("%s", all).contains("OPS");
        assertThat(all).as("suggest must never cross the workspace boundary").doesNotContain("SEC");
        assertThat(all).as("%s", all).isEqualTo(all.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        // a non-member gets 404, never a peek at the vocabulary
        var outsider = login(user());
        mockMvc.perform(get("/api/workspaces/" + home.wsId() + "/search/suggest")
                        .param("field", "project").param("q", "")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
    }

    // ==================================================== the reserved-key guard

    /**
     * The forward-looking half of the custom-field collision (spec §4.8): a registered HQL name
     * outranks a tenant's custom field of that key, and {@code /schema} then drops the tenant's
     * field from the search vocabulary <em>silently</em>. New fields are refused under a claimed
     * key instead — checked AFTER slugification, because a field simply called "Project" is
     * keyed {@code project} automatically, which is how the collision happens without anybody
     * choosing it. It is not retroactive: an existing field keeps working everywhere but search.
     */
    @Test
    void aCustomFieldCannotBeCreatedUnderARegisteredSearchName() throws Exception {
        var home = newProject();
        String fields = "/api/workspaces/" + home.wsId() + "/admin/fields";

        // the slugified path — no key in the payload at all
        mockMvc.perform(post(fields).header("Authorization", "Bearer " + home.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Project\",\"type\":\"TEXT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("reserved search field name")));
        // and an explicit key, for a name the registry claims for another field
        mockMvc.perform(post(fields).header("Authorization", "Bearer " + home.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anything\",\"key\":\"status\",\"type\":\"TEXT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("reserved search field name")));
        // a key the registry has NOT claimed is unaffected
        mockMvc.perform(post(fields).header("Authorization", "Bearer " + home.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Project Phase\",\"type\":\"TEXT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("project_phase"));
    }

    // ==================================================== helpers

    /**
     * A further project in {@code ctx}'s workspace with an explicit key and name — the shared
     * {@code siblingProject} fixture, re-keyed, because the key/name distinction is the subject
     * here and its generated {@code P#####}/"Proj" pair says nothing about either.
     */
    private Ctx projectIn(Ctx ctx, String key, String name) throws Exception {
        var sibling = siblingProject(ctx);
        var project = projectRepository.findById(sibling.projectId()).orElseThrow();
        project.setKey(key);
        project.setName(name);
        projectRepository.save(project);
        return sibling;
    }

    private String keyOf(Ctx ctx) {
        return projectRepository.findById(ctx.projectId()).orElseThrow().getKey();
    }

    private ResultActions archive(Ctx ctx, Ctx target) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/"
                + target.projectId() + "/archive")
                .header("Authorization", "Bearer " + ctx.token()));
    }

    private void refused(Ctx ctx, String hql, String field, String detail) throws Exception {
        search(ctx.wsId(), ctx.token(), hql)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value(field))
                .andExpect(jsonPath("$.detail", containsString(detail)));
    }

    private JsonNode fieldNamed(JsonNode schema, String name) {
        for (var f : schema.get("fields")) {
            if (f.get("name").asText().equals(name)) return f;
        }
        throw new AssertionError("/schema does not advertise '" + name + "'");
    }

    private List<String> suggestValues(Ctx ctx, String q) throws Exception {
        return suggestField(ctx, q, "value");
    }

    private List<String> suggestLabels(Ctx ctx, String q) throws Exception {
        return suggestField(ctx, q, "label");
    }

    private List<String> suggestField(Ctx ctx, String q, String node) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                        .param("field", "project").param("q", q)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.field").value("project"))
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        for (var s : json.readTree(body).get("suggestions")) out.add(s.get(node).asText());
        return out;
    }

    /** Run an HQL query and return the matched issue titles (unordered). */
    private Set<String> found(Ctx ctx, String hql) throws Exception {
        return new java.util.HashSet<>(ordered(ctx, hql));
    }

    /** Run an HQL query and return the matched issue titles in RESULT order. */
    private List<String> ordered(Ctx ctx, String hql) throws Exception {
        var body = search(ctx.wsId(), ctx.token(), hql)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        for (var row : json.readTree(body).get("content")) {
            out.add(row.get("issue").get("title").asText());
        }
        return out;
    }

    private ResultActions search(UUID wsId, String token, String hql) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + wsId + "/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + json.writeValueAsString(hql) + ",\"size\":100}"));
    }
}
