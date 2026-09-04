package com.hamstrack.issue;

import com.hamstrack.issue.entity.IssueLabel;
import com.hamstrack.issue.repository.IssueLabelRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.LabelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HD-30 cross-tenant isolation — the project's top bug class, and the coverage the
 * tenancy reviewer asked for explicitly (labels-components-versions-proposal §3.1,
 * §4.6 "Cross-tenant").
 *
 * <p>Two independent workspaces A and B. Every assertion here is about what a caller
 * of A can learn or touch in B:
 *
 * <ul>
 *   <li>a member of A addressing a label id that lives in B → <strong>404</strong>
 *       on every id-bearing endpoint (indistinguishable from "no such label");</li>
 *   <li>a non-member → <strong>404, never 403</strong>, on all eight label
 *       endpoints (a 403 would confirm the workspace exists);</li>
 *   <li>a merge whose {@code sourceIds} reach into B → <strong>422</strong>, with
 *       B's catalog and attachments verifiably untouched;</li>
 *   <li>an issue payload carrying a foreign {@code labelIds} entry →
 *       <strong>422</strong> "Unknown label" (an invalid field value, leaking
 *       nothing — never a 404/403 that would disclose existence);</li>
 *   <li>the board filter with a foreign {@code labelId} → an empty result, not an
 *       error;</li>
 *   <li>and the <strong>DB-level</strong> guard (fix round 2, §3.8): the composite
 *       foreign keys on {@code issue_labels} make a cross-tenant join row
 *       unrepresentable, so even a service bug that skipped every
 *       {@code …AndWorkspace} lookup could not persist one.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class LabelTenancyTest extends LabelTestBase {

    @Autowired IssueRepository issueRepository;
    @Autowired LabelRepository labelRepository;
    @Autowired IssueLabelRepository issueLabelRepository;

    // ==================================================== (a) foreign id → 404

    @Test
    void memberOfAAddressingALabelThatLivesInBGets404OnEveryIdEndpoint() throws Exception {
        var a = newProject();
        var b = newProject();
        var foreign = createLabel(b, "b-only");
        // A owns a label too, so the 404s below can't be "labels are broken".
        var mine = createLabel(a, "a-only");

        // PATCH / archive / unarchive / merge(target) / delete / usage — all resolve
        // through findByIdAndWorkspace, so B's id is simply not there.
        patchLabel(a, a.token(), foreign, "{\"name\":\"hijacked\"}").andExpect(status().isNotFound());
        archiveLabel(a, a.token(), foreign).andExpect(status().isNotFound());
        unarchiveLabel(a, a.token(), foreign).andExpect(status().isNotFound());
        mergeLabels(a, a.token(), foreign, mine).andExpect(status().isNotFound());
        deleteLabel(a, a.token(), foreign, false).andExpect(status().isNotFound());
        labelUsage(a, a.token(), foreign).andExpect(status().isNotFound());

        // A random (never-existed) id is indistinguishable from B's.
        var ghost = UUID.randomUUID();
        patchLabel(a, a.token(), ghost, "{\"name\":\"x\"}").andExpect(status().isNotFound());
        labelUsage(a, a.token(), ghost).andExpect(status().isNotFound());

        // B's label survived every attempt, under its original name.
        var bList = listLabels(b, b.token(), null);
        assertThat(bList).as("B's label must be untouched, got " + bList).hasSize(1);
        assertThat(bList.get(0).get("name").asText()).as("B's label must be untouched, got " + bList).isEqualTo("b-only");
    }

    @Test
    void aLabelListNeverContainsAForeignRow() throws Exception {
        var a = newProject();
        var b = newProject();
        createLabel(a, "alpha");
        createLabel(b, "beta");

        var aList = listLabels(a, a.token(), "?includeArchived=true&withUsage=true");
        assertThat(aList).as("A's list must contain only A's labels, got " + aList).hasSize(1);
        assertThat(aList.get(0).get("name").asText())
                .as("A's list must contain only A's labels, got " + aList)
                .isEqualTo("alpha");
        var bList = listLabels(b, b.token(), "?includeArchived=true&withUsage=true");
        assertThat(bList).as("B's list must contain only B's labels, got " + bList).hasSize(1);
        assertThat(bList.get(0).get("name").asText())
                .as("B's list must contain only B's labels, got " + bList)
                .isEqualTo("beta");
    }

    // ==================================================== (b) non-member → 404 never 403

    @Test
    void nonMemberGets404NotForbiddenOnAllEightLabelEndpoints() throws Exception {
        var a = newProject();
        var labelId = createLabel(a, "secret");
        var outsider = login(user());          // authenticates fine, member of nothing
        var base = a.labelsBase();

        // 1. list
        expect404(get(base), outsider);
        // 2. create
        expect404(post(base).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"), outsider);
        // 3. update
        expect404(patch(base + "/" + labelId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"), outsider);
        // 4. archive
        expect404(post(base + "/" + labelId + "/archive"), outsider);
        // 5. unarchive
        expect404(post(base + "/" + labelId + "/unarchive"), outsider);
        // 6. merge
        expect404(post(base + "/" + labelId + "/merge").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceIds\":[\"" + UUID.randomUUID() + "\"]}"), outsider);
        // 7. delete
        expect404(delete(base + "/" + labelId), outsider);
        // 8. usage
        expect404(get(base + "/" + labelId + "/usage"), outsider);

        // The label is still there for its real owner — nothing above half-succeeded.
        assertThat(listLabels(a, a.token(), null)).as("owner's catalog must be intact").hasSize(1);
    }

    /**
     * 404 and nothing else — in particular not 403, which would confirm that the
     * workspace (and the label id) exist.
     */
    private void expect404(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                           String token) throws Exception {
        mockMvc.perform(req.header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(status().is(org.hamcrest.Matchers.not(403)));
    }

    // ==================================================== (c) merge across tenants

    @Test
    void mergeWithASourceFromAnotherWorkspaceIs422AndLeavesThatWorkspaceUntouched() throws Exception {
        var a = newProject();
        var b = newProject();
        var target = createLabel(a, "target");
        var mineSource = createLabel(a, "mine");
        var foreignSource = createLabel(b, "theirs");

        // B's label is in use on a B issue — the row that must survive.
        var bIssue = createIssue(b, "b issue", labelIdsJson(foreignSource));
        assertThat(labelNames(bIssue))
                .as("B's label is still on B's issue — the refused merge reassigned nothing across the tenant boundary")
                .isEqualTo(java.util.List.of("theirs"));

        mergeLabels(a, a.token(), target, mineSource, foreignSource)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail",
                        org.hamcrest.Matchers.containsString("don't exist in this workspace")));

        // Nothing in B moved: the label still exists, still non-archived, still attached.
        var bLabels = listLabels(b, b.token(), "?withUsage=true");
        assertThat(bLabels).as("B's label must survive a cross-tenant merge attempt").hasSize(1);
        assertThat(bLabels.get(0).get("name").asText())
                .as("B's label must survive a cross-tenant merge attempt")
                .isEqualTo("theirs");
        assertThat(bLabels.get(0).get("issueCount").asInt())
                .as(() -> "B's attachment must survive, got " + bLabels.get(0))
                .isEqualTo(1);
        assertThat(labelNames(getIssue(b, bIssue.get("number").asLong())))
                .as("B's issue must still carry its label")
                .isEqualTo(java.util.List.of("theirs"));

        // And A's own source was NOT merged either — the whole request was rejected.
        var aLabels = listLabels(a, a.token(), null);
        assertThat(aLabels).as("A's catalog must be unchanged (atomic rejection), got " + aLabels).hasSize(2);
    }

    @Test
    void mergeTargetInAWithASourceInBAlsoFailsWhenAddressedFromB() throws Exception {
        var a = newProject();
        var b = newProject();
        var aLabel = createLabel(a, "a-label");
        var bTarget = createLabel(b, "b-target");

        // Same shape, mirrored: B tries to swallow A's label. 422, not 404/200.
        mergeLabels(b, b.token(), bTarget, aLabel)
                .andExpect(status().isUnprocessableContent());
        assertThat(listLabels(a, a.token(), null)).as("A's label must survive").hasSize(1);
    }

    // ==================================================== (d) foreign labelIds on an issue

    @Test
    void patchingAnIssueWithAForeignLabelIdIs422UnknownLabel() throws Exception {
        var a = newProject();
        var b = newProject();
        var foreign = createLabel(b, "foreign");
        var issue = createIssue(a, "mine");

        patchIssue(a, a.token(), issue.get("number").asLong(), "{" + labelIdsJson(foreign) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("Unknown label")));

        // Create path rejects it the same way, before anything is written.
        postIssue(a, a.token(), "new one", labelIdsJson(foreign))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("Unknown label")));

        assertThat(labelNames(getIssue(a, issue.get("number").asLong())))
                .as("the rejected PATCH must not have attached anything")
                .isEmpty();
        // Mixing a valid local id with a foreign one still rejects the WHOLE payload.
        var local = createLabel(a, "local");
        patchIssue(a, a.token(), issue.get("number").asLong(), "{" + labelIdsJson(local, foreign) + "}")
                .andExpect(status().isUnprocessableContent());
        assertThat(labelNames(getIssue(a, issue.get("number").asLong())))
                .as("a partially-foreign set must attach nothing at all")
                .isEmpty();
    }

    // ==================================================== (e) foreign labelId filter

    @Test
    void boardFilteredByAForeignLabelIdReturnsEmptyNotAnError() throws Exception {
        var a = newProject();
        var b = newProject();
        var foreign = createLabel(b, "foreign");
        var mine = createLabel(a, "mine");
        createIssue(a, "labelled", labelIdsJson(mine));
        createIssue(a, "plain");
        createIssue(b, "b issue", labelIdsJson(foreign));

        // A foreign id is just an id that matches nothing here — 200 + empty.
        var filtered = board(a, "?labelId=" + foreign);
        assertThat(filtered.get("issues"))
                .as(() -> "a foreign labelId must match nothing, got " + filtered.get("issues"))
                .isEmpty();
        // …and never widens: mixing it with a local id still only yields local matches.
        var mixed = board(a, "?labelId=" + foreign + "&labelId=" + mine);
        assertThat(titles(mixed)).as("got " + titles(mixed)).isEqualTo(java.util.Set.of("labelled"));
        // A never-existed id behaves identically.
        assertThat(board(a, "?labelId=" + UUID.randomUUID()).get("issues"))
                .as("a never-existed labelId behaves identically to a foreign one: empty, not an error and not a leak")
                .isEmpty();
    }

    // ==================================================== DB-level composite-FK guard

    /**
     * §3.8 (owner decision, fix round 2): {@code issue_labels} carries its own
     * {@code workspace_id NOT NULL} and references BOTH parents through composite FKs
     * — {@code (issue_id, workspace_id) → issues (id, workspace_id)} and
     * {@code (label_id, workspace_id) → labels (id, workspace_id)}. This test bypasses
     * every service check and writes the join row straight through the repository, so
     * what it locks in is the <em>schema</em>: a cross-tenant attachment is not
     * "rejected", it is unrepresentable.
     */
    @Test
    void aCrossTenantJoinRowCannotBePersistedAtAll() throws Exception {
        var a = newProject();
        var b = newProject();
        var aIssue = issueRepository.findById(
                UUID.fromString(createIssue(a, "a issue").get("id").asText())).orElseThrow();
        var bLabel = labelRepository.findById(createLabel(b, "b label")).orElseThrow();
        var aWorkspace = workspaceRepository.findById(a.wsId()).orElseThrow();
        var bWorkspace = workspaceRepository.findById(b.wsId()).orElseThrow();

        // Stamped with the ISSUE's workspace (what the service factory does) → the
        // label-side composite FK has no (bLabel.id, aWorkspace.id) parent row.
        var stampedFromIssue = new IssueLabel();
        stampedFromIssue.setIssue(aIssue);
        stampedFromIssue.setLabel(bLabel);
        stampedFromIssue.setWorkspace(aWorkspace);
        assertThrows(DataIntegrityViolationException.class,
                () -> issueLabelRepository.saveAndFlush(stampedFromIssue),
                "issue_labels_label_fk must reject a label from another workspace");

        // Stamped with the LABEL's workspace instead → now the issue-side FK fails.
        // Neither stamp can produce a valid row, which is the whole point.
        var stampedFromLabel = new IssueLabel();
        stampedFromLabel.setIssue(aIssue);
        stampedFromLabel.setLabel(bLabel);
        stampedFromLabel.setWorkspace(bWorkspace);
        assertThrows(DataIntegrityViolationException.class,
                () -> issueLabelRepository.saveAndFlush(stampedFromLabel),
                "issue_labels_issue_fk must reject an issue from another workspace");
    }

    /** Sanity anchor: the same write with both parents in ONE workspace succeeds. */
    @Test
    void aSameTenantJoinRowPersistsFine() throws Exception {
        var a = newProject();
        var issue = issueRepository.findById(
                UUID.fromString(createIssue(a, "a issue").get("id").asText())).orElseThrow();
        var label = labelRepository.findById(createLabel(a, "ok")).orElseThrow();

        var row = new IssueLabel();
        row.setIssue(issue);
        row.setLabel(label);
        row.setWorkspace(workspaceRepository.findById(a.wsId()).orElseThrow());
        issueLabelRepository.saveAndFlush(row);

        assertThat(labelNames(getIssue(a, issue.getNumber())))
                .as("a same-tenant join row persists and reads back — the guard refuses foreigners, not neighbours")
                .isEqualTo(java.util.List.of("ok"));
    }
}
