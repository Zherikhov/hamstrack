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

import java.util.ArrayList;
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
 * HD-130 (S7), <strong>security review round 2</strong> — the two ways to arrive at a
 * default-role grant the ceiling never saw, both of them two-call routes past
 * {@code ProjectAccessModeTest}'s section C.
 *
 * <p>Section C proves the ceiling refuses a role <em>named in the request that sets it</em>.
 * Neither route below names one:
 *
 * <ul>
 *   <li><strong>Critical — set the default narrow, then widen the role.</strong> S7 introduced a
 *       second way to hold a PROJECT role: the §5.2 default chain, which has no
 *       {@code project_members} row at all. {@code RoleService.requireNotSelfWidening} tests
 *       holding with an existence query over explicit rows, so it is blind to it, and the
 *       PROJECT-scope <em>definition</em> ceiling is deliberately absent (§11.3). Three calls,
 *       all previously 200, gave a workspace Admin — and every member without an explicit row —
 *       all twenty project permissions, the five gates {@code V13} withholds from Owner and
 *       Admin on purpose included. The fix makes "is a default" a holding mechanism the role
 *       editor recognises ({@code DefaultRoleCeilingException}).</li>
 *   <li><strong>High — {@code STRICT → OPEN} re-activates a default the actor may not
 *       set.</strong> {@code ProjectAccessService.proposalFrom} skipped the ceiling for a body
 *       that does not touch the default, on the ground that "a pure mode flip must not 403
 *       because of a value it is not changing". But the flip changes that value's
 *       <em>effect</em> — an inert declared default becomes live for everyone — and doors 7/8
 *       cannot cover it, because they read the <em>current</em> mode and so produce no
 *       candidates for a change that turns inheritance on. One field, no role id, same
 *       outcome.</li>
 * </ul>
 *
 * <p>Both fixes are calibrated to refuse the escalation and nothing else, and the "and nothing
 * else" halves are asserted here too: narrowing and renaming a default stay legal, the Owner is
 * exempt as at every other ceiling, and a flip to {@code OPEN} whose declared defaults are all
 * inside the actor's baseline is still free — {@code OPEN} is the identity and no round-2 fix
 * may make it cost a 403.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DefaultRoleCeilingTest extends SprintTestBase {

    // ============================================ CRITICAL — set it narrow, then widen it

    /**
     * <strong>The traced route, verbatim: three calls from workspace Admin to all twenty
     * project permissions for everybody.</strong>
     *
     * <p>Duplicate Contributor into a custom PROJECT role (legal, and the product's primary
     * recipe — §11.3 drops the definition ceiling at PROJECT scope precisely for it) · make it
     * the workspace default (the S7 assignment ceiling passes, because the role is narrow
     * <em>at that moment</em>) · PATCH all twenty project keys into it.
     *
     * <p>The third call must be a 403, and it must name the same permission the picker greys
     * the equivalent built-in out with — one {@code firstNotCovered} call over one comparand,
     * or two rules that will disagree.
     */
    @Test
    void anAdminCannotSetTheWorkspaceDefaultNarrowAndThenWidenTheRole() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        var plain = actorWith(ctx, "MEMBER", null);

        var custom = duplicate(ctx, admin.token(), BuiltInRoles.PROJECT_MEMBER, "QA");
        patchWorkspace(ctx, admin.token(), "{\"defaultProjectRoleId\":\"" + custom + "\"}")
                .andExpect(status().isOk());

        patchRole(ctx, admin.token(), custom, allProjectPermissions())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("default project role")))
                // Catalog order, and the same key the picker publishes for built-in Project
                // admin — the two are the same call over the same comparand (AC 16).
                .andExpect(jsonPath("$.detail", containsString("project.archive")));

        assertThat(permissionKeys(role(ctx, ctx.token(), custom)))
                .as("the refused widening must not have been applied")
                .doesNotContain("project.archive", "issue.delete", "project.member.manage");
        assertThat(myPermissions(ctx, plain.token()))
                .as("every member with no explicit project row stands on that default — if the "
                    + "PATCH had landed, all twenty would be here")
                .doesNotContain("project.archive", "issue.delete", "project.member.manage");
        assertThat(myPermissions(ctx, admin.token())).doesNotContain("issue.delete");
    }

    /**
     * The same three calls against a <strong>project's own</strong> default, which is the twin
     * with the smaller blast radius and the sharper self-grant: the actor holds
     * {@code project.member.manage} in that project, so the §4 escape's {@code target != actor}
     * constraint is exactly what is being routed around.
     *
     * <p>Refused outright rather than measured, because {@code RoleService} has no
     * {@code ProjectContext} and a role may be the declared default of many projects — see
     * {@code DefaultRoleCeilingException}. So the refusal is about the transition: a project
     * default may not be widened.
     */
    @Test
    void aProjectDefaultCannotBeWidenedThroughTheRoleEditor() throws Exception {
        var ctx = newProject();
        // A member-manager IN the project (built-in Team lead) who can also edit roles.
        var lead = actorWith(ctx, "ADMIN", "TEAM_LEAD");

        var custom = duplicate(ctx, lead.token(), BuiltInRoles.PROJECT_MEMBER, "Squad");
        setProjectDefault(ctx, lead.token(), "{\"roleId\":\"" + custom + "\"}")
                .andExpect(status().isOk());

        patchRole(ctx, lead.token(), custom, allProjectPermissions())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("default role of 1 project")));

        assertThat(permissionKeys(role(ctx, ctx.token(), custom)))
                .doesNotContain("issue.delete", "project.archive");
    }

    /**
     * <strong>…and nothing more than that.</strong> A default may still be narrowed, renamed
     * and re-described by the same actor: none of those hands anybody a permission they were
     * not already inheriting, and a role that becomes unmaintainable the moment it is chosen as
     * a default would make the picker a trap.
     */
    @Test
    void narrowingAndRenamingADefaultStayLegal() throws Exception {
        var ctx = newProject();
        var lead = actorWith(ctx, "ADMIN", "TEAM_LEAD");
        var custom = duplicate(ctx, lead.token(), BuiltInRoles.PROJECT_MEMBER, "Squad");
        setProjectDefault(ctx, lead.token(), "{\"roleId\":\"" + custom + "\"}")
                .andExpect(status().isOk());

        patchRoleBody(ctx, lead.token(), custom, "{\"name\":\"Squad (EU)\"}")
                .andExpect(status().isOk());
        patchRole(ctx, lead.token(), custom, "[{\"key\":\"issue.create\",\"ownOnly\":false}]")
                .andExpect(status().isOk());
        assertThat(permissionKeys(role(ctx, ctx.token(), custom)))
                .containsExactly("issue.create");
    }

    /**
     * The workspace Owner is the root of trust inside their own workspace and is exempt here as
     * at every other ceiling — otherwise {@code project.administer.all} and every wide default
     * would be permanently unmaintainable, since no built-in role can be edited at all.
     */
    @Test
    void theOwnerMayWidenARoleThatIsTheDefault() throws Exception {
        var ctx = newProject();
        var custom = duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "QA");
        patchWorkspace(ctx, ctx.token(), "{\"defaultProjectRoleId\":\"" + custom + "\"}")
                .andExpect(status().isOk());

        patchRole(ctx, ctx.token(), custom, allProjectPermissions())
                .andExpect(status().isOk());
        assertThat(permissionKeys(role(ctx, ctx.token(), custom))).contains("project.archive");
    }

    /**
     * A role that is <em>not</em> a default is untouched by any of this — the primary recipe
     * ("duplicate Contributor, add {@code sprint.manage}") must keep working for a
     * non-Owner, and a guard that quietly became "role editing needs an Owner" would be a far
     * bigger regression than the hole it closed.
     */
    @Test
    void aRoleThatIsNotADefaultIsStillFreelyWidened() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        var custom = duplicate(ctx, admin.token(), BuiltInRoles.PROJECT_MEMBER, "Sprint lead");

        patchRole(ctx, admin.token(), custom, allProjectPermissions())
                .andExpect(status().isOk());
    }

    // ============================================ HIGH — the flip that re-activates a default

    /**
     * <strong>The workspace default.</strong> An actor refused
     * {@code {"defaultProjectRoleId": <Team lead>}} must not get the same outcome from
     * {@code {"projectAccessMode":"OPEN"}} — the flip is what makes that value apply to every
     * member with no explicit row.
     *
     * <p>The preview is asserted alongside the write: a ceiling failure surfaces as the ordinary
     * 403 from both, never as a "would fail" field in a 200 body (AC 29).
     */
    @Test
    void flippingBackToOpenIsRefusedWhenTheDeclaredDefaultIsAboveTheActor() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        // The Owner declares Team lead and restricts the workspace: the default is now stored
        // and inert, which §2 says must still be bounded.
        patchWorkspace(ctx, ctx.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_TEAM_LEAD + "\"}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isOk());

        // The value itself is refused to the Admin — the ceiling section already proves this…
        patchWorkspace(ctx, admin.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_TEAM_LEAD + "\"}")
                .andExpect(status().isForbidden());
        // …and so is the one field that makes it live again.
        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.member.manage")));
        preview(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isForbidden());

        assertThat(mode(ctx)).as("the refused flip must not have been applied").isEqualTo("STRICT");

        // Satisfiable, and by the person refused: narrow the default first — it is inert while
        // the workspace is restricted — then flip.
        patchWorkspace(ctx, ctx.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_MEMBER + "\"}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isOk());
    }

    /**
     * <strong>The per-project overrides go live too</strong>, and the body names none of them.
     * A workspace default inside the baseline is not enough: one project declaring Project
     * admin is the same grant with a narrower audience.
     */
    @Test
    void flippingBackToOpenAlsoBoundsPerProjectDeclaredDefaults() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        setProjectDefault(ctx, ctx.token(),
                "{\"roleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isOk());

        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("project.archive")));

        // Naming the workspace default in the same body does not buy the flip either: the
        // per-project override is what is out of reach, and it is not what was named.
        patchWorkspace(ctx, admin.token(),
                "{\"projectAccessMode\":\"OPEN\",\"defaultProjectRoleId\":\""
                + BuiltInRoles.PROJECT_VIEWER + "\"}")
                .andExpect(status().isForbidden());

        assertThat(mode(ctx)).isEqualTo("STRICT");
        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isOk());
    }

    /**
     * <strong>The other half, and the one that keeps {@code OPEN} the identity.</strong> When
     * every declared default is inside the actor's baseline the flip costs nothing — including
     * the shipped configuration, where nothing is declared at all and the chain falls through
     * to the built-in Contributor.
     */
    @Test
    void flippingBackToOpenIsFreeWhenEveryDeclaredDefaultIsSettable() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        setProjectDefault(ctx, ctx.token(),
                "{\"roleId\":\"" + BuiltInRoles.PROJECT_VIEWER + "\"}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isOk());

        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isOk());
        assertThat(mode(ctx)).isEqualTo("OPEN");

        // The OTHER direction stays free, and deliberately so — the asymmetry is the point.
        // STRICT takes inheritance away, so there is no grant to bound: an Admin may restrict a
        // workspace whose declared default is above them (doors 7 and 8 are what stop that
        // stranding a project), and is then refused the way back until an Owner narrows the
        // default or performs the flip. A ceiling on the narrowing direction would be a rule
        // with no escalation behind it.
        patchWorkspace(ctx, ctx.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_TEAM_LEAD + "\"}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isForbidden());
    }

    // ====================================== the refusal has to be actionable by its reader

    /**
     * <strong>A refusal that names a role and not the project it is stored on is not
     * actionable</strong> (HD-130 S7, security review round 3, the second Low).
     *
     * <p>The generic {@code WorkspaceGrantCeilingException} named "Project admin" and
     * {@code issue.delete}, on a request that touched one switch on the workspace's General
     * page — leaving the reader to open every project's settings to find which one declared it.
     * Worse, the remedy the guard's javadoc claimed ("narrow the offending default first — you
     * may set it to anything inside your own baseline") is a <em>workspace</em>-default remedy:
     * clearing a per-project override needs {@code project.member.manage} in that project,
     * which no workspace-scoped role grants, and narrowing the role itself is impossible when
     * it is a built-in. Three of the reader's four apparent options do not exist.
     *
     * <p>So this pins the whole body — {@code errorType} so a client can branch without parsing
     * prose, {@code projects} so it can link, {@code role}/{@code missing} so it can write its
     * own copy, and the sentence's honest three-part remedy — and it pins that only the
     * <em>offending</em> project is named: the workspace's other project declares a default
     * that is perfectly settable and has no business appearing in a list of things to go and
     * fix.
     */
    @Test
    void theProjectOverrideRefusalNamesTheOffendingProjectAndAnAchievableRemedy() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        var innocent = secondProject(ctx);
        setProjectDefault(ctx, ctx.token(), ctx.projectId(),
                "{\"roleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}")
                .andExpect(status().isOk());
        setProjectDefault(ctx, ctx.token(), innocent,
                "{\"roleId\":\"" + BuiltInRoles.PROJECT_VIEWER + "\"}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isOk());

        var key = projectKey(ctx, ctx.projectId());
        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("REACTIVATED_DEFAULT_ABOVE_CEILING"))
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].id").value(ctx.projectId().toString()))
                .andExpect(jsonPath("$.projects[0].key").value(key))
                .andExpect(jsonPath("$.role").value("Project admin"))
                // The same key the picker greys the same role out with — one firstNotCovered
                // call over one comparand, or the tooltip and the 403 start disagreeing.
                .andExpect(jsonPath("$.missing").value("project.archive"))
                .andExpect(jsonPath("$.detail", containsString(key)))
                .andExpect(jsonPath("$.detail", containsString("project.archive")))
                // All three remedies, because which one exists depends on facts the server
                // cannot see — and the last of them always does.
                .andExpect(jsonPath("$.detail", containsString("clear its default")))
                .andExpect(jsonPath("$.detail", containsString("ask a workspace Owner")));

        // The preview answers identically: a ceiling failure is the ordinary 403 from both
        // surfaces, never a "would fail" field in a 200 body (AC 29).
        preview(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").value("REACTIVATED_DEFAULT_ABOVE_CEILING"))
                .andExpect(jsonPath("$.projects[0].id").value(ctx.projectId().toString()));

        assertThat(mode(ctx)).as("fail closed: nothing is written").isEqualTo("STRICT");

        // And the remedy the body prescribes actually clears it. An administrator of that
        // project clears its override; the Admin who was refused can then flip, unaided.
        setProjectDefault(ctx, ctx.token(), ctx.projectId(), "{\"inherit\":true}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isOk());
    }

    /**
     * <strong>Every</strong> project declaring the offending role is named, not the first one
     * found — the reader's next action is "go and fix these", and a list that stops at one
     * turns one round trip into as many as there are projects.
     */
    @Test
    void theRefusalNamesEveryProjectDeclaringTheOffendingRole() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        var second = secondProject(ctx);
        for (var project : List.of(ctx.projectId(), second)) {
            setProjectDefault(ctx, ctx.token(), project,
                    "{\"roleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}")
                    .andExpect(status().isOk());
        }
        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isOk());

        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.projects.length()").value(2))
                .andExpect(jsonPath("$.detail", containsString(projectKey(ctx, second))));
    }

    /**
     * <strong>Calibration: the workspace default keeps the generic refusal.</strong> There is no
     * project to name — the value is on the workspace row the caller is already editing — so a
     * {@code projects} extension there would be an empty list a client has to special-case, and
     * an {@code errorType} would claim a next action ("go to that project") that does not apply.
     * A code per distinct next action, not per sentence.
     */
    @Test
    void theWorkspaceDefaultRefusalStaysTheGenericCeilingWithNoProjectList() throws Exception {
        var ctx = newProject();
        var admin = actorWith(ctx, "ADMIN", null);
        patchWorkspace(ctx, ctx.token(),
                "{\"defaultProjectRoleId\":\"" + BuiltInRoles.PROJECT_TEAM_LEAD + "\"}")
                .andExpect(status().isOk());
        patchWorkspace(ctx, ctx.token(), "{\"projectAccessMode\":\"STRICT\"}")
                .andExpect(status().isOk());

        patchWorkspace(ctx, admin.token(), "{\"projectAccessMode\":\"OPEN\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorType").doesNotExist())
                .andExpect(jsonPath("$.projects").doesNotExist())
                .andExpect(jsonPath("$.detail", containsString("project.member.manage")));
    }

    // ------------------------------------------------------------------ helpers

    private String rolesBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/roles";
    }

    private UUID duplicate(Ctx ctx, String token, UUID source, String name) throws Exception {
        var body = mockMvc.perform(post(rolesBase(ctx) + "/" + source + "/duplicate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + json.writeValueAsString(name) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    private ResultActions patchRole(Ctx ctx, String token, UUID roleId, String permissionsJson)
            throws Exception {
        return patchRoleBody(ctx, token, roleId, "{\"permissions\":" + permissionsJson + "}");
    }

    private ResultActions patchRoleBody(Ctx ctx, String token, UUID roleId, String body)
            throws Exception {
        return mockMvc.perform(patch(rolesBase(ctx) + "/" + roleId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode role(Ctx ctx, String token, UUID roleId) throws Exception {
        return json.readTree(mockMvc.perform(get(rolesBase(ctx) + "/" + roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions patchWorkspace(Ctx ctx, String token, String body) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions preview(Ctx ctx, String token, String body) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/project-access/preview")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions setProjectDefault(Ctx ctx, String token, String body) throws Exception {
        return setProjectDefault(ctx, token, ctx.projectId(), body);
    }

    private ResultActions setProjectDefault(Ctx ctx, String token, UUID projectId, String body)
            throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/projects/"
                        + projectId + "/default-role")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * A second live project in the ctx's workspace, administered by the ctx owner — so the
     * refusals above can be asked which projects they name, which is the whole point of them.
     */
    private UUID secondProject(Ctx ctx) {
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        var project = project(ws, ctx.owner());
        projectMember(project, ctx.owner(), "MANAGER");
        return project.getId();
    }

    /** Read back through the API, so the key asserted on is the one a client would see. */
    private String projectKey(Ctx ctx, UUID projectId) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects/" + projectId)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("key").asText();
    }


    private String mode(Ctx ctx) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/workspaces/" + ctx.wsId())
                        .header("Authorization", "Bearer " + ctx.token()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("projectAccessMode").asText();
    }

    /** The caller's effective project permissions, off the project detail read. */
    private List<String> myPermissions(Ctx ctx, String token) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var out = new ArrayList<String>();
        json.readTree(body).get("myPermissions").forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> permissionKeys(JsonNode roleNode) {
        var out = new ArrayList<String>();
        for (var p : roleNode.get("permissions")) out.add(p.get("key").asText());
        return out;
    }

    /**
     * <strong>All twenty, read from the catalog rather than typed out.</strong> The escalation
     * is "every project permission at once", so a hardcoded list would quietly stop testing it
     * the day a twenty-first is added — which is precisely when the guard matters most.
     */
    private static String allProjectPermissions() {
        var parts = new ArrayList<String>();
        for (var p : Permission.of(RoleScope.PROJECT)) {
            parts.add("{\"key\":\"" + p.key() + "\",\"ownOnly\":false}");
        }
        return "[" + String.join(",", parts) + "]";
    }
}
