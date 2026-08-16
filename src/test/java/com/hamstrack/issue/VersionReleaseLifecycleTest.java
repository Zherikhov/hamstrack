package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-32's headline acceptance criterion — <strong>release is reversible</strong>
 * (proposal §6.1, §6.4, §6.6) — plus the optional
 * {@code moveUnresolvedToVersionId} that rides along with it.
 *
 * <p>What is actually being defended here:
 * <ul>
 *   <li><strong>The 409s are the concurrency arbiter, not politeness.</strong> Both
 *       flips are conditional bulk UPDATEs ({@code … WHERE released = false}) checked
 *       by affected-row count. An idempotent-looking success on a second release would
 *       hide a double-click that runs the destructive move twice — precisely the case
 *       that must not be hidden. Asserting "the second call is 409" is therefore
 *       asserting the guard still exists at all.</li>
 *   <li><strong>Un-release preserves {@code releaseDate}.</strong> It is the plan, not
 *       the event; losing it on the round-trip would make "reversible" false in the
 *       only way that matters.</li>
 *   <li><strong>The move is scoped three ways at once</strong> — to the FIX role, to
 *       non-DONE issues, and to a legal target — and it must collapse duplicates
 *       instead of violating {@code UNIQUE (issue_id, version_id, link_type)} (§6.4
 *       T2, the "INSERT before DELETE within one flush" trap).</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class VersionReleaseLifecycleTest extends VersionTestBase {

    // ==================================================== the round trip

    @Test
    void releaseThenUnreleaseRoundTripsWithoutLosingThePlannedDate() throws Exception {
        var ctx = newProject();
        var id = createPlannedVersion(ctx, "2.4.0", "2026-09-01");

        var before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        releaseVersion(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                // no body and a stored plan date → the plan date is what ships
                .andExpect(jsonPath("$.releaseDate").value("2026-09-01"));
        var releasedAt = OffsetDateTime.parse(versionNode(ctx, id).get("releasedAt").asText());
        assert releasedAt.isAfter(before) : "releasedAt must be stamped, got " + releasedAt;

        unreleaseVersion(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.releasedAt").isEmpty())
                // the story's criterion: full reversibility with NO data loss
                .andExpect(jsonPath("$.releaseDate").value("2026-09-01"));

        // …and it can be released again, which is what "reversible" has to mean.
        releaseVersion(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));
    }

    @Test
    void releasingTwiceIs409AndUnreleasingSomethingUnreleasedIs409() throws Exception {
        var ctx = newProject();
        var id = createVersion(ctx, "2.4.0");

        // Never released → un-release is a 409, not a silent no-op.
        unreleaseVersion(ctx, ctx.token(), id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("not released")));

        releaseVersion(ctx, ctx.token(), id).andExpect(status().isOk());
        releaseVersion(ctx, ctx.token(), id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already released")));

        unreleaseVersion(ctx, ctx.token(), id).andExpect(status().isOk());
        // A second un-release is a 409 for the same reason the second release is.
        unreleaseVersion(ctx, ctx.token(), id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("not released")));

        assert !versionNode(ctx, id).get("released").asBoolean();
    }

    /**
     * The 409 must fire <em>before</em> the destructive move runs — that is the whole
     * reason the flip is the conditional UPDATE rather than a read-then-write guard.
     */
    @Test
    void aSecondReleaseCarryingAMoveIs409AndDoesNotRunTheMove() throws Exception {
        var ctx = newProject();
        var shipping = createVersion(ctx, "2.4.0");
        var next = createVersion(ctx, "2.5.0");
        var open = createIssue(ctx, "still open", fixVersionIdsJson(shipping));

        releaseVersion(ctx, ctx.token(), shipping).andExpect(status().isOk());
        // The double-click: same request again, this time with a move attached.
        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + next + "\"}")
                .andExpect(status().isConflict());

        assert fixVersionNames(getIssue(ctx, open.get("number").asLong())).equals(List.of("2.4.0"))
                : "the rejected second release must not have moved anything";
        assert versionNode(ctx, next).get("issueCount").asInt() == 0;
    }

    // ==================================================== the release date

    /** Precedence (§6.4): request body → stored plan → the server's UTC today. */
    @Test
    void theReleaseDateFallsBackFromBodyToStoredPlanToUtcToday() throws Exception {
        var ctx = newProject();

        // (a) neither a body date nor a stored one → today, in UTC
        var bare = createVersion(ctx, "bare");
        releaseVersion(ctx, ctx.token(), bare)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseDate").value(LocalDate.now(ZoneOffset.UTC).toString()));

        // (b) a stored plan and no body date → the stored plan
        var planned = createPlannedVersion(ctx, "planned", "2026-09-01");
        releaseVersion(ctx, ctx.token(), planned)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseDate").value("2026-09-01"));

        // (c) a body date OVERRIDES a stored one — "we actually shipped on the 3rd"
        var slipped = createPlannedVersion(ctx, "slipped", "2026-09-01");
        releaseVersion(ctx, ctx.token(), slipped, "{\"releaseDate\":\"2026-09-03\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseDate").value("2026-09-03"));
        // …and the override survives the un-release round trip, since it is now THE plan.
        unreleaseVersion(ctx, ctx.token(), slipped)
                .andExpect(jsonPath("$.releaseDate").value("2026-09-03"));

        // (d) an empty body object behaves exactly like no body at all
        var empty = createVersion(ctx, "empty-body");
        releaseVersion(ctx, ctx.token(), empty, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseDate").value(LocalDate.now(ZoneOffset.UTC).toString()));
    }

    // ==================================================== moveUnresolvedToVersionId

    /**
     * Seven shapes in one release, because the move's three independent scopes (role,
     * resolution, collision) only interact wrongly in combination.
     */
    @Test
    void theMoveRepointsOnlyUnresolvedFixLinksAndCollapsesCollisions() throws Exception {
        var ctx = newProject();
        var shipping = createVersion(ctx, "2.4.0");
        var next = createVersion(ctx, "2.5.0");

        var openFix = createIssue(ctx, "open fix", fixVersionIdsJson(shipping));
        var doneFix = createIssue(ctx, "done fix", fixVersionIdsJson(shipping));
        var collision = createIssue(ctx, "open, already on target",
                fixVersionIdsJson(shipping, next));
        var doneCollision = createIssue(ctx, "done, already on target",
                fixVersionIdsJson(shipping, next));
        var affectsOnly = createIssue(ctx, "affects the shipping one",
                affectsVersionIdsJson(shipping));
        var crossRole = createIssue(ctx, "fix shipping, affects next",
                fixVersionIdsJson(shipping), affectsVersionIdsJson(next));
        var untouched = createIssue(ctx, "already on next only", fixVersionIdsJson(next));
        markDone(ctx, doneFix.get("number").asLong());
        markDone(ctx, doneCollision.get("number").asLong());

        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + next + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));

        // unresolved FIX → moved
        assert fixVersionNames(getIssue(ctx, openFix.get("number").asLong())).equals(List.of("2.5.0"));
        // DONE FIX → stays on the released version, which is the point of the move
        assert fixVersionNames(getIssue(ctx, doneFix.get("number").asLong())).equals(List.of("2.4.0"));
        // unresolved collision → collapsed to ONE row, no UNIQUE violation (§6.4 T2)
        assert fixVersionNames(getIssue(ctx, collision.get("number").asLong()))
                .equals(List.of("2.5.0"));
        // DONE collision → untouched, so it legitimately keeps BOTH links
        assert fixVersionNames(getIssue(ctx, doneCollision.get("number").asLong()))
                .equals(List.of("2.4.0", "2.5.0"));
        // an AFFECTS link is a statement about where a defect exists — never moved
        var affects = getIssue(ctx, affectsOnly.get("number").asLong());
        assert affectsVersionNames(affects).equals(List.of("2.4.0")) : affects;
        assert fixVersionNames(affects).isEmpty();
        // the cross-role issue moves only its FIX link; the AFFECTS one on the target stays
        var cross = getIssue(ctx, crossRole.get("number").asLong());
        assert fixVersionNames(cross).equals(List.of("2.5.0")) : cross;
        assert affectsVersionNames(cross).equals(List.of("2.5.0")) : cross;
        // an issue that was never on the source is not touched at all
        assert fixVersionNames(getIssue(ctx, untouched.get("number").asLong()))
                .equals(List.of("2.5.0"));

        // The roll-ups agree with the links: 2.4.0 keeps its two DONE fix issues.
        var source = versionNode(ctx, shipping);
        assert source.get("issueCount").asInt() == 2 : source;
        assert source.get("doneIssueCount").asInt() == 2 : source;
        assert source.get("affectsIssueCount").asInt() == 1 : source;
        var target = versionNode(ctx, next);
        assert target.get("issueCount").asInt() == 5 : target;   // open, collision, cross, untouched, doneCollision
        assert target.get("doneIssueCount").asInt() == 1 : target;
        assert target.get("affectsIssueCount").asInt() == 1 : target;
    }

    /**
     * The target validator (§6.4 T5). A <em>released</em> target is rejected here —
     * moving open work onto an already-shipped release is never what the operator
     * meant — which is exactly where this differs from the delete-with-remap target
     * (see {@code VersionApiTest}). Every rejection must leave the source
     * <strong>unreleased</strong>: validation runs before the conditional UPDATE.
     */
    @Test
    void anIllegalMoveTargetIs422AndAbortsTheWholeRelease() throws Exception {
        var ctx = newProject();
        var shipping = createVersion(ctx, "2.4.0");
        var archived = createVersion(ctx, "0.9.0");
        var shipped = createVersion(ctx, "2.3.1");
        var sibling = siblingProject(ctx);
        var foreign = createVersion(sibling, "sibling 1.0");
        var open = createIssue(ctx, "still open", fixVersionIdsJson(shipping));
        archiveVersion(ctx, ctx.token(), archived).andExpect(status().isOk());
        releaseVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());

        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + shipping + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("the version being released")));
        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + archived + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("is archived")));
        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + shipped + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("already released")));
        // A foreign id is an invalid FIELD VALUE, not a 404 — it discloses nothing.
        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + foreign + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Unknown version")));
        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Unknown version")));

        // Nothing was released and nothing was moved by ANY of the five rejects.
        assert !versionNode(ctx, shipping).get("released").asBoolean()
                : "validation must run before the conditional UPDATE";
        assert fixVersionNames(getIssue(ctx, open.get("number").asLong())).equals(List.of("2.4.0"));
        assert versionNode(ctx, shipping).get("issueCount").asInt() == 1;
        // The sibling project's version was never touched by the rejected requests.
        assert versionNames(listVersions(sibling, sibling.token(), null)).equals(List.of("sibling 1.0"));

        // The legal target still works afterwards, so the 422s were about the target.
        var next = createVersion(ctx, "2.5.0");
        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + next + "\"}")
                .andExpect(status().isOk());
        assert fixVersionNames(getIssue(ctx, open.get("number").asLong())).equals(List.of("2.5.0"));
    }

    /** Releasing with open work is deliberately ALLOWED — the move is opt-in. */
    @Test
    void releasingWithUnresolvedWorkIsAllowedAndLeavesTheLinksAlone() throws Exception {
        var ctx = newProject();
        var shipping = createVersion(ctx, "2.4.0");
        var open = createIssue(ctx, "still open", fixVersionIdsJson(shipping));

        releaseVersion(ctx, ctx.token(), shipping)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.issueCount").value(1))
                .andExpect(jsonPath("$.doneIssueCount").value(0));
        assert fixVersionNames(getIssue(ctx, open.get("number").asLong())).equals(List.of("2.4.0"));
    }

    /** A released version stays a legal FIX/AFFECTS target for new issues (§6.4). */
    @Test
    void aReleasedVersionCanStillBeLinkedToNewIssues() throws Exception {
        var ctx = newProject();
        var shipped = createVersion(ctx, "2.3.1");
        releaseVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());

        var issue = createIssue(ctx, "hotfix", fixVersionIdsJson(shipped));
        assert fixVersionNames(issue).equals(List.of("2.3.1"));
        // Only ARCHIVED is a barrier to linking — released is not.
        archiveVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());
        postIssue(ctx, ctx.token(), "too late", fixVersionIdsJson(shipped))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("is archived")));
    }
}
