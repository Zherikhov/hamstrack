package com.hamstrack.workspace;

import com.hamstrack.workspace.service.WorkspaceStorageReconciler;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The quota switched off, and the master rate-limit switch switched off with it</strong>
 * (HD-191, AC-10/14/23).
 *
 * <p>Two independent claims live in one context because they are the same configuration:
 *
 * <ul>
 *   <li><strong>Disabled does not mean unmeasured.</strong> With
 *       {@code app.storage.quota.enabled=false} nothing is refused, but the trigger still counts,
 *       {@code GET …/storage} still reports, and the reconciler still reconciles. That is
 *       {@code RecipientMailThrottle}'s reasoning applied to a measured ceiling: a switch that
 *       stopped the bookkeeping would mean an instance turning the quota back on resumes from a
 *       blank number, and the operator deciding what to set has lost the figure that would tell
 *       them.</li>
 *   <li><strong>{@code app.rate-limit.enabled=false} does not reach the quota.</strong> The two
 *       switches are separate on purpose (§10.2), and the direction asserted here is the one that
 *       would be a security regression if it were wrong — a test suite that turns limiting off
 *       expects the budgets off, and if the quota came off with them, every quota test in the
 *       product would be silently exercising nothing.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.storage.quota.enabled=false",
        "app.storage.quota.workspace-bytes=10000B",
        "app.attachments.max-file-size=9000B",
        "app.write.upload-bytes-per-minute=9000B",
        "app.storage.quota.reconcile-cron="
})
@AutoConfigureMockMvc
class StorageQuotaDisabledTest extends StorageTestBase {

    static final Path STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("hamstrack-quota-off-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("app.storage.type", () -> "local");
        registry.add("app.storage.local.base-dir", STORAGE_DIR::toString);
    }

    @Autowired WorkspaceStorageReconciler reconciler;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;

    /**
     * AC-10: nothing is refused, usage is still counted, and the summary reports
     * {@code quotaEnabled:false} with <strong>absent</strong> numbers rather than a sentinel — a
     * {@code -1} here is a value a client eventually renders as "-1 GB remaining", and a {@code 0}
     * is worse because it reads as "full".
     */
    @Test
    void nothingIsRefusedButEverythingIsStillCounted() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);

        upload(ctx, issue, "a.txt", bytesOf(8000));
        // Far past the configured ceiling, which is not being enforced.
        upload(ctx, issue, "b.txt", bytesOf(8000));
        upload(ctx, issue, "c.txt", bytesOf(8000));

        assertThat(bytesUsed(ctx.wsId())).isEqualTo(24000);

        mockMvc.perform(get("/api/workspaces/" + ctx.wsId() + "/storage")
                        .header("Authorization", "Bearer " + ctx.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaEnabled").value(false))
                .andExpect(jsonPath("$.quotaBytes").doesNotExist())
                .andExpect(jsonPath("$.availableBytes").doesNotExist())
                .andExpect(jsonPath("$.percentUsed").doesNotExist())
                .andExpect(jsonPath("$.usedBytes").value(24000))
                .andExpect(jsonPath("$.attachmentCount").value(3))
                .andExpect(jsonPath("$.asOf").exists());
    }

    /**
     * AC-23: a counter corrupted by hand is corrected on the next pass.
     *
     * <p>Corrupted with raw SQL rather than through the API, because there is no application path
     * that can produce drift — which is exactly why the reconciler exists: the doors that CAN
     * produce it (a restore, a hand-run DELETE, a bulk path nobody has written yet) are all
     * outside the trigger, and all of them are situations in which nobody is watching.
     */
    @Test
    void aCounterCorruptedByHandIsCorrectedOnTheNextPass() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);
        upload(ctx, issue, "a.txt", bytesOf(4000));
        assertThat(bytesUsed(ctx.wsId())).isEqualTo(4000);

        // COMMITTED, not run inside a test-managed transaction. An open transaction here would
        // still hold the row lock the corrupting UPDATE took, and the reconciler's per-workspace
        // transaction takes the same lock — so the pass would sit behind the test until
        // lock_timeout fired. The reconciler's own correctness argument is that it can only observe
        // committed state; a test has to give it some.
        txTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery(
                                "UPDATE workspace_storage_usage SET bytes_used = 999999 "
                                + "WHERE workspace_id = ?1")
                        .setParameter(1, ctx.wsId())
                        .executeUpdate());
        assertThat(bytesUsed(ctx.wsId())).isEqualTo(999999);

        reconciler.reconcile();

        assertThat(bytesUsed(ctx.wsId()))
                .as("the reconciler is the witness that the counter still equals the rows it "
                    + "claims to count; if it cannot correct a counter it can only report one")
                .isEqualTo(4000);
        assertThat(attachmentCount(ctx.wsId())).isEqualTo(1);
    }
}
