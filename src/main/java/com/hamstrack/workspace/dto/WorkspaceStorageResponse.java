package com.hamstrack.workspace.dto;

import java.time.OffsetDateTime;

/**
 * The workspace storage summary (HD-191 §8.1) — {@code GET /api/workspaces/{id}/storage}, open
 * to <strong>any member</strong>.
 *
 * <p><strong>Why no permission beyond membership.</strong> These are the same figures the quota
 * refusal already hands the same person (§5.5), so gating them would hide from a member exactly
 * the number that tells "I am blocked" apart from "the server is broken", while disclosing
 * nothing new: it is a single tenant-wide aggregate with no per-project resolution, so the most
 * it says across a project boundary is that somebody in a workspace the reader belongs to has
 * uploaded a lot. The breakdown that names projects is a different endpoint behind
 * {@code workspace.edit}.
 *
 * <p><strong>Absent numbers rather than a sentinel.</strong> When {@code quotaEnabled} is false
 * there is no ceiling, so {@code quotaBytes}, {@code availableBytes} and {@code percentUsed} are
 * {@code null} — a {@code -1} here is a value a client will eventually render as
 * "-1 GB remaining", and a {@code 0} is worse because it reads as "full".
 *
 * <p>{@code percentUsed} is deliberately not clamped at 100: lowering the quota below current
 * usage is a legitimate operator action (§6.9), and clamping would hide the one state that
 * explains why every upload is being refused.
 *
 * <p>Bytes are raw integers everywhere. {@code asOf} is the counter row's own
 * {@code updated_at}, so a client can tell a fresh figure from one nothing has moved in weeks.
 */
public record WorkspaceStorageResponse(
        boolean quotaEnabled,
        Long quotaBytes,
        long usedBytes,
        Long availableBytes,
        long attachmentCount,
        Double percentUsed,
        int warnAtPercent,
        long maxFileBytes,
        OffsetDateTime asOf
) {}
