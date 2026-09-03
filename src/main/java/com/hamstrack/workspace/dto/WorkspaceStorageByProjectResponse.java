package com.hamstrack.workspace.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Where a workspace's attachment bytes went, by project (HD-191 §8.2) —
 * {@code GET /api/workspaces/{id}/storage/projects}, behind {@code workspace.edit}.
 *
 * <p><strong>{@code unattributedBytes} is published rather than hidden.</strong> It is the
 * counter's total minus the sum of the rows below it, i.e. exactly the drift between what the
 * quota is enforcing and what the attachment rows say — and a non-zero value is the single
 * thing this page exists to let an operator see. Silently normalising it (or dropping the total
 * and summing the list instead) would make the page lie in the one state it was built for. It
 * can also be negative, if the counter has drifted low; that is reported as it stands and
 * corrected by the reconciler, not clamped here.
 *
 * <p>{@code projects} is sorted by {@code bytes} descending, and it names only projects of this
 * workspace: the query groups rows the {@code workspace_id} predicate already restricted, so
 * there is no project id in the request for a caller to aim elsewhere.
 */
public record WorkspaceStorageByProjectResponse(
        OffsetDateTime asOf,
        long totalBytes,
        long unattributedBytes,
        List<ProjectStorageEntry> projects
) {}
