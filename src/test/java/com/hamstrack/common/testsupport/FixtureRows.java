package com.hamstrack.common.testsupport;

import org.springframework.data.repository.CrudRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Rows a fixture created in a namespace that outlives the run, and their removal</strong>
 * (HD-329).
 *
 * <p>The suite database is shared and never emptied. A fixture row scoped to a workspace this run
 * created is invisible to every other run; a row in the <em>global</em> admin catalog is visible to
 * all of them, and it accumulates. Measured on the development database on 2026-10-15, before this
 * class existed: every global workflow except the product's own system default was a fixture nobody
 * deleted, and the global status, priority-set and issue-type catalogs were in the same state — and
 * the reported defect was one of those populations growing until a name collided with it.
 *
 * <p>No population count is quoted here on purpose. They grow on every run, so a number goes stale
 * one entry before the list does: four were written on the first afternoon of this class and three
 * of them had drifted before the change was finished, one of them into a straight contradiction
 * with a sibling sentence. The live numbers are a query, and the only count this class states is
 * the one {@link #deleteAll()} computes into its own failure message.
 *
 * <p>Uniqueness alone does not retire the problem: {@link Unique} stops the collision, and the rows
 * still pile up where every catalog read in the suite has to walk past them. So a fixture that
 * creates such a row records it here and an {@code @AfterEach} removes it.
 *
 * <h2>Why this is one class and not a line in each test</h2>
 * Three classes needed the same delete-and-verify loop, and a loop copied three times is how this
 * repository's folds drifted. The verification is the point: {@link #deleteAll()} asserts the rows
 * are <em>gone</em>, so a delete that silently does nothing (a repository whose
 * {@code deleteById} is a no-op for a missing id, a row held by a foreign key) reds the test that
 * created it rather than being discovered a month later in a row count.
 *
 * <p>The <em>wiring</em> has the same property one level up — a class that holds one of these and
 * forgets the {@code @AfterEach} deletes nothing, asserts nothing and passes — so that is sealed
 * too, by {@code FixtureRowsTest#everyHolderOfAFixtureRowsDrainsItInATeardown}, over every
 * construction in {@code src/test/java} rather than over the three that exist today.
 *
 * <h2>What it must not be used for</h2>
 * A row another row still points at. The reported ticket recorded this precisely: the accumulated
 * {@code WF-%} workflows cannot simply be deleted, because the <em>workspace-scoped</em> ones are
 * referenced by projects. The <em>global</em> ones — the colliding namespace, and the only kind
 * this class is for — are referenced by nothing at all (measured 2026-10-15: not one of them), and
 * that asymmetry is the whole reason a blanket delete is a different, riskier change than this one.
 * Record a row here only when the fixture that created it is the only thing that ever refers to it;
 * a referenced row needs its referrers deleted first, and a test that cannot do that says so where
 * the row is created.
 */
public final class FixtureRows {

    private record Row(String what, Supplier<Boolean> exists, Runnable delete) {
    }

    private record Watched(String what, Supplier<Long> count, long before) {
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<Watched> watched = new ArrayList<>();

    /**
     * Records a row to be deleted after the test, and returns its id so a call site can keep
     * reading as one expression.
     *
     * @param repository the repository that owns the row — {@code existsById} and {@code deleteById}
     *                   are both read from it, so the check and the removal cannot disagree
     * @param id         the row's id
     * @param what       what it is, for the failure message: {@code "global workflow WF-3f9a…"}
     */
    public <T> UUID record(CrudRepository<T, UUID> repository, UUID id, String what) {
        rows.add(new Row(what + " (" + id + ")", () -> repository.existsById(id),
                () -> repository.findById(id).ifPresent(repository::delete)));
        return id;
    }

    /**
     * <strong>Counts a table now, so that {@link #deleteAll()} can refuse a net growth.</strong>
     *
     * <p>Without this, {@link #record} is the whole mechanism and forgetting it is <em>silent</em>:
     * a teardown with nothing recorded deletes nothing, asserts nothing and passes, which is how a
     * global catalog grew by a row per run for a month under a green suite. A census taken in
     * {@code @BeforeEach} and compared in {@code @AfterEach} is the witness — it catches a creation
     * nobody recorded as well as a delete that did not take.
     *
     * <p>Only for a class that really leaves <em>nothing</em> in that table, so the class-level
     * promise this file makes is held <strong>per member</strong> and not by every holder:
     * {@code DelegatedAdminBindingTest} records its one global status and deliberately watches no
     * table, because its other tests create workspace- and project-scoped statuses on purpose and a
     * census would red on every run. Recording the global rows and not watching the table is the
     * correct answer there, and
     * {@code FixtureRowsTest#everyHolderOfAFixtureRowsDrainsItInATeardown} demands only the
     * teardown, never the census, for exactly that reason.
     *
     * <p><strong>A census is a global snapshot, so it assumes a sequential runner.</strong> This
     * suite is one ({@code pom.xml}: {@code forkCount} 1, {@code reuseForks} true, no parallel
     * execution configured and no {@code junit-platform.properties} to enable it), and under a
     * parallel one this arm becomes a fence on a number another test can move — it would fail the
     * innocent test rather than the one that left the row. Whoever enables parallel execution
     * replaces this with a scoped count or drops it.
     */
    public <T> void watch(CrudRepository<T, UUID> repository, String what) {
        watched.add(new Watched(what, repository::count, repository.count()));
    }

    /**
     * Deletes every recorded row — <strong>newest first</strong>, because a row recorded later was
     * built out of the earlier ones (a field set out of a field) and that is the order the foreign
     * keys allow — and asserts that none survived, then forgets them, so a second test in the same
     * class starts empty and a failure names the row rather than the count.
     *
     * @throws AssertionError naming every row that is still there
     */
    public void deleteAll() {
        var survivors = new ArrayList<String>();
        for (var i = rows.size() - 1; i >= 0; i--) {
            rows.get(i).delete().run();
        }
        for (var row : rows) {
            if (row.exists().get()) {
                survivors.add(row.what());
            }
        }
        rows.clear();
        var grown = new ArrayList<String>();
        for (var table : watched) {
            var after = table.count().get();
            if (after != table.before()) {
                grown.add(table.what() + ": " + table.before() + " rows before this test, " + after
                          + " after");
            }
        }
        watched.clear();

        assertThat(survivors)
                .as("""
                    A FIXTURE ROW IN A NAMESPACE THAT OUTLIVES THE RUN WAS NOT REMOVED.

                    The row(s) above were created by this test in the global admin catalog, which \
                    every other test on this instance reads, and the delete did not take. Either \
                    something still references the row - delete the referrer in this teardown \
                    first, in the order the foreign keys allow - or the repository's delete is not \
                    the one that owns it.

                    Do not drop the assertion: a delete that silently does nothing looks exactly \
                    like one that worked.""")
                .isEmpty();
        assertThat(grown)
                .as("""
                    THIS TEST LEFT ROWS BEHIND IN A TABLE IT WATCHES.

                    The count above was taken before the test and again after the teardown. A row \
                    that is still there was either created without being recorded - add \
                    FixtureRows.record(repository, id, what) at the site that creates it - or \
                    deleted somewhere the count cannot see.

                    This is the arm that makes the teardown non-vacuous: a forgotten record() \
                    deletes nothing, asserts nothing and passes.""")
                .isEmpty();
    }

    /** How many rows are waiting to be deleted — for a test asserting on this class itself. */
    public int size() {
        return rows.size();
    }
}
