package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code app.classification.max-version-links-per-issue} (proposal §3.9, §6.6) — the
 * per-issue link bound, overridden to <strong>2</strong> here (default 20) so the
 * boundary is cheap to express. The semantics are identical at any value.
 *
 * <p><strong>The point of this class is that the budget is PER LINK TYPE.</strong>
 * "Ships in 2.4.0 and 2.3.1" and "affects three earlier releases" are unrelated
 * statements about the same issue, so an issue may carry a full set of fix versions
 * AND a full set of affects versions. One shared {@code issue_versions} table and one
 * shared property make collapsing the two into a single shared budget a one-line
 * simplification that nothing else would catch: every existing test uses one role at a
 * time, so a shared budget would still pass all of them. Asserted in both directions
 * (fix first, then affects; and the reverse) and on both the create and the update
 * path, since {@code resolveForIssue} is called once per role from each.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.classification.max-version-links-per-issue=2"
})
@AutoConfigureMockMvc
class VersionLinkCapTest extends VersionTestBase {

    @Test
    void anIssueMayCarryAFullFixBudgetAndAFullAffectsBudgetAtTheSameTime() throws Exception {
        var ctx = newProject();
        var a = createVersion(ctx, "1.0.0");
        var b = createVersion(ctx, "1.1.0");
        var c = createVersion(ctx, "2.0.0");
        var d = createVersion(ctx, "2.1.0");

        // 2 fix + 2 affects on ONE issue = 4 rows, and every one of them is legal.
        var issue = createIssue(ctx, "both budgets full",
                fixVersionIdsJson(a, b), affectsVersionIdsJson(c, d));
        var node = getIssue(ctx, issue.get("number").asLong());
        assert fixVersionNames(node).equals(List.of("1.0.0", "1.1.0")) : node;
        assert affectsVersionNames(node).equals(List.of("2.0.0", "2.1.0")) : node;

        // The same version in BOTH roles doesn't consume the other role's budget either
        // (link_type is part of the unique key — §6.4).
        var overlap = createIssue(ctx, "same version, both roles",
                fixVersionIdsJson(a, b), affectsVersionIdsJson(a, b));
        var overlapNode = getIssue(ctx, overlap.get("number").asLong());
        assert fixVersionNames(overlapNode).equals(List.of("1.0.0", "1.1.0")) : overlapNode;
        assert affectsVersionNames(overlapNode).equals(List.of("1.0.0", "1.1.0")) : overlapNode;
    }

    @Test
    void oneMoreThanTheCapIs422PerRoleOnCreate() throws Exception {
        var ctx = newProject();
        var a = createVersion(ctx, "1.0.0");
        var b = createVersion(ctx, "1.1.0");
        var c = createVersion(ctx, "2.0.0");

        postIssue(ctx, ctx.token(), "too many fix", fixVersionIdsJson(a, b, c))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("At most 2 fix versions per issue")));
        postIssue(ctx, ctx.token(), "too many affects", affectsVersionIdsJson(a, b, c))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail",
                        containsString("At most 2 affects versions per issue")));
        // A full fix budget does NOT make a legal affects set illegal — the failure above
        // must be about the over-full role only.
        postIssue(ctx, ctx.token(), "legal", fixVersionIdsJson(a, b), affectsVersionIdsJson(a, b, c))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail",
                        containsString("At most 2 affects versions per issue")));

        // Nothing was written by any of the rejects.
        assert board(ctx, null).get("issues").size() == 0;
        // Duplicate ids collapse before the count, so 3 ids naming 2 versions is fine.
        createIssue(ctx, "deduplicated", fixVersionIdsJson(a, b, a));
    }

    @Test
    void oneMoreThanTheCapIs422PerRoleOnUpdateAndTheOtherRoleIsUntouched() throws Exception {
        var ctx = newProject();
        var a = createVersion(ctx, "1.0.0");
        var b = createVersion(ctx, "1.1.0");
        var c = createVersion(ctx, "2.0.0");
        var issue = createIssue(ctx, "carrier", fixVersionIdsJson(a, b), affectsVersionIdsJson(a, b));
        long number = issue.get("number").asLong();

        patchIssue(ctx, ctx.token(), number, "{" + fixVersionIdsJson(a, b, c) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("At most 2 fix versions per issue")));
        patchIssue(ctx, ctx.token(), number, "{" + affectsVersionIdsJson(a, b, c) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail",
                        containsString("At most 2 affects versions per issue")));

        // Both roles are exactly as they were — a rejected PATCH is a full no-op.
        var node = getIssue(ctx, number);
        assert fixVersionNames(node).equals(List.of("1.0.0", "1.1.0")) : node;
        assert affectsVersionNames(node).equals(List.of("1.0.0", "1.1.0")) : node;

        // Replacing one role at its full budget while the other is also full stays legal,
        // which is the "independent budgets" rule stated the other way round.
        patchIssue(ctx, ctx.token(), number, "{" + fixVersionIdsJson(b, c) + "}")
                .andExpect(status().isOk());
        var after = getIssue(ctx, number);
        assert fixVersionNames(after).equals(List.of("1.1.0", "2.0.0")) : after;
        assert affectsVersionNames(after).equals(List.of("1.0.0", "1.1.0")) : after;
    }
}
