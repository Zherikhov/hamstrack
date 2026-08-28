package com.hamstrack.common.validation;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.LabelTestBase;
import jakarta.validation.Constraint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>HD-214 AC-7 — Sweep B, sealed as a category: every parameter-level constraint in the tree
 * answers a 4xx, and none of them answers a 5xx.</strong>
 *
 * <p>This is deliberately <em>not</em> phrased about {@code q} or about the two {@code token}s. The
 * defect this ticket removed was not "{@code q} was unbounded" — it was that one of three
 * constrained parameters refused through a different mechanism than its siblings, and the difference
 * was invisible because a 500 and a 400 both look like "the request was rejected" from the outside.
 * A test naming the members would have passed on the day the fourth constrained parameter appeared
 * carrying the same defect. So the row set is <em>discovered by reflection</em> and the test fails
 * when a site is discovered that no row exercises.
 *
 * <p><strong>The scan's own definition of the category</strong>: a Bean Validation constraint
 * annotation (anything meta-annotated {@code @Constraint} — {@code @Size}, {@code @Max},
 * {@code @Pattern}, {@code @NotBlank}, a project-local one) written directly on a
 * {@code @RequestParam}, {@code @PathVariable}, {@code @RequestHeader} or {@code @CookieValue}.
 * {@code @Valid @RequestBody} is a different mechanism (argument-resolver validation) with its own
 * contract test and is not in this table.
 *
 * <p><strong>What is NOT in the category, and why it is worth saying.</strong> Most request
 * parameters in the product carry no constraint and need none, because their <em>type</em> is the
 * bound: a {@code UUID}, an enum, an {@code Integer}, a {@code long} path variable. A malformed
 * value fails Spring's binding and is already a 400. A parameter whose type refuses the value has
 * nothing left to declare, so the absence of an annotation is not a finding.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ParameterConstraintSweepTest extends LabelTestBase {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /**
     * <strong>The scan tripwire.</strong> Nine parameter-level constraints exist today: the four the
     * spec's §4 table enumerates (two {@code token}s, {@code q}, {@code field}) plus the five
     * {@code @Max(Paging.MAX_PAGE)} page bounds this ticket added — which are parameter constraints
     * by exactly the same definition, and which the §4 table predates. The ticket's own floor was
     * {@code >= 4}; this one sits at the truth, because a tripwire is worth the distance between it
     * and reality, and four would not fire until five sites had silently disappeared.
     */
    private static final int MIN_CONSTRAINED_PARAMETERS = 9;

    /** A tree smaller than this means the walk is not looking at the project. */
    private static final int MIN_SOURCES = 100;

    /**
     * Sites deliberately not exercised, each with the reason. An entry here is a decision somebody
     * made; a site in neither this map nor the row table is a build failure. Empty today, and kept
     * so that the answer to "my new constrained parameter is awkward to reach" is a written reason
     * rather than a deleted assertion.
     */
    private static final Map<String, String> EXCLUSIONS = Map.of();

    /** How a row is refused, so the 4xx it must produce is not confused with an auth failure. */
    private enum As { ANONYMOUS, MEMBER, ADMIN }

    private record Row(String id, As as, Function<Fixture, MockHttpServletRequestBuilder> request) {}

    /**
     * One row per discovered site, each sending a value that violates <em>that</em> constraint while
     * everything else about the request is well-formed — otherwise a 400 could come from anywhere
     * and the row would prove nothing about the bound it names.
     */
    private List<Row> rows() {
        var overLong = "x".repeat(1000);
        var pastPage = String.valueOf((long) Paging.MAX_PAGE + 1);
        return List.of(
                new Row("AuthController#verifyEmailLink(token)", As.ANONYMOUS,
                        f -> get("/api/auth/verify-email").param("token", overLong)),
                new Row("WorkspaceController#acceptInvite(token)", As.MEMBER,
                        f -> post("/api/workspaces/accept-invite").param("token", overLong)),
                new Row("SearchController#suggest(field)", As.MEMBER,
                        f -> get(ws(f) + "/search/suggest").param("field", overLong).param("q", "a")),
                new Row("SearchController#suggest(q)", As.MEMBER,
                        f -> get(ws(f) + "/search/suggest").param("field", "assignee").param("q", overLong)),
                new Row("IssueController#list(page)", As.MEMBER,
                        f -> get(issues(f) + "?size=10&page=" + pastPage)),
                new Row("IssueController#history(page)", As.MEMBER,
                        f -> get(issues(f) + "/" + f.issueNumber + "/history?size=10&page=" + pastPage)),
                new Row("IssueController#listComments(page)", As.MEMBER,
                        f -> get(issues(f) + "/" + f.issueNumber + "/comments?size=10&page=" + pastPage)),
                new Row("SprintController#list(page)", As.MEMBER,
                        f -> get(ws(f) + "/projects/" + f.ctx.projectId() + "/sprints?size=10&page=" + pastPage)),
                new Row("AdminUserController#list(page)", As.ADMIN,
                        f -> get("/api/admin/users?size=10&page=" + pastPage)));
    }

    private Fixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        if (fixture == null) {
            fixture = buildFixture();
        }
    }

    // ============================================================ the behaviour

    /**
     * The claim itself, as a status <em>class</em> rather than a value. 400 is what all nine answer
     * today, but a site that answered 422 after normalising the value would also be correct, and
     * pinning 400 everywhere would make this test an obstacle to a legitimate design rather than a
     * guard against a crash. The failure being guarded is a 5xx — and, just as important, a 401 or
     * 403, because a row refused at the door never reached validation and certifies nothing about
     * the bound it is named after.
     */
    @Test
    void noDeclaredParameterConstraintAnswersAServerError() throws Exception {
        var offenders = new ArrayList<String>();
        for (var row : rows()) {
            int status = perform(row);
            if (status >= 500) {
                offenders.add(row.id() + " → " + status + " (a server error)");
            } else if (status < 400) {
                offenders.add(row.id() + " → " + status
                              + " (the value violates the declared constraint and was ACCEPTED — "
                              + "the annotation is not being enforced at all)");
            } else if (status == 401 || status == 403) {
                offenders.add(row.id() + " → " + status
                              + " (refused before validation, so this row exercises no bound; fix "
                              + "the row's credentials rather than accepting the green)");
            }
        }

        assertThat(offenders)
                .as("""
                        A DECLARED PARAMETER CONSTRAINT IS NOT ANSWERING A CLIENT ERROR.

                        A 5xx here means the constraint is being enforced by something other than \
                        Spring MVC's own method validation. The usual cause is @Validated on the \
                        controller class: it makes HandlerMethod.shouldValidateArguments() return \
                        false, so the AOP proxy validates instead and raises \
                        jakarta.validation.ConstraintViolationException. That is HD-214 exactly, and \
                        WebBeanValidatedRuleTest should have caught it one step earlier.

                        A 2xx means the annotation is decorative — nothing is reading it. Check that \
                        the parameter carries a real @RequestParam/@PathVariable binding and that \
                        the constraint is on the parameter rather than on its type.""")
                .isEmpty();
    }

    // ============================================================ the category claim

    /**
     * <strong>The tripwire that makes this a category and not a list.</strong> It reads every
     * production class, finds every parameter carrying both a web binding and a constraint, and
     * requires each to be covered by a row or declared as an exclusion. That is what makes a new
     * constrained parameter a deliberate edit here rather than an omission nobody notices — which is
     * the precise mechanism by which HD-3's defect survived for months.
     */
    @Test
    void everyConstrainedParameterInTheTreeIsExercisedByARow() throws Exception {
        var sites = constrainedParameters();

        assertThat(sites)
                .as("""
                        The scan found %d constrained parameters. A scan that has stopped seeing \
                        declarations certifies nothing while staying green — find out what changed \
                        (a moved package, a classloading failure, a constraint moved off the \
                        parameter) rather than lowering this. If a bound was legitimately deleted, \
                        say which one and why in the same commit.""", sites.size())
                .hasSizeGreaterThanOrEqualTo(MIN_CONSTRAINED_PARAMETERS);

        var covered = rows().stream().map(Row::id).collect(java.util.stream.Collectors.toSet());
        var uncovered = sites.stream()
                .filter(site -> !covered.contains(site) && !EXCLUSIONS.containsKey(site))
                .toList();

        assertThat(uncovered)
                .as("""
                        A PARAMETER CARRIES A DECLARED BOUND AND NOTHING PROVES IT REFUSES CLEANLY.

                        Add a Row to rows() sending a value that violates THAT constraint while the \
                        rest of the request stays well-formed, with the credentials that get it past \
                        the security filter chain — or, if the site genuinely cannot be reached by a \
                        test, add it to EXCLUSIONS with the reason, which is a decision somebody \
                        will read.

                        Do not delete the site's annotation to make this pass: an unbounded \
                        parameter is the other half of the defect this file exists for.""")
                .isEmpty();

        // The rows must also stay honest in the other direction: a row naming a site that no longer
        // exists is dead weight that makes the coverage count look healthier than it is.
        var stale = covered.stream().filter(id -> !sites.contains(id)).toList();
        assertThat(stale)
                .as("""
                        A row names a site the scan cannot find. Either the parameter was renamed or \
                        its constraint was removed — in which case delete the row deliberately — or \
                        the row's id does not match the scan's `SimpleName#method(parameter)` \
                        format, in which case it is covering nothing while appearing to.""")
                .isEmpty();
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Every {@code SimpleName#method(parameter)} in production source where a web binding annotation
     * and a Bean Validation constraint sit on the same parameter.
     *
     * <p>Constraint detection is by meta-annotation ({@code @Constraint}), never by a list of
     * annotation types: a project-local constraint is a constraint, and a scan enumerating
     * {@code jakarta.validation.constraints} by hand would silently stop seeing the first one
     * anybody writes.
     */
    private static Set<String> constrainedParameters() throws IOException {
        var found = new LinkedHashSet<String>();
        for (var type : productionClasses()) {
            for (var method : type.getDeclaredMethods()) {
                for (var parameter : method.getParameters()) {
                    if (isWebBound(parameter) && isConstrained(parameter)) {
                        found.add(type.getSimpleName() + "#" + method.getName()
                                  + "(" + parameter.getName() + ")");
                    }
                }
            }
        }
        return found;
    }

    private static boolean isWebBound(Parameter parameter) {
        return parameter.isAnnotationPresent(RequestParam.class)
               || parameter.isAnnotationPresent(PathVariable.class)
               || parameter.isAnnotationPresent(RequestHeader.class)
               || parameter.isAnnotationPresent(CookieValue.class);
    }

    private static boolean isConstrained(Parameter parameter) {
        for (var annotation : parameter.getAnnotations()) {
            if (AnnotatedElementUtils.hasAnnotation(annotation.annotationType(), Constraint.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> productionClasses() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            var files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                    .toList();
            assertThat(files)
                    .as("scanned %s — if this is empty or tiny, the working directory is not the "
                        + "project root and every claim in this file is vacuous",
                            MAIN_SOURCES.toAbsolutePath())
                    .hasSizeGreaterThan(MIN_SOURCES);

            var loader = ParameterConstraintSweepTest.class.getClassLoader();
            var classes = new ArrayList<Class<?>>();
            for (var file : files) {
                var name = MAIN_SOURCES.relativize(file).toString()
                        .replace(".java", "").replace('\\', '.').replace('/', '.');
                try {
                    classes.add(Class.forName(name, false, loader));
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    throw new AssertionError("scanned " + file + " but could not load " + name
                                             + " — the source tree and the classpath disagree, so "
                                             + "this sweep is not seeing everything it claims to", e);
                }
            }
            return classes;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private int perform(Row row) throws Exception {
        var request = row.request().apply(fixture);
        var token = switch (row.as()) {
            case ANONYMOUS -> null;
            case MEMBER -> fixture.ctx.token();
            case ADMIN -> fixture.adminToken;
        };
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static String ws(Fixture f) {
        return "/api/workspaces/" + f.ctx.wsId();
    }

    private static String issues(Fixture f) {
        return ws(f) + "/projects/" + f.ctx.projectId() + "/issues";
    }

    private static final class Fixture {
        Ctx ctx;
        long issueNumber;
        String adminToken;
    }

    private Fixture buildFixture() throws Exception {
        var f = new Fixture();
        f.ctx = newProject();
        f.issueNumber = createIssue(f.ctx, "parameter sweep").get("number").asLong();

        var admin = new User();
        admin.setEmail(("sweep-admin-" + System.nanoTime() + "-"
                        + UUID.randomUUID().toString().substring(0, 6) + "@example.com").toLowerCase());
        admin.setDisplayName("Sweep Admin");
        admin.setPasswordHash(passwordEncoder.encode("test-password-1"));
        admin.setStatus(UserStatus.ACTIVE);
        admin.setSystemRole(SystemRole.ADMIN);
        f.adminToken = login(userRepository.save(admin));
        return f;
    }
}
