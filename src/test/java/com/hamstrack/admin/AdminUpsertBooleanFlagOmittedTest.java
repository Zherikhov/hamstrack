package com.hamstrack.admin;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HD-73 regression: the boolean flags on admin set-upsert items
 * ({@code required}/{@code showOnCreate} on field-set items, {@code isDefault}
 * on priority-set items) are boxed {@code Boolean} so a JSON body that OMITS
 * them binds (defaulting to false) instead of 400-ing under Boot 4 / Jackson 3
 * ({@code FAIL_ON_NULL_FOR_PRIMITIVES}). A primitive there would have failed
 * request deserialization with 400 "Failed to read request".
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AdminUpsertBooleanFlagOmittedTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void fieldSetItemOmittingBooleanFlagsBindsWithFalse() throws Exception {
        var token = adminToken();

        // Create a field to reference in the set.
        var fieldBody = mockMvc.perform(post("/api/admin/fields")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"HD73-F-" + System.nanoTime() + "\",\"type\":\"TEXT\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String fieldId = JsonPath.read(fieldBody, "$.id");

        // Item OMITS both `required` and `showOnCreate`. With a primitive boolean
        // this 400s ("Failed to read request"); with a boxed Boolean it binds false.
        var setName = "HD73-FSET-" + System.nanoTime();
        mockMvc.perform(post("/api/admin/field-sets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + setName + "\",\"items\":[{\"fieldId\":\"" + fieldId + "\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].field.id").value(fieldId))
                .andExpect(jsonPath("$.items[0].required").value(false))
                .andExpect(jsonPath("$.items[0].showOnCreate").value(false));
    }

    @Test
    void prioritySetItemOmittingIsDefaultBindsWithFalse() throws Exception {
        var token = adminToken();

        // Grab two seeded global priority ids (V6 seeds the global catalog).
        var prioritiesBody = mockMvc.perform(get("/api/admin/priorities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> priorityIds = JsonPath.read(prioritiesBody, "$[*].id");
        String p0 = priorityIds.get(0);
        String p1 = priorityIds.get(1);

        // Both items OMIT `isDefault`. A primitive boolean would 400 here.
        // A priority set needs exactly one default; supply it via a top-level
        // marker on the first item only — but the point is the SECOND item
        // omits the flag entirely and must still bind (false).
        var setName = "HD73-PSET-" + System.nanoTime();
        mockMvc.perform(post("/api/admin/priority-sets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + setName + "\",\"items\":["
                                + "{\"priorityId\":\"" + p0 + "\",\"isDefault\":true},"
                                + "{\"priorityId\":\"" + p1 + "\"}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(setName));
    }

    @Test
    void prioritySetSingleItemOmittingIsDefaultDoesNotFailDeserialization() throws Exception {
        var token = adminToken();
        var prioritiesBody = mockMvc.perform(get("/api/admin/priorities")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> priorityIds = JsonPath.read(prioritiesBody, "$[*].id");
        String p0 = priorityIds.get(0);

        // A single item that omits isDefault must NOT 400 on deserialization.
        // (Whatever the service does with a set having no explicit default is a
        // business concern; the invariant under test is: omitted flag != 400.)
        var setName = "HD73-PSET1-" + System.nanoTime();
        var result = mockMvc.perform(post("/api/admin/priority-sets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + setName + "\",\"items\":["
                                + "{\"priorityId\":\"" + p0 + "\"}]}"))
                .andReturn();
        int sc = result.getResponse().getStatus();
        // The load-bearing assertion: not a request-read 400.
        if (sc == 400) {
            throw new AssertionError("Omitted isDefault should not 400; body: "
                    + result.getResponse().getContentAsString());
        }
    }

    private String adminToken() throws Exception {
        var u = new User();
        u.setEmail(("hd73-adm-" + System.nanoTime() + "@example.com").toLowerCase());
        u.setDisplayName("Admin");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.ADMIN);
        userRepository.save(u);
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
