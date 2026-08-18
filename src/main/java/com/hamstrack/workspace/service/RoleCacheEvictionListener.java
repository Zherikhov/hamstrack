package com.hamstrack.workspace.service;

import com.hamstrack.workspace.event.RolePermissionsChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Evicts a role's cached {@link RoleView} once — and only once — the edit that changed it
 * has actually committed (HD-127, S4).
 *
 * <p><strong>Why the deferral is the whole point.</strong> Evicting inside the editing
 * transaction is worse than not evicting at all: a concurrent reader misses, reloads the
 * <em>pre-commit</em> row (its own transaction cannot see the uncommitted edit) and
 * re-caches it, so the change goes unseen for a full TTL on the node that made it — the
 * exact failure the eviction exists to prevent, with the cost of the extra query. A
 * rolled-back edit must likewise not drop a cache entry that was correct all along.
 *
 * <p><strong>Not {@code @Transactional}</strong>, and no {@code fallbackExecution}: this
 * runs after the transaction is done, and there is no legitimate publisher outside one —
 * every publish site is a {@code @Transactional} write in {@code RoleService}. Dropping an
 * event published with no transaction is therefore the loud-by-omission behaviour we want
 * (the cached entry simply expires on its own TTL), not a silent miss. Contrast
 * {@code SseEventListener}, whose {@code fallbackExecution = true} is load-bearing because
 * attachment upload is intentionally non-transactional.
 */
@Component
@RequiredArgsConstructor
public class RoleCacheEvictionListener {

    private final RolePermissionCache cache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRolePermissionsChanged(RolePermissionsChanged e) {
        cache.evict(e.roleId());
    }
}
