package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 AC 1, AC 12 and AC 13 — the invite door, where the length bound meets the tenancy
 * rule.</strong>
 *
 * <p>{@code POST /api/workspaces/{ws}/invites} is the third address door with no end-to-end
 * assertion, and it is the only one that is workspace-scoped. Two claims live here:
 *
 * <ol>
 *   <li><strong>the bound</strong> — a 300-character well-formed address, and an over-long
 *       {@code role}, are each a 400 naming the field, with no invitation row, no mail and no
 *       {@code mail_send_events} row;</li>
 *   <li><strong>the ordering, which reads backwards against this project's strongest reflex.</strong>
 *       {@code @Valid} on a {@code @RequestBody} is evaluated during argument resolution — before
 *       the handler body runs, therefore before {@code WorkspaceAccessService} resolves membership.
 *       So an authenticated <em>non-member</em>, and a caller naming a workspace id that
 *       <em>does not exist</em>, both receive the same <strong>400</strong> a member gets, not the
 *       404 the tenancy rule would otherwise demand.</li>
 * </ol>
 *
 * <p><strong>Why (2) is correct rather than a tenancy regression.</strong> The invariant is that
 * non-existence and non-membership are indistinguishable <em>from one another</em> — and here they
 * are, because both are byte-identical to what a member gets. The 400 reveals only that the route
 * exists, which the published OpenAPI document already states. The leak would be the opposite
 * outcome: a 400 for a member and a 404 for a stranger on the same malformed body turns a
 * validation error into a membership oracle.
 *
 * <p><strong>"Byte-identical" holds for the two callers who send byte-identical requests, and the
 * spec's wording is one step stronger than the wire allows for the third.</strong> problem+json
 * echoes the request URI back as {@code instance} — filled by the framework, not by
 * {@code GlobalExceptionHandler} — so the caller who names a <em>different</em> workspace id
 * necessarily gets a body differing in that one member. It is the caller's own input and carries
 * nothing the server knows, so it is normalised away; member and non-member, who send the same URI,
 * are compared with nothing normalised at all.
 *
 * <p><strong>Written as an observation of three statuses, not as an assertion about ordering.</strong>
 * If a filter, an interceptor or an {@code @InitBinder} ever moves validation after resolution, this
 * test must report the new reality rather than pass on a stale premise — so it compares the three
 * bodies it actually receives.
 *
 * <p><strong>The condition all of this rests on is a prohibition, not a footnote</strong> (§15,
 * ADR-0017): the byte-identical 400 holds only while every constraint on these DTOs is a pure
 * function of the submitted body. A DB-touching {@code ConstraintValidator} — "this email is
 * already a member", "this role id belongs to this workspace" — would answer differently for a
 * member and a stranger while the handler's shape does not change at all, and this test would go
 * red. That is the intended failure: fix the validator, never this file.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InviteRequestBoundTest {

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean JavaMailSender mailSender;

    // ------------------------------------------------------------------------ the bound (AC 1/12)

    @Test
    void anOverlongAddressIs400NamingTheFieldAndWritesNothing() throws Exception {
        var owner = user();
        var ws = workspaceOwnedBy(owner);
        var token = login(owner);
        long invitesBefore = count("workspace_invites");
        long eventsBefore = count("mail_send_events");

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/invites")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(overlongAddress(), "MEMBER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());

        verifyNoInteractions(mailSender);
        assertThat(count("workspace_invites"))
                .as("a refused invite must leave no invitation row")
                .isEqualTo(invitesBefore);
        assertThat(count("mail_send_events"))
                .as("…and must not spend the recipient's mail budget for a body the server refused")
                .isEqualTo(eventsBefore);
    }

    /**
     * {@code role} is bounded at 40 because {@code roles.key} is {@code VARCHAR(40)} — and because
     * an unknown value is echoed back in the {@code detail} of {@code UnknownRoleException}. Only a
     * caller holding {@code workspace.member.manage} can reach that echo, which is a reason to
     * bound the field rather than a reason not to.
     */
    @Test
    void anOverlongRoleKeyIs400NamingTheField() throws Exception {
        var owner = user();
        var ws = workspaceOwnedBy(owner);

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/invites")
                        .header("Authorization", "Bearer " + login(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("someone@example.com", "R".repeat(41))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role").exists());
    }

    // ------------------------------------------------------------- validation before tenancy (13)

    @Test
    void memberNonMemberAndUnknownWorkspaceAllGetTheSame400() throws Exception {
        var owner = user();
        var ws = workspaceOwnedBy(owner);
        var stranger = user();

        var asMember = refusal("/api/workspaces/" + ws.getId() + "/invites", login(owner));
        var asNonMember = refusal("/api/workspaces/" + ws.getId() + "/invites", login(stranger));
        var unknownWorkspace = refusal("/api/workspaces/" + UUID.randomUUID() + "/invites", login(stranger));

        // The strongest form the mechanism allows: same route, same body, two callers whose access
        // differs completely — and one string.
        assertThat(asNonMember)
                .as("""
                        A non-member must not be told anything a member is not told. These two \
                        requests are byte-identical on the wire, so a difference in the responses \
                        can only come from who sent them — which is a membership oracle arriving \
                        through a 400, the exact leak the 404 rule exists to prevent. If \
                        validation now runs AFTER WorkspaceAccessService, this is the new reality \
                        to reckon with rather than a test to relax.""")
                .isEqualTo(asMember);

        // The third caller names a DIFFERENT workspace id, so its body cannot be byte-identical:
        // problem+json echoes the request URI back as `instance` (the framework fills it, not
        // GlobalExceptionHandler). That echo is the caller's own input and carries nothing the
        // server knows, so it is normalised away rather than asserted on — everything else,
        // including the status, title, detail and the errors map, must match exactly.
        assertThat(withoutInstance(unknownWorkspace))
                .as("""
                        …and a workspace that does not exist must be indistinguishable from one \
                        the caller simply cannot see. Both are the same 400 a member gets, which \
                        is what makes this correct rather than a leak: the response reveals only \
                        that the route exists, which openapi.yaml already says.""")
                .isEqualTo(withoutInstance(asMember));
        assertThat(unknownWorkspace)
                .as("and the only thing that may differ is the URI the caller themselves sent")
                .contains("\"instance\":\"/api/workspaces/");
    }

    /** Drops the {@code instance} member, which is the request URI echoed back verbatim. */
    private static String withoutInstance(String body) {
        return body.replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    /**
     * The security filter chain runs before argument resolution, so the one caller who must
     * <em>not</em> get the 400 is the one who has not authenticated at all.
     */
    @Test
    void anUnauthenticatedCallerStillGets401() throws Exception {
        var owner = user();
        var ws = workspaceOwnedBy(owner);

        mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(overlongAddress(), "MEMBER")))
                .andExpect(status().isUnauthorized());
    }

    private String refusal(String path, String token) throws Exception {
        return mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(overlongAddress(), "MEMBER")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------------------- fixtures

    private static String body(String email, String role) {
        return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    /** The same 300-character, perfectly well-formed address the other doors are asserted with. */
    private static String overlongAddress() {
        var label = "b".repeat(59);
        var address = "a".repeat(60) + "@" + label + "." + label + "." + label + "."
                      + "c".repeat(55) + ".com";
        assertThat(address).hasSize(300);
        return address;
    }

    private long count(String table) throws SQLException {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private User user() {
        var u = new User();
        u.setEmail(("inv-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                    + "@example.com").toLowerCase());
        u.setDisplayName("Invite Test");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug("inv-" + UUID.randomUUID().toString().substring(0, 8) + "-" + (System.nanoTime() % 100000));
        w.setCreatedBy(owner);
        w = workspaceRepository.save(w);
        var m = new WorkspaceMember();
        m.setWorkspace(w);
        m.setUser(owner);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "OWNER"));
        workspaceMemberRepository.save(m);
        return w;
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
