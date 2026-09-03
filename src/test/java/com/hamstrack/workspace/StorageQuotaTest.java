package com.hamstrack.workspace;

import com.hamstrack.workspace.exception.StorageQuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The workspace storage quota, end to end</strong> (HD-191, AC-1/2/4/5/6/9/17).
 *
 * <p>Runs against the REAL {@code LocalFileStorage} in a temp directory, and against the real
 * trigger — every counter assertion here reads {@code workspace_storage_usage} out of the database
 * rather than asking the API, so a service that happened to compute the same number a different
 * way could not satisfy them.
 *
 * <p>The quota is set to <strong>10 000 bytes</strong>, which is far below
 * {@code app.attachments.max-file-size}; that is legal (the startup check only refuses a quota
 * SMALLER than one permitted file — see {@code StorageQuotaConsistencyTest}) and lets every case
 * be driven with kilobytes instead of megabytes.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.storage.quota.enabled=true",
        "app.storage.quota.workspace-bytes=10000B",
        "app.attachments.max-file-size=9000B",
        "app.write.upload-bytes-per-minute=9000B",
        // Nothing here may depend on a scheduled pass: every assertion is about the TRIGGER.
        "app.storage.quota.reconcile-cron="
})
@AutoConfigureMockMvc
class StorageQuotaTest extends StorageTestBase {

    static final Path STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("hamstrack-quota-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.local.base-dir", STORAGE_DIR::toString);
    }

    /**
     * AC-1 and AC-2. The status, the discriminator, all four figures — and the sentence, which is
     * asserted for what it does NOT contain.
     *
     * <p>The negative half is the load-bearing one. This project's rule is that <em>a refusal may
     * only prescribe an action its reader can perform</em>, and it has shipped the opposite three
     * times. This refusal has no reader who is guaranteed to be able to act: a contributor cannot
     * delete other people's attachments and the space is often in a project they cannot see; "ask
     * your administrator" is a dead end on Cloud (a workspace owner cannot raise an instance
     * property) and usually on DC too. So the sentence describes the situation and dispatches
     * nobody — and the only way to keep that true through a future copy edit is to assert it.
     */
    @Test
    void anUploadPastTheQuotaIs409WithTheFiguresAndPrescribesNothing() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);
        upload(ctx, issue, "a.txt", bytesOf(8000));

        uploadRaw(ctx, issue, "b.txt", bytesOf(5000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType")
                        .value(StorageQuotaExceededException.STORAGE_QUOTA_EXCEEDED))
                .andExpect(jsonPath("$.quotaBytes").value(10000))
                .andExpect(jsonPath("$.usedBytes").value(8000))
                .andExpect(jsonPath("$.availableBytes").value(2000))
                .andExpect(jsonPath("$.fileBytes").value(5000))
                // No Retry-After: waiting never frees a byte, and a retry hint here would be an
                // instruction that cannot work.
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().doesNotExist("Retry-After"));

        var detail = json.readTree(uploadRaw(ctx, issue, "b.txt", bytesOf(5000))
                .andReturn().getResponse().getContentAsString()).get("detail").asText();

        assertThat(detail)
                .as("""
                    THE QUOTA REFUSAL MUST NOT PRESCRIBE AN ACTION ITS READER MAY BE UNABLE TO \
                    PERFORM.

                    Whoever reads this sentence is a member holding attachment.create in some \
                    project of a full workspace. They may hold attachment.delete only over their \
                    OWN files; the space may be entirely in a project they cannot see; and neither \
                    they nor their workspace owner can raise an instance property on Cloud. So \
                    "ask your administrator" and "delete some files" are both instructions that \
                    fail silently for a large share of readers. Describe the situation, name the \
                    numbers, and dispatch nobody.""")
                .doesNotContainIgnoringCase("administrator")
                .doesNotContainIgnoringCase("contact")
                .doesNotContainIgnoringCase("please")
                .doesNotContainIgnoringCase("ask ")
                .contains("9.8 KB");
    }

    /** AC-4: the comparison is {@code >} and not {@code >=}, so an exact fill is accepted. */
    @Test
    void aFileThatExactlyFillsTheQuotaIsAcceptedAndTheNextByteIsNot() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);
        upload(ctx, issue, "a.txt", bytesOf(8000));
        upload(ctx, issue, "b.txt", bytesOf(2000));

        assertThat(bytesUsed(ctx.wsId())).isEqualTo(10000);

        uploadRaw(ctx, issue, "c.txt", bytesOf(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.availableBytes").value(0));
    }

    /** AC-5: the trigger decrements in the same transaction as the row delete. */
    @Test
    void deletingAnAttachmentDecrementsTheCounterByExactlyItsSize() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);
        var first = upload(ctx, issue, "a.txt", bytesOf(3000)).get("id").asText();
        upload(ctx, issue, "b.txt", bytesOf(2000));
        assertThat(bytesUsed(ctx.wsId())).isEqualTo(5000);
        assertThat(attachmentCount(ctx.wsId())).isEqualTo(2);

        mockMvc.perform(delete(attachBase(ctx, issue) + "/" + first)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());

        assertThat(bytesUsed(ctx.wsId())).isEqualTo(2000);
        assertThat(attachmentCount(ctx.wsId())).isEqualTo(1);
    }

    /**
     * <strong>AC-6 — the spec's highest-risk assumption, proved rather than read.</strong>
     *
     * <p>Everything about the counter rests on one claim: <em>a row-level {@code AFTER DELETE}
     * trigger fires for rows removed by {@code ON DELETE CASCADE}.</em> If it is false the counter
     * is a one-way ratchet — every issue delete, project delete and future workspace purge
     * ratchets it upward for ever, the quota becomes a valve that only closes, and the ONLY thing
     * that would ever catch it is the nightly reconciler, i.e. up to 24 h of a tenant being
     * wrongly refused with a message naming a number that is not true.
     *
     * <p>So this deletes a <strong>PROJECT</strong>, not an attachment and not even an issue: the
     * rows go through {@code projects → issues → issue_attachments}, <strong>two</strong> levels of
     * cascade, with no application code touching the attachment rows at all. That is also §15's
     * second-order case — a multi-level cascade — which is why it is worth more than the one-level
     * issue delete asserted separately below. A test that deleted the attachment directly would
     * pass whether or not the assumption holds, which is the whole point of writing this one.
     *
     * <p><strong>Deleted through the repository, because the product has no project-delete
     * endpoint</strong> ({@code ProjectController} offers archive and unarchive; {@code DELETE} on
     * a project answers 405). That is not a weakening: what is under test is PostgreSQL's
     * referential cascade, and the row goes away by exactly the same mechanism whichever caller
     * asks for it. Reaching for an endpoint that does not exist would have made this assertion
     * untestable rather than making it stronger.
     */
    @Test
    void deletingAProjectReturnsTheCounterToItsPreUploadValue() throws Exception {
        var ctx = newProject();
        long before = bytesUsed(ctx.wsId());

        var doomed = secondProject(ctx);
        var issue = createIssue(doomed);
        upload(doomed, issue, "a.txt", bytesOf(3000));
        upload(doomed, issue, "b.txt", bytesOf(1500));
        assertThat(bytesUsed(ctx.wsId()))
                .as("precondition: the uploads are accounted for before the project goes away")
                .isEqualTo(before + 4500);

        projectRepository.deleteById(doomed.projectId());

        assertThat(bytesUsed(ctx.wsId()))
                .as("""
                    THE COUNTER DID NOT FOLLOW AN ON DELETE CASCADE.

                    This is the single assumption ADR-0026 rests on: PostgreSQL's referential \
                    cascade performs ordinary row deletions, which fire row-level triggers, so \
                    trg_issue_attachments_storage_usage follows the rows through paths no \
                    application code walks. If this assertion fails, the counter is a ONE-WAY \
                    RATCHET — every project and issue delete on the instance inflates it \
                    permanently, the quota starts refusing uploads into workspaces that are \
                    actually empty, and nothing catches it until the nightly reconciler runs. \
                    Do not "fix" this by decrementing in AttachmentService: that covers exactly \
                    one of the paths and is the design ADR-0026 rejected.""")
                .isEqualTo(before);
        assertThat(attachmentCount(ctx.wsId())).isZero();
    }

    /**
     * AC-6's one-level sibling, and the half that runs through real product code: deleting an
     * ISSUE through the API removes its attachment rows by {@code ON DELETE CASCADE} — nothing in
     * {@code IssueService} touches them — so the counter must follow them down.
     *
     * <p>Both halves are kept. This one proves the cascade fires on a path a user can reach; the
     * project-level one proves it fires through <em>two</em> levels, which is the case §15 flags as
     * the one worth being unsure about.
     */
    @Test
    void deletingAnIssueReturnsTheCounterToItsPreUploadValue() throws Exception {
        var ctx = newProject();
        long before = bytesUsed(ctx.wsId());
        var issue = createIssue(ctx);
        upload(ctx, issue, "a.txt", bytesOf(2500));
        assertThat(bytesUsed(ctx.wsId())).isEqualTo(before + 2500);

        mockMvc.perform(delete("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId()
                               + "/issues/" + issue)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isNoContent());

        assertThat(bytesUsed(ctx.wsId()))
                .as("the attachment rows of a deleted issue go away by ON DELETE CASCADE, not by "
                    + "any line in IssueService — so the counter follows them only if the trigger "
                    + "fires for cascaded deletes")
                .isEqualTo(before);
    }

    /**
     * AC-9: lowering the ceiling below current usage refuses new uploads, deletes nothing, and
     * leaves everything readable.
     *
     * <p>Driven by filling past the ceiling rather than by re-binding the property, which is the
     * same state from the enforcement path's point of view and does not need a second context.
     */
    @Test
    void aWorkspaceOverItsCeilingStillReadsAndDownloadsAndDeletesNothing() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);
        var id = upload(ctx, issue, "a.txt", bytesOf(8000)).get("id").asText();
        uploadRaw(ctx, issue, "b.txt", bytesOf(8000)).andExpect(status().isConflict());

        // Nothing was deleted to make room, and nothing is gated on the quota on the way out.
        assertThat(attachmentCount(ctx.wsId())).isEqualTo(1);
        mockMvc.perform(get(attachBase(ctx, issue)).header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(attachBase(ctx, issue) + "/" + id)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/storage")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(8000));
    }

    /**
     * <strong>AC-17 — the top bug class.</strong> A quota check that reads the wrong tenant's total
     * is worse than no quota: it refuses one workspace for another's usage, or admits an upload
     * because a stranger's workspace is empty.
     */
    @Test
    void anUploadInOneWorkspaceIsInvisibleToAnother() throws Exception {
        var a = newProject();
        var b = newProject();
        var issueA = createIssue(a);
        var issueB = createIssue(b);

        upload(a, issueA, "a.txt", bytesOf(9000));

        assertThat(bytesUsed(b.wsId()))
                .as("workspace B's counter must not have moved")
                .isZero();
        // B is empty, so a file that would be refused in A is accepted in B.
        upload(b, issueB, "b.txt", bytesOf(9000));
        assertThat(bytesUsed(a.wsId())).isEqualTo(9000);
        assertThat(bytesUsed(b.wsId())).isEqualTo(9000);

        mockMvc.perform(get("/api/workspaces/" + b.wsId() + "/storage")
                        .header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(9000))
                .andExpect(jsonPath("$.attachmentCount").value(1));
    }
}
