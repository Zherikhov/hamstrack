package com.hamstrack.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of the sprint burn-up (reports-proposal §2.3): how much work was in the sprint at the
 * end of that day, and how much of it was finished.
 *
 * <p><strong>Both values are "as at the end of {@code date}"</strong>, i.e. computed at the
 * following UTC midnight — the same UTC day boundaries every other report in this epic buckets on
 * (§6, "Timezone"). The first point is the sprint's start day and carries the committed scope; the
 * last is the day the line stops (today for a running sprint, the completion day for a finished
 * one). There is no point after that: <strong>the line ends where it ends</strong>, and no
 * projection is drawn from it (§2.3 rule 2 — forecasting is §2.5, with a stated sample size).
 *
 * @param date      the UTC day
 * @param scope     total work in the sprint at the end of that day, in the requested measure.
 *                  Steps up on adds and down on removes, and on <strong>nothing else</strong> —
 *                  a re-estimate is not a scope event (§2.3 rule 1), which is the whole reason
 *                  this line can be trusted where a burndown's cannot
 * @param completed the part of {@code scope} that was closed by the end of that day. Bounded above
 *                  by {@code scope} by construction: an issue that leaves the sprint takes its
 *                  completion with it, so the two lines can meet but cannot cross
 */
public record BurnupPoint(LocalDate date, BigDecimal scope, BigDecimal completed) {}
