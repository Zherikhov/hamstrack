package com.hamstrack.issue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HD-30 on the issue side (proposal §3.6, §3.7, §4.4): {@code labelIds} as a
 * full-replacement set, the history/{@code @Version} contract, the archived-label
 * asymmetry, and the server-side {@code ?labelId=&labelMatch=} board/backlog filter.
 *
 * <p>The asymmetry in {@link #anArchivedLabelCannotBeAddedButAnIssueCarryingOneStaysEditable()}
 * is deliberate (builder's round-1 note): archiving means "stop using this", not
 * "freeze every issue that ever used it" — so it blocks <em>additions</em> only, and
 * an issue already carrying the archived label can still be edited (including
 * re-sending the same set) without a 422 it cannot possibly satisfy.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class LabelOnIssueTest extends LabelTestBase {

    // ==================================================== create

    @Test
    void createWithLabelIdsAttachesThemAndWritesNoHistory() throws Exception {
        var ctx = newProject();
        var bug = createLabel(ctx, "bug");
        var ui = createLabel(ctx, "ui");

        var issue = createIssue(ctx, "with labels", labelIdsJson(ui, bug));
        assertThat(labelNames(issue))
                .as(() -> "labels come back ordered by lower(name), got " + labelNames(issue))
                .isEqualTo(List.of("bug", "ui"));
        // duplicates in the payload are de-duped, not a 400/409
        var dupes = createIssue(ctx, "dupes", labelIdsJson(bug, bug, bug));
        assertThat(labelNames(dupes))
                .as("duplicates in the payload are de-duped into one row, not answered with a 400 or a 409")
                .isEqualTo(List.of("bug"));

        // create-time values write no history (consistent with custom fields)
        assertThat(historyFields(ctx, issue.get("number").asLong()))
                .as("no history row for labels set at creation time")
                .isEmpty();
    }

    @Test
    void unknownLabelIdOnCreateIs422() throws Exception {
        var ctx = newProject();
        postIssue(ctx, ctx.token(), "x", labelIdsJson(java.util.UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("Unknown label")));
    }

    // ==================================================== full-replacement semantics

    @Test
    void labelIdsIsAFullReplacementSetPresentReplacesEmptyClearsAbsentLeavesAlone() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "alpha");
        var b = createLabel(ctx, "beta");
        var c = createLabel(ctx, "gamma");
        var issue = createIssue(ctx, "subject", labelIdsJson(a, b));
        long n = issue.get("number").asLong();

        // present → replaces wholesale (beta dropped, gamma added)
        var replaced = json.readTree(patchIssue(ctx, ctx.token(), n, "{" + labelIdsJson(a, c) + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(labelNames(replaced)).as(() -> String.valueOf(labelNames(replaced))).isEqualTo(List.of("alpha", "gamma"));

        // absent → untouched (a title-only PATCH must not clear labels)
        var titleOnly = json.readTree(patchIssue(ctx, ctx.token(), n, "{\"title\":\"renamed\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(labelNames(titleOnly))
                .as(() -> String.valueOf(labelNames(titleOnly)))
                .isEqualTo(List.of("alpha", "gamma"));

        // [] → clears
        var cleared = json.readTree(patchIssue(ctx, ctx.token(), n, "{\"labelIds\":[]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(labelNames(cleared)).as(() -> String.valueOf(labelNames(cleared))).isEmpty();
        assertThat(labelNames(getIssue(ctx, n))).as("and it stuck").isEmpty();
    }

    @Test
    void aRealChangeWritesExactlyOneLabelsHistoryRowAndANoOpWritesNone() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "alpha");
        var b = createLabel(ctx, "beta");
        var issue = createIssue(ctx, "subject", labelIdsJson(a));
        long n = issue.get("number").asLong();

        // add beta AND remove alpha in one PATCH → ONE row, comma-joined display names
        patchIssue(ctx, ctx.token(), n, "{" + labelIdsJson(b) + "}").andExpect(status().isOk());
        var rows = historyRows(ctx, n, "labels");
        assertThat(rows).as("add+remove must produce exactly one history row, got " + rows).hasSize(1);
        assertThat(rows.get(0).get("oldValue").asText()).as(() -> String.valueOf(rows.get(0))).isEqualTo("alpha");
        assertThat(rows.get(0).get("newValue").asText()).as(() -> String.valueOf(rows.get(0))).isEqualTo("beta");

        // re-sending the SAME set is a no-op → no second row
        patchIssue(ctx, ctx.token(), n, "{" + labelIdsJson(b) + "}").andExpect(status().isOk());
        assertThat(historyRows(ctx, n, "labels"))
                .as("an unchanged set must not write history, got " + historyRows(ctx, n, "labels"))
                .hasSize(1);

        // clearing writes a second row, with a null newValue (empty set → null)
        patchIssue(ctx, ctx.token(), n, "{\"labelIds\":[]}").andExpect(status().isOk());
        var afterClear = historyRows(ctx, n, "labels");
        assertThat(afterClear).as("%s", afterClear).hasSize(2);
        assertThat(afterClear.stream().anyMatch(r -> r.get("newValue").isNull()
                        && "beta".equals(r.get("oldValue").asText())))
                .withFailMessage("clearing all labels records oldValue = 'beta', newValue = null, got " + afterClear)
                .isTrue();
    }

    @Test
    void aLabelOnlyPatchBumpsVersionByExactlyOneAndAStaleVersionIs409() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "alpha");
        var issue = createIssue(ctx, "subject");
        long n = issue.get("number").asLong();
        int v0 = issue.get("version").asInt();

        var patched = json.readTree(patchIssue(ctx, ctx.token(), n,
                        "{\"version\":" + v0 + "," + labelIdsJson(a) + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(patched.get("version").asInt())
                .as(() -> "a label-only PATCH must bump @Version by exactly 1, got " + patched.get("version"))
                .isEqualTo(v0 + 1);

        // replaying the old version is a stale write → 409
        patchIssue(ctx, ctx.token(), n, "{\"version\":" + v0 + ",\"labelIds\":[]}")
                .andExpect(status().isConflict());

        // A NO-OP label PATCH leaves the version alone. The spec's §4.4 aside ("the
        // issue is still saved, so the PATCH already bumps @Version") does not survive
        // contact with Hibernate: a set equal to the current one dirties nothing, so no
        // UPDATE is emitted and @Version stays put. Locking in the code's behavior —
        // it is also the friendlier one (a redundant save can't invalidate a client's
        // optimistic-lock token).
        var noop = json.readTree(patchIssue(ctx, ctx.token(), n, "{" + labelIdsJson(a) + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(noop.get("version").asInt()).as(() -> String.valueOf(noop.get("version"))).isEqualTo(v0 + 1);
        // …and a real change from there bumps it once more.
        var real = json.readTree(patchIssue(ctx, ctx.token(), n, "{\"labelIds\":[]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(real.get("version").asInt()).as(() -> String.valueOf(real.get("version"))).isEqualTo(v0 + 2);
    }

    // ==================================================== archived asymmetry

    @Test
    void anArchivedLabelCannotBeAddedButAnIssueCarryingOneStaysEditable() throws Exception {
        var ctx = newProject();
        var live = createLabel(ctx, "live");
        var stale = createLabel(ctx, "stale");
        var issue = createIssue(ctx, "carrier", labelIdsJson(stale));
        long n = issue.get("number").asLong();
        archiveLabel(ctx, ctx.token(), stale).andExpect(status().isOk());

        // adding it somewhere else → 422
        postIssue(ctx, ctx.token(), "new", labelIdsJson(stale))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("archived")));

        // but the carrier stays editable: re-sending its own set (which INCLUDES the
        // archived label) is a no-op, not an addition → 200
        patchIssue(ctx, ctx.token(), n, "{\"title\":\"still editable\"," + labelIdsJson(stale) + "}")
                .andExpect(status().isOk());
        // …and it may even gain another (live) label while keeping the archived one
        patchIssue(ctx, ctx.token(), n, "{" + labelIdsJson(stale, live) + "}")
                .andExpect(status().isOk());
        assertThat(labelNames(getIssue(ctx, n)))
                .as("an issue carrying an archived label may still gain a live one while keeping the archived one")
                .isEqualTo(List.of("live", "stale"));
        // dropping the archived one is allowed, re-adding it afterwards is not
        patchIssue(ctx, ctx.token(), n, "{" + labelIdsJson(live) + "}").andExpect(status().isOk());
        patchIssue(ctx, ctx.token(), n, "{" + labelIdsJson(live, stale) + "}")
                .andExpect(status().isUnprocessableContent());
    }

    // ==================================================== board / backlog filter

    @Test
    void labelFilterDefaultsToAnyAndMatchAllNarrows() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "alpha");
        var b = createLabel(ctx, "beta");
        createIssue(ctx, "only-a", labelIdsJson(a));
        createIssue(ctx, "only-b", labelIdsJson(b));
        createIssue(ctx, "both", labelIdsJson(a, b));
        createIssue(ctx, "none");

        // no filter → everything (and no empty-IN crash)
        assertThat(titles(board(ctx, null)))
                .as("no label filter means every issue, and no empty-IN crash on the way")
                .isEqualTo(Set.of("only-a", "only-b", "both", "none"));

        // default = any (OR)
        var any = titles(board(ctx, "?labelId=" + a + "&labelId=" + b));
        assertThat(any).as("%s", any).isEqualTo(Set.of("only-a", "only-b", "both"));
        assertThat(titles(board(ctx, "?labelId=" + a + "&labelId=" + b + "&labelMatch=any")))
                .as("labelMatch=any is the default spelled out, so it must return what the default returned")
                .isEqualTo(any);

        // all (AND) → strictly narrower
        var all = titles(board(ctx, "?labelId=" + a + "&labelId=" + b + "&labelMatch=all"));
        assertThat(all).as("%s", all).isEqualTo(Set.of("both"));

        // one label: any and all agree
        assertThat(titles(board(ctx, "?labelId=" + a)))
                .as("with ONE label there is nothing for any and all to disagree about")
                .isEqualTo(Set.of("only-a", "both"));
        assertThat(titles(board(ctx, "?labelId=" + a + "&labelMatch=all")))
                .as("…so matchAll over a single label returns the same issues as the default")
                .isEqualTo(Set.of("only-a", "both"));
    }

    @Test
    void anUnknownLabelMatchIs400NeverASilentFallbackToAny() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "alpha");
        var b = createLabel(ctx, "beta");
        createIssue(ctx, "only-a", labelIdsJson(a));
        createIssue(ctx, "both", labelIdsJson(a, b));
        var q = "?labelId=" + a + "&labelId=" + b + "&labelMatch=";

        // These used to fall through to the BROADER OR result — a caller asking to
        // narrow silently got a wider list. All must be rejected outright.
        // (Surrounding whitespace is NOT in this list: LabelMatch.parse trims first,
        // so "all " is a legal ALL — deliberate, and the code is the contract.)
        for (var bad : List.of("every", "and", "ANY!", "0", "true", "or")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get(ctx.issuesBase() + q + bad)
                            .header("Authorization", "Bearer " + ctx.token()))
                    .andExpect(status().isBadRequest());
        }
        // case-insensitivity IS supported for the two legal values
        assertThat(titles(board(ctx, q + "ALL")))
                .as("case-insensitivity IS supported for the two legal values")
                .isEqualTo(Set.of("both"));
        assertThat(titles(board(ctx, q + "Any")))
                .as("…for both of them, and each keeps its own meaning")
                .isEqualTo(Set.of("only-a", "both"));
    }

    @Test
    void labelFilterComposesWithStatusAssigneeAndPriorityAndWorksOnTheBacklogPage() throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "alpha");
        var inProgress = ctx.statusId("In Progress");
        createIssue(ctx, "a+progress", labelIdsJson(a), "\"statusId\":\"" + inProgress + "\"");
        createIssue(ctx, "a+todo", labelIdsJson(a));
        createIssue(ctx, "plain+progress", "\"statusId\":\"" + inProgress + "\"");

        var combined = titles(board(ctx, "?labelId=" + a + "&statusId=" + inProgress));
        assertThat(combined).as("label AND status, got " + combined).isEqualTo(Set.of("a+progress"));

        var assigned = titles(board(ctx, "?labelId=" + a + "&assigneeId=" + ctx.owner().getId()));
        assertThat(assigned).as("nothing is assigned, so the AND must be empty, got " + assigned).isEmpty();

        // the paged (backlog) variant applies the same filter
        var page = json.readTree(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get(ctx.issuesBase() + "?size=50&labelId=" + a)
                                .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.get("totalElements").asInt())
                .as(() -> "backlog filter, got " + page.get("totalElements"))
                .isEqualTo(2);
        for (var i : page.get("content")) {
            assertThat(labelNames(i)).as("every returned row carries the label").contains("alpha");
        }
    }

    // ==================================================== helpers

    private List<JsonNode> historyRows(Ctx ctx, long number, String field) throws Exception {
        var out = new java.util.ArrayList<JsonNode>();
        for (var row : issueHistory(ctx, number).get("content")) {
            if (row.get("field").asText().equals(field)) out.add(row);
        }
        return out;   // order-agnostic: callers assert on content, not position
    }

    private List<String> historyFields(Ctx ctx, long number) throws Exception {
        var out = new java.util.ArrayList<String>();
        for (var row : issueHistory(ctx, number).get("content")) out.add(row.get("field").asText());
        return out;
    }
}
