package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.AgileProperties;
import com.hamstrack.common.dto.PageResponse;
import com.hamstrack.common.event.IssueUpdated;
import com.hamstrack.common.security.Permission;
import com.hamstrack.common.util.Points;
import com.hamstrack.issue.dto.AddIssuesToSprintRequest;
import com.hamstrack.issue.dto.CompleteSprintRequest;
import com.hamstrack.issue.dto.CreateSprintRequest;
import com.hamstrack.issue.dto.SectionStats;
import com.hamstrack.issue.dto.SprintCompletionPreview;
import com.hamstrack.issue.dto.SprintCompletionResult;
import com.hamstrack.issue.dto.SprintInsertPosition;
import com.hamstrack.issue.dto.SprintRef;
import com.hamstrack.issue.dto.SprintResponse;
import com.hamstrack.issue.dto.StartSprintRequest;
import com.hamstrack.issue.dto.UnfinishedDisposition;
import com.hamstrack.issue.dto.UpdateSprintRequest;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.IssueHistory;
import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.SprintState;
import com.hamstrack.issue.entity.StatusCategory;
import com.hamstrack.issue.exception.IssueNotFoundException;
import com.hamstrack.issue.exception.SprintNameConflictException;
import com.hamstrack.issue.exception.SprintNotFoundException;
import com.hamstrack.issue.repository.IssueHistoryRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.SprintRepository;
import com.hamstrack.project.entity.Project;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Project sprints (HD-22, agile-sprints-proposal §4) — CRUD, the start/complete
 * lifecycle, bulk issue assignment and the completion report.
 *
 * <p><strong>Tenancy (§3.1):</strong> every entry point resolves through
 * {@link WorkspaceAccessService#resolveProject} — a missing workspace, a missing
 * project and a non-member all yield <strong>404</strong>, never 403. 403 is reserved
 * for a <em>member without the permission</em>, and by construction it can only reach
 * someone whose membership is already proved. Sprint lookups always go through
 * {@code findByIdAndProject}: a foreign id is a 404 on a direct read and a
 * <strong>422</strong> "Unknown sprint" inside an issue payload (an invalid field value,
 * leaking nothing about the other tenant).
 *
 * <p><strong>Permissions (§3.2, HD-123 §10.2):</strong> read = any project member;
 * create / rename / re-plan / delete / start / complete =
 * {@link Permission#SPRINT_MANAGE} (built-in project MANAGER, plus a workspace
 * OWNER/ADMIN through {@link Permission#PROJECT_CURATE_ALL}). Putting <em>items into</em>
 * a sprint is {@link Permission#SPRINT_ASSIGN}, a separate and deliberately wider grant:
 * planning is ordinary work the whole team does at a planning meeting, and requiring
 * MANAGER to drag would make the backlog read-only for most of the team.
 *
 * <p><strong>The double door (§6.5):</strong> {@code sprint.assign} guards three
 * endpoints, not one — {@link #addIssues}, {@link #removeIssue} <em>and</em>
 * {@code PATCH /issues/{n}} / {@code POST /issues/{n}/rank} when the body carries
 * {@code sprintId}/{@code clearSprint} (enforced in {@code IssueService}). A permission
 * that guards one door and not the other is not a permission.
 *
 * <p>The {@code board} delivery capability is a rendering hint and never a check: a
 * KANBAN project still accepts {@code POST /sprints} (delivery Rule A).
 *
 * <p>Sprints are <em>content</em>, not bound taxonomy: nothing here touches
 * {@code ProjectConfigService} or {@code ProjectConfigResponse} (§3.6), so a sprint
 * start never invalidates the config every board render depends on.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SprintService {

    private final WorkspaceAccessService workspaceAccess;
    private final SprintRepository sprintRepository;
    private final IssueRepository issueRepository;
    private final IssueHistoryRepository historyRepository;
    /**
     * HD-137: the ONE writer of {@code sprint_scope_events}. Four of this class's
     * statements are doors onto sprint membership, and each of them writes the ledger in
     * the same breath as its {@code sprint} history row — see {@link SprintScopeLedger}.
     */
    private final SprintScopeLedger scopeLedger;
    private final AgileProperties agileProperties;
    private final ApplicationEventPublisher eventPublisher;

    /** Max name length AFTER normalization (matches {@code sprints.name VARCHAR(60)}). */
    private static final int MAX_NAME_LENGTH = 60;

    /** The one constraint a sprint name write may legitimately lose to (V11__sprints.sql). */
    private static final String NAME_UNIQUE_CONSTRAINT = "sprints_project_name_uk";

    /** The per-project ordinal key — a lost "next sequence" race (V11__sprints.sql). */
    private static final String SEQUENCE_UNIQUE_CONSTRAINT = "sprints_project_id_sequence_key";

    /** THE invariant: one ACTIVE sprint per project, arbitrated in the DB (V11__sprints.sql). */
    private static final String ONE_ACTIVE_CONSTRAINT = "sprints_one_active_per_project_uk";

    /**
     * Above this many moved issues, {@code complete} publishes NO {@code IssueUpdated}
     * events (§4.8). A completion may touch hundreds of issues and one request must
     * stay bounded: the completing client refetches anyway, and other clients refresh
     * on their next poll/navigation. Below it, every moved issue gets its event so open
     * boards update over SSE.
     */
    private static final int COMPLETE_EVENT_FANOUT_THRESHOLD = 50;

    /**
     * How far ahead of the server's clock a {@code startAt} may sit and still count as
     * "now" (HD-137 review R3-2, see {@link #start}).
     *
     * <p>Not zero, and not a policy: the timestamp on a start request is usually the
     * CLIENT's idea of now, and an unsynchronised laptop a couple of minutes fast would
     * otherwise get a 422 on the most ordinary action in the whole feature. Five minutes
     * is generous for clock skew and far too small to express an intention — nobody plans
     * a sprint to begin in four minutes.
     */
    private static final Duration START_CLOCK_SKEW = Duration.ofMinutes(5);

    // ================================================================= reads

    /**
     * The project's sprints, ALWAYS paged (§4.4): open sprints are capped, but
     * COMPLETED ones accumulate for years. Order is ACTIVE first, then FUTURE by
     * sequence, then COMPLETED newest-first.
     *
     * <p>{@code states} is the repeatable {@code ?state=} filter; omitting it returns
     * all states (an empty {@code IN} list is invalid in JPQL, so "all" is substituted
     * rather than the predicate dropped). Counters come from ONE grouped query for the
     * whole page, never one per sprint.
     */
    @Transactional(readOnly = true)
    public PageResponse<SprintResponse> list(User actor, UUID workspaceId, UUID projectId,
                                             Collection<SprintState> states, Pageable pageable) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var filter = states == null || states.isEmpty()
                ? EnumSet.allOf(SprintState.class)
                : EnumSet.copyOf(states);
        var page = sprintRepository.findByProjectAndStates(project, filter, pageable);
        var stats = statsFor(page.getContent());
        return PageResponse.of(page.map(s -> respond(s, stats)));
    }

    /** One sprint — any project member (reads are unrestricted within the project). */
    @Transactional(readOnly = true)
    public SprintResponse get(User actor, UUID workspaceId, UUID projectId, UUID sprintId) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        return respond(requireSprint(project, sprintId));
    }

    /**
     * What {@link #complete} would report, computed before the fact (§4.4) — the
     * dialog's data source, so it never has to guess. <strong>Read-only and readable by
     * any project member</strong>: looking at the numbers is not a commitment.
     *
     * <p>The counters come from the SAME grouped query the completion uses, so the
     * dialog and the result can't drift.
     */
    @Transactional(readOnly = true)
    public SprintCompletionPreview completionPreview(User actor, UUID workspaceId, UUID projectId,
                                                     UUID sprintId) {
        var project = workspaceAccess.resolveProject(actor, workspaceId, projectId).project();
        var sprint = requireSprint(project, sprintId);
        var stats = statsOf(sprint);
        var targets = sprintRepository.findFutureByProject(project).stream()
                .filter(s -> !s.getId().equals(sprint.getId()))
                .map(SprintRef::of)
                .toList();
        return new SprintCompletionPreview(
                stats.issueCount(), stats.doneIssueCount(),
                stats.issueCount() - stats.doneIssueCount(),
                stats.points(), stats.donePoints(),
                normalize(stats.points().subtract(stats.donePoints())),
                targets);
    }

    // ================================================================= CRUD

    /**
     * Create a FUTURE sprint — curator only (§3.2); frozen while the project is
     * archived. A blank/absent name becomes {@code "Sprint " + sequence}, which is why
     * default names can never collide; {@code startAt}/{@code endAt} are a plan and do
     * NOT start it.
     */
    @Transactional
    public SprintResponse create(User actor, UUID workspaceId, UUID projectId,
                                 CreateSprintRequest req) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.SPRINT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);

        // ---- all reads + validation first (CLAUDE.md: mutate-then-query double-writes) ----
        requireValidDates(req.startAt(), req.endAt());

        // Open-sprint cap (§3.7). COMPLETED sprints do NOT count: they are history and
        // can never be started again, so counting them would turn a concurrency bound
        // into a lifetime quota.
        int maxOpen = agileProperties.maxOpenSprintsPerProject();
        if (sprintRepository.countOpenByProject(project) >= maxOpen) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "This project already has the maximum of " + maxOpen
                            + " open sprints — complete or delete one first");
        }

        int sequence = sprintRepository.nextSequence(project);
        String name = req.name() == null || ClassificationNames.normalize(req.name()).isEmpty()
                ? "Sprint " + sequence
                : requireValidName(req.name());

        // Pre-check the common case so a duplicate is a clean 409 instead of a
        // constraint violation that has already marked the transaction rollback-only.
        sprintRepository.findByProjectAndNameIgnoreCase(project, name)
                .ifPresent(existing -> { throw duplicate(existing); });

        var sprint = new Sprint();
        sprint.setWorkspace(project.getWorkspace());
        sprint.setProject(project);
        sprint.setName(name);
        sprint.setGoal(trimToNull(req.goal()));
        sprint.setState(SprintState.FUTURE);
        sprint.setSequence(sequence);
        sprint.setStartAt(req.startAt());
        sprint.setEndAt(req.endAt());
        sprint.setCreatedBy(actor);
        try {
            // Flush now so a concurrent-create race surfaces HERE (as a translated
            // DataIntegrityViolationException) instead of at commit, where it would
            // escape as a 500 after the controller already built a 201.
            sprintRepository.saveAndFlush(sprint);
        } catch (DataIntegrityViolationException e) {
            throw createConflict(e, name);
        }
        // A brand-new sprint holds no issues, so the grouped stats query would return
        // nothing — skip it rather than pay a round-trip for a guaranteed zero.
        return SprintResponse.of(sprint, SectionStats.EMPTY, daysRemaining(sprint));
    }

    /**
     * Rename / re-goal / re-plan — curator only. The lifecycle deliberately does NOT
     * move through here: there is no {@code state} field on the request, and
     * {@code state}/{@code completedAt} are {@code updatable = false} on the entity, so
     * a PATCH can neither start, complete nor re-open a sprint.
     */
    @Transactional
    public SprintResponse update(User actor, UUID workspaceId, UUID projectId, UUID sprintId,
                                 UpdateSprintRequest req) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.SPRINT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);
        var sprint = requireSprint(project, sprintId);

        // ---- all reads first ----
        String newName = null;
        if (req.name() != null) {
            newName = requireValidName(req.name());
            // A pure casing change keeps the same unique slot — allow it.
            if (!newName.equalsIgnoreCase(sprint.getName())) {
                sprintRepository.findByProjectAndNameIgnoreCase(project, newName)
                        .ifPresent(existing -> { throw duplicate(existing); });
            }
        }
        // Captured BEFORE the mutation: the conflict message must not be read off an
        // entity that is dirty (and, after a failed flush, unusable).
        String attemptedName = newName != null ? newName : sprint.getName();

        // Validate the dates the sprint will END UP with, not the ones in the payload.
        OffsetDateTime effectiveStart = req.startAt() != null ? req.startAt()
                : req.clearStartAt() ? null : sprint.getStartAt();
        OffsetDateTime effectiveEnd = req.endAt() != null ? req.endAt()
                : req.clearEndAt() ? null : sprint.getEndAt();
        requireValidDates(effectiveStart, effectiveEnd);
        // sprints_active_ck: an ACTIVE sprint must keep a start date. Catching it here
        // turns what would be a driver-level 500 into an honest 422. Checked before the
        // rule below because its message is the more specific one.
        if (sprint.getState() == SprintState.ACTIVE && effectiveStart == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "An active sprint must keep a start date");
        }
        // HD-137 review R2-2: once a sprint has LEFT the future, its start is history.
        // `start_at` is an ordinary entity-writable column, so a PATCH could move an
        // ACTIVE sprint's start while its commitment rows keep the original stamp — pull
        // the start earlier and "committed scope at start" reads 0, because every ADDED
        // row sits after the new start. Same failure for a COMPLETED sprint's review.
        // The plan is editable exactly as long as it is a plan; after that the recovery
        // path for a mistake is a new sprint, not an edit to a running one (the
        // requireDetachable rule, applied to the dates instead of the membership).
        if (sprint.getState() != SprintState.FUTURE
            && !sameInstant(effectiveStart, sprint.getStartAt())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A sprint's start date can only be changed while it is still in the future");
        }

        // ---- then mutate ----
        if (newName != null) sprint.setName(newName);
        if (req.goal() != null) sprint.setGoal(trimToNull(req.goal()));
        sprint.setStartAt(effectiveStart);
        sprint.setEndAt(effectiveEnd);

        try {
            // Flush so the response carries the audited updatedAt, not the pre-update one.
            return respond(sprintRepository.saveAndFlush(sprint));
        } catch (DataIntegrityViolationException e) {
            if (!isConstraint(e, NAME_UNIQUE_CONSTRAINT)) throw e;
            throw new SprintNameConflictException(
                    "A sprint named '" + attemptedName + "' already exists in this project");
        }
    }

    /**
     * Start a sprint (§4.1) — curator only, FUTURE → ACTIVE, and <strong>one-way</strong>.
     *
     * <p><strong>Concurrency:</strong> the flip is a conditional
     * {@code UPDATE … WHERE state = 'FUTURE'} checked by its affected-row count, NOT a
     * read-then-write — zero rows means "not startable" → <strong>409</strong>. Two
     * <em>different</em> FUTURE sprints started concurrently both pass that guard, so
     * the partial unique index {@code sprints_one_active_per_project_uk} arbitrates and
     * the loser's integrity violation becomes a 409 "Another sprint is already active".
     * Only that constraint is translated; anything else keeps its 500.
     *
     * <p>Starting an <strong>empty</strong> sprint is allowed on purpose: blocking it
     * would push teams to file a placeholder issue. The UI warns, the API does not.
     */
    @Transactional
    public SprintResponse start(User actor, UUID workspaceId, UUID projectId, UUID sprintId,
                                StartSprintRequest req) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.SPRINT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);
        var sprint = requireSprint(project, sprintId);

        // ---- all reads + validation first, before anything is written ----
        // UTC, not OffsetDateTime.now(): every other timestamp this API serializes is
        // UTC, and stamping one field in the JVM's default zone is a bug VersionService
        // already had to fix once.
        OffsetDateTime startAt = req != null && req.startAt() != null
                ? req.startAt() : OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime endAt = req != null && req.endAt() != null
                ? req.endAt() : startAt.plusDays(agileProperties.defaultSprintLengthDays());
        requireValidDates(startAt, endAt);
        requireStartHasArrived(startAt);
        // ---- the tolerance is for a CLOCK, not for a start date ----
        // requireStartHasArrived admits up to now + START_CLOCK_SKEW so an unsynced
        // laptop does not 422 the most ordinary action in the feature. Persisting that
        // reading verbatim would be a different thing entirely: start_at would sit ahead
        // of the commitment rows that describe it (the ledger stamps "now"), and a
        // burn-up windowed on `occurred_at >= start_at` would read committed scope = 0 —
        // not a little wrong, ALL of it, for the exact clients the tolerance exists for.
        // So the admitted skew is normalised here: a couple of minutes fast means "now".
        // Everything downstream (markActive's start_at, the commitment stamp) then gets
        // the SAME instant, `update`'s freeze cannot make a forward date permanent, and
        // completed_at >= start_at holds by construction.
        var startedNow = OffsetDateTime.now(ZoneOffset.UTC);
        if (startAt.isAfter(startedNow)) startAt = startedNow;

        // ---- an ordinary field is written through the ENTITY, not through the
        // lifecycle UPDATE (migration review M-Low1) ----
        // `goal` is entity-writable, so putting it in markActive's SET clause made a
        // bulk UPDATE the writer of a field any PATCH can also write — protected from
        // clobbering nothing but by convention. The lifecycle UPDATE now writes ONLY
        // lifecycle columns (state + the dates that must satisfy sprints_active_ck),
        // and this flush is ordered before it (markActive is flushAutomatically anyway).
        if (req != null && req.goal() != null) {
            sprint.setGoal(trimToNull(req.goal()));
            sprintRepository.saveAndFlush(sprint);
        }

        // ---- the conditional UPDATE is the arbiter ----
        try {
            if (sprintRepository.markActive(sprintId, project.getId(), startAt, endAt) == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a future sprint can be started");
            }
        } catch (DataIntegrityViolationException e) {
            if (!isConstraint(e, ONE_ACTIVE_CONSTRAINT)) throw e;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another sprint is already active in this project — complete it first");
        }
        // markActive flushed then CLEARED the persistence context (it had to: the bulk
        // UPDATE leaves the managed copy stale). `project`/`sprint` are detached from
        // here on — they are only ever used as query parameters below, which bind their
        // identifiers, so that is safe.

        // ---- the commitment, as EVENTS (HD-137, reports-proposal §5.2) ----
        // "Committed" is not a snapshot column: it is one ADDED row per current member,
        // dated startAt, so "scope at time T" (ADDED <= T minus REMOVED <= T) answers the
        // committed scope, the current scope and every step in between with one mechanism.
        //
        // Ordering is load-bearing twice over:
        //   * AFTER the arbitration above, so the loser of a double-click — which got 0
        //     affected rows and a 409 — writes nothing at all;
        //   * after the clear, because markActive carries clearAutomatically and rows
        //     queued before it would be evicted as pending inserts (the documented
        //     "workspace vanishes" trap).
        // The membership is re-read as a SCALAR projection for the same reason the
        // completion path uses one: nothing may re-enter the persistence context here.
        // startAt is <= now here — requireStartHasArrived refuses a real forward date and
        // the clamp above normalises the clock skew it tolerates — so start_at and the
        // stamp on these rows are the SAME instant, which is what lets a burn-up be
        // computed over the sprint's own window rather than only cumulatively. The ledger
        // still clamps its stamp to "now" independently (belt-and-braces: it is the single
        // writer and must not depend on which door called it — for THIS door the clamp is
        // now a no-op, which is what belt-and-braces should mean), and `update` above
        // refuses to move start_at once the sprint has left FUTURE, so the two cannot
        // drift afterwards either.
        scopeLedger.recordCommitment(workspaceId, project.getKey(), sprint,
                issueRepository.findRefsBySprint(sprint), actor, startAt);

        return respond(requireSprint(project, sprintId));
    }

    /**
     * Complete a sprint (§4.1, §4.5) — curator only, ACTIVE → COMPLETED, terminal.
     *
     * <p>Every issue whose status category is NOT DONE moves to the chosen destination
     * (the backlog, or a FUTURE sprint of the same project); <strong>DONE issues keep
     * their {@code sprint_id}</strong> — that is the sprint's record of what it
     * delivered. The rank is never rewritten, so carried-over items keep their relative
     * order.
     *
     * <p>Conditional for the same reason as {@link #start}: a double-click is a
     * <strong>409</strong>, not an idempotent-looking success that would run the
     * destructive move twice.
     */
    @Transactional
    public SprintCompletionResult complete(User actor, UUID workspaceId, UUID projectId,
                                           UUID sprintId, CompleteSprintRequest req) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.SPRINT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);
        var sprint = requireSprint(project, sprintId);

        // ---- all reads + validation first ----
        Sprint target = null;
        if (req.moveUnfinishedTo() == UnfinishedDisposition.SPRINT) {
            if (req.targetSprintId() == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Pick a future sprint to carry the unfinished issues over to");
            }
            target = requireCarryOverTarget(project, sprint, req.targetSprintId());
        }
        // The completion report is computed BEFORE the move — done vs carried-over as of
        // the moment the sprint closed.
        var before = statsOf(sprint);

        // ---- the conditional UPDATE is the arbiter ----
        if (sprintRepository.markCompleted(sprintId, project.getId(),
                OffsetDateTime.now(ZoneOffset.UTC)) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only the active sprint can be completed");
        }
        // markCompleted cleared the persistence context; `sprint`/`target`/`project` are
        // detached and are used only as query parameters (which bind identifiers).
        //
        // THE WRITE-LIST IS THE UPDATE'S OWN OUTPUT (HD-137 review R2-5), not a SELECT run
        // before it. Two statements are two snapshots under READ COMMITTED, and a
        // concurrent POST /sprints/{id}/issues landing between them would either hide a
        // real membership change from both records — an ADDED with no matching REMOVED,
        // so the issue stays in this sprint's scope forever while its sprint_id points
        // elsewhere — or invent a REMOVED that double-decrements the scope line. The
        // audit log and the ledger would agree with each other and both be wrong.
        // Scalars, still: nothing enters the persistence context for a later flush to
        // write the pre-move sprint_id back with.
        var movedRefs = issueRepository.moveUnfinishedOutOfSprint(
                sprintId, target == null ? null : target.getId(), StatusCategory.DONE.name());
        int moved = movedRefs.size();
        // The move is a real membership change on every issue it touches, so it writes
        // the same `sprint` history row the single-issue paths do (security review L2) —
        // otherwise the highest-volume destructive move in the feature would be the one
        // with no audit trail at all.
        writeSprintHistory(movedRefs, actor, sprint.getName(),
                target == null ? null : target.getName());
        // …and the same move is a SCOPE change on both sprints (HD-137, door 6): every
        // carried-over issue leaves this sprint's burn-up here, and joins the target's
        // only if the target is already running (a FUTURE target commits its scope when
        // it starts — see SprintScopeLedger). Same refs, same proxies, same batch shape
        // as the history rows above, deliberately: two writers reading one list is how
        // the two records stay in step.
        scopeLedger.recordBulkMove(workspaceId, project.getKey(), movedRefs, sprint, target, actor);

        var completed = requireSprint(project, sprintId);
        var carriedOverPoints = normalize(before.points().subtract(before.donePoints()));

        // §4.8: one IssueUpdated per moved issue so other clients' boards refresh over
        // SSE — but a completion may touch hundreds of issues, so above the threshold we
        // publish nothing and let clients refresh on their next poll/navigation.
        if (moved > 0 && movedRefs.size() <= COMPLETE_EVENT_FANOUT_THRESHOLD) {
            for (var ref : movedRefs) {
                eventPublisher.publishEvent(
                        new IssueUpdated(workspaceId, projectId, numberOf(ref), List.of()));
            }
        }

        return new SprintCompletionResult(
                respond(completed),
                before.doneIssueCount(),
                before.issueCount() - before.doneIssueCount(),
                target == null ? null : target.getId(),
                before.donePoints(),
                carriedOverPoints);
    }

    /**
     * Delete a sprint (§4.5) — curator only. An <strong>ACTIVE</strong> sprint is a
     * 409 ("complete it first"): deleting a running commitment silently is never what
     * anybody meant. A FUTURE/COMPLETED sprint that still holds issues is a 409 unless
     * {@code force}; with {@code force} the issues are detached first and their rank is
     * preserved.
     *
     * <p><strong>Trap (§4.3):</strong> the FK is {@code ON DELETE SET NULL (sprint_id)},
     * which clears the column behind JPA's back — so the detach is done EXPLICITLY,
     * before the row goes away, or a stale managed {@code Issue} flushed later would
     * write the old id back.
     *
     * <p><strong>Concurrency:</strong> the row is removed by a CONDITIONAL delete keyed
     * by {@code (id, project)} and checked on its affected-row count — the
     * {@link SprintRepository#markActive} pattern. Two curators clicking "delete" at the
     * same moment both pass {@code requireSprint}; {@code repository.delete(entity)}
     * would have the loser MERGE a row that no longer exists and 500, where the SPA
     * expects the 404 it now gets.
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID projectId, UUID sprintId, boolean force) {
        // HD-123 S2: permission first, project state second (§10.3.6).
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.SPRINT_MANAGE);
        var project = ctx.project();
        requireNotArchived(project);
        var sprint = requireSprint(project, sprintId);

        if (sprint.getState() == SprintState.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This sprint is active — complete it before deleting it");
        }
        if (!force) {
            long inUse = issueRepository.countBySprint(sprint);
            if (inUse > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This sprint still holds " + inUse
                                + " issue(s) — move them out, or delete with force");
            }
        }
        String sprintName = sprint.getName();
        // The detach IS the read (HD-137 review R2-5): `held` is what the UPDATE actually
        // moved, so an issue dropped into this sprint a microsecond earlier cannot end up
        // detached-but-unrecorded (nothing would ever say it had been here — security
        // review L2) and one dropped in a microsecond later cannot be recorded as
        // detached when it was not. See IssueRepository.clearSprint.
        var held = force ? issueRepository.clearSprint(sprintId) : List.<Object[]>of();
        if (!held.isEmpty()) {
            // The history rows are written AFTER the detach on purpose: the statement is
            // native, so Hibernate flushes everything pending before it, and rows queued
            // ahead of it would be written against a membership that no longer holds.
            writeSprintHistory(held, actor, sprintName, null);
            // Door 7 (HD-137). These ledger rows are removed again moments later by the
            // sprint's own ON DELETE CASCADE — deliberately not special-cased: the ledger
            // records what happened, and a deleted sprint has no report left to be wrong.
            // deleteByIdAndProject carries flushAutomatically, so they are written before
            // the DELETE and the cascade takes them, rather than an insert arriving after
            // its parent is gone.
            scopeLedger.recordBulkMove(workspaceId, project.getKey(), held, sprint, null, actor);
        }
        if (sprintRepository.deleteByIdAndProject(sprintId, project.getId()) == 0) {
            throw new SprintNotFoundException();
        }
        // Same fan-out bound as `complete` (§4.8): a force-delete can detach hundreds of
        // issues, and one request must stay bounded.
        if (held.size() <= COMPLETE_EVENT_FANOUT_THRESHOLD) {
            for (var ref : held) {
                eventPublisher.publishEvent(
                        new IssueUpdated(workspaceId, projectId, numberOf(ref), List.of()));
            }
        }
    }

    // ================================================================ issue membership

    /**
     * Put a batch of issues into a sprint (§4.4) — {@link Permission#SPRINT_ASSIGN}, NOT
     * {@link Permission#SPRINT_MANAGE}: putting items into a sprint is ordinary planning
     * work the whole team does, and the built-in Contributor holds it. One of the three
     * doors that permission guards (§6.5).
     *
     * <p>An issue already in the target sprint is a <strong>no-op</strong>: no history
     * row, no {@code @Version} bump, no event. Assigning to a COMPLETED sprint is a 422
     * ({@link #requireAssignable}), and so is moving an issue OUT of one
     * ({@link #requireDetachable}) — the batch is checked as a whole up front, so a
     * request containing a frozen member is rejected atomically. Assigning to the ACTIVE
     * one mid-sprint is allowed (a scope change is a real event).
     *
     * <p>The moved issues are re-ranked to the TOP or BOTTOM of the target section,
     * keeping their relative order among themselves — <strong>if the actor holds
     * {@link Permission#ISSUE_RANK}</strong>; otherwise each issue keeps the rank it
     * already had, exactly as {@link #removeIssue} does. {@code issues.position} is the
     * project-wide backlog rank, so re-ranking under {@code sprint.assign} alone would be
     * a back door onto {@code issue.rank}. Positions are mutated on the managed entities
     * and saved — never a bulk JPQL UPDATE, which would desync the copies we just loaded
     * to write history from.
     */
    @Transactional
    public SprintResponse addIssues(User actor, UUID workspaceId, UUID projectId, UUID sprintId,
                                    AddIssuesToSprintRequest req) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.SPRINT_ASSIGN);
        var project = ctx.project();
        requireNotArchived(project);
        var sprint = requireSprint(project, sprintId);
        requireAssignable(sprint);

        // ---- all reads + validation first ----
        var ids = new LinkedHashSet<>(req.issueIds());
        ids.remove(null);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issueIds must not be empty");
        }
        int maxBulk = agileProperties.maxIssuesPerBulkMove();
        if (ids.size() > maxBulk) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At most " + maxBulk + " issues per request");
        }
        var issues = issueRepository.findAllByIdInAndProject(ids, project);
        if (issues.size() != ids.size()) {
            // A foreign/unknown id is an invalid field VALUE in a request the caller is
            // entitled to make — 422, never a 404 that would confirm existence.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown issue");
        }
        // Only the issues that actually change: an issue already in this sprint is a
        // no-op and must not be re-ranked, historied or version-bumped.
        var changing = issues.stream()
                .filter(i -> i.getSprint() == null || !i.getSprint().getId().equals(sprint.getId()))
                .sorted(Comparator.comparingLong(Issue::getPosition))
                .toList();
        if (changing.isEmpty()) {
            return respond(sprint);
        }
        // Assigning into a sprint DETACHES from whatever sprint the issue is in today, so
        // this path is a removal path too and carries the same freeze (security review
        // L1): an issue may not be pulled out of a COMPLETED sprint here any more than
        // via DELETE .../sprints/{id}/issues/{issueId}. Validated over the WHOLE batch
        // before anything moves, so one frozen member fails the request atomically
        // instead of leaving it half-applied.
        for (var issue : changing) {
            requireDetachable(issue.getSprint());
        }
        // The re-rank is a SEPARATE permission (HD-125 review, M2). `issues.position` is
        // the PROJECT-WIDE backlog rank, not a per-sprint one, so writing it here under
        // `sprint.assign` alone would make `issue.rank` bypassable in two calls: add to a
        // sprint with position=TOP, then remove — removeIssue preserves the rank — and the
        // issue returns to the backlog carrying a position computed from that sprint's
        // bounds. Repeat with different anchor sprints to walk it anywhere, which defeats
        // exactly the policy the permission exists to express ("developers must not
        // reprioritise the backlog"). An actor without `issue.rank` may still move issues
        // in and out of sprints; their rank is simply PRESERVED, which is already the
        // contract removeIssue keeps. Δ-free today: Contributor holds both.
        boolean mayRank = ctx.permissions().has(Permission.ISSUE_RANK);
        // The anchor rank is read BEFORE any mutation, so the section's current bounds
        // are not polluted by this batch's own writes — and not read at all when nothing
        // will be written with it.
        long base = !mayRank ? 0
                : req.position() == SprintInsertPosition.TOP
                        ? issueRepository.minPositionInSection(project, sprintId)
                        : issueRepository.maxPositionInSection(project, sprintId);

        // ---- then mutate ----
        var history = new ArrayList<IssueHistory>(changing.size());
        // The sprint each issue is LEAVING, captured before the field is overwritten —
        // the ledger needs it to close the previous sprint's scope (HD-137, door 4).
        var leaving = new ArrayList<Sprint>(changing.size());
        for (int k = 0; k < changing.size(); k++) {
            var issue = changing.get(k);
            history.add(makeHistory(issue, actor, "sprint",
                    issue.getSprint() == null ? null : issue.getSprint().getName(), sprint.getName()));
            leaving.add(issue.getSprint());
            issue.setSprint(sprint);
            if (mayRank) {
                issue.setPosition(req.position() == SprintInsertPosition.TOP
                        ? base - (long) IssueRankService.RANK_STEP * (changing.size() - k)
                        : base + (long) IssueRankService.RANK_STEP * (k + 1));
            }
        }
        issueRepository.saveAll(changing);
        historyRepository.saveAll(history);
        // Door 4 (HD-137), written from the SAME loop's data as the history rows above:
        // one REMOVED for whatever running sprint the issue left, one ADDED here. The
        // issues are managed entities carrying their final story points, so the estimate
        // recorded is the one this sprint actually received.
        for (int k = 0; k < changing.size(); k++) {
            scopeLedger.recordMove(workspaceId, project.getKey(), changing.get(k),
                    leaving.get(k), sprint, actor);
        }

        for (var issue : changing) {
            eventPublisher.publishEvent(
                    new IssueUpdated(workspaceId, projectId, issue.getNumber(), List.of()));
        }
        return respond(sprint);
    }

    /**
     * Take one issue out of a sprint (§4.4) — {@link Permission#SPRINT_ASSIGN}, like
     * {@link #addIssues}. The rank is <strong>preserved</strong>, so the issue keeps its
     * relative place in the backlog it returns to.
     *
     * <p>An issue that is not in this sprint is a silent no-op rather than a 404: the
     * endpoint expresses "this issue must not be in this sprint", which is already true
     * — and DELETE staying idempotent means a retried request never surprises the SPA.
     * An id that does not resolve <em>within the project</em> is still a 404.
     *
     * <p>A <strong>COMPLETED</strong> sprint is a 422 ({@link #requireDetachable}): its
     * membership is the delivery record the completion already reported, and it stays
     * frozen on the removal side exactly as it is on the assignment side.
     */
    @Transactional
    public void removeIssue(User actor, UUID workspaceId, UUID projectId, UUID sprintId,
                            UUID issueId) {
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        ctx.permissions().require(Permission.SPRINT_ASSIGN);
        var project = ctx.project();
        requireNotArchived(project);
        var sprint = requireSprint(project, sprintId);
        var issue = issueRepository.findByIdAndProject(issueId, project)
                .orElseThrow(IssueNotFoundException::new);
        if (issue.getSprint() == null || !issue.getSprint().getId().equals(sprint.getId())) {
            // Not a member of this sprint: nothing to freeze and nothing to do.
            return;
        }
        requireDetachable(issue.getSprint());
        var history = makeHistory(issue, actor, "sprint", sprint.getName(), null);
        issue.setSprint(null);
        issueRepository.save(issue);
        historyRepository.save(history);
        // Door 5 (HD-137): the issue leaves this sprint's scope. `sprint` (not
        // issue.getSprint(), already nulled) is the one it left.
        scopeLedger.recordMove(workspaceId, project.getKey(), issue, sprint, null, actor);
        eventPublisher.publishEvent(
                new IssueUpdated(workspaceId, projectId, issue.getNumber(), List.of()));
    }

    // ============================================================== issue integration

    /**
     * Resolve a {@code sprintId} payload against the issue's OWN project (§3.1).
     * Returns {@code null} when the field is absent.
     *
     * <p>An unknown id, an id from another project and an id from another tenant all
     * produce the same <strong>422 "Unknown sprint"</strong> — an invalid field value in
     * a request the caller is entitled to make, which discloses nothing. Only a direct
     * {@code GET /sprints/{id}} yields a 404.
     */
    @Transactional(readOnly = true)
    public Sprint resolveForIssue(Project project, UUID sprintId) {
        if (sprintId == null) return null;
        return sprintRepository.findByIdAndProject(sprintId, project)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Unknown sprint"));
    }

    /**
     * Assigning to a COMPLETED sprint is a 422 (§4.5) — a completed sprint's membership
     * is a delivered fact and must stay frozen. An issue that already carries one keeps
     * it; only a NEW assignment is rejected.
     */
    public void requireAssignable(Sprint sprint) {
        if (sprint != null && sprint.getState() == SprintState.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Sprint '" + sprint.getName() + "' is completed");
        }
    }

    /**
     * The mirror of {@link #requireAssignable} for the REMOVAL side: an issue may not be
     * taken OUT of a COMPLETED sprint either (422).
     *
     * <p>Freezing only the assignment side would leave the delivery record
     * {@link #complete} reported silently rewritable — any member could strip the DONE
     * issues out of a closed sprint and its "17 of 21 points delivered" would quietly
     * become a different number. The membership of a completed sprint is a fact about
     * the past; the recovery path for a mistake is a new sprint, not an edit to a
     * closed one.
     *
     * @param current the sprint the issue is in TODAY ({@code null} = the backlog)
     */
    public void requireDetachable(Sprint current) {
        if (current != null && current.getState() == SprintState.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Sprint '" + current.getName() + "' is completed");
        }
    }

    /** The project's OPEN sprints, ACTIVE first — the planning view's section list. */
    @Transactional(readOnly = true)
    public List<Sprint> openSprintsOf(Project project) {
        return sprintRepository.findOpenByProject(project);
    }

    /** The project's ACTIVE sprint, if any — the Scrum board's scope. */
    @Transactional(readOnly = true)
    public java.util.Optional<Sprint> activeSprintOf(Project project) {
        return sprintRepository.findActiveByProject(project);
    }

    // ================================================================= stats

    /**
     * {@code sprintId → SectionStats} for a whole list in ONE grouped query (§4.6) —
     * never one query per sprint. Batched ({@link UsageCounts#rowsIn}) because the
     * {@code IN} list binds one JDBC parameter per sprint: the open-sprint cap bounds a
     * page of open sprints, but a page of COMPLETED history is unbounded, and batching
     * keeps that degrading into more round-trips instead of a driver-level failure.
     */
    @Transactional(readOnly = true)
    public Map<UUID, SectionStats> statsFor(List<Sprint> sprints) {
        if (sprints.isEmpty()) return Map.of();
        var byId = new HashMap<UUID, SectionStats>(sprints.size());
        var rows = UsageCounts.rowsIn(sprints,
                batch -> issueRepository.statsBySprints(batch, StatusCategory.DONE));
        for (var row : rows) {
            byId.put((UUID) row[0], statsRow(row).full());
        }
        return byId;
    }

    private SectionStats statsOf(Sprint sprint) {
        return statsFor(List.of(sprint)).getOrDefault(sprint.getId(), SectionStats.EMPTY);
    }

    /**
     * One row of the grouped stats query, carrying BOTH the whole-section aggregates and
     * their DONE-only halves — {@code (count, doneCount, points, donePoints, unestimated,
     * unestimatedDone)}. The done-only halves exist so the backlog section can derive its
     * {@code includeDone = false} numbers by subtraction ({@link #excludingDone}) instead
     * of paying a second grouped query.
     */
    record StatsRow(int count, int doneCount, BigDecimal points, BigDecimal donePoints,
                    int unestimated, int unestimatedDone) {

        SectionStats full() {
            return new SectionStats(count, doneCount, points, donePoints, unestimated);
        }

        /** The same section with its DONE issues removed — the backlog's default view. */
        SectionStats excludingDone() {
            return new SectionStats(count - doneCount, 0,
                    normalize(points.subtract(donePoints)), BigDecimal.ZERO,
                    unestimated - unestimatedDone);
        }
    }

    /** Parse one {@code (sprintId, count, done, points, donePoints, unest, unestDone)} row. */
    static StatsRow statsRow(Object[] row) {
        return new StatsRow(
                ((Number) row[1]).intValue(),
                row[2] == null ? 0 : ((Number) row[2]).intValue(),
                normalize((BigDecimal) row[3]),
                normalize((BigDecimal) row[4]),
                row[5] == null ? 0 : ((Number) row[5]).intValue(),
                row[6] == null ? 0 : ((Number) row[6]).intValue());
    }

    /**
     * A point SUM as a clean JSON number: {@code null → 0}, trailing zeros stripped, no
     * scientific notation. Shared with {@code IssueResponse.storyPoints} via
     * {@link Points} — see its javadoc for why the negative-scale step is a
     * wire-contract fix rather than cosmetics.
     *
     * <p>Sums collapse null to zero (an empty section has scored 0 points); a single
     * issue's estimate does NOT (null means "unestimated").
     */
    static BigDecimal normalize(BigDecimal value) {
        return Points.normalizeSum(value);
    }

    // ================================================================= responses

    /** One sprint with its counters — pays one grouped query; use the batched overload for lists. */
    public SprintResponse respond(Sprint sprint) {
        return SprintResponse.of(sprint, statsOf(sprint), daysRemaining(sprint));
    }

    private SprintResponse respond(Sprint sprint, Map<UUID, SectionStats> stats) {
        return respondWith(sprint, stats.getOrDefault(sprint.getId(), SectionStats.EMPTY));
    }

    /**
     * One sprint rendered against stats the caller ALREADY has — used by the planning
     * view, whose single grouped query has computed every section's numbers before any
     * response is built. Pays no query of its own.
     */
    public SprintResponse respondWith(Sprint sprint, SectionStats stats) {
        return SprintResponse.of(sprint, stats, daysRemaining(sprint));
    }

    /**
     * Whole days from today (UTC) to {@code endAt} — {@code 0} = ends today, negative =
     * overdue. Only meaningful for an ACTIVE sprint with an end date; {@code null}
     * otherwise, because a FUTURE or COMPLETED sprint has nothing to count down.
     *
     * <p>Computed on calendar DAYS, not on {@link Duration}: "2 days left" must not flip
     * to "1 day left" merely because it is now the afternoon.
     */
    static Integer daysRemaining(Sprint sprint) {
        if (sprint.getState() != SprintState.ACTIVE || sprint.getEndAt() == null) return null;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate end = sprint.getEndAt().atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        return (int) ChronoUnit.DAYS.between(today, end);
    }

    // ================================================================= helpers

    Sprint requireSprint(Project project, UUID sprintId) {
        return sprintRepository.findByIdAndProject(sprintId, project)
                .orElseThrow(SprintNotFoundException::new);
    }

    /**
     * The carry-over target of a completion (§4.5): a <strong>FUTURE</strong> sprint of
     * the same project and not the one being completed. Deliberately not an ACTIVE one
     * (there is none — this sprint is it) and not a COMPLETED one. Anything else is a
     * <strong>422</strong>: an invalid field value inside a request the caller is
     * entitled to make, and a foreign id discloses nothing.
     */
    private Sprint requireCarryOverTarget(Project project, Sprint source, UUID targetId) {
        if (source.getId().equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Unfinished issues can't be carried over to the sprint being completed");
        }
        var target = sprintRepository.findByIdAndProject(targetId, project)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Unknown sprint"));
        if (target.getState() != SprintState.FUTURE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Unfinished issues can only be carried over to a future sprint");
        }
        return target;
    }

    /**
     * {@code endAt} must be strictly after {@code startAt} whenever both are present —
     * a zero-length or inverted iteration is a nonsensical field value, i.e. a
     * <strong>422</strong>. Also enforced by {@code sprints_dates_ck}, because both
     * lifecycle write paths are conditional bulk UPDATEs that bypass this check.
     */
    private static void requireValidDates(OffsetDateTime startAt, OffsetDateTime endAt) {
        if (startAt == null || endAt == null) return;
        if (!endAt.isAfter(startAt)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A sprint's end date must be after its start date");
        }
    }

    /**
     * A sprint cannot be STARTED into the future (HD-137 review R3-2), 422. Backdating is
     * untouched — {@code StartSprintRequest} documents it as normal, and it is: a team
     * that began on Monday and clicks Start on Tuesday is telling the truth.
     *
     * <p>PLANNING a future start stays legal (create/PATCH accept any date). This guards
     * only the one door that turns a plan into history, because a forward-dated START
     * breaks three things at once:
     *
     * <ol>
     *   <li><strong>The plan and the ledger legitimately disagree.</strong>
     *       {@code SprintScopeLedger.recordCommitment} clamps the commitment stamp to now,
     *       and must: N {@code ADDED} rows dated a month out plus one ordinary
     *       {@code REMOVED} dated today drive {@code count(ADDED <= T) - count(REMOVED <= T)}
     *       negative for a month. So {@code sprints.start_at} and the sprint's own
     *       commitment rows would describe different instants, and a burn-up computed over
     *       the sprint's WINDOW would read 0 committed. With this guard
     *       {@code start_at == the commitment's occurred_at} always, and R4 may use either
     *       the cumulative or the windowed form.</li>
     *   <li><strong>The mistake becomes unrecoverable.</strong> {@link #update} refuses to
     *       move {@code startAt} once the sprint has left FUTURE, so a mistyped year would
     *       be fixable only by completing the sprint and recreating it — a freeze is only
     *       defensible if the wrong value cannot get behind it in the first place.</li>
     *   <li><strong>A sprint could complete before it started.</strong> V11 relates
     *       {@code start_at} to {@code end_at} ({@code sprints_dates_ck}) and constrains
     *       ACTIVE/COMPLETED rows ({@code active_ck}/{@code completed_ck}), but nothing
     *       relates {@code completed_at} to {@code start_at}. Velocity reads that as a
     *       negative-length iteration.</li>
     * </ol>
     *
     * <p><strong>What this guard admits, {@link #start} normalises.</strong> The
     * {@code START_CLOCK_SKEW} tolerance lets a request through with
     * {@code startAt} up to a few minutes ahead of now — that is a fast clock, not an
     * intention, and 422ing it would break the feature's most ordinary action on an
     * unsynced laptop. But the tolerated value must not be PERSISTED: a start a couple of
     * minutes ahead of its own commitment rows is exactly failure (1) above, in miniature
     * in time and in full in effect (a windowed burn-up reads 0 committed either way). So
     * {@link #start} clamps {@code startAt} to now right after this call and hands the
     * clamped instant to both {@code markActive} and the ledger. Read the two together:
     * this method decides whether the request is legitimate, the clamp decides what a
     * legitimate request means.
     *
     * <p>The message names an action its reader can actually perform. "Fix the date" is
     * not one — by the time they read this the sprint is still FUTURE, so the thing they
     * can do is leave it planned and start it when it starts.
     */
    private static void requireStartHasArrived(OffsetDateTime startAt) {
        if (startAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plus(START_CLOCK_SKEW))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A sprint can't be started with a start date in the future — start it "
                            + "when it actually begins. Until then leave it planned: a future "
                            + "sprint's dates stay editable, and starting it stamps the start "
                            + "for you.");
        }
    }

    /**
     * Instant equality that survives a client re-sending the same moment in a different
     * offset — {@code equals} on {@link OffsetDateTime} compares the offset too, so
     * {@code 09:00+02:00} and {@code 07:00Z} would look like an edit and 422 a PATCH that
     * changed nothing.
     */
    private static boolean sameInstant(OffsetDateTime a, OffsetDateTime b) {
        return a == null ? b == null : b != null && a.isEqual(b);
    }

    /**
     * Name normalization (§4.5, shared with the whole HD-6 epic): NFC, non-whitespace
     * control AND format characters dropped, whitespace/separator runs collapsed to one
     * space, then stripped — see {@link ClassificationNames}. Casing is preserved for
     * display; uniqueness is case-insensitive per project (the
     * {@code sprints_project_name_uk} functional index). The length limit is enforced
     * AFTER normalization.
     */
    private String requireValidName(String raw) {
        String name = ClassificationNames.normalize(raw);
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sprint name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Sprint name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    /** {@code number} out of an {@code (id, number)} projection row. */
    private static long numberOf(Object[] ref) {
        return ((Number) ref[1]).longValue();
    }

    /**
     * One {@code sprint} history row per issue for the two BULK membership moves — the
     * completion's carry-over and the force-delete's detach (security review L2). Both
     * can touch hundreds of issues, and without this they were the only membership
     * changes in the feature that left no trace: after a force-delete nothing recorded
     * that the issues had ever been in that sprint.
     *
     * <p>The rows are built against {@code getReferenceById} PROXIES, never loaded
     * entities: the bulk UPDATE that precedes them has already changed
     * {@code sprint_id} in the database, so materializing the issues would put stale
     * managed copies in the persistence context (the documented "bulk JPQL UPDATE
     * desyncs already-loaded entities" trap). An uninitialized proxy is only ever
     * dereferenced for its id, which the FK insert needs and which we already have.
     *
     * <p>Written in one {@code saveAll} — one persistence-context operation, but NOT one
     * round-trip: {@code hibernate.jdbc.batch_size} is unset in this project, so JDBC
     * batching is off and Hibernate issues N INSERTs. (Turning it on changes flush
     * behaviour for every entity in the application, so it is its own change with its own
     * review; the same correction is on {@code SprintScopeLedger.recordBulkMove}, which
     * used to make the same claim.)
     */
    private void writeSprintHistory(List<Object[]> refs, User actor, String oldName, String newName) {
        if (refs.isEmpty()) return;
        var rows = new ArrayList<IssueHistory>(refs.size());
        for (var ref : refs) {
            var h = new IssueHistory();
            h.setIssue(issueRepository.getReferenceById((UUID) ref[0]));
            h.setChangedBy(actor);
            h.setField("sprint");
            h.setOldValue(oldName);
            h.setNewValue(newName);
            rows.add(h);
        }
        historyRepository.saveAll(rows);
    }

    private IssueHistory makeHistory(Issue issue, User actor, String field, String oldVal, String newVal) {
        var h = new IssueHistory();
        h.setIssue(issue);
        h.setChangedBy(actor);
        h.setField(field);
        h.setOldValue(oldVal);
        h.setNewValue(newVal);
        return h;
    }

    /** Managing an archived project's plan is frozen, exactly as issue edits are. Reads still work. */
    private void requireNotArchived(Project project) {
        if (project.isArchived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is archived");
        }
    }

    private SprintNameConflictException duplicate(Sprint existing) {
        return new SprintNameConflictException(existing.getState() == SprintState.COMPLETED
                ? "A completed sprint already uses this name — rename it"
                : "A sprint named '" + existing.getName() + "' already exists in this project");
    }

    /**
     * Which race did {@code create}'s flush lose — the name slot, or the per-project
     * ordinal? Both are legitimate 409s; anything else is a genuine fault and keeps its
     * 500 rather than masquerading as a plausible-looking conflict.
     *
     * <p><strong>Known limitation (deviation from §4.5):</strong> the spec calls for a
     * single automatic retry on a lost sequence race. A failed flush has already marked
     * the transaction rollback-only, so an in-transaction retry cannot work — the caller
     * gets a 409 telling it to retry instead. The window is tiny (two curators creating
     * a sprint in the same project within milliseconds) and the pre-check absorbs the
     * ordinary case.
     */
    private RuntimeException createConflict(DataIntegrityViolationException e, String name) {
        // Resolved ONCE so a single failure never logs two "unexpected constraint" lines.
        String constraint = constraintNameOf(e);
        if (NAME_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)) {
            log.debug("Sprint create lost the unique-name race on constraint [{}]", constraint);
            return new SprintNameConflictException(
                    "A sprint named '" + name + "' already exists in this project");
        }
        if (SEQUENCE_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraint)) {
            log.debug("Sprint create lost the sequence race on constraint [{}]", constraint);
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "Another sprint was created at the same moment — retry");
        }
        log.warn("Sprint create failed on an unexpected constraint [{}] — rethrowing",
                constraint != null ? constraint : "unknown");
        return e;
    }

    /**
     * Is this integrity violation the named constraint losing a race, or a real fault?
     * Translating <em>every</em> {@code DataIntegrityViolationException} into a 409 would
     * hide a genuine 500-class bug behind a plausible-looking conflict — the shape that
     * makes an incident hard to diagnose.
     *
     * <p>Only the constraint NAME is logged here: no SQL, no exception message, no user input
     * (the error-hygiene rule the HD-30 security review signed off on). The one deliberate
     * exception is {@code GlobalExceptionHandler.handleDataIntegrityViolation}, the backstop
     * under paths like this one, which logs the message because the two directions of a
     * {@code 23503} are indistinguishable from SQLSTATE and the client is told neither — see the
     * note on {@code ComponentService.isNameConflict}. Safe because every parameter is bound; not
     * a licence, because a call site with its own sentence already knows its direction.
     */
    private static boolean isConstraint(DataIntegrityViolationException e, String expected) {
        String constraint = constraintNameOf(e);
        boolean match = constraint != null
                ? expected.equalsIgnoreCase(constraint)
                : e instanceof DuplicateKeyException;
        if (match) {
            log.debug("Sprint write lost a race on constraint [{}]",
                    constraint != null ? constraint : "unknown");
        } else {
            log.warn("Sprint write failed on an unexpected constraint [{}] — rethrowing",
                    constraint != null ? constraint : "unknown");
        }
        return match;
    }

    private static String constraintNameOf(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) return cve.getConstraintName();
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
