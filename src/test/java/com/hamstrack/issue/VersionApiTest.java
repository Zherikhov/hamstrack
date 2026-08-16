package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-32 catalog surface (proposal §6.1, §6.4, §6.6) — create / read / update /
 * archive / delete, the derived Releases-page order, the per-version progress
 * roll-up, and the two delete escape hatches ({@code force} and {@code remapToId}).
 *
 * <p>The release lifecycle itself lives in {@link VersionReleaseLifecycleTest} and
 * the entity-level {@code updatable = false} guard in
 * {@link VersionLifecycleIntegrityTest}; this class covers everything around them.
 *
 * <p>Two things here are load-bearing rather than smoke:
 * <ul>
 *   <li><strong>progress counts FIX links only.</strong> {@code issueCount} /
 *       {@code doneIssueCount} drive the release progress bar; an AFFECTS link means
 *       "this defect exists in that release", which is not work shipping in it. One
 *       shared {@code issue_versions} table makes conflating the two roles a
 *       one-character mistake, and the resulting bar would look plausible.</li>
 *   <li><strong>delete-with-remap must survive a collision</strong> on
 *       {@code UNIQUE (issue_id, version_id, link_type)} — the documented
 *       "INSERT/UPDATE ordered before DELETE within one flush" trap (§6.4 T3), which
 *       already bit label merge and every admin set editor. Asserted with issues that
 *       carry the target in the same role, in the other role, and not at all.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class VersionApiTest extends VersionTestBase {

    // ==================================================== create

    @Test
    void aNewVersionIsBornUnreleasedWithNoReleasedAt() throws Exception {
        var ctx = newProject();

        postVersion(ctx, ctx.token(), "{\"name\":\"2.4.0\",\"description\":\"autumn\","
                + "\"releaseDate\":\"2026-09-01\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("2.4.0"))
                .andExpect(jsonPath("$.description").value("autumn"))
                // releaseDate is only the PLAN — it does not imply a release.
                .andExpect(jsonPath("$.releaseDate").value("2026-09-01"))
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.releasedAt").isEmpty())
                .andExpect(jsonPath("$.archived").value(false))
                // A brand-new version has no links, so the grouped progress query is
                // skipped: the response must still carry honest zeroes, not nulls.
                .andExpect(jsonPath("$.issueCount").value(0))
                .andExpect(jsonPath("$.doneIssueCount").value(0))
                .andExpect(jsonPath("$.affectsIssueCount").value(0));
    }

    @Test
    void aDuplicateNameIs409PerProjectAndTheSameNameInASiblingProjectIs201() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        createVersion(ctx, "2.4.0");

        postVersion(ctx, ctx.token(), "{\"name\":\"2.4.0\"}").andExpect(status().isConflict());
        // Case-insensitive: versions_project_name_uk is a lower(name) functional index.
        postVersion(ctx, ctx.token(), "{\"name\":\"2.4.0 \"}").andExpect(status().isConflict());
        // Versions are PROJECT-owned, so the sibling gets its own name slot.
        postVersion(sibling, sibling.token(), "{\"name\":\"2.4.0\"}").andExpect(status().isCreated());

        // An ARCHIVED row keeps its slot, with a message that says so.
        var archived = createVersion(ctx, "2.3.0");
        archiveVersion(ctx, ctx.token(), archived).andExpect(status().isOk());
        postVersion(ctx, ctx.token(), "{\"name\":\"2.3.0\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("archived version already uses this name")));
    }

    @Test
    void aBlankOrOverlongNameIs400() throws Exception {
        var ctx = newProject();

        postVersion(ctx, ctx.token(), "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        // Normalization collapses whitespace/format characters before the length check.
        postVersion(ctx, ctx.token(), "{\"name\":\"\\u200b\"}").andExpect(status().isBadRequest());
        postVersion(ctx, ctx.token(), "{\"name\":\"" + "x".repeat(61) + "\"}")
                .andExpect(status().isBadRequest());
        assert listVersions(ctx, ctx.token(), null).size() == 0 : "nothing was created";
    }

    /**
     * §6.3: {@code LocalDate} happily parses a year PostgreSQL's {@code date} cannot
     * hold, which used to reach {@code saveAndFlush} and come back as a driver-level
     * failure — a free <strong>500</strong> for any curator. It is an absurd field
     * value in an otherwise legitimate request, i.e. a <strong>422</strong>.
     */
    @Test
    void aReleaseDateOutsidePostgresDateRangeIs422NotAn500() throws Exception {
        var ctx = newProject();

        postVersion(ctx, ctx.token(), "{\"name\":\"far\",\"releaseDate\":\"+999999999-12-31\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Release date must be between")));
        postVersion(ctx, ctx.token(), "{\"name\":\"long ago\",\"releaseDate\":\"-999999999-01-01\"}")
                .andExpect(status().isUnprocessableContent());

        // The same guard on the PATCH and the release paths, not just on create.
        var id = createVersion(ctx, "2.4.0");
        patchVersion(ctx, ctx.token(), id, "{\"releaseDate\":\"+999999999-12-31\"}")
                .andExpect(status().isUnprocessableContent());
        releaseVersion(ctx, ctx.token(), id, "{\"releaseDate\":\"+999999999-12-31\"}")
                .andExpect(status().isUnprocessableContent());
        // …and none of the rejects wrote anything.
        assert versionNode(ctx, id).get("releaseDate").isNull();
        assert !versionNode(ctx, id).get("released").asBoolean();

        // The edges of the accepted window still bind normally.
        postVersion(ctx, ctx.token(), "{\"name\":\"epoch\",\"releaseDate\":\"1000-01-01\"}")
                .andExpect(status().isCreated());
        postVersion(ctx, ctx.token(), "{\"name\":\"forever\",\"releaseDate\":\"9999-12-31\"}")
                .andExpect(status().isCreated());
    }

    // ==================================================== update

    @Test
    void patchRenamesRedescribesAndCanClearThePlannedDate() throws Exception {
        var ctx = newProject();
        var id = createPlannedVersion(ctx, "2.4.0", "2026-09-01");

        patchVersion(ctx, ctx.token(), id, "{\"name\":\"2.4.1\",\"description\":\"slipped\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("2.4.1"))
                .andExpect(jsonPath("$.description").value("slipped"))
                .andExpect(jsonPath("$.releaseDate").value("2026-09-01"));

        // A pure casing change keeps the same unique slot rather than 409ing on itself.
        patchVersion(ctx, ctx.token(), id, "{\"name\":\"2.4.1-RC\"}").andExpect(status().isOk());
        patchVersion(ctx, ctx.token(), id, "{\"name\":\"2.4.1-rc\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("2.4.1-rc"));

        // A date re-plans; clearReleaseDate unsets it (a nullable scalar needs both).
        patchVersion(ctx, ctx.token(), id, "{\"releaseDate\":\"2026-10-01\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseDate").value("2026-10-01"));
        patchVersion(ctx, ctx.token(), id, "{\"clearReleaseDate\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseDate").isEmpty());

        // Renaming onto a sibling's name is a 409.
        createVersion(ctx, "3.0.0");
        patchVersion(ctx, ctx.token(), id, "{\"name\":\"3.0.0\"}").andExpect(status().isConflict());
    }

    // ==================================================== the derived order

    /**
     * §6.1: there is deliberately no {@code position} column — the Releases page order
     * is derived, {@code released ASC} (unreleased first), then
     * {@code release_date ASC NULLS LAST}, then {@code lower(name)}.
     */
    @Test
    void theListIsOrderedUnreleasedFirstThenByDateWithUndatedLastThenByName() throws Exception {
        var ctx = newProject();
        // deliberately created in an order that matches none of the sort keys
        var zUndated = createVersion(ctx, "z-undated");
        createVersion(ctx, "a-undated");
        createPlannedVersion(ctx, "b-late", "2027-01-01");
        createPlannedVersion(ctx, "a-early", "2026-01-01");
        var shipped = createPlannedVersion(ctx, "shipped", "2025-06-01");
        var shippedEarlier = createPlannedVersion(ctx, "shipped-earlier", "2025-01-01");
        releaseVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());
        releaseVersion(ctx, ctx.token(), shippedEarlier).andExpect(status().isOk());

        assert versionNames(listVersions(ctx, ctx.token(), null)).equals(List.of(
                // unreleased, dated, ascending…
                "a-early", "b-late",
                // …then unreleased and undated, by name…
                "a-undated", "z-undated",
                // …then the released ones, still by date.
                "shipped-earlier", "shipped"))
                : versionNames(listVersions(ctx, ctx.token(), null));

        // includeReleased=false drops the shipped ones without disturbing the rest.
        assert versionNames(listVersions(ctx, ctx.token(), "?includeReleased=false"))
                .equals(List.of("a-early", "b-late", "a-undated", "z-undated"));

        // Archived rows are out by default and in on request, keeping their place.
        archiveVersion(ctx, ctx.token(), zUndated).andExpect(status().isOk());
        assert versionNames(listVersions(ctx, ctx.token(), null))
                .equals(List.of("a-early", "b-late", "a-undated", "shipped-earlier", "shipped"));
        assert versionNames(listVersions(ctx, ctx.token(), "?includeArchived=true"))
                .equals(List.of("a-early", "b-late", "a-undated", "z-undated",
                        "shipped-earlier", "shipped"));
    }

    // ==================================================== progress

    /**
     * The FIX-only rule. {@code issueCount}/{@code doneIssueCount} are the release
     * progress bar; an AFFECTS link is a statement about where a defect exists, not
     * work shipping in the release, and it must not move either number.
     */
    @Test
    void progressCountsFixLinksOnlyAndAffectsLinksLandInTheirOwnCounter() throws Exception {
        var ctx = newProject();
        var target = createVersion(ctx, "2.4.0");
        var other = createVersion(ctx, "2.3.0");

        var open = createIssue(ctx, "open fix", fixVersionIdsJson(target));
        var done = createIssue(ctx, "done fix", fixVersionIdsJson(target));
        createIssue(ctx, "affects only", affectsVersionIdsJson(target));
        createIssue(ctx, "both roles", fixVersionIdsJson(target), affectsVersionIdsJson(target));
        createIssue(ctx, "elsewhere", fixVersionIdsJson(other));
        markDone(ctx, done.get("number").asLong());

        var node = versionNode(ctx, target);
        assert node.get("issueCount").asInt() == 3 : node;          // open + done + both
        assert node.get("doneIssueCount").asInt() == 1 : node;
        assert node.get("affectsIssueCount").asInt() == 2 : node;   // affects-only + both

        // usage/{id} is fed by the SAME grouped query and must agree, with
        // unresolvedFixIssueCount = fix - fixDone (what a release-time move would move).
        versionUsage(ctx, ctx.token(), target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixIssueCount").value(3))
                .andExpect(jsonPath("$.affectsIssueCount").value(2))
                .andExpect(jsonPath("$.unresolvedFixIssueCount").value(2));

        // A version nothing links to reports honest zeroes, not nulls or a 404.
        var empty = createVersion(ctx, "0.0.1");
        assert versionNode(ctx, empty).get("issueCount").asInt() == 0;
        assert versionNode(ctx, empty).get("doneIssueCount").asInt() == 0;
        assert versionNode(ctx, empty).get("affectsIssueCount").asInt() == 0;
        versionUsage(ctx, ctx.token(), empty)
                .andExpect(jsonPath("$.fixIssueCount").value(0))
                .andExpect(jsonPath("$.affectsIssueCount").value(0))
                .andExpect(jsonPath("$.unresolvedFixIssueCount").value(0));

        // The list carries the same numbers as the single GET (one grouped query for
        // the whole page — a per-row query would be the thing that drifts).
        for (var v : listVersions(ctx, ctx.token(), null)) {
            if (v.get("id").asText().equals(target.toString())) {
                assert v.get("issueCount").asInt() == 3 && v.get("doneIssueCount").asInt() == 1
                        && v.get("affectsIssueCount").asInt() == 2 : v;
            }
        }
        // …and the elsewhere-linked issue only moved the OTHER version's counter.
        assert versionNode(ctx, other).get("issueCount").asInt() == 1;
        assert versionNode(ctx, other).get("affectsIssueCount").asInt() == 0;
    }

    // ==================================================== archive

    @Test
    void archiveKeepsTheLinksAndUnarchiveRestoresTheRow() throws Exception {
        var ctx = newProject();
        var id = createVersion(ctx, "2.4.0");
        var issue = createIssue(ctx, "carrier", fixVersionIdsJson(id));

        archiveVersion(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        // The link survives and still renders on the issue…
        assert fixVersionNames(getIssue(ctx, issue.get("number").asLong())).equals(List.of("2.4.0"));
        // …and the progress counter still sees it.
        assert versionNode(ctx, id).get("issueCount").asInt() == 1;

        unarchiveVersion(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
    }

    // ==================================================== delete

    @Test
    void deletingAnUnusedVersionIs204AndDeletingAUsedOneIs409WithoutAnEscapeHatch()
            throws Exception {
        var ctx = newProject();
        var unused = createVersion(ctx, "0.0.1");
        var used = createVersion(ctx, "2.4.0");
        createIssue(ctx, "carrier", fixVersionIdsJson(used));

        deleteVersion(ctx, ctx.token(), unused, null).andExpect(status().isNoContent());
        deleteVersion(ctx, ctx.token(), used, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("linked to 1 issue")));
        // An AFFECTS-only link blocks the delete just as a FIX one does.
        var affectsOnly = createVersion(ctx, "2.3.0");
        createIssue(ctx, "affected", affectsVersionIdsJson(affectsOnly));
        deleteVersion(ctx, ctx.token(), affectsOnly, null).andExpect(status().isConflict());

        assert versionNames(listVersions(ctx, ctx.token(), null)).equals(List.of("2.3.0", "2.4.0"));
    }

    @Test
    void forceDeleteDropsBothRolesLinksAndLeavesTheIssuesIntact() throws Exception {
        var ctx = newProject();
        var doomed = createVersion(ctx, "2.4.0");
        var keeper = createVersion(ctx, "2.5.0");
        var a = createIssue(ctx, "fix", fixVersionIdsJson(doomed));
        var b = createIssue(ctx, "affects", affectsVersionIdsJson(doomed));
        var c = createIssue(ctx, "both versions", fixVersionIdsJson(doomed, keeper));

        deleteVersion(ctx, ctx.token(), doomed, "?force=true").andExpect(status().isNoContent());

        assert fixVersionNames(getIssue(ctx, a.get("number").asLong())).isEmpty();
        assert affectsVersionNames(getIssue(ctx, b.get("number").asLong())).isEmpty();
        // The OTHER version's link on the same issue is untouched — force is scoped.
        assert fixVersionNames(getIssue(ctx, c.get("number").asLong())).equals(List.of("2.5.0"));
        assert versionNames(listVersions(ctx, ctx.token(), "?includeArchived=true"))
                .equals(List.of("2.5.0"));
    }

    /**
     * {@code remapToId} re-points <strong>both roles</strong>, and the collision case
     * is what the DELETE → {@code flush()} → re-point sequence exists for: an issue
     * that already carries the target in the same role would otherwise violate
     * {@code UNIQUE (issue_id, version_id, link_type)} (§6.4 T3).
     */
    @Test
    void deleteWithRemapRepointsBothRolesAndCollapsesCollisionsWithoutAUniqueViolation()
            throws Exception {
        var ctx = newProject();
        var doomed = createVersion(ctx, "2.4.0");
        var target = createVersion(ctx, "2.5.0");

        // Five shapes: the collision in each role, a clean re-point in each role, and
        // the cross-role case (FIX on the source, AFFECTS already on the target) which
        // must NOT be treated as a collision — the two roles are independent.
        var fixCollision = createIssue(ctx, "fix on both", fixVersionIdsJson(doomed, target));
        var fixClean = createIssue(ctx, "fix on doomed", fixVersionIdsJson(doomed));
        var affectsCollision = createIssue(ctx, "affects on both",
                affectsVersionIdsJson(doomed, target));
        var affectsClean = createIssue(ctx, "affects on doomed", affectsVersionIdsJson(doomed));
        var crossRole = createIssue(ctx, "fix doomed, affects target",
                fixVersionIdsJson(doomed), affectsVersionIdsJson(target));
        var untouched = createIssue(ctx, "nothing to do with it");

        deleteVersion(ctx, ctx.token(), doomed, "?remapToId=" + target)
                .andExpect(status().isNoContent());

        // The collision collapsed into ONE row instead of blowing up…
        assert fixVersionNames(getIssue(ctx, fixCollision.get("number").asLong()))
                .equals(List.of("2.5.0"));
        assert fixVersionNames(getIssue(ctx, fixClean.get("number").asLong()))
                .equals(List.of("2.5.0"));
        assert affectsVersionNames(getIssue(ctx, affectsCollision.get("number").asLong()))
                .equals(List.of("2.5.0"));
        assert affectsVersionNames(getIssue(ctx, affectsClean.get("number").asLong()))
                .equals(List.of("2.5.0"));
        // …and the cross-role issue now legitimately carries the target TWICE, once per
        // role, because link_type is part of the unique key.
        var cross = getIssue(ctx, crossRole.get("number").asLong());
        assert fixVersionNames(cross).equals(List.of("2.5.0")) : cross;
        assert affectsVersionNames(cross).equals(List.of("2.5.0")) : cross;
        assert fixVersionNames(getIssue(ctx, untouched.get("number").asLong())).isEmpty();

        // The target's counters now reflect the merged sets: 3 fix, 3 affects.
        var node = versionNode(ctx, target);
        assert node.get("issueCount").asInt() == 3 : node;
        assert node.get("affectsIssueCount").asInt() == 3 : node;
        assert versionNames(listVersions(ctx, ctx.token(), "?includeArchived=true"))
                .equals(List.of("2.5.0"));
    }

    /**
     * Unlike the release-time move, a <em>released</em> remap target is legitimate —
     * "that work actually shipped in 2.3.1" is normal back-porting bookkeeping (§6.4).
     * The asymmetry is subtle enough that "simplifying" the two validators into one is
     * an easy and silent regression.
     */
    @Test
    void aReleasedVersionIsALegalRemapTargetButAnArchivedOrSelfOrUnknownOneIs422()
            throws Exception {
        var ctx = newProject();
        var doomed = createVersion(ctx, "2.4.0");
        var archived = createVersion(ctx, "0.9.0");
        var shipped = createVersion(ctx, "2.3.1");
        createIssue(ctx, "carrier", fixVersionIdsJson(doomed));
        archiveVersion(ctx, ctx.token(), archived).andExpect(status().isOk());
        releaseVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());

        deleteVersion(ctx, ctx.token(), doomed, "?remapToId=" + archived)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("is archived")));
        deleteVersion(ctx, ctx.token(), doomed, "?remapToId=" + doomed)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("can't be remapped to itself")));
        deleteVersion(ctx, ctx.token(), doomed, "?remapToId=" + UUID.randomUUID())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Unknown version")));
        // Every rejection left the version — and its link — completely alone.
        assert versionNode(ctx, doomed).get("issueCount").asInt() == 1;

        // …and the released target IS accepted.
        deleteVersion(ctx, ctx.token(), doomed, "?remapToId=" + shipped)
                .andExpect(status().isNoContent());
        assert versionNode(ctx, shipped).get("issueCount").asInt() == 1;
    }

    /** {@code remapToId} is the strictly more specific, non-destructive intent. */
    @Test
    void whenBothForceAndRemapToIdAreGivenRemapWins() throws Exception {
        var ctx = newProject();
        var doomed = createVersion(ctx, "2.4.0");
        var target = createVersion(ctx, "2.5.0");
        var issue = createIssue(ctx, "carrier", fixVersionIdsJson(doomed));

        deleteVersion(ctx, ctx.token(), doomed, "?force=true&remapToId=" + target)
                .andExpect(status().isNoContent());

        assert fixVersionNames(getIssue(ctx, issue.get("number").asLong())).equals(List.of("2.5.0"))
                : "remapToId must win over force — force would have dropped the link";
    }

    /** Deleting a released version is allowed: force/remap already require intent. */
    @Test
    void aReleasedVersionCanBeDeletedWithTheSameRules() throws Exception {
        var ctx = newProject();
        var shipped = createVersion(ctx, "2.3.1");
        createIssue(ctx, "carrier", fixVersionIdsJson(shipped));
        releaseVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());

        deleteVersion(ctx, ctx.token(), shipped, null).andExpect(status().isConflict());
        deleteVersion(ctx, ctx.token(), shipped, "?force=true").andExpect(status().isNoContent());
        assert listVersions(ctx, ctx.token(), "?includeArchived=true").size() == 0;
    }

    // ==================================================== archived project

    /** Managing an archived project's release plan is frozen, exactly as issue edits are. */
    @Test
    void everyMutatingEndpointIs409OnceTheProjectIsArchived() throws Exception {
        var ctx = newProject();
        var id = createVersion(ctx, "2.4.0");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId() + "/archive")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().is2xxSuccessful());

        postVersion(ctx, ctx.token(), "{\"name\":\"nope\"}").andExpect(status().isConflict());
        patchVersion(ctx, ctx.token(), id, "{\"name\":\"nope\"}").andExpect(status().isConflict());
        releaseVersion(ctx, ctx.token(), id).andExpect(status().isConflict());
        unreleaseVersion(ctx, ctx.token(), id).andExpect(status().isConflict());
        archiveVersion(ctx, ctx.token(), id).andExpect(status().isConflict());
        deleteVersion(ctx, ctx.token(), id, null).andExpect(status().isConflict());

        // Reads still work — the freeze is about writes.
        assert versionNames(listVersions(ctx, ctx.token(), null)).equals(List.of("2.4.0"));
        getVersion(ctx, ctx.token(), id).andExpect(status().isOk());
        versionUsage(ctx, ctx.token(), id).andExpect(status().isOk());

        // Unarchiving the project thaws curation again.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                                + "/unarchive").header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().is2xxSuccessful());
        releaseVersion(ctx, ctx.token(), id).andExpect(status().isOk());
    }
}
