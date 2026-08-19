package com.hamstrack.report;

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
 * fragment into a statement that forgot {@code project_id}. Two mechanisms, both asserted here:
 * <ol>
 *   <li>the shared fragment <strong>contains the scope</strong>, so pasting it cannot yield an
 *       unscoped statement in the first place;</li>
 *   <li>every native query in the report package scopes <strong>every {@code issues} alias it
 *       introduces</strong> — the backstop for a statement written without the fragment.</li>
 * </ol>
 *
 * <p>Reflection over the package rather than a list of known classes, deliberately: a test
 * that has to be extended when a report is added is a test the sixth report silently escapes.
 * This suite was <em>not</em> touched when R3 added its statements and it covered them anyway,
 * which is the property working as designed.
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
 * <p>A plain unit test — no Spring context. The annotation values are compile-time constants
 * (that is why {@code @Query} can splice them at all), so they can be read without a database.
 */
class ReportQueryScopeTest {

    private static final String REPORT_PACKAGE = "com.hamstrack.report";

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
                assertThat(unscopedAliases("<fragment>", sql))
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
                assertThat(unscopedAliases(where, query.value()))
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
    void theGuardRejectsEveryWayAStatementCanLoseItsScope() {
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

        // And the control: the real shape, which must pass — otherwise the assertions above
        // prove only that the matcher rejects everything.
        assertThat(unscopedAliases("control", """
                SELECT count(*)
                  FROM issues i
                  JOIN statuses s ON s.id = i.status_id
                 WHERE i.project_id = :projectId
                   AND s.category <> 'DONE'
                """))
                .as("the matcher rejects a correctly scoped statement, so its rejections above "
                    + "prove nothing")
                .isEmpty();
    }

    // ------------------------------------------------------------------ the matcher

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
     * @see #isConjunct for the one exception, which is load-bearing rather than a compromise
     */
    private static int conjunctiveScopes(String alias, String sql) {
        var predicate = Pattern.compile(
                "(?i)\\b" + Pattern.quote(alias) + "\\.project_id\\s*=\\s*:projectId\\b");
        var matcher = predicate.matcher(sql);
        int found = 0;
        while (matcher.find()) {
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
        assertThat(unscopedAliases("hostile fixture", sql))
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
        return types;
    }
}
