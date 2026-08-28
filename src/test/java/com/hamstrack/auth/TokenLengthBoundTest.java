package com.hamstrack.auth;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.util.TokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 §4.4 / AC 10 — all four token doors, because a rule on one of two doors is not a
 * rule.</strong>
 *
 * <p>{@code TokenUtils.generateRawToken} is 32 random bytes rendered as 43 Base64url characters, so
 * every one of these fields is bounded at 64: generous, finite, and set from the <em>generator</em>
 * rather than from the {@code CHAR(64)} hash column behind it — the hash is 64 hex characters
 * whatever was submitted, so the column can never be the thing that refuses.
 *
 * <p>The four doors are the two request bodies ({@code VerifyEmailRequest.token},
 * {@code ResetPasswordRequest.token}) and two {@code @RequestParam} twins carrying the same value on
 * the same flows: {@code GET /api/auth/verify-email} (the redirect that keeps already-sent mail
 * links working) and {@code POST /api/workspaces/accept-invite}. The GET was the one that could
 * actually be hurt — an ~8 KB token of characters {@code URLEncoder} expands 3× builds a ~24 KB
 * {@code Location} header, past Tomcat's 8 KB response-header limit, so an <em>unauthenticated</em>
 * caller got a 500 and an ERROR line for free, on a path {@code AuthRateLimitFilter} deliberately
 * exempts (it skips every non-POST method). Accept-invite is bounded because it is the category,
 * not because of what it risks.
 *
 * <p><strong>The two params are asserted as a status CLASS, and a 500 fails.</strong> Which 4xx a
 * bounded {@code @RequestParam} produces depends on which method-validation mechanism fires, and
 * that is a Spring detail this suite should not pin. A 500 is the outcome the bound exists to
 * remove. Adding {@code @Validated} to either controller class no longer reintroduces it — HD-214
 * gave the AOP proxy's {@code jakarta.validation.ConstraintViolationException} a handler, so it
 * answers 400 — but §4.4 rule 8 still forbids the annotation, and for a reason this assertion
 * cannot see: the violation would take a different door, so the refusal arrives in a different
 * shape from its siblings' and drops an ERROR line on every occurrence. The prohibition is about
 * one refusal shape per declared constraint, not about a crash.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "app.registration.public-signup-enabled=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class TokenLengthBoundTest {

    /** One past the bound. */
    private static final String TOKEN_65 = "t".repeat(65);

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean JavaMailSender mailSender;

    // ------------------------------------------------------------------ the two request bodies

    @Test
    void verifyEmailBodyRefusesA65CharacterTokenByNamingTheField() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN_65 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").exists());
    }

    @Test
    void resetPasswordBodyRefusesA65CharacterTokenByNamingTheField() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN_65 + "\",\"newPassword\":\"brand-new-pw-9\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").exists());
    }

    // ------------------------------------------------------------------ the two request params

    @Test
    void verifyEmailLinkRefusesAnOverlongTokenWithout500() throws Exception {
        var result = mockMvc.perform(get("/api/auth/verify-email").param("token", TOKEN_65))
                .andReturn();

        assertNotServerError(result, "GET /api/auth/verify-email");
        assertThat(result.getResponse().getStatus()).isBetween(400, 499);
    }

    /**
     * The other half of the same door, which is the half that must keep working: a real 43-character
     * token still redirects, so every link already sitting in somebody's inbox is unaffected by the
     * bound.
     */
    @Test
    void verifyEmailLinkStillRedirectsARealToken() throws Exception {
        var raw = TokenUtils.generateRawToken();
        assertThat(raw).hasSizeLessThanOrEqualTo(64);

        mockMvc.perform(get("/api/auth/verify-email").param("token", raw))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/verify-email?token=" + raw));
    }

    @Test
    void acceptInviteRefusesAnOverlongTokenWithout500() throws Exception {
        var token = login(user());

        var result = mockMvc.perform(post("/api/workspaces/accept-invite")
                        .header("Authorization", "Bearer " + token)
                        .param("token", TOKEN_65))
                .andReturn();

        assertNotServerError(result, "POST /api/workspaces/accept-invite");
        assertThat(result.getResponse().getStatus()).isBetween(400, 499);
    }

    private static void assertNotServerError(MvcResult result, String door) {
        assertThat(result.getResponse().getStatus())
                .as("""
                        %s answered a server error to a request the caller got wrong. A bounded \
                        @RequestParam only reaches Spring MVC's built-in method validation while \
                        its controller class carries NO @Validated: adding one defers to the AOP \
                        proxy, whose jakarta.validation.ConstraintViolationException reaches a \
                        different handler — 400 since HD-214, but a divergent refusal shape and an \
                        ERROR line per request. So a 500 here is not that: it means the bound is \
                        gone, or the value reached a layer that could not carry it.""", door)
                .isLessThan(500);
    }

    // ------------------------------------------------------------------------------- fixtures

    private User user() {
        var u = new User();
        u.setEmail(("tok-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                    + "@example.com").toLowerCase());
        u.setDisplayName("Token Test");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
