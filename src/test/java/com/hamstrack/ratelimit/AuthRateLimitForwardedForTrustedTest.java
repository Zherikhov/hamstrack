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
 * HD-75 companion: behind a trusted proxy ({@code trust-forwarded-for=true})
 * keying uses the rightmost X-Forwarded-For entry (the real peer, since Caddy
 * strips client-supplied XFF). A fixed rightmost value trips the per-IP limit
 * even as the socket varies, and a client-supplied left entry is ignored.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=5",
        "app.rate-limit.login-failure-threshold=100",
        "app.rate-limit.trust-forwarded-for=true",
        "seed.admin.email=",
        "app.demo.seed-on-first-login=false"
})
@AutoConfigureMockMvc
class AuthRateLimitForwardedForTrustedTest {

    @Autowired MockMvc mockMvc;

    @Test
    void fixedRightmostXffTripsLimitEvenAsSocketVaries() throws Exception {
        // Trusted proxy: the rightmost XFF entry is the real peer. Keep it fixed
        // while the socket varies — all share one bucket, 6th trips. The
        // client-supplied left entry must be ignored (rightmost wins).
        var realPeer = "198.51.100.4";
        for (int i = 0; i < 5; i++) {
            loginTrusted("10.0.0." + i, "1.2.3.4, " + realPeer)
                    .andExpect(status().isUnauthorized());
        }
        loginTrusted("10.0.0.250", "5.6.7.8, " + realPeer)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    private ResultActions loginTrusted(String socket, String xff) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(r -> { r.setRemoteAddr(socket); return r; })
                .header("X-Forwarded-For", xff)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody-" + System.nanoTime()
                        + "@example.com\",\"password\":\"x-wrong-pass\"}"));
    }
}
