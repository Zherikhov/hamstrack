package com.hamstrack.search;

import com.hamstrack.report.service.InsightsService;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Executable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Who may hold {@link HqlCompiler} — asserted on the addition, not on the behaviour</strong>
 * (HD-140 R6 round 2, tenancy item 1).
 *
 * <p>Before R6, the only things this compiler handed out were finished {@code CriteriaQuery}
 * objects whose single {@code Root<Issue>} was the scoped one, so the tenant scope was an invariant
 * of the class. R6 made {@code scopedPredicate} public so the Insights aggregate could run its
 * {@code GROUP BY} over <em>the same</em> predicate as the search page rather than rebuilding one
 * beside it — which is the right trade, and it is still the right trade after this test exists. But
 * it moved one invariant from "the compiler owns the query" to "the caller applies this
 * correctly", and a fragment carries obligations a whole query does not: apply it to the sole
 * {@code Issue} root, never add a second {@code from(Issue.class)}, AND it with further predicates
 * rather than OR or NOT, and pass a workspace that came from {@code requireMember} with a context
 * built from that same workspace. {@code scopedPredicate}'s javadoc is that checklist.
 *
 * <p>The hazard is specific and it is not hypothetical arithmetic: a query with a second
 * {@code Root<Issue>} that selects from the second root is <em>scopeless</em> while containing a
 * satisfied scope predicate. Nothing about it looks wrong, and no behavioural test of today's two
 * callers can see it, because today's two callers do not do it.
 *
 * <p>So this is a <strong>tripwire on the addition</strong> — the property
 * {@code ReportQueryScopeTest} gives the native report statements, for the one Criteria path it
 * cannot read. It does not assert that a third consumer would be wrong; it asserts that a third
 * consumer is a <em>deliberate, reviewable edit</em>: adding one means editing
 * {@link #PERMITTED_CONSUMERS} in a file whose javadoc is the checklist, in a diff a reviewer sees.
 * Silence is what this test refuses.
 *
 * <p><strong>What it cannot see</strong> (round 3, item 6): {@link #references} reads declared
 * fields, parameter types and return types, so a caller that reaches the bean another way —
 * {@code applicationContext.getBean(HqlCompiler.class)}, a field typed {@code Object} or a
 * {@code Map<String, Object>} of beans — is invisible to it. That is the right scope, not an
 * oversight: those shapes appear nowhere in this codebase and guarding them would mean parsing
 * bytecode. Read this as "the ordinary way of holding a bean is guarded", not as "a third consumer
 * is impossible".
 *
 * <p>A plain unit test — no Spring context. Field and parameter types are in the class file, so
 * they can be read without a database or a container.
 */
class HqlCompilerConsumerTest {

    private static final String ROOT_PACKAGE = "com.hamstrack";

    /**
     * The complete set of types that may reference {@link HqlCompiler}.
     *
     * <p>{@link SearchService} is the search page and its result count; {@code InsightsService} is
     * the panel above it, and it holds the compiler for exactly one reason — so that the panel's
     * numbers come from the <em>same</em> predicate as the list underneath, which is the whole
     * argument for the feature.
     *
     * <p><strong>Before adding a third, read {@code HqlCompiler.scopedPredicate}'s javadoc</strong>
     * and confirm the new caller keeps every obligation in it. If the third consumer wants an
     * aggregate rather than the search's own queries, prefer the escape hatch that javadoc names
     * (a {@code buildAggregateQuery} taking the axis expressions as a callback, so the from-clause
     * is owned here again) over a third holder of the fragment.
     */
    private static final Set<Class<?>> PERMITTED_CONSUMERS =
            Set.of(SearchService.class, InsightsService.class);

    @Test
    void onlyThePermittedServicesHoldTheCompiler() throws Exception {
        var types = productionTypes();

        // Tripwires. A scan that finds nothing, or that cannot find the class it is about, would
        // pass every assertion below while guarding exactly nothing — the failure mode this whole
        // file exists to prevent, so it is checked rather than assumed.
        assertThat(types)
                .as("the classpath scan found almost no production types, so this test is "
                    + "guarding an empty set — the package layout or the build output moved")
                .hasSizeGreaterThan(100);
        assertThat(types)
                .as("the scan did not find HqlCompiler itself, so it cannot be seeing the "
                    + "classes that reference it either")
                .contains(HqlCompiler.class);

        var consumers = new LinkedHashSet<Class<?>>();
        for (var type : types) {
            if (type != HqlCompiler.class && references(type)) {
                consumers.add(type);
            }
        }

        assertThat(consumers)
                .as("no type anywhere references HqlCompiler — if it was renamed, move this "
                    + "test with it; until then this assertion is guarding nothing")
                .isNotEmpty();
        assertThat(consumers)
                .as("the set of types holding HqlCompiler changed. A new consumer of "
                    + "scopedPredicate() takes on obligations the compiler cannot verify — one "
                    + "Issue root and no second from(Issue.class), AND rather than OR/NOT, a "
                    + "workspace from requireMember and a ResolutionContext built from that same "
                    + "workspace. A query with a second Issue root is scopeless while containing "
                    + "a satisfied scope predicate, which is this project's #1 bug class wearing "
                    + "a correct-looking predicate. Read HqlCompiler.scopedPredicate's javadoc, "
                    + "confirm the new caller keeps all four obligations, then add it here.")
                .containsExactlyInAnyOrderElementsOf(PERMITTED_CONSUMERS);
    }

    /**
     * Whether {@code type} mentions {@link HqlCompiler} anywhere reflection can see it: a declared
     * field (which is how both permitted consumers hold it, via Lombok's constructor injection), or
     * any parameter or return type of a declared constructor or method — because "take it as a
     * method argument" and "hand it out from a factory" are consumers too, and a rule that only
     * looked at fields would miss both.
     */
    private static boolean references(Class<?> type) {
        try {
            for (var field : type.getDeclaredFields()) {
                if (field.getType() == HqlCompiler.class) {
                    return true;
                }
            }
            var executables = new ArrayList<Executable>();
            executables.addAll(List.of(type.getDeclaredConstructors()));
            executables.addAll(List.of(type.getDeclaredMethods()));
            for (var executable : executables) {
                for (var parameter : executable.getParameterTypes()) {
                    if (parameter == HqlCompiler.class) {
                        return true;
                    }
                }
            }
            for (var method : type.getDeclaredMethods()) {
                if (method.getReturnType() == HqlCompiler.class) {
                    return true;
                }
            }
            return false;
        } catch (LinkageError e) {
            // Never swallowed into "false": a type this test cannot read is a type it is not
            // guarding, which is the same silence the file refuses.
            throw new AssertionError("cannot inspect " + type.getName()
                                     + " — this test is not covering it", e);
        }
    }

    /**
     * Every application type under {@code com.hamstrack}, <strong>test classes excluded</strong>.
     *
     * <p>Walks the classpath directly rather than using Spring's component scanner, for
     * {@code ReportQueryScopeTest}'s reason: that scanner is built to find beans, and this rule is
     * about any type at all — a helper, a record, a nested class — not only annotated ones.
     *
     * <p>Test classes are excluded because they do not ship: a test that injects the compiler to
     * exercise it is not a production path, and holding tests to this rule would mean editing an
     * allow-list to write a test. {@code target/test-classes} is dropped by path for that reason,
     * and the tripwire in the caller is what notices if the drop ever takes everything with it.
     */
    private static List<Class<?>> productionTypes() throws Exception {
        var packagePath = ROOT_PACKAGE.replace('.', '/');
        var types = new ArrayList<Class<?>>();
        var loader = HqlCompilerConsumerTest.class.getClassLoader();
        var roots = Thread.currentThread().getContextClassLoader().getResources(packagePath);
        int productionRoots = 0;
        while (roots.hasMoreElements()) {
            var root = Path.of(roots.nextElement().toURI());
            if (root.toString().replace(File.separatorChar, '/').contains("/test-classes/")) {
                continue;
            }
            productionRoots++;
            try (var walk = Files.walk(root)) {
                for (var file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                    var relative = root.relativize(file).toString()
                            .replace(File.separatorChar, '.')
                            .replaceAll("\\.class$", "");
                    if (relative.endsWith("package-info")) {
                        continue;
                    }
                    // initialize=false: this walks every class in the application, and running
                    // static initialisers to read a field type would be a side effect for nothing.
                    types.add(Class.forName(ROOT_PACKAGE + "." + relative, false, loader));
                }
            }
        }
        assertThat(productionRoots)
                .as("no production classpath root for %s was found — everything on the classpath "
                    + "looked like test output", ROOT_PACKAGE)
                .isPositive();
        return types;
    }
}
