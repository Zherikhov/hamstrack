package com.hamstrack.report.csv;

import com.hamstrack.report.dto.AgingReportResponse;
import com.hamstrack.report.dto.CycleTimeReportResponse;
import com.hamstrack.report.dto.FlowBucket;
import com.hamstrack.report.dto.FlowReportResponse;
import com.hamstrack.report.dto.ReportMeta;
import com.hamstrack.report.dto.ReportPercentiles;
import com.hamstrack.report.dto.SprintBurnupResponse;
import com.hamstrack.report.dto.SprintReviewList;
import com.hamstrack.report.dto.SprintReviewResponse;
import com.hamstrack.report.dto.VelocityReportResponse;
import com.hamstrack.report.service.CycleTimeReportService;
import com.hamstrack.report.service.VelocityService;

import java.math.BigDecimal;
import java.util.List;

/**
 * <strong>The six report exports: the plotted series, one row per data point.</strong>
 *
 * <p>That sentence is the feature, and the opposite of it is the documented failure. Section 1.6
 * of the proposal found that where an export of this kind exists at all, people ask for the chart
 * and are handed "flat issue lists without burndown or velocity context" - so they go back to
 * screenshotting. Nothing here exports "the issues that matched"; each renderer exports the
 * numbers that were drawn, in the shape they were drawn in, with a comment header saying which
 * project, which window, which measure, when it was computed and how many issues it was computed
 * from. <strong>"Download matching issues" is a different button</strong> that hands off to the
 * search export, and the two must never be served by one endpoint - the moment they are, the
 * cheaper one wins and the chart export is gone again.
 *
 * <p><strong>{@code cycle-time} and {@code aging} are the two that look like the mistake and are
 * not.</strong> Their rows are issues because their charts are scatter plots <em>of issues</em>:
 * the plotted value is {@code cycleDays} / {@code ageDays}, and the key and title are there to
 * identify a dot the reader is pointing at. The test is not "does a row mention an issue" but
 * "is every row a point on the chart, and is every point on the chart a row" - which is why
 * neither of them carries a description, a status history, a component list or any of the other
 * columns an issue export would have and a chart does not plot.
 *
 * <p><strong>Every honesty rule the JSON carries travels with the numbers.</strong> An export is a
 * new surface, not a serialisation format, and the reasoning that drops a caveat is always "it is
 * the same data as a file":
 * <ul>
 *   <li>a suppressed percentile pair or a suppressed velocity band is suppressed here too, and
 *       the header says why rather than leaving an empty column to be read as zero;</li>
 *   <li>cycle time is only defined where a start exists, so {@code cycleDays} is empty - never
 *       backfilled from {@code created_at} - and the header counts how many rows that is;</li>
 *   <li>a truncated series says so in the header instead of silently shipping fewer rows;</li>
 *   <li>a filter that matched nothing is named, so an empty file is never ambiguous;</li>
 *   <li>a partial bucket is flagged in its own column.</li>
 * </ul>
 *
 * <p><strong>{@link #velocity} may not gain a per-person column.</strong> The refusals in section
 * 1.4 are enforced by {@code VelocityRefusalTest} walking the JSON <em>records</em>, and this file
 * returns a {@code String} - so the guard that stops {@code assigneeId} appearing in the JSON
 * cannot see it appearing here. {@link #VELOCITY_COLUMNS} is therefore public and is asserted
 * against {@code VelocitySprint}'s components by that same test: the export is allowed to be a
 * subset of the record and nothing else, which puts the CSV back inside the net that already
 * exists rather than beside it.
 */
public final class ReportCsv {

    public static final List<String> FLOW_COLUMNS =
            List.of("date", "created", "resolved", "openAtEnd", "partial");

    public static final List<String> CYCLE_TIME_COLUMNS =
            List.of("issueId", "key", "title", "typeId", "startedAt", "closedAt",
                    "cycleDays", "leadDays");

    public static final List<String> AGING_COLUMNS =
            List.of("statusId", "status", "category", "issueId", "key", "title", "ageDays",
                    "assigneeId", "startedAt");

    public static final List<String> SPRINT_BURNUP_COLUMNS =
            List.of("date", "scope", "completed");

    public static final List<String> SPRINT_REVIEW_COLUMNS =
            List.of("list", "issueId", "key", "title", "typeId", "assigneeId", "statusId",
                    "points", "closedAt", "deleted");

    /**
     * <strong>Exactly the components of {@code VelocitySprint}, and nothing that is not
     * one.</strong> See the class javadoc: this list is the CSV's half of the section 1.4
     * refusals, and {@code VelocityRefusalTest} holds it to the record.
     */
    public static final List<String> VELOCITY_COLUMNS =
            List.of("sprintId", "name", "startAt", "completedAt", "committed", "completed",
                    "addedAfterStart", "carriedOver", "unestimatedCount");

    private ReportCsv() {
    }

    // ------------------------------------------------------------------ flow

    public static String flow(ReportSubject subject, FlowReportResponse report) {
        var csv = head("flow", subject);
        csv.comment("Window", report.from() + " to " + report.to()
                              + " (inclusive, on created_at and closed_at)");
        csv.comment("Interval", report.interval());
        csv.comment("Totals", "created=" + report.totals().created()
                              + ", resolved=" + report.totals().resolved()
                              + ", net=" + report.totals().net());
        if (report.buckets().stream().anyMatch(FlowBucket::partial)) {
            csv.note("A bucket with partial=true is clipped by the window, so it covers fewer days "
                     + "than the interval and its counts are not comparable with a full one.");
        }
        unmatchedFilters(csv, report.meta());
        tail(csv, report.meta());

        csv.columns(FLOW_COLUMNS);
        for (var bucket : report.buckets()) {
            csv.row(bucket.date(), bucket.created(), bucket.resolved(), bucket.openAtEnd(),
                    bucket.partial());
        }
        return csv.toCsv();
    }

    // ------------------------------------------------------------------ cycle time

    public static String cycleTime(ReportSubject subject, CycleTimeReportResponse report) {
        var csv = head("cycle-time", subject);
        csv.comment("Window", report.from() + " to " + report.to() + " (inclusive, on closed_at)");
        percentiles(csv, "Cycle time percentiles (days)", report.percentiles().cycle(),
                CycleTimeReportService.MIN_PERCENTILE_SAMPLES + " completed issues with a start");
        percentiles(csv, "Lead time percentiles (days)", report.percentiles().lead(),
                CycleTimeReportService.MIN_PERCENTILE_SAMPLES + " completed issues");
        csv.comment("Sample size", report.sampleSize());
        csv.comment("Completed issues with no start", report.missingStartCount());
        if (report.missingStartCount() > 0) {
            csv.note("cycleDays is empty for those rows. created_at is never substituted for a "
                     + "missing start, so they contribute to leadDays and to nothing else.");
        }
        unmatchedFilters(csv, report.meta());
        tail(csv, report.meta());

        csv.columns(CYCLE_TIME_COLUMNS);
        for (var item : report.items()) {
            csv.row(item.issueId(), item.key(), item.title(), item.typeId(),
                    item.startedAt(), item.closedAt(), item.cycleDays(), item.leadDays());
        }
        return csv.toCsv();
    }

    // ------------------------------------------------------------------ aging

    public static String aging(ReportSubject subject, AgingReportResponse report) {
        var csv = head("aging", subject);
        csv.comment("Window", "none - aging is a current-state report, so there is no window to "
                              + "state and none was applied");
        percentiles(csv, "Cycle time percentiles (days)", report.percentiles(),
                CycleTimeReportService.MIN_PERCENTILE_SAMPLES + " completed issues with a start");
        csv.note("Those percentiles are the project's completed work, drawn as reference lines "
                 + "across the columns. They are NOT computed from the open issues below.");
        tail(csv, report.meta());

        csv.columns(AGING_COLUMNS);
        for (var column : report.columns()) {
            for (var item : column.items()) {
                csv.row(column.statusId(), column.name(), column.category(),
                        item.issueId(), item.key(), item.title(), item.ageDays(),
                        item.assigneeId(), item.startedAt());
            }
        }
        return csv.toCsv();
    }

    // ------------------------------------------------------------------ sprint burn-up

    public static String sprintBurnup(ReportSubject subject, SprintBurnupResponse report) {
        var csv = head("sprint-burnup", subject);
        sprintHead(csv, report.sprint() == null ? null : report.sprint().name(),
                report.sprint() == null ? null : report.sprint().id(),
                report.sprint() == null ? null : report.sprint().state(),
                "there is nothing to plot");
        csv.comment("Window", report.startAt() + " to " + report.endAt());
        csv.comment("Measure", report.measure());
        csv.comment("Committed at start", report.committedAtStart());
        csv.comment("Issues in scope with no estimate", report.unestimatedCount());
        if (report.unestimatedCount() > 0) {
            csv.note("Those issues contribute 0 to a POINTS series, which is why a points line can "
                     + "sit below a count line for the same sprint.");
        }
        csv.comment("Scope changes", report.scopeChanges().size()
                                     + " - the scope log is a different shape from a series and is "
                                     + "not exported here; it is on the JSON endpoint");
        if (report.seriesTruncatedAt() != null) {
            csv.comment("Series truncated at", report.seriesTruncatedAt()
                                               + " - the days after that date are missing from "
                                               + "this file rather than absent from the sprint");
        }
        tail(csv, report.meta());

        csv.columns(SPRINT_BURNUP_COLUMNS);
        for (var point : report.series()) {
            csv.row(point.date(), point.scope(), point.completed());
        }
        return csv.toCsv();
    }

    // ------------------------------------------------------------------ sprint review

    public static String sprintReview(ReportSubject subject, SprintReviewResponse report) {
        var csv = head("sprint-review", subject);
        sprintHead(csv, report.sprint() == null ? null : report.sprint().name(),
                report.sprint() == null ? null : report.sprint().id(),
                report.sprint() == null ? null : report.sprint().state(),
                "there is nothing to record");
        csv.comment("Window", report.startAt() + " to " + report.endAt());
        csv.comment("Completed at", report.completedAt());
        var totals = report.totals();
        csv.comment("Totals (count/points)", "committed=" + totals.committedCount()
                                             + "/" + points(totals.committedPoints())
                                             + ", at end=" + totals.atEndCount()
                                             + "/" + points(totals.atEndPoints())
                                             + ", completed=" + totals.completedCount()
                                             + "/" + points(totals.completedPoints())
                                             + ", added after start=" + totals.addedAfterStartCount());
        listSummary(csv, "committed", report.committed());
        listSummary(csv, "addedAfterStart", report.addedAfterStart());
        listSummary(csv, "removedBeforeEnd", report.removedBeforeEnd());
        listSummary(csv, "completed", report.completed());
        listSummary(csv, "carriedOver", report.carriedOver());
        csv.note("An issue appears once per list it belongs to, so the same key can occur several "
                 + "times below; the list column says which line of the record a row belongs to. "
                 + "points are the ledger's entry snapshots, not today's estimates.");
        tail(csv, report.meta());

        csv.columns(SPRINT_REVIEW_COLUMNS);
        listRows(csv, "committed", report.committed());
        listRows(csv, "addedAfterStart", report.addedAfterStart());
        listRows(csv, "removedBeforeEnd", report.removedBeforeEnd());
        listRows(csv, "completed", report.completed());
        listRows(csv, "carriedOver", report.carriedOver());
        return csv.toCsv();
    }

    private static void listSummary(CsvWriter csv, String name, SprintReviewList list) {
        csv.comment("List " + name, list.count() + " issues, " + points(list.points())
                                    + " points, " + list.unestimatedCount() + " unestimated");
    }

    private static void listRows(CsvWriter csv, String name, SprintReviewList list) {
        for (var issue : list.issues()) {
            csv.row(name, issue.issueId(), issue.key(), issue.title(), issue.typeId(),
                    issue.assigneeId(), issue.statusId(), issue.points(), issue.closedAt(),
                    issue.deleted());
        }
    }

    // ------------------------------------------------------------------ velocity

    public static String velocity(ReportSubject subject, VelocityReportResponse report) {
        var csv = head("velocity", subject);
        csv.comment("Measure", report.measure());
        csv.comment("Window", report.sprints().size() + " completed sprints");
        var forecast = report.forecast();
        if (forecast.p50() == null) {
            csv.comment("Forecast", "suppressed - the sample is " + forecast.sampleSize()
                                    + " completed sprints and a band needs at least "
                                    + VelocityService.MIN_FORECAST_SPRINTS
                                    + ". The bars below are facts and are exported anyway.");
        } else {
            csv.comment("Forecast", "p50=" + CsvWriter.number(forecast.p50())
                                    + ", p85=" + CsvWriter.number(forecast.p85())
                                    + ", sample size=" + forecast.sampleSize()
                                    + " - a range to plan the next sprint with, and deliberately "
                                    + "not a single number to compare teams by");
        }
        tail(csv, report.meta());

        csv.columns(VELOCITY_COLUMNS);
        for (var sprint : report.sprints()) {
            csv.row(sprint.sprintId(), sprint.name(), sprint.startAt(), sprint.completedAt(),
                    sprint.committed(), sprint.completed(), sprint.addedAfterStart(),
                    sprint.carriedOver(), sprint.unestimatedCount());
        }
        return csv.toCsv();
    }

    // ------------------------------------------------------------------ shared header

    private static CsvWriter head(String report, ReportSubject subject) {
        return new CsvWriter()
                .comment("Hamstrack report", report)
                .comment("Project", subject.key() + " - " + subject.name())
                .comment("Project id", subject.projectId());
    }

    /**
     * The sprint identity block, or the note that there is no sprint. "No ACTIVE sprint" is a 200
     * on both sprint endpoints rather than a 404, so it has to be a readable line in the file
     * too - an empty CSV with no explanation is exactly the ambiguity the JSON avoided.
     */
    private static void sprintHead(CsvWriter csv, String name, Object id, Object state,
                                   String nothingTo) {
        if (name == null) {
            csv.note("No sprint: this project has no ACTIVE sprint and none was named, so "
                     + nothingTo + ". That is an answer, not an error.");
            return;
        }
        csv.comment("Sprint", name);
        csv.comment("Sprint id", id);
        csv.comment("Sprint state", state);
    }

    /**
     * {@code computedAt}, {@code basedOnIssues} and the truncation disclosure - the three section
     * 1.7 requires every bounded report to print, here as the last thing before the data so a
     * reader at the top of a spreadsheet sees them next to the numbers.
     */
    private static void tail(CsvWriter csv, ReportMeta meta) {
        csv.comment("Computed at", meta.computedAt());
        csv.comment("Based on issues", meta.basedOnIssues());
        if (meta.truncated()) {
            csv.comment("Truncated", "YES - the row cap of " + meta.cap()
                                     + " (app.reports.max-rows) bit, so this file is NOT the whole "
                                     + "series. Narrow the window or the filters and export again.");
        } else {
            csv.comment("Truncated", "no (row cap " + meta.cap() + ")");
        }
    }

    private static void unmatchedFilters(CsvWriter csv, ReportMeta meta) {
        if (meta.unmatchedFilters().isEmpty()) {
            return;
        }
        csv.comment("Filters that matched nothing", String.join(", ", meta.unmatchedFilters())
                                                    + " - which is why this file is empty or thin, "
                                                    + "rather than the project being quiet");
    }

    private static void percentiles(CsvWriter csv, String label, ReportPercentiles pair,
                                    String requirement) {
        if (pair == null || pair.p50() == null) {
            csv.comment(label, "suppressed - fewer than " + requirement
                               + ", and a percentile over a smaller sample is noise");
            return;
        }
        csv.comment(label, "p50=" + CsvWriter.number(pair.p50())
                           + ", p85=" + CsvWriter.number(pair.p85()));
    }

    private static String points(BigDecimal value) {
        return value == null ? "0" : CsvWriter.number(value);
    }
}
