package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The properties every fixture name in this suite now rests on</strong> (HD-329).
 *
 * <p>{@link Unique} replaced a clock in 116 expressions, so a defect in it is a defect in all of
 * them at once. The ticket asked for "a run of the category's tests twice in the same second" as
 * its seal; that proves one member and the host's resolution on one day. These arms assert the
 * property instead — draws taken as fast as the JVM can take them are all distinct, which no clock
 * reading of any resolution can promise.
 */
class UniqueTest {

    /**
     * Draws taken in one tight loop. Sized to be far past any clock's resolution: on this host
     * {@code System.nanoTime()} returned a distinct value on 61% of consecutive calls and 76.6% of
     * its readings were multiples of 100 ns (measured 2026-10-15, 200 000 samples), so a loop of
     * this size built on a clock would collide thousands of times.
     */
    private static final int DRAWS = 50_000;

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{12}-\\d+");

    @Test
    void everyDrawIsDistinctHoweverFastTheyAreTaken() {
        var seen = new HashSet<String>(DRAWS * 2);
        var collisions = new HashSet<String>();
        for (var i = 0; i < DRAWS; i++) {
            var token = Unique.token();
            if (!seen.add(token)) {
                collisions.add(token);
            }
        }

        assertThat(seen).as("the loop really drew %d tokens", DRAWS).hasSize(DRAWS);
        assertThat(collisions)
                .as("""
                    TWO FIXTURE NAMES DREW THE SAME SUFFIX IN ONE RUN.

                    Unique.token() is 48 bits of a fresh random UUID plus a per-JVM counter, and \
                    the counter alone makes a repeat within one JVM impossible. A collision here \
                    means the counter was dropped or the random half is not random, and 116 \
                    fixture expressions rest on it.""")
                .isEmpty();
    }

    /** The shape every caller's column, slug rule and e-mail parser depends on. */
    @Test
    void aTokenIsLowerCaseHexAndACounterAndStaysShort() {
        var token = Unique.token();

        assertThat(token).matches(TOKEN);
        assertThat(Unique.name("WF")).startsWith("WF-").hasSizeLessThan(32);
        assertThat(Unique.slug("ws"))
                .as("a slug feeds a column the product derives a slug into, so only slug-safe "
                    + "characters may appear")
                .matches("[a-z0-9-]+");
        assertThat(Unique.email("u")).matches("[a-z0-9+._-]+@example\\.com");
        assertThat(token.length())
                .as("shorter than the 19-digit nanoTime reading it replaced, which is what makes "
                    + "every rewritten name fit the column it already fitted")
                .isLessThan(19);
    }

    /**
     * A caller composes a local part from an enum name or a role ({@code "adm-" + SystemRole.ADMIN}
     * was one of the expressions this replaced), so the fold is the helper's and it is
     * {@link Locale#ROOT}'s — named here under {@code tr-TR}, where a default-locale fold turns
     * {@code ADMIN} into {@code admın} and the address stops matching the one the test logs in
     * with.
     */
    @Test
    @DefaultLocale("tr-TR")
    void anEmailAndASlugAreFoldedWithTheRootLocaleAndNotTheDefault() {
        assertThat(Unique.email("ADMIN"))
                .as("under tr-TR a default-locale fold yields a dotless ı")
                .startsWith("admin-");
        assertThat(Unique.slug("WS-ID")).startsWith("ws-id-");
    }

    /**
     * <strong>The one source of uniqueness reads no clock.</strong> Asserted on the bytes of
     * {@code Unique.java} rather than argued: the seal over the suite would flag a clock here as an
     * offender like any other, but this class is what every other fixture now delegates to, so it
     * says so in its own test as well.
     */
    @Test
    void theHelperItselfReadsNoClock() throws IOException {
        var unit = SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES,
                        Set.of()).stream()
                .filter(candidate -> candidate.type().equals("Unique"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "Unique.java is not under " + SourceCallSites.TEST_SOURCES
                        + " — this arm would assert nothing"));

        var clocks = SourceCallSites.mentions(unit, List.of("System.nanoTime()",
                "System.currentTimeMillis()", "Instant.now()", "LocalDateTime.now()",
                "Clock.systemUTC()", "new Date()"));
        var random = SourceCallSites.mentions(unit, List.of("UUID.randomUUID()"));

        assertThat(clocks.stream().map(SourceCallSites.Mention::site).toList())
                .as("the shared source of every fixture name must not read a clock in code; its "
                    + "javadoc explains the defect at length, and prose is not code")
                .isEmpty();
        assertThat(random).as("and it must really draw a random UUID — if this is empty the arm "
                             + "above is satisfied by a file that does nothing").isNotEmpty();
    }
}
