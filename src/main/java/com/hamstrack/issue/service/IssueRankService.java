package com.hamstrack.issue.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.RateLimitKind;
import com.hamstrack.common.ratelimit.RateLimitedException;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.entity.Project;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place {@code issues.position} — the project-wide backlog/board rank — is
 * computed (HD-22, agile-sprints-proposal §3.3). Everything else about a drag lives in
 * {@code IssueService.rank}; this class owns only the arithmetic and the neighbour
 * queries, so the rank invariants can be reviewed in isolation.
 *
 * <p><strong>Design in one paragraph.</strong> The rank is a single project-wide
 * BIGINT order shared by the board and the backlog (there is deliberately no second
 * {@code backlog_rank} column — two orders would guarantee an "I ranked it but the
 * board didn't move" bug). Placement is computed <em>server-side from neighbours</em>:
 * the client sends {@code afterIssueId}/{@code beforeIssueId}, the server fills in the
 * missing neighbour with one indexed {@code LIMIT 1} query over the TARGET SECTION and
 * takes the midpoint. The client can therefore never invent, corrupt or leak a rank
 * value — and {@code position} is not even exposed on {@code IssueResponse}.
 *
 * <p><strong>Sections share one rank space.</strong> {@code sprint_id} is a filter, not
 * a separate order: a section renders {@code ORDER BY position} over its own rows, so
 * ranks interleave across sections on purpose — moving an issue out of a sprint keeps
 * its relative place in the backlog.
 *
 * <p><strong>Concurrency.</strong> Two users dropping different issues into the same
 * gap get two different midpoints (each computed under its own transaction from
 * freshly-read neighbours), so both succeed — the desirable outcome in a planning
 * meeting. If the ANCHORS themselves are inconsistent
 * ({@code after.position >= before.position}, i.e. the caller's view is stale) the
 * answer is a <strong>409 "the list changed — refresh"</strong>, never a 500 and never
 * a silent arbitrary placement.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IssueRankService {

    private final IssueRepository issueRepository;
    private final ProductMetrics metrics;

    /**
     * Spacing between adjacent ranks — {@code 2^26 = 67 108 864}. Chosen so a gap
     * survives ~26 successive midpoints before it is exhausted, while the largest
     * realistic position (~1e7 issues × 6.7e7) stays far inside BIGINT. V11 rescaled
     * every pre-existing {@code position} by this factor, and
     * {@code IssueService.create} appends at {@code max(position) + RANK_STEP}.
     */
    public static final long RANK_STEP = 67_108_864L;

    /**
     * How long one project must wait between whole-project rebalances.
     *
     * <p><strong>Why this exists (security review M1).</strong> A rebalance is
     * {@code O(issues in project)} under row locks, but it is reachable by any plain
     * member: dropping the same issue between the same two neighbours halves the gap
     * each time, so after ~26 cheap requests EVERY further request rewrites the whole
     * project. Un-throttled that is one full-table rewrite per ~26 HTTP calls — an
     * authenticated DoS with no rate limit in front of it.
     *
     * <p>A legitimate second rebalance in the same minute is essentially impossible:
     * right after one, every gap is {@link #RANK_STEP} wide again, so exhausting one
     * takes another ~26 <em>successive</em> drops into that same gap. So the cooldown
     * costs honest planning nothing and turns the abuse case into a 429.
     *
     * <p>Deliberately a constant, not a property: it is an internal safety valve, not an
     * operator-tunable behaviour, and a new env var would have to be wired through four
     * files for a knob nobody should turn.
     */
    private static final long REBALANCE_COOLDOWN_MS = 60_000L;

    /**
     * {@code projectId → epoch-ms of the last rebalance} — the throttle's whole state.
     * In-memory and per-instance on purpose (the {@code RateLimitService} precedent):
     * it is a bound on abuse, not an invariant, and a multi-instance deployment simply
     * gets one rebalance per instance per window. Swept by {@link #evictStaleEntries()}
     * so a workspace with many projects cannot grow it without bound.
     */
    private final Map<UUID, Long> lastRebalanceAt = new ConcurrentHashMap<>();

    /** A computed placement, or a signal that the gap is exhausted and must be rebalanced. */
    private record Placement(long position, boolean needsRebalance) {
        static final Placement REBALANCE = new Placement(0, true);
    }

    /**
     * The rank {@code movedId} should end up with inside the section
     * {@code (project, targetSprintId)} — where a {@code null} sprint id IS the backlog.
     *
     * <p>Anchors must already belong to that section (422 otherwise) — that is what
     * makes the operation total and prevents ordering paradoxes. Both anchors absent
     * means "append to the section's end", which is how a pure sprint change (drag from
     * the backlog onto an empty sprint) lands.
     *
     * <p>When the gap between the two anchors is exhausted
     * ({@code before - after <= 1}, i.e. ~26 midpoints into the same gap) the whole
     * project is renumbered in ONE native statement and the midpoint is retried exactly
     * once. That rebalance runs <strong>before any entity mutation in this
     * transaction</strong>: it carries {@code clearAutomatically}, which would otherwise
     * evict pending inserts (the documented "workspace vanishes" trap). Callers must
     * therefore treat every entity they held across this call as detached and re-read it.
     *
     * <p><strong>{@code Propagation.MANDATORY}</strong>: the rebalance opts the
     * transaction out of the {@code set_updated_at()} trigger with a {@code SET LOCAL}
     * GUC, which silently degrades to a per-statement no-op outside a transaction. This
     * method must therefore never be the thing that starts one — it must join the
     * caller's, or fail loudly.
     *
     * <p><strong>Rebalances are throttled per project</strong>
     * ({@link #REBALANCE_COOLDOWN_MS}): a second one inside the window is a
     * <strong>429</strong>, because the only way to ask for one is to have just consumed
     * a freshly-widened gap ~26 times over.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long resolve(User actor, Project project, UUID movedId, UUID targetSprintId,
                        UUID afterId, UUID beforeId) {
        var placement = compute(project, movedId, targetSprintId, afterId, beforeId);
        if (!placement.needsRebalance()) {
            return placement.position();
        }
        requireRebalanceAllowed(actor, project);
        // The gap is exhausted. Renumber the whole project natively — no @Version bump
        // on untouched issues (so nobody's optimistic lock on an unrelated edit is
        // invalidated) and no updated_at churn (so one drag does not mark every issue in
        // the project as recently updated and poison Home / My work / ORDER BY updated).
        issueRepository.suppressUpdatedAtForThisTransaction();
        int renumbered = issueRepository.rebalancePositions(project.getId(), RANK_STEP);
        // Scope the opt-out to the statement that needed it: the GUC is transaction-wide,
        // so leaving it on would silently un-stamp any bulk UPDATE added here later.
        issueRepository.restoreUpdatedAtForThisTransaction();
        log.debug("Rebalanced {} issue ranks in project {}", renumbered, project.getId());

        // The persistence context was cleared by the rebalance — re-read the anchors and
        // retry the midpoint exactly once. After a renumber every gap is RANK_STEP wide,
        // so this can only fail if a concurrent writer just consumed it again.
        var retried = compute(project, movedId, targetSprintId, afterId, beforeId);
        if (retried.needsRebalance()) {
            throw staleAnchors();
        }
        return retried.position();
    }

    private Placement compute(Project project, UUID movedId, UUID targetSprintId,
                              UUID afterId, UUID beforeId) {
        Issue after = afterId == null ? null : requireAnchor(project, afterId, targetSprintId);
        Issue before = beforeId == null ? null : requireAnchor(project, beforeId, targetSprintId);

        // Fill in whichever neighbour the client did not send — ONE indexed LIMIT 1
        // query over the target section, excluding the issue being moved (it may still
        // be sitting between the anchors).
        if (after != null && before == null) {
            before = firstOrNull(issueRepository.findNextInSection(
                    project, targetSprintId, after.getPosition(), movedId, PageRequest.of(0, 1)));
        } else if (before != null && after == null) {
            after = firstOrNull(issueRepository.findPreviousInSection(
                    project, targetSprintId, before.getPosition(), movedId, PageRequest.of(0, 1)));
        }

        if (after == null && before == null) {
            // No anchors at all (or the section is empty): append to the end.
            return new Placement(
                    issueRepository.maxPositionInSection(project, targetSprintId) + RANK_STEP, false);
        }
        if (before == null) {
            return new Placement(after.getPosition() + RANK_STEP, false);
        }
        if (after == null) {
            // Dropped above the section's current first row. The result may drift into
            // negative territory over a very long run of top-drops, which is harmless:
            // ordering is what matters, BIGINT has ~1.4e11 such drops of headroom, and
            // the next mid-gap rebalance renormalizes everything to positive values.
            return new Placement(before.getPosition() - RANK_STEP, false);
        }

        long lo = after.getPosition();
        long hi = before.getPosition();
        if (lo >= hi) {
            // The caller's view of the list is stale — refuse rather than guess.
            throw staleAnchors();
        }
        if (hi - lo <= 1) {
            return Placement.REBALANCE;
        }
        return new Placement(lo + (hi - lo) / 2, false);
    }

    /**
     * An anchor must resolve <em>within the caller's project</em> (422 "Unknown
     * anchor", never a 404 that would confirm existence across tenants) and must
     * already be in the TARGET section — dropping next to a row that is in a different
     * sprint is an ordering paradox, not a placement.
     */
    private Issue requireAnchor(Project project, UUID anchorId, UUID targetSprintId) {
        var anchor = issueRepository.findByIdAndProject(anchorId, project)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Unknown anchor issue"));
        UUID anchorSprintId = anchor.getSprint() == null ? null : anchor.getSprint().getId();
        if (!Objects.equals(anchorSprintId, targetSprintId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That anchor is not in the target list");
        }
        return anchor;
    }

    /**
     * Claim this project's rebalance slot, or refuse with a <strong>429</strong>
     * (security review M1). The claim is an atomic compare-and-set on the timestamp map,
     * so two concurrent drags cannot both trigger a whole-project rewrite.
     *
     * <p><strong>The decision is made INSIDE the atomic block</strong> (security review
     * N1), not inferred afterwards from the value {@code compute} returned: the winner
     * stores {@code now}, so a second thread arriving in the SAME millisecond gets
     * {@code previous == now} back and a returned-value comparison would grant it too —
     * "one rebalance per 60s" would degrade to "one per millisecond-bucket per 60s".
     *
     * <p><strong>The refusal is a metric, not a log line</strong> (security review N2).
     * A refused request rolls back, so the exhausted gap stays exhausted and every
     * further drop into it is refused again at HTTP request rate until the cooldown
     * lapses — a WARN per refusal would be an authenticated log-flooding vector (log
     * disk on DC, ingest cost on Cloud). This follows the {@code RateLimitService}
     * precedent exactly: bump {@link ProductMetrics#rateLimitHit} (bounded cardinality,
     * alertable) and keep the per-request detail at DEBUG. Only ids are recorded in the
     * log, no names and no request content.
     */
    private void requireRebalanceAllowed(User actor, Project project) {
        long now = System.currentTimeMillis();
        var granted = new boolean[1];
        long claimed = lastRebalanceAt.compute(project.getId(), (id, previous) -> {
            if (previous != null && now - previous < REBALANCE_COOLDOWN_MS) {
                return previous;
            }
            granted[0] = true;
            return now;
        });
        if (granted[0]) {
            return;
        }
        long retryAfter = Math.max((REBALANCE_COOLDOWN_MS - (now - claimed)) / 1000, 1);
        metrics.rateLimitHit(RateLimitKind.RANK_REBALANCE);
        log.debug("Rank rebalance throttled for project {} (actor {}) — {}s remaining",
                project.getId(), actor == null ? "unknown" : actor.getId(), retryAfter);
        // RateLimitedException, not a bare ResponseStatusException: the shared
        // handler adds the Retry-After header, so the SPA can back off instead of
        // hammering.
        throw new RateLimitedException(
                "This project's backlog ranks were just re-spaced — retry in "
                        + retryAfter + "s", retryAfter);
    }

    /**
     * The throttle map is keyed by project id, so it is bounded by the number of
     * projects that have ever exhausted a gap on this instance — small, but not
     * self-limiting. Sweeping expired entries keeps it from being a slow leak in a
     * long-running process (the {@code RateLimitService} precedent).
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    void evictStaleEntries() {
        long cutoff = System.currentTimeMillis() - REBALANCE_COOLDOWN_MS;
        lastRebalanceAt.values().removeIf(at -> at < cutoff);
    }

    private static ResponseStatusException staleAnchors() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "The list changed — refresh and try again");
    }

    private static Issue firstOrNull(List<Issue> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
