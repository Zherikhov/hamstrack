package com.hamstrack.common.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.common.tx.AfterCommit;
import com.hamstrack.workspace.dto.InviteMemberRequest;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceInviteRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * <strong>A mail dispatch that arrives after {@code mailExecutor.shutdown()} is refused loudly, and
 * the refusal reaches somebody</strong> (HD-181).
 *
 * <h2>What changed and why it needed to</h2>
 * The pool's rejection handler is {@code CallerRunsPolicy}'s two branches with the silent one
 * replaced. While the pool runs, a full queue means backpressure — the send runs on the calling
 * thread. Once it is shut down, {@code CallerRunsPolicy} <em>discards the task and says nothing</em>,
 * and that mattered less when a lost dispatch and a rolled-back transaction failed together. Now
 * the row is committed and the user has already been told the invitation was sent, so the drop has
 * to leave a trace. The handler therefore throws {@link RejectedExecutionException}, which
 * {@code ThreadPoolTaskExecutor} wraps as {@link TaskRejectedException} on the calling thread.
 *
 * <p>The narrow window this covers is real: a request still in flight during Tomcat's drain
 * dispatches a send after {@code shutdown()} has been called. It is <strong>not</strong> the larger
 * shutdown loss — the queue's remaining contents abandoned when {@code awaitTerminationSeconds}
 * expires happens where no handler is invoked at all, and nothing here helps with it.
 *
 * <h2>The two halves, and why the second is the one that matters</h2>
 * A throw is only better than a discard if it reaches a reader. So this file asserts the throw
 * (below) and then asserts what a <em>real call site</em> does with it: the send is dispatched from
 * an {@link AfterCommit} effect, so the exception is swallowed there — the transaction is already
 * committed and a 500 for committed work would be a lie — and turned into the one ERROR line that
 * is the entire record of the loss. That line carries the description the call site supplied, which
 * is where "names the mail kind and the recipient domain" is decided, and it is asserted to carry
 * the <strong>domain and not the address</strong>: this log is shipped and kept, and the local part
 * is what makes an address personal data.
 *
 * <p>{@code @DirtiesContext} because shutting the pool down is irreversible.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@DirtiesContext
class MailExecutorShutdownRefusalTest {

    @Autowired MailService mailService;
    @Autowired WorkspaceService workspaceService;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired WorkspaceInviteRepository inviteRepository;
    @Autowired RoleCatalog roleCatalog;
    @Autowired TransactionTemplate txTemplate;

    @Autowired @Qualifier("mailExecutor") TaskExecutor mailExecutor;

    /** Nothing may reach SMTP here; if anything does, the pool was not actually shut down. */
    @MockitoBean JavaMailSender mailSender;

    private final ListAppender<ILoggingEvent> afterCommitLog = new ListAppender<>();

    @BeforeEach
    void captureAfterCommitLog() {
        afterCommitLog.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AfterCommit.class))
                .addAppender(afterCommitLog);
    }

    @AfterEach
    void releaseAfterCommitLog() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AfterCommit.class))
                .detachAppender(afterCommitLog);
        afterCommitLog.stop();
    }

    // ================================================================ the throw

    /**
     * The handler's shutdown branch, at the dispatch. {@code CallerRunsPolicy} would return
     * normally here and drop the message; the caller would have no way to know, and neither would
     * anybody reading logs afterwards, because a handler sees a {@code Runnable} and knows neither
     * the recipient nor the kind of mail.
     */
    @Test
    void aDispatchAfterShutdownIsRefusedOnTheCallingThread() {
        shutDownMailExecutor();

        assertThatThrownBy(() -> mailService.sendWorkspaceInviteEmail(
                "shutdown-probe@example.test", "Acme", "token-" + UUID.randomUUID()))
                .as("a dispatch to a shut-down pool must surface to its caller. Discarding it — "
                    + "which is what CallerRunsPolicy does in this branch — loses an email whose "
                    + "database row is already committed and whose user has already been told it "
                    + "was sent, and loses it with nothing written down anywhere")
                .isInstanceOf(TaskRejectedException.class)
                .rootCause()
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("will never be sent");

        verifyNoInteractions(mailSender);
    }

    // ================================================================ and where it lands

    /**
     * <strong>The half that makes the throw worth having.</strong> Driven through the real invite
     * path, so the description in the log line is the one {@code WorkspaceService} actually writes.
     *
     * <p>Three things are asserted together because each is wrong without the others: the caller is
     * <em>not</em> failed (the workspace_invites row committed, and reporting a 500 for committed
     * work would be a lie plus collateral — every synchronization behind it would be skipped), the
     * loss <em>is</em> recorded at ERROR, and the record names the mail kind and the recipient's
     * domain <em>without</em> the address.
     */
    @Test
    void insideAnAfterCommitEffectTheRefusalBecomesTheErrorLineThatIsTheWholeRecord() {
        var owner = user(address("owner-shutdown"));
        var workspace = workspaceOwnedBy(owner);
        var localPart = "invitee-" + UUID.randomUUID().toString().substring(0, 12);
        var invitee = localPart + "@example.test";

        shutDownMailExecutor();

        assertThatCode(() -> txTemplate.executeWithoutResult(status ->
                workspaceService.inviteMember(owner, workspace.getId(),
                        new InviteMemberRequest(invitee, null, "MEMBER"))))
                .as("the invitation row committed, so the caller must get its 201. A dispatch "
                    + "failure escaping commit() would report committed work as a 500 AND skip "
                    + "every synchronization queued behind it")
                .doesNotThrowAnyException();

        assertThat(inviteRepository.findAll().stream().filter(i -> invitee.equals(i.getEmail())).toList())
                .as("premise: the invite really did commit, so the mail really was owed")
                .hasSize(1);

        var errors = afterCommitLog.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(errors)
                .as("the email is gone and this line is the ENTIRE record of it — nobody can retry "
                    + "from a metric and nothing else knows. If the rejection handler goes back to "
                    + "discarding, or AfterCommit stops logging, the loss becomes invisible")
                .hasSize(1);
        assertThat(errors.getFirst())
                .as("the line has to say WHICH mail was lost and to WHOM, or an operator reading "
                    + "it can do nothing with it. The workspace id is what takes them to the row "
                    + "that holds the address")
                .contains("workspace-invite email")
                .contains("example.test")
                .contains(workspace.getId().toString())
                .contains("COMMITTED");
        assertThat(errors.getFirst())
                .as("and it must NOT carry the address. This description is written verbatim into "
                    + "a log that is shipped and kept for as long as logs are kept, and the local "
                    + "part is what makes an address personal data — the same rule "
                    + "RecipientMailThrottle applies to its own send line. Nothing is lost by "
                    + "leaving it out: workspace_invites holds the address, and the workspace id "
                    + "above is the way to it.")
                .doesNotContain(localPart)
                .doesNotContain(invitee);
    }

    // ================================================================ fixture

    private void shutDownMailExecutor() {
        ((ThreadPoolTaskExecutor) mailExecutor).shutdown();
    }

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private User user(String email) {
        var u = new User();
        u.setEmail(email);
        u.setDisplayName("Shutdown refusal");
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    /** A workspace whose creator is its OWNER, i.e. holds {@code workspace.member.manage}. */
    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("Shutdown refusal " + UUID.randomUUID().toString().substring(0, 8));
        w.setSlug("sr-" + UUID.randomUUID().toString().substring(0, 12));
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
