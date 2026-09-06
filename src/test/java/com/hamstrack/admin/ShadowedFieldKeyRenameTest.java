package com.hamstrack.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.admin.dto.UpsertFieldRequest;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.admin.service.AdminFieldService;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetItemRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.issue.repository.IssueFieldValueRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.search.FieldRegistry;
import com.hamstrack.search.RetiredFieldAliases;
import com.hamstrack.search.ShadowedFields;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The exit: a custom field's key is renameable exactly while a built-in search name
 * shadows it</strong> (ADR-0036, HD-275 §6.2, AC-11..AC-22).
 *
 * <p>{@code FieldDef.key} was {@code updatable = false} and {@code updateField} ignored
 * {@code req.key()}, so the only remedy the product offered a shadowed tenant was "create a new
 * field under a different key" — which loses every stored value and every field-set placement, for
 * a defect the product introduced by registering a name. This class pins the replacement and, more
 * importantly, its <em>boundary</em>: the population where the rename is safe is the one where it
 * provably cannot change what any saved filter means.
 *
 * <blockquote>While {@code labels} is claimed by the registry, a filter reading {@code labels = "x"}
 * resolves to the built-in {@code label} field — before the rename and after it, because the
 * registry still claims the name. The tenant's own field was unreachable from HQL beforehand and is
 * unreachable under its old name afterwards. Nothing any stored query means changes.</blockquote>
 *
 * <p>The two refusals whose absence would be silent rather than loud are pinned first: renaming an
 * unshadowed field (422 — the general immutability rule, which is true again the moment a field
 * leaves the shadow) and a rename triggered by <em>presence</em> rather than <em>difference</em>
 * (which would 422 every ordinary edit of every custom field, because a client that echoes the
 * stored key back on save — as this console did until HD-275, and as any round-tripping client
 * naturally does — changes nothing by sending it).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ShadowedFieldKeyRenameTest {

    @Autowired MockMvc mockMvc;
    @Autowired com.hamstrack.workspace.service.RoleCatalog roleCatalog;
    @Autowired AdminFieldService fieldService;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired FieldSetRepository fieldSetRepository;
    @Autowired FieldSetItemRepository fieldSetItemRepository;
    @Autowired IssueFieldValueRepository valueRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired FieldRegistry registry;
    @Autowired ShadowedFields shadowedFields;

    private final ObjectMapper json = new ObjectMapper();

    private static final String RESERVED_MESSAGE_FRAGMENT = "is a reserved search field name";

    /** Every {@code field_defs} row this class inserted — see {@link #removeTheFieldsThisTestInserted()}. */
    private final java.util.List<UUID> insertedFieldIds = new java.util.ArrayList<>();

    /**
     * <strong>Delete what was inserted, because a live claimed-key row is a permanent WARN.</strong>
     * Every fixture here writes one straight through the repository — deliberately, since that is
     * how the shadowed population really arose — and a row left behind is one
     * {@code ShadowedFieldStartupScan} names at the boot of every Spring context in the suite,
     * for ever, on a shared database that keeps it. {@code ShadowedFieldReportingTest} carries the
     * same block for the same reason.
     *
     * <p>{@code field_set_items} and {@code issue_field_values} reference {@code field_defs} with
     * {@code ON DELETE CASCADE} (V1), so the definition is the only row to remove, and
     * {@code deleteAllById} is a no-op for one a test already deleted.
     */
    @org.junit.jupiter.api.AfterEach
    void removeTheFieldsThisTestInserted() {
        fieldDefRepository.deleteAllById(insertedFieldIds);
        insertedFieldIds.clear();
    }

    // ============================================================== the rename itself

    /**
     * <strong>AC-11 / AC-12 — the whole point, end to end.</strong> The rename answers 200, moves
     * no {@code issue_field_values} row (they are keyed by {@code field_id}, a UUID), and the field
     * becomes searchable under the new name while the old name keeps meaning the built-in field.
     *
     * <p>The last assertion is the invariant that licenses the feature, stated as a test: after the
     * rename, {@code labels = "…"} still answers from built-in label links. Nothing a saved filter
     * says changed meaning.
     */
    @Test
    void aShadowedFieldIsRenamedAndBecomesSearchableWithoutMovingAnyValue() throws Exception {
        var ctx = fixture("labels", "Team labels");
        createIssue(ctx, "carries a value", "\"alpha\"");
        createIssue(ctx, "carries nothing", null);

        var def = fieldDefRepository.findById(ctx.fieldId).orElseThrow();
        long valuesBefore = valueRepository.countByField(def);
        assertThat(valuesBefore).as("the fixture must actually carry a value").isEqualTo(1);

        rename(ctx, ctx.fieldId, "team_labels")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("team_labels"))
                .andExpect(jsonPath("$.shadowedBy").value(org.hamcrest.Matchers.nullValue()));

        assertThat(valueRepository.countByField(fieldDefRepository.findById(ctx.fieldId).orElseThrow()))
                .as("a rename moves no rows — values are keyed by field_id, set membership too, "
                    + "and history records the display NAME")
                .isEqualTo(valuesBefore);

        mockMvc.perform(get(schemaUrl(ctx)).header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[*].name", hasItem("team_labels")))
                .andExpect(jsonPath("$.shadowedFields").isEmpty());

        search(ctx, "team_labels IS NOT EMPTY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].issue.title").value("carries a value"));
        search(ctx, "team_labels IS EMPTY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        // The invariant that licenses the whole feature, as an assertion: `labels` meant the
        // built-in label field before the rename and still does after it, because the registry
        // still claims the name. Neither issue carries a built-in label, so the built-in reading
        // matches BOTH while the custom-field reading would have matched only one — which is what
        // makes this able to tell the two apart.
        search(ctx, "labels IS EMPTY")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /**
     * <strong>AC-13 — the refusal that is the whole guard.</strong> Outside the shadowed
     * population the general argument for immutability is true, so the message names it: saved
     * filters are stored as text and would silently change what they match.
     */
    @Test
    void renamingAnUnshadowedFieldIsRefused422() throws Exception {
        var ctx = fixture("team_notes", "Team notes");

        rename(ctx, ctx.fieldId, "team_remarks")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("saved filters are stored as text")))
                .andExpect(jsonPath("$.detail", containsString(
                        "only be changed while a built-in search name has taken it")));

        assertThat(fieldDefRepository.findById(ctx.fieldId).orElseThrow().getKey())
                .isEqualTo("team_notes");
    }

    /**
     * <strong>AC-14 — the compatibility rule: DIFFERENCE, never presence.</strong>
     *
     * <p>A client that initialises its key state from the field and echoes it back on save — the
     * shape of any round-tripping form, and what this console itself did until HD-275 — sends a
     * key while changing nothing. A builder who read "a key arrived" as "a rename was attempted"
     * would make every ordinary edit of every custom field, shadowed or not, answer 422. This test
     * exists solely to catch that, and it is exercised on an <em>unshadowed</em> field, where the
     * rename permission would refuse if it were consulted at all. It is deliberately written about
     * the request rather than about the SPA: the console stopped echoing in the same round that
     * added the rename, and the rule has to outlive that.
     */
    @Test
    void aKeyThatDoesNotDifferIsNeitherARenameNorARefusal() throws Exception {
        var ctx = fixture("team_notes", "Team notes");

        for (String key : new String[]{"\"team_notes\"", "null", "\"\""}) {
            mockMvc.perform(patch(fieldUrl(ctx, ctx.fieldId))
                            .header("Authorization", "Bearer " + ctx.token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Team notes\",\"type\":\"TEXT\",\"key\":" + key + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value("team_notes"));
        }

        // key omitted entirely
        mockMvc.perform(patch(fieldUrl(ctx, ctx.fieldId))
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Team notes renamed\",\"type\":\"TEXT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("team_notes"))
                .andExpect(jsonPath("$.name").value("Team notes renamed"));

        // The comparison is case-INSENSITIVE, asserted at the service because it cannot be
        // reached over HTTP: `@Pattern("[a-z0-9_]*")` on UpsertFieldRequest.key answers 400 to an
        // upper-case spelling before the service sees it (unchanged bean validation, and asserted
        // as such below). The insensitivity is therefore defence in depth rather than a live path
        // — and it is the half that would turn a client's harmless echo into a 422 if it were
        // dropped, so it is pinned where it lives.
        var echoed = new UpsertFieldRequest("Team notes renamed", "TEAM_NOTES", FieldType.TEXT, null, null);
        assertThat(fieldService.updateField(null, ScopeContext.workspace(ctx.wsId), ctx.fieldId, echoed).key())
                .isEqualTo("team_notes");

        mockMvc.perform(patch(fieldUrl(ctx, ctx.fieldId))
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Team notes renamed\",\"type\":\"TEXT\",\"key\":\"TEAM_NOTES\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * <strong>AC-15 / §7.4 — one shadow to another is refused, and the message says why.</strong>
     * Without this the tenant walks from {@code labels} into {@code components}, is shadowed again,
     * is therefore still permitted to rename, and can repeat that forever without ever escaping.
     * Rule 4 runs before rule 5 so the refusal names the real problem rather than the target's
     * occupancy.
     */
    @Test
    void renamingOntoAnotherReservedNameIsRefused409() throws Exception {
        var ctx = fixture("labels", "Team labels");

        rename(ctx, ctx.fieldId, "components")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString(RESERVED_MESSAGE_FRAGMENT)));
    }

    /**
     * <strong>AC-16 / §7.2 — the target's occupancy is checked WIDER than the scope</strong>
     * (global ∪ ancestor workspace ∪ own project), the same reach create uses. A workspace field
     * renamed onto a key its own projects already use would recreate this very ticket one layer
     * down, with us as the shadowing party.
     */
    @Test
    void renamingOntoAnOccupiedOrInheritedKeyIsRefused409() throws Exception {
        var ctx = fixture("labels", "Team labels");
        bindField(ctx, "team_notes", "Team notes");

        rename(ctx, ctx.fieldId, "team_notes")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already exists or is inherited")));

        // …and a GLOBAL row this workspace merely inherits refuses it too.
        var global = new FieldDef();
        global.setKey("hd275_inherited_" + Math.abs(UUID.randomUUID().hashCode()));
        global.setName("Inherited " + System.nanoTime());
        global.setType(FieldType.TEXT);
        global = fieldDefRepository.saveAndFlush(global);
        try {
            rename(ctx, ctx.fieldId, global.getKey())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail", containsString("already exists or is inherited")));
        } finally {
            fieldDefRepository.delete(global);
        }
    }

    /**
     * <strong>AC-17 / §16 — a system field is refused 409 even when its key IS claimed.</strong>
     *
     * <p>This is the guard standing over the whole design's highest-risk assumption. The rename
     * rests on nothing resolving a {@code field_defs} row by key at runtime, and the verification
     * found exactly one path that does: {@code DemoDataService} resolves the global
     * {@code severity} and {@code environment} defs by key. Every V3 placeholder is a system def,
     * so refusing system fields outright closes that path and keeps the assumption true.
     */
    @Test
    void renamingASystemFieldIsRefused409() throws Exception {
        var ctx = fixture("labels", "Team labels");
        var def = fieldDefRepository.findById(ctx.fieldId).orElseThrow();
        def.setSystem(true);
        fieldDefRepository.saveAndFlush(def);

        rename(ctx, ctx.fieldId, "team_labels")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("System fields cannot be renamed."));

        assertThat(fieldDefRepository.findById(ctx.fieldId).orElseThrow().getKey()).isEqualTo("labels");
    }

    /**
     * <strong>AC-18 — a workspace admin cannot reach a GLOBAL shadowed row, and gets 404 not
     * 403.</strong> The row is not at their scope, so it is indistinguishable from one that does
     * not exist — the same rule every other delegated edit follows. Only an instance admin can
     * rename a global field, and that change lands in every workspace on the instance at once.
     */
    @Test
    void aWorkspaceAdminCannotRenameAGlobalShadowedField() throws Exception {
        var ctx = fixture("team_notes", "Team notes");
        var global = new FieldDef();
        global.setKey("fixversion");           // registry-claimed; V3 seeds `fix_version`, not this
        global.setName("Global fix version " + System.nanoTime());
        global.setType(FieldType.TEXT);
        global.setArchivedAt(Instant.now());   // archived, so it never reaches a warning surface
        global = fieldDefRepository.saveAndFlush(global);
        try {
            rename(ctx, global.getId(), "team_fix_version")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Field not found"));

            // …and the instance admin can, which is what makes the 404 a SCOPE answer rather than
            // a blanket refusal. Archived is deliberate: shadowedBy is archive-blind, so the
            // affordance is offered here too (§7.1) — rename-then-unarchive is the recovery path.
            var adminToken = instanceAdminToken();
            mockMvc.perform(patch("/api/admin/fields/" + global.getId())
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + global.getName()
                                     + "\",\"type\":\"TEXT\",\"key\":\"hd275_global_renamed\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value("hd275_global_renamed"))
                    .andExpect(jsonPath("$.shadowedBy").value(org.hamcrest.Matchers.nullValue()));
        } finally {
            fieldDefRepository.deleteById(global.getId());
        }
    }

    /**
     * <strong>AC-19 — tenancy first.</strong> A non-member is 404 on both reading and writing (the
     * workspace's existence is never confirmed), and a proven member without
     * {@code workspace.taxonomy.manage} is 403.
     */
    @Test
    void nonMembersGet404AndMembersWithoutThePermissionGet403() throws Exception {
        var ctx = fixture("labels", "Team labels");

        var stranger = user();
        var strangerToken = login(stranger);
        mockMvc.perform(get(adminFieldsUrl(ctx)).header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(fieldUrl(ctx, ctx.fieldId))
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Team labels\",\"type\":\"TEXT\",\"key\":\"team_labels\"}"))
                .andExpect(status().isNotFound());

        var plain = user();
        memberOf(ctx.wsId, plain, "MEMBER");
        var plainToken = login(plain);
        mockMvc.perform(patch(fieldUrl(ctx, ctx.fieldId))
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Team labels\",\"type\":\"TEXT\",\"key\":\"team_labels\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * <strong>AC-21 / §7.1 — an ARCHIVED shadowed field renames, and unarchiving it puts it in the
     * vocabulary.</strong> The permission is archive-blind on purpose: an archived row is out of
     * resolution entirely, so no filter can be pointing at it, and this sequence is the ordinary
     * recovery for a field somebody archived <em>because</em> it had stopped working.
     */
    @Test
    void anArchivedShadowedFieldRenamesAndComesBackQueryable() throws Exception {
        var ctx = fixture("labels", "Team labels");
        var def = fieldDefRepository.findById(ctx.fieldId).orElseThrow();
        def.setArchivedAt(Instant.now());
        fieldDefRepository.saveAndFlush(def);

        rename(ctx, ctx.fieldId, "team_labels")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("team_labels"))
                .andExpect(jsonPath("$.archived").value(true));

        mockMvc.perform(post(fieldUrl(ctx, ctx.fieldId) + "/unarchive")
                        .header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(schemaUrl(ctx)).header("Authorization", "Bearer " + ctx.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[*].name", hasItem("team_labels")))
                .andExpect(jsonPath("$.fields[*].name", not(hasItem("labels"))));
    }

    /**
     * <strong>§7.6 — a retired alias key is refused by a RULE, with its own sentence.</strong>
     *
     * <p><strong>This assertion is the inverse of the one it replaces, on purpose.</strong> The
     * first version pinned that {@code story_points} answers the <em>occupancy</em> 409 ("already
     * exists or is inherited") and explicitly not a reserved-name message, on the reasoning that
     * {@code RetiredFieldAliases} is consulted <em>after</em> the caller's own custom fields
     * ({@code FieldResolver}), so a tenant keying a field {@code story_points} reaches its own
     * field rather than being shadowed. That reasoning about resolution is still true. What it
     * missed is what happens to <em>everybody else's stored queries</em>: the alias stops firing
     * the moment such a field exists, so every saved filter written before V11 retired the key
     * silently changes what it matches — from the native {@code issues.story_points} column to a
     * custom field created a minute ago — and for a global def that is the whole instance at once.
     *
     * <p><strong>And the old 409 was a data row, not a protection.</strong> {@code story_points}
     * and {@code fix_version} were occupied only because V1/V3 seeded global placeholders under
     * them and {@code existsVisibleToAndKey} counts archived rows. Delete or renumber those
     * placeholders and both keys open; retire a key that never had one and it is open from the
     * start, silently. So the refusal is now stated as a rule in
     * {@code AdminFieldService.requireUnreservedKey}, and this test asserts the rule's own message
     * rather than the placeholder's — which is why the assertion flipped.
     *
     * <p>The reserved-name message must still not appear: a retired key is not registry-claimed,
     * the two refusals say different true things, and collapsing them would tell a curator their
     * field "would not be searchable" when in fact it would be — that is the wrong half of the
     * problem.
     */
    @Test
    void aRetiredAliasKeyIsRefusedByItsOwnRuleNotByTheSeededPlaceholderThatHappensToOccupyIt()
            throws Exception {
        var ctx = fixture("labels", "Team labels");

        rename(ctx, ctx.fieldId, "story_points")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("is a retired search field name")))
                // the canonical name is what those filters would stop reaching
                .andExpect(jsonPath("$.detail", containsString("storyPoints")))
                .andExpect(jsonPath("$.detail",
                        not(containsString(RESERVED_MESSAGE_FRAGMENT))))
                .andExpect(jsonPath("$.detail",
                        not(containsString("already exists or is inherited"))));

        assertThat(fieldDefRepository
                .findByScopeWorkspaceIdIsNullAndScopeProjectIdIsNullAndKey("story_points"))
                .as("V1's global placeholder, archived by V11, is still there and would still have "
                    + "produced an occupancy 409 — the point is that the refusal no longer depends "
                    + "on it, so deleting this row cannot open the key")
                .isPresent()
                .hasValueSatisfying(f -> assertThat(f.getArchivedAt()).isNotNull());
    }

    /**
     * <strong>EVERY retired key is refused as a rename target — read off the alias table itself,
     * never listed here.</strong>
     *
     * <p>The subject is a category, so the assertion is derived from that category's own
     * definition. {@code RetiredFieldAliases}' map <em>is</em> the list, and its javadoc forbids
     * restating that list in prose; a test that spelled two of its four keys out would have been
     * an enumeration wearing the word "every", stale one retirement before anybody noticed. Driven
     * off the map, a key retired in a future release is asserted the moment it is added there,
     * with no edit to this class.
     *
     * <p><strong>Which sentence its author gets is decided by the mechanism that answers first,
     * and that is derived too rather than assumed.</strong> A retired key may <em>also</em> be a
     * live registry name — {@code labels} and {@code components} are ergonomic plural aliases as
     * well as retirements, the doubled mechanism {@code RetiredFieldAliases} documents — and
     * {@code requireUnreservedKey} checks reserved before retired. So this asks
     * {@link ShadowedFields} which of the two a key is and demands the matching message: the
     * retirement sentence, naming the canonical field those old filters would stop reaching, for a
     * key the registry has not claimed; the reserved sentence for one it has. <strong>The refusal
     * itself — 409, naming the key — is what is true of the whole category</strong>, and that part
     * is asserted before the branch.
     *
     * <p>The retirement branch is the one the old occupancy 409 could not have covered: an
     * occupancy refusal names the row, this one names the retirement, so a key whose global
     * placeholder was never seeded — or is deleted tomorrow — is still refused by rule.
     */
    @Test
    void everyRetiredAliasKeyIsRefused() throws Exception {
        var retired = retiredAliasTable();
        assertThat(retired)
                .as("the alias table is this test's subject; an empty one would let it pass by "
                    + "asserting nothing at all")
                .isNotEmpty();

        var host = hostKey(retired);
        var ctx = fixture(host, "Team field");

        for (var entry : retired.entrySet()) {
            String key = entry.getKey();
            String canonical = entry.getValue();

            var refusal = rename(ctx, ctx.fieldId, key)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail", containsString(key)));

            if (shadowedFields.claimedBy(key).isPresent()) {
                refusal.andExpect(jsonPath("$.detail", containsString(RESERVED_MESSAGE_FRAGMENT)));
            } else {
                refusal.andExpect(jsonPath("$.detail",
                                containsString("is a retired search field name")))
                        // the canonical name is what those filters would stop reaching
                        .andExpect(jsonPath("$.detail", containsString(canonical)))
                        .andExpect(jsonPath("$.detail",
                                not(containsString(RESERVED_MESSAGE_FRAGMENT))))
                        .andExpect(jsonPath("$.detail",
                                not(containsString("already exists or is inherited"))));
            }
        }

        assertThat(fieldDefRepository.findById(ctx.fieldId).orElseThrow().getKey())
                .as("all of them were refused, so the field still wears the key it started with")
                .isEqualTo(host);
    }

    /**
     * {@code RetiredFieldAliases}' table, read off the class that owns it.
     *
     * <p>Reflection rather than a new accessor: the map is private because nothing in production
     * needs to enumerate it, and widening a shipped API so a test can read it makes the test's
     * needs part of the product's surface. It cannot rot silently either — a renamed or deleted
     * field fails here, loudly and with instructions, instead of shrinking the loop to nothing.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> retiredAliasTable() {
        try {
            var table = RetiredFieldAliases.class.getDeclaredField("BY_RETIRED_KEY");
            table.setAccessible(true);
            return Map.copyOf((Map<String, String>) table.get(null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "RetiredFieldAliases.BY_RETIRED_KEY is the list of retired keys, and this test "
                    + "reads it rather than restating it. If it moved or was renamed, point this "
                    + "helper at wherever it lives now — do not inline the keys.", e);
        }
    }

    /**
     * A registry-claimed key for the fixture field to wear, chosen so that it is <em>not</em>
     * itself a retired key.
     *
     * <p>Claimed, because only a shadowed field may be renamed at all — an unshadowed one is
     * refused 422 by the permission and never reaches the target checks this test is about. Not
     * retired, because renaming a field to the key it already has renames nothing and answers 200:
     * a host hardcoded to {@code labels} would silently exempt {@code labels} from the loop the day
     * that entry mattered. Derived, so a future retirement of this key moves the host rather than
     * quietly hollowing out the assertion.
     */
    private String hostKey(Map<String, String> retired) {
        return registry.claimedKeys().stream()
                .filter(k -> !retired.containsKey(k.toLowerCase(Locale.ROOT)))
                .sorted()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "every registry-claimed key is also retired, so no key both licenses a "
                        + "rename and is a legal target for one"));
    }

    /**
     * <strong>HD-284 (open) — the occupancy check resolves UPWARD only, so a workspace field can
     * be renamed onto a key one of its own projects already uses.</strong> Pinned as today's
     * behaviour, not asserted as desirable.
     *
     * <p>{@code existsVisibleToAndKey} matches three disjuncts: global rows, {@code :ws}, and
     * {@code :proj}. At workspace scope {@code visibleProjectId()} is null, so a PROJECT-scoped row
     * inside that very workspace matches none of them and the target key reads as free. The
     * predicate is shared with {@code createField}, so it cannot be widened here without changing
     * what a create means — which is why the fix is a ticket rather than a line.
     *
     * <p><strong>This workspace/project pair is one instance of the rule, not the rule.</strong>
     * Nothing below the renaming scope is looked at, so the same collision exists at every scope
     * the endpoint is mounted at: at GLOBAL scope both visible ids are null, the predicate
     * collapses to global rows alone, and an instance admin renaming a global shadowed field onto
     * a key any tenant already uses succeeds in exactly this way — in every workspace at once,
     * which is the higher-blast-radius half. The comment at the call site states the reach as a
     * category and names both instances, so neither reads as the whole of it.
     *
     * <p><strong>The consequence, which is the reason it is worth a ticket at all:</strong> the
     * project can now see two live definitions under one key, and
     * {@code ResolutionContextFactory.addCustomField} is <em>first-wins</em> over visible-project
     * iteration order — so a saved filter naming that key can begin resolving to a different
     * {@code field_defs} row than it did yesterday, with no error and nothing to notice.
     *
     * <p><strong>Whoever picks HD-284 up should REPLACE this test, not delete it.</strong> Its
     * subject is a rule the product has, and a rule that changes deserves an assertion that
     * changes with it; a deleted test leaves no trace that anybody decided.
     */
    @Test
    void aWorkspaceRenameOntoAKeyItsOwnProjectUsesIsAllowedToday() throws Exception {
        var ctx = fixture("labels", "Team labels");
        var projectField = bindProjectField(ctx, "team_notes", "Project notes");

        rename(ctx, ctx.fieldId, "team_notes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("team_notes"));

        assertThat(fieldDefRepository.findById(projectField.getId()))
                .as("the project-scoped row is untouched — the two now collide rather than one "
                    + "replacing the other")
                .hasValueSatisfying(f -> assertThat(f.getKey()).isEqualTo("team_notes"));
        assertThat(fieldDefRepository.findById(ctx.fieldId))
                .hasValueSatisfying(f -> assertThat(f.getKey()).isEqualTo("team_notes"));
    }

    // ============================================================ the display name is logged

    /**
     * <strong>The display name reaches a log line under a prefix operators alert on, so it is
     * single-line at the edge.</strong>
     *
     * <p>{@code ShadowedFieldStartupScan} prints {@code shadowed-field-def: custom field '<name>'
     * …} once per boot, and {@code docs/self-hosting.md} tells operators to key their alerting on
     * that prefix. {@code UpsertFieldRequest.name} was bounded only in length, so a taxonomy admin
     * who owns a live shadowed field — exactly the population that gets printed — could put a line
     * terminator in the <em>display name</em> and forge a second line under the prefix at every
     * boot, for ever, in somebody else's alerting.
     *
     * <p>Both minting doors are asserted because they share the DTO and would fail independently:
     * an annotation added to a record is not evidence that both endpoints validate the record.
     * The key needed nothing — it has been {@code [a-z0-9_]} since V1.
     */
    @Test
    void aDisplayNameCarryingALineTerminatorIsRefusedAtBothDoors() throws Exception {
        var ctx = fixture("labels", "Team labels");
        var forged = "Team labels\nshadowed-field-def: nothing here is shadowed, sleep on";

        mockMvc.perform(patch(fieldUrl(ctx, ctx.fieldId))
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + json.writeValueAsString(forged)
                                 + ",\"type\":\"TEXT\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(adminFieldsUrl(ctx))
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":" + json.writeValueAsString(forged)
                                 + ",\"key\":\"forged_name\",\"type\":\"TEXT\"}"))
                .andExpect(status().isBadRequest());

        assertThat(fieldDefRepository.existsVisibleToAndKey(ctx.wsId, null, "forged_name"))
                .as("refused at the edge means nothing was written")
                .isFalse();
    }

    /**
     * <strong>A description is prose and keeps its newlines</strong> — {@code MULTI_LINE}, not the
     * name's pattern. Applying the single-line class to a textarea answered 400 to an Enter
     * keypress once already (the bug that split the two constants apart), and a rule imported
     * without that distinction re-ships it.
     */
    @Test
    void aDescriptionMayStillContainLineBreaks() throws Exception {
        var ctx = fixture("labels", "Team labels");

        mockMvc.perform(post(adminFieldsUrl(ctx))
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Prose field\",\"key\":\"prose_field\",\"type\":\"TEXT\""
                                 + ",\"description\":"
                                 + json.writeValueAsString("first\nsecond") + "}"))
                .andExpect(status().isCreated());
    }

    // ============================================ the catch that only works with saveAndFlush

    /**
     * <strong>AC-20 — a real unique violation becomes 409, not 500.</strong>
     *
     * <p>This is the assertion that cannot be replaced by reading the code. Spring Data's
     * {@code save} only queues the write; Hibernate flushes at commit, <em>after</em> the
     * {@code @Transactional} service method has returned, so a {@code catch} around {@code save}
     * is never entered and the violation escapes as a 500 — and a catch that is never entered
     * looks exactly like one that works, because both are invisible on the happy path.
     * {@code saveAndFlush} is what brings the violation inside the frame that has a sentence for
     * it.
     *
     * <p>The violation is forced by giving the service a scope whose <em>visible</em> ids are
     * narrower than its <em>own</em> ids, so {@code existsVisibleToAndKey} looks only at global
     * rows and misses the workspace-scoped sibling. That is precisely the shape of the race it
     * backstops: the check-then-act passed, and the database refused anyway.
     */
    @Test
    void aLostRaceOnTheKeyConstraintAnswers409OnRename() throws Exception {
        var ctx = fixture("labels", "Team labels");
        bindField(ctx, "team_labels", "Team labels (already taken)");

        var blind = new ScopeContext(ctx.wsId, null, null);
        var req = new UpsertFieldRequest("Team labels", "team_labels", FieldType.TEXT, null, null);

        assertThatThrownBy(() -> fieldService.updateField(null, blind, ctx.fieldId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .as("a deferred INSERT/UPDATE would surface this as a 500 from outside "
                            + "every try in the service")
                        .isEqualTo(409))
                .hasMessageContaining("taken by another change");
    }

    /**
     * <strong>The 409 is for a DUPLICATE KEY, and only for one.</strong>
     * {@link org.springframework.dao.DataIntegrityViolationException} is Spring's translation for
     * the whole integrity family, so a flat catch answers "that key or name was taken by another
     * change — reload and try again" to violations that have nothing to do with either: a
     * {@code 22001} truncation, which {@code GlobalExceptionHandler} deliberately answers 400 for
     * (HD-171), and a {@code 23503} foreign-key violation. Both would send the caller into a
     * reload loop against advice that cannot work, with the real diagnostic swallowed.
     *
     * <p>Forced with a name longer than {@code field_defs.name VARCHAR(100)}, through the service
     * rather than the endpoint because the endpoint's {@code @Size(max = 100)} refuses it first —
     * which is why this is hardening rather than a live bug. What is asserted is that the
     * violation leaves this service <em>as itself</em>: the class has a handler with a status of
     * its own, and the service's job is not to overwrite it.
     */
    @Test
    void aTruncationIsNotDisguisedAsADuplicateKey() throws Exception {
        var ctx = fixture("labels", "Team labels");
        var scope = ScopeContext.workspace(ctx.wsId);
        var req = new UpsertFieldRequest("x".repeat(300), null, FieldType.TEXT, null, null);

        assertThatThrownBy(() -> fieldService.updateField(null, scope, ctx.fieldId, req))
                .as("a 22001 is not a collision with anything and must not be told to reload")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .isNotInstanceOf(ResponseStatusException.class);
    }

    /**
     * <strong>Rider S9 — {@code createField} carries the identical latent 500, so it is sealed the
     * same way and in the same commit.</strong> It shares the translation, so a test on only one of
     * the two would leave the other looking equally correct and being equally broken.
     */
    @Test
    void aLostRaceOnTheKeyConstraintAnswers409OnCreate() throws Exception {
        var ctx = fixture("team_notes", "Team notes");

        var blind = new ScopeContext(ctx.wsId, null, null);
        var req = new UpsertFieldRequest("Team notes (dup)", "team_notes", FieldType.TEXT, null, null);

        assertThatThrownBy(() -> fieldService.createField(blind, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(409))
                .hasMessageContaining("taken by another change");
    }

    // ==================================================================== helpers

    private record Ctx(UUID wsId, UUID projectId, UUID setId, String token, UUID fieldId,
                       JsonNode config) {
        UUID typeId() {
            for (var t : config.get("issueTypes")) return UUID.fromString(t.get("id").asText());
            throw new AssertionError("no issue type offered");
        }
        UUID todoStatusId() {
            for (var s : config.get("statuses")) {
                if (s.get("category").asText().equals("TODO")) return UUID.fromString(s.get("id").asText());
            }
            throw new AssertionError("no TODO-category status");
        }
    }

    private static String schemaUrl(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId + "/search/schema";
    }

    private static String adminFieldsUrl(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId + "/admin/fields";
    }

    private static String fieldUrl(Ctx ctx, UUID id) {
        return "/api/workspaces/" + ctx.wsId + "/admin/fields/" + id;
    }

    private ResultActions rename(Ctx ctx, UUID fieldId, String newKey) throws Exception {
        var current = fieldDefRepository.findById(fieldId).orElseThrow();
        return mockMvc.perform(patch(fieldUrl(ctx, fieldId))
                .header("Authorization", "Bearer " + ctx.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":" + json.writeValueAsString(current.getName())
                         + ",\"type\":\"" + current.getType() + "\""
                         + ",\"key\":" + json.writeValueAsString(newKey) + "}"));
    }

    private ResultActions search(Ctx ctx, String query) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId + "/search")
                .header("Authorization", "Bearer " + ctx.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":" + json.writeValueAsString(query) + "}"));
    }

    private void createIssue(Ctx ctx, String title, String rawFieldValue) throws Exception {
        var body = new StringBuilder("{\"title\":").append(json.writeValueAsString(title))
                .append(",\"typeId\":\"").append(ctx.typeId()).append("\"")
                .append(",\"statusId\":\"").append(ctx.todoStatusId()).append("\"");
        if (rawFieldValue != null) {
            body.append(",\"fields\":{\"").append(ctx.fieldId).append("\":").append(rawFieldValue).append("}");
        }
        body.append("}");
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId + "/projects/" + ctx.projectId + "/issues")
                        .header("Authorization", "Bearer " + ctx.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
    }

    /**
     * A workspace whose project is bound to a workspace-scoped field set carrying one custom field
     * under the given key, written through the repository — which is how the shadowed population
     * really arose (the reserved-key guard is never retroactive, and postdates every affected row
     * besides).
     */
    private Ctx fixture(String key, String name) throws Exception {
        var owner = user();
        var ws = workspace(owner);
        memberOf(ws.getId(), owner, "OWNER");
        var project = project(ws, owner);
        projectMember(project, owner);

        var set = new FieldSet();
        set.setName("Set-" + System.nanoTime());
        set.setScopeWorkspaceId(ws.getId());
        set = fieldSetRepository.save(set);
        project.setFieldSet(set);
        projectRepository.save(project);

        var token = login(owner);
        var shell = new Ctx(ws.getId(), project.getId(), set.getId(), token, null, null);
        var field = bindField(shell, key, name);

        var config = json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        return new Ctx(ws.getId(), project.getId(), set.getId(), token, field.getId(), config);
    }

    /**
     * One PROJECT-scoped field in the ctx project, bound to no set — the set binding is what makes
     * a field render, and nothing here renders it. It exists to occupy a key one layer below the
     * workspace, which is the shape HD-284 is about.
     */
    private FieldDef bindProjectField(Ctx ctx, String key, String name) {
        var f = new FieldDef();
        f.setScopeProjectId(ctx.projectId);
        f.setKey(key);
        f.setName(name);
        f.setType(FieldType.TEXT);
        f = fieldDefRepository.saveAndFlush(f);
        insertedFieldIds.add(f.getId());
        return f;
    }

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
        u.setEmail(("ren-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Test User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private String instanceAdminToken() throws Exception {
        var u = new User();
        u.setEmail(("ren-adm-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Instance Admin");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.ADMIN);
        return login(userRepository.save(u));
    }

    private Workspace workspace(User creator) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("ren-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(creator);
        return workspaceRepository.save(w);
    }

    private void memberOf(UUID workspaceId, User u, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(workspaceRepository.findById(workspaceId).orElseThrow());
        m.setUser(u);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
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

    private void projectMember(Project project, User u) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(u);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, "MANAGER"));
        projectMemberRepository.save(m);
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
