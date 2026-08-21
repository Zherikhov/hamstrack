package com.hamstrack.search;

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
 * <strong>Who may hold the retired-key alias table — asserted on the addition, not on the
 * behaviour</strong> (HD-114, the shape {@code HqlCompilerConsumerTest} uses for the compiler).
 *
 * <p>HQL field-name precedence is a tenancy-safety property: the alias step is only correct
 * <em>last</em>, because a workspace's own custom field keyed {@code story_points} must resolve
 * to that field and not to the native column ({@link RetiredFieldAliases}). Applied out of
 * order it fails silently — no error, no 500, just other people's saved queries quietly meaning
 * something else.
 *
 * <p>{@link FieldResolver} makes that order impossible to get wrong for anything that resolves
 * through it. The one way back to the old hazard is to hold the alias table and decide where the
 * step goes — which is exactly what the pre-HD-114 copies did, and how HD-107 §9.2 shipped an
 * order that one name-resolution path knew and another did not. So the table has a single
 * holder, and gaining a second is a deliberate, reviewable edit to {@link #PERMITTED_HOLDERS}
 * rather than a line nobody notices in a diff.
 *
 * <p>This is not a claim that a second holder would be wrong; it is a refusal of silence. It
 * also does not guard the <em>registry</em>: knowing whether a name is a live system field is an
 * ordinary question that vocabulary-facing surfaces ({@code /schema}, {@code /suggest}) ask for
 * their own reasons, and it carries none of the alias step's ordering hazard.
 *
 * <p>Reflection sees declared fields, parameter types and return types — the ordinary ways of
 * holding a bean — so a type that fetches one from the context by class, or holds it as
 * {@code Object}, is invisible here. That is the same scope {@code HqlCompilerConsumerTest}
 * takes, and for the same reason: read this as "the ordinary way is guarded".
 *
 * <p>A plain unit test — no Spring context.
 */
class FieldResolutionOwnershipTest {

    private static final String ROOT_PACKAGE = "com.hamstrack";

    /**
     * The complete set of types that may reference {@link RetiredFieldAliases}.
     *
     * <p><strong>Before adding one</strong>, read {@link FieldResolver}'s class javadoc. If the
     * new type wants to know what a name means, it should <em>ask</em> {@code FieldResolver}
     * instead of holding the alias table and placing the step itself — that is the entire
     * reason the resolver exists, and the reason this list is short.
     */
    private static final Set<Class<?>> PERMITTED_HOLDERS = Set.of(FieldResolver.class);

    @Test
    void onlyTheResolverHoldsTheRetiredKeyAliases() throws Exception {
        var types = productionTypes();

        // Tripwires: a scan that finds nothing, or that cannot see the class it is about, would
        // pass every assertion below while guarding nothing at all.
        assertThat(types)
                .as("the classpath scan found almost no production types, so this test is "
                    + "guarding an empty set — the package layout or the build output moved")
                .hasSizeGreaterThan(100);
        assertThat(types)
                .as("the scan did not find RetiredFieldAliases itself, so it cannot be seeing "
                    + "the classes that reference it either")
                .contains(RetiredFieldAliases.class);

        var holders = new LinkedHashSet<Class<?>>();
        for (var type : types) {
            if (type != RetiredFieldAliases.class && references(type)) {
                holders.add(type);
            }
        }

        assertThat(holders)
                .as("the set of types holding RetiredFieldAliases changed. The alias step is "
                    + "only correct as the LAST resolution step — registry, then the caller's "
                    + "visible custom fields, then the alias — because a tenant's own field "
                    + "keyed with a retired key must resolve to that field, and getting it "
                    + "wrong changes what saved queries mean with no error anywhere. A type "
                    + "that resolves field names should ask FieldResolver rather than hold "
                    + "this table and place the step itself; read FieldResolver's javadoc, and "
                    + "if the new holder genuinely needs the raw mapping, add it here.")
                .containsExactlyInAnyOrderElementsOf(PERMITTED_HOLDERS);
    }

    /** Whether {@code type} mentions {@link RetiredFieldAliases} anywhere reflection can see it. */
    private static boolean references(Class<?> type) {
        try {
            for (var field : type.getDeclaredFields()) {
                if (field.getType() == RetiredFieldAliases.class) {
                    return true;
                }
            }
            var executables = new ArrayList<Executable>();
            executables.addAll(List.of(type.getDeclaredConstructors()));
            executables.addAll(List.of(type.getDeclaredMethods()));
            for (var executable : executables) {
                for (var parameter : executable.getParameterTypes()) {
                    if (parameter == RetiredFieldAliases.class) {
                        return true;
                    }
                }
            }
            for (var method : type.getDeclaredMethods()) {
                if (method.getReturnType() == RetiredFieldAliases.class) {
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
     * Every application type under {@code com.hamstrack}, <strong>test classes excluded</strong>
     * — a test that wires its own resolver to exercise one is not a production path.
     */
    private static List<Class<?>> productionTypes() throws Exception {
        var packagePath = ROOT_PACKAGE.replace('.', '/');
        var types = new ArrayList<Class<?>>();
        var loader = FieldResolutionOwnershipTest.class.getClassLoader();
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
                    types.add(Class.forName(ROOT_PACKAGE + "." + relative, false, loader));
                }
            }
        }
        assertThat(productionRoots)
                .as("no production classpath root for %s was found — everything on the "
                    + "classpath looked like test output", ROOT_PACKAGE)
                .isPositive();
        return types;
    }
}
