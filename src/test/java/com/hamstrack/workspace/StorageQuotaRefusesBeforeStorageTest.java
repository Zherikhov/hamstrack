package com.hamstrack.workspace;

import com.hamstrack.common.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The guarantee this ticket actually delivers: no byte of a refused upload is ever passed
 * to {@code FileStorage}</strong> (HD-191 §5.4, AC-3 and AC-7).
 *
 * <p>Provable by construction — the reservation runs inside the short transaction that resolves
 * tenancy and persists the row, and {@code fileStorage.store} is only reached after that
 * transaction commits — and proved here rather than argued, with a {@link FileStorage} double that
 * records whether it was invoked at all.
 *
 * <p>{@code AttachmentDoorsTest} is the sibling seal and neither stands in for the other: that one
 * stops a NEW upload door being written without the two controls, this one stops the controls being
 * wrong on the door that exists.
 *
 * <p>The second test is the other end of the same interaction — a store failure AFTER the row
 * commits. The compensating delete removes the row, the trigger decrements, and the workspace ends
 * where it started: a failed upload must not charge a tenant for bytes that are not stored.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.storage.quota.enabled=true",
        "app.storage.quota.workspace-bytes=10000B",
        "app.attachments.max-file-size=9000B",
        "app.write.upload-bytes-per-minute=9000B",
        "app.storage.quota.reconcile-cron="
})
@AutoConfigureMockMvc
class StorageQuotaRefusesBeforeStorageTest extends StorageTestBase {

    /** Replaces the real backend so it can be asked what it was, and was not, told to do. */
    @MockitoBean FileStorage fileStorage;

    /**
     * AC-3. Two assertions, and the second is the one a reviewer should read: not only was
     * {@code store} never called, but <strong>no row survived either</strong> — the reservation
     * throws inside the transaction that would have persisted it, so there is nothing to
     * compensate and nothing to clean up.
     */
    @Test
    void anOverQuotaUploadNeverReachesTheStoreAndLeavesNoRow() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);

        uploadRaw(ctx, issue, "huge.txt", bytesOf(9000)).andExpect(status().isCreated());
        long afterFirst = bytesUsed(ctx.wsId());
        // The accepted upload legitimately reached the store; what follows is about the refused one.
        org.mockito.Mockito.clearInvocations(fileStorage);

        uploadRaw(ctx, issue, "over.txt", bytesOf(9000)).andExpect(status().isConflict());

        verify(fileStorage, never()).store(anyString(), any(InputStream.class), anyLong(), anyString());
        assertThat(attachmentCount(ctx.wsId()))
                .as("""
                    A REFUSED UPLOAD LEFT A ROW BEHIND.

                    The quota reservation runs INSIDE the transaction that persists the attachment \
                    row, before the save, so a refusal rolls the whole thing back: no row, no blob, \
                    no counter movement. A row surviving here means the reservation moved below the \
                    save, or out of the transaction — at which point the refusal is charging the \
                    tenant for a file it also refused.""")
                .isEqualTo(1);
        assertThat(bytesUsed(ctx.wsId())).isEqualTo(afterFirst);
    }

    /**
     * AC-7. The row commits, the store then fails, the compensating delete removes the row and the
     * trigger decrements with it — so the net effect on the quota is nothing.
     *
     * <p>The direction that would be wrong is the tenant being charged for bytes that are not
     * stored. (The opposite direction — compensation itself failing, leaving a row for a blob that
     * does not exist — is conservative, self-consistent with what the reconciler counts, and
     * already logged at WARN with the key.)
     */
    @Test
    void aStoreFailureLeavesTheCounterWhereItStarted() throws Exception {
        var ctx = newProject();
        var issue = createIssue(ctx);
        long before = bytesUsed(ctx.wsId());

        doThrow(new RuntimeException("storage is down"))
                .when(fileStorage).store(anyString(), any(InputStream.class), anyLong(), anyString());

        uploadRaw(ctx, issue, "doomed.txt", bytesOf(3000))
                .andExpect(status().isInternalServerError());

        assertThat(bytesUsed(ctx.wsId()))
                .as("a failed upload must not charge the tenant for bytes that were never stored")
                .isEqualTo(before);
        assertThat(attachmentCount(ctx.wsId())).isZero();
    }
}
