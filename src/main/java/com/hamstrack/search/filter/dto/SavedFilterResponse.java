package com.hamstrack.search.filter.dto;

import com.hamstrack.search.filter.entity.SavedFilter;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape for a saved filter (Advanced Search proposal §8.3). {@code mine} is
 * caller-relative — {@code true} when the authenticated actor owns the filter — so
 * the SPA can show edit/delete only on the caller's own rows and render shared rows
 * owned by others as read-only.
 */
public record SavedFilterResponse(
        UUID id,
        String name,
        String hql,
        boolean shared,
        UUID ownerId,
        String ownerName,
        boolean mine,
        Instant createdAt,
        Instant updatedAt) {

    /** Map an entity for a given caller. {@code owner} must be loaded (repos fetch it). */
    public static SavedFilterResponse of(SavedFilter f, UUID actorId) {
        var owner = f.getOwner();
        return new SavedFilterResponse(
                f.getId(),
                f.getName(),
                f.getHql(),
                f.isShared(),
                owner.getId(),
                owner.getDisplayName(),
                owner.getId().equals(actorId),
                f.getCreatedAt(),
                f.getUpdatedAt());
    }
}
