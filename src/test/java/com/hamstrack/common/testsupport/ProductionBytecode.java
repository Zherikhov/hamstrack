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

    /**
     * <strong>Every production call SITE of {@code owner}'s named methods</strong> —
     * {@code "com.hamstrack.x.YService#methodName"}, in site-name order — followed transitively
     * through {@code throughHelpers} exactly as {@link #callersOf} does.
     *
     * <p><strong>Why the root is narrowed by METHOD NAME:</strong> a rule about case folding has to
     * start from {@code String.toLowerCase}/{@code toUpperCase}, and {@code callersOf(helpers,
     * String.class)} matches every class that calls <em>any</em> method on {@code String} — the whole
     * tree, floor and all, so the rule would be about nothing. The same applies to any root that is a
     * JDK workhorse rather than a purpose-built helper ({@code Objects}, {@code Map},
     * {@code Optional}). Matched on the NAME and not on a signature, deliberately: overloads of a fold
     * ({@code toLowerCase()} and {@code toLowerCase(Locale)}) are the same act, and an overload added
     * by a future JDK must not silently leave the population.
     *
     * <p><strong>Why a site and not a class, i.e. the defect that produced this method</strong>
     * (HD-306 fix loop): {@code AuthService} folds and stores an address in {@code register}, in
     * {@code resendVerification} and in {@code forgotPassword}. A class-keyed population let ONE row
     * about {@code register} satisfy the whole class, so the two unauthenticated doors were members of
     * the category, present in its population, and covered by nothing — and the seal was green. The
     * unit of a rule about a transform is the place the transform happens; a class is the unit only
     * when the class has one such place, which is a fact nobody re-checks.
     *
     * <p>The enclosing method comes from ArchUnit's {@code getOrigin()}, so a constructor appears as
     * {@code #<init>} and a static initialiser as {@code #<clinit>} — both are real sites and neither
     * is filtered, because a fold in a constructor stores just as well as one in a method.
     *
     * <p>Helper transitivity is unchanged and remains a CLASS-level decision: a class named in
     * {@code throughHelpers} becomes an owner in turn (all of its methods), because a helper's whole
     * job is to be called. Its own sites are members like anybody else's — a consumer that wants them
     * out declares them out, at site granularity, and {@code Population.excluding} then holds it to
     * naming sites that are live.
     */
    public static List<String> callSitesOfMethods(Set<String> throughHelpers, Class<?> owner,
                                                  Set<String> methodNames) {
        Set<String> methodTargets = new LinkedHashSet<>();
        for (String methodName : methodNames) {
            methodTargets.add(owner.getName() + "#" + methodName);
        }
        Set<String> ownerTargets = new LinkedHashSet<>();
        Set<String> sites = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (JavaClass clazz : MAIN) {
                var found = sitesIn(clazz, methodTargets, ownerTargets);
                if (found.isEmpty()) {
                    continue;
                }
                sites.addAll(found);
                if (throughHelpers.contains(clazz.getName()) && ownerTargets.add(clazz.getName())) {
                    grew = true;
                }
            }
        }
        return sites.stream().sorted().toList();
    }

    /** The enclosing methods of {@code clazz}'s calls to {@code methodTargets} or into {@code owners}. */
    private static Set<String> sitesIn(JavaClass clazz, Set<String> methodTargets, Set<String> owners) {
        Set<String> sites = new LinkedHashSet<>();
        clazz.getMethodCallsFromSelf().stream()
                .filter(call -> methodTargets.contains(
                                        call.getTargetOwner().getName() + "#" + call.getName())
                                || owners.contains(call.getTargetOwner().getName()))
                .forEach(call -> sites.add(site(call.getOrigin().getOwner().getName(),
                        call.getOrigin().getName())));
        clazz.getMethodReferencesFromSelf().stream()
                .filter(ref -> methodTargets.contains(
                                       ref.getTargetOwner().getName() + "#" + ref.getName())
                               || owners.contains(ref.getTargetOwner().getName()))
                .forEach(ref -> sites.add(site(ref.getOrigin().getOwner().getName(),
                        ref.getOrigin().getName())));
        return sites;
    }

    /**
     * The one spelling of a site, so a consumer's declared exclusions and this walk's members cannot
     * be formatted differently and silently fail to match.
     */
    public static String site(String className, String methodName) {
        return className + "#" + methodName;
    }

    private static boolean calls(JavaClass clazz, Set<String> targets) {
        return clazz.getMethodCallsFromSelf().stream().anyMatch(call -> targets.contains(call.getTargetOwner().getName()))
               || clazz.getMethodReferencesFromSelf().stream().anyMatch(ref -> targets.contains(ref.getTargetOwner().getName()));
    }
}
