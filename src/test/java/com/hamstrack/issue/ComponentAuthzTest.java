package com.hamstrack.issue;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization matrix of what <em>used</em> to be
 * {@code ScopeResolver.requireProjectCurator} — the authz primitive HD-31 introduced and
 * HD-32 reused (proposal §3.3, §5.6). Since HD-125/HD-126 the predicate is
 * {@code component.manage}, held by the built-in Project admin directly and by the
 * built-in workspace Owner/Admin through {@code project.curate.all} (§17.2); the gate
 * changed, the matrix did not.
 *
 * <p>Why this class exists at all: the whole gate used to rest on the project's
 * <strong>inverted-ordinal</strong> {@code isAtLeast} convention, which a reorder could
 * invert silently. That convention is gone with the enums, and the equivalent silent
 * failure now lives in the built-in role seed — see
 * {@link #theBuiltInSeedTheMatrixAboveRestsOnHolds}, which is this class's version of the
 * same guard.
 *
 * <p>The matrix, asserted against real mutating component endpoints:
 * <table>
 *   <tr><th>actor</th><th>expected</th></tr>
 *   <tr><td>not a member of the workspace</td><td>404 (never 403 — no existence leak)</td></tr>
 *   <tr><td>workspace MEMBER, no project role</td><td>403</td></tr>
 *   <tr><td>project MEMBER / VIEWER</td><td>403</td></tr>
 *   <tr><td>project MANAGER</td><td>2xx</td></tr>
 *   <tr><td>workspace OWNER / ADMIN, not a project member</td><td>2xx (the deliberate bypass)</td></tr>
 *   <tr><td>workspace ADMIN of workspace A aiming at workspace B</td><td>404</td></tr>
 * </table>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ComponentAuthzTest extends ComponentTestBase {

    // ==================================================== 404 — outside the tenant

    @Test
    void aNonMemberOfTheWorkspaceGets404OnEveryComponentEndpointNever403() throws Exception {
        var ctx = newProject();
        var componentId = createComponent(ctx, "billing");
        var outsider = login(user());          // authenticates fine, member of nothing

        var base = componentsBase(ctx);
        expect404(get(base), outsider);                                                  // list
        expect404(post(base).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}"), outsider);                                 // create
        expect404(get(base + "/" + componentId), outsider);                              // read one
        expect404(patch(base + "/" + componentId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}"), outsider);                                 // update
        expect404(post(base + "/" + componentId + "/archive"), outsider);
        expect404(post(base + "/" + componentId + "/unarchive"), outsider);
        expect404(delete(base + "/" + componentId), outsider);
        expect404(get(base + "/" + componentId + "/usage"), outsider);

        // Nothing half-succeeded: the catalog is intact for its real curator.
        assertThat(names(listComponents(ctx, ctx.token(), null)))
                .as("nothing half-succeeded: the catalog is intact for its real curator")
                .isEqualTo(java.util.List.of("billing"));
    }

    /**
     * The deliberate bypass must not become a cross-tenant hole: a workspace ADMIN is
     * only an admin of <em>their own</em> workspace. Both addressing shapes 404 —
     * the honest path (workspace B) and the confused one (workspace A + B's project).
     */
    @Test
    void aWorkspaceAdminOfAnotherWorkspaceGets404NotAPassThroughTheBypass() throws Exception {
        var a = newProject();
        var b = newProject();
        // The actor is a full ADMIN — of A. Nothing about that may reach into B.
        var adminOfA = actorWith(a, "ADMIN", null);
        var bComponent = createComponent(b, "b-only");

        // (i) B's own path: not a member of B → 404 at requireMember.
        expect404(post("/api/workspaces/" + b.wsId() + "/projects/" + b.projectId() + "/components")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hijack\"}"), adminOfA.token());
        expect404(patch("/api/workspaces/" + b.wsId() + "/projects/" + b.projectId()
                + "/components/" + bComponent)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hijack\"}"), adminOfA.token());

        // (ii) A's workspace id + B's project id → the project isn't in A → 404.
        expect404(post("/api/workspaces/" + a.wsId() + "/projects/" + b.projectId() + "/components")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hijack\"}"), adminOfA.token());

        // B's catalog is untouched, under its original name.
        assertThat(names(listComponents(b, b.token(), null)))
                .as("B's catalog is untouched, under its original name")
                .isEqualTo(java.util.List.of("b-only"));
    }

    // ==================================================== 403 — inside, but not a curator

    @Test
    void aWorkspaceMemberWithNoProjectRoleIsForbiddenNotNotFound() throws Exception {
        var ctx = newProject();
        var componentId = createComponent(ctx, "billing");
        // Member of the workspace, member of NOTHING in the project: the caller already
        // knows the project exists (they can list it), so a role failure is honest 403.
        var plain = actorWith(ctx, "MEMBER", null);

        postComponent(ctx, plain.token(), "{\"name\":\"nope\"}").andExpect(status().isForbidden());
        patchComponent(ctx, plain.token(), componentId, "{\"name\":\"nope\"}")
                .andExpect(status().isForbidden());
        archiveComponent(ctx, plain.token(), componentId).andExpect(status().isForbidden());
        unarchiveComponent(ctx, plain.token(), componentId).andExpect(status().isForbidden());
        deleteComponent(ctx, plain.token(), componentId, false).andExpect(status().isForbidden());
    }

    @Test
    void projectMemberAndProjectViewerAreForbidden() throws Exception {
        var ctx = newProject();
        var componentId = createComponent(ctx, "billing");
        var member = actorWith(ctx, "MEMBER", "MEMBER");
        var viewer = actorWith(ctx, "MEMBER", "VIEWER");

        for (var actor : java.util.List.of(member, viewer)) {
            postComponent(ctx, actor.token(), "{\"name\":\"nope\"}").andExpect(status().isForbidden());
            patchComponent(ctx, actor.token(), componentId, "{\"name\":\"nope\"}")
                    .andExpect(status().isForbidden());
            archiveComponent(ctx, actor.token(), componentId).andExpect(status().isForbidden());
            deleteComponent(ctx, actor.token(), componentId, true).andExpect(status().isForbidden());
        }
        // …and none of the refusals mutated anything.
        assertThat(names(listComponents(ctx, ctx.token(), null)))
                .as("none of the refusals mutated anything")
                .isEqualTo(java.util.List.of("billing"));
    }

    /** Reads are open to any project member — the 403 above is about WRITES only. */
    @Test
    void aPlainProjectMemberCanStillReadTheCatalog() throws Exception {
        var ctx = newProject();
        var componentId = createComponent(ctx, "billing");
        var viewer = actorWith(ctx, "MEMBER", "VIEWER");
        var noProjectRole = actorWith(ctx, "MEMBER", null);

        for (var actor : java.util.List.of(viewer, noProjectRole)) {
            assertThat(names(listComponents(ctx, actor.token(), null)))
                    .as("a plain project member can still READ the catalog — the gate is on the writes")
                    .isEqualTo(java.util.List.of("billing"));
            getComponent(ctx, actor.token(), componentId).andExpect(status().isOk());
            componentUsage(ctx, actor.token(), componentId).andExpect(status().isOk());
        }
    }

    // ==================================================== 2xx — the curators

    @Test
    void aProjectManagerMayCurate() throws Exception {
        var ctx = newProject();
        var manager = actorWith(ctx, "MEMBER", "MANAGER");

        var id = createComponent(ctx, manager.token(), "{\"name\":\"ingest\"}");
        patchComponent(ctx, manager.token(), id, "{\"name\":\"ingest pipeline\"}")
                .andExpect(status().isOk());
        archiveComponent(ctx, manager.token(), id).andExpect(status().isOk());
        unarchiveComponent(ctx, manager.token(), id).andExpect(status().isOk());
        deleteComponent(ctx, manager.token(), id, false).andExpect(status().isNoContent());
    }

    /**
     * The bypass: a workspace OWNER/ADMIN curates a project they are not a member of.
     * Rationale (ScopeResolver javadoc): they already edit that project's bindings
     * through the admin API, so refusing them its component list would be arbitrary.
     */
    @Test
    void workspaceOwnerAndWorkspaceAdminCurateWithoutBeingProjectMembers() throws Exception {
        var ctx = newProject();
        var owner = actorWith(ctx, "OWNER", null);
        var admin = actorWith(ctx, "ADMIN", null);

        for (var actor : java.util.List.of(owner, admin)) {
            var id = createComponent(ctx, actor.token(),
                    "{\"name\":\"by-" + actor.user().getId().toString().substring(0, 8) + "\"}");
            patchComponent(ctx, actor.token(), id, "{\"description\":\"curated\"}")
                    .andExpect(status().isOk());
            archiveComponent(ctx, actor.token(), id).andExpect(status().isOk());
            unarchiveComponent(ctx, actor.token(), id).andExpect(status().isOk());
            deleteComponent(ctx, actor.token(), id, false).andExpect(status().isNoContent());
        }
    }

    /**
     * <strong>The seed guard that replaced the ordinal guard</strong> (HD-126, S3).
     *
     * <p>This test used to assert the inverted-ordinal convention directly
     * ({@code OWNER.isAtLeast(ADMIN)}, {@code !MEMBER.isAtLeast(MANAGER)}, …) because the
     * whole matrix above rested on it. Both role enums are deleted; the matrix now rests
     * on <em>which permissions the built-in roles are seeded with</em>, so that is what is
     * asserted, and for the same reason: a change here inverts every HTTP expectation
     * above with nothing failing to compile.
     *
     * <p>Note it asserts the <strong>whole</strong> curator set rather than
     * {@code component.manage} alone. The four permissions moved together as one
     * predicate ({@code requireProjectCurator}), and a seed that gave the workspace
     * Owner three of them would pass a narrower assertion while silently changing what an
     * Owner may do in projects they are not a member of.
     */
    @Test
    void theBuiltInSeedTheMatrixAboveRestsOnHolds() {
        var wsMember = roleCatalog.builtIn(RoleScope.WORKSPACE, "MEMBER").permissions();
        var projectAdmin = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").permissions();
        var contributor = roleCatalog.builtIn(RoleScope.PROJECT, "MEMBER").permissions();
        var viewer = roleCatalog.builtIn(RoleScope.PROJECT, "VIEWER").permissions();

        for (var role : java.util.List.of("OWNER", "ADMIN")) {
            var permissions = roleCatalog.builtIn(RoleScope.WORKSPACE, role).permissions();
            assertThat(permissions.has(Permission.PROJECT_CURATE_ALL))
                    .withFailMessage("the built-in workspace " + role + " lost project.curate.all — that IS the "
                      + "workspace-admin bypass the 2xx rows above assert.")
                    .isTrue();
        }
        assertThat(wsMember.has(Permission.PROJECT_CURATE_ALL))
                .withFailMessage("the built-in workspace Member gained the curator bypass, so the 403 rows above "
                  + "are now testing nothing.")
                .isFalse();
        for (var p : Permission.projectCuration()) {
            assertThat(projectAdmin.has(p)).withFailMessage(() -> "the built-in Project admin lost " + p.key()).isTrue();
            assertThat(contributor.has(p)).withFailMessage(() -> "the built-in Contributor gained " + p.key()).isFalse();
            assertThat(viewer.has(p)).withFailMessage(() -> "the built-in Viewer gained " + p.key()).isFalse();
        }
    }

    // ==================================================== helpers

    /** 404 and specifically NOT 403 — a 403 would confirm the resource exists. */
    private void expect404(MockHttpServletRequestBuilder req, String token) throws Exception {
        mockMvc.perform(req.header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(status().is(not(403)));
    }

    /** A never-existed id must be indistinguishable from a foreign one for an outsider. */
    @Test
    void aGhostComponentIdIsAlsoJust404ForAnOutsider() throws Exception {
        var ctx = newProject();
        var outsider = login(user());
        expect404(get(componentsBase(ctx) + "/" + UUID.randomUUID()), outsider);
    }
}
