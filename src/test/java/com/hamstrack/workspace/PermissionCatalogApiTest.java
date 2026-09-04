package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.LabelTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public surface S1 adds: {@code GET /api/permissions} and the {@code myPermissions}
 * field on the workspace and project responses (roles-permissions-proposal §16 S1,
 * §15.22).
 *
 * <p>{@code myPermissions} is asserted to be <strong>present and correct on every shape of
 * response</strong>, list ones included. An absent field is the failure that matters:
 * §12 requires it to be there even when empty, because a client that cannot find it falls
 * back to permissive rendering — the opposite of what a permission is for.
 *
 * <p>It is also asserted that S1 is <strong>dark</strong>: the workspace/project responses
 * a client already reads keep their {@code myRole} values byte-for-byte, so no SPA change
 * is needed before S5.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class PermissionCatalogApiTest extends LabelTestBase {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theCatalogIsTheEnumAndNothingElse() throws Exception {
        var ctx = newProject();
        var body = mockMvc.perform(get("/api/permissions")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var node = mapper.readTree(body);

        assertThat(node)
                .as(() -> "the catalog returned " + node.size() + " entries, not the 29 of §6. The count is "
                  + "defended one entry at a time there; changing it is a product decision.")
                .hasSize(29);

        var seen = new HashSet<String>();
        int workspaceScoped = 0;
        for (var entry : node) {
            var key = entry.get("key").asText();
            assertThat(seen.add(key)).withFailMessage("duplicate permission key in the catalog: " + key).isTrue();
            assertThat(Permission.byKey(key)).as("the catalog served a key the enum does not know: " + key).isPresent();
            var p = Permission.byKey(key).orElseThrow();
            assertThat(entry.get("scope").asText())
                    .as("the catalog serves the enum's own scope, not a second copy of it that can drift")
                    .isEqualTo(p.scope().name());
            assertThat(entry.get("supportsOwn").asBoolean())
                    .as("…the enum's own supportsOwn flag")
                    .isEqualTo(p.supportsOwn());
            assertThat(entry.get("ownRequired").asBoolean())
                    .as("…and the enum's own ownRequired flag")
                    .isEqualTo(p.ownRequired());
            if (p.scope() == RoleScope.WORKSPACE) workspaceScoped++;
        }
        assertThat(workspaceScoped).as("§6.1 defines 9 workspace-scoped permissions, got " + workspaceScoped).isEqualTo(9);

        // comment.edit is the one entry that can only ever be granted own-only (§17.3).
        assertThat(Permission.COMMENT_EDIT.ownRequired())
                .withFailMessage("comment.edit is the one entry that can only ever be granted own-only")
                .isTrue();
        assertThat(Permission.COMMENT_EDIT.supportsOwn())
                .withFailMessage("comment.edit is the one entry that can only ever be granted own-only")
                .isTrue();
        // The capability hints are hints: sprint.manage carries one and is still a normal
        // permission with a normal check (Rule P3, §5.3).
        assertThat(Permission.SPRINT_MANAGE.capability())
                .as("the capability hints are hints: sprint.manage carries one and is still a normal permission with a normal check")
                .isNotNull();
    }

    @Test
    void theCatalogNeedsAuthentication() throws Exception {
        mockMvc.perform(get("/api/permissions")).andExpect(status().isUnauthorized());
    }

    @Test
    void myPermissionsRidesEveryWorkspaceAndProjectResponseAndMyRoleIsUnchanged() throws Exception {
        var ctx = newProject();
        var auth = "Bearer " + ctx.token();

        // --- workspace detail + list ---
        for (var url : new String[]{"/api/workspaces/" + ctx.wsId(), "/api/workspaces"}) {
            var body = mockMvc.perform(get(url).header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var node = mapper.readTree(body);
            var ws = node.isArray() ? first(node, ctx.wsId().toString()) : node;
            assertThat(ws.has("myPermissions"))
                    .withFailMessage("myPermissions is missing from " + url + " — §12 requires it on every "
                      + "workspace response, list ones included; an absent field makes a client "
                      + "render permissively.")
                    .isTrue();
            assertThat(ws.get("myPermissions").isArray())
                    .withFailMessage("myPermissions is missing from " + url + " — §12 requires it on every "
                      + "workspace response, list ones included; an absent field makes a client "
                      + "render permissively.")
                    .isTrue();
            var keys = keys(ws.get("myPermissions"));
            // The ctx owner is a workspace OWNER: 8 of the 9 workspace permissions (§7.1).
            assertThat(keys)
                    .as(() -> String.valueOf(url + " gave an OWNER " + keys.size() + " workspace permissions, not 8"))
                    .hasSize(8);
            assertThat(keys)
                    .as("an OWNER's wire permissions are the real ones, not a placeholder list: the two that name the role's reach are in it")
                    .contains("workspace.member.manage");
            assertThat(keys)
                    .as("an OWNER's wire permissions are the real ones, not a placeholder list: the two that name the role's reach are in it")
                    .contains("project.curate.all");
            assertThat(keys)
                    .as("an Owner must NOT hold project.administer.all (§7.1). Today's workspace-admin "
                      + "bypass is requireProjectCurator only — four permissions, not all twenty; "
                      + "the wide one exists for custom roles (§17.2) and is seeded on nothing. Got "
                      + keys)
                    .doesNotContain("project.administer.all");
            assertThat(ws.get("myRole").asText())
                    .as("myRole changed shape on " + url + " — S1 must be invisible to the SPA")
                    .isEqualTo("OWNER");
        }

        // --- project detail + list ---
        for (var url : new String[]{
                "/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId(),
                "/api/workspaces/" + ctx.wsId() + "/projects"}) {
            var body = mockMvc.perform(get(url).header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            var node = mapper.readTree(body);
            var p = node.isArray() ? first(node, ctx.projectId().toString()) : node;
            assertThat(p.has("myPermissions")).withFailMessage("myPermissions is missing from " + url).isTrue();
            assertThat(p.get("myPermissions").isArray()).withFailMessage("myPermissions is missing from " + url).isTrue();
            var keys = keys(p.get("myPermissions"));
            // A project MANAGER who is also a workspace OWNER: all 20 project permissions.
            assertThat(keys)
                    .as(() -> String.valueOf(url + " gave a MANAGER " + keys.size() + " project permissions, not 20"))
                    .hasSize(20);
            assertThat(keys)
                    .as("comment.edit must arrive own-qualified even for a Project admin (§17.3); got " + keys)
                    .contains("comment.edit:own");
            assertThat(keys)
                    .as("comment.edit is never grantable unrestricted (§17.3); got " + keys)
                    .doesNotContain("comment.edit");
            assertThat(keys)
                    .as("comment.delete IS unrestricted for a Project admin — the intended moderation "
                      + "widening (§10.3.5)")
                    .contains("comment.delete");
            assertThat(p.get("myRole").asText())
                    .as("myRole changed shape on " + url + " — S1 must be invisible to the SPA")
                    .isEqualTo("MANAGER");
        }
    }

    @Test
    void aWorkspaceMemberWithNoProjectRowInheritsContributorButStillReportsViewer() throws Exception {
        var ctx = newProject();
        var outsider = actorInWorkspaceOnly(ctx);

        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId())
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var p = mapper.readTree(body);
        var keys = keys(p.get("myPermissions"));

        // §5.2 + §7.2: the default chain lands on Contributor, whose 12 grants are verbatim
        // what a workspace member can do in a project today. This is the no-op promise,
        // observable through the API.
        assertThat(keys)
                .as("a workspace member with no project row must inherit Contributor in an OPEN "
                  + "workspace — that inheritance IS what makes the upgrade a no-op (§8.4). Got " + keys)
                .contains("issue.create");
        assertThat(keys)
                .as("a workspace member with no project row must inherit Contributor in an OPEN "
                  + "workspace — that inheritance IS what makes the upgrade a no-op (§8.4). Got " + keys)
                .contains("issue.transition");
        assertThat(keys)
                .as("a workspace member with no project row must inherit Contributor in an OPEN "
                  + "workspace — that inheritance IS what makes the upgrade a no-op (§8.4). Got " + keys)
                .contains("issue.rank");
        assertThat(keys)
                .as("a workspace member with no project row must inherit Contributor in an OPEN "
                  + "workspace — that inheritance IS what makes the upgrade a no-op (§8.4). Got " + keys)
                .contains("sprint.assign");
        assertThat(keys)
                .as("Contributor must not curate: sprint lifecycle and project settings are "
                  + "curator-gated today and must stay so. Got " + keys)
                .doesNotContain("sprint.manage");
        assertThat(keys)
                .as("Contributor must not curate: sprint lifecycle and project settings are "
                  + "curator-gated today and must stay so. Got " + keys)
                .doesNotContain("project.edit");

        // …and myRole stays VIEWER, exactly as before HD-123. S1 changes no wire value.
        assertThat(p.get("myRole").asText())
                .as("myRole flipped for a member with no project row. S1 is a dark slice: the effective "
                  + "role lives in myPermissions, and myRole keeps its pre-HD-123 meaning until S5.")
                .isEqualTo("VIEWER");
    }

    // ------------------------------------------------------------------ helpers

    /** A user who is a member of the workspace but NOT of the project — the common case (§2.3). */
    private String actorInWorkspaceOnly(Ctx ctx) throws Exception {
        var u = user();
        var ws = workspaceRepository.findById(ctx.wsId()).orElseThrow();
        member(ws, u, "MEMBER");
        return login(u);
    }

    private com.fasterxml.jackson.databind.JsonNode first(
            com.fasterxml.jackson.databind.JsonNode array, String id) {
        for (var n : array) {
            if (n.get("id").asText().equals(id)) return n;
        }
        throw new AssertionError("id " + id + " not present in the list response");
    }

    private HashSet<String> keys(com.fasterxml.jackson.databind.JsonNode array) {
        var out = new HashSet<String>();
        for (var n : array) out.add(n.asText());
        return out;
    }
}
