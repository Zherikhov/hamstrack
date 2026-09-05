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
     * The report-only Content-Security-Policy, resolved once at startup (HD-264, ADR-0035). It is
     * installed on the main chain only — see {@link #managementFilterChain} for why :9090 gets
     * none.
     */
    private final ContentSecurityPolicy contentSecurityPolicy;

    /**
     * Dedicated chain for the Actuator management port (:9090, {@code management.server.port}).
     * Actuator endpoints are only ever served on the management port, so matching
     * {@link EndpointRequest#toAnyEndpoint()} scopes this chain to that port's traffic.
     * It runs at higher priority than the main :8080 chain so actuator requests never
     * fall through to the app's auth rules. permitAll is safe: :9090 is never published
     * or proxied by Caddy — only in-network Prometheus reaches it (see observability spec).
     *
     * <p><strong>No Content-Security-Policy here, deliberately</strong> (HD-264): the port is never
     * published or proxied, and nothing served on it is a document that could execute script. A
     * policy is a statement about the JavaScript bundle, and no bundle is reachable from here.
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
            // The report-only CSP, on EVERY response of this chain — documents, assets, 200s,
            // 401s and 404s alike. Report-only cannot block anything; what it buys is the report.
            // ContentSecurityPolicy owns the directive set and the evidence under each directive.
            .headers(this::writeContentSecurityPolicy)
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
                // The CSP report sink. Unauthenticated BY NECESSITY: a browser sends a violation
                // report with no credentials, so requiring any is requiring a report nobody can
                // send. There is no read path behind it and it returns nothing tenant-derived,
                // which is what makes that safe — see CspReportController.
                //
                // SCOPED TO POST, unlike every entry above. This path is not an endpoint that
                // happens to allow anonymous callers; it is a one-way drop for one method, and a
                // permitAll wider than the method it exists for would take a path under /api/**
                // out of the authenticated set for GET as well — where the SPA fallback answers
                // any dotless path with index.html. Nothing is disclosed by that today; the point
                // is that the exemption should not be wider than its reason.
                //
                // Listed unconditionally even though the handler exists only when
                // app.csp.sink-enabled is true, so that a DC box answers "this instance serves no
                // such thing" rather than "there is something here you may not have".
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        ContentSecurityPolicy.REPORT_PATH).permitAll()
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

    /**
     * Installs the report-only policy, or leaves the header block exactly as it is when
     * {@code CSP_REPORT_ONLY_ENABLED=false}.
     *
     * <p>The "off" branch adds <em>nothing</em> rather than adding an empty policy: a
     * {@code Content-Security-Policy-Report-Only} with an empty value is a header a proxy or a
     * scanner still sees, and "the operator turned it off" must be indistinguishable from "this
     * release does not have it".
     */
    private void writeContentSecurityPolicy(
            org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<HttpSecurity> headers) {
        var policy = contentSecurityPolicy.headerValue();
        if (policy == null) {
            return;
        }
        // reportOnly() is what picks the -Report-Only header name. Dropping that call is how this
        // ticket would accidentally become the enforcement ticket, which is why the policy string
        // AND the header name are both asserted by ContentSecurityPolicyHeaderTest.
        headers.contentSecurityPolicy(csp -> csp.policyDirectives(policy).reportOnly());
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
