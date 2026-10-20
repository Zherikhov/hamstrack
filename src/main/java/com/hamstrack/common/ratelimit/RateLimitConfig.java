package com.hamstrack.common.ratelimit;

import com.hamstrack.common.config.RateLimitProperties;
import com.hamstrack.common.exception.ProblemJson;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilter(
            RateLimitService rateLimitService, RateLimitProperties properties,
            ProblemJson problemJson) {
        var registration = new FilterRegistrationBean<>(
                new AuthRateLimitFilter(rateLimitService, properties.trustForwardedFor(), problemJson));
        // Endpoints where unlimited attempts enable brute force or mail spam;
        // /refresh and /logout are cookie-driven and excluded on purpose
        registration.addUrlPatterns(
                "/api/auth/login",
                "/api/auth/register",
                "/api/auth/verify-email",
                "/api/auth/resend-verification",
                "/api/auth/forgot-password",
                "/api/auth/reset-password");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
