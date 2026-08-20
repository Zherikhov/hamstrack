package com.hamstrack.report;

import com.hamstrack.report.service.InsightsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaviour of {@code POST …/search/insights} (reports-proposal §2.6): the grouping itself, the
 * numbers, the refusals, and the two properties that are easy to get subtly wrong — the HQL
 * fragment a bar carries, and what {@code meta.basedOnIssues} counts.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InsightsApiTest extends InsightsTestBase {

    // ============================================================ the grouping

    @Test
    void groupsTheCurrentQueryByStatus() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "a", withStatus(ctx, "In Progress"));
        createIssue(ctx, "Task", "b", withStatus(ctx, "In Progress"));
        createIssue(ctx, "Task", "c");

        var body = insightsBody(ctx, "", "STATUS", null);

        assertThat(body.get("measure").asText()).isEqualTo("COUNT");
        assertThat(body.get("slice").asText()).isEqualTo("STATUS");
        assertThat(body.get("segment").isNull()).isTrue();
        assertThat(sliceNamed(body, "In Progress").get("count").asLong()).isEqualTo(2);
        // Ordered by the measure descending, so the biggest bar is first.
        assertThat(body.get("slices").get(0).get("bucket").get("label").asText()).isEqualTo("In Progress");
        // Unsegmented: the bars ARE the answer, so there is no breakdown to ship.
        assertThat(body.get("cells")).isEmpty();
        assertThat(body.get("segments")).isEmpty();
    }

    /**
     * The dataset is the query, not the workspace. This is the property that makes the panel a
     * replacement for a widget grid rather than another widget: it and the result list under it
     * are computed from one predicate and cannot disagree.
     */
    @Test
    void theDatasetIsTheQueryInTheBox() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "keep", priority(ctx, "Urgent"));
        createIssue(ctx, "Task", "drop", priority(ctx, "Low"));

        var body = insightsBody(ctx, "priority = \"Urgent\"", "PRIORITY", null);

        assertThat(body.get("slices")).hasSize(1);
        assertThat(body.get("slices").get(0).get("bucket").get("label").asText()).isEqualTo("Urgent");
        assertThat(body.get("meta").get("basedOnIssues").asLong()).isEqualTo(1);
    }

    @Test
    void segmentingProducesCellsAndALegendWhileTheBarsStayExact() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "a", withStatus(ctx, "In Progress"), priority(ctx, "Urgent"));
        createIssue(ctx, "Task", "b", withStatus(ctx, "In Progress"), priority(ctx, "Low"));
        createIssue(ctx, "Task", "c", withStatus(ctx, "In Progress"), priority(ctx, "Low"));

        var body = insightsBody(ctx, "", "STATUS", "PRIORITY");

        assertThat(body.get("segment").asText()).isEqualTo("PRIORITY");
        assertThat(sliceNamed(body, "In Progress").get("count").asLong()).isEqualTo(3);
        assertThat(body.get("segments")).hasSize(2);
        assertThat(body.get("cells")).hasSize(2);
        long fromCells = 0;
        for (var cell : body.get("cells")) fromCells += cell.get("count").asLong();
        assertThat(fromCells).isEqualTo(3);
    }

    /**
     * An issue with no assignee is a bar, not a hole. Every nullable dimension gets a
     * {@code null}-id bucket, and its fragment is the {@code IS EMPTY} that reproduces it.
     */
    @Test
    void theNoValueBucketIsARealBarWithAnIsEmptyFragment() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "assigned", assignee(ctx.owner().getId()));
        createIssue(ctx, "Task", "nobody");

        var body = insightsBody(ctx, "", "ASSIGNEE", null);

        var unassigned = sliceNamed(body, null);
        assertThat(unassigned).isNotNull();
        assertThat(unassigned.get("count").asLong()).isEqualTo(1);
        assertThat(unassigned.get("bucket").get("id").isNull()).isTrue();
        assertThat(unassigned.get("bucket").get("hql").asText()).isEqualTo("assignee IS EMPTY");
    }

    // ============================================================ multi-valued LABEL

    /**
     * The one dimension where an issue lands in several buckets. Two things are asserted because
     * both are easy to get wrong: the unlabelled issue still gets a bucket (a LEFT join, not an
     * inner one), and the bars deliberately sum to MORE than {@code basedOnIssues} — which is why
     * {@code sliceMultiValued} is on the response.
     */
    @Test
    void aLabelSliceCountsAnIssueOncePerLabelAndSaysSo() throws Exception {
        var ctx = newProject();
        var alpha = createLabel(ctx, "alpha");
        var beta = createLabel(ctx, "beta");
        createIssue(ctx, "Task", "both", labels(alpha, beta));
        createIssue(ctx, "Task", "bare");

        var body = insightsBody(ctx, "", "LABEL", null);

        assertThat(body.get("sliceMultiValued").asBoolean()).isTrue();
        assertThat(sliceNamed(body, "alpha").get("count").asLong()).isEqualTo(1);
        assertThat(sliceNamed(body, "beta").get("count").asLong()).isEqualTo(1);
        assertThat(sliceNamed(body, null).get("count").asLong()).isEqualTo(1);

        long summed = 0;
        for (var slice : body.get("slices")) summed += slice.get("count").asLong();
        assertThat(summed).isEqualTo(3);
        // …and the honest total is still 2. Never the sum of the series.
        assertThat(body.get("meta").get("basedOnIssues").asLong()).isEqualTo(2);
    }

    // ============================================================ the measure

    /**
     * Both numbers ride the same {@code GROUP BY}, so both are always returned; the measure only
     * decides the ranking. Pinned because the natural "optimisation" is to null out the one that
     * was not asked for, which costs a round trip on every toggle and buys nothing.
     */
    @Test
    void everyBucketCarriesBothNumbersWhateverTheMeasure() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "estimated", withStatus(ctx, "In Progress"), points("5"));
        createIssue(ctx, "Task", "not estimated", withStatus(ctx, "In Progress"));

        var counted = insightsBody(ctx, "", "STATUS", null);
        var bar = sliceNamed(counted, "In Progress");
        assertThat(bar.get("count").asLong()).isEqualTo(2);
        assertThat(bar.get("points").asDouble()).isEqualTo(5.0);
        // "We didn't estimate it" is not "it's free" — the zero is always reported with its count.
        assertThat(bar.get("unestimatedCount").asLong()).isEqualTo(1);
    }

    @Test
    void pointsRankTheBarsDifferentlyFromCount() throws Exception {
        var ctx = newProject();
        // Two small issues in To Do, one big one In Progress: count says To Do, points say the other.
        createIssue(ctx, "Task", "a", points("1"));
        createIssue(ctx, "Task", "b", points("1"));
        createIssue(ctx, "Task", "big", withStatus(ctx, "In Progress"), points("21"));

        var byCount = insightsBody(ctx, "", "STATUS", null);
        assertThat(byCount.get("slices").get(0).get("count").asLong()).isEqualTo(2);

        var byPoints = insights(ctx.wsId(), ctx.token(), "", "POINTS", "STATUS", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var ranked = json.readTree(byPoints);
        assertThat(ranked.get("measure").asText()).isEqualTo("POINTS");
        assertThat(ranked.get("slices").get(0).get("bucket").get("label").asText()).isEqualTo("In Progress");
    }

    /** {@code NONE} is a rendering choice: it is accepted, echoed, and ranks by count. */
    @Test
    void measureNoneIsAcceptedAndEchoed() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "a");

        insights(ctx.wsId(), ctx.token(), "", "none", "TYPE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measure").value("NONE"))
                .andExpect(jsonPath("$.slices[0].count").value(1));
    }

    // ============================================================ the click-to-narrow fragment

    /**
     * The panel is a navigation device as much as a chart (§2.6), so a bar carries the HQL that
     * reproduces it — and the fragment must actually run. Asserted by feeding it back to the
     * endpoint rather than by string comparison: a fragment that parses but selects something else
     * would pass the weaker test.
     */
    @Test
    void aBarsFragmentReproducesExactlyThatBar() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "a", withStatus(ctx, "In Progress"));
        createIssue(ctx, "Task", "b", withStatus(ctx, "In Progress"));
        createIssue(ctx, "Task", "c");

        var bar = sliceNamed(insightsBody(ctx, "", "STATUS", null), "In Progress");
        var fragment = bar.get("bucket").get("hql").asText();
        assertThat(fragment).isEqualTo("status = \"In Progress\"");

        var narrowed = insightsBody(ctx, fragment, "STATUS", null);
        assertThat(narrowed.get("meta").get("basedOnIssues").asLong())
                .as("clicking a bar must select the issues that bar counted, no more and no less")
                .isEqualTo(bar.get("count").asLong());
    }

    /**
     * A project bucket has no fragment, because HQL has no {@code project} field. Pinned rather
     * than left implicit: the SPA must treat {@code hql} as optional, and the day somebody adds a
     * project field this test is where they find out it is now expected to be populated.
     */
    @Test
    void aProjectBucketCarriesNoFragmentBecauseHqlHasNoProjectField() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "a");

        var body = insightsBody(ctx, "", "PROJECT", null);
        assertThat(body.get("slices")).hasSize(1);
        assertThat(body.get("slices").get(0).get("bucket").get("hql").isNull()).isTrue();
        assertThat(body.get("slices").get(0).get("bucket").get("label").asText()).isEqualTo("Proj");
    }

    /**
     * Two visible projects each own a component called "Billing" — two rows, one name — so the
     * name resolves to both ids and a fragment naming it would select both. Two bars, neither
     * clickable: a fragment wider than its own bar is the "these numbers don't match" failure,
     * and no fragment is better than a wrong one.
     *
     * <p><strong>Components, not statuses</strong>, and the difference is the point: statuses,
     * types and priorities come from a SHARED catalog, so two projects on the same workflow
     * reference the same row and produce one bar with a working fragment (which is correct).
     * Ambiguity only arises for the PROJECT-OWNED taxonomies — components and sprints — which
     * is exactly where HQL name resolution already returns several ids.
     */
    @Test
    void anAmbiguousNameProducesTwoBarsAndNoFragmentOnEither() throws Exception {
        var first = newProject();
        var second = addProject(workspaceOf(first), first.owner(), first.token());
        createIssue(first, "Task", "a", component(createComponent(first, "Billing")));
        createIssue(second, "Task", "b", component(createComponent(second, "Billing")));

        var body = insightsBody(first, "", "COMPONENT", null);

        int billingBars = 0;
        for (var slice : body.get("slices")) {
            if ("Billing".equals(slice.get("bucket").get("label").asText(null))) {
                billingBars++;
                assertThat(slice.get("bucket").get("hql").isNull())
                        .as("a fragment that selects both projects' components is wider than its bar")
                        .isTrue();
            }
        }
        assertThat(billingBars).isEqualTo(2);
    }

    /**
     * The other side of the same rule: a name owned by ONE project resolves to one id, so the
     * bar is clickable. Without this, the test above would pass on an implementation that never
     * emits a fragment for a component at all.
     */
    @Test
    void anUnambiguousProjectOwnedNameIsStillClickable() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "a", component(createComponent(ctx, "Billing")));

        var bar = sliceNamed(insightsBody(ctx, "", "COMPONENT", null), "Billing");
        assertThat(bar.get("bucket").get("hql").asText()).isEqualTo("component = \"Billing\"");
    }

    // ============================================================ refusals

    @Test
    void anUnknownSliceIs422NamingWhatIsAccepted() throws Exception {
        var ctx = newProject();
        insights(ctx.wsId(), ctx.token(), "", null, "EPIC", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("EPIC")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("STATUS")));
    }

    @Test
    void anUnknownMeasureIs422() throws Exception {
        var ctx = newProject();
        insights(ctx.wsId(), ctx.token(), "", "AVERAGE", "STATUS", null)
                .andExpect(status().isUnprocessableContent());
    }

    /**
     * The three enum-ish parameters are bounded at binding, and what a refusal quotes back is
     * bounded again in the service (HD-140 R6 round 2, security item 14).
     *
     * <p>They are the fields concatenated into their own 422, so unbounded they were an
     * amplifier: a 20 MB token binds (Jackson 3 allows 20 000 000 characters), {@code trim()} and
     * {@code toUpperCase()} copy it, the message builds another copy and a ~20 MB
     * {@code problem+json} body carries it back out — tens of megabytes of transient heap for one
     * request, on a path whose budget is per minute and whose body nothing upstream caps. Now it
     * is a 400 from bean validation before any of that is allocated. The 422 for a merely-unknown
     * token still names it, because a refusal that does not say what it rejected is the blank
     * banner this project already fixed once.
     */
    @Test
    void anOversizedSliceIsRefusedAtBindingRatherThanEchoed() throws Exception {
        var ctx = newProject();
        var huge = "X".repeat(5000);

        insights(ctx.wsId(), ctx.token(), "", null, huge, null)
                .andExpect(status().isBadRequest());
        insights(ctx.wsId(), ctx.token(), "", huge, "STATUS", null)
                .andExpect(status().isBadRequest());
        insights(ctx.wsId(), ctx.token(), "", null, "STATUS", huge)
                .andExpect(status().isBadRequest());

        // …and the 422 for a token that merely is not a dimension still quotes it back, bounded.
        insights(ctx.wsId(), ctx.token(), "", null, "X".repeat(31), null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("X".repeat(31))));
    }

    @Test
    void aSegmentEqualToTheSliceIsRefused() throws Exception {
        var ctx = newProject();
        insights(ctx.wsId(), ctx.token(), "", null, "STATUS", "STATUS")
                .andExpect(status().isUnprocessableContent());
    }

    /** A broken query fails the way it does on the search box itself — same pipeline, same 422. */
    @Test
    void aBrokenQueryFailsLikeItDoesOnSearch() throws Exception {
        var ctx = newProject();
        insights(ctx.wsId(), ctx.token(), "status =", null, "STATUS", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("PARSE_ERROR"));
        insights(ctx.wsId(), ctx.token(), "nosuchfield = 1", null, "STATUS", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"));
    }

    // ============================================================ conventions

    @Test
    void theResponseCarriesTheSharedMetaBlockAndItsOwnCaps() throws Exception {
        var ctx = newProject();
        createIssue(ctx, "Task", "a");

        insights(ctx.wsId(), ctx.token(), "", null, "STATUS", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.computedAt").exists())
                .andExpect(jsonPath("$.meta.basedOnIssues").value(1))
                // No issue row is ever materialised here, so the ROW cap cannot bite. What can
                // truncate is buckets, and that has its own flags.
                .andExpect(jsonPath("$.meta.truncated").value(false))
                .andExpect(jsonPath("$.meta.cap").exists())
                .andExpect(jsonPath("$.meta.firstIssueAt").doesNotExist())
                .andExpect(jsonPath("$.meta.unmatchedFilters").isEmpty())
                .andExpect(jsonPath("$.slicesTruncated").value(false))
                .andExpect(jsonPath("$.cellsTruncated").value(false))
                .andExpect(jsonPath("$.sliceCap").value(InsightsService.MAX_SLICES))
                .andExpect(jsonPath("$.cellCap").value(InsightsService.MAX_CELLS));
    }

    @Test
    void theResponseIsPrivatelyCacheableAndKeyedOnTheCredential() throws Exception {
        var ctx = newProject();
        insights(ctx.wsId(), ctx.token(), "", null, "STATUS", null)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string(HttpHeaders.VARY,
                        org.hamcrest.Matchers.containsString(HttpHeaders.AUTHORIZATION)));
    }

    @Test
    void anEmptyWorkspaceIsAnEmptyPanelNotAnError() throws Exception {
        var ctx = newProject();
        insights(ctx.wsId(), ctx.token(), "", null, "STATUS", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slices").isEmpty())
                .andExpect(jsonPath("$.meta.basedOnIssues").value(0));
    }
}
