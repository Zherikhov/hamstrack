package com.hamstrack.workspace;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.ComponentTestBase;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import com.hamstrack.workspace.entity.WorkspaceMember;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-126 (S3) — the workspace-scoped permissions actually bite</strong>
 * (roles-permissions-proposal §10.1, §10.4, §12).
 *
 * <p>{@code BuiltInRoleSeedParityTest} proves the built-in seed reproduces the old
 * verdicts; it cannot prove any of them is <em>wired to an endpoint</em>, because it never
 * makes a request. This does, for the gates S3 converted:
 * {@code label.create}, {@code label.manage} (both arities), {@code project.create},
 * {@code workspace.taxonomy.manage} and {@code project.taxonomy.manage}.
 *
 * <p>Membership administration ({@code workspace.member.manage} and the §11.2 grant
 * ceiling) lives in {@link WorkspaceMemberManagementTest} instead — deliberately, so the
 * last-Owner and ceiling rules keep being tested in one place rather than two that can
 * disagree.
 *
 * <p><strong>Tenancy is asserted next to every 403</strong>, because the pair is the
 * property that matters: a member without a permission gets 403, and a non-member gets 404
 * for the same request. Testing either alone is how a 403 that leaks existence gets shipped.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class WorkspacePermissionEnforcementTest extends ComponentTestBase {

    // ==================================================== label.create / label.manage

    /**
     * The pair §10.1 turns on: one catalog key, two arities. The built-in workspace Member
     * holds {@code label.manage} <em>own-only</em>, which is exactly the creator
     * short-circuit {@code LabelService.requireEditor} used to have — so a member may still
     * rename a label they made, and still may not archive it or touch anyone else's.
     */
    @Test
    void aMemberEditsTheirOwnLabelAndCuratesNothing() throws Exception {
        var ctx = newProject();
        var member = addMember(ctx, "MEMBER");
        var mine = newLabel(ctx, member.token(), "mine");
        var theirs = newLabel(ctx, ctx.token(), "theirs");

        // label.manage:own — rename my own
        patchLabel(ctx, member.token(), mine, "{\"name\":\"mine-renamed\"}")
                .andExpect(status().isOk());
        // …but not somebody else's: the own-only grant does not cover it
        patchLabel(ctx, member.token(), theirs, "{\"name\":\"hijacked\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("label.manage")));
        // …and curation asks for the UNRESTRICTED grant with no ownership argument, so an
        // own-only holder is refused even on the label they created. This is the one way
        // to get the pair wrong, and it is why it is asserted on the actor's OWN label.
        archiveLabel(ctx, member.token(), mine)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("label.manage")));
        deleteLabel(ctx, member.token(), mine, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("label.manage")));

        // The workspace OWNER holds it unrestricted.
        archiveLabel(ctx, ctx.token(), mine).andExpect(status().isOk());
    }

    /**
     * {@code label.create} was "any workspace member" and still is for the built-ins — the
     * point of making it a catalog entry is that a workspace can now say otherwise. A
     * custom role that grants nothing proves the gate exists rather than merely being
     * seeded permissively.
     */
    @Test
    void aWorkspaceRoleWithoutLabelCreateCannotCreateLabels() throws Exception {
        var ctx = newProject();
        var member = addMember(ctx, "MEMBER");
        postLabel(ctx, member.token(), "{\"name\":\"fine\"}").andExpect(status().isCreated());

        var nobodyToken = actorWithCustomWorkspaceRole(ctx, "ws-nothing");
        postLabel(ctx, nobodyToken, "{\"name\":\"nope\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("label.create")));
    }

    // ==================================================== project.create

    @Test
    void aWorkspaceRoleWithoutProjectCreateCannotCreateProjects() throws Exception {
        var ctx = newProject();
        var member = addMember(ctx, "MEMBER");
        createProject(ctx, member.token(), "OK").andExpect(status().isCreated());

        var nobodyToken = actorWithCustomWorkspaceRole(ctx, "ws-nothing-p");
        createProject(ctx, nobodyToken, "NO")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.create")));
    }

    // ==================================================== workspace.taxonomy.manage

    /**
     * The workspace-admin console. Δ-free against the deleted
     * {@code ScopeResolver.requireWorkspaceAdmin}: Owner/Admin hold the permission and
     * Member does not — and the tenancy half is unchanged, a stranger still gets 404.
     */
    @Test
    void theWorkspaceAdminConsoleIs403ForAMemberAnd404ForAStranger() throws Exception {
        var ctx = newProject();
        var member = addMember(ctx, "MEMBER");
        var admin = addMember(ctx, "ADMIN");
        var strangerToken = login(user());

        mockMvc.perform(get(workspaceAdmin(ctx) + "/statuses")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("workspace.taxonomy.manage")));
        mockMvc.perform(get(workspaceAdmin(ctx) + "/statuses")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk());
        // A non-member must not be able to tell the workspace apart from one that does not
        // exist — 404 both ways, never the 403 above.
        mockMvc.perform(get(workspaceAdmin(ctx) + "/statuses")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/workspaces/" + UUID.randomUUID() + "/admin/statuses")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    // ==================================================== project.taxonomy.manage — the flip

    /**
     * <strong>The one deliberate 404 → 403 change of this epic</strong> (§10.3.2, §12.2).
     *
     * <p>{@code ScopeResolver.requireProjectAdmin} answered <em>404</em> to a workspace
     * member who was not a project member, while {@code requireProjectCurator} answered
     * 403 to the same person for the same failure — two answers for one shape of failure,
     * and the only place in the codebase where a permission failure wore existence's
     * clothes. It is 403 now, everywhere.
     *
     * <p>Safe precisely because the caller can already see the project: it is in their
     * {@code GET /projects} list, which this test asserts in the same breath so the
     * justification cannot quietly stop being true. A non-member of the <em>workspace</em>
     * still gets 404, and always must.
     */
    @Test
    void theProjectAdminConsole403sAWorkspaceMemberWhoIsNotAProjectMember() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", null);
        var strangerToken = login(user());

        // The premise of the flip: the project is already listed to them.
        var projects = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(projects)
                .as("the 403 below is only defensible while the project is listed to this caller")
                .contains(ctx.projectId().toString());

        mockMvc.perform(get(projectAdmin(ctx) + "/bindings")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.taxonomy.manage")));

        // A workspace OWNER with no project row is refused too — project.taxonomy.manage
        // is NOT in the curator bypass (§17.2), which is exactly what requireProjectAdmin
        // did. Only the status code moved.
        var owner = actorWith(ctx, "OWNER", null);
        mockMvc.perform(get(projectAdmin(ctx) + "/bindings")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isForbidden());

        // The project administrator gets through.
        mockMvc.perform(get(projectAdmin(ctx) + "/bindings")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());

        // …and the tenancy rule is untouched: not a member of the workspace → 404.
        mockMvc.perform(get(projectAdmin(ctx) + "/bindings")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    /**
     * <strong>The flip is a property of the whole {@code /projects/{p}/admin/**} surface, not
     * of {@code /bindings}</strong> — and the surface has <em>two</em> authorization paths, not
     * one: the three binding endpoints authorize inside
     * {@code ScopedProjectAdminService.requireProjectTaxonomyAdmin}, while the other forty-odd
     * (statuses, priorities, issue types, workflows, sets, fields) authorize in
     * {@code ProjectAdminController.scope}. The test above only exercises the first. Two
     * copies of one rule is exactly how half a console keeps answering 404 — or worse, keeps
     * answering 200.
     *
     * <p>Asserted on reads and on a write, because a write reaches the gate only after
     * {@code @Valid} has run, so a controller that authorized late would fail differently.
     */
    @Test
    void everyProjectAdminEndpointFlipsTogetherAndStill404sAStranger() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", null);
        var strangerToken = login(user());

        // The controller-authorized half (a sample across every sub-console it serves).
        for (var path : List.of("/statuses", "/priorities", "/issue-types", "/workflows",
                "/priority-sets", "/issue-type-sets", "/fields", "/field-sets")) {
            mockMvc.perform(get(projectAdmin(ctx) + path)
                            .header("Authorization", "Bearer " + member.token()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail", containsString("project.taxonomy.manage")));
            // …and a non-member of the WORKSPACE is still told nothing at all.
            mockMvc.perform(get(projectAdmin(ctx) + path)
                            .header("Authorization", "Bearer " + strangerToken))
                    .andExpect(status().isNotFound());
            // ANCHOR: the endpoint exists and answers the project administrator.
            mockMvc.perform(get(projectAdmin(ctx) + path)
                            .header("Authorization", "Bearer " + ctx.token()))
                    .andExpect(status().isOk());
        }

        // The service-authorized half, beyond the GET the test above covers.
        mockMvc.perform(get(projectAdmin(ctx) + "/binding-options")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.taxonomy.manage")));

        // Writes: a VALID body, so validation cannot be what refuses.
        mockMvc.perform(post(projectAdmin(ctx) + "/statuses")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"category\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.taxonomy.manage")));
        mockMvc.perform(patch(projectAdmin(ctx) + "/bindings")
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.taxonomy.manage")));
        // The same two writes from a stranger stay 404 — a write must not leak what a read
        // will not.
        mockMvc.perform(post(projectAdmin(ctx) + "/statuses")
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked\",\"category\":\"IN_PROGRESS\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(projectAdmin(ctx) + "/bindings")
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    // ==================================================== helpers

    private String workspaceAdmin(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/admin";
    }

    private String projectAdmin(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/admin";
    }

    private ResultActions createProject(Ctx ctx, String token, String key) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Made by " + key + "\",\"key\":\"K"
                        + (Math.abs(UUID.randomUUID().hashCode()) % 100000) + "\"}"));
    }

    private UUID newLabel(Ctx ctx, String token, String name) throws Exception {
        var resp = postLabel(ctx, token, "{\"name\":" + json.writeValueAsString(name) + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(resp).get("id").asText());
    }

    /**
     * A workspace member holding a <strong>custom workspace role that grants nothing</strong>.
     * The fixture for "is this gate real, or merely seeded permissively?" — no built-in
     * workspace role is without {@code project.create} and {@code label.create}, so nothing
     * else can tell the two apart. Written through the {@link jakarta.persistence.EntityManager}
     * because {@code RoleRepository} deliberately exposes no {@code save} until S4.
     */
    private String actorWithCustomWorkspaceRole(Ctx ctx, String key, Permission... permissions)
            throws Exception {
        var roleId = txTemplate.execute(status -> {
            var role = new Role();
            role.setWorkspaceId(ctx.wsId());
            role.setScope(RoleScope.WORKSPACE);
            role.setKey(key);
            role.setName(key);
            role.setBuiltIn(false);
            var grants = new LinkedHashSet<RolePermission>();
            for (var p : permissions) grants.add(new RolePermission(p, false));
            role.setPermissions(grants);
            entityManager.persist(role);
            entityManager.flush();
            return role.getId();
        });
        var u = user();
        var m = new WorkspaceMember();
        m.setWorkspace(workspaceRepository.findById(ctx.wsId()).orElseThrow());
        m.setUser(u);
        m.setRole(roleCatalog.reference(roleId));
        workspaceMemberRepository.save(m);
        return login(u);
    }
}
