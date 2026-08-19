package com.hamstrack.report;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * <p>R1 ships three statements; five more report slices are specified to copy them. So the
 * risk this pins is not today's code (which is scoped) but tomorrow's: a new report that
 * splices the shared filter fragment into a statement that forgot {@code project_id}. Two
 * mechanisms, both asserted here:
 * <ol>
 *   <li>the shared fragment <strong>contains the scope</strong>, so pasting it cannot yield an
 *       unscoped statement in the first place;</li>
 *   <li>every native query in the report package names {@code project_id = :projectId} at
 *       least once per {@code issues} table reference — the backstop for a statement written
 *       without the fragment at all.</li>
 * </ol>
 *
 * <p>Reflection over the package rather than a list of known classes, deliberately: a test
 * that has to be extended when a report is added is a test the sixth report silently escapes.
 *
 * <p>A plain unit test — no Spring context. The annotation values are compile-time constants
 * (that is why {@code @Query} can splice them at all), so they can be read without a database.
 */
class ReportQueryScopeTest {

    private static final String REPORT_PACKAGE = "com.hamstrack.report";

    /** The tenant predicate itself, in the one spelling every statement uses. */
    private static final Pattern PROJECT_PREDICATE =
            Pattern.compile("project_id\\s*=\\s*:projectId");

    /**
     * Every reference to the {@code issues} table — {@code FROM issues} and {@code JOIN
     * issues} alike. Not {@code issue_labels}: that table is only ever reached through an
     * issue, which is where the scope is stated.
     */
    private static final Pattern ISSUES_TABLE =
            Pattern.compile("(?i)\\b(?:from|join)\\s+issues\\b");

    /**
     * The shared SQL fragments — the thing a future report will paste. {@code SCOPE} in the
     * name is the contract: a fragment named this way promises to carry the tenant predicate,
     * and this test is where that promise is enforced.
     *
     * <p>Before round 2 the fragment was called {@code FILTERS} and contained only narrowing
     * predicates, so nothing stopped it being spliced into a statement with no
     * {@code project_id} at all. The rename is the fix; this assertion is what keeps the
     * rename honest.
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
                assertThat(sql)
                        .as("%s.%s is a shared SQL fragment whose name promises a tenant scope; "
                            + "a report that pastes it must not be able to end up unscoped",
                                type.getSimpleName(), field.getName())
                        .containsPattern(PROJECT_PREDICATE);
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
     * The backstop, for a statement written without the shared fragment: as many
     * {@code project_id = :projectId} predicates as there are {@code issues} table
     * references. It is a counting assertion rather than a "contains" one because the
     * dangerous statement is the one with <em>several</em> subqueries where only some are
     * scoped — the opening-balance query already has three, and a fourth added without a
     * predicate would read the whole install into one number.
     */
    @Test
    void everyNativeReportQueryScopesEveryIssuesReference() throws Exception {
        var checked = 0;
        for (var type : reportTypes()) {
            for (var method : type.getDeclaredMethods()) {
                var query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery()) {
                    continue;
                }
                var sql = query.value();
                int tables = count(ISSUES_TABLE, sql);
                int scopes = count(PROJECT_PREDICATE, sql);
                assertThat(scopes)
                        .as("%s.%s references the issues table %d time(s) but states "
                            + "project_id = :projectId only %d time(s). A report is an "
                            + "aggregate: an unscoped subquery here returns a number that is "
                            + "silently another tenant's, with nothing in the response to "
                            + "notice.%n%s", type.getSimpleName(), method.getName(), tables,
                                scopes, sql)
                        .isGreaterThanOrEqualTo(tables);
                checked++;
            }
        }
        assertThat(checked)
                .as("no native @Query found in %s — this test found nothing to guard, which "
                    + "means it is guarding nothing", REPORT_PACKAGE)
                .isPositive();
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

    private static int count(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
