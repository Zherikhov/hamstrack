package com.hamstrack.admin;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HD-71: {@code field_defs.is_system} must be enforced — a system field can be
 * archived but never permanently deleted, and the flag must be surfaced in
 * {@link com.hamstrack.admin.dto.AdminFieldResponse}. Exercised on the global
 * admin console; the guard lives in the shared {@code AdminFieldService.deleteField}
 * so it covers the workspace/project consoles too.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AdminSystemFieldDeleteTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void deletingASystemFieldIsRejectedAndItSurvives() throws Exception {
        var admin = adminToken();
        var field = systemField();

        mockMvc.perform(delete("/api/admin/fields/" + field.getId())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("System fields can only be archived, not deleted."));

        // still present
        org.junit.jupiter.api.Assertions.assertTrue(fieldDefRepository.findById(field.getId()).isPresent());
    }

    @Test
    void deletingANonSystemFieldStillWorks() throws Exception {
        var admin = adminToken();
        var field = nonSystemField();

        mockMvc.perform(delete("/api/admin/fields/" + field.getId())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertTrue(fieldDefRepository.findById(field.getId()).isEmpty());
    }

    @Test
    void archivingASystemFieldIsStillAllowed() throws Exception {
        var admin = adminToken();
        var field = systemField();

        mockMvc.perform(post("/api/admin/fields/" + field.getId() + "/archive")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertNotNull(
                fieldDefRepository.findById(field.getId()).orElseThrow().getArchivedAt());
    }

    @Test
    void isSystemFlagIsSurfacedInTheFieldListing() throws Exception {
        var admin = adminToken();
        var sysField = systemField();
        var plainField = nonSystemField();

        mockMvc.perform(get("/api/admin/fields").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + sysField.getId() + "')].isSystem").value(true))
                .andExpect(jsonPath("$[?(@.id == '" + plainField.getId() + "')].isSystem").value(false));
    }

    // ---------- fixtures ----------

    private FieldDef systemField() {
        var f = newField();
        f.setSystem(true);
        return fieldDefRepository.save(f);
    }

    private FieldDef nonSystemField() {
        return fieldDefRepository.save(newField());
    }

    private FieldDef newField() {
        var f = new FieldDef();
        f.setKey("sf_" + Math.abs(UUID.randomUUID().hashCode()));
        f.setName("SF-" + System.nanoTime());
        f.setType(FieldType.TEXT);
        return f;
    }

    private String adminToken() throws Exception {
        var u = new User();
        u.setEmail(("adm-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Admin");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.ADMIN);
        return login(userRepository.save(u));
    }

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
