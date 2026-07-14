package com.hamstrack.common.ratelimit;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilter(RateLimitService rateLimitService) {
        var registration = new FilterRegistrationBean<>(
                new AuthRateLimitFilter(rateLimitService));
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
