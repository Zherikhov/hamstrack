package com.hamstrack.workspace.service;

import com.hamstrack.common.config.CacheConfig;
import com.hamstrack.workspace.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * role id &rarr; {@link RoleView} (§9.3). A <strong>separate bean</strong> on purpose:
 * {@code @Cacheable} is applied by a Spring proxy, so a self-invocation from inside
 * {@code WorkspaceAccessService} would silently bypass the cache and turn every
 * authorization into two extra SELECTs. This is the same reason
 * {@code ProjectConfigCache} is separate from {@code ProjectConfigService}. Do not
 * "simplify" it back into its caller.
 *
 * <p><strong>What is cached and what deliberately is not:</strong>
 * <ul>
 *   <li><em>Cached:</em> a role's permission set. It changes only when an admin edits the
 *       role, and built-in roles never change at all — so this is the whole point: the
 *       eight built-ins are resolved once per process and the hot path never touches
 *       {@code roles} or {@code role_permissions} again.</li>
 *   <li><em>Never cached:</em> <strong>membership</strong>. A role reassignment must bite
 *       on the member's very next request, and membership rows are hot in PostgreSQL's
 *       buffer cache anyway.</li>
 *   <li><em>Never in the JWT.</em> Permissions in a token would go stale and the token is
 *       not revocable; a demotion has to take effect immediately. Non-negotiable (§9.3).</li>
 * </ul>
 *
 * <p>Within one request a set is resolved once and passed down the call stack, so a role
 * change committed mid-request is not observed by that request — snapshot semantics,
 * documented and accepted (§9.3).
 *
 * <h2>The revocation window (HD-127, S4)</h2>
 *
 * <p><strong>A permission revocation is honoured for up to 10 seconds on other
 * instances.</strong> The cache is per-process, so {@link #evict} only corrects the node
 * that served the edit; every other node serves the pre-edit {@code PermissionSet} until
 * its own entry expires. Worst case is therefore {@code TTL} plus the duration of a request
 * that had already resolved its context (snapshot semantics, §9.3). Widenings have the same
 * delay and nobody minds. <strong>This is a security property, not a config-freshness one:
 * do not raise the TTL, and do not fold {@code roleView} back into the shared 60s
 * spec</strong> ({@link CacheConfig#ROLE_VIEW_TTL}).
 *
 * <p>Membership is <em>not</em> cached, so a role <strong>re</strong>assignment — moving a
 * person to a different role, and the whole of {@code DELETE /roles/{id}?reassignToRoleId=}
 * — bites on that person's very next request on every node. Only a change to a role's
 * <em>contents</em> has a window.
 *
 * <p><strong>The documented upgrade path is PostgreSQL {@code LISTEN}/{@code NOTIFY}</strong>,
 * recorded here so nobody has to re-derive it. It is available in both deployment modes and
 * degrades to a no-op on a single-instance DC rather than forking the code, so it would not
 * violate the DC/Cloud rule; the honest reason it is deferred is cost — a dedicated
 * long-lived connection per instance outside the Hikari pool, reconnect/backfill handling,
 * and a story for a missed notification, all to close a ten-second window on a mutation a
 * workspace performs a handful of times a year.
 */
@Service
@RequiredArgsConstructor
public class RolePermissionCache {

    private final RoleRepository roleRepository;

    /**
     * <strong>{@code sync = true} is not optional at this TTL.</strong> Spring's non-sync
     * {@code @Cacheable} does get-miss &rarr; invoke &rarr; put, so every concurrent request
     * that misses the same key issues its own query. At 60s that burst was rare; at 10s it
     * happens six times as often on the hottest key in the product (the built-in Member
     * role, held by nearly every user of every workspace). {@code sync = true} routes
     * through {@code CaffeineCache.get(key, loader)}, which is atomic per key, so a key's
     * expiry costs one query regardless of concurrency.
     *
     * @throws IllegalStateException if the id resolves to nothing. That is never a user
     *     input error — ids reach here only from a built-in constant or from a
     *     {@code role_id} the database's own foreign key guarantees — so it means the
     *     seed data or a migration is broken, and failing loudly beats resolving an empty
     *     permission set that would look like a legitimate Viewer. Callers that degrade a
     *     wrong-scope row ({@code WorkspaceAccessService.resolveRoleOrDegrade}) catch
     *     {@code WorkspaceNotFoundException} <em>only</em>, never this, precisely so a
     *     dangling FK stays loud instead of turning into a silent empty set.
     */
    @Cacheable(cacheNames = CacheConfig.ROLE_VIEW_CACHE, key = "#roleId", sync = true)
    @Transactional(readOnly = true)
    public RoleView byId(UUID roleId) {
        return roleRepository.findByIdWithPermissions(roleId)
                .map(RoleView::of)
                .orElseThrow(() -> new IllegalStateException(
                        "No role with id " + roleId + " — a role_id points at a row that does not "
                        + "exist. Check that V13__roles.sql ran and seeded the built-in templates."));
    }

    /**
     * Drop one role's cached snapshot.
     *
     * <p><strong>Call this AFTER COMMIT, never inside the editing transaction.</strong> An
     * eviction that fires mid-transaction is worse than none: a concurrent reader
     * immediately reloads the <em>pre-commit</em> row and re-caches it, and the edit then
     * goes unseen for a full TTL on the very node that made it. {@code RoleService}
     * therefore publishes {@link com.hamstrack.workspace.event.RolePermissionsChanged} and
     * {@code RoleCacheEvictionListener} calls this from a
     * {@code @TransactionalEventListener(AFTER_COMMIT)} — the pattern {@code SseEventListener}
     * already uses for {@code WorkspaceMemberRemoved}.
     */
    @CacheEvict(cacheNames = CacheConfig.ROLE_VIEW_CACHE, key = "#roleId")
    public void evict(UUID roleId) {
        // Body intentionally empty: the annotation is the behaviour.
    }
}
