package com.hamstrack.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HD-127 (S4) acceptance criterion 12 — the {@code roleView} cache keeps its own <strong>10 s
 * spec</strong> while the seven {@code cfg*} taxonomy caches keep 60 s.
 *
 * <p>Why this is worth a test rather than a code comment: {@code roleView} holds
 * {@link com.hamstrack.common.security.PermissionSet}s, so its TTL is not a config-freshness
 * knob but the documented bound on <strong>how long a revoked permission keeps working</strong>
 * on an instance that did not serve the edit. That 10 s number is quoted to operators, and it is
 * produced by a single {@code registerCustomCache} call whose effect is invisible to every other
 * test in the suite: fold {@code roleView} back into the shared 60 s spec and nothing goes red,
 * the authorization window just gets six times longer.
 *
 * <p>The second half of the class pins the reason the TTL is <em>not</em> configurable, which is
 * currently guaranteed by nothing but an implicit auto-configuration back-off. Because
 * {@link CacheConfig} declares its own {@link CacheManager} bean, Boot's
 * {@link CacheAutoConfiguration} is {@code @ConditionalOnMissingBean}'d out entirely, which makes
 * {@code spring.cache.type} and {@code spring.cache.caffeine.spec} inert — an operator cannot
 * lengthen the authorization window through configuration. A refactor that made this bean
 * conditional, or moved it behind a profile, would hand both properties back to the operator with
 * no compile error and no other failing test. That is why the auto-configuration is put on the
 * runner here instead of only the user configuration: this asserts the back-off, not just the
 * beans.
 */
class CacheConfigTest {

    /** Boot's cache auto-configuration is deliberately present — the back-off is under test. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class))
            .withUserConfiguration(CacheConfig.class);

    /** The seven taxonomy caches from {@code ProjectConfigCache}, created on first use. */
    private static final String[] CFG_CACHES = {
            "cfgSystemDefaultWorkflow", "cfgSystemDefaultPrioritySet", "cfgSystemDefaultTypeSet",
            "cfgStatuses", "cfgTransitions", "cfgPriorityItems", "cfgTypes"
    };

    /**
     * The criterion itself. The literals are spelled out rather than read from
     * {@link CacheConfig}'s constants on purpose — a test that compares the constant to itself
     * would stay green through exactly the edit it exists to catch.
     */
    @Test
    void roleViewKeepsItsOwnTenSecondsAndTheTaxonomyCachesKeepSixty() {
        runner.run(context -> {
            var cacheManager = context.getBean(CacheManager.class);

            var roleView = (CaffeineCache) cacheManager.getCache(CacheConfig.ROLE_VIEW_CACHE);
            assertThat(expiryOf(roleView))
                    .as("roleView's TTL is the documented bound on how long a REVOKED permission "
                        + "can still be honoured on a node that did not serve the edit")
                    .isEqualTo(Duration.ofSeconds(10));

            for (var name : CFG_CACHES) {
                // getCache() is what forces the dynamic creation from the default spec.
                var cfg = (CaffeineCache) cacheManager.getCache(name);
                assertThat(expiryOf(cfg))
                        .as("%s is config freshness, not authorization — it keeps the shared 60s",
                                name)
                        .isEqualTo(Duration.ofSeconds(60));
            }
        });
    }

    /**
     * The mechanism: {@code registerCustomCache} stores a <em>built</em> Caffeine instance, so
     * the manager keeps it as-is instead of rebuilding it from {@code setCaffeine(...)}. Asserted
     * as "roleView is strictly shorter than the shared default" so that a refactor which folded
     * it back into the common spec fails here with a message that says what was lost, even if
     * both numbers were changed together.
     */
    @Test
    void roleViewIsRegisteredSeparatelyRatherThanInheritingTheSharedSpec() {
        runner.run(context -> {
            var cacheManager = context.getBean(CacheManager.class);
            var roleView = (CaffeineCache) cacheManager.getCache(CacheConfig.ROLE_VIEW_CACHE);
            var cfg = (CaffeineCache) cacheManager.getCache("cfgStatuses");

            assertThat(expiryOf(roleView))
                    .as("roleView must NOT inherit the shared taxonomy spec — if these two are "
                        + "ever equal, the authorization staleness window has been silently "
                        + "widened to the config-freshness one")
                    .isLessThan(expiryOf(cfg));
        });
    }

    /**
     * <strong>The operator cannot lengthen the authorization window through configuration.</strong>
     * Both cache properties are set to values that would be catastrophic if they were live —
     * a ten-minute spec and a disabled cache type — and neither reaches the manager, because
     * {@link CacheAutoConfiguration} backs off in the presence of our {@link CacheManager} bean.
     * If this test ever fails, the back-off has stopped happening and the 10 s window is now a
     * deployment-time setting.
     */
    @Test
    void springCachePropertiesAreInertBecauseWeOwnTheCacheManager() {
        runner.withPropertyValues(
                        "spring.cache.type=none",
                        "spring.cache.caffeine.spec=expireAfterWrite=600s,maximumSize=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CacheManager.class))
                            .as("our own bean must still be the CacheManager; if Boot's "
                                + "auto-configured one took over, spring.cache.* would be live "
                                + "and roleView would inherit whatever an operator wrote")
                            .isInstanceOf(CaffeineCacheManager.class);

                    var cacheManager = context.getBean(CacheManager.class);
                    assertThat(expiryOf((CaffeineCache)
                            cacheManager.getCache(CacheConfig.ROLE_VIEW_CACHE)))
                            .as("spring.cache.caffeine.spec must not reach roleView")
                            .isEqualTo(Duration.ofSeconds(10));
                    assertThat(expiryOf((CaffeineCache) cacheManager.getCache("cfgStatuses")))
                            .as("nor the dynamically created taxonomy caches")
                            .isEqualTo(Duration.ofSeconds(60));
                });
    }

    private static Duration expiryOf(CaffeineCache cache) {
        assertThat(cache).isNotNull();
        return cache.getNativeCache().policy().expireAfterWrite().orElseThrow().getExpiresAfter();
    }
}
