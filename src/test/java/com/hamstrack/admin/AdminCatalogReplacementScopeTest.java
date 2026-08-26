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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-13 / AC-9 — <strong>{@code ?replaceWithId=} may only name a catalog row the caller can
 * see.</strong>
 *
 * <p>The hole this pins closed: {@code AdminCatalogService.deleteStatus} /
 * {@code deleteIssueType} / {@code deletePriority} resolved the replacement with a bare
 * {@code findById} — unscoped, every tenant — while every sibling admin service resolves a
 * catalog child through {@code findByIdVisibleTo}. A workspace admin could therefore name
 * <em>another tenant's</em> project-scoped row as the replacement and have the bulk remap
 * ({@code remapStatus}/{@code remapType}/{@code remapPriority}, themselves unscoped by
 * design) repoint their own issues at it.
 *
 * <p><strong>The foreign key added by V19 does not close this and cannot.</strong> That is
 * the single most important thing to understand before "simplifying" either half: a
 * single-column FK is satisfied by the parent row merely existing, wherever it lives.
 * {@code issues_priority_id_fkey} has been in the schema since V1 and never once prevented a
 * cross-tenant priority reference — which is why the priority variant is tested here beside
 * the two new ones rather than treated as out of scope.
 *
 * <p><strong>404, not 403 and not 422.</strong> A row the caller cannot see must be
 * indistinguishable from a row that does not exist; answering anything else turns the
 * parameter into an existence oracle for other tenants' catalog ids.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AdminCatalogReplacementScopeTest {

    @Autowired MockMvc mockMvc;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired StatusRepository statusRepository;
    @Autowired IssueTypeRepository issueTypeRepository;
    @Autowired PriorityRepository priorityRepository;
    @Autowired IssueRepository issueRepository;
    @Autowired PasswordEncoder passwordEncoder;

    /**
     * The status branch, and deliberately the <em>reachable</em> one.
     *
     * <p>{@code deleteStatus} has a loop requiring the replacement to be a member of every
     * workflow that contains the deleted status, which happens to reject a foreign row — but
     * only when the deleted status is in at least one workflow. A status that issues still
     * carry after it was removed from the workflow is in <strong>zero</strong> workflows (a
     * state the codebase permits: {@code ProjectConfigService} restricts new writes, not
     * existing rows), the loop body never executes, and the foreign replacement went straight
     * through. So the fixture creates a workspace-scoped status belonging to no workflow at
     * all — otherwise this test would pass on the accident rather than on the fix.
     */
    @Test
    void aForeignProjectScopedStatusIsNotAcceptableAsAReplacement() throws Exception {
        var f = twoTenants();

        // Workspace A's own status, in ZERO workflows, carried by a real issue.
        var ownStatus = statusRepository.save(scopedStatus("A own status", f.wsA().getId()));
        var issue = issueUsing(f, ownStatus, f.typeA(), f.priorityA());

        // Workspace B's PROJECT-scoped status — invisible to workspace A by every rule.
        var foreign = statusRepository.save(projectStatus("B private status", f.projectB().getId()));

        mockMvc.perform(delete("/api/workspaces/" + f.wsA().getId() + "/admin/statuses/" + ownStatus.getId())
                        .param("replaceWithId", foreign.getId().toString())
                        .header("Authorization", "Bearer " + f.tokenA()))
                .andExpect(status().isNotFound());

        assertNotRemapped(issue, ownStatus.getId(), "status");
        assert statusRepository.findById(ownStatus.getId()).isPresent()
                : "the status was deleted even though the replacement was refused — the delete "
                  + "must not be partially applied";
    }

    @Test
    void aForeignProjectScopedIssueTypeIsNotAcceptableAsAReplacement() throws Exception {
        var f = twoTenants();

        var ownType = issueTypeRepository.save(scopedType("A own type", f.wsA().getId()));
        var issue = issueUsing(f, f.statusA(), ownType, f.priorityA());

        var foreign = issueTypeRepository.save(projectType("B private type", f.projectB().getId()));

        mockMvc.perform(delete("/api/workspaces/" + f.wsA().getId() + "/admin/issue-types/" + ownType.getId())
                        .param("replaceWithId", foreign.getId().toString())
                        .header("Authorization", "Bearer " + f.tokenA()))
                .andExpect(status().isNotFound());

        assertNotRemapped(issue, ownType.getId(), "type");
        assert issueTypeRepository.findById(ownType.getId()).isPresent()
                : "the issue type was deleted even though the replacement was refused";
    }

    /**
     * Priorities carry no workflow-membership loop and never had any check at all, so this is
     * the variant that was fully reachable for the longest — and, because
     * {@code issues_priority_id_fkey} predates every other constraint on this table, the
     * empirical proof that a foreign key does not carry tenancy.
     */
    @Test
    void aForeignProjectScopedPriorityIsNotAcceptableAsAReplacement() throws Exception {
        var f = twoTenants();

        var ownPriority = priorityRepository.save(scopedPriority("A own priority", f.wsA().getId()));
        var issue = issueUsing(f, f.statusA(), f.typeA(), ownPriority);

        var foreign = priorityRepository.save(projectPriority("B private priority", f.projectB().getId()));

        mockMvc.perform(delete("/api/workspaces/" + f.wsA().getId() + "/admin/priorities/" + ownPriority.getId())
                        .param("replaceWithId", foreign.getId().toString())
                        .header("Authorization", "Bearer " + f.tokenA()))
                .andExpect(status().isNotFound());

        assertNotRemapped(issue, ownPriority.getId(), "priority");
        assert priorityRepository.findById(ownPriority.getId()).isPresent()
                : "the priority was deleted even though the replacement was refused";
    }

    /**
     * <strong>The narrowing at GLOBAL scope, which nothing else pins.</strong>
     *
     * <p>A side effect of the same three-line fix, accepted on its merits and easy to revert by
     * accident. {@code ScopeContext.global()} has both visibility ids null, and
     * {@code findByIdVisibleTo}'s remaining disjuncts are {@code scopeWorkspaceId = :ws} and
     * {@code scopeProjectId = :proj} — equality against NULL is UNKNOWN in SQL, so at global
     * scope the lookup resolves <strong>global rows only</strong>. A system admin can therefore
     * no longer name a workspace- or project-scoped row as the replacement for a global one.
     *
     * <p>That is stricter than the cross-tenant case this ticket set out to close, and more
     * important: a global status is used by issues in <em>every</em> workspace, so remapping it
     * onto one workspace's private row would repoint every tenant's issues into a row most of
     * them cannot see. It matches the four sibling admin services, which already resolve catalog
     * children this way at every scope, and it matches the SPA — the system console's list is
     * {@code findAllAtScope(null, null)}, so the picker never offered a scoped row here anyway.
     *
     * <p>Without this test a future reader "simplifies" the null handling and nothing fires.
     */
    @Test
    void aWorkspaceScopedRowIsNotAcceptableAsAReplacementForAGlobalOne() throws Exception {
        var f = twoTenants();
        var adminToken = systemAdminToken();

        var globalStatus = statusRepository.save(globalStatus("global doomed"));
        var issue = issueUsing(f, globalStatus, f.typeA(), f.priorityA());
        var workspaceScoped = statusRepository.save(scopedStatus("A scoped heir", f.wsA().getId()));

        mockMvc.perform(delete("/api/admin/statuses/" + globalStatus.getId())
                        .param("replaceWithId", workspaceScoped.getId().toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        assertNotRemapped(issue, globalStatus.getId(), "status");
        assert statusRepository.findById(globalStatus.getId()).isPresent()
                : "the global status was deleted even though the replacement was refused";

        // Cleanup: this row is GLOBAL, so leaving it behind would be visible to every other test
        // on the instance. The delete above was refused in full, so it is still here.
        issueRepository.delete(issue);
        statusRepository.delete(globalStatus);
    }

    /**
     * The other half of the fix, and the reason it is a scoping change rather than a ban: a
     * replacement the caller CAN see still works. Without this, narrowing the lookup to
     * nothing at all would also pass the three tests above, and every legitimate
     * delete-with-remap in the product would 404.
     */
    @Test
    void aVisibleReplacementStillRemapsAndDeletes() throws Exception {
        var f = twoTenants();

        var ownStatus = statusRepository.save(scopedStatus("A doomed status", f.wsA().getId()));
        var replacement = statusRepository.save(scopedStatus("A surviving status", f.wsA().getId()));
        var issue = issueUsing(f, ownStatus, f.typeA(), f.priorityA());

        mockMvc.perform(delete("/api/workspaces/" + f.wsA().getId() + "/admin/statuses/" + ownStatus.getId())
                        .param("replaceWithId", replacement.getId().toString())
                        .header("Authorization", "Bearer " + f.tokenA()))
                .andExpect(status().isNoContent());

        var after = issueRepository.findById(issue.getId()).orElseThrow();
        assert after.getStatus().getId().equals(replacement.getId())
                : "the issue was not remapped onto the replacement";
        assert statusRepository.findById(ownStatus.getId()).isEmpty()
                : "the deleted status is still present";
    }

    // ================================================================ assertions

    private void assertNotRemapped(Issue issue, UUID expected, String what) {
        var after = issueRepository.findById(issue.getId()).orElseThrow();
        UUID actual = switch (what) {
            case "status" -> after.getStatus().getId();
            case "type" -> after.getType().getId();
            case "priority" -> after.getPriority().getId();
            default -> throw new IllegalArgumentException(what);
        };
        assert expected.equals(actual)
                : "the issue's " + what + " was remapped to " + actual + " even though the "
                  + "replacement was refused. A refused request must not have written anything: "
                  + "the whole delete runs in one transaction, so a 404 that still remapped "
                  + "would mean the rollback did not happen.";
    }

    // ================================================================ the fixture

    /**
     * Two tenants that share nothing: workspace A (whose admin makes every call here) and
     * workspace B, which owns a project holding the project-scoped rows A must not be able to
     * name. A's own issues need a valid status/type/priority of their own, so A gets a
     * workspace-scoped one of each as a default.
     */
    private record Tenants(Workspace wsA, Project projectA, User adminA, String tokenA,
                           Status statusA, IssueType typeA, Priority priorityA,
                           Workspace wsB, Project projectB) {}

    private Tenants twoTenants() throws Exception {
        var adminA = user();
        var wsA = workspace(adminA);
        member(wsA, adminA, "OWNER");
        var projectA = project(wsA, adminA);

        var ownerB = user();
        var wsB = workspace(ownerB);
        member(wsB, ownerB, "OWNER");
        var projectB = project(wsB, ownerB);

        return new Tenants(wsA, projectA, adminA, login(adminA),
                statusRepository.save(scopedStatus("A default status", wsA.getId())),
                issueTypeRepository.save(scopedType("A default type", wsA.getId())),
                priorityRepository.save(scopedPriority("A default priority", wsA.getId())),
                wsB, projectB);
    }

    private Issue issueUsing(Tenants f, Status status, IssueType type, Priority priority) {
        var i = new Issue();
        i.setWorkspace(f.wsA());
        i.setProject(f.projectA());
        i.setNumber(1); // one issue per freshly-created project; UNIQUE(project_id, number)
        i.setTitle("HD-13 fixture issue");
        i.setStatus(status);
        i.setType(type);
        i.setPriority(priority);
        i.setReporter(f.adminA());
        return issueRepository.save(i);
    }

    private Status scopedStatus(String name, UUID workspaceId) {
        var s = new Status();
        s.setName(unique(name));
        s.setCategory(StatusCategory.TODO);
        s.setScopeWorkspaceId(workspaceId);
        return s;
    }

    /** Scope stamped nowhere: the shared catalog every tenant inherits. */
    private Status globalStatus(String name) {
        var s = new Status();
        s.setName(unique(name));
        s.setCategory(StatusCategory.TODO);
        return s;
    }

    /** {@code /api/admin/**} is gated by {@code hasRole("ADMIN")} in {@code SecurityConfig}. */
    private String systemAdminToken() throws Exception {
        var u = user();
        u.setSystemRole(com.hamstrack.auth.entity.SystemRole.ADMIN);
        return login(userRepository.save(u));
    }

    private Status projectStatus(String name, UUID projectId) {
        var s = new Status();
        s.setName(unique(name));
        s.setCategory(StatusCategory.TODO);
        s.setScopeProjectId(projectId);
        return s;
    }

    private IssueType scopedType(String name, UUID workspaceId) {
        var t = new IssueType();
        t.setName(unique(name));
        t.setScopeWorkspaceId(workspaceId);
        return t;
    }

    private IssueType projectType(String name, UUID projectId) {
        var t = new IssueType();
        t.setName(unique(name));
        t.setScopeProjectId(projectId);
        return t;
    }

    private Priority scopedPriority(String name, UUID workspaceId) {
        var p = new Priority();
        p.setName(unique(name));
        p.setScopeWorkspaceId(workspaceId);
        return p;
    }

    private Priority projectPriority(String name, UUID projectId) {
        var p = new Priority();
        p.setName(unique(name));
        p.setScopeProjectId(projectId);
        return p;
    }

    /** Catalog names are unique per visible scope; the suite database is shared and reused. */
    private String unique(String name) {
        return name + " " + (System.nanoTime() % 1_000_000);
    }

    private User user() {
        var u = new User();
        u.setEmail(("hd13-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("HD-13 Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("HD-13 WS");
        w.setSlug("hd13-" + UUID.randomUUID().toString().substring(0, 8)
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
        p.setName("HD-13 Proj");
        p.setKey("H" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
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
