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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the native "Closed date" (HD-57): {@code issues.closed_at}
 * is stamped when an issue enters a DONE-category status, cleared when it leaves, and
 * never re-stamped while it stays DONE. Also asserts the field is surfaced on the GET
 * issue response.
 *
 * <p>Bootstrapped through the repository layer like the other issue integration tests;
 * every status id is read from the public project-config endpoint (nothing hardcoded).
 * The system-default workflow seeds To Do / In Progress / Done and defines no explicit
 * transitions, so every transition is open — Done → To Do is reachable here.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class IssueClosedAtTest {

    @Autowired MockMvc mockMvc;
    // HD-123: memberships carry a roles row now; reference() resolves a built-in with no query.
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    // ============================================================ tests

    @Test
    void createInTodoLeavesClosedAtNull() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, ctx.todoStatusId());
        assert !getNode(ctx, issue).hasNonNull("closedAt")
                : "a To Do issue must have no close date";
    }

    @Test
    void createDirectlyInDoneStampsClosedAt() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, ctx.doneStatusId());
        assert getNode(ctx, issue).hasNonNull("closedAt")
                : "creating an issue directly in a DONE status must stamp closed_at";
    }

    @Test
    void enteringDoneStampsClosedAt() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, ctx.todoStatusId());
        assert !getNode(ctx, issue).hasNonNull("closedAt") : "precondition: not closed yet";

        moveTo(ctx, issue, ctx.doneStatusId()).andExpect(status().isOk());

        assert getNode(ctx, issue).hasNonNull("closedAt")
                : "entering a DONE status must stamp closed_at";
    }

    @Test
    void leavingDoneClearsClosedAt() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, ctx.todoStatusId());
        moveTo(ctx, issue, ctx.doneStatusId()).andExpect(status().isOk());
        assert getNode(ctx, issue).hasNonNull("closedAt") : "precondition: closed";

        moveTo(ctx, issue, ctx.todoStatusId()).andExpect(status().isOk());

        assert !getNode(ctx, issue).hasNonNull("closedAt")
                : "leaving a DONE status must clear closed_at";
    }

    @Test
    void movingBetweenNonDoneStatusesNeverStamps() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, ctx.todoStatusId());

        moveTo(ctx, issue, ctx.inProgressStatusId()).andExpect(status().isOk());

        assert !getNode(ctx, issue).hasNonNull("closedAt")
                : "a non-DONE → non-DONE transition must not stamp closed_at";
    }

    @Test
    void nonStatusUpdateWhileDoneDoesNotRestampOrClear() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx, ctx.doneStatusId());
        var stamped = getNode(ctx, issue).get("closedAt").asText();

        // A PATCH that changes only the title must leave closed_at exactly as-is —
        // the stamp/clear logic lives solely in the status-change branch.
        patch(ctx, issue, "{\"title\":\"renamed while done\"}").andExpect(status().isOk());

        var after = getNode(ctx, issue);
        assert after.hasNonNull("closedAt") : "closed_at must survive an unrelated update";
        assert after.get("closedAt").asText().equals(stamped)
                : "closed_at must not be re-stamped by a non-status update";
    }

    // ============================================================ helpers

    private ResultActions moveTo(Ctx ctx, long number, UUID statusId) throws Exception {
        return patch(ctx, number, "{\"statusId\":\"" + statusId + "\"}");
    }

    private JsonNode getNode(Ctx ctx, long number) throws Exception {
        var body = mockMvc.perform(get(issuesBase(ctx) + "/" + number)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private ResultActions patch(Ctx ctx, long number, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch(issuesBase(ctx) + "/" + number)
                .header("Authorization", "Bearer " + ctx.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Create a Task in the given status; returns its project-scoped number. */
    private long createIssue(Ctx ctx, UUID statusId) throws Exception {
        var body = "{\"title\":\"issue\",\"typeId\":\"" + ctx.typeId("Task")
                + "\",\"statusId\":\"" + statusId + "\"}";
        var resp = mockMvc.perform(post(issuesBase(ctx))
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("number").asLong();
    }

    private String issuesBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId + "/issues";
    }

    private record Ctx(UUID wsId, UUID projectId, String token, JsonNode config) {
        UUID typeId(String name) {
            return find(config.get("issueTypes"), "name", name);
        }
        UUID todoStatusId() {
            return statusByCategory("TODO");
        }
        UUID inProgressStatusId() {
            return statusByCategory("IN_PROGRESS");
        }
        UUID doneStatusId() {
            return statusByCategory("DONE");
        }
        private UUID statusByCategory(String category) {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals(category)) {
                    return UUID.fromString(s.get("id").asText());
                }
            }
            throw new AssertionError("no " + category + "-category status in workflow");
        }
        private static UUID find(JsonNode arr, String field, String value) {
            for (var n : arr) {
                if (n.get(field).asText().equals(value)) return UUID.fromString(n.get("id").asText());
            }
            throw new AssertionError(field + "=" + value + " not offered by project");
        }
    }

    private Ctx newProject() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner);
        var project = project(ws, owner);
        projectMember(project, owner);
        var token = login(owner);
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), token, json.readTree(body));
    }

    // ---- entity bootstrap (mirrors IssueHierarchyTest) ----

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

    private void member(Workspace ws, User user) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(WorkspaceRole.OWNER));
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

    private void projectMember(Project project, User user) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(ProjectRole.MANAGER));
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
