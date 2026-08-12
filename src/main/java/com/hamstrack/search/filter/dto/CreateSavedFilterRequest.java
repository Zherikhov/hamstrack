package com.hamstrack.search.filter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/workspaces/{ws}/filters} (Advanced Search proposal §8.3).
 * The owner is always the caller — never taken from the body.
 *
 * @param name   required, ≤ 120 chars; unique per (workspace, owner) — 409 on a dup.
 * @param hql    required (may be an empty string — "all issues"); validated at save
 *               time (parse + structural registry validation) — a bad query → 422
 *               with the same shape as search. ≤ 2000 chars (matches the parser cap).
 * @param shared whether the filter is visible read-only to other workspace members;
 *               boxed so a body omitting it does not 400 under Jackson 3's
 *               {@code FAIL_ON_NULL_FOR_PRIMITIVES} — coalesced to {@code false}.
 */
public record CreateSavedFilterRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String hql,
        Boolean shared) {

    public CreateSavedFilterRequest {
        shared = shared != null && shared;
    }

    public String hqlOrEmpty() {
        return hql == null ? "" : hql;
    }
}
