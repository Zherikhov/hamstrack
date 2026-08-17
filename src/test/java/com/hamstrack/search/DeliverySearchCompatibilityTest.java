package com.hamstrack.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.issue.SprintTestBase;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetItemRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-107 slice S6 — <strong>search backwards-compatibility</strong> under delivery
 * capabilities ({@code docs/design/delivery-paths-proposal.md} §9, acceptance criteria
 * §14.2 "Search").
 *
 * <p>Two rules, and they pull in opposite directions, which is exactly why they are
 * pinned together in one suite:
 *
 * <ul>
 *   <li><strong>§9.1 — hidden fields still resolve, always.</strong> {@code sprint},
 *       {@code fixVersion}, {@code affectsVersion} and {@code storyPoints} keep parsing,
 *       compiling and running whatever any project's capabilities say. Search is
 *       cross-project by nature, so a filter that stopped working the moment a colleague
 *       flipped a toggle would be the same class of failure the epic exists to fix. The
 *       only capability-awareness allowed anywhere in search is
 *       <em>suggestion-only</em>: which fields {@code /schema} offers, and which values
 *       the SPRINT/VERSION picklists and {@code /suggest} draw from.</li>
 *   <li><strong>§9.2 — retired keys become permanent aliases.</strong> V11 archived the
 *       global {@code story_points} custom field and promoted the value to the native
 *       column under the name {@code storyPoints}, which silently broke every saved
 *       filter written as {@code story_points = 5}. The alias restores those — but as a
 *       <em>last-resort fallback</em>, never a {@link FieldRegistry} entry, because the
 *       registry always beats a custom field: registering it would permanently shadow
 *       any tenant's own field keyed {@code story_points}, in every workspace, forever.
 *       {@link #aTenantsOwnCustomFieldKeyedStoryPointsBeatsTheAlias()} is the test that
 *       fails if that precedence is ever inverted, and its failure mode is silent
 *       corruption of somebody else's data — the query still returns rows, just the
 *       wrong ones.</li>
 * </ul>
 *
 * <p>{@code newProject()} bootstraps through the repository, so every fixture here starts
 * <em>lean</em> (Kanban, releases off, estimation off — the §7 new-project default). Each
 * test still states the capabilities it depends on through the real PATCH endpoint rather
 * than leaning on that default, so a change to the default cannot silently turn one of
 * these into a test of nothing.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DeliverySearchCompatibilityTest extends SprintTestBase {

    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired FieldSetRepository fieldSetRepository;
    @Autowired FieldSetItemRepository fieldSetItemRepository;

    // ==================================================== §9.2 the retired-key alias

    /**
     * §9.2 / §14.2 — "a saved filter written as {@code story_points = 5} runs and returns
     * the same rows as {@code storyPoints = 5}". Not "returns something": the SAME rows,
     * across every operator class the field supports, because an alias that behaved even
     * slightly differently from its target would be a second implementation of the field
     * rather than a compatibility shim.
     */
    @Test
    void theRetiredStoryPointsKeyResolvesToTheNativeColumn() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "one", "\"storyPoints\":1");
        createIssue(ctx, "five", "\"storyPoints\":5");
        createIssue(ctx, "thirteen", "\"storyPoints\":13");
        createIssue(ctx, "unestimated");

        // ordered comparison, equality, and the null-shaped operator
        assertSameRows(ctx, "storyPoints >= 5", "story_points >= 5", Set.of("five", "thirteen"));
        assertSameRows(ctx, "storyPoints = 13", "story_points = 13", Set.of("thirteen"));
        assertSameRows(ctx, "storyPoints IS EMPTY", "story_points IS EMPTY", Set.of("unestimated"));
        assertSameRows(ctx, "storyPoints IS NOT EMPTY", "story_points IS NOT EMPTY",
                Set.of("one", "five", "thirteen"));

        // ORDER BY — the second entry point (see orderByResolvesTheRetiredKeyAtBothEntryPoints)
        assert titles(ctx, "story_points IS NOT EMPTY ORDER BY story_points DESC")
                .equals(titles(ctx, "storyPoints IS NOT EMPTY ORDER BY storyPoints DESC"))
                : "the alias sorts differently from the field it aliases";
        assert titles(ctx, "story_points IS NOT EMPTY ORDER BY story_points DESC")
                .equals(List.of("thirteen", "five", "one"));

        // Case-insensitive, like every other HQL name lookup — a saved filter may carry
        // any casing its author typed.
        assertSameRows(ctx, "storyPoints >= 5", "STORY_POINTS >= 5", Set.of("five", "thirteen"));
        assertSameRows(ctx, "storyPoints >= 5", "Story_Points >= 5", Set.of("five", "thirteen"));

        // …and the value domain is the field's own, not a looser alias path (0.13.0's
        // 22003 overflow guard must apply to both names).
        search(ctx, "story_points > 1000").andExpect(status().isUnprocessableContent());
    }

    /**
     * §9.2 — the alias is <strong>compatibility, not vocabulary</strong>: it never appears
     * in {@code /schema}, even on a project that estimates. Offering it would teach the
     * retired name to users who never used it, and would also make it look like a field a
     * tenant could not then create for itself.
     */
    @Test
    void theAliasIsNeverAdvertisedAsVocabulary() throws Exception {
        var ctx = newProject();
        enableEveryCapability(ctx);

        var names = schemaFieldNames(ctx);
        assert names.contains("storyPoints") : "estimation is on, so the canonical name is offered";
        assert !names.contains("story_points")
                : "the retired key is compatibility, not vocabulary — it must not be suggested: " + names;
    }

    /**
     * <strong>The load-bearing precedence test (§9.2).</strong> A workspace that keys its
     * own custom field {@code story_points} must resolve that name to <em>its own field</em>
     * — the alias is consulted only after both normal resolution steps miss.
     *
     * <p>This is the one whose failure silently breaks other people's data. Registering the
     * retired key in {@link FieldRegistry} (the obvious implementation) would shadow the
     * tenant's field in every workspace forever: the query would still return 200 and still
     * return rows, just the wrong ones, read out of a column the tenant never populated.
     * Nobody would file a bug against "search works"; they would conclude their data was
     * lost. So the assertion is deliberately two-sided — the tenant's key reaches the JSONB
     * custom field, and {@code storyPoints} still reaches the native column in the same
     * workspace — and a second workspace proves the alias itself still works for everyone
     * who did NOT define such a field.
     */
    @Test
    void aTenantsOwnCustomFieldKeyedStoryPointsBeatsTheAlias() throws Exception {
        var ctx = newProject();
        var ownKey = bindCustomField(ctx, "story_points", "Story points (tenant)");

        // Deliberately crossed values: an issue estimated 8 in the NATIVE column carries
        // nothing in the custom field, and vice versa. If the precedence ever inverts, the
        // two queries below swap their answers instead of both returning nothing.
        createIssue(ctx, "native-8", "\"storyPoints\":8");
        createIssue(ctx, "custom-8", "\"fields\":{\"" + ownKey + "\":8}");

        assert found(ctx, "story_points >= 5").equals(Set.of("custom-8"))
                : "the tenant's own `story_points` custom field was shadowed by the retired-key alias";
        assert found(ctx, "storyPoints >= 5").equals(Set.of("native-8"))
                : "the canonical name must still reach the native column in the same workspace";
        assert found(ctx, "story_points = 8").equals(Set.of("custom-8"));
        // IS EMPTY follows the same resolution: "carries no value for the tenant's field".
        assert found(ctx, "story_points IS EMPTY").equals(Set.of("native-8"));

        // ORDER BY resolves through the same precedence: a custom field is not sortable in
        // MVP, so the sort key is a 422 naming the TENANT's field — never a silent fall
        // through to the sortable native column.
        search(ctx, "story_points IS NOT EMPTY ORDER BY story_points ASC")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("story_points"))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("not sortable")));

        // …and the /schema entry for that name is the tenant's field, not the native one.
        var names = schemaFieldNames(ctx);
        assert names.contains("story_points")
                : "a tenant's own custom field is ordinary vocabulary and must be suggested: " + names;

        // A DIFFERENT workspace, which defined no such field, still gets the alias — the
        // shadowing is scoped to the tenant that owns the field, exactly like every other
        // custom-field name (HD-52).
        var other = newProject();
        createIssue(other, "elsewhere-8", "\"storyPoints\":8");
        assert found(other, "story_points >= 5").equals(Set.of("elsewhere-8"))
                : "one tenant's custom field must not disable the retired-key alias for everybody else";
    }

    /**
     * <strong>The two-entry-point bug (§9.2 × §9.1).</strong> {@code HqlValidator} and
     * {@code HqlCompiler} resolve field names independently: the validator approves a query,
     * the compiler then builds it. Before this slice the alias reached only the predicate
     * paths, so {@code ORDER BY story_points} was <em>accepted</em> by the validator and then
     * threw "unknown field" inside {@code orderBy}, which had no {@link ResolutionContext} to
     * resolve with — a 500-shaped failure on a query the server had just declared valid.
     *
     * <p>So both entry points are driven here: the validator alone (saving a filter parses
     * and validates but never compiles) and the validator + compiler together (running the
     * search). A regression that fixes only one of them still fails this test.
     */
    @Test
    void orderByResolvesTheRetiredKeyAtBothEntryPoints() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "one", "\"storyPoints\":1");
        createIssue(ctx, "thirteen", "\"storyPoints\":13");

        // Entry point 1 — HqlValidator only: POST /filters parses + validates the stored
        // text and never compiles it.
        var hql = "story_points IS NOT EMPTY ORDER BY story_points DESC";
        var filterId = createFilter(ctx, "biggest first", hql);

        // Entry point 2 — HqlValidator + HqlCompiler: running the very query the validator
        // approved must not throw "unknown field" from the ORDER BY branch.
        assert titles(ctx, storedHql(ctx, filterId)).equals(List.of("thirteen", "one"))
                : "the ORDER BY compiler branch does not resolve the retired key";
        assert titles(ctx, "story_points IS NOT EMPTY ORDER BY story_points ASC")
                .equals(List.of("one", "thirteen"));

        // The alias inherits the target's sortability rather than loosening it: `sprint` is
        // deliberately not sortable and stays a 422 under either name.
        search(ctx, "ORDER BY sprint ASC").andExpect(status().isUnprocessableContent());
        // …and a name that is neither a system field, a custom field nor a retired key is
        // still "unknown field" at BOTH entry points, not a silent pass-through.
        search(ctx, "ORDER BY story_pointz ASC")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("Unknown field")));
        postFilter(ctx, "bad", "ORDER BY story_pointz ASC")
                .andExpect(status().isUnprocessableContent());
    }

    // ============================================ §9.1 hidden fields still resolve

    /**
     * §9.1 / §14.2 — {@code sprint}, {@code fixVersion}, {@code affectsVersion} and
     * {@code storyPoints} compile and return rows even when every visible project has the
     * corresponding capability <strong>off</strong>. The capability is a presentation
     * preference (Rule A); it is not, and must never become, a query-time boundary.
     *
     * <p>The negative half matters as much: an unknown <em>value</em> stays a field-anchored
     * <strong>422</strong>. "Hidden field ⇒ 404" and "hidden field ⇒ silently 0 rows" are the
     * two tempting shortcuts, and both would turn a typo and a hidden field into the same
     * indistinguishable answer.
     */
    @Test
    void hiddenFieldsStillResolveOnACapabilityOffProject() throws Exception {
        var ctx = newProject();
        // Build the data first, then state the lean capabilities explicitly — the order a
        // real project reaches this state in (§13: turning a capability off keeps the data).
        var sprintId = createSprint(ctx, "Sprint 7");
        var fixVersion = createVersion(ctx, "2.4.0");
        var affects = createVersion(ctx, "2.3.0");
        var committed = createIssue(ctx, "committed",
                "\"storyPoints\":8",
                "\"sprintId\":\"" + sprintId + "\"",
                fixVersionIdsJson(fixVersion),
                affectsVersionIdsJson(affects));
        createIssue(ctx, "loose");
        disableEveryCapability(ctx);

        assert found(ctx, "sprint = \"Sprint 7\"").equals(Set.of("committed"));
        assert found(ctx, "fixVersion = \"2.4.0\"").equals(Set.of("committed"));
        assert found(ctx, "affectsVersion = \"2.3.0\"").equals(Set.of("committed"));
        assert found(ctx, "storyPoints >= 5").equals(Set.of("committed"));
        assert found(ctx, "story_points >= 5").equals(Set.of("committed"))
                : "the retired-key alias must work on a capability-off project too";
        // …composed, sorted, and through the null-shaped operators — a real saved filter is
        // rarely one term.
        assert found(ctx, "sprint = \"Sprint 7\" AND fixVersion = \"2.4.0\" AND storyPoints > 5")
                .equals(Set.of("committed"));
        assert found(ctx, "sprint IS EMPTY").equals(Set.of("loose"));
        assert titles(ctx, "storyPoints IS NOT EMPTY ORDER BY storyPoints DESC")
                .equals(List.of("committed"));

        // An unknown VALUE is still a field-anchored 422 — never a 404, never a quiet 200
        // with no rows (which would read as "nothing matches" instead of "no such sprint").
        for (String[] probe : new String[][]{
                {"sprint = \"Sprint 99\"", "sprint"},
                {"fixVersion = \"9.9.9\"", "fixVersion"},
                {"affectsVersion = \"9.9.9\"", "affectsVersion"}}) {
            search(ctx, probe[0])
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                    .andExpect(jsonPath("$.field").value(probe[1]));
        }
        // …and a non-numeric estimate operand keeps its own 422 with estimation off.
        search(ctx, "storyPoints >= \"lots\"")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.field").value("storyPoints"));
    }

    /**
     * §9.1 — <strong>a saved filter must not break because somebody flipped a toggle.</strong>
     * The filter is written and stored while the capabilities are on, the curator then turns
     * all three off, and the stored text must still load, still run and still return exactly
     * the same rows. A new filter naming those fields must also still SAVE while they are
     * off, because {@code POST /filters} validates through the same registry.
     *
     * <p>No migration rewrites stored filter text (§9.2), so the round-tripped {@code hql}
     * is asserted byte-for-byte as well.
     */
    @Test
    void aSavedFilterSurvivesItsProjectsCapabilitiesBeingTurnedOff() throws Exception {
        var ctx = newProject();
        enableEveryCapability(ctx);
        var sprintId = createSprint(ctx, "Sprint 7");
        var versionId = createVersion(ctx, "2.4.0");
        var committed = createIssue(ctx, "committed",
                "\"storyPoints\":8", "\"sprintId\":\"" + sprintId + "\"", fixVersionIdsJson(versionId));
        createIssue(ctx, "loose");

        var hql = "sprint = \"Sprint 7\" AND fixVersion = \"2.4.0\" AND storyPoints >= 5";
        var filterId = createFilter(ctx, "this sprint's release work", hql);
        assert found(ctx, storedHql(ctx, filterId)).equals(Set.of("committed"));

        // …a colleague flips every toggle off.
        disableEveryCapability(ctx);

        assert storedHql(ctx, filterId).equals(hql)
                : "the stored filter text was rewritten — §9.2 forbids editing a user's query";
        assert found(ctx, storedHql(ctx, filterId)).equals(Set.of("committed"))
                : "a saved filter stopped returning its rows because a capability was turned off";

        // …and a NEW filter naming the hidden fields still saves and still runs.
        var second = createFilter(ctx, "written while hidden", "sprint = \"Sprint 7\"");
        assert found(ctx, storedHql(ctx, second)).equals(Set.of("committed"));
        createFilter(ctx, "retired key, hidden field", "story_points >= 5");
    }

    // ==================================== §9.1 suggestions ARE capability-aware

    /**
     * §9.1 / §14.2 — the one thing a capability may change: {@code /schema}
     * <em>suggestions</em>. A field is offered when at least one VISIBLE project has the
     * capability on, and the SPRINT/VERSION picklists only draw values from those projects.
     *
     * <p>Granularity is asserted per capability rather than as an all-or-nothing block:
     * turning releases on must not conjure {@code sprint} or {@code storyPoints}. And the
     * "at least one visible project" rule is proved with a sibling — a workspace that holds
     * one Scrum project and nine Kanban ones still offers {@code sprint}, which is the whole
     * reason the check is per-workspace and not per-project.
     */
    @Test
    void schemaSuggestsACapabilityFieldOnlyWhileSomeVisibleProjectDeclaresIt() throws Exception {
        var ctx = newProject();
        disableEveryCapability(ctx);
        var sprintId = createSprint(ctx, "Sprint 7");
        createVersion(ctx, "2.4.0");
        createIssue(ctx, "committed", "\"storyPoints\":8", "\"sprintId\":\"" + sprintId + "\"");

        // ---- everything off: omitted from vocabulary, picklists empty ----
        var off = schema(ctx);
        var offNames = fieldNames(off);
        for (String hidden : List.of("sprint", "fixVersion", "affectsVersion", "storyPoints")) {
            assert !offNames.contains(hidden)
                    : "no visible project declares the capability, so " + hidden
                      + " must not be suggested: " + offNames;
        }
        assert picklist(off, "SPRINT").isEmpty()
                : "the SPRINT picklist must be empty when no visible project plans in sprints";
        assert picklist(off, "VERSION").isEmpty()
                : "the VERSION picklist must be empty when no visible project uses releases";
        // The ordinary vocabulary is untouched — this narrows four fields, not the language.
        assert offNames.containsAll(List.of("status", "type", "priority", "assignee", "label",
                "component", "text", "created", "due")) : offNames;

        // ---- one capability at a time: the three are independent ----
        setDelivery(ctx, "\"releases\":true");
        var releasesOnly = schema(ctx);
        var releasesNames = fieldNames(releasesOnly);
        assert releasesNames.containsAll(List.of("fixVersion", "affectsVersion")) : releasesNames;
        assert !releasesNames.contains("sprint") && !releasesNames.contains("storyPoints")
                : "turning releases on must not conjure the iteration/estimation fields: " + releasesNames;
        assert picklist(releasesOnly, "VERSION").equals(Set.of("2.4.0")) : picklist(releasesOnly, "VERSION");
        assert picklist(releasesOnly, "SPRINT").isEmpty();

        setDelivery(ctx, "\"estimation\":true");
        var estimationNames = fieldNames(schema(ctx));
        assert estimationNames.contains("storyPoints") : estimationNames;
        assert !estimationNames.contains("sprint") : estimationNames;

        // ---- "at least one VISIBLE project" — a sibling is enough ----
        var sibling = siblingProject(ctx);
        setDelivery(sibling, "\"board\":\"SCRUM\"");
        createSprint(sibling, "Sibling sprint");
        var withSibling = schema(ctx);
        assert fieldNames(withSibling).contains("sprint")
                : "one Scrum project among Kanban ones is enough to offer `sprint` (§9.1)";
        // …and the picklist carries the SCRUM project's sprint only: ctx is still Kanban, so
        // its own "Sprint 7" is not suggested — while still fully resolvable (below).
        assert picklist(withSibling, "SPRINT").equals(Set.of("Sibling sprint"))
                : "the SPRINT picklist must draw from capability-ON projects only, got "
                  + picklist(withSibling, "SPRINT");
        assert found(ctx, "sprint = \"Sprint 7\"").equals(Set.of("committed"))
                : "a value absent from the picklist must still RESOLVE — suggestions are not a contract";
    }

    /**
     * §9.1 — {@code /search/suggest?field=fixVersion|affectsVersion} is the overflow of the
     * capped VERSION picklist, so it is scoped identically. Both field names share one
     * catalog, so both are driven; and the endpoint keeps answering <strong>200 with an
     * empty list</strong> rather than 404 when nothing qualifies, because the field is still
     * a real field.
     */
    @Test
    void versionSuggestFollowsTheReleasesCapability() throws Exception {
        var ctx = newProject();
        disableEveryCapability(ctx);
        var versionId = createVersion(ctx, "2.4.0");

        for (String field : List.of("fixVersion", "affectsVersion")) {
            suggest(ctx, field, "2")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.field").value(field))
                    .andExpect(jsonPath("$.suggestions", org.hamcrest.Matchers.empty()));
        }

        setDelivery(ctx, "\"releases\":true");
        for (String field : List.of("fixVersion", "affectsVersion")) {
            suggest(ctx, field, "2")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.suggestions[*].value",
                            org.hamcrest.Matchers.hasItem("2.4.0")));
        }

        // Turning it back off narrows the suggestions again — and still resolves the value.
        setDelivery(ctx, "\"releases\":false");
        suggest(ctx, "fixVersion", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions", org.hamcrest.Matchers.empty()));
        var issue = createIssue(ctx, "shipped", fixVersionIdsJson(versionId));
        assert issue.get("fixVersions").size() == 1;
        assert found(ctx, "fixVersion = \"2.4.0\"").equals(Set.of("shipped"));
    }

    // ============================================================ narrowing only

    /**
     * <strong>The capability subsets may only ever NARROW suggestions — never widen or
     * narrow what a query can REACH.</strong> The S6 change threads project-id subsets into
     * {@link ResolutionContext}; the danger is that one of them leaks from the picklist into
     * a name→id resolution map, at which point a query would quietly return fewer rows on a
     * capability-off project (a silent wrong answer, the worst failure a search can give).
     *
     * <p>Proved twice: the identical query returns byte-identical row sets before and after
     * every capability is turned off, and a name owned by a capability-OFF sibling project
     * still resolves alongside the capability-ON one — the multi-project resolution rule
     * ("Cross-project" in {@code SprintSearchTest}) is untouched by capabilities.
     */
    @Test
    void capabilitiesNarrowSuggestionsOnlyAndNeverTheRowsAQueryReaches() throws Exception {
        var ctx = newProject();
        enableEveryCapability(ctx);
        var sprintId = createSprint(ctx, "Sprint 7");
        var versionId = createVersion(ctx, "2.4.0");
        var committed = createIssue(ctx, "committed",
                "\"storyPoints\":8", "\"sprintId\":\"" + sprintId + "\"", fixVersionIdsJson(versionId));
        createIssue(ctx, "small", "\"storyPoints\":1");
        createIssue(ctx, "loose");

        var queries = List.of(
                "sprint = \"Sprint 7\"",
                "sprint IS EMPTY",
                "sprint IS NOT EMPTY",
                "fixVersion = \"2.4.0\"",
                "fixVersion IS EMPTY",
                "storyPoints >= 5",
                "storyPoints IS EMPTY",
                "story_points >= 5",
                "sprint = \"Sprint 7\" OR storyPoints < 5",
                "storyPoints IS NOT EMPTY ORDER BY storyPoints DESC");

        var before = new java.util.LinkedHashMap<String, List<String>>();
        for (var q : queries) before.put(q, titles(ctx, q));

        disableEveryCapability(ctx);

        for (var q : queries) {
            assert titles(ctx, q).equals(before.get(q))
                    : "turning capabilities off changed the rows `" + q + "` returns: "
                      + before.get(q) + " → " + titles(ctx, q);
        }
        // Sanity: the fixture actually discriminates, so "identical" is not "empty == empty".
        assert before.get("sprint = \"Sprint 7\"").equals(List.of("committed")) : before;
        assert new java.util.HashSet<>(before.get("sprint IS EMPTY")).equals(Set.of("small", "loose"))
                : before;

        // …and NAME resolution still spans projects whose capability is off: the same
        // version name in a lean sibling resolves to both projects' issues.
        var sibling = siblingProject(ctx);
        disableEveryCapability(sibling);
        var siblingVersion = createVersion(sibling, "2.4.0");
        createIssue(sibling, "sibling-shipped", fixVersionIdsJson(siblingVersion));
        assert found(ctx, "fixVersion = \"2.4.0\"").equals(Set.of("committed", "sibling-shipped"))
                : "a capability-off project dropped out of NAME resolution — §9.1 is absolute";
    }

    // ================================================================== helpers

    private Set<String> found(Ctx ctx, String hql) throws Exception {
        return new java.util.HashSet<>(titles(ctx, hql));
    }

    private List<String> titles(Ctx ctx, String hql) throws Exception {
        var body = search(ctx, hql)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        for (var row : json.readTree(body).get("content")) {
            out.add(row.get("issue").get("title").asText());
        }
        return out;
    }

    /** Both spellings must be accepted AND agree with each other and with the expectation. */
    private void assertSameRows(Ctx ctx, String canonical, String alias, Set<String> expected)
            throws Exception {
        var viaCanonical = found(ctx, canonical);
        var viaAlias = found(ctx, alias);
        assert viaCanonical.equals(expected) : canonical + " → " + viaCanonical;
        assert viaAlias.equals(expected)
                : "`" + alias + "` did not return the same rows as `" + canonical + "`: "
                  + viaAlias + " vs " + viaCanonical;
    }

    private ResultActions search(Ctx ctx, String hql) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/search")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + json.writeValueAsString(hql) + ",\"size\":100}"));
    }

    private ResultActions suggest(Ctx ctx, String field, String q) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/suggest")
                .param("field", field)
                .param("q", q)
                .header("Authorization", "Bearer " + ctx.token()));
    }

    private JsonNode schema(Ctx ctx) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/search/schema")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private Set<String> schemaFieldNames(Ctx ctx) throws Exception {
        return fieldNames(schema(ctx));
    }

    private static Set<String> fieldNames(JsonNode schema) {
        var out = new LinkedHashSet<String>();
        for (var f : schema.get("fields")) out.add(f.get("name").asText());
        return out;
    }

    private static Set<String> picklist(JsonNode schema, String key) {
        var out = new LinkedHashSet<String>();
        for (var v : schema.get("values").get(key)) out.add(v.get("label").asText());
        return out;
    }

    // ---- saved filters ----

    private ResultActions postFilter(Ctx ctx, String name, String hql) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/filters")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":" + json.writeValueAsString(name)
                        + ",\"hql\":" + json.writeValueAsString(hql) + ",\"shared\":false}"));
    }

    private UUID createFilter(Ctx ctx, String name, String hql) throws Exception {
        var body = postFilter(ctx, name, hql)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    /** The filter's text as the server stored it — read back, never assumed. */
    private String storedHql(Ctx ctx, UUID filterId) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/filters/" + filterId)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("hql").asText();
    }

    // ---- fixtures ----

    /**
     * Bind a workspace-scoped NUMBER custom field with an explicit {@code key} to the ctx
     * project, through a field set — the same route the admin console takes, and the same
     * one {@code ResolutionContextFactory} reads. Returns the field's id (what the issue
     * {@code fields} payload is keyed by).
     */
    private UUID bindCustomField(Ctx ctx, String key, String name) throws Exception {
        var def = new FieldDef();
        def.setScopeWorkspaceId(ctx.wsId());
        def.setKey(key);
        def.setName(name + " " + Math.abs(UUID.randomUUID().hashCode()));
        def.setType(FieldType.NUMBER);
        def = fieldDefRepository.save(def);

        var set = new FieldSet();
        set.setName("Delivery search set " + Math.abs(UUID.randomUUID().hashCode()));
        set = fieldSetRepository.save(set);

        var item = new FieldSetItem();
        item.setSet(set);
        item.setField(def);
        item.setPosition((short) 0);
        item.setRequired(false);
        item.setShowOnCreate(true);
        fieldSetItemRepository.save(item);

        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        project.setFieldSet(set);
        projectRepository.save(project);
        return def.getId();
    }
}
