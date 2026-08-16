package com.hamstrack.issue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.storage.FileStorage;
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
 * HD-83 (audit Q-1) — the application-layer size gate. {@code AttachmentService.validateUpload}
 * rejects a file larger than {@code app.attachments.max-file-size} with 413
 * (Content Too Large, RFC 9110 rename) BEFORE the servlet multipart hard ceiling would
 * trip. The business limit is lowered to 10 bytes here so a tiny upload trips the
 * service gate while staying well under {@code spring.servlet.multipart.max-file-size}
 * (25MB) — the assertion is on the service-layer 413, not the container's parse-time cap.
 *
 * <p>FileStorage is mocked so a would-be store never runs (the 413 fires before any blob
 * write anyway). Bootstrapped through the repository layer like the sibling tests.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.attachments.max-file-size=10B"
})
@AutoConfigureMockMvc
class AttachmentOversizeTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean FileStorage fileStorage;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void uploadExceedingBusinessLimitReturns413ContentTooLarge() throws Exception {
        var ctx = newProject();
        createIssue(ctx);

        // 30 bytes > the 10-byte business limit -> service-layer 413.
        var file = new MockMultipartFile("file", "big.txt", "text/plain",
                "this payload is definitely more than ten bytes".getBytes());

        mockMvc.perform(multipart(attachBase(ctx))
                        .file(file)
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isContentTooLarge());

        // The 413 must fire before any blob write.
        Mockito.verify(fileStorage, Mockito.never())
                .store(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    // ============================================================ helpers

    private String attachBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId + "/issues/1/attachments";
    }

    private void createIssue(Ctx ctx) throws Exception {
        var body = "{\"title\":\"issue\",\"typeId\":\"" + ctx.typeId("Task")
                + "\",\"statusId\":\"" + ctx.todoStatusId() + "\"}";
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId + "/issues")
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
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
        m.setRole(WorkspaceRole.OWNER);
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
        m.setRole(ProjectRole.MANAGER);
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
