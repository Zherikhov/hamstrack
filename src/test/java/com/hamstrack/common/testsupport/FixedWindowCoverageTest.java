package com.hamstrack.common.testsupport;

import com.hamstrack.common.testsupport.VacuousVerification.Language;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>No test whose verdict depends on a fixed-window count is left deciding it by
 * phase</strong> (HD-334, seal 2 of 2 — the population arm).
 *
 * <p>{@code FixedWindowTest} proves what {@link FixedWindow} does. This proves it is applied to
 * every member of the category, and that the category is derived from the code rather than listed:
 * fixing the two classes that happened to fail is the defect the 2026-09 retrospective exists to
 * delete.
 *
 * <h2>The predicate (arm A1)</h2>
 * Over every {@code .java} file found by a disk walk of {@code src/test/java}
 * ({@link SourceCallSites#unitsUnder}), with comments and string literals blanked by
 * {@link VacuousVerification#strip} and {@code import}/{@code package} lines blanked:
 *
 * <blockquote>a member is a {@code @Test} / {@code @ParameterizedTest} / {@code @RepeatedTest}
 * method whose <strong>own-class call closure</strong> contains, in code, any of
 * {@code isTooManyRequests(}, {@code TOO_MANY_REQUESTS}, a bare {@code 429} token, or
 * {@code RateLimitedException}.</blockquote>
 *
 * <p>Three properties of that wording are load-bearing, each measured on 2026-10-14:
 *
 * <ul>
 *   <li><strong>The closure, not the body.</strong> Five members carry their refusal in a private
 *       helper ({@code InviteSenderVolumeBudgetTest#refuse}, {@code RecipientDailyCapTest#attempt}),
 *       so a body-only scan loses them.</li>
 *   <li><strong>Literals blanked.</strong> Twelve of the thirty-six files a bare {@code 429} grep
 *       returns mention it only in prose — a javadoc, a comment, an assertion message.</li>
 *   <li><strong>Not a property name.</strong> {@code app.invites.max-per-recipient-per-day} is read
 *       by a <em>sliding</em> throttle and {@code app.invites.max-per-sender-per-day} by a
 *       <em>fixed</em> one: identical name shape, opposite mechanism. Any name-keyed predicate here
 *       is measurably wrong, which is why this one is keyed on the assertion and the mechanism is
 *       declared per member.</li>
 *   <li><strong>A disk walk, not {@code git ls-files}.</strong> Every reader here takes bytes from
 *       disk, so taking names from the index would be the two-sources divergence HD-314 measured.
 *       The cost is that an untracked member counts; that is the safe direction for a seal that
 *       asserts "nothing offends".</li>
 * </ul>
 *
 * <h2>What makes a member GUARDED — enclosure, not presence</h2>
 * A refusal needle must fall <strong>inside</strong> a {@link FixedWindow} block, found by
 * bracket-matching forward from the call's open paren over text whose literals and comments are
 * already blanked. The history of that needle, and why finding the call was not enough, is on
 * {@link #FIXTURE_CALL}; the four sites that ask the question, and the strength each one needs, are
 * on the arms themselves. All four go through one derivation, so there is no site at which presence
 * is still accepted by accident. Sealed by
 * {@link #aWrapperWhoseSpendsWereHoistedOutOfItIsNotGuarded()}, which drives it from a string
 * literal and so needs no repository edit.
 *
 * <h2>And one property that is not about wrapping at all (arm A3)</h2>
 * A wrapped block is <strong>re-run</strong>, so it returns every permit it takes before it ends.
 * The member that did not was this ticket's Critical: its retry could not pass, and it failed
 * accusing the ordering it exists to assert. That arm's unit is the BLOCK, its needles are derived
 * from each file's own helpers and {@code @BeforeEach}, and the shapes it must refuse are planted
 * as string literals on
 * {@link #aWrappedBlockThatParksARequestAndNeverGivesItBackIsRefused()}.
 *
 * <h2>Two properties that are about the DECLARATION rather than the wrapping (arms A4, A5)</h2>
 * An exemption is a drop path and its reason is its membership, so arm A4 demands that the reason
 * quote the rate dials its own context sets — the three reasons that ended "the rate budget in this
 * context is set wide enough that it never binds here" were a verbatim copy from a sibling where
 * the dial is 10000, onto a context that sets 4. And arm A5 holds a wrapper's WIDTH against those
 * same dials, because arm A1 asks only about enclosure: an {@code inOne(Unit.HOUR, ...)} block over
 * a minute-keyed budget is as exposed as an unwrapped one and counts as guarded. Both read the
 * dials through one scan with a table-driven test of its own, and both are blind to a dial left at
 * its default — stated on each arm, with the margin that makes today's members safe.
 *
 * <h2>What the predicate cannot see, and what covers it</h2>
 * It selects a member by the <em>refusal</em> it asserts, so a method that asserts the
 * <strong>absence</strong> of a refusal after spending a budget is invisible to it — and that shape
 * is a false green, not a false red: a roll resets the count, so the non-refusal is true for free.
 * Inside a {@link FixedWindow} block it is covered by that fixture's unconditional roll check.
 * Outside one it is not selected at all, so {@link #GUARDED_BEYOND_THE_PREDICATE} names such
 * members explicitly — an <em>inclusion</em>, which can only ever demand more, and which this seal
 * re-derives on every run: an entry that is not a live {@code @Test} method, or that the predicate
 * has since learned to select, fails rather than lingering.
 *
 * @see FixedWindow
 * @see NoFixedWindow
 */
class FixedWindowCoverageTest {

    /** What makes a method a member — see the class javadoc for why each form is in the set. */
    private static final List<String> REFUSAL_NEEDLES =
            List.of("isTooManyRequests(", "TOO_MANY_REQUESTS", "RateLimitedException");

    /** A bare {@code 429}, not part of an identifier or a decimal. */
    private static final Pattern BARE_429 = Pattern.compile("(?<![\\w.])429(?![\\w.])");

    private static final Pattern IMPORT_OR_PACKAGE = Pattern.compile("^\\s*(import|package)\\s");

    /**
     * A static import of the fixture, which makes the call unqualified and therefore invisible to
     * {@link #FIXTURE_CALL} — a false RED, so safe, but a refusal that handed its reader the
     * wrap-the-block checklist would be prescribing what they have already done. Detected so the
     * refusal can name the real remedy instead.
     */
    private static final Pattern STATIC_IMPORT_OF_FIXTURE = Pattern.compile(
            "^\\s*import\\s+static\\s+[\\w.]*" + FixedWindow.class.getSimpleName() + "\\.");

    /**
     * A counter keyed on {@code floor(wallclock / unit)} — the two halves of the shape, which must
     * both be present for a CLASS to be a fixed-window mechanism (arm A2) and either of which makes
     * a METHOD one (the {@link NoFixedWindow#refusedBy()} check).
     *
     * <p>Measured against the whole of {@code src/main/java}: the conjunction selects exactly the
     * four live families and rejects every sliding or clock-free neighbour, including
     * {@code ProductMetrics}, which reads {@code getEpochSecond()} and keys nothing on it.
     */
    private static final Pattern WINDOW_DIVISION = Pattern.compile(
            "(getEpochSecond\\(\\)|nowSeconds?)\\s*/\\s*(60|[A-Z_]+_SECONDS)");

    private static final Pattern WINDOW_KEY_FIELD = Pattern.compile("epoch(Minute|Hour|Day)");

    /** Prose that claims a window argument — arm A1b. */
    private static final Pattern WINDOW_PROSE = Pattern.compile(
            "epoch-minute|epoch minute|minute boundary|window rolls|straddle",
            Pattern.CASE_INSENSITIVE);

    /**
     * <strong>A fixed-window rate dial as a context WRITES it</strong> — a quoted
     * {@code key=value} whose key names a per-minute, per-hour or per-day budget. Arms A4 and A5.
     *
     * <p>Keyed on the <em>width</em> in the key and not on a list of names, because the name shape
     * of a budget is measurably unreliable here: {@code app.invites.max-per-recipient-per-day} is
     * read by a sliding throttle and {@code app.invites.max-per-sender-per-day} by a fixed one. The
     * width is the only thing this pattern claims, and it is the only thing both arms use it for —
     * A4 asks that the member's prose quote the pair, A5 that its wrapper be no wider than it.
     *
     * <p>Three forms it must read and three it must refuse, all six planted on
     * {@link #theDialScanReadsEveryFormAContextWritesAndNothingElse()}: the trailing-qualifier form
     * ({@code reports-per-minute-per-ip}) resolves to the inner width because {@code per-ip} is not
     * a width; a non-budget dial in the same array ({@code acquire-wait-ms},
     * {@code login-backoff-base-seconds}) is not one; and the master switch
     * ({@code app.rate-limit.enabled=false}) is not a dial at all — see A4's declared weaknesses for
     * what that costs.
     */
    private static final Pattern WINDOW_DIAL = Pattern.compile(
            "\"((?:app|spring)\\.[\\w.-]*per-(minute|hour|day)[\\w-]*)=(\\d+)\"");

    /**
     * Where a context's properties are written. Read from the ANNOTATION's argument text and not
     * from the whole file, so a dial quoted in a javadoc — or in a {@link NoFixedWindow#reason()},
     * which is the text A4 measures against it — cannot add a demand or satisfy one.
     */
    private static final Pattern PROPERTY_ANNOTATION =
            Pattern.compile("@(SpringBootTest|TestPropertySource)\\s*\\(");

    /** The width a {@code FixedWindow.inOne(Unit.X, ...)} call watches — its first argument. */
    private static final Pattern DECLARED_UNIT =
            Pattern.compile("^\\s*(?:FixedWindow\\.)?Unit\\.(MINUTE|HOUR|DAY)\\b");

    /**
     * How this seal finds a {@link FixedWindow} block: the <strong>qualified call</strong>, not the
     * type and not an unqualified name.
     *
     * <p>Measured necessity, twice. The first cut looked for {@code "FixedWindow."}, which a mere
     * mention of {@code FixedWindow.Attempt} in a helper's signature satisfies — and
     * {@code DatabaseBusyAuthBudgetTest} has exactly that, so the arm covering its two hand-added
     * members was green against a plant that removed their wrapper. A check that a type is
     * imported is not a check that a guard is applied. The second cut is this one, and
     * <em>finding</em> the call is only half of it: see the class javadoc on enclosure.
     *
     * <p><strong>Qualified on purpose</strong>, and the class javadoc's propagation checklist says
     * so, because an unqualified {@code inOneMinute(} in source text is indistinguishable from a
     * private helper of that name in the same class — which is precisely the private-copy shape
     * {@link #noOtherTestSourceCarriesItsOwnRollDetectingRetry()} exists to refuse. A statically
     * imported call is therefore a red build, and {@link #STATIC_IMPORT_OF_FIXTURE} is what lets
     * that red say so rather than prescribing the wrapping its reader already did.
     */
    private static final String FIXTURE_CALL = FixedWindow.class.getSimpleName() + ".inOne";

    /**
     * The four files this seal is made of — the fixture, its marker, and both its own test classes.
     * Read by {@link #isFixtureOwnSource(SourceCallSites.CompilationUnit)}, which carries the
     * argument for an exact set here.
     */
    private static final Set<String> FIXTURE_OWN_SOURCES = Set.of(
            FixedWindow.class.getSimpleName(),
            NoFixedWindow.class.getSimpleName(),
            FixedWindowTest.class.getSimpleName(),
            FixedWindowCoverageTest.class.getSimpleName());

    /**
     * Members the predicate cannot select because they assert the <em>absence</em> of a refusal,
     * and are therefore guarded by hand.
     *
     * <p>{@code DatabaseBusyAuthBudgetTest}'s pool-starvation halves are the false-green shape:
     * each sends three auth requests from one IP against a budget of two and asserts
     * {@code containsOnly(503)}, i.e. "no 429 in here". A roll between the second and the third
     * request makes that true whether or not the refund under test exists — and these are the
     * longest-running budget-spending bodies in the whole category (3.033 s and 3.808 s, measured
     * 2026-10-14 from {@code target/surefire-reports}, against means of 0.757 s), so this is the
     * largest single false-green exposure in it, not a theoretical one.
     *
     * <p>{@code CspReportBatchBudgetTest}'s batch half is the mirror image and is here for the same
     * structural reason: it asserts a <em>count</em> of what got through (five lines written, five
     * dropped) with no refusal anywhere in it, so the predicate cannot see it either. A roll inside
     * its ten-report loop resets the per-IP count and more than five lines are written, which fails
     * — a false RED at roughly 1e-5 a run, since the whole spend is one in-process request. The
     * direction differs; the reason it needs naming by hand does not. It was found by scanning all
     * nineteen fixture-calling sources for this shape, which is the enumeration this entry exists
     * to record: the shape is "spends a fixed window and asserts a count without asserting a
     * refusal", and a member of it is listed here or it is guarded by nothing.
     *
     * <p>An inclusion rather than an exclusion on purpose: it can only widen what is demanded, and
     * every entry is checked to be live and to be outside the predicate. <strong>That argument is
     * true of its contents and false of its size</strong> — measured 2026-10-14: emptying this set
     * and removing one of the two wrappers left {@link #theMembersThePredicateCannotSeeAreStillGuarded()}
     * passing, because nothing asserted how many entries it had. Hence
     * {@link #BEYOND_THE_PREDICATE_FLOOR}. The straddle that the one witness line of the final
     * HD-334 run recorded was on an entry of this list, in the {@code body passed} direction, so
     * this is the arm the ticket's strongest evidence came through.
     */
    private static final Set<String> GUARDED_BEYOND_THE_PREDICATE = Set.of(
            "DatabaseBusyAuthBudgetTest#a503AnsweredByTheHandlerDoesNotSpendTheCallersAuthBudget",
            "DatabaseBusyAuthBudgetTest#a503AnsweredByTheFilterDoesNotSpendTheCallersAuthBudgetEither",
            "CspReportBatchBudgetTest#oneRequestCarryingTenReportsWritesOnlyAsManyLinesAsTheBudgetPaysFor");

    /**
     * Anti-vacuity floors. Measured on 2026-10-14: 63 selected members, 40 of them guarded by the
     * fixture, 23 exempt. The first two are floors in the {@link Population} sense — they catch a
     * broken scan or a narrowed predicate, not a member leaving. The third is a
     * <strong>ceiling</strong> and is the anti-escape-hatch bound: without it the marker eats the
     * population and this seal stays green over a category nobody guards, so a new exemption is a
     * deliberate edit of this number together with its {@link NoFixedWindow}.
     */
    private static final int POPULATION_FLOOR = 55;

    private static final int GUARDED_FLOOR = 36;

    private static final int EXEMPT_CEILING = 23;

    /**
     * And the exemption population's own <em>floor</em>, which is a different number from the
     * ceiling and from arm A2's.
     *
     * <p>Measured 2026-10-14: the two live-exclusion arms each ran against 23 exemptions while
     * flooring at {@link #MECHANISM_FLOOR} — 4, a bound over the production <em>families</em>, an
     * unrelated population. A marker scan that lost 19 of its 23 subjects passed both. Mitigated
     * rather than open (a lost marker becomes an unguarded member in arm A1, which was proven by
     * planting a blind {@code markerOn}), but a floor borrowed from another population is not a
     * floor on this one.
     */
    private static final int EXEMPT_FLOOR = 20;

    /**
     * The floor on {@link #GUARDED_BEYOND_THE_PREDICATE} — the members the predicate structurally
     * cannot select, each held by nothing but this list. Raised with the list, in the same change
     * that adds an entry; the count that matters at a red build is computed in the message, not
     * written here.
     */
    private static final int BEYOND_THE_PREDICATE_FLOOR = 3;

    /**
     * <strong>Work that outlives the statement that started it</strong> — arm A3's TAKE half.
     *
     * <p>A permit is held past its own statement in exactly one way in this suite: a request
     * submitted to an executor and parked inside the handler chain. So the needle is the hand-off,
     * not the permit — a counter this seal cannot see, in a bean it cannot reach.
     *
     * <p>{@code .execute(} is deliberately NOT here, and the omission is measured rather than
     * reasoned: the two invite classes call {@code transactions.execute(status -> ...)}, a
     * {@code TransactionTemplate} that runs its body on the calling thread and has returned before
     * the statement ends. Including it selected two blocks that take nothing, and a seal whose
     * population is padded with members that cannot offend is a seal nobody believes.
     */
    private static final Pattern PARKS_WORK = Pattern.compile(
            "\\.submit\\(|\\.invokeAll\\(|\\.invokeAny\\(|new Thread\\s*\\("
            + "|\\.schedule(?:AtFixedRate|WithFixedDelay)?\\("
            + "|CompletableFuture\\.(?:run|supply)Async\\(");

    /**
     * Waiting for it — arm A3's GIVE-BACK half. A {@code Future.get} with a timeout (two
     * arguments), a pool drain, or a join.
     *
     * <p>A single-argument {@code .get(} is not a wait: {@code node.get("detail")} and
     * {@code Optional.get()} are the commonest calls in this tree, and a needle that counted them
     * would discharge this arm from any block that reads a JSON field.
     */
    private static final Pattern WAITS_FOR_IT = Pattern.compile(
            "\\.get\\([^()]*,|awaitTermination\\(|\\.invokeAll\\(|\\.join\\(\\)");

    /**
     * {@code @BeforeEach}, read the same way {@link SourceCallSites#TEST_ANNOTATION} is read and
     * through the same {@link #annotatedMethods} walk.
     *
     * <p>Not promoted into {@link SourceCallSites} as {@code TEST_ANNOTATION} was, because the
     * reason that one is shared does not apply: it is shared so that the arm holding the parser's
     * four-space assumption asks with the same pattern as its consumer, and this one has no second
     * asker. The <em>walk</em> is shared, which is where the attribution lives.
     */
    private static final Pattern SETUP_ANNOTATION = Pattern.compile("^\\s*@BeforeEach\\b");

    /**
     * A method that is a test or a lifecycle hook, i.e. <em>not</em> a helper a block may call.
     *
     * <p>Overlaps {@link #SETUP_ANNOTATION} on purpose rather than by oversight: that one answers
     * "which methods are per-test setup, so what do they call", this one answers "which methods is
     * a block unable to call", and they want different answers about {@code @BeforeEach}.
     *
     * <p>Measured necessity: without it arm A3's refusal prescribed
     * {@code call the class's drain (releaseEverything, permitsComeBackWhenTheHeldRequestFinishes,
     * awaitDrained)} - a teardown and a {@code @Test} method, neither of which its reader can call
     * from inside a block. A refusal may only prescribe an action its reader can perform.
     */
    private static final Pattern NOT_A_HELPER = Pattern.compile(
            "^\\s*@(Test|ParameterizedTest|RepeatedTest|BeforeEach|AfterEach|BeforeAll|AfterAll)\\b");

    /** {@code receiver.method()} with no arguments — group 1 the whole call, group 2 the receiver. */
    private static final Pattern NO_ARG_CALL = Pattern.compile("\\b(([A-Za-z_]\\w*)\\.\\w+\\(\\))");

    /**
     * Arm A3's population floor: every {@link FixedWindow} block in the tree, whether or not it
     * parks anything, because the rule is asked of all of them. Measured 2026-10-14.
     */
    private static final int WRAPPED_BLOCK_FLOOR = 40;

    /**
     * And the floor on the blocks that actually park something — the subset arm A3 can refuse.
     *
     * <p>One, measured: {@code PlanningConcurrencyTest#aBudgetRefusalIsAnsweredBeforeAnyPermitIsTaken}
     * is the only wrapped block in the tree that hands a request to an executor. A floor of one is
     * not a category of one: the rule is evaluated against every block above, and this number
     * exists so that a predicate which stopped selecting anything reds instead of reporting clean.
     * {@code ExpensiveReadConcurrencyTest} has the identical {@code gate}/{@code held} fixture and
     * is outside this subset only because the member that happened to need wrapping parks nothing
     * — which is why the rule is stated over blocks rather than fixed in one file.
     */
    private static final int PARKING_BLOCK_FLOOR = 1;

    /** Arm A2's floor: the four live fixed-window families. */
    private static final int MECHANISM_FLOOR = 4;

    /** Arm A1b's floor: the test sources that argue about a window in prose. */
    private static final int WINDOW_PROSE_FLOOR = 4;

    /**
     * Arm A4's floor, and it is a floor on the <em>demands</em> rather than on the population:
     * every (exempt member, dial its own context declares) pair the arm asks about.
     *
     * <p>Measured 2026-10-15: 22 pairs over 12 of the 23 exemptions — the other 11 sit in contexts
     * that declare no rate dial at all. A floor on the exemptions alone would stay satisfied by a
     * dial scan that silently stopped parsing annotations, which is the shape this number exists to
     * red: the arm's whole strength is the dial it found, not the member it found.
     */
    private static final int DIAL_DEMAND_FLOOR = 18;

    /**
     * Arm A5's floors, on both sides of its question. Measured 2026-10-15: 43 wrapped blocks, 35
     * keyed on the epoch minute and 8 on the hour or the day.
     *
     * <p>Both directions matter. With no coarse block the arm asks nothing of anybody (the whole
     * question is whether a coarse key is wide enough for the budget the block spends); with no
     * minute block the unit derivation has stopped reading {@code inOneMinute} and is answering
     * {@code null} or {@code HOUR} for everything.
     */
    private static final int COARSE_BLOCK_FLOOR = 6;

    private static final int MINUTE_BLOCK_FLOOR = 30;

    /**
     * The propagation checklist appended by every refusal in this file that is about the WRAPPING
     * DECISION — a property of the question rather than a count of the arms, because arm A3 asks
     * what a block gives BACK and prescribes its own action instead (a count here would go stale
     * one arm before the list did).
     *
     * <p><strong>ASCII only</strong>, and this is the one text in the file where that is
     * load-bearing rather than cosmetic: it exists to be read off a console by somebody acting on a
     * red build, and the Windows consoles this project is developed on are not UTF-8. Measured
     * 2026-10-14 — its most action-bearing line rendered as
     * {@code { ? }) ? or inOne(Unit.HOUR/DAY, ?)}, i.e. the three places a reader needs to copy.
     * Held byte-for-byte by {@link #thePropagationChecklistIsAsciiBecauseItsReaderIsAConsole()};
     * the scope of the rule is on {@code FixedWindow}'s discard line.
     */
    private static final String CHECKLIST = """

            What to do about it, in the order a reader can act on:

              1. If the member spends a fixed window and asserts a count, wrap the spending block
                 with the SPENDS AND THE ASSERTION BOTH INSIDE IT, and write the call qualified:
                 FixedWindow.inOneMinute(attempt -> { ... }) - or inOne(Unit.HOUR/DAY, ...) for an
                 invite-volume budget. A static import leaves the call unreadable to this seal. If
                 the key is a literal (an IP, an e-mail), build it from attempt.suffix(): a roll
                 REPLACES the window, so a same-key retry starts from the count the discarded
                 attempt left behind.
              2. If it cannot straddle, say why with @NoFixedWindow(why = ..., refusedBy =
                 "Class.method", reason = "..."). The reason is prose for the reader; refusedBy is
                 resolved against src/main/java and its body is read, so a SLIDING_WINDOW or
                 CLOCK_FREE_MECHANISM that names a method keying a counter on floor(wallclock /
                 unit) is refused - as is a ROLL_INVARIANT_ASSERTION that names one which does not.
              3. If the mechanism is a fixed window that a fresh key cannot escape (an
                 instance-wide budget), assert a bound that survives a SINGLE roll instead of the
                 exact count, and declare ROLL_INVARIANT_ASSERTION. Write the arithmetic, and say
                 how many rolls it survives: "any split" is not available to anybody - with enough
                 windows every such bound reaches zero (measured: 20 reports against a ceiling of
                 5 refuse 10 across two windows, 5 across three and 0 across four).
              4. Do not lower a floor or raise the exemption ceiling to make a run pass.""";

    // ------------------------------------------------------------------ arm A1

    /**
     * <strong>Arm A1 — every member either spends inside {@link FixedWindow} or declares why it
     * need not.</strong> Also the per-run population witness: a seal whose population silently
     * shrank is the greenest possible answer to a mechanism that stopped working.
     */
    @Test
    void everyMemberThatAssertsAThrottleRefusalIsEitherGuardedOrDeclaredSafe() throws Exception {
        var population = members().floor(POPULATION_FLOOR);

        var guarded = new ArrayList<Member>();
        var exempt = new LinkedHashMap<Member, NoFixedWindow>();
        var unguarded = new ArrayList<String>();
        for (var member : population) {
            var marker = markerOn(member);
            if (marker != null) {
                exempt.put(member, marker);
            } else if (refusalIsInsideABlock(member.blocks())) {
                guarded.add(member);
            } else {
                unguarded.add(member.describe() + "\n      " + whyItIsNotGuarded(member));
            }
        }

        System.out.println(FixedWindow.WITNESS + " population=" + population.size()
                           + " guarded=" + guarded.size() + " exempt=" + exempt.size()
                           + " " + reasonBreakdown(exempt.values())
                           + " beyond-the-predicate=" + GUARDED_BEYOND_THE_PREDICATE.size());

        assertThat(unguarded)
                .as("""
                    A TEST WHOSE VERDICT DEPENDS ON WHERE IN A WALL-CLOCK WINDOW IT HAPPENED TO \
                    START IS NOT A TEST OF THE PRODUCT.

                    Every throttle here is a fixed window: the counter is keyed on \
                    floor(wallclock / unit) and REPLACED when the key changes. The member(s) above \
                    assert a refusal that a boundary falling inside their block destroys - in \
                    either direction, since a roll can also make a positive assertion true for \
                    free. The indented line under each one says which of the three states it is \
                    in, because they do not take the same action.%s""".formatted(CHECKLIST))
                .isEmpty();

        assertThat(guarded.size())
                .as("guarded members: %d, under the floor of %d. %s", guarded.size(), GUARDED_FLOOR,
                        population.describe())
                .isGreaterThanOrEqualTo(GUARDED_FLOOR);
        assertThat(exempt.size())
                .as("""
                    THE EXEMPTION IS NOT AN ESCAPE HATCH.

                    %d members declare @NoFixedWindow, above the ceiling of %d. A marker that may \
                    grow without a deliberate edit eats the population, and this seal then reports \
                    clean over a category nobody guards. Raise the ceiling in the SAME change that \
                    adds the exemption, and name the member in that commit's message.%s"""
                        .formatted(exempt.size(), EXEMPT_CEILING, CHECKLIST))
                .isLessThanOrEqualTo(EXEMPT_CEILING);
    }

    /**
     * <strong>Every exemption asserts the property it claims.</strong> A set excused with prose
     * outlives its subject, so {@link NoFixedWindow#refusedBy()} is resolved against
     * {@code src/main/java} and that method's own body is read — and the direction checked is the
     * one the reason claims.
     *
     * <p>The unit is {@code Class.method} and not the class, because a class is the wrong unit here
     * twice over: {@code RateLimitService} holds a fixed window and a sliding backoff side by side,
     * and one class-keyed row otherwise satisfies several sites (HD-306).
     */
    @Test
    void everyExemptionNamesAProductionMethodWhoseBodyAgreesWithItsReason() throws Exception {
        var bodies = productionBodies(SourceCallSites.MAIN_SOURCES);
        var offences = new ArrayList<String>();
        int checked = 0;

        for (var member : members()) {
            var marker = markerOn(member);
            if (marker == null) {
                continue;
            }
            checked++;
            if (marker.reason().isBlank()) {
                offences.add(member.describe() + " — reason is blank");
            }
            if (!marker.refusedBy().matches("[A-Z]\\w+\\.\\w+")) {
                offences.add(member.describe() + " — refusedBy `" + marker.refusedBy()
                             + "` is not in Class.method form");
                continue;
            }
            var body = bodies.get(marker.refusedBy());
            if (body == null) {
                offences.add(member.describe() + " — refusedBy `" + marker.refusedBy()
                             + "` names no method declared under src/main/java");
                continue;
            }
            boolean fixedWindow = WINDOW_DIVISION.matcher(body).find()
                                  || WINDOW_KEY_FIELD.matcher(body).find();
            if (marker.why() == NoFixedWindow.Why.ROLL_INVARIANT_ASSERTION && !fixedWindow) {
                offences.add(member.describe() + " — ROLL_INVARIANT_ASSERTION, but `"
                             + marker.refusedBy() + "` keys no counter on a window");
            }
            if (marker.why() != NoFixedWindow.Why.ROLL_INVARIANT_ASSERTION && fixedWindow) {
                offences.add(member.describe() + " — " + marker.why() + ", but `"
                             + marker.refusedBy() + "` keys a counter on floor(wallclock / unit)");
            }
        }

        assertThat(checked)
                .as("only %d of the tree's exemptions were checked, under the floor of %d - a "
                    + "live-exclusion arm that lost most of its subjects is the greenest possible "
                    + "answer to a broken marker scan, and the floor must be over THIS population "
                    + "(23 markers, measured 2026-10-14) and not over the production families",
                        checked, EXEMPT_FLOOR)
                .isGreaterThanOrEqualTo(EXEMPT_FLOOR);
        assertThat(offences)
                .as("""
                    AN EXCLUSION IS A LIVE CHECK OR IT IS A LIE.

                    Each line above is an exemption whose stated reason is contradicted by the \
                    production method it names, or which names nothing resolvable. A reason names a \
                    PROPERTY, and the property is re-derived here on every run.%s"""
                        .formatted(CHECKLIST))
                .isEmpty();
    }

    /**
     * A {@link NoFixedWindow} on a method the predicate does not select is a decision whose subject
     * has moved — the member stopped asserting a refusal, or was renamed, and the exemption now
     * excuses nothing while still counting against the ceiling.
     */
    @Test
    void noExemptionSitsOnAMethodThePredicateDoesNotSelect() throws Exception {
        var selected = new LinkedHashSet<String>();
        members().forEach(m -> selected.add(m.key()));

        // Read reflectively over EVERY test-shaped method in the tree, not by looking for the text
        // "@NoFixedWindow". Measured: the first cut did the latter and was green with a marker
        // planted, because the plant was written fully qualified — `@com.hamstrack…NoFixedWindow`
        // does not start with the simple name. A scan that reads source text must see every form
        // the form admits (HD-343); here there is no need to enumerate them, because the loaded
        // annotation is the same object whichever way it was spelled.
        var stale = new ArrayList<String>();
        int scanned = 0;
        for (var unit : testUnits()) {
            for (var key : closuresByTestMethod(unit).keySet()) {
                var method = key.substring(key.indexOf('#') + 1);
                if (markerOn(unit.path(), method) == null) {
                    continue;
                }
                scanned++;
                if (!selected.contains(key)) {
                    stale.add(key);
                }
            }
        }

        assertThat(scanned)
                .as("only %d markers were found anywhere in the tree, under the floor of %d - the "
                    + "reflective read is broken, not the tree; the floor is over the exemptions "
                    + "(23, measured 2026-10-14), which is the population this arm scans",
                        scanned, EXEMPT_FLOOR)
                .isGreaterThanOrEqualTo(EXEMPT_FLOOR);
        assertThat(stale)
                .as("""
                    AN EXEMPTION THAT EXCUSES NOTHING IS STILL AN EXEMPTION.

                    The member(s) above carry @NoFixedWindow and are not selected by the predicate, \
                    so the written decision has outlived its subject: it stopped asserting a \
                    refusal, or it was renamed and the next method to take the name inherits a \
                    reason nobody wrote about it. Delete the marker in the same change.%s"""
                        .formatted(CHECKLIST))
                .isEmpty();
    }

    /**
     * <strong>The members the predicate cannot see are guarded, the list that names them is live,
     * and the list is not empty.</strong> See {@link #GUARDED_BEYOND_THE_PREDICATE}.
     *
     * <p>These assert the <em>absence</em> of a refusal, so there is no refusal needle to find
     * inside their blocks and the enclosure question has to be asked differently: the matched block
     * must be <strong>non-trivial</strong> — at least one statement. That is what kills the
     * hoisted-spends plant here, where {@link #refusalIsInsideABlock} cannot.
     */
    @Test
    void theMembersThePredicateCannotSeeAreStillGuarded() throws Exception {
        var closures = new LinkedHashMap<String, Closure>();
        for (var unit : testUnits()) {
            closures.putAll(closuresByTestMethod(unit));
        }
        var selected = new LinkedHashSet<String>();
        members().forEach(m -> selected.add(m.key()));

        var offences = new ArrayList<String>();
        for (var key : GUARDED_BEYOND_THE_PREDICATE.stream().sorted().toList()) {
            var closure = closures.get(key);
            if (closure == null) {
                offences.add(key + " - not a live @Test method; delete or rename the entry");
            } else if (selected.contains(key)) {
                offences.add(key + " - the predicate now selects it, so arm A1 covers it and this"
                             + " entry is dead weight");
            } else if (closure.blocks().isEmpty()) {
                offences.add(key + " - no " + FIXTURE_CALL + " call in its closure");
            } else if (closure.blocks().stream().noneMatch(Block::carriesAStatement)) {
                offences.add(key + " - every " + FIXTURE_CALL + " block in it is EMPTY: the call is"
                             + " there and the spends are outside it, which is a wrapper around"
                             + " nothing");
            }
        }

        assertThat(GUARDED_BEYOND_THE_PREDICATE)
                .as("""
                    AN INCLUSION CAN ONLY WIDEN WHAT IS DEMANDED - OF ITS CONTENTS. ITS SIZE IS A \
                    POPULATION LIKE ANY OTHER.

                    Measured 2026-10-14: emptying this set and removing a wrapper left this arm \
                    passing, because its size was printed in the witness and asserted nowhere. An \
                    entry here is a member the refusal-keyed predicate CANNOT select, so this \
                    list is the only thing demanding its wrapper at all - and the longest-running \
                    budget-spending bodies in the whole category are on it. Remove an entry only \
                    together with the member it names, and lower this floor in the same change.""")
                .hasSizeGreaterThanOrEqualTo(BEYOND_THE_PREDICATE_FLOOR);
        assertThat(offences)
                .as("""
                    A MEMBER ADDED BY HAND IS STILL A MEMBER.

                    These assert the ABSENCE of a refusal after spending a budget, which a window \
                    roll makes true for free - the false-green direction, and the predicate is \
                    keyed on refusals so it cannot see them.%s""".formatted(CHECKLIST))
                .isEmpty();
    }

    // ------------------------------------------------------------------ arm A1b

    /**
     * <strong>Arm A1b — the argument is replaced by the mechanism, as a category.</strong>
     *
     * <p>What this ticket deleted was not a bug in two classes; it was a <em>style of reasoning</em>
     * — "the limits are 2 and the assertions spend three requests, which is far inside a minute on
     * any machine that can run the suite at all". That sentence is true and irrelevant: the failure
     * mode is phase. So any test source that argues about a window in prose must also carry the
     * mechanism, and the next copy of that paragraph reds the build instead of being believed for
     * five slices.
     *
     * <p>The prose is read from the RAW text (prose lives in comments) and the mechanism from the
     * stripped text, which is not a detail: on raw text a <em>commented-out</em> fixture call, or
     * one quoted in the very paragraph being argued, discharged this arm. Carrying the mechanism
     * here means "at least one non-empty block somewhere in the file" — the whole-file question, a
     * deliberately weaker one than arm A1's per-member enclosure, because a source may legitimately
     * argue in prose while every one of its own members is exempt.
     */
    @Test
    void noTestArguesAboutAWindowWithoutCarryingTheMechanism() throws Exception {
        var arguing = new ArrayList<String>();
        var offences = new ArrayList<String>();
        for (var unit : testUnits()) {
            if (isFixtureOwnSource(unit)) {
                continue;
            }
            var text = text(unit);
            if (!WINDOW_PROSE.matcher(text).find()) {
                continue;
            }
            arguing.add(unit.type());
            if (!carriesANonEmptyBlock(unit) && !carriesAMarker(unit)) {
                offences.add(unit.type());
            }
        }

        assertThat(arguing.size())
                .as("no test source argues about a window at all, so this arm asserted nothing — "
                    + "the prose scan is broken, since the four that do are measured")
                .isGreaterThanOrEqualTo(WINDOW_PROSE_FLOOR);
        assertThat(offences)
                .as("""
                    A TEST'S CORRECTNESS MAY NOT REST ON AN ARGUMENT ABOUT HOW LONG ITS BODY TAKES.

                    The source(s) above reason in prose about an epoch window, a boundary or a \
                    straddle, and carry neither FixedWindow nor @NoFixedWindow. A duration \
                    argument bounds the PROBABILITY and establishes nothing about any one run.%s"""
                        .formatted(CHECKLIST))
                .isEmpty();
    }

    // ------------------------------------------------------------------ arm A2

    /**
     * <strong>Arm A2 — no production fixed-window family is unrepresented.</strong> A fifth family
     * shipping with nothing guarding its tests is the way this category grows back, and it would be
     * an omission rather than a decision.
     *
     * <h3>Two declared weaknesses, both deliberate</h3>
     * <ul>
     *   <li><strong>A mention is not a drive.</strong> The family name is searched in the raw text
     *       of any source that carries a non-empty block, javadoc included, so this catches "a
     *       family arrived with no guarded test at all" and not "the guard is in the wrong method"
     *       — which is arm A1's question, asked there by enclosure. Presence is the right strength
     *       here; the contributing source is gated on a real block so a wrapper around nothing
     *       cannot discharge it.</li>
     *   <li><strong>The mechanism scan is name-shape-keyed</strong>, which is the hazard
     *       {@link NoFixedWindow}'s own javadoc warns about. {@link #WINDOW_DIVISION} requires the
     *       divided value to be written {@code getEpochSecond()} or to be named
     *       {@code nowSecond(s)}, and {@link #WINDOW_KEY_FIELD} requires a field named
     *       {@code epoch(Minute|Hour|Day)}; all four live families match both today, measured, so
     *       the claim is true — but a fifth written {@code long t = Instant.now().getEpochSecond();
     *       long k = t / 60;} against a differently-named field is invisible to this arm, and arm
     *       A1 would then be the only thing demanding a guard for its tests. Accepted rather than
     *       fixed: the alternative is a Java parser, and A1 is keyed on the assertion and so does
     *       not share this blindness.</li>
     * </ul>
     */
    @Test
    void everyProductionFixedWindowFamilyIsNamedByAGuardedTest() throws Exception {
        var mechanisms = mechanismsUnder(SourceCallSites.MAIN_SOURCES);

        assertThat(mechanisms.size())
                .as("the fixed-window scan over src/main/java saw %d classes, under the floor of "
                    + "%d — the four live families are PerPrincipalMinuteBudget, RateLimitService, "
                    + "CspReportBudget and InviteSenderVolumeBudget, so a smaller number is a "
                    + "broken scan and not a smaller product", mechanisms.size(), MECHANISM_FLOOR)
                .isGreaterThanOrEqualTo(MECHANISM_FLOOR);

        // The fixture's own sources are excluded, and that exclusion is the arm's whole validity:
        // this file names all four families in its javadoc, so counting itself as a guarded test
        // would satisfy A2 for free — the greenest possible answer to a mechanism question.
        var guardedText = new StringBuilder();
        for (var unit : testUnits()) {
            if (isFixtureOwnSource(unit)) {
                continue;
            }
            if (carriesANonEmptyBlock(unit)) {
                guardedText.append(text(unit)).append('\n');
            }
        }

        var unrepresented = mechanisms.stream()
                .filter(name -> !guardedText.toString().contains(name))
                .sorted()
                .toList();

        assertThat(unrepresented)
                .as("""
                    A FIXED-WINDOW FAMILY WITH NO GUARDED TEST IS A FAMILY WHOSE TESTS DECIDE BY \
                    PHASE.

                    The class(es) above key a counter on floor(wallclock / unit) and are named by \
                    no test source that calls FixedWindow. Either their tests are not guarded, or \
                    the family is new — in which case guard its tests in the same change that adds \
                    it, and add its width to FixedWindow.Unit if it is not a minute, an hour or a \
                    day.%s""".formatted(CHECKLIST))
                .isEmpty();
    }

    /**
     * And the arm's own negative control: pointed at a planted tree containing a fifth family, it
     * names it. The plant lives in a temp directory rather than under {@code src/main/java} because
     * the Stop hook refuses a {@code pass} tests gate from a tree whose index and work tree
     * disagree (HD-328), and a half-reverted plant is exactly that shape — which is also why the
     * scan root is a parameter, mirroring {@link SourceCallSites}' package-private text seam.
     */
    @Test
    void theMechanismScanNamesAFifthFamilyPlantedInATreeItIsPointedAt() throws Exception {
        var planted = Files.createTempDirectory("hd334-fifth-family");
        try {
            var file = planted.resolve("SessionMinuteBudget.java");
            Files.writeString(file, """
                    package com.hamstrack.common.ratelimit;

                    class SessionMinuteBudget {

                        void require(String key) {
                            long nowMinute = Instant.now().getEpochSecond() / 60;
                            var window = windows.compute(key, (k, existing) ->
                                    existing == null || existing.epochMinute != nowMinute
                                            ? new Window(nowMinute) : existing);
                        }
                    }
                    """, StandardCharsets.UTF_8);

            assertThat(mechanismsUnder(planted))
                    .as("the scan must see a planted family, or arm A2 is a check that cannot fail")
                    .containsExactly("SessionMinuteBudget");
        } finally {
            Files.deleteIfExists(planted.resolve("SessionMinuteBudget.java"));
            Files.deleteIfExists(planted);
        }
        assertThatThrownBy(() -> mechanismsUnder(Path.of("src", "main", "no-such-root")))
                .as("and a scan over a root that does not exist must throw rather than report an "
                    + "empty set of offenders")
                .isInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------ arm A3

    /**
     * <strong>Arm A3 — a wrapped block returns every permit it takes before it ends</strong>
     * (HD-334 F1, the one defect on this ticket that only a RUN distinguished from a
     * plausible-looking check).
     *
     * <h2>What went wrong, since it is the inversion of the fixture's whole purpose</h2>
     * {@code PlanningConcurrencyTest#aBudgetRefusalIsAnsweredBeforeAnyPermitIsTaken} parked two
     * requests on a surface whose ceiling is two, asserted the occupancy, and ended — releasing
     * nothing, because the latch is opened by {@code @AfterEach}, which runs once per test METHOD.
     * On a roll, attempt 2's first statement met a full surface and answered
     * {@code 429 EXPENSIVE_SURFACE_BUSY} where it wanted {@code 200}: measured, by running the
     * block twice in a row, {@code attempt 1 PASSED; inFlight=2} then
     * {@code Status expected:<200> but was:<429>}. So a straddle stopped being
     * <em>inconclusive</em> and became <em>confident and wrong</em>, on a member whose entire
     * subject is interceptor ordering — and in the {@code body passed} direction, which no
     * failure-gated retry could ever reach.
     *
     * <h2>The unit is the BLOCK</h2>
     * Not the class (one class holds clean and offending members side by side) and not the method
     * (one method may hold several blocks). Keyed {@code Type#method[block i of n]}, and the key
     * is asserted to be injective against the raw block count, so a future change that merges two
     * blocks into one entry reds here instead of hiding one behind the other (HD-306).
     *
     * <h2>What it demands, and of whom</h2>
     * A block whose own-class call closure {@link #PARKS_WORK parks work} must, in its own source
     * and <em>after</em> the last such hand-off, {@link #WAITS_FOR_IT wait for it} — and must
     * additionally repeat every no-argument call its class's {@code @BeforeEach} makes on a
     * receiver the block touches, which is how {@code gate.reset()} is demanded without this seal
     * knowing the word {@code gate}. Both halves are derived from the file, never listed.
     *
     * <h2>What this predicate cannot see</h2>
     * <ul>
     *   <li><strong>A stale baseline.</strong> A cumulative measure captured OUTSIDE the block and
     *       asserted inside it is the same hazard with no hand-off to find:
     *       {@code CspReportBatchBudgetTest} clears its appender and re-reads its counter as the
     *       first two statements of its block, and nothing here demands that it keep doing so.</li>
     *   <li><strong>A take that is not a call.</strong> A field mutated inline in the block, or a
     *       latch closed by a block that parks nothing, is invisible: the needles are call
     *       shapes.</li>
     *   <li><strong>A setup call with arguments</strong> ({@link #NO_ARG_CALL}) — the one blind
     *       spot here that is still only documented.</li>
     *   <li><strong>A setup inherited from a base class:</strong> the {@code @BeforeEach} read is
     *       the file's own. No base class of a fixture-using source declares one today (measured),
     *       and that is asserted rather than written down — a parking block whose direct base
     *       declares {@code @BeforeEach} is an offence naming it, because the alternative is a
     *       sentence that was true the day it was typed. One level up, deliberately: a second
     *       level would be inferred from a type name this parser cannot resolve, and the first is
     *       where every fixture in this tree keeps its setup.</li>
     *   <li><strong>Order inside a helper.</strong> Positions are read off the block's own source;
     *       a helper that both parks and waits is one position to this arm.</li>
     *   <li><strong>A park or a drain written inside a test or lifecycle method</strong> rather
     *       than in a helper: {@link #NOT_A_HELPER} removes those from both needle sets, because
     *       naming them in the refusal prescribed calling a {@code @Test} method.</li>
     *   <li><strong>A release that is present and ineffective.</strong> This reads text, not
     *       semantics — which is why the fix it covers was proven by running the block twice and
     *       not by reading it. The arm refuses the shape; the run establishes the behaviour.</li>
     * </ul>
     */
    @Test
    void everyWrappedBlockReturnsEveryPermitItTakesBeforeItEnds() throws Exception {
        var keys = new LinkedHashSet<String>();
        var classesWithBlocks = new LinkedHashSet<String>();
        var parking = new ArrayList<String>();
        var offences = new ArrayList<String>();
        int blocks = 0;
        var unitsByType = new LinkedHashMap<String, SourceCallSites.CompilationUnit>();
        for (var unit : testUnits()) {
            unitsByType.put(unit.type(), unit);
        }

        for (var unit : testUnits()) {
            if (isFixtureOwnSource(unit)) {
                continue;
            }
            var code = codeLines(unit);
            var bodies = SourceCallSites.bodiesByMethod(unit.type(), code);
            var notHelpers = annotatedMethods(unit, code, NOT_A_HELPER);
            var parkers = ownMethodsWhoseClosure(unit.type(), bodies, PARKS_WORK);
            var waiters = ownMethodsWhoseClosure(unit.type(), bodies, WAITS_FOR_IT);
            parkers.removeAll(notHelpers);
            waiters.removeAll(notHelpers);
            var repeatables = setupCalls(unit, code, bodies);

            for (var member : closuresByTestMethod(unit).entrySet()) {
                var found = member.getValue().blocks();
                for (int i = 0; i < found.size(); i++) {
                    var block = found.get(i);
                    var key = member.getKey() + "[block " + (i + 1) + " of " + found.size() + "]";
                    blocks++;
                    keys.add(key);
                    classesWithBlocks.add(unit.type());
                    if (!parksWork(block)) {
                        continue;
                    }
                    parking.add(key);
                    var missing = whatTheBlockNeverGaveBack(block, parkers, waiters, repeatables);
                    missing.addAll(inheritedSetupThisArmCannotRead(unit, code, unitsByType));
                    if (!missing.isEmpty()) {
                        offences.add(key + "\n      " + String.join("\n      ", missing));
                    }
                }
            }
        }

        System.out.println(FixedWindow.WITNESS + " wrapped-blocks=" + blocks + " in "
                           + classesWithBlocks.size() + " classes, parking=" + parking.size()
                           + " " + parking);

        assertThat(blocks)
                .as("only %d wrapped blocks were found anywhere in the tree, under the floor of "
                    + "%d - the block scan is broken, not the tree, and a scan that found nothing "
                    + "asks nothing of anybody", blocks, WRAPPED_BLOCK_FLOOR)
                .isGreaterThanOrEqualTo(WRAPPED_BLOCK_FLOOR);
        assertThat(keys)
                .as("""
                    THE UNIT OF THIS ARM IS THE BLOCK.

                    %d blocks were scanned and %d population keys came out of them, so two blocks \
                    share a key and one of them is answering for the other. A population \
                    enumerated at one granularity while its check asks its question at another \
                    CANNOT FAIL and reads as coverage (HD-306). Key on Type#method[block i], not \
                    on the method and not on the class.""".formatted(blocks, keys.size()))
                .hasSize(blocks);
        assertThat(blocks)
                .as("the block count (%d) is not above the count of classes holding one (%d), so "
                    + "nothing here distinguishes a per-block population from a per-class one - "
                    + "the granularity control, which a class-keyed rewrite of this arm would fail",
                        blocks, classesWithBlocks.size())
                .isGreaterThan(classesWithBlocks.size());
        assertThat(parking.size())
                .as("""
                    NO WRAPPED BLOCK IN THE TREE PARKS ANYTHING, SO THIS ARM ASSERTED NOTHING.

                    %d of %d blocks hand work to an executor, under the floor of %d. The needle \
                    stopped matching, or the member it was written for lost its wrapper: either \
                    way the measured defect this arm exists to refuse is now unguarded. Do not \
                    lower this number to make a run pass.""".formatted(parking.size(), blocks,
                        PARKING_BLOCK_FLOOR))
                .isGreaterThanOrEqualTo(PARKING_BLOCK_FLOOR);
        assertThat(offences)
                .as("""
                    A WRAPPED BLOCK RETURNS EVERY PERMIT IT TAKES BEFORE IT ENDS.

                    The block(s) above hand work to an executor - a request parked on a latch - \
                    and the attempt ends with it still standing. FixedWindow discards an attempt \
                    the window rolled under and RE-RUNS THE SAME BLOCK, so attempt 2 opens with \
                    attempt 1's permits taken, its latch closed and its arrivals already counted. \
                    Its retry cannot pass, and it fails accusing whatever the member is about: a \
                    straddle stops being inconclusive and becomes confident and wrong, which is \
                    worse than the hazard this fixture removes. It happens in the body-PASSED \
                    direction, so no failure-gated retry would ever reach it.

                    Make the block self-cleaning with the methods its own class already has: open \
                    the gate, wait for every held request, then re-arm the gate for the next \
                    attempt. @AfterEach is the backstop for an attempt that THREW, not the release \
                    path for one that returned - and it runs once per test method, which is the \
                    scope this invariant is already written at. Do not answer this by deleting the \
                    wrapper, and do not answer it with @NoFixedWindow while the member's refuser is \
                    still a fixed window.""")
                .isEmpty();
    }

    /**
     * Does this block hand work to something that outlives the statement? Asked of the
     * <strong>closure</strong>, so a block that parks through a helper ({@code hold(...)}) is
     * selected as surely as one that writes {@code executor.submit(...)} itself.
     */
    private static boolean parksWork(Block block) {
        return PARKS_WORK.matcher(block.closure()).find();
    }

    /**
     * The releases a parking block never performs, one line each and each naming an action its
     * reader can take in that file. Empty means the block gives back what it took.
     */
    private static List<String> whatTheBlockNeverGaveBack(Block block, Set<String> parkers,
                                                          Set<String> waiters,
                                                          Map<String, String> repeatables) {
        var missing = new ArrayList<String>();
        int took = lastOperation(block.source(), PARKS_WORK, parkers);
        boolean waited = operations(block.source(), WAITS_FOR_IT, waiters).stream()
                .anyMatch(at -> at > took);
        if (!waited) {
            missing.add("it parks work and never waits for it inside the block"
                        + (took < 0 ? " (the hand-off is inside a helper this arm cannot place, so"
                                      + " the wait is demanded anywhere in the block)"
                                    : " after the last hand-off at offset " + took)
                        + " - call the class's drain (" + describe(waiters) + ") before the block"
                        + " closes");
        }
        repeatables.forEach((call, receiver) -> {
            if (block.closure().contains(receiver + ".") && !block.closure().contains(call)) {
                missing.add("@BeforeEach calls " + call + " and this block touches `" + receiver
                            + "` without repeating it - per-method setup is not per-attempt setup,"
                            + " so attempt 2 inherits whatever attempt 1 left on it");
            }
        });
        return missing;
    }

    /**
     * The blind spot made live: a parking block whose class inherits per-test setup this arm reads
     * from nowhere.
     *
     * <p>Asked only of a class that actually parks something, so a base class growing an unrelated
     * {@code @BeforeEach} cannot red an innocent file — and asked of the direct base only, which is
     * where this tree keeps its fixtures.
     */
    private static List<String> inheritedSetupThisArmCannotRead(
            SourceCallSites.CompilationUnit unit, List<String> code,
            Map<String, SourceCallSites.CompilationUnit> unitsByType) {
        var declaration = Pattern.compile("\\bclass\\s+" + Pattern.quote(unit.type())
                                          + "\\s+extends\\s+(\\w+)")
                .matcher(String.join("\n", code));
        if (!declaration.find()) {
            return List.of();
        }
        var base = unitsByType.get(declaration.group(1));
        if (base == null) {
            return List.of();
        }
        var inherited = annotatedMethods(base, codeLines(base), SETUP_ANNOTATION);
        if (inherited.isEmpty()) {
            return List.of();
        }
        return List.of("its base " + base.type() + " declares @BeforeEach " + inherited
                       + ", which this arm reads from no file - the per-attempt releases it"
                       + " demands are derived from the SUBCLASS's own setup only. Move that setup"
                       + " down into " + unit.type() + ", or teach setupCalls to walk the base"
                       + " chain; do not leave it as a sentence saying no base declares one.");
    }

    /** Simple names of the class's own methods whose CLOSURE performs one of these operations. */
    private static Set<String> ownMethodsWhoseClosure(String type, Map<String, String> bodies,
                                                      Pattern operation) {
        var found = new LinkedHashSet<String>();
        for (var key : bodies.keySet()) {
            var method = key.substring(key.indexOf('.') + 1);
            if (operation.matcher(closure(type, method, bodies, new LinkedHashSet<>())).find()) {
                found.add(method);
            }
        }
        return found;
    }

    /**
     * Every offset in {@code source} at which it performs {@code operation}, inline or by calling
     * one of {@code ownMethods}.
     */
    private static List<Integer> operations(String source, Pattern operation,
                                            Set<String> ownMethods) {
        var at = new ArrayList<Integer>();
        var inline = operation.matcher(source);
        while (inline.find()) {
            at.add(inline.start());
        }
        for (var method : ownMethods) {
            var call = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(source);
            while (call.find()) {
                at.add(call.start());
            }
        }
        return at;
    }

    private static int lastOperation(String source, Pattern operation, Set<String> ownMethods) {
        return operations(source, operation, ownMethods).stream().mapToInt(Integer::intValue)
                .max().orElse(-1);
    }

    /**
     * {@code receiver.method() -> receiver} for every no-argument call the file's own
     * {@code @BeforeEach} methods make. These are the per-test-method setup steps a wrapped block
     * has to repeat per attempt, and reading them from the file is what keeps this arm from
     * carrying a list of fixture method names that would go stale on a rename.
     */
    private static Map<String, String> setupCalls(SourceCallSites.CompilationUnit unit,
                                                  List<String> code, Map<String, String> bodies) {
        var out = new LinkedHashMap<String, String>();
        for (var setup : annotatedMethods(unit, code, SETUP_ANNOTATION)) {
            var found = NO_ARG_CALL.matcher(bodies.getOrDefault(unit.type() + "." + setup, ""));
            while (found.find()) {
                out.put(found.group(1), found.group(2));
            }
        }
        return out;
    }

    private static String describe(Set<String> methods) {
        return methods.isEmpty() ? "this class declares none" : String.join(", ", methods);
    }

    // ------------------------------------------------------------------ arm A4

    /**
     * <strong>Arm A4 — an exemption's reason is measured against its own context's dials, not
     * described by an adjective</strong> (HD-334 round 3, F1/F2).
     *
     * <h2>What it is for</h2>
     * Three {@link NoFixedWindow} reasons in {@code PlanningConcurrencyTest} ended with
     * <em>"the rate budget in this context is set wide enough that it never binds here"</em>, in a
     * context that sets {@code app.planning.requests-per-minute=4} — deliberately tight, because
     * that class's own wrapped member has to exhaust it. The sentence was a verbatim copy from
     * {@code ExpensiveReadConcurrencyTest}, where the same dial is 10000 and the claim is true. A
     * claim copied from a sibling where it held onto a member where it does not is the shape the
     * 2026-09 retrospective exists to delete, and this one was sitting inside the EXEMPTION set of
     * a security control.
     *
     * <h2>Why prose alone could not answer it</h2>
     * {@link #everyExemptionNamesAProductionMethodWhoseBodyAgreesWithItsReason()} resolves
     * {@link NoFixedWindow#refusedBy()} under {@code src/main/java} and asks exactly one question of
     * that method's body — does it key a counter on {@code floor(wallclock / unit)}? It never
     * relates the named method to the member, to the member's asserted surface, or to the dials the
     * member's own context sets, so <em>almost any</em> production {@code Class.method} is an
     * acceptable {@code CLOCK_FREE_MECHANISM}. What holds the exemption set today is
     * {@link #EXEMPT_CEILING} plus a human reading the prose — a configuration fact and a naming
     * fact, which is the HD-326 shape restated inside the seal that was meant to replace it. This
     * arm is the half that reads a number.
     *
     * <p>Two properties of that older arm are load-bearing and worth stating where a reader of this
     * one will look. The direction it <em>does</em> hold is the useful one: a fixed window dressed
     * as sliding or clock-free is refused, and all four production families answer
     * {@code fixedWindow=true} — including {@code PerPrincipalMinuteBudget.evictStaleEntries}, which
     * is a sweep rather than a refusal. And {@link SourceCallSites#bodiesByMethod} CONCATENATES
     * overloads under one {@code Class.method} key rather than overwriting, so naming
     * {@code PerPrincipalMinuteBudget.require} cannot launder the family through its one-argument
     * delegation: the two-argument overload's window division is in the same body text. Finally,
     * two of {@link NoFixedWindow.Why}'s three constants are decorative <em>to the machine</em> —
     * {@code CLOCK_FREE_MECHANISM} and {@code SLIDING_WINDOW} assert the same {@code !fixedWindow};
     * only {@code ROLL_INVARIANT_ASSERTION} gets a distinct check.
     *
     * <h2>What it demands</h2>
     * For every exempt member: each rate dial its own {@code @SpringBootTest} /
     * {@code @TestPropertySource} declares must appear in the reason exactly as the annotation
     * spells it, {@code key=value}. Not "a number somewhere" — the pair, so the sentence is
     * anchored to that array and cannot be carried to a sibling that sets a different one without
     * going red there. The pair is matched through {@link #reasonQuotes(String, Dial)}, which
     * anchors the END of the value as well as its start: a substring match let a dial
     * <em>tightened</em> by dropping digits keep a stale reason green, which is the same
     * two-orders-tighter case this arm was built for.
     *
     * <h2>What it cannot see — read this before trusting a green run</h2>
     * <ul>
     *   <li><strong>A dial left at its default.</strong> The scan reads the annotation, so a budget
     *       the context never writes is invisible — and "nowhere near its ceiling" about a DEFAULT
     *       is the F1 shape with the number living in {@code application.properties} instead of a
     *       sibling. Live and measured: the invite exemptions spend at most 21 invite POSTs against
     *       {@code app.write.requests-per-minute} at its default of 180. The margin is 159 and the
     *       claim is true; nothing here establishes that, and the day a context lowers that default
     *       the dial becomes visible and the demand appears.</li>
     *   <li><strong>A dial from a base class or a properties file</strong> — same hole, same
     *       reason, and {@code src/test/resources/spring.properties} reaches every runner.</li>
     *   <li><strong>A dial whose value carries a unit.</strong> {@link #WINDOW_DIAL} reads
     *       {@code \d+} up to the closing quote, so {@code app.write.upload-bytes-per-minute=9000B}
     *       — a {@code DataSize}, measured three times in this tree — yields no demand at all.
     *       Under-demanding is the unsafe direction and this one is live; it is recorded rather
     *       than fixed because a suffixed width would also need an arithmetic comparison, which
     *       nothing here does. The matcher knows about the suffix even so: see
     *       {@link #reasonQuotes(String, Dial)}.</li>
     *   <li><strong>Whether the quoted number is the RIGHT one.</strong> The arm reads prose; it
     *       cannot know which dial is on the member's path, so it demands all of them. Over-
     *       demanding is the safe direction and the cost is a sentence that names a budget the body
     *       never touches — say so when it does, as the planning and reports reasons now do about
     *       the per-IP auth window.</li>
     *   <li><strong>Arithmetic.</strong> "spends 3 of this context's 4" is checked by no machine in
     *       this tree. What the arm buys is that a future editor who changes the dial breaks a
     *       falsifiable string instead of leaving an adjective that was true somewhere else.</li>
     * </ul>
     */
    @Test
    void everyExemptionQuotesTheRateDialsItsOwnContextSets() throws Exception {
        var unitsByPath = new LinkedHashMap<Path, SourceCallSites.CompilationUnit>();
        for (var unit : testUnits()) {
            unitsByPath.put(unit.path(), unit);
        }

        var offences = new ArrayList<String>();
        var quoted = new TreeMap<String, String>();
        var filesWithDials = new TreeSet<String>();
        int checked = 0;
        int demands = 0;
        for (var member : members()) {
            var marker = markerOn(member);
            if (marker == null) {
                continue;
            }
            checked++;
            var dials = dialsDeclaredBy(unitsByPath.get(member.path()));
            if (dials.isEmpty()) {
                continue;
            }
            quoted.put(member.key(), dials.stream().map(Dial::quoted).sorted()
                    .collect(java.util.stream.Collectors.joining(" ")));
            // The whole path, not getFileName(): two same-named files in different packages would
            // collapse into one, and an undercounting figure is the defect this label just had.
            filesWithDials.add(member.path().toString());
            for (var dial : dials) {
                demands++;
                if (!reasonQuotes(marker.reason(), dial)) {
                    offences.add(member.describe() + "\n      does not quote " + dial.quoted()
                                 + ", which its own context declares");
                }
            }
        }

        // The map is keyed on the MEMBER, so its size counts exemptions and not contexts: it read
        // `contexts-with-dials` for one round, and the mislabel propagated into a review brief
        // before anybody divided 12 by 5 (HD-334 round 4). A label on a measurement is part of the
        // measurement. The file count is the figure that sentence wanted, so it is printed as its
        // own figure rather than by renaming this one.
        System.out.println(FixedWindow.WITNESS + " exemptions=" + checked + " dial-demands="
                           + demands + " exemptions-with-dials=" + quoted.size()
                           + " files-with-dials=" + filesWithDials.size() + " " + quoted);

        assertThat(demands)
                .as("only %d (exemption, dial) pairs were asked about, under the floor of %d - the "
                    + "annotation scan stopped reading properties, and an arm that demands nothing "
                    + "of anybody is the greenest possible answer to a copied reason. The floor is "
                    + "over the DEMANDS and not over the %d exemptions on purpose: most of those "
                    + "sit in contexts that declare no dial.", demands, DIAL_DEMAND_FLOOR, checked)
                .isGreaterThanOrEqualTo(DIAL_DEMAND_FLOOR);
        assertThat(checked)
                .as("and only %d exemptions were reached at all, under the floor of %d",
                        checked, EXEMPT_FLOOR)
                .isGreaterThanOrEqualTo(EXEMPT_FLOOR);
        assertThat(offences)
                .as("""
                    AN EXEMPTION THAT NAMES NO NUMBER FROM ITS OWN CONTEXT IS PORTABLE PROSE.

                    Each member above declares @NoFixedWindow and argues about a budget its own \
                    @SpringBootTest sets, without quoting the dial. That sentence can be - and was \
                    - copied verbatim onto a context that sets the same dial two orders of \
                    magnitude tighter: three planning members carried `the rate budget in this \
                    context is set wide enough that it never binds here` against \
                    app.planning.requests-per-minute=4, from a sibling where it is 10000. The \
                    members were safe by a property nobody had written down, with one request of \
                    margin.

                    Write the measure, not the adjective: quote the dial as the annotation spells \
                    it (key=value) and say how much of it this body spends. A reader can then \
                    check it, and an editor who moves the dial breaks something falsifiable. A \
                    dial this body never touches is still quoted - say that it is off the path.""")
                .isEmpty();
    }

    /**
     * <strong>The dial scan, both directions, over planted contexts.</strong>
     *
     * <p>A derivation two arms share trades a loud failure for a silent one, so it gets a table of
     * the forms it must see and the forms it must not (HD-343): a scan that quietly stopped
     * matching one spelling shrinks A4's demands and widens A5's permission while both floors stay
     * satisfied by the survivors.
     *
     * <p>Two derivations are tabled here, not one. The second is {@link #reasonQuotes(String, Dial)}
     * — whether a reason quotes a demand — which has the same property: a match that is too
     * generous shrinks nothing visible and reds nothing, it just admits a stale sentence. Its rows
     * are at the bottom, and the first of them (a demand of {@code =100} against a reason quoting
     * {@code =10000}) is the HD-334 round 4 defect.
     */
    @Test
    void theDialScanReadsEveryFormAContextWritesAndNothingElse() {
        var context = planted("PlantedContext",
                "@SpringBootTest(properties = {",
                "        \"app.rate-limit.enabled=false\",",
                "        \"app.planning.requests-per-minute=4\",",
                "        \"app.csp.reports-per-minute-per-ip=1000\",",
                "        \"app.invites.max-per-sender-per-hour=3\",",
                "        \"app.invites.max-per-recipient-per-day=2\",",
                "        \"app.expensive-read.acquire-wait-ms=250\",",
                "        \"app.rate-limit.login-backoff-base-seconds=60\"",
                "})",
                "class PlantedContext {",
                "}");

        assertThat(dialsDeclaredBy(context).stream().map(Dial::quoted).toList())
                .as("the four widths this product keys budgets on, and nothing else from the same "
                    + "array: a millisecond dial and a backoff base are not budgets, and the "
                    + "master switch is not a dial (see A4's declared weaknesses)")
                .containsExactly("app.planning.requests-per-minute=4",
                        "app.csp.reports-per-minute-per-ip=1000",
                        "app.invites.max-per-sender-per-hour=3",
                        "app.invites.max-per-recipient-per-day=2");
        assertThat(dialsDeclaredBy(context).stream().map(Dial::unit).toList())
                .as("the trailing-qualifier form resolves to the INNER width: `per-ip` is not a "
                    + "width, so reports-per-minute-per-ip is a MINUTE dial and a block keyed on "
                    + "the hour over it is arm A5's offence")
                .containsExactly(FixedWindow.Unit.MINUTE, FixedWindow.Unit.MINUTE,
                        FixedWindow.Unit.HOUR, FixedWindow.Unit.DAY);

        assertThat(dialsDeclaredBy(planted("PlantedProse",
                "/** Runs with app.planning.requests-per-minute=4 in its own context. */",
                "@SpringBootTest",
                "class PlantedProse {",
                "    @NoFixedWindow(why = X, refusedBy = \"Y.z\",",
                "            reason = \"app.planning.requests-per-minute=4\")",
                "    void member() {",
                "    }",
                "}")))
                .as("""
                    A DIAL IS WHAT THE ANNOTATION SETS, NOT WHAT THE FILE MENTIONS.

                    Two shapes here, and both would break A4 in the direction that reads as \
                    coverage. A javadoc mention would invent a demand nobody can satisfy; a \
                    mention inside the REASON would let the reason satisfy itself, which is the \
                    whole measurement. @SpringBootTest with no argument list must also yield \
                    nothing rather than bracket-matching into the class body.""")
                .isEmpty();

        var tightened = new Dial("app.reports.requests-per-minute", "100",
                FixedWindow.Unit.MINUTE);
        assertThat(reasonQuotes("spends 3 of app.reports.requests-per-minute=10000 for one "
                                + "principal and 1 for the other.", tightened))
                .as("""
                    A DEMAND IS NOT SATISFIED BY A LONGER NUMBER THAT STARTS WITH IT.

                    This is the HD-334 round 4 defect, live and measured: the context was tightened \
                    from app.reports.requests-per-minute=10000 to =100, the three reasons were \
                    left alone, and an unanchored contains() found the demand inside the stale \
                    value as a prefix. All 17 arms stayed green while the witness printed =100 next \
                    to reasons saying 10000. Digit truncation IS the two-order tightening arm A4 \
                    was bought for, so this direction is the arm.""")
                .isFalse();
        assertThat(reasonQuotes("spends 3 of app.reports.requests-per-minute=100KB of header "
                                + "budget.", tightened))
                .as("""
                    NOR BY THE SAME NUMBER CARRYING A UNIT.

                    A digit lookahead would close the row above and leave this one open, and this \
                    tree writes suffixed dials: app.write.upload-bytes-per-minute is a DataSize, \
                    spelled =9000B / =90000B / =5000B under a key this scan's key pattern matches. \
                    =100KB is a thousand times =100. The boundary is "nothing that could extend \
                    the value", not "no digit".""")
                .isFalse();
        assertThat(reasonQuotes("app.reports.requests-per-minute=100.5 of a fractional budget",
                tightened))
                .as("a decimal point with a digit behind it extends the value too")
                .isFalse();
        assertThat(reasonQuotes("spends 3 of app.reports.requests-per-minute=100 for one "
                                + "principal", tightened))
                .as("the positive control, mid-sentence: the exact pair must still satisfy its own "
                    + "demand, or A4 reds all 22 live pairs and the anchor has eaten the arm")
                .isTrue();
        assertThat(reasonQuotes("the whole budget is app.reports.requests-per-minute=100.",
                tightened))
                .as("and sentence-final: a bare period does not extend a value, and a reason is "
                    + "allowed to end on the measure. This is why the lookahead excludes a dot "
                    + "only when a digit follows it.")
                .isTrue();
        assertThat(reasonQuotes("spends 3 of `app.reports.requests-per-minute=100`, which is "
                                + "plenty", tightened))
                .as("and in backticks followed by a comma - the shape the live reasons use")
                .isTrue();
        assertThat(reasonQuotes("spends 3 of app.reports.requests-per-minute=100 for one "
                                + "principal",
                new Dial("app.reports.requests-per-minute", "10000", FixedWindow.Unit.MINUTE)))
                .as("THE MIRROR DIRECTION, stated rather than left to a reader: a WIDENED dial "
                    + "(=10000 demanded, =100 quoted) was never at risk, because the longer string "
                    + "simply does not occur in the shorter one - contains() reds it too. Only the "
                    + "tightening direction needed an anchor, and it is the direction an editor "
                    + "lowering a budget takes.")
                .isFalse();
    }

    // ------------------------------------------------------------------ arm A5

    /**
     * <strong>Arm A5 — a wrapper is no wider than the budget its own context dials</strong>
     * (HD-334 round 3, F5).
     *
     * <p>{@link FixedWindow#inOne} takes a unit so that an hour- or day-keyed member is not re-run
     * on every harmless minute roll. Arm A1 asks only about ENCLOSURE, so a block written
     * {@code inOne(Unit.HOUR, ...)} that spends a MINUTE-keyed budget counts as guarded while being
     * exactly as exposed as an unwrapped one: a day roll implies an hour roll and an hour roll
     * implies a minute roll, never the converse. Measured 2026-10-15: 8 of the 43 blocks are keyed
     * on the hour or the day and 35 on the minute, which is where a copy-paste of the wrong call
     * would live.
     *
     * <p>No live instance today — the three files holding a coarse block declare an hour dial or no
     * dial at all — so this is a tripwire written green and proven against a plant
     * ({@link #aBlockKeyedWiderThanItsContextsDialIsRefused()}), which is also why it is a rule and
     * not a fix.
     *
     * <p>It shares A4's dial scan and therefore A4's blindness to a default: the coarse blocks
     * spend at most 21 invite POSTs against {@code app.write.requests-per-minute} at its default of
     * 180, and nothing here establishes that margin. It also over-demands by construction — the
     * dial it compares against need not be the one this block spends — and the remedy for that is
     * performable either way: narrow the wrapper, or move the member that needs the minute dial
     * into its own context. Which of several equally fine dials the refusal NAMES is decided by
     * {@link #finestDial(List)}: the tightest value, because an arbitrary tie-break sends a reader
     * to a budget that is not the one binding here.
     */
    @Test
    void noWrappedBlockIsKeyedWiderThanTheDialsItsFileSets() throws Exception {
        var offences = new ArrayList<String>();
        var byUnit = new TreeMap<String, Integer>();
        for (var unit : testUnits()) {
            if (isFixtureOwnSource(unit)) {
                continue;
            }
            offences.addAll(blocksKeyedWiderThanTheirDials(unit));
            for (var member : closuresByTestMethod(unit).values()) {
                for (var block : member.blocks()) {
                    byUnit.merge(block.unit() == null ? "UNREADABLE" : block.unit().name(), 1,
                            Integer::sum);
                }
            }
        }

        System.out.println(FixedWindow.WITNESS + " wrapped-block units=" + byUnit);

        assertThat(byUnit.getOrDefault(FixedWindow.Unit.MINUTE.name(), 0))
                .as("%s minute-keyed blocks, under the floor of %d - the unit derivation has "
                    + "stopped reading inOneMinute and is answering for every block with whatever "
                    + "it defaults to, which makes this arm permissive everywhere",
                        byUnit, MINUTE_BLOCK_FLOOR)
                .isGreaterThanOrEqualTo(MINUTE_BLOCK_FLOOR);
        assertThat(byUnit.getOrDefault(FixedWindow.Unit.HOUR.name(), 0)
                   + byUnit.getOrDefault(FixedWindow.Unit.DAY.name(), 0))
                .as("%s - no block in the tree is keyed wider than a minute, so this arm asked "
                    + "nothing: its whole question is whether a COARSE key is wide enough for the "
                    + "budget the block spends. Floor %d, measured 8 on 2026-10-15.",
                        byUnit, COARSE_BLOCK_FLOOR)
                .isGreaterThanOrEqualTo(COARSE_BLOCK_FLOOR);
        assertThat(offences)
                .as("""
                    A WRAPPER IS NO WIDER THAN THE BUDGET ITS CONTEXT DIALS.

                    The block(s) above are keyed on the epoch hour or day while their own context \
                    declares a narrower budget. The unit decides what the fixture WATCHES: a day \
                    roll implies an hour roll and an hour roll implies a minute roll, never the \
                    converse, so a minute-keyed count spent inside an hour-keyed block is exactly \
                    as exposed as one spent inside no block at all - and arm A1 reports it guarded, \
                    because it asks about enclosure and not about width.

                    Narrow the wrapper to FixedWindow.inOneMinute, or move the member that needs \
                    the narrow dial into its own context. Do not widen the dial to match the \
                    wrapper.""")
                .isEmpty();
    }

    /**
     * Arm A5's negative control, over planted compilation units rather than a repository edit: the
     * subject is a legitimate HOUR block and a plant that has to be undone is how a tree ends up
     * with an index and a work tree that disagree (HD-328).
     */
    @Test
    void aBlockKeyedWiderThanItsContextsDialIsRefused() {
        var hourlyDial = planted("PlantedHourly",
                "@SpringBootTest(properties = {",
                "        \"app.invites.max-per-sender-per-hour=3\"",
                "})",
                "class PlantedHourly {",
                "    @Test",
                "    void member() throws Exception {",
                "        FixedWindow.inOne(FixedWindow.Unit.HOUR, attempt -> {",
                "            invite(attempt.suffix());",
                "        });",
                "    }",
                "}");
        var minuteDial = planted("PlantedMinute",
                "@SpringBootTest(properties = {",
                "        \"app.invites.max-per-sender-per-hour=3\",",
                "        \"app.write.requests-per-minute=4\"",
                "})",
                "class PlantedMinute {",
                "    @Test",
                "    void member() throws Exception {",
                "        FixedWindow.inOne(FixedWindow.Unit.HOUR, attempt -> {",
                "            invite(attempt.suffix());",
                "        });",
                "    }",
                "}");

        assertThat(blocksKeyedWiderThanTheirDials(hourlyDial))
                .as("the positive control: an hour-keyed block over an hour dial is the shape this "
                    + "fixture's unit parameter exists for, and must be admitted - otherwise this "
                    + "arm reds the eight coarse blocks it is written to protect")
                .isEmpty();
        assertThat(blocksKeyedWiderThanTheirDials(minuteDial))
                .as("""
                    THE DEFECT: the same block, one dial narrower in the same array. A minute roll \
                    inside an hour-keyed block is invisible to the fixture, so the member decides \
                    by phase while arm A1 reports it guarded.""")
                .singleElement().asString()
                .contains("PlantedMinute#member[block 1 of 1]")
                .contains("app.write.requests-per-minute=4");

        var tiedDials = planted("PlantedTie",
                "@SpringBootTest(properties = {",
                "        \"app.rate-limit.auth-ip-requests-per-minute=1000\",",
                "        \"app.planning.requests-per-minute=4\"",
                "})",
                "class PlantedTie {",
                "    @Test",
                "    void member() throws Exception {",
                "        FixedWindow.inOne(FixedWindow.Unit.HOUR, attempt -> {",
                "            plan(attempt.suffix());",
                "        });",
                "    }",
                "}");

        assertThat(blocksKeyedWiderThanTheirDials(tiedDials))
                .as("""
                    A REFUSAL NAMES THE BUDGET THAT BINDS FIRST, NOT WHICHEVER TIED ONE CAME \
                    FIRST IN THE ARRAY.

                    Both dials here are MINUTE dials, so the width comparison is a tie and \
                    declaration order decided: this exact plant accused the per-IP auth window of \
                    1000 while the context's tight dial, the one its own members have to exhaust, \
                    is 4. The claim was true and the action performable, and the reader was sent \
                    to the wrong file.""")
                .singleElement().asString()
                .contains("PlantedTie#member[block 1 of 1]")
                .contains("app.planning.requests-per-minute=4")
                .doesNotContain("app.rate-limit.auth-ip-requests-per-minute=1000");
    }

    /** One file's offences — the derivation A5 and its plant control both drive. */
    private static List<String> blocksKeyedWiderThanTheirDials(
            SourceCallSites.CompilationUnit unit) {
        var finest = finestDial(dialsDeclaredBy(unit));
        var offences = new ArrayList<String>();
        for (var member : closuresByTestMethod(unit).entrySet()) {
            var found = member.getValue().blocks();
            for (int i = 0; i < found.size(); i++) {
                var block = found.get(i);
                var key = member.getKey() + "[block " + (i + 1) + " of " + found.size() + "]";
                if (block.unit() == null) {
                    offences.add(key + "\n      this arm cannot read its unit: the call is neither "
                                 + FIXTURE_CALL + "Minute(...) nor " + FIXTURE_CALL
                                 + "(Unit.X, ...), so nothing here knows what it watches");
                } else if (finest != null && block.unit().seconds() > finest.unit().seconds()) {
                    offences.add(key + "\n      is keyed on the epoch " + block.unit()
                                 + " while its own context declares " + finest.quoted()
                                 + " - a " + finest.unit() + " roll inside this block is "
                                 + "undetected, and arm A1 counts it as guarded");
                }
            }
        }
        return offences;
    }

    /** One dial as the annotation spells it, with the width its key names. */
    private record Dial(String key, String value, FixedWindow.Unit unit) {

        String quoted() {
            return key + "=" + value;
        }

        /**
         * The value as a number, for the tie-break in {@link #finestDial(List)}.
         *
         * <p>{@link java.math.BigInteger} rather than a {@code long} parse because
         * {@link #WINDOW_DIAL} captures {@code \d+} with no bound: a twenty-digit typo in a
         * properties array would make a comparator throw, and a seal that dies on a malformed dial
         * reports neither green nor the offence it exists to name. Leading zeros are why this is
         * not a string comparison.
         */
        java.math.BigInteger amount() {
            return new java.math.BigInteger(value);
        }
    }

    /**
     * Does a reason quote this dial as the annotation spells it — <strong>anchored at the end of
     * the value</strong>?
     *
     * <p>Shared by arm A4 and by the table that proves it
     * ({@link #theDialScanReadsEveryFormAContextWritesAndNothingElse()}), because the question "is
     * this measure in this sentence" is the arm's whole content and a second copy of it would drift
     * from this one.
     *
     * <h2>Why a substring is not the test (HD-334 round 4)</h2>
     * The first cut was {@code reason.contains(dial.quoted())}, and it is open in exactly the
     * direction the arm was bought for. Arm A4 exists because a claim was copied onto a context
     * that sets the same dial <em>two orders of magnitude tighter</em> — and a two-order tightening
     * is digit truncation: with the dial moved from {@code app.reports.requests-per-minute=10000}
     * to {@code =100}, the demand {@code …=100} is a PREFIX of the {@code …=10000} the three stale
     * reasons still quote, so all 17 arms stayed green while the witness printed the mismatch
     * (measured 2026-10-15, three {@code ExpensiveReadConcurrencyTest} exemptions). Widening
     * ({@code 4 → 40}) reds correctly, because the longer string does not occur; only the
     * tightening direction was blind, which is the one an editor reaching for a tighter budget
     * takes.
     *
     * <h2>Why the boundary is not digits alone</h2>
     * {@code (?!\d)} would close the truncation case and nothing else. A dial value in this tree
     * can carry a unit suffix: {@code app.write.upload-bytes-per-minute} is a {@code DataSize} and
     * is written {@code =9000B} / {@code =90000B} / {@code =5000B} (measured 2026-10-15, three
     * occurrences under a key this scan's key pattern matches). So a reason is free to spell a
     * value {@code =100KB} where the demand is {@code =100}, which is a thousand times wider and
     * would pass a digit lookahead. The rule is therefore "not followed by anything that could
     * extend the value": a word character — digit, unit letter or underscore — or a decimal point
     * with a digit behind it. A bare {@code .} is deliberately admitted, because a reason is
     * allowed to end its sentence on the measure.
     *
     * <p>{@link Pattern#quote} is load-bearing and not decoration: a dial key is full of {@code .},
     * which unquoted matches any character.
     */
    private static boolean reasonQuotes(String reason, Dial dial) {
        return Pattern.compile(Pattern.quote(dial.quoted()) + "(?!\\w)(?!\\.\\d)")
                .matcher(reason)
                .find();
    }

    /**
     * The rate dials one file's context declares, in declaration order.
     *
     * <p>Bracket-matched forward from the annotation's open paren over RAW text, which is sound here
     * because no property value in this tree carries a bracket and the alternative is to read the
     * stripped text, where the properties are blanked literals. An unbalanced read yields no dials,
     * i.e. under-demands — the unsafe direction for arm A4, which is why
     * {@link #DIAL_DEMAND_FLOOR} is a floor on the demands rather than on the members.
     */
    private static List<Dial> dialsDeclaredBy(SourceCallSites.CompilationUnit unit) {
        var raw = text(unit);
        var found = new LinkedHashMap<String, Dial>();
        var annotation = PROPERTY_ANNOTATION.matcher(raw);
        while (annotation.find()) {
            int close = matchingBracket(raw, annotation.end() - 1);
            if (close < 0) {
                continue;
            }
            var dial = WINDOW_DIAL.matcher(raw.substring(annotation.end() - 1, close));
            while (dial.find()) {
                found.putIfAbsent(dial.group(1),
                        new Dial(dial.group(1), dial.group(3), widthNamed(dial.group(2))));
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * The narrowest dial of a context — what every block in that file must be no wider than.
     *
     * <p><strong>Ties break toward the tightest value</strong>, and that is the whole reason this
     * is not a one-line {@code min}. Most contexts here declare several MINUTE dials, so the width
     * comparison alone leaves the choice to declaration order: a planted hour-wrapper in
     * {@code PlanningConcurrencyTest} was accused against
     * {@code app.rate-limit.auth-ip-requests-per-minute=1000} rather than against the deliberately
     * tight {@code app.planning.requests-per-minute=4} (measured 2026-10-15). Every tied dial makes
     * the claim equally true — the arm's subject is the WIDTH — so this is not a correctness fix;
     * it is a refusal that sent its reader to the wrong budget, on a ticket whose whole subject is
     * that. The alternative considered was naming all tied dials, and it was not taken: the reader
     * would still have to pick one, and the one that binds first is the smallest count, which is
     * also the only deterministic choice available.
     */
    private static Dial finestDial(List<Dial> dials) {
        return dials.stream()
                .min(java.util.Comparator.<Dial>comparingLong(dial -> dial.unit().seconds())
                        .thenComparing(Dial::amount))
                .orElse(null);
    }

    /** No case fold: the pattern captures the three spellings and nothing else can arrive here. */
    private static FixedWindow.Unit widthNamed(String captured) {
        return switch (captured) {
            case "minute" -> FixedWindow.Unit.MINUTE;
            case "hour" -> FixedWindow.Unit.HOUR;
            case "day" -> FixedWindow.Unit.DAY;
            default -> throw new IllegalStateException("WINDOW_DIAL captured " + captured);
        };
    }

    // ------------------------------------------------------------------ the single-derivation tripwire

    /**
     * <strong>The roll-detection derivation exists exactly once.</strong> The helper this fixture
     * generalises was a private copy in one class, and it was both incomplete (failure-gated) and
     * unapplied to one of that class's own members. A second private copy would be the same defect
     * again, and would be invisible to every other arm here.
     */
    @Test
    void noOtherTestSourceCarriesItsOwnRollDetectingRetry() throws Exception {
        var copies = new ArrayList<String>();
        for (var unit : testUnits()) {
            if (isFixtureOwnSource(unit)) {
                continue;
            }
            var text = text(unit);
            if ((WINDOW_DIVISION.matcher(text).find() || WINDOW_KEY_FIELD.matcher(text).find())
                && Pattern.compile("for\\s*\\(\\s*int\\s+attempt").matcher(text).find()) {
                copies.add(unit.type());
            }
        }

        assertThat(copies)
                .as("""
                    THERE IS ONE ROLL-DETECTING RETRY IN THIS TREE.

                    The source(s) above compute a window key and loop over attempts, i.e. they \
                    carry a private copy of FixedWindow. The copy this one replaced retried only \
                    inside catch (AssertionError), so it was green over a pass that needed the \
                    roll — and the class that owned it did not apply it to one of its own members. \
                    Call FixedWindow instead; if it is missing something, add it there.%s"""
                        .formatted(CHECKLIST))
                .isEmpty();
    }

    // ------------------------------------------------------------------ the population

    /**
     * {@code Type#method}, what the predicate read, and whether the file hides the fixture call
     * behind a static import — the last so the refusal can name the remedy its reader has not
     * already performed.
     */
    private record Member(String type, String method, Path path, Closure closure,
                          boolean staticallyImportsFixture) {

        String key() {
            return type + "#" + method;
        }

        List<Block> blocks() {
            return closure.blocks();
        }

        String describe() {
            return key() + "  (" + path + ")";
        }
    }

    /**
     * A test method's own-class call closure, and one {@link Block} per {@link FixedWindow} call
     * expression found anywhere in it.
     */
    private record Closure(String text, List<Block> blocks) {
    }

    /**
     * One {@code FixedWindow.inOne(…)} call expression: the argument text between its brackets, and
     * that text's <em>own</em> call closure.
     *
     * <p>The second field is why the enclosure check does not break the five helper-carried
     * members: {@code InviteSenderVolumeBudgetTest#theHourlyWindowRefusesAtItsCeilingAndNamesTheRoll}
     * wraps a block that calls {@code refuse(…)}, and the {@code RateLimitedException} it asserts is
     * in that helper. A block that calls a helper encloses the helper's spends, so the block is
     * expanded the same way a method's closure is, by the same {@code expand}.
     */
    private record Block(String source, String closure, FixedWindow.Unit unit) {

        /**
         * For a plant that drives arm A3, whose question has nothing to do with the width:
         * {@code MINUTE} is what 35 of the tree's 43 blocks are, and arm A5 is the only reader of
         * this component.
         */
        Block(String source, String closure) {
            this(source, closure, FixedWindow.Unit.MINUTE);
        }

        /**
         * At least one statement in the block's <strong>own</strong> text — the floor that refuses
         * a wrapper around nothing where no refusal needle exists to locate (the two
         * {@link #GUARDED_BEYOND_THE_PREDICATE} members).
         *
         * <p>Measured on the lambda forms this tree writes rather than on a semicolon count: an
         * expression lambda ({@code attempt -> spend(attempt)}) and a method reference carry no
         * semicolon and are not empty, while {@code attempt -> { }} is. Asked of the source and not
         * of the closure, so an empty lambda cannot borrow statements from a helper that one of the
         * call's <em>other</em> arguments happens to name.
         */
        boolean carriesAStatement() {
            int arrow = source.lastIndexOf("->");
            var body = arrow < 0 ? source : source.substring(arrow + 2);
            return !body.replace("{", "").replace("}", "").isBlank();
        }
    }

    private static Population<Member> members() throws IOException {
        var found = new ArrayList<Member>();
        for (var unit : testUnits()) {
            boolean staticImport = unit.lines().stream()
                    .anyMatch(line -> STATIC_IMPORT_OF_FIXTURE.matcher(line).find());
            closuresByTestMethod(unit).forEach((key, closure) -> {
                if (refusesInCode(closure.text())) {
                    found.add(new Member(unit.type(), key.substring(key.indexOf('#') + 1),
                            unit.path(), closure, staticImport));
                }
            });
        }
        found.sort(java.util.Comparator.comparing(Member::key));
        return Population.of("fixed-window members", found);
    }

    private static boolean refusesInCode(String code) {
        return REFUSAL_NEEDLES.stream().anyMatch(code::contains)
               || BARE_429.matcher(code).find();
    }

    /** Does a refusal needle fall <strong>inside</strong> one of the member's blocks? */
    private static boolean refusalIsInsideABlock(List<Block> blocks) {
        return blocks.stream().anyMatch(block -> refusesInCode(block.closure()));
    }

    /**
     * Which of the four unguarded states a member is in. They do not take the same action, and a
     * refusal may only prescribe one its reader can perform: three of these four would otherwise
     * hand the reader item 1 of the checklist, which in two of them is what they have just done.
     */
    private static String whyItIsNotGuarded(Member member) {
        if (member.blocks().isEmpty()) {
            return member.staticallyImportsFixture()
                    ? "the file STATICALLY IMPORTS " + FixedWindow.class.getSimpleName()
                      + ", so its call is unqualified and unreadable to this seal - write it "
                      + "qualified as " + FIXTURE_CALL + "Minute(...), nothing else to change"
                    : "no " + FIXTURE_CALL + " call anywhere in its closure - it is unwrapped "
                      + "(item 1) or it cannot straddle (item 2)";
        }
        if (member.blocks().stream().noneMatch(Block::carriesAStatement)) {
            return "the " + FIXTURE_CALL + " call is THERE and its block is EMPTY - a wrapper "
                   + "around nothing; move the spends back inside it";
        }
        return "the " + FIXTURE_CALL + " block is non-empty but the REFUSAL ASSERTION is outside "
               + "it - move the assertion in too, not only the spends: a roll between the last "
               + "spend and the assertion is the whole hazard";
    }

    /**
     * {@code Type#method -> its closure}, for every {@code @Test}-shaped method in one file: the
     * method's code plus, transitively, the code of same-file methods it calls. The closure is what
     * makes the five helper-carried members visible.
     *
     * <p>The annotation pattern is {@link SourceCallSites#TEST_ANNOTATION} and not a copy, because
     * the arm that holds this parser's four-space assumption over the suite must ask the question
     * with the same pattern as the consumer it protects (HD-334 F3).
     */
    private static Map<String, Closure> closuresByTestMethod(SourceCallSites.CompilationUnit unit) {
        var code = codeLines(unit);
        var bodies = SourceCallSites.bodiesByMethod(unit.type(), code);

        var out = new LinkedHashMap<String, Closure>();
        for (var test : annotatedMethods(unit, code, SourceCallSites.TEST_ANNOTATION)) {
            var text = closure(unit.type(), test, bodies, new LinkedHashSet<>());
            out.put(unit.type() + "#" + test,
                    new Closure(text, blocksIn(text, unit.type(), bodies, Set.of(test))));
        }
        return out;
    }

    /**
     * The methods in one file that carry {@code annotation}: for each line the pattern accepts, the
     * first method the attribution walk enters after it.
     *
     * <p>One walk for {@code @Test} and for {@code @BeforeEach} (arm A3), because a second copy of
     * this five-line loop is how the two parsers {@link SourceCallSites} replaced came apart. The
     * asymmetry it preserves is deliberate: the annotation line is matched on the RAW text, where
     * the annotation actually is, while attribution is read from the STRIPPED text, so a declaration
     * quoted in a comment cannot become a method.
     */
    private static Set<String> annotatedMethods(SourceCallSites.CompilationUnit unit,
                                                List<String> code, Pattern annotation) {
        var enclosing = SourceCallSites.enclosingMethods(code);
        var found = new LinkedHashSet<String>();
        for (int i = 0; i < unit.lines().size(); i++) {
            if (!annotation.matcher(unit.lines().get(i)).find()) {
                continue;
            }
            for (int j = i + 1; j < enclosing.size(); j++) {
                if (!enclosing.get(j).equals(enclosing.get(i))) {
                    found.add(enclosing.get(j));
                    break;
                }
            }
        }
        return found;
    }

    private static String closure(String type, String method, Map<String, String> bodies,
                                  Set<String> seen) {
        if (!seen.add(method)) {
            return "";
        }
        return expand(bodies.getOrDefault(type + "." + method, ""), type, bodies, seen);
    }

    /**
     * {@code text} followed by the closures of every same-class method it calls — the one
     * expansion, shared by a method's closure and by a {@link Block}'s. Written once because a
     * second copy of it is the defect {@link SourceCallSites} exists to have deleted.
     */
    private static String expand(String text, String type, Map<String, String> bodies,
                                 Set<String> seen) {
        var out = new StringBuilder(text);
        for (var key : bodies.keySet()) {
            var other = key.substring(key.indexOf('.') + 1);
            if (seen.contains(other)) {
                continue;
            }
            if (Pattern.compile("\\b" + Pattern.quote(other) + "\\s*\\(")
                    .matcher(out.toString()).find()) {
                out.append(closure(type, other, bodies, seen));
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ enclosure

    /**
     * The {@link Block}s in {@code text}: one per {@link #FIXTURE_CALL} call expression, with its
     * argument text and that text's own closure.
     *
     * @param seen method names expansion must not pull back in — the enclosing test method, or
     *             nothing when a caller plants text that belongs to no method
     */
    private static List<Block> blocksIn(String text, String type, Map<String, String> bodies,
                                        Set<String> seen) {
        var blocks = new ArrayList<Block>();
        for (var call : fixtureCalls(text)) {
            blocks.add(new Block(call.arguments(),
                    expand(call.arguments(), type, bodies, new LinkedHashSet<>(seen)),
                    call.unit()));
        }
        return blocks;
    }

    /** One {@link #FIXTURE_CALL} call expression: the width it watches, and its argument text. */
    private record Call(FixedWindow.Unit unit, String arguments) {
    }

    /**
     * Every {@link #FIXTURE_CALL} call expression in {@code text}, by matching brackets forward
     * from the call's open paren.
     *
     * <p>Sound on this input because {@link #codeLines} has already blanked comments, strings, char
     * literals and text blocks to spaces of equal width, so no bracket inside a literal is counted
     * — the stripper is reused, never re-derived. An occurrence with no open paren after it (a type
     * mention, a javadoc that survived) or an unbalanced one yields no block, which classifies the
     * member as unguarded: the safe direction.
     *
     * <p>The width is read from the call's own spelling — the method-name tail for
     * {@code inOneMinute}, the first argument for {@code inOne(Unit.X, ...)} — and is {@code null}
     * when neither is present, which arm A5 reports rather than guesses. A default would be a guess
     * in the permissive direction.
     */
    private static List<Call> fixtureCalls(String text) {
        var found = new ArrayList<Call>();
        int from = 0;
        while (true) {
            int at = text.indexOf(FIXTURE_CALL, from);
            if (at < 0) {
                return found;
            }
            from = at + FIXTURE_CALL.length();
            int open = text.indexOf('(', at);
            if (open < 0) {
                return found;
            }
            int close = matchingBracket(text, open);
            if (close < 0) {
                continue;
            }
            var arguments = text.substring(open + 1, close);
            found.add(new Call(widthWatchedBy(text.substring(from, open), arguments), arguments));
            from = close;
        }
    }

    /** {@code Minute} in the name, or {@code Unit.X} as the first argument, or nothing. */
    private static FixedWindow.Unit widthWatchedBy(String tail, String arguments) {
        if (tail.startsWith("Minute")) {
            return FixedWindow.Unit.MINUTE;
        }
        var declared = DECLARED_UNIT.matcher(arguments);
        return declared.find() ? FixedWindow.Unit.valueOf(declared.group(1)) : null;
    }

    /** The index of the bracket closing the one at {@code open}, or {@code -1} if unbalanced. */
    private static int matchingBracket(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            switch (text.charAt(i)) {
                case '(', '{', '[' -> depth++;
                case ')', '}', ']' -> depth--;
                default -> { }
            }
            if (depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Does this file carry at least one non-empty {@link FixedWindow} block? The whole-file
     * question arms A1b and A2 ask, deliberately weaker than arm A1's per-member enclosure, and
     * asked of the <strong>stripped</strong> text so that a commented-out or quoted call cannot
     * discharge either arm.
     */
    private static boolean carriesANonEmptyBlock(SourceCallSites.CompilationUnit unit) {
        var code = codeLines(unit);
        return blocksIn(String.join("\n", code), unit.type(),
                SourceCallSites.bodiesByMethod(unit.type(), code), Set.of()).stream()
                .anyMatch(Block::carriesAStatement);
    }

    /**
     * The file's lines with comments, string literals and {@code import}/{@code package} lines
     * blanked — line count and length preserved, so {@link SourceCallSites#DECLARATION}'s
     * indentation lookahead still holds.
     *
     * <p>{@link VacuousVerification#strip} is reused rather than re-derived: a fourth stripper in
     * this tree is the defect, and that one already has a table-driven test over the forms it must
     * and must not see.
     */
    private static List<String> codeLines(SourceCallSites.CompilationUnit unit) {
        var stripped = VacuousVerification.strip(String.join("\n", unit.lines()), Language.JAVA)
                .split("\n", -1);
        var out = new ArrayList<String>(stripped.length);
        for (var line : stripped) {
            out.add(IMPORT_OR_PACKAGE.matcher(line).find() ? "" : line);
        }
        return out;
    }

    private static List<SourceCallSites.CompilationUnit> testUnits() throws IOException {
        return SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES, Set.of());
    }

    private static String text(SourceCallSites.CompilationUnit unit) {
        return String.join("\n", unit.lines());
    }

    /**
     * The fixture, its marker and its two seals. Excluded from every arm that reads raw text,
     * because all four talk <em>about</em> the shape they guard: this file names all four
     * production families in its own javadoc and carries a planted fixed-window class in a text
     * block, so an arm that counted it would answer its own question.
     *
     * <p><strong>The four names, exactly</strong>, and not {@code type().contains("FixedWindow")},
     * which is the name-shape population {@link NoFixedWindow}'s own javadoc argues against: a
     * substring excuses a member the day somebody calls a class
     * {@code FixedWindowRegressionProbeTest} and reports the same green. Measured blast radius of
     * the old predicate (2026-10-15): {@link #members()} never consulted it, so arm A1 did see such
     * a class and refused it by name — the residual was the raw-text arms, where a parking block or
     * a coarse wrapper inside a so-named class would have been skipped by A1b, A2, A3, A5 and the
     * private-copy tripwire.
     *
     * <p>An exact list is right <em>here</em> and wrong for a population, because this is not a
     * population: it is the seal excluding its own four files, a closed set that changes only when
     * somebody edits the seal — and then they are editing the file that holds this list. The names
     * are {@code Class.getSimpleName()} and not string literals, so the list cannot go stale
     * silently: a rename is carried by the compiler, and a deletion stops compiling. The failure
     * direction if it ever did is the loud one — an unexcused fixture file is read as a member, and
     * every raw-text arm reds on this file's own prose about the shape it guards.
     */
    private static boolean isFixtureOwnSource(SourceCallSites.CompilationUnit unit) {
        return FIXTURE_OWN_SOURCES.contains(unit.type());
    }

    // ------------------------------------------------------------------ the marker, reflectively

    /**
     * The {@link NoFixedWindow} on a member, read from the loaded class rather than from source.
     *
     * <p>Reflection is the authoritative reading of an annotation and source text is not, but the
     * reflective reading is the one that can report "absent" and "not applicable" with the same
     * {@code null} (HD-326), so it is proven by a plant: the HD-334 report records this arm seen red
     * against a blank reason and against an unresolvable {@code refusedBy}.
     */
    private static NoFixedWindow markerOn(Member member) throws Exception {
        return markerOn(member.path(), member.method());
    }

    /**
     * Does any test method in this file declare the marker? Asked reflectively rather than of the
     * text, for the reason on {@link #noExemptionSitsOnAMethodThePredicateDoesNotSelect()}: a
     * fully-qualified {@code @com.hamstrack…NoFixedWindow} is the same annotation and a different
     * string.
     */
    private static boolean carriesAMarker(SourceCallSites.CompilationUnit unit) throws Exception {
        for (var key : closuresByTestMethod(unit).keySet()) {
            if (markerOn(unit.path(), key.substring(key.indexOf('#') + 1)) != null) {
                return true;
            }
        }
        return false;
    }

    private static NoFixedWindow markerOn(Path path, String methodName) throws Exception {
        // initialize = false: loading a @SpringBootTest class must not run its static initialiser
        // (WriteBudgetTest's creates a temp directory), and no annotation read needs it.
        var type = Class.forName(fqcn(path), false, FixedWindowCoverageTest.class.getClassLoader());
        for (var method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                var marker = method.getAnnotation(NoFixedWindow.class);
                if (marker != null) {
                    return marker;
                }
            }
        }
        return null;
    }

    private static String fqcn(Path path) {
        var relative = SourceCallSites.TEST_SOURCES.toAbsolutePath()
                .relativize(path.toAbsolutePath()).toString();
        return relative.substring(0, relative.length() - ".java".length())
                .replace('\\', '.').replace('/', '.');
    }

    private static String reasonBreakdown(Iterable<NoFixedWindow> markers) {
        var counts = new TreeMap<String, Integer>();
        for (var why : NoFixedWindow.Why.values()) {
            counts.put(why.name(), 0);
        }
        for (var marker : markers) {
            counts.merge(marker.why().name(), 1, Integer::sum);
        }
        return counts.toString();
    }

    // ------------------------------------------------------------------ the production side

    /** {@code Class.method -> body} for every method under {@code root}. */
    private static Map<String, String> productionBodies(Path root) throws IOException {
        var bodies = new LinkedHashMap<String, String>();
        for (var unit : SourceCallSites.unitsUnder(root, Set.of())) {
            bodies.putAll(SourceCallSites.bodiesByMethod(unit.type(), unit.lines()));
        }
        return bodies;
    }

    /** Simple names of the classes under {@code root} that key a counter on a window. */
    private static List<String> mechanismsUnder(Path root) throws IOException {
        var found = new ArrayList<String>();
        for (var unit : SourceCallSites.unitsUnder(root, Set.of())) {
            var text = text(unit);
            if (WINDOW_DIVISION.matcher(text).find() && WINDOW_KEY_FIELD.matcher(text).find()) {
                found.add(unit.type());
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ the enclosure controls

    /**
     * <strong>The control that stops F1 coming back</strong> — the hoisted-spends shape fed to the
     * predicate as a string literal, so it needs no repository edit and cannot be half-reverted
     * (HD-328).
     *
     * <p>The planted text is written the way {@link SourceCallSites#bodiesByMethod} hands a body
     * over: trimmed lines joined by {@code '\n'}. It is a text block, so this file's own arms do
     * not see a fixture call in it — {@link VacuousVerification#strip} blanks it before
     * {@link #members()} ever reads this method.
     *
     * <p>What was planted in the repository on 2026-10-14, and what this replaces: a wrapper kept
     * on {@code SprintReportThrottleTest#velocityIsOnTheSameBudget} with an empty lambda and the
     * three spends moved out below it. The seal was GREEN, {@code guarded=40} unchanged.
     */
    @Test
    void aWrapperWhoseSpendsWereHoistedOutOfItIsNotGuarded() {
        var wrapped = """
                FixedWindow.inOneMinute(attempt -> {
                for (int i = 0; i < 2; i++) {
                spend(attempt.suffix());
                }
                mvc.perform(post(path)).andExpect(status().isTooManyRequests());
                });
                """;
        var hoistedSpends = """
                FixedWindow.inOneMinute(attempt -> {
                });
                for (int i = 0; i < 2; i++) {
                spend(attempt.suffix());
                }
                mvc.perform(post(path)).andExpect(status().isTooManyRequests());
                """;
        var hoistedAssertion = """
                FixedWindow.inOneMinute(attempt -> {
                for (int i = 0; i < 2; i++) {
                spend(attempt.suffix());
                }
                });
                mvc.perform(post(path)).andExpect(status().isTooManyRequests());
                """;
        var mentionOnly = """
                private void helper(FixedWindow.Attempt attempt) {
                mvc.perform(post(path)).andExpect(status().isTooManyRequests());
                }
                """;

        assertThat(refusalIsInsideABlock(blocks(wrapped)))
                .as("the positive control: a properly wrapped block must still count as guarded, "
                    + "or this seal reds the whole category instead of sealing it")
                .isTrue();
        assertThat(refusalIsInsideABlock(blocks(hoistedSpends)))
                .as("""
                    THE CALL BEING PRESENT IS NOT THE SPENDS BEING INSIDE IT.

                    This is the plant that was GREEN against the second cut of the needle, which \
                    was a plain substring test for the call. A wrapper around nothing is the shape \
                    a well-meaning edit produces, and it is indistinguishable from a real guard to \
                    any check that looks for the call rather than for the block.""")
                .isFalse();
        assertThat(refusalIsInsideABlock(blocks(hoistedAssertion)))
                .as("and the subtler half: the spends inside, the ASSERTION outside. A roll "
                    + "between the last spend and the assertion is the hazard in full, so a block "
                    + "that encloses only the spends establishes nothing")
                .isFalse();
        assertThat(blocks(hoistedSpends))
                .as("the empty block must be FOUND and found trivial, not missed - that is what "
                    + "lets the refusal distinguish `no call` from `a wrapper around nothing`, "
                    + "which take different actions")
                .singleElement()
                .matches(block -> !block.carriesAStatement(), "carries no statement");
        assertThat(blocks(wrapped)).singleElement()
                .matches(Block::carriesAStatement, "carries a statement");
        assertThat(blocks(mentionOnly))
                .as("a mere mention of the TYPE yields no block, which is the first cut of this "
                    + "needle in its grave")
                .isEmpty();
    }

    /**
     * <strong>The control that stops F1 coming back</strong> — arm A3's predicate driven from
     * string literals, so it needs no repository edit and cannot be half-reverted (HD-328).
     *
     * <p>Four shapes, because three of them pass any check that merely looks for a release
     * somewhere: the one that releases nothing (what shipped), the one that waits BEFORE it parks,
     * and the one that waits but never re-arms the latch. The fourth is the fix, and it has to stay
     * admitted or this arm reds the category instead of sealing it.
     *
     * <p>The repository-level control is recorded in the HD-334 report rather than here: the three
     * release lines were removed from the real member and this arm named it, which is the plant the
     * string literals below model.
     */
    @Test
    void aWrappedBlockThatParksARequestAndNeverGivesItBackIsRefused() {
        var parkers = Set.of("hold");
        var waiters = Set.of("awaitDrained");
        var repeatables = Map.of("gate.reset()", "gate");
        var holdBody = "inFlight.add(held.submit(() -> mockMvc.perform(get(uri))));";

        var released = """
                hold(uri(a), a.token());
                hold(uri(b), b.token());
                gate.awaitArrivals(2);
                gate.open();
                awaitDrained();
                gate.reset();
                """;
        var neverReleased = """
                hold(uri(a), a.token());
                hold(uri(b), b.token());
                gate.awaitArrivals(2);
                """;
        var waitedBeforeParking = """
                awaitDrained();
                hold(uri(a), a.token());
                gate.awaitArrivals(1);
                gate.reset();
                """;
        var notReArmed = """
                hold(uri(a), a.token());
                gate.awaitArrivals(1);
                gate.open();
                awaitDrained();
                """;

        assertThat(parksWork(new Block(released, released + holdBody)))
                .as("the selection, through a helper: a block that calls hold(...) parks work even "
                    + "though the hand-off is in the helper's body, which is where every parking "
                    + "block in this tree keeps it")
                .isTrue();
        assertThat(parksWork(new Block(released, released)))
                .as("and a closure with no hand-off in it parks nothing - the same text without "
                    + "the helper's body must NOT be selected, or this arm would demand a drain of "
                    + "every block that happens to name a method called hold")
                .isFalse();

        assertThat(whatTheBlockNeverGaveBack(new Block(released, released + holdBody),
                        parkers, waiters, repeatables))
                .as("the positive control: the shape HD-334 shipped as the fix must be admitted")
                .isEmpty();
        assertThat(whatTheBlockNeverGaveBack(new Block(neverReleased, neverReleased + holdBody),
                        parkers, waiters, repeatables))
                .as("""
                    THE DEFECT ITSELF - two requests parked, nothing released, the latch left \
                    closed. This is what was measured failing its own retry with \
                    `Status expected:<200> but was:<429>`, and it is what every arm of this seal \
                    was green over, because wrapping was all any of them asked about.""")
                .hasSize(2);
        assertThat(whatTheBlockNeverGaveBack(new Block(waitedBeforeParking,
                        waitedBeforeParking + holdBody), parkers, waiters, repeatables))
                .as("""
                    A DRAIN BEFORE THE HAND-OFF DRAINS NOTHING. The order is the property: a wait \
                    that runs before the request is parked leaves the block ending exactly as the \
                    defect did, while satisfying any check that asks whether a release is \
                    PRESENT.""")
                .hasSize(1);
        assertThat(whatTheBlockNeverGaveBack(new Block(notReArmed, notReArmed + holdBody),
                        parkers, waiters, repeatables))
                .as("""
                    AND THE HALF A READER FORGETS: drained, but the one-shot latch never re-armed, \
                    so attempt 2's held requests do not park at all and its occupancy assertion \
                    reads zero. Demanded because @BeforeEach calls gate.reset() and this block \
                    touches `gate` - derived from the file, not from a list of fixture method \
                    names that a rename would silently empty.""")
                .singleElement().asString()
                .contains("gate.reset()");
    }

    /**
     * <strong>The inherited-setup tripwire, both directions, over planted compilation units.</strong>
     *
     * <p>Planted rather than repository-edited on purpose: the subject is a BASE class outside this
     * ticket's path set, and a plant that has to be undone in a file nobody else is changing is how
     * a tree ends up with an index and a work tree that disagree (HD-328). {@link SourceCallSites}
     * takes its root and its units as parameters precisely so a seal can be proven this way.
     */
    @Test
    void aParkingBlockWhoseBaseDeclaresPerTestSetupIsRefused() {
        var child = planted("PlantedChild",
                "class PlantedChild extends PlantedBase {",
                "    @Test",
                "    void member() throws Exception {",
                "        hold(uri, token);",
                "    }",
                "}");
        var quietBase = planted("PlantedBase",
                "abstract class PlantedBase {",
                "    protected void helper() {",
                "    }",
                "}");
        var baseWithSetup = planted("PlantedBase",
                "abstract class PlantedBase {",
                "    @BeforeEach",
                "    void openTheGate() {",
                "        gate.reset();",
                "    }",
                "}");

        assertThat(inheritedSetupThisArmCannotRead(child, codeLines(child),
                        Map.of("PlantedBase", quietBase)))
                .as("the positive control: a base with no per-test setup is nothing to report, or "
                    + "this tripwire reds every parking block that has a base class at all")
                .isEmpty();
        assertThat(inheritedSetupThisArmCannotRead(child, codeLines(child),
                        Map.of("PlantedBase", baseWithSetup)))
                .as("""
                    A BLIND SPOT STATED IN PROSE IS A SENTENCE, NOT A GUARD.

                    Arm A3 derives the releases it demands from the subclass's own @BeforeEach. \
                    The day a base class acquires one, every parking block below it is measured \
                    against setup this arm reads from nowhere - and the javadoc would still say \
                    that no base declares one, which was true when it was typed.""")
                .singleElement().asString()
                .contains("PlantedBase declares @BeforeEach [openTheGate]");
    }

    /** One compilation unit from planted lines — no file, no root, no repository edit. */
    private static SourceCallSites.CompilationUnit planted(String type, String... lines) {
        return new SourceCallSites.CompilationUnit(Path.of("planted", type + ".java"), type,
                List.of(lines));
    }

    /** The predicate, over planted text that belongs to no method. */
    private static List<Block> blocks(String planted) {
        return blocksIn(planted, "Planted", Map.of(), Set.of());
    }

    /**
     * {@link #CHECKLIST} is ASCII, byte-asserted — see the scope of the rule on
     * {@code FixedWindow.discardLine}. Measured 2026-10-14 on the console this project is developed
     * on: {@code { ? }) ? or inOne(Unit.HOUR/DAY, ?)}, i.e. every place item 1 asks a reader to
     * copy from. A refusal its reader cannot read prescribes nothing.
     */
    @Test
    void thePropagationChecklistIsAsciiBecauseItsReaderIsAConsole() {
        assertThat(CHECKLIST).matches("(?s)\\p{ASCII}+");
    }
}
