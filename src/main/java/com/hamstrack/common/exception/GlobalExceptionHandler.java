package com.hamstrack.common.exception;

import com.hamstrack.common.config.StatementTimeoutProperties;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.ratelimit.RateLimitedException;
import com.hamstrack.issue.exception.LabelNameConflictException;
import com.hamstrack.project.exception.StrandedProjectsException;
import com.hamstrack.workspace.exception.ReactivatedProjectDefaultsException;
import com.hamstrack.workspace.exception.RoleInUseException;
import com.hamstrack.workspace.exception.RoleLimitReachedException;
import com.hamstrack.workspace.exception.SelfHeldRoleException;
import com.hamstrack.search.HqlSemanticException;
import com.hamstrack.search.parser.HqlParseException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerMapping;

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
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Read for one thing only: so the refusal can say how long the request was allowed to take.
     * The number reaches the caller; the property and env var that set it reach the log. See
     * {@link #handleQueryTimeout}.
     */
    private final StatementTimeoutProperties statementTimeoutProperties;

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
