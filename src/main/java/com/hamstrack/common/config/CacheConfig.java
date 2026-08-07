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
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CaffeineCacheManager cacheManager() {
        var manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(10_000));
        return manager;
    }
}
