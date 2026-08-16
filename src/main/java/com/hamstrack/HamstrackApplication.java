package com.hamstrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
// In-memory throttle-state eviction: RateLimitService.evictStaleEntries (auth counters)
// and IssueRankService.evictStaleEntries (per-project rank-rebalance cooldowns). Both
// maps grow with caller-supplied keys, so removing this annotation turns them into leaks.
@EnableScheduling
@EnableJpaAuditing
public class HamstrackApplication {

    public static void main(String[] args) {
        SpringApplication.run(HamstrackApplication.class, args);
    }
}
