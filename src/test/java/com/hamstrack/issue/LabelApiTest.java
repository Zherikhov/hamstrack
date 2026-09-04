package com.hamstrack.issue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HD-30 label catalog: create / normalize / rename / recolor / archive / merge /
 * delete, plus the permission matrix (labels-components-versions-proposal §4.1–§4.4,
 * acceptance list §4.6).
 *
 * <p>Two behaviors here exist because a review round found the bug first, and both
 * would be silent 500s if they regressed:
 *
 * <ul>
 *   <li>{@link #mergeCollapsesTwoSourcesThatCoOccurOnOneIssue()} — an issue carrying
 *       BOTH merged sources used to produce two rows re-pointed at the same
 *       {@code (issue, target)} pair, violating
 *       {@code UNIQUE (issue_id, label_id)};</li>
 *   <li>{@link #renamingIntoATakenNameIs409WithExistingIdNot500()} — the rename path
 *       now catches {@code DataIntegrityViolationException} and answers 409 +
 *       {@code existingId}, the same shape create() uses.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class LabelApiTest extends LabelTestBase {

    // ==================================================== create / normalize / validate

    @Test
    void anyWorkspaceMemberCanCreateALabel() throws Exception {
        var ctx = newProject();
        var plain = addMember(ctx, "MEMBER");

        postLabel(ctx, plain.token(), "{\"name\":\"self-serve\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("self-serve"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.createdById").value(plain.user().getId().toString()))
                // no color given → a deterministic palette swatch, never null
                .andExpect(jsonPath("$.color", matchesPattern("^#[0-9A-Fa-f]{6}$")));
    }

    @Test
    void nameIsNormalizedTrimmedAndWhitespaceCollapsed() throws Exception {
        var ctx = newProject();
        postLabel(ctx, ctx.token(), "{\"name\":\"  tech   debt  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("tech debt"));   // spaces allowed, collapsed
    }

    @Test
    void blankTooLongAndBadColorAre400() throws Exception {
        var ctx = newProject();
        postLabel(ctx, ctx.token(), "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        postLabel(ctx, ctx.token(), "{\"name\":\"" + "a".repeat(61) + "\"}")
                .andExpect(status().isBadRequest());
        postLabel(ctx, ctx.token(), "{\"name\":\"ok\",\"color\":\"blue\"}")
                .andExpect(status().isBadRequest());
        postLabel(ctx, ctx.token(), "{\"name\":\"ok\",\"color\":\"#12345\"}")
                .andExpect(status().isBadRequest());
        // exactly 60 is fine (the boundary, not one past it)
        postLabel(ctx, ctx.token(), "{\"name\":\"" + "a".repeat(60) + "\",\"color\":\"#0EA5A4FF\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateNameIsCaseInsensitive409WithExistingIdButAllowedInAnotherWorkspace() throws Exception {
        var a = newProject();
        var b = newProject();
        var first = createLabel(a, "Tech Debt");

        postLabel(a, a.token(), "{\"name\":\"tech debt\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(first.toString()));

        // The same name in a different workspace is a different namespace → 201.
        postLabel(b, b.token(), "{\"name\":\"tech debt\"}")
                .andExpect(status().isCreated());
        // …and the first workspace kept the ORIGINAL casing.
        assertThat(listLabels(a, a.token(), null).get(0).get("name").asText())
                .as("the first workspace kept the ORIGINAL casing — the duplicate refusal must not rewrite it")
                .isEqualTo("Tech Debt");
    }

    @Test
    void reusingAnArchivedLabelsNameIs409() throws Exception {
        var ctx = newProject();
        var id = createLabel(ctx, "retired");
        archiveLabel(ctx, ctx.token(), id).andExpect(status().isOk());

        postLabel(ctx, ctx.token(), "{\"name\":\"RETIRED\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(id.toString()))
                .andExpect(jsonPath("$.detail", containsString("archived")));
    }

    // ==================================================== rename conflict (bug #2)

    /**
     * The deterministic half of the "rename race" fix: the target name is already
     * taken, so the pre-check fires and must answer <strong>409 + existingId</strong>.
     * Before the fix round the flush-time variant of this escaped as a 500; the
     * service now catches {@code DataIntegrityViolationException} and re-reads the
     * winner on a fresh transaction. (The genuine race — two renames interleaved
     * between pre-check and flush — is not reproducible deterministically here.)
     */
    @Test
    void renamingIntoATakenNameIs409WithExistingIdNot500() throws Exception {
        var ctx = newProject();
        var alpha = createLabel(ctx, "alpha");
        var beta = createLabel(ctx, "beta");

        patchLabel(ctx, ctx.token(), beta, "{\"name\":\"ALPHA\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(alpha.toString()));

        // Renaming into an ARCHIVED label's name is the same 409 (archived rows keep
        // their unique slot) — the case most likely to be forgotten.
        var gamma = createLabel(ctx, "gamma");
        archiveLabel(ctx, ctx.token(), gamma).andExpect(status().isOk());
        patchLabel(ctx, ctx.token(), beta, "{\"name\":\"Gamma\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingId").value(gamma.toString()))
                .andExpect(jsonPath("$.detail", containsString("archived")));

        // beta is unharmed by either failed rename.
        assertThat(names(listLabels(ctx, ctx.token(), "?includeArchived=true")))
                .as("the label that was renamed INTO is unharmed by either failed rename")
                .contains("beta");
    }

    @Test
    void renamingToADifferentCasingOfItsOwnNameIsAllowed() throws Exception {
        var ctx = newProject();
        var id = createLabel(ctx, "tech debt");
        patchLabel(ctx, ctx.token(), id, "{\"name\":\"Tech Debt\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tech Debt"));
    }

    // ==================================================== permission matrix

    @Test
    void renameAndRecolorAllowedForAdminAndCreatorButNotAnotherPlainMember() throws Exception {
        var ctx = newProject();
        var creator = addMember(ctx, "MEMBER");
        var other = addMember(ctx, "MEMBER");
        var admin = addMember(ctx, "ADMIN");
        var id = createLabel(ctx, creator.token(), "owned-by-creator");

        // the creator (a plain MEMBER) may rename/recolor their own label
        patchLabel(ctx, creator.token(), id, "{\"color\":\"#F79009\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("#F79009"));
        // an ADMIN may too
        patchLabel(ctx, admin.token(), id, "{\"name\":\"renamed-by-admin\"}")
                .andExpect(status().isOk());
        // the workspace OWNER may too
        patchLabel(ctx, ctx.token(), id, "{\"description\":\"by owner\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("by owner"));
        // another plain MEMBER may NOT — 403 (a member WITHOUT the role, not a 404)
        patchLabel(ctx, other.token(), id, "{\"name\":\"hijack\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void archiveUnarchiveMergeAndDeleteAreAdminOnly() throws Exception {
        var ctx = newProject();
        var plain = addMember(ctx, "MEMBER");
        // Created BY the plain member — creator rights do not extend to curation.
        var mine = createLabel(ctx, plain.token(), "mine");
        var other = createLabel(ctx, "other");

        archiveLabel(ctx, plain.token(), mine).andExpect(status().isForbidden());
        unarchiveLabel(ctx, plain.token(), mine).andExpect(status().isForbidden());
        mergeLabels(ctx, plain.token(), mine, other).andExpect(status().isForbidden());
        deleteLabel(ctx, plain.token(), mine, false).andExpect(status().isForbidden());

        // …but reads stay open to every member.
        labelUsage(ctx, plain.token(), mine).andExpect(status().isOk());
        assertThat(listLabels(ctx, plain.token(), null))
                .as("reads stay open to every member: the admin gate is on the writes")
                .hasSize(2);

        // and an ADMIN can do all four
        var admin = addMember(ctx, "ADMIN");
        archiveLabel(ctx, admin.token(), mine).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        unarchiveLabel(ctx, admin.token(), mine).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));
        deleteLabel(ctx, admin.token(), mine, false).andExpect(status().isNoContent());
    }

    // ==================================================== archive / usage

    @Test
    void archivedLabelsAreHiddenByDefaultAndListedOnDemandWithUsage() throws Exception {
        var ctx = newProject();
        var live = createLabel(ctx, "live");
        var gone = createLabel(ctx, "gone");
        createIssue(ctx, "one", labelIdsJson(live));
        createIssue(ctx, "two", labelIdsJson(live));
        archiveLabel(ctx, ctx.token(), gone).andExpect(status().isOk());

        assertThat(names(listLabels(ctx, ctx.token(), null)))
                .as("archived labels must be hidden from the default list")
                .isEqualTo(List.of("live"));
        assertThat(names(listLabels(ctx, ctx.token(), "?includeArchived=true")))
                .as("includeArchived must list both, ordered by lower(name)")
                .isEqualTo(List.of("gone", "live"));

        var withUsage = listLabels(ctx, ctx.token(), "?withUsage=true");
        assertThat(withUsage.get(0).get("issueCount").asInt()).as("usage count must be batched and correct").isEqualTo(2);
        labelUsage(ctx, ctx.token(), live)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueCount").value(2));
        // no usage requested → the field is null, never a lie
        assertThat(listLabels(ctx, ctx.token(), null).get(0).get("issueCount").isNull())
                .withFailMessage("no usage requested means the field is null, never a lie about zero")
                .isTrue();
    }

    @Test
    void archivingALabelInUseKeepsItsAttachments() throws Exception {
        var ctx = newProject();
        var id = createLabel(ctx, "aging");
        var issue = createIssue(ctx, "carrier", labelIdsJson(id));
        archiveLabel(ctx, ctx.token(), id).andExpect(status().isOk());

        var node = getIssue(ctx, issue.get("number").asLong());
        assertThat(labelNames(node)).as("archive must preserve existing links").isEqualTo(List.of("aging"));
        assertThat(node.get("labels").get(0).get("archived").asBoolean())
                .withFailMessage("the ref must be flagged archived so the SPA can dim it")
                .isTrue();
        labelUsage(ctx, ctx.token(), id).andExpect(jsonPath("$.issueCount").value(1));
    }

    // ==================================================== delete

    @Test
    void deleteInUseIs409WithoutForceAnd204WithIt() throws Exception {
        var ctx = newProject();
        var id = createLabel(ctx, "doomed");
        var keep = createLabel(ctx, "keeper");
        var issue = createIssue(ctx, "carrier", labelIdsJson(id, keep));

        deleteLabel(ctx, ctx.token(), id, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("used on 1 issue")));
        assertThat(labelNames(getIssue(ctx, issue.get("number").asLong())))
                .as("the refused delete must not have detached anything")
                .hasSize(2);

        deleteLabel(ctx, ctx.token(), id, true).andExpect(status().isNoContent());
        assertThat(labelNames(getIssue(ctx, issue.get("number").asLong())))
                .as("force delete must drop exactly its own join rows")
                .isEqualTo(List.of("keeper"));
        assertThat(names(listLabels(ctx, ctx.token(), "?includeArchived=true")))
                .as("the forced delete removed the catalog row itself, archived listing included")
                .isEqualTo(List.of("keeper"));
        labelUsage(ctx, ctx.token(), id).andExpect(status().isNotFound());
    }

    @Test
    void deletingAnIssueCascadesItsJoinRows() throws Exception {
        var ctx = newProject();
        var id = createLabel(ctx, "shared");
        var doomed = createIssue(ctx, "doomed", labelIdsJson(id));
        createIssue(ctx, "survivor", labelIdsJson(id));
        labelUsage(ctx, ctx.token(), id).andExpect(jsonPath("$.issueCount").value(2));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete(ctx.issuesBase() + "/" + doomed.get("number").asLong())
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());

        // issue_labels cascades at DB level (like issue_field_values) — the usage
        // count must drop, and the label itself must survive.
        labelUsage(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueCount").value(1));
        // the surviving issue still carries it, so an unforced delete is still a 409
        deleteLabel(ctx, ctx.token(), id, false).andExpect(status().isConflict());
    }

    @Test
    void deleteOfAnUnusedLabelNeedsNoForce() throws Exception {
        var ctx = newProject();
        var id = createLabel(ctx, "unused");
        deleteLabel(ctx, ctx.token(), id, false).andExpect(status().isNoContent());
        assertThat(listLabels(ctx, ctx.token(), "?includeArchived=true"))
                .as("deleting an unused label needs no force and leaves nothing behind")
                .isEmpty();
    }

    // ==================================================== merge (bug #1)

    /**
     * The regression that motivated the fix: an issue carrying <em>both</em> sources
     * and not the target. Merging both at once used to leave two rows re-pointed at
     * the same {@code (issue, target)} pair → {@code UNIQUE (issue_id, label_id)}
     * violation → guaranteed 500. The repository now also collapses duplicates
     * <em>among the sources</em> (keeping the lowest id) before the re-point, with the
     * mandatory {@code flush()} in between.
     */
    @Test
    void mergeCollapsesTwoSourcesThatCoOccurOnOneIssue() throws Exception {
        var ctx = newProject();
        var target = createLabel(ctx, "bug");
        var srcA = createLabel(ctx, "defect");
        var srcB = createLabel(ctx, "regression");

        var onlyA = createIssue(ctx, "only-a", labelIdsJson(srcA));
        var onlyB = createIssue(ctx, "only-b", labelIdsJson(srcB));
        var both = createIssue(ctx, "both", labelIdsJson(srcA, srcB));       // ← the killer row
        var already = createIssue(ctx, "already-target", labelIdsJson(target, srcA));
        var untouched = createIssue(ctx, "untouched");

        mergeLabels(ctx, ctx.token(), target, srcA, srcB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetId").value(target.toString()))
                .andExpect(jsonPath("$.mergedLabelCount").value(2))
                // 3 rows survive step 1 and are re-pointed: only-a, only-b, both(one of
                // the two). "already-target" contributed a row that was DELETED as a
                // collision, so it is not counted as reassigned.
                .andExpect(jsonPath("$.reassignedIssueCount").value(3));

        for (var issue : List.of(onlyA, onlyB, both, already)) {
            var labels = labelNames(getIssue(ctx, issue.get("number").asLong()));
            assertThat(labels)
                    .as(() -> "every merged issue must carry exactly one 'bug' row, got " + labels
                      + " on " + issue.get("title").asText())
                    .isEqualTo(List.of("bug"));
        }
        assertThat(labelNames(getIssue(ctx, untouched.get("number").asLong())))
                .as("an issue that carried neither source is untouched by the merge")
                .isEmpty();

        // The source catalog rows are gone; the target survived and absorbed the usage.
        assertThat(names(listLabels(ctx, ctx.token(), "?includeArchived=true")))
                .as("the source catalog rows are gone and the target survived to absorb the usage")
                .isEqualTo(List.of("bug"));
        labelUsage(ctx, ctx.token(), target).andExpect(jsonPath("$.issueCount").value(4));
    }

    @Test
    void mergeIntoItselfIs422AndAnUnknownSourceIs422() throws Exception {
        var ctx = newProject();
        var target = createLabel(ctx, "target");
        var source = createLabel(ctx, "source");
        createIssue(ctx, "carrier", labelIdsJson(source));

        mergeLabels(ctx, ctx.token(), target, target)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", containsString("merged into itself")));
        mergeLabels(ctx, ctx.token(), target, source, target)
                .andExpect(status().isUnprocessableContent());
        mergeLabels(ctx, ctx.token(), target, UUID.randomUUID())
                .andExpect(status().isUnprocessableContent());
        // an empty sourceIds list is a bean-validation 400 (@NotEmpty)
        mergeLabels(ctx, ctx.token(), target).andExpect(status().isBadRequest());

        // nothing merged by any of the rejected calls
        assertThat(names(listLabels(ctx, ctx.token(), null)))
                .as("nothing was merged by any of the rejected calls")
                .isEqualTo(List.of("source", "target"));
    }

    @Test
    void anArchivedSourceStillMerges() throws Exception {
        var ctx = newProject();
        var target = createLabel(ctx, "keep");
        var source = createLabel(ctx, "old");
        var issue = createIssue(ctx, "carrier", labelIdsJson(source));
        archiveLabel(ctx, ctx.token(), source).andExpect(status().isOk());

        // Archiving is the recommended path BEFORE a merge, so an archived source must
        // still merge (the attachment is repointed, not blocked as a "new" attach).
        mergeLabels(ctx, ctx.token(), target, source)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reassignedIssueCount").value(1));
        assertThat(labelNames(getIssue(ctx, issue.get("number").asLong())))
                .as("an archived source still merges: archiving hides a row from pickers, it does not freeze it")
                .isEqualTo(List.of("keep"));
    }

    // ==================================================== helpers

    private static List<String> names(com.fasterxml.jackson.databind.JsonNode labelArray) {
        var out = new java.util.ArrayList<String>();
        for (var l : labelArray) out.add(l.get("name").asText());
        return out;
    }
}
