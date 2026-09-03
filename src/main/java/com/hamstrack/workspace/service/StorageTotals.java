package com.hamstrack.workspace.service;

/**
 * What one workspace's attachment rows actually add up to — the reconciler's own arithmetic,
 * against which {@code workspace_storage_usage} is checked (HD-191 §7.3).
 *
 * <p>Internal: it is never serialised and has no place in a response body. The public figures
 * are {@code WorkspaceStorageResponse}'s, which come from the counter.
 *
 * <p>{@code bytes} is boxed because {@code SUM} over no rows is {@code null} — a workspace that
 * has never held an attachment is the ordinary case, not an error — and the alternative
 * ({@code COALESCE(SUM(…), 0)}) would need its literal's type pinned by hand in HQL. {@code count}
 * is boxed only for symmetry; {@code COUNT} is never null.
 */
public record StorageTotals(Long bytes, Long count) {

    public long bytesOrZero() {
        return bytes == null ? 0L : bytes;
    }

    public long countOrZero() {
        return count == null ? 0L : count;
    }
}
