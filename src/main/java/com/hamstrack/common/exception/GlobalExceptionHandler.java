package com.hamstrack.common.exception;

import com.hamstrack.common.config.DatabaseTimeoutConsistency;
import com.hamstrack.common.config.StatementTimeoutProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.ratelimit.ConcurrencyLimitedException;
import com.hamstrack.common.ratelimit.RateLimitedException;
import com.hamstrack.issue.exception.LabelNameConflictException;
import com.hamstrack.project.exception.StrandedProjectsException;
import com.hamstrack.workspace.exception.DuplicateInviteException;
import com.hamstrack.workspace.exception.InviteAlreadyAcceptedException;
import com.hamstrack.workspace.exception.ReactivatedProjectDefaultsException;
import com.hamstrack.workspace.exception.RoleInUseException;
import com.hamstrack.workspace.exception.RoleLimitReachedException;
import com.hamstrack.workspace.exception.SelfHeldRoleException;
import com.hamstrack.workspace.exception.StorageQuotaExceededException;
import com.hamstrack.search.HqlSemanticException;
import com.hamstrack.search.parser.HqlParseException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerMapping;

import java.sql.SQLException;
import java.time.DateTimeException;
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
 * {@code ResponseEntityExceptionHandler}, which declares 20 exceptions, and this class
 * takes over <em>every one of them it also declares</em> — so a handler added here for a
 * type on that list is an <strong>app-wide response-body change</strong>, never a
 * search-local or upload-local one. Two such handlers exist today and both are deliberate:
 * {@link #handleMaxUploadSize} ({@link MaxUploadSizeExceededException} — status 413 either
 * way, only {@code detail} changes from Spring's {@code "Maximum upload size exceeded"} to
 * our {@code "File is too large"}) and {@link #handleParameterValidation}
 * ({@code HandlerMethodValidationException} — the status never diverges from Boot's, on
 * either of Spring's two branches: 400 for an argument violation, 500 for a return-value
 * one, which {@link #handleParameterValidation} honours explicitly. But every parameter
 * refusal in the app gains a {@code detail} naming the parameter and an {@code errors} map
 * where Boot rendered a bare {@code "Validation failure"}). Everything else declared here
 * is a Hamstrack exception type, or one Boot's advice knows nothing about.
 * <strong>Before adding a handler, check that list</strong>
 * ({@code ResponseEntityExceptionHandler}'s class-level {@code @ExceptionHandler}) — and
 * note that this paragraph is a count, so it goes stale one handler before the list does:
 * if you take over a third, name it here.
 *
 * <p><strong>What does not raise that count, stated as the category rather than as its
 * members</strong>, since the count above already goes stale one handler before the list does:
 * a handler bound to a type Boot's advice does not declare — a Hamstrack exception, or one from
 * {@code org.springframework.dao} / {@code org.springframework.transaction} / {@code …jdbc} —
 * changes no existing response anywhere. It gives a body to a status this class did not
 * previously produce, which is a different act from taking one over.
 * {@link #handleStorageQuotaExceeded} (HD-191) and {@link #handleDatabaseBusy} (HD-233) are both
 * of that kind; the count stays at two.
 *
 * <p>Ordered at {@code HIGHEST_PRECEDENCE + 100} rather than the bare minimum: it beats
 * Boot's order-0 advice just as reliably while leaving room for an advice that ever needs
 * to sit in front of this one.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Read for one thing only: so the refusal can say how long the request was allowed to take.
     * The number reaches the caller; the property and env var that set it reach the log. See
     * {@link #handleQueryTimeout}.
     */
    private final StatementTimeoutProperties statementTimeoutProperties;

    /**
     * Read for one thing only, and deliberately not from the pool: so the WARN beside a
     * {@code 503 DATABASE_BUSY} can say how long the request was allowed to wait for a connection.
     * The number reaches the log; nothing about the pool reaches the caller. Injected rather than
     * re-read from the {@code Environment} because that class is where the acquisition bound is
     * validated and resolved, and two readings of one value is how they come to disagree.
     */
    private final DatabaseTimeoutConsistency databaseTimeouts;

    /** Makes a refused request alertable without anybody reading a log. */
    private final ProductMetrics productMetrics;

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

    /**
     * More specific again — the same 429 + {@code Retry-After} envelope, plus the
     * {@code errorType} that tells the THREE refusals of one path apart (HD-182).
     *
     * <p>A caller on the expensive-read surface can now be refused because they asked too OFTEN
     * (the minute budget: no {@code errorType}, {@code Retry-After} up to 60), because too many of
     * their own requests are running AT ONCE ({@code TOO_MANY_IN_FLIGHT}), or because the whole
     * surface is full ({@code EXPENSIVE_SURFACE_BUSY}). The remedies are different and not
     * interchangeable — one is "stop for a minute", one is "let one of yours finish", one is
     * "retry shortly, there is nothing else to do" — so a client must be able to branch without
     * parsing prose. The two new ones carry {@code Retry-After: 1} because the obstacle is a
     * request that ends, not a window that rolls.
     *
     * <p>The metric is emitted at the throw site rather than here, unlike the storage quota:
     * {@link com.hamstrack.common.ratelimit.PerPrincipalInFlightLimit} is the one place both
     * refusals are decided, and it already has the kind in hand.
     */
    @ExceptionHandler(ConcurrencyLimitedException.class)
    public ResponseEntity<ProblemDetail> handleConcurrencyLimited(ConcurrencyLimitedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
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

    /**
     * More specific than the {@code AppException} handler — a 403 whose obstacle lives on
     * <em>another</em> screen, so the body has to say which one (HD-130 S7, review round 3).
     *
     * <p>{@code projects} names every project whose declared default carries the offending
     * role; {@code role} and {@code missing} are the same pair the picker greys a role out
     * with, so a client can render the refusal in its own copy without parsing {@code detail}.
     * Discloses nothing — the caller holds {@code workspace.edit} and can already list every
     * project of this workspace.
     */
    @ExceptionHandler(ReactivatedProjectDefaultsException.class)
    public ResponseEntity<ProblemDetail> handleReactivatedProjectDefaults(
            ReactivatedProjectDefaultsException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        problem.setProperty("projects", ex.getProjects());
        problem.setProperty("role", ex.getRoleName());
        problem.setProperty("missing", ex.getMissing());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }


    /**
     * More specific than the {@code AppException} handler — publishes the workspace-scoped
     * usage counts as the {@code usage} extension so the client can render the remap dialog
     * straight from the refusal, without a second round trip (HD-127 §7.1 R5).
     *
     * <p>Discloses nothing: every count is scoped to the workspace the caller is already
     * administering, and they hold {@code workspace.role.manage} or they would not have
     * reached this at all.
     */
    @ExceptionHandler(RoleInUseException.class)
    public ResponseEntity<ProblemDetail> handleRoleInUse(RoleInUseException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        problem.setProperty("usage", ex.getUsage());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /**
     * More specific than the {@code AppException} handler — publishes the four figures the
     * refusal is made of, so a client renders it from the body rather than from a cached
     * summary that may be stale (HD-191 §8.3).
     *
     * <p>Discloses nothing beyond what the sentence already says, and the sentence is
     * deliberately what it says: a single tenant-wide aggregate with no per-project resolution,
     * handed to a member of that tenant who holds {@code attachment.create} in it. The
     * project-level breakdown is a different endpoint behind {@code workspace.edit}. Same
     * argument, same shape, as {@link #handleRoleInUse}.
     *
     * <p><strong>No {@code Retry-After}, and that is the load-bearing difference from
     * {@link #handleRateLimited}.</strong> Waiting frees no bytes, so a retry hint here would be
     * an instruction that cannot work.
     *
     * <p>The metric is emitted here rather than at the throw site because this is the one place
     * every path to that 409 passes through, and a quota refusal is a clean 4xx that appears in
     * no error rate — {@code hamstrack.storage.quota_refused} is the only signal an operator has
     * that a tenant is stuck.
     */
    @ExceptionHandler(StorageQuotaExceededException.class)
    public ResponseEntity<ProblemDetail> handleStorageQuotaExceeded(StorageQuotaExceededException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        problem.setProperty("quotaBytes", ex.getQuotaBytes());
        problem.setProperty("usedBytes", ex.getUsedBytes());
        problem.setProperty("availableBytes", ex.getAvailableBytes());
        problem.setProperty("fileBytes", ex.getFileBytes());
        productMetrics.storageQuotaRefused();
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /**
     * More specific than the {@code AppException} handler — carries the {@code errorType}
     * discriminator only. There is no payload: the refusal is about the caller's own
     * membership, which they can already see.
     */
    @ExceptionHandler(SelfHeldRoleException.class)
    public ResponseEntity<ProblemDetail> handleSelfHeldRole(SelfHeldRoleException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /**
     * Same shape, same reason: {@code POST /roles/{id}/duplicate} answers 409 for the sprawl
     * cap, for a display-name conflict and for a lock-wait timeout, and a client that cannot
     * tell them apart cannot offer the right remedy. The name {@code ROLE_LIMIT_REACHED}
     * existed in the spec and in the exception's own javadoc but never on the wire (round-2
     * docs review) — which is the same defect HD-136's two stranded refusals had.
     */
    @ExceptionHandler(RoleLimitReachedException.class)
    public ResponseEntity<ProblemDetail> handleRoleLimitReached(RoleLimitReachedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /**
     * Same shape, same reason (HD-158 §8.2): {@code DELETE /api/workspaces/{ws}/invites/{id}}
     * answers <strong>404</strong> for an invitation that is gone and <strong>409</strong> for one
     * that was accepted, and the client's behaviour on the two is <em>opposite</em> — it renders
     * the 404 as success and must render this one as a refusal. A discriminator is the only thing
     * that lets it tell them apart without parsing prose. No payload: the detail names the member,
     * and the caller can already read both the roster and the invitation list.
     */
    @ExceptionHandler(InviteAlreadyAcceptedException.class)
    public ResponseEntity<ProblemDetail> handleInviteAlreadyAccepted(
            InviteAlreadyAcceptedException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /**
     * Same shape, same reason (HD-133 §8): {@code POST /api/workspaces/{ws}/invites} answers
     * <strong>409</strong> for two entirely different states — the invitee is already a member
     * ({@code AlreadyWorkspaceMemberException}, no discriminator, nothing for the client to do but
     * show it) and this one, where the remedy is a control the client owns. A client that cannot
     * tell them apart cannot refresh the pending list so the blocking row is on screen beside the
     * sentence naming it, and a stale list is exactly how an administrator concludes the refusal
     * is wrong.
     *
     * <p>No payload. The detail echoes the address the caller just submitted, and the blocking
     * row itself is already readable in full through {@code GET /api/workspaces/{ws}/invites} by
     * the permission this caller proved to be refused at all.
     */
    @ExceptionHandler(DuplicateInviteException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateInvite(DuplicateInviteException ex) {
        var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("errorType", ex.getErrorType());
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

    /**
     * <strong>A date the caller supplied that no arithmetic can survive is a 400, not a
     * crash</strong> (HD-28 R1 round 2, item 3).
     *
     * <p>{@code @DateTimeFormat(iso = DATE)} parses with {@code ISO_DATE}, whose year field is
     * {@code EXCEEDS_PAD} — so {@code ?to=+999999999-12-31} <em>binds</em>, as an ordinary
     * {@link java.time.LocalDate}. It is the first {@code plusDays(1)} downstream that throws
     * {@link DateTimeException}, and nothing declared it: the request surfaced as a 500 on an
     * endpoint whose contract promises 400 for a bad date. The sibling case needs no overflow
     * at all — a year inside Java's range but outside PostgreSQL's {@code timestamptz} fails
     * in the driver instead. Both are the caller's input, so both are 4xx.
     *
     * <p>This is a <strong>backstop, not the message</strong>. A call site that takes dates
     * should refuse an out-of-band one itself, naming the band it accepts
     * ({@code FlowReportService.validateDateBand} is the pattern every later report slice
     * inherits) — a caller cannot act on "invalid date" with no numbers in it. This handler
     * exists so that the one that gets forgotten degrades to a clean 400 rather than to a
     * stack trace.
     *
     * <p>It logs, at WARN, because that degradation is the interesting case: after the band
     * checks, no known client path can reach here, so an entry means either a new endpoint
     * without a band check or a genuine server-side date bug — and turning the 500 into a
     * clean 400 removes the only signal an operator had. The client's message stays generic;
     * the URI and the exception go to the log.
     *
     * <p>Not on Boot's list, per the class note: {@code ResponseEntityExceptionHandler}
     * declares nothing from {@code java.time}, so the only behaviour that changes is 500 →
     * 400. It is deliberately NOT extended to cover parse failures — those arrive as
     * {@code MethodArgumentTypeMismatchException} and Boot already answers them 400.
     */
    @ExceptionHandler(DateTimeException.class)
    public ResponseEntity<ProblemDetail> handleDateTime(DateTimeException ex,
                                                        HttpServletRequest request) {
        log.warn("Unhandled date arithmetic on {} {}: {} — answering 400. A call site that "
                 + "takes dates should refuse an out-of-band one itself, naming the band.",
                request.getMethod(), request.getRequestURI(), ex.toString(), ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "That date is outside the range this API can work with");
        return ResponseEntity.badRequest().body(problem);
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
    // public, not package-private: pinned by tests in two packages now — its own
    // (OptimisticLockConflictContractTest) and common.persistence (LockContentionRefusalTest,
    // which drives a real version race through an endpoint). A literal copied into the second
    // would drift the day this sentence is reworded.
    public static final String OPTIMISTIC_LOCK_DETAIL =
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
     * <p><strong>Two bindings, for the reason its pessimistic neighbour has three: Spring's DAO
     * hierarchy only exists on translated paths.</strong> {@link OptimisticLockingFailureException}
     * is the Spring DAO superclass, so the ORM subclass and the plain-JDBC variant both land here
     * from anything that goes through translation — a Spring Data repository, or the commit-time
     * flush, which {@code JpaTransactionManager.doCommit} routes through
     * {@code HibernateJpaDialect}. Nothing translates an explicit {@code entityManager.flush()}.
     * There, {@code ExceptionConverterImpl.wrapStaleStateException} converts Hibernate's
     * {@code StaleObjectStateException} into {@link jakarta.persistence.OptimisticLockException} —
     * every branch of that method returns it, with or without the entity — and that type is
     * unrelated to the DAO one, so resolution walked thrown → cause → {@code PSQLException},
     * matched nothing, and the request <strong>500</strong>ed.
     *
     * <p>The reachable path is narrow and worth naming precisely, because it is not the obvious
     * one: {@code SprintScopeLedger.recordDepartureBeforeDelete} is the only explicit flush in the
     * application, it runs only while deleting an issue that sits in a <em>started</em> sprint, and
     * what it flushes that can conflict is the versioned {@code UPDATE} re-pointing that issue's
     * children to {@code parent = null}. A colleague editing one of those children between the
     * load and the flush turned a documented 409 into a crash. Note what that means for the
     * paragraph above: "a client that omits {@code version} surfaces later, at flush" was true of
     * the <em>condition</em> and false of the <em>status</em>, on the one path that flushes early.
     *
     * <p>Deliberately not a third binding: unlike {@link #handleQueryTimeout}, where the spec
     * forces a bare {@code PersistenceException} and the condition is visible only as a cause,
     * every branch here produces a lock-specific JPA type.
     *
     * <p><strong>Not on Boot's list.</strong> Per the class note above, adding a handler for
     * anything {@code ResponseEntityExceptionHandler} declares changes that exception's body
     * app-wide. This one is a {@code org.springframework.dao} exception, which that class
     * knows nothing about — so the only behaviour that changes is 500 → 409.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<ProblemDetail> handleOptimisticLock(RuntimeException ex) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, OPTIMISTIC_LOCK_DETAIL);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * What a client sees when it loses a <em>row-lock</em> race. Deliberately does not say
     * "deadlock": the caller's move is the same whether they lost a deadlock, a lock
     * timeout or a serialisation failure, and naming the mechanism would leak an internal
     * detail into a message a human reads.
     */
    /** Public for the reason {@link #OPTIMISTIC_LOCK_DETAIL} is: its sibling assertions live in
     * {@code common.persistence}, and the two refusals are told apart by their text as well as by
     * {@code Retry-After}. */
    public static final String LOCK_CONTENTION_DETAIL =
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
     * <p>409 with {@code Retry-After}, in the shape {@link #handleRateLimited} already uses: the
     * request was valid and will very likely succeed on its own the second time — this is the one
     * failure whose entire user-facing contract is "try again".
     *
     * <h4>Three bindings, because Spring's DAO hierarchy only exists on translated paths</h4>
     * {@link PessimisticLockingFailureException} is the Spring DAO superclass, so
     * {@code CannotAcquireLockException} (deadlock / lock timeout) lands here from anything that
     * goes through exception translation — a Spring Data repository, or a commit-time failure,
     * which {@code JpaTransactionManager.doCommit} routes through
     * {@code HibernateJpaDialect.translateExceptionIfPossible}. <strong>Nothing translates a call
     * made on the {@code EntityManager} directly.</strong> There, Hibernate's own
     * {@code org.hibernate.exception.LockTimeoutException} is converted by the JPA spec's rules
     * ({@code ExceptionConverterImpl.wrapLockException}) into one of exactly two types, chosen by
     * whether the transaction is already marked for rollback:
     * {@link jakarta.persistence.PessimisticLockException} when it is — which a lock timeout
     * always does, so this is the one real traffic produces — and
     * {@link jakarta.persistence.LockTimeoutException} when it is not. Neither is related by type
     * to the DAO one, so binding only that answered <strong>500</strong>: the status
     * intermediaries and SDKs retry automatically, on the one condition where a retry is right but
     * the client should be the one deciding to make it.
     *
     * <p><strong>This gap was unreachable until the lock bound went global.</strong> While only
     * {@code LockTimeout}'s seven call sites could hit a lock bound, and all seven run through
     * repositories, the single binding was sufficient — and looked correct. Widening the bound to
     * every transaction ({@code BoundedJpaTransactionManager}) turned a latent hole into a live
     * 500. Both spellings of the untranslated path were checked rather than assumed to match:
     * {@code em.createQuery(...)} and {@code entityManager.flush()} both route through
     * {@code SessionImpl}'s {@code getExceptionConverter().convert(...)} and therefore produce the
     * same two types.
     *
     * <p><strong>Where that leaves the product, as a category rather than a list:</strong> any
     * {@code EntityManager} call that can wait on a lock is exposed — and a sentence that said
     * "queries" would miss the explicit {@code flush()}, which is the half that meets ordinary
     * traffic. How exposed each half is takes care to state, because the intuitive answer is
     * wrong. {@code SprintScopeLedger}'s insert into {@code sprint_scope_events} makes PostgreSQL
     * take {@code FOR KEY SHARE} on the parent issue for the composite FK, and
     * {@code FOR KEY SHARE} conflicts with {@code FOR UPDATE} <em>only</em>. An ordinary issue
     * edit is not {@code FOR UPDATE}: PostgreSQL chooses tuple-lock strength by whether the
     * {@code UPDATE} touches a key column, the key set for {@code issues} is the union of its
     * unique indexes ({@code id}, {@code (id, workspace_id)}, {@code (project_id, number)}), and
     * title, status, assignee, {@code position} and {@code story_points} are none of those — so a
     * normal edit takes {@code FOR NO KEY UPDATE} and does not conflict. Nothing in the tree takes
     * an explicit {@code FOR UPDATE} on {@code issues}, and an issue cannot change project. What
     * genuinely conflicts is an issue <strong>DELETE</strong>, or a future update of a key column.
     * A bare read ({@code SearchService}, {@code InsightsService}) waits only behind
     * {@code ACCESS EXCLUSIVE} — DDL or an explicit {@code LOCK TABLE} — so in production those
     * meet the <em>statement</em> bound far more often than this one. The bindings are right
     * either way; this paragraph is written as specification, so it may not overstate the reach.
     *
     * <p><strong>Deliberately not a fourth binding.</strong> Unlike {@link #handleQueryTimeout} —
     * where the spec forces a <em>bare</em> {@code PersistenceException} on an aborted transaction
     * and the condition is therefore visible only as a cause — every branch here produces a
     * lock-specific JPA type, so there is nothing left to catch by cause. A commit-time lock
     * failure needs no binding of its own either: it is translated, and arrives as the DAO type.
     *
     * <p><strong>It logs, because turning a 500 into a clean 409 also removed the only
     * signal an operator had.</strong> A stack trace is a poor error response and a good
     * alarm; sustained contention would otherwise be silent server-side, visible only as clients
     * retrying. WARN rather than ERROR: one lost race is normal contention, not a fault, and the
     * exception message plus the request URI are what tell an operator which lock and which
     * endpoint. The client's message stays mechanism-free. (This paragraph used to say "the
     * membership path — the one place in the product that takes row locks across two tables",
     * which was a claim about the only code that could <em>reach</em> a lock bound. Since the
     * bound is applied to every transaction, any contended write can arrive here.)
     *
     * <p>Not on Boot's list, per the class note: {@code ResponseEntityExceptionHandler} declares
     * none of the three, so the only behaviour that changes is 500 → 409.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class,
            jakarta.persistence.PessimisticLockException.class,
            jakarta.persistence.LockTimeoutException.class})
    public ResponseEntity<ProblemDetail> handlePessimisticLock(RuntimeException ex,
                                                               HttpServletRequest request) {
        // ex.toString(), not the throwable: the MESSAGE without the STACK, and the distinction is
        // the whole of this line. Passing `ex` printed both, and this WARN stopped being rare when
        // BoundedJpaTransactionManager began bounding every transaction — any authenticated member
        // can now produce it on any contended write, bounded by the pool at roughly
        // poolSize / lock_timeout per second. The frames are all Hibernate and say nothing an
        // operator acts on. The message is the opposite: it carries the SQLSTATE text and the
        // statement ("canceling statement due to lock timeout" / "update issues set … where id=?"),
        // and for a deadlock PostgreSQL's "Process N waits for ShareLock on transaction M; blocked
        // by process K". Method and URI narrow the endpoint but never the statement — and without
        // the message this line cannot tell the common lock-timeout branch from the rare deadlock
        // one, which is a distinction this javadoc makes two paragraphs up. No user data: every
        // parameter is bound, so the statement logs as `?`. Same shape as handleDateTime.
        log.warn("Lock contention on {} {}: {} — answering 409 with Retry-After {}s",
                request.getMethod(), request.getRequestURI(), ex.toString(),
                LOCK_RETRY_AFTER_SECONDS);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, LOCK_CONTENTION_DETAIL);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(LOCK_RETRY_AFTER_SECONDS))
                .body(problem);
    }

    /**
     * The discriminator, in the shape {@link #handleStrandedProjects} and {@link #handleHqlParse}
     * already use — one convention for "which failure is this", not three. It says
     * <em>budget</em> rather than <em>timeout</em> on purpose: a client that reads "timeout"
     * hears "transient", and this one is not.
     */
    static final String STATEMENT_BUDGET_ERROR_TYPE = "STATEMENT_BUDGET_EXCEEDED";

    /**
     * <strong>A statement the database refused to keep running is a 422, and deliberately carries
     * no {@code Retry-After}</strong> (HD-151).
     *
     * <p>PostgreSQL cancels a statement that outruns the {@code SET LOCAL statement_timeout} that
     * {@code BoundedJpaTransactionManager} puts on every transaction, with SQLSTATE
     * {@code 57014 query_canceled}. Note what that is not: a sibling of
     * {@link PessimisticLockingFailureException} under {@code TransientDataAccessException}, not a
     * subclass — so it never reached {@link #handlePessimisticLock} and, until this existed, fell
     * through to a bare <strong>500</strong> for a refusal the server made deliberately and
     * correctly.
     *
     * <p><strong>Why not 5xx — the decisive argument, since this is the choice most likely to be
     * re-litigated.</strong> 5xx is the class that intermediaries, SDKs and retrying clients
     * <em>automatically</em> repeat. An automatic retry here re-spends the entire budget against a
     * connection pool that is already under stress, converting one expensive request into an
     * unbounded series of them — the exact failure the bound exists to prevent. That rules out
     * {@code 503} and {@code 504} outright ({@code 504} has a second defect: a proxy's gateway
     * timeout is indistinguishable from ours on the wire, so an operator cannot tell who gave up).
     * {@code 500} is honest about "we failed" and wrong about "we decided", and renders in the SPA
     * as a crash rather than as a sentence. And the {@code 409 + Retry-After} its lock sibling
     * answers is true of a lock — the rival commits and the retry succeeds — and false here: the
     * same statement over the same data costs the same time, so {@code Retry-After} would be an
     * instruction that cannot work. {@code 422} is what this codebase already means by
     * "well-formed, understood, and we will not process it".
     *
     * <p><strong>Name the number, never the knob.</strong> The <em>value</em> goes in
     * {@code detail}, in human units, exactly as the report window cap is quoted back inside its
     * own 400. The property and env var go in the WARN below and nowhere else: they mean nothing
     * to an end user, and on a multi-tenant Cloud instance they are the operator's tuning surface,
     * which does not belong in front of every authenticated tenant. The operator's copy of this
     * refusal is the log line.
     *
     * <p>The sentence also may not prescribe an action its reader cannot perform, and that rule
     * has <strong>two</strong> edges here, the second of which this handler got wrong once.
     * Several bounded paths take no narrowing parameters at all — {@code GET …/reports/aging}
     * takes none — so narrowing is offered <em>conditionally</em>. And the fallback may not be
     * "ask your administrator": on DC the administrator owns the {@code .env} and that is sound,
     * but on Cloud the reader's administrator is a workspace owner with no access to this
     * setting and no way to obtain it, so the sentence would send them to a dead end. The
     * fallback therefore <em>describes the situation</em> rather than dispatching anybody. Not
     * profile-branched: one sentence true in both modes beats two that can drift, and a refusal
     * is not the place to teach a reader which deployment model they are in.
     *
     * <p>It logs at WARN, for the reason {@link #handlePessimisticLock} does: turning a 500 into a
     * clean 422 also removes the only signal an operator had. WARN rather than ERROR — one refused
     * request is the bound doing its job. The counter beside it is what makes the condition
     * alertable without reading logs, and it is tagged with the <em>mapped pattern</em> rather than
     * the URI, which carries workspace and project ids.
     *
     * <p>Two honest limits, stated so nobody re-derives them from a surprise. The same SQLSTATE is
     * raised when a DBA cancels a query by hand, so this refusal can occasionally name a budget
     * that was not the cause — the WARN is where that shows. And the bound is per
     * <em>statement</em>: a transaction of several statements can outlive the number in this
     * message without any single one of them reaching it.
     *
     * <p><strong>Three types for one condition, and none of them is their common parent.</strong>
     * {@code TransientDataAccessException} would also swallow Hikari's pool-exhaustion failure,
     * which is a different fault with a different remedy, and folding the two together destroys
     * exactly the signal this feature exists to produce. So each spelling is declared explicitly,
     * and there are three because a cancelled statement arrives as whichever exception the path
     * that ran it produces — verified, not assumed, by {@code StatementBudgetRefusalTest}, which
     * drives one endpoint of each kind into a real cancellation:
     * <ul>
     *   <li>Through a <strong>Spring Data repository</strong> (every report but Insights): the
     *       repository proxy translates, so it is {@link QueryTimeoutException}.</li>
     *   <li>Through the <strong>{@code EntityManager} directly</strong> — how
     *       {@code InsightsService} runs all three of its Criteria aggregates, the least bounded
     *       queries in the product — it is a bare
     *       {@link jakarta.persistence.PersistenceException} wrapping
     *       {@link org.hibernate.QueryTimeoutException}, <em>not</em> the JPA timeout type. That
     *       is deliberate on Hibernate's part and mandated by the JPA spec: once the transaction
     *       is marked for rollback — which a cancelled statement does — the spec requires the
     *       plain {@code PersistenceException} (see {@code ExceptionConverterImpl}). Spring's
     *       {@code @ExceptionHandler} resolution falls back to the <em>cause</em> when the thrown
     *       type matches nothing, so naming Hibernate's type is what catches it. Declaring only
     *       the first two would bound Insights and then answer <strong>500</strong> the moment
     *       the bound fired, on the endpoint that needs it most; that is what the first run of
     *       this feature's tests actually did.</li>
     *   <li>{@link jakarta.persistence.QueryTimeoutException} is the same condition on a
     *       transaction that was <em>not</em> marked for rollback. Not reachable from any path in
     *       the tree today, and declared anyway: it is the spec-blessed spelling, and the
     *       alternative is a 500 the day a caller runs a query outside a transaction that gets
     *       marked.</li>
     * </ul>
     *
     * <p>Not on Boot's list, per the class note: {@code ResponseEntityExceptionHandler} declares
     * none of them, so the only behaviour that changes is 500 → 422.
     */
    @ExceptionHandler({QueryTimeoutException.class, jakarta.persistence.QueryTimeoutException.class,
            org.hibernate.QueryTimeoutException.class})
    public ResponseEntity<ProblemDetail> handleQueryTimeout(RuntimeException ex,
                                                            HttpServletRequest request) {
        int budgetMs = statementTimeoutProperties.statementTimeoutMs();
        var pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // The throwable IS passed here, while its lock-contention neighbour deliberately passes
        // only ex.toString(). Both decisions were taken in HD-151; writing down one and not the
        // other leaves the next reader with two opposite choices and one reason between them.
        //
        // The difference is what the frames are worth, not how often the line is written — this
        // path is at least as reachable (60 report + 120 search requests per principal per minute)
        // and each entry costs a full stack. A lock exception's frames are pure Hibernate: the
        // waiting statement is already in the message and the stack adds nothing an operator acts
        // on. A cancelled STATEMENT is the opposite — the frames name the service and the method
        // that issued the query, which is the first thing anybody asks when a report starts timing
        // out on one tenant, and nothing else here carries it: the metric's route tag is the
        // mapped pattern, and the message is the SQL rather than the caller. Drop the throwable
        // and "which code path" has to be reconstructed from a URL.
        //
        // If the volume ever does bite, the answer is sampling or a second line at debug level,
        // not symmetry with the neighbour: these two are asymmetric on purpose.
        log.warn("Statement budget exceeded on {} {} after {}ms — answering 422 (SQLSTATE 57014). "
                 + "Raise app.persistence.statement-timeout-ms (DB_STATEMENT_TIMEOUT_MS) if this "
                 + "request is legitimate, or narrow the query. No Retry-After: an identical retry "
                 + "costs identical time.",
                request.getMethod(), pattern == null ? request.getRequestURI() : pattern, budgetMs, ex);
        productMetrics.statementBudgetExceeded(request.getMethod(),
                pattern == null ? null : pattern.toString());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "This request was stopped after " + humanSeconds(budgetMs) + " — it is too "
                + "expensive to complete on this instance. If it takes a date range or filters, "
                + "narrow them; otherwise this request is larger than this instance is "
                + "configured to answer. Retrying it unchanged will take just as long.");
        problem.setProperty("errorType", STATEMENT_BUDGET_ERROR_TYPE);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    /**
     * The budget as a human reads it. Milliseconds are the operator's unit and are in the log;
     * "10 seconds" is the reader's, and a reader who is being told their request was too expensive
     * does not want to divide by a thousand to find out how patient the server was.
     */
    private static String humanSeconds(int millis) {
        long seconds = Math.round(millis / 1000.0);
        return seconds <= 1 ? "1 second" : seconds + " seconds";
    }

    /**
     * <strong>A connection the pool could not hand over in time is a 503, and this is the one
     * refusal in the class whose status was chosen BECAUSE intermediaries retry it</strong>
     * (HD-233, ADR-0034).
     *
     * <p>Hikari raises {@link java.sql.SQLTransientConnectionException} when an acquisition outruns
     * {@code spring.datasource.hikari.connection-timeout}; Spring wraps it as
     * {@link CannotCreateTransactionException} on the transactional path and
     * {@link CannotGetJdbcConnectionException} on the plain JDBC one. Neither had a handler, so
     * until this existed a saturated pool answered a bare <strong>500</strong> — tolerable while the
     * bound was Hikari's unset 30 s and the condition was rare, and not tolerable at 3 s, where it
     * becomes the ordinary shape of saturation.
     *
     * <p><strong>5xx is right here for exactly the reason it is wrong for
     * {@link #STATEMENT_BUDGET_ERROR_TYPE}, and the two are documented as a contrast so that nobody
     * harmonises them.</strong> That refusal rejects 5xx because intermediaries and SDKs retry it
     * automatically and an automatic retry re-spends the whole statement budget. A failed
     * <em>acquisition</em> is transient by construction — the obstacle is somebody else's
     * transaction, which will end — and one retry costs one acquisition attempt rather than a
     * re-run of an expensive query. Auto-retry is the correct behaviour, so the status that invites
     * it is the correct status. Not {@code 504}, which is indistinguishable from a gateway timeout
     * on the wire, so an operator cannot tell whether the app refused or the proxy gave up. Not
     * {@code 429}: the caller has consumed no budget of theirs, and on {@code /api/auth/*} a 429
     * already means "you are being rate-limited" to every client and every operator — reusing it
     * would make a pool incident indistinguishable from a throttle on the two most-attacked
     * endpoints in the product. (The bulkhead's {@code EXPENSIVE_SURFACE_BUSY} is a 429 for a
     * superficially similar condition; the difference is that it is a deliberate refusal on a
     * designated surface, while this one is a property of every request that needs a connection —
     * an unauthenticated one included.) And not {@code 500}, which is honest about "we failed" and
     * wrong about "we decided". That last one is not a matter of taste: the SPA declines to retry a
     * 503 and deliberately DOES retry a 500, so for as long as this condition answered 500 it was
     * amplified by every open tab at the moment the instance had least room to give.
     *
     * <p><strong>The body says nothing about the pool</strong>, and it is a constant rather than a
     * sentence composed here — {@link DatabaseBusyRefusal} owns every value that goes on the wire,
     * because {@link DatabaseBusyFilter} answers the same condition from outside the dispatcher and
     * two hand-written copies of one JSON body is the defect this ticket is otherwise about. No
     * size, no queue depth, no property name, no environment variable, no SQL: it is reachable
     * unauthenticated, and it must read identically for every caller, in particular on both
     * branches of {@code forgot-password}. The operator's copy of this refusal is the WARN, which
     * names the bound and the two dials; the counter beside it is what makes the condition alertable
     * without anybody reading a log, and it is tagged with the <strong>mapped pattern</strong>
     * rather than the URI, which carries workspace and project ids. Unlike the 422, a 503 also
     * reaches the {@code HighErrorRate} 5xx alert, and that is correct: pool exhaustion is an
     * incident. That is a property of the STATUS and not of this handler, so it holds for the other
     * writer too — but only because {@link DatabaseBusyFilter} runs <em>inside</em> Boot's
     * {@code ServerHttpObservationFilter}. Outside it, the exception unwinds through the
     * observation with the response still at 200 and the alert goes blind to exactly the
     * authenticated half; the two filters were on the same order constant once, and
     * {@code DatabaseBusyFilterOrderTest} is what now decides it.
     *
     * <p><strong>Not every arrival is an acquisition timeout, so the cause chain decides.</strong>
     * Both declared types are also raised for failures that are not this condition at all — a
     * driver that will not start, a transaction manager that cannot begin for its own reasons — and
     * answering "the instance is busy, retry" to one of those is a wrong sentence with a wrong
     * remedy. {@link DatabaseBusyRefusal#acquisitionTimeoutIn} looks for Hikari's own exception type
     * in the chain, and anything else keeps today's outcome exactly: ERROR with the full throwable,
     * answered 500.
     *
     * <p><strong>Which half of the condition arrives here.</strong> This is a
     * {@code @RestControllerAdvice}, so it sees what a handler method raises. A failure earlier than
     * that — {@code JwtAuthenticationFilter}'s user lookup is the one on every authenticated
     * request — unwinds through the filter chain and reaches no advice; {@link DatabaseBusyFilter}
     * answers those with the identical body. The two together are the whole of what a status can be
     * given to, and that class states what is left over.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseBusy(RuntimeException ex,
                                                            HttpServletRequest request) {
        var pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern == null ? request.getRequestURI() : pattern.toString();
        var timeout = DatabaseBusyRefusal.acquisitionTimeoutIn(ex);
        if (timeout == null) {
            log.error("Could not open a database transaction on {} {}, and it was not an "
                      + "acquisition timeout — answering 500 rather than 503 DATABASE_BUSY, which "
                      + "would tell the caller to retry something that is not going to clear on "
                      + "its own.", request.getMethod(), route, ex);
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Something went wrong");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
        }
        DatabaseBusyRefusal.logRefusal(log, databaseTimeouts.acquisitionBoundMs(),
                request.getMethod(), route, timeout);
        productMetrics.connectionAcquisitionFailed(request.getMethod(),
                pattern == null ? null : pattern.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER,
                        String.valueOf(DatabaseBusyRefusal.RETRY_AFTER_SECONDS))
                .body(DatabaseBusyRefusal.problemDetail());
    }

    /**
     * The discriminator for a refusal that came from a foreign key, in the shape
     * {@link #STATEMENT_BUDGET_ERROR_TYPE} and {@code handleHqlParse} already use. It names
     * neither the constraint nor a direction, and both omissions are deliberate: a client may
     * branch on this string, so it must not go stale when a constraint is renamed, and it must
     * not assert something this handler cannot determine.
     *
     * <p>It read {@code REFERENCED_ROW_IN_USE} until review. That name is true of only one of the
     * two directions a {@code 23503} arrives in, and the <em>other</em> one — a child write whose
     * referenced row does not exist — is the reachable one, so the discriminator was asserting
     * the less likely half. A client that branched on it would render "this row is still in use"
     * over a failed issue create.
     */
    static final String REFERENCE_CONSTRAINT_ERROR_TYPE = "REFERENCE_CONSTRAINT_VIOLATION";

    /** PostgreSQL {@code foreign_key_violation}. */
    private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";

    /**
     * The discriminator for "a value was longer than the column that had to hold it", in the
     * shape {@link #REFERENCE_CONSTRAINT_ERROR_TYPE} and {@link #STATEMENT_BUDGET_ERROR_TYPE}
     * already use — one convention for "which failure is this", not three.
     *
     * <p>It names no field, and that omission is honest rather than lazy: by the time a
     * {@code 22001} reaches this class the request body is gone and PostgreSQL has said only how
     * wide the column was, never which one. A client may branch on this string; it must not be
     * given one that promises a field the server cannot supply.
     */
    static final String VALUE_TOO_LONG_ERROR_TYPE = "VALUE_TOO_LONG";

    /** PostgreSQL {@code string_data_right_truncation} — a value longer than its column. */
    private static final String SQLSTATE_STRING_TOO_LONG = "22001";

    /**
     * <strong>A row the database refuses to orphan is a 409, not a crash</strong> (HD-13).
     *
     * <p>This is the <em>fourth</em> instance of one defect in one release, and the pattern is
     * worth naming rather than re-discovering a fifth time: a database-level condition with a
     * correct, documented outcome escaping as a <strong>500</strong> because nothing bound it.
     * {@link #handleOptimisticLock}, {@link #handlePessimisticLock} and
     * {@link #handleQueryTimeout} are the other three. {@code V19__issues_taxonomy_fk.sql} adds
     * {@code issues_type_id_fkey} and {@code issues_status_id_fkey}, so it creates a new way to
     * reach {@code 23503}; shipping the constraint without this handler would have shipped the
     * fourth crash with it.
     *
     * <p><strong>Branch on SQLSTATE, never on the message.</strong>
     * {@link DataIntegrityViolationException} is a wide family — {@code 23505} unique violations,
     * {@code 23502} not-null violations and {@code 23514} check violations all arrive as the same
     * Spring type, and each wants a different sentence. The message text is PostgreSQL's, is
     * localisable, and names the constraint; matching on it would couple this class to strings the
     * database owns. {@link #sqlStateOf} finds the state by walking the cause chain, so it does
     * not matter how deeply the {@link java.sql.SQLException} that carries it is nested.
     *
     * <h4>The gate translates two states, and the rest is untouched on purpose</h4>
     * {@code 23503} is a 409 (this handler's original job) and {@code 22001} is a 400 (HD-171
     * §8, below). <strong>Everything else keeps exactly today's outcome</strong> — logged at
     * ERROR with the full throwable, answered {@code 500} — and the two that are translated are
     * translated because each is unambiguous about <em>whose</em> fault it is. Folding
     * {@code 23505} into a {@code 409} here would silently change the outcome of
     * {@code issues_project_id_number_key} and several admin unique constraints at once, each of
     * which wants its own message; that is a separate decision with a separate ticket. Note that
     * a path that already <em>has</em> a sentence for its own unique violation catches the
     * exception at its call site and never reaches here — that is the pattern to copy rather than
     * to generalise from. (Written as a property because the list is longer than it looks and
     * grows: naming three of them was already an undercount when it was written.)
     *
     * <p><strong>This is a backstop, not the message.</strong> The authoritative refusal stays the
     * pre-check in {@code AdminCatalogService.deleteStatus}/{@code deleteIssueType}/
     * {@code deletePriority}, which counts the affected issues and names the remedy before the
     * database ever has to object. This handler exists so that the path nobody predicted — a
     * future workspace purge, a demo-data teardown, a project delete — degrades to a sentence
     * instead of a stack trace. Same doctrine as {@link #handleDateTime}.
     *
     * <h4>The sentence may not assume a direction it cannot determine</h4>
     * {@code 23503} is raised in <strong>two opposite directions</strong>, and this handler can
     * tell them apart only by reading PostgreSQL's message, which the rule above forbids:
     * <ul>
     *   <li><strong>Parent delete refused</strong> — {@code update or delete on table "statuses"
     *       violates foreign key constraint … on table "issues"}. Something exists that points at
     *       the row being deleted. Remap or archive are real actions here.</li>
     *   <li><strong>Child write refused</strong> — {@code insert or update on table "issues"
     *       violates foreign key constraint …}. The referenced row does <em>not</em> exist,
     *       nothing is being deleted, and neither remap nor archive is anything the caller could
     *       do.</li>
     * </ul>
     * <strong>Quoted from the PRIMARY message, not from {@code DETAIL}</strong>, and that is a
     * statement about this application rather than about PostgreSQL. The server also spells the
     * direction in {@code DETAIL} — {@code Key (id)=(…) is still referenced from table "issues"} /
     * {@code Key (status_id)=(…) is not present in table "statuses"} — and this list quoted that
     * pair until HD-133 set {@code logServerErrorDetail=false} on the datasource to keep
     * {@code DETAIL}'s key VALUES out of the logs. Those two sentences are still what PostgreSQL
     * emits and are no longer what this process can read, so the discriminator to reason about,
     * to log and to test against is the primary-message pair above.
     * The <em>second</em> is the reachable one. The three catalog deletes all pre-check, so
     * direction one needs a path that bypasses them; direction two needs only a TOCTOU on an
     * ordinary issue write — {@code IssueService} resolves a status, a concurrent
     * {@code deleteStatus} whose count legitimately saw zero commits its {@code DELETE}, and the
     * first transaction then flushes against a row that is gone. An ordinary member doing
     * {@code POST …/issues} would have been told to "delete it with a replacement, or archive it
     * instead", and a client branching on the discriminator would have rendered a remap dialog
     * over a failed issue create. (Before V19 that race existed on {@code priority_id} alone and
     * produced a 500; the fix must not convert it into a confident, wrong 409.)
     *
     * <p>So both the sentence and {@link #REFERENCE_CONSTRAINT_ERROR_TYPE} are
     * <strong>direction-neutral and non-prescriptive</strong>: they report that a reference could
     * not be satisfied and say what to do next in a way that is true either way (re-read, then
     * retry), and they leave remap/archive to the three pre-checks, which are the only place that
     * knows the direction <em>and</em> the entry <em>and</em> the count. It also says a retry may
     * work, unlike {@link #handleQueryTimeout}, because a 409 invites one and
     * <em>where the cause was somebody else's commit — the reachable direction — it can
     * succeed</em>. That qualification is not decoration: a retry does nothing for direction one,
     * where the reference blocking the delete is still there, and a sentence claiming otherwise
     * for the whole handler would be the same one-direction mistake this section exists to
     * correct.
     *
     * <p>It says "in a moment" rather than "now" for a reason that lives in another file:
     * {@code Issue}'s javadoc records that catalog membership is read through
     * {@code ProjectConfigCache} (~60 s), so in the direction-two race the caller can reload,
     * be handed the same stale picker still offering the deleted status, and meet the identical
     * violation until the entry ages out. The remedy is honest in kind and was under-specified in
     * time. <strong>Deliberately fixed in the wording rather than by evicting the cache here</strong>:
     * a global exception handler reaching into a feature's cache to repair a race is a coupling
     * that would outlive the reason for it, and would put a write into a path whose whole job is
     * to describe a failure. There is also no {@code Retry-After} — that header is reserved for
     * the lock-contention 409, where the wait has a known bound — so the sentence must not imply
     * a schedule a client could key on.
     *
     * <p><strong>Nothing from the database reaches the wire.</strong> Not the constraint name, not
     * the SQL, not the parameters, not the key values — PostgreSQL's {@code DETAIL} carries the
     * offending key in both directions, and that is an id belonging to whichever tenant owns it.
     * What is left of it goes in the log line with the request method and the mapped pattern, in
     * the shape {@link #handlePessimisticLock} uses; the client gets a sentence.
     *
     * <p><strong>"All of it" is no longer accurate about the LOG either, and the difference is the
     * point of this paragraph.</strong> Since HD-133 the datasource runs
     * {@code logServerErrorDetail=false} (see {@code application.properties}), so {@code DETAIL},
     * {@code HINT} and {@code POSITION} never reach {@code getMessage()} and therefore never reach
     * the log line below — the key values are gone from both destinations, not merely from the
     * response. The constraint name, the two table names and the direction all live in the PRIMARY
     * message and survive, which is why the 409 translation, the direction hint in the WARN and
     * every constraint-name test still work. An operator who needs the key back has one variable,
     * {@code DB_LOG_SERVER_ERROR_DETAIL}, and it is documented as a one-session tool.
     *
     * <h4>Two bindings, because Spring's DAO hierarchy only exists on translated paths</h4>
     * The same trap {@link #handlePessimisticLock} and {@link #handleQueryTimeout} each fell into
     * once, and the reason this handler does not have to fall into it a third time.
     * {@link DataIntegrityViolationException} is Spring's translated type, so it arrives from
     * anything that goes through translation — a Spring Data repository (which is how every
     * catalog delete in the product runs), or a commit-time flush, which
     * {@code JpaTransactionManager.doCommit} routes through {@code HibernateJpaDialect}.
     * <strong>Nothing translates a call made on the {@code EntityManager} directly.</strong> The
     * reachable spelling today is the explicit {@code entityManager.flush()} in
     * {@code SprintScopeLedger.recordDepartureBeforeDelete}; stated as a category rather than as
     * a list, because a sentence naming that one method goes stale the first time anybody adds
     * another — <strong>any {@code EntityManager} write that can violate a constraint is
     * exposed</strong>.
     *
     * <p><strong>What arrives there, read out of the source rather than inferred</strong>
     * ({@code hibernate-core-7.4.1.Final}, the version Boot 4.1.0 pins). Two competent reviewers
     * reached opposite conclusions about this, so it is written down with the line of reasoning
     * that settles it. {@code ExceptionConverterImpl.convert(HibernateException, LockOptions)} is
     * a chain of {@code instanceof} branches; {@code ConstraintViolationException} matches
     * <em>none</em> of them and falls to the final {@code else}, which is
     * {@code rollbackIfNecessary(exception); return exception;} — so it propagates
     * <strong>unwrapped</strong>, and the second binding below matches it <em>directly</em>
     * rather than by cause-fallback.
     *
     * <p>This paragraph previously said the converter wraps it in a bare
     * {@code PersistenceException}. That is <strong>false for this exception and true for a
     * different one</strong>: the {@code org.hibernate.QueryTimeoutException} branch really does
     * wrap, when the transaction is marked for rollback, which is why
     * {@link #handleQueryTimeout} needs three bindings. The sentence was carried across from a
     * neighbour it was true of. There <em>is</em> a wrapping path here, and it is a different
     * one: {@code convertCommitException} returns a {@code RollbackException} wrapping the
     * converted cause, so a commit-time failure arrives nested — normally translated by
     * {@code HibernateJpaDialect} into the DAO type before it reaches this class, but nested all
     * the same.
     *
     * <h4>Why {@link #sqlStateOf} walks the cause chain instead of inspecting {@code ex}</h4>
     * <strong>A handler that branches on the bound exception's type must not assume that type is
     * the one it declared.</strong> Spring builds the candidate arguments as
     * {@code [thrown, cause, cause.cause, …]} and
     * {@code AnnotatedMethod.findProvidedArgument} binds the <em>first</em> one assignable to the
     * declared parameter — so with a parameter of {@code Throwable}, {@code ex} is whatever was
     * thrown, which may be a wrapper several levels above the exception that actually selected
     * this method. {@link #handlePessimisticLock} and {@link #handleQueryTimeout} share the same
     * shape and are unaffected only because they never inspect the argument; they log it. This is
     * the first handler in the class whose control flow depends on the bound argument's runtime
     * type, and walking the chain makes it correct for every nesting, including ones nobody has
     * predicted — which is the whole job of a backstop.
     *
     * <p>Not on Boot's list, per the class note: {@code ResponseEntityExceptionHandler} declares
     * nothing from {@code org.springframework.dao} and nothing from {@code org.hibernate}, so for
     * {@code 23503} the only behaviour that changes is 500 to 409, and for everything else only
     * the body shape.
     *
     * <h4>{@code 22001} is the second state, and it is a 400 (HD-171 §8)</h4>
     * {@code string_data_right_truncation} means the database was handed a value longer than the
     * column. Unlike {@code 23503} there is <strong>no second direction to guess at</strong> and
     * no reading under which the caller supplied nothing wrong — even a value the <em>server</em>
     * derived over-long is derived from something a request supplied — so the translation can be
     * confident where the 409 has to be careful. It arrives as
     * {@link DataIntegrityViolationException} because {@code HibernateJpaDialect} maps
     * {@code org.hibernate.exception.DataException} to it, and {@link #sqlStateOf} finds the
     * state in either spelling.
     *
     * <p><strong>This one is a backstop for a bound that is missing, and it is logged like a
     * fault even though it answers like a client error.</strong> The authoritative refusal is the
     * {@code @Size} at the door (which names the field) or a service's post-normalisation length
     * check (which names the limit); this handler names neither, because it knows neither. HD-171
     * removed the two reachable ways to get here — {@code WorkspaceService.generateSlug} and
     * {@code issue_history.field} — so a {@code 22001} arriving now is by definition a path
     * nobody bounded, and answering a clean 400 without an ERROR line would delete the only
     * signal an operator gets that one exists. Same doctrine as {@link #handleDateTime} and
     * {@link #handleQueryTimeout}.
     *
     * <p><strong>It must not widen.</strong> {@code 23505}, {@code 23502} and {@code 23514}
     * arrive as the same Spring type and each means the application believed a write was valid
     * when it was not — a server fault whose remedy is a fix, not a retry. They keep the 500.
     * And this cannot live in a service: a {@code catch} around {@code save()} never fires,
     * because {@code save()} only queues the persist and Hibernate flushes at commit, after the
     * {@code @Transactional} method has returned. There is nothing to translate <em>into</em>
     * either — no domain status the way {@code DUPLICATE_INVITE} has one — so even the
     * {@code saveAndFlush} escape hatch would buy nothing.
     */
    @ExceptionHandler({DataIntegrityViolationException.class,
            org.hibernate.exception.ConstraintViolationException.class})
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(Throwable ex,
                                                                      HttpServletRequest request) {
        var pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object route = pattern == null ? request.getRequestURI() : pattern;
        String sqlState = sqlStateOf(ex);

        if (SQLSTATE_STRING_TOO_LONG.equals(sqlState)) {
            // ERROR, not WARN, and deliberately louder than the status suggests: a 22001 that
            // reaches this handler means some path accepted a value no column could hold, and the
            // clean 400 below would otherwise be the whole of the record. The two paths that used
            // to arrive here are fixed at the source (HD-171 §4.1/§4.2), so a line here is a
            // report of a NEW missing bound rather than a known one recurring.
            //
            // The mapped PATTERN, never the URI — the URI carries workspace and project ids, and
            // this is the one branch whose log line is expected to be read by whoever then goes
            // looking for the unbounded field. The 'route' local above falls back to the URI for
            // the 23503 path (where it predates this rule); this branch does not take it and
            // says so instead, because a pattern is always present on a request that reached a
            // handler at all.
            //
            // ex.toString(), not the throwable — and what that string CONTAINS was overstated
            // here for one review round, so it is now written from the actual output. It is
            // SPRING'S TRANSLATED message, and AbstractFallbackSQLExceptionTranslator.buildMessage
            // folds Hibernate's statement text into it, so the line reads roughly:
            //   could not execute statement [ERROR: value too long for type character varying(100)]
            //   [insert into issue_history (changed_by,created_at,field,issue_id,…) values (?,?,?,?,…)]
            // So THE PARAMETERISED SQL ARRIVES: table and column NAMES do reach the log — what
            // does not is any VALUE, because every parameter is a `?` (ids and row data included),
            // and logServerErrorDetail=false (HD-133) keeps PostgreSQL's DETAIL out at the driver.
            // That is not a leak and it is arguably better for the operator this line is written
            // for: it names the table whose column is too narrow, which is the first thing they
            // need. Stated exactly because this sentence is what a later reader will trust when
            // deciding whether this log line is safe — the frames, by contrast, are Hibernate's
            // and identify no caller.
            log.error("Value too long for its column on {} {} (SQLSTATE {}): {} — answering 400 "
                      + "{}. A request path is missing a length bound: validation accepted a "
                      + "value the column refused, so find the field and bound it at the door.",
                    request.getMethod(), pattern == null ? "(no mapped pattern)" : pattern,
                    sqlState, ex.toString(), VALUE_TOO_LONG_ERROR_TYPE);
            // Names no field, because this handler genuinely cannot know which one: the body is
            // long gone and the database reported a width, not a column. Nothing from the
            // database reaches the wire — not the width, not the SQL, not the column name.
            var tooLong = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Some of the text you submitted is too long.");
            tooLong.setProperty("errorType", VALUE_TOO_LONG_ERROR_TYPE);
            return ResponseEntity.badRequest().body(tooLong);
        }

        if (!SQLSTATE_FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            // Unchanged outcome, deliberately: a 23505/23502/23514 that reaches here is a genuine
            // fault with no sentence written for it yet, and inventing one would be worse than a
            // 500. ERROR with the throwable, because unlike its lock neighbours this is not
            // normal contention — it means a write the application believed was valid was not.
            //
            // Logging the WHOLE throwable is only safe because of a setting made elsewhere: a
            // 23505 arriving here would otherwise carry PostgreSQL's DETAIL, and DETAIL spells the
            // colliding key VALUES — on the invite path, a third party's email address.
            // logServerErrorDetail=false on the datasource (application.properties, HD-133) masks
            // it at the driver, which is the only layer that can: Hibernate's SqlExceptionHelper
            // has already logged the same message at ERROR before this handler is reached, so
            // redacting here would suppress nothing. If that property is ever removed — or set
            // back to true, which an operator may legitimately do for one debugging session via
            // DB_LOG_SERVER_ERROR_DETAIL — this line and every SQL log in the application start
            // emitting row values again. That is why the variable is documented as temporary and
            // why the default lives in application.properties rather than in a deployment file.
            log.error("Unhandled data integrity violation on {} {} (SQLSTATE {}) — answering 500",
                    request.getMethod(), route, sqlState, ex);
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Something went wrong");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
        }

        // WARN, not ERROR: a 23503 here is the database enforcing a rule correctly, and the
        // request was refused rather than half-applied. It is still worth a line, for the reason
        // handlePessimisticLock logs — turning the 500 into a clean 409 removes the only signal an
        // operator had that a delete path is missing its pre-check. ex.toString() rather than the
        // throwable: the message carries the constraint name and the two table names, which is the
        // whole of what an operator needs, and the frames are Hibernate's.
        //
        // The log line is where the DIRECTION lives, because the message the client must not see is
        // exactly what distinguishes them: "update or delete on table X" (a parent delete that
        // bypassed its pre-check — a bug in that path) versus "insert or update on table X" (a
        // child write racing a concurrent delete — expected, and the caller should retry). An
        // operator needs to know which; nobody else can be told without leaking the key.
        //
        // THOSE PHRASES ARE THE PRIMARY MESSAGE, DELIBERATELY, AND THE PAIR THIS USED TO NAME WAS
        // NOT. PostgreSQL also states the direction in its DETAIL line — "is still referenced from
        // table X" / "is not present in table X" — and this comment quoted that pair until HD-133
        // set logServerErrorDetail=false on the datasource to keep DETAIL's KEY VALUES out of the
        // logs (see application.properties). DETAIL no longer reaches getMessage(), so a log line
        // keyed on those words would now match nothing while still reading correct. The primary
        // message survives the mask and carries the same distinction, so the signal is intact and
        // it is the half to key on. Verified against PostgreSQL, both directions, both settings.
        log.warn("Foreign key violation on {} {}: {} — answering 409 {}. \"update or delete on "
                 + "table\" means a delete path is missing its pre-check; \"insert or update on "
                 + "table\" means a write raced a concurrent delete and the retry will normally "
                 + "succeed.",
                request.getMethod(), route, ex.toString(), REFERENCE_CONSTRAINT_ERROR_TYPE);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This change conflicts with a related record, so it was not applied. Something "
                + "else may have changed at the same time — reload and try again in a moment.");
        problem.setProperty("errorType", REFERENCE_CONSTRAINT_ERROR_TYPE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The SQLSTATE, from whichever of the two spellings arrived — {@code null} if neither
     * carries one, which routes to the unchanged 500 rather than guessing.
     *
     * <p><strong>It walks the chain rather than inspecting {@code ex}, and that is the whole
     * point.</strong> The argument bound to a handler is not necessarily the exception that
     * selected it: Spring offers {@code [thrown, cause, cause.cause, …]} and binds the first one
     * assignable to the declared parameter, so a wrapper wins whenever one exists. Two shapes
     * that both occur here make the distinction real — Spring's {@code DataIntegrityViolationException}
     * carries the driver's {@link SQLException} some levels down, and Hibernate's untranslated
     * {@link org.hibernate.JDBCException} exposes {@code getSQLState()} on itself. Walking finds
     * the state in either, in any nesting, without the method having to know which arrived.
     *
     * <p><strong>Why not {@code NestedExceptionUtils.getMostSpecificCause}, correctly this
     * time.</strong> An earlier version of this javadoc said that helper "would work for the
     * first shape and not for the second". That is wrong: it is a static taking any throwable,
     * and a {@code JDBCException} always keeps its {@code SQLException} as its cause, so it
     * resolves both. The real objection is the opposite one — it goes to the <em>deepest</em>
     * cause, so it walks straight <em>past</em> the SQLSTATE whenever the {@code SQLException}
     * is itself wrapping something (a connection failure underneath a wrapped SQL error, say),
     * and returns a throwable with no state at all. This walk takes the <strong>first</strong>
     * carrier it meets. {@code ReferencedRowConflictContractTest} pins exactly that difference,
     * because it is the only assertion in the suite the two implementations disagree on.
     *
     * <p>Both spellings are checked at every level because they are unrelated by type, and
     * {@code getSQLState()} is null-guarded because a {@code JDBCException} constructed without a
     * cause reports {@code null} — in which case the walk should keep going rather than conclude.
     * A self-referential cause chain (rare, but a real shape when a framework re-wraps) is
     * terminated explicitly, so this can never spin.
     *
     * <p><strong>Never parse the message.</strong> PostgreSQL's text is localisable, names the
     * constraint, and belongs to the database rather than to us; a {@code contains("foreign key")}
     * would couple this class to a string nobody here controls and would silently start matching
     * the wrong things the first time a message is reworded. It is also what makes the two
     * <em>directions</em> of a {@code 23503} indistinguishable here — see the handler's javadoc
     * for why the refusal is worded not to guess.
     *
     * <p><strong>Not covered, deliberately and worth knowing:</strong>
     * {@code jakarta.validation.ConstraintViolationException} shares a simple name with
     * Hibernate's and is a completely unrelated type carrying no SQLSTATE. It has its own
     * handler now — {@link #handleBeanValidation}, added by HD-214, which answers 400 and logs
     * at ERROR — so it no longer escapes as a 500, and the advice on this method held: it got
     * its own handler rather than a widening of this one. The collision is therefore <em>live
     * in both directions</em> and matters more than when it was hypothetical: the two names
     * differ only by import, so a bare {@code import …ConstraintViolationException} in this
     * class silently rebinds one handler to the other's type, with no compile error and no
     * failing happy path. Both bindings are written fully qualified for that reason and must
     * stay that way.
     */
    private static String sqlStateOf(Throwable ex) {
        Throwable t = ex;
        // Bounded, not merely self-reference-guarded. `t.getCause() == t` catches the one-step
        // loop and nothing else: A -> B -> A spins forever, inside an exception handler, on a
        // thread already handling a failure. The depth is far beyond any real chain (the deepest
        // shape here is DataIntegrityViolationException -> ConstraintViolationException ->
        // SQLException -> cause, and a commit adds one RollbackException on top).
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof SQLException se && se.getSQLState() != null) {
                return se.getSQLState();
            }
            if (t instanceof org.hibernate.JDBCException je && je.getSQLState() != null) {
                return je.getSQLState();
            }
        }
        return null;
    }

    /** Generous enough that no real chain reaches it, small enough that a cycle cannot hang. */
    private static final int MAX_CAUSE_DEPTH = 20;

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
        return validationRefusal(collected);
    }

    /**
     * The same refusal for a constraint written on a <em>parameter</em> — a
     * {@code @RequestParam}, {@code @PathVariable}, {@code @RequestHeader} or
     * {@code @CookieValue} — as {@link #handleValidation} gives one written on a body
     * field (HD-163/HD-214, ADR-0019). Both render through {@link #validationRefusal},
     * so the guarantee is a category rather than a coincidence: <strong>every 400 raised
     * by a declared constraint carries an {@code errors} map keyed by the name of the
     * thing that was refused, whichever door the constraint is written on.</strong> That
     * is what the SPA branches on since HD-171; a refusal that names nothing forces a
     * client back to pattern-matching English.
     *
     * <p><strong>This overrides Boot's advice for an exception it declares.</strong>
     * {@code HandlerMethodValidationException} is on {@code ResponseEntityExceptionHandler}'s
     * list, so — per this class's precedence note — adding it here changes that exception's
     * body <em>app-wide</em>, not only on the endpoints this ticket was filed about. Every
     * parameter constraint in the tree gains a named field and an {@code errors} map where
     * Boot rendered a bare {@code "Validation failure"}. The status never diverges from
     * Boot's: {@code initHttpStatus} answers 400 for an argument violation and 500 for a
     * return-value one, and both branches are honoured below.
     *
     * <p>Keys come from {@link MethodParameter#getParameterName()}, which needs
     * {@code -parameters} — {@code spring-boot-starter-parent} sets it, and this project
     * uses that parent. {@link #nameOf} falls back to a positional key rather than letting
     * a nameless parameter silently drop out of the map: a 400 whose {@code errors} is
     * {@code {}} is the failure mode this handler exists to remove.
     *
     * <p><strong>Both statuses Spring can mean by this exception are honoured.</strong>
     * {@code HandlerMethodValidationException} extends {@code ResponseStatusException} with a
     * status chosen at construction, and that status is <em>not</em> fixed at {@code BAD_REQUEST}:
     * {@code initHttpStatus} (spring-web 7.0.8) returns {@code INTERNAL_SERVER_ERROR} when the
     * violation is on the handler's <strong>return value</strong> and {@code BAD_REQUEST} for
     * everything else. A return-value violation is the server failing its own contract, so
     * rendering it through {@link #validationRefusal} would blame a caller for a fault they
     * cannot fix — and would hand them a constraint message describing data they never sent.
     * The branch below answers an opaque 500 and logs at ERROR; every other shape is a 400,
     * which is Boot's own status for it and not a widening.
     *
     * <p>That branch is unreachable today, because return-value validation only runs when the
     * bean carries {@code @Validated} and ADR-0018 forbids that on anything MVC dispatches to.
     * It is written anyway: <em>unreachable</em> is a claim about the current call graph, and a
     * claim about the call graph is exactly the kind this codebase keeps outliving. One
     * {@code if} is cheaper than the guarantee it would otherwise stand in for.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleParameterValidation(HandlerMethodValidationException ex) {
        if (ex.isForReturnValue()) {
            // The caller's request was accepted and the handler then produced a value its own
            // constraints reject. Nothing here is client-fixable, and a message about a return
            // value describes server-side data — so the response says nothing beyond the status,
            // and the detail goes to the log with the throwable.
            log.error("Method validation failed on the RETURN VALUE of {} — answering 500, not 400: "
                            + "the caller is not at fault. Return-value validation only runs when "
                            + "the bean carries @Validated, which ADR-0018 forbids on anything "
                            + "Spring MVC dispatches to, so this line also means that annotation "
                            + "is back on a web bean.",
                    ex.getMethod(), ex);
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Something went wrong");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
        }
        Map<String, String> collected = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String parameterKey = nameOf(result.getMethodParameter());
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                // A cascaded parameter object (@Valid on a @ModelAttribute, a validated
                // container) reports FieldErrors describing fields INSIDE it. Key those by
                // the field, exactly as a body refusal does, so a nested failure degrades to
                // the same {name: message} shape instead of being reported under the
                // container's name — or, worse, dropped for not being a plain parameter.
                String key = (error instanceof FieldError fieldError) ? fieldError.getField() : parameterKey;
                collected.putIfAbsent(key, messageOf(error));
            }
        }
        // A cross-parameter constraint (one written on the METHOD, relating two arguments)
        // belongs to no single parameter, so it takes the same empty key a class-level body
        // constraint takes and renders bare.
        for (MessageSourceResolvable error : ex.getCrossParameterValidationResults()) {
            collected.putIfAbsent(GLOBAL_ERROR_KEY, messageOf(error));
        }
        return validationRefusal(collected);
    }

    /**
     * Backstop for {@code jakarta.validation.ConstraintViolationException} — the same
     * doctrine as {@link #handleDateTime} and {@link #handleQueryTimeout}: <strong>a
     * backstop, not the mechanism.</strong> After ADR-0018 nothing in the tree should be
     * able to raise this, so an occurrence is a fact about the codebase rather than about
     * the request, and it is logged at ERROR for that reason. A clean 400 with no line
     * deletes the only signal an operator would get (HD-151).
     *
     * <p><strong>Fully qualified, deliberately and permanently.</strong>
     * {@link #handleDataIntegrityViolation} binds
     * {@code org.hibernate.exception.ConstraintViolationException}, an unrelated type
     * sharing this one's simple name. A bare import here would silently rebind that handler
     * to the wrong class — a one-character way to break data-integrity handling with no
     * compile error. {@link #sqlStateOf}'s javadoc records the collision from the other
     * side.
     *
     * <p><strong>Trade-off worth knowing.</strong> No {@code @Entity} in the tree carries
     * Bean Validation annotations today. If one ever does, Hibernate's pre-insert listener
     * raises this same type for a <em>server-side</em> failure and this handler would report
     * it as a client error. The right response then is a dedicated handler for that path,
     * not widening or narrowing this one — and the ERROR line below is what will make it
     * visible in the first place.
     *
     * <p><strong>The ERROR line carries names, not submitted values — and that is a property
     * of this codebase's constraint messages, not of the line.</strong> It logs
     * {@code collected.keySet()} plus the throwable, so it says <em>which</em> things were
     * refused; it stays free of what was in them only because no message template here
     * interpolates <code>{@literal $}{validatedValue}</code>. A template that does puts the
     * rejected value into {@code violation.getMessage()}, from where it reaches this log line,
     * the {@code errors} map and {@code detail} alike — every one of them, in one edit. So a
     * constraint guarding a secret-shaped field (password, token, e-mail, invite code) must
     * not use that placeholder: a refusal states the rule, never the submission. Same rule for
     * {@link #handleValidation} and {@link #handleParameterValidation}, which render the same
     * messages.
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleBeanValidation(
            jakarta.validation.ConstraintViolationException ex) {
        Map<String, String> collected = new LinkedHashMap<>();
        var violations = ex.getConstraintViolations();
        if (violations != null) {
            for (var violation : violations) {
                // The LAST node of the path, so a client sees `q` rather than `suggest.q`.
                collected.putIfAbsent(lastNodeOf(violation.getPropertyPath()),
                        violation.getMessage() != null ? violation.getMessage() : "");
            }
        }
        log.error("Bean Validation escaped to the advice: {}. Answered 400, but nothing should be "
                        + "able to raise this — either a bean Spring MVC dispatches to has acquired "
                        + "@Validated (forbidden by ADR-0018; parameter constraints then go through "
                        + "the AOP proxy instead of MVC's own method validation), or an @Entity has "
                        + "gained Bean Validation annotations, in which case this is a SERVER fault "
                        + "being reported to a client as a 400. Check which before assuming the "
                        + "caller is at fault.",
                collected.keySet(), ex);
        return validationRefusal(collected);
    }

    /**
     * The one rendering both validation refusals share — extracted rather than copied on
     * purpose. The sort, the {@link #MAX_REPORTED_ERRORS} cap, the {@code "; … and N more"}
     * overflow line and {@link #render}'s prefix rule are a contract the API docs describe in
     * four bullets; two copies of it would drift, and the drift would be invisible because
     * both copies produce a 400. Any new handler that refuses a declared constraint calls
     * this — it does not reimplement it.
     *
     * @param collected failed items in insertion order, keyed by the name of the refused
     *                  thing (a body field path, a parameter name, or
     *                  {@link #GLOBAL_ERROR_KEY} for a rule belonging to no single one)
     */
    private static ResponseEntity<ProblemDetail> validationRefusal(Map<String, String> collected) {
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

    /**
     * Widened to {@link MessageSourceResolvable} so one implementation serves a body error
     * ({@code ObjectError}/{@code FieldError}, which implement it) and a parameter error
     * (which is only ever the interface). Null message → "": a null value would NPE the map
     * it goes into, turning a 400 into a 500.
     */
    private static String messageOf(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "";
    }

    /**
     * The parameter's own name, or a positional key when the class was compiled without
     * {@code -parameters}. Never null and never empty, because an empty key is
     * {@link #GLOBAL_ERROR_KEY} and would render the message as if it belonged to no
     * parameter at all.
     */
    private static String nameOf(MethodParameter parameter) {
        String name = parameter.getParameterName();
        return (name != null && !name.isBlank()) ? name : "arg" + parameter.getParameterIndex();
    }

    /**
     * The last named node of a violation's property path, so a client is told {@code q}
     * rather than {@code suggest.q} — the parameter it sent, not the method it happened to
     * reach. A path with no named node falls to {@link #GLOBAL_ERROR_KEY} and renders bare.
     */
    private static String lastNodeOf(jakarta.validation.Path path) {
        String last = GLOBAL_ERROR_KEY;
        for (var node : path) {
            if (node.getName() != null) {
                last = node.getName();
            }
        }
        return last;
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
