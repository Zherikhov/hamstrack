package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceInvite;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceInviteRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HD-120 - <strong>an invitation is bound to one address, and "one address" means one string
 * of code points.</strong>
 *
 * <p>{@code acceptInvite} and {@code declineInvite} compared the invited address to the
 * caller's with {@code equalsIgnoreCase}, which does not fold the way anything else in this
 * system folds. It compares character by character through
 * {@code Character.toUpperCase}/{@code toLowerCase}, and those collapse dotless i
 * ({@code U+0131}), dotted capital I ({@code U+0130}), long s ({@code U+017F}) and the Kelvin
 * sign ({@code U+212A}) onto plain ASCII {@code i}, {@code s} and {@code k}. Nothing else
 * agrees: {@code toLowerCase(Locale.ROOT)} leaves every one of them alone, so does the UNIQUE
 * index on {@code users.email}, and so does Postgres {@code lower()}. Two addresses the
 * database calls two different accounts were therefore one address to this guard - and the
 * guard's whole job is to stop a leaked or forwarded invitation being redeemed by somebody it
 * was not addressed to, at a role it was never offered to them at.
 *
 * <p><strong>Why exact comparison loses nothing.</strong> Both operands are canonicalised
 * before they are ever stored - {@code inviteMember} folds with {@code Locale.ROOT}, and so
 * does every path that writes a {@code users} row - so no legitimate invitee has an address
 * differing from the stored one only by case. Case-insensitivity here could only ever
 * <em>add</em> matches to a comparison that was already complete, which is why the fix is a
 * strict narrowing and why {@code theInvitedAddressItselfStillRedeemsIt} sits next to the
 * refusals: a tightening is only correct if it is provably empty on the legitimate side.
 *
 * <p>The generalisable rule, since this will not be the last comparison of its kind:
 * <em>fold once, at the boundary, and compare exactly ever after.</em> A second, differently
 * spelled fold at the point of comparison is not tolerance for how a human typed something -
 * it is a second spelling of somebody else's identifier.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class InviteEmailBindingTest {

    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired WorkspaceInviteRepository inviteRepository;
    @Autowired WorkspaceService workspaceService;
    @Autowired RoleCatalog roleCatalog;

    @PersistenceContext EntityManager em;

    /**
     * The premise, asserted rather than assumed. If a future JDK ever changes how
     * {@code equalsIgnoreCase} treats these code points, this fails first and says so - which
     * is better than the two behavioural tests below quietly passing while proving nothing.
     */
    @Test
    void theTwoSpellingsAreOneStringToIgnoreCaseAndTwoToEverythingElse() {
        var invited = "ıvan@example.com";   // U+0131 LATIN SMALL LETTER DOTLESS I
        var other = "ivan@example.com";

        assertThat(invited.equalsIgnoreCase(other))
                .as("equalsIgnoreCase is what made these one address; if it no longer does, "
                        + "the tests below have stopped exercising the defect")
                .isTrue();
        assertThat(invited)
                .as("Locale.ROOT folding - the fold that decides which users row exists - "
                        + "keeps them apart, which is why they are two accounts")
                .isNotEqualTo(other);
        assertThat(invited.toLowerCase(Locale.ROOT))
                .isNotEqualTo(other.toLowerCase(Locale.ROOT));
    }

    /**
     * The finding itself: an account differing from the invited address only by a Unicode
     * confusable is a <em>different account</em>, and must get the same indistinguishable 404
     * as any other stranger holding the link - on accept and on decline alike. Decline is
     * included because it is destructive: letting the wrong account reach it hands a stranger
     * the power to cancel someone else's invitation.
     */
    @Test
    @Transactional
    void aConfusableSpellingOfTheInvitedAddressCannotRedeemOrCancelTheInvite() {
        var suffix = UUID.randomUUID().toString().substring(0, 12);
        var invitedAddress = "ıvan-" + suffix + "@example.com";
        var confusableAddress = "ivan-" + suffix + "@example.com";

        var ws = workspace();
        var invite = invite(ws, invitedAddress);
        var impostor = user(confusableAddress);

        // The description goes in the assertThatThrownBy overload, NOT in a chained .as():
        // a chained description is attached to the assertion object that assertThatThrownBy
        // returns, which never exists when the callable does not throw - so on the one
        // outcome this test is here to catch, AssertJ printed a bare "Expecting code to
        // raise a throwable" and none of the addresses. Verified by watching it happen.
        assertThatThrownBy(() -> workspaceService.acceptInvite(impostor, invite.getId()),
                "an invitation addressed to %s was redeemed by the different account %s",
                invitedAddress, confusableAddress)
                .isInstanceOf(WorkspaceNotFoundException.class);


        assertThatThrownBy(() -> workspaceService.declineInvite(impostor, invite.getId()),
                "%s cancelled an invitation addressed to %s", confusableAddress, invitedAddress)
                .isInstanceOf(WorkspaceNotFoundException.class);


        em.flush();
        em.clear();
        assertThat(workspaceMemberRepository.findByWorkspaceAndUser(ws, impostor))
                .as("no membership row may exist for an account the invite was not addressed to")
                .isEmpty();
        assertThat(inviteRepository.findById(invite.getId()))
                .as("the refused decline must leave the invitation standing for its real recipient")
                .isPresent();
    }

    /**
     * The other half of the narrowing, and the reason it is safe: the address the invitation
     * actually names still redeems it, confusable characters and all. Without this, "tighten
     * the compare" and "break invitations" would look identical from the test suite.
     */
    @Test
    @Transactional
    void theInvitedAddressItselfStillRedeemsIt() {
        var invitedAddress = "ıvan-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";

        var ws = workspace();
        var invite = invite(ws, invitedAddress);
        var invitee = user(invitedAddress);

        var joined = workspaceService.acceptInvite(invitee, invite.getId());

        assertThat(joined.id()).isEqualTo(ws.getId());
        em.flush();
        em.clear();
        assertThat(workspaceMemberRepository.findByWorkspaceAndUser(ws, invitee))
                .as("the invited address must still be able to join")
                .isPresent();
    }

    // ------------------------------------------------------------------ fixture

    private Workspace workspace() {
        var w = new Workspace();
        w.setName("Invite binding");
        w.setSlug("invite-binding-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(user("owner-" + UUID.randomUUID() + "@example.com"));
        return workspaceRepository.save(w);
    }

    /**
     * A live, unaccepted invitation to {@code email}, written through the
     * {@link EntityManager} rather than {@code POST /invites} because the exact bytes of the
     * address are the point here: going through the endpoint would re-fold it on the way in
     * and hide what is actually stored.
     */
    private WorkspaceInvite invite(Workspace ws, String email) {
        var invite = new WorkspaceInvite();
        invite.setWorkspace(ws);
        invite.setEmail(email);
        invite.setRole(roleCatalog.reference(RoleScope.WORKSPACE, "MEMBER"));
        invite.setTokenHash(UUID.randomUUID().toString().replace("-", ""));
        invite.setInvitedBy(ws.getCreatedBy());
        invite.setExpiresAt(Instant.now().plusSeconds(3600));
        em.persist(invite);
        em.flush();
        return invite;
    }

    private User user(String email) {
        var u = new User();
        u.setEmail(email);
        u.setDisplayName("Invite binding");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }
}
