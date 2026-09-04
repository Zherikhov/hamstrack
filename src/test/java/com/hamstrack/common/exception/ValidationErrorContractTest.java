package com.hamstrack.common.exception;

import com.hamstrack.issue.LabelTestBase;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The <strong>error-body contract for bean-validation failures</strong>, pinned across the
 * whole app rather than inside one feature suite.
 *
 * <p>Why this class exists: {@code GlobalExceptionHandler.handleValidation} had
 * <em>never executed</em>. With {@code spring.mvc.problemdetails.enabled=true} Boot
 * registers its own {@code ProblemDetailsExceptionHandler} at order 0, and an unordered
 * {@code @RestControllerAdvice} sits at {@code LOWEST_PRECEDENCE}, so it lost every
 * exception both of them declare — {@code MethodArgumentNotValidException} included.
 * Every {@code @Valid @RequestBody} failure in the application answered Boot's generic
 * {@code {"detail":"Invalid request content."}} and the {@code errors} map never reached a
 * client. Nothing failed, because no test had ever asserted the body of a validation
 * failure: the suite was green against the wrong-but-consistent answer. That is the exact
 * failure mode this class removes — a regression in advice precedence now breaks a test
 * instead of silently degrading every 400 in the product.
 *
 * <p><strong>Locale warning for anyone extending this class.</strong> {@code detail} now
 * carries constraint messages, and a constraint <em>without</em> an explicit
 * {@code message} renders Hibernate Validator's <em>localized</em> default text (on a
 * Russian-locale JVM {@code @NotBlank} comes back in Russian). So: assert on the message
 * only where the constraint declares one, and assert on the <strong>field path</strong>
 * (the {@code errors} key, or the {@code "field: "} prefix the handler adds) everywhere
 * else. Never assert on default constraint text — it is a machine-dependent test.
 *
 * <p>Since {@code common.config.ValidationConfig} pins interpolation to
 * {@link java.util.Locale#ENGLISH} that rule is belt-and-braces rather than load-bearing,
 * and it is <em>kept on purpose</em>: the pin is one bean, and a test that only passes
 * because that bean exists should be the test that says so
 * ({@link #constraintMessagesStayEnglishWhateverTheCallerAsksFor()}), not thirty
 * incidental assertions elsewhere that would all break together.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ValidationErrorContractTest extends LabelTestBase {

    /** Boot's generic stand-in — the body this whole class exists to keep out of 400s. */
    private static final String BOOT_GENERIC = "Invalid request content";

    /** The tail the handler appends when it truncates; the group is the dropped count. */
    private static final Pattern OVERFLOW_SUFFIX = Pattern.compile("; … and (\\d+) more$");

    static final String CROSS_FIELD_PATH = "/api/__test/validation/cross-field";
    static final String CROSS_FIELD_MESSAGE = "start must be before end";

    // ====================================================== the contract, top-level field

    /**
     * The representative case: a single failed field with an explicit constraint message.
     * Both halves of the contract are asserted — the {@code errors} map (which the old,
     * dead handler never emitted at all) and {@code detail} (which the SPA's
     * {@code request()} is the only thing that renders). Asserting only one of the two
     * would let half the regression back in.
     */
    @Test
    void aFailedFieldIsNamedInDetailAndCarriedInTheErrorsMap() throws Exception {
        var ctx = newProject();

        createProject(ctx, "{\"name\":\"Valid name\",\"key\":\"lower-case\"}")
                .andExpect(status().isBadRequest())
                // the errors map: keyed by field path, valued by the constraint message
                .andExpect(jsonPath("$.errors.key")
                        .value("Key must be uppercase letters and digits only"))
                // …and the same message reaches `detail`, prefixed with the field it is about
                .andExpect(jsonPath("$.detail", containsString("key")))
                .andExpect(jsonPath("$.detail",
                        containsString("Key must be uppercase letters and digits only")))
                // the regression guard: Boot's advice must NOT have won this exception
                .andExpect(jsonPath("$.detail", not(containsString(BOOT_GENERIC))))
                // and the response is still a ProblemDetail, not some ad-hoc shape
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Every failed field is reported, not just whichever one Hibernate Validator happened
     * to visit first, and the join is <strong>deterministic</strong> (sorted) so the body
     * is a stable contract rather than a coin flip between deploys.
     *
     * <p>{@code name}'s constraints carry no explicit message, so only its field path is
     * asserted — see the class note on localized defaults.
     */
    @Test
    void everyFailedFieldIsReportedInADeterministicOrder() throws Exception {
        var ctx = newProject();

        createProject(ctx, "{\"name\":\"\",\"key\":\"lower-case\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.key")
                        .value("Key must be uppercase letters and digits only"))
                .andExpect(jsonPath("$.errors.name").exists())
                // sorted: "key: …" before "name: …", joined with "; " — pins the handler's
                // sort(), which is what makes this body reproducible.
                .andExpect(jsonPath("$.detail", matchesPattern("(?s)key: .*; name: .*")))
                .andExpect(jsonPath("$.detail", not(containsString(BOOT_GENERIC))));
    }

    // ========================================================== nested, cascaded constraint

    /**
     * A constraint on a {@code @Valid}-cascaded nested record reports its <strong>full
     * path</strong> ({@code delivery.preset}), and a message that already names its own
     * field is not stuttered back as {@code "delivery.preset: delivery.preset is …"}.
     *
     * <p>{@code delivery.preset}'s whole purpose IS its wording (HD-102, open question 5:
     * a client that echoes back a GET's {@code delivery} object must be told which field
     * it may not set). Under the old precedence that message existed in the source and
     * reached nobody.
     */
    @Test
    void aCascadedNestedConstraintReportsItsFullPathAndItsOwnWording() throws Exception {
        var ctx = newProject();
        var message = "delivery.preset is derived from board/releases/estimation and cannot be set";

        patchProject(ctx, "{\"delivery\":{\"board\":\"KANBAN\",\"preset\":\"KANBAN\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['delivery.preset']").value(message))
                // no "delivery.preset: delivery.preset …" stutter: the message names the field
                .andExpect(jsonPath("$.detail").value(message))
                .andExpect(jsonPath("$.detail", not(containsString(BOOT_GENERIC))));
    }

    // ================================================= the same shape on the other surfaces

    /**
     * The {@code @Order} fix is application-wide, so the contract is checked on the three
     * other surfaces that answer {@code @Valid @RequestBody} 400s — <strong>auth</strong>
     * (unauthenticated, and the first thing an integrator ever calls), <strong>admin</strong>
     * and the <strong>issue</strong> feature.
     *
     * <p>Auth and admin constraints carry no explicit messages, so only field paths are
     * asserted there; the label colour constraint has one, so its wording is pinned too.
     */
    @Test
    void authAdminAndIssueSurfacesAnswerTheSameShape() throws Exception {
        var ctx = newProject();

        // ---- auth: no token, no session, no membership — still the full contract ----
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\","
                                + "\"displayName\":\"Someone\",\"termsAccepted\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.detail", containsString("email")))
                .andExpect(jsonPath("$.detail", containsString("password")))
                .andExpect(jsonPath("$.detail", not(containsString(BOOT_GENERIC))));

        // ---- admin: workspace catalog upsert ----
        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/admin/statuses")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"color\":\"red\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.color").exists())
                .andExpect(jsonPath("$.errors.category").exists())   // @NotNull, omitted
                .andExpect(jsonPath("$.detail", containsString("category")))
                .andExpect(jsonPath("$.detail", not(containsString(BOOT_GENERIC))));

        // ---- issue feature: a label colour that is not a hex swatch ----
        // The wording comes from ColorFormat.MESSAGE, which the label DTOs now name instead of
        // carrying an inline copy — the same sentence the service belt and the 422 on the
        // admin-field path answer. It reads with a small stutter in the per-field map
        // ("color: Color must be ..."); that is the price of one sentence for one shape, and it
        // is asserted as a literal here so a silent rewording is a failing test rather than a
        // product that answers two sentences to one mistake again.
        postLabel(ctx, ctx.token(), "{\"name\":\"backend\",\"color\":\"teal\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.color").value("Color must be #RRGGBB or #RRGGBBAA"))
                .andExpect(jsonPath("$.detail",
                        containsString("color: Color must be #RRGGBB or #RRGGBBAA")))
                .andExpect(jsonPath("$.detail", not(containsString(BOOT_GENERIC))));
    }

    // ================================================================= the cap on error count

    /**
     * The list is capped at {@code MAX_REPORTED_ERRORS} (10) and the overflow is
     * <strong>counted</strong> in {@code detail}, so a caller is never left thinking the
     * ten shown were all of them. The cap exists because several admin upsert DTOs
     * validate an unbounded {@code List} — each element yields a distinct path
     * ({@code items[7].priorityId}) that the map cannot dedup, so without it one request
     * echoes back an arbitrary multiple of its own weight.
     *
     * <p>The probe: a priority-set upsert with a blank name and twelve items each missing
     * their {@code @NotNull priorityId} — 13 violations, 3 of which must be dropped.
     *
     * <p><strong>Asserted as shape, not membership.</strong> The sort is lexicographic on
     * the <em>rendered</em> line, so {@code items[10]} and {@code items[11]} sort ahead of
     * {@code items[1]} and {@code name} falls off the end — true, but an implementation
     * detail of decimal-string ordering that no client depends on. Pinning which ten
     * survive would make this test a hostage to that; pinning the count, the overflow
     * suffix and the agreement below is the actual contract.
     */
    @Test
    void theErrorListIsCappedAndTheOverflowIsCountedInDetail() throws Exception {
        var ctx = newProject();

        var problem = json.readTree(overflowingPrioritySet(ctx));
        var detail = problem.get("detail").asText();

        var overflow = OVERFLOW_SUFFIX.matcher(detail);
        assertThat(overflow.find())
                .as("detail must end with the overflow count, was: %s", detail)
                .isTrue();
        assertThat(Integer.parseInt(overflow.group(1)))
                .as("the dropped errors are counted, not silently hidden")
                .isPositive();
        assertThat(problem.get("errors").size())
                .as("the errors map is capped at MAX_REPORTED_ERRORS")
                .isEqualTo(10);
        assertThat(detail).doesNotContain(BOOT_GENERIC);
    }

    /**
     * <strong>The invariant worth a test of its own:</strong> {@code detail} and
     * {@code errors} are rendered from the <em>same</em> capped, ordered list, so they can
     * never disagree about which errors were reported or in what order.
     *
     * <p>Today that is true by construction (one {@code shown} list feeds both). It is
     * asserted anyway because the two halves are built by two separate stream pipelines a
     * few lines apart: a refactor that re-derives either one from {@code collected} — or
     * that caps only the string, or only the map — reintroduces a response whose prose
     * lists errors its machine-readable half omits. That divergence is invisible to every
     * other test in this class, each of which reads one half or the other.
     */
    @Test
    void detailAndTheErrorsMapReportTheSameCappedListInTheSameOrder() throws Exception {
        var ctx = newProject();

        var problem = json.readTree(overflowingPrioritySet(ctx));
        var detail = problem.get("detail").asText();

        // strip the "; … and N more" tail — everything before it is the rendered list
        var overflow = OVERFLOW_SUFFIX.matcher(detail);
        assertThat(overflow.find()).isTrue();
        var shown = detail.substring(0, overflow.start()).split("; ");

        // the same entries, rendered the same way, in the same order (no constraint
        // message here contains "; ", so the split is faithful)
        var fromMap = new ArrayList<String>();
        problem.get("errors").properties()
                .forEach(e -> fromMap.add(render(e.getKey(), e.getValue().asText())));

        assertThat(shown).as("detail lists exactly the capped entries").hasSize(10);
        assertThat(fromMap).as("detail and errors are the same list, in the same order")
                .containsExactly(shown);
    }

    // ============================================== class-level (cross-field) constraints

    /**
     * A class-level constraint has <em>no</em> field path, so it is not a field error:
     * reading only {@code getFieldErrors()} answers
     * {@code {"detail":"Validation failed","errors":{}}} and throws the actual reason away.
     * The handler folds global errors in under the empty-string key, and {@code render}
     * leaves them un-prefixed (every string starts with {@code ""}) so they read as prose
     * rather than as {@code ": start must be before end"}.
     *
     * <p><strong>Why a test-only DTO.</strong> Hamstrack has no cross-field constraint
     * today — which is the whole reason this needs pinning: the behaviour exists so the
     * <em>first</em> one anyone adds does not fail invisibly, and until then nothing else
     * exercises that branch. Adding a real constraint to a production DTO to make the test
     * possible would be the tail wagging the dog (it would change a live API's rules), so
     * the probe is a test-scoped record + controller registered by {@link CrossFieldConfig}
     * below and reachable only from this class.
     *
     * <p>Both kinds are sent at once: a field error on {@code start} and the global rule,
     * so the test also covers the two <em>coexisting</em> in one body — a handler that
     * handled globals by replacing the field map would pass a globals-only probe.
     */
    @Test
    void aClassLevelConstraintIsFoldedInUnderTheEmptyKey() throws Exception {
        var ctx = newProject();

        var body = mockMvc.perform(post(CROSS_FIELD_PATH)
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"start\":-5,\"end\":-9}"))
                .andExpect(status().isBadRequest())
                // the global message reaches detail bare — no ": …" stutter for the ""-key
                .andExpect(jsonPath("$.detail", containsString(CROSS_FIELD_MESSAGE)))
                .andExpect(jsonPath("$.detail", not(containsString(": " + CROSS_FIELD_MESSAGE))))
                // …and the coexisting field error is still reported
                .andExpect(jsonPath("$.errors.start").exists())
                .andExpect(jsonPath("$.detail", containsString("start:")))
                .andExpect(jsonPath("$.detail", not(containsString(BOOT_GENERIC))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // the "" key is awkward in JsonPath — read it off the tree instead
        var errors = json.readTree(body).get("errors");
        assertThat(errors.has(""))
                .as("global errors are bucketed under the empty key, body was: %s", body)
                .isTrue();
        assertThat(errors.get("").asText()).isEqualTo(CROSS_FIELD_MESSAGE);
    }

    // ================================================================== message language

    /**
     * Constraint messages are English whatever the caller asks for. Out of the box they
     * are <em>client-controlled</em>: {@code LocaleContextMessageInterpolator} resolves the
     * locale per call from the request's {@code Accept-Language} (Hibernate Validator ships
     * 28 bundles), falling back to the host locale — so the same 400 could answer in
     * Russian, and a header-less caller got whatever locale the container happened to run
     * in. {@code ValidationConfig} pins it; this test is the only thing standing between
     * that one bean and a silently locale-dependent API.
     *
     * <p>This is the one place default constraint text is asserted, and it is safe here
     * <em>because</em> of the pin — see the class note before copying the pattern.
     * {@code name} is two spaces, not empty, so only {@code @NotBlank} fails and
     * {@code @Size(min = 2)} does not: one violation, one message, nothing to race over.
     */
    @Test
    void constraintMessagesStayEnglishWhateverTheCallerAsksFor() throws Exception {
        var ctx = newProject();

        mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects")
                        .header("Authorization", "Bearer " + ctx.token())
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"key\":\"" + freshKey() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("must not be blank"))
                .andExpect(jsonPath("$.detail").value("name: must not be blank"))
                // and nothing localized leaked in anywhere else in the sentence
                .andExpect(jsonPath("$.detail", matchesPattern("\\p{ASCII}*")));
    }

    // ============================================================== the boundaries of the fix

    /**
     * Raising our advice to {@code HIGHEST_PRECEDENCE} must not make it swallow exceptions
     * it does not declare. A body that cannot be <em>read</em> at all
     * ({@code HttpMessageNotReadableException}) is still Boot's to answer: 400, no
     * {@code errors} map, because there were no field errors — nothing was ever bound.
     *
     * <p>This is the counterweight to the rest of the class. Without it, "make our handler
     * win" could quietly grow into "our handler answers everything", which is how a 500 or
     * a 415 ends up mislabelled as a validation failure.
     */
    @Test
    void anUnreadableBodyIsStillBootsToAnswer() throws Exception {
        var ctx = newProject();

        createProject(ctx, "{\"name\": not json at all")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    /**
     * A valid body still reaches the controller — the ordering change is about which advice
     * answers a failure, not about a new gate on the happy path. Cheap, but it is the one
     * assertion that would catch "the advice now intercepts everything".
     */
    @Test
    void aValidBodyIsUnaffected() throws Exception {
        var ctx = newProject();

        createProject(ctx, "{\"name\":\"Perfectly fine\",\"key\":\"" + freshKey() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Perfectly fine"));
    }

    // ==================================================================== plumbing

    /**
     * A priority-set upsert that fails 13 constraints — a blank {@code name} plus twelve
     * items each missing their {@code @NotNull priorityId}. Returns the raw body; the
     * upsert DTO's {@code items} list has no {@code @Size}, which is exactly the shape the
     * cap defends against.
     */
    private String overflowingPrioritySet(Ctx ctx) throws Exception {
        var items = IntStream.range(0, 12).mapToObj(i -> "{}").collect(Collectors.joining(","));
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/admin/priority-sets")
                        .header("Authorization", "Bearer " + ctx.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"items\":[" + items + "]}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** The handler's own rendering rule, restated as the contract it is. */
    private static String render(String field, String message) {
        return message.startsWith(field) ? message : field + ": " + message;
    }

    private ResultActions createProject(Ctx ctx, String bodyJson) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ctx.wsId() + "/projects")
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    private ResultActions patchProject(Ctx ctx, String bodyJson) throws Exception {
        return mockMvc.perform(patch("/api/workspaces/" + ctx.wsId() + "/projects/" + ctx.projectId())
                .header("Authorization", "Bearer " + ctx.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson));
    }

    /** A project key that cannot collide with a concurrently-running test's. */
    private static String freshKey() {
        return "V" + Math.abs(UUID.randomUUID().hashCode() % 100000);
    }

    // ======================================= test-only surface for the cross-field case

    /**
     * Registers the cross-field probe controller. Nested {@code @TestConfiguration} classes
     * are picked up automatically by {@code @SpringBootTest}; the cost is that this class
     * no longer shares the default context with its property-set neighbours, which is the
     * price of not putting a synthetic constraint on a production DTO.
     */
    @TestConfiguration
    static class CrossFieldConfig {
        @Bean
        CrossFieldController crossFieldController() {
            return new CrossFieldController();
        }
    }

    /**
     * Under {@code /api/**}, so it is authenticated like any real endpoint and the request
     * travels the same filter chain, argument resolver and advice ordering as production
     * traffic. The name space is deliberately un-guessable-adjacent ({@code __test}) and the
     * bean only exists in this class's context.
     */
    @RestController
    static class CrossFieldController {
        @PostMapping(CROSS_FIELD_PATH)
        String post(@Valid @RequestBody Window body) {
            return "ok";
        }
    }

    /** {@code start} carries a field constraint so both kinds fail in one request. */
    @StartBeforeEnd
    record Window(@Positive Integer start, Integer end) {}

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = StartBeforeEndValidator.class)
    @interface StartBeforeEnd {
        String message() default CROSS_FIELD_MESSAGE;
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class StartBeforeEndValidator implements ConstraintValidator<StartBeforeEnd, Window> {
        @Override
        public boolean isValid(Window value, ConstraintValidatorContext context) {
            return value == null || value.start() == null || value.end() == null
                    || value.start() < value.end();
        }
    }
}
