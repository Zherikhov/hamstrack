package com.hamstrack.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetItemRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>Ending the silence: a shadowed custom field is REPORTED, by both surfaces that used to
 * drop it</strong> (HD-275 §6.1, AC-1..AC-6).
 *
 * <p>Before this, a tenant whose custom field was keyed with a name the registry later claimed got
 * the worst available refusal: nothing was lost and nothing was unreachable — the field still
 * rendered on issues, still sat in field sets, still came back in {@code ProjectConfigResponse} —
 * but {@code /search/schema} omitted it <em>with no reason given</em>, and every query against its
 * key answered <strong>200 with plausible rows from the built-in field</strong>. The tenant could
 * notice only an absence.
 *
 * <p>The two rules pinned here are the ones a later change is most likely to undo:
 *
 * <ul>
 *   <li>a shadowed key goes in {@code shadowedFields} and <strong>never in {@code fields}</strong>
 *       — every entry in {@code fields} is a name the caller may write and that means what the
 *       entry says it means, and offering a shadowed key there would have the product's own
 *       autocomplete suggest a name whose answers come from somewhere else;</li>
 *   <li>{@code AdminFieldResponse.shadowedBy} is populated for an <strong>archived</strong> row
 *       while {@code /schema} is silent about it — two similar-looking predicates that differ on
 *       exactly this row shape, which is why both directions are pinned.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ShadowedFieldReportingTest {

    @Autowired MockMvc mockMvc;
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired FieldSetRepository fieldSetRepository;
    @Autowired FieldSetItemRepository fieldSetItemRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Every {@code field_defs} row this class inserted, in insertion order — see
     * {@link #removeTheClaimedKeysThisTestInserted()}.
     */
    private final List<UUID> insertedFieldIds = new ArrayList<>();

    /**
     * <strong>Delete what was inserted, because what was inserted is a permanent WARN.</strong>
     * This class writes <em>live</em> claimed-key {@code field_defs} rows straight through the
     * repository — the whole point, since that is how the shadowed population really arose — and
     * every one it leaves behind is a row {@code ShadowedFieldStartupScan} then names at the boot
     * of every Spring context in the suite, for ever. Left uncleaned it measured 5,353 WARN lines,
     * 31% of one build log, and the count grew monotonically run over run: a shared dev database
     * keeps the rows, so each full run adds another dozen across every context that starts.
     * {@code ShadowedFieldStartupScanTest} deletes its rows inline for the same reason; this one
     * has too many insertion sites for that, so the bookkeeping is central and the delete is here.
     *
     * <p>{@code field_set_items} and {@code issue_field_values} both reference {@code field_defs}
     * with {@code ON DELETE CASCADE} (V1), so removing the definition is enough and no order has
     * to be maintained here.
     */
    @AfterEach
    void removeTheClaimedKeysThisTestInserted() {
        fieldDefRepository.deleteAllById(insertedFieldIds);
        insertedFieldIds.clear();
    }

    /**
     * <strong>AC-1 — reported, with the built-in name that took the key, and absent from
     * {@code fields}.</strong>
     *
     * <p>{@code shadowedBy} is the CANONICAL name ({@code label}, not the {@code labels} alias the
     * tenant's key spells): that is the field whose rows a query would actually answer from, which
     * is what a reader needs to be told.
     */
    @Test
    void aShadowedFieldIsReportedWithItsClaimantAndIsNotOfferedAsVocabulary() throws Exception {
        var ws = newWorkspaceWithField("labels", "Team labels");

        mockMvc.perform(get(schemaUrl(ws)).header("Authorization", "Bearer " + ws.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shadowedFields[0].key").value("labels"))
                .andExpect(jsonPath("$.shadowedFields[0].name").value("Team labels"))
                .andExpect(jsonPath("$.shadowedFields[0].shadowedBy").value("label"))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("labels"))));
    }

    /**
     * <strong>AC-2 — no cross-tenant leak.</strong> The list is built from the caller's own
     * {@code ResolutionContext}, which is assembled from their visible projects, so a shadowed
     * field in somebody else's workspace is not merely filtered out — it was never in the data
     * this response is computed from, and no repository query was issued to find it.
     */
    @Test
    void anotherWorkspacesShadowedFieldIsInvisibleHere() throws Exception {
        newWorkspaceWithField("labels", "Team labels");
        var other = newWorkspaceWithField(null, null);

        mockMvc.perform(get(schemaUrl(other)).header("Authorization", "Bearer " + other.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shadowedFields").isEmpty());
    }

    /**
     * <strong>AC-3 — empty is absent-shaped, not error-shaped.</strong> An unshadowed workspace
     * gets {@code []} and a 200; nothing about this feature may turn into a status code.
     */
    @Test
    void anUnshadowedWorkspaceReportsAnEmptyList() throws Exception {
        var ws = newWorkspaceWithField("team_labels", "Team labels");

        mockMvc.perform(get(schemaUrl(ws)).header("Authorization", "Bearer " + ws.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shadowedFields").isEmpty())
                .andExpect(jsonPath("$.fields[*].name", hasItem("team_labels")));
    }

    /**
     * <strong>AC-5 — sorted by key, and the same on the next request.</strong>
     * {@code customFieldsByKey} is a {@code LinkedHashMap} in visible-project iteration order,
     * which is not stable between requests; a list whose order drifts under a client diffing it is
     * a list that looks like it changed.
     */
    @Test
    void theListIsSortedByKeyAndStableAcrossRequests() throws Exception {
        var ws = newWorkspaceWithField("sprint", "Team sprint");
        bindField(ws, "components", "Team components");
        bindField(ws, "labels", "Team labels");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(schemaUrl(ws)).header("Authorization", "Bearer " + ws.token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.shadowedFields[*].key",
                            contains("components", "labels", "sprint")));
        }
    }

    /**
     * <strong>AC-4 — the admin console sees it too</strong>, on the same field and not on an
     * ordinary one. The console is where the remedy lives, so this is the surface that has to
     * carry the flag.
     */
    @Test
    void theAdminListingCarriesShadowedByOnlyForAClaimedKey() throws Exception {
        var ws = newWorkspaceWithField("labels", "Team labels");
        var plain = bindField(ws, "team_notes", "Team notes");

        mockMvc.perform(get(adminFieldsUrl(ws)).header("Authorization", "Bearer " + ws.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + ws.fieldId + "')].shadowedBy").value("label"))
                .andExpect(jsonPath("$[?(@.id == '" + plain.getId() + "')].shadowedBy")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }

    /**
     * <strong>AC-6 — the two predicates diverge on an archived row, deliberately.</strong>
     *
     * <p>An archived def is out of resolution, so it is not shadowed and no warning surface
     * mentions it. But {@code shadowedBy} is archive-blind, because the rename affordance it
     * drives must be offered for an archived row too: that row is the one for which the licensing
     * invariant holds hardest, and rename-then-unarchive is the recovery path for a field somebody
     * archived <em>because</em> it had stopped working.
     */
    @Test
    void anArchivedShadowedFieldIsSilentInSchemaAndStillFlaggedInAdmin() throws Exception {
        var ws = newWorkspaceWithField("labels", "Team labels");
        var def = fieldDefRepository.findById(ws.fieldId).orElseThrow();
        def.setArchivedAt(Instant.now());
        fieldDefRepository.saveAndFlush(def);

        mockMvc.perform(get(schemaUrl(ws)).header("Authorization", "Bearer " + ws.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shadowedFields").isEmpty());

        mockMvc.perform(get(adminFieldsUrl(ws)).header("Authorization", "Bearer " + ws.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + ws.fieldId + "')].shadowedBy").value("label"));
    }

    // ==================================================================== fixtures

    private record Ctx(UUID wsId, UUID projectId, UUID setId, String token, UUID fieldId) {}

    private static String schemaUrl(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId + "/search/schema";
    }

    private static String adminFieldsUrl(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId + "/admin/fields";
    }

    /**
     * A workspace whose project is bound to a field set carrying one workspace-scoped custom
     * field under the given key — inserted through the repository, which is how the shadowed
     * population really arose (the reserved-key guard is never retroactive, and postdates those
     * rows besides).
     */
    private Ctx newWorkspaceWithField(String key, String name) throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner);
        var project = project(ws, owner);
        var set = new FieldSet();
        set.setName("Set-" + System.nanoTime());
        set.setScopeWorkspaceId(ws.getId());
        set = fieldSetRepository.save(set);
        project.setFieldSet(set);
        projectRepository.save(project);

        var ctx = new Ctx(ws.getId(), project.getId(), set.getId(), login(owner), null);
        UUID fieldId = key == null ? null : bindField(ctx, key, name).getId();
        return new Ctx(ctx.wsId, ctx.projectId, ctx.setId, ctx.token, fieldId);
    }

    /** Add one more workspace-scoped field to the ctx set. */
    private FieldDef bindField(Ctx ctx, String key, String name) {
        var f = new FieldDef();
        f.setScopeWorkspaceId(ctx.wsId);
        f.setKey(key);
        f.setName(name);
        f.setType(FieldType.TEXT);
        f = fieldDefRepository.saveAndFlush(f);
        insertedFieldIds.add(f.getId());

        var item = new FieldSetItem();
        item.setSet(fieldSetRepository.findById(ctx.setId).orElseThrow());
        item.setField(f);
        item.setPosition((short) fieldSetItemRepository.count());
        item.setRequired(false);
        item.setShowOnCreate(true);
        fieldSetItemRepository.save(item);
        return f;
    }

    private User user() {
        var u = new User();
        u.setEmail(("shf-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("shf-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void member(Workspace ws, User u) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(u);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(m);
    }

    private Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Proj");
        p.setKey("P" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }
}
