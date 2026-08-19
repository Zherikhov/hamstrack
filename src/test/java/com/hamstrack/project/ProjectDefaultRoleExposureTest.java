package com.hamstrack.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.SprintTestBase;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import java.util.LinkedHashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-129 (S6) — <strong>{@code ProjectResponse.defaultRole}</strong>, the §5.2 chain made
 * visible: {@code projects.default_project_role_id} → {@code workspaces
 * .default_project_role_id} → the built-in Contributor.
 *
 * <p>Why it is the important half of Project People: almost nobody has an explicit
 * {@code project_members} row, so for most workspaces the default role is the <em>only</em>
 * access control anyone ever sets. Until now the card could describe the mechanism and count
 * who was covered by it, but not say what they got — a description of a privilege with the
 * privilege missing.
 *
 * <p>Two chain links, not one, and that is the point of the shape: a card showing only the
 * effective value cannot explain where it came from, which is the difference between "this
 * project overrides the workspace" and "this project inherits".
 *
 * <p><strong>Read-only.</strong> The picker and the grant ceiling that must bound it are
 * S7's — {@code defaultProjectRole} hands the named role to every workspace member with no
 * explicit row, so an unbounded picker lets an Admin grant permissions they do not hold
 * themselves. {@link #thereIsNoWritePathAndABodyCannotInventOne()} pins that this slice did
 * not accidentally ship that door.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ProjectDefaultRoleExposureTest extends SprintTestBase {

    /**
     * The shipped default everywhere today: V14 leaves both columns NULL, so the chain falls
     * through to the built-in Contributor. Both fields are <strong>present and null</strong>
     * rather than absent — an absent field makes a client fall back to a guess.
     */
    @Test
    void theChainIsNullUntilSomethingOverridesIt() throws Exception {
        var ctx = newProject();

        var project = projectDetail(ctx);
        assertThat(project.has("defaultRole")).isTrue();
        assertThat(project.get("defaultRole").get("projectRoleId").isNull()).isTrue();
        assertThat(project.get("defaultRole").get("workspaceRoleId").isNull()).isTrue();

        // …and on the list, which is the response the SPA actually holds on most screens.
        assertThat(listed(ctx).get("defaultRole").get("workspaceRoleId").isNull()).isTrue();
    }

    /**
     * <strong>Both links are reported, so a client can tell an override from an
     * inheritance.</strong> Two projects of one workspace: one carries its own default, the
     * other inherits the workspace default. Same {@code workspaceRoleId} in both, and only
     * the overriding project names a {@code projectRoleId}.
     *
     * <p>Written straight to the columns because there is deliberately no API that writes
     * them (S7) — the fixture is the whole reason this read exists ahead of the write.
     */
    @Test
    void bothLinksOfTheChainAreReported() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var override = customProjectRole(ctx.wsId(), "delivery-guest", Permission.ISSUE_CREATE);
        plantWorkspaceDefault(ctx.wsId(), BuiltInRoles.PROJECT_VIEWER);
        plantProjectDefault(ctx.projectId(), override);

        var overriding = projectDetail(ctx).get("defaultRole");
        assertThat(UUID.fromString(overriding.get("projectRoleId").asText())).isEqualTo(override);
        assertThat(UUID.fromString(overriding.get("workspaceRoleId").asText()))
                .as("the workspace link must ride along even when the project overrides it — "
                    + "otherwise the card cannot say WHERE the value came from")
                .isEqualTo(BuiltInRoles.PROJECT_VIEWER);

        var inheriting = projectDetail(sibling).get("defaultRole");
        assertThat(inheriting.get("projectRoleId").isNull())
                .as("no override means null, not the inherited value copied down")
                .isTrue();
        assertThat(UUID.fromString(inheriting.get("workspaceRoleId").asText()))
                .isEqualTo(BuiltInRoles.PROJECT_VIEWER);
    }

    /**
     * <strong>No write path, and a body cannot invent one.</strong> The project PATCH ignores
     * both the nested object and a flattened guess at it; the columns are untouched.
     *
     * <p>This is not tidiness. The default role is handed to every workspace member without
     * an explicit row, so accepting it here — before S7's grant ceiling bounds what may be
     * named — would let an Admin grant a role holding permissions they do not hold
     * themselves, in one PATCH, to the whole workspace at once.
     */
    @Test
    void thereIsNoWritePathAndABodyCannotInventOne() throws Exception {
        var ctx = newProject();
        var wider = customProjectRole(ctx.wsId(), "sneaky-default", Permission.ISSUE_DELETE);

        mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId())
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\","
                                 + "\"defaultProjectRoleId\":\"" + wider + "\","
                                 + "\"defaultRole\":{\"projectRoleId\":\"" + wider + "\","
                                 + "\"workspaceRoleId\":\"" + wider + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                // value(nullValue()), not doesNotExist(): both fields ARE present and null
                // (theChainIsNullUntilSomethingOverridesIt pins exactly that), so
                // doesNotExist() tolerates the value under test AND would keep passing if
                // the fields were dropped from the DTO altogether. This says what it means
                // — the chain is still empty — and fails both on a write that landed and
                // on a field that left.
                .andExpect(jsonPath("$.defaultRole.projectRoleId").value(nullValue()))
                .andExpect(jsonPath("$.defaultRole.workspaceRoleId").value(nullValue()));

        assertThat(projectRepository.findById(ctx.projectId()).orElseThrow().getDefaultProjectRoleId())
                .as("a default-role write landed. That is the escalation S7 exists to bound: it "
                    + "hands the named role to EVERY workspace member with no explicit row.")
                .isNull();
        assertThat(workspaceRepository.findById(ctx.wsId()).orElseThrow().getDefaultProjectRoleId())
                .isNull();
    }

    // ==================================================== helpers

    private JsonNode projectDetail(Ctx ctx) throws Exception {
        return json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId())
                                .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode listed(Ctx ctx) throws Exception {
        var body = json.readTree(mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (var p : body) {
            if (p.get("id").asText().equals(ctx.projectId().toString())) return p;
        }
        throw new AssertionError("project missing from its own workspace listing: " + body);
    }

    private UUID customProjectRole(UUID workspaceId, String key, Permission... permissions) {
        return txTemplate.execute(status -> {
            var role = new Role();
            role.setWorkspaceId(workspaceId);
            role.setScope(RoleScope.PROJECT);
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
    }

    private void plantWorkspaceDefault(UUID workspaceId, UUID roleId) {
        txTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE workspaces SET default_project_role_id = :role WHERE id = :id")
                .setParameter("role", roleId)
                .setParameter("id", workspaceId)
                .executeUpdate());
    }

    private void plantProjectDefault(UUID projectId, UUID roleId) {
        txTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE projects SET default_project_role_id = :role WHERE id = :id")
                .setParameter("role", roleId)
                .setParameter("id", projectId)
                .executeUpdate());
    }
}
