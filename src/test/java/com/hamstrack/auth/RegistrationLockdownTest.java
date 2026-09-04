package com.hamstrack.auth;

import com.hamstrack.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-84 (audit Q-2) — the DC self-registration lockdown. With
 * {@code app.registration.public-signup-enabled=false} (the DC default), public
 * self-registration is fully closed: {@code POST /api/auth/register} must be rejected
 * with 403 ({@code RegistrationDisabledException}) and create NO user — accounts are
 * provisioned only by the system admin. In its own {@code @SpringBootTest} context so
 * the signup flag differs from {@code AuthFlowsTest} (which runs signup ON).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.registration.public-signup-enabled=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class RegistrationLockdownTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;

    @MockitoBean JavaMailSender mailSender;

    @Test
    void registerIsForbiddenWhenPublicSignupDisabled() throws Exception {
        var email = ("u-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                + "@example.com").toLowerCase();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\","
                                + "\"displayName\":\"Blocked User\",\"termsAccepted\":true}"))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findByEmail(email)).as("a blocked registration must not create a user").isEmpty();
    }
}
