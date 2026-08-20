package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.common.util.Points;
import com.hamstrack.issue.dto.SprintRef;
import com.hamstrack.report.dto.BurnupPoint;
import com.hamstrack.report.dto.ReportMeasure;
import com.hamstrack.report.dto.ScopeChange;
import com.hamstrack.report.dto.SprintBurnupResponse;
import com.hamstrack.report.service.SprintLedger.LedgerIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The sprint burn-up (reports-proposal §2.3) — <em>will this sprint land, and what happened to the
 * plan?</em>
 *
 * <p>Two lines over the sprint's days: <strong>scope</strong>, which steps up when work joins and
 * down when it leaves, and <strong>completed</strong>, which is the part of that scope already
 * closed. Below them, the scope-change log, so every step in the first line has a timestamp, an
 * issue and an author.
 *
 * <h2>The three rules that make this not a burndown</h2>
 * <ol>
 *   <li><strong>Scope change is membership change only.</strong> A re-estimate is not a scope event
 *       and is never drawn as a step. This class reads {@code sprint_scope_events}, which records a
 *       row only when membership changes, so the rule is enforced by the data rather than by
 *       remembering it. It is the single rule that deletes the documented disagreement burndowns
 *       cause (§1.2): a jump in this line always has a cause with a name.</li>
 *   <li><strong>The line ends where it ends.</strong> The last point is today for a running sprint
 *       and the completion day for a finished one. No projection, no "at this rate you finish
 *       Thursday" — forecasting is §2.5, with a stated sample size, and inventing it here would be
 *       a forecast with a sample size of one.</li>
 *   <li><strong>The ideal guide is drawn to the COMMITTED scope</strong>, not to the current one:
 *       the client draws it from {@code (startAt, 0)} to {@code (endAt, committedAtStart)}. A
 *       sprint that took work on therefore sits above its guide, which is information rather than a
 *       verdict. Regenerating the guide against today's scope would make every sprint look
 *       on-plan.</li>
 * </ol>
 *
 * <h2>Points are the issue's CURRENT estimate — and the review's are not</h2>
 * Decision, 2026-08-19 (§2.3), and the asymmetry is deliberate. <strong>Do not "fix" either report
 * to match the other</strong>: they answer different questions and each is wrong with the other's
 * points.
 *
 * <ul>
 *   <li><strong>Here: current points.</strong> The consequence is worth stating plainly — a
 *       re-estimate moves the whole scope line, <em>including its past</em>, so a chart read
 *       yesterday can look different today. The UI footnotes it ("Points reflect current
 *       estimates"). The alternative is not buildable anyway: the ledger writes a row only on a
 *       membership change, so a per-day estimate history does not exist and cannot be
 *       reconstructed from anything we store. What freezing entry-time points WOULD buy is an
 *       immovable history at the price of a re-estimate being invisible everywhere and a sprint's
 *       scope disagreeing with the same issues' estimates on every other screen.</li>
 *   <li><strong>{@code SprintReviewService}: the ledger's snapshot.</strong> The retro question is
 *       what a thing weighed <em>when it entered</em>, and current points destroy that answer.</li>
 * </ul>
 *
 * <p>One consequence is inherited rather than chosen: an issue that has since been DELETED has no
 * current estimate, so it is weighed by its snapshot — the only number left. It keeps its steps in
 * the line either way; dropping it is the one thing that is not allowed (see
 * {@code SprintReportRepository}).
 *
 * <h2>Capabilities gate the UI, never this</h2>
 * {@code measure=POINTS} answers with a points series in a project with {@code estimation} off, and
 * every sprint answers whatever {@code board} says (Rule A). Unestimated issues contribute 0 and
 * are counted in {@code unestimatedCount} — never a silent zero (§6, documented failure mode #4).
 *
 * <h2>The series can stop before the sprint does, and it says so</h2>
 * A sprint's day count is bounded by real time and needs no cap in any realistic case — but
 * {@code start_at} may be BACKDATED arbitrarily when a sprint is started (only future dates are
 * refused), so "the last day minus the first" is caller-influenced and one request could otherwise
 * ask for fifty thousand points. It is bounded by {@code app.reports.max-window-days}, the same
 * number that bounds every other report's window, and the FIRST days are the ones kept: they carry
 * the commitment, without which every later number means nothing.
 *
 * <p>Disclosed as {@link SprintBurnupResponse#seriesTruncatedAt()} — <strong>the day the chart
 * stops at</strong>, null when it does not. Not through {@code meta.truncated}, which round 2 of
 * this slice found overloaded: that flag means "the {@code app.reports.max-rows} cap bit" and is
 * printed beside {@code meta.cap}, so a day-clipped twelve-issue sprint was answering
 * {@code basedOnIssues: 12, truncated: true, cap: 20000} — a banner quoting twenty thousand at a
 * report that dropped nothing of the kind. Two different limits, two different signals, and the
 * one that fired names the day it fired on.
 *
 * <p>Everything else in the response is clipped WITH it: {@code scopeChanges} stops at the same
 * boundary and {@code unestimatedCount} is measured there. A chart that ends in 2019 beside a
 * change log running to today is three numbers describing three windows, which is the "these
 * numbers don't match what I expected" failure {@code meta} exists to prevent. The sprint review
 * has no such bound — it returns lists, not a day series — so on a clipped sprint the two reports
 * legitimately describe different spans, which is exactly what the field announces.
 *
 * <p>It is not a 400: the caller stated no window here — the sprint did — so there is nothing for
 * them to correct, and refusing the report would leave a legitimately long sprint with no chart.
 *
 * <h2>Cost</h2>
 * No statement of its own: everything is computed from the one ledger read
 * {@link SprintLedgerReader} performs, in memory, over a result bounded by sprint size. The series
 * is O(days + moves × log days) — see {@link #series}, which paints each issue's membership
 * intervals onto a difference array rather than asking every issue about every day.
 */
@Service
@RequiredArgsConstructor
public class SprintBurnupService {

    private final SprintLedgerReader ledgerReader;
    private final ReportProperties reportProperties;

    @Transactional(readOnly = true)
    public SprintBurnupResponse burnup(User actor, UUID workspaceId, UUID projectId,
                                       UUID sprintId, ReportMeasure requested) {
        var measure = requested == null ? ReportMeasure.COUNT : requested;
        var ledger = ledgerReader.read(actor, workspaceId, projectId, sprintId);
        var sprint = ledger.sprint();
        var startAt = sprint == null ? null : sprint.getStartAt();

        // A sprint that never started has no burn-up, and neither has "no sprint at all". Both are
        // 200s with an empty series: commitment is an event, and it has not happened yet.
        if (startAt == null) {
            return new SprintBurnupResponse(SprintRef.of(sprint), null,
                    sprint == null ? null : sprint.getEndAt(), measure,
                    BigDecimal.ZERO, 0, List.of(), List.of(), null, ledger.meta());
        }

        var issues = ledger.issues();
        var weights = new BigDecimal[issues.size()];
        for (int i = 0; i < issues.size(); i++) {
            weights[i] = weight(issues.get(i), measure);
        }

        var firstDay = day(startAt);
        // Where the lines stop: the completion instant, or now — resolved once, on the ledger, and
        // shared with the sprint review. Not the end of the completion DAY; SprintLedger says why.
        var seriesEnd = ledger.endBoundary();
        var lastDay = day(seriesEnd);
        if (lastDay.isBefore(firstDay)) {
            lastDay = firstDay;
        }
        int maxDays = reportProperties.maxWindowDays();
        boolean clipped = ChronoUnit.DAYS.between(firstDay, lastDay) + 1 > maxDays;
        if (clipped) {
            lastDay = firstDay.plusDays(maxDays - 1L);
        }

        var boundaries = boundaries(firstDay, lastDay, seriesEnd);
        var lastEnd = boundaries[boundaries.length - 1];
        var series = series(issues, weights, firstDay, boundaries);
        var committedAtStart = BigDecimal.ZERO;
        int unestimated = 0;
        for (int i = 0; i < issues.size(); i++) {
            var issue = issues.get(i);
            // Inclusive at startAt: the commitment batch is stamped with exactly that instant, so
            // the exclusive form would report every sprint as having started empty.
            if (issue.memberAt(startAt)) {
                committedAtStart = committedAtStart.add(weights[i]);
            }
            if (issue.memberBefore(lastEnd) && unestimated(issue)) {
                unestimated++;
            }
        }

        return new SprintBurnupResponse(
                SprintRef.of(sprint), startAt, sprint.getEndAt(), measure,
                Points.normalizeSum(committedAtStart), unestimated,
                series, scopeChanges(issues, weights, startAt, clipped ? lastEnd : null),
                clipped ? lastDay : null, ledger.meta());
    }

    // ------------------------------------------------------------------ the lines

    /**
     * The instant each day's values are computed at, one per point: the following UTC midnight, or
     * {@code seriesEnd} where that comes first — which is the case for exactly one day, the last,
     * unless the series was clipped, in which case for none.
     *
     * <p>An event stamped exactly at midnight therefore belongs to the day it opens, and the final
     * point is the sprint as it stood when it stopped rather than at the end of the calendar day it
     * stopped in (see {@link SprintLedger} for why that distinction is the difference between a
     * chart that can show a miss and one that cannot). The same UTC day boundaries every other
     * report in the epic buckets on (§6).
     *
     * <p>Materialised as an array rather than recomputed per issue because it is what
     * {@link #series} binary-searches: the array is non-decreasing (the last entry can equal the
     * one before it, when a sprint was completed at exactly midnight), which is all a lower-bound
     * search needs.
     */
    private static OffsetDateTime[] boundaries(LocalDate firstDay, LocalDate lastDay,
                                               OffsetDateTime seriesEnd) {
        int days = (int) ChronoUnit.DAYS.between(firstDay, lastDay) + 1;
        var boundaries = new OffsetDateTime[days];
        for (int d = 0; d < days; d++) {
            boundaries[d] = boundaryOf(firstDay.plusDays(d), seriesEnd);
        }
        return boundaries;
    }

    /**
     * One point per UTC day: how much was in the sprint at that day's boundary, and how much of it
     * was closed by then.
     *
     * <p><strong>Painted from each issue's move list, not sampled per day</strong> (HD-29 R4 round
     * 2). The obvious form of this loop — for each day, for each issue, ask
     * {@link LedgerIssue#memberBefore} — is O(days × issues × moves), a product of two numbers that
     * {@code ReportProperties} bounds <em>independently</em>: the defaults multiply out to ~7.3
     * million comparisons and {@code BigDecimal} allocations for a single request, and the
     * documented ceilings ({@code max-window-days=3650}, {@code max-rows=50000}) to ~182 million.
     * The throttle bounds request RATE and imposes no concurrency bound at all, so that product is
     * per concurrent caller. A sprint's ledger is hundreds of rows in practice and days are bounded
     * by real time — but {@code start_at} may be backdated arbitrarily, which is precisely why the
     * day bound exists, and a bound that only turns a slow response into a slower one is not much
     * of a bound.
     *
     * <p>So each issue is walked ONCE, over its own moves: every membership interval becomes a
     * contiguous run of days, added to a difference array and prefix-summed at the end. Cost is
     * O(days + moves × log days), and the BigDecimal arithmetic drops from days × issues additions
     * to two per interval.
     *
     * <p><strong>This is not a second implementation of membership</strong>, which is the reason
     * the first version of this method refused a cursor and the reason that refusal was right: the
     * intervals are read off {@link LedgerIssue#moves()}, the same list {@code memberBefore} walks,
     * with the same rule — a day counts when its boundary is strictly after an {@code ADDED} and at
     * or before the matching {@code REMOVED}, which is exactly "the state left by every move
     * strictly before the boundary". A second statement, or a second notion of what membership
     * means, is what the shared ledger exists to prevent; the burn-up and the review still read one
     * list through one record.
     *
     * <p>{@code completed} is bounded above by {@code scope} by construction, and the construction
     * is {@link #paint}: the completed run is intersected with the membership run it belongs to
     * ({@code completedFrom = max(from, closedFrom)}, guarded by {@code completedFrom <= to}), so
     * every day it covers is a day the same interval already added to {@code scope}, and weights
     * are non-negative. Work removed from a sprint takes its completion out with it; the two lines
     * can meet but never cross.
     *
     * <p><strong>The {@code max(from, …)} clamp is what holds that, not the closure rule's
     * strictness</strong> — worth stating because the wrong attribution has an obvious next step:
     * believing the strictness is what protects the invariant makes the clamp look redundant, and
     * writing {@code completedDelta[closedFrom]} directly lets an issue closed BEFORE it joined the
     * sprint count as completed outside its own scope interval, which is the crossing. What
     * matching strictness buys is a different and also real property: the burn-up and the review
     * agree about "done in this sprint" (see {@link LedgerIssue#closedBefore}).
     */
    private static List<BurnupPoint> series(List<LedgerIssue> issues, BigDecimal[] weights,
                                            LocalDate firstDay, OffsetDateTime[] boundaries) {
        int days = boundaries.length;
        var scopeDelta = new BigDecimal[days + 1];
        var completedDelta = new BigDecimal[days + 1];
        Arrays.fill(scopeDelta, BigDecimal.ZERO);
        Arrays.fill(completedDelta, BigDecimal.ZERO);

        for (int i = 0; i < issues.size(); i++) {
            var issue = issues.get(i);
            // The first day whose boundary is after the closure — from there on, and only while it
            // is a member, this issue is on the completed line. `days` means "never, in this
            // window", which covers an open issue and one closed after the last point.
            int closedFrom = issue.closedAt() == null
                    ? days
                    : firstDayAfter(boundaries, issue.closedAt());
            OffsetDateTime enteredAt = null;
            for (var move : issue.moves()) {
                if (move.added()) {
                    // A repeated ADD does not re-enter: the interval is still the first one's.
                    if (enteredAt == null) {
                        enteredAt = move.at();
                    }
                } else if (enteredAt != null) {
                    paint(scopeDelta, completedDelta, weights[i], boundaries, enteredAt, move.at(),
                            closedFrom);
                    enteredAt = null;
                }
            }
            if (enteredAt != null) {                        // still in the sprint at the last point
                paint(scopeDelta, completedDelta, weights[i], boundaries, enteredAt, null,
                        closedFrom);
            }
        }

        var points = new ArrayList<BurnupPoint>(days);
        var scope = BigDecimal.ZERO;
        var completed = BigDecimal.ZERO;
        for (int d = 0; d < days; d++) {
            scope = scope.add(scopeDelta[d]);
            completed = completed.add(completedDelta[d]);
            points.add(new BurnupPoint(firstDay.plusDays(d), Points.normalizeSum(scope),
                    Points.normalizeSum(completed)));
        }
        return List.copyOf(points);
    }

    /**
     * Add one membership interval to the two difference arrays.
     *
     * <p>{@code enteredAt} is when it joined and {@code leftAt} when it left ({@code null} = still
     * in). The days it covers are those whose boundary is strictly after {@code enteredAt} and at
     * or before {@code leftAt} — the exclusive-then-inclusive pair that makes this identical to
     * asking {@link LedgerIssue#memberBefore} at every boundary, an event stamped exactly at a
     * boundary belonging to the day that boundary opens.
     *
     * <p><strong>{@code completedFrom = max(from, closedFrom)} is the invariant, not an
     * optimisation.</strong> It intersects the completed run with THIS membership run, so the days
     * marked completed are always a subrange of the days marked in scope — which is what keeps the
     * two lines from crossing, whatever closure rule {@link LedgerIssue#closedBefore} uses.
     * Writing {@code completedDelta[closedFrom]} instead would count an issue closed before it
     * joined the sprint as completed outside its own scope interval.
     */
    private static void paint(BigDecimal[] scopeDelta, BigDecimal[] completedDelta,
                              BigDecimal weight, OffsetDateTime[] boundaries,
                              OffsetDateTime enteredAt, OffsetDateTime leftAt, int closedFrom) {
        int days = boundaries.length;
        int from = firstDayAfter(boundaries, enteredAt);
        int to = leftAt == null ? days - 1 : firstDayAfter(boundaries, leftAt) - 1;
        if (from >= days || from > to) {
            return;                                          // wholly outside the plotted window
        }
        scopeDelta[from] = scopeDelta[from].add(weight);
        scopeDelta[to + 1] = scopeDelta[to + 1].subtract(weight);

        int completedFrom = Math.max(from, closedFrom);
        if (completedFrom <= to) {
            completedDelta[completedFrom] = completedDelta[completedFrom].add(weight);
            completedDelta[to + 1] = completedDelta[to + 1].subtract(weight);
        }
    }

    /**
     * The first day whose boundary is strictly after {@code instant}, or {@code boundaries.length}
     * when none is — a lower-bound search over the non-decreasing boundary array.
     */
    private static int firstDayAfter(OffsetDateTime[] boundaries, OffsetDateTime instant) {
        int low = 0;
        int high = boundaries.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (boundaries[mid].isAfter(instant)) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    /**
     * Every membership change <strong>after</strong> the start, oldest first.
     *
     * <p>The commitment batch is not a change: those {@code ADDED} rows are all stamped exactly
     * {@code startAt}, they are already reported as {@code committedAtStart} and as the height of
     * the first point, and listing them here would bury the four real changes under twenty-three
     * rows saying "the sprint started".
     *
     * <p>Ties are broken by key so two issues moved in the same batch cannot swap places between
     * requests.
     *
     * <p><strong>{@code logEnd} clips the log to the same window as the chart, and is null when
     * the chart was not clipped</strong> (HD-29 R4 round 2). An over-long sprint's series stops at
     * {@code app.reports.max-window-days}; before this, the log did not, so the response could
     * carry a 2019 chart beside a 2026 change log and an unestimated count measured in 2019 —
     * three numbers describing three different windows, which is exactly the "these numbers don't
     * match what I expected" failure the whole {@code meta} convention exists to prevent. When the
     * chart is NOT clipped the log runs to the end of the ledger, which is deliberate and is why
     * this is a nullable bound rather than always the last boundary: a completed sprint's
     * carry-over rows are stamped a moment AFTER {@code completed_at}, so bounding the log at the
     * last point would silently drop the completion's own moves — the ones that explain where the
     * remaining scope went.
     */
    private static List<ScopeChange> scopeChanges(List<LedgerIssue> issues, BigDecimal[] weights,
                                                  OffsetDateTime startAt, OffsetDateTime logEnd) {
        var changes = new ArrayList<ScopeChange>();
        for (int i = 0; i < issues.size(); i++) {
            var issue = issues.get(i);
            for (var move : issue.moves()) {
                if (!move.at().isAfter(startAt)) {
                    continue;
                }
                if (logEnd != null && !move.at().isBefore(logEnd)) {
                    continue;                    // past the day the clipped chart stops at
                }
                var delta = move.added() ? weights[i] : weights[i].negate();
                changes.add(new ScopeChange(move.at(), issue.issueId(), issue.key(), move.event(),
                        Points.normalizeSum(delta), move.actorId(),
                        Points.normalize(issue.snapshotPoints())));
            }
        }
        changes.sort(Comparator.comparing(ScopeChange::at).thenComparing(ScopeChange::key));
        return List.copyOf(changes);
    }

    // ------------------------------------------------------------------ weighing

    /**
     * What one issue contributes to the lines: 1 under {@code COUNT}, its <strong>current</strong>
     * estimate under {@code POINTS} — falling back to the ledger's entry snapshot only for an issue
     * that no longer exists, which has no current estimate to read.
     *
     * <p>An unestimated issue weighs 0 <em>and</em> is counted in {@code unestimatedCount}: 0 alone
     * is documented failure mode #4, since "we didn't estimate it" and "it's free" are different
     * statements that a bare zero renders identically.
     */
    private static BigDecimal weight(LedgerIssue issue, ReportMeasure measure) {
        if (measure == ReportMeasure.COUNT) {
            return BigDecimal.ONE;
        }
        var points = points(issue);
        return points == null ? BigDecimal.ZERO : points;
    }

    private static boolean unestimated(LedgerIssue issue) {
        return points(issue) == null;
    }

    private static BigDecimal points(LedgerIssue issue) {
        return issue.deleted() ? issue.snapshotPoints() : issue.currentPoints();
    }

    // ------------------------------------------------------------------ the axis

    /**
     * The exclusive instant a day's values are computed at: the following UTC midnight, or
     * {@link SprintLedger#endBoundary()} when that comes first — which it does for exactly one day,
     * the last, and for none at all when the series has been clipped to
     * {@code app.reports.max-window-days}.
     */
    private static OffsetDateTime boundaryOf(LocalDate day, OffsetDateTime seriesEnd) {
        var midnight = endOf(day);
        return midnight.isAfter(seriesEnd) ? seriesEnd : midnight;
    }

    /** The UTC day an instant falls on — every boundary in this report is a UTC day boundary (§6). */
    private static LocalDate day(OffsetDateTime instant) {
        return instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
    }

    /** The exclusive end of a UTC day: the following midnight. */
    private static OffsetDateTime endOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
