package com.hamstrack.common.testsupport;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * <strong>A block that spends a fixed window and asserts something about the count, made
 * inconclusive rather than believed when a window boundary falls inside it</strong> (HD-334).
 *
 * <h2>The hazard</h2>
 * Every throttle in this product is a <em>fixed</em> window: the counter is keyed on
 * {@code floor(wallclock / unit)} and the window object is <strong>replaced</strong> when the key
 * changes ({@code PerPrincipalMinuteBudget}, {@code RateLimitService}, {@code CspReportBudget},
 * {@code InviteSenderVolumeBudget}). A test that spends N units and asserts that the (N+1)-th is
 * refused is therefore correct only if no boundary falls between its first spend and its last
 * assertion — and the failure mode is <strong>phase</strong>, not duration. "The body takes
 * milliseconds and the window is a minute" bounds the <em>probability</em> and establishes nothing
 * about any single run; four full-suite runs between 2026-09-17 and 2026-10-14 died on it, each on
 * unrelated work, each costing a ~50-minute re-run, and each read at first as a product defect.
 *
 * <h2>What this does, and the one clause that is not obvious</h2>
 * The block is run with the window key read immediately before and immediately after. If the key
 * did not change, the verdict stands — the body ran exactly once and the suite paid nothing. If it
 * did change, the attempt is <strong>discarded and re-run</strong>, and that happens
 * <strong>whether the body passed or failed</strong>.
 *
 * <p>The passing direction is the one a faithful copy of the helper this class replaces would have
 * lost. {@code DatabaseBusyAuthBudgetTest.withinOneWindow} retried only inside
 * {@code catch (AssertionError)}, which cannot see a measured false green:
 * {@code WriteBudgetTest} exhausts a principal's write budget, asserts the 429, and then asserts
 * that the same principal's {@code GET} on the same path is still {@code 200} — "if this ever
 * fails, every board and issue page on the instance is being charged to the write budget". A roll
 * between those two requests makes that {@code 200} true whether or not the interceptor is
 * verb-conditioned. Nothing throws, so a failure-gated retry is green over a destroyed assertion.
 * Hence the check here is unconditional.
 *
 * <h2>Why a retry needs a fresh key</h2>
 * A roll does not merely reset the count — it <em>replaces</em> the window, so the spends the body
 * had already made land in the new one. A same-key retry therefore starts from a non-zero count and
 * the "the first N are admitted" half of the member fails instead. Most members get this for free
 * (their {@code newProject()} / {@code user()} mint a unique principal per run, so the key is new
 * each time); a body whose key is a literal — an IP address, an e-mail — must build it from
 * {@link Attempt#suffix()}, and a body that accumulates per-key state outside the window must vary
 * that too (a login burst re-run on one address crosses the per-account backoff threshold and
 * answers 429 where the member expects 401 — measured against
 * {@code RateLimitService.recordLoginFailure}, which is fed for unknown addresses on purpose).
 *
 * <h2>What it is not</h2>
 * Not a clock seam. Production keeps {@code Instant.now()}: a test-controllable clock on every rate
 * limiter is a production fork on a security-relevant surface, bought for a test's benefit, and the
 * members do not want to control time — they want their block not to straddle. The injectable
 * notion of "the current window" lives here, where {@code FixedWindowTest} can drive it and no
 * production path can reach it.
 *
 * <p>Not named {@code *Test}: Surefire's includes ({@code Test*}, {@code *Test}, {@code *Tests},
 * {@code *TestCase}) are the test-tree coverage guard's bound, so a support class named that way
 * would be <em>demanded</em> to contain tests.
 *
 * <p>Not a mitigation for a window that cannot be re-keyed. An instance-wide budget
 * ({@code CspReportBudget}'s second ceiling) hands a fresh key the window it was already spending,
 * so such a member asserts a bound that survives a <em>single roll</em> instead and declares
 * {@link NoFixedWindow.Why#ROLL_INVARIANT_ASSERTION}. Per roll, not "across any split": on the
 * live example 20 reports against a ceiling of 5 refuse 10 across two windows, 5 across three and
 * 0 across four, so a bound claimed for any number of splits is a bound of zero.
 *
 * @see NoFixedWindow
 */
public final class FixedWindow {

    /**
     * How many times a block may be discarded before the fixture gives up.
     *
     * <p>Five, the value the helper this class generalises already chose. The residual it leaves is
     * {@code (t/unit)^5}: at the longest in-category member measured on 2026-10-14 (2.702 s against
     * a minute) that is {@code 1.8e-7}, and at the mean (0.757 s) {@code 5e-11}. Raising it cannot
     * help a body that is comparable to its own window, which is why the refusal on exhaustion
     * prescribes shortening the body instead.
     */
    static final int MAX_ATTEMPTS = 5;

    /** The prefix every line this fixture prints carries, in the {@code [doors]} idiom. */
    static final String WITNESS = "[fixed-window]";

    /**
     * How much of a discarded attempt's own failure message the witness carries.
     *
     * <p>One console line, so: the first line of the message, cut here. An AssertJ failure begins
     * with the {@code as(...)} description, and the descriptions in this tree run to paragraphs —
     * a witness that reproduced one would bury the thing it witnesses, and a multi-line one would
     * stop being a line at all.
     */
    static final int WITNESSED_MESSAGE_CHARS = 160;

    /**
     * The window widths this product keys counters on.
     *
     * <p>A day boundary is also an hour boundary and an hour boundary is also a minute boundary
     * (3600 and 86400 are whole multiples of 60), so checking {@code MINUTE} would be a correct
     * superset for every member — and would re-run the hour and day members on every harmless
     * minute roll. The unit is a parameter so the retry rate stays at {@code t/unit}.
     */
    public enum Unit {

        MINUTE(60),
        HOUR(3600),
        DAY(86_400);

        private final long seconds;

        Unit(long seconds) {
            this.seconds = seconds;
        }

        public long seconds() {
            return seconds;
        }

        /** {@code floor(now / seconds)} — the key the production limiters compute. */
        long keyNow() {
            return Instant.now().getEpochSecond() / seconds;
        }
    }

    /** A block that spends a fixed window. */
    public interface Body {

        void run(Attempt attempt) throws Exception;
    }

    /**
     * Which attempt this is. {@link #suffix()} is what a body whose key is a literal must vary on;
     * it differs across attempts, which is the whole reason the body takes a parameter.
     *
     * @param index 1-based
     */
    public record Attempt(int index) {

        public String suffix() {
            return Integer.toString(index);
        }
    }

    private FixedWindow() {
    }

    /** The common case: a budget keyed on the epoch minute. */
    public static void inOneMinute(Body body) throws Exception {
        inOne(Unit.MINUTE, body);
    }

    /** For an hour- or day-keyed budget — {@code InviteSenderVolumeBudget} has both. */
    public static void inOne(Unit unit, Body body) throws Exception {
        inOne(unit, unit::keyNow, MAX_ATTEMPTS, System.out::println, body);
    }

    /**
     * The seam {@code FixedWindowTest} drives, with a scripted supplier instead of the wall clock
     * and a capturing witness instead of stdout — package-private exactly as
     * {@link SourceCallSites#enclosingMethods(java.util.List)} is, so the behaviour can be proven
     * against planted inputs rather than against a clock nobody can place.
     */
    static void inOne(Unit unit, LongSupplier windowOf, int maxAttempts, Consumer<String> witness,
                      Body body) throws Exception {
        var member = member();
        AssertionError lastFailure = null;
        int discarded = 0;
        for (int index = 1; index <= maxAttempts; index++) {
            long before = windowOf.getAsLong();
            AssertionError caught = null;
            try {
                body.run(new Attempt(index));
            } catch (AssertionError e) {
                // Only an AssertionError. Anything else — an exception out of MockMvc, an
                // IllegalStateException from a fixture — is a broken test, not a straddle, and
                // propagates on attempt 1 with its own stack.
                caught = e;
            }
            long after = windowOf.getAsLong();
            if (after == before) {
                if (caught != null) {
                    // Rethrown unchanged: what a reader sees is the member's own message, never
                    // this fixture's. A real defect with no roll is indistinguishable from one in
                    // an unwrapped member, which is the point.
                    throw caught;
                }
                return;
            }
            witness.accept(discardLine(member, unit, index, maxAttempts, before, after, caught));
            discarded++;
            lastFailure = caught;
        }
        throw exhausted(member, unit, discarded, lastFailure);
    }

    /**
     * ASCII only, deliberately. This line's reader is a console, and the Windows consoles this
     * project is developed on are not UTF-8: the first run of this fixture printed its roll as
     * {@code 29866442 ? 29866443} with the arrow and the dash mangled. The surefire XML keeps
     * whatever is written, so the loss is only on the surface that matters most.
     *
     * <p><strong>It carries the discarded attempt's own message, and that is the only way this
     * fixture can be shown not to have hidden a defect.</strong> A body that fails under a roll and
     * passes on the retry returns GREEN, by design — but until HD-334's third round the
     * {@code AssertionError} it threw appeared in no stream at all: {@code caught} was read only to
     * choose the word {@code failed}, and {@code lastFailure.getMessage()} is printed only by
     * {@link #exhausted}, which that run never reaches. That branch is the one place in this
     * fixture where an intermittent product defect can be absorbed (a race between a window
     * {@code compute} and a count {@code addAndGet} would land exactly there), and it is the
     * diff's one <em>unwitnessed drop</em>: the branch named a witness and the witness omitted the
     * thing that was dropped. It now names it.
     *
     * <p><strong>Where the rule stops, so the asymmetry does not read as an oversight.</strong>
     * ASCII is required of exactly two kinds of text here: a line some test <em>byte-asserts</em>
     * (this one — {@code FixedWindowTest} compares it with {@code containsExactly} and then
     * {@code matches("\\p{ASCII}+")}, so a console substitution reds the build; the ASCII claim is
     * about this fixture's OWN words, since the appended fragment is the member's bytes and an
     * AssertJ description in this tree may carry an em-dash, mangled on that console exactly as it
     * already is when the same assertion fails for real), and
     * {@code FixedWindowCoverageTest}'s propagation checklist, whose entire purpose is to be read
     * off that same console by somebody acting on a red build. Ordinary javadoc and ordinary
     * assertion prose follow the codebase's em-dash style and are mangled harmlessly: nothing
     * byte-compares them, and the mangling was observed in situ — an {@code as(...)} sentence in
     * this very fixture's seal printed {@code ...seen at all ? in a run that stayed green} during a
     * run that was otherwise correct. Do not "fix" the dashes elsewhere; do not let one appear in
     * either of the two kinds above.
     */
    private static String discardLine(String member, Unit unit, int index, int maxAttempts,
                                      long before, long after, AssertionError caught) {
        return WITNESS + " " + member + " attempt " + index + "/" + maxAttempts
               + " discarded - epoch " + unit + " rolled " + before + " -> " + after
               + " during the block (body " + verdict(caught)
               + "); re-running on a fresh key";
    }

    /** {@code passed}, or {@code failed} with the first line of what it threw away. */
    private static String verdict(AssertionError caught) {
        if (caught == null) {
            return "passed";
        }
        var message = caught.getMessage();
        if (message == null || message.isBlank()) {
            return "failed with no message";
        }
        var first = message.lines().findFirst().orElse("").strip();
        return "failed: " + (first.length() <= WITNESSED_MESSAGE_CHARS ? first
                : first.substring(0, WITNESSED_MESSAGE_CHARS) + "...");
    }

    /**
     * The refusal when every attempt straddled. It prescribes shortening the body because that is
     * the only action its reader can perform: a block comparable in length to its own window
     * straddles at a rate no attempt count can outrun.
     */
    private static AssertionError exhausted(String member, Unit unit, int discarded,
                                            AssertionError lastFailure) {
        var message = """
                %s %s straddled an epoch %s boundary on all %d attempts, so nothing it asserts \
                has been established.

                A fixed window is keyed on floor(wallclock / %ds) and REPLACED when the key \
                changes, so a block that spans a boundary spends part of its budget in one window \
                and asserts against another. Straddling every time means this body is comparable \
                in length to its own window: shorten it — split the spends that need one window \
                from the setup that does not, or drive the limiter directly instead of over HTTP. \
                Raising the attempt count cannot help.""".formatted(
                        WITNESS, member, unit, discarded, unit.seconds());
        if (lastFailure == null) {
            return new AssertionError(message + System.lineSeparator() + System.lineSeparator()
                                      + "Every discarded attempt PASSED, which is why this is a "
                                      + "refusal and not a green run: a pass that needed a window "
                                      + "roll is a pass about the clock.");
        }
        return new AssertionError(message + System.lineSeparator() + System.lineSeparator()
                                  + "The last discarded attempt also failed, with its own message:"
                                  + System.lineSeparator() + lastFailure.getMessage(), lastFailure);
    }

    /**
     * {@code Class#method} of the nearest frame outside this class, so the witness names the member
     * rather than the fixture.
     */
    private static String member() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .filter(f -> !FixedWindow.class.getName().equals(f.getClassName()))
                        .findFirst()
                        .map(f -> simpleName(f.getClassName()) + "#" + f.getMethodName())
                        .orElse("<unknown>"));
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
