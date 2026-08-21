package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;

import static com.hamstrack.issue.BacklogSectionParityTest.assertSameSection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-96 — <strong>a truncated section truncates the same way on both planning
 * surfaces</strong>.
 *
 * <p>This is the half of the parity property that the sibling suite cannot reach, because
 * it needs a section ABOVE {@code app.agile.section-max-issues} and the stock cap is 300.
 * The cap is shrunk here (the {@code SprintCapsTest} property set, verbatim, so the two
 * suites share a context) and truncation is exercised with a handful of rows.
 *
 * <p>It matters more than the row count suggests. {@code truncated} was the field that let
 * the defect stay silent: it survived the round trip while changing predicate — "more than
 * the cap" on the aggregate, "more than the page you received" on the refresh path — so the
 * two surfaces disagreed in a vocabulary that made them look like they agreed. The
 * assertions below therefore compare the flag, the whole-section total and every stat
 * BETWEEN the surfaces, and never against a number written here: the cap is an operator
 * knob, and a test that hardcoded it would be repeating the client's original mistake.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.agile.section-max-issues=2",
        "app.agile.max-open-sprints-per-project=2",
        "app.agile.max-issues-per-bulk-move=2",
        "app.agile.default-sprint-length-days=7"
})
@AutoConfigureMockMvc
class BacklogSectionCapTest extends SprintTestBase {

    @Test
    void truncationTotalsAndStatsAgreeAboveAndBelowTheCap() throws Exception {
        var ctx = newProject();
        var labelId = createLabel(ctx, "carried");
        var sprintId = createSprint(ctx, "Sprint 1");

        // A sprint ABOVE the cap, part of it labelled so a filtered section is above it too.
        for (int i = 0; i < 3; i++) {
            createIssue(ctx, "labelled sprint row " + i,
                    "\"sprintId\":\"" + sprintId + "\"",
                    "\"labelIds\":[\"" + labelId + "\"]",
                    "\"storyPoints\":2");
        }
        createIssue(ctx, "plain sprint row", "\"sprintId\":\"" + sprintId + "\"");

        // A backlog that sits EXACTLY at the cap by default and goes above it with
        // includeDone — the same rows under two filters, which is where the backlog's
        // derived (done-subtracted) stats could drift between the surfaces.
        createIssue(ctx, "backlog row 1", "\"storyPoints\":3");
        createIssue(ctx, "backlog row 2");
        markDone(ctx, numberOf(createIssue(ctx, "backlog row 3, done")));

        for (var query : List.of("", "?includeDone=true", "?labelId=" + labelId)) {
            var view = backlogView(ctx, ctx.token(), query);
            assertSameSection(view, view.get("backlog"), sectionNode(ctx, "backlog", query),
                    "the backlog section of '" + query + "'");
            assertSameSection(view, sprintSection(view, sprintId),
                    sectionNode(ctx, sprintId.toString(), query),
                    "the sprint section of '" + query + "'");
        }

        // Spelled out for the truncated case, so the parity above cannot be satisfied by
        // both surfaces being wrong in the same way.
        var section = sectionNode(ctx, sprintId.toString(), null);
        int cap = section.get("sectionCap").asInt();
        assertThat(section.get("issues").size())
                .as("a section above the cap returns exactly the cap")
                .isEqualTo(cap);
        assertThat(section.get("truncated").asBoolean())
                .as("'truncated' on a section fetch means the section holds more matching issues "
                    + "than sectionCap")
                .isTrue();
        assertThat(section.get("totalAvailable").asLong())
                .as("totalAvailable is the WHOLE section, from the grouped query — a truncated "
                    + "section reporting the rows it just returned is the bug this pins")
                .isGreaterThan(cap)
                .isEqualTo(section.get("stats").get("issueCount").asLong());
        assertThat(section.get("stats").get("points").asDouble())
                .as("the stats are cap-blind: three 2-point rows are 6 points even though only "
                    + "two rows came back")
                .isEqualTo(6.0d);

        // …and the section exactly AT the cap is not truncated: the cap + 1 probe is what
        // distinguishes "exactly the cap" from "one more than the cap".
        var atTheCap = sectionNode(ctx, "backlog", null);
        assertThat(atTheCap.get("issues").size()).isEqualTo(cap);
        assertThat(atTheCap.get("truncated").asBoolean())
                .as("a section holding exactly sectionCap is complete, not truncated")
                .isFalse();
        assertThat(atTheCap.get("totalAvailable").asLong()).isEqualTo(cap);
    }
}
