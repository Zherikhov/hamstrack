package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-127 (S4) — {@code /api/workspaces/{ws}/roles}.
 *
 * <p>The endpoints, their gates and the guards that make custom roles safe to define:
 * duplicate-only creation, the built-in refusal, server-generated keys, name uniqueness,
 * the sprawl bound, permission validation (including the scope rule this whole slice rests
 * on), optimistic concurrency, delete-with-reassign and its two refusals, the derived
 * assignment feedback, and tenancy throughout.
 *
 * <p>The workspace-scoped {@code max-custom-per-workspace} is lowered to 3 for this suite,
 * so the limit case is three creates rather than fifty.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.roles.max-custom-per-workspace=3"
})
@AutoConfigureMockMvc
class RoleApiTest extends SprintTestBase {

    // ==================================================== duplication is the only door

    @Test
    void duplicatingABuiltInProducesAnEditableCopyAndLeavesTheSourceAlone() throws Exception {
        var ctx = newProject();

        var copy = duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "{\"name\":\"Team lead (sprints)\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.builtIn").value(false))
                .andExpect(jsonPath("$.scope").value("PROJECT"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(copy);

        // The permission set is the source's, verbatim — that is what makes duplication a
        // usable starting point rather than an empty checklist.
        assertThat(permissionKeys(node))
                .isEqualTo(permissionKeys(role(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER)));

        // …and the source is untouched.
        var source = role(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER);
        assertThat(source.get("builtIn").asBoolean()).isTrue();
        assertThat(source.get("name").asText()).isEqualTo("Contributor");
    }

    /** There is deliberately no bare create endpoint — "from scratch" is duplicating Viewer. */
    @Test
    void thereIsNoBarePostRoles() throws Exception {
        var ctx = newProject();

        mockMvc.perform(post(rolesBase(ctx))
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PROJECT\",\"name\":\"Invented\"}"))
                .andExpect(status().isMethodNotAllowed());

        // The supported way to start from nothing: duplicate the role that grants nothing.
        var copy = duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_VIEWER, "{\"name\":\"Blank\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(permissionKeys(json.readTree(copy))).isEmpty();
    }

    @Test
    void builtInsCannotBeEditedOrDeleted() throws Exception {
        var ctx = newProject();

        patchRole(ctx, ctx.token(), BuiltInRoles.PROJECT_MANAGER, "{\"name\":\"Renamed\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("duplicate it to customise")));

        deleteRole(ctx, ctx.token(), BuiltInRoles.PROJECT_MANAGER, null)
                .andExpect(status().isConflict());

        assertThat(role(ctx, ctx.token(), BuiltInRoles.PROJECT_MANAGER).get("name").asText())
                .isEqualTo("Project admin");
    }

    // ==================================================== keys and names

    /**
     * <strong>The key is server-generated, and a collision with a built-in takes the
     * suffix.</strong> {@code roles_scope_key_uk} is {@code UNIQUE NULLS NOT DISTINCT}, so
     * the database would happily store a workspace-owned role keyed {@code ADMIN} beside the
     * built-in one — this guard is the only thing in the way.
     */
    @Test
    void aGeneratedKeyNeverCollidesWithABuiltInOrWithASibling() throws Exception {
        var ctx = newProject();

        var first = json.readTree(duplicate(ctx, ctx.token(), BuiltInRoles.WORKSPACE_MEMBER,
                "{\"name\":\"Admin!\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(first.get("key").asText())
                .as("a custom role must never take a built-in's key")
                .isEqualTo("ADMIN_2");

        // A different NAME whose slug lands on the same key gets the next free suffix (the
        // display names differ, so the name guard has nothing to say).
        var second = json.readTree(duplicate(ctx, ctx.token(), BuiltInRoles.WORKSPACE_MEMBER,
                "{\"name\":\"admin?\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(second.get("key").asText()).isEqualTo("ADMIN_3");
    }

    /** Names are what users read, so ambiguity there is refused rather than suffixed. */
    @Test
    void aNameThatCollidesWithABuiltInOrACustomRoleIs409() throws Exception {
        var ctx = newProject();

        duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "{\"name\":\"contributor\"}")
                .andExpect(status().isConflict());

        duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "{\"name\":\"QA lead\"}")
                .andExpect(status().isCreated());
        duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_VIEWER, "{\"name\":\"qa LEAD\"}")
                .andExpect(status().isConflict());

        // Uniqueness is per (workspace, SCOPE): the same name at the other scope is fine.
        duplicate(ctx, ctx.token(), BuiltInRoles.WORKSPACE_MEMBER, "{\"name\":\"QA lead\"}")
                .andExpect(status().isCreated());
    }

    /**
     * <strong>"No control characters" has to mean the Unicode ones too</strong> (round-3
     * review). The obvious {@code @Pattern("[^\\p{Cntrl}]*")} is ASCII-only in Java —
     * {@code [\x00-\x1F\x7F]} without {@code UNICODE_CHARACTER_CLASS}, which Bean Validation
     * does not set — so NEL, LINE/PARAGRAPH SEPARATOR and the bidi overrides walked straight
     * through the annotation written to keep exactly them out of a CSV export, an email or a
     * webhook payload.
     */
    @Test
    void aRoleNameMayNotCarryUnicodeLineBreaksOrBidiOverrides() throws Exception {
        var ctx = newProject();

        // The ASCII case the original pattern did catch, so a regression cannot hide here.
        duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "{\"name\":\"QA\\nlead\"}")
                .andExpect(status().isBadRequest());
        // U+0085 NEL, U+2028/U+2029 LINE and PARAGRAPH SEPARATOR, U+202E RIGHT-TO-LEFT
        // OVERRIDE, U+200F RLM, U+FEFF ZWNBSP — spelled as code points rather than pasted,
        // because every one of them is invisible in a diff, which is half of why they work.
        //
        // Round 4 completed two families the first pass left with a member missing, which is
        // the failure mode of an enumerated class: U+061C ARABIC LETTER MARK is the third
        // bidi mark, with U+200E/U+200F's semantics exactly, and U+200B–U+200D and U+2060 are
        // zero-width like the U+FEFF already listed — enough on their own for two roles whose
        // names are indistinguishable in a picker.
        for (var sneaky : new int[]{0x85, 0x2028, 0x2029, 0x202E, 0x200F, 0xFEFF,
                0x61C, 0x200B, 0x200C, 0x200D, 0x2060}) {
            duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER,
                    json.writeValueAsString(java.util.Map.of(
                            "name", "QA" + (char) sneaky + "lead")))
                    .andExpect(status().isBadRequest());
        }
        // …and an ordinary non-ASCII name is still perfectly legal.
        duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "{\"name\":\"Ответственный\"}")
                .andExpect(status().isCreated());
    }

    /** The cap counts custom roles across BOTH scopes; built-ins never count. */
    @Test
    void theCustomRoleCapSpansBothScopesAndIgnoresBuiltIns() throws Exception {
        var ctx = newProject();

        duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER, "{\"name\":\"One\"}")
                .andExpect(status().isCreated());
        duplicate(ctx, ctx.token(), BuiltInRoles.WORKSPACE_MEMBER, "{\"name\":\"Two\"}")
                .andExpect(status().isCreated());
        duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_VIEWER, "{\"name\":\"Three\"}")
                .andExpect(status().isCreated());

        duplicate(ctx, ctx.token(), BuiltInRoles.PROJECT_VIEWER, "{\"name\":\"Four\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("maximum of 3")));

        // The 8 built-ins are visible in the same list and did not consume any of the budget.
        assertThat(roles(ctx, ctx.token(), null)).hasSize(8 + 3);
    }

    // ==================================================== permission validation

    /**
     * <strong>The scope rule is the highest-risk assumption of this slice.</strong> A
     * PROJECT-scoped role holding {@code workspace.member.manage} would put that permission
     * into every holder's {@code ProjectContext} — a flat {@code PermissionSet} does not
     * remember where a grant came from — so it is refused at the write path, where it is the
     * only place it can be seen.
     */
    @Test
    void aPermissionOfTheWrongScopeIs422() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Scoped");

        patchRole(ctx, ctx.token(), role, "{\"permissions\":[{\"key\":\"workspace.member.manage\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("own scope")));

        // …and nothing was written.
        assertThat(permissionKeys(role(ctx, ctx.token(), role)))
                .contains("issue.create");
    }

    @Test
    void unknownDuplicateAndIllegalOwnPermissionsAreAll422() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_VIEWER, "Validated");

        patchRole(ctx, ctx.token(), role, "{\"permissions\":[{\"key\":\"issue.teleport\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Unknown permission")));

        patchRole(ctx, ctx.token(), role,
                "{\"permissions\":[{\"key\":\"issue.create\"},{\"key\":\"issue.create\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("twice")));

        // issue.transition is all-or-nothing; honouring `own` silently would NARROW it.
        patchRole(ctx, ctx.token(), role,
                "{\"permissions\":[{\"key\":\"issue.transition\",\"ownOnly\":true}]}")
                .andExpect(status().isUnprocessableContent());

        // comment.edit is own-REQUIRED and is forced, not refused.
        patchRole(ctx, ctx.token(), role, "{\"permissions\":[{\"key\":\"comment.edit\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].key").value("comment.edit"))
                .andExpect(jsonPath("$.permissions[0].ownOnly").value(true));
    }

    /**
     * A grant object with no {@code ownOnly} must not 400 — Jackson 3's
     * {@code FAIL_ON_NULL_FOR_PRIMITIVES} makes a primitive field in a request record reject
     * the overwhelmingly common shape, which is exactly what bit {@code UpdateIssueRequest}.
     */
    @Test
    void aGrantWithoutOwnOnlyDeserialisesAsUnrestricted() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_VIEWER, "Boxed");

        patchRole(ctx, ctx.token(), role, "{\"permissions\":[{\"key\":\"issue.rank\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].ownOnly").value(false));
    }

    /** Full replacement, and re-sending an existing key must not violate the composite PK. */
    @Test
    void permissionsAreReplacedWholesaleAndAreIdempotent() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Replaced");

        patchRole(ctx, ctx.token(), role,
                "{\"permissions\":[{\"key\":\"issue.create\"},{\"key\":\"issue.rank\"}]}")
                .andExpect(status().isOk());
        patchRole(ctx, ctx.token(), role,
                "{\"permissions\":[{\"key\":\"issue.create\"},{\"key\":\"comment.create\"}]}")
                .andExpect(status().isOk());

        assertThat(permissionKeys(role(ctx, ctx.token(), role)))
                .containsExactlyInAnyOrder("issue.create", "comment.create");

        // Toggling `own` on a key the role already holds must actually persist — the
        // RolePermission-equality trap that makes add() a silent no-op.
        patchRole(ctx, ctx.token(), role,
                "{\"permissions\":[{\"key\":\"issue.edit\",\"ownOnly\":true}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].ownOnly").value(true));
        patchRole(ctx, ctx.token(), role,
                "{\"permissions\":[{\"key\":\"issue.edit\",\"ownOnly\":false}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[0].ownOnly").value(false));
    }

    /** An empty list is a real value: a role granting nothing is legal. */
    @Test
    void aRoleMayBeStrippedToNothing() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Emptied");

        patchRole(ctx, ctx.token(), role, "{\"permissions\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    // ==================================================== concurrency & immutability

    @Test
    void aStaleVersionIs409AndTheLosersEditIsNotApplied() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Contended");

        patchRole(ctx, ctx.token(), role, "{\"version\":0,\"name\":\"Winner\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        patchRole(ctx, ctx.token(), role, "{\"version\":0,\"permissions\":[]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("modified by someone else")));

        var after = role(ctx, ctx.token(), role);
        assertThat(after.get("name").asText()).isEqualTo("Winner");
        assertThat(permissionKeys(after)).isNotEmpty();
    }

    /**
     * <strong>{@code scope} is immutable.</strong> One PATCH changing it would turn every
     * existing assignment of the role into a wrong-scope row — the corruption
     * {@code findAssignable} exists to prevent, delivered wholesale and retroactively. It is
     * simply not on the DTO, so a body carrying it changes nothing.
     */
    @Test
    void scopeCannotBeChanged() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Fixed");

        patchRole(ctx, ctx.token(), role, "{\"scope\":\"WORKSPACE\",\"name\":\"Still project\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PROJECT"));
    }

    // ==================================================== delete & reassign

    @Test
    void deletingARoleInUseNeedsAReassignTargetAndMovesEveryHolder() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.WORKSPACE_MEMBER, "Contractor");
        var holder = actorWith(ctx, "MEMBER", null);
        // Move them onto the custom role through the real API, by id.
        patchWorkspaceMember(ctx, ctx.token(), holder.user().getId(), "{\"roleId\":\"" + role + "\"}")
                .andExpect(status().isOk());

        deleteRole(ctx, ctx.token(), role, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("ROLE_IN_USE"))
                .andExpect(jsonPath("$.usage.members").value(1))
                .andExpect(jsonPath("$.usage.inUse").value(true));

        deleteRole(ctx, ctx.token(), role, BuiltInRoles.WORKSPACE_MEMBER)
                .andExpect(status().isNoContent());

        // The holder now carries the target role, and their access still resolves.
        assertThat(workspaceMemberRole(ctx, holder.user().getId())).isEqualTo("MEMBER");
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId())
                        .header("Authorization", "Bearer " + holder.token()))
                .andExpect(status().isOk());
    }

    /**
     * <strong>Delete-with-reassign is a self-escalation route no ceiling sees</strong>: a
     * ceiling is evaluated per assignment, and this is a bulk UPDATE over every holder at
     * once. Refused bluntly, with a remedy the refused person can perform.
     */
    @Test
    void deletingARoleTheActorHoldsIs409() throws Exception {
        var ctx = newProject();
        // A duplicate of PROJECT ADMIN, so taking it does not demote the project's only
        // administrator — door 3 would refuse that, and this test is about door R5.
        var role = customRole(ctx, BuiltInRoles.PROJECT_MANAGER, "Self held");
        // The owner takes the role in their own project.
        patchProjectMember(ctx, ctx.token(), ctx.owner().getId(), role)
                .andExpect(status().isOk());

        deleteRole(ctx, ctx.token(), role, BuiltInRoles.PROJECT_MANAGER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("SELF_HELD_ROLE"));
    }

    /** A reassign target is a VALUE, so every unusable one is the same 422. */
    @Test
    void aBadReassignTargetIs422() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Doomed");

        // Wrong scope.
        deleteRole(ctx, ctx.token(), role, BuiltInRoles.WORKSPACE_MEMBER)
                .andExpect(status().isUnprocessableContent());
        // Itself.
        deleteRole(ctx, ctx.token(), role, role)
                .andExpect(status().isUnprocessableContent());
        // Another workspace's custom role.
        var other = newProject();
        var foreign = customRole(other, BuiltInRoles.PROJECT_MEMBER, "Elsewhere");
        deleteRole(ctx, ctx.token(), role, foreign)
                .andExpect(status().isUnprocessableContent());
        // Nonsense — indistinguishable from all of the above, which is the point.
        deleteRole(ctx, ctx.token(), role, UUID.randomUUID())
                .andExpect(status().isUnprocessableContent());

        assertThat(role(ctx, ctx.token(), role)).isNotNull();
    }

    // ==================================================== usage

    /**
     * <strong>The single most likely tenancy defect in this slice.</strong> Built-in roles
     * are shared rows, so an unscoped {@code COUNT(*) WHERE role_id = …} would publish every
     * other tenant's headcount from a workspace-scoped endpoint.
     */
    @Test
    void usageCountsForABuiltInAreScopedToOneWorkspace() throws Exception {
        var a = newProject();
        var b = newProject();
        actorWith(a, "MEMBER", null);
        actorWith(b, "MEMBER", null);
        actorWith(b, "MEMBER", null);

        var usage = json.readTree(mockMvc.perform(get(rolesBase(a) + "/" + BuiltInRoles.WORKSPACE_MEMBER + "/usage")
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(usage.get("members").asLong())
                .as("workspace B's members leaked into workspace A's usage count")
                .isEqualTo(1);
    }

    @Test
    void includeUsageNeedsThePermissionButTheBareListDoesNot() throws Exception {
        var ctx = newProject();
        var plain = actorWith(ctx, "MEMBER", null);

        mockMvc.perform(get(rolesBase(ctx)).header("Authorization", "Bearer " + plain.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].usage").doesNotExist());

        mockMvc.perform(get(rolesBase(ctx) + "?includeUsage=true")
                        .header("Authorization", "Bearer " + plain.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("workspace.role.manage")));

        mockMvc.perform(get(rolesBase(ctx) + "?includeUsage=true")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());
    }

    // ==================================================== derived assignment feedback

    /**
     * The ceiling is a <em>subset</em> rule, not a ladder, so a role holding only member
     * management is a superset of nothing useful. That is legal — and warned about,
     * machine-readably, rather than blocked.
     *
     * <p>Note what it <em>can</em> assign: the built-in Viewer, which grants the empty set,
     * and the empty set is covered by every set. That is why the warning keys on "every
     * assignable role grants nothing" rather than on an empty {@code canAssign}, which is
     * unreachable and would make it dead code.
     */
    @Test
    void aRoleThatManagesMembersAndAssignsNobodyIsWarnedAboutNotRefused() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_VIEWER, "Roster only");

        var body = patchRole(ctx, ctx.token(), role,
                "{\"permissions\":[{\"key\":\"project.member.manage\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignment.managesMembers").value(true))
                .andExpect(jsonPath("$.assignment.warnings[0]")
                        .value("MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING"))
                .andReturn().getResponse().getContentAsString();
        var assignment = json.readTree(body).get("assignment");

        var canAssign = new ArrayList<String>();
        for (var r : assignment.get("canAssign")) canAssign.add(r.get("name").asText());
        assertThat(canAssign)
                .as("only roles that grant nothing are assignable by a bare member-manager")
                .containsExactly("Viewer");

        // cannotAssign names the FIRST missing permission, by key — the same string the
        // runtime 403 carries, which is the whole reason it is published.
        var blockers = assignment.get("cannotAssign");
        assertThat(blockers.isEmpty()).isFalse();
        for (var b : blockers) {
            assertThat(b.get("missing").asText()).isNotBlank();
        }
    }

    @Test
    void aDuplicateOfContributorCanAssignTheNarrowerBuiltIns() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Team lead (sprints)");

        var body = patchRole(ctx, ctx.token(), role, "{\"permissions\":" + contributorPlus(ctx, "sprint.manage") + "}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var assignment = json.readTree(body).get("assignment");

        var canAssign = new ArrayList<String>();
        for (var r : assignment.get("canAssign")) canAssign.add(r.get("name").asText());
        assertThat(canAssign).contains("Contributor", "Commenter", "Viewer");
        // …and not Project admin, which holds far more.
        assertThat(canAssign).doesNotContain("Project admin");
    }

    /** The preview persists nothing and answers with the identical block. */
    @Test
    void previewMatchesTheStoredAnswerAndWritesNothing() throws Exception {
        var ctx = newProject();
        var before = roles(ctx, ctx.token(), null).size();

        var preview = json.readTree(mockMvc.perform(post(rolesBase(ctx) + "/preview")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PROJECT\",\"permissions\":"
                                 + "[{\"key\":\"project.member.manage\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(preview.get("assignment").get("managesMembers").asBoolean()).isTrue();
        assertThat(preview.get("assignment").get("warnings").get(0).asText())
                .isEqualTo("MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING");
        assertThat(roles(ctx, ctx.token(), null)).hasSize(before);

        // The preview runs the same validation a real write does.
        mockMvc.perform(post(rolesBase(ctx) + "/preview")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PROJECT\",\"permissions\":"
                                 + "[{\"key\":\"workspace.edit\"}]}"))
                .andExpect(status().isUnprocessableContent());
    }

    // ==================================================== cache invalidation

    /**
     * <strong>The eviction fires after commit, so the holder's very next request on this
     * instance sees the narrower role</strong> — not the 10-second TTL later.
     *
     * <p>What is under test is {@code evict}, not the TTL: the holder's <em>membership</em>
     * is untouched throughout (that is never cached and always bites immediately), and only
     * the role's <em>contents</em> move — the one thing that has a cache at all.
     */
    @Test
    void aRevocationBitesOnTheHoldersNextRequest() throws Exception {
        var ctx = newProject();
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Filer");
        var holder = actorWith(ctx, "MEMBER", null);
        addProjectMember(ctx, ctx.token(), holder.user().getId(), role)
                .andExpect(status().isCreated());

        postIssue(ctx, holder.token(), "Allowed while the role grants it")
                .andExpect(status().isCreated());

        // Drop issue.create from the role. No membership row changes.
        patchRole(ctx, ctx.token(), role, "{\"permissions\":" + contributorMinus(ctx, "issue.create") + "}")
                .andExpect(status().isOk());

        postIssue(ctx, holder.token(), "Refused on the very next request")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("issue.create")));
    }

    // ==================================================== tenancy

    @Test
    void aNonMemberGets404FromEveryRoleEndpoint() throws Exception {
        var ctx = newProject();
        var stranger = newProject();          // a member of a DIFFERENT workspace
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Private");

        mockMvc.perform(get(rolesBase(ctx)).header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(rolesBase(ctx) + "/" + role)
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());
        duplicate(ctx, stranger.token(), BuiltInRoles.PROJECT_MEMBER, "{}")
                .andExpect(status().isNotFound());
        patchRole(ctx, stranger.token(), role, "{\"name\":\"Mine now\"}")
                .andExpect(status().isNotFound());
        deleteRole(ctx, stranger.token(), role, null)
                .andExpect(status().isNotFound());
        mockMvc.perform(get(rolesBase(ctx) + "/" + role + "/usage")
                        .header("Authorization", "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(rolesBase(ctx) + "/preview")
                        .header("Authorization", "Bearer " + stranger.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PROJECT\",\"permissions\":[]}"))
                .andExpect(status().isNotFound());
    }

    /**
     * A role id in the <strong>path</strong> is an address, so another workspace's role is
     * 404 — the same answer an id that never existed gets, which keeps the namespace opaque.
     */
    @Test
    void anotherWorkspacesRoleIs404ByPath() throws Exception {
        var ctx = newProject();
        var other = newProject();
        var foreign = customRole(other, BuiltInRoles.PROJECT_MEMBER, "Theirs");

        mockMvc.perform(get(rolesBase(ctx) + "/" + foreign)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(rolesBase(ctx) + "/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNotFound());
        // …and its name never appears in this workspace's list.
        assertThat(roles(ctx, ctx.token(), null).toString()).doesNotContain("Theirs");
    }

    @Test
    void aMemberWithoutRoleManageIsForbiddenFromEveryWrite() throws Exception {
        var ctx = newProject();
        var plain = actorWith(ctx, "MEMBER", null);
        var role = customRole(ctx, BuiltInRoles.PROJECT_MEMBER, "Guarded");

        // Reads are open…
        mockMvc.perform(get(rolesBase(ctx)).header("Authorization", "Bearer " + plain.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get(rolesBase(ctx) + "/" + role)
                        .header("Authorization", "Bearer " + plain.token()))
                .andExpect(status().isOk());

        // …writes are not.
        duplicate(ctx, plain.token(), BuiltInRoles.PROJECT_MEMBER, "{}")
                .andExpect(status().isForbidden());
        patchRole(ctx, plain.token(), role, "{\"name\":\"Nope\"}")
                .andExpect(status().isForbidden());
        deleteRole(ctx, plain.token(), role, null)
                .andExpect(status().isForbidden());
        mockMvc.perform(get(rolesBase(ctx) + "/" + role + "/usage")
                        .header("Authorization", "Bearer " + plain.token()))
                .andExpect(status().isForbidden());
    }

    // ==================================================== helpers

    private String rolesBase(Ctx ctx) {
        return "/api/workspaces/" + ctx.wsId() + "/roles";
    }

    private ResultActions duplicate(Ctx ctx, String token, UUID sourceId, String body) throws Exception {
        return mockMvc.perform(post(rolesBase(ctx) + "/" + sourceId + "/duplicate")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchRole(Ctx ctx, String token, UUID roleId, String body) throws Exception {
        return mockMvc.perform(patch(rolesBase(ctx) + "/" + roleId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteRole(Ctx ctx, String token, UUID roleId, UUID reassignTo) throws Exception {
        return mockMvc.perform(delete(rolesBase(ctx) + "/" + roleId
                        + (reassignTo == null ? "" : "?reassignToRoleId=" + reassignTo))
                .header("Authorization", "Bearer " + token));
    }

    private JsonNode role(Ctx ctx, String token, UUID roleId) throws Exception {
        return json.readTree(mockMvc.perform(get(rolesBase(ctx) + "/" + roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode roles(Ctx ctx, String token, RoleScope scope) throws Exception {
        return json.readTree(mockMvc.perform(get(rolesBase(ctx)
                        + (scope == null ? "" : "?scope=" + scope))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Duplicate {@code source} under {@code name} and hand back the new role's id. */
    private UUID customRole(Ctx ctx, UUID source, String name) throws Exception {
        var body = duplicate(ctx, ctx.token(), source,
                "{\"name\":" + json.writeValueAsString(name) + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    private static List<String> permissionKeys(JsonNode roleNode) {
        var out = new ArrayList<String>();
        for (var p : roleNode.get("permissions")) out.add(p.get("key").asText());
        return out;
    }

    /** Contributor's grants plus one more, as a JSON permissions array. */
    private String contributorPlus(Ctx ctx, String extra) throws Exception {
        var keys = permissionKeys(role(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER));
        var out = new ArrayList<>(keys);
        out.add(extra);
        return asPermissionsJson(ctx, out);
    }

    private String contributorMinus(Ctx ctx, String dropped) throws Exception {
        var out = new ArrayList<>(permissionKeys(role(ctx, ctx.token(), BuiltInRoles.PROJECT_MEMBER)));
        out.remove(dropped);
        return asPermissionsJson(ctx, out);
    }

    private String asPermissionsJson(Ctx ctx, List<String> keys) throws Exception {
        var parts = new ArrayList<String>();
        for (var k : keys) {
            // comment.edit is own-required and is stored own-only whatever we send.
            parts.add("{\"key\":\"" + k + "\",\"ownOnly\":" + k.equals("comment.edit") + "}");
        }
        return "[" + String.join(",", parts) + "]";
    }

    private ResultActions patchWorkspaceMember(Ctx ctx, String token, UUID userId, String body)
            throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/members/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchProjectMember(Ctx ctx, String token, UUID userId, UUID roleId)
            throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/projects/"
                        + ctx.projectId() + "/members/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + roleId + "\"}"));
    }

    private ResultActions addProjectMember(Ctx ctx, String token, UUID userId, UUID roleId)
            throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects/"
                        + ctx.projectId() + "/members")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"roleId\":\"" + roleId + "\"}"));
    }

    private String workspaceMemberRole(Ctx ctx, UUID userId) throws Exception {
        var body = mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/members")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var m : json.readTree(body)) {
            if (m.get("userId").asText().equals(userId.toString())) {
                var role = m.get("role");
                return role == null || role.isNull() ? null : role.asText();
            }
        }
        return null;
    }
}
