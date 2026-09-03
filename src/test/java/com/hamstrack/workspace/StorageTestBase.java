package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.repository.IssueAttachmentRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.repository.WorkspaceStorageUsageRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bootstrap shared by the HD-191 storage tests — a workspace, a project, a member who owns both,
 * and helpers to upload and to read the counter straight out of the database.
 *
 * <p>Repository-layer bootstrap, mirroring {@code AttachmentCrudTenancyTest} /
 * {@code IssueClosedAtTest}, rather than driving the signup and onboarding flows: those have
 * throttles and mail of their own and would make every storage assertion depend on them.
 *
 * <p>Deliberately NOT annotated {@code @SpringBootTest}. Each subclass declares its own properties
 * — a tiny quota, a disabled quota, a mocked {@code FileStorage} — and those are what the context
 * key is built from, so sharing the annotation here would only hide which context a test runs in.
 */
abstract class StorageTestBase {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected RoleCatalog roleCatalog;
    @Autowired protected UserRepository userRepository;
    @Autowired protected WorkspaceRepository workspaceRepository;
    @Autowired protected WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired protected ProjectRepository projectRepository;
    @Autowired protected ProjectMemberRepository projectMemberRepository;
    @Autowired protected IssueRepository issueRepository;
    @Autowired protected IssueAttachmentRepository attachmentRepository;
    @Autowired protected WorkspaceStorageUsageRepository usageRepository;
    @Autowired protected PasswordEncoder passwordEncoder;

    protected final ObjectMapper json = new ObjectMapper();

    /** A workspace, a project inside it, an owner of both, and that owner's access token. */
    protected record Ctx(UUID wsId, UUID projectId, String token, JsonNode config) {
        UUID typeId(String name) {
            return find(config.get("issueTypes"), name);
        }

        UUID todoStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("TODO")) {
                    return UUID.fromString(s.get("id").asText());
                }
            }
            throw new AssertionError("no TODO-category status in workflow");
        }

        private static UUID find(JsonNode arr, String value) {
            for (var n : arr) {
                if (n.get("name").asText().equals(value)) {
                    return UUID.fromString(n.get("id").asText());
                }
            }
            throw new AssertionError("name=" + value + " not offered by project");
        }
    }

    /**
     * The counter as the database holds it — read through the repository rather than through the
     * API, so an assertion about the TRIGGER cannot be satisfied by a service that happens to
     * compute the same number a different way.
     */
    protected long bytesUsed(UUID workspaceId) {
        return usageRepository.findByWorkspaceId(workspaceId)
                .map(u -> u.getBytesUsed())
                .orElse(0L);
    }

    protected long attachmentCount(UUID workspaceId) {
        return usageRepository.findByWorkspaceId(workspaceId)
                .map(u -> u.getAttachmentCount())
                .orElse(0L);
    }

    protected String attachBase(Ctx ctx, long issueNumber) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
               + "/issues/" + issueNumber + "/attachments";
    }

    protected ResultActions uploadRaw(Ctx ctx, long issueNumber, String filename, byte[] bytes)
            throws Exception {
        return mockMvc.perform(multipart(attachBase(ctx, issueNumber))
                .file(new MockMultipartFile("file", filename, "text/plain", bytes))
                .header("Authorization", "Bearer " + ctx.token()));
    }

    protected JsonNode upload(Ctx ctx, long issueNumber, String filename, byte[] bytes)
            throws Exception {
        var body = uploadRaw(ctx, issueNumber, filename, bytes)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected byte[] bytesOf(int size) {
        var b = new byte[size];
        java.util.Arrays.fill(b, (byte) 'x');
        return b;
    }

    protected long createIssue(Ctx ctx) throws Exception {
        var body = "{\"title\":\"issue\",\"typeId\":\"" + ctx.typeId("Task")
                   + "\",\"statusId\":\"" + ctx.todoStatusId() + "\"}";
        var resp = mockMvc.perform(post("/api/workspaces/" + ctx.wsId()
                                        + "/projects/" + ctx.projectId() + "/issues")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("number").asLong();
    }

    protected Ctx newProject() throws Exception {
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

    /** A second project inside an EXISTING workspace — for the cascade and breakdown assertions. */
    protected Ctx secondProject(Ctx existing) throws Exception {
        var ws = workspaceRepository.findById(existing.wsId()).orElseThrow();
        var owner = workspaceMemberRepository.findAll().stream()
                .filter(m -> m.getWorkspace().getId().equals(ws.getId()))
                .findFirst().orElseThrow().getUser();
        var project = project(ws, owner);
        projectMember(project, owner);
        var configBase = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config";
        var body = mockMvc.perform(get(configBase)
                        .header("Authorization", "Bearer " + existing.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ctx(ws.getId(), project.getId(), existing.token(), json.readTree(body));
    }

    protected User user() {
        var u = new User();
        u.setEmail(("u-" + System.nanoTime() + "-" + UUID.randomUUID() + "@example.com").toLowerCase());
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

    protected void member(Workspace ws, User user) {
        member(ws, user, "OWNER");
    }

    protected void member(Workspace ws, User user, String builtInRoleKey) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, builtInRoleKey));
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

    protected void projectMember(Project project, User user) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, "MANAGER"));
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
