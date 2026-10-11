package com.hamstrack.common.testsupport;

import com.hamstrack.ops.ContainerJvm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.cache.ContextCacheUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-343 &mdash; every JVM this project starts declares a heap bound, and the bound on the
 * test fork is read back from the fork that is running.</strong>
 *
 * <h2>Why this exists</h2>
 * The production container has had a heap bound since HD-152. The test JVM &mdash; the one every
 * developer and every CI run executes &mdash; had none, and neither did the coverage guard's fork.
 * Measured 2026-09-30 and again 2026-10-09: Surefire at all defaults is <em>one</em> JVM for the
 * whole suite ({@code forkCount=1}, {@code reuseForks=true}), it took the ergonomic default
 * {@code MaxHeapSize = 4127195136} (3.84 GiB, a quarter of a 15.37 GiB box) and an
 * <em>unbounded</em> {@code MaxMetaspaceSize = 18446744073709551615}, and Spring's context cache
 * defaulted to 32. In one working session the full suite was killed three times for low memory, so
 * every piece of work that day was verified by narrow {@code -Dtest=} runs &mdash; which disarm the
 * {@code [test-tree]} arm and say nothing about the rest of the tree. That is HD-265's failure mode
 * arriving by a different road.
 *
 * <h2>The category, and the unit it is keyed on</h2>
 * The rule is <em>every JVM this project starts declares a heap bound</em>, and the population is
 * read out of the build files on every run rather than listed here: the {@code argLine} property
 * and every forked {@code <java>} task in {@code pom.xml}, the {@code java} invocation in the
 * {@code Dockerfile}'s {@code ENTRYPOINT}/{@code CMD}, and {@code .mvn/jvm.config} if it ever
 * exists. The unit is the <strong>start site</strong>, not the file: {@code pom.xml} starts two
 * different JVMs with two different budgets, and a population keyed on the file would let one of
 * them answer for both. {@link #theCategoryIsKeyedOnTheStartSiteAndNotOnTheFile} is the live
 * control on that collapse.
 *
 * <h2>Why the rule says "JVM" when the same build also starts a dozen node processes</h2>
 * The ticket's subject is "the OS killed this build", which does not care which runtime did the
 * allocating, so the restriction to JVMs is a <strong>scope statement and it is measured rather
 * than assumed</strong>. On this box, 2026-10-10: node v24.14.0 reports
 * {@code heap_size_limit = 4288 MiB} per process; there are 12 logical CPUs; vitest's default pool
 * is {@code forks} with {@code maxForks = cpus - 1 = 11} ({@code vitest.config.ts} sets only
 * {@code testTimeout}, no pool); and {@code NODE_OPTIONS} appears <em>nowhere</em> in
 * {@code pom.xml} or {@code .github/workflows/} (zero occurrences in all three files). So the SPA
 * suite can in principle start eleven unbounded workers beside one bounded Surefire fork.
 *
 * <p>They are out of scope here for one reason and it is not that they are harmless: <strong>they
 * do not overlap the Surefire fork</strong> — {@code npm-test} is a separate
 * {@code frontend-maven-plugin} execution in the {@code test} phase and Maven runs the phase's
 * executions in sequence, so the suite that was being killed was never competing with them. Giving
 * node a bound is a real question with a different measurement behind it (the vitest/vite peak,
 * which this ticket did not take) and a different owner, and HD-343 deliberately did not widen into
 * it. What is <em>not</em> acceptable is leaving the restriction unstated, which is why this
 * paragraph exists rather than a word like "JVM" doing the excluding quietly.
 *
 * <h2>Why the metaspace half of the rule is narrower than the heap half</h2>
 * Only the two JVMs Maven starts are required to bound metaspace. The container is not excused by
 * oversight and not by a count: it runs under a cgroup whose {@code mem_limit} caps heap,
 * metaspace, code cache and thread stacks <em>together</em>, which is why its bound is
 * {@code -XX:MaxRAMPercentage} and not {@code -Xmx} at all (the reasoning is in the
 * {@code Dockerfile}, and {@code ContainerHandoverClaimsTest} is what holds its flags). A fork on a
 * developer laptop has no such ceiling, so each region it can grow without limit has to be named.
 *
 * <h2>Which arm covers which member, and where the cover runs out</h2>
 * This section used to say that the effect arm was a bonus because "the declaration is still
 * checked by the category tests above". That was <strong>false</strong>, and the tests gate proved
 * it: while {@link #startSites()} was reading a comment, the declaration arms were green with the
 * Surefire fork's heap bound deleted and the effect arm was the <em>only</em> thing that failed.
 * The division of labour is therefore stated as what each arm actually covers:
 * <ul>
 *   <li><strong>Declarations</strong> &mdash; {@link #everyJvmThisProjectStartsDeclaresAHeapBound}
 *       and {@link #everyJvmMavenStartsAlsoBoundsMetaspace} cover all members at every runner, and
 *       are only as good as the scan behind them, which is why
 *       {@link #everyMemberCapturedAnArgumentListAndNotADocument} now sits between them and the
 *       file.</li>
 *   <li><strong>Effect</strong> &mdash; {@link #theRunningForkCarriesTheBoundsTheBuildFileDeclares}
 *       is the only assertion here that reads an effect rather than a declaration, and it covers
 *       <strong>one member under one runner</strong>: the Surefire fork, only when Surefire started
 *       it. <em>Outside Maven there is no effect to read</em>, because the bounds genuinely do not
 *       reach an IDE run &mdash; the same limitation the {@code <enableAssertions>} comment in
 *       {@code pom.xml} states about {@code -ea}. It is not redundant with the declaration arms
 *       even when they work: a {@code -DargLine=…} on the command line <em>replaces</em> the pom's
 *       default rather than adding to it (measured, see that file), and only this arm can see
 *       that.</li>
 *   <li><strong>Context cache</strong> &mdash; unconditional, because that half lives in
 *       {@code src/test/resources/spring.properties} and is in force for every runner.</li>
 * </ul>
 * So the honest summary is that the container's and the guard fork's bounds are held by declaration
 * only, the Surefire fork's by declaration everywhere plus effect under Maven, and nothing here
 * reads an effect in an IDE at all.
 *
 * <p>It cannot see whether the numbers are the <em>right</em> numbers &mdash; that is a
 * measurement, and the measurements that chose them are in the two files beside the values.
 */
class JvmBoundsTest {

    private static final Path POM = Path.of("pom.xml");

    private static final Path DOCKERFILE = Path.of("Dockerfile");

    /**
     * Maven's own JVM. It has no declaration site in this repository today, which is the
     * <em>reason</em> it is not a member rather than an excuse for it: the moment somebody creates
     * this file the member acquires a site and {@link #everyJvmThisProjectStartsDeclaresAHeapBound}
     * starts demanding a bound in it. Measured 2026-10-09 for the record, so the next reader knows
     * which way the risk points: {@code MAVEN_OPTS=-Xmx256m -XX:MaxMetaspaceSize=256m} completed
     * both {@code mvnw compile} and {@code mvnw package -DskipTests}, so this JVM is not the one
     * that was being killed.
     */
    private static final Path MAVEN_JVM_CONFIG = Path.of(".mvn/jvm.config");

    /** Every spelling of "the heap has a ceiling" that any of these start sites uses. */
    private static final Pattern HEAP_BOUND =
            Pattern.compile("-(?:Xmx\\d|XX:MaxHeapSize=|XX:MaxRAMPercentage=|XX:MaxRAM=)");

    private static final Pattern METASPACE_BOUND = Pattern.compile("-XX:MaxMetaspaceSize=");

    /** XML comment syntax, blanked before any element scan of {@code pom.xml}. */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** {@code #} comment syntax, for the {@code Dockerfile} and {@code .mvn/jvm.config}. */
    private static final Pattern HASH_COMMENT = Pattern.compile("(?m)#[^\n]*");

    /**
     * The Surefire fork's declaration. Anchored to an element at the start of a line, with a body
     * that cannot contain {@code <}: a capture that cannot cross a tag boundary cannot become a
     * document. See {@link #startSites()} for what happened without that.
     */
    private static final Pattern ARG_LINE_ELEMENT =
            Pattern.compile("(?m)^\\s*<argLine>([^<]*)</argLine>");

    /**
     * How much of a member's captured arguments a failure message may quote. The project's budget
     * is 25 lines for a whole message, and the defect this class shipped once produced a 258-line
     * AssertJ dump with the same comment printed four times — a message that long is not read, so
     * it is not a message.
     */
    private static final int ARGS_QUOTED = 90;

    /**
     * A generous backstop on a captured argument list's length. <strong>It is not the control</strong>
     * &mdash; the {@code <} and newline tests beside it are, because an XML document contains both
     * and an argument list contains neither. This only catches a blob that somehow has neither, and
     * it is set well above anything legitimate: the container member is the union of every directive
     * {@link ContainerJvm#JVM_DIRECTIVES} names, so it grows when an operator adds an
     * {@code ENV JAVA_TOOL_OPTIONS}, and a tight bound here would red that legitimate edit while
     * the defect it exists for was 7602 characters.
     */
    private static final int ARGS_LENGTH_BACKSTOP = 600;

    /** Spring's own default, read from {@code ContextCacheUtils.retrieveMaxCacheSize} bytecode. */
    private static final int SPRING_DEFAULT_CACHE_SIZE = 32;

    private static final String CACHE_SIZE_PROPERTY = "spring.test.context.cache.maxSize";

    /** Set by Surefire's {@code <systemPropertyVariables>} only; absent in an IDE run. */
    private static final String STARTED_BY_SUREFIRE = "hamstrack.test-run.id";

    /**
     * One JVM this project starts. {@code site} is the unit of the category; {@code mavenStarted}
     * decides whether the metaspace half of the rule applies, for the reason in the class javadoc.
     */
    private record JvmStart(Kind kind, String site, String file, String args, boolean mavenStarted) {
    }

    /**
     * What sort of JVM start site a member is. <strong>The population is keyed on this and not on a
     * count</strong>, because a count was exactly satisfied: three members over two files, zero
     * slack, so adding one more forked {@code <java>} task to {@code pom.xml} — which this project
     * does — would have kept {@code >= 3} and the members-exceed-files control both true
     * <em>with the container member gone</em>. Every kind must be present, so a member can only be
     * lost by being noticed.
     */
    private enum Kind {
        SUREFIRE_FORK("the Surefire fork that runs the suite, from pom.xml's <argLine> property"),
        FORKED_ANT_TASK("a forked <java> task of the antrun plugin, e.g. the coverage guard"),
        CONTAINER("the production container's JVM, from the Dockerfile"),
        MAVEN_LAUNCHER("Maven's own JVM, from .mvn/jvm.config — absent by default, see the field");

        private final String describe;

        Kind(String describe) {
            this.describe = describe;
        }
    }

    /**
     * The kinds that must be found on every run. {@link Kind#MAVEN_LAUNCHER} is deliberately absent:
     * it has no declaration site in this repository, and demanding it would turn a correct tree red.
     */
    private static final List<Kind> REQUIRED_KINDS =
            List.of(Kind.SUREFIRE_FORK, Kind.FORKED_ANT_TASK, Kind.CONTAINER);

    // --------------------------------------------------------------- the category

    @Test
    void everyJvmThisProjectStartsDeclaresAHeapBound() {
        var members = startSites();

        assertEveryKindWasFound(members);

        var unbounded = members.stream()
                .filter(member -> !HEAP_BOUND.matcher(member.args()).find())
                .map(member -> member.site() + "  (" + member.file() + "): " + quoted(member))
                .toList();

        assertThat(unbounded)
                .as("""
                    A JVM this project starts has no heap ceiling, so on a box with no cgroup it may
                    grow to the ergonomic default of a quarter of physical RAM before the OS kills
                    it with no output at all. Give it one at the site named below: a -Xmx for a
                    process Maven starts, -XX:MaxRAMPercentage for one inside a container. The
                    numbers in use, and what they were measured against, are commented beside each.
                    Sites found in this run: %d. Unbounded:""".formatted(members.size()))
                .isEmpty();
    }

    @Test
    void everyJvmMavenStartsAlsoBoundsMetaspace() {
        var members = startSites().stream().filter(JvmStart::mavenStarted).toList();

        assertThat(members.stream().map(JvmStart::kind).toList())
                .as("The Maven-started half of the population collapsed. Keyed on kind rather than"
                    + " on a total of two, because two forks of the SAME kind would satisfy a total"
                    + " while one of these went missing.")
                .contains(Kind.SUREFIRE_FORK, Kind.FORKED_ANT_TASK);

        var unbounded = members.stream()
                .filter(member -> !METASPACE_BOUND.matcher(member.args()).find())
                .map(member -> member.site() + ": " + quoted(member))
                .toList();

        assertThat(unbounded)
                .as("""
                    A JVM Maven starts on a developer machine has no cgroup above it, so an
                    unbounded metaspace is a second region that can take the box down while the
                    heap bound looks after the first one and reports nothing. Add
                    -XX:MaxMetaspaceSize to the site named below. (The container is deliberately
                    outside this rule -- its mem_limit caps every region together, which is why its
                    bound is a percentage of the limit rather than an absolute heap.) Unbounded:""")
                .isEmpty();
    }

    /**
     * The control over the defect this class shipped: a scan that read a comment instead of an
     * element. It is phrased over the <em>shape</em> of what every scan captured rather than over
     * the one pattern that was wrong, because comment-blindness is a property of every regex that
     * reads a build file expecting elements, and the next such scan will be written by somebody who
     * has not read this story. An argument list is one line with no tag in it; a document is not.
     */
    @Test
    void everyMemberCapturedAnArgumentListAndNotADocument() {
        var members = startSites();
        var blobs = members.stream()
                .filter(member -> member.args().contains("<")
                                  || member.args().contains("\n")
                                  || member.args().length() > ARGS_LENGTH_BACKSTOP)
                .map(member -> member.site() + ": " + member.args().length() + " chars, "
                               + member.args().lines().count() + " lines, starts " + quoted(member))
                .toList();

        assertThat(blobs)
                .as("""
                    A scan below captured a DOCUMENT where a JVM's argument list belongs, so
                    whatever it then asserted was asserted about prose. This is how the first
                    version of this class passed with the Surefire fork's heap bound deleted: the
                    pattern matched from a `<argLine>` inside a comment to the real closing tag and
                    captured 7602 characters containing four `-Xmx` strings that were documentation
                    about other numbers. Make the scan comment-blind (withoutComments) and anchor it
                    to an element, rather than making this assertion looser. Offenders:""")
                .isEmpty();

        assertEveryKindWasFound(members);
    }

    @Test
    void theCategoryIsKeyedOnTheStartSiteAndNotOnTheFile() {
        var members = startSites();
        var files = new LinkedHashSet<String>();
        members.forEach(member -> files.add(member.file()));

        assertThat(members.size())
                .as("""
                    The unit of this rule is the START SITE, and this is the control that notices it
                    being merged back into the FILE. pom.xml starts two JVMs with two budgets -- the
                    Surefire fork that runs the suite and the small forked <java> that scans the
                    tree afterwards -- so there must be strictly more members than files for the
                    keying to be doing any work. If this fires after a tidy-up that gave them one
                    shared argLine, the tidy-up removed the distinction this rule is about: one
                    bound sized for the whole suite and its cached contexts is several times what
                    the scanner needs, so sharing it hides a leak in the scanner behind the suite's
                    headroom. Sites: %s. Files: %s."""
                        .formatted(members.stream().map(JvmStart::site).toList(), files))
                .isGreaterThan(files.size());
    }

    // --------------------------------------------------------------- the effects

    @Test
    @EnabledIfSystemProperty(named = STARTED_BY_SUREFIRE, matches = ".+",
            disabledReason = "The heap and metaspace bounds live in pom.xml's argLine and reach"
                             + " only the Maven fork; an IDE builds its own command line and passes"
                             + " neither, so outside Surefire there is no effect to read back."
                             + " The declaration is still checked by the category tests above, and"
                             + " the context cache -- the half that does reach every runner -- is"
                             + " checked unconditionally below.")
    void theRunningForkCarriesTheBoundsTheBuildFileDeclares() {
        var arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        var joined = String.join(" ", arguments);

        assertThat(joined)
                .as("""
                    This fork was started by Surefire and has no heap ceiling. Either pom.xml's
                    <properties><argLine> lost its bound, or this run passed -DargLine=... on the
                    command line: a user property REPLACES the pom's default outright rather than
                    adding to it (measured 2026-10-09 -- the forked command line carried only the
                    command-line value), so an override that forgets -Xmx silently restores the
                    unbounded default this ticket was about. Carry the bounds in your override.
                    Arguments this fork was given: %s""".formatted(arguments))
                .containsPattern(HEAP_BOUND);

        assertThat(joined)
                .as("""
                    This fork was started by Surefire and its metaspace is unbounded. Same two
                    causes, same remedy, as the heap assertion above. Arguments: %s"""
                        .formatted(arguments))
                .containsPattern(METASPACE_BOUND);

        assertThat(Runtime.getRuntime().maxMemory())
                .as("""
                    The heap the fork is actually running with does not match the -Xmx it was given,
                    so something downstream is re-sizing it and the declared bound is not the
                    effective one. Declared: %s. Effective Runtime.maxMemory(): %d bytes."""
                        .formatted(arguments, Runtime.getRuntime().maxMemory()))
                .isLessThanOrEqualTo(declaredMaxHeapBytes(joined));
    }

    @Test
    void theContextCacheIsBoundedBelowSpringsDefaultForEveryRunner() {
        assertThat(ContextCacheUtils.retrieveMaxCacheSize())
                .as("""
                    The Spring test context cache is back at or above its default of %d, so this JVM
                    may hold that many complete application contexts at once -- each with its own
                    SessionFactory, Hikari pool and bean graph. That is the lever HD-343 is about:
                    measured on the same 114-class subset, 32 cached contexts committed 1508 MiB of
                    heap against 1188 MiB at 8, for no measurable wall clock (4:25 against 4:26).
                    The bound lives in src/test/resources/spring.properties, which is on the
                    classpath of every runner including an IDE; that comment carries the whole
                    curve. Effective value now: %d."""
                        .formatted(SPRING_DEFAULT_CACHE_SIZE, ContextCacheUtils.retrieveMaxCacheSize()))
                .isLessThan(SPRING_DEFAULT_CACHE_SIZE);
    }

    @Test
    void anIgnoredSystemPropertyOverrideIsNotSilent() {
        var requested = System.getProperty(CACHE_SIZE_PROPERTY);
        var effective = String.valueOf(ContextCacheUtils.retrieveMaxCacheSize());

        if (requested == null) {
            assertThat(effective)
                    .as("No override was requested, so the effective value is the one in"
                        + " src/test/resources/spring.properties and that is all this case has to"
                        + " say.")
                    .isNotBlank();
            return;
        }

        assertThat(effective)
                .as("""
                    This run asked for %s=%s and is getting %s, because SpringProperties consults
                    the classpath resource spring.properties FIRST and falls back to
                    System.getProperty only when the key is absent there (measured, spring-core
                    7.0.8 bytecode). That is the reverse of the usual Spring ordering, so the
                    request was discarded in silence and this assertion exists to break the
                    silence. To try another cache size, edit
                    src/test/resources/spring.properties."""
                        .formatted(CACHE_SIZE_PROPERTY, requested, effective))
                .isEqualTo(requested);
    }

    // --------------------------------------------------------------- deriving the population

    /**
     * Every JVM start site this repository declares, read out of the build files. A site this
     * cannot parse is expected to be a member and will be reported as unbounded: a guard in doubt
     * asks for the bound, it does not excuse the site.
     *
     * <p><strong>No build-file text reaches a pattern here until its comments are blanked, and that
     * is the whole correctness of this method.</strong> Every read below does it at the read, except
     * the {@code Dockerfile}, which {@link ContainerJvm} strips on the way in — so the property is
     * about the text that reaches a pattern, not about the call sites, and counting them here would
     * go stale one read before this sentence looked wrong. The first version of this class scanned {@code pom.xml}
     * raw with {@code <argLine>(.*?)</argLine>} under {@code DOTALL}. The first literal
     * {@code <argLine>} in that file is inside the prose of the comment explaining why the setting
     * is a property and not an element, so the non-greedy match began in the comment and ran 111
     * lines to the real closing tag: ONE member whose captured arguments were 7602 characters of
     * documentation containing five different {@code -Xmx} strings, four of them prose about other
     * experiments ({@code 1234m} and {@code 777m} from the override probe, {@code 256m} from the
     * negative control). Deleting the real {@code -Xmx2560m} from the real element left this class
     * GREEN, for the one member the ticket exists to bound — a declaration arm passing on the
     * strength of a comment about itself. Two separate properties were wrong at once: the scan read
     * bytes nobody had declared, and its correctness had become a function of prose, so rewording
     * the comment would silently change which bytes it read.
     *
     * <p>The remedy is applied to the <em>class</em> of scan and not to the one that was caught:
     * comment-blindness is a property of every regex that reads a build file expecting elements, so
     * no such read is left raw, and the sibling {@code <java fork="true">} scan was
     * stripped in the same change even though it happened to parse correctly — it did so only
     * because no comment in the file happens to contain that string today, which is luck and not
     * design. Belt and braces on the member that failed: its pattern is also anchored to an element
     * at the start of a line with a {@code [^<]*} body, so its capture is structurally incapable of
     * swallowing a document even if the stripping ever regresses.
     * <p>The two halves are controlled by different things, and reading one as covering both is a
     * mistake this javadoc made until it was measured.
     * {@link #everyMemberCapturedAnArgumentListAndNotADocument} catches a capture that has become a
     * <em>document</em> — the 7602-character one — because it refuses a {@code <}, a newline or an
     * over-long body. It does <em>not</em> catch a short phantom: measured, a comment-borne
     * {@code <argLine>-Xmx9m</argLine>} on one line passes that arm untouched and is caught by the
     * metaspace arm instead, because one line with no {@code <} is argument-shaped. So that arm
     * holds the document shape and the stripping holds everything else; neither is redundant.
     */
    private static List<JvmStart> startSites() {
        var pom = withoutComments(read(POM), XML_COMMENT);
        var sites = new ArrayList<JvmStart>();

        var argLine = ARG_LINE_ELEMENT.matcher(pom);
        while (argLine.find()) {
            sites.add(new JvmStart(Kind.SUREFIRE_FORK, "Surefire test fork (<argLine>)", POM.toString(),
                    argLine.group(1).trim(), true));
        }

        // Each forked <java> task of the antrun plugin: its own JVM, its own <jvmarg> list.
        var forked = Pattern.compile("<java\\b([^>]*?fork\\s*=\\s*\"true\"[^>]*)>(.*?)</java>",
                Pattern.DOTALL).matcher(pom);
        while (forked.find()) {
            var classname = Pattern.compile("classname\\s*=\\s*\"([^\"]+)\"").matcher(forked.group(1));
            var named = classname.find() ? simpleName(classname.group(1)) : "unnamed";
            var args = new StringBuilder();
            var jvmarg = Pattern.compile("<jvmarg\\s+value\\s*=\\s*\"([^\"]+)\"").matcher(forked.group(2));
            while (jvmarg.find()) {
                args.append(jvmarg.group(1)).append(' ');
            }
            sites.add(new JvmStart(Kind.FORKED_ANT_TASK, "forked <java> task: " + named, POM.toString(),
                    args.toString().trim(), true));
        }

        // ONE member for the container's one JVM, and the directives it may be configured from are
        // ContainerJvm's list and not a second opinion of this class's own. See that class for what
        // the second opinion cost: this scan read only ENTRYPOINT/CMD, while the Dockerfile's own
        // comment recommends ENV JAVA_TOOL_OPTIONS=-Xmx… to an operator raising the memory limit,
        // so following the documented advice would have reported the production container
        // unbounded. Comments and continuation lines are handled by the shared parser.
        var containerJvm = ContainerJvm.jvmConfiguringDirectives(read(DOCKERFILE));
        if (!containerJvm.isBlank()) {
            sites.add(new JvmStart(Kind.CONTAINER, "container JVM (" + ContainerJvm.JVM_DIRECTIVES.size()
                                   + " directives read)", DOCKERFILE.toString(), containerJvm, false));
        }

        if (Files.exists(MAVEN_JVM_CONFIG)) {
            sites.add(new JvmStart(Kind.MAVEN_LAUNCHER, "Maven's own JVM (.mvn/jvm.config)", MAVEN_JVM_CONFIG.toString(),
                    withoutComments(read(MAVEN_JVM_CONFIG), HASH_COMMENT).trim(), true));
        }

        return sites;
    }

    /**
     * {@code text} with every comment blanked out, newlines preserved so that line-anchored
     * patterns still see the same line structure. Blanking rather than deleting matters for the
     * {@code Dockerfile} read, whose pattern is {@code (?m)^\s*(ENTRYPOINT|CMD)} — deleting a
     * comment would join its neighbours and move a directive off the start of a line.
     */
    private static String withoutComments(String text, Pattern commentSyntax) {
        return commentSyntax.matcher(text)
                .replaceAll(match -> match.group().replaceAll("[^\n]", ""));
    }

    /**
     * The {@code -Xmx} this fork was given, in bytes, as a ceiling for the effective heap. A JVM
     * always reports slightly less than it was asked for, so this is an upper bound and not an
     * equality; what it catches is an effective heap that does not come from the declared flag at
     * all.
     */
    private static long declaredMaxHeapBytes(String arguments) {
        var xmx = Pattern.compile("-Xmx(\\d+)([kmgKMG]?)").matcher(arguments);
        if (!xmx.find()) {
            return Long.MAX_VALUE;
        }
        long value = Long.parseLong(xmx.group(1));
        return switch (xmx.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> value * 1024L;
            case "m" -> value * 1024L * 1024L;
            case "g" -> value * 1024L * 1024L * 1024L;
            default -> value;
        };
    }

    /**
     * Refuses a run in which any required kind of start site was not found. Keyed on the kind so
     * that a member cannot be replaced by an unrelated one of a different kind and still satisfy a
     * total; the failure names the kind that went missing and what it is.
     */
    private static void assertEveryKindWasFound(List<JvmStart> members) {
        var found = members.stream().map(JvmStart::kind).collect(java.util.stream.Collectors.toSet());
        var missing = REQUIRED_KINDS.stream().filter(kind -> !found.contains(kind))
                .map(kind -> "  " + kind + " - " + kind.describe)
                .toList();

        assertThat(missing)
                .as("""
                    A KIND of JVM start site was not found at all, so whatever this run then
                    asserted, it did not assert it about that one. This is a broken scan and not a
                    clean tree: each kind below is derived from a file that is still in the
                    repository. Check that the scan in startSites() still matches the file's
                    shape before changing anything here. Missing:""")
                .isEmpty();
    }

    /**
     * A member's captured arguments, bounded so one offender cannot spend the whole message budget.
     * The length is printed when it is truncated, because "there is more of this than you are being
     * shown" is the part a reader needs in order to suspect the scan rather than the declaration.
     */
    private static String quoted(JvmStart member) {
        var args = member.args().replace("\n", "\\n");
        return args.length() <= ARGS_QUOTED
                ? args
                : args.substring(0, ARGS_QUOTED) + "... (" + args.length() + " chars in total)";
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
    }
}
