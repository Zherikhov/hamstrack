package com.hamstrack.common.dto;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Builds a clamped {@link PageRequest} from optional {@code page}/{@code size}
 * query params. Size is bounded to {@code [1, MAX_SIZE]} so a client can't ask
 * for an unbounded page; the SPA offers 10/20/50/100. Page defaults to 0.
 */
public final class Paging {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 50;

    private Paging() {}

    public static PageRequest of(Integer page, Integer size, Sort sort) {
        int p = (page == null || page < 0) ? 0 : page;
        int s = (size == null) ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        return PageRequest.of(p, s, sort);
    }
}
