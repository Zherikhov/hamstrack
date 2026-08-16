package com.hamstrack.issue;

import com.hamstrack.workspace.entity.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The create-time auto-assign rule (proposal §5.1, §5.6) — the one piece of HD-31 that
 * silently changes data the user did not type, so each of its four guards gets its own
 * assertion:
 *
 * <ul>
 *   <li>component has {@code autoAssign} + a lead and the request carries no
 *       {@code assigneeId} → the issue is assigned to the lead;</li>
 *   <li>an explicit {@code assigneeId} always wins;</li>
 *   <li>{@code autoAssign} off → unassigned (a lead alone is just metadata);</li>
 *   <li>a lead who has left the workspace is skipped <strong>silently</strong> — the
 *       issue is created unassigned and the request still returns 201, because a stale
 *       lead must never fail issue creation;</li>
 *   <li>and it never fires on <strong>update</strong>: attaching a component later must
 *       not silently reassign someone's work.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ComponentAutoAssignTest extends ComponentTestBase {

    @Test
    void anIssueCreatedOnAnAutoAssignComponentGoesToTheLead() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        var component = createComponent(ctx, ctx.token(),
                "{\"name\":\"billing\",\"autoAssign\":true,\"leadId\":\"" + lead.user().getId() + "\"}");

        var issue = createIssue(ctx, "auto", "\"componentId\":\"" + component + "\"");
        assert issue.get("assignee").get("id").asText().equals(lead.user().getId().toString())
                : "the component lead must have been auto-assigned, got " + issue.get("assignee");
        // …and it is persisted, not just echoed back by the create response.
        assert assigneeName(getIssue(ctx, issue.get("number").asLong()))
                .equals(lead.user().getDisplayName());
        // Create-time values write no history (consistent with every other create-time
        // field), so nobody sees a phantom "assignee changed" entry.
        for (var row : issueHistory(ctx, issue.get("number").asLong()).get("content")) {
            assert !row.get("field").asText().equals("assignee")
                    : "auto-assign must not write history on create";
        }
    }

    @Test
    void anExplicitAssigneeAlwaysWins() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        var chosen = addMember(ctx, WorkspaceRole.MEMBER);
        var component = createComponent(ctx, ctx.token(),
                "{\"name\":\"billing\",\"autoAssign\":true,\"leadId\":\"" + lead.user().getId() + "\"}");

        var issue = createIssue(ctx, "explicit",
                "\"componentId\":\"" + component + "\"",
                "\"assigneeId\":\"" + chosen.user().getId() + "\"");
        assert issue.get("assignee").get("id").asText().equals(chosen.user().getId().toString())
                : "an explicit assigneeId must beat the component lead, got " + issue.get("assignee");
    }

    @Test
    void autoAssignOffLeavesTheIssueUnassignedEvenWithALead() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        var component = createComponent(ctx, ctx.token(),
                "{\"name\":\"billing\",\"leadId\":\"" + lead.user().getId() + "\"}");

        var issue = createIssue(ctx, "manual", "\"componentId\":\"" + component + "\"");
        assert assigneeName(issue) == null : "a lead without the switch is metadata only";
        // A component with neither lead nor switch is of course inert too.
        var plain = createComponent(ctx, "plain");
        assert assigneeName(createIssue(ctx, "plain issue", "\"componentId\":\"" + plain + "\"")) == null;
        // …and so is having no component at all.
        assert assigneeName(createIssue(ctx, "no component")) == null;
    }

    /**
     * The guard that must fail OPEN: the lead left the workspace, so they can no longer
     * receive work — but issue creation is not the place to discover that. The issue is
     * created unassigned, 201, no error.
     */
    @Test
    void aLeadWhoLeftTheWorkspaceIsSkippedSilentlyAndTheIssueIsStillCreated() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        var component = createComponent(ctx, ctx.token(),
                "{\"name\":\"billing\",\"autoAssign\":true,\"leadId\":\"" + lead.user().getId() + "\"}");
        removeFromWorkspace(ctx, lead.user());

        var issue = createIssue(ctx, "orphaned lead", "\"componentId\":\"" + component + "\"");
        assert assigneeName(issue) == null
                : "a departed lead must be skipped, not assigned: " + issue.get("assignee");
        assert "billing".equals(componentName(issue)) : "the component itself still attaches";
    }

    @Test
    void autoAssignNeverFiresOnUpdate() throws Exception {
        var ctx = newProject();
        var lead = addMember(ctx, WorkspaceRole.MEMBER);
        var other = addMember(ctx, WorkspaceRole.MEMBER);
        var component = createComponent(ctx, ctx.token(),
                "{\"name\":\"billing\",\"autoAssign\":true,\"leadId\":\"" + lead.user().getId() + "\"}");

        // (a) an unassigned issue that LATER gets the component stays unassigned.
        var issue = createIssue(ctx, "later");
        patchIssue(ctx, ctx.token(), issue.get("number").asLong(),
                "{\"componentId\":\"" + component + "\"}").andExpect(status().isOk());
        assert assigneeName(getIssue(ctx, issue.get("number").asLong())) == null
                : "attaching a component on update must not assign anyone";

        // (b) an issue that already has an assignee keeps THEM — the case that would be
        //     an actual data loss if auto-assign ever leaked into the update path.
        var assigned = createIssue(ctx, "assigned", "\"assigneeId\":\"" + other.user().getId() + "\"");
        patchIssue(ctx, ctx.token(), assigned.get("number").asLong(),
                "{\"componentId\":\"" + component + "\"}").andExpect(status().isOk());
        assert assigneeName(getIssue(ctx, assigned.get("number").asLong()))
                .equals(other.user().getDisplayName())
                : "changing a component must never silently reassign someone's work";

        // (c) not even when the switch is turned on afterwards on the component itself.
        var fresh = createIssue(ctx, "switch flipped later");
        var lazy = createComponent(ctx, ctx.token(),
                "{\"name\":\"lazy\",\"leadId\":\"" + lead.user().getId() + "\"}");
        patchIssue(ctx, ctx.token(), fresh.get("number").asLong(),
                "{\"componentId\":\"" + lazy + "\"}").andExpect(status().isOk());
        patchComponent(ctx, ctx.token(), lazy, "{\"autoAssign\":true}").andExpect(status().isOk());
        assert assigneeName(getIssue(ctx, fresh.get("number").asLong())) == null;
    }
}
