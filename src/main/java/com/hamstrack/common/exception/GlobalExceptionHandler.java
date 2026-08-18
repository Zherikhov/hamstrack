package com.hamstrack.common.exception;

import com.hamstrack.common.ratelimit.RateLimitedException;
import com.hamstrack.issue.exception.LabelNameConflictException;
import com.hamstrack.project.exception.StrandedProjectsException;
import com.hamstrack.search.HqlSemanticException;
import com.hamstrack.search.parser.HqlParseException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
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
@Slf4j
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

    /**
     * More specific than the {@code AppException} handler — publishes the full list of
     * projects the refused removal would have stranded as the {@code projects} extension
     * (HD-136).
     *
     * <p>The list is deliberately not folded into {@code detail} alone: {@code detail} is
     * a sentence for a human and is capped at three names, while a client that wants to
     * render "fix these" as links needs ids. Naming them discloses nothing — every project
     * here belongs to the workspace the caller is already administering and is already
     * listable via {@code GET /api/workspaces/{ws}/projects}.
     *
     * <p>{@code errorType} is the other half (review round 4): the exception has <em>two</em>
     * variants that share this status and this extension and demand opposite client
     * behaviour — one is fixed by retrying with {@code adoptStrandedProjects=true}, the
     * other fails identically on that retry. Same extension name and stable-string shape as
     * {@link #handleHqlParse}, so there is one convention for "which failure is this",
     * not two.
     */
    @ExceptionHandler(StrandedProjectsException.class)
    public ResponseEntity<ProblemDetail> handleStrandedProjects(StrandedProjectsException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        problem.setProperty("projects", ex.getProjects());
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
     * What a client sees when it loses an optimistic-lock race. Deliberately the same
     * sentence {@code IssueService.update}'s pre-check uses, minus the subject noun: this
     * handler cannot know <em>what</em> was modified (and naming the entity class here would
     * leak internals onto the wire), but the caller's move is identical either way.
     */
    static final String OPTIMISTIC_LOCK_DETAIL =
            "This item was modified by someone else — refresh and retry";

    /**
     * <strong>Losing an optimistic-lock race is a 409, not a crash.</strong>
     *
     * <p>Until this existed, only the <em>pre-check</em> in {@code IssueService.update} —
     * "does the client's {@code version} match the row I just loaded?" — produced a 409.
     * That check cannot cover the case it is named for. It compares against the entity
     * loaded in <em>this</em> transaction, so a competing commit that lands <em>after</em>
     * the read still slips past it, and a client that omits {@code version} skips it
     * entirely. In both cases the conflict surfaces later, at flush, as Hibernate's
     * {@code ObjectOptimisticLockingFailureException} — which no handler here declared, so
     * it fell through to a bare <strong>500</strong>. The write was correctly rejected, so
     * this was never a data or authorization problem; it just told the SPA to render a crash
     * instead of "refresh and retry", for the one failure mode whose entire user-facing
     * contract is "retry".
     *
     * <p>HD-132 made that window materially wider — removing a member bumps {@code @Version}
     * on every issue it unassigns (see {@code IssueRepository.unassignAllInWorkspace}) — but
     * the gap is <strong>app-wide and predates it</strong>: {@code Issue} and {@code Role}
     * both carry {@code @Version}, and every loser on either 500'd.
     *
     * <p>Catches {@link OptimisticLockingFailureException}, the Spring DAO superclass, so
     * both the ORM subclass and the plain-JDBC variant land here rather than only whichever
     * one today's persistence path happens to raise.
     *
     * <p><strong>Not on Boot's list.</strong> Per the class note above, adding a handler for
     * anything {@code ResponseEntityExceptionHandler} declares changes that exception's body
     * app-wide. This one is a {@code org.springframework.dao} exception, which that class
     * knows nothing about — so the only behaviour that changes is 500 → 409.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, OPTIMISTIC_LOCK_DETAIL);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * What a client sees when it loses a <em>row-lock</em> race. Deliberately does not say
     * "deadlock": the caller's move is the same whether they lost a deadlock, a lock
     * timeout or a serialisation failure, and naming the mechanism would leak an internal
     * detail into a message a human reads.
     */
    static final String LOCK_CONTENTION_DETAIL =
            "Someone else is changing this right now — try again in a moment";

    /** Long enough for the winning transaction to commit, short enough to feel instant. */
    private static final int LOCK_RETRY_AFTER_SECONDS = 1;

    /**
     * The <em>pessimistic</em> twin, and the other half of a promise HD-136 made in a
     * javadoc: the membership paths' locking reads {@code ORDER BY} their rows, so two
     * overlapping removals <strong>queue instead of interleaving</strong>. The case this
     * handler will therefore actually see is the end of that queue — a
     * {@code lock_timeout}, the bound {@code LockTimeout} puts on a wait PostgreSQL would
     * otherwise hold for ever. A deadlock is the rare branch and is equally safe, since
     * Postgres rolls one side back and nothing is half done. Either way the argument only
     * holds if the victim is told to retry: until this handler existed the loser of a lock
     * timeout (or of a deadlock) surfaced as an unhandled <strong>500</strong> — the
     * database did exactly the right thing and the API reported a crash.
     *
     * <p>Catches {@link PessimisticLockingFailureException}, the Spring DAO superclass, so
     * {@code CannotAcquireLockException} (deadlock / lock timeout) and
     * {@code PessimisticLockingFailureException} proper both land here rather than only
     * whichever one today's driver happens to raise. 409 with {@code Retry-After}, in the
     * shape {@link #handleRateLimited} already uses: the request was valid and will very
     * likely succeed on its own the second time — this is the one failure whose entire
     * user-facing contract is "try again".
     *
     * <p><strong>It logs, because turning a 500 into a clean 409 also removed the only
     * signal an operator had.</strong> A stack trace is a poor error response and a good
     * alarm; a deadlock storm on the membership path — the one place in the product that
     * takes row locks across two tables — would otherwise be completely silent server-side,
     * visible only as clients retrying. WARN rather than ERROR: one lost race is normal
     * contention, not a fault, and the exception class plus the request URI are what tell an
     * operator which lock and which endpoint. The client's message stays mechanism-free.
     *
     * <p>Not on Boot's list, per the class note: a {@code org.springframework.dao}
     * exception, so the only behaviour that changes is 500 → 409.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handlePessimisticLock(PessimisticLockingFailureException ex,
                                                               HttpServletRequest request) {
        log.warn("Lock contention on {} {}: {} — answering 409 with Retry-After {}s",
                request.getMethod(), request.getRequestURI(), ex.getClass().getSimpleName(),
                LOCK_RETRY_AFTER_SECONDS, ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, LOCK_CONTENTION_DETAIL);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(LOCK_RETRY_AFTER_SECONDS))
                .body(problem);
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
