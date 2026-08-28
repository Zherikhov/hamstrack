package com.hamstrack.common.validation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>ADR-0018, made real: {@code @Validated} is forbidden on any bean Spring MVC dispatches
 * to.</strong> (HD-214 AC-6.)
 *
 * <p>Until this file existed the rule was a convention written in three comments — and it had been
 * obeyed on two doors out of three since HD-3, which is how {@code SearchController} spent months
 * answering <strong>500</strong> to an over-long {@code q} while the {@code @Size(max = 100)} sat
 * right there on the parameter, looking enforced. Nothing caught it because the annotation and the
 * bound landed in the same commit and no test ever sent a 101st character. A convention with
 * nothing enforcing it is a convention that has already been broken somewhere you have not looked.
 *
 * <p><strong>The claim is scoped by category, not by name</strong>, in both directions:
 * <ul>
 *   <li>the <em>forbidden</em> category is "a class Spring MVC dispatches to" — anything carrying
 *       {@code @Controller}, {@code @RestController}, {@code @ControllerAdvice},
 *       {@code @RestControllerAdvice} or {@code @RequestMapping}. Naming the controllers that exist
 *       today would go stale on the next one, which is the failure mode this ticket is about;</li>
 *   <li>the <em>permitted</em> category is {@code @ConfigurationProperties}, where {@code @Validated}
 *       is the correct mechanism (start-up binding validation, fail fast, never clamp) and ten
 *       classes rely on it. A rule phrased as "no {@code @Validated} anywhere" would fail on ten
 *       correct classes and be relaxed within a day.</li>
 * </ul>
 *
 * <p><strong>Every assertion here is of the form "nothing offends", so the scan needs tripwires.</strong>
 * There are three, and they are different guarantees: the source walk must still find a tree
 * ({@link #MIN_SOURCES}), the reflection must still recognise web classes ({@link #MIN_WEB_CLASSES}),
 * and — the one that matters most — {@link #MIN_VALIDATED_CLASSES} proves the detector can see
 * {@code @Validated} <em>at all</em>. Without that last one, a detector broken by a renamed
 * annotation, a retention change or a classloading slip reports a clean sweep of an empty set and
 * this file certifies nothing while staying green.
 */
class WebBeanValidatedRuleTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** A tree this size or smaller means the walk is not looking at the project. */
    private static final int MIN_SOURCES = 100;

    /** 32 classes carry a web stereotype today. */
    private static final int MIN_WEB_CLASSES = 30;

    /** The ten {@code @ConfigurationProperties} classes — see {@link #theRulePermitsBindingValidation}. */
    private static final int MIN_VALIDATED_CLASSES = 10;

    /**
     * The explanation IS the failure message: anyone who hits this is doing the thing that caused
     * the ticket, and needs to know the alternative before they need to know the rule.
     */
    private static final String WHY = """
            @Validated IS FORBIDDEN ON A BEAN SPRING MVC DISPATCHES TO (ADR-0018).

            It reads as "turn on validation" and does the opposite. HandlerMethod.shouldValidateArguments()
            returns false exactly when the bean type carries @Validated, so Spring MVC hands parameter
            validation to the AOP proxy instead of doing it itself. The proxy raises
            jakarta.validation.ConstraintViolationException; MVC raises HandlerMethodValidationException.
            Only the second is rendered by GlobalExceptionHandler.handleParameterValidation as a 400 whose
            `errors` map names the refused parameter — which is what the SPA branches on since HD-171.

            WHAT TO DO INSTEAD: nothing. Delete the annotation and leave the constraint where it is.
            @Size / @Max / @Pattern on a @RequestParam, @PathVariable, @RequestHeader or @CookieValue are
            enforced by Spring MVC's own method validation WITHOUT it, and @Valid @RequestBody never went
            through the proxy in the first place. Removing @Validated therefore refuses strictly more, never
            less. The single capability it adds is return-value validation, which no handler here declares.

            THIS IS NO LONGER A CRASH, AND THAT IS NOT A REASON TO ALLOW IT. HD-214 added a
            jakarta.validation.ConstraintViolationException backstop, so the annotation now answers 400
            rather than 500 — but the backstop logs at ERROR by design, because reaching it means the
            CODEBASE is wrong rather than the caller. Leaving @Validated on a controller buys one ERROR line
            per refused request plus a refusal shape that differs from every sibling controller's.

            If a controller genuinely needs a proxy for something else (@Transactional, @Cacheable, @Async),
            that is fine — it is @Validated specifically that reroutes parameter validation.""";

    /**
     * The rule. A class carrying a web stereotype must not carry {@code @Validated}.
     */
    @Test
    void noBeanSpringMvcDispatchesToCarriesValidated() throws IOException {
        var classes = productionClasses();

        var webClasses = classes.stream().filter(WebBeanValidatedRuleTest::isWebClass).toList();
        assertThat(webClasses)
                .as("""
                        The scan found %d classes carrying a web stereotype, far fewer than this \
                        application has. A scan that has stopped seeing declarations certifies \
                        nothing while staying green — find out what changed (a moved package, a \
                        classloading failure, a renamed annotation) rather than lowering this.""",
                        webClasses.size())
                .hasSizeGreaterThanOrEqualTo(MIN_WEB_CLASSES);

        var offenders = webClasses.stream()
                .filter(WebBeanValidatedRuleTest::isValidated)
                .map(Class::getName)
                .toList();

        assertThat(offenders).as(WHY).isEmpty();
    }

    /**
     * The other half of the category claim, and the tripwire that stops the first half being
     * vacuous. {@code @Validated} on a {@code @ConfigurationProperties} class is <em>correct</em> —
     * it is what makes a bad value fail the boot rather than surface as a mystery at request time —
     * so a rule that flagged those ten would be a regression in the opposite direction, and would
     * be switched off rather than obeyed.
     *
     * <p>It doubles as proof that the detector works: these classes are the only place in the tree
     * the annotation appears, so if the sweep above passes while this finds nothing, the sweep was
     * looking at nothing.
     */
    @Test
    void theRulePermitsBindingValidation() throws IOException {
        var validated = productionClasses().stream()
                .filter(WebBeanValidatedRuleTest::isValidated)
                .toList();

        assertThat(validated)
                .as("""
                        THE DETECTOR TRIPWIRE. @Validated must still be FOUND somewhere — the ten \
                        @ConfigurationProperties classes carry it. If this is empty, the sweep in \
                        noBeanSpringMvcDispatchesToCarriesValidated() is passing over a set it \
                        cannot see into, and the guarantee this file advertises does not exist. Do \
                        not lower this to match a broken scan.""")
                .hasSizeGreaterThanOrEqualTo(MIN_VALIDATED_CLASSES);

        var notConfiguration = new ArrayList<String>();
        for (var type : validated) {
            if (!AnnotatedElementUtils.hasAnnotation(type, ConfigurationProperties.class)) {
                notConfiguration.add(type.getName());
            }
        }

        assertThat(notConfiguration)
                .as("""
                        @Validated appears on a class that is neither @ConfigurationProperties nor \
                        (per the sibling test) a web bean. That is a third category nobody has \
                        decided about yet, so decide: on a @ConfigurationProperties class the \
                        annotation is start-up binding validation and is correct; on anything Spring \
                        proxies at request time it changes which exception a constraint raises, and \
                        GlobalExceptionHandler.handleBeanValidation will log an ERROR every time one \
                        fires. Either add the class to the permitted category here with the reason, \
                        or drop the annotation.""")
                .isEmpty();
    }

    // ------------------------------------------------------------------ detection

    /**
     * Meta-annotation aware on purpose: {@code @RestController} is itself annotated
     * {@code @Controller}, {@code @RestControllerAdvice} is annotated {@code @ControllerAdvice},
     * and a future Hamstrack-local stereotype composed from either must be caught by the same
     * sweep rather than slip through as an unrecognised annotation.
     */
    private static boolean isWebClass(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, Controller.class)
               || AnnotatedElementUtils.hasAnnotation(type, RestController.class)
               || AnnotatedElementUtils.hasAnnotation(type, ControllerAdvice.class)
               || AnnotatedElementUtils.hasAnnotation(type, RestControllerAdvice.class)
               || AnnotatedElementUtils.hasAnnotation(type, RequestMapping.class);
    }

    private static boolean isValidated(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, Validated.class);
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Every production class, nested types included — a {@code @RestController} declared as a
     * static nested class is dispatched to exactly like a top-level one, and a scan that only
     * looked at file names would miss it.
     *
     * <p>Loaded with {@code initialize = false}: the sweep reads annotations, which needs no static
     * initialiser to have run, and running several hundred of them outside a container is a way to
     * turn a clean rule into a flaky one.
     */
    private static List<Class<?>> productionClasses() throws IOException {
        var files = javaSources();
        assertThat(files)
                .as("scanned %s — if this is empty or tiny, the working directory is not the "
                    + "project root and every claim in this file is vacuous",
                        MAIN_SOURCES.toAbsolutePath())
                .hasSizeGreaterThan(MIN_SOURCES);

        var loader = WebBeanValidatedRuleTest.class.getClassLoader();
        var found = new LinkedHashSet<Class<?>>();
        for (var file : files) {
            var className = classNameOf(file);
            Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                throw new AssertionError("scanned " + file + " but could not load " + className
                                         + " — the source tree and the classpath disagree, so this "
                                         + "sweep is not seeing everything it claims to", e);
            }
            collect(type, found);
        }
        return List.copyOf(found);
    }

    private static void collect(Class<?> type, Set<Class<?>> into) {
        if (!into.add(type)) {
            return;
        }
        for (var nested : type.getDeclaredClasses()) {
            collect(nested, into);
        }
    }

    private static String classNameOf(Path file) {
        var relative = MAIN_SOURCES.relativize(file).toString();
        return relative.replace(".java", "").replace('\\', '.').replace('/', '.');
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                    .toList();
        }
    }
}
