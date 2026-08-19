package com.hamstrack.report.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * The bucket width of a time-series report (reports-proposal §2.1).
 *
 * <p>Every boundary is a <strong>UTC</strong> boundary (§6, "Timezone"), and the
 * bucketing itself happens in PostgreSQL via {@code date_trunc} — never by counting
 * rows in Java. This enum is the one place the two halves of that arrangement are
 * kept in step: {@link #sqlUnit()} is what {@code date_trunc} is given, and
 * {@link #truncate}/{@link #next} reproduce exactly the same boundaries in Java so the
 * service can zero-fill the buckets PostgreSQL had no rows for. If those two ever
 * disagree, empty buckets land on dates no non-empty bucket can ever occupy and the
 * chart grows phantom points — so they are defined side by side, deliberately.
 *
 * <p><strong>Weeks start on Monday</strong>, because that is what PostgreSQL's
 * {@code date_trunc('week', …)} does (ISO-8601). It is not configurable in v1.
 */
public enum ReportInterval {

    DAY("day") {
        @Override
        public LocalDate truncate(LocalDate date) {
            return date;
        }

        @Override
        public LocalDate next(LocalDate bucketStart) {
            return bucketStart.plusDays(1);
        }
    },

    WEEK("week") {
        @Override
        public LocalDate truncate(LocalDate date) {
            return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }

        @Override
        public LocalDate next(LocalDate bucketStart) {
            return bucketStart.plusWeeks(1);
        }
    };

    private final String sqlUnit;

    ReportInterval(String sqlUnit) {
        this.sqlUnit = sqlUnit;
    }

    /** The first argument to {@code date_trunc}. */
    public String sqlUnit() {
        return sqlUnit;
    }

    /**
     * The start of the bucket containing {@code date} — the Java twin of
     * {@code date_trunc(sqlUnit(), …)}.
     *
     * <p>Note that {@code truncate(from)} can land <em>before</em> the requested window
     * start: a WEEK window beginning on a Wednesday opens with a bucket dated the
     * previous Monday. That first bucket (and, symmetrically, the last one) is
     * <strong>partial</strong> — it counts only what happened inside the window, so its
     * bar is legitimately shorter than a full week's. The alternative would be snapping
     * the caller's window to bucket boundaries, i.e. quietly answering a different
     * question than the one asked, which is the one thing this feature refuses to do.
     */
    public abstract LocalDate truncate(LocalDate date);

    /** The start of the bucket after the one starting at {@code bucketStart}. */
    public abstract LocalDate next(LocalDate bucketStart);
}
