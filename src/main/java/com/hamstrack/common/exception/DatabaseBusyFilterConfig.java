package com.hamstrack.common.exception;

import com.hamstrack.common.config.DatabaseTimeoutConsistency;
import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.security.ContentSecurityPolicy;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link DatabaseBusyFilter} across every request, in the shape {@code RateLimitConfig}
 * uses: a {@link FilterRegistrationBean} rather than a {@code @Component}, because Spring Boot
 * auto-registers any {@code Filter} bean for every request <em>in addition</em> to wherever it was
 * placed on purpose (CLAUDE.md gotchas).
 *
 * <p><strong>{@code /*} rather than a list of paths</strong>, because the condition is a property of
 * every request that needs a connection rather than of a set of endpoints — the same reason the API
 * reference attaches this status to no path. And <strong>two</strong> notches inside
 * {@link Ordered#HIGHEST_PRECEDENCE}, which is two separate relations and neither of them is
 * decoration: see {@link DatabaseBusyFilter} for the filters it has to stay inside and what each
 * one buys. {@code DatabaseBusyFilterOrderTest} fails if either inverts.
 */
@Configuration
public class DatabaseBusyFilterConfig {

    @Bean
    public FilterRegistrationBean<DatabaseBusyFilter> databaseBusyFilter(
            DatabaseTimeoutConsistency databaseTimeouts, ProductMetrics productMetrics,
            ContentSecurityPolicy contentSecurityPolicy) {
        var registration = new FilterRegistrationBean<>(
                new DatabaseBusyFilter(databaseTimeouts, productMetrics, contentSecurityPolicy));
        registration.addUrlPatterns("/*");
        // +2, not +1: at +1 it TIES with Boot's own ServerHttpObservationFilter and the winner is
        // whichever bean definition was registered first. See DatabaseBusyFilter.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
