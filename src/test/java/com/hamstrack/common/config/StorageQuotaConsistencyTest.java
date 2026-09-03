package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>The boot refuses a configuration in which no legal file could ever be uploaded</strong>
 * (HD-191, AC-11).
 *
 * <p>Every failure it catches is a 4xx at runtime, which is exactly why they need a startup check:
 * a 409 on a visibly empty workspace, a 429 telling the caller to wait for room that a fixed
 * window can never make, and a proxy 413 with no error body at all are clean refusals that appear
 * in no error rate and log nothing an operator would find. The mistake is cheap to make — lower
 * one number, forget the other — and expensive to notice.
 *
 * <p>A plain unit test rather than a context-fails-to-start one. The behaviour is arithmetic over
 * four records, {@code @PostConstruct} is what runs it, and spinning a Spring context per
 * misconfiguration would buy nothing but four more contexts and a slower suite.
 */
class StorageQuotaConsistencyTest {

    @Test
    void aQuotaSmallerThanOnePermittedFileRefusesTheBoot() {
        assertThatThrownBy(() -> check("20MB", "10MB", "250MB"))
                .as("""
                    A QUOTA BELOW THE PER-FILE LIMIT MUST NOT BOOT.

                    Every upload into every workspace would answer 409 STORAGE_QUOTA_EXCEEDED on a \
                    workspace that is visibly empty, naming two numbers the reader cannot change \
                    and prescribing (correctly) no action at all. It is a clean 4xx, so it appears \
                    in no error rate; the only signal is hamstrack.storage.quota_refused, and only \
                    if somebody built the alert.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.storage.quota.workspace-bytes")
                .hasMessageContaining("STORAGE_QUOTA_WORKSPACE_BYTES");
    }

    @Test
    void aByteBudgetSmallerThanOnePermittedFileRefusesTheBoot() {
        assertThatThrownBy(() -> check("20MB", "100GB", "10MB"))
                .as("""
                    A BYTE BUDGET BELOW THE PER-FILE LIMIT MUST NOT BOOT.

                    The worse of the two, because of what it says: 429 with a Retry-After tells the \
                    caller that waiting helps, and a fixed one-minute window that is smaller than \
                    the file will refuse the identical retry for ever. A refusal that prescribes an \
                    action which cannot work is the failure this project has shipped three times.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.write.upload-bytes-per-minute")
                .hasMessageContaining("WRITE_UPLOAD_BYTES_PER_MINUTE");
    }

    /**
     * <strong>Checked even when the quota is disabled.</strong> A number validated only while a
     * switch is on is a number that is wrong the moment somebody turns the switch on — and the
     * switch is one environment variable, flipped by an operator who will not be re-reading the
     * arithmetic.
     */
    @Test
    void theQuotaBoundIsCheckedEvenWhenTheQuotaIsDisabled() {
        assertThatThrownBy(() -> check("20MB", "10MB", "250MB", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.storage.quota.workspace-bytes");
    }

    /**
     * Equal is admissible: a quota exactly the size of one permitted file admits exactly one file,
     * which is strange but coherent. Refusing it would be this check inventing a policy rather than
     * catching an impossibility.
     */
    @Test
    void exactlyEqualIsAccepted() {
        assertThatCode(() -> check("20MB", "20MB", "20MB")).doesNotThrowAnyException();
        assertThatCode(() -> check("20MB", "100GB", "250MB")).doesNotThrowAnyException();
    }

    /**
     * <strong>The relation every operator-facing document has always asserted and nothing checked.</strong>
     *
     * <p>A per-file limit at the servlet parse ceiling is refused, and refused at a MARGIN rather
     * than at equality, because that ceiling is not enforced only by the servlet: the bundled Caddy
     * reads the same {@code ATTACHMENT_MAX_UPLOAD_SIZE} and reads {@code MB} as 10^6 where Spring
     * reads 2^20. At equality the proxy is ~4.63% tighter than the app, and that top slice of the
     * app-legal range is refused at the edge with a bare 413 carrying no Hamstrack body — a size
     * the product advertises and nothing in the product can explain.
     *
     * <p>{@code 25MB} against {@code 25MB} is the pairing an operator reaches for, which is why it
     * is the case written here.
     */
    @Test
    void aPerFileLimitAtTheServletCeilingRefusesTheBoot() {
        assertThatThrownBy(() -> check("25MB", "100GB", "250MB", true, "25MB"))
                .as("""
                    A PER-FILE LIMIT AT THE PARSE CEILING MUST NOT BOOT.

                    The edge and the servlet read one variable and they do not read it the same \
                    way: Caddy's MB is 10^6 and Spring's is 2^20. Equal values leave 4.63% of the \
                    app-legal size range answering a proxy 413 with no error body, on an instance \
                    whose own settings say the file is fine.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ATTACHMENT_MAX_UPLOAD_SIZE")
                .hasMessageContaining("spring.servlet.multipart.max-file-size");
    }

    /**
     * The SMALLER of the two multipart bounds is what a single-part upload meets, so the check
     * takes the min rather than naming one of them — a {@code max-request-size} lowered on its own
     * is the same misconfiguration wearing the other property's name.
     */
    @Test
    void theSmallerMultipartBoundIsTheOneCompared() {
        var multipart = new MultipartProperties();
        multipart.setMaxFileSize(DataSize.parse("25MB"));
        multipart.setMaxRequestSize(DataSize.parse("20MB"));
        assertThatThrownBy(() -> new StorageQuotaConsistency(
                new AttachmentProperties(DataSize.parse("20MB"), List.of("txt")),
                new StorageQuotaProperties(true, DataSize.parse("100GB"), 80, ""),
                new WriteProperties(180, DataSize.parse("250MB")),
                multipart).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-request-size");
    }

    private void check(String maxFile, String quota, String uploadBudget) {
        check(maxFile, quota, uploadBudget, true);
    }

    private void check(String maxFile, String quota, String uploadBudget, boolean quotaEnabled) {
        check(maxFile, quota, uploadBudget, quotaEnabled, "25MB");
    }

    private void check(String maxFile, String quota, String uploadBudget, boolean quotaEnabled,
                       String servletCeiling) {
        var multipart = new MultipartProperties();
        multipart.setMaxFileSize(DataSize.parse(servletCeiling));
        multipart.setMaxRequestSize(DataSize.parse(servletCeiling));
        new StorageQuotaConsistency(
                new AttachmentProperties(DataSize.parse(maxFile), List.of("txt")),
                new StorageQuotaProperties(quotaEnabled, DataSize.parse(quota), 80, ""),
                new WriteProperties(180, DataSize.parse(uploadBudget)),
                multipart)
                .check();
    }
}
