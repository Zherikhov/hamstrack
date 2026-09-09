package com.hamstrack.common.testsupport;

import com.hamstrack.HamstrackApplication;
import jakarta.persistence.Table;
import jakarta.servlet.Filter;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.Repository;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.RequestMethodsRequestCondition;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <strong>One place answers "what are all the X" (HD-296).</strong>
 *
 * <p>81 of the 194 defects in the 2026-09 retrospective were a rule applied to one door and not to
 * its siblings, and the tests that catch that class each carried a private scan of "all the X"
 * that disagreed with its neighbours' (three definitions of <em>write handler</em>, two copies of
 * <em>mailer</em>, two copies of one crude method parser). Every method here returns a
 * {@link Population} — members in a stable order, a {@link Population#floor(int) floor} that
 * refuses a collapsed scan with one standard message, and a {@code describe()} on every member so
 * a failure list is built from the population rather than typed.
 *
 * <h2>How it enumerates (D1, D2)</h2>
 * The compiled production tree: the code source of {@link HamstrackApplication} — a directory
 * ({@code target/classes} under Surefire; a jar is <strong>refused</strong>, not half-supported) —
 * walked for {@code **&#47;*.class} and loaded with {@code Class.forName(name, false, loader)}.
 * Anonymous and synthetic classes, {@code package-info} and {@code module-info} are skipped; nested
 * classes come for free. No Spring context, no scanner library. Handlers are selected the way
 * {@code RequestMappingHandlerMapping} selects them ({@link MethodIntrospector#selectMethods} +
 * {@link AnnotatedElementUtils#findMergedAnnotation}) and their paths and verbs are composed by the
 * runtime's own {@link PathPatternsRequestCondition#combine} / {@link RequestMethodsRequestCondition#combine},
 * so the composition is shared with Spring rather than copied from it. {@code DoorsHandlerMappingParityTest}
 * proves the result equal to what the {@code DispatcherServlet} routes, in both directions.
 *
 * <h2>The contract every method keeps</h2>
 * <ul>
 *   <li>its javadoc carries a <strong>{@code Not included:}</strong> paragraph — the blind spots a
 *       consumer must know before trusting a clean pass ({@code DoorsHarnessTest} checks the marker);</li>
 *   <li>{@code DoorsHarnessTest} asserts its floor on the real tree on every run and prints one
 *       {@code [doors]} line with every {@code describe()} — the count witness;</li>
 *   <li>results are computed once per JVM: the suite runs ~290 test classes and must not pay a
 *       class walk per consumer.</li>
 * </ul>
 *
 * <p>Doors reads {@code src/main}; it never changes it. A stale {@code .class} for a deleted source
 * is a phantom door (over-reporting — the safe direction, but a false offender), which is why
 * {@code DoorsHarnessTest.compiledTreeMatchesTrackedSources} holds the compiled tree equal to
 * {@code git ls-files src/main/java}.
 */
public final class Doors {

    private Doors() {
    }

    // =================================================================== member types

    /** How a controller parameter is bound, read from the merged annotation on it. */
    public enum Binding { BODY, PART, QUERY, PATH, HEADER, COOKIE, MODEL, OTHER }

    /** Where a response can be written before or beside MVC's own rendering. */
    public enum WriterKind { ADVICE_HANDLER, FILTER, ENTRY_POINT, ACCESS_DENIED, INTERCEPTOR }

    /** The verbs a write budget exists for; a mapping with no method condition counts as all of them. */
    public static final Set<RequestMethod> WRITE_VERBS =
            Set.copyOf(EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE));

    private static final Set<RequestMethod> READ_VERBS =
            Set.copyOf(EnumSet.of(RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS));

    /** The parser the runtime's {@code PathPatternsRequestCondition} composes with; pattern strings do not depend on its options. */
    private static final PathPatternParser PARSER = new PathPatternParser();

    /**
     * One request-mapped handler method.
     *
     * @param bean    the controller class Spring instantiates
     * @param method  the handler method
     * @param verbs   the composed method condition — empty means <em>unconditioned</em> (answers every verb)
     * @param paths   the composed pattern strings, class prefix × method path, exactly as Spring composes them
     * @param restful whether the return value is the body ({@code @ResponseBody}, directly or via
     *                {@code @RestController}); {@code false} for a view-name handler such as the SPA forwards
     * @param conditional whether the bean class carries a {@code @Conditional}-meta annotation
     *                ({@code @ConditionalOnProperty}, {@code @Profile}, …) — Doors counts classes, not
     *                beans, so such a handler is a member whether or not its condition holds in a given
     *                context; {@code CspReportController} ({@code app.csp.sink-enabled}) is one today
     */
    public record Handler(Class<?> bean, Method method, Set<RequestMethod> verbs, List<String> paths,
                          boolean restful, boolean conditional) {

        /** {@code SimpleName#method} — the javadoc convention, and the one spelling for exemption keys (D7). */
        public String id() {
            return bean.getSimpleName() + "#" + method.getName();
        }

        /** Whether a write budget applies: {@code verbs ∩ WRITE_VERBS ≠ ∅}, or unconditioned. */
        public boolean writes() {
            return !writeVerbs().isEmpty();
        }

        /** Whether a read reaches it: GET / HEAD / OPTIONS, or unconditioned. */
        public boolean reads() {
            return verbs.isEmpty() || verbs.stream().anyMatch(READ_VERBS::contains);
        }

        /**
         * The mutating verbs this handler answers — all four when the mapping has no method condition
         * (the {@code WriteThrottleCoverageTest} rule, held here so every consumer applies the same one).
         */
        public Set<RequestMethod> writeVerbs() {
            if (verbs.isEmpty()) {
                return WRITE_VERBS;
            }
            return verbs.stream().filter(WRITE_VERBS::contains)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(RequestMethod.class)));
        }

        /** {@code "POST /api/workspaces/{workspaceId}/… (IssueController#create)"}; {@code ANY} when unconditioned. */
        public String describe() {
            var verbList = verbs.isEmpty() ? "ANY"
                    : verbs.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
            return verbList + " " + String.join(", ", paths) + " (" + id() + ")" + (conditional ? " [conditional]" : "");
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** One parameter of a handler method, with the binding Spring will apply to it. */
    public record Param(Handler handler, Parameter parameter, Binding binding, boolean required) {

        public int index() {
            return List.of(handler.method().getParameters()).indexOf(parameter);
        }

        public String describe() {
            return handler.id() + "[" + index() + "] " + binding + (required ? "" : "?") + " "
                   + parameter.getType().getSimpleName() + " " + parameter.getName();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * A record that is read from a request body.
     *
     * @param type       the record
     * @param mountedBy  the handlers from whose body/part/model parameter it is reachable (empty when found by name only)
     * @param via        the <em>shortest</em> type chain from any such parameter down to this record (the record
     *                   itself excluded) — empty exactly when some handler mounts it directly, whichever
     *                   handler the walk met first
     * @param byNameOnly {@code true} when only the {@code *Request} naming half found it — a DTO written before
     *                   its endpoint, or mounted somewhere the reachability half cannot see
     */
    public record RequestRecord(Class<?> type, Set<Handler> mountedBy, List<Class<?>> via, boolean byNameOnly) {

        public String describe() {
            if (byNameOnly) {
                return type.getSimpleName() + " (by name only)";
            }
            var chain = via.isEmpty() ? "" : " via " + via.stream().map(Class::getSimpleName).collect(Collectors.joining(" > "));
            return type.getSimpleName() + " (mounted by "
                   + mountedBy.stream().map(Handler::id).sorted().collect(Collectors.joining(", ")) + chain + ")";
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** A JPA entity and the table it maps. */
    public record Entity(Class<?> type, String table) {

        public String describe() {
            return table + " (" + type.getSimpleName() + ")";
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * A method offered by a Spring Data repository, attributed to the repository's <em>domain type</em>.
     *
     * @param inherited {@code true} when the method is declared on a Spring Data interface rather than on the repository itself
     */
    public record Finder(Class<?> repository, Method method, Class<?> domainType, String table, boolean inherited) {

        public String describe() {
            return repository.getSimpleName() + "#" + method.getName() + " → " + table
                   + (inherited ? " (inherited from " + method.getDeclaringClass().getSimpleName() + ")" : "");
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** A method carrying {@code @Scheduled}. */
    public record Job(Class<?> owner, Method method, String schedule) {

        public String describe() {
            return owner.getSimpleName() + "#" + method.getName() + " (" + schedule + ")";
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** A public {@code send*} method on a class that holds a {@link JavaMailSender}. */
    public record MailSite(Class<?> holder, Method mailer) {

        public String describe() {
            return holder.getSimpleName() + "#" + mailer.getName();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /**
     * A place that can write a response body outside a handler's own return value.
     *
     * @param method the {@code @ExceptionHandler} method for {@link WriterKind#ADVICE_HANDLER}; {@code null} for a door
     */
    public record ProblemWriter(WriterKind kind, Class<?> owner, Method method) {

        public String describe() {
            return kind + " " + owner.getSimpleName() + (method == null ? "" : "#" + method.getName());
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    // =================================================================== populations

    /**
     * Every compiled production class (P0): the D1 walk over the code source of
     * {@link HamstrackApplication}, nested classes included.
     *
     * <p><strong>Not included:</strong> anonymous and synthetic classes, {@code package-info} and
     * {@code module-info}; anything in {@code target/test-classes} (a test-scoped probe controller is
     * outside every population here by design); a class whose source was deleted but whose
     * {@code .class} survives is <em>included</em> until {@code mvnw clean} — the compiled⇄tracked
     * parity in {@code DoorsHarnessTest} is what catches that.
     */
    public static Population<Class<?>> productionClasses() {
        return PRODUCTION_CLASSES.get();
    }

    /**
     * Every class carrying a web stereotype (P1): {@code @Controller}, {@code @RestController},
     * {@code @ControllerAdvice}, {@code @RestControllerAdvice} or a type-level {@code @RequestMapping},
     * meta-annotation aware — the HD-214 predicate, and the one definition of "web bean" the
     * ADR-0018 rule in {@code ArchitectureRulesTest} reads (HD-297).
     *
     * <p><strong>Not included:</strong> a functional endpoint ({@code RouterFunction}); a bean Spring
     * instantiates without a stereotype. Conversely a stereotype on a class Spring never
     * instantiates <em>is</em> counted — classes, not beans.
     */
    public static Population<Class<?>> webBeans() {
        return WEB_BEANS.get();
    }

    /**
     * Every request-mapped handler method (P2), selected and composed the way
     * {@code RequestMappingHandlerMapping} does it: {@link MethodIntrospector#selectMethods} over
     * every {@code @Controller}-meta class, one merged {@code @RequestMapping} per method
     * ({@code @PostMapping} and friends resolve through {@code @AliasFor}), class prefix × method
     * path combined by {@link PathPatternsRequestCondition#combine}, verbs unioned by
     * {@link RequestMethodsRequestCondition#combine}. {@code DoorsHandlerMappingParityTest} holds
     * this equal to the runtime's handler map.
     *
     * <p><strong>Not included:</strong> a handler on a class that is not {@code @Controller}-meta
     * (a bare type-level {@code @RequestMapping} is a web bean in {@link #webBeans()} but not a
     * handler bean since Spring 6, and Doors follows the runtime); a functional endpoint; a handler
     * in {@code target/test-classes}; a path prefix configured through {@code PathMatchConfigurer}
     * (none today — the parity test would fail the day one appears). Conversely, <em>classes, not
     * beans</em>: a handler on a {@code @Conditional}-meta class is a member whether or not its
     * condition holds ({@link Handler#conditional()} says so; the parity test runs with every such
     * condition on, so the runtime routes it there). {@code SpaController}'s view-returning
     * handlers <em>are</em> members ({@code restful = false}); a consumer wanting the JSON API
     * filters on {@link Handler#restful()}.
     */
    public static Population<Handler> handlers() {
        return HANDLERS.get();
    }

    /**
     * Handlers a write budget applies to (P2w): {@code verbs ∩ {POST, PUT, PATCH, DELETE} ≠ ∅}, and a
     * mapping with no method condition counts as all four ({@link Handler#writeVerbs()}).
     *
     * <p><strong>Not included:</strong> verbs come from the mapping, not from what the method does —
     * a {@code GET} that writes is a read here, a {@code POST} that only reads is a write.
     */
    public static Population<Handler> writeHandlers() {
        return WRITE_HANDLERS.get();
    }

    /**
     * Handlers a read reaches (P2r): GET / HEAD / OPTIONS, or unconditioned.
     *
     * <p><strong>Not included:</strong> the same blind spot as {@link #writeHandlers()} — the verb is
     * the mapping's, not the method's; an SSE emitter or a view forward is a read like any other.
     */
    public static Population<Handler> readHandlers() {
        return READ_HANDLERS.get();
    }

    /**
     * Every parameter of every handler (P3) with the {@link Binding} Spring applies to it and whether
     * it is required ({@code required = false}, a {@code defaultValue}, or an un-annotated parameter
     * all read as not required).
     *
     * <p><strong>Not included:</strong> un-annotated simple-type parameters are bound as request
     * params by Spring and classified {@link Binding#OTHER} here (none today); {@code MultipartFile}
     * parameters carry {@code @RequestParam} and are QUERY; {@code @AuthenticationPrincipal} and
     * servlet types are OTHER. Whether a {@code @RequestParam boolean} without {@code defaultValue}
     * would 500 on absence is a rule for a consumer, not a fact recorded here.
     */
    public static Population<Param> controllerParams() {
        return CONTROLLER_PARAMS.get();
    }

    /**
     * Every record read from a request (P4): the closure of record types reachable from every
     * BODY / PART / MODEL parameter through {@link ResolvableType} — record components, arrays, all
     * generic arguments, non-record production classes followed for their fields, depth ≤ 6,
     * cycle-guarded — <strong>∪</strong> every production record whose simple name ends in
     * {@code Request} ({@link RequestRecord#byNameOnly()} when only the second half found it).
     *
     * <p><strong>Not included:</strong> a body type that is a class, not a record, is followed for
     * its record-typed fields but is not itself a member; a type reachable only through a
     * {@code JsonNode} is opaque; Jackson polymorphic subtypes are invisible; a query object the
     * controller assembles from {@code @RequestParam}s ({@code CycleTimeQuery}, {@code FlowQuery})
     * is not a request record; response records are excluded on purpose — they may carry primitives.
     */
    public static Population<RequestRecord> requestRecords() {
        return REQUEST_RECORDS.get();
    }

    /**
     * Every Spring Data repository (P5): production interfaces assignable to {@link Repository},
     * whether they extend {@code JpaRepository} or the bare marker.
     *
     * <p><strong>Not included:</strong> a data-access class that is not a Spring Data interface — an
     * {@code EntityManager} holder, a {@code JdbcTemplate} user (none today) — is invisible here.
     */
    public static Population<Class<?>> repositories() {
        return REPOSITORIES.get();
    }

    /**
     * Every JPA entity and its table (P5t): {@code @Entity} classes → {@code @Table.name}, falling back
     * to Spring Boot's camel-case-to-underscores naming when the annotation or its name is absent.
     *
     * <p><strong>Not included:</strong> a table with no entity (a Flyway-only table, a join table
     * mapped through {@code @JoinTable}) is not a member; the fallback naming is Boot's default and
     * would be wrong under a custom {@code PhysicalNamingStrategy} (none configured today).
     */
    public static Population<Entity> tables() {
        return TABLES.get();
    }

    /**
     * Every method offered by every repository whose <em>domain type</em> maps {@code table} (P5f):
     * {@code getMethods()} minus {@code Object}'s, so inherited {@code findAll}/{@code save} count.
     * Several repositories may share one entity ({@code issues} does); {@link Finder#repository()}
     * tells them apart. An unknown table yields an empty population — assert the table exists via
     * {@link #tables()} first.
     *
     * <p><strong>Not included:</strong> finders are grouped by the repository's domain type, so a
     * {@code @Query} (JPQL or native) on a repository of entity A that reads table B is a finder of A
     * here, not of B; reads outside Spring Data are invisible — a class that queries through an
     * {@code EntityManager} (several exist), a {@code JdbcTemplate} user or a
     * {@code JpaSpecificationExecutor} (none today). A source regex on the table name
     * ({@code NotificationFinderSealTest}) remains the tool for that hole.
     */
    public static Population<Finder> repositoryFinders(String table) {
        Objects.requireNonNull(table, "table");
        var known = FINDERS_BY_TABLE.get();
        var finders = known.getOrDefault(table, List.of());
        var name = "repository finders of `" + table + "`";
        if (finders.isEmpty()) {
            name += " (no repository maps `" + table + "`; tables known: " + String.join(", ", known.keySet()) + ")";
        }
        return Population.of(name, finders);
    }

    /**
     * Every method carrying a merged, repeatable {@code @Scheduled} (P6), one member per method with
     * its schedules joined.
     *
     * <p><strong>Not included:</strong> enumerated by annotation, not by registration — a task added
     * through {@code SchedulingConfigurer} ({@code StorageReconcileSchedule} is one), anything handed
     * to a {@code TaskScheduler}, and {@code @Async} work are invisible; a {@code @Scheduled} on a
     * class Spring never registers is counted as a live job.
     */
    public static Population<Job> scheduledJobs() {
        return SCHEDULED_JOBS.get();
    }

    /**
     * Every concrete production class assignable to {@link Filter} (P7).
     *
     * <p><strong>Not included:</strong> classes, not registrations — whether a filter is enabled, on
     * which URLs, in which order, or in the Security chain is not visible here
     * ({@code JwtAuthenticationFilter}'s registration bean is disabled and it is mounted by
     * {@code addFilterBefore}); a {@code Filter} registered only by a test is outside the tree.
     */
    public static Population<Class<? extends Filter>> servletFilters() {
        return SERVLET_FILTERS.get();
    }

    /**
     * Every public, non-synthetic {@code send*} method on every class that holds a
     * {@link JavaMailSender} (P8) — a declared field up the hierarchy, or a constructor / method
     * parameter of that type.
     *
     * <p><strong>Not included:</strong> the {@code send*} naming convention is the handle — a mailer
     * named {@code dispatch} is invisible (a second holder still shows); callers of a mailer are a
     * source fact and stay with {@code MailerAfterCommitCoverageTest}'s scanner; mail produced by
     * anything that bypasses the holder is invisible by construction.
     */
    public static Population<MailSite> mailSendSites() {
        return MAIL_SEND_SITES.get();
    }

    /**
     * Every place that can write a problem body (P9): {@code @ExceptionHandler} methods on
     * {@code @ControllerAdvice}-meta beans ({@link WriterKind#ADVICE_HANDLER}) ∪ the pre-MVC doors —
     * {@link #servletFilters()}, {@link AuthenticationEntryPoint}, {@link AccessDeniedHandler} and
     * {@link HandlerInterceptor} implementations.
     *
     * <p><strong>Not included:</strong> reflection sees <em>places that can write</em>, not whether
     * they do or with which content type; a static helper that writes on a filter's behalf
     * ({@code DatabaseBusyRefusal}) is reached through the filter and not listed itself; Spring's own
     * error rendering ({@code /error}, {@code ProblemDetail} negotiation) is outside the tree. The
     * assignability scan is also blind to every writer kind it does not look for: a
     * {@code HandlerExceptionResolver} implementation, an {@code ErrorController} /
     * {@code ErrorAttributes} override, a {@code ResponseBodyAdvice}, an
     * {@code AuthenticationFailureHandler}, a {@code LogoutSuccessHandler}. None of these exists in
     * {@code src/main} today (the only {@code HandlerExceptionResolver} occurrences are javadoc
     * mentions); the day one appears it becomes a member only when its interface is added to the
     * scan, so a clean pass over this population says nothing about it until then.
     */
    public static Population<ProblemWriter> problemJsonWriters() {
        return PROBLEM_JSON_WRITERS.get();
    }

    // =================================================================== the root

    /**
     * The directory {@link HamstrackApplication} was loaded from. Refused when it is not a directory
     * (a jar): the walk, the tracked-source parity and the "stale class file" diagnosis all assume
     * one, and half-supporting a jar would silently change what every consumer scans.
     */
    static Path codeSourceRoot() {
        var source = HamstrackApplication.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new AssertionError("HamstrackApplication has no code source location — Doors cannot find the "
                                     + "production tree to scan");
        }
        Path root;
        try {
            root = Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("code source of HamstrackApplication is not a filesystem location: "
                                     + source.getLocation(), e);
        }
        if (!Files.isDirectory(root)) {
            throw new AssertionError("Doors scans a compiled class DIRECTORY and HamstrackApplication was loaded from "
                                     + root + ". Running from a jar is refused rather than half-supported: run the "
                                     + "tests from the Maven module so the code source is target/classes.");
        }
        return root;
    }

    // =================================================================== the scans

    private static final Memo<Population<Class<?>>> PRODUCTION_CLASSES = new Memo<>(() -> {
        var root = codeSourceRoot();
        var loader = Doors.class.getClassLoader();
        var found = new ArrayList<Class<?>>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".class")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
        for (var file : files) {
            var name = classNameOf(root, file);
            if (name.endsWith("package-info") || name.endsWith("module-info")) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new AssertionError("Doors found " + file + " but could not load " + name + " — a door nobody can "
                                         + "load is a door nobody checks; fix the classpath (an optional dependency "
                                         + "missing from test scope?) rather than skipping it", e);
            }
            if (type.isAnonymousClass() || type.isSynthetic()) {
                continue;
            }
            found.add(type);
        }
        found.sort(Comparator.comparing(Class::getName));
        return Population.of("production classes", found);
    });

    private static final Memo<Population<Class<?>>> WEB_BEANS = new Memo<>(() -> Population.of("web beans",
            productionClasses().stream().filter(Doors::isWebClass).toList()));

    private static final Memo<Population<Handler>> HANDLERS = new Memo<>(() -> {
        var handlers = new ArrayList<Handler>();
        for (var bean : webBeans()) {
            if (!AnnotatedElementUtils.hasAnnotation(bean, Controller.class)) {
                continue;
            }
            var typeMapping = AnnotatedElementUtils.findMergedAnnotation(bean, RequestMapping.class);
            var typePaths = new PathPatternsRequestCondition(PARSER, typeMapping == null ? new String[0] : typeMapping.path());
            var typeVerbs = new RequestMethodsRequestCondition(typeMapping == null ? new RequestMethod[0] : typeMapping.method());
            var restfulBean = AnnotatedElementUtils.hasAnnotation(bean, ResponseBody.class);
            var conditional = AnnotatedElementUtils.hasAnnotation(bean, Conditional.class);
            Map<Method, RequestMapping> selected = MethodIntrospector.selectMethods(bean,
                    (MethodIntrospector.MetadataLookup<RequestMapping>) m ->
                            AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class));
            for (var entry : selected.entrySet()) {
                var method = entry.getKey();
                var mapping = entry.getValue();
                var paths = typePaths.combine(new PathPatternsRequestCondition(PARSER, mapping.path())).getPatternValues();
                var verbs = typeVerbs.combine(new RequestMethodsRequestCondition(mapping.method())).getMethods();
                var restful = restfulBean || AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class);
                handlers.add(new Handler(bean, method, Set.copyOf(verbs), List.copyOf(new ArrayList<>(paths)), restful,
                        conditional));
            }
        }
        handlers.sort(Comparator.comparing((Handler h) -> h.bean().getName()).thenComparing(h -> h.method().getName())
                .thenComparing(h -> String.join(",", h.paths())));
        return Population.of("handlers", handlers);
    });

    private static final Memo<Population<Handler>> WRITE_HANDLERS = new Memo<>(() -> Population.of("write handlers",
            handlers().stream().filter(Handler::writes).toList()));

    private static final Memo<Population<Handler>> READ_HANDLERS = new Memo<>(() -> Population.of("read handlers",
            handlers().stream().filter(Handler::reads).toList()));

    private static final Memo<Population<Param>> CONTROLLER_PARAMS = new Memo<>(() -> {
        var params = new ArrayList<Param>();
        for (var handler : handlers()) {
            for (var parameter : handler.method().getParameters()) {
                params.add(classify(handler, parameter));
            }
        }
        return Population.of("controller parameters", params);
    });

    private static final Memo<Population<RequestRecord>> REQUEST_RECORDS = new Memo<>(() -> {
        var reached = new LinkedHashMap<Class<?>, Reached>();
        for (var param : controllerParams()) {
            if (param.binding() != Binding.BODY && param.binding() != Binding.PART && param.binding() != Binding.MODEL) {
                continue;
            }
            var index = param.index();
            var type = ResolvableType.forMethodParameter(new MethodParameter(param.handler().method(), index));
            collectRecords(type, param.handler(), new ArrayList<>(), 0, new HashSet<>(), reached);
        }
        var records = new ArrayList<RequestRecord>();
        for (var entry : reached.entrySet()) {
            records.add(new RequestRecord(entry.getKey(), Set.copyOf(entry.getValue().mountedBy),
                    List.copyOf(entry.getValue().via), false));
        }
        for (var type : productionClasses()) {
            if (type.isRecord() && type.getSimpleName().endsWith("Request") && !reached.containsKey(type)) {
                records.add(new RequestRecord(type, Set.of(), List.of(), true));
            }
        }
        records.sort(Comparator.comparing(r -> r.type().getName()));
        return Population.of("request records", records);
    });

    private static final Memo<Population<Class<?>>> REPOSITORIES = new Memo<>(() -> Population.of("repositories",
            productionClasses().stream().filter(c -> c.isInterface() && Repository.class.isAssignableFrom(c)).toList()));

    private static final Memo<Population<Entity>> TABLES = new Memo<>(() -> {
        var entities = new ArrayList<Entity>();
        for (var type : productionClasses()) {
            if (!type.isAnnotationPresent(jakarta.persistence.Entity.class)) {
                continue;
            }
            var table = type.getAnnotation(Table.class);
            var name = table == null || table.name().isBlank() ? camelCaseToUnderscores(type.getSimpleName()) : table.name();
            entities.add(new Entity(type, name));
        }
        entities.sort(Comparator.comparing(Entity::table).thenComparing(e -> e.type().getName()));
        return Population.of("tables", entities);
    });

    private static final Memo<Map<String, List<Finder>>> FINDERS_BY_TABLE = new Memo<>(() -> {
        var tableOf = new LinkedHashMap<Class<?>, String>();
        for (var entity : tables()) {
            tableOf.put(entity.type(), entity.table());
        }
        var objectMethods = Set.of(Object.class.getMethods());
        var byTable = new TreeMap<String, List<Finder>>();
        for (var repository : repositories()) {
            var domain = ResolvableType.forClass(Repository.class, repository).getGeneric(0).resolve();
            if (domain == null) {
                continue;
            }
            var table = tableOf.get(domain);
            if (table == null) {
                continue;
            }
            var finders = new ArrayList<Finder>();
            for (var method : repository.getMethods()) {
                if (objectMethods.contains(method) || method.isSynthetic()) {
                    continue;
                }
                finders.add(new Finder(repository, method, domain, table, method.getDeclaringClass() != repository));
            }
            finders.sort(Comparator.comparing((Finder f) -> f.repository().getName()).thenComparing(f -> f.method().getName()));
            byTable.computeIfAbsent(table, t -> new ArrayList<>()).addAll(finders);
        }
        byTable.replaceAll((t, list) -> List.copyOf(list));
        return Map.copyOf(byTable);
    });

    private static final Memo<Population<Job>> SCHEDULED_JOBS = new Memo<>(() -> {
        var jobs = new ArrayList<Job>();
        for (var type : productionClasses()) {
            if (type.isInterface() || type.isAnnotation()) {
                continue;
            }
            Map<Method, Set<Scheduled>> selected = MethodIntrospector.selectMethods(type,
                    (MethodIntrospector.MetadataLookup<Set<Scheduled>>) m -> {
                        var found = AnnotatedElementUtils.findMergedRepeatableAnnotations(m, Scheduled.class, Schedules.class);
                        return found.isEmpty() ? null : found;
                    });
            for (var entry : selected.entrySet()) {
                var schedule = entry.getValue().stream().map(Doors::describeSchedule).collect(Collectors.joining(" + "));
                jobs.add(new Job(type, entry.getKey(), schedule));
            }
        }
        jobs.sort(Comparator.comparing((Job j) -> j.owner().getName()).thenComparing(j -> j.method().getName()));
        return Population.of("scheduled jobs", jobs);
    });

    private static final Memo<Population<Class<? extends Filter>>> SERVLET_FILTERS = new Memo<>(() -> Population.of(
            "servlet filters",
            productionClasses().stream().filter(Doors::isConcrete).filter(Filter.class::isAssignableFrom)
                    .<Class<? extends Filter>>map(c -> c.asSubclass(Filter.class)).toList()));

    private static final Memo<Population<MailSite>> MAIL_SEND_SITES = new Memo<>(() -> {
        var sites = new ArrayList<MailSite>();
        for (var type : productionClasses()) {
            if (!holdsA(type, JavaMailSender.class)) {
                continue;
            }
            for (var method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic() && method.getName().startsWith("send")) {
                    sites.add(new MailSite(type, method));
                }
            }
        }
        sites.sort(Comparator.comparing((MailSite s) -> s.holder().getName()).thenComparing(s -> s.mailer().getName()));
        return Population.of("mail send sites", sites);
    });

    private static final Memo<Population<ProblemWriter>> PROBLEM_JSON_WRITERS = new Memo<>(() -> {
        var writers = new ArrayList<ProblemWriter>();
        for (var type : productionClasses()) {
            if (AnnotatedElementUtils.hasAnnotation(type, ControllerAdvice.class)) {
                Map<Method, ExceptionHandler> selected = MethodIntrospector.selectMethods(type,
                        (MethodIntrospector.MetadataLookup<ExceptionHandler>) m ->
                                AnnotatedElementUtils.findMergedAnnotation(m, ExceptionHandler.class));
                for (var method : selected.keySet()) {
                    writers.add(new ProblemWriter(WriterKind.ADVICE_HANDLER, type, method));
                }
            }
            if (!isConcrete(type)) {
                continue;
            }
            if (Filter.class.isAssignableFrom(type)) {
                writers.add(new ProblemWriter(WriterKind.FILTER, type, null));
            }
            if (AuthenticationEntryPoint.class.isAssignableFrom(type)) {
                writers.add(new ProblemWriter(WriterKind.ENTRY_POINT, type, null));
            }
            if (AccessDeniedHandler.class.isAssignableFrom(type)) {
                writers.add(new ProblemWriter(WriterKind.ACCESS_DENIED, type, null));
            }
            if (HandlerInterceptor.class.isAssignableFrom(type)) {
                writers.add(new ProblemWriter(WriterKind.INTERCEPTOR, type, null));
            }
        }
        writers.sort(Comparator.comparing((ProblemWriter w) -> w.kind().ordinal())
                .thenComparing(w -> w.owner().getName())
                .thenComparing(w -> w.method() == null ? "" : w.method().getName()));
        return Population.of("problem writers", writers);
    });

    // =================================================================== predicates & helpers

    /**
     * Meta-annotation aware on purpose: {@code @RestController} is itself annotated {@code @Controller},
     * {@code @RestControllerAdvice} is annotated {@code @ControllerAdvice}, and a Hamstrack-local
     * stereotype composed from either must be caught by the same sweep.
     */
    private static boolean isWebClass(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, Controller.class)
               || AnnotatedElementUtils.hasAnnotation(type, RestController.class)
               || AnnotatedElementUtils.hasAnnotation(type, ControllerAdvice.class)
               || AnnotatedElementUtils.hasAnnotation(type, RestControllerAdvice.class)
               || AnnotatedElementUtils.hasAnnotation(type, RequestMapping.class);
    }

    private static boolean isConcrete(Class<?> type) {
        return !type.isInterface() && !type.isAnnotation() && !Modifier.isAbstract(type.getModifiers());
    }

    private static Param classify(Handler handler, Parameter parameter) {
        var body = AnnotatedElementUtils.findMergedAnnotation(parameter, RequestBody.class);
        if (body != null) {
            return new Param(handler, parameter, Binding.BODY, body.required());
        }
        var part = AnnotatedElementUtils.findMergedAnnotation(parameter, RequestPart.class);
        if (part != null) {
            return new Param(handler, parameter, Binding.PART, part.required());
        }
        var query = AnnotatedElementUtils.findMergedAnnotation(parameter, RequestParam.class);
        if (query != null) {
            return new Param(handler, parameter, Binding.QUERY, query.required() && isNone(query.defaultValue()));
        }
        var path = AnnotatedElementUtils.findMergedAnnotation(parameter, PathVariable.class);
        if (path != null) {
            return new Param(handler, parameter, Binding.PATH, path.required());
        }
        var header = AnnotatedElementUtils.findMergedAnnotation(parameter, RequestHeader.class);
        if (header != null) {
            return new Param(handler, parameter, Binding.HEADER, header.required() && isNone(header.defaultValue()));
        }
        var cookie = AnnotatedElementUtils.findMergedAnnotation(parameter, CookieValue.class);
        if (cookie != null) {
            return new Param(handler, parameter, Binding.COOKIE, cookie.required() && isNone(cookie.defaultValue()));
        }
        if (AnnotatedElementUtils.hasAnnotation(parameter, ModelAttribute.class)) {
            return new Param(handler, parameter, Binding.MODEL, true);
        }
        return new Param(handler, parameter, Binding.OTHER, false);
    }

    private static boolean isNone(String defaultValue) {
        return ValueConstants.DEFAULT_NONE.equals(defaultValue);
    }

    private static final int RECORD_DEPTH = 6;

    private record Reached(Set<Handler> mountedBy, List<Class<?>> via) {
    }

    /**
     * The reachability half of {@link #requestRecords()}: generics and arrays are followed for any type,
     * record components for a production record, declared fields for a production class that is not a
     * record; everything else (JDK types, {@code JsonNode}, enums) is a leaf.
     */
    private static void collectRecords(ResolvableType type, Handler handler, List<Class<?>> via, int depth,
                                       Set<Class<?>> visited, Map<Class<?>, Reached> into) {
        if (depth > RECORD_DEPTH) {
            return;
        }
        var raw = type.resolve();
        if (raw == null) {
            return;
        }
        if (raw.isArray()) {
            collectRecords(type.getComponentType(), handler, via, depth + 1, visited, into);
            return;
        }
        for (var generic : type.getGenerics()) {
            collectRecords(generic, handler, via, depth + 1, visited, into);
        }
        if (!isProduction(raw) || raw.isEnum() || !visited.add(raw)) {
            return;
        }
        var deeper = new ArrayList<>(via);
        if (raw.isRecord()) {
            // The shortest chain wins, so `via` is empty exactly when some handler mounts the record
            // directly — whichever order the handlers were walked in.
            var reached = into.get(raw);
            if (reached == null || via.size() < reached.via().size()) {
                reached = new Reached(reached == null ? new LinkedHashSet<>() : reached.mountedBy(), List.copyOf(via));
                into.put(raw, reached);
            }
            reached.mountedBy().add(handler);
            deeper.add(raw);
            for (var component : raw.getRecordComponents()) {
                collectRecords(ResolvableType.forType(component.getGenericType(), type), handler, deeper, depth + 1,
                        visited, into);
            }
            return;
        }
        if (raw.isInterface()) {
            return;
        }
        deeper.add(raw);
        for (Field field : raw.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            collectRecords(ResolvableType.forField(field, type), handler, deeper, depth + 1, visited, into);
        }
    }

    private static boolean isProduction(Class<?> type) {
        return type.getName().startsWith(HamstrackApplication.class.getPackageName() + ".");
    }

    /** A declared field up the hierarchy, or a constructor / method parameter, of the given type. */
    private static boolean holdsA(Class<?> type, Class<?> held) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var field : c.getDeclaredFields()) {
                if (held.isAssignableFrom(field.getType())) {
                    return true;
                }
            }
        }
        for (var constructor : type.getDeclaredConstructors()) {
            if (Arrays.stream(constructor.getParameterTypes()).anyMatch(held::isAssignableFrom)) {
                return true;
            }
        }
        for (var method : type.getDeclaredMethods()) {
            if (Arrays.stream(method.getParameterTypes()).anyMatch(held::isAssignableFrom)) {
                return true;
            }
        }
        return false;
    }

    private static String describeSchedule(Scheduled scheduled) {
        var parts = new ArrayList<String>();
        if (!scheduled.cron().isEmpty()) {
            parts.add("cron=" + scheduled.cron());
        }
        if (scheduled.fixedDelay() >= 0) {
            parts.add("fixedDelay=" + scheduled.fixedDelay());
        }
        if (!scheduled.fixedDelayString().isEmpty()) {
            parts.add("fixedDelayString=" + scheduled.fixedDelayString());
        }
        if (scheduled.fixedRate() >= 0) {
            parts.add("fixedRate=" + scheduled.fixedRate());
        }
        if (!scheduled.fixedRateString().isEmpty()) {
            parts.add("fixedRateString=" + scheduled.fixedRateString());
        }
        if (scheduled.initialDelay() >= 0) {
            parts.add("initialDelay=" + scheduled.initialDelay());
        }
        if (!scheduled.initialDelayString().isEmpty()) {
            parts.add("initialDelayString=" + scheduled.initialDelayString());
        }
        return parts.isEmpty() ? "?" : String.join(" ", parts);
    }

    /** Spring Boot's default physical naming ({@code CamelCaseToUnderscoresNamingStrategy}) for an entity without {@code @Table}. */
    static String camelCaseToUnderscores(String simpleName) {
        var out = new StringBuilder();
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(simpleName.charAt(i - 1))) {
                out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** {@code com/hamstrack/Foo$Bar.class} → {@code com.hamstrack.Foo$Bar}, with {@code /} and {@code \} both mapped. */
    static String classNameOf(Path root, Path file) {
        var relative = root.relativize(file).toString();
        return relative.substring(0, relative.length() - ".class".length()).replace('\\', '.').replace('/', '.');
    }

    /** Computed once per JVM; one lock per population so dependent populations never re-enter one another. */
    private static final class Memo<T> {
        private final Supplier<T> supplier;
        private T value;

        Memo(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        synchronized T get() {
            if (value == null) {
                value = supplier.get();
            }
            return value;
        }
    }
}
