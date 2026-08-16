package com.hamstrack.issue;

import com.hamstrack.workspace.entity.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.Set;

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
        assert titles(all).equals(Set.of("with component", "bare 1", "bare 2"))
                : "component-less issues must not be dropped from an unfiltered board, got "
                  + titles(all);
        assert all.get("totalAvailable").asLong() == 3
                : "the filtered COUNT query must agree with the list, got " + all.get("totalAvailable");
        // The same is true of every other filter that leaves componentId unset.
        assert titles(board(ctx, "?labelMatch=any")).size() == 3;
    }

    @Test
    void theUnfilteredBacklogStillContainsComponentLessIssues() throws Exception {
        var ctx = newProject();
        var billing = createComponent(ctx, "billing");
        createIssue(ctx, "with component", "\"componentId\":\"" + billing + "\"");
        createIssue(ctx, "bare 1");
        createIssue(ctx, "bare 2");

        var page = backlog(ctx, null);
        assert pageTitles(page).equals(Set.of("with component", "bare 1", "bare 2"))
                : "component-less issues must not be dropped from an unfiltered backlog, got "
                  + pageTitles(page);
        // The paged query has its OWN countQuery — a join there would break paging
        // arithmetic even if the content query stayed correct.
        assert page.get("totalElements").asLong() == 3
                : "the backlog's countQuery must agree with its content, got " + page;
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

        assert titles(board(ctx, "?componentId=" + billing)).equals(Set.of("b1", "b2"));
        assert board(ctx, "?componentId=" + billing).get("totalAvailable").asLong() == 2;
        assert titles(board(ctx, "?componentId=" + ingest)).equals(Set.of("i1"));
        assert pageTitles(backlog(ctx, "&componentId=" + billing)).equals(Set.of("b1", "b2"));
        assert backlog(ctx, "&componentId=" + ingest).get("totalElements").asLong() == 1;
        // There is deliberately no "has no component" filter value — that is HQL's
        // `component IS EMPTY` (see ComponentSearchTest), not a board parameter.
    }

    @Test
    void theComponentFilterComposesWithTheOtherFilters() throws Exception {
        var ctx = newProject();
        var assignee = addMember(ctx, WorkspaceRole.MEMBER);
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
        assert titles(board(ctx, "?componentId=" + billing + "&assigneeId=" + assignee.user().getId()))
                .equals(Set.of("billing + assignee"));
        // …with the label filter…
        assert titles(board(ctx, "?componentId=" + billing + "&labelId=" + label))
                .equals(Set.of("billing + label"));
        // …with status…
        assert titles(board(ctx, "?componentId=" + billing + "&statusId=" + done))
                .equals(Set.of("billing + done"));
        // …and with the backlog's excludeDone.
        assert pageTitles(backlog(ctx, "&componentId=" + billing + "&excludeDone=true"))
                .equals(Set.of("billing + assignee", "billing + label"));
        // A combination that matches nothing is an empty 200, never an error.
        assert board(ctx, "?componentId=" + billing + "&assigneeId=" + ctx.owner().getId())
                .get("issues").size() == 0;
    }

    /** An archived component still filters — the issues carrying it are still real. */
    @Test
    void filteringByAnArchivedComponentStillReturnsItsIssues() throws Exception {
        var ctx = newProject();
        var stale = createComponent(ctx, "stale");
        createIssue(ctx, "carrier", "\"componentId\":\"" + stale + "\"");
        createIssue(ctx, "bare");
        archiveComponent(ctx, ctx.token(), stale).andExpect(status().isOk());

        assert titles(board(ctx, "?componentId=" + stale)).equals(Set.of("carrier"));
        assert titles(board(ctx, null)).equals(Set.of("carrier", "bare"));
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
