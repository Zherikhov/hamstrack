package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.SprintScopeEvent;
import com.hamstrack.issue.entity.SprintScopeEventType;
import com.hamstrack.issue.entity.SprintState;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.SprintRepository;
import com.hamstrack.issue.repository.SprintScopeEventRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <strong>The only writer of {@code sprint_scope_events}</strong> (HD-137,
 * reports-proposal §5.2) — the append-only ledger the sprint burn-up, the sprint review
 * record and velocity are all computed from.
 *
 * <h2>Why a single writer, spelled out</h2>
 * Sprint membership changes in <strong>eight</strong> places today. Six of them write an
 * {@code issue_history} row with {@code field = 'sprint'}; the seventh writes no history
 * at all (create-time values are never audited) and is exactly the one an "audit the
 * history writers" approach would miss; the eighth writes {@code sprint_id} nowhere at
 * all, and is the one the membership-write scan cannot see:
 *
 * <table border="1">
 *   <caption>The enumerated doors</caption>
 *   <tr><th>#</th><th>Door</th><th>Writer</th><th>History?</th></tr>
 *   <tr><td>1</td><td>{@code POST /issues} carrying {@code sprintId}</td>
 *       <td>{@code IssueService.create}</td><td>no</td></tr>
 *   <tr><td>2</td><td>{@code PATCH /issues/{n}} carrying {@code sprintId}/{@code clearSprint}</td>
 *       <td>{@code IssueService.update}</td><td>yes</td></tr>
 *   <tr><td>3</td><td>{@code POST /issues/{n}/rank} carrying {@code sprintId}/{@code clearSprint}</td>
 *       <td>{@code IssueService.rank}</td><td>yes</td></tr>
 *   <tr><td>4</td><td>{@code POST /sprints/{id}/issues}</td>
 *       <td>{@code SprintService.addIssues}</td><td>yes</td></tr>
 *   <tr><td>5</td><td>{@code DELETE /sprints/{id}/issues/{issueId}}</td>
 *       <td>{@code SprintService.removeIssue}</td><td>yes</td></tr>
 *   <tr><td>6</td><td>completion carry-over (bulk)</td>
 *       <td>{@code SprintService.complete}</td><td>yes</td></tr>
 *   <tr><td>7</td><td>force-delete detach (bulk)</td>
 *       <td>{@code SprintService.delete}</td><td>yes</td></tr>
 *   <tr><td>8</td><td>{@code DELETE /issues/{n}} — the row goes away and its membership
 *       with it</td>
 *       <td>{@code IssueService.delete}</td><td>no</td></tr>
 * </table>
 *
 * <p>Door 3 is <em>not</em> in the proposal's table of five — it was found by the
 * enumerated-doors test, which is the point of having one. If any door misses this
 * class, the scope line is silently wrong: no error, no missing row anywhere a user can
 * see, just a chart that quietly disagrees with reality. {@code SprintScopeLedgerDoorsTest}
 * enumerates the doors from the SOURCE and fails with "you added a door and didn't tell
 * me" when a new one appears, so the failure names the cause rather than a count.
 *
 * <p><strong>Door 8 is outside what the MEMBERSHIP-WRITE scan can enforce, and that limit
 * is stated rather than papered over.</strong> That scan matches statements that WRITE
 * {@code issues.sprint_id} ({@code .setSprint(…)}, {@code SET sprint_id =}); a DELETE ends
 * the membership without writing the column, so no pattern <em>of that shape</em> can find
 * it. Which is a narrower claim than "no scan can": a delete has a shape of its own, and
 * since R4 a SECOND scan matches it ({@code issueRepository.delete…},
 * {@code DELETE FROM issues}) and requires every hit to resolve to a member registered in
 * {@code SprintScopeLedgerDoorsTest.UNSCANNABLE_DOORS}. So the ninth door — a bulk-delete
 * endpoint, a cleanup service, a retention job — fails the build the day it appears, and
 * the exception list is a scanned registry rather than a list somebody has to remember.
 * What no scan can do is prove that the registered member CALLS this class; that
 * obligation is pinned by {@code SprintScopeLedgerIntegrityTest.deletingAnIssue…}.
 * {@code UNSCANNABLE_DOORS} stays a separate map from {@code DOORS}, deliberately, so
 * nobody can read one registry and conclude it covers every door. Door 8 is also the one
 * door that is <em>conditional</em>: a COMPLETED sprint's record is frozen and a delete
 * writes nothing, while an ACTIVE sprint's scope must come back down. See
 * {@code IssueService.recordDepartureOnDelete}.
 *
 * <p>Called from services only, never from a controller: a door is a statement that
 * changes {@code issues.sprint_id}, and those all live in {@code IssueService} /
 * {@code SprintService}.
 *
 * <h2>{@code MANDATORY}, on every method</h2>
 * A ledger row is only ever true <em>together with</em> the membership change that caused
 * it, so this class refuses to be the thing that starts a transaction. All seven call
 * sites are {@code @Transactional} today, which makes a partial write impossible right
 * now — but a future caller from a scheduled job, an {@code @Async} fan-out or a
 * controller shortcut would get its own auto-commit transaction per {@code saveAll} and
 * could commit a scope event while the caller's history write rolled back. That
 * divergence is exactly what this design exists to prevent, and it would show up as a
 * chart that disagrees with the audit log months later. {@code MANDATORY} turns it into
 * an {@code IllegalTransactionStateException} on the first call instead.
 *
 * <h2>The one rule that is not obvious: planning is not scope change</h2>
 * Membership churn on a <strong>FUTURE</strong> sprint writes nothing here. Commitment is
 * an event, not a snapshot: {@code SprintService.start} writes one {@code ADDED} per
 * current member dated {@code startAt}, so an issue added during planning would otherwise
 * be counted twice at {@code startAt} (once when it was added, once by the batch) and the
 * committed scope would be inflated by exactly the amount of planning the team did. The
 * gate lives HERE rather than at each call site for the same reason the writer is single:
 * seven doors, one rule, one place to be wrong.
 *
 * <p>Consequences worth stating: a sprint that is never started has an empty ledger (it
 * has no burn-up either), and "scope at time T" is only defined for {@code T >= startAt},
 * which is precisely the window every sprint report draws.
 *
 * <h2>Bulk paths</h2>
 * The two bulk doors hand over {@code (id, number, storyPoints)} projection rows that the
 * bulk {@code UPDATE … RETURNING} itself produced, and every row here is built against
 * {@code getReferenceById} proxies — the documented trick from
 * {@code SprintService.writeSprintHistory}. Never re-materialise the issues after a bulk
 * UPDATE: the managed copies would be stale and a later flush would write the pre-move
 * {@code sprint_id} back.
 */
@Component
@RequiredArgsConstructor
public class SprintScopeLedger {

    private final SprintScopeEventRepository eventRepository;
    private final IssueRepository issueRepository;
    private final SprintRepository sprintRepository;
    private final WorkspaceRepository workspaceRepository;
    /** Only {@link #recordDepartureBeforeDelete} needs it — see the detach it explains. */
    private final EntityManager entityManager;

    // ------------------------------------------------------------ single-issue doors

    /**
     * Doors 1–5: one issue moved from {@code from} to {@code to} (either may be
     * {@code null} = the backlog). Writes a {@code REMOVED} for the source and an
     * {@code ADDED} for the destination — so a move between two running sprints is two
     * rows, which is what "scope at T" needs from both charts.
     *
     * <p>Call it AFTER the issue has been saved and after every other mutation of the
     * request: {@code storyPoints} is read here, and a PATCH that changes the sprint and
     * the estimate in one body must record the estimate the sprint actually received.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordMove(UUID workspaceId, String projectKey, Issue issue,
                           Sprint from, Sprint to, User actor) {
        var at = OffsetDateTime.now(ZoneOffset.UTC);
        var key = key(projectKey, issue.getNumber());
        var rows = new ArrayList<SprintScopeEvent>(2);
        if (started(from)) {
            rows.add(event(workspaceId, issue.getId(), key, from, SprintScopeEventType.REMOVED,
                    issue.getStoryPoints(), actor, at));
        }
        if (started(to)) {
            rows.add(event(workspaceId, issue.getId(), key, to, SprintScopeEventType.ADDED,
                    issue.getStoryPoints(), actor, at));
        }
        saveAll(rows);
    }

    /**
     * Door 8: the issue itself is about to be <strong>deleted</strong>, which ends its
     * membership without ever writing {@code sprint_id}. One {@code REMOVED}, so a running
     * sprint's scope line comes back down.
     *
     * <p>Separate from {@link #recordMove} only because of the two lines at the end, and
     * they are the whole reason this method exists rather than a call site. Both are
     * load-bearing — R4 briefly demoted the detach to belt-and-braces on the theory that
     * the removed {@code Issue}'s row is still in the database until the DELETE executes,
     * and an experiment settled it the other way (see the last paragraph):
     *
     * <ul>
     *   <li><strong>order</strong> — the row is written while the {@code Issue} is still
     *       MANAGED, i.e. before {@code issueRepository.delete(issue)} is called at all.
     *       Reversed (delete → save), the flush-time to-one check
     *       ({@code AbstractFlushingEventListener.checkForTransientReferences} →
     *       {@code CascadingActions.CHECK_ON_FLUSH} → {@code isChildTransient}) fails
     *       immediately: an entity whose entry in the persistence context is
     *       deleted-or-gone counts as TRANSIENT, and no database snapshot is consulted to
     *       say otherwise ({@code TransientPropertyValueException: … references an unsaved
     *       transient instance}).</li>
     *   <li><strong>flush, then detach</strong> — as a pair, and in that order. The flush
     *       makes the queued INSERT actually execute (detaching an unflushed new entity
     *       would simply drop it); the detach then takes the managed ledger row out of the
     *       persistence context, which is what keeps the COMMIT flush from re-running the
     *       same check over it once the {@code Issue} is in the deleted state. It is not a
     *       precaution: exactly this shape, minus the detach, is what
     *       {@code AttachmentService.removeStoredFilesForIssue} used to do, and deleting
     *       any issue that had an attachment was a 500 until R4 fixed it. Once the DELETE
     *       runs, V18's {@code ON DELETE SET NULL (issue_id)} fires and the ledger keeps
     *       both of its steps with a NULL {@code issue_id} — exactly what that column is
     *       nullable for.
     *       <p>The detach is the only lever available here, and the FK is why.
     *       {@code isChildTransient} throws only when
     *       {@code entry.getStatus().isDeletedOrGone() && !isCascadeDeleteEnabled}, so an
     *       {@code @OnDelete(action = OnDeleteAction.CASCADE)} mapping suppresses it — that
     *       is a third possible fix for the attachment case, whose FK is genuinely
     *       {@code ON DELETE CASCADE}. It is not one here: V18's issue FK is
     *       {@code ON DELETE SET NULL (issue_id)}, and claiming cascade-delete on it would
     *       be a lie about the schema (and would throw away the surviving ledger row this
     *       whole design exists to keep). Scalar projection — the other fix — is likewise
     *       unavailable, because this method's job is to WRITE an entity, not read one.</li>
     * </ul>
     *
     * <p><strong>Two consequences of the flush, stated so nobody re-derives them.</strong>
     * First, {@code EntityManager.flush()} is a FULL flush: it also writes whatever
     * {@code IssueService.delete} has queued above this call — the child-orphaning UPDATEs
     * and their {@code issue_history} inserts. Harmless, but it means a constraint
     * violation from THOSE now surfaces from inside this method and reads as "the ledger
     * broke"; look up before believing that. Second, nothing may re-read
     * {@code sprint_scope_events} in the same transaction after the delete is queued: such
     * a read returns a freshly managed row whose {@code issue} proxy points at the deleted
     * {@code Issue}, which puts the commit-time re-check straight back — R4's report reads
     * this table, so this is a live constraint on where those reads may run, not a
     * hypothetical.
     *
     * <p><strong>What a commit-time throw actually is, since this javadoc used to call it
     * "after the response was decided": a 500.</strong>
     * {@code IssueService.delete} is the transaction boundary
     * ({@code open-in-view=false}, no {@code @Transactional} on the controller, every
     * {@code IssueDeleted} listener is {@code AFTER_COMMIT}), so the failure propagates out
     * of the service call, the whole delete rolls back atomically, and nobody gets a 204
     * over a delete that did not happen. Loud, not silent — but still a delete the user
     * cannot perform, which is how the attachment version of this bug sat unnoticed:
     * nothing in the suite deleted an issue that had one.
     *
     * <p><strong>The experiment, because two readings of the mechanism disagreed.</strong>
     * The question was whether an un-detached row referencing a to-be-deleted entity
     * survives the commit flush (the DELETE has not executed yet, so "does the row exist"
     * would say yes). It does not: {@code AttachmentCrudTenancyTest
     * .deletingAnIssueThatHasAnAttachmentSucceeds} was written against exactly that shape —
     * {@code removeStoredFilesForIssue} left managed attachments pointing at the doomed
     * issue and detached nothing — and it failed at
     * {@code SessionImpl.flushBeforeTransactionCompletion → checkForTransientReferences}
     * with this method's exception. Hibernate treats a deleted-or-gone entry as transient
     * without asking the database. So: the detach is required, and the reading that made it
     * optional is wrong. The evidence is a test rather than a paragraph, which is the only
     * reason it could be settled at all.
     *
     * <p>The caller decides WHETHER to call this — a COMPLETED sprint's record is frozen
     * and gets no row at all (see {@code IssueService.recordDepartureOnDelete}). This
     * method only refuses the one case the whole class refuses: a sprint that never
     * started has no scope to change.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDepartureBeforeDelete(UUID workspaceId, String projectKey, Issue issue,
                                            Sprint from, User actor) {
        if (!started(from)) return;
        var row = event(workspaceId, issue.getId(), key(projectKey, issue.getNumber()), from,
                SprintScopeEventType.REMOVED, issue.getStoryPoints(), actor,
                OffsetDateTime.now(ZoneOffset.UTC));
        // saveAll, not a save-one: SprintScopeEventRepository deliberately declares no
        // single-entity write, so "append-only" is a fact about the type rather than a
        // comment. It returns the managed instances, and the one to detach is that one.
        var written = eventRepository.saveAll(List.of(row));
        entityManager.flush();
        written.forEach(entityManager::detach);
    }

    // ------------------------------------------------------------------- bulk doors

    /**
     * Doors 6–7: every issue of {@code refs} moved from {@code from} to {@code to}
     * ({@code null} = the backlog) by one bulk UPDATE.
     *
     * <p>{@code refs} are the {@code (id, number, storyPoints)} rows the bulk
     * {@code UPDATE … RETURNING} <strong>returned</strong> — scalars, so nothing entered
     * the persistence context that the UPDATE could leave stale, and, more importantly,
     * exactly the set of rows the statement actually moved. Reading a write-list with a
     * separate SELECT first is a different snapshot under READ COMMITTED: a concurrent
     * {@code POST /sprints/{id}/issues} landing in that window either hides a real
     * membership change from the ledger (an {@code ADDED} with no matching
     * {@code REMOVED} — the issue stays in the completed sprint's scope forever while its
     * {@code sprint_id} points elsewhere) or invents a {@code REMOVED} that
     * double-decrements the line. History and the ledger would agree with each other and
     * both be wrong.
     *
     * <p>Written in one {@code saveAll}, which is one persistence-context operation but
     * <strong>not</strong> one round-trip: {@code hibernate.jdbc.batch_size} is unset in
     * this project, so JDBC batching is off and Hibernate issues N INSERTs. Turning
     * batching on changes flush behaviour for every entity in the application, so it is
     * its own change with its own review — filed separately, deliberately not smuggled in
     * here.
     *
     * <p>Door 7 (force-delete) writes rows that the sprint's own {@code ON DELETE
     * CASCADE} removes moments later in the same transaction. That is not a special case
     * worth branching on: the ledger records what happened, and a deleted sprint has no
     * report to be wrong. {@code SprintRepository.deleteByIdAndProject} carries
     * {@code flushAutomatically}, so these inserts are ordered before the DELETE and the
     * cascade takes them cleanly instead of the insert failing on a missing parent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBulkMove(UUID workspaceId, String projectKey, List<Object[]> refs,
                               Sprint from, Sprint to, User actor) {
        if (refs.isEmpty()) return;
        var at = OffsetDateTime.now(ZoneOffset.UTC);
        var rows = new ArrayList<SprintScopeEvent>(refs.size() * 2);
        for (var ref : refs) {
            var issueId = (UUID) ref[0];
            var key = key(projectKey, numberOf(ref));
            var points = pointsOf(ref);
            if (started(from)) {
                rows.add(event(workspaceId, issueId, key, from, SprintScopeEventType.REMOVED,
                        points, actor, at));
            }
            if (started(to)) {
                rows.add(event(workspaceId, issueId, key, to, SprintScopeEventType.ADDED,
                        points, actor, at));
            }
        }
        saveAll(rows);
    }

    // ------------------------------------------------------------------ commitment

    /**
     * The commitment batch — one {@code ADDED} per current member of a sprint that has
     * just started, dated {@code startAt} rather than "now".
     *
     * <p>The ONLY method that does not consult {@link #started(Sprint)}: at this moment
     * the {@code Sprint} the caller holds still says {@code FUTURE} (the flip is a bulk
     * UPDATE that does not refresh the managed copy), and this call IS the start.
     *
     * <p><strong>The stamp is clamped to "now" — a commitment cannot have occurred in the
     * future.</strong> Left unclamped, five {@code ADDED} rows dated {@code now + 30d}
     * plus one ordinary {@code REMOVED} dated today make
     * {@code count(ADDED <= T) - count(REMOVED <= T)} equal <strong>-1</strong> for a
     * month, and a scope line that goes negative is not an edge case, it is a chart that
     * visibly lies. Backdating is untouched: it is a real thing teams do, and the
     * arithmetic survives it.
     *
     * <p>Since HD-137 R3/R4 the clamp is <strong>belt-and-braces rather than the
     * guard</strong>, and as of R4 it is a genuine no-op for every reachable door:
     * {@code SprintService.requireStartHasArrived} refuses (422) to START a sprint dated
     * ahead of now, and the few minutes of clock skew it deliberately tolerates are
     * clamped to now by {@code SprintService.start} BEFORE the same instant is handed to
     * both {@code markActive} and this method. So {@code sprints.start_at} and the stamp
     * on these rows are the same instant, and a burn-up may be computed over the sprint's
     * own window. The clamp stays anyway, because this class is the single writer and must
     * not be correct only for the doors that happen to call it today — a scheduled starter
     * or an importer is the caller it is waiting for. {@code SprintService.update} refuses
     * to move {@code startAt} once the sprint has left FUTURE, so the two cannot drift
     * afterwards either.
     *
     * <p>Must be called inside the start transaction and <strong>after</strong> the
     * conditional {@code markActive} UPDATE has been arbitrated by its affected-row
     * count, so a losing double-click writes nothing. That ordering is also forced by
     * mechanics: {@code markActive} carries {@code clearAutomatically}, and rows queued
     * before it would be evicted as pending inserts (the documented "workspace vanishes"
     * trap).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCommitment(UUID workspaceId, String projectKey, Sprint sprint,
                                 List<Object[]> memberRefs, User actor, OffsetDateTime startAt) {
        if (memberRefs.isEmpty()) return;
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var at = startAt.isAfter(now) ? now : startAt;
        var rows = new ArrayList<SprintScopeEvent>(memberRefs.size());
        for (var ref : memberRefs) {
            rows.add(event(workspaceId, (UUID) ref[0], key(projectKey, numberOf(ref)), sprint,
                    SprintScopeEventType.ADDED, pointsOf(ref), actor, at));
        }
        saveAll(rows);
    }

    // ------------------------------------------------------------------- plumbing

    /**
     * Planning is not scope change: only a sprint that has already started keeps a
     * ledger. See the class javadoc — this is the whole rule, in one place.
     */
    private static boolean started(Sprint sprint) {
        return sprint != null && sprint.getState() != SprintState.FUTURE;
    }

    /** {@code storyPoints} out of an {@code (id, number, storyPoints)} projection row. */
    private static BigDecimal pointsOf(Object[] ref) {
        return (BigDecimal) ref[2];
    }

    /**
     * {@code number} out of the same row. Boxed as {@code Number} because the two sources
     * disagree on type: JPQL hands back a {@code Long}, the native
     * {@code UPDATE … RETURNING} whatever the driver makes of {@code BIGINT}.
     */
    private static long numberOf(Object[] ref) {
        return ((Number) ref[1]).longValue();
    }

    /**
     * The issue key, snapshotted onto the row so it survives the issue (V18: the FK is
     * {@code ON DELETE SET NULL}, so a deleted issue leaves a row that must still be
     * readable as a line of a sprint review).
     *
     * <p><strong>{@code projectKey} is passed in rather than read off the issue</strong>,
     * for the same reason {@code workspaceId} is — see {@link #event}. Both bulk doors
     * have only scalar rows by design, and on the single-issue doors
     * {@code issue.getProject()} may be a lazy proxy in a persistence context a bulk
     * update has just cleared. Every call site already holds the resolved
     * {@code ctx.project()}.
     */
    private static String key(String projectKey, long number) {
        return projectKey + "-" + number;
    }

    /**
     * Every association is a {@code getReferenceById} proxy — no query, and (unlike a
     * managed entity read earlier in the request) nothing that a bulk UPDATE in the same
     * transaction could have left stale.
     *
     * <p><strong>{@code workspaceId} is passed in rather than read off the sprint</strong>,
     * and that is not a style choice. Two of the doors run after a {@code @Modifying}
     * with {@code clearAutomatically} ({@code markActive}, {@code markCompleted}), which
     * detaches every entity the request had loaded AND unhooks their lazy proxies from
     * the session; {@code sprint.getWorkspace()} would then be an uninitialisable proxy
     * (Hibernate cannot promise to serve even an identifier without initialising when the
     * entity uses field access). The caller always has the id anyway — it is the path
     * variable {@code WorkspaceAccessService.resolveProject} has already proved
     * membership of, and the sprint was resolved inside that same project, so passing it
     * invents no scope: it re-states the one the request was authorised against.
     *
     * <p>And it is no longer only a convention: V18 makes {@code workspace_id} part of
     * BOTH parent foreign keys, so a row whose workspace disagrees with its sprint or its
     * issue is rejected by the database rather than quietly filed into another tenant's
     * velocity sweep.
     */
    private SprintScopeEvent event(UUID workspaceId, UUID issueId, String issueKey, Sprint sprint,
                                   SprintScopeEventType type,
                                   BigDecimal points, User actor, OffsetDateTime at) {
        var e = new SprintScopeEvent();
        e.setWorkspace(workspaceRepository.getReferenceById(workspaceId));
        e.setSprint(sprintRepository.getReferenceById(sprint.getId()));
        e.setIssue(issueRepository.getReferenceById(issueId));
        e.setIssueKey(issueKey);
        e.setEvent(type);
        e.setStoryPoints(points);
        e.setActor(actor);
        e.setOccurredAt(at);
        return e;
    }

    private void saveAll(List<SprintScopeEvent> rows) {
        if (!rows.isEmpty()) eventRepository.saveAll(rows);
    }
}
