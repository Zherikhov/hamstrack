package com.hamstrack.report.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * <strong>One definition of "a report window", and one definition of how one is refused.</strong>
 * Extracted from {@link FlowQuery}/{@code FlowReportService} when R3 became the second report
 * to need it (reports-proposal §4.3, §6) — verbatim, messages included.
 *
 * <p>The extraction is not tidying. The window rules are the spec's most load-bearing
 * behaviour ("refuse, never clamp"), and the failure mode of duplicating them is that R3, R4
 * and R5 each end up with <em>almost</em> the same refusal: a cap named in one message and not
 * in another, a band checked on {@code from} but not on {@code to}, a default window that one
 * endpoint caps against the configured maximum and the next one does not. Each of those is a
 * separate 400-or-500 that only appears on somebody's oddly-configured install. There is one
 * copy here, and every report calls it.
 *
 * <p>A class of statics rather than a record: the window is carried by each report's own query
 * record ({@link FlowQuery}, {@link CycleTimeQuery}) alongside that report's own parameters,
 * and wrapping two {@link LocalDate}s in a value object would only add a level of unwrapping at
 * every use site. What must be shared is the <em>arithmetic and the refusals</em>, and that is
 * exactly what lives here.
 */
public final class ReportWindow {

    /** The documented default window, in days, inclusive of both endpoints (§2.1, §2.2). */
    public static final int DEFAULT_WINDOW_DAYS = 90;

    /**
     * The oldest date any report window may touch. The epoch: {@code created_at} is written
     * by this application and no row can predate it, so a request reaching further back is a
     * typo or a fuzzer, never a question about data.
     */
    public static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);

    /**
     * The newest date any report window may touch — and the reason the constant exists at
     * all (HD-28 R1 round 2, item 3).
     *
     * <p>{@code ISO_DATE}'s year field is {@code EXCEEDS_PAD}, so {@code +999999999-12-31}
     * <em>binds</em>: it arrives as a perfectly ordinary {@link LocalDate}, passes a
     * 90-day-window check, and then throws {@link java.time.DateTimeException} on
     * {@link #endExclusive}'s {@code plusDays(1)} — a 500 on an endpoint whose contract
     * promises 400 for a bad date. The sibling case needs no overflow at all: a year inside
     * Java's range but outside PostgreSQL's {@code timestamptz} fails in the driver instead.
     * Both are refused before any arithmetic runs.
     *
     * <p>2200 rather than something tighter because a window may legitimately end in the
     * future (a burn-up runs to a sprint's end date), and far below the {@code timestamptz}
     * ceiling because the point is a sane band, not the last representable instant.
     */
    public static final LocalDate MAX_DATE = LocalDate.of(2200, 12, 31);

    private ReportWindow() {
    }

    /**
     * The default window length actually used, in days, given the configured cap.
     *
     * <p><strong>This is defaulting, not clamping — do not "fix" it back.</strong> The 90 is
     * capped by {@code maxWindowDays} because otherwise the endpoint's own default request is
     * refused by the endpoint's own cap: {@code REPORTS_MAX_WINDOW_DAYS=30} is inside the
     * documented range 1–3650, and before round 2 it turned the parameterless call the reports
     * page makes on load into a permanent 400 (HD-28 R1 round 2, item 2). R3 inherits the fix
     * by inheriting this method rather than by remembering the story.
     *
     * <p>The never-silently-narrow rule is untouched by this, and the distinction is exact: a
     * clamp narrows a window the caller <em>asked for</em>, and that is still a 400 naming the
     * cap ({@link #validate}). Here the caller asked for nothing, so there is no window to
     * narrow — the server is picking its own default and must pick one it will actually serve.
     */
    public static int defaultWindowDays(int maxWindowDays) {
        return Math.min(DEFAULT_WINDOW_DAYS, maxWindowDays);
    }

    /**
     * The date band, refused in the same refuse-and-name-it wording as the cap.
     *
     * <p>Must run <strong>before</strong> {@link #validate} and before the default window is
     * derived, because both do date arithmetic: at {@code to=+999999999-12-31} — which
     * {@code ISO_DATE} parses happily — the half-open window's own {@code plusDays(1)}
     * overflows and throws, and a year merely outside PostgreSQL's {@code timestamptz} fails
     * later in the driver. Both used to be 500s. {@code GlobalExceptionHandler} now maps
     * {@code DateTimeException} to a 400 as a backstop, but a backstop is not a message: the
     * refusal a caller can act on is this one.
     */
    public static void validateBand(LocalDate from, LocalDate to) {
        requireInBand(from, "from");
        requireInBand(to, "to");
    }

    private static void requireInBand(LocalDate date, String name) {
        if (date == null || (!date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid window: " + name + " (" + date + ") is outside the supported range "
                        + MIN_DATE + " to " + MAX_DATE + ". Reports cover "
                        + "issue history, which cannot predate the epoch, and a date past 2200 "
                        + "is a typo rather than a question.");
    }

    /**
     * The two 400s of §6, and the single most important behavioural rule in the spec: a window
     * this server will not serve is <strong>refused with the cap named</strong>, not silently
     * reduced to one it likes better.
     *
     * <p>Both messages state the numbers they measured, because the caller of a report API is
     * usually a chart that did the date arithmetic itself and needs to know which of the two
     * ends it got wrong.
     */
    public static void validate(LocalDate from, LocalDate to, int maxWindowDays) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid window: from (" + from + ") is after to (" + to + ")");
        }
        long days = windowDays(from, to);
        if (days > maxWindowDays) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Window is " + days + " days (" + from + " to " + to + "); the maximum is "
                            + maxWindowDays + " days (app.reports.max-window-days). "
                            + "Narrow the range — it is not clamped for you, because a report of a "
                            + "different window than the one you asked for is worse than no report.");
        }
    }

    /** Window length in days, counting both endpoints. Negative when {@code from > to}. */
    public static long windowDays(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** Window start as an instant — {@code from} at 00:00 UTC, inclusive. */
    public static OffsetDateTime startInclusive(LocalDate from) {
        return from.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /**
     * Window end as an instant — the start of the day AFTER {@code to}, exclusive. Half-open
     * so that no timestamp can be counted twice or missed, and so that both range predicates
     * stay index-friendly ({@code >=} … {@code <}).
     *
     * <p>{@code plusDays(1)} is the overflow {@link #MAX_DATE} exists to prevent; every caller
     * validates the band before reaching here.
     */
    public static OffsetDateTime endExclusive(LocalDate to) {
        return to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
