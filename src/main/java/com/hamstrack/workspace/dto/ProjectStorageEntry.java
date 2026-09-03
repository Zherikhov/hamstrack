package com.hamstrack.workspace.dto;

import java.util.UUID;

/**
 * One row of the per-project storage breakdown (HD-191 §8.2) — built directly by an HQL
 * constructor expression, so the projection and the wire shape are the same object and cannot
 * drift apart.
 *
 * <p>Bytes are raw integers: formatting ("5.0 GB") is the client's business, and a server that
 * pre-formats them makes the number unusable for anything but display.
 *
 * <p>Real disclosure — project names and volumes — which is why this side of the feature is
 * behind {@code workspace.edit} while the workspace summary is open to every member.
 */
public record ProjectStorageEntry(
        UUID projectId,
        String key,
        String name,
        long bytes,
        long attachmentCount
) {
    /**
     * HQL hands {@code SUM} and {@code COUNT} back boxed; the canonical form keeps the wire
     * contract on primitives so a client never has to consider a null byte count.
     */
    public ProjectStorageEntry(UUID projectId, String key, String name, Long bytes, Long attachmentCount) {
        this(projectId, key, name,
                bytes == null ? 0L : bytes,
                attachmentCount == null ? 0L : attachmentCount);
    }
}
