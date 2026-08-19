package com.hamstrack.report.dto;

import com.hamstrack.issue.entity.SprintScopeEventType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry of the burn-up's <strong>scope-change log</strong> (reports-proposal §2.3): an issue
 * joined or left a running sprint, when, moved by whom, and by how much the scope line stepped.
 *
 * <p>This is the answer to "what happened to the plan", and it is the reason the burn-up is worth
 * building when a burndown is not: every step in the line is hoverable and names the issue and the
 * actor, so a scope increase is a fact with an author instead of an unexplained jump (§1.2).
 *
 * <p><strong>The commitment is not a change.</strong> The {@code ADDED} rows a sprint's start
 * writes — one per member, all stamped {@code startAt} — are the sprint's committed scope and
 * appear as {@code committedAtStart} and as the height of the line's first point, never as N log
 * entries. Only events strictly after {@code startAt} are changes, and only those are listed here.
 *
 * @param at      when it happened (the ledger's {@code occurred_at}, UTC)
 * @param issueId the issue, or <strong>{@code null} if it has since been deleted</strong>. The
 *                event survives the issue by design (the ledger's issue FK is
 *                {@code ON DELETE SET NULL}), because a sprint's record must not be quietly
 *                rewritten by a later delete — so a client must be able to render this row without
 *                a link. {@link #key} is always present
 * @param key     the issue key as the ledger snapshotted it at the moment of the event. A snapshot
 *                rather than a join, so it still reads as a line of a sprint's history after the
 *                issue is gone
 * @param event   {@code ADDED} or {@code REMOVED}
 * @param delta   the signed step in the requested measure — {@code +1}/{@code -1} for
 *                {@code COUNT}, the issue's current estimate for {@code POINTS} (see
 *                {@code SprintBurnupService} for why current and not snapshotted). Always the same
 *                weight the series used for this issue, so the log and the line cannot disagree
 * @param actorId who moved it, or {@code null} in two cases: the account is gone (the ledger's
 *                {@code actor_id} is {@code ON DELETE SET NULL}, because the event is a fact about
 *                the sprint and must outlive the account that caused it), or <strong>the issue is
 *                gone</strong> — a deleted issue's row keeps its step, its instant and its key but
 *                names nobody, because {@code issue_history} cascades away on delete and this log
 *                must not become the last surviving attribution for an issue erased everywhere
 *                else. See {@code SprintLedger.LedgerIssue.Move}
 * @param storyPoints what the issue weighed <strong>when it entered this sprint</strong> — the
 *                ledger's own {@code story_points} snapshot, {@code null} when it entered
 *                unestimated. Independent of {@link #delta}, and that is the point of carrying
 *                both: {@code delta} is in the requested measure, so under {@code COUNT} it is
 *                ±1 and no point value would otherwise reach this log at all. A project with
 *                {@code estimation} switched off is shown exactly that measure, and delivery rule
 *                B says a value the project already recorded stays visible when a capability is
 *                switched off — so the estimate travels with the row rather than being inferred
 *                client-side from a number that means something else
 */
public record ScopeChange(
        OffsetDateTime at,
        UUID issueId,
        String key,
        SprintScopeEventType event,
        BigDecimal delta,
        UUID actorId,
        BigDecimal storyPoints
) {}
