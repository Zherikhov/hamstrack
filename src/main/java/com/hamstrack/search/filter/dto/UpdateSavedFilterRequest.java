package com.hamstrack.search.filter.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/workspaces/{ws}/filters/{id}} (Advanced Search proposal
 * §8.3) — a <em>partial</em> update; only the non-null fields are applied. Owner
 * only (a non-owner, even on a shared filter, gets 404 — §16.5).
 *
 * <p>Every field is boxed so an omitted field is a no-op rather than a 400 under
 * Jackson 3's {@code FAIL_ON_NULL_FOR_PRIMITIVES} (CLAUDE.md gotcha). A field that is
 * present is applied; {@code hql}, when present, is re-validated (parse + structural)
 * and a bad value → 422.
 *
 * @param name   new name (≤ 120 chars) if present; re-checked for (workspace, owner)
 *               uniqueness — 409 on a dup.
 * @param hql    new HQL (≤ 2000 chars) if present; re-validated at save time.
 * @param shared new sharing flag if present.
 */
public record UpdateSavedFilterRequest(
        @Size(max = 120) String name,
        @Size(max = 2000) String hql,
        Boolean shared) {
}
