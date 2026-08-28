package com.hamstrack.common.exception;

import com.hamstrack.issue.LabelTestBase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>ADR-0019, made real: every 400 raised by a declared constraint carries the same
 * {@code {detail, errors}} body, whichever door the constraint is written on.</strong>
 * (HD-163/HD-214 AC-8 and AC-11.)
 *
 * <p>Three exception types reach that one shape now — {@code MethodArgumentNotValidException} from a
 * {@code @Valid @RequestBody}, {@code HandlerMethodValidationException} from a constraint on a
 * request parameter, and {@code jakarta.validation.ConstraintViolationException} from the backstop.
 * The rendering is <em>shared, not copied</em>, and this file is what makes that enforceable: two
 * copies of a renderer drift, and the drift is invisible because both copies produce a 400. The
 * strongest form of the claim is the one asserted below — for equivalent inputs the two bodies are
 * <strong>equal</strong>, not merely similar.
 *
 * <p><strong>Two of the three paths are unreachable through the production API, on purpose, and are
 * forced here rather than left unexercised.</strong> The {@code @Validated} backstop is dead code
 * while ADR-0018 holds (no web bean carries the annotation, no {@code @Entity} carries Bean
 * Validation annotations, nothing calls a {@code Validator} by hand); the return-value branch of
 * {@code handleParameterValidation} is dead for the same reason. "Unreachable" is a claim about the
 * current call graph, and this codebase's whole history is claims about the call graph outliving
 * their truth — so both are driven from test-scoped beans that exist only in this class's context.
 *
 * <p>The probes live under {@code /api/__test/**}, so they travel the same filter chain, argument
 * resolvers and advice ordering as production traffic. None of them carries {@code @Validated} on a
 * controller: the backstop is forced through a {@code @Validated} <em>collaborator</em>, which is
 * what a future accident would most likely look like anyway, and which keeps the probe from being
 * the very thing {@link com.hamstrack.common.validation.WebBeanValidatedRuleTest} forbids.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class DeclaredConstraintRefusalTest extends LabelTestBase {

    static final String MANY_PARAMS = "/api/__test/constraints/many-params";
    static final String MANY_FIELDS = "/api/__test/constraints/many-fields";
    static final String BACKSTOP = "/api/__test/constraints/backstop";
    static final String CONSTRAINED_RETURN = "/api/__test/constraints/constrained-return";

    /** More than {@code MAX_REPORTED_ERRORS}, so the cap and the overflow line are both exercised. */
    private static final int PROBE_FIELDS = 12;

    /** The tail the shared renderer appends when it truncates; the group is the dropped count. */
    private static final Pattern OVERFLOW_SUFFIX = Pattern.compile("; … and (\\d+) more$");

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        if (token == null) {
            token = newProject().token();
        }
    }

    // ============================================================ AC-8: one shape, two doors

    /**
     * <strong>The parity assertion, in its strongest available form.</strong> Twelve parameters each
     * violating {@code @Size(max = 1)}, and twelve body fields with the same names violating the same
     * constraint, produce the <em>same body</em> — same {@code detail} string, same {@code errors}
     * map, same order, same cap, same overflow line.
     *
     * <p>Equality rather than a list of similarities is the point. Each individual property (sorted,
     * capped at ten, {@code "; "}-joined, {@code "field: message"} unless the message already names
     * the field, an {@code "; … and N more"} tail) could be re-implemented correctly in a second
     * copy and then drift on the next edit to either one. A body-for-body comparison cannot be
     * satisfied by two copies that have diverged in any way at all, which is precisely the guarantee
     * "extract one renderer and have both handlers call it" was supposed to buy.
     *
     * <p>{@code instance} is normalised away: problem+json echoes the request URI, and the two
     * probes are necessarily at different URIs.
     */
    @Test
    void aParameterRefusalAndABodyRefusalAreTheSameResponse() throws Exception {
        var fromParameters = refusal(get(MANY_PARAMS + "?" + probeQuery()));
        var fromBody = refusal(post(MANY_FIELDS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(probeBody()));

        assertThat(withoutInstance(fromParameters))
                .as("""
                        A PARAMETER REFUSAL AND A BODY REFUSAL HAVE DIVERGED.

                        These two requests fail the same twelve constraints under the same twelve \
                        names, so the only thing that can differ is which handler rendered them — \
                        and both are supposed to call GlobalExceptionHandler.validationRefusal. A \
                        difference here means the rendering has been copied rather than shared, and \
                        from now on every fix to one of the copies is a silent divergence in the \
                        other. Both stay 400, so nothing else in the suite will notice.

                        parameters: %s
                        body:       %s""", fromParameters, fromBody)
                .isEqualTo(withoutInstance(fromBody));
    }

    /**
     * …and the shared contract itself, asserted on the parameter path, because equality above would
     * also be satisfied by two identically <em>broken</em> renderings. The cap, the overflow count
     * and the agreement between {@code detail} and {@code errors} are the four bullets the API docs
     * describe, now claimed for parameter refusals too.
     */
    @Test
    void theParameterRefusalIsCappedCountedAndSelfConsistent() throws Exception {
        var problem = json.readTree(refusal(get(MANY_PARAMS + "?" + probeQuery())));
        var detail = problem.get("detail").asText();

        var overflow = OVERFLOW_SUFFIX.matcher(detail);
        assertThat(overflow.find())
                .as("detail must end with the overflow count so a caller is never left thinking the "
                    + "ten shown were all of them; was: %s", detail)
                .isTrue();
        assertThat(Integer.parseInt(overflow.group(1)))
                .as("the dropped parameter errors are counted, not silently hidden")
                .isEqualTo(PROBE_FIELDS - 10);
        assertThat(problem.get("errors").size())
                .as("the errors map is capped at MAX_REPORTED_ERRORS on the parameter path too")
                .isEqualTo(10);

        // detail and errors are rendered from the same capped, ordered list, so they can never
        // disagree about which errors were reported or in what order.
        var shown = detail.substring(0, overflow.start()).split("; ");
        var fromMap = new ArrayList<String>();
        problem.get("errors").properties()
                .forEach(e -> fromMap.add(render(e.getKey(), e.getValue().asText())));
        assertThat(fromMap)
                .as("detail and errors must be the same list in the same order")
                .containsExactly(shown);
    }

    // ============================================================ AC-11: the backstop

    /**
     * <strong>The backstop, forced.</strong> A {@code @Validated} collaborator raises
     * {@code jakarta.validation.ConstraintViolationException} from inside the handler — the shape a
     * future accident takes — and it must answer 400 in the shared body, keyed by the <em>last node</em>
     * of the violation's property path, so a client is told {@code value} rather than
     * {@code check.value}: the parameter it sent, not the method it happened to reach.
     *
     * <p>It answers a clean 400 <em>and</em> logs at ERROR, and the second half is not decoration.
     * Nothing in the tree should be able to raise this type, so an occurrence is a fact about the
     * codebase rather than about the request; a silent 400 would delete the only signal an operator
     * gets that a web bean has re-acquired {@code @Validated} or that an entity has grown Bean
     * Validation annotations — the second of which would be a SERVER fault reported as a client
     * error.
     */
    @Test
    void aBeanValidationEscapeAnswers400InTheSharedShape() throws Exception {
        mockMvc.perform(get(BACKSTOP).param("value", "far too long")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                // keyed by the LAST node of the property path — `value`, not `check.value`
                .andExpect(jsonPath("$.errors.value").exists())
                .andExpect(jsonPath("$.errors['check.value']").doesNotExist())
                .andExpect(jsonPath("$.detail", containsString("value:")))
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * <strong>The simple-name collision, asserted rather than trusted to a comment.</strong>
     * {@code GlobalExceptionHandler} binds two unrelated types that share the name
     * {@code ConstraintViolationException}: Hibernate's, on the data-integrity handler, and
     * Jakarta's, on the backstop. A bare {@code import} of either one silently rebinds the other
     * handler to the wrong class — no compile error, no failing happy path, and the data-integrity
     * translation (a 409 that becomes a 500) breaks in a way only a constraint violation in
     * production would reveal.
     *
     * <p>Both classes' javadoc records the trap from its own side. This is the assertion that makes
     * the record enforceable: a one-character mistake fails the build.
     */
    @Test
    void theTwoConstraintViolationExceptionsStayBoundToTheirOwnHandlers() {
        var bindings = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ExceptionHandler.class))
                .flatMap(m -> Arrays.stream(m.getAnnotation(ExceptionHandler.class).value())
                        .map(type -> m.getName() + " -> " + type.getName()))
                .toList();

        assertThat(bindings)
                .as("""
                        The Bean Validation backstop must bind jakarta.validation's type. If this is \
                        missing, either the handler was removed or its @ExceptionHandler argument \
                        now names Hibernate's same-named class — which would ALSO silently take the \
                        data-integrity handler's exception away from it.""")
                .contains("handleBeanValidation -> jakarta.validation.ConstraintViolationException");
        assertThat(bindings)
                .as("""
                        …and the data-integrity handler must still bind Hibernate's, unchanged. \
                        These two types are unrelated, carry different information (only Hibernate's \
                        has a SQLSTATE) and mean opposite things about whose fault the request was. \
                        Keep BOTH references fully qualified in GlobalExceptionHandler.""")
                .contains("handleDataIntegrityViolation -> org.hibernate.exception.ConstraintViolationException");
    }

    // ============================================================ the return-value branch

    /**
     * <strong>The 500 branch of {@code handleParameterValidation}, forced.</strong>
     * {@code HandlerMethodValidationException} does not always mean 400: Spring's
     * {@code initHttpStatus} answers 500 when the violation is on the handler's <em>return value</em>,
     * and the handler honours both branches rather than flattening them to a 400.
     *
     * <p>Flattening would be worse than a wrong number. A return-value violation is the server
     * failing its own contract — nothing about it is client-fixable — so a 400 would blame the caller
     * for a fault they cannot act on, and the constraint message would describe data the caller never
     * sent and may not be entitled to see. Hence the two assertions here: the status is 500,
     * <strong>and</strong> the body carries none of the constraint text. The explanation goes to the
     * log with the throwable, where an operator can act on it.
     */
    @Test
    void aConstrainedReturnValueIs500AndSaysNothingAboutTheConstraint() throws Exception {
        mockMvc.perform(get(CONSTRAINED_RETURN).header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail", not(containsString("size must be"))))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    // ------------------------------------------------------------------ plumbing

    private String refusal(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r)
            throws Exception {
        return mockMvc.perform(r.header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Drops the {@code instance} member, which is the request URI echoed back verbatim. */
    private static String withoutInstance(String body) {
        return body.replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    /** The shared renderer's own prefix rule, restated as the contract it is. */
    private static String render(String field, String message) {
        return message.startsWith(field) ? message : field + ": " + message;
    }

    /** {@code p01=xx&p02=xx&…} — every parameter two characters long, every bound one. */
    private static String probeQuery() {
        return IntStream.rangeClosed(1, PROBE_FIELDS)
                .mapToObj(i -> String.format("p%02d=xx", i))
                .collect(Collectors.joining("&"));
    }

    private static String probeBody() {
        return IntStream.rangeClosed(1, PROBE_FIELDS)
                .mapToObj(i -> String.format("\"p%02d\":\"xx\"", i))
                .collect(Collectors.joining(",", "{", "}"));
    }

    // ======================================= test-only surfaces for the three forced paths

    /**
     * Registers the probes. Nested {@code @TestConfiguration} classes are picked up automatically by
     * {@code @SpringBootTest}; the cost is a context of this class's own, which is the price of not
     * putting a synthetic constraint — or, worse, a {@code @Validated} — on a production bean.
     */
    @TestConfiguration
    static class ConstraintProbeConfig {
        @Bean
        ValidatedCollaborator validatedCollaborator() {
            return new ValidatedCollaborator();
        }

        @Bean
        ConstraintProbeController constraintProbeController(ValidatedCollaborator collaborator) {
            return new ConstraintProbeController(collaborator);
        }
    }

    /**
     * <strong>The one bean in this file that carries {@code @Validated}</strong>, and it is not a
     * controller — Spring MVC never dispatches to it, so it is outside ADR-0018's prohibition and
     * outside the sweep that enforces it. It is also the realistic shape of the accident the backstop
     * exists for: a service acquires the annotation, a parameter constraint on it starts raising
     * {@code jakarta.validation.ConstraintViolationException} from inside a handler, and without the
     * backstop that is a 500.
     */
    @Validated
    public static class ValidatedCollaborator {
        public String check(@Size(max = 5) String value) {
            return value;
        }
    }

    @RestController
    static class ConstraintProbeController {

        private final ValidatedCollaborator collaborator;

        ConstraintProbeController(ValidatedCollaborator collaborator) {
            this.collaborator = collaborator;
        }

        /**
         * Twelve constrained parameters, so one request produces more violations than the renderer
         * will report — the only way to exercise the cap and the overflow line on the parameter
         * path, which no production endpoint can currently do (the most-constrained one has two).
         */
        @GetMapping(MANY_PARAMS)
        String manyParams(@RequestParam @Size(max = 1) String p01,
                          @RequestParam @Size(max = 1) String p02,
                          @RequestParam @Size(max = 1) String p03,
                          @RequestParam @Size(max = 1) String p04,
                          @RequestParam @Size(max = 1) String p05,
                          @RequestParam @Size(max = 1) String p06,
                          @RequestParam @Size(max = 1) String p07,
                          @RequestParam @Size(max = 1) String p08,
                          @RequestParam @Size(max = 1) String p09,
                          @RequestParam @Size(max = 1) String p10,
                          @RequestParam @Size(max = 1) String p11,
                          @RequestParam @Size(max = 1) String p12) {
            return "ok";
        }

        /** The body twin: same names, same constraint, the other exception type. */
        @PostMapping(MANY_FIELDS)
        String manyFields(@Valid @RequestBody Probe body) {
            return "ok";
        }

        @GetMapping(BACKSTOP)
        String backstop(@RequestParam String value) {
            return collaborator.check(value);
        }

        /**
         * A constraint on the RETURN VALUE, which this controller then violates. Spring MVC's own
         * method validation performs return-value validation when the bean does not carry
         * {@code @Validated} — so this reaches {@code handleParameterValidation} with
         * {@code isForReturnValue()} true, which is the branch no production handler can reach.
         */
        @GetMapping(CONSTRAINED_RETURN)
        @Size(max = 2)
        String constrainedReturn() {
            return "far too long to be two characters";
        }
    }

    record Probe(@Size(max = 1) String p01, @Size(max = 1) String p02, @Size(max = 1) String p03,
                 @Size(max = 1) String p04, @Size(max = 1) String p05, @Size(max = 1) String p06,
                 @Size(max = 1) String p07, @Size(max = 1) String p08, @Size(max = 1) String p09,
                 @Size(max = 1) String p10, @Size(max = 1) String p11, @Size(max = 1) String p12) {}
}
