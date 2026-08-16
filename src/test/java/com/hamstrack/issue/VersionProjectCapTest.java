package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code app.classification.max-versions-per-project} (proposal §3.9, §6.6) — the
 * catalog bound that makes curator-gated version creation safe. The gate is not a
 * volume barrier: any authenticated user creates their own workspace, is its OWNER and
 * is therefore curator of every project in it, while {@code ResolutionContextFactory}
 * loads every version NAME of every visible project on each {@code /search},
 * {@code /schema} and {@code /suggest}, and {@code GET …/versions} has no
 * {@code Pageable} at all.
 *
 * <p>Overridden to <strong>2</strong> here (default 500), mirroring
 * {@code ComponentProjectCapTest} — with the one difference that matters:
 * <strong>released</strong> versions count too, not just archived ones. A release plan
 * naturally accumulates shipped versions, so "release it and the slot frees up" is the
 * plausible-sounding shortcut this class exists to refuse. That is also why the message
 * deliberately says "delete some first" rather than the label wording's "archive or
 * delete": archiving does not free a slot.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.classification.max-versions-per-project=2"
})
@AutoConfigureMockMvc
class VersionProjectCapTest extends VersionTestBase {

    @Test
    void theVersionAfterTheCapIs422AndTheCapIsPerProject() throws Exception {
        var ctx = newProject();
        var sibling = siblingProject(ctx);
        createVersion(ctx, "1.0.0");
        createVersion(ctx, "2.0.0");

        postVersion(ctx, ctx.token(), "{\"name\":\"3.0.0\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("maximum of 2 versions")))
                // "delete some first", NOT "archive or delete" — archiving keeps the slot.
                .andExpect(jsonPath("$.detail", containsString("delete some first")))
                .andExpect(jsonPath("$.detail", not(containsString("archive"))));

        // A sibling PROJECT has its own budget (versions are project-owned)…
        postVersion(sibling, sibling.token(), "{\"name\":\"3.0.0\"}").andExpect(status().isCreated());
        // …and so does another workspace entirely.
        var other = newProject();
        postVersion(other, other.token(), "{\"name\":\"3.0.0\"}").andExpect(status().isCreated());
    }

    /**
     * The two bypasses the builder deliberately closed. An archived <em>or</em> released
     * row keeps its unique name slot and is still loaded by
     * {@code findAllByProject(includeArchived = true, includeReleased = true)}, so
     * counting only live/unreleased rows would make the ceiling defeatable by
     * create → archive → repeat (or create → release → repeat), forever.
     */
    @Test
    void archivedAndReleasedVersionsBothStillCountTowardsTheCeiling() throws Exception {
        var ctx = newProject();
        var archived = createVersion(ctx, "1.0.0");
        var released = createVersion(ctx, "2.0.0");

        archiveVersion(ctx, ctx.token(), archived).andExpect(status().isOk());
        postVersion(ctx, ctx.token(), "{\"name\":\"3.0.0\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("maximum of 2 versions")));

        // Releasing the other one doesn't free a slot either — a release plan
        // accumulates shipped versions, which is exactly why they have to count.
        releaseVersion(ctx, ctx.token(), released).andExpect(status().isOk());
        postVersion(ctx, ctx.token(), "{\"name\":\"3.0.0\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("maximum of 2 versions")));

        // Both rows are still there — nothing was silently reclaimed.
        assert versionNames(listVersions(ctx, ctx.token(), "?includeArchived=true"))
                .equals(List.of("1.0.0", "2.0.0"));

        // …and DELETING one — not archiving, not releasing it — is what frees the slot.
        deleteVersion(ctx, ctx.token(), archived, "?force=true").andExpect(status().isNoContent());
        postVersion(ctx, ctx.token(), "{\"name\":\"3.0.0\"}").andExpect(status().isCreated());
    }

    /**
     * A full catalog must not break the versions a project already has: the cap gates
     * {@code create} only, and every other lifecycle move stays available. Otherwise
     * hitting the ceiling would freeze the release plan instead of just refusing to grow
     * it — the failure mode a clamped/misplaced cap produces.
     */
    @Test
    void aFullCatalogStillEditsReleasesAndLinksNormally() throws Exception {
        var ctx = newProject();
        var one = createVersion(ctx, "1.0.0");
        var two = createVersion(ctx, "2.0.0");
        postVersion(ctx, ctx.token(), "{\"name\":\"3.0.0\"}")
                .andExpect(status().isUnprocessableContent());

        patchVersion(ctx, ctx.token(), one, "{\"name\":\"1.0.1\"}").andExpect(status().isOk());
        releaseVersion(ctx, ctx.token(), one).andExpect(status().isOk());
        unreleaseVersion(ctx, ctx.token(), one).andExpect(status().isOk());
        var issue = createIssue(ctx, "carrier", fixVersionIdsJson(one), affectsVersionIdsJson(two));
        assert fixVersionNames(getIssue(ctx, issue.get("number").asLong())).equals(List.of("1.0.1"));
    }
}
