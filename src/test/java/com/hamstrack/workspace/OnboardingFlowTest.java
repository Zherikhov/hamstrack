package com.hamstrack.workspace;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With onboarding enabled (Cloud), the demo workspace is provisioned only when
 * the user picks "Create a team" — NOT at first authentication — so a user who
 * joins an existing team gets no demo. Guards that split.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.onboarding.enabled=true",
        "app.demo.seed-on-first-login=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class OnboardingFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired WorkspaceService workspaceService;

    @Test
    void authDoesNotSeedDemoWhenOnboardingEnabled_createTeamDoes() throws Exception {
        var email = ("onb-" + System.nanoTime() + "@example.com").toLowerCase();
        var user = new User();
        user.setEmail(email);
        user.setDisplayName("Onboarding Test");
        user.setPasswordHash(passwordEncoder.encode("test-password-1"));
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        var token = login(email);

        // Logging in must NOT seed a demo while onboarding is enabled
        assertThat(workspaceService.listForUser(user))
                .noneMatch(w -> w.name().equals("Demo Workspace"));

        // "Create a team" seeds the demo starter and completes onboarding
        mockMvc.perform(post("/api/onboarding/create-team").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(workspaceService.listForUser(user))
                .anyMatch(w -> w.name().equals("Demo Workspace"));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getOnboardedAt()).isNotNull();
    }

    private String login(String email) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
