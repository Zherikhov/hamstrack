package com.hamstrack.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * In-process caching for the global, rarely-changing taxonomy resolved by
 * {@code ProjectConfigService} (the statuses/transitions/priorities/types of a
 * project's effective workflow and sets). This trims the handful of small
 * catalog queries that every issue read/write and the public config endpoint
 * would otherwise run — fewer DB round-trips per request at scale.
 *
 * <p>Single-node (Caffeine), per-instance — like the rate limiter; a
 * multi-instance Cloud deployment would each keep its own copy. Entries expire
 * 60s after write, which doubles as the invalidation strategy: an admin catalog
 * edit propagates within at most 60s. There is deliberately NO explicit eviction
 * wiring, so there is no missed-eviction bug that could serve stale config
 * indefinitely — the bounded TTL is the safety net, and the catalog changes
 * rarely and only through the admin console.
 *
 * <p><strong>{@code roleView} is the one exception, and it is registered separately on
 * purpose</strong> (HD-127, S4). Everything above is <em>config freshness</em>; that cache
 * holds {@link com.hamstrack.common.security.PermissionSet}s, so its TTL is how long a
 * <em>revoked permission</em> keeps working on an instance that did not serve the edit. It
 * gets its own {@link #ROLE_VIEW_TTL} through {@code registerCustomCache}, which keeps its
 * own Caffeine instance instead of being rebuilt from the default spec below, and
 * {@code RolePermissionCache.byId} additionally evicts after commit so the editing node
 * corrects immediately. Do <strong>not</strong> fold it back into the shared spec, and do
 * not raise its TTL — see that class's javadoc for the residual and for the documented
 * {@code LISTEN}/{@code NOTIFY} upgrade path.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** The one cache in the product whose TTL is load-bearing for a security property. */
    public static final String ROLE_VIEW_CACHE = "roleView";

    /**
     * The aging report's lifetime cycle-time percentiles, per project (HD-138 R3 round 2).
     *
     * <p><strong>On the shared 60 s spec above, deliberately</strong> — the opposite decision from
     * {@link #ROLE_VIEW_CACHE}, for the opposite reason. That one is an authorization answer, so
     * its staleness window is a security property and it needs its own shorter, unreachable TTL.
     * This one is an <em>availability</em> measure over a read every member is already entitled
     * to: it fronts the only O(project-history) statement in the reports feature, which has no
     * window and no {@code LIMIT} and grows forever. Sixty seconds is also exactly the
     * {@code Cache-Control: private, max-age=60} that {@code ReportController} already puts on
     * the response, so the server-side snapshot is no staler than what the client was already
     * told it may show. Nothing is evicted; the TTL is the whole invalidation strategy — see
     * {@code LifetimeCycleTimeCache} for why there is no sensible event to evict on.
     *
     * <p>Not registered via {@code registerCustomCache}: {@link CaffeineCacheManager} creates a
     * cache on first use from {@link #cacheManager()}'s default spec, and that spec <em>is</em>
     * the intent here. It is named here rather than as a string literal in the report package so
     * that this class stays the one place listing what this process holds in memory.
     */
    public static final String REPORT_LIFETIME_CYCLE_TIME_CACHE = "reportLifetimeCycleTime";

    /**
     * How long an <strong>authorization</strong> answer may be stale — six times shorter
     * than the taxonomy caches above, because it is a security property rather than a
     * config-freshness one.
     *
     * <p>The cache is keyed by role id, so its population is "distinct roles in play"
     * (8 built-ins plus a workspace's custom roles), not requests; under
     * {@code expireAfterWrite} with no {@code refreshAfterWrite} the steady-state cost is
     * {@code active distinct role ids / TTL} primary-key lookups per second, independent of
     * request rate. An instance holding ~1,000 live keys pays a ceiling of ~100 trivial PK
     * lookups per second at 10s against ~17 at 60s — noise, for a 6x better window. Below
     * ~5s it would start trading real database work for a window that request latency and
     * clock skew already blur.
     *
     * <p><strong>Not a property — and here is why that actually holds.</strong> The claim is
     * load-bearing (an operator who could lengthen this could lengthen how long a revoked
     * permission keeps working), and it is currently true only by an implicit back-off that a
     * refactor could silently undo, so it is written down rather than assumed:
     * <ul>
     *   <li>this class declares its own {@link CaffeineCacheManager} bean, so Boot's
     *       {@code CacheAutoConfiguration} backs off entirely via
     *       {@code @ConditionalOnMissingBean(CacheManager.class)} — which makes
     *       {@code spring.cache.type} and {@code spring.cache.caffeine.spec}
     *       <strong>inert</strong>; nothing reads them;</li>
     *   <li>and {@code registerCustomCache} hands the manager a <em>built</em> Caffeine
     *       instance, which it stores as-is rather than rebuilding from
     *       {@code setCaffeine(...)} — so even the 60 s default above cannot reach this
     *       cache.</li>
     * </ul>
     * <strong>If this bean is ever deleted or made conditional</strong> and Boot's
     * auto-configured manager takes over, both properties become live, every cache collapses
     * onto one spec, and {@code roleView} silently inherits it. That would be a security
     * regression with no compile error and no failing test — so do not do it without moving
     * this TTL somewhere equally unreachable from configuration.
     */
    static final Duration ROLE_VIEW_TTL = Duration.ofSeconds(10);

    @Bean
    public CaffeineCacheManager cacheManager() {
        var manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(10_000));
        manager.registerCustomCache(ROLE_VIEW_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(ROLE_VIEW_TTL)
                .maximumSize(10_000)
                .build());
        return manager;
    }
}
