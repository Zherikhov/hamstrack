package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>No fixture name in this suite derives its uniqueness from a clock</strong> (HD-329).
 *
 * <p>The suite database is shared and never emptied, so a fixture name that must be unique must be
 * unique <em>across runs</em>. Fifty files answered that with a clock reading, and a clock reading
 * is only as unique as the clock's resolution — a property of the host, not of the code. Measured
 * on the development database on 2026-10-15: 105 global {@code WF-%} workflow rows left behind by
 * one such fixture, <strong>103 of them ending in {@code 00}</strong> — a ratio counted over the
 * one family that can collide with itself, never over every {@code WF-%} row, which mixes in the
 * workspace-scoped rows that collide with nothing — so {@code % 1000000} leaves
 * a namespace of ten thousand rather than a million and the next run has about a one-in-a-hundred
 * chance of a {@code 409} — a correct product response, read as a product defect every time.
 *
 * <h2>The modulus is the defect, not the shortening</h2>
 * A truncated reading is strictly worse than a full one: {@code % 1000000} discards exactly the
 * high bits that differ between runs and keeps the low ones the host quantises. The next author who
 * wants a short name will reach for a modulus, so the rule is phrased over the whole category
 * rather than over the modulus: <em>a name's uniqueness comes from a random source, never from a
 * clock</em>, and {@link Unique} is that source.
 *
 * <h2>Three kinds of clock site, and how they are told apart</h2>
 * The population is the <strong>site</strong> — one entry per occurrence, keyed on
 * {@code path:line:offset}, because several files hold both kinds and a file-keyed population lets
 * one safe site answer for an unsafe one (the HD-306 shape).
 * <ol>
 *   <li><strong>Identity</strong> — the occurrence is an operand of a {@code +} chain that contains
 *       a string literal, or it is read into a variable that is concatenated with one somewhere in
 *       the same file. Both are offenders when the operand is a clock.</li>
 *   <li><strong>Timing</strong> — the occurrence is part of a <em>difference</em> in a chain that
 *       builds no text, or is a bare start marker whose variable is only ever differenced. The
 *       difference test is <strong>chain-wide</strong> (one subtraction anywhere in a chain, every
 *       occurrence in it), so it is tested <em>after</em> the string literal rather than before:
 *       the other order exempts a clock-built name for the company it keeps. A duration printed
 *       into a message whose chain holds no literal — the live shape, a {@code long} marker — is
 *       still timing; one printed into a literal is named, which is the price and is planted.</li>
 *   <li><strong>Random-sourced</strong> — the chain carries {@code UUID.randomUUID()} or
 *       {@link Unique}. Compliant, and floored, because it is the only population here that does
 *       not shrink when the suite is fixed.</li>
 * </ol>
 *
 * <p><strong>The unit is the chain and not the statement.</strong> A MockMvc call is one statement,
 * so a seal that asked "is there a UUID in this statement?" would green a clock-named workflow
 * because some unrelated argument of the same chain carried a random id. Proven by a plant below.
 *
 * <p><strong>What this cannot see.</strong> Each shape is <em>planted</em> below as well as listed
 * here, because a hole that is only written down is a hole nobody re-measures, and none of them
 * exists in the tree today — the census prints every clock occurrence and its verdict on every run,
 * so a new one moves a number.
 * <ol>
 *   <li>A clock reached through a <strong>helper that returns one</strong> ({@code now()}, an
 *       injected {@code Clock}): no needle, no occurrence, nothing to classify.</li>
 *   <li>A clock <strong>passed into a call that composes the name elsewhere</strong>. The chain is
 *       then the bare argument and reads as timing. {@code unique(System.nanoTime())} is the small
 *       version; the family is wider than that example and is what matters —
 *       {@code String.format}, {@code "…".formatted(…)}, {@code StringBuilder.append},
 *       {@code String.join} and any local helper of your own compose a name out of sight of the
 *       {@code +} chain.</li>
 *   <li>A name whose <strong>literal half lives in a constant</strong>:
 *       {@code String s = PREFIX + System.nanoTime()} carries no literal in its chain, and the
 *       variable is never concatenated with one either. Closing it means resolving a same-file
 *       {@code static final String}.</li>
 * </ol>
 *
 * <p>Two shapes that used to be on that list are now caught, both by testing the string literal
 * ahead of the chain-wide difference: {@code "WF-" + (System.nanoTime() - BASE)} — a subtraction is
 * the other shortener — and a chain holding a name and a duration at once. The known-imprecise part
 * that remains is that {@code isElapsed} asks its question of the whole chain; making it
 * occurrence-local needs an offset on {@link SourceCallSites.Mention} and is the next step on this
 * seal, not a defect in the rule it enforces.
 */
class FixtureNameUniquenessTest {

    /** Every way this suite can read a wall or monotonic clock. */
    private static final List<String> CLOCKS = List.of(
            "System.nanoTime()", "System.currentTimeMillis()", "Instant.now()",
            "LocalDateTime.now()", "LocalDate.now()", "OffsetDateTime.now()",
            "ZonedDateTime.now()", "Clock.systemUTC()", "new Date()");

    /** Every platform-independent source of uniqueness a fixture name may use. */
    private static final List<String> RANDOM = List.of("UUID.randomUUID()", "Unique.");

    private static final List<String> NEEDLES =
            Stream.concat(CLOCKS.stream(), RANDOM.stream()).toList();

    /**
     * A clock occurrence that is an operand of a subtraction. Anchored on the call's own
     * {@code ()} so that {@code "u-" + System.nanoTime() + "-" + …} — a hyphen inside a literal
     * and a {@code +} in between — is not mistaken for one.
     */
    private static final Pattern ELAPSED = Pattern.compile("\\(\\)\\s*-\\s*[\\w(]");

    /** A conversion that turns a number into text, i.e. the right-hand side yields a name. */
    private static final Pattern YIELDS_STRING =
            Pattern.compile("toString\\(|String\\.valueOf\\(|toHexString\\(");

    /** Any {@code name(} or {@code a.b.name(} in an expression. */
    private static final Pattern INVOCATION = Pattern.compile("(\\w+(?:\\.\\w+)*)\\s*\\(");

    /**
     * Calls that pass a value through without giving it an identity of its own, so an expression
     * wrapped in one is still "derived from" what it wraps. Deliberately short: anything not named
     * here ends the derivation, which is the direction that under-reports an indirect identity
     * rather than inventing one.
     */
    private static final Set<String> TRANSPARENT = Set.of("Long.toString", "Integer.toString",
            "String.valueOf", "Objects.toString", "Math.abs", "Long.toHexString",
            "Integer.toHexString", "toString", "substring", "replace", "toLowerCase", "strip",
            "trim");

    /** {@link #NEEDLES} as invocation names, so {@code System.nanoTime()} matches a call to it. */
    private static final Set<String> CALL_NAMES = NEEDLES.stream()
            .map(needle -> needle.endsWith("()") ? needle.substring(0, needle.length() - 2) : needle)
            .map(needle -> needle.startsWith("new ") ? needle.substring(4) : needle)
            .collect(Collectors.toUnmodifiableSet());

    /** {@code UUID.randomUUID().toString()…substring(0, N)} — the width the chain actually keeps. */
    private static final Pattern UUID_WIDTH =
            Pattern.compile("UUID\\.randomUUID\\(\\)[\\w.()\"\\-, ]*?substring\\(\\s*0\\s*,\\s*(\\w+)\\s*\\)");

    /** A same-file {@code static final int} a width may be written as. */
    private static final Pattern INT_CONSTANT =
            Pattern.compile("static\\s+final\\s+int\\s+(\\w+)\\s*=\\s*(\\d+)");

    /**
     * Hex characters a truncated random source must keep. Eight is 32 bits, so <strong>one draw
     * against the 111 678 {@code ws-%} workspace slugs</strong> standing in the development
     * database on 2026-10-15 collides with probability 111 678 / 2<sup>32</sup> =
     * <strong>0.0026%</strong>. Written as the arithmetic and not as a verdict, so the next reader
     * recomputes it against their own occupancy instead of trusting this number.
     *
     * <p>Six was the first value here and it was fitted to the narrowest site in the tree rather
     * than drawn against that occupancy: 24 bits is 111 678 / 2<sup>24</sup> = <strong>0.67%</strong>
     * per draw, about two thirds of the 1.05% per-run collision this seal exists to delete (105
     * occupants in the 10 000-wide namespace a {@code % 1000000} modulus really leaves) — a bound
     * blessing most of its own subject, and citing the occupancy that says so as its reason.
     * {@link Unique#RANDOM_HEX} keeps twelve (3.9&times;10<sup>-8</sup>%) and is what a new site
     * uses; eight is the floor for the older {@code substring(0, 8)} slug idiom only.
     */
    private static final int MIN_RANDOM_HEX = 8;

    // Floors. Each sits well under what the census printed when it was set (2026-10-15, the day
    // the clock left the suite) and the census prints the live numbers on every run, which is the
    // only honest place for a count: this very comment carried the measured set for an afternoon
    // and three of its seven numbers were stale by the end of it, because adding a test file adds
    // sites.
    //
    // They are anti-vacuity, not change detection, and which population carries which floor is the
    // whole design: clock-identity is 0 and must stay 0, so it cannot carry a floor at all. The
    // clock-occurrence and timing floors are what notice a needle list or a classifier that stopped
    // seeing anything, and the random-identity floor is the only population here that does NOT
    // shrink when the suite is corrected. Never lower one to make a run pass.
    private static final int UNITS_FLOOR = 250;
    private static final int CLOCK_OCCURRENCE_FLOOR = 70;
    private static final int TIMING_FLOOR = 70;
    private static final int RANDOM_IDENTITY_FLOOR = 200;
    private static final int MULTI_SITE_FILE_FLOOR = 40;
    private static final int TRUNCATED_RANDOM_FLOOR = 40;
    private static final int MULTI_SITE_LINE_FLOOR = 5;

    private static final String REMEDY = """
            Every one of them must take its uniqueness from com.hamstrack.common.testsupport.Unique,
            which is 48 bits of a fresh random UUID plus a per-JVM counter and reads no clock:

              1. A display name          -> Unique.name("WF")        "WF-3f9a1c2b4d5e-7"
              2. A workspace slug        -> Unique.slug("ws")        lower case, slug-safe
              3. An e-mail address       -> Unique.email("u")        "u-3f9a1c2b4d5e-7@example.com"
              4. Any other shape         -> Unique.token()           the bare suffix

            Do NOT keep the clock beside the UUID and do NOT reach for a modulus to shorten a name:
            the modulus is what creates the collision, because it discards the high bits that differ
            between runs and keeps the low ones the host quantises (measured: 103 of the 105 rows one
            such fixture left behind end in 00, so % 1000000 leaves a namespace of ten thousand).""";

    private enum Kind { CLOCK_IDENTITY, RANDOM_IDENTITY, TIMING, RANDOM_VALUE }

    private record Member(SourceCallSites.Mention mention, Kind kind, String why) {

        String key() {
            return mention.position() + ":" + mention.needle();
        }

        String describe() {
            return mention.site() + "  " + mention.type() + "#" + mention.method() + "  "
                   + abbreviate(mention.chain());
        }
    }

    // ---------------------------------------------------------------------------------- the rule

    /**
     * <strong>Arm 1 — no fixture-name expression in the suite reads a clock.</strong> Also the
     * per-run census: a population that silently shrank is the greenest possible answer to a scan
     * that stopped working, so every floor below is printed with the number it is drawn against.
     */
    @Test
    void everyFixtureNameTakesItsUniquenessFromARandomSourceAndNeverFromAClock() throws IOException {
        var members = members(SourceCallSites.TEST_SOURCES);

        var byKind = new LinkedHashMap<Kind, List<Member>>();
        for (var kind : Kind.values()) {
            byKind.put(kind, members.stream().filter(m -> m.kind() == kind).toList());
        }
        var clockOccurrences = members.stream()
                .filter(m -> CLOCKS.contains(m.mention().needle())).toList();
        var offenders = byKind.get(Kind.CLOCK_IDENTITY);

        System.out.println("[fixture-identity] sites=" + members.size()
                           + " clock-occurrences=" + clockOccurrences.size()
                           + " clock-identity=" + offenders.size()
                           + " random-identity=" + byKind.get(Kind.RANDOM_IDENTITY).size()
                           + " timing=" + byKind.get(Kind.TIMING).size()
                           + " random-value=" + byKind.get(Kind.RANDOM_VALUE).size());

        assertThat(offenders.stream().map(Member::describe).toList())
                .as("""
                    A FIXTURE NAME IS ONLY AS UNIQUE AS THE CLOCK IT WAS BUILT FROM, AND THE \
                    CLOCK'S RESOLUTION IS A PROPERTY OF THE HOST.

                    The site(s) above compose a name, an e-mail or a slug from a clock reading. \
                    The suite database is shared and never emptied, so the row outlives the run and \
                    the next run's name must miss every row already there.

                    %s""".formatted(REMEDY))
                .isEmpty();

        assertThat(clockOccurrences.size())
                .as("clock occurrences in src/test/java: %d, under the floor of %d. A needle list "
                    + "that stopped matching makes arm 1 vacuous: it would report zero offenders "
                    + "because it sees no clocks at all. Fix the needles, never the floor.",
                        clockOccurrences.size(), CLOCK_OCCURRENCE_FLOOR)
                .isGreaterThanOrEqualTo(CLOCK_OCCURRENCE_FLOOR);
        assertThat(byKind.get(Kind.TIMING).size())
                .as("timing sites: %d, under the floor of %d. Timing is the class this seal must "
                    + "NOT red; a classifier that stopped producing it is one that calls every "
                    + "elapsed measure an identity, and the suite would be red everywhere.",
                        byKind.get(Kind.TIMING).size(), TIMING_FLOOR)
                .isGreaterThanOrEqualTo(TIMING_FLOOR);
        assertThat(byKind.get(Kind.RANDOM_IDENTITY).size())
                .as("fixture names with a random source: %d, under the floor of %d. This is the "
                    + "population that does NOT shrink when the suite is corrected, so it is the "
                    + "one that notices a broken walk or a narrowed chain parser.",
                        byKind.get(Kind.RANDOM_IDENTITY).size(), RANDOM_IDENTITY_FLOOR)
                .isGreaterThanOrEqualTo(RANDOM_IDENTITY_FLOOR);
    }

    /**
     * <strong>Arm 2 — a random source that carries a name is wide enough to carry it.</strong>
     *
     * <p>Arm 1 is satisfied by the <em>presence</em> of {@code UUID.randomUUID()}, and presence is
     * the weakest thing a guard can ask (HD-334 found six such arms). {@code substring(0, 2)} beside
     * a clock would pass arm 1 while carrying eight bits, so the kept width is read — resolved
     * through a same-file {@code static final int} when it is not a literal, and refused outright
     * when it cannot be resolved at all, because an unreadable width is not a wide one.
     */
    @Test
    void aRandomSourceThatCarriesAFixtureNameIsWideEnoughToCarryIt() throws IOException {
        var narrow = new ArrayList<String>();
        var measured = new ArrayList<String>();

        for (var unit : SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES, Set.of())) {
            narrow.addAll(narrowSources(unit, measured));
        }

        System.out.println("[fixture-identity] truncated-random-sources=" + measured.size());

        assertThat(narrow)
                .as("""
                    A RANDOM SOURCE NARROWED FAR ENOUGH CARRIES NOTHING, AND ARM 1 CANNOT SEE IT.

                    The site(s) above keep fewer than %d hex characters of a UUID — or a width this \
                    seal cannot resolve — while that UUID is the whole uniqueness of the name. \
                    Widen it, or use Unique.token(), which keeps %d.

                    Do not add a clock to make up the difference: that is the defect this seal \
                    exists for.""".formatted(MIN_RANDOM_HEX, Unique.RANDOM_HEX))
                .isEmpty();
        assertThat(measured.size())
                .as("truncated random sources read: %d, under the floor of %d. This arm reads the "
                    + "width of a `substring` on a random UUID; a smaller number means the pattern "
                    + "stopped matching and the arm asserts nothing.", measured.size(),
                        TRUNCATED_RANDOM_FLOOR)
                .isGreaterThanOrEqualTo(TRUNCATED_RANDOM_FLOOR);
    }

    /**
     * <strong>Arm 3 — the population is keyed on the site, not on the file or the class.</strong>
     *
     * <p>The granularity control. HD-306 shipped a population enumerated per class against a check
     * asked per site: one compliant row answered for three sites and the defect stayed green inside
     * the test written to kill it. Several files here hold both an offending and a compliant site,
     * so a future change that keys this population on the file or the type reds immediately.
     */
    @Test
    void thePopulationIsKeyedOnTheSiteAndNotOnTheFileOrTheClass() throws IOException {
        var members = members(SourceCallSites.TEST_SOURCES);

        var keys = members.stream().map(Member::key).toList();
        var perFile = new LinkedHashMap<String, Integer>();
        for (var member : members) {
            perFile.merge(member.mention().site().replaceAll(":\\d+$", ""), 1, Integer::sum);
        }
        var multiSite = perFile.values().stream().filter(n -> n > 1).count();
        var multiSiteLines = members.stream()
                .collect(Collectors.groupingBy(m -> m.mention().site(), Collectors.counting()))
                .values().stream().filter(n -> n > 1).count();

        System.out.println("[fixture-identity] files=" + perFile.size() + " multi-site-files="
                           + multiSite + " multi-site-lines=" + multiSiteLines);

        assertThat(keys).as("a site key must be unique: a duplicate means two occurrences collapsed "
                            + "into one member and one of them is unguarded for free")
                .doesNotHaveDuplicates();
        assertThat(keys).allSatisfy(key -> assertThat(key)
                .as("a member key carries the line, the column and the needle, so two occurrences "
                    + "on ONE line stay two members")
                .matches(".*\\.java:\\d+:\\d+:.+"));
        assertThat(multiSiteLines)
                .as("lines holding more than one occurrence: %d, under the floor of %d. A "
                    + "line-keyed population would collapse them, and the real tree has such lines "
                    + "(a name and a slug built in one expression are two members on one line).",
                        multiSiteLines, MULTI_SITE_LINE_FLOOR)
                .isGreaterThanOrEqualTo(MULTI_SITE_LINE_FLOOR);
        assertThat(multiSite)
                .as("files holding more than one fixture-identity site: %d, under the floor of %d. "
                    + "This floor is what makes the site-keyed population load-bearing — if no file "
                    + "held two sites, keying on the file would be indistinguishable.",
                        multiSite, MULTI_SITE_FILE_FLOOR)
                .isGreaterThanOrEqualTo(MULTI_SITE_FILE_FLOOR);
    }

    // ------------------------------------------------------------------------------- the plants

    /**
     * <strong>Arm 4 — the classifier on planted bytes, one row per decision it makes.</strong>
     * Each row is written the way this codebase writes it, planted in a temp tree so the repository
     * stays clean (HD-328), and asserted by verdict — a seal that cannot be shown to red on a
     * defect and green on its safe twin is decoration.
     */
    @Test
    void theClassifierTellsIdentityFromTimingOnPlantedBytes(@TempDir Path root) throws IOException {
        plant(root, "Planted.java", """
                class Planted {

                    private static final long BASE = 0L;
                    private static final String PREFIX = "WF-";

                    private String truncatedClockOnly() {
                        return "WF-" + (System.nanoTime() % 1000000);
                    }

                    private String fullClockOnly() {
                        return "Dup-" + System.nanoTime();
                    }

                    private String clockBesideAUuid() {
                        return "ws-" + UUID.randomUUID().toString().substring(0, 8) + "-"
                               + (System.nanoTime() % 100000);
                    }

                    private String randomOnly() {
                        return Unique.name("WF");
                    }

                    private String viaAVariable() {
                        String tag = Long.toString(Math.abs(System.nanoTime() % 1_000_000));
                        return "Deep  Freeze " + tag;
                    }

                    private long elapsed() {
                        var startedAt = System.nanoTime();
                        work();
                        return (System.nanoTime() - startedAt) / 1_000_000;
                    }

                    private void elapsedInAMessage() {
                        var startedAt = System.nanoTime();
                        assertThat(ok).as("refused after " + (System.nanoTime() - startedAt) + "ns");
                    }

                    private void aMomentInAMessage() {
                        long startMillis = System.currentTimeMillis();
                        run();
                        offenders.add(id + " did not advance (run started at " + startMillis + " ms)");
                    }

                    private void aNumericVariableUsedAsAName() {
                        long t = System.nanoTime();
                        create("WF-" + t);
                    }

                    private String aDifferenceUsedAsAName() {
                        return "WF-" + (System.nanoTime() - BASE);
                    }

                    private void aNameAndADurationInOneChain() {
                        create("WF-" + System.nanoTime() + " after "
                               + (System.nanoTime() - BASE) + "ns");
                    }

                    private void aLiteralHalfHeldInAConstant() {
                        String composed = PREFIX + System.nanoTime();
                        create(composed);
                    }

                    private void aClockArgumentOfSomethingElse() throws Exception {
                        var raw = issueVerification(user, Instant.now().plusSeconds(3600), null);
                        mockMvc.perform(post("/api/auth/verify-email")
                                .content("{\\"token\\":\\"" + raw + "\\"}"));
                    }

                    private String quotedNotCalled() {
                        return needles("System.nanoTime()", "x-" + Unique.token());
                    }
                }
                """);

        var byMethod = new LinkedHashMap<String, List<Member>>();
        for (var member : members(root)) {
            byMethod.computeIfAbsent(member.mention().method(), k -> new ArrayList<>()).add(member);
        }

        assertThat(kinds(byMethod, "truncatedClockOnly")).containsExactly(Kind.CLOCK_IDENTITY);
        assertThat(kinds(byMethod, "fullClockOnly"))
                .as("an untruncated reading is far safer and still a clock")
                .containsExactly(Kind.CLOCK_IDENTITY);
        assertThat(kinds(byMethod, "clockBesideAUuid"))
                .as("the UUID is compliant and the clock beside it is not — two sites, two verdicts, "
                    + "in one expression")
                .containsExactlyInAnyOrder(Kind.RANDOM_IDENTITY, Kind.CLOCK_IDENTITY);
        assertThat(kinds(byMethod, "randomOnly")).containsExactly(Kind.RANDOM_IDENTITY);
        assertThat(kinds(byMethod, "viaAVariable"))
                .as("a clock read into a variable and concatenated in a LATER statement is an "
                    + "identity use; the chain alone cannot see it, and this is the shape that made "
                    + "a one-line predicate green on a real offender")
                .containsExactly(Kind.CLOCK_IDENTITY);
        assertThat(kinds(byMethod, "elapsed"))
                .as("a difference is never an identity, and nor is the start marker it is taken from")
                .containsExactly(Kind.TIMING, Kind.TIMING);
        assertThat(kinds(byMethod, "elapsedInAMessage"))
                .as("""
                    THE PRICE OF THE ORDER, PLANTED SO THAT IT IS VISIBLE RATHER THAN REMEMBERED.

                    `isElapsed` is chain-wide, so a chain that both builds a name and prints a \
                    duration can only be read one way; the string-literal test therefore runs \
                    FIRST and the duration here is named as an identity. Over-reporting an \
                    innocent message is the direction that reds a bystander, and it is taken \
                    deliberately because the other order exempts every clock occurrence in any \
                    chain holding one difference - three such shapes were latent (two rows below). \
                    Measured 2026-10-15: zero live sites of this shape, the census byte-identical \
                    across the reorder, so the price today is this plant row and nothing else. \
                    The fix that pays neither price is an occurrence-local `isElapsed`, which \
                    needs an offset on `SourceCallSites.Mention` - the next step, not this one.""")
                .containsExactly(Kind.TIMING, Kind.CLOCK_IDENTITY);
        assertThat(kinds(byMethod, "aMomentInAMessage"))
                .as("""
                    a wall-clock marker interpolated into an offender message is a moment, not a \
                    name - and it is clock-derived and concatenated with a literal, so only the \
                    String test tells it from a fixture name. Two live sites of this shape \
                    (ScheduledJobHeartbeatTest) were named by the broader rule, which is how it \
                    was found""")
                .containsExactly(Kind.TIMING);
        assertThat(kinds(byMethod, "aNumericVariableUsedAsAName"))
                .as("""
                    THE DECLARED HOLE, PLANTED SO THAT IT IS VISIBLE RATHER THAN REMEMBERED.

                    A clock read into a `long` and concatenated in a later statement reads as \
                    timing: the concatenation carries no clock needle and the declaration carries \
                    no string. No site of this shape exists in the tree - the census prints every \
                    clock occurrence and its verdict on every run - and closing it would cost two \
                    live false reds on a moment in a message, which is the trade this row records. \
                    If this row ever reds, the hole has been closed and the row should say so.""")
                .containsExactly(Kind.TIMING);
        assertThat(kinds(byMethod, "aDifferenceUsedAsAName"))
                .as("""
                    A DIFFERENCE IS THE OTHER SHORTENER, AND IT IS NAMED.

                    `"WF-" + (System.nanoTime() - BASE)` is a name whose uniqueness is a clock, \
                    and the prose above predicts a future author reaching for a modulus; a \
                    subtraction from a fixed base does the same job. It read as TIMING until the \
                    string-literal test was moved ahead of the elapsed test, and no live site of \
                    the shape existed in either direction (measured 2026-10-15).""")
                .containsExactly(Kind.CLOCK_IDENTITY);
        assertThat(kinds(byMethod, "aNameAndADurationInOneChain"))
                .as("""
                    one chain, a genuine name and a genuine duration: the first is correctly \
                    named, the second is the over-report the order buys it, and both reds are \
                    cheaper than exempting the pair - which is what a chain-wide elapsed test \
                    tested first does. Occurrence-local `isElapsed` is what splits them.""")
                .containsExactly(Kind.CLOCK_IDENTITY, Kind.CLOCK_IDENTITY);
        assertThat(kinds(byMethod, "aLiteralHalfHeldInAConstant"))
                .as("""
                    THE SECOND DECLARED HOLE: THE NAME'S LITERAL HALF LIVES IN A CONSTANT.

                    `String composed = PREFIX + System.nanoTime()` carries no string literal in \
                    its chain, so the identity test cannot see it, and the variable is never \
                    concatenated with one either. No site of this shape exists in the tree - the \
                    census prints every clock occurrence and its verdict on every run - and \
                    closing it means resolving a same-file `static final String`, which is a \
                    parser change. If this row ever reds, the hole has been closed and the row \
                    should say so.""")
                .containsExactly(Kind.TIMING);
        assertThat(kinds(byMethod, "aClockArgumentOfSomethingElse"))
                .as("""
                    the variable IS concatenated with a literal, and the clock is still not what \
                    makes it unique - the token generator is, and the clock is an expiry argument. \
                    The indirect rule therefore also asks whether the variable's value is DERIVED \
                    from the occurrence; without that it named a whole family of innocent sites \
                    (measured 2026-10-15: arm 1 fell from 135 named sites to 118, and all \
                    seventeen were of this shape - a token, an expiry, a clock in the argument \
                    list)""")
                .containsExactly(Kind.TIMING);
        assertThat(kinds(byMethod, "quotedNotCalled"))
                .as("a clock QUOTED in a literal is not a clock call — this seal and its own plants "
                    + "must name the shape they refuse, so a scan that counted quoted text would "
                    + "report itself and could never red for the right reason")
                .containsExactly(Kind.RANDOM_IDENTITY);
    }

    /**
     * <strong>Arm 5 — the unit is the chain, not the statement.</strong> The one false green that a
     * simpler seal would have shipped: a MockMvc call is a single statement, so "is there a UUID in
     * this statement?" is answered by any argument of the chain, including an unrelated id in the
     * URL. Both halves are asserted — the offender is named, and the compliant twin is not.
     */
    @Test
    void aUuidElsewhereInTheSameStatementDoesNotExcuseAClockNamedFixture(@TempDir Path root)
            throws IOException {
        plant(root, "Chain.java", """
                class Chain {

                    private void offender() throws Exception {
                        mockMvc.perform(post("/api/workspaces/" + UUID.randomUUID() + "/admin/workflows")
                                        .header("Authorization", "Bearer " + token)
                                        .content("{\\"name\\":\\"WF-" + System.nanoTime() + "\\"}"))
                                .andExpect(status().isCreated());
                    }

                    private void compliant() throws Exception {
                        mockMvc.perform(post("/api/workspaces/" + UUID.randomUUID() + "/admin/workflows")
                                        .header("Authorization", "Bearer " + token)
                                        .content("{\\"name\\":\\"" + Unique.name("WF") + "\\"}"))
                                .andExpect(status().isCreated());
                    }
                }
                """);

        var members = members(root);

        assertThat(members.stream()
                .filter(m -> m.kind() == Kind.CLOCK_IDENTITY)
                .map(m -> m.mention().method()).toList())
                .as("the clock-named workflow is an offender even though the same statement carries "
                    + "a random workspace id")
                .containsExactly("offender");
        assertThat(members.stream().filter(m -> m.mention().method().equals("compliant")).toList())
                .as("and the compliant twin is not an offender")
                .allSatisfy(m -> assertThat(m.kind()).isEqualTo(Kind.RANDOM_IDENTITY));
    }

    /**
     * <strong>Arm 6 — a narrowed random source is named.</strong> The positive control for arm 2,
     * which is otherwise satisfied by every compliant site in the tree.
     */
    @Test
    void aRandomSourceNarrowedToNothingIsNamed(@TempDir Path root) throws IOException {
        plant(root, "Narrow.java", """
                class Narrow {

                    private static final int KEPT = 2;

                    private String literalWidth() {
                        return "ws-" + UUID.randomUUID().toString().substring(0, 2);
                    }

                    private String constantWidth() {
                        return "ws-" + UUID.randomUUID().toString().substring(0, KEPT);
                    }

                    private String unresolvableWidth() {
                        return "ws-" + UUID.randomUUID().toString().substring(0, width);
                    }

                    private String wideEnough() {
                        return "ws-" + UUID.randomUUID().toString().substring(0, 8);
                    }
                }
                """);

        var measured = new ArrayList<String>();
        var narrow = new ArrayList<String>();
        for (var unit : SourceCallSites.unitsUnder(root, Set.of())) {
            narrow.addAll(narrowSources(unit, measured));
        }

        assertThat(narrow)
                .as("the three narrowed widths are named — a literal, one written as a same-file "
                    + "constant, and one this seal cannot resolve, which is refused rather than "
                    + "assumed wide")
                .hasSize(3)
                .allSatisfy(entry -> assertThat(entry).doesNotContain("wideEnough"));
        assertThat(narrow.stream().map(entry -> entry.replaceAll(".*#(\\w+).*", "$1")).toList())
                .containsExactlyInAnyOrder("literalWidth", "constantWidth", "unresolvableWidth");
        assertThat(measured).as("and the wide one was read, not skipped").hasSize(4);
    }

    // -------------------------------------------------------------------------------- the engine

    private static List<Member> members(Path root) throws IOException {
        var units = SourceCallSites.unitsUnder(root, Set.of());
        if (root.equals(SourceCallSites.TEST_SOURCES)) {
            assertThat(units.size())
                    .as("compilation units walked under %s: %d, under the floor of %d — a short "
                        + "walk is a broken walk and every arm here asserts \"nothing offends\"",
                            root, units.size(), UNITS_FLOOR)
                    .isGreaterThanOrEqualTo(UNITS_FLOOR);
        }
        var members = new ArrayList<Member>();
        for (var unit : units) {
            members.addAll(members(unit));
        }
        return members;
    }

    /**
     * The narrowed random sources of one unit, appending every width it <em>read</em> to
     * {@code measured} so the caller can floor the reading itself.
     *
     * <p>One derivation, called by the arm over the real tree and by the arm that plants a
     * narrowed width: a plant that re-implements the check it is the control for proves the plant,
     * not the check (HD-334 shipped six arms of that shape).
     */
    private static List<String> narrowSources(SourceCallSites.CompilationUnit unit,
                                              List<String> measured) {
        var constants = new LinkedHashMap<String, Integer>();
        var constant = INT_CONSTANT.matcher(String.join("\n", unit.lines()));
        while (constant.find()) {
            constants.put(constant.group(1), Integer.valueOf(constant.group(2)));
        }
        var narrow = new ArrayList<String>();
        for (var member : members(unit)) {
            if (member.kind() != Kind.RANDOM_IDENTITY) {
                continue;
            }
            var width = UUID_WIDTH.matcher(member.mention().chain());
            while (width.find()) {
                measured.add(member.key());
                var kept = width.group(1).chars().allMatch(Character::isDigit)
                        ? Integer.valueOf(width.group(1))
                        : constants.get(width.group(1));
                if (kept == null) {
                    narrow.add(member.describe() + "  (width `" + width.group(1)
                               + "` does not resolve to a number in this file)");
                } else if (kept < MIN_RANDOM_HEX) {
                    narrow.add(member.describe() + "  (keeps " + kept + " hex characters, "
                               + 4 * kept + " bits)");
                }
            }
        }
        return narrow;
    }

    private static List<Member> members(SourceCallSites.CompilationUnit unit) {
        var statements = SourceCallSites.statementsOf(unit.lines());
        var members = new ArrayList<Member>();
        for (var mention : SourceCallSites.mentions(unit, NEEDLES)) {
            members.add(classify(mention, statements));
        }
        return members;
    }

    private static Member classify(SourceCallSites.Mention mention,
                                   List<SourceCallSites.Statement> file) {
        var clock = CLOCKS.contains(mention.needle());
        if (clock && isElapsed(mention.chain()) && mention.chain().indexOf('"') < 0) {
            return new Member(mention, Kind.TIMING, "an elapsed difference");
        }
        if (mention.chain().indexOf('"') >= 0) {
            return new Member(mention, clock ? Kind.CLOCK_IDENTITY : Kind.RANDOM_IDENTITY,
                    "composed with a string literal");
        }
        var declared = mention.declares();
        if (declared != null && yieldsAString(mention)
            && isDerivedFrom(mention.needle(), mention.declaredFrom())
            && concatenatedSomewhere(declared, file)) {
            return new Member(mention, clock ? Kind.CLOCK_IDENTITY : Kind.RANDOM_IDENTITY,
                    "read into `" + declared + "`, which is concatenated with a literal");
        }
        return new Member(mention, clock ? Kind.TIMING : Kind.RANDOM_VALUE,
                clock ? "a bare reading, never concatenated" : "a random value, not a name");
    }

    /**
     * Is {@code variable} an operand of a concatenation that carries a string literal, anywhere in
     * this file? Asked through the same chain parser the population is built with, so "is this an
     * operand of a name" has one answer in this seal rather than two.
     *
     * <p>An elapsed difference is not such an operand even when it is concatenated: an assertion
     * message that prints {@code "waited " + (System.nanoTime() - startedAt) + "ms"} would
     * otherwise make every start marker in the suite an identity use, which is the direction that
     * reds forty innocent sites rather than greening one bad one — but a seal that reds the
     * innocent gets switched off, so it is excluded here and planted below.
     *
     * <p>Deliberately <em>not</em> symmetric with {@link #classify}, which tests the literal first
     * and therefore does name a duration printed into one. The two ask different questions: there,
     * the clock occurrence is itself an operand of the text; here, the question is whether some
     * other value derived from it ever becomes text, and a start marker's only text-bearing use
     * being a duration is the live shape in this suite rather than a hypothetical.
     */
    private static boolean concatenatedSomewhere(String variable,
                                                 List<SourceCallSites.Statement> file) {
        for (var statement : file) {
            var text = statement.text();
            var from = 0;
            var at = 0;
            while ((at = text.indexOf(variable, from)) >= 0) {
                from = at + 1;
                var before = at == 0 ? ' ' : text.charAt(at - 1);
                var afterAt = at + variable.length();
                var after = afterAt >= text.length() ? ' ' : text.charAt(afterAt);
                if (statement.literal()[at] || isWord(before) || isWord(after) || after == '.') {
                    continue;
                }
                var chain = SourceCallSites.chainAround(statement, at);
                if (chain.indexOf('"') >= 0 && !isElapsed(chain)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Is {@code value} nothing but this needle and arithmetic or string conversions of it?
     *
     * <p>The other half of the indirect rule, and the half that decides whether it over-reports.
     * {@code var raw = issueVerification(user, Instant.now().plusSeconds(3600), null)} declares a
     * variable that IS concatenated with a literal two lines later — but what makes it unique is
     * the token generator, and the clock is an expiry argument. So the variable's value must be
     * <em>derived from</em> the occurrence: every invocation in the right-hand side is either the
     * clock itself or a transparent conversion of it.
     *
     * <p><strong>This condition is held by its plant, not by a live measurement.</strong> Measured
     * 2026-10-15 on the shipped bytes: neutralising it leaves {@code clock-identity} at zero and
     * moves two random sites, so a reader who deleted it would see a green suite — the whole of its
     * correctness is {@code aClockArgumentOfSomethingElse} in arm 4, which is where the number that
     * justifies it (seventeen named innocents of exactly that shape, out of 135) is recorded. It
     * earns its keep the next time a clock-and-token line is written, which is the day the
     * difference is seventeen false reds again.
     */
    private static boolean isDerivedFrom(String needle, String value) {
        if (value == null) {
            return false;
        }
        var invocation = INVOCATION.matcher(value);
        while (invocation.find()) {
            var name = invocation.group(1);
            if (!TRANSPARENT.contains(name) && !CALL_NAMES.contains(name)
                && !name.startsWith("Unique.")) {
                return false;
            }
        }
        return value.contains(needle);
    }

    private static boolean isElapsed(String chain) {
        return ELAPSED.matcher(chain).find();
    }

    /**
     * Is the variable this occurrence is read into a <strong>string</strong>?
     *
     * <p>The third condition on the indirect rule, and the one that keeps it from reading a
     * measurement as a name. {@code ScheduledJobHeartbeatTest} writes
     * {@code long startMillis = System.currentTimeMillis();} and then interpolates it into an
     * offender message — a clock-derived variable, concatenated with a literal, and not an
     * identity. In this codebase a name is a {@code String} and a moment is a {@code long}, so
     * that is what is asked. Measured 2026-10-15: without it, arm 1 named 118 sites, and the two
     * it dropped when this condition went in were exactly those two.
     *
     * <p><strong>The gap this leaves</strong>, declared rather than papered over:
     * {@code long t = System.nanoTime(); var name = "WF-" + t;} would read as timing, because the
     * concatenation carries no clock and the declaration carries no string. No site of that shape
     * exists in the tree (the census prints every clock occurrence and its verdict on every run),
     * and the arm below plants it so that the hole is visible rather than remembered.
     */
    private static boolean yieldsAString(SourceCallSites.Mention mention) {
        return "String".equals(mention.declaredType())
               || YIELDS_STRING.matcher(mention.declaredFrom()).find();
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static List<Kind> kinds(Map<String, List<Member>> byMethod, String method) {
        return byMethod.getOrDefault(method, List.of()).stream().map(Member::kind).toList();
    }

    private static void plant(Path root, String name, String source) throws IOException {
        Files.writeString(root.resolve(name), source, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String chain) {
        var oneLine = chain.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 110 ? oneLine : oneLine.substring(0, 107) + "...";
    }
}
