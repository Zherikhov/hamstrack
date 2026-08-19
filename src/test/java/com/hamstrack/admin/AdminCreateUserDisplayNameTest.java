package com.hamstrack.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/admin/users} writes the <strong>same</strong> {@code users.display_name}
 * column as public registration, so it needs the same guard.
 *
 * <p>{@code RegisterRequest.displayName} has carried {@link com.hamstrack.common.util.DisplayText#SINGLE_LINE}
 * since round 3; the admin-side create never got it, which left the rule enforced on the
 * door most attackers cannot use and unenforced on the one that mints accounts. The stored
 * value is identical either way — it heads the sentences the server writes about a privilege
 * ("<em>{name} … is now Team lead in:</em>"), notification subjects, the audit trail and CSV
 * exports — so a bidi override or a zero-width space smuggled in here is exactly the string
 * the registration pattern exists to keep out of them.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AdminCreateUserDisplayNameTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anAdminCreatedDisplayNameRejectsControlAndInvisibleCharacters() throws Exception {
        var token = loginAsAdmin();

        // The ASCII case, which is what a pasted multi-line name looks like.
        create(token, "ops+nl-" + System.nanoTime() + "@example.com", "Ada\nLovelace")
                .andExpect(status().isBadRequest());

        // …and the Unicode ones that walk through a bare [^\p{Cntrl}]* — spelled as code
        // points because every one of them is invisible in a diff.
        var i = 0;
        for (var sneaky : new int[]{0x85, 0x2028, 0x2029, 0x202E, 0x200F, 0xFEFF,
                0x61C, 0x200B, 0x200C, 0x200D, 0x2060}) {
            create(token, "ops+" + (i++) + "-" + System.nanoTime() + "@example.com",
                    "Ada" + (char) sneaky + "Lovelace")
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anOrdinaryNonAsciiDisplayNameIsStillAccepted() throws Exception {
        var token = loginAsAdmin();

        create(token, "ops+ok-" + System.nanoTime() + "@example.com", "Ада Лавлейс")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.displayName").value("Ада Лавлейс"));
    }

    private ResultActions create(String token, String email, String displayName) throws Exception {
        return mockMvc.perform(post("/api/admin/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "displayName", displayName))));
    }

    private String loginAsAdmin() throws Exception {
        // Lowercase — login lowercases the submitted email before lookup
        var email = ("adm-dn-" + System.nanoTime() + "@example.com").toLowerCase();
        var user = new User();
        user.setEmail(email);
        user.setDisplayName("Admin Test");
        user.setPasswordHash(passwordEncoder.encode("test-password-1"));
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole(SystemRole.ADMIN);
        userRepository.save(user);

        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
