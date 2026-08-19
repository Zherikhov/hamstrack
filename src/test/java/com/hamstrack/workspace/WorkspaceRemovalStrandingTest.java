package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.entity.ProjectMember;
import com.hamstrack.project.repository.ProjectMemberRepository;
import com.hamstrack.project.service.ProjectAdminGuard;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Role;
import com.hamstrack.workspace.entity.RolePermission;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceMemberService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-136 — <strong>{@code DELETE /api/workspaces/{ws}/members/{userId}} must not strand a
 * project.</strong>
 *
 * <p>A workspace removal deletes every {@code project_members} row the departing member
 * holds in that workspace, and did so with no project-level check at all: the invariant
 * {@code ProjectService.removeMember} refuses to break one project at a time
 * ("a project that has an administrator must keep one") was freely breakable — for any
 * number of projects at once — through this endpoint. Nothing covered the case before this
 * class existed.
 *
 * <p>It matters because {@code project.member.manage} is deliberately outside the
 * workspace-admin curator bypass ({@code Permission.projectCuration()}): a project with no
 * holder of it can never get one back through the API, by anybody, and can no longer be
 * archived or have its taxonomy managed either. Recovery is a manual {@code UPDATE}.
 *
 * <p>The owner's decision, which these tests pin: <strong>refuse with 409 and name every
 * affected project</strong>. Offboarding is blocked until those projects get another
 * administrator — visible, and fixable in a minute — instead of silently stranding them
 * for someone to discover months later.
 *
 * <p>The second half is the one the built-in roles cannot show: the guard asks who holds
 * {@code project.member.manage}, not who carries the built-in Project admin role id, so a
 * project whose sole administrator holds a <em>custom</em> role is protected too. That is
 * why HD-136 lands before the S4 role editor rather than after it.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class WorkspaceRemovalStrandingTest {

    @Autowired MockMvc mockMvc;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ProjectAdminGuard guard;
    @Autowired WorkspaceMemberService memberService;
    // Custom roles are written through the EntityManager: RoleRepository deliberately
    // exposes no save() until S4 ships the role editor (see its javadoc).
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;

    private final ObjectMapper json = new ObjectMapper();

    // ==================================================== the headline case

    /**
     * <strong>The case nothing covered.</strong> Mia is the only administrator of one
     * project; removing her from the workspace would delete that row and leave the project
     * unmanageable. The refusal names the project, and nothing at all is removed — not the
     * workspace membership, not the project membership.
     */
    @Test
    void removingTheSoleAdministratorOfAProjectIsRefusedAndNamesIt() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var project = project(ws, "Apollo");
        projectMember(project, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());

        var body = deleteMember(ws, ws.ownerToken(), mia.getId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        var problem = json.readTree(body);
        assertThat(problem.get("errorType").asText())
                .as("the variant a client can clear by retrying with the flag")
                .isEqualTo("STRANDED_PROJECTS");
        assertThat(problem.get("detail").asText())
                .as("a bare 'some projects would be stranded' is exactly what was rejected")
                .contains("Apollo").contains(project.getKey()).contains("without an administrator");
        assertThat(refs(problem)).containsExactly(project.getId());
        assertThat(problem.get("projects").get(0).get("name").asText()).isEqualTo("Apollo");
        assertThat(problem.get("projects").get(0).get("key").asText()).isEqualTo(project.getKey());

        // Nothing moved: the refusal is not a partial removal.
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), mia)).isTrue();
        assertThat(projectMemberRepository.existsByProjectAndUser(project, mia)).isTrue();
    }

    /**
     * <strong>Hole 2, which no built-in role can exercise.</strong> The guard used to ask
     * for members carrying the built-in Project admin role <em>id</em>, so an administrator
     * holding a custom role simply was not seen — and their removal passed a guard that was
     * not looking for them.
     *
     * <p>The control is the whole point of the pair: the same fixture with a custom role
     * that does <em>not</em> grant {@code project.member.manage} is removed without
     * complaint. What protects a project is the permission, not the custom-ness.
     */
    @Test
    void aSoleAdministratorHoldingACustomRoleIsProtectedToo() throws Exception {
        var ws = newWorkspace();
        var carla = addMember(ws, "MEMBER");
        var withManage = project(ws, "Custom Admin");
        projectMember(withManage, carla,
                customProjectRole(ws, "team-lead", Permission.PROJECT_MEMBER_MANAGE,
                        Permission.ISSUE_CREATE, Permission.ISSUE_EDIT));

        var problem = json.readTree(deleteMember(ws, ws.ownerToken(), carla.getId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        assertThat(refs(problem)).containsExactly(withManage.getId());

        // ---- control: a custom role WITHOUT project.member.manage is not an administrator
        var dave = addMember(ws, "MEMBER");
        var withoutManage = project(ws, "Ordinary");
        projectMember(withoutManage, dave,
                customProjectRole(ws, "senior-dev", Permission.ISSUE_CREATE,
                        Permission.ISSUE_EDIT, Permission.ISSUE_RANK));

        deleteMember(ws, ws.ownerToken(), dave.getId()).andExpect(status().isNoContent());
        assertThat(projectMemberRepository.existsByProjectAndUser(withoutManage, dave)).isFalse();
    }

    /**
     * One person can be the sole administrator of many projects at once — which is the
     * offboarding scenario that actually happens — and the response has to name
     * <strong>all</strong> of them, or the admin fixes three and is refused again for the
     * fourth. The sentence is capped (it is prose for a human); the {@code projects} array
     * is not.
     */
    @Test
    void aRemovalThatWouldStrandSeveralProjectsNamesEveryOne() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();
        var stranded = List.of(project(ws, "Alpha"), project(ws, "Bravo"),
                project(ws, "Charlie"), project(ws, "Delta"));
        stranded.forEach(p -> projectMember(p, mia, managerRole));
        // …plus one she administers alongside somebody else, which must NOT be listed.
        var shared = project(ws, "Echo");
        projectMember(shared, mia, managerRole);
        projectMember(shared, addMember(ws, "MEMBER"), managerRole);

        var problem = json.readTree(deleteMember(ws, ws.ownerToken(), mia.getId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());

        assertThat(refs(problem))
                .containsExactlyInAnyOrderElementsOf(stranded.stream().map(Project::getId).toList());
        assertThat(refs(problem)).doesNotContain(shared.getId());
        // The prose names a few and counts the rest rather than running to four lines.
        assertThat(problem.get("detail").asText()).contains("4 projects").contains("1 more");
        // Ordered by key rather than by whatever the locking read happened to return: the
        // SPA renders this list verbatim, so "fix these four" must not reshuffle per call.
        assertThat(keys(problem)).isSorted();
    }

    /**
     * <strong>The refusal is a state answer, not an oracle.</strong> It names projects, so
     * it must never be reachable by somebody who could not have removed the member anyway:
     * a plain workspace member is refused for who they are (403, naming the permission and
     * no project at all), and a non-member is answered 404 — the workspace does not exist
     * as far as they are concerned. The guard runs last, after the permission gate; this is
     * what keeps it there.
     */
    @Test
    void theRefusalIsNeverReachedByACallerWhoCouldNotRemoveAnybody() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var project = project(ws, "Apollo");
        projectMember(project, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());

        var bystander = addMember(ws, "MEMBER");
        var body = deleteMember(ws, login(bystander), mia.getId())
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("projects"))
                .as("a 403 must not carry the project list the 409 exists to publish")
                .isNull();
        assertThat(body).doesNotContain("Apollo").doesNotContain(project.getKey());

        // …and somebody who is not in this workspace at all is told nothing whatsoever.
        deleteMember(ws, login(user()), mia.getId()).andExpect(status().isNotFound());

        assertThat(projectMemberRepository.existsByProjectAndUser(project, mia)).isTrue();
    }

    /**
     * The guard counts, it does not blanket-ban: an ordinary offboarding still goes
     * through, including for someone who administers a project that has another
     * administrator, and their project rows are cleaned up exactly as before.
     */
    @Test
    void aRemovalThatStrandsNothingStillSucceeds() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var colleague = addMember(ws, "MEMBER");
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();

        var coManaged = project(ws, "Shared");
        projectMember(coManaged, mia, managerRole);
        projectMember(coManaged, colleague, managerRole);
        var contributesTo = project(ws, "Elsewhere");
        projectMember(contributesTo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MEMBER").id());
        // A project with no explicit members at all is normal and must not be dragged in.
        project(ws, "Nobody");

        deleteMember(ws, ws.ownerToken(), mia.getId()).andExpect(status().isNoContent());

        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), mia)).isFalse();
        assertThat(projectMemberRepository.existsByProjectAndUser(coManaged, mia)).isFalse();
        assertThat(projectMemberRepository.existsByProjectAndUser(contributesTo, mia)).isFalse();
        // The colleague keeps the project they still administer.
        assertThat(projectMemberRepository.existsByProjectAndUser(coManaged, colleague)).isTrue();
    }

    /**
     * <strong>The check is workspace-scoped, like every other half of this cascade.</strong>
     * A {@code User} is global, so a guard that forgot the workspace predicate would let one
     * tenant's project block — and, through {@code detail}, <em>name</em> — a removal issued
     * in another. That is the top bug class, and it is cheap to pin here because the removal
     * has to succeed for the right reason: the sole-administrator project belongs to a
     * workspace this removal is not about.
     */
    @Test
    void aProjectInAnotherWorkspaceNeitherBlocksNorLeaksIntoThisRemoval() throws Exception {
        var here = newWorkspace();
        var there = newWorkspace();
        var mia = addMember(here, "MEMBER");
        member(there.workspace(), mia, "MEMBER");
        var elsewhere = project(there, "Foreign");
        projectMember(elsewhere, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());

        deleteMember(here, here.ownerToken(), mia.getId()).andExpect(status().isNoContent());

        // The other tenant's membership — and its sole administrator — are untouched.
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(there.workspace(), mia)).isTrue();
        assertThat(projectMemberRepository.existsByProjectAndUser(elsewhere, mia)).isTrue();
    }

    /**
     * <strong>An administrator by inheritance counts only when somebody is actually
     * standing on the fallback</strong> — the refined form of the guard's first
     * approximation (security review), and both halves are asserted here because the
     * cheaper version of this rule is wrong in the dangerous direction.
     *
     * <p>When the project's default role grants {@code project.member.manage} and the
     * workspace is OPEN, every member <em>with no explicit row</em> holds it — so the
     * project genuinely cannot be stranded and the removal must go through (Apollo: the
     * workspace Owner has no row there). But "the default grants it" alone is not a proof:
     * in a project where every member has an explicit narrower row, nobody inherits
     * anything and waving the removal through would strand it exactly as before (Bravo).
     * The difference between the two is one existence check, and it is the whole
     * difference between a refinement and a hole.
     */
    @Test
    void anInheritedAdministratorCountsOnlyWhenSomebodyActuallyInheritsIt() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();
        var contributorRole = roleCatalog.builtIn(RoleScope.PROJECT, "MEMBER").id();

        // Apollo: Mia is the only explicit administrator, and the Owner — who has no row
        // here — inherits Project admin from the project default.
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, managerRole);
        apollo.setDefaultProjectRoleId(managerRole);
        projectRepository.save(apollo);

        deleteMember(ws, ws.ownerToken(), mia.getId())
                .andExpect(status().isNoContent());

        // Bravo: same default, but EVERY member of the workspace has an explicit row, so
        // the fallback role is one nobody is standing on.
        var ws2 = newWorkspace();
        var mia2 = addMember(ws2, "MEMBER");
        var bravo = project(ws2, "Bravo");
        projectMember(bravo, mia2, managerRole);
        projectMember(bravo, ws2.owner(), contributorRole);
        bravo.setDefaultProjectRoleId(managerRole);
        projectRepository.save(bravo);

        var problem = json.readTree(deleteMember(ws2, ws2.ownerToken(), mia2.getId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        assertThat(refs(problem))
                .as("nobody inherits the default here, so the project really would be stranded")
                .containsExactly(bravo.getId());
    }

    // ==================================================== the refusal must be satisfiable

    /**
     * <strong>The 409 has to be answerable by the person who received it</strong> (security
     * review H1) — otherwise it is not a guard but a lock-out, and a worse bug than the one
     * it replaced.
     *
     * <p>This is that scenario end to end, and the anchors are the point: the workspace
     * Owner is refused the removal, <em>and</em> is refused the remedy the refusal names —
     * adding an administrator to that project needs {@code project.member.manage} in it,
     * which no workspace-scoped role grants ({@code project.curate.all} covers four
     * permissions and not this one). Without the retry flag the only way out is SQL.
     */
    @Test
    void theOwnerCannotSatisfyTheRefusalByHandSoTheRetryFlagIsTheWayOut() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());

        deleteMember(ws, ws.ownerToken(), mia.getId()).andExpect(status().isConflict());

        // ANCHOR: the remedy the 409 asks for is not available to the caller it asks.
        var newcomer = addMember(ws, "MEMBER");
        mockMvc.perform(post("/api/workspaces/" + ws.workspace().getId()
                              + "/projects/" + apollo.getId() + "/members")
                        .header("Authorization", "Bearer " + ws.ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + newcomer.getId() + "\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isForbidden());

        // The retry: explicit consent, and the removal completes.
        deleteMember(ws, ws.ownerToken(), mia.getId(), true).andExpect(status().isOk());

        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), mia)).isFalse();
        assertThat(projectMemberRepository.existsByProjectAndUser(apollo, mia)).isFalse();
        // The actor is the project's administrator now — really, not on paper.
        var adopted = projectMemberRepository.findByProjectAndUser(apollo, ws.owner()).orElseThrow();
        assertThat(adopted.getRole().getId()).isEqualTo(BuiltInRoles.PROJECT_TEAM_LEAD);
        mockMvc.perform(post("/api/workspaces/" + ws.workspace().getId()
                              + "/projects/" + apollo.getId() + "/members")
                        .header("Authorization", "Bearer " + ws.ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + newcomer.getId() + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isCreated());
    }

    /**
     * Adoption covers <strong>every</strong> project of one refusal at once — offboarding
     * one person who ran four projects must not take four rounds — and it promotes an
     * existing membership in place rather than inserting a second row, which
     * {@code UNIQUE(project_id, user_id)} would refuse.
     */
    @Test
    void adoptionCoversEveryStrandedProjectAndPromotesAnExistingRowInPlace() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();
        var alpha = project(ws, "Alpha");
        var bravo = project(ws, "Bravo");
        projectMember(alpha, mia, managerRole);
        projectMember(bravo, mia, managerRole);
        // The actor is already an ordinary member of one of them.
        projectMember(bravo, ws.owner(), roleCatalog.builtIn(RoleScope.PROJECT, "MEMBER").id());

        deleteMember(ws, ws.ownerToken(), mia.getId(), true).andExpect(status().isOk());

        assertThat(projectMemberRepository.findByProjectAndUser(alpha, ws.owner()))
                .as("a project the actor had no row in gains one")
                .get().extracting(m -> m.getRole().getId()).isEqualTo(BuiltInRoles.PROJECT_TEAM_LEAD);
        var promoted = projectMemberRepository.findAllByProject(bravo);
        assertThat(promoted)
                .as("the actor's existing row is promoted, never duplicated")
                .hasSize(1);
        assertThat(promoted.get(0).getRole().getId()).isEqualTo(BuiltInRoles.PROJECT_TEAM_LEAD);
    }

    /**
     * <strong>The flag is consent, not an instruction.</strong> A removal that strands
     * nothing must not quietly make the caller an administrator of anything — a client that
     * sets it on every request (or a user who checked the box once) would otherwise
     * accumulate project admin rights across the workspace as a side effect of ordinary
     * offboarding.
     */
    @Test
    void theRetryFlagGrantsNothingWhenNothingWouldBeStranded() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var colleague = addMember(ws, "MEMBER");
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();
        var shared = project(ws, "Shared");
        projectMember(shared, mia, managerRole);
        projectMember(shared, colleague, managerRole);
        var untouched = project(ws, "Untouched");

        deleteMember(ws, ws.ownerToken(), mia.getId(), true).andExpect(status().isNoContent());

        assertThat(projectMemberRepository.findByProjectAndUser(shared, ws.owner()))
                .as("no stranding, so no adoption — the flag must not be a grant request")
                .isEmpty();
        assertThat(projectMemberRepository.findByProjectAndUser(untouched, ws.owner())).isEmpty();
    }

    /**
     * <strong>The flag is a bare "yes", and nothing a client sends can widen it.</strong>
     * This is the honesty claim the whole adoption path rests on: the set adopted is the one
     * {@code lockStrandedProjects} recomputes inside the transaction under its own
     * {@code FOR UPDATE}, never a list echoed back from the refusal the client saw, and the
     * request has no place to put one.
     *
     * <p>So the request here tries every shape a client might reach for — a project-naming
     * parameter under three plausible names — while the workspace holds three projects with
     * three different relationships to the removal: one genuinely about to be stranded, one
     * the departing member co-administers (so it is refused nothing and needs nothing), and
     * one she has never touched. Exactly the first is adopted.
     *
     * <p>Two mutations this is aimed at, both of which the suite otherwise waved through:
     * honouring a client-supplied project list, and dropping the "only what is about to be
     * stranded" bound so the flag adopts every live project in the workspace. Either turns
     * one consent checkbox into a self-service grant of project authority.
     */
    @Test
    void theFlagCarriesNoProjectListSoNoClientCanWidenWhatItAdopts() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();

        var apollo = project(ws, "Apollo");          // she is its only administrator
        projectMember(apollo, mia, managerRole);
        var shared = project(ws, "Shared");          // …and shares this one
        projectMember(shared, mia, managerRole);
        projectMember(shared, addMember(ws, "MEMBER"), managerRole);
        var bystander = project(ws, "Bystander");    // …and has nothing to do with this one

        var receipt = mockMvc.perform(delete("/api/workspaces/" + ws.workspace().getId()
                               + "/members/" + mia.getId()
                               + "?adoptStrandedProjects=true"
                               + "&adoptProjectIds=" + bystander.getId() + "," + shared.getId()
                               + "&projectIds=" + bystander.getId()
                               + "&projects=" + bystander.getId())
                        .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The receipt names what the SERVER adopted, and it is not what the client asked
        // for — the same claim as the rows below, asserted on the wire.
        var adoptedNames = new java.util.ArrayList<String>();
        for (var p : json.readTree(receipt).get("adoptedProjects")) {
            adoptedNames.add(p.get("name").asText());
        }
        assertThat(adoptedNames).containsExactly("Apollo");

        assertThat(projectMemberRepository.findByProjectAndUser(apollo, ws.owner()))
                .as("the one project the SERVER computed as stranded is the one adopted")
                .isPresent();
        assertThat(projectMemberRepository.findByProjectAndUser(shared, ws.owner()))
                .as("named by the client, but it keeps an administrator — so nothing to adopt")
                .isEmpty();
        assertThat(projectMemberRepository.findByProjectAndUser(bystander, ws.owner()))
                .as("a project the caller merely fancied must never be reachable by the flag")
                .isEmpty();
    }

    /**
     * <strong>Consent is answered against the database, not against the refusal.</strong>
     * The caller says "yes" to a 409 they were shown a moment ago; by the time they retry,
     * the project may already have been rescued the way that 409 asked. Then there is
     * nothing to adopt and the removal simply proceeds — a stale, replayed or forged "yes"
     * cannot bank a grant it would have earned earlier.
     *
     * <p>The rescue is performed by the departing member herself, deliberately: she is the
     * only person who <em>can</em> (that is the entire reason the flag exists), so this also
     * re-asserts that the ordinary remedy still works and still clears the refusal.
     */
    @Test
    void aRetryIsRecomputedSoAStrandingAlreadyFixedAdoptsNothing() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());

        var problem = json.readTree(deleteMember(ws, ws.ownerToken(), mia.getId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        assertThat(refs(problem)).containsExactly(apollo.getId());

        // …the remedy the refusal names, carried out by the only person who holds the
        // permission it needs.
        var successor = addMember(ws, "MEMBER");
        mockMvc.perform(post("/api/workspaces/" + ws.workspace().getId()
                             + "/projects/" + apollo.getId() + "/members")
                        .header("Authorization", "Bearer " + login(mia))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + successor.getId() + "\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated());

        deleteMember(ws, ws.ownerToken(), mia.getId(), true).andExpect(status().isNoContent());

        assertThat(projectMemberRepository.findByProjectAndUser(apollo, ws.owner()))
                .as("the stranding was already fixed, so the flag grants nothing at all")
                .isEmpty();
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, successor))
                .as("and the administrator she appointed is still there")
                .isPresent();
    }

    /**
     * <strong>A removal that fails leaves no adoption behind</strong> — the flag grants only
     * as part of a removal that actually happens, and the adoption is not a separate act
     * that can outlive the transaction it belongs to.
     *
     * <p>All four refusals the endpoint can raise are exercised with the flag set, and the
     * last two are the ones with teeth: the actor there is also a project's sole
     * administrator, so an adoption run too early would rewrite <em>their own</em> project
     * row (Project admin → Team lead), and a rollback that did not cover it would leave that
     * demotion in place. This is what makes the ordering ("adoption after the ceiling, self
     * and last-Owner guards") and the atomicity ("in the caller's transaction, never its
     * own") observable from outside at all.
     */
    @Test
    void aRefusedRemovalLeavesNoAdoptionBehind() throws Exception {
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();

        // ---- 403: a member who may not administer membership, asking for adoption anyway
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, managerRole);
        var bystander = addMember(ws, "MEMBER");

        deleteMember(ws, login(bystander), mia.getId(), true).andExpect(status().isForbidden());
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, bystander))
                .as("a 403 must not be a way to acquire a project")
                .isEmpty();

        // ---- 404: not a member of this workspace at all
        var stranger = user();
        deleteMember(ws, login(stranger), mia.getId(), true).andExpect(status().isNotFound());
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, stranger)).isEmpty();
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, mia))
                .as("and neither refusal removed anything either")
                .isPresent();

        // ---- 409: the workspace's last owner, who is also a project's sole administrator
        var solo = newWorkspace();
        var bravo = project(solo, "Bravo");
        projectMember(bravo, solo.owner(), managerRole);

        deleteMember(solo, solo.ownerToken(), solo.owner().getId(), true)
                .andExpect(status().isConflict());
        assertThat(projectMemberRepository.findByProjectAndUser(bravo, solo.owner()).orElseThrow()
                .getRole().getId())
                .as("a refused removal must not leave a ROLE CHANGE behind either")
                .isEqualTo(BuiltInRoles.PROJECT_MANAGER);

        // ---- 422: self-removal, with a second owner so the last-Owner guard is not what fires
        var pair = newWorkspace();
        addMember(pair, "OWNER");
        var charlie = project(pair, "Charlie");
        projectMember(charlie, pair.owner(), managerRole);

        deleteMember(pair, pair.ownerToken(), pair.owner().getId(), true)
                .andExpect(status().isUnprocessableContent());
        assertThat(projectMemberRepository.findByProjectAndUser(charlie, pair.owner()).orElseThrow()
                .getRole().getId())
                .isEqualTo(BuiltInRoles.PROJECT_MANAGER);
    }

    /**
     * <strong>Repeating the call grants nothing further.</strong> A client that retries — or
     * a user who double-clicks — sends the same flagged DELETE twice; the second is a 404
     * about a membership that is already gone, and it must not be a second helping of
     * project authority. The project created between the two calls is the probe: a repeat
     * that had stopped recomputing, or that adopted more than the stranded set, would show
     * up as a row there.
     */
    @Test
    void repeatingTheAdoptingRemovalGrantsNothingFurther() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        var bystander = project(ws, "Bystander");   // present all along, and never at risk

        deleteMember(ws, ws.ownerToken(), mia.getId(), true).andExpect(status().isOk());
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, ws.owner()).orElseThrow()
                .getRole().getId()).isEqualTo(BuiltInRoles.PROJECT_TEAM_LEAD);
        assertThat(projectMemberRepository.findByProjectAndUser(bystander, ws.owner()))
                .as("the first call adopted only what it had to")
                .isEmpty();

        var later = project(ws, "Later");

        deleteMember(ws, ws.ownerToken(), mia.getId(), true).andExpect(status().isNotFound());

        assertThat(projectMemberRepository.findAllByProject(apollo))
                .as("the second call added no row and removed none")
                .hasSize(1);
        assertThat(projectMemberRepository.findByProjectAndUser(later, ws.owner()))
                .as("a project that exists only after the adoption is not retro-adopted")
                .isEmpty();
        assertThat(projectMemberRepository.findByProjectAndUser(bystander, ws.owner()))
                .as("and the repeat did not widen what the first call took")
                .isEmpty();
    }

    // ==================================================== who counts as an administrator

    /**
     * <strong>A DISABLED account administers nothing</strong>, and the guard has to agree in
     * both directions — this is the one approximation that was not merely conservative.
     *
     * <ul>
     *   <li>Counting a disabled holder claimed an invariant the data does not support: the
     *       project below would have "an administrator" who cannot log in.</li>
     *   <li>And it made the refusal unclearable by the ordinary revocation step —
     *       deactivating the account did not release the 409.</li>
     * </ul>
     */
    @Test
    void aDeactivatedAccountIsNotAnAdministratorInEitherDirection() throws Exception {
        var ws = newWorkspace();
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();

        // (1) The sole administrator has been deactivated: removing them is not stranding
        // anything that was not already unmanaged, so it goes through.
        var retired = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, retired, managerRole);
        disable(retired);

        deleteMember(ws, ws.ownerToken(), retired.getId()).andExpect(status().isNoContent());

        // (2) The mirror, which is where counting them was outright unsafe: a project whose
        // only OTHER administrator is disabled has, in truth, one administrator — so
        // removing them must be refused.
        var mia = addMember(ws, "MEMBER");
        var dormant = addMember(ws, "MEMBER");
        var bravo = project(ws, "Bravo");
        projectMember(bravo, mia, managerRole);
        projectMember(bravo, dormant, managerRole);
        disable(dormant);

        var problem = json.readTree(deleteMember(ws, ws.ownerToken(), mia.getId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        assertThat(refs(problem))
                .as("a disabled co-administrator is not a co-administrator")
                .containsExactly(bravo.getId());
    }

    /**
     * An <strong>archived</strong> project is frozen — there is nothing left in it to
     * administer — so it must never be the reason somebody's offboarding is refused a year
     * later. (Recorded on {@code ProjectRepository}: the flip side is that such a project
     * cannot be unarchived through the API afterwards, because {@code project.archive} is
     * outside the curator bypass too. That is the audit's separate finding, not this
     * removal's to solve.)
     */
    @Test
    void anArchivedProjectNeverBlocksAnOffboarding() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var old = project(ws, "Retired");
        projectMember(old, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        old.setArchivedAt(java.time.Instant.now());
        projectRepository.save(old);

        deleteMember(ws, ws.ownerToken(), mia.getId()).andExpect(status().isNoContent());

        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), mia)).isFalse();
    }

    /**
     * <strong>What adoption grants is deliberately not Project admin.</strong> The first cut
     * handed over all 20 project permissions to satisfy an invariant about one; this pins
     * the narrow role instead — Contributor plus {@code project.member.manage} — and the
     * boundary in both directions.
     *
     * <p>The negative half is the load-bearing one: the adopter can appoint members, and
     * still cannot delete an issue in the project they took over. A test that only asserted
     * the role id would pass just as happily against a role that had quietly regrown.
     */
    @Test
    void adoptionGrantsTheRosterNotTheProject() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        var issue = anIssueIn(ws, apollo, ws.ownerToken());

        deleteMember(ws, ws.ownerToken(), mia.getId(), true).andExpect(status().isOk());

        var adopted = projectMemberRepository.findByProjectAndUser(apollo, ws.owner()).orElseThrow();
        assertThat(adopted.getRole().getId())
                .as("adoption must grant the narrow Team lead, never the built-in Project admin")
                .isEqualTo(BuiltInRoles.PROJECT_TEAM_LEAD);

        // The positive half: the roster is manageable again, which is what the 409 asked for.
        var newcomer = addMember(ws, "MEMBER");
        addProjectMember(ws, apollo, newcomer, "MEMBER").andExpect(status().isCreated());

        // The negative half: no authority over the project's WORK came with it. issue.delete
        // is the permission the escalation argument named, and Project admin is the only
        // built-in that holds it.
        mockMvc.perform(delete("/api/workspaces/" + ws.workspace().getId()
                               + "/projects/" + apollo.getId() + "/issues/" + issue)
                        .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isForbidden());
        // …nor may they promote THEMSELVES. The §4 ceiling escape lets any holder of
        // project.member.manage hand out the built-in Project admin, which is what makes an
        // under-administered project repairable at all — but only to somebody ELSE, and that
        // constraint is what keeps HD-136's safety case intact. Team lead is handed out here
        // on the promise that nothing it grants can destroy anything; a self-grant would turn
        // every adoption into a two-call route to issue.delete, which is the exact escalation
        // adoption was argued down from.
        mockMvc.perform(patch("/api/workspaces/" + ws.workspace().getId()
                              + "/projects/" + apollo.getId() + "/members/" + ws.owner().getId())
                        .header("Authorization", "Bearer " + ws.ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"" + BuiltInRoles.PROJECT_MANAGER + "\"}"))
                .andExpect(status().isForbidden());

        // …and issue.delete is still refused afterwards, which is the claim that matters.
        mockMvc.perform(delete("/api/workspaces/" + ws.workspace().getId()
                               + "/projects/" + apollo.getId() + "/issues/" + issue)
                        .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isForbidden());
    }

    /**
     * <strong>The adopter must not end up narrower than they started.</strong> In an OPEN
     * workspace every member inherits Contributor in every project, so a membership-only
     * adoption row would silently strip the adopter of issue rights in the project they just
     * rescued — and the grant ceiling would then refuse to let them delete that row, since
     * the role it would leave them on (Contributor) is one they no longer cover. Permanent,
     * and inflicted by a rescue.
     *
     * <p>So the assertion is behavioural, not structural: after adopting, the actor can
     * still do the everyday work they could do before.
     */
    @Test
    void adoptionNeverNarrowsWhatTheAdopterCouldAlreadyDoInThatProject() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());

        // ANCHOR: with no row at all, the Owner inherits Contributor and can file work.
        anIssueIn(ws, apollo, ws.ownerToken());

        deleteMember(ws, ws.ownerToken(), mia.getId(), true).andExpect(status().isOk());

        // Still can, now on an explicit row.
        anIssueIn(ws, apollo, ws.ownerToken());

        // …and the row is not a one-way door. Both halves of the exit are ceiling
        // questions a membership-only role would fail: appointing a successor (they must
        // cover what they hand out) and then dropping their own row (they must cover the
        // role the removal LEAVES them on — the inherited Contributor).
        var successor = addMember(ws, "MEMBER");
        addProjectMember(ws, apollo, successor, "TEAM_LEAD").andExpect(status().isCreated());
        mockMvc.perform(delete("/api/workspaces/" + ws.workspace().getId() + "/projects/"
                               + apollo.getId() + "/members/" + ws.owner().getId())
                        .header("Authorization", "Bearer " + ws.ownerToken()))
                .andExpect(status().isNoContent());
    }

    // ==================================================== the lock is a lock

    /**
     * <strong>{@code MANDATORY}, not {@code REQUIRED}</strong>, on both of the guard's
     * locking reads. {@code LockedProjectAdmins} / {@code StrandedProjects} are nominal
     * types whose whole promise is "you can only get one from a method that took the lock";
     * under {@code REQUIRED} a caller with no surrounding transaction would silently get one
     * that commits on return, releasing every {@code FOR UPDATE} before the guard reading it
     * runs — the unlocked read this shape exists to delete, wearing the type that says it is
     * not. Nothing else in the suite would notice, which is why this is here.
     */
    @Test
    void theGuardRefusesToLockOutsideACallersTransaction() throws Exception {
        var ws = newWorkspace();
        var apollo = project(ws, "Apollo");

        assertThatThrownBy(() -> guard.lockStrandedProjects(ws.workspace(), ws.owner().getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> guard.lockAdmins(apollo))
                .isInstanceOf(IllegalTransactionStateException.class);

        // …and inside one, both answer normally — otherwise the two assertions above would
        // also pass against a method that always threw.
        txTemplate.executeWithoutResult(s -> {
            assertThat(guard.lockStrandedProjects(ws.workspace(), ws.owner().getId()).isEmpty()).isTrue();
            assertThat(guard.lockAdmins(projectRepository.findById(apollo.getId()).orElseThrow())
                    .userIds()).isEmpty();
        });
    }

    /**
     * The workspace half of the same rule, which needs both belts: {@code lockOwners} is
     * called by two methods <em>of its own class</em>, and a self-invocation does not pass
     * through the proxy — so the annotation cannot fire there and an {@code Assert.state}
     * carries it. Asserted through the raw target object, which is exactly what a
     * self-invocation reaches.
     */
    @Test
    void theOwnerLockRefusesToRunOutsideATransactionByEitherRoute() throws Exception {
        var ws = newWorkspace();

        // Through the proxy: the annotation refuses.
        assertThatThrownBy(() -> memberService.lockOwners(ws.workspace()))
                .isInstanceOf(IllegalTransactionStateException.class);
        // Past the proxy, as a self-invocation would: the assert refuses.
        var raw = AopTestUtils.<WorkspaceMemberService>getTargetObject(memberService);
        assertThatThrownBy(() -> raw.lockOwners(ws.workspace()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");

        txTemplate.executeWithoutResult(s ->
                assertThat(memberService.lockOwners(ws.workspace()).size()).isEqualTo(1));
    }

    /**
     * <strong>The adoption belongs to the removal's transaction, and cannot be given one of
     * its own.</strong> {@code MANDATORY} on the two locking reads is already pinned above;
     * this is the third method that needs it, and it needs it for a different reason.
     *
     * <p>The locks care about {@code MANDATORY} because a transaction that commits on return
     * releases them too early. {@code adoptAll} cares because it is the one part of a removal
     * that <em>grants</em> rather than revokes: under {@code REQUIRES_NEW} it would commit on
     * its own, so every later refusal — and every rollback — would leave the caller holding
     * project authority for a removal that never happened. Nothing observable through HTTP
     * distinguishes the two while the adoption runs last and nothing after it can fail, which
     * is exactly why this assertion is structural.
     *
     * <p>The set passed in is deliberately non-empty, so the test cannot be satisfied by an
     * early return that never reaches the transactional work.
     */
    @Test
    void adoptionRefusesToRunOutsideTheRemovalsTransaction() throws Exception {
        var ws = newWorkspace();
        var apollo = project(ws, "Apollo");
        var mia = addMember(ws, "MEMBER");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        // Obtained the only way anything can obtain one since round 3: from the guard, under
        // its lock. StrandedProjects used to be a public record, so this fixture could forge
        // one from thin air — which was also the hole under the whole H1 argument, since
        // adoptAll would then grant from a set a caller had chosen.
        var stranded = txTemplate.execute(s -> guard.lockStrandedProjects(ws.workspace(), mia.getId()));
        assertThat(stranded.isEmpty())
                .as("the fixture must hand adoptAll a genuinely non-empty set, or an early "
                    + "return would satisfy the assertions below")
                .isFalse();

        assertThatThrownBy(() -> guard.adoptAll(ws.owner(), ws.workspace(), stranded))
                .as("a grant that can commit by itself outlives the removal it was part of")
                .isInstanceOf(IllegalTransactionStateException.class);

        // …and nothing was granted on the way out — otherwise the assertion above could
        // pass against a method that threw only after writing.
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, ws.owner())).isEmpty();

        // Inside a transaction it does the work, so the refusal above is about propagation
        // and not about the method being broken.
        txTemplate.executeWithoutResult(s -> guard.adoptAll(ws.owner(), ws.workspace(), stranded));
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, ws.owner()))
                .get().extracting(m -> m.getRole().getId()).isEqualTo(BuiltInRoles.PROJECT_TEAM_LEAD);
    }

    /**
     * <strong>An adoption is announced, not just logged.</strong> The flag is accepted on a
     * first attempt with no prior 409, so a control wired to always send it — or a script
     * that retries on any conflict — could hand its user project authority with nothing on
     * the wire ever saying so; the only trace was a server log the actor cannot read. So a
     * removal that adopted something answers <strong>200</strong> with the list, and an
     * ordinary removal keeps its 204.
     *
     * <p>The body is the server's list, not an echo: the client cannot name projects, and
     * the one here was never mentioned in the request.
     */
    @Test
    void anAdoptingRemovalAnswers200NamingWhatItGranted() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var managerRole = roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id();
        var alpha = project(ws, "Alpha");
        var bravo = project(ws, "Bravo");
        projectMember(alpha, mia, managerRole);
        projectMember(bravo, mia, managerRole);

        var body = json.readTree(deleteMember(ws, ws.ownerToken(), mia.getId(), true)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        var adopted = new java.util.ArrayList<UUID>();
        for (var p : body.get("adoptedProjects")) {
            adopted.add(UUID.fromString(p.get("id").asText()));
            assertThat(p.get("key").asText()).isNotBlank();
            assertThat(p.get("name").asText()).isNotBlank();
        }
        assertThat(adopted).containsExactlyInAnyOrder(alpha.getId(), bravo.getId());

        // …and the quiet case stays quiet: nothing adopted, nothing to announce.
        var other = addMember(ws, "MEMBER");
        deleteMember(ws, ws.ownerToken(), other.getId(), true).andExpect(status().isNoContent());
    }

    /**
     * <strong>Adoption must not demote the adopter either.</strong> The overwrite assumed the
     * actor's existing row was always narrower than Team lead, which holds for every built-in
     * — but not for a custom role carrying something Team lead lacks and no member
     * management. A "QA lead" with {@code issue.delete} would be silently downgraded by a
     * rescue, and §11.2 would then refuse to give it back, because after the removal nobody
     * left holds {@code issue.delete} to grant it.
     *
     * <p>Skipping that project and removing anyway would strand it, which is the outcome this
     * whole feature exists to prevent — so the removal is refused, in the same 409 shape,
     * with a sentence naming the real obstacle instead of offering a retry that would fail
     * identically. The person told is the one who can act on it.
     */
    @Test
    void adoptionRefusesRatherThanDemoteTheAdoptersOwnWiderRole() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        // The actor already works here on a role holding something Team lead does not, and
        // no member management — so this project really is stranded by Mia's removal.
        var qaLead = customProjectRole(ws, "qa-lead", Permission.ISSUE_DELETE,
                Permission.ISSUE_CREATE, Permission.ISSUE_EDIT);
        projectMember(apollo, ws.owner(), qaLead);

        var problem = json.readTree(deleteMember(ws, ws.ownerToken(), mia.getId(), true)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
        assertThat(refs(problem)).containsExactly(apollo.getId());
        assertThat(problem.get("errorType").asText())
                .as("the variant on which the adopt retry fails identically — a client must be "
                    + "able to tell without parsing English")
                .isEqualTo("ADOPTION_BLOCKED");
        assertThat(problem.get("detail").asText())
                .as("the sentence must name the obstacle, not re-offer the retry that just failed")
                .contains("Apollo").doesNotContain("adoptStrandedProjects");

        // Nothing was written: not the removal, and above all not a replacement role.
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), mia)).isTrue();
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, ws.owner()).orElseThrow()
                .getRole().getId())
                .as("the adopter's own wider role must survive a refused adoption")
                .isEqualTo(qaLead);
    }

    /**
     * <strong>A corrupt role on the adopter's own row blocks the adoption; it does not 404
     * the removal</strong> (HD-127 review round 3).
     *
     * <p>{@code adoptAll} reads the actor's existing {@code project_members.role_id} through
     * {@code requireRole}, which answers a wrong-scope or foreign id with 404. Refusing is
     * right — a degrade would read as "no permissions", i.e. "never a demotion", and the
     * adoption would silently overwrite whatever that row actually granted. The <em>code</em>
     * was not: on {@code DELETE /workspaces/{ws}/members/{u}} that 404 reaches a caller who
     * has just resolved the workspace and passed {@code workspace.member.manage}, so it is
     * indistinguishable from the tenancy 404 and tells them nothing they can act on — the
     * exact shape H1 argued against.
     *
     * <p>It answers 409 naming the project instead, which is a sentence an operator can act
     * on. Nobody is blinded — {@code requireRole} has already logged its ERROR and
     * incremented {@code hamstrack.role.scope_violation} on the way past.
     *
     * <p><strong>Its own {@code errorType}, and its own copy</strong> (round 4). Round 3
     * folded it into {@code ADOPTION_BLOCKED}, which shares a status and a payload but also
     * shared a <em>sentence</em> — and that sentence is false here in both halves: it asserts
     * the reader's own role is wider than the adoption role (never tested on this branch, and
     * unknowable, since the row is precisely what could not be read), and it prescribes
     * asking the departing member to appoint a successor, which cannot clear a refusal that
     * does not depend on who administers the project. The retry blocks on the same unusable
     * {@code role_id} every time. So the two are separate codes with different readers: the
     * blocked one is cleared by a named colleague, this one only by an operator repairing
     * stored data. Both halves of that are asserted below, because the copy is the defect
     * being fixed — this is the third refusal in this class caught naming a cause or a remedy
     * it could not deliver.
     */
    @Test
    void aCorruptRoleOnTheAdoptersOwnRowBlocksTheAdoptionRatherThan404ingTheRemoval()
            throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        // The adopter already works here, on a row whose role_id is not usable as a PROJECT
        // role of this workspace — the shape a bad migration or a hand-written UPDATE leaves.
        projectMember(apollo, ws.owner(), customWorkspaceRole(ws, "corrupt-on-project-row"));

        var problem = json.readTree(deleteMember(ws, ws.ownerToken(), mia.getId(), true)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());

        assertThat(problem.get("errorType").asText())
                .as("a bare 404 here is unactionable and indistinguishable from the tenancy "
                    + "404 the same endpoint gives a non-member; and it is NOT the demotion "
                    + "refusal, whose copy is false on this branch")
                .isEqualTo("ADOPTION_ROLE_UNREADABLE");
        assertThat(refs(problem)).containsExactly(apollo.getId());
        var detail = problem.get("detail").asText();
        assertThat(detail)
                .as("it must not assert a cause it did not test — the actor's own role is not "
                    + "the obstacle here, and its contents are exactly what could not be read")
                .doesNotContain("holds more than")
                .as("nor prescribe a handover, which cannot clear a refusal that does not "
                    + "depend on who administers the project")
                .doesNotContain("appoint")
                .as("and not offer the retry either: it blocks on the same unusable row")
                .doesNotContain("adoptStrandedProjects");
        assertThat(detail)
                .as("the reader is told who can actually fix it")
                .contains("system administrator");
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(ws.workspace(), mia))
                .as("a refused removal is not a partial one")
                .isTrue();
    }

    /**
     * <strong>The two 409s must be tellable apart by a machine, and the second must name a
     * remedy its reader can actually perform</strong> (review round 4).
     *
     * <p>Both refusals carry the same status and the same {@code projects} extension, and
     * they demand <em>opposite</em> client behaviour: one is cleared by retrying with
     * {@code adoptStrandedProjects=true}, the other fails identically on that retry, so
     * offering the button is worse than rendering nothing. Until this discriminator existed
     * the only difference was prose, and "do not branch on {@code detail}" was the documented
     * advice — which leaves the screen S6 builds unable to render either case correctly.
     *
     * <p>The second half is the remedy itself. {@code ADOPTION_BLOCKED} used to end "give
     * that project another administrator by hand instead" — which needs
     * {@code project.member.manage} <em>in</em> that project, i.e. exactly what the reader
     * does not have (if they had it the project had two administrators and was never
     * stranded). It named the one action guaranteed to 403: the lock-out shape this whole
     * feature exists to delete, reintroduced inside it. The wording that replaced it named a
     * second rescuer who also does not exist — "someone who already administers that project,
     * other than the person being removed" is by construction the empty set here, because the
     * branch is reachable only when the departing member is that project's <strong>single
     * </strong> ACTIVE holder of {@code project.member.manage}. Both routes the sentence names
     * now do exist: the departing member is still ACTIVE and still administers the project, so
     * they can appoint a successor while they still can; and another workspace administrator
     * who holds no role in that project (or a narrower one) never reaches this refusal at all,
     * because their own role is not wider than the adoption role — their adoption succeeds.
     *
     * <p>That claim is pinned as a <em>property</em> rather than as prose by
     * {@link #bothWaysOutTheBlockedRefusalNamesAreExecutable()}, which executes each named
     * route against the API and requires it to clear the removal. What stays asserted on the
     * sentence here is only the part no rewording may bring back: a remedy needing
     * {@code project.member.manage} inside the stranded project, or a rescuer drawn from the
     * set this branch has just proved empty.
     */
    @Test
    void theTwoRefusalsCarryDifferentCodesAndTheBlockedOneNamesAnActionableWayOut() throws Exception {
        var ws = newWorkspace();
        var mia = addMember(ws, "MEMBER");
        var apollo = project(ws, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());

        var plain = json.readTree(deleteMember(ws, ws.ownerToken(), mia.getId())
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());

        // Now make the very same removal un-adoptable: the actor works in Apollo on a role
        // holding something Team lead does not, so adopting would demote them.
        projectMember(apollo, ws.owner(),
                customProjectRole(ws, "qa-lead", Permission.ISSUE_DELETE, Permission.ISSUE_CREATE));
        var blocked = json.readTree(deleteMember(ws, ws.ownerToken(), mia.getId(), true)
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());

        assertThat(plain.get("errorType").asText()).isEqualTo("STRANDED_PROJECTS");
        assertThat(blocked.get("errorType").asText()).isEqualTo("ADOPTION_BLOCKED");
        assertThat(plain.get("errorType").asText())
                .as("same status, same extension, opposite remedy — the code IS the difference")
                .isNotEqualTo(blocked.get("errorType").asText());
        // The half that must NOT change: a client that only renders "these are in the way"
        // stays correct on both.
        assertThat(refs(plain)).containsExactly(apollo.getId());
        assertThat(refs(blocked)).containsExactly(apollo.getId());

        assertThat(plain.get("detail").asText())
                .as("the retryable one names its retry")
                .contains("adoptStrandedProjects=true");
        assertThat(blocked.get("detail").asText())
                .as("a remedy needing project.member.manage inside the stranded project is the "
                    + "one thing this reader provably cannot do — it is why they are being "
                    + "refused — and a rescuer 'who already administers' it is the set this "
                    + "branch has just proved empty; the routes that DO work are executed for "
                    + "real in bothWaysOutTheBlockedRefusalNamesAreExecutable()")
                .doesNotContain("by hand")
                .doesNotContainIgnoringCase("already administers")
                .doesNotContainIgnoringCase("who administers")
                .doesNotContainIgnoringCase("administers that project");
    }

    /**
     * <strong>The property behind the sentence</strong> (review round 7): every way out
     * {@code ADOPTION_BLOCKED} names is executed here and has to actually clear the removal.
     *
     * <p>Prose assertions on a remedy have failed this feature twice — first the version
     * that told the reader to do the one thing that 403s, then the version that pointed at
     * "someone who already administers that project", which on this branch is the set the
     * guard has just proved empty. Both read fine. The only assertion that cannot be talked
     * past is the removal succeeding, so that is what these two halves assert; the wording
     * may be rewritten freely, and this test still says whether the advice is true.
     *
     * <p>Route 1 — the departing member is still ACTIVE and still administers the project,
     * so she can appoint a successor herself, after which the removal is ordinary (204).
     * Route 2 — another workspace administrator with no row in that project is not narrowed
     * by adopting, so their adoption is not blocked at all (200 + the adopted project).
     */
    @Test
    void bothWaysOutTheBlockedRefusalNamesAreExecutable() throws Exception {
        // ---- Route 1: the member being removed appoints another administrator.
        var a = newWorkspace();
        var mia = addMember(a, "MEMBER");
        var apollo = project(a, "Apollo");
        projectMember(apollo, mia, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        var qaLead = customProjectRole(a, "qa-lead", Permission.ISSUE_DELETE, Permission.ISSUE_CREATE);
        projectMember(apollo, a.owner(), qaLead);

        // Precondition: this really is the refusal whose remedy we are testing.
        assertThat(json.readTree(deleteMember(a, a.ownerToken(), mia.getId(), true)
                        .andExpect(status().isConflict())
                        .andReturn().getResponse().getContentAsString())
                .get("errorType").asText())
                .as("the route being verified is only advertised by ADOPTION_BLOCKED")
                .isEqualTo("ADOPTION_BLOCKED");

        var nina = addMember(a, "MEMBER");
        mockMvc.perform(post("/api/workspaces/" + a.workspace().getId()
                             + "/projects/" + apollo.getId() + "/members")
                        .header("Authorization", "Bearer " + login(mia))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + nina.getId() + "\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated());

        deleteMember(a, a.ownerToken(), mia.getId())
                .andExpect(status().isNoContent());
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(a.workspace(), mia))
                .as("the advice was 'appoint a successor first' — after doing exactly that, "
                    + "the removal it was blocking must go through")
                .isFalse();
        assertThat(projectMemberRepository.findByProjectAndUser(apollo, a.owner()).orElseThrow()
                .getRole().getId())
                .as("and it goes through as an ORDINARY removal: nothing was adopted, so the "
                    + "actor's own wider role is untouched")
                .isEqualTo(qaLead);

        // ---- Route 2: a different workspace administrator, with no role in that project.
        var b = newWorkspace();
        var mo = addMember(b, "MEMBER");
        var atlas = project(b, "Atlas");
        projectMember(atlas, mo, roleCatalog.builtIn(RoleScope.PROJECT, "MANAGER").id());
        projectMember(atlas, b.owner(),
                customProjectRole(b, "qa-lead", Permission.ISSUE_DELETE, Permission.ISSUE_CREATE));

        assertThat(json.readTree(deleteMember(b, b.ownerToken(), mo.getId(), true)
                        .andExpect(status().isConflict())
                        .andReturn().getResponse().getContentAsString())
                .get("errorType").asText())
                .isEqualTo("ADOPTION_BLOCKED");

        var zoe = addMember(b, "ADMIN");
        var adopted = json.readTree(deleteMember(b, login(zoe), mo.getId(), true)
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        var adoptedIds = new java.util.ArrayList<UUID>();
        for (var pr : adopted.get("adoptedProjects")) adoptedIds.add(UUID.fromString(pr.get("id").asText()));
        assertThat(adoptedIds)
                .as("the colleague the sentence names is one who does NOT already work there — "
                    + "nothing of theirs can be narrowed, so the adoption simply succeeds")
                .containsExactly(atlas.getId());
        assertThat(workspaceMemberRepository.existsByWorkspaceAndUser(b.workspace(), mo)).isFalse();
        assertThat(projectMemberRepository.findByProjectAndUser(atlas, zoe))
                .as("and the project ends the removal with an administrator, which is the "
                    + "whole point of the refusal that sent them here")
                .isPresent();
    }

    // ==================================================== helpers

    /**
     * File an issue in {@code project} as whoever holds {@code token}, returning its number.
     * The type and status ids come from the project's own config endpoint — the same route
     * the SPA takes, and the only way to learn them without hard-coding seed data.
     */
    private long anIssueIn(Ws ws, Project project, String token) throws Exception {
        var base = "/api/workspaces/" + ws.workspace().getId() + "/projects/" + project.getId();
        var config = json.readTree(mockMvc.perform(get(base + "/config")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var typeId = config.get("issueTypes").get(0).get("id").asText();
        String statusId = null;
        for (var st : config.get("statuses")) {
            if (st.get("category").asText().equals("TODO")) statusId = st.get("id").asText();
        }
        var body = mockMvc.perform(post(base + "/issues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Work\",\"typeId\":\"" + typeId + "\",\"statusId\":\""
                                 + statusId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("number").asLong();
    }

    /** {@code POST /projects/{p}/members} as the workspace owner. */
    private ResultActions addProjectMember(Ws ws, Project project, User user, String role)
            throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ws.workspace().getId()
                                    + "/projects/" + project.getId() + "/members")
                .header("Authorization", "Bearer " + ws.ownerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + user.getId() + "\",\"role\":\"" + role + "\"}"));
    }

    /** The project ids named by a 409 body's {@code projects} extension. */
    private List<UUID> refs(JsonNode problem) {
        var out = new java.util.ArrayList<UUID>();
        for (var p : problem.get("projects")) out.add(UUID.fromString(p.get("id").asText()));
        return out;
    }

    /** The project keys named by a 409 body's {@code projects} extension, in order. */
    private List<String> keys(JsonNode problem) {
        var out = new java.util.ArrayList<String>();
        for (var p : problem.get("projects")) out.add(p.get("key").asText());
        return out;
    }

    private ResultActions deleteMember(Ws ws, String token, UUID userId) throws Exception {
        return deleteMember(ws, token, userId, false);
    }

    private ResultActions deleteMember(Ws ws, String token, UUID userId, boolean adopt) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/" + ws.workspace().getId() + "/members/" + userId
                                      + (adopt ? "?adoptStrandedProjects=true" : ""))
                .header("Authorization", "Bearer " + token));
    }

    /** Deactivate an account the way the admin console does — the row stays, access goes. */
    private void disable(User u) {
        u.setStatus(UserStatus.DISABLED);
        userRepository.save(u);
    }

    // ==================================================== bootstrap

    private record Ws(Workspace workspace, User owner, String ownerToken) {}

    private Ws newWorkspace() throws Exception {
        var owner = user();
        var ws = workspace(owner);
        member(ws, owner, "OWNER");
        return new Ws(ws, owner, login(owner));
    }

    private User addMember(Ws ws, String role) {
        var u = user();
        member(ws.workspace(), u, role);
        return u;
    }

    private User user() {
        var u = new User();
        u.setEmail(("u-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 8)
                    + "@example.com").toLowerCase());
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

    private Project project(Ws ws, String name) {
        var p = new Project();
        p.setWorkspace(ws.workspace());
        p.setName(name);
        p.setKey("P" + (Math.abs(UUID.randomUUID().hashCode()) % 1000000));
        p.setCreatedBy(ws.owner());
        return projectRepository.save(p);
    }

    private void projectMember(Project project, User user, UUID roleId) {
        var m = new ProjectMember();
        m.setProject(project);
        m.setUser(user);
        m.setRole(roleCatalog.reference(roleId));
        projectMemberRepository.save(m);
    }

    /**
     * A workspace-owned <strong>WORKSPACE</strong>-scoped role — the fixture for a corrupt
     * {@code project_members.role_id}, since nothing in the API can write one there.
     */
    private UUID customWorkspaceRole(Ws ws, String key) {
        return txTemplate.execute(status -> {
            var role = new Role();
            role.setWorkspaceId(ws.workspace().getId());
            role.setScope(RoleScope.WORKSPACE);
            role.setKey(key + "-" + UUID.randomUUID().toString().substring(0, 8));
            role.setName(key);
            role.setBuiltIn(false);
            entityManager.persist(role);
            entityManager.flush();
            return role.getId();
        });
    }

    /** A workspace-owned PROJECT role granting exactly {@code permissions}. */
    private UUID customProjectRole(Ws ws, String key, Permission... permissions) {
        return txTemplate.execute(status -> {
            var role = new Role();
            role.setWorkspaceId(ws.workspace().getId());
            role.setScope(RoleScope.PROJECT);
            role.setKey(key + "-" + UUID.randomUUID().toString().substring(0, 8));
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

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
