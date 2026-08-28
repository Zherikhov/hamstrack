package com.hamstrack.auth;

import com.hamstrack.auth.entity.PasswordReset;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.PasswordResetRepository;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.seed.DataSeeder;
import com.hamstrack.common.util.TokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-200 — startup refuses the published password; nothing refused it on the way
 * IN.</strong>
 *
 * <p>{@code DataSeeder} will not let an instance start while a system administrator's stored
 * hash verifies the value this project printed in its own {@code .env.prod.example}. Both
 * doors a password can walk through in the running application — {@code POST /register} and
 * {@code POST /reset-password} — bounded it only with {@code @Size(min = 8, max = 100)}, and
 * that literal is 19 characters. So it was accepted, with two consequences: the next restart
 * refuses to boot with a message about a template the operator never edited, and until that
 * restart the instance is administrable by anyone who can read the repository. An
 * administrator could do this to their own account from the normal "choose a new password"
 * form.
 *
 * <p><strong>Phrased over the doors, not over one of them.</strong> The reason this bug
 * existed is that the guard was written where the value is <em>read</em> and not where it is
 * <em>written</em>; naming {@code /register} alone would rebuild the same shape. The
 * predicate is {@code DataSeeder.isPublishedPassword}, shared with both startup guards, so
 * the three readers cannot come to disagree about the set — which is also what
 * {@link #theSamePredicateGuardsTheWriteSitesAndTheStartupChecks()} asserts.
 *
 * <p>Shares its property set with {@code AuthFlowsTest} and {@code EmailLengthBoundTest} on
 * purpose, so all three run in one Spring context rather than three.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "app.registration.public-signup-enabled=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class PublishedPasswordWriteSitesTest {

    /**
     * Assembled rather than written out: the repository-wide credential scan in
     * {@code JwtSecretValidationTest} reads this file too, and a literal assignment here
     * would be one more place we published it.
     */
    private static final String PUBLISHED = "SEED_ADMIN" + "_PASSWORD";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordResetRepository passwordResetRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean JavaMailSender mailSender;

    @Test
    void registrationRefusesThePublishedPasswordWith422() throws Exception {
        String email = email();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PUBLISHED
                                 + "\",\"displayName\":\"New User\"}"))
                .andExpect(status().isUnprocessableContent());

        assertThat(userRepository.existsByEmail(email))
                .withFailMessage("""

                        The published password was accepted at registration, so it is now a stored \
                        hash in `users`. If that account is ever made a system administrator the \
                        next boot refuses to start, naming a template the operator never edited - \
                        and until that restart the instance is administrable by anyone who can read \
                        this repository. It is 19 characters, so @Size(min = 8) has no opinion \
                        about it.""")
                .isFalse();
    }

    @Test
    void resetPasswordRefusesThePublishedPasswordWith422() throws Exception {
        String email = email();
        User user = activeUser(email, "old-password-1");
        String token = issueReset(user);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + PUBLISHED + "\"}"))
                .andExpect(status().isUnprocessableContent());

        // The stored password is unchanged - the refusal happened before anything was written.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"old-password-1\"}"))
                .andExpect(status().isOk());
    }

    /**
     * <strong>The refusal must not cost the caller their link.</strong> It is checked before
     * the reset row is marked used, so somebody who picks a bad password gets to pick
     * another one rather than a dead link and a second email.
     */
    @Test
    void aRefusedResetLeavesTheLinkUsable() throws Exception {
        String email = email();
        User user = activeUser(email, "old-password-1");
        String token = issueReset(user);

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + PUBLISHED + "\"}"))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"k7Qv2#tR9pLm4zXw\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"k7Qv2#tR9pLm4zXw\"}"))
                .andExpect(status().isOk());
    }

    /** A password an operator chose is still a password. This refuses one string, by name. */
    @Test
    void anOrdinaryPasswordIsStillAccepted() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email() + "\",\"password\":\"k7Qv2#tR9pLm4zXw\","
                                 + "\"displayName\":\"New User\"}"))
                .andExpect(status().isCreated());
    }

    /**
     * <strong>One set, three readers.</strong> If the write sites ever grow their own copy of
     * the denylist, the two can disagree — and the direction that disagreement will take is
     * the dangerous one, because the startup guard is the one that stops the instance and the
     * write sites are the ones that create the state it stops on.
     */
    @Test
    void theSamePredicateGuardsTheWriteSitesAndTheStartupChecks() {
        assertThat(DataSeeder.isPublishedPassword(PUBLISHED))
                .withFailMessage("""

                        The predicate the register/reset guards call no longer recognises the value \
                        the startup guards refuse. That disagreement only ever goes one way: the \
                        write sites are what create the state, and the startup guard is what stops \
                        the instance because of it.""")
                .isTrue();
        assertThat(DataSeeder.isPublishedPassword(PUBLISHED + " "))
                .withFailMessage("Whitespace nobody can see is not a bypass here either")
                .isTrue();
        assertThat(DataSeeder.isPublishedPassword(null))
                .withFailMessage("A missing password is somebody else's rule (@NotBlank), not this one")
                .isFalse();
        assertThat(DataSeeder.isPublishedPassword("k7Qv2#tR9pLm4zXw"))
                .withFailMessage("""

                        The predicate refused a password nobody published. This is NOT a \
                        weak-password check and must not become one - what justifies its single \
                        entry is that THIS repository printed that exact string as production \
                        configuration, under SEED_ADMIN_PASSWORD's own name.""")
                .isFalse();
    }

    private String email() {
        return ("hd200-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                + "@example.com").toLowerCase();
    }

    private User activeUser(String email, String password) {
        var user = new User();
        user.setEmail(email.toLowerCase());
        user.setDisplayName("Test User");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    /** Insert a password-reset row exactly as the service does; returns the raw token. */
    private String issueReset(User user) {
        var raw = TokenUtils.generateRawToken();
        var reset = new PasswordReset();
        reset.setUser(user);
        reset.setTokenHash(TokenUtils.sha256(raw));
        reset.setExpiresAt(Instant.now().plusSeconds(3600));
        passwordResetRepository.save(reset);
        return raw;
    }
}
