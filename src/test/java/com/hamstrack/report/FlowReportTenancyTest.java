package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The project's top bug class, applied to the reports surface (reports-proposal §4.1,
 * §12 "Tenancy"). A report is an <em>aggregate</em> of a tenant's data, which makes a
 * missed scope here quieter than almost anywhere else: no issue title, no key, no id
 * appears in the response — just a number that is silently somebody else's.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>a missing workspace, a missing project, a project in <em>another</em> workspace
 *       and a non-member all answer <strong>404</strong>. Never 403 — that would confirm
 *       the resource exists;</li>
 *   <li><strong>404 beats 400.</strong> A non-member sending a window the server would
 *       otherwise refuse still gets 404, because the tenancy check runs first. If
 *       validation ran first, the 400/404 difference would be a free existence oracle for
 *       any project id an outsider cared to guess;</li>
 *   <li>a sibling project in the SAME workspace contributes nothing. Workspace membership
 *       is not the scope of the numbers — the project is.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class FlowReportTenancyTest extends FlowReportTestBase {

    @Test
    void anonymousIsUnauthorized() throws Exception {
        var ctx = newProject();

        mockMvc.perform(get(reportsBase(ctx) + "/flow"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownWorkspaceOrProjectIsNotFound() throws Exception {
        var ctx = newProject();

        mockMvc.perform(get("/api/workspaces/" + UUID.randomUUID()
                        + "/projects/" + ctx.projectId() + "/reports/flow")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId()
                        + "/projects/" + UUID.randomUUID() + "/reports/flow")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantsProjectIsNotFoundBothWaysRound() throws Exception {
        var mine = newProject();
        var theirs = newProject();   // its own workspace, its own owner

        // Their project id, spliced into MY workspace path: the project exists, but not
        // inside the workspace I am a member of.
        mockMvc.perform(get("/api/workspaces/" + mine.wsId()
                        + "/projects/" + theirs.projectId() + "/reports/flow")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());

        // Their workspace, their project, my token: not a member.
        mockMvc.perform(get("/api/workspaces/" + theirs.wsId()
                        + "/projects/" + theirs.projectId() + "/reports/flow")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());
    }

    /**
     * The ordering rule. A 400 here would tell an outsider that the project exists —
     * cheaply, repeatably, and without ever authenticating as anyone who may see it.
     */
    @Test
    void aNonMemberGetsNotFoundEvenWhenTheWindowIsAlsoInvalid() throws Exception {
        var mine = newProject();
        var theirs = newProject();

        mockMvc.perform(get("/api/workspaces/" + theirs.wsId() + "/projects/" + theirs.projectId()
                        + "/reports/flow?from=2025-12-31&to=2025-01-01")
                        .header("Authorization", "Bearer " + mine.token()))
                .andExpect(status().isNotFound());

        // Same request from a member is the 400 it deserves — proving the 404 above was
        // the tenancy check and not the validation accidentally producing the right code.
        mockMvc.perform(get("/api/workspaces/" + theirs.wsId() + "/projects/" + theirs.projectId()
                        + "/reports/flow?from=2025-12-31&to=2025-01-01")
                        .header("Authorization", "Bearer " + theirs.token()))
                .andExpect(status().isBadRequest());
    }

    /** Same workspace, same owner, different project — and the numbers do not mix. */
    @Test
    void aSiblingProjectsIssuesAreNotCounted() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);

        issueCreatedAt(ctx, "mine", "2025-03-04T10:00:00Z");
        issueCreatedAt(sibling, "the sibling's", "2025-03-04T10:00:00Z");
        issueCreatedAt(sibling, "the sibling's, older", "2025-01-04T10:00:00Z");

        var report = flow(ctx, "?from=2025-03-03&to=2025-03-09&interval=DAY");

        assertThat(report.get("totals").get("created").asLong()).isEqualTo(1);
        assertThat(report.get("meta").get("basedOnIssues").asLong()).isEqualTo(1);
        assertThat(bucket(report, "2025-03-03").get("openAtEnd").asLong())
                .as("the sibling's January issue must not appear in this project's opening balance")
                .isZero();
    }

    /**
     * Reports are not permission-gated (§4.2): any workspace member who can reach the
     * project may open them, including one with no explicit project membership at all.
     * This is the decision the proposal argues at length, so it is pinned rather than left
     * to be re-litigated by a future "tighten this up" change: there are no read
     * permissions in this product, and every number here is derivable from the search API
     * the same member already holds.
     */
    @Test
    void anyWorkspaceMemberMayReadTheReport() throws Exception {
        var ctx = newProject();
        issueCreatedAt(ctx, "counted", "2025-03-04T10:00:00Z");

        var plainMember = actorWith(ctx, "MEMBER", null);

        var body = getFlow(ctx, plainMember.token(), "?from=2025-03-03&to=2025-03-09&interval=DAY")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("totals").get("created").asLong()).isEqualTo(1);
    }
}
