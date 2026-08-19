package com.hamstrack.report.dto;

/**
 * The three numbers printed under the flow chart (reports-proposal §2.1) — the whole
 * window, not a bucket.
 *
 * @param created  issues created inside the window.
 * @param resolved issues closed inside the window (same "closed now, dated by the latest
 *                 closure" reading as {@link FlowBucket#resolved()}).
 * @param net      {@code created - resolved}. Positive means the backlog grew over the
 *                 window. Deliberately signed and deliberately computed here rather than
 *                 in the client, so every surface (chart, table, and the CSV in R7) reads
 *                 the same arithmetic.
 */
public record FlowTotals(
        long created,
        long resolved,
        long net
) {}
