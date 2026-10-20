package com.hamstrack.common.exception;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * <strong>The one writer of a problem document for the doors that have no message converter behind
 * them</strong> (HD-305).
 *
 * <p>Three pre-MVC doors answer with an RFC 9457 body by hand — the authentication entry point, the
 * auth rate-limit filter and the database-busy refusal — and before this class existed each of them
 * decided for itself what to call that body and how to produce it. They did not agree, and the
 * disagreement was not only cosmetic:
 *
 * <ul>
 *   <li>the 401 was labelled {@code application/json} while every other refusal in the product is
 *       {@code application/problem+json} — the one refusal a browser client parses most often;</li>
 *   <li>the 401 was serialised through a <em>bare</em> Jackson 2 {@code ObjectMapper} constructed in
 *       the entry point, which is not the mapper MVC renders with, so the document had a different
 *       <strong>shape</strong> as well as a different label: {@code {"type":null,…,"instance":null,
 *       "properties":null}}, three members no other refusal emits. Spring's own
 *       {@code ProblemDetailJacksonMixin} javadoc says why — it applies "unless an
 *       {@code ObjectMapper} is instantiated directly";</li>
 *   <li>the 429 was a hand-built JSON string written through {@link HttpServletResponse#getWriter()},
 *       which encodes with the response's charset. Nothing had set one, so the default ISO-8859-1
 *       went on the wire and the em dash in the only sentence that body carries arrived as a
 *       literal {@code ?}. It also interpolated the exception's message into JSON with no escaping,
 *       which was safe only for as long as every throw site that can reach that filter happens to
 *       pass no text of its own.</li>
 * </ul>
 *
 * <p><strong>The mapper is the one MVC uses, injected and never constructed.</strong> Boot 4.1's
 * {@code JacksonAutoConfiguration} exposes a {@code @Primary} Jackson 3 {@link JsonMapper} bean and
 * registers {@code ProblemDetailJacksonMixin} into its builder
 * ({@code JacksonAutoConfiguration.JsonProblemDetailsConfiguration}), which is what unwraps
 * {@code ProblemDetail}'s extension properties to top level and drops the empty members. So a body
 * written here is byte-identical to what the same {@link ProblemDetail} would produce through an
 * {@code @ExceptionHandler} — the difference MVC still adds is {@code instance}, which
 * {@code HttpEntityMethodProcessor} fills with the request URI on its way out.
 *
 * <p>What the old comment in {@code AuthRateLimitFilter} said — "Boot 4 doesn't expose an
 * ObjectMapper bean with plain jackson-databind on the classpath" — was half of a true sentence and
 * the half that was true pointed the wrong way: there is indeed no Jackson <strong>2</strong>
 * {@code ObjectMapper} bean (Boot 4 autoconfigures Jackson 3), but the Jackson 3 mapper that renders
 * every other refusal in this product <em>is</em> a bean, in every profile, and a filter can be
 * handed it like any other collaborator. Its sibling three files away was constructing a mapper of
 * its own at the same time, so the two writers' stated reasons contradicted each other.
 *
 * <p><strong>Bytes, not a {@link HttpServletResponse#getWriter() writer}.</strong> A
 * {@code PrintWriter} encodes with the response's charset, which defaults to ISO-8859-1 — the trap
 * the 429 was in. Jackson writes UTF-8, the charset is declared, and {@code Content-Length} is set
 * from the array so the refusal is framed rather than chunked.
 */
@Component
public class ProblemJson {

    private final JsonMapper jsonMapper;

    public ProblemJson(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * The document as the wire carries it. Exposed separately from {@link #write} so a seal can
     * compare these bytes with what MVC renders without needing a response to write them onto.
     */
    public byte[] render(ProblemDetail problem) {
        return jsonMapper.writeValueAsBytes(problem);
    }

    /**
     * Status, content type, charset, length and body — the five decisions that have to be made
     * together, made in one place.
     *
     * <p>The status comes from the document rather than from the caller, so a refusal cannot answer
     * one status in its headers and claim another in its body. Everything else about the response is
     * the caller's: a {@code Retry-After}, the security header block, and whether the response had
     * to be {@link HttpServletResponse#reset()} first are decisions of the door, not of the body.
     */
    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        byte[] body = render(problem);
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
