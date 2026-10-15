package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>{@link FixedWindow}'s behaviour, driven against a scripted window instead of a clock</strong>
 * (HD-334, seal 1 of 2).
 *
 * <p>The ticket asked for "a run with the clock forced to a boundary, seen red against the current
 * fixtures". There is no clock seam in the limiter family and there must not be one, so the forced
 * boundary is driven where it belongs: the package-private seam takes the window as a
 * {@code LongSupplier}, and every case below scripts the exact sequence of keys that a boundary
 * produces. That makes "what happens when it rolls" a fact on every suite run rather than a
 * property observed once a month in a failed build.
 *
 * <p>Each case names the plant it was seen red against; the plants are listed in the HD-334 report
 * and were executed before this file was believed.
 */
class FixedWindowTest {

    /** A supplier that returns each scripted key in turn and repeats the last one for ever. */
    private static final class ScriptedWindow implements LongSupplier {

        private final long[] keys;
        private int next;

        private ScriptedWindow(long... keys) {
            this.keys = keys;
        }

        @Override
        public long getAsLong() {
            return keys[Math.min(next++, keys.length - 1)];
        }
    }

    /**
     * A window that has rolled by the time anybody looks again — every read returns a new key, so
     * no attempt count makes the block conclusive. The exhaustion cases use this rather than a
     * finite script, because a script that runs out starts repeating its last value and the block
     * then succeeds on the attempt after the script ends: the bound would be "proven" by the end
     * of the fixture's own input instead of by the bound.
     */
    private static LongSupplier alwaysRolling() {
        var key = new AtomicInteger();
        return key::incrementAndGet;
    }

    /**
     * <strong>E1 — no roll and a passing body: the body runs exactly once.</strong> The guard's
     * whole affordability argument is this one: it costs the suite nothing when nothing happened.
     *
     * <p>Plant it was seen red against: P3, {@code if (after != before)} forced to {@code true},
     * which runs the body five times and then refuses.
     */
    @Test
    void aBlockThatDoesNotStraddleRunsItsBodyExactlyOnce() throws Exception {
        var runs = new AtomicInteger();
        var witnessed = new ArrayList<String>();

        FixedWindow.inOne(FixedWindow.Unit.MINUTE, new ScriptedWindow(10, 10),
                FixedWindow.MAX_ATTEMPTS, witnessed::add, attempt -> runs.incrementAndGet());

        assertThat(runs.get())
                .as("a block that did not straddle must be believed, and believed once — a guard "
                    + "that re-ran a clean body would multiply the suite time of every throttle "
                    + "test in the tree")
                .isEqualTo(1);
        assertThat(witnessed)
                .as("and it must say nothing: a witness line on the happy path would make the "
                    + "real one unreadable")
                .isEmpty();
    }

    /**
     * <strong>E2 — no roll and a failing body: the member's own {@code AssertionError} is rethrown
     * unchanged, once.</strong>
     *
     * <p>This is what keeps the fixture from hiding a real defect. A broken member with no roll is
     * indistinguishable from a broken unwrapped member, including its message and its stack.
     *
     * <p>Plant: P2, the roll check forced to {@code false} — the error then surfaces wrapped in the
     * fixture's exhaustion message after five runs.
     */
    @Test
    void aFailingBlockThatDoesNotStraddleSurfacesItsOwnFailureImmediately() {
        var runs = new AtomicInteger();
        var mine = new AssertionError("the fourth report must be refused");

        assertThatThrownBy(() -> FixedWindow.inOne(FixedWindow.Unit.MINUTE,
                new ScriptedWindow(10, 10), FixedWindow.MAX_ATTEMPTS, line -> { },
                attempt -> {
                    runs.incrementAndGet();
                    throw mine;
                }))
                .as("the surfaced error must be the member's own object, so a reader of a red "
                    + "build sees the assertion they wrote and not a fixture's paraphrase")
                .isSameAs(mine);
        assertThat(runs.get())
                .as("a real defect with no roll is not retried — retrying it would turn every "
                    + "genuine failure into a five-times-slower genuine failure, and would make a "
                    + "flaky product look like a flaky test")
                .isEqualTo(1);
    }

    /**
     * <strong>E3 — a rolled attempt whose body FAILED is discarded and re-run, and the witness
     * carries what it said.</strong> The direction the ticket was filed for, and
     * <strong>the one path on which this fixture can mask a defect at all</strong>: a body that
     * fails under a roll and passes on the retry returns green, at a measured ~0.3% for a
     * mean-length body and ~1.7% for the longest in the category. Every other shape rethrows or
     * exhausts.
     *
     * <p>So the line is asserted byte-for-byte including the discarded message. Until HD-334's
     * third round it read {@code (body failed)} and nothing else — the {@code AssertionError}
     * appeared in no stream, because {@code lastFailure.getMessage()} is printed only on
     * exhaustion, which this path never reaches. An intermittent product defect absorbed here left
     * a green run whose only trace said that <em>something</em> had been thrown away.
     *
     * <p>Plant it was seen red against: the message dropped from the discard line, i.e. the shape
     * that shipped.
     */
    @Test
    void aRolledAttemptThatFailedIsDiscardedAndReRun() throws Exception {
        var runs = new AtomicInteger();
        var witnessed = new ArrayList<String>();

        FixedWindow.inOne(FixedWindow.Unit.MINUTE, new ScriptedWindow(10, 11, 11, 11),
                FixedWindow.MAX_ATTEMPTS, witnessed::add, attempt -> {
                    if (runs.incrementAndGet() == 1) {
                        throw new AssertionError("expected 429 but was 200");
                    }
                });

        assertThat(runs.get()).isEqualTo(2);
        assertThat(witnessed)
                .as("the discarded attempt is witnessed, the witness says it FAILED, and it names "
                    + "the failure — a reader of a green run has to be able to see not only that "
                    + "a verdict was thrown away but WHICH one, or this branch is a drop with a "
                    + "witness that omits the thing dropped")
                .containsExactly(FixedWindow.WITNESS + " FixedWindowTest#"
                                 + "aRolledAttemptThatFailedIsDiscardedAndReRun attempt 1/5 "
                                 + "discarded - epoch MINUTE rolled 10 -> 11 during the block "
                                 + "(body failed: expected 429 but was 200); re-running on a "
                                 + "fresh key");
    }

    /**
     * And the two edges of that fragment: a failure with no message at all, and one longer than a
     * console line.
     *
     * <p>An AssertJ failure starts with the {@code as(...)} description, and the descriptions in
     * this tree run to paragraphs — so the witness takes the FIRST line and cuts it at
     * {@link FixedWindow#WITNESSED_MESSAGE_CHARS}. A witness that reproduced a paragraph would
     * bury itself, and one that spanned lines would stop being a line.
     */
    @Test
    void theWitnessedFragmentIsOneBoundedLineOrSaysThereWasNone() throws Exception {
        var witnessed = new ArrayList<String>();
        var runs = new AtomicInteger();

        FixedWindow.inOne(FixedWindow.Unit.MINUTE, new ScriptedWindow(10, 11, 11, 11),
                FixedWindow.MAX_ATTEMPTS, witnessed::add, attempt -> {
                    if (runs.incrementAndGet() == 1) {
                        throw new AssertionError();
                    }
                });

        assertThat(witnessed).singleElement().asString()
                .as("a bare AssertionError has a null message, and `(body failed: null)` reads "
                    + "like a product value")
                .endsWith("(body failed with no message); re-running on a fresh key");

        witnessed.clear();
        runs.set(0);
        var paragraph = "[the first line, which an AssertJ description makes this long] "
                        + "x".repeat(300) + System.lineSeparator() + "expected: <429>";

        FixedWindow.inOne(FixedWindow.Unit.MINUTE, new ScriptedWindow(10, 11, 11, 11),
                FixedWindow.MAX_ATTEMPTS, witnessed::add, attempt -> {
                    if (runs.incrementAndGet() == 1) {
                        throw new AssertionError(paragraph);
                    }
                });

        assertThat(witnessed).singleElement().asString()
                .as("first line only, cut at %d: the second line of a multi-line description must "
                    + "not reach the witness at all", FixedWindow.WITNESSED_MESSAGE_CHARS)
                .contains("(body failed: "
                          + paragraph.substring(0, FixedWindow.WITNESSED_MESSAGE_CHARS) + "...)")
                .doesNotContain("expected: <429>")
                .doesNotContain(System.lineSeparator());
    }

    /**
     * <strong>E4 — a rolled attempt whose body PASSED is discarded and re-run.</strong>
     *
     * <p><strong>The clause the helper this fixture replaces did not have</strong>, and the reason
     * a faithful generalisation of that helper would have shipped half the fix.
     * {@code WriteBudgetTest} asserts, immediately after a 429, that the same principal's
     * {@code GET} on the same path still answers 200 — "if this ever fails, every board and issue
     * page on the instance is being charged to the write budget". A roll between those two
     * requests makes that 200 true whether or not the interceptor is verb-conditioned, and nothing
     * throws, so a {@code catch (AssertionError)}-gated retry is green over a destroyed assertion.
     *
     * <p>Plant: <strong>P1</strong> — gate the roll check behind {@code caught != null}, i.e.
     * restore {@code withinOneWindow}'s exact semantics. This case is the only one in the file that
     * reds against it, which is what makes it the plant that proves this is a generalisation and
     * not a copy.
     */
    @Test
    void aRolledAttemptThatPassedIsDiscardedAndReRun() throws Exception {
        var runs = new AtomicInteger();
        var witnessed = new ArrayList<String>();

        FixedWindow.inOne(FixedWindow.Unit.MINUTE, new ScriptedWindow(10, 11, 11, 11),
                FixedWindow.MAX_ATTEMPTS, witnessed::add, attempt -> runs.incrementAndGet());

        assertThat(runs.get())
                .as("A PASS THAT STRADDLED IS NOT A PASS. The false-green direction is measured, "
                    + "not hypothetical: a positive assertion made on the same principal "
                    + "immediately after its window was exhausted is true for free once the "
                    + "window has rolled, and no exception is thrown for a failure-gated retry to "
                    + "notice.")
                .isEqualTo(2);
        assertThat(witnessed).hasSize(1);
        assertThat(witnessed.get(0))
                .as("and the witness distinguishes the two directions, because 'this member was "
                    + "re-run because its PASS was roll-assisted' is the only evidence this arm "
                    + "works at all")
                .contains("body passed");
    }

    /**
     * <strong>E5 — bounded, and the refusal prescribes an action its reader can perform.</strong>
     *
     * <p>An always-rolling window gives up after {@code MAX_ATTEMPTS} and says to shorten the body.
     * It must not say "raise the attempt count": a block comparable in length to its own window
     * straddles at a rate no count outruns, so that advice is unperformable in the only situation
     * that produces it.
     *
     * <p>Plant: P4, the {@code index <= maxAttempts} bound removed — the body then runs for ever
     * and this method's timeout is what fails.
     *
     * <p><strong>{@code SEPARATE_THREAD}, and that is not decoration — measured.</strong> JUnit's
     * default {@code ThreadMode.SAME_THREAD} runs the body on the calling thread and only
     * <em>compares elapsed time after it returns</em>, so against the unbounded plant the first
     * run of this file did not fail in 20 seconds: it hung, and had to be killed by hand. A
     * timeout that cannot interrupt is not a bound on an infinite loop, and a suite whose guard
     * against one is such a timeout hangs the whole build instead of reporting.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anAlwaysRollingWindowIsRefusedAfterFiveAttemptsWithAPerformableRemedy() {
        var runs = new AtomicInteger();

        // A supplier that never repeats a value, so there is no attempt count at which this block
        // would stop straddling: with the bound removed it runs until the @Timeout kills it.
        assertThatThrownBy(() -> FixedWindow.inOne(FixedWindow.Unit.MINUTE, alwaysRolling(),
                FixedWindow.MAX_ATTEMPTS, line -> { }, attempt -> runs.incrementAndGet()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("shorten it")
                .hasMessageContaining("Raising the attempt count cannot help")
                .hasMessageContaining("all " + FixedWindow.MAX_ATTEMPTS + " attempts");

        assertThat(runs.get())
                .as("bounded at %d — an unbounded retry on a body that always straddles is a "
                    + "suite that hangs instead of a suite that reports", FixedWindow.MAX_ATTEMPTS)
                .isEqualTo(FixedWindow.MAX_ATTEMPTS);
    }

    /**
     * And when every discarded attempt PASSED, the exhaustion refusal says so rather than reading
     * like a failure with no message: there is no member error to append, and a reader who sees
     * five passes thrown away needs to be told why that is a red build.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anExhaustedBlockWhoseAttemptsAllPassedSaysThatTheyPassed() {
        assertThatThrownBy(() -> FixedWindow.inOne(FixedWindow.Unit.MINUTE, alwaysRolling(),
                FixedWindow.MAX_ATTEMPTS, line -> { }, attempt -> { }))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Every discarded attempt PASSED");
    }

    /**
     * An exhausted block whose last attempt failed surfaces <em>both</em>: the straddle note and
     * the member's own message. A genuinely broken member that also straddles must read as broken.
     */
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void anExhaustedBlockKeepsTheMembersOwnFailure() {
        assertThatThrownBy(() -> FixedWindow.inOne(FixedWindow.Unit.MINUTE, alwaysRolling(),
                FixedWindow.MAX_ATTEMPTS, line -> { }, attempt -> {
                    throw new AssertionError("the fourth report must be refused");
                }))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("shorten it")
                .hasMessageContaining("the fourth report must be refused")
                .hasCauseInstanceOf(AssertionError.class);
    }

    /**
     * <strong>E7 — every attempt gets a distinct {@link FixedWindow.Attempt#suffix()}.</strong>
     *
     * <p>Not a convenience. A roll <em>replaces</em> the window, so the spends the discarded
     * attempt already made land in the new one; a retry on the same key starts from a non-zero
     * count and the "the first N are admitted" half of the member fails instead. A body whose key
     * is a literal builds it from this suffix.
     *
     * <p>Plant: P5, {@code Attempt.index} made constant.
     */
    @Test
    void everyAttemptIsHandedADistinctSuffix() throws Exception {
        var suffixes = new ArrayList<String>();

        FixedWindow.inOne(FixedWindow.Unit.MINUTE, new ScriptedWindow(10, 11, 12, 13, 13, 13),
                FixedWindow.MAX_ATTEMPTS, line -> { },
                attempt -> suffixes.add(attempt.suffix()));

        assertThat(suffixes)
                .as("a same-key retry starts from the count the discarded attempt left in the NEW "
                    + "window, so a body that cannot vary its key is not retryable at all")
                .doesNotHaveDuplicates()
                .containsExactly("1", "2", "3");
    }

    /**
     * <strong>E6 — a non-{@code AssertionError} propagates on attempt 1.</strong> A broken test is
     * not a straddle: an exception out of MockMvc or a fixture is a defect in the test, and
     * retrying it four more times would turn one legible stack into five.
     *
     * <p>Plant: P6, the catch widened to {@code Throwable}.
     */
    @Test
    void aThrowableThatIsNotAnAssertionErrorPropagatesImmediately() {
        var runs = new AtomicInteger();

        assertThatThrownBy(() -> FixedWindow.inOne(FixedWindow.Unit.MINUTE,
                new ScriptedWindow(10, 11, 11), FixedWindow.MAX_ATTEMPTS, line -> { },
                attempt -> {
                    runs.incrementAndGet();
                    throw new IllegalStateException("the holding interceptor never arrived");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the holding interceptor never arrived");

        assertThat(runs.get()).isEqualTo(1);
    }

    /**
     * <strong>The witness line, exactly</strong> — AC-6, and the drill for the whole observability
     * contract. A fixture that retries in silence is a mechanism that cannot be shown to work, and
     * the one number that tells "the guard is on the hot members" from "the guard is present but
     * never exercised" is the count of these lines per suite run.
     *
     * <p>Plant: P7, the witness call dropped.
     */
    @Test
    void theWitnessLineNamesTheMemberTheAttemptBothWindowsAndTheVerdict() throws Exception {
        var witnessed = new ArrayList<String>();
        var runs = new AtomicInteger();

        FixedWindow.inOne(FixedWindow.Unit.MINUTE, new ScriptedWindow(29_435_281L, 29_435_282L,
                        29_435_282L, 29_435_282L), FixedWindow.MAX_ATTEMPTS, witnessed::add,
                attempt -> runs.incrementAndGet());

        assertThat(witnessed)
                .as("the witness reaches surefire's <system-out> and the console (no "
                    + "redirectTestOutputToFile in pom.xml), so this line is how a straddle is "
                    + "seen at all — in a run that stayed green")
                .containsExactly(FixedWindow.WITNESS + " FixedWindowTest#"
                                 + "theWitnessLineNamesTheMemberTheAttemptBothWindowsAndTheVerdict"
                                 + " attempt 1/5 discarded - epoch MINUTE rolled 29435281 "
                                 + "-> 29435282 during the block (body passed); re-running on "
                                 + "a fresh key");
        assertThat(witnessed.get(0))
                .as("ASCII only: the console this is read from is not UTF-8, and the first run of "
                    + "this fixture printed its roll as `29866442 ? 29866443`")
                .matches("\\p{ASCII}+");
    }

    /**
     * The unit is a parameter because an hour- or day-keyed member would otherwise be re-run on
     * every harmless minute roll — the narrower unit is a correct superset (3600 and 86400 are
     * whole multiples of 60) but a wasteful one. The arithmetic is asserted rather than stated.
     */
    @Test
    void theUnitsNestSoTheNarrowestCheckWouldBeASupersetOfTheOthers() {
        assertThat(FixedWindow.Unit.HOUR.seconds() % FixedWindow.Unit.MINUTE.seconds())
                .as("an hour boundary is also a minute boundary")
                .isZero();
        assertThat(FixedWindow.Unit.DAY.seconds() % FixedWindow.Unit.HOUR.seconds())
                .as("and a day boundary is also an hour boundary")
                .isZero();
        assertThat(List.of(FixedWindow.Unit.values()))
                .as("the units this product actually keys counters on — a fifth family with a new "
                    + "width is a deliberate edit here and in FixedWindowCoverageTest's A2 arm")
                .containsExactly(FixedWindow.Unit.MINUTE, FixedWindow.Unit.HOUR,
                        FixedWindow.Unit.DAY);
    }

    /**
     * And the live wall-clock path agrees with the scripted one: {@link FixedWindow#inOneMinute}
     * runs its body and returns. Cheap, and it is the only case that proves the public entry point
     * is wired to the real clock at all — every other case here drives the seam.
     */
    @Test
    @Timeout(30)
    void thePublicEntryPointRunsAgainstTheRealClock() throws Exception {
        var runs = new AtomicInteger();

        FixedWindow.inOneMinute(attempt -> runs.incrementAndGet());

        assertThat(runs.get())
                .as("a body of microseconds against a minute window — one run, unless this "
                    + "assertion is the one in a million that straddled, in which case it is two")
                .isBetween(1, FixedWindow.MAX_ATTEMPTS);
    }
}
