package com.hamstrack.common.security;

import com.hamstrack.common.config.AppProperties;
import com.hamstrack.common.config.CspProperties;
import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.observability.ProductMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * <strong>Everything the CSP report sink is made of, behind one property</strong> (HD-264).
 *
 * <p>{@code app.csp.sink-enabled} is the single gate: it decides whether the endpoint exists,
 * whether its guard filter is registered, and — through {@link ContentSecurityPolicy} — whether the
 * header names a {@code report-uri} at all. One condition rather than three is what makes the
 * proposal's §8.2 failure ("a {@code report-uri} that 404s") unreachable by configuration rather
 * than merely unlikely — and the one configuration that could still have reached it, a
 * {@code CSP_POLICY} override naming this path with the gate shut, fails the boot in
 * {@link ContentSecurityPolicy} rather than shipping.
 *
 * <p><strong>It is a property and not a profile.</strong> The default lives in
 * {@code application-dc.properties} (false) and {@code application-cloud.properties} (true), in the
 * shape {@code app.storage.type} already uses; no code in this feature asks which mode it is
 * running in, and a DC operator who wants collection sets one variable. ADR-0006.
 */
@Configuration
@ConditionalOnProperty(name = "app.csp.sink-enabled", havingValue = "true")
public class CspReportSinkConfig {

    @Bean
    public CspReportBudget cspReportBudget(CspProperties properties) {
        return new CspReportBudget(properties);
    }

    /**
     * The sink takes the <em>budget</em> as well as the guard filter does, and that is the whole
     * shape of this feature's volume control: the filter spends one token to admit a request, the
     * sink spends one more for every line past the first that request writes. A budget counted in
     * requests bounds requests; what this feature has to bound is lines.
     */
    @Bean
    public CspReportSink cspReportSink(JsonMapper jsonMapper, ProductMetrics metrics,
                                       AppProperties appProperties, CspReportBudget budget) {
        return new CspReportSink(jsonMapper, metrics, appProperties, budget);
    }

    /**
     * Bound to the sink's one URL, and outermost: a request over the bound or over the budget is
     * refused before it costs a parse, an observation or anything else.
     *
     * <p>{@code HIGHEST_PRECEDENCE} is the same order {@code AuthRateLimitFilter} carries, and the
     * two never contend — they are registered on disjoint URLs, and the equality is therefore not
     * a tie that anything resolves. Constructed inside the registration and never exposed as a
     * {@code Filter} bean, so Boot does not auto-register it for every request in the application
     * as well (CLAUDE.md).
     *
     * <p>It reads {@code app.rate-limit.trust-forwarded-for} — the same setting the auth budget
     * keys on — because the two answer the same question about the same deployment. That coupling
     * is documented for the operator in {@code docs/self-hosting.md}: turning the sink on behind a
     * proxy without turning that on makes every report arrive from one address, which degrades the
     * per-IP bound to the instance bound.
     */
    @Bean
    public FilterRegistrationBean<CspReportGuardFilter> cspReportGuardFilter(
            CspReportBudget budget, ProductMetrics metrics, RateLimitProperties rateLimit) {
        var registration = new FilterRegistrationBean<>(
                new CspReportGuardFilter(budget, metrics, rateLimit.trustForwardedFor()));
        registration.addUrlPatterns(ContentSecurityPolicy.REPORT_PATH);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
