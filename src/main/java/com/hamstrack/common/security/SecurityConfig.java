package com.hamstrack.common.security;

import com.hamstrack.auth.repository.UserRepository;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final JwtAuthenticationEntryPoint entryPoint;

    /**
     * Dedicated chain for the Actuator management port (:9090, {@code management.server.port}).
     * Actuator endpoints are only ever served on the management port, so matching
     * {@link EndpointRequest#toAnyEndpoint()} scopes this chain to that port's traffic.
     * It runs at higher priority than the main :8080 chain so actuator requests never
     * fall through to the app's auth rules. permitAll is safe: :9090 is never published
     * or proxied by Caddy — only in-network Prometheus reaches it (see observability spec).
     */
    @Bean
    @Order(0)
    public SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .anyRequest().permitAll()
            );
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // ASYNC: SSE emitter completion/timeout re-dispatches through the filter
                // chain without a security context; the original request was already
                // authorized, and the committed stream can't take a 401 anyway
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .requestMatchers(
                    "/api/auth/register", "/api/auth/login",
                    "/api/auth/refresh", "/api/auth/logout",
                    "/api/auth/verify-email", "/api/auth/resend-verification",
                    "/api/auth/forgot-password", "/api/auth/reset-password",
                    "/api/meta"
                ).permitAll()
                // System administration (global taxonomy) — system ADMIN only
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                // SPA routes and static assets — auth enforced client-side by React
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService, userRepository);
    }

    // Prevents Spring Boot from auto-registering the filter as a generic servlet filter
    // (which would cause it to run twice and wipe authentication — see CLAUDE.md gotchas)
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> disableAutoRegistration(
            JwtAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
