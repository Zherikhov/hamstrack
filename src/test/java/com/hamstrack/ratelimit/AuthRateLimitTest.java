package com.hamstrack.ratelimit;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.auth-ip-requests-per-minute=5",
        "app.rate-limit.login-failure-threshold=3",
        "app.rate-limit.login-backoff-base-seconds=60",
        // don't let the admin seeder / demo seeding interfere
        "seed.admin.email=",
        "app.demo.seed-on-first-login=false"
})
@AutoConfigureMockMvc
class AuthRateLimitTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private String email;

    @BeforeEach
    void createUser() {
        // Unique per test — rate-limit state is in-memory and shared across tests
        email = "rl-" + System.nanoTime() + "@example.com";
        var user = new User();
        user.setEmail(email);
        user.setDisplayName("Rate Limit");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);
    }

    @Test
    void loginBackoffKicksInAfterConsecutiveFailures() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(email, "wrong-password", "10.1.0." + i).andExpect(status().isUnauthorized());
        }
        // 4th attempt is blocked by per-account backoff even with the right password
        login(email, "correct-password", "10.1.0.99")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void successfulLoginResetsFailureCounter() throws Exception {
        for (int i = 0; i < 2; i++) {
            login(email, "wrong-password", "10.2.0." + i).andExpect(status().isUnauthorized());
        }
        login(email, "correct-password", "10.2.0.50").andExpect(status().isOk());
        // Counter was reset — two more failures stay under the threshold of 3
        for (int i = 0; i < 2; i++) {
            login(email, "wrong-password", "10.2.1." + i).andExpect(status().isUnauthorized());
        }
        login(email, "correct-password", "10.2.0.51").andExpect(status().isOk());
    }

    @Test
    void perIpBudgetReturns429WithProblemBody() throws Exception {
        var ip = "10.3.0.7";
        // Budget is 5/min; each request targets a different unknown email so
        // the per-account backoff never triggers — only the IP window can
        for (int i = 0; i < 5; i++) {
            login("nobody-" + i + "@example.com", "x-wrong-pass", ip).andExpect(status().isUnauthorized());
        }
        login("nobody-final@example.com", "x-wrong-pass", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content -> {
                    var body = content.getResponse().getContentAsString();
                    if (!body.contains("\"status\":429")) {
                        throw new AssertionError("Expected problem+json body with status 429, got: " + body);
                    }
                });
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password, String ip)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }
}
