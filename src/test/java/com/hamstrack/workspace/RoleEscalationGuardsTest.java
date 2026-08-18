package com.hamstrack.workspace;

import com.hamstrack.common.security.Permission;
import com.hamstrack.issue.SprintTestBase;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-127 (S4), <strong>security review round 2</strong> — the two privilege-escalation
 * routes the first build left open, plus the stale-cache precondition that could switch a
 * third guard off.
 *
 * <p>All three share one shape: <em>a ceiling is evaluated per assignment, and none of these
 * is an assignment.</em> A bulk reassign, a role edit and a role's own grants read from a
 * cache are each a way to change what a set of people may do without ever going through the
 * door that checks whether the actor may hand that out.
 *
 * <ul>
 *   <li><strong>Critical</strong> — {@code DELETE /roles/{id}?reassignToRoleId=} applied no
 *       ceiling at all to the target. {@code SELF_HELD_ROLE} closed the half pointed at the
 *       actor; the half pointed at everybody else was a four-call route from workspace Admin
 *       to workspace Owner, and a <em>one</em>-call route to Project admin in every project
 *       of the workspace. Both chains are reproduced below verbatim.</li>
 *   <li><strong>High</strong> — §11.3 drops the definition ceiling at PROJECT scope so that
 *       an Admin can duplicate Contributor (the product's primary recipe). The consequence:
 *       editing a role is a grant to every existing holder, evaluated against nobody —
 *       including the actor, when they are one of them.</li>
 *   <li><strong>Medium</strong> — the doors-4/5 stranding guard read its own precondition
 *       ("does this role currently grant {@code project.member.manage}?") from the 10 s
 *       {@code roleView} cache, so on a node that had not served the previous edit it could
 *       skip itself entirely.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class RoleEscalationGuardsTest extends SprintTestBase {

    // ============================================================ CRITICAL — the reassign target

    /**
     * <strong>The workspace chain, verbatim: Admin → Owner in four calls.</strong>
     *
     * <p>Duplicate built-in Member into a custom role (the <em>definition</em> ceiling passes
     * — Member's grants are a subset of Admin's) · invite a controlled address onto it (the
     * <em>assignment</em> ceiling passes, and the invite path's Owner refusal does not fire
     * because the granted role is not the built-in Owner) · delete the custom role reassigning
     * to built-in Owner (not self-held; in use, but a target is present; the stranding
     * aggregate returns early at WORKSPACE scope) · the pending invite's {@code role_id} is
     * now built-in Owner, and {@code WorkspaceService.acceptInvite} writes it onto the
     * membership with no re-validation.
     *
     * <p>Round 1 answered <strong>204</strong> here. The third call must be a 403.
     */
    @Test
    void aWorkspaceAdminCannotBulkReassignAnyoneOntoTheBuiltInOwnerRole() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);

        var custom = customRole(ctx, admin.token(), BuiltInRoles.WORKSPACE_MEMBER, "Contractor");
        invite(ctx, admin.token(), custom).andExpect(status().isCreated());

        deleteRole(ctx, admin.token(), custom, BuiltInRoles.WORKSPACE_OWNER)
                .andExpect(status().isForbidden())
                // Owner and Admin are seeded with IDENTICAL permission sets on purpose, so
                // set containment alone says yes here — this is the guardrail sets cannot
                // express, inherited for free by reusing the membership ceiling rather than
                // re-deriving one.
                .andExpect(jsonPath("$.detail", containsString("Only an Owner")));

        // Nothing moved: the role still exists and the invite still names it.
        assertThat(role(ctx, admin.token(), custom).get("id").asText())
                .isEqualTo(custom.toString());
        assertThat(usage(ctx, ctx.token(), custom).get("invites").asLong()).isEqualTo(1);
    }

    /**
     * …and an Owner, the root of trust inside their own workspace, still may — for
     * <em>members</em>. Promoting somebody who has already joined is a handover, which is
     * the door ownership is supposed to travel through.
     */
    @Test
    void aWorkspaceOwnerMayStillBulkReassignMembersOntoTheBuiltInOwnerRole() throws Exception {
        var ctx = newProject();
        var custom = customRole(ctx, ctx.token(), BuiltInRoles.WORKSPACE_MEMBER, "Contractor");
        var holder = actorWith(ctx, "MEMBER", null);
        patchWorkspaceMember(ctx, ctx.token(), holder.user().getId(), custom)
                .andExpect(status().isOk());

        deleteRole(ctx, ctx.token(), custom, BuiltInRoles.WORKSPACE_OWNER)
                .andExpect(status().isNoContent());
    }

    /**
     * <strong>…but not for a pending INVITE, and not even for an Owner</strong> (round-3
     * review). {@code WorkspaceService.inviteMember} refuses to grant the built-in Owner role
     * by invitation in as many words — "you promote a colleague to owner, you do not invite a
     * stranger as one" — and the reassign path returned before that clause could fire,
     * because the Owner exemption in the grant ceiling comes first. So an Owner deleting a
     * custom role with an invite on it repointed that invite at Owner, and the version of
     * this test above asserted it as <em>desired behaviour</em>: the invariant was broken by
     * a test rather than by accident.
     *
     * <p>The impact was bounded — the actor could promote the invitee a moment after they
     * accept — but a call-site rule with a hole is not a rule, and the refusal is satisfiable
     * in one move, which the second half of this test performs.
     */
    @Test
    void aReassignOntoBuiltInOwnerIsRefusedWhileTheRoleCarriesAPendingInvite() throws Exception {
        var ctx = newProject();
        var custom = customRole(ctx, ctx.token(), BuiltInRoles.WORKSPACE_MEMBER, "Contractor");
        invite(ctx, ctx.token(), custom).andExpect(status().isCreated());

        deleteRole(ctx, ctx.token(), custom, BuiltInRoles.WORKSPACE_OWNER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail",
                        containsString("an invitation can never grant the Owner role")));

        // Nothing moved, and the way out is any other target.
        assertThat(usage(ctx, ctx.token(), custom).get("invites").asLong()).isEqualTo(1);
        deleteRole(ctx, ctx.token(), custom, BuiltInRoles.WORKSPACE_MEMBER)
                .andExpect(status().isNoContent());
    }

    /**
     * <strong>The project chain, verbatim: one call, and the actor needs
     * {@code project.member.manage} nowhere.</strong>
     *
     * <p>Reassigning a narrow custom PROJECT role to the built-in Project admin promotes every
     * holder, in every project of the workspace, to all twenty project permissions — bypassing
     * the project ceiling (which is per assignment), the §4 escape's {@code target != actor}
     * constraint, and HD-136's safety case for {@code adoptAll} at the same time. The rule is
     * therefore about the <em>operation</em>: narrow or preserve, never widen.
     */
    @Test
    void aBulkReassignMayNotWidenAtProjectScope() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        // Contributor is strictly narrower than Project admin — and an Admin may duplicate it,
        // because §11.3 deliberately applies no definition ceiling at PROJECT scope.
        var custom = customRole(ctx, admin.token(), BuiltInRoles.PROJECT_MEMBER, "QA");

        deleteRole(ctx, admin.token(), custom, BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("no wider than the role being deleted")));

        // The workspace Owner is exempt, as everywhere else.
        deleteRole(ctx, ctx.token(), custom, BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isNoContent());
    }

    /**
     * The rule is "may not widen", not "may not reassign": a target covered by the role being
     * deleted goes through for an ordinary Admin, which is what keeps the remedy on the
     * {@code LAST_PROJECT_ADMIN_BULK} refusal satisfiable by the person being refused.
     */
    @Test
    void aBulkReassignThatNarrowsOrPreservesIsAllowedForANonOwner() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);

        // Narrowing: Project admin copy -> built-in Contributor.
        var wide = customRole(ctx, admin.token(), BuiltInRoles.PROJECT_MANAGER, "Wide");
        deleteRole(ctx, admin.token(), wide, BuiltInRoles.PROJECT_MEMBER)
                .andExpect(status().isNoContent());

        // Preserving, at workspace scope: a Member copy -> built-in Member.
        var same = customRole(ctx, admin.token(), BuiltInRoles.WORKSPACE_MEMBER, "Same");
        deleteRole(ctx, admin.token(), same, BuiltInRoles.WORKSPACE_MEMBER)
                .andExpect(status().isNoContent());
    }

    // ================================================= MEDIUM — the ceiling's other end

    /**
     * <strong>A ceiling checked at one end is a demotion route</strong> (round-3 review).
     *
     * <p>{@code WorkspaceMemberService.updateRole} states the rule the role editor skipped:
     * <em>the ceiling applies to BOTH ends of the edit; only checking the new role would let
     * an ADMIN demote an OWNER.</em> S4 opened two doors that checked one end —
     * {@code PATCH /roles/{id}} bounded only the incoming permission set, and
     * {@code DELETE /roles/{id}} bounded only the reassign target — so a workspace Admin who
     * is correctly refused at {@code PATCH /members/{B}} could take the same access away from
     * B by emptying or deleting the role itself.
     *
     * <p>It is sabotage rather than escalation, and worse than it sounds because it is
     * <em>irreversible by the actor</em>: putting {@code project.administer.all} back fails
     * the definition ceiling that let them remove it. All four calls are asserted here — the
     * two that were already refused, and the two that were not.
     */
    @Test
    void aWorkspaceAdminCanNeitherStripNorDeleteARoleGrantingMoreThanTheyHold() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        // Only an Owner can mint project.administer.all — no built-in holds it, which is why
        // the definition ceiling's Owner exemption exists at all.
        var delegate = customRole(ctx, ctx.token(), BuiltInRoles.WORKSPACE_ADMIN, "Delegate");
        patchPermissions(ctx, delegate, plus(ctx, delegate, "project.administer.all"))
                .andExpect(status().isOk());
        var b = actorWith(ctx, "MEMBER", null);
        patchWorkspaceMember(ctx, ctx.token(), b.user().getId(), delegate)
                .andExpect(status().isOk());

        // The membership door, already closed before this slice: the ceiling on the role B
        // CURRENTLY holds.
        patchWorkspaceMember(ctx, admin.token(), b.user().getId(), BuiltInRoles.WORKSPACE_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.administer.all")));

        // The two S4 doors, which were not.
        patchRoleAs(ctx, admin.token(), delegate, "{\"permissions\":[]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.administer.all")));
        deleteRole(ctx, admin.token(), delegate, BuiltInRoles.WORKSPACE_MEMBER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.administer.all")));

        assertThat(permissionKeys(role(ctx, ctx.token(), delegate)))
                .as("B's access must be exactly where it was")
                .contains("project.administer.all");

        // The Owner, the root of trust, may still do both — the exemption is unchanged.
        patchRole(ctx, delegate, "{\"name\":\"Delegate (renamed)\"}")
                .andExpect(status().isOk());
        deleteRole(ctx, ctx.token(), delegate, BuiltInRoles.WORKSPACE_MEMBER)
                .andExpect(status().isNoContent());
    }

    /**
     * <strong>…including a bare rename, which is the case the first fix would have let
     * through</strong> (round-3, second pass).
     *
     * <p>The guard was originally placed inside the {@code permissions != null} branch, so a
     * {@code PATCH} carrying only a {@code name} skipped it entirely. A rename is not a
     * permission change and it is the same sabotage one size smaller: a role's name is what
     * an administrator picks it by in the People tab and in the assignment picker, so
     * relabelling "Deputy" as "Intern" leaves the next admin handing out twenty project
     * permissions believing they know what they are handing out. The role being deleted is
     * checked unconditionally, so anything less here is an asymmetry with no argument behind
     * it.
     *
     * <p>A description-only edit is the same act with a smaller blast radius and is refused
     * by the same line — asserted so that "only {@code name} is guarded" cannot be read into
     * the placement later.
     */
    @Test
    void aWorkspaceAdminCannotEvenRenameARoleGrantingMoreThanTheyHold() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        var delegate = customRole(ctx, ctx.token(), BuiltInRoles.WORKSPACE_ADMIN, "Deputy");
        patchPermissions(ctx, delegate, plus(ctx, delegate, "project.administer.all"))
                .andExpect(status().isOk());

        patchRoleAs(ctx, admin.token(), delegate, "{\"name\":\"Intern\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.administer.all")));
        patchRoleAs(ctx, admin.token(), delegate, "{\"description\":\"Nothing to see here\"}")
                .andExpect(status().isForbidden());

        assertThat(role(ctx, ctx.token(), delegate).get("name").asText())
                .as("the label an administrator picks this role by must be exactly where it was")
                .isEqualTo("Deputy");

        // The Owner may still rename it, and an Admin may still rename any role within their
        // own set — the refusal is about the role, never about the verb.
        patchRole(ctx, delegate, "{\"name\":\"Deputy (EU)\"}").andExpect(status().isOk());
        var ordinary = customRole(ctx, admin.token(), BuiltInRoles.WORKSPACE_MEMBER, "Contractor");
        patchRoleAs(ctx, admin.token(), ordinary, "{\"name\":\"Contractor (EU)\"}")
                .andExpect(status().isOk());
    }

    /**
     * …and the narrowing is exactly that narrow: an Admin still edits and deletes any role
     * within their own set, which is every role the product ships except one an Owner
     * deliberately minted above them. Without this half the fix above would be indexed as
     * "role editing needs Owner", which it must not become.
     */
    @Test
    void anAdminStillEditsAndDeletesRolesWithinTheirOwnSet() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);

        var workspaceScoped = customRole(ctx, admin.token(), BuiltInRoles.WORKSPACE_MEMBER, "Contractor");
        patchRoleAs(ctx, admin.token(), workspaceScoped, "{\"name\":\"Contractor (EU)\"}")
                .andExpect(status().isOk());
        patchRoleAs(ctx, admin.token(), workspaceScoped, "{\"permissions\":[]}")
                .andExpect(status().isOk());
        deleteRole(ctx, admin.token(), workspaceScoped, null)
                .andExpect(status().isNoContent());

        // And PROJECT scope is untouched by this: §11.3 drops the definition ceiling there on
        // purpose, so an Admin may still strip a project role they could not compose.
        var projectScoped = customRole(ctx, admin.token(), BuiltInRoles.PROJECT_MANAGER, "Wide");
        patchRoleAs(ctx, admin.token(), projectScoped, "{\"permissions\":[]}")
                .andExpect(status().isOk());
        deleteRole(ctx, admin.token(), projectScoped, null)
                .andExpect(status().isNoContent());
    }

    // ============================================================ HIGH — editing a role you hold

    /**
     * <strong>Editing a project role is a grant to everyone already holding it.</strong> The
     * actor is one of them here — the A1 recipe's own shape — so a widening PATCH would hand
     * their own account {@code issue.delete} in that project, one of the five gates V13
     * withholds from Owner and Admin.
     */
    @Test
    void wideningAProjectRoleYouHoldYourselfIsRefused() throws Exception {
        var ctx = newProject();
        // A second project administrator, so moving the owner off Project admin is not
        // door 3's business.
        actorWith(ctx, "MEMBER", "MANAGER");
        var custom = customRole(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "QA lead");
        patchProjectMember(ctx, ctx.token(), ctx.owner().getId(), custom)
                .andExpect(status().isOk());

        patchPermissions(ctx, custom, plus(ctx, custom, "issue.delete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("SELF_HELD_ROLE"));
        assertThat(permissionKeys(role(ctx, ctx.token(), custom)))
                .as("the refused widening must not have been applied")
                .doesNotContain("issue.delete");

        // Narrowing it, and renaming it, stay legal while holding it — the guard is keyed on
        // the edit widening, not on the actor holding the role.
        patchRole(ctx, custom, "{\"name\":\"QA lead (renamed)\"}")
                .andExpect(status().isOk());
        patchPermissions(ctx, custom, minus(ctx, custom, "issue.rank"))
                .andExpect(status().isOk());
    }

    /**
     * …and the primary recipe is untouched: widening a role the actor does <em>not</em> hold
     * is exactly acceptance case A1 ("duplicate Contributor, add {@code sprint.manage}").
     */
    @Test
    void wideningAProjectRoleYouDoNotHoldIsTheProductsPrimaryRecipe() throws Exception {
        var ctx = newProject();
        var custom = customRole(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "Team lead (sprints)");

        patchPermissions(ctx, custom, plus(ctx, custom, "sprint.manage"))
                .andExpect(status().isOk());
        assertThat(permissionKeys(role(ctx, ctx.token(), custom))).contains("sprint.manage");
    }

    // ============================================================ MEDIUM — the stale precondition

    /**
     * <strong>The doors-4/5 guard must read its own precondition from the row, not from the
     * cache.</strong>
     *
     * <p>{@code roleView} is a 10 s per-process cache evicted only on the node that served an
     * edit. The fixture below is that node's neighbour: prime the cache with the role's
     * <em>pre-edit</em> grants, then apply the edit the way another instance would (straight
     * to the row, no event, no eviction). The guard's question — "does this role currently
     * grant {@code project.member.manage}?" — then answers <em>no</em> from the cache and
     * <em>yes</em> from the row.
     *
     * <p>Reading the cache makes the guard skip itself and strand the project silently, which
     * is strictly worse than the "advisory against a concurrent membership change" its javadoc
     * admits to. Reading the entity gives the 409.
     */
    @Test
    void theBulkStrandingGuardReadsTheRoleRowAndNotTheTenSecondCache() throws Exception {
        var ctx = newProject();
        // A role that does NOT manage members yet. Its snapshot goes into the cache here.
        var custom = customRole(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "Roster lead");
        assertThat(roleCatalog.view(custom).permissions().has(Permission.PROJECT_MEMBER_MANAGE))
                .as("fixture: the cached snapshot must predate the grant")
                .isFalse();

        // The edit another instance made: straight to the row, so this process never evicts.
        addGrantOutOfBand(custom, Permission.PROJECT_MEMBER_MANAGE);
        assertThat(roleCatalog.view(custom).permissions().has(Permission.PROJECT_MEMBER_MANAGE))
                .as("fixture: this process must still be serving the stale snapshot")
                .isFalse();

        // The role's holder becomes the project's only administrator.
        var lead = actorWith(ctx, "MEMBER", null);
        addProjectMember(ctx, ctx.token(), lead.user().getId(), custom)
                .andExpect(status().isCreated());
        removeProjectMember(ctx, ctx.token(), ctx.owner().getId())
                .andExpect(status().isNoContent());

        // Dropping the permission strands the project. Read from the cache, the guard
        // believes the role never had it and waves this through.
        patchPermissions(ctx, custom, "[{\"key\":\"issue.create\",\"ownOnly\":false}]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("LAST_PROJECT_ADMIN_BULK"));
    }

    // ==================================================== helpers

    private String rolesBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/roles";
    }

    /** Duplicate {@code source} under {@code name} as {@code token}, and return the new id. */
    private UUID customRole(Ctx ctx, String token, UUID source, String name) throws Exception {
        var body = mockMvc.perform(post(rolesBase(ctx) + "/" + source + "/duplicate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + json.writeValueAsString(name) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    private ResultActions invite(Ctx ctx, String token, UUID roleId) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/invites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"chain-" + UUID.randomUUID() + "@example.com\","
                         + "\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions deleteRole(Ctx ctx, String token, UUID roleId, UUID reassignTo) throws Exception {
        return mockMvc.perform(delete(rolesBase(ctx) + "/" + roleId
                        + (reassignTo == null ? "" : "?reassignToRoleId=" + reassignTo))
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions patchRole(Ctx ctx, UUID roleId, String body) throws Exception {
        return patchRoleAs(ctx, ctx.token(), roleId, body);
    }

    private ResultActions patchRoleAs(Ctx ctx, String token, UUID roleId, String body)
            throws Exception {
        return mockMvc.perform(patch(rolesBase(ctx) + "/" + roleId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchWorkspaceMember(Ctx ctx, String token, UUID userId, UUID roleId)
            throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/members/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions patchPermissions(Ctx ctx, UUID roleId, String permissionsJson)
            throws Exception {
        return patchRole(ctx, roleId, "{\"permissions\":" + permissionsJson + "}");
    }

    private com.fasterxml.jackson.databind.JsonNode role(Ctx ctx, String token, UUID roleId)
            throws Exception {
        return json.readTree(mockMvc.perform(get(rolesBase(ctx) + "/" + roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private com.fasterxml.jackson.databind.JsonNode usage(Ctx ctx, String token, UUID roleId)
            throws Exception {
        return json.readTree(mockMvc.perform(get(rolesBase(ctx) + "/" + roleId + "/usage")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> permissionKeys(com.fasterxml.jackson.databind.JsonNode roleNode) {
        var out = new ArrayList<String>();
        for (var p : roleNode.get("permissions")) out.add(p.get("key").asText());
        return out;
    }

    /** {@code role}'s current grants plus one, as a permissions array. */
    private String plus(Ctx ctx, UUID roleId, String extra) throws Exception {
        return permissionsJson(role(ctx, ctx.token(), roleId), extra, null);
    }

    /** {@code role}'s current grants minus one. */
    private String minus(Ctx ctx, UUID roleId, String dropped) throws Exception {
        return permissionsJson(role(ctx, ctx.token(), roleId), null, dropped);
    }

    /**
     * <strong>{@code ownOnly} is carried over, not re-guessed.</strong> A helper that resent
     * every grant unrestricted would turn any "unchanged" replacement into a widening, which
     * is precisely the transition the guard under test refuses — the assertion would then pass
     * for the wrong reason and the narrowing case would fail for one.
     */
    private static String permissionsJson(com.fasterxml.jackson.databind.JsonNode roleNode,
                                          String add, String drop) {
        var parts = new ArrayList<String>();
        for (var p : roleNode.get("permissions")) {
            var key = p.get("key").asText();
            if (key.equals(drop)) continue;
            parts.add("{\"key\":\"" + key + "\",\"ownOnly\":" + p.get("ownOnly").asBoolean() + "}");
        }
        if (add != null) parts.add("{\"key\":\"" + add + "\",\"ownOnly\":false}");
        return "[" + String.join(",", parts) + "]";
    }

    private String projectMembersBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/members";
    }

    private ResultActions patchProjectMember(Ctx ctx, String token, UUID userId, UUID roleId)
            throws Exception {
        return mockMvc.perform(patch(projectMembersBase(ctx) + "/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions addProjectMember(Ctx ctx, String token, UUID userId, UUID roleId)
            throws Exception {
        return mockMvc.perform(post(projectMembersBase(ctx))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions removeProjectMember(Ctx ctx, String token, UUID userId) throws Exception {
        return mockMvc.perform(delete(projectMembersBase(ctx) + "/" + userId)
                .header("Authorization", "Bearer " + token));
    }

    /**
     * Add a grant straight to the row, bypassing the service — <strong>the "another instance
     * made this edit" fixture</strong>. No {@code RolePermissionsChanged} is published, so this
     * process's {@code roleView} entry stays exactly as stale as a real neighbour node's would
     * be for up to the TTL.
     */
    private void addGrantOutOfBand(UUID roleId, Permission permission) {
        txTemplate.executeWithoutResult(status -> {
            var role = entityManager.find(Role.class, roleId);
            role.getPermissions().add(new RolePermission(permission, false));
            entityManager.flush();
        });
    }
}
