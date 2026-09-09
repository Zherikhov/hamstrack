package com.hamstrack.common.architecture;

import com.hamstrack.common.testsupport.Doors;
import com.hamstrack.common.testsupport.ProductionBytecode;
import com.hamstrack.ops.PublishedCredentials;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.GeneratedValue;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.members;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-297 (epic HD-294, wave 1) &mdash; the CLAUDE.md gotchas that have a mechanical form,
 * executed.</strong> Each rule's {@code because(...)} is the gotcha, so a failure prints the
 * lesson next to the member that forgot it; each rule has exactly one home; the tree was clean
 * on the day this landed because the eight sites it found were fixed, not allow-listed.
 *
 * <h2>The seven rules and their homes</h2>
 * <ul>
 *   <li>R1 no primitive component in a request record &mdash; {@code RequestRecordBoxedFieldsTest}
 *       (its population, {@code Doors.requestRecords()}, is wider than a name filter).</li>
 *   <li>R2 no zero-argument {@code toLowerCase()}/{@code toUpperCase()} &mdash;
 *       {@link #noDefaultLocaleCaseFold()}. Replaces the HD-120 regex scan, which read text and
 *       so could not see a {@code String::toLowerCase} method reference; bytecode can.</li>
 *   <li>R3 no {@code @Validated} on a bean Spring MVC dispatches to (ADR-0018) &mdash;
 *       {@link #noWebBeanCarriesValidated()}, over the one definition of "web bean",
 *       {@link Doors#webBeans()}; and its other half, {@code @Validated} belongs on
 *       {@code @ConfigurationProperties} classes only &mdash;
 *       {@link #validatedOnlyOnConfigurationProperties()}. Replaces the HD-214 reflection scan.</li>
 *   <li>R4 no {@code @CreationTimestamp} / {@code @UpdateTimestamp} / {@code @GeneratedValue} &mdash;
 *       {@link #noHibernateTimestampOrGeneratedValue()}, over every member (a property-access getter
 *       is a member too).</li>
 *   <li>R5 {@code @Modifying(clearAutomatically = true)} carries {@code flushAutomatically = true}
 *       &mdash; {@link #clearAutomaticallyFlushes()}.</li>
 *   <li>R6 no JDK trimmer under {@code com.hamstrack.search..} &mdash; {@link #noJdkTrimmerInSearch()},
 *       the same condition as R2 with a different name set; a copy would be a defect.</li>
 *   <li>R7 no bare {@code assert} under {@code src/test} &mdash;
 *       {@code VacuousVerificationRulesTest#noBackendTestLeansOnABareAssert}. Not moved: this class
 *       imports production bytecode only, and a second import over the test tree would split R7
 *       from the two vacuous-verification rules that read TypeScript and {@code package.json}.</li>
 * </ul>
 *
 * <h2>Why core ArchUnit and plain {@code @Test}s</h2>
 * Boot 4.1 pins JUnit 6; {@code archunit-junit5} cannot run under it, so there is no engine on the
 * classpath and every rule is a Jupiter test calling {@code rule.check(MAIN)} &mdash; the discovery
 * path the HD-265 suite-coverage recorder keys on, and {@code -Dtest=ArchitectureRulesTest#method}
 * works. The import is one static field, once per JVM ({@code forkCount} is 1).
 *
 * <h2>Floors before rules</h2>
 * "Found nothing" and "looked at nothing" print the same green line, so every rule first asserts
 * the size of the population it is about to judge: the import itself, the web beans (through
 * {@code Doors}' own floor), the {@code @Validated} classes, the members R4 reads, the
 * {@code @Modifying} methods, the classes under {@code ..search..}, and the {@code ⟶ test:} pointers. The counts on landing day are
 * on each constant; the floors sit at roughly 80&nbsp;% of them. Raise deliberately; never lower to pass.
 *
 * <h2>What this class cannot see</h2>
 * {@code toLowerCase(Locale.getDefault())} (the one-argument overload passes R2 on purpose &mdash;
 * it says out loud what the bare call hides), a {@code String.format} without a locale, a
 * locale-sensitive {@code Collator}; a trimmer called outside {@code ..search..}; anything in
 * {@code target/test-classes}. An allow-list does not exist because nothing needs one; the day one
 * does, its shape is {@code Map<"FQCN#member", "HD-nnn: reason">} applied as one
 * {@code DescribedPredicate} with a tripwire that every key resolves &mdash; never a
 * {@code FreezingArchRule}, which is an allow-list without a reason.
 */
class ArchitectureRulesTest {

    /**
     * Production bytecode only, imported once per JVM by {@link ProductionBytecode} (shared with the
     * caller-set category test in {@code RequestFieldLengthBoundTest}): {@code DoNotIncludeTests}
     * drops {@code target/test-classes} (0 of the imported classes came from there on landing day),
     * so a test-scoped probe controller or the many zero-arg folds in test code are outside every
     * rule here by construction.
     */
    private static final JavaClasses MAIN = ProductionBytecode.main();

    // ------------------------------------------------------------------ floors (landing-day counts on each)

    /** 709 classes imported on 2026-09-09 (572 source files; nested classes push the count up). */
    private static final int IMPORT_FLOOR = 560;

    /**
     * Fields, constructors and methods across the import — R4's own population, floored directly
     * rather than only through {@link #IMPORT_FLOOR}: a tree whose classes survived but whose members
     * went unread (a bytecode-level import option, a resolution failure) would pass the class floor
     * and judge nothing. 8 233 members on 2026-09-09 (709 classes).
     */
    private static final int MEMBER_FLOOR = 6_500;

    /** 34 on 2026-09-09; the same floor {@code DoorsHarnessTest} holds {@code webBeans()} at. */
    private static final int WEB_BEAN_FLOOR = 30;

    /** 18 {@code @ConfigurationProperties} classes on 2026-09-09 (was "the ten" when HD-214 wrote it). */
    private static final int VALIDATED_FLOOR = 10;

    /** 36 {@code @Modifying} methods on 2026-09-09. */
    private static final int MODIFYING_FLOOR = 30;

    /** 83 classes under {@code com.hamstrack.search..} on 2026-09-09 (47 source files). */
    private static final int SEARCH_CLASS_FLOOR = 65;

    /** 14 {@code ⟶ test:} pointers across CLAUDE.md and {@code .claude/agents/*.md} on 2026-09-09. */
    private static final int POINTER_FLOOR = 8;

    private static final Set<String> CASE_FOLDS = Set.of("toLowerCase", "toUpperCase");
    private static final Set<String> JDK_TRIMMERS = Set.of("trim", "strip", "stripLeading", "stripTrailing");
    private static final String SEARCH_PACKAGES = "com.hamstrack.search..";

    // ------------------------------------------------------------------ the gotchas, as reasons

    private static final String R2_WHY = """
            toLowerCase()/toUpperCase() fold through Locale.getDefault(), which is whatever the container
            was started with: under tr-TR 'I' folds to a dotless 'i', so identical code over identical data
            differs per deployment, silently (HD-120: shipped three times - the search term, the outbound
            mail address, two slug functions). Pass the locale: Locale.ROOT for anything compared, stored,
            keyed or derived; a named human locale for text a human reads back. A site that must follow
            the JVM default writes toLowerCase(Locale.getDefault()) out loud - the one-argument overload passes""";

    private static final String R3_WHY = """
            @Validated on a bean Spring MVC dispatches to reads as "turn on validation" and does the
            opposite (ADR-0018): HandlerMethod.shouldValidateArguments() returns false when the bean type
            carries it, so MVC skips its own method validation and the AOP proxy throws
            jakarta.validation.ConstraintViolationException instead of HandlerMethodValidationException -
            SearchController answered 500 to an over-long q from HD-3 to HD-214 that way, and the backstop
            that now answers 400 logs at ERROR on purpose. Delete the annotation: @Size/@Max/@Pattern on a
            @RequestParam/@PathVariable are enforced by MVC without it. It belongs on @ConfigurationProperties only""";

    private static final String R3B_WHY = """
            @Validated on a @ConfigurationProperties class is start-up binding validation (fail fast, never
            clamp) and is correct; on anything Spring proxies at request time it changes which exception a
            constraint raises (ADR-0018). A third category nobody has decided about is a decision, not an
            omission: either the class is a properties holder, or the annotation goes""";

    private static final String R4_WHY = """
            In Hibernate 7, @CreationTimestamp/@UpdateTimestamp set their values at flush time, not at
            persist(), so createdAt/updatedAt are null after save(). Use Spring Data JPA @CreatedDate /
            @LastModifiedDate + @EntityListeners(AuditingEntityListener.class) (@EnableJpaAuditing is on
            HamstrackApplication): they fire during @PrePersist/@PreUpdate and are available immediately.
            IDs are UUID v7 from @UuidGenerator(style = UuidGenerator.Style.TIME), never @GeneratedValue
            (IDENTITY / BIGSERIAL)""";

    private static final String R5_WHY = """
            @Modifying(clearAutomatically = true) mid-transaction silently discards pending inserts: a bulk
            UPDATE only auto-flushes entities whose tables the query touches, so unrelated pending inserts
            stay queued and clearAutomatically em.clear()s them away before they are written -
            WorkspaceService.create lost its just-saved workspace and member to UserRepository.markOnboarded
            that way. Use clearAutomatically only when the mutated entity is re-read in the same transaction,
            and always pair it with flushAutomatically = true so whatever is pending is written before the clear""";

    private static final String R6_WHY = """
            User-supplied identifiers and operands go through the one canonical helper - SearchNames.key for
            a map lookup, SearchNames.canonical for anything matched elsewhere or quoted back - never a bare
            trim()/strip(). ClassificationNames.normalize (NFC, invisible characters dropped, separator runs
            collapsed) is what the stored side was written with, so a bare trim on the read side lets a pasted
            non-breaking space or a double space miss a name that is there (HD-90, HD-121), and two doors on
            one field that trim differently disagree with each other""";

    // ------------------------------------------------------------------ the import, witnessed

    /**
     * The count witness for the run, and the first floor: every rule below calls {@link #tree()} and
     * so refuses an import that saw too little, but this is the one line a reader greps for.
     */
    @Test
    void importSeesTheProductionTree() {
        var tree = tree();
        System.out.println("[archunit] imported " + tree.size() + " production classes"
                           + "; web beans " + Doors.webBeans().size()
                           + "; @Validated " + validatedClasses(tree).size()
                           + "; @Modifying methods " + modifyingMethods(tree).size()
                           + "; classes under " + SEARCH_PACKAGES + " " + tree.that(resideInAPackage(SEARCH_PACKAGES)).size()
                           + "; test pointers " + pointers().size());
    }

    // ------------------------------------------------------------------ R2

    @Test
    void noDefaultLocaleCaseFold() {
        classes()
                .should(useNoZeroArgumentStringMethod(CASE_FOLDS, "case fold"))
                .because(R2_WHY)
                .check(tree());
    }

    // ------------------------------------------------------------------ R3

    @Test
    void noWebBeanCarriesValidated() {
        Set<String> webBeans = Doors.webBeans().floor(WEB_BEAN_FLOOR).stream()
                .map(Class::getName)
                .collect(Collectors.toSet());
        DescribedPredicate<JavaClass> areWebBeans = DescribedPredicate.describe(
                "are web beans (Doors.webBeans(), " + webBeans.size() + " classes)",
                c -> webBeans.contains(c.getName()));

        // The two views of the tree must agree, or the rule judges a subset of the population it names.
        assertThat(tree().that(areWebBeans).size())
                .as("ArchUnit's import must contain every class Doors.webBeans() names — a web bean the "
                    + "import cannot see is a web bean this rule never judges")
                .isEqualTo(webBeans.size());

        noClasses().that(areWebBeans)
                .should().beAnnotatedWith(Validated.class)
                .orShould().beMetaAnnotatedWith(Validated.class)
                .because(R3_WHY)
                .check(tree());
    }

    @Test
    void validatedOnlyOnConfigurationProperties() {
        assertThat(validatedClasses(tree()))
                .as("classes carrying @Validated — the detector tripwire: if this is under %d the sweep "
                    + "in noWebBeanCarriesValidated is passing over a set it cannot see into. Do not lower "
                    + "this to match a broken scan", VALIDATED_FLOOR)
                .hasSizeGreaterThanOrEqualTo(VALIDATED_FLOOR);

        classes().that().areAnnotatedWith(Validated.class).or().areMetaAnnotatedWith(Validated.class)
                .should().beAnnotatedWith(ConfigurationProperties.class)
                .orShould().beMetaAnnotatedWith(ConfigurationProperties.class)
                .because(R3B_WHY)
                .check(tree());
    }

    // ------------------------------------------------------------------ R4

    @Test
    void noHibernateTimestampOrGeneratedValue() {
        assertThat(tree().stream().mapToLong(c -> c.getMembers().size()).sum())
                .as("members (fields + constructors + methods) across the import — under %d the import "
                    + "kept the classes but not what is declared on them, and R4 judges members", MEMBER_FLOOR)
                .isGreaterThanOrEqualTo(MEMBER_FLOOR);

        members()
                .should().notBeAnnotatedWith(CreationTimestamp.class)
                .andShould().notBeAnnotatedWith(UpdateTimestamp.class)
                .andShould().notBeAnnotatedWith(GeneratedValue.class)
                .because(R4_WHY)
                .check(tree());
    }

    // ------------------------------------------------------------------ R5

    @Test
    void clearAutomaticallyFlushes() {
        assertThat(modifyingMethods(tree()))
                .as("@Modifying methods — under %d the import is not seeing the repositories", MODIFYING_FLOOR)
                .hasSizeGreaterThanOrEqualTo(MODIFYING_FLOOR);

        methods().that().areAnnotatedWith(Modifying.class)
                .should(flushWheneverClearing())
                .because(R5_WHY)
                .check(tree());
    }

    // ------------------------------------------------------------------ R6

    @Test
    void noJdkTrimmerInSearch() {
        assertThat(tree().that(resideInAPackage(SEARCH_PACKAGES)).size())
                .as("classes under %s — under %d the import is not seeing the search feature", SEARCH_PACKAGES, SEARCH_CLASS_FLOOR)
                .isGreaterThanOrEqualTo(SEARCH_CLASS_FLOOR);

        classes().that().resideInAPackage(SEARCH_PACKAGES)
                .should(useNoZeroArgumentStringMethod(JDK_TRIMMERS, "trimmer"))
                .because(R6_WHY)
                .check(tree());
    }

    // ------------------------------------------------------------------ the pointers

    /**
     * Every {@code ⟶ test: Class#method} pointer in CLAUDE.md and {@code .claude/agents/*.md} names a
     * test class under {@code src/test/java} and a {@code @Test} method on it, by reflection. A pointer
     * is the doc's claim that a rule is executed; a pointer that rots is that claim outliving the rule,
     * which is the exact failure the pointers were added to end.
     */
    @Test
    void everyTestPointerResolves() {
        var pointers = pointers();
        assertThat(pointers)
                .as("⟶ test: pointers found in CLAUDE.md and .claude/agents/*.md — under %d the regex "
                    + "stopped matching the format, and a rule about pointers that reads none is green for free",
                        POINTER_FLOOR)
                .hasSizeGreaterThanOrEqualTo(POINTER_FLOOR);

        var testClasses = testClassesBySimpleName();
        var offenders = new ArrayList<String>();
        for (var pointer : pointers) {
            String fqcn = testClasses.get(pointer.className());
            if (fqcn == null) {
                offenders.add(pointer + ": no " + pointer.className() + ".java under src/test/java");
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(fqcn, false, ArchitectureRulesTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                offenders.add(pointer + ": " + fqcn + " has a source but no compiled class");
                continue;
            }
            boolean hasTest = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.getName().equals(pointer.method()))
                    .anyMatch(ArchitectureRulesTest::isTest);
            if (!hasTest) {
                offenders.add(pointer + ": " + fqcn + " declares no @Test method named " + pointer.method());
            }
        }

        assertThat(offenders)
                .as("""
                        A ⟶ test: POINTER DOES NOT RESOLVE.

                        %s

                        A pointer is the doc's claim that a rule is executed by the build. Rename the pointer
                        to the test that holds the rule now, or restore the test — never delete the pointer
                        to make this green, because the line it sits on then goes back to being held by prose.""",
                        String.join("\n", offenders))
                .isEmpty();
    }

    // ------------------------------------------------------------------ conditions

    /**
     * The one condition behind R2 and R6: every call <em>and every method reference</em> from the
     * class to a zero-argument {@code java.lang.String} method in {@code names}. Method references are
     * why this moved off a text scan — {@code .map(String::toLowerCase)} contains no {@code ()}.
     */
    private static ArchCondition<JavaClass> useNoZeroArgumentStringMethod(Set<String> names, String noun) {
        return new ArchCondition<>("use no zero-argument String " + noun + " (" + String.join("/", names) + ")") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                stringUses(clazz)
                        .filter(access -> names.contains(access.getName()))
                        .filter(access -> access.getTarget().getRawParameterTypes().isEmpty())
                        .forEach(access -> events.add(SimpleConditionEvent.violated(access, access.getDescription())));
            }
        };
    }

    private static Stream<JavaCodeUnitAccess<?>> stringUses(JavaClass clazz) {
        return Stream.<JavaCodeUnitAccess<?>>concat(
                        clazz.getMethodCallsFromSelf().stream(),
                        clazz.getMethodReferencesFromSelf().stream())
                .filter(access -> access.getTargetOwner().isEquivalentTo(String.class));
    }

    /**
     * Reads the annotation through ArchUnit's proxy, which applies defaults: a plain {@code @Modifying}
     * answers {@code clearAutomatically() == false} (measured on 1.5.0 — the raw
     * {@code JavaAnnotation.getProperties()} carries both defaults as well).
     */
    private static ArchCondition<JavaMethod> flushWheneverClearing() {
        return new ArchCondition<>("carry flushAutomatically = true whenever clearAutomatically = true") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Modifying modifying = method.getAnnotationOfType(Modifying.class);
                if (modifying.clearAutomatically() && !modifying.flushAutomatically()) {
                    events.add(SimpleConditionEvent.violated(method, method.getDescription()
                            + " is @Modifying(clearAutomatically = true) without flushAutomatically = true in ("
                            + method.getSourceCodeLocation().getSourceFileName() + ")"));
                }
            }
        };
    }

    // ------------------------------------------------------------------ populations

    private static JavaClasses tree() {
        assertThat(MAIN.size())
                .as("classes imported from com.hamstrack (production bytecode) — under %d the importer is "
                    + "not looking at target/classes and every rule in this class is vacuous", IMPORT_FLOOR)
                .isGreaterThanOrEqualTo(IMPORT_FLOOR);
        return MAIN;
    }

    private static List<JavaClass> validatedClasses(JavaClasses tree) {
        return tree.stream()
                .filter(c -> c.isAnnotatedWith(Validated.class) || c.isMetaAnnotatedWith(Validated.class))
                .toList();
    }

    private static List<JavaMethod> modifyingMethods(JavaClasses tree) {
        return tree.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Modifying.class))
                .toList();
    }

    // ------------------------------------------------------------------ pointers

    private static final Path ROOT = PublishedCredentials.REPO_ROOT;
    private static final Path TEST_SOURCES = ROOT.resolve("src/test/java");

    /** {@code ⟶ test: A#b} or a comma list {@code ⟶ test: A#b, C#d}; the arrow is U+27F6. */
    private static final Pattern POINTER_LINE = Pattern.compile("⟶ test: ((?:\\w+#\\w+)(?:,\\s*\\w+#\\w+)*)");
    private static final Pattern POINTER = Pattern.compile("(\\w+)#(\\w+)");

    private record Pointer(String file, int line, String className, String method) {
        @Override
        public String toString() {
            return file + ":" + line + " ⟶ test: " + className + "#" + method;
        }
    }

    private static List<Pointer> pointers() {
        var files = new ArrayList<Path>();
        files.add(ROOT.resolve("CLAUDE.md"));
        try (Stream<Path> agents = Files.list(ROOT.resolve(".claude/agents"))) {
            agents.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var found = new ArrayList<Pointer>();
        for (Path file : files) {
            var lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher line = POINTER_LINE.matcher(lines.get(i));
                while (line.find()) {
                    Matcher each = POINTER.matcher(line.group(1));
                    while (each.find()) {
                        found.add(new Pointer(PublishedCredentials.repositoryPath(file), i + 1, each.group(1), each.group(2)));
                    }
                }
            }
        }
        return found;
    }

    private static Map<String, String> testClassesBySimpleName() {
        try (Stream<Path> walk = Files.walk(TEST_SOURCES)) {
            var byName = new LinkedHashMap<String, String>();
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String relative = TEST_SOURCES.relativize(p).toString().replace('\\', '/');
                String fqcn = relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
                byName.put(fqcn.substring(fqcn.lastIndexOf('.') + 1), fqcn);
            });
            return byName;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isTest(Method method) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(a -> a.annotationType().getName().startsWith("org.junit.jupiter.api.")
                               && a.annotationType().getSimpleName().endsWith("Test"));
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
