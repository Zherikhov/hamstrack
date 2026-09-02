package com.hamstrack.common.mail;

import com.hamstrack.auth.dto.ForgotPasswordRequest;
import com.hamstrack.auth.dto.RegisterRequest;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.PasswordResetRepository;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.auth.service.AuthService;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.workspace.dto.InviteMemberRequest;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceInviteRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * <strong>A rollback delivers no mail — at all three call sites, driven through the services that
 * own them</strong> (HD-181).
 *
 * <p>{@code AfterCommitTest} proves the mechanism in the abstract. This file proves it is actually
 * <em>wired</em>, which is a separate claim and the one that decays: the helper stays correct while
 * a call site quietly goes back to calling the mailer inline, and nothing about the helper's own
 * tests would notice. Each of the three mailers therefore gets both directions —
 *
 * <ul>
 *   <li><strong>rolled back:</strong> no send, and no row either (the row's absence is what makes
 *       the send wrong: the recipient would hold a link that resolves to nothing);</li>
 *   <li><strong>committed:</strong> exactly one send, and — the assertion that separates this fix
 *       from the code it replaced — <em>nothing sent while the transaction was still open</em>. The
 *       old code also sent; it sent too early.</li>
 * </ul>
 *
 * <p>The rollback is <strong>forced by a throw from inside the transaction</strong> rather than
 * asserted from reading the call site. That is the shape of every real cause — a constraint
 * violation, a late refusal, a statement cancelled at the bound {@code BoundedJpaTransactionManager}
 * applies — and it is reachable today: {@code register}'s {@code users.email} UNIQUE settles at the
 * commit that follows the publish, and HD-133 shipped the same shape for invites in
 * {@code workspace_invites_pending_email_uk} (V22, partial and over {@code lower(email)}), which a
 * concurrent second invitation to one address really can lose at the flush.
 *
 * <p>{@link MailService} is a Mockito bean here, and deliberately: the question is <em>whether and
 * when the mailer was called</em>, not what it puts on the wire. Mocking it also removes the
 * {@code @Async} hop, so "no mail was sent" is a fact at the point it is asserted rather than a race
 * won by a sleep — and it removes the last hiding place, because a mock records the call even when
 * the real bean would have swallowed a failure. What the real mailer does with a send is
 * {@code MailDurabilityTest}'s subject.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class MailFollowsTheCommitTest {

    @Autowired AuthService authService;
    @Autowired WorkspaceService workspaceService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordResetRepository passwordResetRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired WorkspaceInviteRepository inviteRepository;
    @Autowired RoleCatalog roleCatalog;
    @Autowired TransactionTemplate txTemplate;
    @Autowired DataSource dataSource;

    @MockitoBean MailService mailService;

    // ================================================================ password reset

    @Test
    void aRolledBackPasswordResetSendsNothing() {
        var address = address("reset-rollback");
        var user = user(address);

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            authService.forgotPassword(new ForgotPasswordRequest(address));
            throw new IllegalStateException("a late refusal, as a constraint violation would be");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(passwordResetRepository.findAll().stream()
                .filter(r -> r.getUser().getId().equals(user.getId())).toList())
                .as("premise: the rollback really did take the password_resets row with it. If a "
                    + "row survived, the send below would not have been wrong and this test is "
                    + "proving nothing")
                .isEmpty();
        verifyNoInteractions(mailService);
    }

    @Test
    void aCommittedPasswordResetSendsExactlyOneMailAndOnlyAfterTheCommit() {
        var address = address("reset-commit");
        user(address);

        txTemplate.executeWithoutResult(status -> {
            authService.forgotPassword(new ForgotPasswordRequest(address));
            verifyNoInteractions(mailService);
        });

        verify(mailService, times(1)).sendPasswordResetEmail(eq(address), anyString());
    }

    // ================================================================ email verification

    @Test
    void aRolledBackRegistrationSendsNoVerificationMail() {
        var address = address("register-rollback");

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            authService.register(register(address));
            throw new IllegalStateException("the users.email UNIQUE losing a concurrent race");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(userRepository.existsByEmail(address))
                .as("premise: the rollback really did take the users row with it, so the "
                    + "confirmation link below would name an account that does not exist")
                .isFalse();
        verifyNoInteractions(mailService);
    }

    @Test
    void aCommittedRegistrationSendsExactlyOneVerificationMailAndOnlyAfterTheCommit() {
        var address = address("register-commit");

        txTemplate.executeWithoutResult(status -> {
            authService.register(register(address));
            verifyNoInteractions(mailService);
        });

        verify(mailService, times(1)).sendVerificationEmail(eq(address), anyString());
    }

    // ================================================================ workspace invite

    @Test
    void aRolledBackInviteSendsNothing() {
        var owner = user(address("owner-invite-rollback"));
        var workspace = workspaceOwnedBy(owner);
        var invitee = address("invitee-rollback");

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            workspaceService.inviteMember(owner, workspace.getId(),
                    new InviteMemberRequest(invitee, null, "MEMBER"));
            throw new IllegalStateException(
                    "workspace_invites_pending_email_uk colliding at flush");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(inviteRepository.findAll().stream()
                .filter(i -> invitee.equals(i.getEmail())).toList())
                .as("premise: the rollback really did take the workspace_invites row with it, so "
                    + "the join link in the mail below would answer 404")
                .isEmpty();
        verifyNoInteractions(mailService);
    }

    @Test
    void aCommittedInviteSendsExactlyOneMailAndOnlyAfterTheCommit() {
        var owner = user(address("owner-invite-commit"));
        var workspace = workspaceOwnedBy(owner);
        var invitee = address("invitee-commit");

        txTemplate.executeWithoutResult(status -> {
            workspaceService.inviteMember(owner, workspace.getId(),
                    new InviteMemberRequest(invitee, null, "MEMBER"));
            verifyNoInteractions(mailService);
        });

        verify(mailService, times(1))
                .sendWorkspaceInviteEmail(eq(invitee), eq(workspace.getName()), anyString());
    }

    /**
     * <strong>The send happens outside the per-recipient advisory lock</strong> — the other half of
     * what the ordering buys, and the half {@code RecipientMailThrottle} and
     * {@code WorkspaceService.inviteMember} both now tell their readers to rely on.
     *
     * <p>Asserted against the lock itself, from <em>another session</em>, because the obvious probes
     * do not work here and one of them is a trap.
     * {@code TransactionSynchronizationManager.isActualTransactionActive()} answers <strong>true</strong>
     * inside an {@code afterCommit} effect — the flag is cleared in {@code cleanupAfterCompletion},
     * after every callback — and that indistinguishability is the same one that makes a dead-letter
     * write joining the bound transaction vanish in silence (see {@code FailedEmailWriter}). Nor can
     * the probe go through {@code JdbcTemplate} or the {@code EntityManager}: {@code DataSourceUtils}
     * hands back the transaction-bound connection, advisory locks are re-entrant within a session,
     * and the answer would be "free" no matter who holds it. So it takes a connection straight from
     * the pool, which is exactly what the second tenant inviting the same person is.
     *
     * <p>{@code pg_try_advisory_xact_lock} rather than the blocking form on purpose: the failure to
     * report is "somebody else is holding this", not a test that hangs until the lock timeout.
     *
     * <p>The claim this replaces is that handing the send to the mail pool kept SMTP out of the lock.
     * It never did — the pool was bounded with a caller-runs policy, so a full queue ran the send
     * inline, under exactly the load where a cross-tenant lock hold would hurt most. HD-208 has
     * since removed that branch, which is why this test asserts the ORDERING and not the hand-off:
     * the ordering is a property of the call site and survives the next change to the pool.
     */
    @Test
    void theInviteSendHappensOutsideTheRecipientAdvisoryLock() {
        var owner = user(address("owner-invite-lock"));
        var workspace = workspaceOwnedBy(owner);
        var invitee = address("invitee-lock");
        var freeAtSendTime = new boolean[1];

        doAnswer(invocation -> {
            freeAtSendTime[0] = recipientLockIsFreeInAnotherSession(MailAddresses.throttleKey(invitee));
            return null;
        }).when(mailService).sendWorkspaceInviteEmail(anyString(), anyString(), anyString());

        txTemplate.executeWithoutResult(status ->
                workspaceService.inviteMember(owner, workspace.getId(),
                        new InviteMemberRequest(invitee, null, "MEMBER")));

        verify(mailService, times(1)).sendWorkspaceInviteEmail(eq(invitee), anyString(), anyString());
        assertThat(freeAtSendTime[0])
                .as("another session could not take pg_advisory_xact_lock on this recipient key "
                    + "while the invite mail was being sent, so the send is happening INSIDE the "
                    + "lock RecipientMailThrottle holds to commit. A recipient address is something "
                    + "two tenants legitimately share, so a wait held there is a wait one tenant "
                    + "can impose on another by inviting the same person. Nothing slow may be added "
                    + "between the throttle and the commit; the send must stay registered on "
                    + "AfterCommit. What may NOT be relied on instead is the pool's rejection "
                    + "policy: it has already changed once (HD-208) and this assertion is about "
                    + "the call site, which is where the guarantee has to live.")
                .isTrue();
    }

    /**
     * {@code true} when a connection that is not this thread's transactional one can take the
     * recipient lock — i.e. nobody is holding it. Raw JDBC, so it is genuinely another session:
     * anything routed through Spring would be handed the bound connection and would answer "free"
     * because advisory locks are re-entrant within a session.
     */
    private boolean recipientLockIsFreeInAnotherSession(String recipientKey) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT pg_try_advisory_xact_lock(CAST(hashtext(?) AS bigint))")) {
            statement.setString(1, recipientKey);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    // ================================================================ fixture

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private static RegisterRequest register(String address) {
        // Long, random and unpublished: register() refuses passwords from the breach list.
        return new RegisterRequest(address, "commit-order-" + UUID.randomUUID(),
                "Commit Order", true);
    }

    private User user(String email) {
        var u = new User();
        u.setEmail(email);
        u.setDisplayName("Commit order");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    /** A workspace whose creator is its OWNER, i.e. holds {@code workspace.member.manage}. */
    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("Commit order " + UUID.randomUUID().toString().substring(0, 8));
        w.setSlug("co-" + UUID.randomUUID().toString().substring(0, 12));
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
