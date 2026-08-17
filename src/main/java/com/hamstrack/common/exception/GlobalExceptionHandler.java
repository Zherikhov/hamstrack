package com.hamstrack.common.exception;

import com.hamstrack.common.ratelimit.RateLimitedException;
import com.hamstrack.issue.exception.LabelNameConflictException;
import com.hamstrack.search.HqlSemanticException;
import com.hamstrack.search.parser.HqlParseException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <strong>Ordered ahead of Boot's own advice on purpose.</strong> With
 * {@code spring.mvc.problemdetails.enabled=true}, Boot registers
 * {@code ProblemDetailsExceptionHandler} at order 0; an unordered
 * {@code @RestControllerAdvice} sits at {@code LOWEST_PRECEDENCE} and therefore loses
 * every exception both of them declare. That silently made {@link #handleValidation}
 * dead code: every {@code @Valid @RequestBody} failure in the app answered Boot's
 * generic {@code {"detail":"Invalid request content."}} and the {@code errors} map
 * below never reached a client at all. Raising the precedence is what makes our
 * handlers actually run.
 *
 * <p><strong>What else the precedence change moved.</strong> Boot's advice extends
 * {@code ResponseEntityExceptionHandler}, which declares 20 exceptions — and
 * {@link MaxUploadSizeExceededException} is one of them, so {@link #handleMaxUploadSize}
 * was dead in exactly the same way and now wins too. Benign: the status is 413 either
 * way, only {@code detail} changes from Spring's {@code "Maximum upload size exceeded"}
 * to our {@code "File is too large"}. Everything else declared here is a Hamstrack
 * exception type that Boot's advice knows nothing about. <strong>Before adding a handler,
 * check that list</strong> ({@code ResponseEntityExceptionHandler}'s class-level
 * {@code @ExceptionHandler}) — anything on it now answers from here instead of Boot, and
 * that is a response-body change, not a no-op.
 *
 * <p>Ordered at {@code HIGHEST_PRECEDENCE + 100} rather than the bare minimum: it beats
 * Boot's order-0 advice just as reliably while leaving room for an advice that ever needs
 * to sit in front of this one.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> handleAppException(AppException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    // More specific than the AppException handler — adds the Retry-After hint
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem);
    }

    // More specific than the AppException handler — publishes the winning label's id
    // as the `existingId` ProblemDetail extension so the label picker can recover from
    // a duplicate-name 409 in one round-trip (HD-30 §4.3).
    @ExceptionHandler(LabelNameConflictException.class)
    public ResponseEntity<ProblemDetail> handleLabelNameConflict(LabelNameConflictException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        if (ex.getExistingId() != null) {
            problem.setProperty("existingId", ex.getExistingId().toString());
        }
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    // HQL parse error (Advanced Search §7.1): 422 with a highlight span. The custom
    // ProblemDetail properties (position/length/token/errorType) drive the SPA's
    // inline underline. errorType is always "PARSE_ERROR".
    @ExceptionHandler(HqlParseException.class)
    public ResponseEntity<ProblemDetail> handleHqlParse(HqlParseException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        problem.setProperty("position", ex.getPosition());
        problem.setProperty("length", ex.getLength());
        if (ex.getToken() != null) {
            problem.setProperty("token", ex.getToken());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    // HQL semantic error (Advanced Search §7.2): 422, field-anchored. errorType is
    // always "SEMANTIC_ERROR"; field/position are included when known.
    @ExceptionHandler(HqlSemanticException.class)
    public ResponseEntity<ProblemDetail> handleHqlSemantic(HqlSemanticException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        if (ex.getField() != null) {
            problem.setProperty("field", ex.getField());
        }
        if (ex.getPosition() >= 0) {
            problem.setProperty("position", ex.getPosition());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "File is too large");
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem);
    }

    /**
     * How many failed fields a 400 renders, in {@code detail} and in {@code errors} alike.
     * A validated collection is only as bounded as its {@code @Size} — several admin
     * upsert DTOs currently have none, so an authenticated caller could post a very large
     * items array and have every element echoed back (each element yields a distinct path
     * like {@code items[7].fieldId}, which the map cannot dedup) for several times the
     * request's weight. Ten is far more than a human form needs and the overflow is still
     * counted in {@code detail}, so nothing is silently hidden.
     */
    private static final int MAX_REPORTED_ERRORS = 10;

    /**
     * Bucket for class-level / cross-field errors, which have no field path of their own.
     * Empty on purpose: {@code render} prefixes the path only when the message doesn't
     * already start with it, and every string starts with "", so a global message is
     * rendered bare instead of as {@code ": …"}.
     */
    private static final String GLOBAL_ERROR_KEY = "";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        var binding = ex.getBindingResult();
        // Coalesce a null default message (a constraint without one) to "" — a null value
        // would NPE the map below, turning a 400 into a 500. First failure per path wins.
        Map<String, String> collected = new LinkedHashMap<>();
        binding.getFieldErrors()
                .forEach(fe -> collected.putIfAbsent(fe.getField(), messageOf(fe)));
        // Class-level constraints are NOT field errors: reading only getFieldErrors() would
        // answer {"detail":"Validation failed","errors":{}} and discard the actual reason,
        // so the first cross-field rule anyone adds would fail invisibly.
        binding.getGlobalErrors()
                .forEach(oe -> collected.putIfAbsent(GLOBAL_ERROR_KEY, messageOf(oe)));

        // Sorted for a deterministic body, then capped — detail and errors report the same
        // entries in the same order, so the two halves of the response never disagree.
        List<Map.Entry<String, String>> ordered = collected.entrySet().stream()
                .sorted(Comparator.comparing(GlobalExceptionHandler::render))
                .toList();
        List<Map.Entry<String, String>> shown = ordered.size() > MAX_REPORTED_ERRORS
                ? ordered.subList(0, MAX_REPORTED_ERRORS)
                : ordered;

        // The constraint message has to reach `detail`, not only the `errors` map: the
        // SPA's request() renders `detail`, and a rule whose whole point IS its wording
        // (e.g. delivery.preset's @Null) would otherwise surface as a bare "Validation
        // failed".
        String detail = shown.stream()
                .map(GlobalExceptionHandler::render)
                .collect(Collectors.joining("; "));
        if (ordered.size() > shown.size()) {
            detail += "; … and " + (ordered.size() - shown.size()) + " more";
        }
        Map<String, String> errors = shown.stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                detail.isBlank() ? "Validation failed" : detail);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    private static String messageOf(ObjectError error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "";
    }

    /**
     * The field path is prefixed unless the message already names it, so full-sentence
     * messages (and global errors, whose path is "") aren't stuttered back.
     */
    private static String render(Map.Entry<String, String> error) {
        return error.getValue().startsWith(error.getKey())
                ? error.getValue()
                : error.getKey() + ": " + error.getValue();
    }
}
