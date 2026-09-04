package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(releasedAt.isAfter(before)).withFailMessage("releasedAt must be stamped, got " + releasedAt).isTrue();

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

        assertThat(versionNode(ctx, id).get("released").asBoolean())
                .withFailMessage("a refused unrelease leaves the version in the state it already had")
                .isFalse();
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

        assertThat(fixVersionNames(getIssue(ctx, open.get("number").asLong())))
                .as("the rejected second release must not have moved anything")
                .isEqualTo(List.of("2.4.0"));
        assertThat(versionNode(ctx, next).get("issueCount").asInt())
                .as("the rejected second release must not have run its move — the target gained nothing")
                .isEqualTo(0);
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
        assertThat(fixVersionNames(getIssue(ctx, openFix.get("number").asLong())))
                .as("an unresolved fix link is what the move exists to repoint")
                .isEqualTo(List.of("2.5.0"));
        // DONE FIX → stays on the released version, which is the point of the move
        assertThat(fixVersionNames(getIssue(ctx, doneFix.get("number").asLong())))
                .as("a DONE fix link stays on the released version — that is the point of the move")
                .isEqualTo(List.of("2.4.0"));
        // unresolved collision → collapsed to ONE row, no UNIQUE violation (§6.4 T2)
        assertThat(fixVersionNames(getIssue(ctx, collision.get("number").asLong())))
                .as("an unresolved collision collapses to ONE row instead of a UNIQUE violation")
                .isEqualTo(List.of("2.5.0"));
        // DONE collision → untouched, so it legitimately keeps BOTH links
        assertThat(fixVersionNames(getIssue(ctx, doneCollision.get("number").asLong())))
                .as("a DONE collision is untouched, so it legitimately keeps BOTH links")
                .isEqualTo(List.of("2.4.0", "2.5.0"));
        // an AFFECTS link is a statement about where a defect exists — never moved
        var affects = getIssue(ctx, affectsOnly.get("number").asLong());
        assertThat(affectsVersionNames(affects)).as("%s", affects).isEqualTo(List.of("2.4.0"));
        assertThat(fixVersionNames(affects))
                .as("an affects link is a statement about where a defect exists — the move must not turn it into a fix link")
                .isEmpty();
        // the cross-role issue moves only its FIX link; the AFFECTS one on the target stays
        var cross = getIssue(ctx, crossRole.get("number").asLong());
        assertThat(fixVersionNames(cross)).as("%s", cross).isEqualTo(List.of("2.5.0"));
        assertThat(affectsVersionNames(cross)).as("%s", cross).isEqualTo(List.of("2.5.0"));
        // an issue that was never on the source is not touched at all
        assertThat(fixVersionNames(getIssue(ctx, untouched.get("number").asLong())))
                .as("an issue that was never on the source version is not touched at all")
                .isEqualTo(List.of("2.5.0"));

        // The roll-ups agree with the links: 2.4.0 keeps its two DONE fix issues.
        var source = versionNode(ctx, shipping);
        assertThat(source.get("issueCount").asInt()).as("%s", source).isEqualTo(2);
        assertThat(source.get("doneIssueCount").asInt()).as("%s", source).isEqualTo(2);
        assertThat(source.get("affectsIssueCount").asInt()).as("%s", source).isEqualTo(1);
        var target = versionNode(ctx, next);
        assertThat(target.get("issueCount").asInt()).as("%s", target).isEqualTo(5);   // open, collision, cross, untouched, doneCollision
        assertThat(target.get("doneIssueCount").asInt()).as("%s", target).isEqualTo(1);
        assertThat(target.get("affectsIssueCount").asInt()).as("%s", target).isEqualTo(1);
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
        assertThat(versionNode(ctx, shipping).get("released").asBoolean())
                .withFailMessage("validation must run before the conditional UPDATE")
                .isFalse();
        assertThat(fixVersionNames(getIssue(ctx, open.get("number").asLong())))
                .as("no reject moved a link: validation runs before the move, not alongside it")
                .isEqualTo(List.of("2.4.0"));
        assertThat(versionNode(ctx, shipping).get("issueCount").asInt())
                .as("…and the source version's progress counter is unchanged by the rejects")
                .isEqualTo(1);
        // The sibling project's version was never touched by the rejected requests.
        assertThat(versionNames(listVersions(sibling, sibling.token(), null)))
                .as("the sibling project's version was never touched by the rejected requests")
                .isEqualTo(List.of("sibling 1.0"));

        // The legal target still works afterwards, so the 422s were about the target.
        var next = createVersion(ctx, "2.5.0");
        releaseVersion(ctx, ctx.token(), shipping,
                "{\"moveUnresolvedToVersionId\":\"" + next + "\"}")
                .andExpect(status().isOk());
        assertThat(fixVersionNames(getIssue(ctx, open.get("number").asLong())))
                .as("the legal request that follows the rejects still performs the move")
                .isEqualTo(List.of("2.5.0"));
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
        assertThat(fixVersionNames(getIssue(ctx, open.get("number").asLong())))
                .as("releasing with unresolved work is allowed and leaves the links exactly where they were")
                .isEqualTo(List.of("2.4.0"));
    }

    /** A released version stays a legal FIX/AFFECTS target for new issues (§6.4). */
    @Test
    void aReleasedVersionCanStillBeLinkedToNewIssues() throws Exception {
        var ctx = newProject();
        var shipped = createVersion(ctx, "2.3.1");
        releaseVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());

        var issue = createIssue(ctx, "hotfix", fixVersionIdsJson(shipped));
        assertThat(fixVersionNames(issue))
                .as("a released version is still a legal link target — releasing is not archiving")
                .isEqualTo(List.of("2.3.1"));
        // Only ARCHIVED is a barrier to linking — released is not.
        archiveVersion(ctx, ctx.token(), shipped).andExpect(status().isOk());
        postIssue(ctx, ctx.token(), "too late", fixVersionIdsJson(shipped))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("is archived")));
    }
}
