package com.hamstrack.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * <strong>A ceiling that no legal file can fit under is a misconfiguration, not a policy — and
 * this refuses the boot rather than letting the first upload after a deploy discover it</strong>
 * (HD-191 §6.5, AC-11).
 *
 * <p>Three independent numbers have to clear {@code app.attachments.max-file-size}, and they fail
 * in the same shape for different reasons:
 *
 * <ul>
 *   <li>{@code app.storage.quota.workspace-bytes} — an empty workspace whose quota is smaller
 *       than one permitted file can never accept anything. Every upload answers <strong>409
 *       {@code STORAGE_QUOTA_EXCEEDED}</strong> naming two numbers, neither of which the reader
 *       can change, on a workspace that is visibly empty.</li>
 *   <li>{@code app.write.upload-bytes-per-minute} — a byte budget below one permitted file can
 *       never admit one either, and it answers <strong>429 with a {@code Retry-After}</strong>,
 *       which is worse: it tells the caller that waiting helps, and no amount of waiting ever
 *       will.</li>
 *   <li>{@code spring.servlet.multipart.max-file-size} / {@code max-request-size} — the servlet
 *       parse ceiling, which both come from {@code ATTACHMENT_MAX_UPLOAD_SIZE}. A per-file limit
 *       above the parse ceiling is a size the product advertises and the container refuses at
 *       <strong>413</strong> before a handler is ever mapped, so nothing in the application can
 *       explain it. <strong>Compared at a margin rather than at equality</strong>, and the margin
 *       is not a fudge factor: the bundled Caddy reads that same variable and reads {@code MB} as
 *       10⁶ where Spring reads 2²⁰, so at equality the edge is ~4.63% tighter than the app's own
 *       ceiling and that top slice of the app-legal range is refused by the proxy with a bare 413
 *       carrying no Hamstrack body at all. {@code ATTACHMENT_MAX_FILE_SIZE=25MB} beside
 *       {@code ATTACHMENT_MAX_UPLOAD_SIZE=25MB} — the natural pairing, and the one this refuses —
 *       is exactly that configuration.</li>
 * </ul>
 *
 * <p>The third comparison is the one relation in this family that every operator-facing document
 * has always asserted ({@code .env.prod.example}, {@code docs/self-hosting.md}: "MAX_FILE_SIZE
 * must stay ≤ MAX_UPLOAD_SIZE") and nothing anywhere checked.
 *
 * <p>Both comparisons rest on {@code app.attachments.max-file-size} being <strong>positive</strong>,
 * which is checked first and is not a formality: at zero every comparison here is trivially
 * satisfied, so the whole class stops guarding, and the zero quota it then admits turns the
 * storage summary's fill percentage into a division by zero — serialised as a bare
 * {@code Infinity}, which is not valid JSON. A check whose operand can be zero is a check that can
 * be switched off from {@code .env}.
 *
 * <p>Every refusal these prevent is a clean 4xx — the last one is not even the application's, it
 * is the proxy's — so none of them appears in an error rate and none logs anything an operator
 * would find. That is exactly the class of failure a startup check is for: the mistake is cheap to
 * make (lower one number, forget the other) and expensive to notice.
 *
 * <p><strong>A {@code @Component} with {@code @PostConstruct} rather than an {@code @AssertTrue}
 * because the operands live in different configuration classes</strong> — the shape
 * {@link DatabaseTimeoutConsistency} already uses. The outcome is identical (the context does
 * not start) and the message is the documentation.
 *
 * <p>The two application-side comparisons are {@code >=}, not {@code >}: a quota exactly the size
 * of one permitted file admits exactly one file, which is a strange but coherent configuration,
 * and refusing it would be this class inventing a policy rather than catching an impossibility.
 * The servlet one is the deliberate exception, and its margin is arithmetic rather than taste —
 * two parsers disagree about what {@code MB} means, so equality there is not the coherent
 * configuration it looks like.
 */
@Component
// MultipartProperties is registered by MultipartAutoConfiguration, which is
// @ConditionalOnWebApplication(SERVLET) — so a non-servlet boot (the seed-guard tests build one
// deliberately) would have no bean to inject and this @Component would fail the context for a
// reason that has nothing to do with uploads. Registering the same @ConfigurationProperties class
// here is idempotent (same bean name, one definition) and binds from the Environment either way,
// so the check runs in every context that runs this bean.
@EnableConfigurationProperties(MultipartProperties.class)
@RequiredArgsConstructor
public class StorageQuotaConsistency {

    /**
     * The margin the servlet ceiling must clear the per-file limit by, as a percentage — the
     * MB/MiB skew between Caddy and Spring (2²⁰/10⁶ = 4.8576%) rounded up to a round number, so
     * that the edge reading the same variable is never tighter than the limit the application
     * advertises. Not a property: it is a consequence of two parsers, not a preference.
     */
    private static final long EDGE_MARGIN_PERCENT = 5;

    private final AttachmentProperties attachments;
    private final StorageQuotaProperties quota;
    private final WriteProperties write;
    private final MultipartProperties multipart;

    @PostConstruct
    void check() {
        long maxFile = attachments.maxFileSize().toBytes();

        // A NON-POSITIVE PER-FILE LIMIT IS THE ONE VALUE THAT MAKES EVERY CHECK BELOW VACUOUS —
        // and it is what lets a ZERO QUOTA through, because the comparisons are "at least as big
        // as one legal file" and everything is at least as big as nothing. A zero quota then
        // reaches WorkspaceStorageService.summary's fill percentage as a division by zero, whose
        // double result serialises as a bare `Infinity` token: not a refusal, not a number, but a
        // response body that is not valid JSON at all, which the SPA fails to parse rather than
        // renders. Refused here rather than annotated, because bean validation cannot see it:
        // @Min applies to numeric types and DataSize is not one — Hibernate Validator answers an
        // UnexpectedTypeException, i.e. every boot fails, including the correct ones.
        if (maxFile <= 0) {
            throw new IllegalStateException(
                    "app.attachments.max-file-size is " + maxFile + " bytes. A non-positive "
                    + "per-file limit permits no upload at all, and it also disables the two "
                    + "checks below (every other size is 'at least as big as one legal file'), "
                    + "which lets app.storage.quota.workspace-bytes be zero — and a zero quota "
                    + "makes GET /api/workspaces/{id}/storage answer a fill percentage of "
                    + "Infinity, which is not valid JSON. Set ATTACHMENT_MAX_FILE_SIZE to the "
                    + "largest file this instance should accept (default 20MB).");
        }

        // Checked even when the quota is disabled. A number that is only validated while a
        // switch is on is a number that is wrong the moment somebody turns the switch on — and
        // the switch is one env var, flipped by an operator who will not be re-reading this.
        long quotaBytes = quota.workspaceBytes().toBytes();
        if (quotaBytes < maxFile) {
            throw new IllegalStateException(
                    "app.storage.quota.workspace-bytes (" + quotaBytes + " bytes) is smaller than "
                    + "app.attachments.max-file-size (" + maxFile + " bytes), so no legal file "
                    + "could ever be uploaded into any workspace: every upload would answer 409 "
                    + "STORAGE_QUOTA_EXCEEDED on a workspace that is visibly empty, naming two "
                    + "numbers the reader cannot change. Raise STORAGE_QUOTA_WORKSPACE_BYTES to "
                    + "at least " + maxFile + " bytes, or lower ATTACHMENT_MAX_FILE_SIZE.");
        }

        long budgetBytes = write.uploadBytesPerMinute().toBytes();
        if (budgetBytes < maxFile) {
            throw new IllegalStateException(
                    "app.write.upload-bytes-per-minute (" + budgetBytes + " bytes) is smaller than "
                    + "app.attachments.max-file-size (" + maxFile + " bytes), so a file this "
                    + "instance permits could never be uploaded: the byte budget would refuse it "
                    + "with 429 and a Retry-After, telling the caller to wait for room that a "
                    + "fixed one-minute window can never make. Raise "
                    + "WRITE_UPLOAD_BYTES_PER_MINUTE to at least " + maxFile + " bytes, or lower "
                    + "ATTACHMENT_MAX_FILE_SIZE.");
        }

        // THE SERVLET PARSE CEILING, AT A MARGIN. Both multipart bounds come from
        // ATTACHMENT_MAX_UPLOAD_SIZE, and the smaller of the two is what a single-part upload
        // actually meets, so the comparison takes the min rather than naming one of them. A null
        // or negative value is Spring's "unbounded" (MultipartConfigElement reads -1 that way), and
        // an unbounded parser bounds nothing this check could be wrong about.
        long servletCeiling = Math.min(ceilingOf(multipart.getMaxFileSize()),
                                       ceilingOf(multipart.getMaxRequestSize()));
        // Rounded UP, so the margin does not vanish on a small limit set by a test or a kiosk.
        long required = maxFile + (maxFile * EDGE_MARGIN_PERCENT + 99) / 100;
        if (servletCeiling < required) {
            throw new IllegalStateException(
                    "app.attachments.max-file-size (" + maxFile + " bytes) is not at least "
                    + EDGE_MARGIN_PERCENT + "% below the servlet multipart ceiling ("
                    + servletCeiling + " bytes, the smaller of "
                    + "spring.servlet.multipart.max-file-size and max-request-size, both set from "
                    + "ATTACHMENT_MAX_UPLOAD_SIZE). A per-file limit at or above the parse ceiling "
                    + "is a size this instance advertises and the container refuses at 413 before "
                    + "any handler is mapped. The margin rather than equality is the MB/MiB skew: "
                    + "the bundled Caddy reads the same variable and reads MB as 10^6 where Spring "
                    + "reads 2^20, so at equality the proxy refuses the top ~4.6% of the app-legal "
                    + "range with a bare 413 carrying no Hamstrack body. Raise "
                    + "ATTACHMENT_MAX_UPLOAD_SIZE to at least " + required + " bytes, or lower "
                    + "ATTACHMENT_MAX_FILE_SIZE (the shipped pairing is 20MB against 25MB).");
        }
    }

    /** Spring reads an absent or negative multipart bound as unlimited; so does this. */
    private static long ceilingOf(DataSize size) {
        return size == null || size.toBytes() < 0 ? Long.MAX_VALUE : size.toBytes();
    }
}
