package com.hamstrack.report;

import com.hamstrack.issue.repository.SprintScopeEventRepository;
import com.hamstrack.report.repository.FlowReportRepository;
import com.hamstrack.report.repository.VelocityReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-28 R1 round 2, item 6 — <strong>the tenant predicate is part of the copy-paste unit,
 * and this test is what keeps it there</strong>.
 *
 * <p>Reports are the quietest possible place to lose a scope. Every other surface returns
 * rows — a leaked issue arrives with a title, a key and an id, and somebody notices. A report
 * returns <em>numbers</em>: a flow chart computed over two tenants looks exactly like a flow
 * chart, renders without error, and is simply wrong. There is no symptom to notice.
 *
 * <p>R1 shipped three statements; the later slices copy them. So the risk this pins is not
 * today's code (which is scoped) but tomorrow's: a new report that splices the shared filter
 * fragment into a statement that forgot {@code project_id}. Three mechanisms, all asserted here:
 * <ol>
 *   <li>the shared fragment <strong>contains the scope</strong>, so pasting it cannot yield an
 *       unscoped statement in the first place;</li>
 *   <li>every native query in the report package scopes <strong>every {@code issues} alias it
 *       introduces</strong> — the backstop for a statement written without the fragment;</li>
 *   <li>every native query scopes <strong>the table it drives from</strong>, whatever that table
 *       is — the backstop for a statement that does not drive from {@code issues} at all, which
 *       R4 is the first in this epic to write.</li>
 * </ol>
 *
 * <p>Reflection over the package rather than a list of known classes, deliberately: a test
 * that has to be extended when a report is added is a test the sixth report silently escapes.
 * This suite was <em>not</em> touched when R3 added its statements and it covered them anyway,
 * which is the property working as designed.
 *
 * <p><strong>Reach (HD-149).</strong> "The report package" above is the default, not the
 * boundary: {@link #ADOPTED_TYPES} names the report-shaped types that live elsewhere, and today
 * that is the sprint scope ledger's repository, which sits with its writer in
 * {@code issue.repository}. The rule those types are held to is exactly the rule below — see
 * that constant for what travels and what deliberately does not.
 *
 * <h2>Round 2 (HD-138 R3): the budget was satisfiable by the wrong thing</h2>
 * The original backstop counted {@code project_id = :projectId} <em>anywhere</em> in the SQL and
 * compared that count against the number of {@code issues} references. The predicate was tied to
 * no alias and required to be in no particular position, so three different unscoped statements
 * passed it. All three are now rejected, and all three are pinned below by
 * {@link #theGuardRejectsEveryWayAStatementCanLoseItsScope()} — because a guard whose entire
 * value is that it fails is decoration until somebody has watched it fail:
 * <ul>
 *   <li><strong>a predicate on another table paying the budget.</strong>
 *       {@code JOIN sprints sp ON sp.project_id = :projectId} beside an unscoped
 *       {@code JOIN issues i} counted one table and one predicate and passed — with the issues
 *       join reading the whole install. This is the form R4 (sprint burn-up) would have written;</li>
 *   <li><strong>a disjunctive predicate.</strong> {@code (i.project_id = :projectId OR …)} is not
 *       a scope at all — the {@code OR} branch is exactly the leak — and it counted as one;</li>
 *   <li><strong>an {@code issues} reference with no alias</strong>, whose columns are then written
 *       bare ({@code project_id = :projectId} with no qualifier). Nothing tied that predicate to
 *       that table, so in a multi-table statement it could be satisfied elsewhere.</li>
 * </ul>
 * The fourth listed in review — an {@code issues} reference reached through a CTE — is covered by
 * the same change without special handling: a CTE body still has to say {@code FROM issues x}
 * somewhere, so the alias {@code x} is captured and must be scoped inside that body. What a CTE
 * can no longer do is launder an unaliased or unscoped reference past the counter.
 *
 * <h2>Round 3 (HD-29 R4): the rule only looked at {@code issues}</h2>
 * Both rules above are about {@code issues} aliases, which covered every statement in the epic
 * because every one of them drove from {@code issues}. R4's ledger read is the first that does
 * not — it drives from {@code sprint_scope_events} and joins {@code issues} on the way — and in
 * that shape the join carries the scope while the driver may carry nothing, which the alias rule
 * cannot see. {@link #unscopedDrivingTable} is the third mechanism, added a slice before the
 * statement that would have escaped it (R5's velocity sweep), and its hostile fixtures are the
 * last block of {@link #theGuardRejectsEveryWayAStatementCanLoseItsScope()}.
 *
 * <p><strong>The bet paid (HD-139).</strong> R5's sweep landed in the shape hostile fixture #1
 * describes — {@code FROM sprint_scope_events e} with the {@code issues} scope in the {@code ON}
 * clause — and is discharged by the restated {@code e.workspace_id = :workspaceId} the alias rule
 * would never have asked for. Both spellings of the sweep are now controls at the end of that
 * method: the one this rule anticipated, and the one that shipped.
 *
 * <h2>Round 4 (HD-139 R5): the driving-table rule had the flaw it was created to prevent</h2>
 * Three corrections, and the first is the rule failing on its own lesson:
 * <ul>
 *   <li><strong>Position was not checked.</strong> {@link #tenantPredicate} searched the whole
 *       statement, so the driver's tenant predicate discharged the rule <em>from inside a
 *       {@code JOIN … ON}</em> — the one place it does not filter the driving side. Fixture #7 is
 *       fixture #1 with {@code e.workspace_id = :workspaceId} moved three words to the left; it
 *       passed. R5 makes {@code LEFT JOIN … ON … AND <scope>} the house style for this table, so
 *       that is where the next author will put a predicate "with the others".</li>
 *   <li><strong>Only the outermost block was checked.</strong> A scalar subquery drives from a
 *       table of its own — velocity's {@code metaCounts} drives one from
 *       {@code sprint_scope_events} — and deleting its predicates raised no complaint.
 *       {@link #nestedBlockComplaint} now asks every nested block the same question, in the
 *       weaker form a probe can actually answer.</li>
 *   <li><strong>The controls were hand-copies.</strong> They had already drifted from the shipped
 *       statement they claimed to be, which makes a green control a statement about a string in
 *       this file. They are read off {@code VelocityReportRepository} now.</li>
 * </ul>
 *
 * <p>A plain unit test — no Spring context. The annotation values are compile-time constants
 * (that is why {@code @Query} can splice them at all), so they can be read without a database.
 */
class ReportQueryScopeTest {

    private static final String REPORT_PACKAGE = "com.hamstrack.report";

    /**
     * Types that hold report statements but do <strong>not</strong> live in
     * {@link #REPORT_PACKAGE} — the guard follows the STATEMENT, not the package (HD-149).
     *
     * <p>Exactly one today: {@link SprintScopeEventRepository}, the sprint scope ledger. It sits
     * in {@code issue.repository} because it is first of all the ledger's <em>write</em> side —
     * {@code SprintScopeLedger} is its only writer and it belongs beside the doors it is written
     * from — and R4 is the first slice to <em>read</em> the table. Its own javadoc already
     * promises that the read side lands "in {@code com.hamstrack.report}, where
     * {@code ReportQueryScopeTest} holds every native statement to its tenant predicate". This
     * list is the other half of that promise: R4's read statements do live in
     * {@code report.repository}, and the ledger's own interface is adopted here so that a future
     * reader added to it — the obvious place somebody will reach for — is guarded on the day it
     * is written rather than on the day it is noticed.
     *
     * <p><strong>Widened BEFORE the read side was written</strong>, deliberately. A guard
     * retrofitted to shipped code can only confirm what was already written. The ledger's reader
     * is the statement in this feature most likely to lose its scope: it starts from
     * {@code sprint_scope_events}, whose tenant predicate is a {@code sprint_id} the caller has
     * already resolved rather than a {@code project_id} the statement itself states, so joining
     * {@code issues} onto it "because the sprint is already scoped" is a one-line, entirely
     * plausible leak. That is not a hypothetical shape — it is hostile fixture #1 in
     * {@link #theGuardRejectsEveryWayAStatementCanLoseItsScope()}, written a slice before the
     * statement it describes existed.
     *
     * <p>The reach is extended; the <em>rule</em> is not. A native statement here must scope
     * every {@code issues} alias it binds, exactly as one in the report package must. One thing
     * deliberately does not travel: a shared fragment named {@code *SCOPE*} is required to carry
     * {@code project_id}, and this table has no such column, so a scope fragment for
     * {@code sprint_scope_events} itself would fail
     * {@link #everySharedSqlFragmentCarriesTheProjectScope()} rather than pass it vacuously.
     * That is the correct failure — it is a conversation about what this table's tenant predicate
     * is, not a silent exemption — and it does not arise today, because R4's fragments live in
     * {@code report.repository}.
     */
    private static final List<Class<?>> ADOPTED_TYPES = List.of(SprintScopeEventRepository.class);

    /**
     * The adopted types that contribute <strong>no checked statement yet</strong> — a tripwire
     * placed ahead of the code it is waiting for, and required to say so out loud.
     *
     * <p>Round 2 of R4 found the reason this list has to exist. {@link #ADOPTED_TYPES} reads like
     * coverage, and it was cited as coverage — but {@link SprintScopeEventRepository} declares no
     * native {@code @Query} at all, so adopting it guarded exactly nothing; the proof offered for
     * the widening ("the join's tenant predicate was deleted and the suite went red") actually came
     * from the ordinary package scan finding {@code SprintReportRepository}. Both facts are fine.
     * Confusing them is not: a reader counting adopted types as guarded statements over-credits the
     * net by however many types sit in this list.
     *
     * <p>So every adopted type must be in exactly one state, and
     * {@link #everyAdoptedTypeIsEitherGuardingAStatementOrSaysItIsWaitingForOne()} pins both
     * directions — a type here that has since grown a statement fails until it is removed, and a
     * type adopted with nothing to check fails until it is added. The second failure is the useful
     * one: it is what makes "adopted ahead of its first statement" a decision somebody wrote down
     * rather than a claim the list silently implies.
     */
    private static final Set<Class<?>> ADOPTED_BEFORE_ITS_FIRST_STATEMENT =
            Set.of(SprintScopeEventRepository.class);

    /**
     * Every reference to the {@code issues} table, <strong>with the alias it introduces</strong>.
     * The optional {@code AS} is accepted because SQL accepts it. Not {@code issue_labels}: the
     * {@code \b} after {@code issues} keeps that out, and it is only ever reached through an
     * issue, which is where the scope is stated.
     *
     * <p>Group 1 is null when the table is referenced bare ({@code FROM issues WHERE …}), which
     * is itself a rejection — see {@link #unscopedAliases}.
     */
    private static final Pattern ISSUES_ALIAS =
            Pattern.compile("(?i)\\b(?:from|join)\\s+issues\\b(?:\\s+as)?(?:\\s+(\\w+))?");

    /**
     * A table name and the alias it binds, read immediately after a {@code FROM} — the driving
     * reference. The same shape as {@link #ISSUES_ALIAS} with the table name left open.
     */
    private static final Pattern DRIVING_REFERENCE =
            Pattern.compile("(?i)(\\w+)(?:\\s+as)?(?:\\s+(\\w+))?");

    /** The same, but wherever it appears — every table a block binds, not only its driver. */
    private static final Pattern TABLE_REFERENCE =
            Pattern.compile("(?i)\\b(?:from|join)\\s+(\\w+)(?:\\s+as)?(?:\\s+(\\w+))?");

    /** A bracket group that opens a query block of its own — a subquery or a derived table. */
    private static final Pattern NESTED_SELECT = Pattern.compile("\\(\\s*(?i:select)\\b");

    /**
     * The keywords that end a {@code JOIN … ON} clause. Everything that can follow one at the same
     * level: another join, the {@code WHERE}, or the tail of the statement.
     */
    private static final Pattern CLAUSE_KEYWORD = Pattern.compile(
            "(?i)\\b(?:join|inner|left|right|full|cross|natural|where|group|order|having|limit"
            + "|offset|union|except|intersect|window|returning)\\b");

    /** SQL keywords that cannot be an alias, so that {@code FROM issues WHERE} is not "alias WHERE". */
    private static final Set<String> NOT_AN_ALIAS =
            Set.of("where", "on", "group", "order", "limit", "having", "union", "join", "inner",
                    "left", "right", "full", "cross", "using", "and", "or", "as", "select");

    /**
     * The shared SQL fragments — the thing a future report will paste. {@code SCOPE} in the
     * name is the contract: a fragment named this way promises to carry the tenant predicate,
     * and this test is where that promise is enforced.
     *
     * <p>Before round 2 of R1 the fragment was called {@code FILTERS} and contained only narrowing
     * predicates, so nothing stopped it being spliced into a statement with no {@code project_id}
     * at all. The rename is the fix; this assertion is what keeps the rename honest.
     */
    @Test
    void everySharedSqlFragmentCarriesTheProjectScope() throws Exception {
        var fragments = new ArrayList<String>();
        for (var type : reportTypes()) {
            for (var field : type.getDeclaredFields()) {
                if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())
                    || !field.getName().contains("SCOPE")) {
                    continue;
                }
                field.setAccessible(true);
                var sql = (String) field.get(null);
                assertThat(complaints("<fragment>", sql))
                        .as("%s.%s is a shared SQL fragment whose name promises a tenant scope; "
                            + "a report that pastes it must not be able to end up unscoped.%n%s",
                                type.getSimpleName(), field.getName(), sql)
                        .isEmpty();
                assertThat(sql)
                        .as("%s.%s states no tenant predicate at all", type.getSimpleName(),
                                field.getName())
                        .containsPattern("(?i)\\w+\\.project_id\\s*=\\s*:projectId");
                fragments.add(field.getName());
            }
        }
        assertThat(fragments)
                .as("no scope-carrying SQL fragment found in %s — either the constants were "
                    + "renamed away from *SCOPE* (in which case rename this test with them) or "
                    + "the shared fragment stopped carrying the tenant predicate, which is the "
                    + "exact regression this test exists for", REPORT_PACKAGE)
                .isNotEmpty();
    }

    /**
     * The backstop, for a statement written without the shared fragment: <strong>every alias the
     * statement binds to {@code issues} must be scoped by that same alias, conjunctively</strong>.
     *
     * <p>Per alias rather than per count, and that difference is the whole of round 2. A count
     * can be paid in the wrong currency — by a predicate on {@code sprints}, or by a second
     * subquery that scopes itself twice while a third scopes itself never. An alias cannot: the
     * only thing that discharges {@code i} is {@code i.project_id = :projectId} not sitting behind
     * an {@code OR}.
     */
    @Test
    void everyNativeReportQueryScopesEveryIssuesAlias() throws Exception {
        var checked = 0;
        for (var type : reportTypes()) {
            for (var method : type.getDeclaredMethods()) {
                var query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery()) {
                    continue;
                }
                var where = type.getSimpleName() + "." + method.getName();
                assertThat(complaints(where, query.value()))
                        .as("%s binds an issues alias it never scopes. A report is an aggregate: "
                            + "an unscoped reference here returns a number that is silently "
                            + "another tenant's, with nothing in the response to notice. Note "
                            + "that a project_id predicate on a DIFFERENT table does not discharge "
                            + "this, and neither does one behind an OR.%n%s", where, query.value())
                        .isEmpty();
                checked++;
            }
        }
        assertThat(checked)
                .as("no native @Query found in %s — this test found nothing to guard, which "
                    + "means it is guarding nothing", REPORT_PACKAGE)
                .isPositive();
    }

    /**
     * <strong>An adopted type is either guarding a statement or admitting it is not.</strong>
     *
     * <p>{@link #ADOPTED_TYPES} extends this suite's reach past its package, and it reads like
     * coverage. For {@link SprintScopeEventRepository} it currently is not: that interface declares
     * no native {@code @Query}, so the two rules above have nothing of its to check and the
     * adoption is a tripwire waiting for a statement rather than a statement being watched. Both
     * are legitimate; conflating them inflates what this file claims to cover, which is how a green
     * suite comes to stand for more than it tested — twice already in this epic.
     *
     * <p>So the two states are named, and each has to stay true: a type that contributes nothing
     * must appear in {@link #ADOPTED_BEFORE_ITS_FIRST_STATEMENT}, and a type that has since grown
     * one must be taken out of it — that removal is the moment the adoption stops being a promise
     * and starts being coverage, and it should be a deliberate edit rather than a silent
     * reclassification.
     */
    @Test
    void everyAdoptedTypeIsEitherGuardingAStatementOrSaysItIsWaitingForOne() {
        assertThat(ADOPTED_TYPES)
                .as("a type is registered as adopted-ahead-of-its-first-statement without being "
                    + "adopted at all")
                .containsAll(ADOPTED_BEFORE_ITS_FIRST_STATEMENT);
        for (var type : ADOPTED_TYPES) {
            long statements = nativeQueryCount(type);
            if (statements == 0) {
                assertThat(ADOPTED_BEFORE_ITS_FIRST_STATEMENT)
                        .as("%s is adopted but declares no native @Query, so it contributes NO "
                            + "checked statement — the adoption is a tripwire, not coverage, and "
                            + "it has to say so here", type.getSimpleName())
                        .contains(type);
            } else {
                assertThat(ADOPTED_BEFORE_ITS_FIRST_STATEMENT)
                        .as("%s now contributes %d native statement(s) and is guarded like any "
                            + "other — take it out of the waiting list, which is the edit that "
                            + "records the adoption paying off", type.getSimpleName(), statements)
                        .doesNotContain(type);
            }
        }
    }

    /** How many native {@code @Query} methods a type declares — i.e. how many statements it adds. */
    private static long nativeQueryCount(Class<?> type) {
        long count = 0;
        for (var method : type.getDeclaredMethods()) {
            var query = method.getAnnotation(Query.class);
            if (query != null && query.nativeQuery()) {
                count++;
            }
        }
        return count;
    }

    /**
     * <strong>The guard proving it fails.</strong>
     *
     * <p>Every statement below is a real shape that the pre-round-2 counting guard accepted, and
     * every one of them reads another tenant's issues. They live here as strings rather than as a
     * mutated repository because this way they are permanent: the next person to "simplify" the
     * matcher gets a red test naming the exact leak they re-enabled, instead of a green suite.
     *
     * <p>{@link #everyNativeReportQueryScopesEveryIssuesAlias()} asserting "no violations" is only
     * meaningful if this asserts "these are violations" — the two together are the guard. A suite
     * that has only the first half cannot distinguish a scoped codebase from a broken matcher, and
     * this epic has now shipped two green-but-empty safety nets that were caught by review rather
     * than by the suite.
     */
    @Test
    void theGuardRejectsEveryWayAStatementCanLoseItsScope() throws Exception {
        // 1. The budget paid by another table. This is the shape R4's sprint burn-up would have
        //    written, and the counting guard scored it 1 table / 1 predicate and passed it.
        assertRejected("a sibling table's project_id does not scope issues", """
                SELECT count(*)
                  FROM sprint_issues si
                  JOIN sprints sp ON sp.id = si.sprint_id AND sp.project_id = :projectId
                  JOIN issues i ON i.id = si.issue_id
                 WHERE i.closed_at IS NOT NULL
                """);

        // 2. A disjunctive predicate is not a scope: the OR branch IS the leak.
        assertRejected("a project_id behind an OR is not a scope", """
                SELECT count(*)
                  FROM issues i
                 WHERE (i.project_id = :projectId OR i.parent_id IS NULL)
                """);
        assertRejected("...including when the OR opens a parenthesis", """
                SELECT count(*)
                  FROM issues i
                 WHERE i.closed_at IS NOT NULL OR (i.project_id = :projectId)
                """);

        // 3. An unaliased reference, whose bare predicate is tied to no table at all.
        assertRejected("an unaliased issues reference cannot be checked, so it is refused", """
                SELECT count(*) FROM issues WHERE project_id = :projectId
                """);

        // 4. Several references, only some scoped — the shape the round-1 counting guard was
        //    built for. Both spellings must be rejected: a differently-named second alias...
        assertRejected("a second alias is not covered by the first one being scoped", """
                SELECT (SELECT count(*) FROM issues i
                         WHERE i.project_id = :projectId AND i.closed_at IS NULL) AS a,
                       (SELECT count(*) FROM issues i2
                         WHERE i2.closed_at IS NOT NULL) AS b
                """);
        //    ...and the far likelier one, where the subquery is copied verbatim and keeps the
        //    same alias `i`. This is why the rule counts references per alias instead of merely
        //    asking whether the alias appears in a scope somewhere: each subquery has its own
        //    WHERE, so one predicate cannot discharge two independent bindings of one name.
        assertRejected("one predicate does not discharge two independent bindings of one alias", """
                SELECT (SELECT count(*) FROM issues i
                         WHERE i.project_id = :projectId AND i.closed_at IS NULL) AS a,
                       (SELECT count(*) FROM issues i
                         WHERE i.closed_at IS NOT NULL) AS b
                """);

        // 5. A CTE cannot launder an unscoped reference: the body still says FROM issues.
        assertRejected("a CTE body is checked like any other reference", """
                WITH open_work AS (SELECT id, project_id FROM issues i WHERE i.closed_at IS NULL)
                SELECT count(*) FROM open_work o WHERE o.project_id = :projectId
                """);

        // 6. THE DRIVING TABLE (HD-29 R4 round 2). Everything above inspects `issues` aliases,
        //    which is every report up to R3 because they all drive FROM issues. R4 drives from the
        //    scope ledger and reaches issues through a LEFT join, so the scoped side is the JOINED
        //    one — and a driving side with no tenant predicate at all sails through the alias rule
        //    while returning its own columns for every tenant. This is R5's velocity sweep, one
        //    predicate short.
        assertRejected("a scoped join does not scope the table the statement drives from", """
                SELECT e.sprint_id, e.issue_key, e.story_points, i.closed_at
                  FROM sprint_scope_events e
                  LEFT JOIN issues i ON i.id = e.issue_id AND i.project_id = :projectId
                 WHERE e.occurred_at >= :since
                """);
        assertRejected("...and the same statement driving from issue_history is no better", """
                SELECT h.issue_id, h.field, h.created_at
                  FROM issue_history h
                  JOIN issues i ON i.id = h.issue_id AND i.project_id = :projectId
                 WHERE h.field = 'status'
                """);
        assertRejected("a driving table's tenant predicate behind an OR is not a scope", """
                SELECT count(*)
                  FROM sprint_scope_events e
                 WHERE (e.workspace_id = :workspaceId OR e.event = 'ADDED')
                """);
        assertRejected("a driving table with no alias cannot be checked, so it is refused", """
                SELECT count(*) FROM sprint_scope_events WHERE workspace_id = :workspaceId
                """);

        // 7. THE SAME STATEMENT WITH THE PREDICATE THREE WORDS TO THE LEFT (HD-139 R5 round 2).
        //    Fixture #1 plus a workspace predicate — but inside the ON clause, where it does not
        //    filter the driving side of a LEFT JOIN at all: the foreign rows come back with their
        //    `issues` columns nulled out and the aggregate is silently cross-tenant. This is the
        //    rule's own lesson turned on the rule, and it passed until the matcher was made
        //    positional. R5 makes `LEFT JOIN … ON … AND <scope>` the house style for this table,
        //    so the next author putting a predicate "with the others" lands exactly here.
        assertRejected("a tenant predicate inside a JOIN … ON does not scope the driving table", """
                SELECT e.sprint_id, count(*)
                  FROM sprint_scope_events e
                  LEFT JOIN issues i ON i.id = e.issue_id AND i.project_id = :projectId
                                    AND e.workspace_id = :workspaceId
                 WHERE e.occurred_at >= :since
                 GROUP BY 1
                """);

        // 8. A NESTED BLOCK'S OWN DRIVING TABLE (round 2, item 9). Everything above inspects the
        //    outermost FROM; a scalar subquery has one of its own, and velocity's metaCounts drives
        //    one from sprint_scope_events. This is that statement with its tenant predicates
        //    deleted: the outer half is impeccably scoped and the number it returns is counted
        //    across every workspace in the install.
        assertRejected("a subquery is not scoped by the statement it is written inside", """
                SELECT min(i.created_at) AS first_issue_at,
                       (SELECT count(DISTINCT e.issue_id)
                          FROM sprint_scope_events e
                         WHERE e.event = 'ADDED') AS based_on_issues
                  FROM issues i
                 WHERE i.project_id = :projectId
                """);

        // 9. "CORRELATED" MEANT MENTIONS-AN-OUTER-ALIAS (HD-139 R5 round 3, item 1). The exemption
        //    above claims a correlated block's rows are "already restricted by whatever restricts
        //    the outer row". That holds when the correlation is an equality on the outer row's
        //    IDENTITY — the label probe's `il.issue_id = i.id` — and not otherwise. Three shapes
        //    mention an outer alias while restricting nothing, and all three read the ledger of
        //    every workspace in the install.
        assertRejected("a correlation by inequality restricts WHEN, not WHOSE", """
                SELECT count(*) FROM issues i
                 WHERE i.project_id = :projectId
                   AND (SELECT count(*) FROM sprint_scope_events e
                         WHERE e.occurred_at < i.created_at) > 0
                """);
        assertRejected("an outer alias in the SELECT list correlates the projection only", """
                SELECT (SELECT count(*) || i.key FROM sprint_scope_events e
                         WHERE e.event = 'ADDED') AS x
                  FROM issues i WHERE i.project_id = :projectId
                """);
        //    And the third re-opened the disjunction hole round 2 closed, because the exemption
        //    short-circuited BEFORE the tenant check, so isConjunct was never consulted at all.
        assertRejected("a correlation behind an OR is not a correlation", """
                SELECT count(*) FROM issues i
                 WHERE i.project_id = :projectId
                   AND (SELECT count(*) FROM sprint_scope_events e
                         WHERE e.event = 'ADDED' OR e.issue_id = i.id) > 0
                """);

        // 10. THE JOIN REVERSED (round 3, item 2). A scope in a `JOIN … ON` counts for an `issues`
        //     alias because `issues` is the OPTIONAL side of the LEFT joins this package writes.
        //     Reverse the join and `issues` is the PRESERVED side: its unmatched rows come back
        //     whatever the ON says, the driving-table rule sees a properly scoped `e`, and the
        //     statement hands back every project's issue ids and titles.
        assertRejected("a RIGHT join's ON does not scope the side it preserves", """
                SELECT i.id, i.title
                  FROM sprint_scope_events e
                 RIGHT JOIN issues i ON i.id = e.issue_id AND i.project_id = :projectId
                 WHERE e.workspace_id = :workspaceId
                """);
        assertRejected("...and FULL JOIN preserves it too", """
                SELECT i.id, i.title
                  FROM sprint_scope_events e
                  FULL JOIN issues i ON i.id = e.issue_id AND i.project_id = :projectId
                 WHERE e.workspace_id = :workspaceId
                """);

        // 11. A PREDICATE FROM A DIFFERENT BLOCK (round 3, item 3). The nested-block rule scanned
        //     the block's whole text, so a predicate belonging to a block nested INSIDE it paid its
        //     bill — round 2's "budget paid in the wrong currency", at block granularity. This is
        //     metaCounts with the tenant predicate moved one level deeper: the middle block is
        //     uncorrelated and counts distinct issue ids across every tenant.
        assertRejected("a deeper block's tenant predicate does not scope the block above it", """
                SELECT min(i.created_at),
                       (SELECT count(DISTINCT e.issue_id) FROM sprint_scope_events e
                         WHERE e.occurred_at >= (SELECT min(i2.created_at) FROM issues i2
                                                  WHERE i2.project_id = :projectId)) AS n
                  FROM issues i WHERE i.project_id = :projectId
                """);

        // 12. THE DRIVER'S PREDICATE INSIDE A NESTED PROBE (round 3, item 4). `JOIN … ON` was the
        //     first place a predicate can sit without filtering the driver; a subquery is the
        //     other. Here `e.workspace_id = :workspaceId` restricts the probe, not `e` — and the
        //     enclosing OR is invisible because isConjunct stops at a subquery boundary.
        assertRejected("a tenant predicate inside a probe does not scope the driving table", """
                SELECT count(*) FROM sprint_scope_events e
                 WHERE EXISTS (SELECT 1 FROM sprints s
                                WHERE s.id = e.sprint_id AND e.workspace_id = :workspaceId)
                    OR e.event = 'ADDED'
                """);

        // And the controls: the real shapes, which must pass — otherwise the assertions above
        // prove only that the matcher rejects everything.
        assertThat(complaints("control", """
                SELECT count(*)
                  FROM issues i
                  JOIN statuses s ON s.id = i.status_id
                 WHERE i.project_id = :projectId
                   AND s.category <> 'DONE'
                """))
                .as("the matcher rejects a correctly scoped statement, so its rejections above "
                    + "prove nothing")
                .isEmpty();
        // R4's ledger read, which drives from sprint_scope_events and is scoped twice over: by the
        // sprint it is ABOUT and by the workspace it restates. Either alone discharges the rule;
        // both are there because the statement must be safe to read on its own.
        assertThat(complaints("control", """
                SELECT e.issue_id, e.issue_key, e.event, i.closed_at
                  FROM sprint_scope_events e
                  LEFT JOIN issues i ON i.id = e.issue_id AND i.project_id = :projectId
                 WHERE e.sprint_id = :sprintId
                   AND e.workspace_id = :workspaceId
                 ORDER BY e.occurred_at ASC, e.id ASC
                """))
                .as("the driving-table rule rejects the statement it was written for")
                .isEmpty();
        // A velocity sweep can drive from the ledger across many sprints — it just has to say
        // which tenant. This was written as the shape R5 was EXPECTED to write.
        assertThat(complaints("control", """
                SELECT e.sprint_id, count(*)
                  FROM sprint_scope_events e
                 WHERE e.workspace_id = :workspaceId
                   AND e.sprint_id IN (:sprintIds)
                 GROUP BY 1
                """))
                .as("a workspace-scoped sweep over several sprints is legitimate and must pass")
                .isEmpty();
        // And these are what R5 actually shipped (HD-139), read from the repository itself rather
        // than copied here. A hand-copied control drifts from the statement it claims to be —
        // round 2 found this one already differing from the shipped sweep in its select list and
        // its LIMIT — and a control that is no longer the real statement asserts nothing about it.
        // The package scan already checks both; what these two add is the SENTENCE, which a
        // failure message can then quote: this shape must pass, and here is why it is the shape.
        assertThat(complaints("control", shippedStatement("ledgerAcrossSprints")))
                .as("R5's shipped velocity sweep must pass the rule that was written for it: it "
                    + "drives from sprint_scope_events across many sprints, and it says which "
                    + "tenant in its own WHERE rather than in the join that reaches issues")
                .isEmpty();
        assertThat(complaints("control", shippedStatement("metaCounts")))
                .as("velocity's meta scalars must pass too — the outer half drives from issues and "
                    + "the inner scalar subquery drives from sprint_scope_events, each stating its "
                    + "own scope. That inner block is the one the driving-table rule could not see "
                    + "until round 2")
                .isEmpty();
        // The optional-filter probe — the one shape the correlation exemption exists for, read off
        // the fragment that defines it. Round 3 narrowed that exemption from "mentions an outer
        // alias" to "is tied to the outer row's identity by a conjunctive equality", so this is the
        // control that says the narrowing kept what it was narrowed around: `il.issue_id = i.id`
        // still exempts the probe, and issue_labels has no tenant column with which to say
        // anything else.
        assertThat(complaints("control", FlowReportRepository.PROJECT_SCOPE_AND_FILTERS))
                .as("the label probe is correlated to an already-scoped issues row by an equality "
                    + "on that row's identity, which is exactly the correlation the exemption is "
                    + "for — narrowing it must not have cost the idiom the package actually writes")
                .isEmpty();
    }

    /** A statement as shipped, straight off the repository interface — never a copy of one. */
    private static String shippedStatement(String method) throws Exception {
        var query = VelocityReportRepository.class
                .getDeclaredMethod(method, statementParameters(method))
                .getAnnotation(Query.class);
        assertThat(query)
                .as("VelocityReportRepository.%s carries no @Query — the control below is "
                    + "asserting over an empty string", method)
                .isNotNull();
        return query.value();
    }

    private static Class<?>[] statementParameters(String method) {
        for (var candidate : VelocityReportRepository.class.getDeclaredMethods()) {
            if (candidate.getName().equals(method)) {
                return candidate.getParameterTypes();
            }
        }
        throw new AssertionError("VelocityReportRepository has no method " + method
                                 + " — move this control with it");
    }

    // ------------------------------------------------------------------ the matcher

    /**
     * Both rules, run over one statement: <strong>every {@code issues} alias it binds must be
     * scoped, and the table it DRIVES from must be scoped too.</strong> Empty means clean.
     */
    private static Set<String> complaints(String where, String sql) {
        var complaints = new LinkedHashSet<String>(unscopedAliases(where, sql));
        complaints.addAll(unscopedDrivingTable(where, sql));
        return complaints;
    }

    /**
     * <strong>The driving-table rule (HD-29 R4 round 2).</strong> The statement's own
     * {@code FROM} — the first one at its top level, unwrapping a derived table — must carry a
     * conjunctive tenant predicate on the alias it binds: {@code project_id = :projectId},
     * {@code workspace_id = :workspaceId} or {@code sprint_id = :sprintId}.
     *
     * <p>The rule below it inspects {@code issues} aliases only, which was enough while every
     * report in the epic drove from {@code issues}. R4 is the first statement that does not: it
     * drives from {@code sprint_scope_events} and reaches {@code issues} through a LEFT join. That
     * inverts what the join is for. In
     * {@code FROM sprint_scope_events e LEFT JOIN issues i ON … AND i.project_id = :projectId} the
     * scoped side is the <em>joined</em> one, so a driving side with no tenant predicate at all
     * passes the alias rule cleanly — and returns the driving table's own columns for every tenant,
     * with the {@code issues} columns nulling out. R4 is safe only because {@code :sprintId} is a
     * subject the caller resolved through membership; R5's velocity sweep
     * ({@code FROM sprint_scope_events e WHERE e.sprint_id IN (…)}) and anything driving from
     * {@code issue_history} would not be, and the suite would stay green.
     *
     * <p>So the rule is widened <strong>before</strong> the statement that would slip past it,
     * which is the same order HD-149 used and the same reason: a guard retrofitted to shipped code
     * can only confirm what was already written. It is also what makes R4's restated
     * {@code e.workspace_id = :workspaceId} load-bearing rather than conventional — the composite
     * foreign key already determines it, so nothing but this test asks for it to stay.
     *
     * <p>A statement with no top-level {@code FROM} earns nothing here, and that is correct rather
     * than a gap: the flow report's whole-window scalars are a {@code SELECT} of independent scalar
     * subqueries, each of which is a query block with its own driving reference — every one of them
     * an {@code issues} reference the rule below already checks.
     */
    private static Set<String> unscopedDrivingTable(String where, String sql) {
        var complaints = new LinkedHashSet<String>();
        var checked = new LinkedHashSet<Integer>();
        complaints.addAll(drivingComplaint(where, sql, 0, sql.length(), checked));
        var block = NESTED_SELECT.matcher(sql);
        while (block.find()) {
            int start = block.start() + 1;
            if (checked.contains(start)) {
                continue;                   // a derived table — already held to the strict rule
            }
            complaints.addAll(nestedBlockComplaint(where, sql, start, closerOf(sql, start)));
        }
        return complaints;
    }

    /**
     * <strong>The same question, asked of every nested query block</strong> (HD-139 R5 round 2,
     * item 9) — because the rule above stops at the outermost {@code FROM}, and a statement can
     * drive a subquery from anything.
     *
     * <p>{@code metaCounts} is the shape that exposed the gap: its scalar subquery drives from
     * {@code sprint_scope_events}, which is exactly the non-{@code issues} driver the rule was
     * created for, and deleting either of its predicates would have raised no complaint at all.
     *
     * <p>The rule a nested block is held to is deliberately <em>weaker</em> than the top-level
     * one, and the difference is not laziness — it is that the top-level rule is unsatisfiable
     * inside a probe. A probe legitimately drives from a link table that HAS no tenant column:
     * {@code issue_labels} has {@code issue_id} and {@code label_id} and nothing else, so
     * "state the tenant on the driving table" cannot be written there in any form. What can be
     * asked instead is that the block states a tenant <em>somewhere at its own level</em> — on
     * whichever alias it binds — which for the label probe is the scoped {@code issues} row it
     * joins.
     *
     * <p>And a <strong>correlated</strong> block is exempt outright: if a conjunctive equality ties
     * a row it binds to a row of the enclosing query, its rows are already restricted by whatever
     * restricts that outer row, and that outer block is checked by these same two rules. That is
     * the package's optional-filter idiom ({@code EXISTS (SELECT 1 FROM issue_labels il WHERE
     * il.issue_id = i.id …)}), which is correct and must keep passing — while an
     * <em>uncorrelated</em> subquery with no tenant predicate is a whole-table read wearing a
     * scalar's clothes. What counts as a correlation is the whole of round 3's item 1: an
     * equality on the outer row's <em>identity</em>, not merely a mention of its alias, since
     * {@code e.occurred_at < i.created_at} restricts when rather than whose. See
     * {@link #correlated}.
     */
    private static Set<String> nestedBlockComplaint(String where, String sql, int from, int to) {
        var block = sql.substring(from, to);
        if (correlated(block) || statesATenant(block)) {
            return Set.of();
        }
        return Set.of(where + " has a nested SELECT at offset " + from + " that states no tenant"
                      + " predicate of its own and correlates to nothing outside itself, so it"
                      + " reads its table across every tenant. A subquery is not scoped by the"
                      + " statement it is written inside: only a correlation to an already-scoped"
                      + " row, or a tenant predicate of its own, restricts it.");
    }

    private static Set<String> drivingComplaint(String where, String sql, int from, int to,
                                                Set<Integer> checked) {
        checked.add(from);
        int afterFrom = topLevelFrom(sql, from, to);
        if (afterFrom < 0) {
            return Set.of();                          // no FROM of its own — nothing drives it
        }
        int start = afterFrom;
        while (start < to && Character.isWhitespace(sql.charAt(start))) {
            start++;
        }
        if (start < to && sql.charAt(start) == '(') {
            // A derived table: what matters is the reference IT drives from.
            return drivingComplaint(where, sql, start + 1, closerOf(sql, start + 1), checked);
        }
        var matcher = DRIVING_REFERENCE.matcher(sql.substring(start, to));
        if (!matcher.lookingAt()) {
            return Set.of(where + " drives from something this guard cannot read at offset "
                          + start + " — write it as `FROM <table> <alias>`");
        }
        var table = matcher.group(1);
        var alias = matcher.group(2);
        if (alias == null || NOT_AN_ALIAS.contains(alias.toLowerCase())) {
            return Set.of(where + " drives from " + table + " with no alias — nothing can tie a "
                          + "tenant predicate to it");
        }
        var span = sql.substring(from, to);
        return tenantScoped(alias, span)
                ? Set.of()
                : Set.of(where + " drives from " + table + " " + alias + ", which it never scopes."
                         + " A statement that drives from a table other than issues carries the"
                         + " tenant on THAT table: a scoped join hangs off an unscoped driver and"
                         + " returns the driver's own rows for every tenant.");
    }

    /** Whether {@code alias} carries a conjunctive workspace/project/sprint predicate in {@code sql}. */
    private static boolean tenantScoped(String alias, String sql) {
        return tenantPredicate("\\b" + Pattern.quote(alias) + "\\.", sql);
    }

    /**
     * Whether any alias <strong>this block binds</strong> carries one — the nested-block rule's
     * question, and it has to be asked of this block's own aliases at this block's own level.
     *
     * <p><strong>Round 3 (HD-139 R5), and it is round 2's bug at block granularity.</strong> The
     * old version scanned the block's whole text for {@code \w+.<tenant column>} with no depth
     * check and no binding check, though its own sentence said "on whichever alias it BINDS". So a
     * predicate belonging to a block nested <em>inside</em> this one discharged it — the budget
     * paid in the wrong currency, which is precisely what round 2 fixed for the alias rule.
     * Literally {@code metaCounts} with the tenant predicate moved one level deeper:
     * {@code (SELECT count(DISTINCT e.issue_id) FROM sprint_scope_events e WHERE e.occurred_at >=
     * (SELECT min(i2.created_at) FROM issues i2 WHERE i2.project_id = :projectId))} counts distinct
     * issue ids across every tenant and passed. An <em>outer</em> alias's predicate discharged it
     * too, which is the same error pointing the other way.
     */
    private static boolean statesATenant(String block) {
        for (var alias : boundAliases(block)) {
            if (tenantScoped(alias, block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A conjunctive tenant predicate on a qualifier, <strong>outside every {@code JOIN … ON}
     * clause of this block</strong> (HD-139 R5 round 2, item 7).
     *
     * <p>The position is the rule, and leaving it out was this matcher's own version of the bug it
     * exists to catch. Searching the whole statement text let the driver's tenant predicate
     * discharge the rule <em>from inside an {@code ON} clause</em> — and an {@code ON} predicate on
     * the driving side of a {@code LEFT JOIN} does not filter the driving side at all. That is
     * precisely the lesson {@link #unscopedDrivingTable} was created for, restated: move
     * {@code e.workspace_id = :workspaceId} three words to the left, into the {@code ON} beside
     * {@code i.project_id}, and the sweep reads every workspace's ledger while the statement still
     * looks scoped and the guard still passes. It is hostile fixture #5.
     *
     * <p><strong>And at this block's own level</strong> (round 3, item 4). {@code JOIN … ON} was
     * the first place a predicate can sit without filtering the driver; a nested block is the
     * other. {@code FROM sprint_scope_events e WHERE EXISTS (SELECT 1 FROM sprints s WHERE
     * s.id = e.sprint_id AND e.workspace_id = :workspaceId) OR e.event = 'ADDED'} discharged the
     * driver's obligation from inside a probe, where it restricts the probe and not {@code e} —
     * and the enclosing {@code OR} was invisible because {@link #isConjunct} stops at a subquery
     * boundary, which is correct for the probe's own scope and wrong when the probe is being asked
     * to pay the driver's bill. The nested-block rule is unaffected: it already passes a
     * block-local substring, in which its own predicates are at depth 0.
     *
     * <p>The restriction belongs to the <em>driving</em>-table rule (and to the nested-block rule,
     * which asks the same question of a whole block). A JOINED {@code issues} alias scoped in its
     * own {@code ON} is a different and perfectly legitimate thing — R4's shipped ledger read is
     * written that way — so {@link #conjunctiveScopes}, which the alias rule counts with,
     * deliberately does not have it.
     *
     * <p>The bind may be plain ({@code :sprintId}) or wrapped in the package's
     * {@code CAST(:sprintId AS uuid)} idiom, which is how a nullable id reaches a native statement
     * here. An {@code = ANY (CAST(:sprintIds AS uuid[]))} over a resolved set is NOT accepted: a
     * set of subject ids is not a tenant predicate, and velocity's sweep is discharged by its
     * {@code workspace_id} exactly as this rule intends.
     */
    private static boolean tenantPredicate(String qualifier, String sql) {
        var matcher = Pattern.compile(
                "(?i)" + qualifier
                + "(?:project_id\\s*=\\s*" + bind("projectId")
                + "|workspace_id\\s*=\\s*" + bind("workspaceId")
                + "|sprint_id\\s*=\\s*" + bind("sprintId") + ")").matcher(sql);
        var joinOn = joinOnClauses(sql);
        while (matcher.find()) {
            if (depthAt(sql, matcher.start()) != 0) {
                continue;
            }
            if (within(joinOn, matcher.start())) {
                continue;
            }
            if (isConjunct(sql, matcher.start(), matcher.end())) {
                return true;
            }
        }
        return false;
    }

    /** A named bind, plain or in the {@code CAST(:name AS type)} spelling this package uses. */
    private static String bind(String name) {
        return "(?:CAST\\(\\s*:" + name + "\\s+AS[^)]*\\)|:" + name + "\\b)";
    }

    /**
     * The {@code [start, end)} span of every {@code JOIN … ON} clause at this block's own bracket
     * level — from just after the {@code ON} to the next top-level clause keyword.
     */
    private static List<int[]> joinOnClauses(String sql) {
        return onClauses(sql, false);
    }

    /**
     * The same, restricted to the joins whose {@code ON} clause <strong>cannot</strong> filter
     * their joined side: {@code RIGHT} and {@code FULL}, where that side is the preserved one
     * (HD-139 R5 round 3, item 2).
     *
     * <p>{@link #conjunctiveScopes} deliberately accepts an {@code issues} scope written in a
     * {@code JOIN … ON}, and the exemption is <em>earned</em> — but by something narrower than
     * "a joined alias", which is what the rule said. It is earned by {@code issues} being the
     * <strong>optional side of an INNER/LEFT join</strong>: a foreign row then never matches and
     * its columns null out, and forcing the predicate into the {@code WHERE} would turn the
     * {@code LEFT} join into an effective {@code INNER} one and drop exactly the
     * {@code issue_id IS NULL} rows V18's {@code ON DELETE SET NULL} exists to preserve.
     *
     * <p>Reverse the join and the reasoning inverts with it. In
     * {@code FROM sprint_scope_events e RIGHT JOIN issues i ON … AND i.project_id = :projectId} the
     * preserved side is {@code issues}: unmatched issue rows come back regardless of the
     * {@code ON}, so that statement returns every project's issue ids and titles in the install
     * while the driving-table rule sees a properly scoped {@code e} and passes. {@code FULL JOIN}
     * behaves the same. Nothing in the package writes either — {@link #CLAUSE_KEYWORD} already
     * knew both words — which is exactly when a guard is cheap to correct.
     *
     * <p>Depth 0 only, like every other clause-position rule here: a {@code RIGHT} join written
     * inside a subquery is not detected. That is the same bound {@link #joinOnClauses} has always
     * had, and it is a tripwire over hand-written constants rather than a parser.
     */
    private static List<int[]> joinedSideIsPreserved(String sql) {
        return onClauses(sql, true);
    }

    private static List<int[]> onClauses(String sql, boolean onlyPreservingTheJoinedSide) {
        var clauses = new ArrayList<int[]>();
        var on = Pattern.compile("(?i)\\bon\\b").matcher(sql);
        while (on.find()) {
            if (depthAt(sql, on.start()) != 0) {
                continue;
            }
            if (onlyPreservingTheJoinedSide && !preservesTheJoinedSide(sql, on.start())) {
                continue;
            }
            int end = sql.length();
            var terminator = CLAUSE_KEYWORD.matcher(sql);
            terminator.region(on.end(), sql.length());
            while (terminator.find()) {
                if (depthAt(sql, terminator.start()) == 0) {
                    end = terminator.start();
                    break;
                }
            }
            clauses.add(new int[]{on.end(), end});
        }
        return clauses;
    }

    /**
     * Whether the join introducing the {@code ON} at {@code onStart} keeps its joined side's
     * unmatched rows — i.e. is a {@code RIGHT} or {@code FULL} join. Read off the words
     * immediately before the nearest preceding top-level {@code JOIN}, which is where SQL puts
     * them ({@code RIGHT OUTER JOIN issues i ON …}).
     */
    private static boolean preservesTheJoinedSide(String sql, int onStart) {
        var join = Pattern.compile("(?i)\\bjoin\\b").matcher(sql);
        int nearest = -1;
        while (join.find()) {
            if (join.start() >= onStart) {
                break;
            }
            if (depthAt(sql, join.start()) == 0) {
                nearest = join.start();
            }
        }
        if (nearest < 0) {
            return false;
        }
        var qualifiers = sql.substring(Math.max(0, nearest - 24), nearest);
        return Pattern.compile("(?i)\\b(?:right|full)\\b").matcher(qualifiers).find();
    }

    private static boolean within(List<int[]> spans, int index) {
        return spans.stream().anyMatch(span -> index >= span[0] && index < span[1]);
    }

    /** Bracket depth at {@code index}, counted from the start of the (block-local) string. */
    private static int depthAt(String sql, int index) {
        int depth = 0;
        for (int i = 0; i < index; i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth;
    }

    /**
     * Whether a block is <strong>correlated</strong> to the query it sits in — tied to the outer
     * row by a conjunctive equality between an alias it binds and one it does not, at its own
     * bracket level and outside its own {@code JOIN … ON} clauses.
     *
     * <p><strong>Round 3 (HD-139 R5) narrowed this from "mentions an outer alias", and the two are
     * not the same predicate.</strong> The exemption's justification is that such a block's rows
     * "are already restricted by whatever restricts the outer row" — which is true when the
     * correlation ties the inner row to the outer row's <em>identity</em>, i.e. the label probe's
     * {@code il.issue_id = i.id}. It is false for every other way an outer alias can appear, and
     * all three of these passed the old test and are hostile fixtures now:
     * <ul>
     *   <li><strong>an inequality</strong> — {@code WHERE e.occurred_at &lt; i.created_at} restricts
     *       <em>when</em>, never <em>whose</em>, and counts the ledger of every workspace in the
     *       install;</li>
     *   <li><strong>a mention in the SELECT list only</strong> — {@code SELECT count(*) || i.key
     *       FROM sprint_scope_events e WHERE …} correlates the projection and restricts nothing;</li>
     *   <li><strong>a correlation behind an {@code OR}</strong> — which also re-opened the
     *       disjunction hole round 2 closed, because this method short-circuits <em>before</em>
     *       {@link #statesATenant}, so {@link #isConjunct} was never consulted at all.</li>
     * </ul>
     *
     * <p>The {@code ON} exclusion is here for {@link #tenantPredicate}'s reason: a correlation in
     * the {@code ON} of a {@code LEFT JOIN} does not restrict the block's preserved side either.
     *
     * <p>Only this block's own level is inspected: an alias bound inside a deeper subquery is that
     * subquery's business, and that subquery is checked as its own block. An alias bound by a
     * <em>derived table</em> at this level reads as unbound, since {@link #boundAliases} matches a
     * table name after {@code FROM}/{@code JOIN} and a derived table has none — so such a block is
     * exempted somewhat too readily. That is inherited rather than introduced (the old rule
     * exempted it too, and on a weaker test), no statement in the package writes one, and the
     * shape of the fix is the same as the one above: name what the correlation has to be.
     */
    private static boolean correlated(String block) {
        var bound = boundAliases(block);
        var joinOn = joinOnClauses(block);
        var equality =
                Pattern.compile("\\b([A-Za-z_]\\w*)\\.\\w+\\s*=\\s*([A-Za-z_]\\w*)\\.\\w+")
                        .matcher(block);
        while (equality.find()) {
            if (depthAt(block, equality.start()) != 0 || within(joinOn, equality.start())) {
                continue;
            }
            boolean leftBound = bound.contains(equality.group(1).toLowerCase());
            boolean rightBound = bound.contains(equality.group(2).toLowerCase());
            if (leftBound == rightBound) {
                continue;             // both sides local, or neither — this ties nothing outwards
            }
            if (isConjunct(block, equality.start(), equality.end())) {
                return true;
            }
        }
        return false;
    }

    /** Every alias (or bare table name) this block binds at its own level. */
    private static Set<String> boundAliases(String block) {
        var aliases = new LinkedHashSet<String>();
        var matcher = TABLE_REFERENCE.matcher(block);
        while (matcher.find()) {
            if (depthAt(block, matcher.start()) != 0) {
                continue;
            }
            var alias = matcher.group(2);
            var bound = alias == null || NOT_AN_ALIAS.contains(alias.toLowerCase())
                    ? matcher.group(1)
                    : alias;
            aliases.add(bound.toLowerCase());
        }
        return aliases;
    }

    /**
     * The index just after the first {@code FROM} keyword that sits at the top level of
     * {@code [from, to)} — i.e. inside no bracket group of its own — or {@code -1}.
     *
     * <p>Skipping bracketed ones is what keeps {@code EXTRACT(EPOCH FROM …)} and every scalar
     * subquery in a {@code SELECT} list out of the answer.
     */
    private static int topLevelFrom(String sql, int from, int to) {
        int depth = 0;
        var matcher = Pattern.compile("(?i)\\bfrom\\b").matcher(sql);
        matcher.region(from, to);
        int scanned = from;
        while (matcher.find()) {
            for (int i = scanned; i < matcher.start(); i++) {
                char c = sql.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
            scanned = matcher.start();
            if (depth == 0) {
                return matcher.end();
            }
        }
        return -1;
    }

    /**
     * The complaints this statement earns — empty means it is clean.
     *
     * <p>Two rules, and the second is why this is not simply "every alias appears in a scope
     * somewhere". A statement may bind the <em>same</em> alias in several independent subqueries
     * (the flow report's opening balance binds {@code i} four times), and each of those
     * subqueries has its own {@code WHERE}. So the requirement is per alias <strong>and per
     * reference</strong>: as many conjunctive {@code alias.project_id = :projectId} predicates as
     * there are references binding that alias. That keeps the round-1 property — the dangerous
     * statement is the one with several subqueries where only some are scoped — while adding the
     * round-2 one, that the predicates must belong to <em>this</em> table and must not be
     * disjunctive.
     */
    private static Set<String> unscopedAliases(String where, String sql) {
        var complaints = new LinkedHashSet<String>();
        var references = new LinkedHashMap<String, Integer>();
        var matcher = ISSUES_ALIAS.matcher(sql);
        while (matcher.find()) {
            var alias = matcher.group(1);
            if (alias == null || NOT_AN_ALIAS.contains(alias.toLowerCase())) {
                complaints.add("an unaliased issues reference at offset " + matcher.start()
                               + " in " + where + " — nothing can tie a predicate to it");
            } else {
                references.merge(alias, 1, Integer::sum);
            }
        }
        references.forEach((alias, refs) -> {
            int scopes = conjunctiveScopes(alias, sql);
            if (scopes < refs) {
                complaints.add(alias + " (bound " + refs + "x, conjunctively scoped " + scopes
                               + "x)");
            }
        });
        return complaints;
    }

    /**
     * How many times {@code <alias>.project_id = :projectId} appears in a
     * <strong>conjunctive</strong> position — a scope that is one branch of a disjunction is not
     * a scope, because the other branch is precisely the leak.
     *
     * <p>An "immediately preceded by OR" check is what round 2 asked for and it is not enough:
     * {@code WHERE (i.project_id = :projectId OR i.parent_id IS NULL)} puts the {@code OR}
     * <em>after</em> the predicate, and the first version of this matcher passed it. (It was the
     * hostile fixture in {@link #theGuardRejectsEveryWayAStatementCanLoseItsScope()} that caught
     * that, on its first run — which is the argument for writing the fixture rather than
     * eyeballing the regex.) So the rule is positional instead: find the smallest bracket group
     * containing the predicate and require no {@code OR} at that group's own nesting level.
     *
     * <p>Unlike {@link #tenantPredicate}, a scope written inside a {@code JOIN … ON} counts here —
     * that is R4's and R5's house style for reaching {@code issues} from the ledger, and it is
     * legitimate because {@code issues} is the <em>optional</em> side of those {@code LEFT} joins.
     * {@link #joinedSideIsPreserved} is where that "unlike" stops: on a {@code RIGHT} or
     * {@code FULL} join the joined side is preserved, its unmatched rows come back whatever the
     * {@code ON} says, and a scope written there is not one.
     *
     * @see #isConjunct for the one exception, which is load-bearing rather than a compromise
     */
    private static int conjunctiveScopes(String alias, String sql) {
        var predicate = Pattern.compile(
                "(?i)\\b" + Pattern.quote(alias) + "\\.project_id\\s*=\\s*:projectId\\b");
        var matcher = predicate.matcher(sql);
        var preserved = joinedSideIsPreserved(sql);
        int found = 0;
        while (matcher.find()) {
            if (within(preserved, matcher.start())) {
                continue;
            }
            if (isConjunct(sql, matcher.start(), matcher.end())) {
                found++;
            }
        }
        return found;
    }

    /**
     * Whether the span {@code [from, to)} sits in a conjunctive position: no {@code OR} at its
     * own bracket level, and — walking outwards — none at the level of any plain bracket group
     * that encloses it either, since {@code a OR (i.project_id = :projectId)} leaks exactly as
     * much as {@code (i.project_id = :projectId OR a)}.
     *
     * <p><strong>The walk stops at a subquery boundary, and that is the interesting line.</strong>
     * The package's optional-filter idiom is
     * {@code AND (CAST(:typeId AS uuid) IS NULL OR EXISTS (SELECT 1 FROM issues i WHERE
     * i.project_id = :projectId …))} — a scoped subquery hanging off an {@code OR}. That is
     * correct and must keep passing: the {@code OR} decides whether the probe runs, and the probe
     * itself cannot see another tenant's rows because its own {@code WHERE} is scoped. A
     * disjunction outside a {@code SELECT} cannot widen what that {@code SELECT} returns. So a
     * group whose content begins with {@code SELECT} ends the walk, and a plain boolean grouping
     * does not.
     *
     * <p>Deliberately not a SQL parser — it does not know about string literals containing
     * brackets, and it does not model {@code AND} binding tighter than {@code OR}, so
     * {@code WHERE a OR b AND i.project_id = :projectId} would slip past. This is a tripwire on
     * a few hand-written constants in one package, where the convention is that any {@code OR} is
     * parenthesised, and the cost of a false positive is writing the predicate the plain way the
     * rest of the package already writes it.
     */
    private static boolean isConjunct(String sql, int from, int to) {
        int start = from;
        int end = to;
        while (true) {
            int open = openerOf(sql, start);
            int close = closerOf(sql, end);
            var clause = sql.substring(open, close);
            if (Pattern.compile("(?i)\\bor\\b").matcher(blankNestedGroups(clause)).find()) {
                return false;
            }
            if (open == 0) {
                return true;                                   // nothing encloses it
            }
            if (clause.stripLeading().regionMatches(true, 0, "SELECT", 0, 6)) {
                return true;                                   // a subquery boundary — see above
            }
            start = open - 1;                                  // the '(' itself
            end = close + 1;                                   // and its ')'
        }
    }

    /** Index just after the {@code (} opening the span's own bracket group, or 0. */
    private static int openerOf(String sql, int from) {
        int depth = 0;
        for (int i = from - 1; i >= 0; i--) {
            char c = sql.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) return i + 1;
                depth--;
            }
        }
        return 0;
    }

    /** Index of the {@code )} closing the span's own bracket group, or the end of the string. */
    private static int closerOf(String sql, int to) {
        int depth = 0;
        for (int i = to; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) return i;
                depth--;
            }
        }
        return sql.length();
    }

    /** The clause with every nested bracket group blanked out, leaving only its own level. */
    private static String blankNestedGroups(String clause) {
        var out = new StringBuilder(clause);
        int depth = 0;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c == '(') {
                depth++;
                out.setCharAt(i, ' ');
            } else if (c == ')') {
                depth--;
                out.setCharAt(i, ' ');
            } else if (depth > 0) {
                out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    private static void assertRejected(String why, String sql) {
        assertThat(complaints("hostile fixture", sql))
                .as("this statement reads another tenant's issues and the guard accepted it — %s"
                    + "%n%s", why, sql)
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Every type in the report package and below, <strong>interfaces included</strong> —
     * which is why this walks the classpath directly instead of using Spring's component
     * scanner: that one is built to find beans, and a repository interface is exactly the
     * shape it is designed to skip.
     */
    private static List<Class<?>> reportTypes() throws Exception {
        var packagePath = REPORT_PACKAGE.replace('.', '/');
        var types = new ArrayList<Class<?>>();
        var roots = Thread.currentThread().getContextClassLoader().getResources(packagePath);
        while (roots.hasMoreElements()) {
            var root = Path.of(roots.nextElement().toURI());
            try (var walk = Files.walk(root)) {
                for (var file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    var relative = root.relativize(file).toString()
                            .replace(File.separatorChar, '.')
                            .replaceAll("\\.class$", "");
                    types.add(Class.forName(REPORT_PACKAGE + "." + relative));
                }
            }
        }
        assertThat(types)
                .as("the report package scan found no types at all — this test cannot guard "
                    + "what it cannot see")
                .isNotEmpty();
        // …plus the report-shaped types that live elsewhere. Named rather than scanned: adopting
        // one is a decision somebody wrote down, and a compiler-checked class literal survives the
        // rename or move that a second package string would quietly stop matching.
        types.addAll(ADOPTED_TYPES);
        return types;
    }
}
