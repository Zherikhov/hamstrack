package com.hamstrack.common.testsupport;

import com.hamstrack.ops.PublishedCredentials;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>The harness proves its own scan (HD-296 §4.7).</strong>
 *
 * <p>Every population {@link Doors} offers is asserted at its floor on the real tree here — this is
 * the run that keeps the floors honest, and the {@code [doors]} line it prints is the count witness
 * (one per run, every {@code describe()}; a consumer never prints its own). "Every" is held by
 * equality, not by a count: the set of population methods floored here must equal the set
 * reflection finds on {@code Doors}, so a population added without a floor is red by name. Floors
 * are ~85 % of the count on the day the population was written for the large ones and the exact
 * count for the populations of five or fewer, where a deleted door is worth a deliberate edit (D4).
 * Raise deliberately; never lower to pass — the message says which of three causes to look for.
 * {@link Doors#requestRecords()} is the one population whose floor says nothing about which members
 * it holds (its by-name half re-finds a record the closure dropped), so it also gets an exact
 * witness: its reachability half is derived a second way from the body parameters and held equal.
 *
 * <p>The other three things this class holds: the compiled tree Doors scans is the tracked source
 * tree (D5 — a class only on the compiled side is a stale {@code target/classes}, one only on the
 * source side is a compile the scan is not looking at); the {@link Population} primitives do what
 * their javadoc says (a permanent positive control for {@code floor}, the parent count in
 * {@code filter}, the refusal in {@code excluding}); and every population method states its blind
 * spots under a fixed {@code Not included:} marker, checked against the source of {@code Doors.java}
 * so a new population cannot be added without saying what it does not see.
 */
class DoorsHarnessTest {

    /** The one source this class reads: the harness's own javadoc. */
    private static final Path DOORS_SOURCE = Path.of("src", "test", "java", "com", "hamstrack", "common", "testsupport", "Doors.java");

    private static final String BLIND_SPOT_MARKER = "Not included:";

    // ------------------------------------------------------------------ floors on the real tree

    /**
     * §4.3, every population at its floor — and "every" is an equality: the keys of the map below
     * are {@code Doors} method names and must equal {@link #populationMethods()}, so a population
     * added without a floor cannot escape both the floors and the {@code [doors]} line by never
     * being typed here. The counts on 2026-09-08, measured by reflection, are the baseline the floors were cut
     * from; the {@code [doors]} line is the number that stays current.
     */
    @Test
    void everyPopulationClearsItsFloor() {
        // Keyed by the Doors method name — the key set is the claim, the values are the floors.
        var floored = new LinkedHashMap<String, Population<?>>();
        floored.put("productionClasses", Doors.productionClasses().floor(250));
        floored.put("webBeans", Doors.webBeans().floor(30));
        floored.put("handlers", Doors.handlers().floor(250));
        floored.put("writeHandlers", Doors.writeHandlers().floor(150));
        floored.put("readHandlers", Doors.readHandlers().floor(90));
        floored.put("controllerParams", Doors.controllerParams().floor(500));
        floored.put("requestRecords", Doors.requestRecords().floor(45));
        floored.put("repositories", Doors.repositories().floor(40));
        floored.put("tables", Doors.tables().floor(35));
        floored.put("repositoryFinders", Doors.repositoryFinders("issues").floor(1));
        floored.put("scheduledJobs", Doors.scheduledJobs().floor(10));
        floored.put("servletFilters", Doors.servletFilters().floor(4));
        floored.put("mailSendSites", Doors.mailSendSites().floor(3));
        floored.put("problemJsonWriters", Doors.problemJsonWriters().floor(25));

        // Narrowings and second calls the consumers rely on: floored and printed, but not
        // populations of their own, so they are outside the equality below.
        var narrowed = List.of(
                Doors.controllerParams().filter("annotated", p -> p.binding() != Doors.Binding.OTHER).floor(500),
                Doors.repositoryFinders("sprint_scope_events").floor(1),
                Doors.problemJsonWriters()
                        .filter("advice handlers", w -> w.kind() == Doors.WriterKind.ADVICE_HANDLER).floor(20),
                Doors.problemJsonWriters()
                        .filter("pre-MVC doors", w -> w.kind() != Doors.WriterKind.ADVICE_HANDLER).floor(5));

        // The count witness: one line per run, every describe(), diffable across CI logs.
        var described = new ArrayList<String>();
        described.add("root=" + Doors.codeSourceRoot());
        floored.values().forEach(p -> described.add(p.describe()));
        narrowed.forEach(p -> described.add(p.describe()));
        System.out.println("[doors] " + String.join(" | ", described));

        var withoutFloor = new TreeSet<>(populationMethods());
        withoutFloor.removeAll(floored.keySet());
        assertThat(withoutFloor)
                .as("""
                        A population Doors offers has no floor asserted in everyPopulationClearsItsFloor: %s

                        Every consumer trusts a population exactly as far as a collapsed scan of it is \
                        refused somewhere, and this test is the one place that refuses one for EVERY \
                        population on EVERY run (a consumer floors only what it uses). Add \
                        `floored.put("<method>", Doors.<method>().floor(n))` above — n at ~85 %% of \
                        today's count for a large population, the exact count at five or fewer — and \
                        it prints on the [doors] line from then on.""", withoutFloor)
                .isEmpty();
        assertThat(new TreeSet<>(floored.keySet()))
                .as("a key above names a method Doors does not have — a typo, or a population that was renamed")
                .isEqualTo(populationMethods());
    }

    /** The set of public static methods on {@link Doors} returning a {@link Population} — the one source both harness claims read. */
    static Set<String> populationMethods() {
        var reflected = new TreeSet<String>();
        for (Method method : Doors.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == Population.class) {
                reflected.add(method.getName());
            }
        }
        return reflected;
    }

    /**
     * Every repository of an entity is reachable through its table, and a table nobody maps answers
     * an empty population whose name says so — the {@code floor(1)} a consumer writes then fails
     * with the known tables listed rather than with a bare zero.
     */
    @Test
    void repositoryFindersOfAnUnknownTableSayWhichTablesExist() {
        assertThatThrownBy(() -> Doors.repositoryFinders("no_such_table").floor(1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no repository maps `no_such_table`")
                .hasMessageContaining("issues");

        var issueRepositories = Doors.repositoryFinders("issues").stream()
                .map(f -> f.repository().getSimpleName()).collect(Collectors.toCollection(TreeSet::new));
        assertThat(issueRepositories)
                .as("the four repositories whose domain type is Issue — a fifth is fine, a missing one means "
                    + "ResolvableType stopped resolving the domain type of a bare Repository<Issue, UUID>")
                .contains("IssueRepository", "FlowReportRepository", "CycleTimeReportRepository", "AgingReportRepository");
    }

    /**
     * Consumers key on {@link Doors.Handler#id()}, so two handlers with one id would collapse into
     * one row in every map built from it — an overload that silently loses its sibling's coverage.
     */
    @Test
    void handlerIdsAreUnique() {
        var seen = new LinkedHashMap<String, List<Doors.Handler>>();
        for (var handler : Doors.handlers().floor(250)) {
            seen.computeIfAbsent(handler.id(), k -> new ArrayList<>()).add(handler);
        }
        var duplicates = seen.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " ← " + e.getValue()).toList();
        assertThat(duplicates)
                .as("""
                        Two handlers share one Handler.id() (SimpleName#method). RequestFieldLengthBoundTest and \
                        WriteThrottleCoverageTest key their coverage and exemptions on the id, so one of these two \
                        would be certified by a row written about the other. Rename one of the two methods.""")
                .isEmpty();
    }

    /**
     * P4's exact witness. {@link Doors#requestRecords()} is the one population a floor cannot vouch
     * for member by member: its by-name half re-finds any {@code *Request} record the closure
     * dropped, so the count holds while the record is silently {@code byNameOnly} and its handler
     * is gone from {@code mountedBy}. So the reachability half is derived a second way — every
     * BODY / PART parameter whose raw type is a production record — and the two derivations are held
     * <em>equal</em>: the same set of records at depth 0, and for each such (record, handler) pair
     * the handler present in {@code mountedBy}.
     */
    @Test
    void everyBodyRecordIsReachedByRequestRecordsFromItsHandler() {
        var production = Set.copyOf(Doors.productionClasses().floor(250).members());

        // Derivation 1: the body parameters themselves — record FQCN → the handlers mounting it directly.
        var fromParams = new TreeMap<String, Set<String>>();
        for (var param : Doors.controllerParams().floor(500)) {
            if (param.binding() != Doors.Binding.BODY && param.binding() != Doors.Binding.PART) {
                continue;
            }
            var raw = param.parameter().getType();
            if (raw.isRecord() && production.contains(raw)) {
                fromParams.computeIfAbsent(raw.getName(), k -> new TreeSet<>()).add(param.handler().id());
            }
        }
        assertThat(fromParams)
                .as("almost no body parameter has a production record type — Binding classification or the "
                    + "parameter walk collapsed, and the equality below would hold between two empty sets")
                .hasSizeGreaterThanOrEqualTo(40);

        // Derivation 2: what requestRecords() says is mounted at depth 0 (via empty), and by whom.
        var byName = new HashMap<String, Doors.RequestRecord>();
        var fromRecords = new TreeMap<String, Set<String>>();
        for (var record : Doors.requestRecords().floor(45)) {
            byName.put(record.type().getName(), record);
            if (!record.byNameOnly() && record.via().isEmpty()) {
                fromRecords.put(record.type().getName(), mountIds(record));
            }
        }

        var offenders = new ArrayList<String>();
        fromParams.forEach((type, handlers) -> {
            var record = byName.get(type);
            for (var handler : handlers) {
                if (record == null || record.byNameOnly()) {
                    offenders.add(simpleName(type) + " ← " + handler + ": a body parameter of this handler, but the "
                                  + "reachability half of requestRecords() did not reach it"
                                  + (record == null ? " (not in the population at all)" : " (found by its *Request name only)"));
                } else if (!mountIds(record).contains(handler)) {
                    offenders.add(simpleName(type) + " ← " + handler + ": reached, but this handler is missing from "
                                  + "mountedBy — " + record.describe());
                }
            }
        });
        fromRecords.forEach((type, handlers) -> {
            if (!fromParams.containsKey(type)) {
                offenders.add(simpleName(type) + " ← " + handlers + ": requestRecords() says it is mounted at depth 0, "
                              + "but no BODY/PART parameter has this raw type");
            }
        });
        assertThat(offenders)
                .as("""
                        The two derivations of "records read directly from a request body" disagree:
                          %s

                        Derivation 1 is Doors.controllerParams() (BODY/PART parameters whose raw type is a \
                        production record); derivation 2 is Doors.requestRecords() (via empty, and mountedBy). \
                        A record on the first side and not the second fell out of the ResolvableType closure \
                        (collectRecords) — and, when it is named *Request, was re-found by name, which is why \
                        the floor stayed green. Fix the closure in Doors; never the floor, never this list.""",
                        String.join("\n  ", offenders))
                .isEmpty();
        assertThat(fromRecords.keySet())
                .as("the directly mounted request records, derived from the parameters and from requestRecords()")
                .isEqualTo(fromParams.keySet());
    }

    private static Set<String> mountIds(Doors.RequestRecord record) {
        return record.mountedBy().stream().map(Doors.Handler::id).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    // ------------------------------------------------------------------ D5: compiled ⇄ tracked

    /**
     * The compiled tree Doors scans is exactly the tracked source tree: top-level class names on the
     * compiled side equal {@code git ls-files src/main/java/**&#47;*.java} mapped to FQCNs
     * ({@code package-info} excluded on both sides).
     */
    @Test
    void compiledTreeMatchesTrackedSources() {
        var compiled = Doors.productionClasses().floor(250).stream()
                .filter(c -> c.getEnclosingClass() == null && !c.getName().contains("$"))
                .map(Class::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        var tracked = new TreeSet<String>();
        var trackedFiles = 0;
        for (var file : PublishedCredentials.trackedFiles()) {
            var path = PublishedCredentials.repositoryPath(file);
            if (!path.startsWith("src/main/java/") || !path.endsWith(".java")) {
                continue;
            }
            trackedFiles++;
            var name = path.substring("src/main/java/".length(), path.length() - ".java".length()).replace('/', '.');
            if (!name.endsWith("package-info") && !name.endsWith("module-info")) {
                tracked.add(name);
            }
        }
        assertThat(trackedFiles)
                .as("git ls-files listed almost no production sources — this test must run from a checkout at the module root")
                .isGreaterThan(250);

        var onlyCompiled = new TreeSet<>(compiled);
        onlyCompiled.removeAll(tracked);
        var onlyTracked = new TreeSet<>(tracked);
        onlyTracked.removeAll(compiled);

        var report = new ArrayList<String>();
        onlyCompiled.forEach(c -> report.add("only compiled: " + c + " — a stale class file; run mvnw clean"));
        onlyTracked.forEach(t -> report.add("only tracked: src/main/java/" + t.replace('.', '/')
                                            + ".java — this class is not in the tree Doors scans"));
        assertThat(report)
                .as("""
                        The compiled tree Doors scans and the tracked source tree disagree. A class only on the \
                        compiled side is a phantom door (its source is gone; `mvnw clean` removes it); a class only on \
                        the source side is one the scan is not looking at (a compile that did not run, or a code-source \
                        root that moved). Every population inherits whichever side is wrong.""")
                .isEmpty();
    }

    // ------------------------------------------------------------------ the Population primitives

    /** The permanent positive control: a floor that is not met throws the §4.5 message. */
    @Test
    void floorRefusesAPopulationBelowIt() {
        var probe = Population.of("probe doors", List.of("a", "b"));

        assertThatThrownBy(() -> probe.floor(3))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("probe doors: the scan saw 2, under the floor of 3.")
                .hasMessageContaining("Exactly one of these is true:")
                .hasMessageContaining("Do not lower a floor to make a run pass. (probe doors: 2 (floor 3))");

        assertThat(probe.floor(2).describe()).isEqualTo("probe doors: 2 (floor 2)");
        assertThat(Population.floorMessage("probe doors", 2, 3, "probe doors: 2 (floor 3)").lines().count())
                .as("a failure message names the action in at most 25 lines")
                .isLessThanOrEqualTo(25);
    }

    @Test
    void filterRemembersTheParentCount() {
        var narrowed = Population.of("letters", List.of("a", "b", "c")).filter("vowels only", "aeiou"::contains);

        assertThat(narrowed.members()).containsExactly("a");
        assertThat(narrowed.describe()).isEqualTo("letters: 1 (filtered from 3 — vowels only)");
        assertThat(narrowed.floor(1).describe()).isEqualTo("letters: 1 (filtered from 3 — vowels only; floor 1)");
    }

    @Test
    void excludingRefusesAMemberThatIsNotLive() {
        var letters = Population.of("letters", List.of("a", "b", "c"));

        assertThat(letters.excluding("EXEMPT", Set.of("b")).members()).containsExactly("a", "c");
        assertThatThrownBy(() -> letters.excluding("EXEMPT", Set.of("b", "z")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("letters: `EXEMPT` excludes 1 member(s) that are not live:")
                .hasMessageContaining("  z")
                .hasMessageNotContaining("  b");
    }

    // ------------------------------------------------------------------ AC 7: blind spots

    /** {@code public static … Population<…> name(} in {@code Doors.java}. */
    private static final Pattern POPULATION_METHOD =
            Pattern.compile("(?m)^\\s*public static (?:<[^>]+>\\s+)?Population<[^\\n]*?>\\s+(\\w+)\\(");

    /**
     * Every public population method carries a {@code Not included:} paragraph in its javadoc — a
     * presence check on a fixed marker, not a prose classifier. The set of methods found in the
     * source is held equal to the set reflection sees on {@link Doors}, so the check cannot go
     * quiet by missing a declaration.
     */
    @Test
    void everyPopulationMethodStatesItsBlindSpots() {
        var source = PublishedCredentials.read(DOORS_SOURCE);
        var matcher = POPULATION_METHOD.matcher(source);
        var inSource = new TreeSet<String>();
        var withoutMarker = new ArrayList<String>();
        while (matcher.find()) {
            var name = matcher.group(1);
            inSource.add(name);
            if (!javadocBefore(source, matcher.start()).contains(BLIND_SPOT_MARKER)) {
                withoutMarker.add("Doors." + name + "()");
            }
        }

        assertThat(inSource)
                .as("the source regex and reflection must see the same population methods, or this check is "
                    + "silently skipping one")
                .isEqualTo(populationMethods())
                .hasSizeGreaterThanOrEqualTo(14);

        assertThat(withoutMarker)
                .as("""
                        A population method has no "%s" paragraph in its javadoc. A consumer trusts a clean pass \
                        over a population exactly as far as it knows what the population cannot see; write the \
                        blind spots (what the predicate misses by construction — registrations vs classes, \
                        naming conventions, reflection vs behaviour) under that marker, in the javadoc of the \
                        method itself.""", BLIND_SPOT_MARKER)
                .isEmpty();
    }

    /** The javadoc block that ends immediately before {@code position}, with only whitespace between. */
    private static String javadocBefore(String source, int position) {
        int cursor = position;
        while (cursor > 0 && Character.isWhitespace(source.charAt(cursor - 1))) {
            cursor--;
        }
        if (cursor < 2 || !source.startsWith("*/", cursor - 2)) {
            return "";
        }
        int open = source.lastIndexOf("/**", cursor - 2);
        return open < 0 ? "" : source.substring(open, cursor);
    }
}
