package com.hamstrack.search.filter;

import com.hamstrack.common.security.RoleScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration coverage for HD-26 (Saved filters — personal + shared), per the
 * acceptance checklist in docs/design/advanced-search-hql-proposal.md §14 (HD-26) and
 * the CRUD contract in §8.3. Bootstraps data through the repository layer like the
 * other search tests.
 *
 * <p>Covers: create (valid HQL) → 201; create with invalid HQL → 422 (parse +
 * semantic shapes); own + shared visibility (private filter 404s for a non-owner,
 * shared is readable but not writable); owner-only mutation (non-owner PATCH/DELETE →
 * 404 even when shared, §16.5); duplicate name per (workspace,owner) → 409; non-member
 * → 404 everywhere; cross-workspace isolation; and the delete-usage warning hook
 * (empty in MVP).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SavedFilterApiTest {

    @Autowired MockMvc mockMvc;
    // HD-123: memberships carry a roles row now; reference() resolves a built-in with no query.
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    private static final String VALID_HQL = "assignee = currentUser() AND status = \"In Progress\"";

    // ============================================================ create + validation

    @Test
    void createWithValidHqlReturns201() throws Exception {
        var ws = newWorkspace();
        create(ws, ws.ownerToken, "My in-progress", VALID_HQL, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("My in-progress"))
                .andExpect(jsonPath("$.hql").value(VALID_HQL))
                .andExpect(jsonPath("$.shared").value(false))
                .andExpect(jsonPath("$.ownerId").value(ws.owner.getId().toString()))
                .andExpect(jsonPath("$.ownerName").exists())
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void createWithEmptyHqlIsAllowed() throws Exception {
        var ws = newWorkspace();
        create(ws, ws.ownerToken, "Everything", "", false)
                .andExpect(status().isCreated());
    }

    @Test
    void createWithParseErrorHqlReturns422WithPosition() throws Exception {
        var ws = newWorkspace();
        // Unquoted value → parse error.
        create(ws, ws.ownerToken, "bad", "status = In Progress", false)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("PARSE_ERROR"))
                .andExpect(jsonPath("$.position").exists());
    }

    @Test
    void createWithSemanticErrorHqlReturns422WithErrorType() throws Exception {
        var ws = newWorkspace();
        create(ws, ws.ownerToken, "bad", "statuss = \"To Do\"", false)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("SEMANTIC_ERROR"))
                .andExpect(jsonPath("$.field").value("statuss"));
    }

    @Test
    void duplicateNameForSameOwnerReturns409() throws Exception {
        var ws = newWorkspace();
        create(ws, ws.ownerToken, "dup", VALID_HQL, false).andExpect(status().isCreated());
        create(ws, ws.ownerToken, "dup", VALID_HQL, false).andExpect(status().isConflict());
    }

    @Test
    void differentOwnersMayReuseAName() throws Exception {
        var ws = newWorkspace();
        var other = addMember(ws);
        create(ws, ws.ownerToken, "shared-name", VALID_HQL, false).andExpect(status().isCreated());
        create(ws, other.token, "shared-name", VALID_HQL, false).andExpect(status().isCreated());
    }

    // ============================================================ list / visibility

    @Test
    void listReturnsOwnAndSharedButNotOthersPrivate() throws Exception {
        var ws = newWorkspace();
        var other = addMember(ws);

        var mine = createId(ws, ws.ownerToken, "mine", VALID_HQL, false);
        var othersShared = createId(ws, other.token, "others-shared", VALID_HQL, true);
        var othersPrivate = createId(ws, other.token, "others-private", VALID_HQL, false);

        listFilters(ws.wsId, ws.ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(mine)))
                .andExpect(jsonPath("$[*].id", hasItem(othersShared)))
                .andExpect(jsonPath("$[*].id", not(hasItem(othersPrivate))));
    }

    @Test
    void getSharedFilterByAnotherMemberReturnsItReadOnly() throws Exception {
        var ws = newWorkspace();
        var other = addMember(ws);
        var id = createId(ws, ws.ownerToken, "shared", VALID_HQL, true);

        // Another member can read it, sees mine=false.
        getFilter(ws.wsId, id, other.token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(false))
                .andExpect(jsonPath("$.shared").value(true));
    }

    @Test
    void getPrivateFilterByNonOwnerReturns404() throws Exception {
        var ws = newWorkspace();
        var other = addMember(ws);
        var id = createId(ws, ws.ownerToken, "private", VALID_HQL, false);

        getFilter(ws.wsId, id, other.token).andExpect(status().isNotFound());
    }

    // ============================================================ owner-only mutation

    @Test
    void ownerCanUpdateOwnFilter() throws Exception {
        var ws = newWorkspace();
        var id = createId(ws, ws.ownerToken, "orig", VALID_HQL, false);

        update(ws.wsId, id, ws.ownerToken, "{\"name\":\"renamed\",\"shared\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"))
                .andExpect(jsonPath("$.shared").value(true));
    }

    @Test
    void updateWithInvalidHqlReturns422() throws Exception {
        var ws = newWorkspace();
        var id = createId(ws, ws.ownerToken, "orig", VALID_HQL, false);

        update(ws.wsId, id, ws.ownerToken, "{\"hql\":\"status = In Progress\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorType").value("PARSE_ERROR"));
    }

    @Test
    void updateWithBlankNameReturns400NotHqlEnvelope() throws Exception {
        var ws = newWorkspace();
        var id = createId(ws, ws.ownerToken, "orig", VALID_HQL, false);

        // A blank name on a partial update is a plain input-validation failure — 400,
        // not the 422 HQL-error envelope (errorType) used for query errors.
        update(ws.wsId, id, ws.ownerToken, "{\"name\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorType").doesNotExist());
    }

    @Test
    void nonOwnerUpdatingSharedFilterReturns404() throws Exception {
        var ws = newWorkspace();
        var other = addMember(ws);
        var id = createId(ws, ws.ownerToken, "shared", VALID_HQL, true);

        // Even though it's shared and readable, a non-owner cannot mutate — 404, not 403.
        update(ws.wsId, id, other.token, "{\"name\":\"hijack\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanDeleteOwnFilter() throws Exception {
        var ws = newWorkspace();
        var id = createId(ws, ws.ownerToken, "todelete", VALID_HQL, false);

        deleteFilter(ws.wsId, id, ws.ownerToken).andExpect(status().isNoContent());
        getFilter(ws.wsId, id, ws.ownerToken).andExpect(status().isNotFound());
    }

    @Test
    void nonOwnerDeletingSharedFilterReturns404() throws Exception {
        var ws = newWorkspace();
        var other = addMember(ws);
        var id = createId(ws, ws.ownerToken, "shared", VALID_HQL, true);

        deleteFilter(ws.wsId, id, other.token).andExpect(status().isNotFound());
        // Still there for the owner.
        getFilter(ws.wsId, id, ws.ownerToken).andExpect(status().isOk());
    }

    // ============================================================ usage hook

    @Test
    void usageHookReturnsEmptyForOwner() throws Exception {
        var ws = newWorkspace();
        var id = createId(ws, ws.ownerToken, "f", VALID_HQL, false);

        mockMvc.perform(get("/api/workspaces/" + ws.wsId + "/filters/" + id + "/usage")
                        .header("Authorization", "Bearer " + ws.ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usages").isArray())
                .andExpect(jsonPath("$.usages.length()").value(0));
    }

    @Test
    void usageHookForNonOwnerReturns404() throws Exception {
        var ws = newWorkspace();
        var other = addMember(ws);
        var id = createId(ws, ws.ownerToken, "shared", VALID_HQL, true);

        mockMvc.perform(get("/api/workspaces/" + ws.wsId + "/filters/" + id + "/usage")
                        .header("Authorization", "Bearer " + other.token))
                .andExpect(status().isNotFound());
    }

    // ============================================================ tenancy

    @Test
    void nonMemberGets404OnAllEndpoints() throws Exception {
        var ws = newWorkspace();
        var id = createId(ws, ws.ownerToken, "f", VALID_HQL, true);
        var outsider = login(user());

        listFilters(ws.wsId, outsider).andExpect(status().isNotFound());
        getFilter(ws.wsId, id, outsider).andExpect(status().isNotFound());
        create(ws, outsider, "x", VALID_HQL, false).andExpect(status().isNotFound());
        update(ws.wsId, id, outsider, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        deleteFilter(ws.wsId, id, outsider).andExpect(status().isNotFound());
    }

    @Test
    void crossWorkspaceFilterIsNotVisibleOrGettable() throws Exception {
        var a = newWorkspace();
        var b = newWorkspace();
        var idInA = createId(a, a.ownerToken, "a-shared", VALID_HQL, true);

        // B's owner (member of B only) can neither list nor get A's filter.
        listFilters(b.wsId, b.ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(idInA))));
        // Getting A's id under workspace B → 404 (scoped by workspace_id).
        getFilter(b.wsId, idInA, b.ownerToken).andExpect(status().isNotFound());
    }

    // ============================================================ helpers

    private record Ws(UUID wsId, User owner, String ownerToken) {}

    private record Member(User user, String token) {}

    private Ws newWorkspace() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, "OWNER");
        return new Ws(ws.getId(), owner, login(owner));
    }

    /** Add a second member to an existing workspace and log them in. */
    private Member addMember(Ws ws) throws Exception {
        var u = user();
        var workspace = workspaceRepository.findById(ws.wsId).orElseThrow();
        member(workspace, u, "MEMBER");
        return new Member(u, login(u));
    }

    // ---- filter HTTP ----

    private ResultActions create(Ws ws, String token, String name, String hql, boolean shared) throws Exception {
        var body = "{\"name\":" + json.writeValueAsString(name)
                + ",\"hql\":" + json.writeValueAsString(hql)
                + ",\"shared\":" + shared + "}";
        return mockMvc.perform(post("/api/workspaces/" + ws.wsId + "/filters")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String createId(Ws ws, String token, String name, String hql, boolean shared) throws Exception {
        var resp = create(ws, token, name, hql, shared)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asText();
    }

    private ResultActions listFilters(UUID wsId, String token) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + wsId + "/filters")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions getFilter(UUID wsId, String id, String token) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + wsId + "/filters/" + id)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions update(UUID wsId, String id, String token, String body) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + wsId + "/filters/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteFilter(UUID wsId, String id, String token) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/" + wsId + "/filters/" + id)
                .header("Authorization", "Bearer " + token));
    }

    // ---- entity bootstrap ----

    private User user() {
        var u = new User();
        u.setEmail(("u-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("ws-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
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

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
