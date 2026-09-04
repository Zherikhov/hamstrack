package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.LabelTestBase;
import com.hamstrack.issue.entity.Label;
import com.hamstrack.issue.repository.LabelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * HD-30's HQL surface (proposal §3.5, §4.6 "HQL"): {@code label} is a live
 * {@code LABEL_REF} field compiled to a correlated EXISTS over {@code IssueLabel},
 * with the plural {@code labels} alias, a capped {@code LABEL} picklist on
 * {@code /schema}, and a bounded {@code /suggest} typeahead.
 *
 * <p>The suggest test is the interesting one: containment is implemented with
 * {@code LOCATE}, not {@code LIKE '%'||:q||'%'}. Under LIKE a user typing {@code _}
 * matched any character and {@code %} matched every label in the workspace — wrong
 * results and a free scan amplifier. Every character must be literal.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class LabelSearchTest extends LabelTestBase {

    @Autowired LabelRepository labelRepository;

    // ==================================================== compilation & semantics

    @Test
    void labelEqualsNotEqualsInAndIsEmptyAllCompileAndReturnTheRightRows() throws Exception {
        var ctx = newProject();
        var bug = createLabel(ctx, "bug");
        var ui = createLabel(ctx, "ui");
        createIssue(ctx, "only-bug", labelIdsJson(bug));
        createIssue(ctx, "only-ui", labelIdsJson(ui));
        createIssue(ctx, "both", labelIdsJson(bug, ui));
        createIssue(ctx, "bare");

        assertThat(found(ctx, "label = \"bug\""))
                .as("label = resolves a label name to every issue carrying it")
                .isEqualTo(Set.of("only-bug", "both"));
        // != is "does not carry it" — NOT EXISTS, so the unlabelled issue matches too
        assertThat(found(ctx, "label != \"bug\""))
                .as("label != is 'does not carry it' — a NOT EXISTS, so the unlabelled issue matches too")
                .isEqualTo(Set.of("only-ui", "bare"));
        assertThat(found(ctx, "label IN (\"bug\", \"ui\")"))
                .as("IN unions the named labels without duplicating the issue that carries both")
                .isEqualTo(Set.of("only-bug", "only-ui", "both"));
        assertThat(found(ctx, "label IS EMPTY"))
                .as("label IS EMPTY is the issues carrying no label at all")
                .isEqualTo(Set.of("bare"));
        assertThat(found(ctx, "label IS NOT EMPTY"))
                .as("label IS NOT EMPTY is the complement of IS EMPTY over the same rows")
                .isEqualTo(Set.of("only-bug", "only-ui", "both"));
        // name resolution is case-insensitive, and the plural alias is the same field
        assertThat(found(ctx, "labels = \"BUG\""))
                .as("name resolution is case-insensitive and the plural alias is the same field")
                .isEqualTo(Set.of("only-bug", "both"));
        // composes with the rest of the language
        assertThat(found(ctx, "label = \"bug\" AND text ~ \"only\""))
                .as("label composes with the rest of the language instead of being a special case")
                .isEqualTo(Set.of("only-bug"));
    }

    @Test
    void labelIsNotSortableAndAnUnknownNameIs422() throws Exception {
        var ctx = newProject();
        createLabel(ctx, "bug");
        createIssue(ctx, "x", labelIdsJson(createLabel(ctx, "ui")));

        search(ctx.wsId(), ctx.token(), "ORDER BY label")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("label"));

        search(ctx.wsId(), ctx.token(), "label = \"nope\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("No label named 'nope'")));
    }

    @Test
    void anArchivedLabelDropsOutOfNameResolutionButStillMatchesTheIssuesCarryingIt() throws Exception {
        var ctx = newProject();
        var stale = createLabel(ctx, "stale");
        createIssue(ctx, "carrier", labelIdsJson(stale));
        archiveLabel(ctx, ctx.token(), stale).andExpect(status().isOk());

        // resolution excludes archived rows (§3.5) → the documented run-time 422
        search(ctx.wsId(), ctx.token(), "label = \"stale\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("No label named 'stale'")));
        // …but the issue still HAS a label, so IS NOT EMPTY still finds it
        assertThat(found(ctx, "label IS NOT EMPTY"))
                .as("an archived label drops out of NAME resolution, but the issue still HAS it, so IS NOT EMPTY still finds it")
                .isEqualTo(Set.of("carrier"));
    }

    @Test
    void hqlLabelQueriesNeverCrossTheWorkspaceBoundary() throws Exception {
        var a = newProject();
        var b = newProject();
        createIssue(a, "a-issue", labelIdsJson(createLabel(a, "shared-name")));
        createIssue(b, "b-issue", labelIdsJson(createLabel(b, "shared-name")));

        // The same label name exists in both tenants; each side sees only its own.
        assertThat(found(a, "label = \"shared-name\""))
                .as("the same label name exists in both tenants and each side resolves its own")
                .isEqualTo(Set.of("a-issue"));
        assertThat(found(b, "label = \"shared-name\""))
                .as("the other tenant resolves the shared name to its own label, never across")
                .isEqualTo(Set.of("b-issue"));
    }

    @Test
    void aSavedFilterUsingLabelSavesLoadsAndRuns() throws Exception {
        var ctx = newProject();
        var bug = createLabel(ctx, "bug");
        createIssue(ctx, "only-bug", labelIdsJson(bug));
        createIssue(ctx, "bare");

        var created = json.readTree(mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/filters")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bugs\",\"hql\":\"label = \\\"bug\\\"\",\"shared\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/filters/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hql", containsString("label")));

        assertThat(found(ctx, created.get("hql").asText()))
                .as("a saved filter round-trips: the stored HQL runs and finds what it found when it was saved")
                .isEqualTo(Set.of("only-bug"));
    }

    // ==================================================== /schema

    @Test
    void schemaAdvertisesLabelOnceAndCapsTheLabelPicklistAt200() throws Exception {
        var ctx = newProject();
        // 205 labels — created straight through the repository (this is bulk fixture
        // volume, not behavior under test; the API path is covered by LabelApiTest).
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        var bulk = new java.util.ArrayList<Label>();
        for (int i = 0; i < 205; i++) {
            var l = new Label();
            l.setWorkspace(ws);
            l.setName(String.format("bulk-%03d", i));
            l.setColor("#667085");
            bulk.add(l);
        }
        labelRepository.saveAll(bulk);

        var schema = json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                                .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                // listed once under its canonical name; `labels` is an alias sharing
                // the same descriptor instance, de-duplicated by availableFields()
                .andExpect(jsonPath("$.fields[?(@.name == 'label')]", hasSize(1)))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("labels"))))
                .andReturn().getResponse().getContentAsString());

        var label = fieldNamed(schema, "label");
        assertThat(label.get("valueSuggest").asText()).as("%s", label).isEqualTo("LABEL");
        assertThat(label.get("type").asText()).as("%s", label).isEqualTo("LABEL_REF");
        assertThat(label.get("sortable").asBoolean()).withFailMessage("label must not be sortable").isFalse();
        assertThat(label.get("nullable").asBoolean()).withFailMessage("label supports IS [NOT] EMPTY").isTrue();
        var ops = new java.util.ArrayList<String>();
        for (var o : label.get("operators")) ops.add(o.asText());
        assertThat(ops).as("%s", ops).isEqualTo(List.of("=", "!=", "IN", "IS EMPTY", "IS NOT EMPTY"));

        assertThat(schema.get("values").get("LABEL"))
                .as(() -> "the LABEL picklist is capped at 200, got " + schema.get("values").get("LABEL").size())
                .hasSize(200);
    }

    // ==================================================== /suggest

    @Test
    void suggestIsBoundedScopedAndTreatsPercentAndUnderscoreAsLiteralCharacters() throws Exception {
        var ctx = newProject();
        var other = newProject();
        createLabel(ctx, "a_b");           // the LIKE-wildcard trap
        createLabel(ctx, "axb");
        createLabel(ctx, "100% done");
        createLabel(ctx, "plain");
        createLabel(other, "foreign");

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
                .as("q=% must match only labels containing a literal %, got " + suggestions(ctx, "%"))
                .isEqualTo(List.of("100% done"));
        // an empty q lists the workspace's labels (bounded), never another tenant's
        var all = suggestions(ctx, "");
        assertThat(all).as("%s", all).isEqualTo(List.of("100% done", "a_b", "axb", "plain"));
        assertThat(all).as("suggest must never cross the workspace boundary").doesNotContain("foreign");
        // case-insensitive substring match
        assertThat(suggestions(ctx, "PLA")).as("suggest is a case-insensitive substring match").isEqualTo(List.of("plain"));

        // archived labels drop out of suggest (they are excluded from resolution too)
        UUID plainId = null;
        for (var l : listLabels(ctx, ctx.token(), null)) {
            if (l.get("name").asText().equals("plain")) plainId = UUID.fromString(l.get("id").asText());
        }
        archiveLabel(ctx, ctx.token(), plainId).andExpect(status().isOk());
        assertThat(suggestions(ctx, "")).as("an archived label drops out of suggest").doesNotContain("plain");

        // the alias resolves to the same suggester
        assertThat(suggestions(ctx, "labels", "axb"))
                .as("the plural alias resolves to the same suggester")
                .isEqualTo(List.of("axb"));
        // and a non-member gets 404, never a peek at the vocabulary
        var outsider = login(user());
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                        .param("field", "label").param("q", "")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
    }

    // ==================================================== helpers

    private JsonNode fieldNamed(JsonNode schema, String name) {
        for (var f : schema.get("fields")) {
            if (f.get("name").asText().equals(name)) return f;
        }
        throw new AssertionError("/schema does not advertise '" + name + "'");
    }

    private List<String> suggestions(Ctx ctx, String q) throws Exception {
        return suggestions(ctx, "label", q);
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

    /** Run an HQL query and return the matched issue titles. */
    private Set<String> found(Ctx ctx, String hql) throws Exception {
        var body = search(ctx.wsId(), ctx.token(), hql)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.HashSet<String>();
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
