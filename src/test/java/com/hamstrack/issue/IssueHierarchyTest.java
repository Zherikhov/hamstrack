package com.hamstrack.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.entity.WorkspaceRole;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration coverage for the issue-hierarchy backend (HD-18) — the acceptance
 * checklist in docs/design/issue-hierarchy-proposal.md §10: catalog/migration
 * levels, parent create/update validation (level, self, cycle, type-change
 * conflicts, stale version), the children endpoint, roll-up counters, delete
 * re-homing, and the multi-tenant 404-not-403 / 422-no-leak invariants.
 *
 * <p>Data is bootstrapped through the repository layer (users/ws/project, like
 * the other integration tests) and every issue/type/status id is read from the
 * public project-config endpoint — nothing about the catalog is hardcoded.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class IssueHierarchyTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired com.hamstrack.issue.repository.IssueRepository issueRepository;
    @Autowired com.hamstrack.issue.repository.FieldSetRepository fieldSetRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    // ============================================================ catalog / migration

    @Test
    void configExposesHierarchyLevelsIncludingSubtask() throws Exception {
        var ctx = newProject();
        var config = json.readTree(configBody(ctx));

        var types = config.get("issueTypes");
        // Sub-task must be present (seeded into the system-default "All types" set by V2)
        // and every type must carry its hierarchyLevel.
        assertLevel(types, "Epic", 2);
        assertLevel(types, "Story", 1);
        assertLevel(types, "Task", 1);
        assertLevel(types, "Bug", 1);
        assertLevel(types, "Sub-task", 0);
    }

    private void assertLevel(JsonNode types, String name, int level) {
        for (var t : types) {
            if (t.get("name").asText().equals(name)) {
                assert t.has("hierarchyLevel") : name + " missing hierarchyLevel";
                assert t.get("hierarchyLevel").asInt() == level
                        : name + " level " + t.get("hierarchyLevel").asInt() + " != " + level;
                return;
            }
        }
        throw new AssertionError("type not offered by fresh project: " + name);
    }

    // ============================================================ create

    @Test
    void createStoryUnderEpicSucceedsAndSetsParentKey() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "The epic");

        create(ctx, body(ctx, "Story", "child story", parentField(epic.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(epic.id()))
                .andExpect(jsonPath("$.parentKey").value(epic.key()))
                .andExpect(jsonPath("$.parentTitle").value("The epic"))
                .andExpect(jsonPath("$.parentTypeId").value(ctx.typeId("Epic").toString()));
    }

    @Test
    void createStoryUnderStoryRejectedByLevel() throws Exception {
        var ctx = newProject();
        var parent = createIssue(ctx, "Story", "parent story");

        create(ctx, body(ctx, "Story", "child story", parentField(parent.id())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("can't be a child")));
    }

    @Test
    void createUnderSubtaskRejectedByLevel() throws Exception {
        var ctx = newProject();
        var subtask = createIssue(ctx, "Sub-task", "leaf subtask");

        // Nothing may parent a Sub-task (level 0). Even another Sub-task (0 == 0).
        create(ctx, body(ctx, "Sub-task", "another subtask", parentField(subtask.id())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("can't be a child")));
    }

    @Test
    void createSubtaskUnderEpicRejectedByAdjacency() throws Exception {
        // Revision 2: Epic(2) → Sub-task(0) is a gap of 2 — was legal under the old
        // strictly-greater rule, now forbidden by strict adjacency.
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "the epic");

        create(ctx, body(ctx, "Sub-task", "orphan subtask", parentField(epic.id())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("exactly one level above")));
    }

    @Test
    void createSubtaskUnderStorySucceeds() throws Exception {
        // Adjacent: Story(1) → Sub-task(0).
        var ctx = newProject();
        var story = createIssue(ctx, "Story", "the story");

        create(ctx, body(ctx, "Sub-task", "the subtask", parentField(story.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(story.id()));
    }

    @Test
    void createWithParentFromAnotherProjectRejectedAsUnknownParent() throws Exception {
        var ctx = newProject();
        // A second project in the SAME workspace, owned by the same user.
        var other = newProjectInWorkspace(ctx, ctx.owner);
        var foreignEpic = createIssue(other, "Epic", "foreign epic");

        create(ctx, body(ctx, "Story", "story", parentField(foreignEpic.id())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown parent issue"));
    }

    @Test
    void createWithParentFromAnotherWorkspaceIsNotLeaked() throws Exception {
        var ctx = newProject();
        // A separate workspace/project the caller is also a member of — the parent
        // id is real and resolvable to the caller, just not in THIS project. Must
        // be a 422 "unknown parent", never a 403/404 existence leak.
        var otherWs = newProject();
        var foreignEpic = createIssue(otherWs, "Epic", "foreign-ws epic");

        create(ctx, body(ctx, "Story", "story", parentField(foreignEpic.id())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Unknown parent issue"));
    }

    // ============================================================ update

    @Test
    void setLegalParentViaPatchWritesHistory() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var story = createIssue(ctx, "Story", "story");

        patch(ctx, story.number(), "{\"parentId\":\"" + epic.id() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(epic.id()))
                .andExpect(jsonPath("$.parentKey").value(epic.key()));

        // A "parent" history entry (old null → new epic key) was recorded.
        history(ctx, story.number())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.field == 'parent')].newValue", hasItem(epic.key())));
    }

    @Test
    void clearParentDetachesAndWritesHistory() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var story = createIssue(ctx, "Story", "story", parentField(epic.id()));

        patch(ctx, story.number(), "{\"clearParent\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").doesNotExist());

        history(ctx, story.number())
                .andExpect(jsonPath("$.content[?(@.field == 'parent')].oldValue", hasItem(epic.key())))
                .andExpect(jsonPath("$.content[?(@.field == 'parent')].newValue", hasItem(nullValue())));
    }

    @Test
    void selfParentRejected() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");

        patch(ctx, epic.number(), "{\"parentId\":\"" + epic.id() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("An issue can't be its own parent"));
    }

    @Test
    void directCycleRejected() throws Exception {
        var ctx = newProject();
        // Legal edge first: Story B under Epic A (level 1 under 2).
        var a = createIssue(ctx, "Epic", "A");
        var b = createIssue(ctx, "Story", "B");
        patch(ctx, b.number(), "{\"parentId\":\"" + a.id() + "\"}")
                .andExpect(status().isOk());

        // Now try to make the ancestor A a child of its own descendant B — a
        // direct cycle. It is rejected 422. (Given the strictly-greater level
        // rule, an ancestor→descendant reparent also trips the level guard, which
        // fires first here; the acyclic invariant is what the assertion pins —
        // the two guards are belt-and-braces and both reject.)
        patch(ctx, a.number(), "{\"parentId\":\"" + b.id() + "\"}")
                .andExpect(status().isUnprocessableContent());

        // A's parent is unchanged (still root); the cycle was not created.
        getIssue(ctx, a.number()).andExpect(jsonPath("$.parentId").doesNotExist());
    }

    @Test
    void typeChangeThatBreaksCurrentParentRejectedAndParentRetained() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var story = createIssue(ctx, "Story", "story", parentField(epic.id()));

        // Promote the Story (level 1) to an Epic (level 2): its Epic parent (level 2)
        // is no longer exactly one tier above → rejected, parent NOT detached.
        patch(ctx, story.number(), "{\"typeId\":\"" + ctx.typeId("Epic") + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("conflicts with its parent")));

        // The parent survives (no silent detach) and the type is unchanged.
        getIssue(ctx,story.number())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(epic.id()))
                .andExpect(jsonPath("$.type.name").value("Story"));
    }

    @Test
    void typeChangeThatBreaksExistingChildrenRejected() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        createIssue(ctx, "Story", "child story", parentField(epic.id()));

        // Demote the parent Epic (level 2) to a Story (level 1): its Story child
        // (level 1) is now not strictly-lower → must be rejected.
        patch(ctx, epic.number(), "{\"typeId\":\"" + ctx.typeId("Story") + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("child relationship")));

        getIssue(ctx,epic.number()).andExpect(jsonPath("$.type.name").value("Epic"));
    }

    @Test
    void updateSetSubtaskParentToEpicRejectedByAdjacency() throws Exception {
        // Revision 2: setting a Sub-task's parent to an Epic (gap 2) → 422.
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var subtask = createIssue(ctx, "Sub-task", "loose subtask");

        patch(ctx, subtask.number(), "{\"parentId\":\"" + epic.id() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("exactly one level above")));
    }

    @Test
    void promoteStoryParentingSubtaskToEpicRejectedByAdjacency() throws Exception {
        // §4.4.4 under adjacency (stricter than the old rule): a Story(1) parents a
        // Sub-task(0) — legal. Promoting the Story to an Epic(2) would leave an
        // Epic → Sub-task gap-2 edge, which the old strictly-greater rule permitted
        // (2 > 0) but adjacency forbids. Must now 422.
        var ctx = newProject();
        var story = createIssue(ctx, "Story", "story");
        createIssue(ctx, "Sub-task", "child", parentField(story.id()));

        patch(ctx, story.number(), "{\"typeId\":\"" + ctx.typeId("Epic") + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("child relationship")));

        getIssue(ctx, story.number()).andExpect(jsonPath("$.type.name").value("Story"));
    }

    @Test
    void changeTypeAcrossTaskTierKeepsEpicParent() throws Exception {
        // Story→Bug (both level 1) with an Epic parent stays adjacent → 201.
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var story = createIssue(ctx, "Story", "story", parentField(epic.id()));

        patch(ctx, story.number(), "{\"typeId\":\"" + ctx.typeId("Bug") + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type.name").value("Bug"))
                .andExpect(jsonPath("$.parentId").value(epic.id()));
    }

    @Test
    void staleVersionOnParentChangeConflicts() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var story = createIssue(ctx, "Story", "story");

        // version 0 is current; sending a stale version must 409 before mutating.
        patch(ctx, story.number(),
                "{\"parentId\":\"" + epic.id() + "\",\"version\":999}")
                .andExpect(status().isConflict());

        // Correct version succeeds.
        patch(ctx, story.number(),
                "{\"parentId\":\"" + epic.id() + "\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(epic.id()));
    }

    // ============================================================ children / roll-up

    @Test
    void childrenEndpointReturnsDirectChildrenInBoardOrder() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var first = createIssue(ctx, "Story", "first child", parentField(epic.id()));
        var second = createIssue(ctx, "Task", "second child", parentField(epic.id()));
        // A grandchild — must NOT appear among the epic's direct children.
        createIssue(ctx, "Sub-task", "grandchild", parentField(first.id()));

        children(ctx, epic.number())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // board order = position ASC (creation order here)
                .andExpect(jsonPath("$[0].id").value(first.id()))
                .andExpect(jsonPath("$[1].id").value(second.id()));
    }

    @Test
    void childrenEndpoint404ForNonMember() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        // An outsider — member of no workspace here — must get 404, not 403.
        var outsider = login(user());

        mockMvc.perform(get(issuesBase(ctx) + "/" + epic.number() + "/children")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
    }

    @Test
    void childrenEndpoint404ForUnknownIssue() throws Exception {
        var ctx = newProject();
        mockMvc.perform(get(issuesBase(ctx) + "/999999/children")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNotFound());
    }

    @Test
    void rollupCountsDirectDoneChildrenOnly() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var c1 = createIssue(ctx, "Story", "c1", parentField(epic.id()));
        createIssue(ctx, "Story", "c2", parentField(epic.id()));
        // A grandchild in DONE must NOT count toward the epic (direct children only).
        var grandchild = createIssue(ctx, "Sub-task", "gc", parentField(c1.id()));
        moveToDone(ctx, grandchild.number());

        // 2 direct children, none done yet.
        getIssue(ctx,epic.number())
                .andExpect(jsonPath("$.childCount").value(2))
                .andExpect(jsonPath("$.doneChildCount").value(0));

        // Move a direct child to a DONE-category status → doneChildCount ticks up.
        moveToDone(ctx, c1.number());
        getIssue(ctx,epic.number())
                .andExpect(jsonPath("$.childCount").value(2))
                .andExpect(jsonPath("$.doneChildCount").value(1));
    }

    @Test
    void issueWithoutChildrenHasZeroRollupAndNullParent() throws Exception {
        var ctx = newProject();
        var lone = createIssue(ctx, "Task", "lonely");

        getIssue(ctx,lone.number())
                .andExpect(jsonPath("$.childCount").value(0))
                .andExpect(jsonPath("$.doneChildCount").value(0))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andExpect(jsonPath("$.parentKey").doesNotExist());

        children(ctx, lone.number())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ============================================================ delete

    @Test
    void deletingParentOrphansChildrenToRootWithHistory() throws Exception {
        // Revision 2 §4.7-superseded: children orphan to root (parent = null), NOT
        // to the grandparent (which would break strict adjacency — gap 2).
        var ctx = newProject();
        // grandparent (Epic) → parent (Story) → child (Sub-task)
        var grandparent = createIssue(ctx, "Epic", "grandparent");
        var parent = createIssue(ctx, "Story", "parent", parentField(grandparent.id()));
        var child = createIssue(ctx, "Sub-task", "child", parentField(parent.id()));

        // Delete the middle Story: the Sub-task orphans to root (no FK error).
        mockMvc.perform(delete(issuesBase(ctx) + "/" + parent.number())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNoContent());

        getIssue(ctx,child.number())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").doesNotExist());

        // A "parent" history entry (old parent key → null) on the child.
        history(ctx, child.number())
                .andExpect(jsonPath("$.content[?(@.field == 'parent')].oldValue", hasItem(parent.key())))
                .andExpect(jsonPath("$.content[?(@.field == 'parent')].newValue", hasItem(nullValue())));

        // The grandparent does NOT adopt the orphaned child.
        children(ctx, grandparent.number())
                .andExpect(jsonPath("$[?(@.id == '" + child.id() + "')]").doesNotExist());
    }

    @Test
    void deletingRootParentOrphansChildrenToNull() throws Exception {
        var ctx = newProject();
        var root = createIssue(ctx, "Epic", "root");
        var child = createIssue(ctx, "Story", "child", parentField(root.id()));

        mockMvc.perform(delete(issuesBase(ctx) + "/" + root.number())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNoContent());

        // Deleted issue had no parent → child orphans to null, survives, no FK error.
        getIssue(ctx,child.number())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").doesNotExist());
    }

    @Test
    void deletingLeafWorksUnchanged() throws Exception {
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var leaf = createIssue(ctx, "Story", "leaf", parentField(epic.id()));

        mockMvc.perform(delete(issuesBase(ctx) + "/" + leaf.number())
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNoContent());

        getIssue(ctx,leaf.number()).andExpect(status().isNotFound());
        // Parent unaffected, now childless.
        getIssue(ctx,epic.number()).andExpect(jsonPath("$.childCount").value(0));
    }

    // ============================================================ atomic create (bug #2)

    @Test
    void createChildIsAtomicAndReturns201() throws Exception {
        // A child whose parent itself has a parent — the deepest assembly path.
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");
        var story = createIssue(ctx, "Story", "story", parentField(epic.id()));

        long before = issueRepository.count();
        create(ctx, body(ctx, "Sub-task", "the subtask", parentField(story.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(story.id()))
                .andExpect(jsonPath("$.parentKey").value(story.key()))
                .andExpect(jsonPath("$.parentTitle").value("story"))
                .andExpect(jsonPath("$.parentTypeId").value(ctx.typeId("Story").toString()))
                .andExpect(jsonPath("$.childCount").value(0))
                .andExpect(jsonPath("$.doneChildCount").value(0));
        assert issueRepository.count() == before + 1 : "exactly one issue created";
    }

    @Test
    void createChildWithCustomFieldValueSerializesThroughJacksonBoundary() throws Exception {
        // The has-parent + JSONB-custom-field combination R2.5 flags as the most
        // likely to hit the Jackson 2/3 boundary on the create response path. With
        // the response now serialized INSIDE the transaction, this must 201 and the
        // MVC-emitted body must parse.
        var ctx = newProject();
        bindEngineeringFields(ctx);
        var cfg = json.readTree(configBody(ctx));
        var storyPointsFieldId = fieldId(cfg, "story_points");

        var story = createIssue(ctx, "Story", "story");
        long before = issueRepository.count();
        var resp = create(ctx, body(ctx, "Sub-task", "sub with field",
                        parentField(story.id()), "\"fields\":{\"" + storyPointsFieldId + "\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(story.id()))
                .andReturn().getResponse().getContentAsString();
        // The emitted body parses and carries the field value (JSONB round-trip).
        var node = json.readTree(resp);
        assert node.get("fields").get(0).get("value").asInt() == 5 : "field value round-tripped";
        assert issueRepository.count() == before + 1 : "exactly one issue created";
    }

    @Test
    void rejectedCreateLeavesNoRowThenRetrySucceedsOnce() throws Exception {
        // Any 4xx (here: illegal adjacency) must persist zero rows; a valid retry
        // then creates exactly one.
        var ctx = newProject();
        var epic = createIssue(ctx, "Epic", "epic");

        long before = issueRepository.count();
        // Epic → Sub-task is now illegal (gap 2) → 422, nothing persisted.
        create(ctx, body(ctx, "Sub-task", "doomed", parentField(epic.id())))
                .andExpect(status().isUnprocessableContent());
        assert issueRepository.count() == before : "rejected create left no orphan row";

        // Valid retry: a Story under the Epic → exactly one new issue.
        create(ctx, body(ctx, "Story", "valid retry", parentField(epic.id())))
                .andExpect(status().isCreated());
        assert issueRepository.count() == before + 1 : "retry created exactly one";
    }

    @Test
    void parentlessCreateStillReturns201Body() throws Exception {
        // Ensure the atomic-serialization path didn't regress the common case.
        var ctx = newProject();
        long before = issueRepository.count();
        create(ctx, body(ctx, "Task", "plain task"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("plain task"))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andExpect(jsonPath("$.childCount").value(0));
        assert issueRepository.count() == before + 1;
    }

    private void bindEngineeringFields(Ctx ctx) {
        var engSet = fieldSetRepository
                .findByScopeWorkspaceIdIsNullAndScopeProjectIdIsNullAndName("Engineering fields")
                .orElseThrow();
        var project = projectRepository.findById(ctx.projectId).orElseThrow();
        project.setFieldSet(engSet);
        projectRepository.save(project);
    }

    private String fieldId(JsonNode config, String key) {
        for (var f : config.get("fields")) {
            if (f.get("key").asText().equals(key)) return f.get("id").asText();
        }
        throw new AssertionError("field not in config: " + key);
    }

    // ============================================================ helpers

    /** One workspace + project + logged-in MANAGER, plus cached config type/status ids. */
    private record Ctx(UUID wsId, UUID projectId, String projectKey, User owner, String token, JsonNode config) {
        String issuesBase() {
            return "/api/workspaces/" + wsId + "/projects/" + projectId + "/issues";
        }
        UUID typeId(String name) {
            for (var t : config.get("issueTypes")) {
                if (t.get("name").asText().equals(name)) return UUID.fromString(t.get("id").asText());
            }
            throw new AssertionError("type not offered: " + name);
        }
        UUID statusId(String name) {
            for (var s : config.get("statuses")) {
                if (s.get("name").asText().equals(name)) return UUID.fromString(s.get("id").asText());
            }
            throw new AssertionError("status not in workflow: " + name);
        }
        /** first status whose category is DONE */
        UUID doneStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("DONE")) return UUID.fromString(s.get("id").asText());
            }
            throw new AssertionError("no DONE-category status in workflow");
        }
        UUID todoStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("TODO")) return UUID.fromString(s.get("id").asText());
            }
            throw new AssertionError("no TODO-category status in workflow");
        }
    }

    /** A created issue's identity, parsed from the create response. */
    private record IssueRef(String id, long number, String key) {}

    private String issuesBase(Ctx ctx) {
        return ctx.issuesBase();
    }

    private Ctx newProject() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), project.getKey(), owner, token, json.readTree(body));
    }

    /** A second project in the same workspace as {@code base}, for the same owner. */
    private Ctx newProjectInWorkspace(Ctx base, User owner) throws Exception {
        var ws = workspaceRepository.findById(base.wsId).orElseThrow();
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase).header("Authorization", "Bearer " + base.token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), project.getKey(), owner, base.token, json.readTree(body));
    }

    private String configBody(Ctx ctx) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId + "/config")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** JSON fragment for a create body's parent field. */
    private String parentField(String parentId) {
        return "\"parentId\":\"" + parentId + "\"";
    }

    /** Build a create-issue JSON body: title + type + default TO DO status + extra fragments. */
    private String body(Ctx ctx, String typeName, String title, String... extra) {
        var sb = new StringBuilder("{");
        sb.append("\"title\":\"").append(title).append("\",");
        sb.append("\"typeId\":\"").append(ctx.typeId(typeName)).append("\",");
        sb.append("\"statusId\":\"").append(ctx.todoStatusId()).append("\"");
        for (var e : extra) sb.append(",").append(e);
        sb.append("}");
        return sb.toString();
    }

    private ResultActions create(Ctx ctx, String body) throws Exception {
        return mockMvc.perform(post(issuesBase(ctx))
                .header("Authorization", "Bearer " + ctx.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private IssueRef createIssue(Ctx ctx, String typeName, String title, String... extra) throws Exception {
        var resp = create(ctx, body(ctx, typeName, title, extra))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(resp);
        return new IssueRef(node.get("id").asText(), node.get("number").asLong(), node.get("key").asText());
    }

    private ResultActions patch(Ctx ctx, long number, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch(issuesBase(ctx) + "/" + number)
                .header("Authorization", "Bearer " + ctx.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void moveToDone(Ctx ctx, long number) throws Exception {
        patch(ctx, number, "{\"statusId\":\"" + ctx.doneStatusId() + "\"}")
                .andExpect(status().isOk());
    }

    private ResultActions getIssue(Ctx ctx, long number) throws Exception {
        return mockMvc.perform(get(issuesBase(ctx) + "/" + number)
                .header("Authorization", "Bearer " + ctx.token));
    }

    private ResultActions children(Ctx ctx, long number) throws Exception {
        return mockMvc.perform(get(issuesBase(ctx) + "/" + number + "/children")
                .header("Authorization", "Bearer " + ctx.token));
    }

    private ResultActions history(Ctx ctx, long number) throws Exception {
        return mockMvc.perform(get(issuesBase(ctx) + "/" + number + "/history")
                .header("Authorization", "Bearer " + ctx.token));
    }

    // ---- entity bootstrap (mirrors DelegatedAdminBindingTest) ----

    private User user() {
        var u = new User();
        u.setEmail(("u-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("ws-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User user, WorkspaceRole role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(role);
        workspaceMemberRepository.save(m);
    }

    private Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Proj");
        p.setKey("P" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    private void projectMember(Project project, User user, ProjectRole role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(role);
        projectMemberRepository.save(m);
    }

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
