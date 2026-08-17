package com.hamstrack.workspace.service;

import com.hamstrack.workspace.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * role id → {@link RoleView} (§9.3). A <strong>separate bean</strong> on purpose:
 * {@code @Cacheable} is applied by a Spring proxy, so a self-invocation from inside
 * {@code WorkspaceAccessService} would silently bypass the cache and turn every
 * authorization into two extra SELECTs. This is the same reason
 * {@code ProjectConfigCache} is separate from {@code ProjectConfigService}.
 *
 * <p><strong>What is cached and what deliberately is not:</strong>
 * <ul>
 *   <li><em>Cached:</em> a role's permission set. It changes only when an admin edits the
 *       role, and built-in roles never change at all — so this is the whole point: the
 *       seven built-ins are resolved once per process and the hot path never touches
 *       {@code roles} or {@code role_permissions} again.</li>
 *   <li><em>Never cached:</em> <strong>membership</strong>. A role reassignment must bite
 *       on the member's very next request, and membership rows are hot in PostgreSQL's
 *       buffer cache anyway.</li>
 *   <li><em>Never in the JWT.</em> Permissions in a token would go stale and the token is
 *       not revocable; a demotion has to take effect immediately. Non-negotiable (§9.3).</li>
 * </ul>
 *
 * <p>Entries also expire on the shared 60s TTL from {@code CacheConfig}, which is the
 * safety net behind {@link #evict}: a missed eviction costs at most a minute of stale
 * permissions, never an indefinitely stale role.
 *
 * <p>Within one request a set is resolved once and passed down the call stack, so a role
 * change committed mid-request is not observed by that request — snapshot semantics,
 * documented and accepted (§9.3).
 */
@Service
@RequiredArgsConstructor
public class RolePermissionCache {

    private final RoleRepository roleRepository;

    /**
     * @throws IllegalStateException if the id resolves to nothing. That is never a user
     *     input error — ids reach here only from a built-in constant or from a
     *     {@code role_id} the database's own foreign key guarantees — so it means the
     *     seed data or a migration is broken, and failing loudly beats resolving an empty
     *     permission set that would look like a legitimate Viewer.
     */
    @Cacheable(cacheNames = "roleView", key = "#roleId")
    @Transactional(readOnly = true)
    public RoleView byId(UUID roleId) {
        return roleRepository.findByIdWithPermissions(roleId)
                .map(RoleView::of)
                .orElseThrow(() -> new IllegalStateException(
                        "No role with id " + roleId + " — a role_id points at a row that does not "
                        + "exist. Check that V13__roles.sql ran and seeded the built-in templates."));
    }

    /** Called by the S4 role editor after an edit or delete. */
    @CacheEvict(cacheNames = "roleView", key = "#roleId")
    public void evict(UUID roleId) {
        // Body intentionally empty: the annotation is the behaviour.
    }
}
