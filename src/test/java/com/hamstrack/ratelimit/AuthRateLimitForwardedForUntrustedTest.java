package com.hamstrack.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-75 regression (default, DC-safe): {@code app.rate-limit.trust-forwarded-for}
 * is false, so X-Forwarded-For is ignored for per-IP keying. A single socket
 * that rotates a spoofed XFF header still trips the per-IP budget — a client on
 * a directly-reachable app port cannot mint a fresh budget per fake header value.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=5",
        "app.rate-limit.login-failure-threshold=100",
        // trust-forwarded-for left at its default (false)
        "seed.admin.email=",
        "app.demo.seed-on-first-login=false"
})
@AutoConfigureMockMvc
class AuthRateLimitForwardedForUntrustedTest {

    @Autowired MockMvc mockMvc;

    @Test
    void spoofedRotatingXffFromOneSocketStillTripsPerIpLimit() throws Exception {
        // One real socket. Budget is 5/min. Each request carries a DIFFERENT
        // (spoofed) X-Forwarded-For — if XFF were trusted, each would key a
        // fresh budget and never 429. Since it's untrusted, the socket addr
        // keys them all together and the 6th trips.
        var socket = "203.0.113.9";
        for (int i = 0; i < 5; i++) {
            loginSpoofed(socket, "9.9.9." + i).andExpect(status().isUnauthorized());
        }
        loginSpoofed(socket, "9.9.9.250")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    private ResultActions loginSpoofed(String socket, String spoofedXff) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(r -> { r.setRemoteAddr(socket); return r; })
                .header("X-Forwarded-For", spoofedXff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody-" + System.nanoTime()
                        + "@example.com\",\"password\":\"x-wrong-pass\"}"));
    }
}
