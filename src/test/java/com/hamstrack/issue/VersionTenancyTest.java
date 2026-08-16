package com.hamstrack.issue;

import com.hamstrack.issue.entity.IssueVersionLink;
import com.hamstrack.issue.entity.VersionLinkType;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.IssueVersionLinkRepository;
import com.hamstrack.issue.repository.VersionRepository;
import com.hamstrack.project.entity.ProjectRole;
import com.hamstrack.workspace.entity.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-32 isolation + authorization — the project's top bug class (proposal §3.1, §3.3,
 * §6.6 "Cross-tenant"). The third and last of the epic's tenancy suites, after
 * {@code LabelTenancyTest} (workspace-scoped) and {@link ComponentTenancyTest}
 * (project-scoped).
 *
 * <p>Versions are <strong>project</strong>-owned, so there are two boundaries to
 * defend and they must be indistinguishable to a caller: another <em>workspace</em>
 * (the tenant boundary) and another <em>project of the same workspace</em> (the
 * ownership boundary). A foreign id is a 404 on every id endpoint, a 422 "Unknown
 * version" wherever it appears as a field value inside a request the caller is
 * entitled to make, and an empty result when used as a filter — never a 403, which
 * would confirm existence.
 *
 * <p>What is more than smoke here:
 * <ul>
 *   <li><strong>the two destructive re-point paths</strong> —
 *       {@code release?moveUnresolvedToVersionId} and {@code DELETE ?remapToId=} —
 *       are asserted on their <em>effects</em>, not just their status code. A 422
 *       that had already re-pointed half the links would still look correct to a
 *       status-only assertion;</li>
 *   <li><strong>the DB-level guard (§3.8)</strong>: {@code issue_versions} carries a
 *       denormalized {@code workspace_id} and COMPOSITE FKs to <em>both</em> parents,
 *       so a cross-tenant link row is not "rejected" — it is unrepresentable. Written
 *       straight through the repository, bypassing every service check, in BOTH stamp
 *       directions, with a same-tenant control that succeeds;</li>
 *   <li><strong>all three cascades</strong> asserted at the row level, because
 *       {@code issue_versions} declares its {@code ON DELETE CASCADE} on the composite
 *       FKs rather than on the single-column ones — a shape a migration edit could
 *       silently drop while every service-level test kept passing.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class VersionTenancyTest extends VersionTestBase {

    @Autowired IssueRepository issueRepository;
    @Autowired VersionRepository versionRepository;
    @Autowired IssueVersionLinkRepository linkRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // ==================================================== (a) foreign id → 404

    @Test
    void aVersionIdFromAnotherProjectOfTheSameWorkspaceIs404OnEveryIdEndpoint() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var foreign = createVersion(sibling, "sibling-only");
        var mine = createVersion(ctx, "mine");

        assert404OnEveryIdEndpoint(ctx, foreign);
        // The sibling's version survived every attempt, unreleased and unarchived.
        var survivor = listVersions(sibling, sibling.token(), "?includeArchived=true").get(0);
        assert survivor.get("name").asText().equals("sibling-only") : survivor;
        assert !survivor.get("released").asBoolean() && !survivor.get("archived").asBoolean() : survivor;
        // …and this project's own version still resolves, so the 404s above are about
        // scoping, not about a broken endpoint.
        getVersion(ctx, ctx.token(), mine).andExpect(status().isOk());
    }

    @Test
    void aVersionIdFromAnotherWorkspaceIs404OnEveryIdEndpointAndSoIsAGhostId() throws Exception {
        var a = newProject();
        var b = newProject();
        var foreign = createVersion(b, "b-only");

        assert404OnEveryIdEndpoint(a, foreign);
        // A never-existed id is indistinguishable from B's — no existence oracle.
        assert404OnEveryIdEndpoint(a, UUID.randomUUID());
        assert versionNames(listVersions(b, b.token(), null)).equals(List.of("b-only"));
    }

    private void assert404OnEveryIdEndpoint(Ctx ctx, UUID versionId) throws Exception {
        getVersion(ctx, ctx.token(), versionId).andExpect(status().isNotFound());
        patchVersion(ctx, ctx.token(), versionId, "{\"name\":\"hijacked\"}")
                .andExpect(status().isNotFound());
        releaseVersion(ctx, ctx.token(), versionId).andExpect(status().isNotFound());
        unreleaseVersion(ctx, ctx.token(), versionId).andExpect(status().isNotFound());
        archiveVersion(ctx, ctx.token(), versionId).andExpect(status().isNotFound());
        unarchiveVersion(ctx, ctx.token(), versionId).andExpect(status().isNotFound());
        deleteVersion(ctx, ctx.token(), versionId, "?force=true").andExpect(status().isNotFound());
        versionUsage(ctx, ctx.token(), versionId).andExpect(status().isNotFound());
    }

    @Test
    void aVersionListNeverContainsAForeignRow() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var other = newProject();
        createVersion(ctx, "alpha");
        createVersion(sibling, "beta");
        createVersion(other, "gamma");

        var query = "?includeArchived=true&includeReleased=true";
        assert versionNames(listVersions(ctx, ctx.token(), query)).equals(List.of("alpha"));
        assert versionNames(listVersions(sibling, sibling.token(), query)).equals(List.of("beta"));
        assert versionNames(listVersions(other, other.token(), query)).equals(List.of("gamma"));
    }

    // ==================================================== (b) the two re-point paths

    /**
     * {@code moveUnresolvedToVersionId} is the destructive half of a release. A foreign
     * target must abort the WHOLE call before anything is written — asserted on the
     * links afterwards, not just on the status code: a 422 raised after a partial
     * re-point would satisfy a status-only assertion and still have moved the work.
     */
    @Test
    void releaseWithAForeignMoveTargetIs422AndRePointsNothing() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var other = newProject();
        var shipping = createVersion(ctx, "2.4.0");
        var open = createIssue(ctx, "still open", fixVersionIdsJson(shipping));

        for (var foreign : List.of(createVersion(sibling, "sibling-next"),
                createVersion(other, "tenant-b-next"), UUID.randomUUID())) {
            releaseVersion(ctx, ctx.token(), shipping,
                    "{\"moveUnresolvedToVersionId\":\"" + foreign + "\"}")
                    .andExpect(status().isUnprocessableContent())
                    // "Unknown version" — the same answer a never-existed id gets, so the
                    // response can't be used to probe another project's release plan.
                    .andExpect(jsonPath("$.detail", containsString("Unknown version")));

            // Nothing moved: the issue still carries the source version…
            assert fixVersionNames(getIssue(ctx, open.get("number").asLong())).equals(List.of("2.4.0"))
                    : "a rejected move must not have re-pointed the link";
            // …and the release itself was aborted with it.
            assert !versionNode(ctx, shipping).get("released").asBoolean()
                    : "a 422 move target must abort the release, not release-then-fail";
        }
        // The foreign versions were not touched either — no link leaked into them.
        assert versionNode(sibling, listId(sibling, "sibling-next")).get("issueCount").asInt() == 0;
        assert versionNode(other, listId(other, "tenant-b-next")).get("issueCount").asInt() == 0;

        // A LOCAL target still works, so the refusals above are about scope.
        var local = createVersion(ctx, "2.5.0");
        releaseVersion(ctx, ctx.token(), shipping, "{\"moveUnresolvedToVersionId\":\"" + local + "\"}")
                .andExpect(status().isOk());
        assert fixVersionNames(getIssue(ctx, open.get("number").asLong())).equals(List.of("2.5.0"));
    }

    @Test
    void deleteWithAForeignRemapTargetIs422AndTheVersionAndItsLinksSurvive() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var other = newProject();
        var doomed = createVersion(ctx, "2.4.0");
        var issue = createIssue(ctx, "carrier", fixVersionIdsJson(doomed),
                affectsVersionIdsJson(doomed));

        for (var foreign : List.of(createVersion(sibling, "sibling-target"),
                createVersion(other, "tenant-b-target"), UUID.randomUUID())) {
            deleteVersion(ctx, ctx.token(), doomed, "?remapToId=" + foreign)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail", containsString("Unknown version")));

            // The version is still there, with BOTH roles' links intact.
            assert versionNames(listVersions(ctx, ctx.token(), null)).equals(List.of("2.4.0"));
            var node = getIssue(ctx, issue.get("number").asLong());
            assert fixVersionNames(node).equals(List.of("2.4.0")) : node;
            assert affectsVersionNames(node).equals(List.of("2.4.0")) : node;
        }
        // Nothing was re-pointed into another project's release plan.
        assert versionNode(sibling, listId(sibling, "sibling-target")).get("issueCount").asInt() == 0;
        assert versionNode(sibling, listId(sibling, "sibling-target"))
                .get("affectsIssueCount").asInt() == 0;
        assert versionNode(other, listId(other, "tenant-b-target")).get("issueCount").asInt() == 0;
    }

    // ==================================================== (c) foreign ids in an issue payload

    @Test
    void fixAndAffectsVersionIdsFromAnotherProjectAre422UnknownVersionNot404() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var other = newProject();
        var siblingVersion = createVersion(sibling, "sibling-only");
        var foreignTenantVersion = createVersion(other, "tenant-b");
        var mine = createVersion(ctx, "mine");
        var issue = createIssue(ctx, "mine", fixVersionIdsJson(mine));

        for (var foreign : List.of(siblingVersion, foreignTenantVersion, UUID.randomUUID())) {
            for (var field : List.of("fixVersionIds", "affectsVersionIds")) {
                var payload = "\"" + field + "\":[\"" + foreign + "\"]";
                // PATCH — 422, the "invalid field value" answer, never a 404/403 that
                // would let the caller tell "exists elsewhere" from "does not exist".
                patchIssue(ctx, ctx.token(), issue.get("number").asLong(), "{" + payload + "}")
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.detail", containsString("Unknown version")));
                // CREATE rejects it the same way, before anything is written.
                postIssue(ctx, ctx.token(), "new one", payload)
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.detail", containsString("Unknown version")));
                // A foreign id mixed in with a LEGAL one is still the whole request's
                // 422 — resolveForIssue compares set sizes, so a silent drop here would
                // look like a successful partial write.
                patchIssue(ctx, ctx.token(), issue.get("number").asLong(),
                        "{\"" + field + "\":[\"" + mine + "\",\"" + foreign + "\"]}")
                        .andExpect(status().isUnprocessableContent());
            }
        }
        // The issue's own links are exactly as they started.
        var node = getIssue(ctx, issue.get("number").asLong());
        assert fixVersionNames(node).equals(List.of("mine")) : node;
        assert affectsVersionNames(node).isEmpty() : node;
        // …and no half-created issue landed in the project.
        assert board(ctx, null).get("issues").size() == 1;
    }

    // ==================================================== (d) foreign id as a filter

    @Test
    void boardAndBacklogFilteredByAForeignFixVersionIdReturnEmptyNotAnError() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var foreign = createVersion(sibling, "sibling-only");
        var mine = createVersion(ctx, "mine");
        createIssue(ctx, "tagged", fixVersionIdsJson(mine));
        createIssue(ctx, "plain");
        createIssue(sibling, "sibling issue", fixVersionIdsJson(foreign));

        // A foreign id is just an id that matches nothing here — 200 + empty, both shapes.
        assert board(ctx, "?fixVersionId=" + foreign).get("issues").size() == 0
                : "a foreign fixVersionId must match nothing on the board";
        assert backlog(ctx, "&fixVersionId=" + foreign).get("content").size() == 0
                : "a foreign fixVersionId must match nothing on the backlog";
        // A never-existed id behaves identically.
        assert board(ctx, "?fixVersionId=" + UUID.randomUUID()).get("issues").size() == 0;
        // And the local id still works, so the empties above are the filter, not a break.
        assert titles(board(ctx, "?fixVersionId=" + mine)).equals(Set.of("tagged"));
        assert pageTitles(backlog(ctx, "&fixVersionId=" + mine)).equals(Set.of("tagged"));
    }

    // ==================================================== (e) the DB-level guard

    /**
     * §3.8: {@code issue_versions} carries a denormalized {@code workspace_id} and
     * COMPOSITE FKs to BOTH parents —
     * {@code (issue_id, workspace_id) → issues (id, workspace_id)} and
     * {@code (version_id, workspace_id) → versions (id, workspace_id)}. This test
     * bypasses every service check and writes the row straight through the repository,
     * so what it locks in is the <em>schema</em>: a link between one tenant's issue and
     * another tenant's version is not "rejected", it is unrepresentable. Neither stamp
     * direction can produce a valid row, which is the whole point.
     */
    @Test
    void aCrossTenantLinkRowCannotBePersistedAtAll() throws Exception {
        var a = newProject();
        var b = newProject();
        var aIssue = issueRepository.findById(
                UUID.fromString(createIssue(a, "a issue").get("id").asText())).orElseThrow();
        var bVersion = versionRepository.findById(createVersion(b, "b version")).orElseThrow();
        var aWorkspace = workspaceRepository.findById(a.wsId()).orElseThrow();
        var bWorkspace = workspaceRepository.findById(b.wsId()).orElseThrow();

        // Stamped with the ISSUE's workspace (what VersionService.newRow does) → the
        // version-side composite FK has no (bVersion.id, aWorkspace.id) parent row.
        var stampedFromIssue = new IssueVersionLink();
        stampedFromIssue.setIssue(aIssue);
        stampedFromIssue.setVersion(bVersion);
        stampedFromIssue.setWorkspace(aWorkspace);
        stampedFromIssue.setLinkType(VersionLinkType.FIX);
        assertThrows(DataIntegrityViolationException.class,
                () -> linkRepository.saveAndFlush(stampedFromIssue),
                "issue_versions_version_fk must reject a version from another workspace");

        // Stamped with the VERSION's workspace instead → now the issue-side FK fails.
        var stampedFromVersion = new IssueVersionLink();
        stampedFromVersion.setIssue(aIssue);
        stampedFromVersion.setVersion(bVersion);
        stampedFromVersion.setWorkspace(bWorkspace);
        stampedFromVersion.setLinkType(VersionLinkType.AFFECTS);
        assertThrows(DataIntegrityViolationException.class,
                () -> linkRepository.saveAndFlush(stampedFromVersion),
                "issue_versions_issue_fk must reject an issue from another workspace");
    }

    /** Sanity anchor: the same write with both parents in ONE workspace succeeds. */
    @Test
    void aSameTenantLinkRowPersistsFine() throws Exception {
        var ctx = newProject();
        var issue = issueRepository.findById(
                UUID.fromString(createIssue(ctx, "mine").get("id").asText())).orElseThrow();
        var version = versionRepository.findById(createVersion(ctx, "2.4.0")).orElseThrow();

        var row = new IssueVersionLink();
        row.setIssue(issue);
        row.setVersion(version);
        row.setWorkspace(workspaceRepository.findById(ctx.wsId()).orElseThrow());
        row.setLinkType(VersionLinkType.FIX);
        linkRepository.saveAndFlush(row);

        assert fixVersionNames(getIssue(ctx, issue.getNumber())).equals(List.of("2.4.0"));
    }

    /**
     * The boundary the composite FKs do <em>not</em> draw, stated explicitly so nobody
     * mistakes the schema for the whole guard: they are keyed on the WORKSPACE, so a
     * version of a sibling project in the SAME workspace is representable at the DB
     * level. Keeping a link inside the issue's own project is
     * {@code VersionService.resolveForIssue}'s job — the 422 asserted above. If this
     * test ever starts failing because the FKs grew a project column, that is an
     * improvement: delete it and keep the stronger constraint.
     */
    @Test
    void theCompositeFksGuardTheTenantBoundaryAndTheServiceGuardsTheProjectBoundary()
            throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var issue = issueRepository.findById(
                UUID.fromString(createIssue(ctx, "mine").get("id").asText())).orElseThrow();
        var siblingVersion = versionRepository.findById(
                createVersion(sibling, "sibling-only")).orElseThrow();

        var row = new IssueVersionLink();
        row.setIssue(issue);
        row.setVersion(siblingVersion);
        row.setWorkspace(workspaceRepository.findById(ctx.wsId()).orElseThrow());
        row.setLinkType(VersionLinkType.FIX);
        linkRepository.saveAndFlush(row);      // same workspace → the FKs are satisfied

        // …which is exactly why the service-level 422 (asserted above) is load-bearing.
        patchIssue(ctx, ctx.token(), issue.getNumber(),
                "{\"fixVersionIds\":[\"" + siblingVersion.getId() + "\"]}")
                .andExpect(status().isUnprocessableContent());
    }

    // ==================================================== (f) the three cascades

    /**
     * §6.6 "Deleting an issue or a project cascades the links", plus the version case.
     * Asserted by deleting the parent ROW directly, no service in the way: the
     * {@code ON DELETE CASCADE} rides on the COMPOSITE FKs (the single-column ones are
     * deliberately not declared), a shape a migration edit could silently drop while
     * every service-level test kept passing — {@code VersionService.delete} removes the
     * links itself before deleting the row.
     */
    @Test
    void deletingTheVersionRowDirectlyCascadesItsLinksAndKeepsTheIssues() throws Exception {
        var ctx = newProject();
        var doomed = createVersion(ctx, "2.4.0");
        var keeper = createVersion(ctx, "2.5.0");
        var issue = createIssue(ctx, "carrier", fixVersionIdsJson(doomed, keeper),
                affectsVersionIdsJson(doomed));
        var issueId = UUID.fromString(issue.get("id").asText());
        assert linkRows(issueId) == 3;

        versionRepository.deleteById(doomed);
        versionRepository.flush();

        // Both of the doomed version's rows are gone, in BOTH roles…
        assert linkRows(issueId) == 1 : "issue_versions must cascade from versions";
        // …the issue itself survived (CASCADE on the LINK table, not on issues)…
        assert jdbcTemplate.queryForObject("SELECT count(*) FROM issues WHERE id = ?",
                Integer.class, issueId) == 1;
        // …and the other version's link is untouched.
        var node = getIssue(ctx, issue.get("number").asLong());
        assert fixVersionNames(node).equals(List.of("2.5.0")) : node;
        assert affectsVersionNames(node).isEmpty() : node;
    }

    @Test
    void deletingAnIssueCascadesItsLinksAndTheVersionSurvives() throws Exception {
        var ctx = newProject();
        var version = createVersion(ctx, "2.4.0");
        var doomed = createIssue(ctx, "doomed", fixVersionIdsJson(version),
                affectsVersionIdsJson(version));
        createIssue(ctx, "survivor", fixVersionIdsJson(version));
        versionUsage(ctx, ctx.token(), version)
                .andExpect(jsonPath("$.fixIssueCount").value(2))
                .andExpect(jsonPath("$.affectsIssueCount").value(1));

        mockMvc.perform(delete(ctx.issuesBase() + "/" + doomed.get("number").asLong())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());

        assert linkRows(UUID.fromString(doomed.get("id").asText())) == 0
                : "issue_versions must cascade from issues";
        // The version itself is untouched and its counters simply drop.
        versionUsage(ctx, ctx.token(), version)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixIssueCount").value(1))
                .andExpect(jsonPath("$.affectsIssueCount").value(0));
        // The surviving issue still carries it, so an unforced delete is still a 409.
        deleteVersion(ctx, ctx.token(), version, null).andExpect(status().isConflict());
    }

    @Test
    void deletingAProjectCascadesItsVersionsAndTheirLinks() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        var doomedVersion = createVersion(ctx, "2.4.0");
        var siblingVersion = createVersion(sibling, "9.9.9");
        var issue = createIssue(ctx, "carrier", fixVersionIdsJson(doomedVersion));
        createIssue(sibling, "sibling carrier", fixVersionIdsJson(siblingVersion));
        var issueId = UUID.fromString(issue.get("id").asText());

        projectRepository.deleteById(ctx.projectId());
        projectRepository.flush();

        assert versionRows(doomedVersion) == 0 : "versions must cascade from projects";
        assert linkRows(issueId) == 0 : "issue_versions must cascade with the issues";
        // The SIBLING project — same workspace — is completely unaffected.
        assert versionRows(siblingVersion) == 1;
        assert versionNames(listVersions(sibling, sibling.token(), null)).equals(List.of("9.9.9"));
        assert versionNode(sibling, siblingVersion).get("issueCount").asInt() == 1;
    }

    // ==================================================== (g) requireProjectCurator

    /**
     * The full {@code ScopeResolver.requireProjectCurator} matrix against the version
     * endpoints. The gate rests on the project's <strong>inverted-ordinal</strong>
     * {@code isAtLeast} convention (the privileged value sits at ordinal 0, so
     * {@code isAtLeast} compares {@code <=}); a refactor that reorders the role enums or
     * "fixes" the comparison to {@code >=} silently inverts every check, with nothing
     * failing to compile. {@link ComponentAuthzTest} pins the same matrix on the
     * component endpoints — versions get their own because they add four mutating
     * endpoints (release / unrelease / archive / unarchive) that gate independently.
     */
    @Test
    void aNonMemberOfTheWorkspaceGets404OnEveryVersionEndpointNever403() throws Exception {
        var ctx = newProject();
        var versionId = createVersion(ctx, "2.4.0");
        var outsider = login(user());          // authenticates fine, member of nothing

        var base = versionsBase(ctx);
        expect404(get(base), outsider);                                                  // list
        expect404(post(base).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}"), outsider);                                 // create
        expect404(get(base + "/" + versionId), outsider);                                // read one
        expect404(patch(base + "/" + versionId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}"), outsider);                                 // update
        expect404(post(base + "/" + versionId + "/release"), outsider);
        expect404(post(base + "/" + versionId + "/unrelease"), outsider);
        expect404(post(base + "/" + versionId + "/archive"), outsider);
        expect404(post(base + "/" + versionId + "/unarchive"), outsider);
        expect404(delete(base + "/" + versionId), outsider);
        expect404(get(base + "/" + versionId + "/usage"), outsider);

        // Nothing half-succeeded: the release plan is intact for its real curator.
        var mine = versionNode(ctx, versionId);
        assert mine.get("name").asText().equals("2.4.0") && !mine.get("released").asBoolean();
    }

    /**
     * The workspace OWNER/ADMIN bypass must not become a cross-tenant hole: an admin is
     * only an admin of <em>their own</em> workspace. Both addressing shapes 404 — the
     * honest path (workspace B) and the confused one (workspace A + B's project).
     */
    @Test
    void aWorkspaceAdminOfAnotherWorkspaceGets404NotAPassThroughTheBypass() throws Exception {
        var a = newProject();
        var b = newProject();
        var adminOfA = actorWith(a, WorkspaceRole.ADMIN, null);
        var bVersion = createVersion(b, "b-only");

        // (i) B's own path: not a member of B → 404 at requireMember.
        expect404(post("/api/workspaces/" + b.wsId() + "/projects/" + b.projectId() + "/versions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hijack\"}"),
                adminOfA.token());
        expect404(post("/api/workspaces/" + b.wsId() + "/projects/" + b.projectId()
                + "/versions/" + bVersion + "/release"), adminOfA.token());

        // (ii) A's workspace id + B's project id → the project isn't in A → 404.
        expect404(post("/api/workspaces/" + a.wsId() + "/projects/" + b.projectId() + "/versions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hijack\"}"),
                adminOfA.token());

        // B's release plan is untouched.
        assert versionNames(listVersions(b, b.token(), null)).equals(List.of("b-only"));
        assert !versionNode(b, bVersion).get("released").asBoolean();
    }

    @Test
    void aWorkspaceMemberWithNoProjectRoleIsForbiddenNotNotFound() throws Exception {
        var ctx = newProject();
        var versionId = createVersion(ctx, "2.4.0");
        // Member of the workspace, member of NOTHING in the project: the caller already
        // knows the project exists (they can list it), so a role failure is an honest 403.
        var plain = actorWith(ctx, WorkspaceRole.MEMBER, null);

        assertEveryMutationIsForbidden(ctx, versionId, plain.token());
    }

    @Test
    void projectMemberAndProjectViewerAreForbidden() throws Exception {
        var ctx = newProject();
        var versionId = createVersion(ctx, "2.4.0");
        var member = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.MEMBER);
        var viewer = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.VIEWER);

        assertEveryMutationIsForbidden(ctx, versionId, member.token());
        assertEveryMutationIsForbidden(ctx, versionId, viewer.token());
        // …and none of the refusals mutated anything.
        assert versionNames(listVersions(ctx, ctx.token(), "?includeArchived=true"))
                .equals(List.of("2.4.0"));
        assert !versionNode(ctx, versionId).get("released").asBoolean();
        assert !versionNode(ctx, versionId).get("archived").asBoolean();
    }

    private void assertEveryMutationIsForbidden(Ctx ctx, UUID versionId, String token)
            throws Exception {
        postVersion(ctx, token, "{\"name\":\"nope\"}").andExpect(status().isForbidden());
        patchVersion(ctx, token, versionId, "{\"name\":\"nope\"}").andExpect(status().isForbidden());
        releaseVersion(ctx, token, versionId).andExpect(status().isForbidden());
        unreleaseVersion(ctx, token, versionId).andExpect(status().isForbidden());
        archiveVersion(ctx, token, versionId).andExpect(status().isForbidden());
        unarchiveVersion(ctx, token, versionId).andExpect(status().isForbidden());
        deleteVersion(ctx, token, versionId, "?force=true").andExpect(status().isForbidden());
    }

    /** Reads are open to any project member — the 403s above are about WRITES only. */
    @Test
    void aPlainProjectMemberCanStillReadTheReleasePlan() throws Exception {
        var ctx = newProject();
        var versionId = createVersion(ctx, "2.4.0");
        var viewer = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.VIEWER);
        var noProjectRole = actorWith(ctx, WorkspaceRole.MEMBER, null);

        for (var actor : List.of(viewer, noProjectRole)) {
            assert versionNames(listVersions(ctx, actor.token(), null)).equals(List.of("2.4.0"));
            getVersion(ctx, actor.token(), versionId).andExpect(status().isOk());
            versionUsage(ctx, actor.token(), versionId).andExpect(status().isOk());
        }
    }

    @Test
    void aProjectManagerMayCurateTheWholeLifecycle() throws Exception {
        var ctx = newProject();
        var manager = actorWith(ctx, WorkspaceRole.MEMBER, ProjectRole.MANAGER);

        var id = createVersion(ctx, manager.token(), "{\"name\":\"2.4.0\"}");
        patchVersion(ctx, manager.token(), id, "{\"description\":\"curated\"}")
                .andExpect(status().isOk());
        releaseVersion(ctx, manager.token(), id).andExpect(status().isOk());
        unreleaseVersion(ctx, manager.token(), id).andExpect(status().isOk());
        archiveVersion(ctx, manager.token(), id).andExpect(status().isOk());
        unarchiveVersion(ctx, manager.token(), id).andExpect(status().isOk());
        deleteVersion(ctx, manager.token(), id, null).andExpect(status().isNoContent());
    }

    /**
     * The deliberate bypass (ScopeResolver javadoc): a workspace OWNER/ADMIN curates a
     * project they are not a member of, because they already edit that project's
     * bindings through the admin API.
     */
    @Test
    void workspaceOwnerAndWorkspaceAdminCurateWithoutBeingProjectMembers() throws Exception {
        var ctx = newProject();
        var owner = actorWith(ctx, WorkspaceRole.OWNER, null);
        var admin = actorWith(ctx, WorkspaceRole.ADMIN, null);

        for (var actor : List.of(owner, admin)) {
            var id = createVersion(ctx, actor.token(),
                    "{\"name\":\"by-" + actor.user().getId().toString().substring(0, 8) + "\"}");
            patchVersion(ctx, actor.token(), id, "{\"description\":\"curated\"}")
                    .andExpect(status().isOk());
            releaseVersion(ctx, actor.token(), id).andExpect(status().isOk());
            unreleaseVersion(ctx, actor.token(), id).andExpect(status().isOk());
            archiveVersion(ctx, actor.token(), id).andExpect(status().isOk());
            unarchiveVersion(ctx, actor.token(), id).andExpect(status().isOk());
            deleteVersion(ctx, actor.token(), id, null).andExpect(status().isNoContent());
        }
    }

    // ==================================================== helpers

    /** 404 and specifically NOT 403 — a 403 would confirm the resource exists. */
    private void expect404(MockHttpServletRequestBuilder req, String token) throws Exception {
        mockMvc.perform(req.header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(status().is(not(403)));
    }

    /** Raw {@code issue_versions} row count for one issue — no service in the way. */
    private int linkRows(UUID issueId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM issue_versions WHERE issue_id = ?", Integer.class, issueId);
    }

    private int versionRows(UUID versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM versions WHERE id = ?", Integer.class, versionId);
    }

    /** The id of a version by name, read back off the project's list. */
    private UUID listId(Ctx ctx, String name) throws Exception {
        for (var v : listVersions(ctx, ctx.token(), "?includeArchived=true")) {
            if (v.get("name").asText().equals(name)) return UUID.fromString(v.get("id").asText());
        }
        throw new AssertionError("no version named " + name);
    }
}
