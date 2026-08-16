package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code app.classification.max-labels-per-issue} (proposal §3.9) — a DoS guard with
 * identical defaults in DC and Cloud (20), overridden to <strong>2</strong> here so the
 * cap is exercised with three labels instead of twenty-one. Same
 * {@code @SpringBootTest(properties = …)} style as {@code BoardCapTest}.
 *
 * <p>The cap bites in two places: the issue payload (422 — a business-rule rejection
 * of an otherwise well-formed request) and the repeatable {@code ?labelId=} filter
 * (400 — a malformed query parameter, and an unbounded list would churn the query-plan
 * cache).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.classification.max-labels-per-issue=2"
})
@AutoConfigureMockMvc
class LabelPerIssueCapTest extends LabelTestBase {

    @Test
    void moreLabelsThanTheCapIs422OnCreateAndOnPatch() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "a");
        var b = createLabel(ctx, "b");
        var c = createLabel(ctx, "c");

        postIssue(ctx, ctx.token(), "three labels", labelIdsJson(a, b, c))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("At most 2 labels per issue")));

        var issue = createIssue(ctx, "two labels", labelIdsJson(a, b));
        assert labelNames(issue).equals(List.of("a", "b")) : "exactly at the cap is fine";

        patchIssue(ctx, ctx.token(), issue.get("number").asLong(), "{" + labelIdsJson(a, b, c) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("At most 2 labels per issue")));
        assert labelNames(getIssue(ctx, issue.get("number").asLong())).equals(List.of("a", "b"))
                : "the rejected PATCH must not have partially applied";

        // De-duplication happens BEFORE the cap check: 3 ids that are really 2 pass.
        patchIssue(ctx, ctx.token(), issue.get("number").asLong(), "{" + labelIdsJson(a, b, b) + "}")
                .andExpect(status().isOk());
    }

    @Test
    void moreLabelIdFilterValuesThanTheCapIs400() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "a");
        var b = createLabel(ctx, "b");
        var c = createLabel(ctx, "c");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(ctx.issuesBase() + "?labelId=" + a + "&labelId=" + b + "&labelId=" + c)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("At most 2 labelId filter values")));

        // at the cap it still works
        assert board(ctx, "?labelId=" + a + "&labelId=" + b).get("issues").size() == 0;
    }
}
