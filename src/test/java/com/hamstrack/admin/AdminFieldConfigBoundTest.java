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

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 §4.3 rule 6 / AC 11 — a bound on the members of a set is not a bound on the
 * set.</strong>
 *
 * <p>{@code field_defs.config} is {@code JSONB} and had no bound of any kind: not the document size,
 * not the option count, not any option's {@code id} or {@code label}. The first round bounded the
 * two <em>leaves</em> and described {@code config} as bounded — it was not.
 * {@code {"options":[{"id":"a","label":"b","color":"<20 M chars>"}]}} passed, so did any unrelated
 * top-level key, and so did the entire {@code config} of every non-SELECT type, because the option
 * checks live inside the SELECT branch and never see them.
 *
 * <p>What makes this worth a 422 rather than a shrug is where the document goes:
 * {@code ProjectConfigController} returns {@code config} on the endpoint the SPA fetches for
 * <em>every board and every issue form</em>, so an unbounded document is not a stored blob but
 * hundreds of megabytes re-served on every page load — plantable by any workspace admin, contained
 * to their own tenant.
 *
 * <p>The three assertions below are three different guards, and the third is the one the leaf checks
 * could never have made: a <strong>non-SELECT</strong> type carrying an over-large document.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AdminFieldConfigBoundTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void aSelectFieldWith101OptionsIsRefused() throws Exception {
        var options = IntStream.range(0, 101)
                .mapToObj(i -> "{\"id\":\"o" + i + "\",\"label\":\"Option " + i + "\"}")
                .collect(Collectors.joining(","));

        create("SELECT", "{\"options\":[" + options + "]}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anOptionLabelOf101CharactersIsRefused() throws Exception {
        create("SELECT", "{\"options\":[{\"id\":\"o1\",\"label\":\"" + "L".repeat(101) + "\"}]}")
                .andExpect(status().isUnprocessableContent());
    }

    /**
     * <strong>The case the option checks never see.</strong> A TEXT field's {@code config} is not
     * validated as options at all, so before the document-level ceiling this body was stored whole
     * and re-served to every project member on every board load.
     */
    @Test
    void anOverLargeConfigIsRefusedForANonSelectTypeToo() throws Exception {
        create("TEXT", "{\"note\":\"" + "x".repeat(20_001) + "\"}")
                .andExpect(status().isUnprocessableContent());
    }

    /** The other edge, so the three refusals above are not passing for some unrelated reason. */
    @Test
    void anOrdinarySelectFieldIsStillAccepted() throws Exception {
        create("SELECT", "{\"options\":[{\"id\":\"a\",\"label\":\"A\"},{\"id\":\"b\",\"label\":\"B\"}]}")
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------------------- fixtures

    private org.springframework.test.web.servlet.ResultActions create(String type, String config)
            throws Exception {
        var suffix = Math.abs(UUID.randomUUID().hashCode());
        return mockMvc.perform(post("/api/admin/fields")
                .header("Authorization", "Bearer " + admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"HD171 " + suffix + "\",\"key\":\"hd171_" + suffix
                         + "\",\"type\":\"" + type + "\",\"config\":" + config + "}"));
    }

    private String admin() throws Exception {
        var email = ("cfg-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                     + "@example.com").toLowerCase();
        var u = new User();
        u.setEmail(email);
        u.setDisplayName("Config Test");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.ADMIN);
        userRepository.save(u);

        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
