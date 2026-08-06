package com.hamstrack.common.observability;

import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Single home for all custom business (product) metrics — every custom meter
 * name and label lives here so the metric surface is auditable in one place
 * (see {@code docs/design/observability-proposal.md} §5).
 *
 * <p><b>Cardinality / privacy rule (non-negotiable):</b> no meter here is ever
 * labelled by a userId / workspaceId / issueId / email or any other unbounded
 * or tenant-identifying value — that would explode Prometheus series and leak
 * tenant data into the metrics store. Only bounded enum-like labels are used
 * ({@code outcome}, {@code reason}, {@code kind}, {@code source}, {@code type},
 * {@code phase}).
 *
 * <p>Counter {@code .increment()} is in-memory (no JPA flush), so there is no
 * {@code @Version} double-flush risk — but callers still emit AFTER the final
 * save, at the event site. Counters are not transactional; a rolled-back tx may
 * still have incremented (accepted drift). The gauges below are the source of
 * truth for totals and are evaluated cheaply at scrape time.
 */
@Component
public class ProductMetrics {

    // --- login label enums (bounded) ---

    public enum LoginOutcome {
        SUCCESS("success"), FAILURE("failure");
        final String tag;
        LoginOutcome(String tag) { this.tag = tag; }
    }

    public enum LoginReason {
        OK("ok"),
        BAD_CREDENTIALS("bad_credentials"),
        NOT_VERIFIED("not_verified"),
        DISABLED("disabled");
        final String tag;
        LoginReason(String tag) { this.tag = tag; }
    }

    public enum RateLimitKind {
        IP_WINDOW("ip_window"), LOGIN_BACKOFF("login_backoff");
        final String tag;
        RateLimitKind(String tag) { this.tag = tag; }
    }

    public enum WorkspaceSource {
        USER("user"), ONBOARDING("onboarding"), DEMO("demo");
        final String tag;
        WorkspaceSource(String tag) { this.tag = tag; }
    }

    public enum PasswordResetPhase {
        REQUESTED("requested"), COMPLETED("completed");
        final String tag;
        PasswordResetPhase(String tag) { this.tag = tag; }
    }

    public enum EmailType {
        VERIFICATION("verification"), PASSWORD_RESET("password_reset"), INVITE("invite");
        final String tag;
        EmailType(String tag) { this.tag = tag; }
    }

    public enum EmailOutcome {
        SUCCESS("success"), FAILURE("failure");
        final String tag;
        EmailOutcome(String tag) { this.tag = tag; }
    }

    private final MeterRegistry registry;

    // Label-free counters registered up front (they carry no dynamic tags)
    private final Counter usersRegistered;
    private final Counter emailVerified;
    private final Counter projectsCreated;
    private final Counter invitesSent;
    private final Counter invitesAccepted;
    private final Counter invitesDeclined;
    private final Counter attachmentsUploaded;
    private final DistributionSummary attachmentBytes;

    public ProductMetrics(MeterRegistry registry,
                          UserRepository userRepository,
                          WorkspaceRepository workspaceRepository,
                          ProjectRepository projectRepository,
                          IssueRepository issueRepository) {
        this.registry = registry;

        this.usersRegistered = Counter.builder("hamstrack.users.registered")
                .description("User accounts registered via self-signup").register(registry);
        this.emailVerified = Counter.builder("hamstrack.auth.email_verified")
                .description("Email addresses verified").register(registry);
        this.projectsCreated = Counter.builder("hamstrack.projects.created")
                .description("Projects created").register(registry);
        this.invitesSent = Counter.builder("hamstrack.invites.sent")
                .description("Workspace invites sent").register(registry);
        this.invitesAccepted = Counter.builder("hamstrack.invites.accepted")
                .description("Workspace invites accepted").register(registry);
        this.invitesDeclined = Counter.builder("hamstrack.invites.declined")
                .description("Workspace invites declined").register(registry);
        this.attachmentsUploaded = Counter.builder("hamstrack.attachments.uploaded")
                .description("Attachments uploaded").register(registry);
        this.attachmentBytes = DistributionSummary.builder("hamstrack.attachments.bytes")
                .description("Uploaded attachment sizes in bytes")
                .baseUnit("bytes").register(registry);

        // Gauges — evaluated at scrape time, each a single cheap count query.
        Gauge.builder("hamstrack.users.total", userRepository, r -> r.count())
                .description("Total user accounts").register(registry);
        Gauge.builder("hamstrack.users.active", userRepository,
                        r -> r.countByStatus(UserStatus.ACTIVE))
                .description("Active user accounts").register(registry);
        Gauge.builder("hamstrack.workspaces.total", workspaceRepository, r -> r.count())
                .description("Total workspaces").register(registry);
        Gauge.builder("hamstrack.projects.total", projectRepository, r -> r.count())
                .description("Total projects").register(registry);
        Gauge.builder("hamstrack.issues.total", issueRepository, r -> r.count())
                .description("Total issues").register(registry);
    }

    // --- auth ---

    /** {@code hamstrack.users.registered} — after the user row is saved. */
    public void userRegistered() {
        usersRegistered.increment();
    }

    /** {@code hamstrack.auth.login{outcome,reason}}. */
    public void recordLogin(LoginOutcome outcome, LoginReason reason) {
        registry.counter("hamstrack.auth.login",
                "outcome", outcome.tag, "reason", reason.tag).increment();
    }

    /** {@code hamstrack.auth.email_verified} — after status → ACTIVE. */
    public void emailVerified() {
        emailVerified.increment();
    }

    /** {@code hamstrack.auth.password_reset{phase}}. */
    public void passwordReset(PasswordResetPhase phase) {
        registry.counter("hamstrack.auth.password_reset", "phase", phase.tag).increment();
    }

    // --- rate limiting ---

    /** {@code hamstrack.ratelimit.hit{kind}} — at each RateLimitedException throw. */
    public void rateLimitHit(RateLimitKind kind) {
        registry.counter("hamstrack.ratelimit.hit", "kind", kind.tag).increment();
    }

    // --- workspace / project / issue ---

    /** {@code hamstrack.workspaces.created{source}}. */
    public void workspaceCreated(WorkspaceSource source) {
        registry.counter("hamstrack.workspaces.created", "source", source.tag).increment();
    }

    /** {@code hamstrack.projects.created}. */
    public void projectCreated() {
        projectsCreated.increment();
    }

    /**
     * {@code hamstrack.issues.created{type}} — {@code type} is the issue-type
     * NAME, a bounded catalog (Bug/Task/Story/Epic + admin-defined types).
     */
    public void issueCreated(String typeName) {
        registry.counter("hamstrack.issues.created", "type", typeName).increment();
    }

    // --- invites ---

    public void inviteSent() {
        invitesSent.increment();
    }

    public void inviteAccepted() {
        invitesAccepted.increment();
    }

    public void inviteDeclined() {
        invitesDeclined.increment();
    }

    // --- email ---

    /** {@code hamstrack.email.sent{type,outcome}}. */
    public void emailSent(EmailType type, EmailOutcome outcome) {
        registry.counter("hamstrack.email.sent",
                "type", type.tag, "outcome", outcome.tag).increment();
    }

    // --- attachments ---

    /** {@code hamstrack.attachments.uploaded} + {@code hamstrack.attachments.bytes}. */
    public void attachmentUploaded(long sizeBytes) {
        attachmentsUploaded.increment();
        attachmentBytes.record(sizeBytes);
    }
}
