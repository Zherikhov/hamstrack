package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.SprintTestBase;
import com.hamstrack.workspace.entity.BuiltInRoles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

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
 * HD-130 (S7) — <strong>the project-access mode and the two default-role pickers</strong>:
 * {@code PATCH /api/workspaces/{ws}}, {@code GET|POST …/project-access[/preview]} and
 * {@code GET|PATCH …/projects/{p}/default-role}.
 *
 * <p>This is the slice that makes roles <em>bite</em>. Everything before it reproduced today's
 * behaviour exactly, so the acceptance criteria here are of three kinds and each is a different
 * kind of failure:
 * <ul>
 *   <li><strong>{@code OPEN} is the identity</strong> (§11 A) — a workspace that never opens
 *       the new screen must behave byte-identically. If this breaks, every existing install
 *       changes on upgrade;</li>
 *   <li><strong>the round trip</strong> (§11 B) — {@code STRICT} removes writes, restores them
 *       on the way back, and touches no data in between. If this breaks, people lose access to
 *       their own projects;</li>
 *   <li><strong>the ceiling</strong> (§11 C) — the pickers are an escalation door, and this
 *       slice is what makes them reachable. If this breaks, a workspace Admin holding four
 *       project permissions hands every member all twenty.</li>
 * </ul>
 *
 * <p>Stranding (§11 D) lives in {@code InheritedAdministratorStrandingTest}; the preview's query
 * cost (§11 E, AC 28) in {@code ProjectAccessPreviewQueryCountTest}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ProjectAccessModeTest extends SprintTestBase {

    // ==================================================== A. OPEN is a no-op

    /**
     * <strong>AC 2.</strong> Setting {@code OPEN} on an already-{@code OPEN} workspace is a 200
     * that writes <em>nothing</em>: {@code updated_at} does not move.
     *
     * <p>"Nothing changed" has to be true at the row level and not merely at the permission
     * level. A save that only re-stamps the timestamp is invisible in every behavioural test and
     * shows up as a workspace that looks edited to anyone reading the audit trail.
     */
    @Test
    void settingTheModeItAlreadyHasWritesNothing() throws Exception {
        var ctx = newProject();
        var before = updatedAt(ctx.wsId());

        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectAccessMode").value("OPEN"));

        assertThat(updatedAt(ctx.wsId()))
                .as("a no-op PATCH re-stamped updated_at. §7.1 rule 6 exists so that 'setting "
                    + "OPEN on an OPEN workspace changes nothing' is true of the ROW, not only "
                    + "of the permissions it implies")
                .isEqualTo(before);
    }

    /** A body that asks for nothing is a 400, not a silent 200 (§7.1 rule 1). */
    @Test
    void anEmptyBodyIsRefused() throws Exception {
        var ctx = newProject();
        patchWorkspace(ctx, ctx.token(), "{}").andExpect(status().isBadRequest());
    }

    /**
     * The two ways of naming the workspace default mean different things, so sending both is a
     * <strong>422</strong> rather than a precedence rule — the shape
     * {@code RoleSelectionException} already defines for every membership door.
     */
    @Test
    void namingTheDefaultRoleBothWaysIsUnprocessable() throws Exception {
        var ctx = newProject();
        patchWorkspace(ctx, ctx.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_VIEWER
                + "\",\"clearDefaultProjectRole\":true}")
                .andExpect(status().isUnprocessableContent());
    }

    // ==================================================== B. the round trip

    /**
     * <strong>AC 6–8, the heart of the slice.</strong> A plain member with no
     * {@code project_members} row loses every write in {@code STRICT} and keeps every read —
     * and gets all of it back, byte-identically, on the way out.
     *
     * <p>The Owner assertion is the one that surprises people and the one §4.1 forces the UI to
     * state: {@code project.curate.all} is
     * {@code {project.edit, component.manage, version.manage, sprint.manage}} and contains no
     * issue permission at all, so in a restricted project with no explicit members
     * <strong>nobody can file an issue, the Owner included</strong>.
     */
    @Test
    void strictRemovesWritesKeepsReadsAndTheFlipBackRestoresEverythingExactly() throws Exception {
        var ctx = newProject();
        // Both archetypes deliberately hold NO project_members row — the population §2.3 says
        // is nearly everybody, and the only one the mode is about.
        var member = actorWith(ctx, "MEMBER", null);
        var ownerNoRow = actorWith(ctx, "OWNER", null);
        var issueNumber = createIssue(ctx, "Existing work").get("number").asLong();

        var before = myPermissions(ctx, member.token());
        var ownerBefore = myPermissions(ctx, ownerNoRow.token());
        assertThat(before).contains("issue.create", "issue.edit", "comment.create");

        setMode(ctx, "STRICT");

        assertThat(myPermissions(ctx, member.token()))
                .as("STRICT left the member with permissions. The mode's ONLY job is to stop "
                    + "the §5.2 fallback from yielding a role")
                .isEmpty();
        postIssue(ctx, member.token(), "Nope").andExpect(status().isForbidden());
        patchIssue(ctx, member.token(), issueNumber, "{\"title\":\"Nope\"}")
                .andExpect(status().isForbidden());
        // …and every read still works: STRICT narrows writes, never reads (§13).
        mockMvc.perform(get(issuesUrl(ctx)).header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get(issuesUrl(ctx) + "/" + issueNumber)
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects")
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk());

        // A workspace Owner with no project row keeps exactly the curator four — and therefore
        // cannot file an issue either. That is the honest consequence §4.1 makes the UI state.
        assertThat(myPermissions(ctx, ownerNoRow.token()))
                .containsExactlyInAnyOrderElementsOf(
                        Permission.projectCuration().stream().map(Permission::key).toList());
        postIssue(ctx, ownerNoRow.token(), "Nope").andExpect(status().isForbidden());

        setMode(ctx, "OPEN");

        assertThat(myPermissions(ctx, member.token()))
                .as("the flip back did not restore the member's permissions exactly. The whole "
                    + "safety argument for shipping a switch that removes access is that it is "
                    + "reversible in one click")
                .isEqualTo(before);
        assertThat(myPermissions(ctx, ownerNoRow.token())).isEqualTo(ownerBefore);
        postIssue(ctx, member.token(), "Back").andExpect(status().isCreated());
    }

    /**
     * <strong>AC 9.</strong> A project whose own default is the built-in <em>Viewer</em> is
     * strict while its workspace is {@code OPEN} — that is the model's "None" (§12.1), and the
     * reason S7 ships no per-project mode column.
     */
    @Test
    void aProjectDefaultingToViewerIsStrictWhileTheWorkspaceIsOpen() throws Exception {
        var ctx = newProject();
        var member = actorWith(ctx, "MEMBER", null);

        setProjectDefault(ctx, ctx.token(), "{\"roleId\":\"" + BuiltInRoles.PROJECT_VIEWER + "\"}")
                .andExpect(status().isOk());

        assertThat(myPermissions(ctx, member.token())).isEmpty();
        // …while a sibling project in the same OPEN workspace is untouched.
        var sibling = siblingProject(ctx);
        assertThat(myPermissions(sibling, member.token())).contains("issue.create");
    }

    // ==================================================== C. the ceiling

    /**
     * <strong>AC 10–11.</strong> A workspace Admin holds {@code project.curate.all}'s four
     * permissions and not {@code project.administer.all}, so the baseline they are measured
     * against is <em>built-in Contributor ∪ those four</em>: Viewer, Commenter and Contributor
     * are settable, Project admin and Team lead are not.
     *
     * <p>The refusal names the first uncovered permission in catalog order, because the ceiling
     * is a <strong>subset</strong> rule and "insufficient role" is not an answer to a subset
     * failure.
     */
    @Test
    void anAdminMaySetTheDefaultUpToContributorAndNoFurther() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);

        for (var settable : List.of(BuiltInRoles.PROJECT_VIEWER, BuiltInRoles.PROJECT_COMMENTER,
                BuiltInRoles.PROJECT_MEMBER)) {
            patchWorkspace(ctx, admin.token(), "{\"defaultProjectRoleId\":\"" + settable + "\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.defaultProjectRoleId").value(settable.toString()));
        }

        // Team lead = Contributor + project.member.manage — refused, naming exactly that.
        patchWorkspace(ctx, admin.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_TEAM_LEAD + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.member.manage")));

        // Project admin = all twenty. The FIRST uncovered permission in catalog order is
        // project.archive, and the message must quote the same string `settable.cannotSet`
        // publishes for that role (AC 16).
        patchWorkspace(ctx, admin.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.archive")));

        assertThat(missingFor(projectAccess(ctx, admin.token()), BuiltInRoles.PROJECT_MANAGER))
                .as("the greyed-out tooltip and the runtime 403 must quote ONE value — they are "
                    + "the same firstNotCovered call or they are two rules")
                .isEqualTo("project.archive");

        // The stored value is the last one that passed.
        assertThat(projectAccess(ctx, admin.token()).get("defaultProjectRoleId").asText())
                .isEqualTo(BuiltInRoles.PROJECT_MEMBER.toString());
    }

    /** <strong>AC 12.</strong> The built-in workspace Owner is the root of trust — exempt. */
    @Test
    void theOwnerMaySetAnyAssignableProjectRoleAsTheDefault() throws Exception {
        var ctx = newProject();
        patchWorkspace(ctx, ctx.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}")
                .andExpect(status().isOk());
        assertThat(projectAccess(ctx, ctx.token()).get("settable").get("cannotSet")).isEmpty();
    }

    /**
     * <strong>AC 14 — the "current end", and the reason the comparand is a constant.</strong>
     * With the default at Team lead an Admin may not <em>narrow</em> it to Viewer: whatever you
     * may not grant, you may not strip. The Owner can.
     */
    @Test
    void anAdminMayNotNarrowADefaultThatIsOutsideTheirBaseline() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        patchWorkspace(ctx, ctx.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_TEAM_LEAD + "\"}")
                .andExpect(status().isOk());

        patchWorkspace(ctx, admin.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_VIEWER + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.member.manage")));
        patchWorkspace(ctx, admin.token(), "{\"clearDefaultProjectRole\":true}")
                .andExpect(status().isForbidden());

        patchWorkspace(ctx, ctx.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_VIEWER + "\"}")
                .andExpect(status().isOk());
    }

    /**
     * <strong>AC 13 — the §4 escape does not reach the picker</strong>, which is the whole
     * argument for a separate ceiling call at project scope.
     *
     * <p>The same actor, in the same project, with the same role: promoting a colleague to the
     * built-in Project admin is allowed ({@code target != actor} is load-bearing and satisfied),
     * and aiming that role at the project <em>default</em> is a 403 — because a default's target
     * is everyone, the actor included, so extending the escape would hand all twenty project
     * permissions to the whole workspace on the authority of one.
     */
    @Test
    void theMembershipEscapeDoesNotExtendToTheProjectDefault() throws Exception {
        var ctx = newProject();
        var lead = actorWithCustomProjectRole(ctx, "narrow-lead", Permission.PROJECT_MEMBER_MANAGE);
        // A Viewer, because the escape exempts only the GRANTING half of the ceiling: the
        // ACTING_ON half still bounds the target's CURRENT role, and Viewer grants nothing.
        var colleague = actorWith(ctx, "MEMBER", "VIEWER");

        mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                        + "/members/" + colleague.user().getId())
                        .header("Authorization", "Bearer " + lead.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}"))
                .andExpect(status().isOk());

        // The same actor, the same role, the same project — and a 403, because a default has
        // no target: its target is everyone, the actor included.
        setProjectDefault(ctx, lead.token(), "{\"roleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail",
                        containsString("default role for everyone in this project")))
                // …naming the first permission of Project admin the actor does not hold, which
                // is the same string the picker greys the role out with (AC 16).
                .andExpect(jsonPath("$.detail",
                        containsString(missingFor(defaultRole(ctx, lead.token()),
                                BuiltInRoles.PROJECT_MANAGER))));
    }

    /**
     * <strong>AC 15.</strong> At project scope the comparand is the actor's real effective set,
     * with nobody exempt — so a narrow member-manager cannot change a project default of Project
     * admin, and a real Project admin can.
     */
    @Test
    void aNarrowMemberManagerCannotChangeAWideProjectDefault() throws Exception {
        var ctx = newProject();
        setProjectDefault(ctx, ctx.token(), "{\"roleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}")
                .andExpect(status().isOk());
        var lead = actorWithCustomProjectRole(ctx, "narrow-lead-2", Permission.PROJECT_MEMBER_MANAGE);

        setProjectDefault(ctx, lead.token(), "{\"inherit\":true}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("You cannot change a default")));

        setProjectDefault(ctx, ctx.token(), "{\"inherit\":true}").andExpect(status().isOk());
    }

    /** Neither field, or both — 422 either way, never a precedence rule. */
    @Test
    void theProjectPickerRequiresExactlyOneOfRoleIdAndInherit() throws Exception {
        var ctx = newProject();
        setProjectDefault(ctx, ctx.token(), "{}").andExpect(status().isUnprocessableContent());
        setProjectDefault(ctx, ctx.token(),
                "{\"inherit\":true,\"roleId\":\"" + BuiltInRoles.PROJECT_VIEWER + "\"}")
                .andExpect(status().isUnprocessableContent());
    }

    // ==================================================== E. the preview

    /**
     * <strong>AC 24, 25, 26, 27.</strong> The preview persists nothing and its numbers are the
     * state that applying the same body actually produces — asserted by applying it and
     * recounting, field by field.
     */
    @Test
    void thePreviewPersistsNothingAndItsNumbersSurviveApplyingTheSameBody() throws Exception {
        var ctx = newProject();                       // owner is an EXPLICIT Project admin here
        var viewerOnly = actorWith(ctx, "MEMBER", "VIEWER");
        var plain = actorWith(ctx, "MEMBER", null);
        actorWith(ctx, "ADMIN", null);                // 4 ACTIVE workspace members in total
        // A second project whose ONLY explicit member is that Viewer — the AC 26 shape, which
        // `newProject` cannot produce because it always seeds its creator as Project admin.
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        var readOnlyProject = project(ws, ctx.owner());
        projectMember(readOnlyProject, viewerOnly.user(), "VIEWER");

        var updatedAtBefore = updatedAt(ctx.wsId());
        var preview = preview(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}");

        assertThat(updatedAt(ctx.wsId()))
                .as("the preview wrote to the workspace row")
                .isEqualTo(updatedAtBefore);
        assertThat(preview.get("from").get("mode").asText()).isEqualTo("OPEN");
        assertThat(preview.get("to").get("mode").asText()).isEqualTo("STRICT");
        assertThat(preview.get("activeMembers").asLong()).isEqualTo(4);
        assertThat(preview.get("projects").asInt()).isEqualTo(2);
        assertThat(preview.get("computedAt").isNull()).isFalse();

        var row = perProject(preview, ctx.projectId());
        // The owner and `viewerOnly` hold explicit rows here; the other two inherit.
        assertThat(row.get("explicitMembers").asLong()).isEqualTo(2);
        assertThat(row.get("membersOnDefault").asLong()).isEqualTo(2);
        // AC 25: the ADMIN holds project.curate.all, so they do NOT hold the empty set after.
        assertThat(row.get("membersLosingEverything").asLong()).isEqualTo(1);
        // …and this one keeps a writer, because its explicit Project admin holds issue.create.
        assertThat(row.get("noWritersAfter").asBoolean()).isFalse();

        // AC 26: the other project's only explicit member is a Viewer, who grants nothing — so
        // once the fallback is gone nobody at all holds issue.create there. The Owner does NOT
        // rescue it: project.curate.all carries no issue or comment permission, which is
        // exactly the sentence §4.1 says the epic's own copy got wrong.
        var readOnlyRow = perProject(preview, readOnlyProject.getId());
        assertThat(readOnlyRow.get("explicitMembers").asLong()).isEqualTo(1);
        assertThat(readOnlyRow.get("membersOnDefault").asLong()).isEqualTo(3);
        assertThat(readOnlyRow.get("noWritersAfter").asBoolean()).isTrue();
        assertThat(preview.get("projectsWithNoWriters").asInt()).isEqualTo(1);
        assertThat(preview.get("projectsWithNoExplicitMembers").asInt()).isEqualTo(0);
        assertThat(preview.get("strandedProjects")).isEmpty();

        // Apply it, then recount from the database through the same model.
        setMode(ctx, "STRICT");
        var after = projectAccess(ctx, ctx.token()).get("impact");
        for (var field : List.of("activeMembers", "projects", "projectsWithNoExplicitMembers",
                "projectsWithNoWriters")) {
            assertThat(after.get(field)).as(field).isEqualTo(preview.get(field));
        }
        assertThat(perProject(after, ctx.projectId()).get("membersLosingEverything"))
                .isEqualTo(row.get("membersLosingEverything"));

        // …and it really did remove the writes it said it would.
        assertThat(myPermissions(ctx, plain.token())).isEmpty();
    }

    /** <strong>AC 25.</strong> A DISABLED account is not a person who loses access. */
    @Test
    void deactivatedAccountsAreNotCounted() throws Exception {
        var ctx = newProject();
        var leaving = actorWith(ctx, "MEMBER", null);

        assertThat(preview(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .get("activeMembers").asLong()).isEqualTo(2);

        var user = userRepository.findById(leaving.user().getId()).orElseThrow();
        user.setStatus(com.hamstrack.auth.entity.UserStatus.DISABLED);
        userRepository.save(user);

        assertThat(preview(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .get("activeMembers").asLong())
                .as("a deactivated account cannot log in, so it administers nothing and loses "
                    + "nothing — counting one inflates the number the whole decision rests on")
                .isEqualTo(1);
    }

    /** <strong>AC 29.</strong> A refused preview is a 403, not a 200 with a "would fail" field. */
    @Test
    void aCeilingFailureSurfacesFromThePreviewAsA403() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        mockMvc.perform(post(previewUrl(ctx.wsId()))
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}"))
                .andExpect(status().isForbidden());
    }

    // ==================================================== F. tenancy

    /**
     * <strong>AC 30.</strong> A non-member gets 404 from all five endpoints — for a workspace
     * and a project that plainly exist. A member without the gate gets 403. Non-existence and
     * non-membership are indistinguishable, which is the rule the whole product rests on.
     */
    @Test
    void aNonMemberGets404FromEveryNewEndpointAndAMemberWithoutTheGateGets403() throws Exception {
        var ctx = newProject();
        var outsider = newProject();          // their own workspace, and a stranger to this one
        var member = actorWith(ctx, "MEMBER", null);

        mockMvc.perform(get(projectAccessUrl(ctx.wsId()))
                .header("Authorization", "Bearer " + outsider.token())).andExpect(status().isNotFound());
        mockMvc.perform(post(previewUrl(ctx.wsId()))
                        .header("Authorization", "Bearer " + outsider.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectAccessMode\":\"STRICT\"}"))
                .andExpect(status().isNotFound());
        patchWorkspace(ctx, outsider.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isNotFound());
        mockMvc.perform(get(defaultRoleUrl(ctx))
                .header("Authorization", "Bearer " + outsider.token())).andExpect(status().isNotFound());
        setProjectDefault(ctx, outsider.token(), "{\"inherit\":true}").andExpect(status().isNotFound());

        // A proven member, missing the permission — 403, and only ever after the 404 check.
        mockMvc.perform(get(projectAccessUrl(ctx.wsId()))
                .header("Authorization", "Bearer " + member.token())).andExpect(status().isForbidden());
        patchWorkspace(ctx, member.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isForbidden());
        mockMvc.perform(get(defaultRoleUrl(ctx))
                .header("Authorization", "Bearer " + member.token())).andExpect(status().isForbidden());
    }

    /**
     * <strong>AC 31.</strong> A foreign PROJECT role and a home WORKSPACE role are both 422 on
     * both writes, and neither is stored.
     *
     * <p>The scope half matters as much as the tenancy half, and less obviously: a
     * {@code PermissionSet} is a flat {@code EnumSet} with no memory of where its grants came
     * from, so a WORKSPACE role accepted as a project default would put {@code workspace.edit}
     * into every member's {@code ProjectContext} in every project of the workspace.
     */
    @Test
    void aForeignOrWrongScopeRoleIdIs422AndIsNeverStored() throws Exception {
        var ctx = newProject();
        var other = newProject();
        var foreign = customProjectRole(other.wsId(), "their-role");

        for (var bad : List.of(foreign.toString(), BuiltInRoles.WORKSPACE_ADMIN.toString())) {
            patchWorkspace(ctx, ctx.token(), "{\"defaultProjectRoleId\":\"" + bad + "\"}")
                    .andExpect(status().isUnprocessableContent());
            setProjectDefault(ctx, ctx.token(), "{\"roleId\":\"" + bad + "\"}")
                    .andExpect(status().isUnprocessableContent());
        }

        var state = projectAccess(ctx, ctx.token());
        assertThat(state.get("defaultProjectRoleId").isNull()).isTrue();
        assertThat(defaultRole(ctx, ctx.token()).get("projectRoleId").isNull()).isTrue();
    }

    /**
     * <strong>AC 32.</strong> The impact counts never reach across tenants. Built-in roles are
     * SHARED rows ({@code workspace_id IS NULL}), so an unscoped {@code GROUP BY role_id} on
     * {@code workspace_members} would publish the neighbouring workspace's headcount — from an
     * endpoint whose entire job is counting people.
     */
    @Test
    void impactCountsNeverIncludeAnotherWorkspacesMembers() throws Exception {
        var ctx = newProject();
        var neighbour = newProject();
        for (int i = 0; i < 4; i++) {
            actorWith(neighbour, "MEMBER", "MEMBER");
        }

        assertThat(preview(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .get("activeMembers").asLong()).isEqualTo(1);
        assertThat(preview(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .get("projects").asInt()).isEqualTo(1);
    }

    // ==================================================== the mode on the wire

    /**
     * {@code projectAccessMode} lives on {@code WorkspaceResponse} and <strong>nowhere
     * else</strong> (§6): one source of truth, so the project People card cannot render a copy
     * that disagrees with it. {@code GET …/default-role} echoes it deliberately — that one is a
     * self-contained read for a dialog, not a second home for the field.
     */
    @Test
    void theModeIsPublishedOnTheWorkspaceAndTheDefaultRoleIsAlwaysAnId() throws Exception {
        var ctx = newProject();
        setMode(ctx, "STRICT");

        var ws = json.readTree(mockMvc.perform(get("/api/workspaces/" + ctx.wsId())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ws.get("projectAccessMode").asText()).isEqualTo("STRICT");
        assertThat(ws.get("defaultProjectRoleId").isNull()).isTrue();

        // Ids only — never a name, a key or a permission list (§6).
        var picker = defaultRole(ctx, ctx.token());
        assertThat(picker.get("mode").asText()).isEqualTo("STRICT");
        for (var forbidden : List.of("name", "key", "permissions", "roleName")) {
            assertThat(picker.has(forbidden))
                    .as("ProjectDefaultRoleResponse grew a `%s`. Any name here must go through "
                        + "resolveRoleOrDegrade and be null on failure, or not exist", forbidden)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------ HTTP helpers

    private String issuesUrl(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/issues";
    }

    private String projectAccessUrl(UUID wsId) {
        return "/api/workspaces/" + wsId + "/project-access";
    }

    private String previewUrl(UUID wsId) {
        return projectAccessUrl(wsId) + "/preview";
    }

    private String defaultRoleUrl(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/default-role";
    }

    private ResultActions patchWorkspace(Ctx ctx, String token, String body) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void setMode(Ctx ctx, String mode) throws Exception {
        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"" + mode + "\"}")
                .andExpect(status().isOk());
    }

    private ResultActions setProjectDefault(Ctx ctx, String token, String body) throws Exception {
        return mockMvc.perform(patch(defaultRoleUrl(ctx))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode projectAccess(Ctx ctx, String token) throws Exception {
        return json.readTree(mockMvc.perform(get(projectAccessUrl(ctx.wsId()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode defaultRole(Ctx ctx, String token) throws Exception {
        return json.readTree(mockMvc.perform(get(defaultRoleUrl(ctx))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode preview(Ctx ctx, String token, String body) throws Exception {
        return json.readTree(mockMvc.perform(post(previewUrl(ctx.wsId()))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode perProject(JsonNode impact, UUID projectId) {
        for (var row : impact.get("perProject")) {
            if (row.get("id").asText().equals(projectId.toString())) return row;
        }
        throw new AssertionError("no perProject row for " + projectId);
    }

    private static String missingFor(JsonNode projectAccess, UUID roleId) {
        for (var row : projectAccess.get("settable").get("cannotSet")) {
            if (row.get("roleId").asText().equals(roleId.toString())) return row.get("missing").asText();
        }
        throw new AssertionError(roleId + " is not in settable.cannotSet");
    }

    /** The caller's effective project permissions, off the project detail read. */
    private List<String> myPermissions(Ctx ctx, String token) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        json.readTree(body).get("myPermissions").forEach(n -> out.add(n.asText()));
        return out;
    }

    private java.time.Instant updatedAt(UUID workspaceId) {
        return workspaceRepository.findById(workspaceId).orElseThrow().getUpdatedAt();
    }

    /** A PROJECT-scoped custom role of some OTHER workspace — the foreign-id fixture. */
    private UUID customProjectRole(UUID workspaceId, String key) {
        return txTemplate.execute(s -> {
            var role = new com.hamstrack.workspace.entity.Role();
            role.setWorkspaceId(workspaceId);
            role.setScope(RoleScope.PROJECT);
            role.setKey(key + "-" + UUID.randomUUID().toString().substring(0, 6));
            role.setName(key);
            role.setBuiltIn(false);
            entityManager.persist(role);
            entityManager.flush();
            return role.getId();
        });
    }
}
