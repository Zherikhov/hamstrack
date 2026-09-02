package com.hamstrack.auth;

import com.hamstrack.auth.dto.LoginRequest;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>{@code @Email} is a shape check, not a length check.</strong> Hibernate Validator's
 * implementation bounds the local part at 64 characters and the domain at 255, so it accepts
 * addresses of roughly 320 — while every column any of them lands in is
 * {@code VARCHAR(255)}, and this application has no {@code DataIntegrityViolationException}
 * handler. A 300-character address that is perfectly well-formed therefore travelled all the
 * way to the INSERT and came back as a <strong>500</strong> where a <strong>400</strong>
 * belongs: a validation failure wearing a server-error costume, which also puts a Postgres
 * message on a path a stack trace has no business reaching. Measured, not inferred: removing
 * the bound from {@code RegisterRequest} produces
 * {@code DataIntegrityViolationException: value too long for type character varying(255)}
 * out of {@code insert into users}, unhandled.
 *
 * <p><strong>Phrased about the category, deliberately.</strong> {@code CreateUserRequest} had
 * carried {@code @Size(max = 255)} since it was written, and its own javadoc states the rule
 * this test enforces — <em>"A rule on one of two doors into a column is not a rule"</em> —
 * while the five other doors into the same columns had no bound at all. A test naming those
 * five would pin exactly the five that existed on the day it was written; the scan below is
 * phrased about every {@code @Email} in production source instead, so the next request record
 * that carries an address is a deliberate decision rather than an omission.
 *
 * <p><strong>The read-only doors are in scope too, and that is the interesting part.</strong>
 * {@code /login}, {@code /forgot-password} and {@code /resend-verification} only ever look an
 * address up, so nothing overflows and the argument for leaving them unbounded is available.
 * It is still wrong, and the way they fail is itself the argument. Removing the bound from
 * {@code LoginRequest} does not produce a 500 - it produces a <strong>401</strong>, because a
 * 300-character address is simply looked up, missed, and reported as bad credentials. The
 * read-only doors do not crash, they <em>answer</em>, which is the harder failure to notice
 * and the reason "it cannot overflow here" is not a reason. Meanwhile an unbounded address on
 * those paths is an unbounded key into
 * {@code RateLimitService}'s per-address maps, and "this one only reads" is a property of
 * today's handler, not of the field. Bound the field, not the itinerary.
 *
 * <p><strong>Scope, so the boundary is not mistaken for a guarantee.</strong> The scan reads
 * source text and strips comments, so that prose <em>about</em> {@code @Email} is not
 * mistaken for one. It says nothing about columns reached by any route other than a validated
 * request body.
 *
 * <p><strong>And not every {@code @Email} it finds is a request field, which the opening
 * paragraph's column argument does not reach.</strong> The scan is over production source, so it
 * also covers an address that lands in no column at all — a bound {@code @ConfigurationProperties}
 * value published on a public endpoint, for one. The width is still right there, for a plainer
 * reason than the column: {@code @Email} accepts roughly 320 characters <em>wherever</em> it is
 * written, so a field carrying only {@code @Email} is unbounded by construction and the 500 is
 * merely the loudest of the ways that shows up. That is why the rule below is phrased about the
 * annotation rather than about the table behind it, and why widening {@link #COLUMN_WIDTH} is the
 * documented escape rather than an exemption list.
 *
 * <p><strong>It reads declarations, not lines, and that is a correction rather than a
 * design.</strong> The first version matched {@code @Email} and {@code String} <em>on one
 * line</em> and claimed that a declaration spanning several would be "reported rather than
 * missed, which is the safe direction". That was false, and HD-190 proved it: adding a
 * {@code @Pattern} to {@code InviteMemberRequest.email} pushed the type onto a third line, and
 * the field silently left the scan — so the one address field on the invitation path, the path
 * a whole ticket about outbound mail was hardening, became the one field nothing checked.
 * Nothing failed except the {@code checked >= 6} tripwire below, which is the only reason this
 * was noticed at all and is the argument for keeping such a tripwire under every
 * "nothing offends" assertion. The scan now walks from each {@code @Email} to the
 * {@code String} it annotates, so annotations may be laid out however anyone likes; a
 * declaration it cannot resolve is <em>reported</em>, which is what the old sentence promised
 * and did not do.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "app.registration.public-signup-enabled=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class EmailLengthBoundTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** {@code users.email}, {@code workspace_invites.email} — both {@code VARCHAR(255)}. */
    private static final int COLUMN_WIDTH = 255;

    private static final Pattern EMAIL_ANNOTATION = Pattern.compile("@Email\\b");
    private static final Pattern STRING_TYPE = Pattern.compile("\\bString\\b");
    private static final Pattern SIZE_MAX = Pattern.compile("@Size\\s*\\([^)]*\\bmax\\s*=\\s*(\\d+)");

    /**
     * How far past an {@code @Email} its {@code String} may be before the scan gives up and
     * reports rather than guesses. Generous — {@code InviteMemberRequest} carries a multi-line
     * {@code @Pattern} between the two — but finite, so a missing type cannot silently swallow
     * the next declaration's bound.
     */
    private static final int DECLARATION_WINDOW = 600;

    @Autowired MockMvc mockMvc;
    @Autowired jakarta.validation.Validator validator;

    // Nothing here should ever reach the mail sender — every request under test is refused
    // during validation, before a handler runs — so this is a guarantee rather than a need.
    // It also gives this class the same context cache key as AuthFlowsTest, whose property
    // set it shares, so the two run in one Spring context instead of two.
    @MockitoBean JavaMailSender mailSender;

    // ------------------------------------------------------------------ the category

    @Test
    void everyEmailFieldInProductionSourceIsBoundedToTheColumnWidth() throws IOException {
        var files = javaSources();

        // A mis-rooted run must fail rather than pass over nothing.
        assertThat(files)
                .as("scanned %s — if this is empty the working directory is not the project root",
                        MAIN_SOURCES.toAbsolutePath())
                .hasSizeGreaterThan(100);

        var offenders = new ArrayList<String>();
        var checked = 0;
        for (var file : files) {
            var source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher email = EMAIL_ANNOTATION.matcher(source);
            while (email.find()) {
                checked++;
                var where = file + ":" + lineOf(source, email.start());
                var declaration = declarationAround(source, email.start());
                if (declaration == null) {
                    offenders.add(where + " — cannot find the String this @Email annotates "
                            + "within " + DECLARATION_WINDOW + " characters, so this field's "
                            + "bound could not be checked at all");
                    continue;
                }
                Matcher size = SIZE_MAX.matcher(declaration);
                if (!size.find()) {
                    offenders.add(where + " — @Email with no @Size at all");
                } else if (Integer.parseInt(size.group(1)) > COLUMN_WIDTH) {
                    offenders.add(where + " — @Size(max = " + size.group(1)
                            + ") is wider than the " + COLUMN_WIDTH + "-character column");
                }
            }
        }

        // The tripwire, and it has already earned its keep once: every assertion below is of
        // the form "nothing offends", so a scan that has stopped seeing declarations passes
        // while guarding nothing. That is not hypothetical here — HD-190 moved one field's
        // type onto another line and this is the line that noticed.
        assertThat(checked)
                .as("the scan found %d @Email declarations, fewer than the %d that exist. A "
                        + "field that has left the scan is a field with no bound as far as this "
                        + "test is concerned, and the offender list below will be empty and "
                        + "green while it happens. Do not lower this number to make it pass — "
                        + "find out which declaration stopped matching", checked, 7)
                .isGreaterThanOrEqualTo(7);

        assertThat(offenders)
                .as("""
                        An @Email field with no length bound. @Email accepts roughly 320
                        characters; the columns are VARCHAR(255) and nothing handles
                        DataIntegrityViolationException, so an over-long address answers 500
                        instead of 400.

                        Add @Size(max = 255) next to the @Email. Do it on read-only endpoints
                        too: an unbounded address is an unbounded rate-limiter key, and
                        "this one only looks the address up" describes today's handler rather
                        than the field.

                        If a field here genuinely writes a wider column, widen COLUMN_WIDTH
                        deliberately and say which column -- do not add an exception.""")
                .isEmpty();
    }

    // ------------------------------------------------------- the mechanism, end to end

    /**
     * The write door: a well-formed 300-character address must be refused by validation, not
     * by Postgres. Asserting {@code 400} is the contract; the sibling test below is what
     * proves the {@code 400} comes from the length bound and not from {@code @Email}, which
     * would make this assertion true for the wrong reason.
     */
    @Test
    void anOverlongButWellFormedAddressIs400OnRegisterRatherThan500() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + overlongAddress()
                                + "\",\"password\":\"password123\",\"displayName\":\"Too Long\"}"))
                .andExpect(status().isBadRequest());
    }

    /** The read-only door, which was unbounded on the argument that it never writes. */
    @Test
    void anOverlongButWellFormedAddressIs400OnLoginRatherThan500() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + overlongAddress() + "\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * The premise the two tests above rest on: this address <em>passes</em> {@code @Email}.
     * Without this, a fixture that happened to be malformed would produce the same two 400s
     * and prove nothing about the length bound.
     */
    @Test
    void theFixtureAddressIsRejectedByTheLengthBoundAndNotByTheShapeCheck() {
        var address = overlongAddress();
        assertThat(address).hasSizeGreaterThan(COLUMN_WIDTH);

        var violations = validator.validate(new LoginRequest(address, "password123"));

        assertThat(violations)
                .as("exactly one constraint should refuse this address")
                .hasSize(1);
        assertThat(violations.iterator().next().getConstraintDescriptor().getAnnotation())
                .as("@Email accepts this address — the length bound is what refuses it, and if "
                        + "@Email refused it instead the end-to-end tests are passing by accident")
                .isInstanceOf(Size.class);
    }

    // ------------------------------------------------------------------ fixture

    /**
     * 300 characters, and valid: a 60-character local part (the limit is 64) and a
     * 239-character domain of labels no longer than 63 (the limit is 255 overall).
     */
    private static String overlongAddress() {
        var label = "b".repeat(59);
        var address = "a".repeat(60) + "@" + label + "." + label + "." + label + "."
                + "c".repeat(55) + ".com";
        assertThat(address).hasSize(300);
        return address;
    }

    /**
     * The declaration an {@code @Email} belongs to: back to whatever ended the previous one
     * ({@code (} {@code ,} {@code ;} {@code \{} {@code \}}), forward to the {@code String} it
     * annotates. Both halves matter — {@code @Size} may be written before {@code @Email}
     * ({@code CreateUserRequest}) or after it ({@code RegisterRequest}), and the type may be
     * several annotations further down ({@code InviteMemberRequest}).
     *
     * <p>{@code null} when no type turns up inside {@link #DECLARATION_WINDOW}, and the caller
     * reports that rather than passing over it. Guessing further would eventually read the
     * <em>next</em> declaration's {@code @Size} and certify a bound belonging to another field.
     */
    private static String declarationAround(String source, int emailAt) {
        int start = 0;
        for (int i = emailAt - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == '(' || c == ',' || c == ';' || c == '{' || c == '}') {
                start = i + 1;
                break;
            }
        }
        Matcher type = STRING_TYPE.matcher(source);
        if (!type.find(emailAt) || type.start() - emailAt > DECLARATION_WINDOW) {
            return null;
        }
        return source.substring(start, type.end());
    }

    /** 1-based, so a reported offender can be opened where it is. */
    private static int lineOf(String source, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static List<Path> javaSources() throws IOException {
        if (!Files.isDirectory(MAIN_SOURCES)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * Blanks line- and block-comment content, preserving length and line breaks so reported
     * line numbers stay true. Javadoc that discusses {@code @Email} is
     * prose, not a declaration — and several of the records this scan covers now carry
     * exactly such a paragraph, explaining why the bound is there.
     *
     * <p>String literals are left alone, unlike the sibling scan in
     * {@code LocaleIndependentFoldingTest}: no literal in this codebase contains
     * {@code "@Email"} followed by {@code String}, and the two scanners are kept independent
     * rather than sharing a helper so that neither can be broken by a change made for the
     * other's benefit.
     */
    private static String stripComments(String src) {
        var out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
