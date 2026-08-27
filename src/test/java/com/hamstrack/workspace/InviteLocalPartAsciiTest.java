package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.mail.MailAddresses;
import com.hamstrack.common.security.JwtService;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.dto.InviteMemberRequest;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.lang.annotation.Annotation;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>An invitation may not be addressed to a local part this application will silently
 * rewrite</strong> (HD-190, round 3) — {@code InviteMemberRequest.email}'s
 * {@code @Pattern("\p{ASCII}*@[^@]*")}.
 *
 * <h2>The defect this closes was introduced by an earlier fix in the same ticket</h2>
 * Round 1 changed {@code WorkspaceService.inviteMember} to mail the <em>folded</em> address rather
 * than {@code req.email()}, so that the address a ceiling counted, the address
 * {@code workspace_invites.email} bound, and the address the mail went to were one value. That
 * reasoning is right and it stays.
 *
 * <p>But the fold is {@code toLowerCase(Locale.ROOT)}, and that is not a no-op on every input:
 * <strong>it maps U+212A KELVIN SIGN onto plain {@code k}</strong> (and U+212B ANGSTROM SIGN onto
 * {@code a}-with-ring). So an admin who pasted a local part spelled with U+212A was, from round 1
 * onwards, sending a workspace name and a <em>live join token</em> to the ASCII spelling — a
 * different real person, whose own account address folds the same way, matches the invitation
 * exactly, and who can therefore accept it. Accidental misdelivery rather than an escalation (an
 * attacker who is already the inviter would simply type ASCII), and it did not exist before round 1.
 *
 * <p><strong>Three things had to be true at once, and each is asserted here</strong>, because the
 * cheapest wrong fixes fail exactly one of them:
 * <ol>
 *   <li>a non-ASCII <em>local part</em> is refused at the boundary — the application has no SMTPUTF8
 *       path, so such an address was never deliverable as typed, and a 400 costs nobody a working
 *       invitation while a silent redirect to a stranger has no upside at all;</li>
 *   <li>an internationalised <em>domain</em> is still accepted — punycode is normalisation to the
 *       wire form, not a fold onto somebody else's mailbox, and refusing the whole address would
 *       shut IDN users out of the product to fix a local-part problem;</li>
 *   <li>a quoted local part containing an {@code @} of its own still works — {@code @Email} accepts
 *       {@code "a@b"@example.com}, so a pattern anchored on the FIRST {@code @} would refuse a
 *       legitimate address the rest of this feature already folds correctly.</li>
 * </ol>
 *
 * <p><strong>Not "mail {@code req.email()} instead".</strong> That un-does round 1 and restores the
 * worse defect: a ceiling that counts one address while the mailer writes to another, which is what
 * made every recipient-keyed ceiling defeatable by a keystroke. The boundary is the only place this
 * can be closed without reopening that.
 *
 * <p>Each refusal is asserted on the constraint that <em>produces</em> it, not merely on a non-empty
 * violation set: this DTO also carries {@code @Email} and {@code @Size(max = 255)}, and a green that
 * came from either of those would certify a bound nobody is relying on here. Both refusal tests
 * therefore assert that {@code @Email} is <em>not</em> among the violations — a shape check accepts
 * any code point in {@code U+0080..U+FFFF} in a local part, which is precisely why a pattern had to
 * be added rather than relied upon.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InviteLocalPartAsciiTest {

    /**
     * The hazardous spelling, as an escape. U+212A is indistinguishable from ASCII {@code K} in a
     * diff, an editor and a code review — which is most of why the misdelivery is worth a
     * constraint — so nothing in this file spells it literally.
     */
    private static final String KELVIN = "\u212A";

    /** Mail never leaves the process: CI has no SMTP server, and nothing here needs a real send. */
    @MockitoBean JavaMailSender mailSender;

    @Autowired MockMvc mockMvc;
    @Autowired Validator validator;
    @Autowired JwtService jwtService;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;

    // ================================================== the hazard, before the constraint

    /**
     * The premise, so the tests below are about a real hazard rather than a style rule: the fold
     * {@code inviteMember} applies before it binds and before it mails turns one spelling into
     * <em>somebody else's address</em>. Nothing downstream can notice — by the time the mailer sees
     * it, it is an ordinary ASCII address matching an ordinary account.
     */
    @Test
    void theFoldTurnsAKelvinLocalPartIntoADifferentRealPersonsAddress() {
        var typed = KELVIN + "elvin@example.test";
        var stranger = "kelvin@example.test";

        assertThat(typed)
                .as("two different strings as typed — an admin pasting one has no reason to think "
                    + "they are addressing the other")
                .isNotEqualTo(stranger);
        assertThat(typed.toLowerCase(Locale.ROOT))
                .as("...but this is the fold inviteMember applies before it binds the invitation "
                    + "and before it hands an address to the mailer, so the join token would go to "
                    + "the ASCII person's inbox")
                .isEqualTo(stranger);
        assertThat(MailAddresses.throttleKey(typed))
                .as("and the ceiling is spent on that person's key too, which is correct for a "
                    + "ceiling and is exactly what makes the misdelivery invisible")
                .isEqualTo(MailAddresses.throttleKey(stranger));
    }

    // ================================================== 1. the local part is refused

    @Test
    void aNonAsciiLocalPartIsRefusedByThePatternAndNotByAnythingElse() {
        var typed = KELVIN + "elvin@example.test";

        assertThat(refusedBy(typed))
                .as("a local part this application will rewrite into a stranger's address must be "
                    + "refused at the boundary, and it has to be @Pattern that does it: @Email "
                    + "accepts any code point in U+0080..U+FFFF in a local part, and @Size sees an "
                    + "address well inside 255, so no other constraint here can refuse this")
                .contains(Pattern.class)
                .doesNotContain(Size.class)
                .doesNotContain(Email.class);
    }

    /**
     * End to end, and both directions in one test: the same invitation with an ASCII local part is
     * accepted. Without the second half, a 400 caused by anything at all — a broken fixture, a
     * missing membership, a typo in the JSON — would look like the seal working.
     */
    @Test
    void theEndpointAnswers400ForTheKelvinSpellingAnd201ForTheAsciiOne() throws Exception {
        var owner = user();
        var workspace = workspaceOwnedBy(owner);
        var suffix = UUID.randomUUID().toString().substring(0, 12);

        invite(owner, workspace, KELVIN + "elvin-" + suffix + "@example.test")
                .andExpect(status().isBadRequest());

        invite(owner, workspace, "kelvin-" + suffix + "@example.test")
                .andExpect(status().isCreated());
    }

    /**
     * The pattern is anchored on the <em>last</em> {@code @}, so a non-ASCII character hidden inside
     * a quoted local part is still refused. {@code @Email} accepts this address — a quoted local
     * part may contain both {@code @} and any code point in {@code U+0080..U+FFFF} — so a pattern
     * that split on the first {@code @} would read the tail as a domain and wave it through, which
     * is the same misdelivery wearing punctuation.
     */
    @Test
    void aNonAsciiCharacterHiddenInsideAQuotedLocalPartIsRefusedToo() {
        assertThat(refusedBy("\"a@" + KELVIN + "\"@example.test"))
                .as("the non-ASCII character is before the LAST @, so it is in the local part "
                    + "however many @s precede it")
                .contains(Pattern.class)
                .doesNotContain(Email.class);
    }

    // ================================================== 2. the domain is still accepted

    /**
     * <strong>The domain is deliberately left internationalisable.</strong> Punycoding a domain is
     * normalisation to the form that goes on the wire — the same mailbox either way — where folding
     * a local part is a rewrite onto a different mailbox. Refusing both because one is dangerous
     * would lock every IDN user out of being invited, to fix a problem that is not in the domain.
     */
    @Test
    void anInternationalisedDomainIsStillAcceptedAndStillReachesItsOwnKey() throws Exception {
        var owner = user();
        var workspace = workspaceOwnedBy(owner);
        var local = "opfer-" + UUID.randomUUID().toString().substring(0, 12);
        var address = local + "@m\u00FCnchen.de";

        assertThat(refusedBy(address))
                .as("nothing may refuse an internationalised domain: the ASCII rule is about the "
                    + "local part, and the pattern's tail is [^@]* precisely so the domain stays "
                    + "unrestricted")
                .isEmpty();

        invite(owner, workspace, address).andExpect(status().isCreated());

        assertThat(MailAddresses.throttleKey(address))
                .as("and it keys on the punycode spelling, which is the SAME inbox — not a fold "
                    + "onto a stranger, which is the distinction the whole constraint rests on")
                .isEqualTo(MailAddresses.throttleKey(local + "@xn--mnchen-3ya.de"));
    }

    // ================================================== 3. quoted local parts still work

    /**
     * {@code "a@b"@example.test} is a legal address, {@code @Email} accepts it, and
     * {@code throttleKey} already has a rule for stripping its quotes. The pattern must not be the
     * thing that breaks it — hence {@code \p{ASCII}*@[^@]*} matching greedily to the last
     * {@code @} rather than an expression that stops at the first one.
     */
    @Test
    void aQuotedLocalPartContainingAnAtStillPasses() throws Exception {
        var owner = user();
        var workspace = workspaceOwnedBy(owner);
        var address = "\"a@b-" + UUID.randomUUID().toString().substring(0, 8) + "\"@example.test";

        assertThat(refusedBy(address))
                .as("an all-ASCII local part is legal however many @s it contains — the rule is "
                    + "about code points, not about punctuation")
                .isEmpty();

        invite(owner, workspace, address).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Which constraint annotations refuse this address, asked of the real DTO. Returning the
     * annotation types rather than a boolean is the point: "something refused it" is the assertion
     * that lets a test certify a bound nobody is relying on.
     */
    private Set<Class<? extends Annotation>> refusedBy(String address) {
        Set<ConstraintViolation<InviteMemberRequest>> violations =
                validator.validate(new InviteMemberRequest(address, null, "MEMBER"));
        return violations.stream()
                .map(v -> v.getConstraintDescriptor().getAnnotation().annotationType())
                .collect(Collectors.toSet());
    }

    /**
     * The body is written with JSON backslash-u escapes so the bytes on the wire are ASCII. A 400
     * that came from a mangled request encoding rather than from the constraint would be the same
     * green, and unnoticeable.
     */
    private ResultActions invite(User sender, Workspace workspace, String email) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspace.getId() + "/invites")
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + jsonEscape(email) + "\",\"role\":\"MEMBER\"}"));
    }

    private static String jsonEscape(String raw) {
        var out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private User user() {
        var u = new User();
        u.setEmail("inviter-" + UUID.randomUUID() + "@example.test");
        u.setDisplayName("ASCII local part");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    /** A workspace whose creator is its OWNER, i.e. holds {@code workspace.member.manage}. */
    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("ASCII local part " + UUID.randomUUID().toString().substring(0, 8));
        w.setSlug("alp-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(owner);
        var saved = workspaceRepository.save(w);
        var member = new WorkspaceMember();
        member.setWorkspace(saved);
        member.setUser(owner);
        member.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(member);
        return saved;
    }
}
