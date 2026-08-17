package com.hamstrack.workspace;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.sse.SseRegistry;
import com.hamstrack.issue.entity.Component;
import com.hamstrack.issue.repository.ComponentRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceInvite;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceInviteRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-132 — {@code PATCH}/{@code DELETE /api/workspaces/{ws}/members/{userId}}.
 *
 * <p>Covers the rules that make membership administration safe, plus the removal cascade:
 * <ul>
 *   <li><strong>Last Owner</strong> — a workspace can never lose its last OWNER, by
 *       demotion or removal, including when the owner acts on themselves;</li>
 *   <li><strong>Grant ceiling</strong> — nobody may act on, or hand out, a role stronger
 *       than their own;</li>
 *   <li><strong>Tenancy</strong> — unknown workspace and non-member caller are one
 *       indistinguishable 404; a member below ADMIN gets 403; a target who is not a member
 *       here is a 404 about the membership, never about the account;</li>
 *   <li><strong>Idempotency</strong> — a second DELETE is a clean 404, not a 500;</li>
 *   <li><strong>No self-removal</strong> — the admin path refuses target == actor (422),
 *       while self-<em>demotion</em> stays legal;</li>
 *   <li><strong>The cascade</strong> — issues unassigned (with audit, and with a version
 *       bump so a stale concurrent edit cannot silently restore the assignee), project
 *       memberships dropped, leftover invites revoked, live SSE streams closed — and every
 *       other reference (reporter, comment author, history author, attachment uploader,
 *       saved filters, <em>component leads</em>, other workspaces) untouched.</li>
 * </ul>
 *
 * <p>Bootstrapped through the repository layer for tenancy scaffolding and through the real
 * API for everything the feature owns, matching {@code WorkspaceAccessTenancyTest} /
 * {@code LabelTestBase}.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class WorkspaceMemberManagementTest {

    // Attachment blobs (the uploader-attribution test) go to a per-JVM temp root, never to
    // the developer's ./data/attachments — same wiring as AttachmentCrudTenancyTest.
    static final Path STORAGE_DIR;
    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("hamstrack-member-removal-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.local.base-dir", () -> STORAGE_DIR.toString());
    }

    @Autowired MockMvc mockMvc;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired ComponentRepository componentRepository;
    @Autowired WorkspaceInviteRepository inviteRepository;
    @Autowired SseRegistry sseRegistry;
    @Autowired PasswordEncoder passwordEncoder;
    // The custom-role fixtures below (the set half of the grant ceiling) — RoleRepository
    // exposes no save() until S4, so they are written through the EntityManager.
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate;

    private final ObjectMapper json = new ObjectMapper();

    // ============================================================ happy path

    @Test
    void ownerChangesAMembersRole() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");

        patchMember(ws, ws.ownerToken(), member.getId(), "ADMIN")
                .andExpect(status().isOk());

        assertThat(roleOf(ws, member)).isEqualTo("ADMIN");
    }

    /** Re-sending the role a member already holds is a no-op, not a rejection. */
    @Test
    void settingTheSameRoleIsANoOp() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");

        patchMember(ws, ws.ownerToken(), member.getId(), "MEMBER")
                .andExpect(status().isOk());

        assertThat(roleOf(ws, member)).isEqualTo("MEMBER");
    }

    /** The whole point of the endpoint: an admin can now remove someone. */
    @Test
    void adminRemovesAMember() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        var member = addMember(ws, "MEMBER", "Mia");

        deleteMember(ws, login(admin), member.getId())
                .andExpect(status().isNoContent());

        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), member)).isFalse();
        // The ACCOUNT is global and survives — removal is not user deletion.
        assertThat(userRepository.findById(member.getId())).isPresent();
    }

    // ============================================================ last Owner

    @Test
    void theLastOwnerCannotBeDemoted() throws Exception {
        var ws = newWorkspace();

        patchMember(ws, ws.ownerToken(), ws.owner().getId(), "ADMIN")
                .andExpect(status().isConflict());

        assertThat(roleOf(ws, ws.owner())).isEqualTo("OWNER");
    }

    /** An owner must not be able to orphan the workspace through the admin path either. */
    @Test
    void theLastOwnerCannotRemoveThemselves() throws Exception {
        var ws = newWorkspace();

        deleteMember(ws, ws.ownerToken(), ws.owner().getId())
                .andExpect(status().isConflict());

        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), ws.owner())).isTrue();
    }

    /** With a second owner in place the guard stands down — it counts, it does not blanket-ban. */
    @Test
    void aSecondOwnerMakesTheFirstRemovable() throws Exception {
        var ws = newWorkspace();
        var coOwner = addMember(ws, "OWNER", "Otto");

        deleteMember(ws, ws.ownerToken(), coOwner.getId())
                .andExpect(status().isNoContent());
        // …and now the survivor is the last one again.
        deleteMember(ws, ws.ownerToken(), ws.owner().getId())
                .andExpect(status().isConflict());
    }

    @Test
    void theLastOwnerCanStillBeGivenTheSameRoleBack() throws Exception {
        var ws = newWorkspace();

        patchMember(ws, ws.ownerToken(), ws.owner().getId(), "OWNER")
                .andExpect(status().isOk());
    }

    // ============================================================ grant ceiling

    @Test
    void anAdminCannotPromoteAnyoneToOwner() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        var member = addMember(ws, "MEMBER", "Mia");

        patchMember(ws, login(admin), member.getId(), "OWNER")
                .andExpect(status().isForbidden());

        assertThat(roleOf(ws, member)).isEqualTo("MEMBER");
    }

    /** The other door: the ceiling applies to the target's CURRENT role too. */
    @Test
    void anAdminCannotDemoteAnOwner() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        addMember(ws, "OWNER", "Otto"); // so the last-Owner guard is not what refuses

        patchMember(ws, login(admin), ws.owner().getId(), "MEMBER")
                .andExpect(status().isForbidden());

        assertThat(roleOf(ws, ws.owner())).isEqualTo("OWNER");
    }

    @Test
    void anAdminCannotRemoveAnOwner() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        addMember(ws, "OWNER", "Otto");

        deleteMember(ws, login(admin), ws.owner().getId())
                .andExpect(status().isForbidden());

        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), ws.owner())).isTrue();
    }

    @Test
    void anOwnerCanMintAnotherOwner() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");

        patchMember(ws, ws.ownerToken(), member.getId(), "OWNER")
                .andExpect(status().isOk());

        assertThat(roleOf(ws, member)).isEqualTo("OWNER");
    }

    // ============================================================ tenancy

    /** A member below ADMIN is authorized, just not for this — 403, not 404. */
    @Test
    void aPlainMemberCannotAdministerMembership() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");
        var other = addMember(ws, "MEMBER", "Moe");
        var token = login(member);

        patchMember(ws, token, other.getId(), "ADMIN").andExpect(status().isForbidden());
        deleteMember(ws, token, other.getId()).andExpect(status().isForbidden());

        // …including on themselves: self-removal ("leave workspace") is deliberately NOT
        // this endpoint, so the admin gate refuses it rather than quietly doubling as one.
        patchMember(ws, token, member.getId(), "ADMIN").andExpect(status().isForbidden());
        deleteMember(ws, token, member.getId()).andExpect(status().isForbidden());
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), member)).isTrue();
    }

    /**
     * The top bug class: a caller who is not a member of the workspace must not be able to
     * tell it apart from one that does not exist — both 404, never 403, never 200.
     */
    @Test
    void aNonMemberGets404IndistinguishableFromAnUnknownWorkspace() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");
        var outsider = newWorkspace(); // owner of an entirely different tenant

        patchMember(ws, outsider.ownerToken(), member.getId(), "ADMIN")
                .andExpect(status().isNotFound());
        deleteMember(ws, outsider.ownerToken(), member.getId())
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/workspaces/" + UUID.randomUUID() + "/members/" + member.getId())
                        .header("Authorization", "Bearer " + outsider.ownerToken()))
                .andExpect(status().isNotFound());

        assertThat(roleOf(ws, member)).isEqualTo("MEMBER");
    }

    /**
     * A target who is not a member <em>here</em> is a 404 about the membership. An account
     * that exists in another tenant and an id that exists nowhere must be one answer, or
     * the endpoint becomes a global user-enumeration oracle.
     */
    @Test
    void aTargetWhoIsNotAMemberHereIs404AndRevealsNothingAboutTheAccount() throws Exception {
        var ws = newWorkspace();
        var strangerInAnotherTenant = newWorkspace().owner();

        var known = deleteMember(ws, ws.ownerToken(), strangerInAnotherTenant.getId())
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        var unknown = deleteMember(ws, ws.ownerToken(), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(detail(known)).isEqualTo(detail(unknown));
        assertThat(detail(known)).doesNotContain(strangerInAnotherTenant.getEmail());

        // PATCH is the same oracle if it answers differently — it must not.
        var patchKnown = patchMember(ws, ws.ownerToken(), strangerInAnotherTenant.getId(), "ADMIN")
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        var patchUnknown = patchMember(ws, ws.ownerToken(), UUID.randomUUID(), "ADMIN")
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(detail(patchKnown)).isEqualTo(detail(patchUnknown));
        assertThat(detail(patchKnown)).doesNotContain(strangerInAnotherTenant.getEmail());
        assertThat(detail(patchKnown)).isEqualTo(detail(known));
    }

    // ============================================================ idempotency

    @Test
    void aSecondDeleteIsACleanNotFound() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");

        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());
        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNotFound());
    }

    // ============================================================ the removal cascade

    /**
     * The whole cascade in one scenario: what must be cleaned up, what must survive, and
     * what must not leak into a second workspace the same account belongs to.
     */
    @Test
    void removalUnassignsWorkTidiesLeadershipAndLeavesHistoryAlone() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia Departing");
        projectMember(ws.project(), member, "MEMBER");
        var memberToken = login(member);

        // A second workspace the SAME account belongs to — the cross-tenant control.
        var other = newWorkspace();
        member(other.workspace(), member, "MEMBER");
        projectMember(other.project(), member, "MEMBER");
        var elsewhere = createIssue(other, other.ownerToken(), "Work elsewhere",
                "\"assigneeId\":\"" + member.getId() + "\"");

        // An issue they REPORTED and were assigned, plus a history row and a comment of
        // their own — the attribution that must outlive them.
        var number = createIssue(ws, memberToken, "Their issue",
                "\"assigneeId\":\"" + member.getId() + "\"");
        mockMvc.perform(patch(issues(ws) + "/" + number)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed by Mia\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(issues(ws) + "/" + number + "/comments")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Mia was here\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/workspaces/" + ws.workspace().getId() + "/filters")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mia's shared view\",\"hql\":\"\",\"shared\":true}"))
                .andExpect(status().isCreated());

        // ---- remove ----
        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());

        // Membership gone, in this workspace and in this workspace's projects only.
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), member)).isFalse();
        assertThat(projectMemberRepository.existsByProjectAndUser(ws.project(), member)).isFalse();
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(other.workspace(), member)).isTrue();
        assertThat(projectMemberRepository.existsByProjectAndUser(other.project(), member)).isTrue();

        // The issue is unassigned, and the unassignment is auditable.
        var issue = getIssue(ws, ws.ownerToken(), number);
        assertThat(issue.get("assignee").isNull()).isTrue();
        var unassign = historyRows(ws, number, "assignee");
        assertThat(unassign).hasSize(1);
        assertThat(unassign.get(0).get("oldValue").asText()).isEqualTo("Mia Departing");
        assertThat(unassign.get(0).get("newValue").isNull()).isTrue();

        // The other tenant's assignment is untouched — removal is workspace-scoped.
        var otherIssue = getIssue(other, other.ownerToken(), elsewhere);
        assertThat(otherIssue.get("assignee").isNull())
                .as("the other workspace's assignment was cleared by a removal issued elsewhere")
                .isFalse();
        assertThat(otherIssue.get("assignee").get("id").asText()).isEqualTo(member.getId().toString());

        // ---- historical attribution survives, all of it ----
        assertThat(issue.get("reporter").get("id").asText()).isEqualTo(member.getId().toString());
        assertThat(historyRows(ws, number, "title")).singleElement()
                .satisfies(h -> assertThat(h.get("changedById").asText())
                        .isEqualTo(member.getId().toString()));
        var comments = json.readTree(mockMvc.perform(get(issues(ws) + "/" + number + "/comments")
                        .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(comments.get("content").get(0).get("authorId").asText())
                .isEqualTo(member.getId().toString());
        // A shared saved filter resolves in the VIEWER's context, so it keeps working.
        var filters = json.readTree(mockMvc.perform(
                        get("/api/workspaces/" + ws.workspace().getId() + "/filters")
                                .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filters.toString()).contains("Mia's shared view");
    }

    /**
     * <strong>HD-31 §5.4, which removal must not overturn.</strong> A component lead who
     * leaves the workspace <em>keeps the row</em> — the module still shows who led it — and
     * the consequence is handled where HD-31 put it: {@code ComponentService.autoAssignee}
     * re-checks membership at issue-create time and silently skips a departed lead.
     *
     * <p>HD-132 briefly clearing {@code lead_id} would have contradicted a shipped,
     * twice-documented decision (the {@code V9__components.sql} comment and the
     * {@code Component.lead} javadoc), and would have been the only code path able to
     * produce {@code auto_assign = true} with no lead. Revoking access is not editing
     * project configuration — and the neighbouring rule agrees: a removed member's shared
     * saved filters are left alone for the same least-destructive reason.
     *
     * <p>The pre-removal assertion is the anchor: without it, "no auto-assignee afterwards"
     * would also pass if auto-assign simply never worked.
     */
    @Test
    void aDepartedComponentLeadIsKeptAndAutoAssignSkipsThem() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia Departing");
        projectMember(ws.project(), member, "MEMBER");

        var component = new Component();
        component.setWorkspace(ws.workspace());
        component.setProject(ws.project());
        component.setName("Billing");
        component.setLead(member);
        component.setAutoAssign(true);
        componentRepository.save(component);
        var componentId = "\"componentId\":\"" + component.getId() + "\"";

        // Anchor: while they are a member, auto-assign fires.
        var beforeIssue = getIssue(ws, ws.ownerToken(),
                createIssue(ws, ws.ownerToken(), "Before they left", componentId));
        assertThat(beforeIssue.get("assignee").isNull())
                .as("ANCHOR: auto-assign must demonstrably fire while the lead is a member — "
                    + "otherwise 'no assignee afterwards' proves nothing")
                .isFalse();
        assertThat(beforeIssue.get("assignee").get("id").asText())
                .isEqualTo(member.getId().toString());

        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());

        // The lead is KEPT — the row still records who led the module.
        assertThat(componentRepository.findAllByProject(ws.project(), true))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getLead())
                            .as("removal must not clear components.lead_id (HD-31 §5.4)")
                            .isNotNull();
                    assertThat(c.getLead().getId()).isEqualTo(member.getId());
                    assertThat(c.isAutoAssign())
                            .as("auto_assign must never be silently switched off either")
                            .isTrue();
                });

        // …and auto-assign silently skips them, exactly as HD-31 designed.
        var after = createIssue(ws, ws.ownerToken(), "After they left", componentId);
        assertThat(getIssue(ws, ws.ownerToken(), after).get("assignee").isNull()).isTrue();
    }

    /** An issue nobody left behind must not collect a spurious history row. */
    @Test
    void removingAMemberWithNoAssignedWorkWritesNoHistory() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");
        var number = createIssue(ws, ws.ownerToken(), "Unrelated");

        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());

        assertThat(historyRows(ws, number, "assignee")).isEmpty();
    }

    /**
     * <strong>Cross-workspace containment — the single most damaging bug this endpoint can
     * have.</strong> Removal runs two bulk statements keyed on a {@code User}, and a
     * {@code User} is global: drop the workspace predicate from either one and removing
     * somebody from workspace A silently unassigns their work, or evicts them from their
     * projects, in every other tenant they belong to. No single-workspace test can see it —
     * the scenario needs the same account in two workspaces at once.
     *
     * <p>Deliberately stronger than "the other tenant's assignee survives": it also asserts
     * the other tenant's issue collected <em>no</em> history row. An unscoped
     * {@code findAssignedRefsInWorkspace} paired with a correctly scoped UPDATE would leave
     * assignees intact and still forge an "unassigned" entry in a workspace the actor has
     * no access to — a leak of one tenant's membership event into another's audit trail.
     */
    @Test
    void removalIsContainedToTheWorkspaceItWasIssuedIn() throws Exception {
        var here = newWorkspace();
        var there = newWorkspace();
        var member = addMember(here, "MEMBER", "Mia Departing");
        member(there.workspace(), member, "MEMBER");
        projectMember(here.project(), member, "MEMBER");
        projectMember(there.project(), member, "MEMBER");

        var assignedHere = createIssue(here, here.ownerToken(), "Work here",
                "\"assigneeId\":\"" + member.getId() + "\"");
        var assignedThere = createIssue(there, there.ownerToken(), "Work there",
                "\"assigneeId\":\"" + member.getId() + "\"");
        var alsoThere = createIssue(there, there.ownerToken(), "More work there",
                "\"assigneeId\":\"" + member.getId() + "\"");

        deleteMember(here, here.ownerToken(), member.getId()).andExpect(status().isNoContent());

        // Here: everything the removal owns is gone.
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(here.workspace(), member)).isFalse();
        assertThat(projectMemberRepository.existsByProjectAndUser(here.project(), member)).isFalse();
        assertThat(getIssue(here, here.ownerToken(), assignedHere).get("assignee").isNull()).isTrue();

        // There: nothing moved. Not the membership, not the project role, not the work.
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(there.workspace(), member)).isTrue();
        assertThat(projectMemberRepository.existsByProjectAndUser(there.project(), member)).isTrue();
        for (var number : new long[]{assignedThere, alsoThere}) {
            var untouched = getIssue(there, there.ownerToken(), number);
            assertThat(untouched.get("assignee").isNull())
                    .as("issue %s in the untouched workspace was unassigned by a removal "
                        + "issued in a DIFFERENT workspace", number)
                    .isFalse();
            assertThat(untouched.get("assignee").get("id").asText())
                    .as("issue %s in the untouched workspace", number)
                    .isEqualTo(member.getId().toString());
            assertThat(historyRows(there, number, "assignee"))
                    .as("no forged audit row may appear in a workspace the actor never touched")
                    .isEmpty();
        }
    }

    /**
     * An attachment records <em>who uploaded it</em>, which is history, not access. The
     * blob and its attribution both outlive the uploader's membership — the file is the
     * workspace's, and "who added this" does not change because they left.
     */
    @Test
    void anAttachmentKeepsItsUploaderAfterTheyAreRemoved() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia Departing");
        projectMember(ws.project(), member, "MEMBER");
        var memberToken = login(member);
        var number = createIssue(ws, ws.ownerToken(), "Has an attachment");

        mockMvc.perform(multipart(issues(ws) + "/" + number + "/attachments")
                        .file(new MockMultipartFile("file", "spec.txt", "text/plain", "hello".getBytes()))
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isCreated());

        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());

        var attachments = json.readTree(mockMvc.perform(
                        get(issues(ws) + "/" + number + "/attachments")
                                .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(attachments.size()).isEqualTo(1);
        assertThat(attachments.get(0).get("uploadedById").asText()).isEqualTo(member.getId().toString());
        assertThat(attachments.get(0).get("uploadedByName").asText()).isEqualTo("Mia Departing");
        // …and it is still downloadable — attribution surviving is worth nothing if the
        // file went with the person.
        mockMvc.perform(get(issues(ws) + "/" + number + "/attachments/"
                        + attachments.get(0).get("id").asText())
                        .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isOk());
    }

    // ============================================================ revocation actually revokes

    /**
     * <strong>Removal must revoke, not merely tidy up.</strong> Access is decided by the
     * {@code workspace_members} row on every request and never by the JWT, so a token minted
     * while they were a member stops working the moment the row is gone — no logout, no
     * token expiry, no cache TTL to wait out. If this ever regressed, "removing" someone
     * would leave them with a working session for the rest of the token's lifetime, which
     * is the entire point of the endpoint undone.
     *
     * <p>The pre-removal call is the anchor: without it, a 404 afterwards would also pass if
     * the token had never worked.
     */
    @Test
    void aTokenMintedBeforeRemovalStopsWorkingImmediately() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia Departing");
        projectMember(ws.project(), member, "MEMBER");
        var token = login(member);

        // Anchor: the token works while the membership exists.
        mockMvc.perform(get(members(ws)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(issues(ws)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());

        // Same token, no re-login: the workspace is now indistinguishable from one that
        // does not exist — 404, never 403, and never a stale 200.
        mockMvc.perform(get(members(ws)).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(issues(ws)).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /**
     * The quieter half of the same rule: a <em>demotion</em> also bites on the very next
     * request with the same token. S1 introduced a role→permissions cache, and the one thing
     * it must never cache is <em>membership</em> — the role a member holds is re-read per
     * request, so stripping ADMIN takes effect at once rather than after the cache TTL.
     */
    @Test
    void aDemotionBitesOnTheNextRequestWithTheSameToken() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        var bystander = addMember(ws, "MEMBER", "Moe");
        var adminToken = login(admin);

        // Anchor: the token administers membership while the role backs it.
        patchMember(ws, adminToken, bystander.getId(), "MEMBER")
                .andExpect(status().isOk());

        patchMember(ws, ws.ownerToken(), admin.getId(), "MEMBER")
                .andExpect(status().isOk());

        // Same token, no re-login, no waiting for a cache to expire.
        patchMember(ws, adminToken, bystander.getId(), "ADMIN")
                .andExpect(status().isForbidden());
        deleteMember(ws, adminToken, bystander.getId())
                .andExpect(status().isForbidden());
        assertThat(roleOf(ws, bystander)).isEqualTo("MEMBER");
    }

    /** …and symmetrically, a promotion is live at once too — the cache cuts both ways. */
    @Test
    void aPromotionBitesOnTheNextRequestWithTheSameToken() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");
        var bystander = addMember(ws, "MEMBER", "Moe");
        var token = login(member);

        // Anchor: as a plain member the token cannot administer membership.
        patchMember(ws, token, bystander.getId(), "ADMIN")
                .andExpect(status().isForbidden());

        patchMember(ws, ws.ownerToken(), member.getId(), "ADMIN")
                .andExpect(status().isOk());

        patchMember(ws, token, bystander.getId(), "ADMIN")
                .andExpect(status().isOk());
        assertThat(roleOf(ws, bystander)).isEqualTo("ADMIN");
    }

    // ============================================================ request body

    /**
     * A role this build cannot assign is a client error, not a 500 and — much worse — not a
     * silent fall-through to some default role. The membership must be exactly as it was.
     *
     * <p><strong>422, not 400, since HD-126 (S3).</strong> {@code role} was a
     * {@code WorkspaceRole} enum and Jackson rejected an unknown value with 400 before the
     * handler ran; the enum is deleted (it cannot name a custom role, which S4 ships), so
     * the field is a key string validated by {@code RoleCatalog.requireAssignable}. 422
     * "Unknown role" is §12's verdict for every role reference that cannot be honoured —
     * and it has to be, because the same path will shortly resolve role <em>ids</em>: if a
     * foreign workspace's role id answered 404 and a nonsense one answered something else,
     * the pair would be an oracle for which role ids exist in other tenants.
     */
    @Test
    void anUnknownRoleIsACleanUnprocessableContent() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");

        patchRaw(ws, ws.ownerToken(), member.getId(), "{\"role\":\"SUPERUSER\"}")
                .andExpect(status().isUnprocessableContent());
        // Keys are exact: a lowercase one is not "the same role, casually written".
        patchRaw(ws, ws.ownerToken(), member.getId(), "{\"role\":\"owner\"}")
                .andExpect(status().isUnprocessableContent());
        // …and neither is a PROJECT-scoped built-in key. Scope is part of the question:
        // MEMBER names a different role in each scope, and a PermissionSet does not
        // remember which one its grants came from (§12).
        patchRaw(ws, ws.ownerToken(), member.getId(), "{\"role\":\"MANAGER\"}")
                .andExpect(status().isUnprocessableContent());

        assertThat(roleOf(ws, member)).isEqualTo("MEMBER");
    }

    /**
     * {@code role} is the whole body, so an omitted or null one is a client bug — 400 from
     * {@code @NotBlank}, never an accepted no-op and never an NPE-shaped 500.
     */
    @Test
    void aMissingOrNullRoleIsACleanBadRequest() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "ADMIN", "Ada");

        patchRaw(ws, ws.ownerToken(), member.getId(), "{}")
                .andExpect(status().isBadRequest());
        patchRaw(ws, ws.ownerToken(), member.getId(), "{\"role\":null}")
                .andExpect(status().isBadRequest());
        patchRaw(ws, ws.ownerToken(), member.getId(), "{\"role\":\"\"}")
                .andExpect(status().isBadRequest());
        patchRaw(ws, ws.ownerToken(), member.getId(), "")
                .andExpect(status().isBadRequest());

        assertThat(roleOf(ws, member)).isEqualTo("ADMIN");
    }

    // ============================================================ HD-126 (S3)

    /**
     * <strong>The gate is {@code workspace.member.manage} now</strong> (§10.1), not the
     * {@code WorkspaceRole} ladder — and the 403 says so, which the ladder's
     * "InsufficientWorkspaceRole" never did.
     *
     * <p>The ceiling is a <em>different</em> refusal from the permission, and this asserts
     * both, because collapsing them is how "you cannot do this at all" and "you cannot do
     * this to <em>that</em> person" become one unactionable message.
     */
    @Test
    void theRefusalsNameWhatActuallyRefused() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");
        var admin = addMember(ws, "ADMIN", "Ada");
        addMember(ws, "OWNER", "Otto"); // so the last-Owner guard is not what refuses

        // No permission at all → names the permission.
        var noPermission = patchMember(ws, login(member), admin.getId(), "MEMBER")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail(noPermission)).contains("workspace.member.manage");

        // Permission held, ceiling refuses → says so, and does NOT name a permission the
        // caller does hold. Owner and Admin are seeded with IDENTICAL sets on purpose
        // ("Owner is a guardrail on assignment, not a bigger role"), so set containment
        // alone cannot refuse this and the built-in-Owner guardrail is what does.
        var ceiling = patchMember(ws, login(admin), ws.owner().getId(), "MEMBER")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail(ceiling)).containsIgnoringCase("owner");
        assertThat(detail(ceiling)).doesNotContain("workspace.member.manage");

        assertThat(roleOf(ws, ws.owner())).isEqualTo("OWNER");
    }

    /**
     * The invite path and the member-administration paths share one implementation of the
     * gate and the ceiling ({@code WorkspaceMemberService}), so they cannot drift — with
     * one deliberate asymmetry: an invite refuses Owner <em>even for an Owner</em>. You
     * promote a colleague to owner; you do not invite a stranger as one.
     */
    @Test
    void invitingSharesTheGateAndRefusesOwnerEvenForAnOwner() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");

        var refused = invitePost(ws, login(member), "someone@example.com", "MEMBER")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail(refused)).contains("workspace.member.manage");

        // An OWNER may mint another Owner through PATCH (asserted above) but not here.
        invitePost(ws, ws.ownerToken(), "owner-to-be@example.com", "OWNER")
                .andExpect(status().isForbidden());
        // …and an unusable role key is 422 here for the same reason it is on PATCH.
        invitePost(ws, ws.ownerToken(), "someone@example.com", "SUPERUSER")
                .andExpect(status().isUnprocessableContent());
        // The ordinary case still works.
        invitePost(ws, ws.ownerToken(), "someone@example.com", "ADMIN")
                .andExpect(status().isCreated());
    }

    private ResultActions invitePost(Ws ws, String token, String email, String role) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ws.workspace().getId() + "/invites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"));
    }

    // ================================================ HD-126 audit: the Owner ceiling, every path

    /**
     * <strong>The Owner-vs-Admin ceiling has to hold on every door at once</strong>, because
     * V13 seeds workspace Owner and Admin with <em>identical</em> permission sets ("Owner is a
     * guardrail on assignment, not a bigger role"). Set containment therefore cannot separate
     * them at all — {@code firstNotCovered} returns empty in both directions — and the only
     * thing standing between an Admin and an Owner is the built-in-Owner guardrail keyed on the
     * role id. One unguarded path is the whole rule.
     *
     * <p>The paths, and the anchor for each: an Admin may administer ordinary members (so a
     * blanket 403 cannot be what passes this test), and may not touch an Owner or mint one —
     * by promotion, by demotion, by removal or by invitation.
     *
     * <p><strong>Including remove-then-invite</strong>, the laundering route: removing somebody
     * is allowed, so if the invite door did not carry the same rule an Admin could remove a
     * member and invite them straight back as an Owner — an Owner they could then never
     * demote, and who could demote them. Two legal steps composing into the escalation neither
     * one permits.
     */
    @Test
    void theOwnerGuardrailHoldsOnEveryMembershipPathIncludingRemoveThenInvite() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        var adminToken = login(admin);
        var coOwner = addMember(ws, "OWNER", "Otto"); // so last-Owner is never what refuses
        var member = addMember(ws, "MEMBER", "Mia");

        // ---- ANCHOR: this Admin really can administer ordinary membership ----
        patchMember(ws, adminToken, member.getId(), "ADMIN").andExpect(status().isOk());
        patchMember(ws, adminToken, member.getId(), "MEMBER").andExpect(status().isOk());

        // ---- promotion ----
        patchMember(ws, adminToken, member.getId(), "OWNER").andExpect(status().isForbidden());
        // ---- demotion, on BOTH owners (the workspace creator and a promoted one) ----
        patchMember(ws, adminToken, ws.owner().getId(), "MEMBER").andExpect(status().isForbidden());
        patchMember(ws, adminToken, coOwner.getId(), "MEMBER").andExpect(status().isForbidden());
        // ---- removal ----
        deleteMember(ws, adminToken, coOwner.getId()).andExpect(status().isForbidden());
        // ---- invitation ----
        invitePost(ws, adminToken, "owner-by-invite@example.com", "OWNER")
                .andExpect(status().isForbidden());

        // ---- the laundering route: remove, then invite back one rung higher ----
        deleteMember(ws, adminToken, member.getId()).andExpect(status().isNoContent());
        invitePost(ws, adminToken, member.getEmail(), "OWNER")
                .andExpect(status().isForbidden());
        // …while the legitimate re-invite still works, so the rule is about OWNER and not
        // about re-inviting.
        invitePost(ws, adminToken, member.getEmail(), "ADMIN")
                .andExpect(status().isCreated());

        // Nothing moved: both owners still owners, and no owner was minted.
        assertThat(roleOf(ws, ws.owner())).isEqualTo("OWNER");
        assertThat(roleOf(ws, coOwner)).isEqualTo("OWNER");
        assertThat(roleOf(ws, admin)).isEqualTo("ADMIN");
    }

    /**
     * The guardrail is keyed on the built-in Owner's <strong>role id</strong>, never on the
     * key string {@code "OWNER"} — and this is the test that says so out loud, because
     * {@code roles_scope_key_uk} is {@code UNIQUE NULLS NOT DISTINCT}, so once S4 ships the
     * role editor a workspace may own a role of its own keyed {@code OWNER}.
     *
     * <p>A string-keyed guardrail would break in the dangerous direction <em>and</em> the
     * annoying one: a workspace could mint an unremovable "OWNER" that no Admin may ever
     * administer (a permanent backdoor account, since the custom role need grant nothing),
     * and the real guardrail would still be bypassable by any other key. Here the custom
     * role grants strictly less than an Admin holds, so the ceiling has nothing to refuse and
     * the edit must go through.
     */
    @Test
    void aCustomRoleMerelyKeyedOwnerIsNotTheOwnerGuardrail() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        var impostor = addMember(ws, "MEMBER", "Imo");

        // A workspace-owned role that calls itself OWNER and grants one ordinary permission.
        assignCustomRole(ws, impostor, "OWNER", grant(Permission.PROJECT_CREATE, false));

        patchMember(ws, login(admin), impostor.getId(), "MEMBER")
                .andExpect(status().isOk());
        assertThat(roleOf(ws, impostor)).isEqualTo("MEMBER");
    }

    // ================================================ HD-126 audit: the set half of the ceiling

    /**
     * <strong>The other half of §11.2, which no built-in pair can exercise.</strong> Owner and
     * Admin hold the same 8 workspace permissions and Member's grants are a strict subset, so
     * {@code firstNotCovered} never fires between built-ins — every ceiling refusal the suite
     * has seen so far came from the built-in-Owner guardrail. If the set comparison were
     * deleted outright, all of those tests would still pass.
     *
     * <p>{@code project.administer.all} is the one catalog entry <em>no</em> built-in is
     * seeded with (V13: seeding it would silently open five project-MANAGER-only gates for
     * every workspace admin in every install), which makes it the only permission a custom
     * role can hold that puts it above an Owner. So it is also the proof that the ceiling is
     * about grants and not about rank: the workspace <em>Owner</em> is refused too.
     */
    @Test
    void theSetCeilingRefusesARoleWiderThanTheActorEvenForAnOwner() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        var wideMember = addMember(ws, "MEMBER", "Wilma");
        assignCustomRole(ws, wideMember, "program-manager",
                grant(Permission.PROJECT_ADMINISTER_ALL, false));

        for (var token : new String[]{login(admin), ws.ownerToken()}) {
            var refused = patchMember(ws, token, wideMember.getId(), "MEMBER")
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();
            assertThat(detail(refused))
                    .as("the ceiling must name the grant that refused, not 'insufficient role'")
                    .contains(Permission.PROJECT_ADMINISTER_ALL.key());
            // …and it is the SET, not the Owner guardrail, that spoke.
            assertThat(detail(refused)).doesNotContain("Only an Owner");

            deleteMember(ws, token, wideMember.getId()).andExpect(status().isForbidden());
        }
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), wideMember))
                .isTrue();
    }

    /**
     * Width is compared <em>per grant</em>: an own-only holder may hand out the own-only form
     * and never the unrestricted one. Without that, a role holding {@code label.manage:own}
     * (which every plain workspace Member holds) plus {@code workspace.member.manage} could
     * mint an Admin holding {@code label.manage} unrestricted — self-escalation by proxy, one
     * promotion away.
     *
     * <p>The pair is the whole assertion: granting built-in <em>Member</em> (own-only
     * {@code label.manage}) must succeed for the same actor that is refused built-in Admin.
     * Only one of the two is a statement about width.
     */
    @Test
    void anOwnOnlyHolderCannotMintAnUnrestrictedGrant() throws Exception {
        var ws = newWorkspace();
        var narrowAdmin = addMember(ws, "MEMBER", "Nadia");
        var target = addMember(ws, "MEMBER", "Mia");
        // Everything the built-in Admin holds, EXCEPT that label.manage is narrowed to their
        // own labels. Deliberately the whole set minus one width: {@code firstNotCovered}
        // walks the catalog in order and reports the first gap, so a fixture missing other
        // permissions would be refused for one of those instead and would prove nothing about
        // own-vs-unrestricted.
        assignCustomRole(ws, narrowAdmin, "membership-clerk",
                grant(Permission.WORKSPACE_EDIT, false),
                grant(Permission.WORKSPACE_MEMBER_MANAGE, false),
                grant(Permission.WORKSPACE_ROLE_MANAGE, false),
                grant(Permission.WORKSPACE_TAXONOMY_MANAGE, false),
                grant(Permission.PROJECT_CREATE, false),
                grant(Permission.PROJECT_CURATE_ALL, false),
                grant(Permission.LABEL_CREATE, false),
                grant(Permission.LABEL_MANAGE, true));
        var token = login(narrowAdmin);

        // ANCHOR: the same-width grant goes through — built-in Member holds label.manage:own.
        patchMember(ws, token, target.getId(), "MEMBER").andExpect(status().isOk());

        var refused = patchMember(ws, token, target.getId(), "ADMIN")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(detail(refused))
                .as("an own-only holder minting an UNRESTRICTED grant must be refused, naming it")
                .contains(Permission.LABEL_MANAGE.key());
        assertThat(roleOf(ws, target)).isEqualTo("MEMBER");
    }

    // ================================================ HD-126 audit: 422 "Unknown role"

    /**
     * <strong>Every unusable role reference gets one answer, and the answer says nothing
     * about which roles exist where.</strong> §12's reason for 422 over 404 is that the same
     * code path will shortly resolve role <em>ids</em>: if a key belonging to another
     * workspace answered differently from one that exists nowhere, the pair would be an
     * oracle for another tenant's role catalog.
     *
     * <p>S4 has not shipped the role editor, so the foreign role is written straight to the
     * table — which is exactly the state the upgrade will produce, and the state that makes
     * the comparison meaningful today rather than after the leak is reachable.
     */
    @Test
    void everyUnusableRoleKeyIsTheSame422AndNamesNothingItShouldNot() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");

        // A custom role that exists — in SOMEBODY ELSE's workspace.
        var elsewhere = newWorkspace();
        var foreignHolder = addMember(elsewhere, "MEMBER", "Fay");
        assignCustomRole(elsewhere, foreignHolder, "FOREIGN_KEY_ROLE",
                grant(Permission.PROJECT_CREATE, false));

        var foreign = detail(patchRaw(ws, ws.ownerToken(), member.getId(),
                "{\"role\":\"FOREIGN_KEY_ROLE\"}")
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString());
        var ghost = detail(patchRaw(ws, ws.ownerToken(), member.getId(),
                "{\"role\":\"GHOST_KEY_ROLE\"}")
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString());

        // Identical but for the key the caller sent back — which is theirs already.
        assertThat(foreign.replace("FOREIGN_KEY_ROLE", "X"))
                .as("a key that exists in another workspace must be indistinguishable from one "
                    + "that exists nowhere — otherwise this endpoint enumerates other tenants' roles")
                .isEqualTo(ghost.replace("GHOST_KEY_ROLE", "X"));
        assertThat(foreign).doesNotContain(elsewhere.workspace().getId().toString());

        // The same one answer for a wrong-case key, a wrong-SCOPE built-in key, and a value
        // that is not a key at all but is still a well-formed string.
        for (var key : new String[]{"SUPERUSER", "owner", "MANAGER", "COMMENTER", "123"}) {
            var body = detail(patchRaw(ws, ws.ownerToken(), member.getId(),
                    "{\"role\":" + json.writeValueAsString(key) + "}")
                    .andExpect(status().isUnprocessableContent())
                    .andReturn().getResponse().getContentAsString());
            assertThat(body).isEqualTo(ghost.replace("GHOST_KEY_ROLE", key));
        }

        // A body whose `role` is not a string at all is a malformed REQUEST, not an unusable
        // value: 400, and still never a 500 and never a silent fall-through to a default.
        for (var raw : new String[]{"{\"role\":[\"OWNER\"]}", "{\"role\":{\"key\":\"OWNER\"}}",
                "{\"role\":\"   \"}"}) {
            patchRaw(ws, ws.ownerToken(), member.getId(), raw)
                    .andExpect(status().isBadRequest());
        }

        assertThat(roleOf(ws, member)).isEqualTo("MEMBER");
        // …and the invite door answers identically, since it shares requireAssignable.
        var invited = detail(invitePost(ws, ws.ownerToken(), "x@example.com", "GHOST_KEY_ROLE")
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString());
        assertThat(invited).isEqualTo(ghost);
    }

    // ================================================ custom-role fixture (no editor until S4)

    private static RolePermission grant(Permission permission, boolean ownOnly) {
        return new RolePermission(permission, ownOnly);
    }

    /**
     * Give {@code user} a <strong>workspace-owned custom role</strong> with exactly these
     * grants. Written through the {@link jakarta.persistence.EntityManager} because
     * {@code RoleRepository} deliberately exposes no {@code save} until S4 — and needed here
     * because no built-in pair can exercise the set half of the grant ceiling at all.
     */
    private void assignCustomRole(Ws ws, User user, String key, RolePermission... grants) {
        var roleId = txTemplate.execute(status -> {
            var role = new Role();
            role.setWorkspaceId(ws.workspace().getId());
            role.setScope(RoleScope.WORKSPACE);
            role.setKey(key);
            role.setName(key);
            role.setBuiltIn(false);
            role.setPermissions(new LinkedHashSet<>(java.util.List.of(grants)));
            entityManager.persist(role);
            entityManager.flush();
            return role.getId();
        });
        var membership = workspaceMemberRepository
                .findByWorkspaceAndUser(ws.workspace(), user).orElseThrow();
        membership.setRole(roleCatalog.reference(roleId));
        workspaceMemberRepository.save(membership);
    }

    // ============================================================ revoking the ways back in

    /**
     * <strong>Removal that leaves an invite standing does not remove anybody.</strong>
     * {@code inviteMember} only refuses someone who is <em>already</em> a member and there is
     * no {@code UNIQUE(workspace_id, email)} on {@code workspace_invites}, so a leftover
     * unaccepted invite is an ordinary consequence of a re-send. It is invisible while they
     * are a member — {@code listPendingInvites} filters on {@code !existsByWorkspaceAndUser}
     * — which means the removal is precisely what makes it reappear, as a live join button.
     * One click and they are back at the invite's role, with no admin action and no
     * notification.
     */
    @Test
    void removalRevokesLeftoverInvitesSoTheRemovedMemberCannotWalkBackIn() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");
        var memberToken = login(member);

        // A pending invite for them in THIS workspace (a re-send that was never accepted),
        // an accepted one (the historical record of how they joined), and a pending one in
        // ANOTHER tenant that this removal must not touch.
        var pending = invite(ws.workspace(), member.getEmail(), "ADMIN", false);
        var accepted = invite(ws.workspace(), member.getEmail(), "MEMBER", true);
        var elsewhere = newWorkspace();
        var foreign = invite(elsewhere.workspace(), member.getEmail(), "MEMBER", false);

        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());

        assertThat(inviteRepository.findById(pending)).as("pending invite revoked").isEmpty();
        assertThat(inviteRepository.findById(accepted)).as("accepted invite kept as history").isPresent();
        assertThat(inviteRepository.findById(foreign)).as("another tenant's invite untouched").isPresent();

        // End to end: the join button is gone from their onboarding screen, and only the
        // other tenant's invite remains.
        var visible = json.readTree(mockMvc.perform(get("/api/invites")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(visible.toString()).doesNotContain(ws.workspace().getId().toString());
        assertThat(visible.toString()).contains(elsewhere.workspace().getId().toString());
    }

    // ============================================================ no self-removal

    /**
     * The grant ceiling passes trivially when actor and target are the same person, so
     * without an explicit refusal an admin could delete their own membership through a door
     * the docs say does not exist. 422: their permissions are fine, the endpoint simply is
     * not "leave workspace".
     */
    @Test
    void anAdminCannotRemoveThemselves() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        var token = login(admin);

        deleteMember(ws, token, admin.getId())
                .andExpect(status().isUnprocessableContent());

        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), admin)).isTrue();
    }

    /** Not the last-Owner guard doing the work — a co-owner exists, and it is still refused. */
    @Test
    void anOwnerWithACoOwnerStillCannotRemoveThemselves() throws Exception {
        var ws = newWorkspace();
        addMember(ws, "OWNER", "Otto");

        deleteMember(ws, ws.ownerToken(), ws.owner().getId())
                .andExpect(status().isUnprocessableContent());

        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), ws.owner())).isTrue();
    }

    /**
     * Self-<em>demotion</em> stays legal — an owner stepping down while another owner exists
     * is an ordinary handover, and the guard that matters ({@code requireNotLastOwner})
     * already covers the case that would orphan the workspace.
     */
    @Test
    void anOwnerMayStepDownWhileAnotherOwnerExists() throws Exception {
        var ws = newWorkspace();
        addMember(ws, "OWNER", "Otto");

        patchMember(ws, ws.ownerToken(), ws.owner().getId(), "ADMIN")
                .andExpect(status().isOk());

        assertThat(roleOf(ws, ws.owner())).isEqualTo("ADMIN");
    }

    // ============================================================ the unassign cannot be reverted

    /**
     * <strong>The bulk unassign bumps {@code @Version} on purpose.</strong> {@code Issue}
     * has no {@code @DynamicUpdate}, so every flush writes every column from the snapshot
     * that transaction loaded — {@code assignee_id} included, whether or not the editor
     * touched it. Left un-versioned, an editor who opened the issue before the removal and
     * saved an unrelated title change would write the departed assignee straight back, and
     * their optimistic-lock check would still match, so nothing would notice:
     * {@code issue_history} would say unassigned while the row said otherwise.
     *
     * <p>MockMvc cannot hold a transaction open across requests, but it does not need to —
     * what a concurrent editor holds is a <em>stale version number</em>, and sending it is
     * exactly what the issue PATCH's optimistic-lock check consumes.
     */
    @Test
    void aStaleConcurrentEditCannotSilentlyRestoreTheRemovedAssignee() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");
        var number = createIssue(ws, ws.ownerToken(), "Their work",
                "\"assigneeId\":\"" + member.getId() + "\"");
        // What an editor who opened the issue BEFORE the removal is holding.
        var staleVersion = getIssue(ws, ws.ownerToken(), number).get("version").asInt();

        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());

        // The unassign is visible AND the version moved, so the stale edit is rejected
        // rather than silently winning.
        var after = getIssue(ws, ws.ownerToken(), number);
        assertThat(after.get("assignee").isNull()).isTrue();
        assertThat(after.get("version").asInt()).isGreaterThan(staleVersion);

        mockMvc.perform(patch(issues(ws) + "/" + number)
                        .header("Authorization", "Bearer " + ws.ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed by a stale editor\",\"version\":" + staleVersion + "}"))
                .andExpect(status().isConflict());

        // …and after refreshing, the retry succeeds without resurrecting the assignee.
        mockMvc.perform(patch(issues(ws) + "/" + number)
                        .header("Authorization", "Bearer " + ws.ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed after refresh\",\"version\":"
                                + after.get("version").asInt() + "}"))
                .andExpect(status().isOk());
        assertThat(getIssue(ws, ws.ownerToken(), number).get("assignee").isNull()).isTrue();
    }

    // ============================================================ live streams are closed

    /**
     * {@code SseController} checks membership only at subscribe, so a removed member keeps
     * receiving live workspace activity until their emitter times out (~30 min). The drop
     * has to be wired through an AFTER_COMMIT domain event — this test proves the whole
     * chain (service → event → listener → registry), not just the registry method.
     */
    @Test
    void removalClosesTheRemovedMembersLiveSseStreams() throws Exception {
        var ws = newWorkspace();
        var member = addMember(ws, "MEMBER", "Mia");
        var bystander = addMember(ws, "MEMBER", "Moe");

        var theirs = trackedEmitter(ws.workspace().getId(), member.getId());
        var bystanders = trackedEmitter(ws.workspace().getId(), bystander.getId());

        deleteMember(ws, ws.ownerToken(), member.getId()).andExpect(status().isNoContent());

        assertThat(theirs.get()).as("the removed member's stream is closed").isTrue();
        assertThat(bystanders.get()).as("everyone else keeps their stream").isFalse();
    }

    /** A rolled-back removal must not drop a live stream — the reason the hook is AFTER_COMMIT. */
    @Test
    void arefusedRemovalLeavesTheStreamAlone() throws Exception {
        var ws = newWorkspace();
        var admin = addMember(ws, "ADMIN", "Ada");
        var owner = ws.owner();

        var ownersStream = trackedEmitter(ws.workspace().getId(), owner.getId());

        // An ADMIN may not act on an OWNER — 403, nothing committed.
        deleteMember(ws, login(admin), owner.getId()).andExpect(status().isForbidden());

        assertThat(ownersStream.get()).as("a refused removal closes nothing").isFalse();
    }

    // ============================================================ HTTP helpers

    private ResultActions patchRaw(Ws ws, String token, UUID userId, String body) throws Exception {
        return mockMvc.perform(patch(members(ws) + "/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchMember(Ws ws, String token, UUID userId, String role) throws Exception {
        return mockMvc.perform(patch(members(ws) + "/" + userId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + role + "\"}"));
    }

    private ResultActions deleteMember(Ws ws, String token, UUID userId) throws Exception {
        return mockMvc.perform(delete(members(ws) + "/" + userId)
                .header("Authorization", "Bearer " + token));
    }

    private String members(Ws ws) {
        return "/api/workspaces/" + ws.workspace().getId() + "/members";
    }

    private String issues(Ws ws) {
        return "/api/workspaces/" + ws.workspace().getId() + "/projects/" + ws.project().getId() + "/issues";
    }

    /** The role the members listing reports for a user — read through the API, not the row. */
    private String roleOf(Ws ws, User user) throws Exception {
        var body = mockMvc.perform(get(members(ws)).header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var m : json.readTree(body)) {
            if (m.get("userId").asText().equals(user.getId().toString())) return m.get("role").asText();
        }
        return null;
    }

    private long createIssue(Ws ws, String token, String title, String... extra) throws Exception {
        var parts = new ArrayList<String>();
        parts.add("\"title\":" + json.writeValueAsString(title));
        parts.add("\"typeId\":\"" + firstId(ws.config(), "issueTypes") + "\"");
        parts.add("\"statusId\":\"" + todoStatusId(ws.config()) + "\"");
        for (var e : extra) parts.add(e);
        var body = mockMvc.perform(post(issues(ws))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + String.join(",", parts) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("number").asLong();
    }

    private JsonNode getIssue(Ws ws, String token, long number) throws Exception {
        var body = mockMvc.perform(get(issues(ws) + "/" + number).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private java.util.List<JsonNode> historyRows(Ws ws, long number, String field) throws Exception {
        var body = mockMvc.perform(get(issues(ws) + "/" + number + "/history?size=100")
                        .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var out = new ArrayList<JsonNode>();
        for (var h : json.readTree(body).get("content")) {
            if (h.get("field").asText().equals(field)) out.add(h);
        }
        return out;
    }

    private static UUID firstId(JsonNode config, String collection) {
        return UUID.fromString(config.get(collection).get(0).get("id").asText());
    }

    private static UUID todoStatusId(JsonNode config) {
        for (var s : config.get("statuses")) {
            if (s.get("category").asText().equals("TODO")) return UUID.fromString(s.get("id").asText());
        }
        throw new AssertionError("no TODO-category status");
    }

    /** The {@code detail} of a ProblemDetail body — what the SPA actually renders. */
    private String detail(String problemJson) throws Exception {
        var node = json.readTree(problemJson).get("detail");
        return node == null ? "" : node.asText();
    }

    // ============================================================ bootstrap

    /** A workspace + project + its OWNER, already logged in, with the project config read. */
    private record Ws(Workspace workspace, Project project, User owner, String ownerToken, JsonNode config) {}

    private Ws newWorkspace() throws Exception {
        var owner = user("Owner");
        var ws = workspace(owner);
        member(ws, owner, "OWNER");
        var project = project(ws, owner);
        projectMember(project, owner, "MANAGER");
        var token = login(owner);
        var body = mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/projects/" + project.getId() + "/config")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Ws(ws, project, owner, token, json.readTree(body));
    }

    private User addMember(Ws ws, String role, String displayName) {
        var u = user(displayName);
        member(ws.workspace(), u, role);
        return u;
    }

    private User user(String displayName) {
        var u = new User();
        u.setEmail(("u-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com").toLowerCase());
        u.setDisplayName(displayName);
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

    private Project project(Workspace ws, User creator) {
        var p = new Project();
        p.setWorkspace(ws);
        p.setName("Proj");
        p.setKey("P" + (Math.abs(UUID.randomUUID().hashCode()) % 100000));
        p.setCreatedBy(creator);
        return projectRepository.save(p);
    }

    /**
     * A {@code workspace_invites} row, written straight through the repository: the feature
     * under test is invite <em>revocation</em>, and driving the real invite flow would mean
     * fishing an emailed token out of MailHog to produce the one state that matters (an
     * unaccepted row for someone who is already a member).
     */
    private UUID invite(Workspace ws, String email, String role, boolean accepted) {
        var i = new WorkspaceInvite();
        i.setWorkspace(ws);
        i.setEmail(email.toLowerCase());
        i.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        i.setTokenHash(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        i.setInvitedBy(ws.getCreatedBy());
        i.setExpiresAt(Instant.now().plusSeconds(7 * 24 * 3600));
        if (accepted) i.setAcceptedAt(Instant.now());
        return inviteRepository.save(i).getId();
    }

    /**
     * Register a stream for {@code userId} on {@code workspaceId} and hand back a flag that
     * flips when it is completed.
     *
     * <p>Injected into the registry's map the way {@code SseRegistryBroadcastTest} does,
     * rather than through {@code subscribe()}: {@code ResponseBodyEmitter.onCompletion}
     * holds ONE callback, so registering our own observer would clobber the cleanup callback
     * {@code subscribe()} installs — we would be testing a registry we had just broken.
     * Overriding {@code complete()} observes without displacing anything.
     */
    @SuppressWarnings("unchecked")
    private java.util.concurrent.atomic.AtomicBoolean trackedEmitter(UUID workspaceId, UUID userId)
            throws Exception {
        var completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var emitter = new SseEmitter() {
            @Override
            public void complete() {
                completed.set(true);
                super.complete();
            }
        };
        var field = SseRegistry.class.getDeclaredField("connections");
        field.setAccessible(true);
        var connections = (java.util.Map<UUID, java.util.List<SseRegistry.UserEmitter>>) field.get(sseRegistry);
        connections.computeIfAbsent(workspaceId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new SseRegistry.UserEmitter(userId, emitter));
        return completed;
    }

    private void projectMember(Project project, User user, String role) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.PROJECT, role));
        projectMemberRepository.save(m);
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
