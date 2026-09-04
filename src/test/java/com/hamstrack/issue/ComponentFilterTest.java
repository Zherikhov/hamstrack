package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The server-side {@code ?componentId=} filter on the board and the backlog (proposal
 * §3.6, §5.6) — and, above all, <strong>the regression the HD-31 reviewers asked for by
 * name</strong>.
 *
 * <p>{@code IssueRepository}'s predicate is
 * {@code (:componentId IS NULL OR i.component.id = :componentId)}. Dereferencing
 * {@code i.component.id} works because Hibernate reads the FK column on {@code issues}
 * itself, so an issue with a NULL {@code component_id} still satisfies the disjunction's
 * first branch when the filter is off. Rewrite that predicate as a join —
 * {@code JOIN i.component c WHERE (:componentId IS NULL OR c.id = :componentId)}, the
 * obvious-looking "cleanup" — and every component-less issue silently disappears from
 * every <em>unfiltered</em> board and backlog. Nothing would 500; the board would just
 * quietly stop showing most of the project.
 *
 * <p>So the first two tests here assert the boring case (filter OFF, component-less
 * issues present) on both shapes, and only then the filtering itself.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ComponentFilterTest extends ComponentTestBase {

    // ==================================================== the "filter off" regression

    @Test
    void theUnfilteredBoardStillContainsComponentLessIssues() throws Exception {
        var ctx = newProject();
        var billing = createComponent(ctx, "billing");
        createIssue(ctx, "with component", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "bare 1");
        createIssue(ctx, "bare 2");

        var all = board(ctx, null);
        assertThat(titles(all))
                .as("component-less issues must not be dropped from an unfiltered board, got "
                  + titles(all))
                .isEqualTo(Set.of("with component", "bare 1", "bare 2"));
        assertThat(all.get("totalAvailable").asLong())
                .as(() -> "the filtered COUNT query must agree with the list, got " + all.get("totalAvailable"))
                .isEqualTo(3);
        // The same is true of every other filter that leaves componentId unset.
        assertThat(titles(board(ctx, "?labelMatch=any")))
                .as("every other filter that leaves componentId unset keeps the component-less issues too")
                .hasSize(3);
    }

    @Test
    void theUnfilteredBacklogStillContainsComponentLessIssues() throws Exception {
        var ctx = newProject();
        var billing = createComponent(ctx, "billing");
        createIssue(ctx, "with component", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "bare 1");
        createIssue(ctx, "bare 2");

        var page = backlog(ctx, null);
        assertThat(pageTitles(page))
                .as(() -> "component-less issues must not be dropped from an unfiltered backlog, got "
                  + pageTitles(page))
                .isEqualTo(Set.of("with component", "bare 1", "bare 2"));
        // The paged query has its OWN countQuery — a join there would break paging
        // arithmetic even if the content query stayed correct.
        assertThat(page.get("totalElements").asLong())
                .as("the backlog's countQuery must agree with its content, got " + page)
                .isEqualTo(3);
    }

    // ==================================================== the filter itself

    @Test
    void componentIdFiltersBothShapesAndOnlyMatchesThatComponent() throws Exception {
        var ctx = newProject();
        var billing = createComponent(ctx, "billing");
        var ingest = createComponent(ctx, "ingest");
        createIssue(ctx, "b1", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "b2", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "i1", "\"componentId\":\"" + ingest + "\"");
        createIssue(ctx, "bare");

        assertThat(titles(board(ctx, "?componentId=" + billing)))
                .as("componentId returns exactly the issues carrying that component")
                .isEqualTo(Set.of("b1", "b2"));
        assertThat(board(ctx, "?componentId=" + billing).get("totalAvailable").asLong())
                .as("the filtered COUNT query must agree with the filtered list")
                .isEqualTo(2);
        assertThat(titles(board(ctx, "?componentId=" + ingest)))
                .as("a second component filters to its own issues, so the filter is not matching everything")
                .isEqualTo(Set.of("i1"));
        assertThat(pageTitles(backlog(ctx, "&componentId=" + billing)))
                .as("the backlog is the second shape of the same filter and must agree with the board")
                .isEqualTo(Set.of("b1", "b2"));
        assertThat(backlog(ctx, "&componentId=" + ingest).get("totalElements").asLong())
                .as("the backlog's own total must agree with its filtered page")
                .isEqualTo(1);
        // There is deliberately no "has no component" filter value — that is HQL's
        // `component IS EMPTY` (see ComponentSearchTest), not a board parameter.
    }

    @Test
    void theComponentFilterComposesWithTheOtherFilters() throws Exception {
        var ctx = newProject();
        var assignee = addMember(ctx, "MEMBER");
        var billing = createComponent(ctx, "billing");
        var label = createLabel(ctx, "urgent");
        var done = ctx.statusId(doneStatusName(ctx));

        createIssue(ctx, "billing + assignee", "\"componentId\":\"" + billing + "\"",
                "\"assigneeId\":\"" + assignee.user().getId() + "\"");
        createIssue(ctx, "billing + label", "\"componentId\":\"" + billing + "\"", labelIdsJson(label));
        createIssue(ctx, "billing + done", "\"componentId\":\"" + billing + "\"",
                "\"statusId\":\"" + done + "\"");
        createIssue(ctx, "assignee only", "\"assigneeId\":\"" + assignee.user().getId() + "\"");

        // ANDed with assignee…
        assertThat(titles(board(ctx, "?componentId=" + billing + "&assigneeId=" + assignee.user().getId())))
                .as("the component filter ANDs with assignee rather than replacing it")
                .isEqualTo(Set.of("billing + assignee"));
        // …with the label filter…
        assertThat(titles(board(ctx, "?componentId=" + billing + "&labelId=" + label)))
                .as("…and with the label filter")
                .isEqualTo(Set.of("billing + label"));
        // …with status…
        assertThat(titles(board(ctx, "?componentId=" + billing + "&statusId=" + done)))
                .as("…and with status")
                .isEqualTo(Set.of("billing + done"));
        // …and with the backlog's excludeDone.
        assertThat(pageTitles(backlog(ctx, "&componentId=" + billing + "&excludeDone=true")))
                .as("…and with the backlog's excludeDone")
                .isEqualTo(Set.of("billing + assignee", "billing + label"));
        // A combination that matches nothing is an empty 200, never an error.
        assertThat(board(ctx, "?componentId=" + billing + "&assigneeId=" + ctx.owner().getId())
                .get("issues"))
                .as("a combination that matches nothing is an empty 200, never an error")
                .isEmpty();
    }

    /** An archived component still filters — the issues carrying it are still real. */
    @Test
    void filteringByAnArchivedComponentStillReturnsItsIssues() throws Exception {
        var ctx = newProject();
        var stale = createComponent(ctx, "stale");
        createIssue(ctx, "carrier", "\"componentId\":\"" + stale + "\"");
        createIssue(ctx, "bare");
        archiveComponent(ctx, ctx.token(), stale).andExpect(status().isOk());

        assertThat(titles(board(ctx, "?componentId=" + stale)))
                .as("filtering by an ARCHIVED component still returns its issues: archiving hides a catalog row, it does not hide work")
                .isEqualTo(Set.of("carrier"));
        assertThat(titles(board(ctx, null)))
                .as("…and the unfiltered board still carries them alongside everything else")
                .isEqualTo(Set.of("carrier", "bare"));
    }

    // ==================================================== helpers

    /** The name of a DONE-category status in this project's workflow. */
    private String doneStatusName(Ctx ctx) {
        for (var s : ctx.config().get("statuses")) {
            if (s.get("category").asText().equals("DONE")) return s.get("name").asText();
        }
        throw new AssertionError("no DONE-category status in the default workflow");
    }
}
