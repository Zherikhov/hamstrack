package com.hamstrack.admin;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.IssueTypeRepository;
import com.hamstrack.issue.repository.PriorityRepository;
import com.hamstrack.issue.repository.StatusRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-13 / AC-4 and AC-5 — <strong>the two layers that stand between a catalog delete and a
 * stranded issue, pinned separately so a regression says which one broke.</strong>
 *
 * <p>Until {@code V19__issues_taxonomy_fk.sql} there was only one layer: the pre-check in
 * {@code AdminCatalogService}. If it regressed, the delete went through and issues were
 * stranded <em>silently</em> — a row rendering as a blank board column, absent from every
 * status filter, unrepairable through the UI because no screen can name a status that is not
 * there. Now there are two layers, and they fail differently: the pre-check answers a
 * <strong>409 naming the count and the remedy</strong> (AC-5), and if it is ever bypassed the
 * database answers a <strong>409 {@code REFERENCE_CONSTRAINT_VIOLATION}</strong> (AC-6,
 * {@code ReferencedRowConflictContractTest}). Both are 409s, which is exactly why they need
 * distinct assertions: a test checking only the status code would stay green while the entire
 * first layer was gone.
 *
 * <h2>Why AC-4 runs the matrix at all three scopes rather than once</h2>
 * This is not routine coverage. The foreign keys V19 adds are <strong>single-column</strong>
 * precisely because the three catalog scopes — global, workspace, project — cannot be expressed
 * in one key: {@code statuses}/{@code issue_types} have no {@code workspace_id}, and one global
 * row is referenced by issues in every workspace at once. So <em>"an issue may legitimately
 * reference a catalog row at any of the three scopes"</em> is now the load-bearing premise of
 * the whole design, and delete-with-remap is where it is exercised. A test covering one scope
 * leaves the other two resting on the argument alone.
 *
 * <p>The remap itself is deliberately <strong>unscoped</strong> at every one of them — see
 * {@code CatalogDeleteGuardsStayUnscopedTest} for why narrowing it is what turns these 204s
 * into a {@code 23503}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AdminCatalogDeleteWithRemapTest {

    @Autowired MockMvc mockMvc;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired StatusRepository statusRepository;
    @Autowired IssueTypeRepository issueTypeRepository;
    @Autowired PriorityRepository priorityRepository;
    @Autowired IssueRepository issueRepository;
    @Autowired PasswordEncoder passwordEncoder;

    // ===================================================================== AC-4

    /**
     * Global scope: the system console, {@code /api/admin/**}, authorized by the system role
     * {@code ADMIN}. The rows here are the shared catalog every tenant inherits, so this is the
     * scope where the unscoped remap does the most work — a global status is referenced by
     * issues in every workspace on the instance, and every one of them has to move before the
     * row can go.
     */
    @Test
    void deleteWithRemapWorksAtGlobalScope() throws Exception {
        var t = tenant();
        var token = systemAdminToken();

        var doomedStatus = statusRepository.save(statusRow("global doomed", null, null));
        var newStatus = statusRepository.save(statusRow("global heir", null, null));
        var doomedType = issueTypeRepository.save(typeRow("global doomed", null, null));
        var newType = issueTypeRepository.save(typeRow("global heir", null, null));
        var issue = issue(t, doomedStatus, doomedType, t.priority());

        remap(token, "/api/admin/statuses/" + doomedStatus.getId(), newStatus.getId());
        remap(token, "/api/admin/issue-types/" + doomedType.getId(), newType.getId());

        assertRemapped(issue, newStatus.getId(), newType.getId());
        assertGone(doomedStatus.getId(), doomedType.getId());
    }

    /** Workspace scope: a workspace OWNER holds {@code workspace.taxonomy.manage}. */
    @Test
    void deleteWithRemapWorksAtWorkspaceScope() throws Exception {
        var t = tenant();
        var base = "/api/workspaces/" + t.workspace().getId() + "/admin";

        var doomedStatus = statusRepository.save(statusRow("ws doomed", t.workspace().getId(), null));
        var newStatus = statusRepository.save(statusRow("ws heir", t.workspace().getId(), null));
        var doomedType = issueTypeRepository.save(typeRow("ws doomed", t.workspace().getId(), null));
        var newType = issueTypeRepository.save(typeRow("ws heir", t.workspace().getId(), null));
        var issue = issue(t, doomedStatus, doomedType, t.priority());

        remap(t.ownerToken(), base + "/statuses/" + doomedStatus.getId(), newStatus.getId());
        remap(t.ownerToken(), base + "/issue-types/" + doomedType.getId(), newType.getId());

        assertRemapped(issue, newStatus.getId(), newType.getId());
        assertGone(doomedStatus.getId(), doomedType.getId());
    }

    /**
     * Project scope: a project MANAGER holds {@code project.taxonomy.manage}, and the actor is
     * only a plain workspace MEMBER — so this also exercises the delegated shape, where
     * {@code ScopeContext.project} carries a null {@code workspaceId} and a replacement's
     * visibility is resolved through the project's ancestor workspace instead.
     */
    @Test
    void deleteWithRemapWorksAtProjectScope() throws Exception {
        var t = tenant();
        var manager = user();
        member(t.workspace(), manager, "MEMBER");
        projectMember(t.project(), manager, "MANAGER");
        var token = login(manager);
        var base = "/api/workspaces/" + t.workspace().getId()
                   + "/projects/" + t.project().getId() + "/admin";

        var doomedStatus = statusRepository.save(statusRow("proj doomed", null, t.project().getId()));
        var newStatus = statusRepository.save(statusRow("proj heir", null, t.project().getId()));
        var doomedType = issueTypeRepository.save(typeRow("proj doomed", null, t.project().getId()));
        var newType = issueTypeRepository.save(typeRow("proj heir", null, t.project().getId()));
        var issue = issue(t, doomedStatus, doomedType, t.priority());

        remap(token, base + "/statuses/" + doomedStatus.getId(), newStatus.getId());
        remap(token, base + "/issue-types/" + doomedType.getId(), newType.getId());

        assertRemapped(issue, newStatus.getId(), newType.getId());
        assertGone(doomedStatus.getId(), doomedType.getId());
    }

    // ===================================================================== AC-5

    /**
     * AC-5 — <strong>the pre-check still refuses first, before the database has to.</strong>
     *
     * <p>All three catalogs, because the three delete methods carry three separate copies of
     * this guard and nothing but a test makes them agree. Each refusal has to carry the two
     * things that make it actionable: <em>how many</em> issues are affected, and <em>what to
     * do</em> — remap or archive, both of which exist at every scope.
     *
     * <p>The assertions on the aftermath are the half that matters most now. A 409 that had
     * already deleted or partly remapped something would be a far worse regression than a wrong
     * status code, and before V19 nothing else in the system would have noticed.
     */
    @Test
    void deletingAnInUseEntryWithNoReplacementIsRefusedByThePreCheck() throws Exception {
        var t = tenant();
        var base = "/api/workspaces/" + t.workspace().getId() + "/admin";

        var inUseStatus = statusRepository.save(statusRow("ws in-use", t.workspace().getId(), null));
        var inUseType = issueTypeRepository.save(typeRow("ws in-use", t.workspace().getId(), null));
        var inUsePriority = priorityRepository.save(priorityRow("ws in-use", t.workspace().getId()));
        var issue = issue(t, inUseStatus, inUseType, inUsePriority);

        refused(t.ownerToken(), base + "/statuses/" + inUseStatus.getId(), "status");
        refused(t.ownerToken(), base + "/issue-types/" + inUseType.getId(), "issue type");
        refused(t.ownerToken(), base + "/priorities/" + inUsePriority.getId(), "priority");

        // Nothing deleted and nothing remapped: the refusal is total, not partial.
        var after = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(after.getStatus().getId()).isEqualTo(inUseStatus.getId());
        assertThat(after.getType().getId()).isEqualTo(inUseType.getId());
        assertThat(after.getPriority().getId()).isEqualTo(inUsePriority.getId());
        assertThat(statusRepository.findById(inUseStatus.getId())).isPresent();
        assertThat(issueTypeRepository.findById(inUseType.getId())).isPresent();
        assertThat(priorityRepository.findById(inUsePriority.getId())).isPresent();
    }

    /**
     * The case a reader is most likely to assume the test above covers, and it does not: an
     * entry <strong>nothing</strong> uses deletes with no replacement at all. Without this, a
     * guard that had degenerated into refusing every delete would satisfy AC-5 and break the
     * product.
     */
    @Test
    void deletingAnUnusedEntryNeedsNoReplacement() throws Exception {
        var t = tenant();
        var base = "/api/workspaces/" + t.workspace().getId() + "/admin";
        var unused = statusRepository.save(statusRow("ws unused", t.workspace().getId(), null));

        mockMvc.perform(delete(base + "/statuses/" + unused.getId())
                        .header("Authorization", "Bearer " + t.ownerToken()))
                .andExpect(status().isNoContent());

        assertThat(statusRepository.findById(unused.getId())).isEmpty();
    }

    // ================================================================ assertions

    private void remap(String token, String path, UUID replacement) throws Exception {
        mockMvc.perform(delete(path)
                        .param("replaceWithId", replacement.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    /**
     * The pre-check refusal, asserted on substance rather than on the exact sentence: the count
     * (so an administrator knows the size of what they are about to move) and the word
     * "archive" (the alternative the delete dialog offers, and the only remedy for someone who
     * does not want to remap at all).
     */
    private void refused(String token, String path, String noun) throws Exception {
        var body = mockMvc.perform(delete(path)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("the pre-check 409 for a %s must name HOW MANY issues are affected. The "
                    + "number is what tells an administrator whether they are moving one issue "
                    + "or ten thousand, and the database backstop structurally cannot supply it "
                    + "— by the time a 23503 fires the transaction is aborting and no further "
                    + "query can run inside it.", noun)
                .contains("1 issue");
        assertThat(body)
                .as("the refusal for a %s must name a remedy its reader can perform. Both exist "
                    + "at all three scopes: ?replaceWithId= on this same endpoint, and the "
                    + "/archive sibling, which keeps the row so history still renders.", noun)
                .contains("archive");
        assertThat(body)
                .as("this must be the PRE-CHECK refusing, not the database. If the body now "
                    + "carries REFERENCE_CONSTRAINT_VIOLATION, the guard stopped refusing and the request "
                    + "reached the constraint — catalog deletion would then be resting on a "
                    + "backstop that can name neither the count nor the entry.")
                .doesNotContain("REFERENCE_CONSTRAINT_VIOLATION");
    }

    private void assertRemapped(Issue issue, UUID statusId, UUID typeId) {
        var after = issueRepository.findById(issue.getId()).orElseThrow();
        assertThat(after.getStatus().getId())
                .as("the issue was not remapped onto the replacement status — before V19 it "
                    + "would now be stranded and nothing in the system would have said so")
                .isEqualTo(statusId);
        assertThat(after.getType().getId())
                .as("the issue was not remapped onto the replacement issue type")
                .isEqualTo(typeId);
    }

    private void assertGone(UUID statusId, UUID typeId) {
        assertThat(statusRepository.findById(statusId))
                .as("the status survived its own delete")
                .isEmpty();
        assertThat(issueTypeRepository.findById(typeId))
                .as("the issue type survived its own delete")
                .isEmpty();
    }

    // ================================================================ the fixture

    /** One workspace, one project, an owner who can drive the workspace console, one priority. */
    private record Tenant(Workspace workspace, Project project, User owner, String ownerToken,
                          Priority priority) {}

    private Tenant tenant() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, "OWNER");
        var project = project(ws, owner);
        return new Tenant(ws, project, owner, login(owner),
                priorityRepository.save(priorityRow("ws default", ws.getId())));
    }

    /**
     * One issue per freshly-created project, so {@code UNIQUE(project_id, number)} is satisfied
     * by a constant and the count in every AC-5 message is a predictable "1 issue".
     */
    private Issue issue(Tenant t, Status status, IssueType type, Priority priority) {
        var i = new Issue();
        i.setWorkspace(t.workspace());
        i.setProject(t.project());
        i.setNumber(1);
        i.setTitle("HD-13 remap fixture");
        i.setStatus(status);
        i.setType(type);
        i.setPriority(priority);
        i.setReporter(t.owner());
        return issueRepository.save(i);
    }

    private Status statusRow(String name, UUID workspaceId, UUID projectId) {
        var s = new Status();
        s.setName(unique(name));
        s.setCategory(StatusCategory.TODO);
        s.setScopeWorkspaceId(workspaceId);
        s.setScopeProjectId(projectId);
        return s;
    }

    private IssueType typeRow(String name, UUID workspaceId, UUID projectId) {
        var t = new IssueType();
        t.setName(unique(name));
        t.setScopeWorkspaceId(workspaceId);
        t.setScopeProjectId(projectId);
        return t;
    }

    private Priority priorityRow(String name, UUID workspaceId) {
        var p = new Priority();
        p.setName(unique(name));
        p.setScopeWorkspaceId(workspaceId);
        return p;
    }

    /**
     * Catalog names are unique per visible scope and the suite database is shared and reused —
     * and the GLOBAL rows this class creates are visible to every other test on the instance
     * until it deletes them again, so a fixed name would collide across runs as well as within
     * one.
     */
    private String unique(String name) {
        return name + " " + System.nanoTime();
    }

    private User user() {
        var u = new User();
        u.setEmail(("hd13r-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("HD-13 Remap User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    /** {@code /api/admin/**} is gated by {@code hasRole("ADMIN")} in {@code SecurityConfig}. */
    private String systemAdminToken() throws Exception {
        var u = user();
        u.setSystemRole(SystemRole.ADMIN);
        return login(userRepository.save(u));
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("HD-13 Remap WS");
        w.setSlug("hd13r-" + UUID.randomUUID().toString().substring(0, 8)
                  + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User user, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
    }

    private Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("HD-13 Remap Proj");
        p.setKey("R" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    private void projectMember(Project project, User user, String role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, role));
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
