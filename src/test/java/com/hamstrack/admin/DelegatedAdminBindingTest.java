package com.hamstrack.admin;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.entity.PrioritySet;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.PrioritySetRepository;
import com.hamstrack.issue.repository.StatusRepository;
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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Delegated-admin binding surface: workspace-admin and project-admin
 * authorization + scope-isolated binding validation.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DelegatedAdminBindingTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PrioritySetRepository prioritySetRepository;
    @Autowired StatusRepository statusRepository;
    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired PasswordEncoder passwordEncoder;

    // ---------- workspace admin ----------

    @Test
    void workspaceOwnerSeesMatrixAndCanBindGlobalSet() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        var token = login(owner);

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/admin/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.projectId == '" + project.getId() + "')]").exists());

        var globalSet = prioritySetRepository.findBySystemDefaultTrue().orElseThrow().getId();
        mockMvc.perform(patch("/api/workspaces/" + ws.getId() + "/admin/projects/" + project.getId() + "/bindings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prioritySetId\":\"" + globalSet + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prioritySetId").value(globalSet.toString()));
    }

    @Test
    void workspaceMemberWithoutAdminRoleGets403() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var plain = user();
        member(ws, plain, WorkspaceRole.MEMBER);
        var token = login(plain);

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/admin/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonMemberGets404OnWorkspaceAdmin() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var outsider = user();
        var token = login(outsider);

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/admin/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void bindingASetFromAnotherWorkspaceIsRejected() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        var token = login(owner);

        // a priority set scoped to a *different* workspace
        var otherWs = workspace(owner);
        var foreign = new PrioritySet();
        foreign.setName("Foreign-" + System.nanoTime());
        foreign.setScopeWorkspaceId(otherWs.getId());
        prioritySetRepository.save(foreign);

        mockMvc.perform(patch("/api/workspaces/" + ws.getId() + "/admin/projects/" + project.getId() + "/bindings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prioritySetId\":\"" + foreign.getId() + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- project admin ----------

    @Test
    void projectManagerCanReadAndBind() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var manager = user();
        member(ws, manager, WorkspaceRole.MEMBER);
        var project = project(ws, owner);
        projectMember(project, manager, ProjectRole.MANAGER);
        var token = login(manager);

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/bindings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()));

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/binding-options")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows[?(@.scope == 'GLOBAL')]").exists());

        var globalSet = prioritySetRepository.findBySystemDefaultTrue().orElseThrow().getId();
        mockMvc.perform(patch("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/bindings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prioritySetId\":\"" + globalSet + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prioritySetId").value(globalSet.toString()));
    }

    @Test
    void projectMemberWithoutManagerRoleGets403() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var viewer = user();
        member(ws, viewer, WorkspaceRole.MEMBER);
        var project = project(ws, owner);
        projectMember(project, viewer, ProjectRole.MEMBER);
        var token = login(viewer);

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/bindings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void projectManagerCannotBindAnotherProjectsPrivateSet() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        var other = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);

        // priority set private to the *other* project in the same workspace
        var priv = new PrioritySet();
        priv.setName("Priv-" + System.nanoTime());
        priv.setScopeProjectId(other.getId());
        prioritySetRepository.save(priv);

        mockMvc.perform(patch("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/bindings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prioritySetId\":\"" + priv.getId() + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- scoped catalog ----------

    @Test
    void projectPrivateStatusIsScopedAndInvisibleGlobally() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var base = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin";

        var name = "PP-" + System.nanoTime();
        mockMvc.perform(post(base + "/statuses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"category\":\"TODO\"}"))
                .andExpect(status().isCreated());

        // visible in the project-scoped catalog
        mockMvc.perform(get(base + "/statuses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + name + "')]").exists());

        // NOT visible in the global catalog (scope-correct global list)
        var admin = adminToken();
        mockMvc.perform(get("/api/admin/statuses").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + name + "')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.name == 'To Do')]").exists());
    }

    @Test
    void projectAdminCannotEditAGlobalStatus() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);

        // a seeded global status id
        var admin = adminToken();
        var body = mockMvc.perform(get("/api/admin/statuses").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        String globalId = ((java.util.List<String>) com.jayway.jsonpath.JsonPath.read(
                body, "$[?(@.name == 'To Do')].id")).get(0);

        // a global row is not at the project scope → 404, not editable here
        mockMvc.perform(patch("/api/workspaces/" + ws.getId() + "/projects/" + project.getId()
                        + "/admin/statuses/" + globalId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\",\"category\":\"TODO\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusNameUniquenessIsPerScope() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var base = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin";

        var name = "Dup-" + System.nanoTime();
        var payload = "{\"name\":\"" + name + "\",\"category\":\"TODO\"}";
        mockMvc.perform(post(base + "/statuses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        // same name, same scope → conflict
        mockMvc.perform(post(base + "/statuses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict());
        // same name at the *global* scope → allowed (different scope)
        var admin = adminToken();
        mockMvc.perform(post("/api/admin/statuses").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
    }

    @Test
    void projectMemberCannotCreateAStatus() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var viewer = user();
        member(ws, viewer, WorkspaceRole.MEMBER);
        var project = project(ws, owner);
        projectMember(project, viewer, ProjectRole.MEMBER);
        var token = login(viewer);

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/statuses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X-" + System.nanoTime() + "\",\"category\":\"TODO\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- scoped sets (workflows) ----------

    @Test
    void projectAdminBuildsPrivateWorkflowFromOwnStatus() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var base = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin";

        // create a project-private status, then a private workflow using it
        var statusBody = mockMvc.perform(post(base + "/statuses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"S-" + System.nanoTime() + "\",\"category\":\"TODO\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var statusId = statusBody.replaceAll(".*\"id\":\"([a-f0-9-]+)\".*", "$1");

        var wfName = "WF-" + System.nanoTime();
        mockMvc.perform(post(base + "/workflows").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + wfName + "\",\"statusIds\":[\"" + statusId + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statuses[0].id").value(statusId));

        // visible in the project set console and offered as a PROJECT-scoped binding
        mockMvc.perform(get(base + "/workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + wfName + "')]").exists());
        mockMvc.perform(get(base + "/binding-options").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflows[?(@.name == '" + wfName + "')].scope").value("PROJECT"));
    }

    @Test
    void workflowCannotIncludeAnotherProjectsPrivateStatus() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        var other = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);

        // a status private to the *other* project
        var foreign = new Status();
        foreign.setName("FS-" + System.nanoTime());
        foreign.setCategory(StatusCategory.TODO);
        foreign.setScopeProjectId(other.getId());
        statusRepository.save(foreign);

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"WF-" + System.nanoTime() + "\",\"statusIds\":[\"" + foreign.getId() + "\"]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void scopedWorkflowIsInvisibleInTheGlobalConsole() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var admin = adminToken();

        // a global status id to build the workflow from (global children are allowed)
        var statuses = mockMvc.perform(get("/api/admin/statuses").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        String globalStatusId = ((java.util.List<String>) com.jayway.jsonpath.JsonPath.read(
                statuses, "$[?(@.name == 'To Do')].id")).get(0);

        var wfName = "WF-" + System.nanoTime();
        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + wfName + "\",\"statusIds\":[\"" + globalStatusId + "\"]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/workflows").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + wfName + "')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.systemDefault == true)]").exists());
    }

    // ---------- scoped fields ----------

    @Test
    void projectAdminCreatesPrivateFieldAndSet() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var base = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin";

        var fieldBody = mockMvc.perform(post(base + "/fields").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PF-" + System.nanoTime() + "\",\"type\":\"TEXT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var fieldId = fieldBody.replaceAll(".*\"id\":\"([a-f0-9-]+)\".*", "$1");

        var setName = "FSET-" + System.nanoTime();
        mockMvc.perform(post(base + "/field-sets").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + setName + "\",\"items\":[{\"fieldId\":\"" + fieldId
                                + "\",\"required\":false,\"showOnCreate\":true}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].field.id").value(fieldId));

        mockMvc.perform(get(base + "/field-sets").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + setName + "')]").exists());
    }

    @Test
    void fieldSetCannotIncludeAnotherProjectsPrivateField() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        var other = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);

        var foreign = new FieldDef();
        foreign.setKey("ff_" + Math.abs(UUID.randomUUID().hashCode()));
        foreign.setName("FF-" + System.nanoTime());
        foreign.setType(FieldType.TEXT);
        foreign.setScopeProjectId(other.getId());
        fieldDefRepository.save(foreign);

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/field-sets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"FSET-" + System.nanoTime() + "\",\"items\":[{\"fieldId\":\""
                                + foreign.getId() + "\",\"required\":false,\"showOnCreate\":true}]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void projectPrivateFieldIsInvisibleInTheGlobalConsole() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);

        var name = "PF-" + System.nanoTime();
        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin/fields")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"TEXT\"}"))
                .andExpect(status().isCreated());

        var admin = adminToken();
        mockMvc.perform(get("/api/admin/fields").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '" + name + "')]").doesNotExist());
    }

    @Test
    void projectCatalogListIncludesInheritedGlobalRows() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var base = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin";

        // The project uses the global default catalog — its Settings list must SHOW those
        // seeded global statuses (read-only, tagged GLOBAL), not be empty.
        mockMvc.perform(get(base + "/statuses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'To Do' && @.scope == 'GLOBAL')]").exists());
        mockMvc.perform(get(base + "/workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.systemDefault == true && @.scope == 'GLOBAL')]").exists());
    }

    @Test
    void projectAdminCanReadCatalogUsage() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, WorkspaceRole.OWNER);
        var project = project(ws, owner);
        projectMember(project, owner, ProjectRole.MANAGER);
        var token = login(owner);
        var base = "/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/admin";

        var body = mockMvc.perform(post(base + "/statuses").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"US-" + System.nanoTime() + "\",\"category\":\"TODO\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var id = body.replaceAll(".*\"id\":\"([a-f0-9-]+)\".*", "$1");

        mockMvc.perform(get(base + "/statuses/" + id + "/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues").value(0));
    }

    // ---------- cross-tenant usage isolation (the P0 leak: usage stats must not span tenants) ----------

    @Test
    void catalogUsageCountsAreScopedToTheCallersWorkspace() throws Exception {
        // Workspace A with one project; workspace B with two — all unbound, so all
        // three implicitly use the global system-default workflow that the seeded
        // global "To Do" status belongs to. A delegated console must report only the
        // caller's own workspace's projects, never a global figure spanning tenants.
        var ownerA = user();
        var wsA = workspace(ownerA);
        member(wsA, ownerA, WorkspaceRole.OWNER);
        project(wsA, ownerA);
        var tokenA = login(ownerA);

        var ownerB = user();
        var wsB = workspace(ownerB);
        member(wsB, ownerB, WorkspaceRole.OWNER);
        project(wsB, ownerB);
        project(wsB, ownerB);
        var tokenB = login(ownerB);

        // Workspace A sees exactly its 1 project using the shared global "To Do"
        mockMvc.perform(get("/api/workspaces/" + wsA.getId() + "/admin/statuses")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'To Do')].usage.projects").value(Matchers.contains(1)));

        // Workspace B sees exactly its 2 — A's project never inflates B's figure and vice versa
        mockMvc.perform(get("/api/workspaces/" + wsB.getId() + "/admin/statuses")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'To Do')].usage.projects").value(Matchers.contains(2)));

        // The global (system-admin / DC) console still counts across the whole install
        var admin = adminToken();
        mockMvc.perform(get("/api/admin/statuses").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'To Do')].usage.projects")
                        .value(Matchers.contains(Matchers.greaterThanOrEqualTo(3))));
    }

    // ---------- setup helpers ----------

    private String adminToken() throws Exception {
        var u = new User();
        u.setEmail(("adm-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Admin");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.ADMIN);
        return login(userRepository.save(u));
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
