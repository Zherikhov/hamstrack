package com.hamstrack.admin;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-176 §7.2 / AC 15 — a custom-field select option's {@code color} was validated as
 * NOTHING.</strong>
 *
 * <p>Statuses, priorities and issue types have each carried a {@code @Pattern} on their DTO since
 * V1. An option's colour could not: {@code UpsertFieldRequest.config} is a {@code JsonNode}, and no
 * bean-validation annotation reaches inside one — the same structural reason
 * {@code requireConfigSize} has to exist as a service check rather than as an annotation. So
 * {@code "red"}, {@code ""} and {@code "javascript:…"} were accepted, stored, and re-served from
 * {@code ProjectConfigController} — the endpoint the SPA fetches for <em>every</em> board load and
 * every issue form — straight into whatever a component does with a colour.
 *
 * <p><strong>The message is asserted, not just the code.</strong> A refusal that says only that it
 * refused leaves an admin guessing between {@code red}, {@code rgb(255,0,0)} and {@code #F00}; this
 * one names the shape it wants and quotes the option it is talking about, because a select may
 * carry up to 100 of them.
 *
 * <p><strong>And the accepting half is the assertion that this is a FORMAT check.</strong>
 * {@code #FFFF00} measures 1.07:1 against white and is accepted here on purpose: the owner's
 * decision (ADR-0027) is that a stored colour is an identity hue and that the readable foreground
 * is derived from it at render time. A future edit that turned this guard into a contrast guard
 * would fail {@link #aBlindinglyBrightColourIsAcceptedBecauseThisIsFormatNotContrast}, which is the
 * only place that decision is written down as a status code.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class AdminFieldOptionColorFormatTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------------------- refused

    /** A CSS colour keyword is not a colour here, and the refusal has to say which shape is. */
    @Test
    void aNamedColourIsRefusedAndTheMessageNamesTheShapeItWants() throws Exception {
        create("SELECT", options("\"color\":\"red\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("#RRGGBB")))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("'critical'")));
    }

    /**
     * The shape the spec actually named. It is stored today and handed back to every project
     * member; whether any current component would execute it is not the point — a value that is
     * not a colour has no business in a colour column.
     */
    @Test
    void aUrlSchemeIsRefused() throws Exception {
        create("SELECT", options("\"color\":\"javascript:alert(1)\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("#RRGGBB")));
    }

    /**
     * <strong>Blank is a value and it is refused; absent is not a value and is accepted</strong>
     * (below). That split is not a preference — it is exactly what {@code @Pattern} does on the
     * three sibling colour columns, and the point of this ticket is that the four paths stop
     * disagreeing.
     */
    @Test
    void anEmptyStringIsRefused() throws Exception {
        create("SELECT", options("\"color\":\"\""))
                .andExpect(status().isUnprocessableContent());
    }

    /** Three digits is the other spelling people reach for, and it is not the one we store. */
    @Test
    void aThreeDigitHexIsRefused() throws Exception {
        create("SELECT", options("\"color\":\"#F00\""))
                .andExpect(status().isUnprocessableContent());
    }

    /**
     * <strong>The create endpoint is not the only door.</strong> {@code PATCH} rewrites
     * {@code config} wholesale, so a guard installed on one verb leaves the value one request away
     * — and the update path is the one an admin actually uses to add an option to an existing
     * field.
     */
    @Test
    void theUpdatePathRefusesToo() throws Exception {
        var token = admin();
        var id = idOf(create(token, "SELECT", options("\"color\":\"#FF0000\""))
                .andExpect(status().isCreated()));

        mockMvc.perform(patch("/api/admin/fields/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SELECT", options("\"color\":\"red\""))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("#RRGGBB")));
    }

    // ------------------------------------------------------------------------------- accepted

    @Test
    void aWellFormedHexIsAccepted() throws Exception {
        create("SELECT", options("\"color\":\"#FF0000\"")).andExpect(status().isCreated());
    }

    /** {@code labels.color} stores the 8-digit form, so one rule covers both spellings. */
    @Test
    void theEightDigitFormIsAccepted() throws Exception {
        create("SELECT", options("\"color\":\"#FF000080\"")).andExpect(status().isCreated());
    }

    /**
     * <strong>The assertion that decision 2 was taken and not quietly reversed.</strong>
     * {@code #FFFF00} is unreadable as ink on a white card — that is the renderer's problem, solved
     * by deriving the ink from the hue, and it is never a reason to refuse a write.
     */
    @Test
    void aBlindinglyBrightColourIsAcceptedBecauseThisIsFormatNotContrast() throws Exception {
        create("SELECT", options("\"color\":\"#FFFF00\"")).andExpect(status().isCreated());
    }

    /** No colour at all has always been legal — the SPA falls back to a neutral token. */
    @Test
    void anOptionWithNoColourAtAllIsStillAccepted() throws Exception {
        create("SELECT", "{\"options\":[{\"id\":\"critical\",\"label\":\"Critical\"}]}")
                .andExpect(status().isCreated());
    }

    /**
     * The guard lives in the SELECT branch, so a non-SELECT field's {@code config} is not read as
     * options — stated as a case so nobody "fixes" it into a document-wide scan that would refuse a
     * TEXT field carrying a key called {@code color} for its own reasons.
     */
    @Test
    void aNonSelectTypeIsNotInspectedForOptionColours() throws Exception {
        create("TEXT", "{\"color\":\"red\"}").andExpect(status().isCreated());
    }

    // ---------------------------------------- the refusal has to survive being written (Low 1)

    /**
     * <strong>The refusal is itself a response, and the message quotes the offending value back.</strong>
     * That echo used to be bounded with {@code substring(0, 40)}, which counts UTF-16 code units,
     * so a value whose 41st unit is the low half of a surrogate pair was cut <em>between the
     * halves</em> and the {@code detail} carried a lone high surrogate. 39 characters plus one
     * astral character is the exact arithmetic that lands the cut inside the pair; it is computed
     * rather than written as a literal so it cannot drift away from the boundary it is aiming at.
     *
     * <p><strong>Read the assertion before assuming what it is guarding.</strong> The reviewer
     * expected a replacement character or a serialisation failure — a 500 on a path whose only job
     * is to answer 422. It is neither: Jackson's UTF-8 generator emits an unencodable surrogate as
     * a {@code \\uD83D} escape, so the observed defect was a well-formed HTTP 422 carrying an
     * <strong>unpaired surrogate escape</strong> — a body that decodes, everywhere, to a message
     * with an unrenderable character standing where the value should be, and that anything
     * re-encoding it downstream mangles on its own terms.
     *
     * <p>Which is why this asserts the <em>decoded</em> {@code detail} and not the status. Against
     * the unfixed code the status was 422 and the raw body was clean ASCII (the surrogate was
     * hiding inside an escape sequence): a status-only test, or one reading the raw body, passes
     * over the defect without noticing it. The observed pre-fix body was
     * {@code "Option 'critical' has color 'aaa…\\uD83D...' — Color must be #RRGGBB or #RRGGBBAA"}.
     */
    @Test
    void aRefusedColourCutInsideASurrogatePairIsNotEchoedAsHalfOfOne() throws Exception {
        var colour = "a".repeat(39) + "\\ud83d\\ude00";   // 39 + one astral code point = 41 units
        var response = create("SELECT", options("\"color\":\"" + colour + "\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("#RRGGBB")))
                .andReturn().getResponse().getContentAsString();
        assertEveryCharacterIsRenderable(response);
    }

    /**
     * <strong>The colour is not the only thing the message quotes.</strong> The option id is echoed
     * too — a select may carry 100 options and "one of yours is wrong" is not actionable — so it is
     * the same door, and it does not even need a cut to go through it: an <em>unpaired</em>
     * {@code \\uD83D} is trivially writable as JSON and arrives in the message whole.
     *
     * <p>Asserted as a status <em>class</em> rather than as 422, because a layer below is entitled
     * to refuse an unpaired surrogate outright with a 400 and that would be a perfectly good answer;
     * what is not acceptable is a refusal nobody can read. (Observed: it reaches the service, so the
     * answer is 422.)
     */
    @Test
    void anOptionIdCarryingAnUnpairedSurrogateIsNotEchoedRaw() throws Exception {
        var config = "{\"options\":[{\"id\":\"crit\\ud83d\",\"label\":\"Critical\","
                     + "\"color\":\"red\"}]}";
        var response = create("SELECT", config)
                .andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString();
        assertEveryCharacterIsRenderable(response);
    }

    /**
     * <strong>The fix neutralises what cannot be encoded, and nothing else.</strong> Flattening the
     * echo to ASCII would also have closed the hole and would have traded an unrenderable character
     * for a refusal that names no option an admin can find: an option id is legitimately Cyrillic or
     * CJK, and it is the only part of the sentence saying <em>which</em> option is meant. So a
     * well-formed non-ASCII id comes back intact — including an astral character, whose pair is
     * exactly what the bound must not split.
     */
    @Test
    void aWellFormedNonAsciiOptionIdIsEchoedIntact() throws Exception {
        var config = "{\"options\":[{\"id\":\"крит\\ud83d\\ude00\",\"label\":\"Critical\","
                     + "\"color\":\"red\"}]}";
        create("SELECT", config)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("крит😀")));
    }

    /**
     * Decodes the body the way a client does and requires every surrogate in the message to have
     * its partner — <strong>decoding is the whole point</strong>: an unpaired surrogate travels
     * inside a JSON string escape, so the raw response text looks like ordinary ASCII and only
     * a parse reveals it. {@code U+FFFD} is rejected as well, since a substituting encoder anywhere
     * upstream leaves that instead.
     */
    private static void assertEveryCharacterIsRenderable(String body) throws Exception {
        var detail = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("detail").asText();
        for (int i = 0; i < detail.length(); i++) {
            char c = detail.charAt(i);
            org.junit.jupiter.api.Assertions.assertNotEquals('�', c,
                    "a replacement character in the refusal: " + detail);
            if (Character.isHighSurrogate(c)) {
                org.junit.jupiter.api.Assertions.assertTrue(
                        i + 1 < detail.length() && Character.isLowSurrogate(detail.charAt(i + 1)),
                        "lone high surrogate at " + i + " in the refusal: " + detail);
                i++;
            } else {
                org.junit.jupiter.api.Assertions.assertFalse(Character.isLowSurrogate(c),
                        "lone low surrogate at " + i + " in the refusal: " + detail);
            }
        }
    }

    // ------------------------------------------------------------------------------- fixtures

    private static String options(String colorMember) {
        return "{\"options\":[{\"id\":\"critical\",\"label\":\"Critical\"," + colorMember + "}]}";
    }

    private ResultActions create(String type, String config) throws Exception {
        return create(admin(), type, config);
    }

    private ResultActions create(String token, String type, String config) throws Exception {
        return mockMvc.perform(post("/api/admin/fields")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(type, config)));
    }

    private static String body(String type, String config) {
        var suffix = Math.abs(UUID.randomUUID().hashCode());
        return "{\"name\":\"HD176 " + suffix + "\",\"key\":\"hd176_" + suffix
               + "\",\"type\":\"" + type + "\",\"config\":" + config + "}";
    }

    /**
     * The FIRST {@code "id"} in the body, which is the field's own — {@code config.options[].id}
     * is in there too, and a greedy expression happily returns {@code critical}, producing a
     * {@code PATCH /api/admin/fields/critical} that answers 400 for a reason that has nothing to
     * do with this test.
     */
    private static String idOf(ResultActions created) throws Exception {
        var body = created.andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(body);
        org.junit.jupiter.api.Assertions.assertTrue(m.find(), "no id in: " + body);
        return m.group(1);
    }

    private String admin() throws Exception {
        var email = ("opt-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                     + "@example.com").toLowerCase();
        var u = new User();
        u.setEmail(email);
        u.setDisplayName("Option Colour Test");
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
