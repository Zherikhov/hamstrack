package com.hamstrack.report.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.config.ReportProperties;
import com.hamstrack.common.util.Points;
import com.hamstrack.issue.entity.SprintScopeEventType;
import com.hamstrack.report.dto.ReportMeasure;
import com.hamstrack.report.dto.ReportMeta;
import com.hamstrack.report.dto.VelocityForecast;
import com.hamstrack.report.dto.VelocityReportResponse;
import com.hamstrack.report.dto.VelocitySprint;
import com.hamstrack.report.repository.VelocityReportRepository;
import com.hamstrack.report.service.SprintLedger.LedgerIssue;
import com.hamstrack.report.service.SprintLedger.LedgerIssue.Move;
import com.hamstrack.issue.repository.SprintRepository;
import com.hamstrack.issue.repository.SprintRepository.CompletedSprint;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Velocity (reports-proposal §2.5) — <em>how much should we plan for next sprint?</em>
 *
 * <p>The last N completed sprints as bars, each showing what it delivered with its commitment marked
 * on it, and beside them a p50/p85 band with the sample size stated.
 *
 * <h1>This report is defined by what it refuses to do</h1>
 * §1.4 is the only section of the proposal whose conclusion is "ship it, redesigned", and the
 * redesign is the point. Velocity's documented harm is real and specific: it <em>"was never intended
 * to be used to compare two teams"</em>, and when it is, <em>"leaders misinterpret higher story
 * point averages to mean one team is more productive"</em>, whereupon <em>"teams begin optimizing for
 * points instead of outcomes … developers inflate estimates, avoid refactoring, and resist taking on
 * complex items — velocity goes up but value does not"</em>. The mechanism is the audience, not the
 * arithmetic. So the four constraints below are implemented as constraints, not written down as
 * intentions, and each says how it is enforced rather than merely that it holds.
 *
 * <h2>1. No per-person breakdown — enforced in both SELECT lists</h2>
 * Not as a filter, not as a tooltip field, not in a future CSV.
 * {@code VelocityReportRepository.ledgerAcrossSprints} <strong>does not select</strong>
 * {@code actor_id} or {@code assignee_id}, both of which are one column away — the single-sprint
 * read does select the latter — so there is no per-person breakdown to add without first re-opening
 * the statement.
 *
 * <p>"Does not select" is the honest form of the claim, and it has to hold for the OTHER read too.
 * This class used to take its sample as {@code List<Sprint>}, and a {@code Sprint} carries a lazy
 * {@code createdBy} — so a name was one dereference away, needing no query change and tripping
 * neither refusal test. The sample is therefore
 * {@code SprintRepository.CompletedSprint}: five scalars, no association, nothing to dereference.
 * With that, nothing reaching this class knows who anybody is, structurally rather than by habit.
 * {@code VelocitySprint} has no person-shaped field either — {@code VelocityRefusalTest} holds the
 * response to an allow-list of components rather than to a list of forbidden words, because a
 * deny-list of names is the guard a rename satisfies.
 *
 * <h2>2. No cross-project or workspace-level velocity — and the friction is the feature</h2>
 * This service takes a single {@code projectId}, resolves it, and has no sibling that takes a set.
 * There is no workspace-level velocity endpoint, and <strong>there must not be one</strong>:
 * comparing two teams has to be done by hand, by opening two pages and doing the arithmetic
 * yourself, and <em>that is the intended amount of friction</em>. It is not an omission waiting to
 * be tidied up. The cost of the comparison is deliberately paid by the person making it, because
 * the harm above is caused by comparisons that were cheap enough to make without thinking about
 * them. Anyone adding an aggregate above this — a workspace rollup, a "all projects" chart, a CSV
 * that unions two projects — deletes the mitigation and keeps the metric, which is the exact shape
 * §1.4 describes.
 *
 * <h2>3. Below three completed sprints the band is suppressed, with the sample size stated</h2>
 * {@link #MIN_FORECAST_SPRINTS}. The same principle as R3's percentile suppression, one level up: a
 * p85 over two sprints is one sprint's number wearing a statistic's name. The bars still render —
 * they are facts — and {@link VelocityForecast#sampleSize()} still states what there was, so the
 * client can say "need 3, have 2" rather than showing an empty box.
 *
 * <h2>4. The cap is 12, and it is deliberately far below Jira's</h2>
 * {@link #MAX_SPRINTS}. Jira Data Center caps its Velocity Chart at <strong>120 sprints and 25 000
 * issues</strong> <em>"for performance reasons"</em>, raisable by a REST call (§1.7). We cap an
 * order of magnitude lower, and not only for cost — twelve sprints is roughly half a year, and a
 * forecast built from three years of a team that has since changed its members, its process and its
 * definition of a point is a precise number about nobody who is still here. §1.7's first conclusion
 * is that every report is bounded <em>and the bound is disclosed</em>: over 12 is a <strong>400
 * naming the cap</strong> (§4.3), never a silent clamp, because a silently-clamped sample is exactly
 * how a report earns "these numbers don't match what I expected".
 *
 * <h2>Points are the ledger's SNAPSHOT — the review's rule, not the burn-up's</h2>
 * §2.5 names its data as "{@code sprint_scope_events} + {@code closed_at} + {@code story_points}"
 * without saying which, so the choice is argued here and it follows {@code SprintReviewService}:
 * <ul>
 *   <li><strong>Velocity is retrospective.</strong> Every sprint it describes is COMPLETED and
 *       frozen. With current points, re-estimating one old issue would move a bar drawn four months
 *       ago <em>and</em> the band computed from it, so tomorrow's plan would change because somebody
 *       tidied up yesterday's backlog. The burn-up can afford current points because it charts a
 *       sprint that is still running and the reader is asking a live question; velocity is asking
 *       what happened.</li>
 *   <li><strong>Two reports over one ledger must not disagree.</strong> A bar saying the sprint
 *       committed to 60 points beside a sprint review saying 55 discredits both, and R4 already
 *       fixed one instance of that failure at a single instant. The commitment number here is
 *       computed from the same snapshots {@code SprintReviewService} sums, so the review is
 *       literally the drill-down of the bar.</li>
 *   <li><strong>It is the only weight a deleted issue has.</strong> A departed issue has no current
 *       estimate at all, so under current points it would silently weigh zero and shrink a finished
 *       sprint's bar — the failure V18's nulling foreign key exists to prevent.</li>
 * </ul>
 *
 * <h2>Membership has exactly one implementation, and this class does not add a second</h2>
 * The per-sprint figures are the sprint review's, and they are computed the same way: each sprint's
 * ledger rows become {@link LedgerIssue} records, and the questions are asked through
 * {@link LedgerIssue#memberAt} (committed, inclusive at {@code startAt} because the commitment batch
 * is stamped with exactly that instant) and {@link LedgerIssue#outcomeAt} (the three-way cut that
 * separates removed-while-running from completed from carried-over). Both live on the shared record.
 * A grouped {@code SUM(...) FILTER (WHERE ...)} in SQL would have been one round trip too and would
 * have been a second definition of "in the sprint at time T" — the thing R4 built the shared ledger
 * to prevent.
 *
 * <h2>Cost — 4 + 3, the same as R4, and independent of N</h2>
 * Four statements of tenancy resolution, then three: the sprint list ({@code LIMIT N}), the
 * {@code meta} scalars, and <strong>one</strong> sweep over all N ledgers. Not 3N. Six when the
 * project has never completed a sprint, since there is then nothing to sweep. Pinned by
 * {@code VelocityQueryCountTest}, which measures a 1-sprint and a 12-sprint project and requires the
 * same number.
 */
@Service
@RequiredArgsConstructor
public class VelocityService {

    /** Sprints sampled when the caller names none (§2.5) — roughly a quarter of a year. */
    public static final int DEFAULT_SPRINTS = 6;

    /**
     * The most sprints one request may sample. See the class note: an order of magnitude below
     * Jira's 120, and refused rather than clamped.
     */
    public static final int MAX_SPRINTS = 12;

    /**
     * Below this many completed sprints the band is suppressed (§2.5, §6). Three is the smallest
     * sample in which a p50 and a p85 can name different sprints at all — at two they are the same
     * two numbers, so the "band" would be the range itself, dressed up as a statistic.
     */
    public static final int MIN_FORECAST_SPRINTS = 3;

    private final WorkspaceAccessService workspaceAccess;
    private final SprintRepository sprintRepository;
    private final VelocityReportRepository velocityReportRepository;
    private final ReportProperties reportProperties;

    /**
     * @param sprints how many completed sprints to sample; {@code null} means {@link #DEFAULT_SPRINTS}
     * @param requested the measure; {@code null} means {@link ReportMeasure#COUNT}
     */
    @Transactional(readOnly = true)
    public VelocityReportResponse velocity(User actor, UUID workspaceId, UUID projectId,
                                           Integer sprints, ReportMeasure requested) {
        // 1. Tenancy FIRST, before any parameter is judged. A 400 about `sprints` on a project the
        //    caller cannot see would confirm that the project exists; a missing workspace, a missing
        //    project and a non-member all answer 404 and answer it identically.
        var ctx = workspaceAccess.resolveProject(actor, workspaceId, projectId);
        var project = ctx.project();
        int requestedSprints = validated(sprints);
        var measure = requested == null ? ReportMeasure.COUNT : requested;

        // 2. The sprints, resolved THROUGH the project. This is the only source of sprint ids in
        //    this report — see VelocityReportRepository on why binding them from a request would
        //    turn a workspace-scoped sweep into a cross-project read that still looked scoped.
        var sampled = sprintRepository.findRecentCompletedByProject(
                project, PageRequest.of(0, requestedSprints));

        var ids = idArray(sampled);
        var meta = row(velocityReportRepository.metaCounts(
                project.getId(), ctx.workspace().getId(), ids));
        var firstIssueAt = instant(meta[0]);
        long basedOnIssues = count(meta[1]);
        int cap = reportProperties.maxRows();

        if (sampled.isEmpty()) {
            // Never completed a sprint. A 200 with an empty chart and a suppressed band: §6 wants an
            // empty state with a sentence, not a blank panel and not an error.
            return new VelocityReportResponse(measure, List.of(),
                    VelocityForecast.suppressed(0),
                    new ReportMeta(now(), basedOnIssues, false, cap, firstIssueAt, List.of()));
        }

        // 3. One sweep over all N ledgers. Cap plus one: a full result set IS the truncation signal.
        var rows = velocityReportRepository.ledgerAcrossSprints(
                ctx.workspace().getId(), project.getId(), ids, cap + 1);
        var grouped = group(rows, cap);

        var bars = new ArrayList<VelocitySprint>(sampled.size());
        for (var sprint : sampled) {
            var issues = grouped.issues().get(sprint.getId());
            if (issues == null && grouped.truncated()) {
                // Once the cap has bitten, "this sprint has no ledger rows" and "this sprint's
                // ledger rows are past the cap" are the same observation, and one of them is a
                // sprint drawn as four zeros. Both are dropped — see group().
                continue;
            }
            bars.add(bar(sprint, issues == null ? List.of() : issues, measure));
        }
        // The query returns the sprints newest first, because the cap has to keep the most RECENT
        // ones; a chart reads left to right in time. So the bars are REVERSED, not re-sorted —
        // reversing inherits the statement's own total order (completed_at, then sequence as the
        // tiebreaker) instead of restating half of it here, where the two could drift apart, and it
        // cannot trip over a null completed_at the way a comparator would.
        Collections.reverse(bars);

        return new VelocityReportResponse(measure, List.copyOf(bars),
                forecast(bars, grouped.truncated()),
                new ReportMeta(now(), basedOnIssues, grouped.truncated(), cap, firstIssueAt,
                        List.of()));
    }

    /**
     * {@code sprints}, refused rather than clamped (§4.3, §6).
     *
     * <p>Both ends are checked and both name the bound, because the caller of a report API is usually
     * a chart that computed the number itself. A zero or negative sample is refused for the same
     * reason the cap is: {@code PageRequest.of(0, 0)} is an {@code IllegalArgumentException} deeper
     * in, i.e. a 500 on an endpoint whose contract promises a 400, and "0 sprints" is a question
     * with no answer rather than a request for an empty chart.
     */
    private static int validated(Integer sprints) {
        if (sprints == null) {
            return DEFAULT_SPRINTS;
        }
        if (sprints < 1 || sprints > MAX_SPRINTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sprints must be between 1 and " + MAX_SPRINTS + " (asked for " + sprints
                            + "). The sample is not clamped for you: a forecast built from a "
                            + "different number of sprints than the one you asked for is worse "
                            + "than no forecast. " + MAX_SPRINTS + " is about half a year, and a "
                            + "band computed from further back describes a team that has since "
                            + "changed its members, its process and its definition of a point.");
        }
        return sprints;
    }

    // ------------------------------------------------------------------ one bar

    /**
     * One sprint's four numbers, from its own ledger.
     *
     * <p>Every question is asked through {@link LedgerIssue}, so this is the sprint review's
     * arithmetic without the sprint review's issue rows — and it has to be, or the drill-down would
     * contradict the chart. {@code committed} is asked INCLUSIVELY at {@code startAt} because the
     * commitment batch is stamped with exactly that instant, and the boundary everything else is
     * measured at is {@link SprintLedger#endBoundaryOf(com.hamstrack.issue.entity.SprintState,
     * OffsetDateTime)} — the projection-shaped half of the one shared definition, so a bar and the
     * review of the same sprint cannot end at different instants: {@code completed_at}, never the
     * planned {@code endAt}. A sprint completed three days late did not remove anything "after the
     * end", and one completed early did not carry over what it had already stopped doing.
     *
     * <p>{@code startAt} cannot be null here — {@code sprints_active_ck} requires it before a sprint
     * can be ACTIVE and the lifecycle is one-way, so a COMPLETED sprint has one. It is defended
     * anyway rather than asserted: the alternative to a defensive read is a
     * {@code NullPointerException} on the whole report because one row in one project is odd.
     */
    private static VelocitySprint bar(CompletedSprint sprint, List<LedgerIssue> issues,
                                      ReportMeasure measure) {
        var startAt = sprint.getStartAt();
        var endBoundary = SprintLedger.endBoundaryOf(sprint.getState(), sprint.getCompletedAt());
        var committed = BigDecimal.ZERO;
        var completed = BigDecimal.ZERO;
        var addedAfterStart = BigDecimal.ZERO;
        var carriedOver = BigDecimal.ZERO;
        int unestimated = 0;

        for (var issue : issues) {
            var weight = weight(issue, measure);
            if (startAt != null && issue.memberAt(startAt)) {
                committed = committed.add(weight);
            } else {
                addedAfterStart = addedAfterStart.add(weight);
            }
            var outcome = issue.outcomeAt(endBoundary);
            switch (outcome) {
                case COMPLETED -> completed = completed.add(weight);
                case CARRIED_OVER -> carriedOver = carriedOver.add(weight);
                case REMOVED_BEFORE_END -> { }   // it left before the end; it is in neither
            }
            // Measured over what the sprint HELD at its end — the population `completed` and
            // `carriedOver` partition, i.e. the one the bar's height is drawn from.
            if (outcome != LedgerIssue.Outcome.REMOVED_BEFORE_END
                    && issue.snapshotPoints() == null) {
                unestimated++;
            }
        }

        return new VelocitySprint(sprint.getId(), sprint.getName(), startAt, sprint.getCompletedAt(),
                Points.normalizeSum(committed), Points.normalizeSum(completed),
                Points.normalizeSum(addedAfterStart), Points.normalizeSum(carriedOver),
                unestimated);
    }

    /**
     * What one issue weighs in the requested measure.
     *
     * <p>{@code COUNT} weighs everything 1 — the measure every project has, and the one a project
     * that has decided nothing about estimation still gets a real chart from. {@code POINTS} reads
     * the ledger's ENTRY SNAPSHOT (see the class note on why, and why it differs from the burn-up),
     * and an unestimated issue contributes <strong>0 and a tick on {@code unestimatedCount}</strong>
     * — never a silent zero, which is documented failure mode #4 (§1.2, §6).
     */
    private static BigDecimal weight(LedgerIssue issue, ReportMeasure measure) {
        if (measure == ReportMeasure.COUNT) {
            return BigDecimal.ONE;
        }
        var snapshot = issue.snapshotPoints();
        return snapshot == null ? BigDecimal.ZERO : snapshot;
    }

    // ------------------------------------------------------------------ the band

    /**
     * The p50/p85 of what the sampled sprints delivered, or a suppressed band with the sample size.
     *
     * <p><strong>Computed over {@code completed}, and over nothing else.</strong> Not the commitment
     * (that is what the team hoped, and forecasting from hopes compounds them), and not
     * commitment-minus-carry-over (which is the same number by a longer route only when nothing was
     * added).
     *
     * <p>{@code percentile_cont}, matching PostgreSQL — deliberately the same definition R3's cycle
     * time uses through {@code percentile_cont(...) WITHIN GROUP (...)}, so a "p85" means one thing
     * in this product. In Java rather than in SQL only because the sample is at most twelve numbers
     * that are already in memory: sending them back to the database would be a fourth statement to
     * sort twelve values.
     *
     * <p><strong>A truncated read suppresses the band outright</strong>, however many bars survived.
     * The bars that shipped are each complete (see {@link #group}), so they are facts and they still
     * render; but the sample they form is no longer the sample the caller asked for — it is whatever
     * fitted under the row cap — and a p85 over a subset nobody chose is precisely the "number the
     * report cannot stand behind" the group-dropping rule refuses one level down.
     * {@link VelocityForecast#sampleSize()} still states how many bars there were, so the client
     * says what it has rather than showing an empty box, and {@code meta.truncated} says why.
     */
    private static VelocityForecast forecast(List<VelocitySprint> bars, boolean truncated) {
        int n = bars.size();
        if (n < MIN_FORECAST_SPRINTS || truncated) {
            return VelocityForecast.suppressed(n);
        }
        var sample = new double[n];
        for (int i = 0; i < n; i++) {
            sample[i] = bars.get(i).completed().doubleValue();
        }
        Arrays.sort(sample);
        return new VelocityForecast(percentile(sample, 0.50), percentile(sample, 0.85), n);
    }

    /**
     * PostgreSQL's {@code percentile_cont} over a sorted sample: linear interpolation between the
     * two values straddling rank {@code p × (n − 1)}.
     */
    private static double percentile(double[] sorted, double p) {
        double rank = p * (sorted.length - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted[low];
        }
        return sorted[low] + (rank - low) * (sorted[high] - sorted[low]);
    }

    // ------------------------------------------------------------------ grouping the sweep

    /**
     * The sweep's rows, cut into one {@link LedgerIssue} list per sprint.
     *
     * <p>Two levels of grouping, and the second one is the single-sprint reader's rule applied
     * again: rows are grouped by sprint, and inside a sprint by {@code issue_id} where there is one
     * and by the snapshotted {@code issue_key} where there is not. Grouping a deleted issue by its
     * null id would collapse every departed issue of a sprint into one phantom whose ADDs and
     * REMOVEs interleave — a bar computed from an incoherent membership history rather than a wrong
     * one, which is worse because it is not even reproducible.
     *
     * <p><strong>Truncation drops a half-read sprint whole — and an unread one too.</strong> The
     * cap does not cut a sprint's numbers down proportionally; it removes evidence, and a number
     * computed from the remainder is arithmetically fine and factually wrong. Two shapes, and the
     * second is the one this originally got wrong:
     * <ul>
     *   <li><strong>The group cut mid-ledger.</strong> The statement orders by sprint, so at most
     *       one group is left incomplete, and the cap-plus-one row identifies it exactly: if the
     *       extra row belongs to the same sprint as the last shipped row, that sprint was cut in
     *       half. It is removed from {@link GroupedLedger#issues} entirely.</li>
     *   <li><strong>The groups that never arrived at all.</strong> {@code ORDER BY e.sprint_id} is
     *       ordering by a <strong>UUID v7</strong>, which is time-ordered — so a cap that bites
     *       does not shave the tail of one sprint, it drops every <em>later-created</em> sampled
     *       sprint whole. Those sprints are simply absent from the map, which is indistinguishable
     *       from the legitimate "nobody ever put anything in this sprint". Read as the latter they
     *       would each be drawn as a bar of four zeros, and — worse than the wrong bar — those
     *       zeros would enter the forecast as real samples and drag p50 and p85 down. So once
     *       {@code truncated} is set, <em>every</em> sampled sprint with no shipped rows is dropped
     *       (the caller's loop does this), and the band is suppressed as well: the surviving bars
     *       are each complete, but the sample is no longer the one the caller asked for.</li>
     * </ul>
     * {@code meta.truncated} says the cap bit and {@code meta.basedOnIssues} (counted in the
     * database, above the cap) still states how many issues the report was about, so the reader can
     * see how much is missing rather than reading a shortened chart as a quiet one. The cost of
     * being conservative here is a sprint that really was empty vanishing from a truncated report;
     * the cost of the alternative is a forecast that is wrong without looking it.
     *
     * <p>Unreachable in practice — twelve sprints of hundreds of events against a cap of 20 000 —
     * and handled anyway, because "unreachable" is a property of today's data, and because a member
     * who can move issues in and out of a sprint appends a ledger row every time.
     */
    private static GroupedLedger group(List<Object[]> rows, int cap) {
        boolean truncated = rows.size() > cap;
        var shipped = truncated ? rows.subList(0, cap) : rows;
        UUID partial = null;
        if (truncated && !shipped.isEmpty()) {
            var lastShipped = uuid(shipped.get(shipped.size() - 1)[0]);
            var firstDropped = uuid(rows.get(cap)[0]);
            if (lastShipped != null && lastShipped.equals(firstDropped)) {
                partial = lastShipped;
            }
        }

        Map<UUID, Map<Object, Accumulator>> bySprint = new LinkedHashMap<>();
        for (var row : shipped) {
            var sprintId = uuid(row[0]);
            var eventIssueId = uuid(row[1]);
            var key = (String) row[2];
            bySprint.computeIfAbsent(sprintId, id -> new LinkedHashMap<>())
                    .computeIfAbsent(eventIssueId != null ? eventIssueId : "key:" + key,
                            identity -> new Accumulator(key, eventIssueId, instant(row[6])))
                    .add(row);
        }

        Map<UUID, List<LedgerIssue>> issues = new LinkedHashMap<>();
        bySprint.forEach((sprintId, accumulators) -> {
            var list = new ArrayList<LedgerIssue>(accumulators.size());
            accumulators.values().forEach(accumulator -> list.add(accumulator.build()));
            issues.put(sprintId, List.copyOf(list));
        });
        if (partial != null) {
            issues.remove(partial);
        }
        return new GroupedLedger(issues, truncated);
    }

    /**
     * @param issues    one list per sprint that had ledger rows. A sprint absent from this map has
     *                  no shipped rows, and what that MEANS depends entirely on {@code truncated}:
     *                  without truncation it is a sprint nobody put anything in, which is honestly
     *                  four zeros; with it, the sprint may equally well be one the cap ate whole —
     *                  the two are not distinguishable from here, so the caller drops both rather
     *                  than drawing either
     * @param truncated whether the row cap bit. It is not only a disclosure flag: it is what
     *                  decides the meaning of an absent group, and it suppresses the band
     */
    private record GroupedLedger(Map<UUID, List<LedgerIssue>> issues, boolean truncated) {}

    /**
     * One issue's rows within one sprint.
     *
     * <p>Deliberately narrower than {@code SprintLedgerReader}'s accumulator: no title, no type, no
     * status — and <strong>no assignee and no actor</strong>, because the statement does not select
     * them. The {@link LedgerIssue} it builds therefore carries nulls in those positions, which is
     * the honest representation of a report that is not allowed to know them.
     */
    private static final class Accumulator {
        private final String key;
        private final UUID issueId;
        private final OffsetDateTime closedAt;
        private final List<Move> moves = new ArrayList<>(2);
        /** The estimate on the issue's FIRST ADD — what it weighed when it entered this sprint. */
        private BigDecimal snapshotPoints;
        private boolean snapshotTaken;

        private Accumulator(String key, UUID issueId, OffsetDateTime closedAt) {
            this.key = key;
            this.issueId = issueId;
            this.closedAt = closedAt;
        }

        private void add(Object[] row) {
            var event = SprintScopeEventType.valueOf((String) row[3]);
            // No actor: velocity does not carry one, and a null here is not a missing value but a
            // refusal (§1.4). See the class note.
            moves.add(new Move(instant(row[4]), event, null));
            if (!snapshotTaken && event == SprintScopeEventType.ADDED) {
                snapshotPoints = decimal(row[5]);
                snapshotTaken = true;
            }
        }

        private LedgerIssue build() {
            return new LedgerIssue(issueId, key, issueId == null, null, null, null, null, closedAt,
                    null, snapshotPoints, List.copyOf(moves));
        }
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The sampled sprint ids as one PostgreSQL {@code uuid[]} literal.
     *
     * <p>The ids come from {@code findRecentCompletedByProject}, i.e. from the database, keyed by a
     * resolved project — they are never anything a caller typed, so there is nothing here for a
     * caller to inject even in principle. {@code UUID.toString()} cannot produce a comma or a brace
     * either way. An empty sample renders {@code {}}, which matches nothing rather than failing the
     * way an empty {@code IN} list would.
     */
    private static String idArray(List<CompletedSprint> sprints) {
        var joined = new StringBuilder("{");
        for (int i = 0; i < sprints.size(); i++) {
            if (i > 0) {
                joined.append(',');
            }
            joined.append(sprints.get(i).getId());
        }
        return joined.append('}').toString();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** The one row of a single-row aggregate — null-tolerant, as {@code SprintLedgerReader} is. */
    private static Object[] row(List<Object[]> rows) {
        return rows.isEmpty() ? new Object[]{null, null} : rows.get(0);
    }

    /** A native {@code bigint} count — the shapes a driver can hand one back as. */
    private static long count(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    /** A native {@code numeric} column. Never widened to double: points are exact decimals. */
    private static BigDecimal decimal(Object raw) {
        return switch (raw) {
            case null -> null;
            case BigDecimal value -> value;
            case Number value -> BigDecimal.valueOf(value.doubleValue());
            default -> throw new IllegalStateException(
                    "unexpected numeric type from the report query: " + raw.getClass());
        };
    }

    /** A native {@code uuid} column. Accepts the text form a driver change could produce. */
    private static UUID uuid(Object raw) {
        return switch (raw) {
            case null -> null;
            case UUID id -> id;
            case String s -> UUID.fromString(s);
            default -> throw new IllegalStateException(
                    "unexpected uuid type from the report query: " + raw.getClass());
        };
    }

    /** A native {@code timestamptz} column, normalised to UTC. */
    private static OffsetDateTime instant(Object raw) {
        return switch (raw) {
            case null -> null;
            case OffsetDateTime odt -> odt.withOffsetSameInstant(ZoneOffset.UTC);
            case java.time.Instant i -> i.atOffset(ZoneOffset.UTC);
            case java.sql.Timestamp ts -> ts.toInstant().atOffset(ZoneOffset.UTC);
            default -> throw new IllegalStateException(
                    "unexpected timestamp type from the report query: " + raw.getClass());
        };
    }
}
