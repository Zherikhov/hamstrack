package com.hamstrack.admin;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AdminAccessTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void regularUserGets403OnAdminApi() throws Exception {
        var token = loginAs(SystemRole.USER);
        mockMvc.perform(get("/api/admin/statuses").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousGets401OnAdminApi() throws Exception {
        mockMvc.perform(get("/api/admin/statuses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanCrudCatalogAndSeesSeededDefaults() throws Exception {
        var token = loginAs(SystemRole.ADMIN);

        // V6 seeds the global catalog
        mockMvc.perform(get("/api/admin/statuses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'To Do')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'Done')]").exists());
        mockMvc.perform(get("/api/admin/priorities").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Urgent')]").exists());
        mockMvc.perform(get("/api/admin/workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.systemDefault == true)]").exists());

        // create → update → delete a status (unused: no remap needed)
        var name = "T-" + System.nanoTime() % 1_000_000;
        var created = mockMvc.perform(post("/api/admin/statuses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"category\":\"IN_PROGRESS\",\"color\":\"#123456\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var id = created.replaceAll(".*\"id\":\"([a-f0-9-]+)\".*", "$1");

        mockMvc.perform(patch("/api/admin/statuses/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "x\",\"category\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("DONE"));

        mockMvc.perform(delete("/api/admin/statuses/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    private String loginAs(SystemRole role) throws Exception {
        // Lowercase — login lowercases the submitted email before lookup
        var email = ("adm-" + role + "-" + System.nanoTime() + "@example.com").toLowerCase();
        var user = new User();
        user.setEmail(email);
        user.setDisplayName("Admin Test");
        user.setPasswordHash(passwordEncoder.encode("test-password-1"));
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole(role);
        userRepository.save(user);

        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
