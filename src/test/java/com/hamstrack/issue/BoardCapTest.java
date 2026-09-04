package com.hamstrack.issue;

import com.hamstrack.common.security.RoleScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the board issue-list cap (HD-79). The no-{@code size}
 * variant of {@code GET .../issues} is bounded server-side to {@code app.board.max-issues}
 * and returns a {@code BoardIssuesResponse} object ({@code issues/truncated/totalAvailable/cap})
 * instead of a bare array; the {@code ?size=} variant is unchanged and still returns the
 * paginated {@code PageResponse} envelope.
 *
 * <p>The cap is overridden to 3 here so truncation is exercised with a handful of issues.
 * Bootstrapped through the repository layer like the other issue integration tests.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.board.max-issues=3"
})
@AutoConfigureMockMvc
class BoardCapTest {

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
    void boardWithoutSizeIsCappedAndFlaggedTruncated() throws Exception {
        var ctx = newProject();
        for (int i = 0; i < 5; i++) createIssue(ctx, ctx.todoStatusId());

        var node = getBoard(ctx);
        assertThat(node.has("issues"))
                .withFailMessage("board (no size) must be an object with an issues array, not a bare array")
                .isTrue();
        assertThat(node.get("issues").isArray())
                .withFailMessage("board (no size) must be an object with an issues array, not a bare array")
                .isTrue();
        assertThat(node.get("issues"))
                .as(() -> "issues must be capped at app.board.max-issues=3, got " + node.get("issues").size())
                .hasSize(3);
        assertThat(node.get("truncated").asBoolean())
                .withFailMessage("truncated must be true when the project exceeds the cap")
                .isTrue();
        assertThat(node.get("totalAvailable").asLong())
                .as("totalAvailable must report the full filtered count (5)")
                .isEqualTo(5);
        assertThat(node.get("cap").asInt()).as("cap must reflect app.board.max-issues").isEqualTo(3);
    }

    @Test
    void boardBelowCapIsNotTruncated() throws Exception {
        var ctx = newProject();
        createIssue(ctx, ctx.todoStatusId());
        createIssue(ctx, ctx.todoStatusId());

        var node = getBoard(ctx);
        assertThat(node.get("issues")).as("all issues returned when under the cap").hasSize(2);
        assertThat(node.get("truncated").asBoolean()).withFailMessage("truncated must be false under the cap").isFalse();
        assertThat(node.get("totalAvailable").asLong())
                .as("totalAvailable equals the issue count when not truncated")
                .isEqualTo(2);
    }

    @Test
    void withSizeReturnsPageResponseEnvelopeUnchanged() throws Exception {
        var ctx = newProject();
        for (int i = 0; i < 4; i++) createIssue(ctx, ctx.todoStatusId());

        var node = json.readTree(mockMvc.perform(get(issuesBase(ctx) + "?size=2")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(node.has("content"))
                .withFailMessage("size-present must return the PageResponse envelope (content array)")
                .isTrue();
        assertThat(node.get("content").isArray())
                .withFailMessage("size-present must return the PageResponse envelope (content array)")
                .isTrue();
        assertThat(node.get("content")).as("page size respected").hasSize(2);
        assertThat(node.has("truncated"))
                .withFailMessage("the paged envelope must not carry the board 'truncated' flag")
                .isFalse();
    }

    // ============================================================ helpers

    private JsonNode getBoard(Ctx ctx) throws Exception {
        var body = mockMvc.perform(get(issuesBase(ctx))
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private void createIssue(Ctx ctx, UUID statusId) throws Exception {
        var body = "{\"title\":\"issue\",\"typeId\":\"" + ctx.typeId("Task")
                + "\",\"statusId\":\"" + statusId + "\"}";
        mockMvc.perform(post(issuesBase(ctx))
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String issuesBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId + "/issues";
    }

    private record Ctx(UUID wsId, UUID projectId, String token, JsonNode config) {
        UUID typeId(String name) {
            return find(config.get("issueTypes"), "name", name);
        }
        UUID todoStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("TODO")) {
                    return UUID.fromString(s.get("id").asText());
                }
            }
            throw new AssertionError("no TODO-category status in workflow");
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

    // ---- entity bootstrap (mirrors IssueClosedAtTest) ----

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
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
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
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, "MANAGER"));
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
