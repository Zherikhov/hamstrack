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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HD-32's HQL surface (proposal §3.5, §6.6 "Filtering by fix version works"):
 * {@code fixVersion} and {@code affectsVersion} are two live {@code VERSION_REF}
 * fields over <strong>one</strong> {@code issue_versions} table, told apart only by
 * {@code link_type}. Many-valued like {@code label}, so neither carries an
 * {@code entityPath} — the compiler emits a correlated {@code EXISTS} filtered by the
 * role — and neither is sortable.
 *
 * <p><strong>The link-type separation is the point of this class.</strong> One shared
 * table, one shared subquery builder and one shared {@code VERSION} picklist make
 * dropping the {@code link_type} filter a one-line change that no other test would
 * notice: every fix-only fixture would still match {@code fixVersion = …}. So the
 * separation is asserted on <em>every</em> operator path — {@code =}, {@code !=},
 * {@code IN}, {@code IS EMPTY}, {@code IS NOT EMPTY} — against a project that
 * deliberately carries the same version in each role, in both roles, and in neither.
 *
 * <p>Two further things are more than smoke:
 * <ul>
 *   <li><strong>name resolution is built from the VISIBLE PROJECT set</strong>, not
 *       from the workspace, so a version name owned only by a project the actor cannot
 *       see must not resolve at all (422) — the tenancy rule expressed inside the query
 *       language;</li>
 *   <li><strong>{@code /schema} advertises each role exactly once.</strong> Unlike
 *       {@code labels}/{@code components}, the lowercase spellings {@code fixversion}
 *       and {@code affectsversion} are NOT alias entries — {@code FieldRegistry.find}
 *       lowercases every lookup and the registry key IS the lowercased canonical name,
 *       so case-insensitivity comes for free and must not show up as a duplicate field.
 *       A future "let's add the aliases for symmetry" edit would break the schema
 *       contract, which is what the count assertions pin.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class VersionSearchTest extends VersionTestBase {

    // ==================================================== compilation & semantics

    /**
     * The whole operator surface of both roles, on ONE fixture that carries the same
     * version as fix only, as affects only, as both, and not at all. Every line here
     * fails if the {@code link_type} filter is dropped from the correlated EXISTS.
     */
    @Test
    void everyOperatorSeparatesTheFixRoleFromTheAffectsRole() throws Exception {
        var ctx = newProject();
        var target = createVersion(ctx, "2.4.0");
        var other = createVersion(ctx, "2.3.0");

        createIssue(ctx, "fix-only", fixVersionIdsJson(target));
        createIssue(ctx, "affects-only", affectsVersionIdsJson(target));
        createIssue(ctx, "both roles", fixVersionIdsJson(target), affectsVersionIdsJson(target));
        createIssue(ctx, "elsewhere", fixVersionIdsJson(other));
        createIssue(ctx, "bare");

        // ---- = : "ships in 2.4.0" vs "the defect exists in 2.4.0" ----
        assert found(ctx, "fixVersion = \"2.4.0\"").equals(Set.of("fix-only", "both roles"))
                : "an AFFECTS-only link must not satisfy a FIX query";
        assert found(ctx, "affectsVersion = \"2.4.0\"").equals(Set.of("affects-only", "both roles"))
                : "a FIX-only link must not satisfy an AFFECTS query";

        // ---- != : "does NOT ship in 2.4.0" — includes issues with no fix version at
        // all AND issues that merely affect it (a different role is a different claim).
        assert found(ctx, "fixVersion != \"2.4.0\"")
                .equals(Set.of("affects-only", "elsewhere", "bare"));
        assert found(ctx, "affectsVersion != \"2.4.0\"")
                .equals(Set.of("fix-only", "elsewhere", "bare"));

        // ---- IN : the union of ids, still inside ONE role ----
        assert found(ctx, "fixVersion IN (\"2.4.0\", \"2.3.0\")")
                .equals(Set.of("fix-only", "both roles", "elsewhere"));
        assert found(ctx, "affectsVersion IN (\"2.4.0\", \"2.3.0\")")
                .equals(Set.of("affects-only", "both roles"))
                : "IN must not leak the other role's links either";

        // ---- IS [NOT] EMPTY : "has no fix version at all" — §6.6's unassigned work.
        // The affects-only issue is emphatically still `fixVersion IS EMPTY`.
        assert found(ctx, "fixVersion IS EMPTY").equals(Set.of("affects-only", "bare"));
        assert found(ctx, "fixVersion IS NOT EMPTY")
                .equals(Set.of("fix-only", "both roles", "elsewhere"));
        assert found(ctx, "affectsVersion IS EMPTY")
                .equals(Set.of("fix-only", "elsewhere", "bare"));
        assert found(ctx, "affectsVersion IS NOT EMPTY").equals(Set.of("affects-only", "both roles"));

        // ---- the two roles compose with each other and with the rest of the language
        assert found(ctx, "fixVersion = \"2.4.0\" AND affectsVersion = \"2.4.0\"")
                .equals(Set.of("both roles"));
        assert found(ctx, "fixVersion IS EMPTY AND affectsVersion IS NOT EMPTY")
                .equals(Set.of("affects-only"));
        assert found(ctx, "fixVersion = \"2.4.0\" AND text ~ \"fix-\"").equals(Set.of("fix-only"));
        assert found(ctx, "fixVersion = \"2.3.0\" OR affectsVersion = \"2.4.0\"")
                .equals(Set.of("elsewhere", "affects-only", "both roles"));

        // Field names are case-insensitive (the registry key IS the lowercased canonical
        // name), so the spec's all-lowercase spellings work without an alias entry.
        assert found(ctx, "fixversion = \"2.4.0\"").equals(Set.of("fix-only", "both roles"));
        assert found(ctx, "AFFECTSVERSION = \"2.4.0\"").equals(Set.of("affects-only", "both roles"));
    }

    /** An archived version keeps its links, so it keeps answering IS NOT EMPTY. */
    @Test
    void archivedAndReleasedVersionsBehaveAsDocumentedInResolution() throws Exception {
        var ctx = newProject();
        var stale = createVersion(ctx, "0.9.0");
        var shipped = createVersion(ctx, "2.3.1");
        createIssue(ctx, "on stale", fixVersionIdsJson(stale));
        createIssue(ctx, "on shipped", affectsVersionIdsJson(shipped));

        // A RELEASED version resolves normally — releasing is not archiving.
        releaseVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());
        assert found(ctx, "affectsVersion = \"2.3.1\"").equals(Set.of("on shipped"));

        // An ARCHIVED one drops out of name resolution (§3.5) → the documented 422…
        archiveVersion(ctx, ctx.token(), stale).andExpect(status().isOk());
        search(ctx.wsId(), ctx.token(), "fixVersion = \"0.9.0\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("fixVersion"))
                .andExpect(jsonPath("$.detail", containsString("No version named '0.9.0'")));
        // …but the issue still HAS a fix version, so IS NOT EMPTY still finds it.
        assert found(ctx, "fixVersion IS NOT EMPTY").equals(Set.of("on stale"));
    }

    /** Versions are project-owned, so one name can legitimately mean two rows. */
    @Test
    void aNameOwnedByTwoVisibleProjectsMatchesIssuesInBoth() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var here = createVersion(ctx, "2.4.0");
        var there = createVersion(sibling, "2.4.0");
        createIssue(ctx, "here", fixVersionIdsJson(here));
        createIssue(sibling, "there", fixVersionIdsJson(there));
        createIssue(ctx, "bare");

        assert found(ctx, "fixVersion = \"2.4.0\"").equals(Set.of("here", "there"))
                : "a name owned by two visible projects must match issues in both";
        assert found(ctx, "fixVersion IS EMPTY").equals(Set.of("bare"));
    }

    @Test
    void aVersionNameOnlyOwnedByAnInvisibleProjectDoesNotResolve() throws Exception {
        var ctx = newProject();
        var hidden = siblingProject(ctx);
        var secret = createVersion(hidden, "9.9.9-secret");
        createIssue(hidden, "hidden issue", fixVersionIdsJson(secret));
        createIssue(ctx, "visible issue");

        // While the project is visible the name resolves and its issue is found…
        assert found(ctx, "fixVersion = \"9.9.9-secret\"").equals(Set.of("hidden issue"));

        // …but an ARCHIVED project leaves the visible set (SearchScope), so the name is
        // no longer resolvable at all — a 422, not an empty result that would still
        // confirm the name exists somewhere.
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/" + hidden.projectId()
                        + "/archive").header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().is2xxSuccessful());

        for (var field : List.of("fixVersion", "affectsVersion")) {
            search(ctx.wsId(), ctx.token(), field + " = \"9.9.9-secret\"")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                    .andExpect(jsonPath("$.field").value(field))
                    .andExpect(jsonPath("$.detail",
                            containsString("No version named '9.9.9-secret'")));
        }
        // The issues of an invisible project are gone from every query, not just this one.
        assert found(ctx, "fixVersion IS NOT EMPTY").isEmpty();
        assert found(ctx, "fixVersion IS EMPTY").equals(Set.of("visible issue"));
    }

    @Test
    void hqlVersionQueriesNeverCrossTheWorkspaceBoundary() throws Exception {
        var a = newProject();
        var b = newProject();
        createIssue(a, "a-issue", fixVersionIdsJson(createVersion(a, "2.4.0")));
        createIssue(b, "b-issue", affectsVersionIdsJson(createVersion(b, "2.4.0")));

        // The same version name exists in both tenants; each side sees only its own —
        // and only through the role its own issue actually uses.
        assert found(a, "fixVersion = \"2.4.0\"").equals(Set.of("a-issue"));
        assert found(a, "affectsVersion = \"2.4.0\"").isEmpty();
        assert found(b, "affectsVersion = \"2.4.0\"").equals(Set.of("b-issue"));
        assert found(b, "fixVersion = \"2.4.0\"").isEmpty();
    }

    @Test
    void anUnknownVersionNameIs422OnBothRoles() throws Exception {
        var ctx = newProject();
        createVersion(ctx, "2.4.0");

        for (var field : List.of("fixVersion", "affectsVersion")) {
            search(ctx.wsId(), ctx.token(), field + " = \"nope\"")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.field").value(field))
                    .andExpect(jsonPath("$.detail", containsString("No version named 'nope'")));
            // Inside an IN list too — one bad member fails the whole query.
            search(ctx.wsId(), ctx.token(), field + " IN (\"2.4.0\", \"nope\")")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail", containsString("No version named 'nope'")));
        }
    }

    /**
     * An issue carries a <em>set</em> of versions per role, so there is no meaningful
     * {@code ORDER BY} key — both descriptors declare {@code sortable = false} and
     * {@code HqlValidator} rejects the sort before compilation. (This is the documented
     * difference from {@code component}, which is single-valued and sortable.)
     */
    @Test
    void neitherRoleIsSortable() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "anything");

        for (var field : List.of("fixVersion", "affectsVersion")) {
            search(ctx.wsId(), ctx.token(), "ORDER BY " + field + " ASC")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                    .andExpect(jsonPath("$.field").value(field))
                    .andExpect(jsonPath("$.detail", containsString("is not sortable")));
        }
    }

    @Test
    void aSavedFilterUsingFixVersionSavesLoadsAndRuns() throws Exception {
        var ctx = newProject();
        var target = createVersion(ctx, "2.4.0");
        createIssue(ctx, "shipping", fixVersionIdsJson(target));
        createIssue(ctx, "merely affected", affectsVersionIdsJson(target));

        var created = json.readTree(mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/filters")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"2.4.0 scope\",\"hql\":\"fixVersion = \\\"2.4.0\\\"\","
                                + "\"shared\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/filters/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hql", containsString("fixVersion")));

        assert found(ctx, created.get("hql").asText()).equals(Set.of("shipping"))
                : "a stored fixVersion filter must not start matching AFFECTS links";
    }

    // ==================================================== /schema

    @Test
    void schemaAdvertisesBothRolesExactlyOnceSharingOneVersionPicklist() throws Exception {
        var ctx = newProject();
        createVersion(ctx, "2.4.0");
        createVersion(ctx, "2.3.1");
        // A version in a sibling project shows up too (the picklist is the visible set).
        createVersion(siblingProject(ctx), "1.0.0-sibling");

        var schema = json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                                .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name == 'fixVersion')]", hasSize(1)))
                .andExpect(jsonPath("$.fields[?(@.name == 'affectsVersion')]", hasSize(1)))
                // The lowercase spellings are NOT separate alias entries (unlike
                // `labels`/`components`): FieldRegistry.find lowercases every lookup and
                // the key IS the lowercased canonical name.
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("fixversion"))))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("affectsversion"))))
                .andReturn().getResponse().getContentAsString());

        for (var name : List.of("fixVersion", "affectsVersion")) {
            var field = fieldNamed(schema, name);
            assert field.get("type").asText().equals("VERSION_REF") : field;
            // ONE picklist backs both roles — the two fields differ in meaning, not in
            // vocabulary.
            assert field.get("valueSuggest").asText().equals("VERSION") : field;
            assert !field.get("sortable").asBoolean()
                    : name + " is many-valued, so it must NOT be sortable";
            assert field.get("nullable").asBoolean() : name + " supports IS [NOT] EMPTY";
            var ops = new java.util.ArrayList<String>();
            for (var o : field.get("operators")) ops.add(o.asText());
            assert ops.equals(List.of("=", "!=", "IN", "IS EMPTY", "IS NOT EMPTY")) : ops;
        }

        var values = new java.util.HashSet<String>();
        for (var v : schema.get("values").get("VERSION")) values.add(v.get("label").asText());
        assert values.equals(Set.of("2.4.0", "2.3.1", "1.0.0-sibling"))
                : "the VERSION picklist spans the visible projects, got " + values;
    }

    // ==================================================== /suggest

    @Test
    void suggestIsScopedToVisibleProjectsAndTreatsPercentAndUnderscoreAsLiteralCharacters()
            throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var otherTenant = newProject();
        createVersion(ctx, "a_b");               // the LIKE-wildcard trap
        createVersion(ctx, "axb");
        createVersion(ctx, "100% done");
        createVersion(ctx, "plain");
        createVersion(sibling, "sibling 1.0");
        createVersion(otherTenant, "foreign");

        // `_` is a literal underscore, not "any character": q=axb must NOT match a_b …
        assert suggestions(ctx, "axb").equals(List.of("axb"));
        // … and q=a_b must NOT match axb.
        assert suggestions(ctx, "a_b").equals(List.of("a_b"));
        // `%` is a literal percent, not "match everything".
        assert suggestions(ctx, "%").equals(List.of("100% done"))
                : "q=% must match only versions containing a literal %, got " + suggestions(ctx, "%");
        // an empty q lists the visible projects' versions, never another tenant's
        var all = suggestions(ctx, "");
        assert all.equals(List.of("100% done", "a_b", "axb", "plain", "sibling 1.0")) : all;
        assert !all.contains("foreign") : "suggest must never cross the workspace boundary";
        // case-insensitive substring match
        assert suggestions(ctx, "PLA").equals(List.of("plain"));

        // Both roles share ONE catalog, so the two field names give identical lists.
        assert suggestions(ctx, "affectsVersion", "").equals(all);
        assert suggestions(ctx, "fixversion", "axb").equals(List.of("axb"));

        // archived versions drop out of suggest (they are excluded from resolution too);
        // a RELEASED one stays, because it is still a legitimate thing to name.
        UUID plainId = null;
        UUID hundredId = null;
        for (var v : listVersions(ctx, ctx.token(), null)) {
            if (v.get("name").asText().equals("plain")) plainId = UUID.fromString(v.get("id").asText());
            if (v.get("name").asText().equals("100% done")) {
                hundredId = UUID.fromString(v.get("id").asText());
            }
        }
        releaseVersion(ctx, ctx.token(), hundredId).andExpect(status().isOk());
        assert suggestions(ctx, "").contains("100% done") : "releasing must not hide a version";
        archiveVersion(ctx, ctx.token(), plainId).andExpect(status().isOk());
        assert !suggestions(ctx, "").contains("plain");

        // a non-member gets 404, never a peek at the vocabulary
        var outsider = login(user());
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                        .param("field", "fixVersion").param("q", "")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
    }

    /** Two projects may each ship a "2.4.0" — the suggestion list is about NAMES. */
    @Test
    void suggestDeduplicatesTheSameNameOwnedByTwoProjects() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        createVersion(ctx, "2.4.0");
        createVersion(sibling, "2.4.0");

        var suggestions = suggestions(ctx, "2.4");
        assert suggestions.equals(List.of("2.4.0")) : "de-duplicated by name, got " + suggestions;
    }

    // ==================================================== helpers

    private JsonNode fieldNamed(JsonNode schema, String name) {
        for (var f : schema.get("fields")) {
            if (f.get("name").asText().equals(name)) return f;
        }
        throw new AssertionError("/schema does not advertise '" + name + "'");
    }

    private List<String> suggestions(Ctx ctx, String q) throws Exception {
        return suggestions(ctx, "fixVersion", q);
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
        var body = search(ctx.wsId(), ctx.token(), hql)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.HashSet<String>();
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
