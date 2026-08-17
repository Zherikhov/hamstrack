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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared bootstrap + HTTP helpers for the HD-30 (Labels) integration suites. Data is
 * set up through the repository layer for tenancy scaffolding (users / workspaces /
 * projects, exactly like {@code BoardCapTest} and {@code WorkspaceAccessTenancyTest})
 * and through the <em>real API</em> for everything the feature owns — labels, issues,
 * attachments — so every service-level invariant is exercised on the way in.
 *
 * <p>Subclasses carry their own {@code @SpringBootTest} annotation because several of
 * them override a property (the classification caps, Hibernate statistics).
 */
public abstract class LabelTestBase {

    @Autowired protected MockMvc mockMvc;
    // HD-123: memberships carry a roles row now; reference() resolves a built-in with no query.
    @Autowired protected com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired protected UserRepository userRepository;
    @Autowired protected WorkspaceRepository workspaceRepository;
    @Autowired protected WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired protected ProjectRepository projectRepository;
    @Autowired protected ProjectMemberRepository projectMemberRepository;
    @Autowired protected PasswordEncoder passwordEncoder;

    protected final ObjectMapper json = new ObjectMapper();

    // ============================================================ context records

    /** One workspace + project + its OWNER, already logged in. */
    protected record Ctx(UUID wsId, UUID projectId, User owner, String token, JsonNode config) {

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

        UUID todoStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("TODO")) return UUID.fromString(s.get("id").asText());
            }
            throw new AssertionError("no TODO-category status");
        }

        String labelsBase() {
            return "/api/workspaces/" + wsId + "/labels";
        }

        String issuesBase() {
            return "/api/workspaces/" + wsId + "/projects/" + projectId + "/issues";
        }
    }

    /** A secondary member of a {@link Ctx}'s workspace. */
    protected record Actor(User user, String token) {}

    protected Ctx newProject() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, "OWNER");
        var project = project(ws, owner);
        projectMember(project, owner, "MANAGER");
        var token = login(owner);
        var configUrl = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configUrl).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), owner, token, json.readTree(body));
    }

    /** Add a further user to the ctx's workspace (and its project) and log them in. */
    protected Actor addMember(Ctx ctx, String role) throws Exception {
        var u = user();
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        member(ws, u, role);
        var project = projectRepository.findById(ctx.projectId()).orElseThrow();
        projectMember(project, u, "MEMBER");
        return new Actor(u, login(u));
    }

    // ============================================================ label HTTP

    protected ResultActions postLabel(Ctx ctx, String token, String bodyJson) throws Exception {
        return mockMvc.perform(post(ctx.labelsBase())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    /** Create a label as the ctx owner and return its id. */
    protected UUID createLabel(Ctx ctx, String name) throws Exception {
        return createLabel(ctx, ctx.token(), name);
    }

    protected UUID createLabel(Ctx ctx, String token, String name) throws Exception {
        var resp = postLabel(ctx, token, "{\"name\":" + json.writeValueAsString(name) + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    protected ResultActions patchLabel(Ctx ctx, String token, UUID labelId, String bodyJson) throws Exception {
        return mockMvc.perform(patch(ctx.labelsBase() + "/" + labelId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    protected ResultActions archiveLabel(Ctx ctx, String token, UUID labelId) throws Exception {
        return mockMvc.perform(post(ctx.labelsBase() + "/" + labelId + "/archive")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions unarchiveLabel(Ctx ctx, String token, UUID labelId) throws Exception {
        return mockMvc.perform(post(ctx.labelsBase() + "/" + labelId + "/unarchive")
                .header("Authorization", "Bearer " + token));
    }

    protected ResultActions mergeLabels(Ctx ctx, String token, UUID targetId, UUID... sourceIds) throws Exception {
        var ids = new ArrayList<String>();
        for (var id : sourceIds) ids.add("\"" + id + "\"");
        return mockMvc.perform(post(ctx.labelsBase() + "/" + targetId + "/merge")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceIds\":[" + String.join(",", ids) + "]}"));
    }

    protected ResultActions deleteLabel(Ctx ctx, String token, UUID labelId, boolean force) throws Exception {
        return mockMvc.perform(delete(ctx.labelsBase() + "/" + labelId + (force ? "?force=true" : ""))
                .header("Authorization", "Bearer " + token));
    }

    protected JsonNode listLabels(Ctx ctx, String token, String query) throws Exception {
        var body = mockMvc.perform(get(ctx.labelsBase() + (query == null ? "" : query))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected ResultActions labelUsage(Ctx ctx, String token, UUID labelId) throws Exception {
        return mockMvc.perform(get(ctx.labelsBase() + "/" + labelId + "/usage")
                .header("Authorization", "Bearer " + token));
    }

    // ============================================================ issue HTTP

    /** Create an issue via the API; {@code extra} are raw JSON fragments. */
    protected JsonNode createIssue(Ctx ctx, String title, String... extra) throws Exception {
        var resp = postIssue(ctx, ctx.token(), title, extra)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp);
    }

    protected ResultActions postIssue(Ctx ctx, String token, String title, String... extra) throws Exception {
        var parts = new ArrayList<String>();
        parts.add("\"title\":" + json.writeValueAsString(title));
        parts.add("\"typeId\":\"" + ctx.typeId("Task") + "\"");
        boolean hasStatus = false;
        for (var e : extra) if (e.contains("statusId")) hasStatus = true;
        if (!hasStatus) parts.add("\"statusId\":\"" + ctx.todoStatusId() + "\"");
        for (var e : extra) parts.add(e);
        return mockMvc.perform(post(ctx.issuesBase())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + String.join(",", parts) + "}"));
    }

    protected ResultActions patchIssue(Ctx ctx, String token, long number, String bodyJson) throws Exception {
        return mockMvc.perform(patch(ctx.issuesBase() + "/" + number)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    protected JsonNode getIssue(Ctx ctx, long number) throws Exception {
        var body = mockMvc.perform(get(ctx.issuesBase() + "/" + number)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected JsonNode issueHistory(Ctx ctx, long number) throws Exception {
        var body = mockMvc.perform(get(ctx.issuesBase() + "/" + number + "/history?size=100")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** The board (no {@code size}) read, optionally with a query string. */
    protected JsonNode board(Ctx ctx, String query) throws Exception {
        var body = mockMvc.perform(get(ctx.issuesBase() + (query == null ? "" : query))
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** Titles of a board response, for set assertions. */
    protected static java.util.Set<String> titles(JsonNode boardNode) {
        var out = new java.util.HashSet<String>();
        for (var i : boardNode.get("issues")) out.add(i.get("title").asText());
        return out;
    }

    /** The label names carried by an issue response node. */
    protected static java.util.List<String> labelNames(JsonNode issueNode) {
        var out = new ArrayList<String>();
        for (var l : issueNode.get("labels")) out.add(l.get("name").asText());
        return out;
    }

    protected static String labelIdsJson(UUID... ids) {
        var parts = new ArrayList<String>();
        for (var id : ids) parts.add("\"" + id + "\"");
        return "\"labelIds\":[" + String.join(",", parts) + "]";
    }

    // ============================================================ entity bootstrap

    protected User user() {
        var u = new User();
        u.setEmail(("u-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com").toLowerCase());
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    protected Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("ws-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    protected void member(Workspace ws, User user, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
    }

    protected Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Proj");
        p.setKey("P" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    protected void projectMember(Project project, User user, String role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, role));
        projectMemberRepository.save(m);
    }

    protected String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
