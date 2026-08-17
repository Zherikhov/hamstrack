package com.hamstrack.workspace;

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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard for the tenancy invariant enforced by
 * {@link com.hamstrack.workspace.service.WorkspaceAccessService} (HD-82) — the project's
 * top bug class. The board read ({@code GET .../projects/{id}/issues}) now resolves its
 * workspace/project through {@code WorkspaceAccessService.resolveProject}.
 *
 * <p>The invariant under test: a caller who is <strong>not a member</strong> of the target
 * workspace, and a caller hitting a <strong>non-existent</strong> workspace, must both get
 * <strong>404</strong> — never 403 (which would disclose existence across tenants) and
 * never 200 (which would leak another tenant's data). The two cases are indistinguishable.
 *
 * <p>Two users each own a workspace/project; user B (a member of nothing in A's tenant)
 * pokes at user A's workspace and project. Bootstrapped through the repository layer like
 * {@code BoardCapTest} / {@code IssueClosedAtTest}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class WorkspaceAccessTenancyTest {

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

    /**
     * User B is authenticated but is NOT a member of user A's workspace. Reading A's board
     * must be a 404 (workspace "not found" from B's vantage) — not 403, not 200.
     */
    @Test
    void nonMemberReadingAnotherTenantsBoardGets404NotForbiddenNor200() throws Exception {
        var a = newProject();
        var bToken = login(user()); // B: exists, authenticates, member of nothing in A's tenant

        mockMvc.perform(get(boardUrl(a.wsId, a.projectId))
                        .header("Authorization", "Bearer " + bToken))
                .andExpect(status().isNotFound());
    }

    /**
     * A non-existent workspace id must be indistinguishable from a workspace the caller
     * simply isn't a member of: both 404. (Guards against a future 403/existence disclosure.)
     */
    @Test
    void nonExistentWorkspaceGives404IndistinguishableFromNonMember() throws Exception {
        var bToken = login(user());

        mockMvc.perform(get(boardUrl(UUID.randomUUID(), UUID.randomUUID()))
                        .header("Authorization", "Bearer " + bToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Sanity anchor: the owner (a real member) reads their own board successfully, proving
     * the 404s above are the tenancy gate rejecting B — not a broken endpoint rejecting
     * everyone.
     */
    @Test
    void ownerReadsOwnBoardOk() throws Exception {
        var a = newProject();

        mockMvc.perform(get(boardUrl(a.wsId, a.projectId))
                        .header("Authorization", "Bearer " + a.token))
                .andExpect(status().isOk());
    }

    // ============================================================ helpers

    private String boardUrl(UUID wsId, UUID projectId) {
        return "/api/workspaces/" + wsId + "/projects/" + projectId + "/issues";
    }

    private record Ctx(UUID wsId, UUID projectId, String token) {}

    private Ctx newProject() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner);
        var project = project(ws, owner);
        projectMember(project, owner);
        return new Ctx(ws.getId(), project.getId(), login(owner));
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
