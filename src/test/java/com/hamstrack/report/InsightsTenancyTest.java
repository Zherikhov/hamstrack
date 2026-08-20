package com.hamstrack.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.report.dto.InsightsDimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tenant boundary of the first <strong>workspace-level</strong> report in this epic.
 *
 * <p>This file exists because the guard the rest of the epic relies on cannot see this endpoint.
 * {@code ReportQueryScopeTest} reflects over native {@code @Query} strings and asserts that each
 * one scopes the table it drives from; the insights aggregate is JPA Criteria, assembled at
 * runtime, so it has no string to inspect. The substitute is deliberately <em>stronger</em> rather
 * than equivalent: instead of asserting that a predicate is present, it asserts the thing the
 * predicate is for — that no bucket, no count and no total ever contains an issue the caller may
 * not see, including when the query text is written specifically to reach for one.
 *
 * <p>Reports are also the quietest place to lose a scope: a leaked issue in a list arrives with a
 * title somebody notices, while a leaked issue in a bucket is just a number that is too big.
 *
 * <h2>Enum-driven, because the substitute has to survive an addition (round 2)</h2>
 * Round 1 asserted the boundary on three of the eight {@link InsightsDimension} values, which is a
 * strong test of today's code and a weak guard on tomorrow's: a ninth dimension added to
 * {@code InsightsService.axis} would have got no tenancy assertion at all. Every axis today is a
 * join off the scoped root and is therefore structurally safe — but "safe by inspection" is
 * exactly the claim a guard exists to stop anybody making, and the guard this file substitutes for
 * (a reflective scan) is one that covers new statements automatically. So the core case is
 * {@code @EnumSource}: a new dimension is covered the moment its constant exists, as a slice and
 * as a segment, and a leak in <em>its</em> join fails this file rather than shipping.
 *
 * <p>The fixture is built once for the whole class ({@code PER_CLASS}) rather than per case,
 * because sixteen parameterised runs against a fresh pair of tenants would spend most of the
 * suite's time creating users. The rows are never mutated, only queried.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightsTenancyTest extends InsightsTestBase {

    /** One issue, in the caller's workspace, carrying exactly one value on every dimension. */
    private Ctx mine;

    @Test
    void aNonMemberGets404NotForbidden() throws Exception {
        var ctx = newProject();
        var outsider = login(user());

        insights(ctx.wsId(), outsider, "", null, "STATUS", null)
                .andExpect(status().isNotFound());
    }

    /** A workspace that does not exist and one the caller cannot see answer identically. */
    @Test
    void anUnknownWorkspaceIsTheSame404() throws Exception {
        var ctx = newProject();
        insights(UUID.randomUUID(), ctx.token(), "", null, "STATUS", null)
                .andExpect(status().isNotFound());
    }

    /**
     * The core assertion, for <strong>every</strong> dimension as the slice: another tenant's
     * issues are absent from the buckets AND from {@code basedOnIssues}. Both are checked because
     * they are computed by two different statements — losing the scope in one of them and not the
     * other is a real way for this to break, and it would show up as a chart whose bars do not add
     * up to its own total.
     *
     * <p>The bars sum to exactly one, not merely to at most one: the fixture's issue carries a
     * value on every dimension and <em>one</em> label, so it lands in exactly one bucket even on
     * the many-valued {@code LABEL} axis. Asserting equality also catches the opposite failure —
     * a scope that narrowed to nothing would pass an "at most" bound while showing the caller an
     * empty chart of their own data.
     */
    @ParameterizedTest
    @EnumSource(InsightsDimension.class)
    void everyDimensionIsScopedWhenItIsTheSlice(InsightsDimension slice) throws Exception {
        twoTenants();

        var body = insightsBody(mine, "", slice.name(), null);

        assertThat(body.get("meta").get("basedOnIssues").asLong())
                .as("basedOnIssues for slice %s", slice)
                .isEqualTo(1);
        assertThat(counted(body.get("slices")))
                .as("issues across all buckets of slice %s", slice)
                .isEqualTo(1);
    }

    /**
     * The same, for every dimension as the <strong>segment</strong>. Segmenting is a second
     * statement with a second pair of joins, so the boundary is asserted on that path rather than
     * assumed to be inherited from the slice.
     *
     * <p>The slice is held fixed at {@code STATUS} — except when {@code STATUS} is the segment,
     * where it is {@code TYPE}, because a diagonal is a 422 by design and skipping that case would
     * leave the one dimension most likely to be someone's default untested as a segment.
     */
    @ParameterizedTest
    @EnumSource(InsightsDimension.class)
    void everyDimensionIsScopedWhenItIsTheSegment(InsightsDimension segment) throws Exception {
        twoTenants();
        var slice = segment == InsightsDimension.STATUS
                ? InsightsDimension.TYPE
                : InsightsDimension.STATUS;

        var body = insightsBody(mine, "", slice.name(), segment.name());

        assertThat(body.get("meta").get("basedOnIssues").asLong())
                .as("basedOnIssues for %s x %s", slice, segment)
                .isEqualTo(1);
        assertThat(counted(body.get("cells")))
                .as("issues across all cells of %s x %s", slice, segment)
                .isEqualTo(1);
    }

    /**
     * <strong>No parsed token can widen the boundary.</strong> The scope is ANDed outermost and
     * the query is nested strictly inside it, so an {@code OR} — the classic attempt, because it
     * is the one operator that can turn a narrowing predicate into a widening one — cannot reach
     * out of the workspace.
     */
    @Test
    void anOrCannotReachOutOfTheWorkspace() throws Exception {
        var mine = newProject();
        createIssue(mine, "Task", "mine");

        var theirs = newProject();
        createIssue(theirs, "Task", "theirs");

        var body = insightsBody(mine, "text ~ \"mine\" OR text ~ \"theirs\"", "PROJECT", null);

        assertThat(body.get("slices")).hasSize(1);
        assertThat(body.get("meta").get("basedOnIssues").asLong()).isEqualTo(1);
    }

    /**
     * A same-named label in each workspace, so the segmented path is tested against the one thing
     * a name-keyed lookup could confuse. The label join is also the only one that reaches into
     * another table entirely.
     */
    @Test
    void aSameNamedLabelInAnotherWorkspaceIsADifferentBucket() throws Exception {
        var mine = newProject();
        var mineLabel = createLabel(mine, "shared-name");
        createIssue(mine, "Task", "mine", labels(mineLabel));

        var theirs = newProject();
        var theirLabel = createLabel(theirs, "shared-name");
        createIssue(theirs, "Task", "theirs", labels(theirLabel));
        createIssue(theirs, "Task", "theirs too", labels(theirLabel));

        var body = insightsBody(mine, "", "STATUS", "LABEL");

        assertThat(body.get("segments")).hasSize(1);
        assertThat(body.get("cells")).hasSize(1);
        assertThat(body.get("cells").get(0).get("count").asLong()).isEqualTo(1);
        assertThat(body.get("meta").get("basedOnIssues").asLong()).isEqualTo(1);
    }

    /**
     * <strong>The realistic Cloud shape: one person, two workspaces.</strong> Every other case in
     * this file uses a caller who belongs to neither the other tenant nor anything in it, so a bug
     * that computed the visible-project list for the wrong workspace — the caller's <em>other</em>
     * one — would answer correctly by accident. It passes today because the workspace id is bound
     * server-side from the resolved membership; this is what keeps it passing.
     *
     * <p>The second half is a control, and it is not decoration: without it, a membership row that
     * silently failed to take would make the first assertion true for the wrong reason.
     */
    @Test
    void aMemberOfBothWorkspacesSeesOnlyTheOneTheyAskedFor() throws Exception {
        var mine = newProject();
        createIssue(mine, "Task", "mine");

        var other = newProject();
        createIssue(other, "Task", "theirs one");
        createIssue(other, "Task", "theirs two");
        member(workspaceOf(other), mine.owner(), "MEMBER");

        var body = insightsBody(mine, "", "STATUS", null);
        assertThat(body.get("meta").get("basedOnIssues").asLong()).isEqualTo(1);
        assertThat(counted(body.get("slices"))).isEqualTo(1);

        // Control: the same caller, the same token, the other workspace — both of its issues.
        var elsewhere = json.readTree(insights(other.wsId(), mine.token(), "", null, "STATUS", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(elsewhere.get("meta").get("basedOnIssues").asLong()).isEqualTo(2);
    }

    /**
     * The other half of "visible". {@code SearchScope} scopes to the workspace's
     * <strong>non-archived</strong> projects, so an archived project's issues are in no bucket and
     * in no total — for a caller who is a full member of the workspace that owns them. Same
     * predicate as every case above, different clause of it.
     */
    @Test
    void anArchivedProjectsIssuesAreInNoBucketAndInNoTotal() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "live");

        var shelved = addProject(workspaceOf(ctx), ctx.owner(), ctx.token());
        createIssue(shelved, "Task", "archived one");
        createIssue(shelved, "Task", "archived two");
        var project = projectRepository.findById(shelved.projectId()).orElseThrow();
        project.setArchivedAt(Instant.now());
        projectRepository.save(project);

        var body = insightsBody(ctx, "", "PROJECT", null);

        assertThat(body.get("slices")).hasSize(1);
        assertThat(counted(body.get("slices"))).isEqualTo(1);
        assertThat(body.get("meta").get("basedOnIssues").asLong()).isEqualTo(1);
    }

    /**
     * A member of the workspace who is not a member of a project still sees it — visibility is
     * "the workspace's non-archived projects", exactly as {@code SearchScope} defines it and
     * exactly as the search results list under the panel behaves. Pinned so the panel and the list
     * can never drift apart: a panel that showed fewer issues than the list under it would be as
     * confusing as one that showed more.
     */
    @Test
    void thePanelSeesWhatTheSearchListSees() throws Exception {
        var owner = newProject();
        createIssue(owner, "Task", "a");

        var colleague = user();
        member(workspaceOf(owner), colleague, "MEMBER");
        var token = login(colleague);

        var body = json.readTree(insights(owner.wsId(), token, "", null, "STATUS", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(body.get("meta").get("basedOnIssues").asLong()).isEqualTo(1);
    }

    // ---- the shared two-tenant fixture ----

    /**
     * One issue here, two next door. The issue in the caller's workspace carries a value on every
     * dimension that has one ({@code SPRINT} has no fixture, so both tenants land in its
     * {@code null} bucket — which is the harder case, since a leak would arrive in the same bucket
     * as the caller's own issue rather than in a new one).
     *
     * <p>Built lazily and shared: the parameterised cases only read.
     */
    private void twoTenants() throws Exception {
        if (mine != null) {
            return;
        }
        var here = newProject();
        createIssue(here, "Task", "mine",
                withStatus(here, "In Progress"), priority(here, "Urgent"),
                assignee(here.owner().getId()), component(createComponent(here, "Billing")),
                labels(createLabel(here, "shared-name")), points("3"));

        var next = newProject();
        var theirLabel = createLabel(next, "shared-name");
        var theirComponent = createComponent(next, "Billing");
        for (var title : new String[]{"theirs one", "theirs two"}) {
            createIssue(next, "Task", title,
                    withStatus(next, "In Progress"), priority(next, "Urgent"),
                    assignee(next.owner().getId()), component(theirComponent),
                    labels(theirLabel), points("5"));
        }

        mine = here;
    }

    /** Total {@code count} across a series — the bars, or the stacks. */
    private static long counted(JsonNode series) {
        long total = 0;
        for (var entry : series) {
            total += entry.get("count").asLong();
        }
        return total;
    }
}
