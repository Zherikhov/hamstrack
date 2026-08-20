package com.hamstrack.report.service;

import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.SprintScopeEventType;
import com.hamstrack.issue.entity.SprintState;
import com.hamstrack.report.dto.ReportMeta;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * One sprint's scope ledger, grouped per issue and ready to be read as membership over time —
 * the single in-memory model <strong>both</strong> R4 reports are computed from (reports-proposal
 * §2.3, §2.4).
 *
 * <p>Shared deliberately. The burn-up and the sprint review ask different questions and even
 * disagree on purpose about which story points to use, but they must never disagree about
 * <em>membership</em>: "what was in the sprint at time T" has exactly one answer, and two reports
 * deriving it from two statements would eventually give two. So the statement runs once, this
 * record holds its result, and each report applies its own weighting to the same facts.
 *
 * <h2>Where a sprint's record stops: {@link #endBoundary()}, an instant and not a day</h2>
 * The last question both reports ask is asked against one instant: {@code completed_at} for a
 * COMPLETED sprint, the moment of the request for a running one. The planned {@code end_at}
 * decides nothing — a sprint completed three days late did not remove anything "after the end",
 * and one completed early did not carry over what it had already stopped doing.
 *
 * <p><strong>An instant rather than "the end of the last day", and that is not a detail.</strong>
 * A completion is one transaction: it stamps {@code completed_at} and only then writes a
 * {@code REMOVED} for every unfinished issue it moves out. Measure the burn-up's last point at
 * the following midnight and those removals are inside it — the scope line dives to exactly the
 * work that was finished, the two lines meet, and <em>every</em> completed sprint renders as
 * having delivered 100% of its final scope. That is a chart that cannot show a miss, which is the
 * one thing the report exists to be able to show. Measured at {@code completed_at} instead, the
 * last point is the sprint as it stood when it was closed: scope 23, completed 18.
 *
 * <p>It lives HERE rather than in either service because both of them need it and each once
 * computed its own: the burn-up's series end and the review's list boundary were the same
 * sentence written twice, and they had already drifted by one inclusivity —
 * {@code closedAt.isBefore(end)} against {@code !closedAt.isAfter(end)} — so an issue closed at
 * exactly {@code completed_at} was in the review's {@code completed} list and missing from the
 * burn-up's final completed value. Unreachable in practice (completing a sprint closes nothing),
 * and repaired rather than argued with: there is now one boundary and one rule
 * ({@link LedgerIssue#closedBefore}), and the drift vector is closed instead of the instance.
 *
 * <p>Resolving "now" once also makes a running sprint's two reports agree with each other when
 * they are fetched by one page: two evaluations of {@code now()} in one request are two different
 * instants, and an issue closed between them would land in different lists.
 *
 * @param sprint      the resolved sprint, or {@code null} when the project has no ACTIVE sprint and
 *                    the caller named none. Every field below is then empty
 * @param issues      every issue that has ever been in this sprint, each with its moves in
 *                    chronological order
 * @param truncated   whether the row cap bit (see {@code SprintReportRepository.ledger})
 * @param cap         the cap that would bite, reported either way
 * @param firstIssueAt the project's earliest issue — {@code meta.firstIssueAt}, a property of the
 *                    project and never of this sample
 * @param endBoundary <strong>where this sprint's record stops</strong> — {@code completed_at} for
 *                    a COMPLETED sprint and the instant of this request for a running one, and
 *                    {@code null} when there is no sprint at all. Resolved ONCE, here, because
 *                    both reports decide their last question against it and two evaluations of
 *                    "now" in one request are already two different instants. See
 *                    {@link #endBoundary()} for why it is an instant and not the end of a day
 * @param basedOnIssues how many distinct issues this sprint's ledger holds, counted in the
 *                    database and <strong>not</strong> derived from {@link #issues} — see
 *                    {@link #meta()}
 */
public record SprintLedger(Sprint sprint, List<LedgerIssue> issues, boolean truncated, int cap,
                           OffsetDateTime firstIssueAt, OffsetDateTime endBoundary,
                           long basedOnIssues) {

    /**
     * The shared provenance block, identical in shape on both R4 responses.
     *
     * <p><strong>{@code basedOnIssues} is the counted number, never {@code issues.size()}.</strong>
     * They are equal until the row cap bites and then they diverge in the direction that matters:
     * the family contract ({@link ReportMeta}) is that this field says how many issues the report
     * was ABOUT — which, when truncated, is deliberately larger than what came back, because it is
     * exactly what the reader can no longer see. Sizing it from the surviving rows made the one
     * field whose job is to disclose truncation shrink along with the data it was disclosing, so a
     * reader would have been told the report covered fewer issues at the moment they most needed
     * the true count.
     */
    public ReportMeta meta() {
        return new ReportMeta(OffsetDateTime.now(ZoneOffset.UTC), basedOnIssues, truncated, cap,
                firstIssueAt, List.of());
    }

    /** Nothing to report on: no sprint, or a sprint that never started and never held anything. */
    public boolean isEmpty() {
        return sprint == null || issues.isEmpty();
    }

    /**
     * <strong>Where a sprint's record stops — the one definition, for every report that reads this
     * ledger.</strong>
     *
     * <p>{@code completed_at} for a sprint that has been completed, and <strong>now</strong> for
     * everything else, including one that has run past its planned {@code end_at}: the line ends
     * where it ends, and drawing a record out to a future planned end would be a projection no
     * report here makes.
     *
     * <p>It lives on this record rather than inside a reader because R5 (velocity) resolves it for
     * <em>each</em> of up to twelve sprints without going through {@link SprintLedgerReader} at
     * all. R4 already paid for two copies of this sentence drifting apart by one inclusivity; a
     * third copy, in a loop, would have been the same bug spread over a year of history — and
     * nothing about the resulting bar chart would have looked wrong.
     *
     * <p>See {@link #endBoundary()} for why it is an instant and not the end of a day. That
     * reasoning is what makes a completed sprint's chart able to show a miss at all, and it is
     * exactly as load-bearing for a bar as it is for a line.
     */
    public static OffsetDateTime endBoundaryOf(Sprint sprint) {
        return endBoundaryOf(sprint.getState(), sprint.getCompletedAt());
    }

    /**
     * The same boundary, from the two columns that define it rather than from the entity.
     *
     * <p>Velocity reads its sample as a five-scalar projection
     * ({@code SprintRepository.CompletedSprint}) precisely so that no {@code Sprint} — and
     * therefore no {@code Sprint.createdBy} — reaches the report at all, so it cannot call the
     * overload above. This is <strong>the same sentence</strong>, not a second one: the entity
     * form now delegates here, so there is still exactly one definition of where a sprint's
     * record stops. That is the whole point of the method existing (see above) and it survives
     * the projection only because the delegation goes this way round.
     */
    public static OffsetDateTime endBoundaryOf(SprintState state, OffsetDateTime completedAt) {
        return state == SprintState.COMPLETED && completedAt != null
                ? completedAt
                : OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * One issue's whole history with respect to <em>this</em> sprint, plus the issue as it stands
     * today — or as much of it as still exists.
     *
     * <p><strong>{@code deleted} is not an edge case, it is the reason the ledger is shaped the way
     * it is.</strong> V18's issue FK is {@code ON DELETE SET NULL}, so a deleted issue leaves its
     * events behind with {@link #issueId} null and every live-issue field null; {@link #key} and
     * {@link #snapshotPoints} are the snapshots that keep the row readable. Neither report may drop
     * such an issue: the scope line keeps both of its steps and the review keeps its line, because
     * a sprint's record must not be rewritten by something that happened after it ended.
     *
     * @param issueId       the live issue, or {@code null} if it has been deleted
     * @param key           the key snapshotted on its events — always present
     * @param deleted       whether the issue is gone
     * @param title         the live title, {@code null} once deleted
     * @param typeId        the live type, {@code null} once deleted
     * @param assigneeId    the live assignee, {@code null} when unassigned or deleted
     * @param statusId      the live status, {@code null} once deleted
     * @param closedAt      the live {@code closed_at}: null while open, and <strong>cleared on
     *                      reopen</strong>, so an issue that was reopened after the sprint leaves
     *                      that sprint's completed line retroactively. That is the same honesty the
     *                      flow report footnotes (§2.1) rather than a stale copy of history
     * @param currentPoints today's estimate — what the BURN-UP weighs with
     * @param snapshotPoints what it weighed when it first entered this sprint — what the REVIEW
     *                      weighs with, and the only weight available at all once the issue is gone
     * @param moves         its ADDED/REMOVED events for this sprint, oldest first
     */
    public record LedgerIssue(UUID issueId, String key, boolean deleted, String title, UUID typeId,
                              UUID assigneeId, UUID statusId, OffsetDateTime closedAt,
                              BigDecimal currentPoints, BigDecimal snapshotPoints,
                              List<Move> moves) {

        /**
         * One membership change: when, which way, and by whom.
         *
         * <p>{@code actorId} is {@code null} in two cases, and the second is a decision rather
         * than a foreign key. The first is the ordinary one — the ledger's {@code actor_id} is
         * {@code ON DELETE SET NULL}, because the event is a fact about the sprint and must
         * outlive the account that caused it.
         *
         * <p>The second: <strong>a move belonging to a DELETED issue reports no actor at
         * all</strong>, nulled by {@code SprintLedgerReader} rather than by the database (the row
         * keeps its {@code actor_id}; this is about what a report may say). The ledger
         * deliberately preserves a departed issue's key and its entry estimate, so that deleting
         * an issue cannot quietly rewrite a finished sprint — but attribution is wider than that
         * and was never argued for. {@code issue_history.issue_id} is {@code ON DELETE CASCADE},
         * so a delete erases every other record of who touched that issue and when; leaving the
         * scope log as the one surviving place in the product that says "user X moved DEMO-77 at
         * 14:32" would make a delete strengthen the audit trail in one corner while erasing it
         * everywhere else. A delete is a strong enough erasure signal to be honoured here too,
         * and the audit value of a name on a row nobody can click through to is small. The step,
         * its instant, its direction and the key all survive — only the person does not.
         */
        public record Move(OffsetDateTime at, SprintScopeEventType event, UUID actorId) {

            public boolean added() {
                return event == SprintScopeEventType.ADDED;
            }
        }

        /**
         * Was this issue in the sprint <strong>strictly before</strong> {@code instant}?
         *
         * <p>The exclusive form, and the one nearly everything uses. A day's value is asked for at
         * the following UTC midnight, and an event stamped exactly at midnight belongs to the day
         * that is starting, not the one that just ended. It is also what separates a completed
         * sprint's carry-over from a removal made during it: {@code sprints.completed_at} is
         * stamped by the conditional UPDATE that arbitrates the completion, <em>before</em> the
         * ledger rows for the issues it then moves out, so at {@code completed_at} those issues are
         * still members — which is exactly what "carried over" means.
         */
        public boolean memberBefore(OffsetDateTime instant) {
            return memberAsOf(instant, false);
        }

        /**
         * Was this issue in the sprint <strong>at</strong> {@code instant}, inclusive?
         *
         * <p>Used for one thing: the committed scope. The commitment batch is stamped with exactly
         * {@code startAt}, so the exclusive form would report every sprint as starting empty.
         */
        public boolean memberAt(OffsetDateTime instant) {
            return memberAsOf(instant, true);
        }

        /**
         * Was this issue closed <strong>strictly before</strong> {@code instant}?
         *
         * <p>The single "done by then" rule, used by the burn-up's completed line at every day
         * boundary and by the review's {@code completed} list at {@link SprintLedger#endBoundary()}
         * — one expression, so the two reports cannot answer "was it done in this sprint"
         * differently. They each had their own before, and the two disagreed at the tie.
         *
         * <p>Exclusive, matching {@link #memberBefore}. What that symmetry buys is
         * <strong>agreement between the two reports</strong>: both ask the same question with the
         * same strictness at the same instant, so the burn-up's final completed value and the
         * review's {@code completed} list name the same issues. It is <em>not</em> what keeps the
         * burn-up's two lines from crossing — see {@code SprintBurnupService.paint}, which
         * intersects each completed run with the membership run it belongs to, so the containment
         * survives any change to this rule.
         *
         * <p>At the tie the exclusive form fails <strong>conservatively</strong>: an issue closed
         * at exactly {@code completed_at} is carried over rather than completed, so the report
         * under-claims delivery instead of over-claiming it. That is the same direction as the
         * deleted-issue rule ({@code SprintReviewService} — a departed issue lands in carried over,
         * never in completed): a report may not assert what it cannot show, and both edge cases
         * lean the same way on purpose rather than by coincidence.
         */
        public boolean closedBefore(OffsetDateTime instant) {
            return closedAt != null && closedAt.isBefore(instant);
        }


        /**
         * <strong>The three-way cut of a sprint's final state, defined exactly once</strong>
         * (HD-139 R5): was this issue taken OUT while the sprint ran, was it FINISHED by the
         * boundary, or did it CARRY OVER?
         *
         * <p>It lives here, on the record both readers share, because two reports now need it —
         * {@code SprintReviewService} for three of its five lists, and {@code VelocityService} for
         * two of every bar's four numbers, twelve sprints at a time. Written twice, the two would
         * be free to disagree about a completed sprint, and the way that failure presents is a
         * velocity band computed from numbers the sprint review beside it does not show. The
         * membership question already has one implementation ({@link #memberBefore}); so does the
         * closure question ({@link #closedBefore}); this is the one sentence that composes them,
         * and it is the sentence with the ordering in it.
         *
         * <p>The ordering is the subtle part and it is not commutative. Removal is asked FIRST,
         * because an issue can be both closed and removed and the record must say it left: a
         * completion stamps {@code sprints.completed_at} in the conditional UPDATE that arbitrates
         * it and only then writes the ledger rows for the issues it moves out, so at
         * {@code completed_at} the carried-over issues are still members while anything a person
         * took off the sprint earlier is not. Asking closure first would move every issue somebody
         * removed-after-finishing into {@code COMPLETED} and quietly inflate every velocity bar.
         *
         * @param endBoundary {@link SprintLedger#endBoundaryOf}, never the planned {@code endAt}
         */
        public Outcome outcomeAt(OffsetDateTime endBoundary) {
            if (!memberBefore(endBoundary)) {
                return Outcome.REMOVED_BEFORE_END;
            }
            return closedBefore(endBoundary) ? Outcome.COMPLETED : Outcome.CARRIED_OVER;
        }

        /**
         * What became of one issue by {@link SprintLedger#endBoundary()} — a partition, unlike the
         * sprint review's five lists, three of which are cuts of this one and two of which
         * (committed / added after start) cut the same population the other way.
         */
        public enum Outcome {

            /** Somebody took it off the sprint while it was running. Not carry-over. */
            REMOVED_BEFORE_END,

            /** Still a member at the boundary, and closed strictly before it. */
            COMPLETED,

            /**
             * Still a member at the boundary and not closed by then — what the completion moved to
             * the backlog or to the next sprint. A DELETED issue lands here too: its
             * {@code closed_at} died with it, so "completed" is a claim the record can no longer
             * prove, and a report may not assert what it cannot show.
             */
            CARRIED_OVER
        }

        private boolean memberAsOf(OffsetDateTime instant, boolean inclusive) {
            boolean in = false;
            for (var move : moves) {
                int cmp = move.at().compareTo(instant);
                if (cmp > 0 || (cmp == 0 && !inclusive)) break;
                in = move.added();
            }
            return in;
        }
    }
}
