package com.hamstrack.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.controller.BacklogController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-96 — <strong>a section fetched on its own is the same section the planning view
 * rendered</strong>, and it is not silently narrowed on the way.
 *
 * <p>The defect this pins down: the SPA refreshed one section through the ordinary issue
 * list asking for {@code size = sectionCap}, {@code Paging} clamped that to
 * {@link Paging#MAX_SIZE} and answered 200, so a section holding more than a page loaded
 * whole and came back cut — with no error anywhere, because {@code truncated} survived the
 * round trip and changed meaning ("more than the cap" on one surface, "more than the page
 * you received" on the other). The dedicated endpoint takes no {@code page} and no
 * {@code size} at all, which is why there is no clamp left to test around.
 *
 * <p><strong>Everything here is asserted between the two surfaces rather than against
 * literals.</strong> The numbers involved — the section cap, the bulk-move cap, the list
 * endpoint's page size — are operator knobs or shared constants, and a test that froze one
 * of them would go stale the first time an install retuned it, which is the exact failure
 * mode HD-96 is. The one number written down is the size of the fixture, and it is
 * expressed as {@code Paging.MAX_SIZE + 1} so it follows the constant it exists to exceed.
 *
 * <p>Truncation parity — where the two surfaces disagreed about what {@code truncated}
 * even means — needs a section ABOVE the cap and is therefore in {@code
 * BacklogSectionCapTest}, which shrinks the cap so it can be reached with a handful of rows.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class BacklogSectionParityTest extends SprintTestBase {

    /**
     * A section wider than one page of the endpoint the SPA used to refresh through. Sized
     * off the constant rather than written as a number, so it keeps meaning "more than the
     * list endpoint will hand over in one go" if that constant ever moves.
     */
    private static final int ABOVE_ONE_PAGE = Paging.MAX_SIZE + 1;

    @Test
    void aSectionWiderThanOnePageComesBackWholeFromBothMappings() throws Exception {
        var ctx = newProject();
        var created = new ArrayList<UUID>();
        for (int i = 0; i < ABOVE_ONE_PAGE; i++) {
            created.add(idOf(createIssue(ctx, "planning row " + i)));
        }

        var view = backlogView(ctx);
        assertThat(view.get("sectionCap").asInt())
                .as("this fixture only means anything while the section cap is above one page of "
                    + "the issue list — with a smaller cap the aggregate itself would truncate and "
                    + "there would be nothing for the section endpoint to be narrower than")
                .isGreaterThanOrEqualTo(ABOVE_ONE_PAGE);

        // ---- the BACKLOG mapping ----
        var backlogSection = sectionNode(ctx, "backlog", null);
        assertThat(backlogSection.get("issues").size())
                .as("the section endpoint narrowed a %d-row section — the HD-96 defect, which is "
                    + "invisible unless you count", ABOVE_ONE_PAGE)
                .isEqualTo(ABOVE_ONE_PAGE);
        assertSameSection(view, view.get("backlog"), backlogSection, "the backlog section");

        // ---- the SPRINT mapping, on the same rows ----
        var sprintId = createSprint(ctx, "Sprint 1");
        moveAllToSprint(ctx, sprintId, created, view.get("bulkMoveCap").asInt());

        var afterMove = backlogView(ctx);
        var sprintSection = sectionNode(ctx, sprintId.toString(), null);
        assertThat(sprintSection.get("issues").size()).isEqualTo(ABOVE_ONE_PAGE);
        assertSameSection(afterMove, sprintSection(afterMove, sprintId), sprintSection,
                "the sprint section");

        // …and the section they all left behind, which is the empty-section shape.
        var emptied = sectionNode(ctx, "backlog", null);
        assertThat(emptied.get("issues")).isEmpty();
        assertThat(emptied.get("truncated").asBoolean()).isFalse();
        assertThat(emptied.get("totalAvailable").asLong()).isZero();
        assertSameSection(afterMove, afterMove.get("backlog"), emptied, "the emptied backlog");
    }

    /**
     * The filters are where a refresh most easily starts answering a different question
     * than the render did — this defect's own shape, one layer up — so every section of a
     * filtered view is compared against its individual fetch under the same query string.
     */
    @Test
    void everySectionOfAFilteredViewMatchesItsIndividualFetch() throws Exception {
        var ctx = newProject();
        var labelId = createLabel(ctx, "planning");
        var sprintId = createSprint(ctx, "Sprint 1");

        createIssue(ctx, "backlog, labelled", "\"labelIds\":[\"" + labelId + "\"]");
        var backlogDone = createIssue(ctx, "backlog, done");
        createIssue(ctx, "backlog, plain", "\"storyPoints\":3");
        createIssue(ctx, "sprint, labelled",
                "\"labelIds\":[\"" + labelId + "\"]", "\"sprintId\":\"" + sprintId + "\"");
        var sprintDone = createIssue(ctx, "sprint, done", "\"sprintId\":\"" + sprintId + "\"",
                "\"storyPoints\":5");
        markDone(ctx, numberOf(backlogDone));
        markDone(ctx, numberOf(sprintDone));

        // includeDone is sent to the sprint mapping too, where it is an unknown parameter
        // and ignored: a sprint section always carries its DONE issues, on both surfaces.
        var queries = List.of(
                "", "?includeDone=true",
                "?labelId=" + labelId,
                "?labelId=" + labelId + "&labelMatch=all&includeDone=true",
                "?statusId=" + doneStatusId(ctx) + "&includeDone=true",
                "?assigneeId=" + ctx.owner().getId());

        for (var query : queries) {
            var view = backlogView(ctx, ctx.token(), query);
            assertSameSection(view, view.get("backlog"), sectionNode(ctx, "backlog", query),
                    "the backlog section of '" + query + "'");
            assertSameSection(view, sprintSection(view, sprintId),
                    sectionNode(ctx, sprintId.toString(), query),
                    "the sprint section of '" + query + "'");
        }
    }

    /**
     * A COMPLETED sprint's section still answers 200 (spec §5). It has dropped out of the
     * aggregate, which lists OPEN sprints, so the client's next full refetch removes it —
     * but a refresh already in flight must not turn into a 404 or a 409, because that would
     * be state changing a status code.
     */
    @Test
    void aCompletedSprintsSectionStillAnswers() throws Exception {
        var ctx = newProject();
        var sprintId = startedSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "delivered", "\"sprintId\":\"" + sprintId + "\"");
        markDone(ctx, numberOf(issue));
        completeToBacklog(ctx, ctx.token(), sprintId).andExpect(status().isOk());

        var view = backlogView(ctx);
        assertThat(view.get("sprints"))
                .as("a COMPLETED sprint is not a section of the planning view any more")
                .isEmpty();

        var section = sectionNode(ctx, sprintId.toString(), null);
        assertThat(section.get("sprint").get("state").asText()).isEqualTo("COMPLETED");
        assertThat(keysOf(section.get("issues"))).containsExactly(issue.get("key").asText());
    }

    /**
     * Tenancy, the project's top bug class: every failure is a 404 — non-existence and
     * non-membership are indistinguishable — and a sprint is resolved THROUGH the project,
     * so a sibling project's sprint id inside the same workspace answers nothing either.
     * There is no permission to miss, so no 403 is reachable and an ordinary member reads
     * both mappings: a refresh must not fail where the render that produced it succeeded.
     */
    @Test
    void everyTenancyFailureIsA404AndNoPermissionGatesTheRead() throws Exception {
        var ctx = newProject();
        var otherTenant = newProject();
        var sibling = siblingProject(ctx);
        var ourSprint = createSprint(ctx, "Ours");
        var siblingSprint = createSprint(sibling, "Sibling's");
        var theirSprint = createSprint(otherTenant, "Theirs");

        for (var ref : List.of("backlog", ourSprint.toString())) {
            // A non-member of the workspace — as opaque as an id that does not exist.
            sectionRequest(otherTenant.token(), ctx.wsId(), ctx.projectId(), ref)
                    .andExpect(status().isNotFound());
            // Unknown workspace, unknown project, and a project of ANOTHER workspace
            // addressed through ours.
            sectionRequest(ctx.token(), UUID.randomUUID(), ctx.projectId(), ref)
                    .andExpect(status().isNotFound());
            sectionRequest(ctx.token(), ctx.wsId(), UUID.randomUUID(), ref)
                    .andExpect(status().isNotFound());
            sectionRequest(ctx.token(), ctx.wsId(), otherTenant.projectId(), ref)
                    .andExpect(status().isNotFound());
        }

        // A sprint id that exists, but not in THIS project — the nested-path leak.
        getSection(ctx, ctx.token(), siblingSprint.toString(), null)
                .andExpect(status().isNotFound());
        getSection(ctx, ctx.token(), theirSprint.toString(), null)
                .andExpect(status().isNotFound());
        getSection(ctx, ctx.token(), UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound());

        // A malformed id never reaches the handler.
        getSection(ctx, ctx.token(), "not-a-uuid", null).andExpect(status().isBadRequest());

        // …and a plain member reads both mappings.
        var member = actorWith(ctx, "MEMBER", "MEMBER");
        getSection(ctx, member.token(), "backlog", null).andExpect(status().isOk());
        getSection(ctx, member.token(), ourSprint.toString(), null).andExpect(status().isOk());
    }

    /**
     * <strong>The section mappings accept exactly the aggregate's filters.</strong> If the
     * sets ever diverge, a refresh answers a different question than the render did — which
     * is HD-96 again, so it is worth a guard rather than a convention.
     *
     * <p>Detection, not prevention: the spec's stronger option is a shared
     * {@code @ModelAttribute} record, which makes drift impossible but edits the working
     * aggregate signature inside a defect fix, and is left as the owner's call (spec §12.3).
     * If that record ever lands, this test can go with it.
     */
    @Test
    void theSectionMappingsAcceptExactlyTheAggregatesFilterParameters() {
        var aggregate = requestParamNames("view");
        assertThat(aggregate)
                .as("no @RequestParam names were readable — the compiler dropped -parameters, "
                    + "which would also break Spring's own binding")
                .isNotEmpty()
                .doesNotContain("arg0");

        assertThat(requestParamNames("backlogSection"))
                .as("the backlog section mapping takes a different filter set than the view it "
                    + "refreshes a section of")
                .isEqualTo(aggregate);

        var sprintExpected = new LinkedHashSet<>(aggregate);
        sprintExpected.remove("includeDone");
        assertThat(requestParamNames("sprintSection"))
                .as("the sprint section mapping takes a different filter set than the view. "
                    + "includeDone is the ONE deliberate omission: it applies to the backlog "
                    + "section only, on both surfaces")
                .isEqualTo(sprintExpected);
    }

    // ============================================================ helpers

    /** Every {@code @RequestParam} name of one {@link BacklogController} handler, in order. */
    private static Set<String> requestParamNames(String methodName) {
        Method handler = null;
        for (var m : BacklogController.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) handler = m;
        }
        assertThat(handler).as("no handler named %s", methodName).isNotNull();
        var names = new LinkedHashSet<String>();
        for (var p : handler.getParameters()) {
            var annotation = p.getAnnotation(RequestParam.class);
            if (annotation == null) continue;
            names.add(annotation.value().isEmpty() ? p.getName() : annotation.value());
        }
        return names;
    }

    /** A section read against an arbitrary workspace/project pair, for the tenancy matrix. */
    private ResultActions sectionRequest(
            String token, UUID workspaceId, UUID projectId, String sectionRef) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspaceId + "/projects/" + projectId
                                   + "/backlog/sections/" + sectionRef)
                .header("Authorization", "Bearer " + token));
    }

    /** Bulk-move every id into a sprint, chunked at whatever this install allows. */
    private void moveAllToSprint(Ctx ctx, UUID sprintId, List<UUID> ids, int bulkMoveCap)
            throws Exception {
        for (int from = 0; from < ids.size(); from += bulkMoveCap) {
            var chunk = ids.subList(from, Math.min(from + bulkMoveCap, ids.size()));
            addIssuesToSprint(ctx, ctx.token(), sprintId, chunk.toArray(new UUID[0]))
                    .andExpect(status().isOk());
        }
    }

    /**
     * The whole HD-96 property in one place: the section a client refreshes is the section
     * the view rendered — the same rows in the same order, the same truncation verdict, the
     * same whole-section numbers, and the same caps in force.
     *
     * @param view              the aggregate response the section was rendered from
     * @param aggregateSection  that response's node for this section
     * @param section           the same section fetched on its own
     */
    static void assertSameSection(JsonNode view, JsonNode aggregateSection, JsonNode section,
                                  String what) {
        assertThat(section.get("issues"))
                .as("%s: the rows differ between the two surfaces. Field names are byte-identical "
                    + "on purpose, so a difference here is a difference in what was ASKED, "
                    + "fetched or ordered — not in how it was rendered", what)
                .isEqualTo(aggregateSection.get("issues"));
        assertThat(section.get("truncated"))
                .as("%s: 'truncated' must mean the same thing on both surfaces — this section "
                    + "holds more matching issues than sectionCap — and never 'more exist than "
                    + "the page you received'", what)
                .isEqualTo(aggregateSection.get("truncated"));
        assertThat(section.get("totalAvailable"))
                .as("%s: totalAvailable must come from the whole-section grouped query, never "
                    + "from the length of the list above it", what)
                .isEqualTo(aggregateSection.get("totalAvailable"));
        assertThat(section.get("stats"))
                .as("%s: the stats are the server's whole-section, filter-aware, cap-blind "
                    + "numbers on both surfaces", what)
                .isEqualTo(aggregateSection.get("stats"));

        var expectedSprint = aggregateSection.get("sprint");
        if (expectedSprint == null) {
            var actualSprint = section.get("sprint");
            assertThat(actualSprint == null || actualSprint.isNull())
                    .as("%s: the backlog section carries no sprint header — null IS the backlog "
                        + "throughout this domain", what)
                    .isTrue();
        } else {
            assertThat(section.get("sprint"))
                    .as("%s: the sprint header differs, so the section's own counters would "
                        + "disagree with its rows after a refresh", what)
                    .isEqualTo(expectedSprint);
        }

        assertThat(section.get("sectionCap"))
                .as("%s: sectionCap must ride on a refreshed section too — the client keeps no "
                    + "copy of it and must be able to adopt a retuned value", what)
                .isEqualTo(view.get("sectionCap"));
        assertThat(section.get("bulkMoveCap"))
                .as("%s: bulkMoveCap is an independent knob and 'move all to…' is driven by the "
                    + "rendered section, so a refreshed section must still carry it", what)
                .isEqualTo(view.get("bulkMoveCap"));
    }
}
