package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.JwtService;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>A duplicate refusal costs the caller a unit of their own sending volume</strong> — the
 * half of HD-133's ordering rule that is held by nothing else, and that a refactor would undo
 * without breaking a single other test.
 *
 * <p><strong>The bug this file exists to make impossible again.</strong> HD-190 shipped
 * {@code InviteThrottle.require}, one method that spent the sender budget and then the recipient
 * ceilings. HD-133's first build put its duplicate check <em>above</em> that call, obeying the rule
 * written in three places — <em>every new refusal on this path goes above the throttle, because the
 * recipient half RECORDS a row a rollback would unwrite</em>. The consequence was invisible in the
 * diff: a repeat POST to a pending address then spent <em>no budget of either kind</em>, and
 * {@code POST /api/workspaces/{id}/invites} carries no {@code PrincipalThrottleInterceptor} to
 * charge the caller instead, so an account holding {@code workspace.member.manage} could loop cheap
 * 409s indefinitely.
 *
 * <p>The generalisation is the part worth keeping: under "every refusal goes above the throttle",
 * <em>each new refusal this path acquires makes one more response free</em>. The fix was to notice
 * that the two halves obey opposite rules — the sender budget is an in-memory counter (ADR-0015)
 * that no rollback returns, so it is spent ABOVE every refusal; the recipient half writes a row in
 * this transaction, so it goes BELOW them all — and to split {@code require} into
 * {@code requireSenderVolume} and {@code requireRecipientCeilings} so both can be true at once.
 *
 * <p><strong>Recombining them passes the whole suite except this file.</strong> That is why it is a
 * file: the split is otherwise held only by comments, and a comment does not fail a build. The
 * ceiling is lowered to three an hour so the arithmetic fits in one screen; at the shipped default
 * of twenty the same test needs twenty-one requests and reads like a load test.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.invites.max-per-sender-per-hour=3",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class InviteDuplicateCostsTheSenderTest {

    /** {@code app.invites.max-per-sender-per-hour}, as overridden above. */
    private static final int HOURLY = 3;

    /** No SMTP in CI, and a real attempt is a five-second connect timeout per invitation. */
    @MockitoBean JavaMailSender mailSender;

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired TransactionTemplate transactions;

    @PersistenceContext EntityManager em;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * <strong>One accepted invitation and two refused ones spend three units, and the fourth
     * request pays for it</strong> — with a <em>fresh</em> address, so the 429 can only have come
     * from the sender's own volume.
     *
     * <p>The last step is the load-bearing one. {@code bob} has never been invited by anyone, so
     * neither the (sender, recipient) cooldown nor the recipient's daily cap has anything to say
     * about him; the only ceiling left that can refuse is the caller's hourly volume, and it can
     * only be full if the two 409s each cost a unit. With {@code requireSenderVolume} moved back
     * below the duplicate check, the counter reads 1 at this point and this request is a 201.
     */
    @Test
    void everyDuplicateRefusalSpendsAUnitOfTheCallersOwnHourlyVolume() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var alice = address("alice");

        invite(admin, workspace, alice).andExpect(status().isCreated());
        for (int i = 2; i <= HOURLY; i++) {
            var repeat = invite(admin, workspace, alice).andExpect(status().isConflict());
            assertThat(errorTypeOf(repeat))
                    .as("request %d must be the duplicate refusal — if it were anything else this "
                        + "test would be counting the wrong thing", i)
                    .isEqualTo("DUPLICATE_INVITE");
        }

        var fresh = address("bob");
        var refusal = invite(admin, workspace, fresh)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        assertThat(detailOf(refusal))
                .as("""
                        THE ADDRESS IS FRESH, SO THIS 429 HAS EXACTLY ONE POSSIBLE SOURCE. Nobody \
                        has ever mailed it: the (sender, recipient) cooldown sees no earlier send \
                        and the recipient's daily cap is empty. Only the caller's own hourly \
                        volume can refuse — and it is full only because the two duplicate 409s \
                        above each spent a unit. Asserting the wording as well as the status is \
                        what stops a future ceiling from quietly satisfying this test for a \
                        different reason.""")
                .contains(String.valueOf(HOURLY))
                .contains("Invitation limit reached");
        assertThat(inviteRowsFor(fresh))
                .as("and it really was refused: a 429 leaves no invitation behind")
                .isZero();
    }

    /**
     * <strong>Over the volume ceiling AND re-inviting a pending address answers 429, not 409</strong>
     * — the same split seen from the other side, and a disclosure property in its own right.
     *
     * <p>Correct in that order for two reasons. The volume ceiling is a statement about the
     * <em>caller</em> and holds whatever the address turns out to be, so it does not need to look at
     * one; and answering it first <strong>declines to say whether that address is already
     * invited</strong>. A caller who has run out of budget learns nothing about the workspace's
     * pending list until they are inside their allowance again.
     *
     * <p>Move {@code requireSenderVolume} below the duplicate check and this line reads 409 — the
     * same inversion the sibling test catches by its cost, caught here by what it says.
     */
    @Test
    void aCallerOutOfVolumeIsToldAboutTheirOwnBudgetAndNotAboutThePendingRow() throws Exception {
        var admin = user();
        var workspace = workspaceOwnedBy(admin);
        var pending = address("pending");

        invite(admin, workspace, pending).andExpect(status().isCreated());
        for (int i = 2; i <= HOURLY; i++) {
            invite(admin, workspace, address("filler-" + i)).andExpect(status().isCreated());
        }

        var refusal = invite(admin, workspace, pending)
                .andExpect(status().isTooManyRequests());

        assertThat(detailOf(refusal))
                .as("the caller is over their hourly volume and re-inviting a pending address at "
                    + "the same time. The volume ceiling answers first, so the refusal describes "
                    + "the caller's own budget and says nothing about the row")
                .contains("Invitation limit reached")
                .doesNotContain("withdraw")
                .doesNotContain("waiting in this workspace");
        assertThat(errorTypeOf(refusal))
                .as("and it is not the duplicate refusal wearing a different status")
                .isNotEqualTo("DUPLICATE_INVITE");
    }

    // ================================================================ fixture

    private ResultActions invite(User sender, Workspace workspace, String email) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + workspace.getId() + "/invites")
                .header("Authorization", "Bearer " + jwtService.generateAccessToken(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"MEMBER\"}"));
    }

    private String detailOf(ResultActions actions) throws Exception {
        var node = json.readTree(actions.andReturn().getResponse().getContentAsString());
        return node.hasNonNull("detail") ? node.get("detail").asText() : "";
    }

    private String errorTypeOf(ResultActions actions) throws Exception {
        var node = json.readTree(actions.andReturn().getResponse().getContentAsString());
        return node.hasNonNull("errorType") ? node.get("errorType").asText() : null;
    }

    private long inviteRowsFor(String email) {
        return transactions.execute(status -> em.createQuery(
                        "SELECT count(i) FROM WorkspaceInvite i WHERE lower(i.email) = lower(:email)",
                        Long.class)
                .setParameter("email", email).getSingleResult());
    }

    /** Unique per test run, so tests never share a recipient key or a {@code users} row. */
    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private User user() {
        var u = new User();
        u.setEmail(address("user"));
        u.setDisplayName("Invite volume");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    /** A workspace whose creator is its OWNER, i.e. holds {@code workspace.member.manage}. */
    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("Invite volume " + UUID.randomUUID().toString().substring(0, 8));
        w.setSlug("iv-" + UUID.randomUUID().toString().substring(0, 12));
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
