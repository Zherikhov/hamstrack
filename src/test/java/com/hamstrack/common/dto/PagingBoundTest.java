package com.hamstrack.common.dto;

import com.hamstrack.common.exception.PageOffsetTooLargeException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>HD-163 AC-4, plus the belt the API cannot reach.</strong> Two claims that need no
 * container: that {@link Paging#MAX_PAGE} is the <em>derived</em> edge of the {@code int} offset
 * rather than a number somebody liked, and that {@link Paging#offsetOf} refuses the case it was
 * written for.
 *
 * <p><strong>No arbitrary large number appears in any assertion here</strong>, and that is the
 * point rather than a style preference. A test written against {@code 2_000_000_000} passes both
 * before and after the constant drifts, so it proves nothing about the edge — it only proves that
 * something very large is refused, which was already true when the edge was a 500. Every value
 * below is computed from {@link Paging#MAX_SIZE} and {@link Integer#MAX_VALUE}, so if the page-size
 * cap ever moves, this file follows it instead of going quietly stale.
 *
 * <p><strong>Why {@code offsetOf} needs a unit test at all.</strong> It is unreachable through the
 * API while the {@code @Max(Paging.MAX_PAGE)} at each door stands — argument resolution refuses
 * first, so no integration test can enter it. A belt that ships unexercised is a belt nobody knows
 * is buckled: it exists precisely for the day the annotation is relaxed, removed, or forgotten on a
 * seventh surface, and that is the day it must already work.
 */
class PagingBoundTest {

    /**
     * The invariant {@code MAX_PAGE} exists to hold, stated as the spec states it (§6.1) —
     * {@code (long) page × size ≤ Integer.MAX_VALUE} — asserted at the boundary and one past it.
     * The worst case is {@code size == MAX_SIZE}, because {@code size} is clamped server-side: a
     * ceiling that holds there holds for every smaller page size, which is what lets one fixed
     * {@code @Max} guard a variable {@code size}.
     */
    @Test
    void theBoundIsTheLastPageWhoseOffsetStillFitsAnInt() {
        assertThat((long) Paging.MAX_PAGE * Paging.MAX_SIZE)
                .as("""
                        MAX_PAGE must be ACCEPTABLE: at the largest page size the server will ever \
                        hand out, its offset has to fit the int a JPA setFirstResult takes. If this \
                        fails, the bound is too loose and the overflow HD-163 removed is back.""")
                .isLessThanOrEqualTo(Integer.MAX_VALUE);

        assertThat((long) (Paging.MAX_PAGE + 1) * Paging.MAX_SIZE)
                .as("""
                        …and MAX_PAGE + 1 must be the first page that does NOT fit. If this fails, \
                        the bound is too tight: it refuses page indexes that are arithmetically \
                        fine, which is a behavioural change for callers rather than a crash fix.""")
                .isGreaterThan(Integer.MAX_VALUE);
    }

    /**
     * The constant is the division, not its result. {@code Paging}'s javadoc argues for this at
     * length (it is the one deliberate divergence from ADR-0017's bare-numeral rule) and asks the
     * next reader not to "fix" it into {@code 21474836}; this is the assertion that makes the
     * request enforceable. Written as the division, raising {@code MAX_SIZE} narrows the page
     * ceiling automatically and the invariant above survives with no second edit — written as a
     * literal, the same change silently re-opens the overflow.
     */
    @Test
    void theBoundIsDerivedFromThePageSizeCapAndNotWrittenOut() {
        assertThat(Paging.MAX_PAGE)
                .as("""
                        MAX_PAGE must remain Integer.MAX_VALUE / MAX_SIZE. If someone replaced the \
                        expression with the number it happens to equal today, this still passes \
                        until MAX_SIZE moves — and then the literal keeps its old value while the \
                        invariant it was derived from no longer holds. Restore the division.""")
                .isEqualTo(Integer.MAX_VALUE / Paging.MAX_SIZE);
    }

    /**
     * The belt agrees with the annotation at the boundary: what {@code @Max} accepts,
     * {@code offsetOf} computes, and what {@code @Max} refuses, {@code offsetOf} refuses too —
     * with the same status, so relaxing the annotation changes where the refusal happens and never
     * what it looks like.
     */
    @Test
    void offsetOfAgreesWithTheAnnotationAtTheBoundary() {
        assertThat(Paging.offsetOf(Paging.MAX_PAGE, Paging.MAX_SIZE))
                .isEqualTo((int) ((long) Paging.MAX_PAGE * Paging.MAX_SIZE));

        assertThatThrownBy(() -> Paging.offsetOf(Paging.MAX_PAGE + 1, Paging.MAX_SIZE))
                .isInstanceOf(PageOffsetTooLargeException.class)
                .asInstanceOf(InstanceOfAssertFactories.type(PageOffsetTooLargeException.class))
                .extracting(PageOffsetTooLargeException::getStatus)
                .as("""
                        the belt must answer the same 400 the door answers. A belt that changed the \
                        status class would make relaxing the bound look like a NEW failure mode \
                        rather than the same refusal arriving one statement later.""")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * <strong>The case this method exists for, and it is not the negative wrap.</strong>
     *
     * <p>A product that wraps <em>negative</em> is loud: Hibernate rejects a negative
     * {@code firstResult} and the caller gets an unmistakable 500. A product that wraps far enough
     * to land back in the positive range ({@code page × size ≥ 2³²}) is the dangerous one — it is
     * an entirely ordinary-looking offset, so the request answers <strong>200 with the wrong
     * rows</strong> and nothing anywhere says so. Silent wrong data outranks a visible crash, which
     * is why the check is on the arithmetic and not on the sign.
     *
     * <p>The page index below is derived, not chosen: the smallest one whose offset at
     * {@code MAX_SIZE} reaches 2³². The test first demonstrates that the naive {@code int}
     * multiplication really does produce a small, plausible, wholly wrong offset — without that
     * half, "offsetOf throws" would be indistinguishable from a bound that merely dislikes big
     * numbers.
     */
    @Test
    void offsetOfRefusesTheWrapThatWouldOtherwiseAnswer200WithTheWrongRows() {
        int wrapping = (int) Math.ceilDiv(1L << Integer.SIZE, Paging.MAX_SIZE);

        long honest = (long) wrapping * Paging.MAX_SIZE;
        int naive = wrapping * Paging.MAX_SIZE;

        assertThat(honest)
                .as("the probe must actually reach 2^32, or it is not exercising the wrap at all")
                .isGreaterThanOrEqualTo(1L << Integer.SIZE);
        assertThat(naive)
                .as("""
                        The fixture itself. Computed in int, page %d at size %d has an offset of \
                        %d — small, positive and utterly wrong. Nothing downstream can tell it from \
                        a real offset: Hibernate takes it, PostgreSQL honours it, and the caller is \
                        served the first rows of the result set for a page index in the tens of \
                        millions. THAT silent answer, not the negative wrap, is what \
                        Paging.offsetOf exists to refuse.""", wrapping, Paging.MAX_SIZE, naive)
                .isNotNegative()
                .isLessThan(Paging.MAX_SIZE);

        assertThatThrownBy(() -> Paging.offsetOf(wrapping, Paging.MAX_SIZE))
                .as("""
                        offsetOf must refuse rather than return the wrapped offset. If this ever \
                        returns a value, any paged surface reached with this index answers 200 with \
                        rows the caller did not ask for, and no status code, log line or metric \
                        records that it happened.""")
                .isInstanceOf(PageOffsetTooLargeException.class);
    }

    /**
     * A negative index is coerced to 0 rather than refused, exactly as {@link Paging#of} coerces
     * it — deliberately left alone by HD-163 (§11 Q1), because turning a request that answers 200
     * today into a 400 is a behavioural change on six endpoints at once and belongs to its own
     * ticket. The asymmetry (coerce below, refuse above) is intentional, and is pinned here so it
     * is not "tidied" into a refusal by accident.
     */
    @Test
    void aNegativeIndexIsStillCoercedRatherThanRefused() {
        assertThat(Paging.offsetOf(-5, Paging.MAX_SIZE)).isZero();
        assertThat(Paging.of(-5, Paging.MAX_SIZE, Sort.unsorted()).getPageNumber()).isZero();
    }
}
