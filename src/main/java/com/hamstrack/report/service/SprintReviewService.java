package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.util.Points;
import com.hamstrack.issue.dto.SprintRef;
import com.hamstrack.report.dto.SprintReviewIssue;
import com.hamstrack.report.dto.SprintReviewList;
import com.hamstrack.report.dto.SprintReviewResponse;
import com.hamstrack.report.dto.SprintReviewTotals;
import com.hamstrack.report.service.SprintLedger.LedgerIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The sprint review record (reports-proposal §2.4) — <em>what did we commit to, what arrived late,
 * what did we finish, what carried over?</em>
 *
 * <p>Not a chart: five labelled lists of issue rows, each with a count and a point sum, plus the one
 * header line the team reads out at the retro. §1.1 says this is the artefact people actually open
 * and §2.4 says it is the cheapest thing in the document to build — it shares its single ledger
 * statement with the burn-up and adds none of its own.
 *
 * <h2>Points here are the ledger's SNAPSHOT, and the burn-up's are not</h2>
 * Deliberate asymmetry (§2.3, decision 2026-08-19). <strong>Do not "fix" one to match the
 * other.</strong> The retrospective question is <em>what did this weigh when it entered this
 * sprint</em>, and today's estimate destroys that answer: an issue re-pointed from 3 to 8 after the
 * sprint ended would retroactively rewrite what the team committed to, which is the one number a
 * retro exists to look at honestly. The burn-up asks a live question instead ("what is in the
 * sprint now") and reads current points; each is wrong with the other's.
 *
 * <p>Consequently every number in this record is stable for a COMPLETED sprint. The ledger is
 * append-only and id-keyed, so it survives renames; it survives DELETED ISSUES too — those arrive
 * as rows with {@code deleted: true} carrying their snapshotted key and points, never dropped (see
 * {@code SprintReportRepository} on why the join is LEFT).
 *
 * <h2>The five lists, defined exactly once</h2>
 * Everything below is decided against ONE boundary — {@link SprintLedger#endBoundary()}, the
 * sprint's {@code completedAt} for a finished sprint and the instant of the request for a running
 * one. The planned {@code endAt} decides nothing: a sprint completed three days late did not remove
 * anything "after the end", and one completed early did not carry over what it had already stopped
 * doing.
 *
 * <p>It is resolved on the LEDGER, not here, because the burn-up's last point is the same instant
 * and the two files each computed their own until round 2 of this slice — by then they had already
 * drifted apart by one inclusivity at the tie.
 *
 * <ul>
 *   <li><strong>Committed</strong> — in the sprint at {@code startAt}, i.e. in the commitment batch
 *       the start wrote. Membership is asked INCLUSIVELY at that instant because those rows are
 *       stamped with exactly it.</li>
 *   <li><strong>Added after start</strong> — everything else that ever entered. Committed and added
 *       partition the sprint's whole population; the other three lists cut across them.</li>
 *   <li><strong>Removed before end</strong> — not a member at {@code endBoundary}: someone took it
 *       off the sprint while it was running. Distinct from carry-over, and told apart from it
 *       WITHOUT a timestamp comparison, which is the subtle part: a completion stamps
 *       {@code sprints.completed_at} in the conditional UPDATE that arbitrates it, and only then
 *       writes the ledger rows for the issues it moves out — so at {@code completed_at} those
 *       issues are still members, and asking membership strictly before that instant separates the
 *       two populations exactly.</li>
 *   <li><strong>Completed</strong> — a member at the boundary whose {@code closed_at} is strictly
 *       before it ({@link SprintLedger.LedgerIssue#closedBefore}, the same expression the burn-up's
 *       completed line uses at every day boundary). Identical to the last point of that line, on
 *       purpose: two reports over one ledger that disagreed about "done in this sprint" would
 *       discredit both — and they did, at exactly one instant, until the rule moved onto the
 *       shared record.</li>
 *   <li><strong>Carried over</strong> — a member at {@code endBoundary} that was not completed. For
 *       a finished sprint that is exactly what the completion moved to the backlog or to the next
 *       sprint; for a running one it is what is still open right now, which is the same question
 *       asked early.</li>
 * </ul>
 *
 * <p>An issue may appear in several lists — Committed <em>and</em> Completed is the ordinary case.
 * They are labelled views, not a partition. The one pair that IS a partition of the sprint's final
 * state is {@code completed} + {@code carriedOver}, which is why {@link SprintReviewTotals} counts
 * the headline's denominator from those two and not from the commitment.
 *
 * <h2>The one thing a departed issue cannot tell us</h2>
 * A deleted issue's row survives with its key and its entry snapshot, and that is everything the
 * ledger stores about it — <strong>not</strong> whether it was finished. {@code closed_at} lives on
 * the issue, and the issue is gone; nothing is written on a delete out of a COMPLETED sprint at all
 * (that record is frozen — see {@code IssueService.recordDepartureOnDelete}), so there is no row
 * that could have carried it.
 *
 * <p>Such an issue therefore lands in <strong>carriedOver</strong> rather than in
 * {@code completed}: "completed" is a claim the record can no longer prove, and a report may not
 * assert what it cannot show. The row carries {@code deleted: true} and a null {@code closedAt}, so
 * a reader can see that the line is a shadow rather than a verdict. The alternative — dropping it —
 * would silently shrink what the sprint committed to, which is the failure the nulling foreign key
 * exists to prevent, and the one that matters more. Recorded here rather than discovered: if this
 * ever needs fixing, the fix is a snapshot on the ledger row, not a guess in this class.
 *
 * <h2>No per-person anything</h2>
 * {@code assigneeId} is a field on a row so the UI can show an avatar. Nothing here groups, filters,
 * counts or sums by it, and nothing may (§2.5): velocity and the review are the two reports where a
 * per-assignee breakdown would be trivial to add and is refused.
 */
@Service
@RequiredArgsConstructor
public class SprintReviewService {

    private final SprintLedgerReader ledgerReader;

    @Transactional(readOnly = true)
    public SprintReviewResponse review(User actor, UUID workspaceId, UUID projectId,
                                       UUID sprintId) {
        var ledger = ledgerReader.read(actor, workspaceId, projectId, sprintId);
        var sprint = ledger.sprint();
        var startAt = sprint == null ? null : sprint.getStartAt();
        var completedAt = sprint == null ? null : sprint.getCompletedAt();

        if (startAt == null) {
            // No sprint, or one that never started: there is nothing to review, and saying so with
            // five empty lists is a better answer than a 404 to a question about a real project.
            return new SprintReviewResponse(SprintRef.of(sprint), null,
                    sprint == null ? null : sprint.getEndAt(), completedAt,
                    SprintReviewList.EMPTY, SprintReviewList.EMPTY, SprintReviewList.EMPTY,
                    SprintReviewList.EMPTY, SprintReviewList.EMPTY,
                    new SprintReviewTotals(0, null, 0, null, 0, null, 0),
                    ledger.meta());
        }

        // One boundary, resolved on the ledger and shared with the burn-up's last point — see
        // SprintLedger. Computing it here as well is how the two reports drifted apart once.
        var endBoundary = ledger.endBoundary();

        var committed = new ArrayList<LedgerIssue>();
        var addedAfterStart = new ArrayList<LedgerIssue>();
        var removedBeforeEnd = new ArrayList<LedgerIssue>();
        var completed = new ArrayList<LedgerIssue>();
        var carriedOver = new ArrayList<LedgerIssue>();

        for (var issue : ledger.issues()) {
            if (issue.memberAt(startAt)) {
                committed.add(issue);
            } else {
                addedAfterStart.add(issue);
            }
            // The three-way cut lives on LedgerIssue, not here: velocity (§2.5) needs the same
            // sentence for twelve sprints at a time, and two copies of it would be free to
            // disagree about a completed sprint that both reports describe.
            switch (issue.outcomeAt(endBoundary)) {
                case REMOVED_BEFORE_END -> removedBeforeEnd.add(issue);
                case COMPLETED -> completed.add(issue);
                case CARRIED_OVER -> carriedOver.add(issue);
            }
        }

        var committedList = list(committed);
        var completedList = list(completed);
        var carriedOverList = list(carriedOver);
        return new SprintReviewResponse(
                SprintRef.of(sprint), startAt, sprint.getEndAt(), completedAt,
                committedList, list(addedAfterStart), list(removedBeforeEnd), completedList,
                carriedOverList,
                totals(committedList, completedList, carriedOverList, addedAfterStart.size()),
                ledger.meta());
    }

    /**
     * The header line, with the denominator the sentence actually needs.
     *
     * <p>"Completed X of Y" counts Y as <strong>everything the sprint held at its end</strong> —
     * completed plus carried over, two disjoint cuts of one population — so X is a subset of Y and
     * the ratio cannot exceed one. Against the commitment it could: work that arrived after the
     * start can be completed, and a sprint that took late work on and finished it reported
     * "completed 25 of 23". The commitment keeps its own list, its own count and its own point sum
     * beside the outcome, and {@code addedAfterStartCount} is the clause that discloses the gap.
     *
     * <p>The two lists are disjoint by construction (the branch above puts each issue in exactly
     * one of them), so the sums add without re-deriving membership.
     */
    private static SprintReviewTotals totals(SprintReviewList committed, SprintReviewList completed,
                                             SprintReviewList carriedOver, int addedAfterStart) {
        return new SprintReviewTotals(
                committed.count(), committed.points(),
                completed.count() + carriedOver.count(), sum(completed.points(), carriedOver.points()),
                completed.count(), completed.points(),
                addedAfterStart);
    }

    /** Two nullable point sums added, staying null only while BOTH sides had nothing to add. */
    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return Points.normalizeSum(a.add(b));
    }

    /**
     * One list with its count, its point sum and its unestimated count.
     *
     * <p>Rows keep the order the ledger returned them in — the order they first entered the sprint
     * — which is chronological for "added after start", deterministic for the rest, and never
     * dependent on a key's lexicographic accident ({@code DEMO-10} sorts before {@code DEMO-9}).
     *
     * <p>The sum skips unestimated issues instead of treating them as zero, and reports how many it
     * skipped: {@code SectionStats} already draws that distinction on the backlog, and "committed
     * 55 points" means something quite different when six of the twenty-three were never estimated.
     *
     * <p>When NOTHING in the list was estimated the sum is <strong>null</strong>, not zero — the
     * same distinction one row further out. A list of six unestimated issues summing to {@code 0}
     * is indistinguishable on the wire from six issues estimated at zero, and every client would
     * have to re-derive "there are no estimates here" from {@code count > unestimatedCount} to tell
     * them apart. An empty list is null for the same reason: it has no points, rather than zero of
     * them.
     */
    private static SprintReviewList list(List<LedgerIssue> issues) {
        var rows = new ArrayList<SprintReviewIssue>(issues.size());
        BigDecimal points = null;
        int unestimated = 0;
        for (var issue : issues) {
            var snapshot = issue.snapshotPoints();
            if (snapshot == null) {
                unestimated++;
            } else {
                points = points == null ? snapshot : points.add(snapshot);
            }
            rows.add(new SprintReviewIssue(issue.issueId(), issue.key(), issue.title(),
                    issue.typeId(), issue.assigneeId(), issue.statusId(),
                    Points.normalize(snapshot), issue.closedAt(), issue.deleted()));
        }
        return new SprintReviewList(rows.size(), Points.normalize(points), unestimated,
                List.copyOf(rows));
    }
}
