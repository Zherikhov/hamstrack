package com.hamstrack.common.testsupport;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <strong>The one ArchUnit import of the production tree (HD-297).</strong> {@link Doors} answers
 * "what are all the X" by reflection; a rule about what a class <em>calls</em> needs bytecode, and
 * ArchUnit's importer is the tool for that. It is one static field so the ~700-class import happens
 * once per JVM however many test classes read it ({@code forkCount} is 1), and it lives here rather
 * than inside {@code ArchitectureRulesTest} so a category test elsewhere shares the import instead
 * of copying its options — two importers configured by hand would eventually disagree on what
 * "production" means.
 *
 * <p>Production bytecode only: {@link ImportOption.DoNotIncludeTests} drops {@code target/test-classes},
 * so a test-scoped probe controller or the many zero-arg folds in test code are outside every rule
 * read from here by construction. {@link #main()} carries no floor of its own — every reader asserts
 * the size of the population it is about to judge ({@code ArchitectureRulesTest.IMPORT_FLOOR} holds
 * the whole import; a caller-set consumer floors its own subset), because "found nothing" and
 * "looked at nothing" print the same green line.
 */
public final class ProductionBytecode {

    private ProductionBytecode() {
    }

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.hamstrack");

    /** Every production class under {@code com.hamstrack}, nested classes included. */
    public static JavaClasses main() {
        return MAIN;
    }

    /**
     * Every production class with at least one method call or method reference whose target is
     * declared on one of {@code owners} — <strong>followed transitively through
     * {@code throughHelpers}</strong> — in class-name order. This is the enumeration behind a rule of
     * the form "every caller of X also does Y": a new caller is a member the day it compiles, so it
     * cannot be left out of the rule by forgetting to list it.
     *
     * <p>{@code throughHelpers} closes the one-hop hole: a consumer that excludes X's own wrapper
     * ({@code SearchNames.canonical} delegating to {@code ClassificationNames.normalize}) as "the
     * helper itself" would otherwise lose every door that reaches X only through that wrapper. So a
     * caller whose name is in {@code throughHelpers} becomes an owner in turn and <em>its</em> callers
     * are members too, to a fixpoint. The contract for a consumer: the set it excludes as helpers is
     * the set it passes here — one constant, used for both — so declaring a delegator a helper is what
     * makes the graph walk through it, never what hides what is behind it.
     *
     * <p>The blind spot that remains, stated: a class that reaches the same effect <em>without</em>
     * calling any owner (its own regex, its own {@code Normalizer} call when {@code Normalizer} is not
     * an owner) is outside every call graph, and the consumer's javadoc must say so.
     *
     * @param throughHelpers fully-qualified names of classes to follow through (a caller in this set
     *                       becomes an owner); a name that is not a caller is simply never reached
     * @param owners         the roots — production classes or JDK classes ({@code java.text.Normalizer})
     */
    public static List<JavaClass> callersOf(Set<String> throughHelpers, Class<?>... owners) {
        Set<String> targets = new LinkedHashSet<>();
        for (Class<?> owner : owners) {
            targets.add(owner.getName());
        }
        Set<JavaClass> callers = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (JavaClass clazz : MAIN) {
                if (callers.contains(clazz) || !calls(clazz, targets)) {
                    continue;
                }
                callers.add(clazz);
                if (throughHelpers.contains(clazz.getName()) && targets.add(clazz.getName())) {
                    grew = true;
                }
            }
        }
        return callers.stream().sorted(Comparator.comparing(JavaClass::getName)).toList();
    }

    private static boolean calls(JavaClass clazz, Set<String> targets) {
        return clazz.getMethodCallsFromSelf().stream().anyMatch(call -> targets.contains(call.getTargetOwner().getName()))
               || clazz.getMethodReferencesFromSelf().stream().anyMatch(ref -> targets.contains(ref.getTargetOwner().getName()));
    }
}
