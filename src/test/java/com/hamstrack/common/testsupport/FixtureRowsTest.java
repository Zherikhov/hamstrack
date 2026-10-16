package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.repository.CrudRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <strong>{@link FixtureRows} deletes, and says so only when the row is really gone</strong>
 * (HD-329).
 *
 * <p>A teardown that calls {@code delete} and asserts nothing is indistinguishable from one that
 * works: both leave the happy path silent, and the rows pile up in a shared database for a month
 * before anybody counts them. So the mechanism is sealed here in both directions — the row that
 * goes, and the row that does not.
 */
class FixtureRowsTest {

    @Test
    void aRecordedRowIsDeletedNewestFirstAndForgottenAfterwards() {
        var deleted = new ArrayList<String>();
        var rows = new FixtureRows();
        var field = repositoryThatForgets(deleted, "field");
        var set = repositoryThatForgets(deleted, "set");

        var fieldId = rows.record(field, UUID.randomUUID(), "global field");
        rows.record(set, UUID.randomUUID(), "global field set");
        assertThat(rows.size()).isEqualTo(2);

        rows.deleteAll();

        assertThat(deleted)
                .as("newest first: a row built out of an earlier one is the one the foreign keys "
                    + "let go of first")
                .containsExactly("set", "field");
        assertThat(rows.size()).as("and the recording is forgotten, so the next test starts empty")
                .isZero();
        assertThat(fieldId).as("record returns the id so a call site stays one expression")
                .isNotNull();
    }

    @Test
    void aRowThatSurvivesTheDeleteIsNamedAndTheTeardownFails() {
        var rows = new FixtureRows();
        @SuppressWarnings("unchecked")
        CrudRepository<Object, UUID> deaf = mock(CrudRepository.class);
        var id = UUID.randomUUID();
        when(deaf.findById(id)).thenReturn(Optional.of(new Object()));
        when(deaf.existsById(id)).thenReturn(true);

        rows.record(deaf, id, "global workflow WF-planted");

        assertThatThrownBy(rows::deleteAll)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("global workflow WF-planted")
                .hasMessageContaining(id.toString())
                .hasMessageContaining("delete did not take");
        verify(deaf).delete(any());
        assertThat(rows.size())
                .as("a failed teardown still forgets its rows, or every later test in the class "
                    + "inherits the same failure and the first one stops being findable")
                .isZero();
    }

    /**
     * The arm that makes the teardown non-vacuous: a row created without being recorded is caught
     * by the census, which is the only witness a forgotten {@code record} has.
     */
    @Test
    void aTableThatGrewAcrossTheTestIsNamedEvenThoughNothingWasRecorded() {
        var rows = new FixtureRows();
        @SuppressWarnings("unchecked")
        CrudRepository<Object, UUID> repository = mock(CrudRepository.class);
        when(repository.count()).thenReturn(105L, 106L);

        rows.watch(repository, "global workflows");

        assertThatThrownBy(rows::deleteAll)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("global workflows: 105 rows before this test, 106 after")
                .hasMessageContaining("FixtureRows.record(");
    }

    @Test
    void aTableThatDidNotGrowPassesAndIsForgotten() {
        var rows = new FixtureRows();
        @SuppressWarnings("unchecked")
        CrudRepository<Object, UUID> repository = mock(CrudRepository.class);
        when(repository.count()).thenReturn(105L);

        rows.watch(repository, "global workflows");
        rows.deleteAll();
        rows.deleteAll();

        assertThat(rows.size()).isZero();
    }

    // ------------------------------------------------- every holder drains it (HD-329, round 1)

    /**
     * <strong>Every {@link FixtureRows} in the suite is drained</strong> — in an {@code @AfterEach}
     * when a class shares one across its tests, in the test itself when a test builds its own.
     *
     * <p>Until this arm, the mechanism had the exact property it exists to delete: a class that
     * constructs a {@code FixtureRows} and forgets the teardown deletes nothing, asserts nothing
     * and passes, which is how a global catalog grew by a row per run for a month under a green
     * suite. Every holder in the tree is correct today; the <em>next</em> one was silent.
     *
     * <p><strong>No exclusion list.</strong> The two populations are told apart by a property of
     * the code — whether the construction sits inside a test method — and each is then asked for
     * the drain that would really remove its rows, so this file's own four local instances are
     * members under their own arm rather than exemptions under the first. A written reason would
     * have had to excuse them, and an excuse outlives its subject.
     */
    @Test
    void everyHolderOfAFixtureRowsDrainsItInATeardown() throws IOException {
        var shared = new ArrayList<String>();
        var local = new ArrayList<String>();
        var undrained = new ArrayList<String>();

        for (var unit : SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES, Set.of())) {
            collectHolders(unit, shared, local, undrained);
        }

        System.out.println("[fixture-rows] shared-holders=" + shared.size() + " local-instances="
                           + local.size() + " undrained=" + undrained.size());

        assertThat(undrained)
                .as("""
                    A FIXTURE ROWS THAT IS NEVER DRAINED DELETES NOTHING, ASSERTS NOTHING AND \
                    PASSES.

                    The site(s) above construct a FixtureRows whose deleteAll() is never reached: \
                    a class field needs an @AfterEach that calls it, a local needs the call in the \
                    test that built it. Add the one that fits, in the class named above.

                    This is the shape of the defect the class was written for, one level up: a \
                    forgotten teardown is invisible in the happy path and shows up a month later \
                    as a row count.""")
                .isEmpty();
        assertThat(shared.size())
                .as("classes sharing a FixtureRows across their tests: %d, under the floor of %d. "
                    + "This arm asserts \"nothing is undrained\", so a construction pattern that "
                    + "stopped matching reports clean for ever.", shared.size(), SHARED_HOLDER_FLOOR)
                .isGreaterThanOrEqualTo(SHARED_HOLDER_FLOOR);
        assertThat(local.size())
                .as("FixtureRows built inside a single test: %d, under the floor of %d. The floor "
                    + "is what keeps the two populations distinguishable — with none of these, "
                    + "asking the question per construction instead of per class would look the "
                    + "same (the HD-306 shape).", local.size(), LOCAL_INSTANCE_FLOOR)
                .isGreaterThanOrEqualTo(LOCAL_INSTANCE_FLOOR);
    }

    /**
     * The positive control for the arm above: a holder with no teardown and a local that is never
     * drained are both named, and their correct twins are not. Planted in a temp tree so the
     * repository stays clean (HD-328).
     *
     * <p>{@code Local.java} is also the granularity control — two constructions in one file, one
     * drained and one not. A file-keyed or class-keyed population would let the good one answer
     * for the bad one and stay green with the defect planted, which is the shape HD-306 shipped
     * twice; the assertion below therefore names {@code forgetsItsOwn} and refuses
     * {@code drainsItsOwn} in the same breath.
     */
    @Test
    void aHolderWithNoTeardownAndAnUndrainedLocalAreBothNamed(@TempDir Path root) throws IOException {
        plant(root, "Forgetful.java", """
                class Forgetful {

                    private final FixtureRows globalRows = new FixtureRows();

                    @Test
                    void createsAGlobalRow() {
                        globalRows.record(workflowRepository, id, "global workflow");
                    }
                }
                """);
        plant(root, "Careful.java", """
                class Careful {

                    private final FixtureRows globalRows = new FixtureRows();

                    @AfterEach
                    void dropGlobalRows() {
                        globalRows.deleteAll();
                    }

                    @Test
                    void createsAGlobalRow() {
                        globalRows.record(workflowRepository, id, "global workflow");
                    }
                }
                """);
        plant(root, "Local.java", """
                class Local {

                    @Test
                    void drainsItsOwn() {
                        var rows = new FixtureRows();
                        assertThatThrownBy(rows::deleteAll).isInstanceOf(AssertionError.class);
                    }

                    @Test
                    void forgetsItsOwn() {
                        var rows = new FixtureRows();
                        rows.record(repository, id, "global workflow");
                    }
                }
                """);

        var shared = new ArrayList<String>();
        var local = new ArrayList<String>();
        var undrained = new ArrayList<String>();
        for (var unit : SourceCallSites.unitsUnder(root, Set.of())) {
            collectHolders(unit, shared, local, undrained);
        }

        assertThat(undrained).hasSize(2);
        assertThat(String.join("\n", undrained))
                .as("the field with no @AfterEach and the local its test forgot — and neither twin, "
                    + "one of which drains through a `::deleteAll` method reference")
                .contains("Forgetful")
                .contains("Local#forgetsItsOwn")
                .doesNotContain("Careful")
                .doesNotContain("drainsItsOwn");
        assertThat(shared).as("two planted fields, one correct and one not").hasSize(2);
        assertThat(local).as("two planted locals, one correct and one not").hasSize(2);
    }

    /** {@code FixtureRows x = new FixtureRows()} and {@code var x = new FixtureRows()} alike. */
    private static final Pattern CONSTRUCTION =
            Pattern.compile("(?:FixtureRows|var)\\s+(\\w+)\\s*=\\s*new\\s+FixtureRows\\s*\\(");

    private static final Pattern AFTER_EACH = Pattern.compile("^\\s*@AfterEach\\b");

    /**
     * Class-body indentation, i.e. a field rather than a statement inside a method — the same
     * four-space assumption {@code SourceCallSites.DECLARATION} is built on, and the same one a
     * seal there already holds for this tree.
     */
    private static final Pattern FIELD_INDENT = Pattern.compile("^ {0,4}\\S");

    // Anti-vacuity floors over the real tree, printed against the live numbers on every run. The
    // local floor is not decoration: without a local in the tree, asking the question per
    // CONSTRUCTION rather than per class would be indistinguishable from asking it per class, and
    // that collapse is the HD-306 shape this suite keeps re-learning.
    private static final int SHARED_HOLDER_FLOOR = 3;
    private static final int LOCAL_INSTANCE_FLOOR = 4;

    /**
     * Appends every {@code FixtureRows} construction in {@code unit} to {@code shared} or
     * {@code local}, and those whose {@code deleteAll()} is unreachable from the right scope to
     * {@code undrained}.
     *
     * <p>One walk for both the population and the check, and the key is the <em>construction</em>:
     * a class-keyed population would let one correct holder answer for a forgotten sibling field,
     * and a file-keyed one would let this class's own four locals answer for each other.
     */
    private static void collectHolders(SourceCallSites.CompilationUnit unit, List<String> shared,
                                       List<String> local, List<String> undrained) {
        var enclosing = SourceCallSites.enclosingMethods(unit.lines());
        var teardowns = methodsAnnotated(unit, enclosing, AFTER_EACH);
        for (var statement : SourceCallSites.statementsOf(unit.lines())) {
            var found = CONSTRUCTION.matcher(statement.text());
            while (found.find()) {
                if (statement.literal()[found.start()]) {
                    continue;
                }
                var variable = found.group(1);
                var line = statement.lineOf(found.start());
                var where = enclosing.get(line - 1);
                var isField = FIELD_INDENT.matcher(unit.lines().get(line - 1)).find();
                var site = unit.path().toString().replace('\\', '/') + ":" + line + "  "
                           + unit.type() + "#" + where + "  " + variable;
                (isField ? shared : local).add(site);
                var drained = drainScopes(unit, enclosing, variable);
                if (isField ? teardowns.stream().noneMatch(drained::contains)
                        : !drained.contains(where)) {
                    undrained.add(site + (isField
                            ? "  (shared by the class, and no @AfterEach calls " + variable
                              + ".deleteAll())"
                            : "  (built inside this test and never drained in it)"));
                }
            }
        }
    }

    /** The names of the methods {@code annotation} is written above, in this unit. */
    private static Set<String> methodsAnnotated(SourceCallSites.CompilationUnit unit,
                                                List<String> enclosing, Pattern annotation) {
        var names = new LinkedHashSet<String>();
        for (var i = 0; i < unit.lines().size(); i++) {
            if (!annotation.matcher(unit.lines().get(i)).find()) {
                continue;
            }
            for (var j = i; j < enclosing.size(); j++) {
                if (!enclosing.get(j).equals(enclosing.get(i))) {
                    names.add(enclosing.get(j));
                    break;
                }
            }
        }
        return names;
    }

    /**
     * The methods in which {@code variable}'s {@code deleteAll} is reached. Both spellings count —
     * {@code rows.deleteAll()} and the {@code rows::deleteAll} a test asserting on the teardown's
     * own failure uses — because a seal that saw only the call would demand a second drain beside
     * a perfectly good one.
     */
    private static Set<String> drainScopes(SourceCallSites.CompilationUnit unit,
                                           List<String> enclosing, String variable) {
        var drain = Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*(?:\\.|::)\\s*deleteAll\\b");
        var scopes = new LinkedHashSet<String>();
        for (var statement : SourceCallSites.statementsOf(unit.lines())) {
            var found = drain.matcher(statement.text());
            while (found.find()) {
                if (!statement.literal()[found.start()]) {
                    scopes.add(enclosing.get(statement.lineOf(found.start()) - 1));
                }
            }
        }
        return scopes;
    }

    private static void plant(Path root, String name, String source) throws IOException {
        Files.writeString(root.resolve(name), source, StandardCharsets.UTF_8);
    }

    /** A repository that deletes what it is given and then says the row is gone. */
    private static CrudRepository<Object, UUID> repositoryThatForgets(List<String> log, String name) {
        @SuppressWarnings("unchecked")
        CrudRepository<Object, UUID> repository = mock(CrudRepository.class);
        when(repository.findById(any())).thenReturn(Optional.of(new Object()));
        when(repository.existsById(any())).thenReturn(false);
        doAnswer(invocation -> log.add(name)).when(repository).delete(any());
        return repository;
    }
}
