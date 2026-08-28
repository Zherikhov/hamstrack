package com.hamstrack.common.dto;

import com.hamstrack.common.exception.PageOffsetTooLargeException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Builds a clamped {@link PageRequest} from optional {@code page}/{@code size}
 * query params. Size is bounded to {@code [1, MAX_SIZE]} so a client can't ask
 * for an unbounded page; the SPA offers 10/20/50/100. Page defaults to 0.
 *
 * <p><strong>The index is bounded at the edge, and belted here.</strong> A refusal made
 * inside this class is necessarily late — by the time a caller's value reaches
 * {@code of(...)} or {@link #offsetOf} the handler has been entered and, on some surfaces,
 * a count query already paid for. So {@link #MAX_PAGE} is declared here and <em>applied</em>
 * as a {@code @Max} on whatever carries the index into the request (a DTO field or a
 * {@code @RequestParam}), which refuses during argument resolution — before any query and
 * before membership is resolved (HD-163). Any new surface that lets a caller name a page
 * index owes that annotation; a surface that fixes the index at 0 owes nothing, because it
 * has no offset to overflow.
 *
 * <p>{@link #offsetOf} is the second line, for whatever gets past the first: it does the
 * multiplication in {@code long} and refuses an offset no {@code int} can hold. The two are
 * not redundant — the annotation is what makes the refusal <em>free</em>, and the belt is
 * what survives the annotation being relaxed. Anything that turns a caller-named index into
 * a JPA {@code setFirstResult} calls it rather than multiplying inline.
 */
public final class Paging {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 50;

    /**
     * Largest page index a caller may ask for: the one that keeps the offset inside an
     * {@code int} at the largest page size this class will ever hand out.
     *
     * <pre>
     *   21_474_836 × 100 = 2_147_483_600 ≤ Integer.MAX_VALUE   ✔
     *   21_474_837 × 100 = 2_147_483_700 &gt; Integer.MAX_VALUE   ✘
     * </pre>
     *
     * <p>The worst case is {@code size == MAX_SIZE}, and {@code size} is clamped
     * server-side, so a ceiling that holds there holds for every smaller size. Above it
     * the arithmetic breaks in two different ways depending on who does it — an
     * {@code int} multiplication overflows to a negative {@code firstResult}
     * ({@code IllegalArgumentException}), and Spring Data's own offset conversion
     * refuses ({@code InvalidDataAccessApiUsageException}). Both were 500s; the
     * annotation makes both a 400, and {@link #offsetOf} keeps the first of them a 400
     * even if the annotation goes away.
     *
     * <p><strong>Deliberately an expression, against ADR-0017's bare-numeral rule.</strong>
     * That rule exists because {@code EmailLengthBoundTest} regex-scans
     * {@code @Size(max = …)} for digits, and a symbolic reference would read as a
     * missing bound. No scanner reads {@code @Max}, and this is a <em>derived
     * invariant</em> rather than a column width: written as the division it stays
     * correct if {@code MAX_SIZE} ever moves, where a literal would silently re-open the
     * overflow. Both operands are compile-time constants, so the expression is legal as
     * an annotation argument. Do not "fix" this into {@code 21474836}.
     */
    public static final int MAX_PAGE = Integer.MAX_VALUE / MAX_SIZE;

    private Paging() {}

    public static PageRequest of(Integer page, Integer size, Sort sort) {
        int p = (page == null || page < 0) ? 0 : page;
        int s = (size == null) ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        return PageRequest.of(p, s, sort);
    }

    /**
     * The 0-based row offset {@code page × size} implies, as the {@code int} a JPA
     * {@code setFirstResult} demands — or a 400 ({@link PageOffsetTooLargeException}) when
     * the product does not fit in one.
     *
     * <p><strong>Why the product is computed in {@code long} and checked, rather than left
     * to overflow visibly.</strong> The two ways an {@code int} multiplication can go wrong
     * here are not equally loud. A product that wraps <em>negative</em> reaches Hibernate,
     * which rejects a negative {@code firstResult} — ugly (a 500) but impossible to miss. A
     * product that wraps far enough to land back in the positive range
     * ({@code page × size ≥ 2³²}) is an entirely ordinary-looking offset: the request answers
     * <strong>200 with the wrong rows</strong>, and nothing anywhere says so. The silent
     * outcome is the one worth spending a branch on, so the check is on the arithmetic and
     * not on its sign.
     *
     * <p>It refuses instead of clamping, because clamping answers a page the caller did not
     * ask for, and it refuses with a translated 400 rather than {@code Math.toIntExact}'s
     * {@code ArithmeticException}, which no handler translates and which would answer the
     * same 500 this whole bound exists to remove.
     *
     * @param page 0-based page index; a negative value is coerced to 0, matching {@link #of}
     * @param size page size, already clamped by the caller
     */
    public static int offsetOf(int page, int size) {
        long offset = (long) Math.max(page, 0) * Math.max(size, 0);
        if (offset > Integer.MAX_VALUE) {
            throw new PageOffsetTooLargeException();
        }
        return (int) offset;
    }
}
