package com.hamstrack.auth;

import com.hamstrack.auth.entity.PasswordReset;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.PasswordResetRepository;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.PasswordLimits;
import com.hamstrack.common.util.TokenUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 §4.4 rule 9 — the doors that WRITE a password may not advertise a range the
 * encoder refuses, and the two guards that hold that are not redundant.</strong>
 *
 * <p>{@code BCryptPasswordEncoder.encode} throws {@code IllegalArgumentException("password
 * cannot be more than 72 bytes")} above {@value com.hamstrack.common.security.PasswordLimits#MAX_PASSWORD_BYTES}
 * UTF-8 bytes, and nothing translates it — so {@code POST /api/auth/register}
 * (<em>unauthenticated</em>) and {@code POST /api/auth/reset-password} answered <strong>500</strong>
 * to a password a person could plausibly type. Two guards now stand there and each covers what
 * the other cannot see:
 *
 * <ul>
 *   <li>{@code @Size(max = 72)} counts <strong>UTF-16 code units</strong> and answers
 *       <strong>400</strong>, naming the field;</li>
 *   <li>{@code AuthService.rejectUnencodablePassword} counts <strong>UTF-8 bytes</strong> and
 *       answers <strong>422</strong> ({@code PasswordTooLongException}) for a value that fits in
 *       72 units and not in 72 bytes.</li>
 * </ul>
 *
 * <p><strong>The 37-character Cyrillic case is the one that proves they are complementary.</strong>
 * 37 units passes {@code @Size}, 74 bytes does not pass the encoder; without this case the
 * annotation looks sufficient and is not. It is asserted <em>per endpoint</em>, because a guard
 * installed on one of two doors is the defect this whole ticket is named after.
 *
 * <p><strong>The ordering assertion is the one that would otherwise never be noticed.</strong> The
 * byte check runs before the reset token is looked up and before {@code reset.setUsedAt(...)}, so a
 * refused attempt leaves the emailed link still usable. Reorder the guard below the lookup and
 * nothing visible breaks — the refusal is still a 422 — except that the caller has now burned the
 * one-time link on a password the server never even tried to store, and their only recovery is to
 * ask for another mail. That is why this class asserts the <em>second</em> request succeeds rather
 * than only that the first is refused.
 *
 * <p>Also here, because it is the same subject seen from the other side: the <strong>reading</strong>
 * door (AC 9). {@code LoginRequest.password} carries {@code max} and deliberately <strong>no
 * {@code min}</strong> — a {@code min} would answer 400 where the endpoint must answer 401 and hand
 * an attacker an oracle telling short guesses apart from wrong ones.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "app.registration.public-signup-enabled=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class PasswordLengthDoorsTest {

    /** One past {@code BCrypt.hashpw}'s ceiling in the unit {@code @Size} counts. */
    private static final String ASCII_73 = "a".repeat(73);

    /**
     * 37 Cyrillic characters: 37 UTF-16 units (so {@code @Size(max = 72)} accepts it) and
     * <strong>74 UTF-8 bytes</strong> (so the encoder does not). The whole reason the byte check
     * exists beside the annotation.
     */
    private static final String CYRILLIC_37 = "б".repeat(37);

    /** Exactly 72 bytes as ASCII — the bound is exact, not approximate. */
    private static final String ASCII_72 = "a".repeat(72);

    /** Exactly 72 bytes as Cyrillic (36 characters × 2), from the other side of the arithmetic. */
    private static final String CYRILLIC_36 = "б".repeat(36);

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordResetRepository passwordResetRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean JavaMailSender mailSender;

    // ------------------------------------------------------- the fixtures are what they claim

    /**
     * The premise every case below rests on, asserted rather than assumed: one fixture is over the
     * limit in <em>units</em>, one is over it in <em>bytes only</em>, and the two accepted ones sit
     * exactly on it. A typo in a repeat count would otherwise make several assertions below true
     * for the wrong reason.
     */
    @Test
    void theFixturesSitWhereTheArithmeticSaysTheyDo() {
        assertThat(ASCII_73).hasSize(73);
        assertThat(bytes(ASCII_73)).isEqualTo(73);

        assertThat(CYRILLIC_37)
                .as("must pass @Size(max = 72), or it proves nothing about the byte check")
                .hasSizeLessThanOrEqualTo(72);
        assertThat(bytes(CYRILLIC_37))
                .as("…and must be over the encoder's ceiling all the same")
                .isEqualTo(74);

        assertThat(bytes(ASCII_72)).isEqualTo(PasswordLimits.MAX_PASSWORD_BYTES);
        assertThat(bytes(CYRILLIC_36)).isEqualTo(PasswordLimits.MAX_PASSWORD_BYTES);
        assertThat(CYRILLIC_36).hasSize(36);
    }

    // ----------------------------------------------------------------- register (unauthenticated)

    @Test
    void register73AsciiCharacterPasswordIs400NamingTheField() throws Exception {
        register(email(), ASCII_73)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void register37CyrillicCharacterPasswordIs422AndNever500() throws Exception {
        register(email(), CYRILLIC_37)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("bytes")));
    }

    @Test
    void registerAccepts72BytesInEitherScript() throws Exception {
        register(email(), ASCII_72).andExpect(status().isCreated());
        register(email(), CYRILLIC_36).andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- reset-password (same rules)

    @Test
    void reset73AsciiCharacterPasswordIs400NamingTheField() throws Exception {
        var raw = issueReset(activeUser("old-password-1"));

        resetPassword(raw, ASCII_73)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());
    }

    @Test
    void reset37CyrillicCharacterPasswordIs422AndNever500() throws Exception {
        var raw = issueReset(activeUser("old-password-1"));

        resetPassword(raw, CYRILLIC_37)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("bytes")));
    }

    @Test
    void resetAccepts72BytesInEitherScript() throws Exception {
        var ascii = activeUser("old-password-1");
        resetPassword(issueReset(ascii), ASCII_72).andExpect(status().isOk());
        assertThat(passwordEncoder.matches(ASCII_72, reload(ascii).getPasswordHash())).isTrue();

        var cyrillic = activeUser("old-password-1");
        resetPassword(issueReset(cyrillic), CYRILLIC_36).andExpect(status().isOk());
        assertThat(passwordEncoder.matches(CYRILLIC_36, reload(cyrillic).getPasswordHash())).isTrue();
    }

    /**
     * <strong>The ordering property, and the only assertion here that a reordering would break.</strong>
     *
     * <p>Both refusals — the 400 from the annotation and the 422 from the byte check — must happen
     * before the reset row is read and marked used. If either moved below
     * {@code reset.setUsedAt(...)}, this class would still be green everywhere else: the refusal
     * status is identical either way. What changes is that a user who typed a passphrase two bytes
     * too long has silently spent the link in their inbox, and the product's answer to "try again"
     * becomes "request another email".
     */
    @Test
    void aRefusedAttemptLeavesTheResetLinkStillUsable() throws Exception {
        var user = activeUser("old-password-1");
        var raw = issueReset(user);

        // The byte-check refusal (422) …
        resetPassword(raw, CYRILLIC_37).andExpect(status().isUnprocessableContent());
        // … and the annotation's refusal (400), on the same still-unused link.
        resetPassword(raw, ASCII_73).andExpect(status().isBadRequest());

        assertThat(passwordResetRepository.findByTokenHash(TokenUtils.sha256(raw)).orElseThrow().getUsedAt())
                .as("""
                        the token must still be unused after a refusal — the guards run before the \
                        lookup and before setUsedAt on purpose""")
                .isNull();

        // THE LINK MUST STILL WORK. A guard that refuses after the token is marked used
        // produces exactly the same 400/422 above and costs the user their emailed link; this
        // second request is the only thing that can tell the two orderings apart.
        resetPassword(raw, "brand-new-pw-9")
                .andExpect(status().isOk());
        assertThat(passwordEncoder.matches("brand-new-pw-9", reload(user).getPasswordHash())).isTrue();
    }

    // --------------------------------------------------------------- the reading door (AC 9)

    @Test
    void loginRefusesA1025CharacterPasswordByNamingTheField() throws Exception {
        login(email(), "a".repeat(1025))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    /**
     * <strong>No {@code min} on this door.</strong> A 7-character password is shorter than any this
     * application will ever store, and the answer must still be the same 401 a wrong password gets.
     * A {@code @Size(min = 8)} here would sort guesses into "too short" and "wrong" for free.
     */
    @Test
    void loginAnswers401ToAShortPasswordRatherThan400() throws Exception {
        var user = activeUser("old-password-1");

        login(user.getEmail(), "short12")
                .andExpect(status().isUnauthorized());
    }

    /**
     * And a password longer than any writing door can produce still reaches the service on its
     * merits: {@code matches} takes BCrypt's {@code for_check} branch, which truncates instead of
     * throwing, so 128 characters verify at the same cost as 100 and the answer is about the
     * credential, never about the length.
     */
    @Test
    void loginAnswers401ToA128CharacterPasswordRatherThan400() throws Exception {
        var user = activeUser("old-password-1");

        login(user.getEmail(), "a".repeat(128))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------- fixtures

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private org.springframework.test.web.servlet.ResultActions register(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password
                         + "\",\"displayName\":\"Length Test\",\"termsAccepted\":true}"));
    }

    private org.springframework.test.web.servlet.ResultActions resetPassword(String token, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/reset-password")
                .contentType(APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private static String email() {
        return ("pw-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                + "@example.com").toLowerCase();
    }

    private User activeUser(String password) {
        var u = new User();
        u.setEmail(email());
        u.setDisplayName("Length Test");
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /** A live reset row written exactly as the service writes one; returns the raw token. */
    private String issueReset(User user) {
        var raw = TokenUtils.generateRawToken();
        var r = new PasswordReset();
        r.setUser(user);
        r.setTokenHash(TokenUtils.sha256(raw));
        r.setExpiresAt(Instant.now().plusSeconds(3600));
        passwordResetRepository.save(r);
        return raw;
    }
}
