package com.hamstrack.issue;

import com.hamstrack.common.security.RoleScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.storage.FileStorage;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Failure-injection regression for HD-77 (P2 cluster 1). The attachment upload
 * orchestrator ({@code AttachmentService.upload}) reserves + commits the attachment
 * row in a short tx, then writes the blob OFF-tx on the request thread; a
 * {@code FileStorage.store} failure must run a tenant-scoped compensating delete
 * (via {@code findByIdAndIssue}) so no orphan row survives, and surface a 500.
 *
 * <p>The {@link FileStorage} is replaced with a Mockito mock so {@code store(...)}
 * can be made to throw. The compensating delete runs synchronously on the request
 * thread (the upload is not {@code @Async}), so the DB state is fully settled by the
 * time the response returns and can be asserted directly.
 *
 * <p>Bootstrapped through the repository layer like the other issue integration
 * tests (mirrors {@code BoardCapTest} / {@code IssueClosedAtTest}).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AttachmentUploadFailureTest {

    @Autowired MockMvc mockMvc;
    // HD-123: memberships carry a roles row now; reference() resolves a built-in with no query.
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired IssueRepository issueRepository;
    @Autowired IssueAttachmentRepository attachmentRepository;
    @Autowired PasswordEncoder passwordEncoder;

    // Replaces the real LocalFileStorage/S3FileStorage bean for this context so
    // store(...) can be driven to fail.
    @MockitoBean FileStorage fileStorage;

    private final ObjectMapper json = new ObjectMapper();

    // ============================================================ tests

    @Test
    void storeFailureReturns500AndLeavesNoOrphanRow() throws Exception {
        var ctx = newProject();
        var issueId = createIssue(ctx);

        // Inject the failure: the blob write blows up (mirrors an IOException /
        // SdkException / HD-76 timeout from a real backend).
        Mockito.doThrow(new RuntimeException("injected storage failure"))
                .when(fileStorage).store(anyString(), any(InputStream.class), anyLong(), anyString());

        var file = new MockMultipartFile("file", "note.txt", "text/plain", "hello world".getBytes());

        mockMvc.perform(multipart(issuesBase(ctx) + "/1/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isInternalServerError());

        // The compensating tenant-scoped delete must have removed the reserved row —
        // zero attachments remain on the issue.
        var issue = issueRepository.findById(issueId).orElseThrow();
        assert attachmentRepository.findAllByIssueOrderByCreatedAtAsc(issue).isEmpty()
                : "compensating delete must remove the reserved attachment row on store failure; "
                  + "found " + attachmentRepository.findAllByIssueOrderByCreatedAtAsc(issue).size();
    }

    @Test
    void happyPathPersistsRowAndStoresBlob() throws Exception {
        var ctx = newProject();
        var issueId = createIssue(ctx);

        // Default mock store(...) is a no-op success.
        var file = new MockMultipartFile("file", "note.txt", "text/plain", "hello world".getBytes());

        var body = mockMvc.perform(multipart(issuesBase(ctx) + "/1/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        assert node.get("filename").asText().equals("note.txt") : "filename echoed back";

        // store(...) was actually invoked, and the row persists.
        Mockito.verify(fileStorage).store(anyString(), any(InputStream.class), anyLong(), anyString());
        var issue = issueRepository.findById(issueId).orElseThrow();
        assert attachmentRepository.findAllByIssueOrderByCreatedAtAsc(issue).size() == 1
                : "the attachment row must persist on a successful store";
    }

    // ============================================================ helpers

    private UUID createIssue(Ctx ctx) throws Exception {
        var body = "{\"title\":\"issue\",\"typeId\":\"" + ctx.typeId("Task")
                + "\",\"statusId\":\"" + ctx.todoStatusId() + "\"}";
        var resp = mockMvc.perform(post(issuesBase(ctx))
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
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

    // ---- entity bootstrap (mirrors BoardCapTest / IssueClosedAtTest) ----

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
