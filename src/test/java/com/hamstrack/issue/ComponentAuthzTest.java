package com.hamstrack.issue;

import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.workspace.entity.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization matrix of {@code ScopeResolver.requireProjectCurator} — the authz
 * primitive HD-31 introduced and HD-32 will reuse (proposal §3.3, §5.6).
 *
 * <p>Why this class exists at all: the whole gate rests on the project's
 * <strong>inverted-ordinal</strong> {@code isAtLeast} convention (the privileged value
 * sits at ordinal 0, so {@code isAtLeast} compares {@code <=}). A refactor that
 * reorders {@link WorkspaceRole} or {@link ProjectRole}, or that "fixes" the comparison
 * to {@code >=}, silently inverts every check here — a plain MEMBER would curate and an
 * OWNER would be refused, with nothing failing to compile.
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
        assert names(listComponents(ctx, ctx.token(), null)).equals(java.util.List.of("billing"));
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
        var adminOfA = actorWith(a, WorkspaceRole.ADMIN, null);
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
        assert names(listComponents(b, b.token(), null)).equals(java.util.List.of("b-only"));
    }

    // ==================================================== 403 — inside, but not a curator

    @Test
    void aWorkspaceMemberWithNoProjectRoleIsForbiddenNotNotFound() throws Exception {
        var ctx = newProject();
        var componentId = createComponent(ctx, "billing");
        // Member of the workspace, member of NOTHING in the project: the caller already
        // knows the project exists (they can list it), so a role failure is honest 403.
        var plain = actorWith(ctx, WorkspaceRole.MEMBER, null);

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
        var member = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.MEMBER);
        var viewer = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.VIEWER);

        for (var actor : java.util.List.of(member, viewer)) {
            postComponent(ctx, actor.token(), "{\"name\":\"nope\"}").andExpect(status().isForbidden());
            patchComponent(ctx, actor.token(), componentId, "{\"name\":\"nope\"}")
                    .andExpect(status().isForbidden());
            archiveComponent(ctx, actor.token(), componentId).andExpect(status().isForbidden());
            deleteComponent(ctx, actor.token(), componentId, true).andExpect(status().isForbidden());
        }
        // …and none of the refusals mutated anything.
        assert names(listComponents(ctx, ctx.token(), null)).equals(java.util.List.of("billing"));
    }

    /** Reads are open to any project member — the 403 above is about WRITES only. */
    @Test
    void aPlainProjectMemberCanStillReadTheCatalog() throws Exception {
        var ctx = newProject();
        var componentId = createComponent(ctx, "billing");
        var viewer = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.VIEWER);
        var noProjectRole = actorWith(ctx, WorkspaceRole.MEMBER, null);

        for (var actor : java.util.List.of(viewer, noProjectRole)) {
            assert names(listComponents(ctx, actor.token(), null)).equals(java.util.List.of("billing"));
            getComponent(ctx, actor.token(), componentId).andExpect(status().isOk());
            componentUsage(ctx, actor.token(), componentId).andExpect(status().isOk());
        }
    }

    // ==================================================== 2xx — the curators

    @Test
    void aProjectManagerMayCurate() throws Exception {
        var ctx = newProject();
        var manager = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.MANAGER);

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
        var owner = actorWith(ctx, WorkspaceRole.OWNER, null);
        var admin = actorWith(ctx, WorkspaceRole.ADMIN, null);

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
     * The ordinal guard, stated as an assertion rather than a comment: OWNER/ADMIN are
     * "at least ADMIN" and MEMBER is not; MANAGER is "at least MANAGER" and
     * MEMBER/VIEWER are not. If the enums are reordered or the comparison flipped, this
     * fails immediately and points at the cause of the HTTP failures above.
     */
    @Test
    void theInvertedOrdinalConventionTheGateDependsOnHolds() {
        assert WorkspaceRole.OWNER.isAtLeast(WorkspaceRole.ADMIN);
        assert WorkspaceRole.ADMIN.isAtLeast(WorkspaceRole.ADMIN);
        assert !WorkspaceRole.MEMBER.isAtLeast(WorkspaceRole.ADMIN);
        assert ProjectRole.MANAGER.isAtLeast(ProjectRole.MANAGER);
        assert !ProjectRole.MEMBER.isAtLeast(ProjectRole.MANAGER);
        assert !ProjectRole.VIEWER.isAtLeast(ProjectRole.MANAGER);
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
