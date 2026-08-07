package com.hamstrack.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Uniform pagination envelope for list endpoints. Keeps the wire shape stable
 * and small (no Spring {@code Page} internals leak out): the rows plus the
 * cursor metadata the SPA needs to render a pager / "load more".
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static <T> PageResponse<T> of(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages(), p.hasNext());
    }
}
