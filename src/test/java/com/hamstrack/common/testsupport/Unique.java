package com.hamstrack.common.testsupport;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <strong>The one source of a unique fixture name in this suite</strong> (HD-329).
 *
 * <p>The suite database is shared and never emptied, so a fixture name that must be unique must be
 * unique <em>across runs</em> as well as within one. Fifty files answered that with a clock —
 * {@code "WF-" + (System.nanoTime() % 1000000)} and its relatives — and a clock reading is only as
 * unique as the clock's <em>resolution</em>, which is a property of the host and not of the code.
 *
 * <h2>Why a modulus is the defect and not a shortening</h2>
 * {@code System.nanoTime()} on this host is quantised: of the 105 global {@code WF-%} workflow rows
 * the reported fixture left behind, 103 end in {@code 00} (2026-10-15). That ratio is taken over
 * the rows that can actually <em>collide</em> — one global namespace, one fixture — and not over
 * every {@code WF-%} row in the database, which mixes that family with workspace-scoped rows that
 * collide with nothing and answers a different question with an equally plausible number. So
 * {@code % 1000000} does not leave a
 * namespace of a million — it leaves one of ten thousand, because the modulus discards exactly the
 * high bits that differ between runs and keeps the low ones the host quantises. At 105 occupants
 * that is a one-in-a-hundred chance per run of a {@code 409} on a product response that is
 * <em>correct</em>, read as a product defect every time it fires. An untruncated reading is far
 * safer and still platform-derived: its namespace is the host's resolution times its uptime range,
 * neither of which is a fact about this repository.
 *
 * <h2>What this gives instead</h2>
 * {@link #token()} is {@value #RANDOM_HEX} hex characters of a fresh random {@link UUID} — 48 bits,
 * so a draw against the ~110 000 fixture rows of one prefix already in the local database collides
 * with probability about 4 in 10<sup>10</sup> — followed by a per-JVM counter, which makes two
 * tokens from one run distinct by construction rather than by probability. Both halves are
 * platform-independent: nothing here reads a clock, a host timer, a process id or a file system.
 *
 * <p>Shorter than what it replaces (about 15 characters against a 19-digit {@code nanoTime}), which
 * matters because several fixtures feed these names into a bounded column.
 *
 * <p>Sealed by {@code FixtureNameUniquenessTest}, which refuses any fixture-name expression in
 * {@code src/test/java} that reads a clock, and by {@code UniqueTest} for the properties above.
 */
public final class Unique {

    /**
     * Hex characters of a random UUID kept in a token. Twelve is 48 bits: enough that the
     * birthday arithmetic against a shared database of a few hundred thousand fixture rows is
     * negligible, short enough that every rewritten name got shorter than the {@code nanoTime} it
     * replaced. The existing {@code UUID.randomUUID().toString().substring(0, 8)} slug idiom used
     * 32 bits and leaned on a truncated clock for the rest; this needs no second source.
     */
    static final int RANDOM_HEX = 12;

    /**
     * Distinguishes two tokens drawn in the same run without consulting anything outside this JVM.
     * A counter cannot carry uniqueness across runs — that is the random half's job — and the
     * random half cannot promise distinctness within a run, only improbability of collision.
     */
    private static final AtomicLong WITHIN_RUN = new AtomicLong();

    private Unique() {
    }

    /** {@code "3f9a1c2b4d5e-7"} — the primitive, for a fixture whose name shape is its own. */
    public static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_HEX)
               + "-" + WITHIN_RUN.incrementAndGet();
    }

    /** {@code name("WF")} → {@code "WF-3f9a1c2b4d5e-7"}: a display name unique across runs. */
    public static String name(String prefix) {
        return prefix + "-" + token();
    }

    /**
     * {@code slug("ws")} → {@code "ws-3f9a1c2b4d5e-7"}: lower case and slug-safe, for a column the
     * product itself derives a slug into.
     */
    public static String slug(String prefix) {
        return (prefix + "-" + token()).toLowerCase(Locale.ROOT);
    }

    /**
     * {@code email("u")} → {@code "u-3f9a1c2b4d5e-7@example.com"}, folded with {@link Locale#ROOT}
     * because a caller composes the local part from an enum name or a role and the product stores
     * what it is given.
     */
    public static String email(String localPart) {
        return (localPart + "-" + token() + "@example.com").toLowerCase(Locale.ROOT);
    }
}
