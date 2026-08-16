package com.hamstrack.issue.service;

import com.hamstrack.issue.repository.LabelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Re-reads the winner of a concurrent label create, in a <strong>separate</strong>
 * transaction (HD-30 §4.4, "two users create the same label concurrently").
 *
 * <p>Why its own bean: when the unique index arbitrates, Postgres aborts the losing
 * transaction — every further statement on that connection fails with
 * <em>"current transaction is aborted"</em>. So the "who won?" lookup that produces
 * the {@code existingId} in the 409 cannot run on the doomed transaction;
 * {@code REQUIRES_NEW} suspends it and borrows a clean connection.
 *
 * <p>Still workspace-scoped — the lookup is keyed by {@code (workspaceId, lower(name))},
 * never by name alone, so it can never surface another tenant's label id.
 */
@Component
@RequiredArgsConstructor
public class LabelConflictLookup {

    private final LabelRepository labelRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<UUID> findWinnerId(UUID workspaceId, String normalizedName) {
        return labelRepository.findIdByWorkspaceIdAndNameIgnoreCase(workspaceId, normalizedName);
    }
}
