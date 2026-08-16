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

        assert found(ctx, "label = \"bug\"").equals(Set.of("only-bug", "both"));
        // != is "does not carry it" — NOT EXISTS, so the unlabelled issue matches too
        assert found(ctx, "label != \"bug\"").equals(Set.of("only-ui", "bare"));
        assert found(ctx, "label IN (\"bug\", \"ui\")").equals(Set.of("only-bug", "only-ui", "both"));
        assert found(ctx, "label IS EMPTY").equals(Set.of("bare"));
        assert found(ctx, "label IS NOT EMPTY").equals(Set.of("only-bug", "only-ui", "both"));
        // name resolution is case-insensitive, and the plural alias is the same field
        assert found(ctx, "labels = \"BUG\"").equals(Set.of("only-bug", "both"));
        // composes with the rest of the language
        assert found(ctx, "label = \"bug\" AND text ~ \"only\"").equals(Set.of("only-bug"));
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
        assert found(ctx, "label IS NOT EMPTY").equals(Set.of("carrier"));
    }

    @Test
    void hqlLabelQueriesNeverCrossTheWorkspaceBoundary() throws Exception {
        var a = newProject();
        var b = newProject();
        createIssue(a, "a-issue", labelIdsJson(createLabel(a, "shared-name")));
        createIssue(b, "b-issue", labelIdsJson(createLabel(b, "shared-name")));

        // The same label name exists in both tenants; each side sees only its own.
        assert found(a, "label = \"shared-name\"").equals(Set.of("a-issue"));
        assert found(b, "label = \"shared-name\"").equals(Set.of("b-issue"));
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

        assert found(ctx, created.get("hql").asText()).equals(Set.of("only-bug"));
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
        assert label.get("valueSuggest").asText().equals("LABEL") : label;
        assert label.get("type").asText().equals("LABEL_REF") : label;
        assert !label.get("sortable").asBoolean() : "label must not be sortable";
        assert label.get("nullable").asBoolean() : "label supports IS [NOT] EMPTY";
        var ops = new java.util.ArrayList<String>();
        for (var o : label.get("operators")) ops.add(o.asText());
        assert ops.equals(List.of("=", "!=", "IN", "IS EMPTY", "IS NOT EMPTY")) : ops;

        assert schema.get("values").get("LABEL").size() == 200
                : "the LABEL picklist is capped at 200, got " + schema.get("values").get("LABEL").size();
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
        assert suggestions(ctx, "axb").equals(List.of("axb"));
        // … and q=a_b must NOT match axb.
        assert suggestions(ctx, "a_b").equals(List.of("a_b"));
        // `%` is a literal percent, not "match everything".
        assert suggestions(ctx, "%").equals(List.of("100% done"))
                : "q=% must match only labels containing a literal %, got " + suggestions(ctx, "%");
        // an empty q lists the workspace's labels (bounded), never another tenant's
        var all = suggestions(ctx, "");
        assert all.equals(List.of("100% done", "a_b", "axb", "plain")) : all;
        assert !all.contains("foreign") : "suggest must never cross the workspace boundary";
        // case-insensitive substring match
        assert suggestions(ctx, "PLA").equals(List.of("plain"));

        // archived labels drop out of suggest (they are excluded from resolution too)
        UUID plainId = null;
        for (var l : listLabels(ctx, ctx.token(), null)) {
            if (l.get("name").asText().equals("plain")) plainId = UUID.fromString(l.get("id").asText());
        }
        archiveLabel(ctx, ctx.token(), plainId).andExpect(status().isOk());
        assert !suggestions(ctx, "").contains("plain");

        // the alias resolves to the same suggester
        assert suggestions(ctx, "labels", "axb").equals(List.of("axb"));
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
