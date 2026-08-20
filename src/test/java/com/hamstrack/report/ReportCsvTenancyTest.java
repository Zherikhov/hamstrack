package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>An export is a new surface, so it is asked the tenancy questions again</strong>
 * (HD-141 R7).
 *
 * <p>Not redundant with {@code FlowReportTenancyTest} and friends, and the reason is the one this
 * slice is built around: the six {@code .csv} handlers are a <em>different set of handler
 * methods</em> from the six JSON ones. They delegate to the same services today — which is exactly
 * why a future "just read the rows directly, it's only a file" would be a one-method change with
 * nothing failing. Every path is therefore asked directly, and every path is looped over from
 * {@link #REPORTS}.
 *
 * <p><strong>That list is only as good as the thing that proves it complete</strong>, and round 2
 * caught this file claiming a guarantee it did not have: "a seventh export cannot be added without
 * either appearing here or failing the list's own size assertion". It could — a seventh handler
 * that nobody adds to {@code REPORTS} leaves the list at six, so the size assertion passed while
 * all five loops below silently stopped covering the surface. The completeness of {@code REPORTS}
 * is now derived from the real handler mapping, by the set of handlers producing {@code text/csv}
 * ({@code ReportCsvSurfaceTest}), which is the one property an export cannot hide.
 *
 * <p>The rule being pinned: <strong>non-existence and non-membership are both 404</strong>. A 403
 * on a foreign project would confirm the project exists, which in Cloud is one tenant learning
 * about another; a 400 would do the same. There is no case here where a caller who cannot see a
 * project learns anything but "no".
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ReportCsvTenancyTest extends ReportCsvTestBase {

    @Test
    void aForeignWorkspaceIsA404OnEveryExport() throws Exception {
        var mine = newProject();
        var theirs = newProject();

        for (var report : REPORTS) {
            getCsvAt(theirs.wsId().toString(), theirs.projectId().toString(), mine.token(),
                    report[0], report[1])
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * The nested-path case: the workspace IS the caller's, the project is not in it. The parent
     * being visible must not make the child resolvable — this is the shape that has bitten the
     * project before, because the membership check passes and only the second lookup catches it.
     */
    @Test
    void aProjectFromAnotherWorkspaceIsA404EvenThroughMyOwnWorkspaceId() throws Exception {
        var mine = newProject();
        var theirs = newProject();

        for (var report : REPORTS) {
            getCsvAt(mine.wsId().toString(), theirs.projectId().toString(), mine.token(),
                    report[0], report[1])
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void aWorkspaceAndAProjectThatDoNotExistAnswerTheSameWayAsOnesIAmNotIn() throws Exception {
        var mine = newProject();

        for (var report : REPORTS) {
            getCsvAt(UUID.randomUUID().toString(), UUID.randomUUID().toString(), mine.token(),
                    report[0], report[1])
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void anAnonymousCallerIsA401OnEveryExport() throws Exception {
        var ctx = newProject();

        for (var report : REPORTS) {
            mockMvc.perform(get(reportsBase(ctx) + "/" + report[0] + report[1]))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * A sprint id is the sprint reports' <em>subject</em>, so it resolves through the project and
     * 404s — the same rule as the JSON endpoints, restated here because a download is the natural
     * place for someone to "just look the sprint up by id".
     */
    @Test
    void aSprintFromAnotherProjectIsA404OnTheSprintExports() throws Exception {
        var mine = newProject();
        var theirs = newProject();
        var theirSprint = completedSprint(theirs, "S1", "2025-03-01T00:00:00Z",
                "2025-03-14T00:00:00Z", 1, 1, null);

        getCsv(mine, mine.token(), "sprint-burnup.csv", "?sprintId=" + theirSprint)
                .andExpect(status().isNotFound());
        getCsv(mine, mine.token(), "sprint-review.csv", "?sprintId=" + theirSprint)
                .andExpect(status().isNotFound());
    }

    /**
     * A workspace member who is not on the project still gets the report, exactly as on the JSON
     * endpoints (§4.2: there is no read permission in this product and reports add none). Asserted
     * so that "the export is stricter than the endpoint it exports" cannot creep in unnoticed
     * either.
     */
    @Test
    void aWorkspaceMemberOutsideTheProjectExportsItJustAsTheyReadIt() throws Exception {
        var ctx = newProject();
        var other = addMember(ctx, "MEMBER");

        for (var report : REPORTS) {
            getCsv(ctx, other.token(), report[0], report[1]).andExpect(status().isOk());
        }
    }
}
