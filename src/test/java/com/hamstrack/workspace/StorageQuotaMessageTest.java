package com.hamstrack.workspace;

import com.hamstrack.workspace.exception.StorageQuotaExceededException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The quota refusal spells a size the same way the rest of the product does</strong>
 * (HD-191 R4, L3).
 *
 * <p>A quota is configured as a round number, so the figure this sentence carries most often is
 * the ceiling itself — and it lands on a panel that is already showing that ceiling, rendered by
 * the SPA. {@code 10.0 GB} in the refusal beside {@code 10 GB} in the figures line is one fact
 * printed two ways, and the reader's first question is which of the two is the real limit. The
 * spec's own example copy is the reference and it says {@code 10 GB}.
 *
 * <p>No Spring context: the formatter is a pure function of a long, and the behavioural half —
 * that the refusal is a 409 carrying these numbers and prescribing nothing — is
 * {@link StorageQuotaTest}. This file exists because that one uses a quota of 10 000 bytes, which
 * renders as {@code 9.8 KB} and can never observe a trailing zero.
 */
class StorageQuotaMessageTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void aRoundSizeLosesItsTrailingZeroAndAFractionalOneKeepsItsDecimal() {
        var round = new StorageQuotaExceededException(10 * GB, 10 * GB, 1024).getMessage();

        assertThat(round)
                .as("""
                    A ROUND SIZE MUST READ "10 GB", NOT "10.0 GB". The quota is configured as a \
                    round number, so this is the figure the sentence carries most often, and it \
                    appears beside the SPA's own rendering of the same number on the same panel. \
                    Two adjacent lines disagreeing by one character make a reader ask which is \
                    the real ceiling.""")
                .contains("10 GB")
                .doesNotContain("10.0 GB");

        var fractional = new StorageQuotaExceededException(10 * GB, 3689348814L, 1024).getMessage();

        assertThat(fractional)
                .as("""
                    AND THE DECIMAL IS NOT SIMPLY DROPPED. Only an exact value loses a digit that \
                    carries nothing; a used-figure of 3.4 GB rendered as "3 GB" would be a \
                    different number, and this sentence is the only place many readers ever see \
                    how full the workspace is.""")
                .contains("3.4 GB");
    }

    @Test
    void bytesUnderAKilobyteStayIntegerBytes() {
        assertThat(new StorageQuotaExceededException(1000, 900, 200).getMessage())
                .as("the unit-zero branch prints the exact byte count — it is not a rounded "
                    + "quantity, and the trimming above must not reach into it")
                .contains("1000 B")
                .contains("900 B")
                .contains("200 B");
    }
}
