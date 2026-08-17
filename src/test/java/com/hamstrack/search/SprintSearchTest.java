package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.SprintTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-22 §4.7 / §4.9 — the search sub-slice: the HQL fields {@code sprint} (an
 * {@code ENUM_REF} resolved by name across the caller's VISIBLE projects) and
 * {@code storyPoints} (the language's first <strong>native numeric</strong> field,
 * which replaced the old "NUMBER is not queryable in MVP" throw).
 *
 * <p>Two rules are more than smoke tests here:
 * <ul>
 *   <li><strong>{@code ORDER BY sprint} is a 422.</strong> Sprint order across several
 *       projects has no common meaning, so the descriptor is deliberately not sortable
 *       — {@code storyPoints}, which does have a cross-project meaning, is;</li>
 *   <li><strong>names resolve through the visible project set only</strong>, and
 *       COMPLETED sprints leave name resolution (years of history would flood the
 *       namespace) while the issues carrying them still match by id.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintSearchTest extends SprintTestBase {

    // ==================================================== sprint

    @Test
    void sprintEqualsNotEqualsInAndIsEmptyAllCompileAndReturnTheRightRows() throws Exception {
        var ctx = newProject();
        var one = createSprint(ctx, "Sprint 1");
        var two = createSprint(ctx, "Sprint 2");
        var inOne = createIssue(ctx, "in-one");
        var inTwo = createIssue(ctx, "in-two");
        createIssue(ctx, "loose");
        addIssuesToSprint(ctx, ctx.token(), one, idOf(inOne)).andExpect(status().isOk());
        addIssuesToSprint(ctx, ctx.token(), two, idOf(inTwo)).andExpect(status().isOk());

        assert found(ctx, "sprint = \"Sprint 1\"").equals(Set.of("in-one"));
        // != is "does not carry it", so the sprint-less issue matches too (the `component`
        // semantics this field reuses).
        assert found(ctx, "sprint != \"Sprint 1\"").equals(Set.of("in-two", "loose"));
        assert found(ctx, "sprint IN (\"Sprint 1\", \"Sprint 2\")").equals(Set.of("in-one", "in-two"));
        assert found(ctx, "sprint IS EMPTY").equals(Set.of("loose"));
        assert found(ctx, "sprint IS NOT EMPTY").equals(Set.of("in-one", "in-two"));
        // case-insensitive resolution + the plural alias is the same field
        assert found(ctx, "sprints = \"SPRINT 1\"").equals(Set.of("in-one"));
        // …and it composes with the rest of the language
        assert found(ctx, "sprint = \"Sprint 1\" AND text ~ \"in-\"").equals(Set.of("in-one"));
    }

    /** Sprint order across projects has no common meaning — the field is not sortable. */
    @Test
    void orderBySprintIs422() throws Exception {
        var ctx = newProject();
        createSprint(ctx, "Sprint 1");
        createIssue(ctx, "anything");

        search(ctx.wsId(), ctx.token(), "ORDER BY sprint ASC")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void sprintNamesResolveOnlyThroughVisibleProjectsAndNeverCrossATenant() throws Exception {
        var a = newProject();
        var b = newProject();
        var here = createSprint(a, "Shared name");
        var there = createSprint(b, "Shared name");
        var mine = createIssue(a, "a-issue");
        var theirs = createIssue(b, "b-issue");
        addIssuesToSprint(a, a.token(), here, idOf(mine)).andExpect(status().isOk());
        addIssuesToSprint(b, b.token(), there, idOf(theirs)).andExpect(status().isOk());

        assert found(a, "sprint = \"Shared name\"").equals(Set.of("a-issue"));
        assert found(b, "sprint = \"Shared name\"").equals(Set.of("b-issue"));

        // A name owned by two VISIBLE projects of one workspace matches issues in both.
        var sibling = siblingProject(a);
        var siblingSprint = createSprint(sibling, "Cross-project");
        var siblingIssue = createIssue(sibling, "sibling-issue");
        addIssuesToSprint(sibling, sibling.token(), siblingSprint, idOf(siblingIssue))
                .andExpect(status().isOk());
        var mySprint = createSprint(a, "Cross-project");
        var myIssue = createIssue(a, "my-issue");
        addIssuesToSprint(a, a.token(), mySprint, idOf(myIssue)).andExpect(status().isOk());
        assert found(a, "sprint = \"Cross-project\"").equals(Set.of("sibling-issue", "my-issue"));
    }

    /**
     * A COMPLETED sprint leaves NAME resolution (a name that maps to 40 archived
     * iterations is useless) — but the issues that carry it are still real, so
     * {@code IS NOT EMPTY} still finds them.
     */
    @Test
    void completedSprintsDropOutOfNameResolutionButTheirIssuesStillMatch() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");
        var delivered = createIssue(ctx, "delivered");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(delivered)).andExpect(status().isOk());
        markDone(ctx, numberOf(delivered));

        assert found(ctx, "sprint = \"Sprint 1\"").equals(Set.of("delivered"));

        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isOk());

        search(ctx.wsId(), ctx.token(), "sprint = \"Sprint 1\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("sprint"));
        assert found(ctx, "sprint IS NOT EMPTY").equals(Set.of("delivered"))
                : "the DONE issue keeps its sprint, so it must still match IS NOT EMPTY";
    }

    @Test
    void anUnknownSprintNameIs422() throws Exception {
        var ctx = newProject();
        createSprint(ctx, "Sprint 1");
        createIssue(ctx, "anything");

        search(ctx.wsId(), ctx.token(), "sprint = \"Sprint 99\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("sprint"));
    }

    // ==================================================== storyPoints

    @Test
    void storyPointsSupportsOrderedComparisonsIsEmptyAndSorting() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "one", "\"storyPoints\":1");
        createIssue(ctx, "five", "\"storyPoints\":5");
        createIssue(ctx, "thirteen", "\"storyPoints\":13");
        createIssue(ctx, "half", "\"storyPoints\":0.5");
        createIssue(ctx, "unestimated");

        assert found(ctx, "storyPoints >= 5").equals(Set.of("five", "thirteen"));
        assert found(ctx, "storyPoints > 5").equals(Set.of("thirteen"));
        assert found(ctx, "storyPoints < 1").equals(Set.of("half"));
        assert found(ctx, "storyPoints <= 1").equals(Set.of("half", "one"));
        assert found(ctx, "storyPoints = 13").equals(Set.of("thirteen"));
        assert found(ctx, "storyPoints = 0.5").equals(Set.of("half"));
        // `!=` deliberately also matches the UNESTIMATED issue: SQL's three-valued logic
        // would otherwise silently drop null rows, which reads as "13 points" to nobody.
        assert found(ctx, "storyPoints != 13").equals(Set.of("one", "five", "half", "unestimated"));
        // IS EMPTY is "unestimated" — deliberately NOT the same statement as = 0
        assert found(ctx, "storyPoints IS EMPTY").equals(Set.of("unestimated"));
        assert found(ctx, "points IS NOT EMPTY").equals(Set.of("one", "five", "thirteen", "half"));

        // sortable (unlike sprint): "show me the big ones first"
        assert orderedTitles(ctx, "storyPoints IS NOT EMPTY ORDER BY storyPoints DESC")
                .equals(List.of("thirteen", "five", "one", "half"));
        assert orderedTitles(ctx, "points IS NOT EMPTY ORDER BY points ASC")
                .equals(List.of("half", "one", "five", "thirteen"));
    }

    @Test
    void aNonNumericStoryPointsOperandIs422() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "anything", "\"storyPoints\":3");

        search(ctx.wsId(), ctx.token(), "storyPoints >= \"lots\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("storyPoints"));
    }

    /**
     * A {@code storyPoints} operand outside the field's own domain is a
     * <strong>422</strong>, not a 500 (0.13.0 review, security-officer L3).
     *
     * <p>Parsing alone accepts {@code storyPoints > 1e999999999}: the compiler binds it as
     * a {@code BigDecimal} and PostgreSQL then rejects it during parameter conversion with
     * SQLSTATE 22003 ("value overflows numeric format"). There is no handler for that, so
     * any plain member could turn a filter that could never have matched a row into a bare
     * 500 plus an ERROR stack trace. The query path now enforces the SAME bounds the write
     * path does ({@code Points}: 0…999, at most 2 decimals), so the two cannot drift — an
     * operand the write path would reject can match nothing anyway.
     */
    @Test
    void aStoryPointsOperandOutsideTheFieldsOwnDomainIs422() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "estimated", "\"storyPoints\":3");

        // Bare numeric literals (the lexer has no exponent form, so those come below).
        for (String operand : List.of(
                "1000",    // above issues_story_points_ck's ceiling
                "-1",      // an estimate is never negative
                "1.234")) {// finer than NUMERIC(5,2)
            search(ctx.wsId(), ctx.token(), "storyPoints > " + operand)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.field").value("storyPoints"));
        }
        // A QUOTED operand is resolved by the same code path — and it is the only way to
        // express the exponent form that produced the 22003 overflow in the first place.
        for (String operand : List.of(
                "1e999999999", "-1e999999999", "1000", "-1", "1.234")) {
            search(ctx.wsId(), ctx.token(), "storyPoints > \"" + operand + "\"")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.field").value("storyPoints"));
        }

        // The bounds are inclusive at both ends, and the domain's own values still run.
        assert found(ctx, "storyPoints >= 0").equals(Set.of("estimated"));
        assert found(ctx, "storyPoints <= 999").equals(Set.of("estimated"));
        assert found(ctx, "storyPoints != 0.25").equals(Set.of("estimated"));
    }

    // ==================================================== /schema + saved filters

    /**
     * <p><strong>Fixture note (HD-107 / delivery capabilities).</strong> Since HD-102 a
     * repository-bootstrapped project comes out <em>lean</em> — Kanban, estimation off —
     * and {@code /schema} suggests {@code sprint}/{@code storyPoints} only when at least
     * one VISIBLE project declares the matching capability (§9.1). So both projects here
     * now declare it, which is also the honest fixture: a project that owns sprints and
     * estimates its issues is a Scrum, estimating project. The capability-OFF side of
     * that behaviour — omission from suggestions while the fields still parse, compile
     * and run — is pinned in {@code DeliverySearchCompatibilityTest}, not here; this test
     * is about the descriptors' SHAPE (type, sortability, operators, alias de-duplication)
     * and the picklist's project scoping, which is what HD-22 §4.9 asked for.
     */
    @Test
    void schemaAdvertisesBothFieldsOnceWithASprintPicklist() throws Exception {
        var ctx = newProject();
        setDelivery(ctx, "\"board\":\"SCRUM\",\"estimation\":true");
        createSprint(ctx, "Sprint 1");
        var completed = startedSprint(ctx, "Sprint 0");
        completeToBacklog(ctx, ctx.token(), completed).andExpect(status().isOk());
        var sibling = siblingProject(ctx);
        setDelivery(sibling, "\"board\":\"SCRUM\"");
        createSprint(sibling, "Sibling sprint");

        var schema = json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                                .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                // each listed once under its canonical name; the aliases share the
                // descriptor instance and are de-duplicated by availableFields()
                .andExpect(jsonPath("$.fields[?(@.name == 'sprint')]", hasSize(1)))
                .andExpect(jsonPath("$.fields[?(@.name == 'storyPoints')]", hasSize(1)))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("sprints"))))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("points"))))
                .andReturn().getResponse().getContentAsString());

        var sprint = fieldNamed(schema, "sprint");
        assert sprint.get("type").asText().equals("ENUM_REF") : sprint;
        assert sprint.get("valueSuggest").asText().equals("SPRINT") : sprint;
        assert !sprint.get("sortable").asBoolean() : "sprint must NOT be sortable: " + sprint;
        assert sprint.get("nullable").asBoolean() : sprint;

        var points = fieldNamed(schema, "storyPoints");
        assert points.get("type").asText().equals("NUMBER") : points;
        assert points.get("sortable").asBoolean() : "storyPoints IS sortable: " + points;
        assert points.get("nullable").asBoolean() : points;
        assert points.get("valueSuggest").isNull() : "a number has no picklist: " + points;
        var ops = new java.util.ArrayList<String>();
        for (var o : points.get("operators")) ops.add(o.asText());
        assert ops.containsAll(List.of("=", "!=", ">", "<", ">=", "<=")) : ops;

        // The SPRINT picklist spans the visible projects' OPEN sprints only.
        var values = new java.util.HashSet<String>();
        for (var v : schema.get("values").get("SPRINT")) values.add(v.get("label").asText());
        assert values.equals(Set.of("Sprint 1", "Sibling sprint"))
                : "the SPRINT picklist must list open sprints of the visible projects, got " + values;
    }

    @Test
    void aSavedFilterUsingSprintSavesLoadsAndRuns() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var committed = createIssue(ctx, "committed");
        createIssue(ctx, "loose");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(committed)).andExpect(status().isOk());

        var created = json.readTree(mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/filters")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"this sprint\",\"hql\":\"sprint = \\\"Sprint 1\\\"\","
                                + "\"shared\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/filters/" + created.get("id").asText())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hql", containsString("sprint")));

        assert found(ctx, created.get("hql").asText()).equals(Set.of("committed"));
    }

    /** Search rows carry the new fields too, and still never carry the rank. */
    @Test
    void searchRowsCarrySprintAndStoryPointsButNotPosition() throws Exception {
        var ctx = newProject();
        var sprintId = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "committed", "\"storyPoints\":3");
        addIssuesToSprint(ctx, ctx.token(), sprintId, idOf(issue)).andExpect(status().isOk());

        var body = json.readTree(search(ctx.wsId(), ctx.token(), "sprint = \"Sprint 1\"")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var row = body.get("content").get(0).get("issue");
        assert "Sprint 1".equals(sprintName(row)) : row;
        assert row.get("storyPoints").asDouble() == 3.0 : row;
        assert !row.has("position") : "the rank must never be exposed: " + row;
    }

    // ==================================================== helpers

    private JsonNode fieldNamed(JsonNode schema, String name) {
        for (var f : schema.get("fields")) {
            if (f.get("name").asText().equals(name)) return f;
        }
        throw new AssertionError("/schema does not advertise '" + name + "'");
    }

    private Set<String> found(Ctx ctx, String hql) throws Exception {
        return new java.util.HashSet<>(orderedTitles(ctx, hql));
    }

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
