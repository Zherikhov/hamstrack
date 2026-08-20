package com.hamstrack.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fixtures for the Insights panel (HD-140 R6). Bootstrapped through the repository layer and the
 * public API exactly as {@code SearchApiTest} does — every catalog id is read from the project
 * config endpoint rather than hardcoded, so a change to the seeded taxonomy shows up as a fixture
 * failure instead of a silently wrong assertion.
 *
 * <p>The one thing worth noting for a reader coming from the other report test bases: insights is
 * <strong>workspace</strong>-scoped, so several of these helpers take a workspace and add projects
 * to it, and a {@code Ctx} is a project inside a workspace rather than a whole tenancy.
 */
abstract class InsightsTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    final ObjectMapper json = new ObjectMapper();

    record Ctx(UUID wsId, UUID projectId, String projectKey, User owner, String token, JsonNode config) {
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

        UUID priorityId(String name) {
            for (var p : config.get("priorities")) {
                if (p.get("name").asText().equals(name)) return UUID.fromString(p.get("id").asText());
            }
            throw new AssertionError("priority not offered: " + name);
        }

        UUID todoStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("TODO")) return UUID.fromString(s.get("id").asText());
            }
            throw new AssertionError("no TODO-category status");
        }

        String issuesBase() {
            return "/api/workspaces/" + wsId + "/projects/" + projectId + "/issues";
        }
    }

    // ---- fixtures ----

    /** A fresh workspace with one project, owned by a fresh member. */
    Ctx newProject() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, "OWNER");
        return addProject(ws, owner, login(owner));
    }

    /** A second (third, …) project in an existing workspace, sharing the caller's token. */
    Ctx addProject(Workspace ws, User owner, String token) throws Exception {
        var project = project(ws, owner);
        projectMember(project, owner, "MANAGER");
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), project.getKey(), owner, token, json.readTree(body));
    }

    Workspace workspaceOf(Ctx ctx) {
        return workspaceRepository.findById(ctx.wsId()).orElseThrow();
    }

    // ---- issue creation ----

    String assignee(UUID id) {
        return "\"assigneeId\":\"" + id + "\"";
    }

    String withStatus(Ctx ctx, String name) {
        return "\"statusId\":\"" + ctx.statusId(name) + "\"";
    }

    String priority(Ctx ctx, String name) {
        return "\"priorityId\":\"" + ctx.priorityId(name) + "\"";
    }

    String points(String value) {
        return "\"storyPoints\":" + value;
    }

    String labels(UUID... ids) {
        var parts = new ArrayList<String>();
        for (var id : ids) parts.add("\"" + id + "\"");
        return "\"labelIds\":[" + String.join(",", parts) + "]";
    }

    String createIssue(Ctx ctx, String typeName, String title, String... extra) throws Exception {
        var parts = new ArrayList<String>();
        parts.add("\"title\":\"" + title + "\"");
        parts.add("\"typeId\":\"" + ctx.typeId(typeName) + "\"");
        boolean hasStatus = false;
        for (var e : extra) if (e.contains("statusId")) hasStatus = true;
        if (!hasStatus) parts.add("\"statusId\":\"" + ctx.todoStatusId() + "\"");
        for (var e : extra) parts.add(e);
        var resp = mockMvc.perform(post(ctx.issuesBase())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + String.join(",", parts) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asText();
    }

    UUID createLabel(Ctx ctx, String name) throws Exception {
        var resp = mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/labels")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    UUID createComponent(Ctx ctx, String name) throws Exception {
        var resp = mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/"
                        + ctx.projectId() + "/components")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    String component(UUID id) {
        return "\"componentId\":\"" + id + "\"";
    }

    // ---- the endpoint under test ----

    ResultActions insights(UUID wsId, String token, String query, String measure,
                           String slice, String segment) throws Exception {
        var body = new StringBuilder("{\"query\":").append(json.writeValueAsString(query));
        if (measure != null) body.append(",\"measure\":").append(json.writeValueAsString(measure));
        if (slice != null) body.append(",\"slice\":").append(json.writeValueAsString(slice));
        if (segment != null) body.append(",\"segment\":").append(json.writeValueAsString(segment));
        body.append("}");
        return mockMvc.perform(post("/api/workspaces/" + wsId + "/search/insights")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    JsonNode insightsBody(Ctx ctx, String query, String slice, String segment) throws Exception {
        var body = insights(ctx.wsId(), ctx.token(), query, null, slice, segment)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** The slice bucket with this label, or {@code null}. */
    static JsonNode sliceNamed(JsonNode response, String label) {
        for (var s : response.get("slices")) {
            var name = s.get("bucket").get("label");
            if (label == null ? name.isNull() : label.equals(name.asText(null))) return s;
        }
        return null;
    }

    // ---- entity bootstrap ----

    User user() {
        var u = new User();
        u.setEmail(("u-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("ws-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    void member(Workspace ws, User user, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
    }

    Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Proj");
        p.setKey("P" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    void projectMember(Project project, User user, String role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, role));
        projectMemberRepository.save(m);
    }

    String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
