package com.hamstrack.common.observability;

import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.repository.WorkspaceStorageUsageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

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
        IP_WINDOW("ip_window"),
        LOGIN_BACKOFF("login_backoff"),
        // Per-project cooldown on whole-project rank rebalances (IssueRankService).
        RANK_REBALANCE("rank_rebalance"),
        // Per-principal budget across the whole reports surface (ReportRateLimiter). The
        // one alert worth building on it: a report is O(project history), so a sustained
        // rate here is either an abusive client or a UI that lost its query cache.
        REPORT_REQUESTS("report_requests"),
        // Per-principal budget across the whole HQL search surface (SearchRateLimiter). Separate
        // from REPORT_REQUESTS because the two are sized for different behaviour — a sustained
        // rate here is a client that lost its debounce, or somebody replaying a 50-predicate
        // query, and telling those apart from report load matters for the alert.
        SEARCH_REQUESTS("search_requests"),
        // ---- The invitation ceilings (HD-190). Three constants rather than one, because they
        // mean three different things to whoever reads the alert, and the kind label is the only
        // thing distinguishing them: no meter here may carry an address or an id.
        //
        // NOTE FOR WHOEVER BUILDS AN ALERT ON THESE: a rule built on refusals can only see an
        // attacker who HITS a ceiling. One who reads the numbers and stays beneath them trips
        // nothing here and is invisible. The valuable rules watch mail VOLUME and the invitation
        // ACCEPTANCE RATIO (real invitations get accepted; spam does not) — see
        // observability/grafana/provisioning/alerting/rules.yml.
        //
        // Somebody is sending a lot — bulk / quota shape (InviteSenderVolumeBudget).
        INVITE_SENDER_VOLUME("invite_sender_volume"),
        // Somebody is pressing invite at ONE address — harassment shape (RecipientMailThrottle).
        INVITE_RECIPIENT_COOLDOWN("invite_recipient_cooldown"),
        // One address has taken all it may take in a day — either from several accounts
        // (coordinated harassment: the cap counts each other sender once, so reaching it that way
        // costs a mailbox per slot, which makes this the sharpest of the three) or from one
        // account spending its own share across the day. The kind alone does not say which; the
        // domain-only INFO line and mail_send_events do.
        INVITE_RECIPIENT_DAILY("invite_recipient_daily"),
        // ---- The anonymous auth mailers (HD-202). Same mechanism, same table, separate budgets:
        // one address may receive the reset cap AND the verification cap, because a shared bucket
        // would let a stranger's traffic suppress the one piece of mail the victim asked for.
        //
        // THESE FIRE ON REFUSALS THE CALLER NEVER SEES. Both endpoints answer one uniform sentence
        // whether the address exists, whether mail went out, and whether a ceiling refused — so a
        // metric is not merely the best signal here, it is the ONLY one. A rate on either _COOLDOWN
        // is somebody pressing "send me a link" at one address; a rate on either _WINDOW is that
        // sustained past the hourly cap, which is both a mail bomb aimed at a person and, from that
        // person's side, an hour in which they cannot recover their own account.
        PASSWORD_RESET_RECIPIENT_COOLDOWN("password_reset_recipient_cooldown"),
        PASSWORD_RESET_RECIPIENT_WINDOW("password_reset_recipient_window"),
        VERIFICATION_RECIPIENT_COOLDOWN("verification_recipient_cooldown"),
        VERIFICATION_RECIPIENT_WINDOW("verification_recipient_window"),
        // POST /api/auth/register holds its OWN pair, because a bucket shared with
        // resend-verification is a bucket a stranger can fill through an endpoint that sends no
        // mail at all: resend records unconditionally (it must, or the row itself answers whether
        // the account exists), so filling it at an address with no PENDING account denied signup
        // for that whole inbox for free and in silence. Unlike its siblings these two are NOT
        // silent - register answers 429 - so they are a corroborating signal rather than the only
        // evidence; a rate on them is somebody walking spellings of one inbox through signup.
        REGISTRATION_VERIFICATION_RECIPIENT_COOLDOWN("registration_verification_recipient_cooldown"),
        REGISTRATION_VERIFICATION_RECIPIENT_WINDOW("registration_verification_recipient_window"),
        // ---- The write surface (HD-191). Two constants rather than one, because they mean
        // different things to whoever reads the alert: a rate on the first is a client that lost
        // its debounce or a script driving the issue API; a rate on the second is somebody moving
        // VOLUME, which is the shape that costs money on Cloud. Sharing a tag would make the two
        // indistinguishable at exactly the moment the difference decides what to do.
        //
        // Neither of these is the storage quota. A refusal here says a caller went too fast and
        // will be served again next minute; hamstrack.storage.quota_refused says a TENANT is
        // stuck until somebody frees space or raises a number.
        WRITE_REQUESTS("write_requests"),
        UPLOAD_BYTES("upload_bytes");
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

    /**
     * Where a {@code role_id} that failed the scope/ownership assertion was read from —
     * the table, not the tenant. Bounded and enum-like, per the cardinality rule above:
     * a free-form {@code String} label here would let a future call site put an id into
     * Prometheus.
     */
    public enum RoleScopeViolationSource {
        WORKSPACE_MEMBERS("workspace_members"),
        PROJECT_MEMBERS("project_members"),
        /**
         * A pending invitation's {@code role_id} (HD-127 round-3 review). Its own constant
         * because the invite path used to write the id straight onto the membership: the
         * ERROR line then named {@code workspace_members}, sending an operator to the table
         * whose row is <em>correct</em> while the wrong one sat in {@code workspace_invites}.
         */
        WORKSPACE_INVITES("workspace_invites"),
        DEFAULT_PROJECT_ROLE("default_project_role");
        final String tag;
        RoleScopeViolationSource(String tag) { this.tag = tag; }

        /** The table the id came from — also what the ERROR log names. */
        public String tag() { return tag; }
    }

    /**
     * The kinds of outbound mail, and — since HD-202 — the {@code mail_send_events.email_type}
     * budget keys, which is why the two are not quite the same list.
     *
     * <p>{@link #REGISTRATION_VERIFICATION} names a BUDGET and no mailer emits it: the message
     * {@code POST /api/auth/register} eventually sends is ordinary verification mail and is
     * metered as {@link #VERIFICATION}. Separating the budget is what stops one endpoint denying
     * the other; separating the meter would only split a volume series nobody reads per endpoint.
     * {@code MailThrottleCoverageTest} seals mailer-to-type in that direction only (every mailer
     * declares exactly one type), so a budget-only constant is legitimate here.
     *
     * <p><strong>A budget-only constant still has to be PLACED on every fork that switches on this
     * enum</strong>, and {@code MailService.isCritical} is one: emitting nothing today means the
     * placement is untested rather than unnecessary, and the day a mailer does emit it the
     * else-branch would silently make it log-only. {@code MailCriticalityCoverageTest} fails on a
     * constant neither side names.
     */
    public enum EmailType {
        VERIFICATION("verification"),
        /**
         * The budget {@code POST /api/auth/register} spends. Its own bucket, not a share of
         * {@link #VERIFICATION}: while they shared one, five {@code resend-verification} requests
         * an hour at an address with no account — which send nothing and log nothing above
         * DEBUG — denied registration for every spelling of that inbox, indefinitely and for free.
         * The cost of the split is a third bucket in the per-inbox bound — twice the per-window
         * verification cap on the hour-wide budgets, and see {@code AuthMailProperties} for the
         * whole sum, which is a sum of rates rather than a multiple of the cap. Refusal shape:
         * {@code RecipientMailThrottle.requireAndRecordWhereEndpointDiscloses}.
         */
        REGISTRATION_VERIFICATION("registration_verification"),
        PASSWORD_RESET("password_reset"),
        INVITE("invite");
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
    private final Counter invitesRevoked;
    private final Counter attachmentsUploaded;
    private final DistributionSummary attachmentBytes;

    /**
     * Backing value of {@code hamstrack.mail.anonymous_recipient_max}. Refreshed on a schedule by
     * {@code AnonymousMailConcentration} rather than evaluated at scrape time: the query behind it
     * is a {@code GROUP BY} over hours of rows, and a scrape is not the place for one.
     */
    private final AtomicLong anonymousMailConcentration = new AtomicLong();

    /**
     * When {@link #anonymousMailConcentration} was last <em>successfully</em> refreshed, as epoch
     * seconds — <strong>seeded with the moment this bean was built, not with zero</strong>.
     *
     * <p>It exists because the gauge above is last-write-wins and therefore <strong>fails
     * silent</strong>: a query that starts erroring or timing out leaves it frozen at its last
     * value, which is almost certainly a quiet one, and a frozen number reads exactly like a calm
     * instance. Worse, the population that makes that query slow is the attack the gauge exists to
     * see. So the freshness is published beside the value and
     * {@code MailConcentrationGaugeStale} alerts on it.
     *
     * <p><strong>Seeded at boot because the state that mattered most was the one the alert could
     * not reach</strong> (HD-202 final review). This used to start at {@code 0} and the gauge
     * mapped {@code 0} to {@code -1}, so "has never refreshed successfully, not once" published a
     * value BELOW its own {@code > 1800} threshold — permanently. On an instance where the refresh
     * never succeeds (V25 not applied on an upgrade, a persistent timeout, a permissions problem)
     * that is the whole alerting story: {@code MailConcentrationGaugeStale} never fires because
     * {@code -1} is not stale, {@code MailRecipientConcentration} never fires because the value is
     * still {@code 0}, both series <em>exist</em> so {@code NoData}/{@code Error} do not help, and
     * no rule in that file is Loki-backed so the WARN from
     * {@code AnonymousMailConcentration.refresh} alerts nobody. The sole witness and the witness
     * watching it were both silent for ever, in the one state where an operator most needs to hear
     * from them.
     *
     * <p>Seeding it with boot time makes the never-succeeded case indistinguishable from the
     * stopped-succeeding case, which is correct — they have the same remedy — and it costs
     * nothing at startup: the schedule's first run is fifteen seconds in and the rule needs
     * 1800 s over a {@code for: 10m}, which tolerates a deploy six times over.
     */
    private final AtomicLong anonymousMailConcentrationRefreshedAt =
            new AtomicLong(Instant.now().getEpochSecond());

    /** {@code hamstrack.storage.quota_refused} — see {@link #storageQuotaRefused()}. */
    private final Counter storageQuotaRefused;

    /**
     * Backing value of {@code hamstrack.storage.quota_fill_max} — the fullest workspace on the
     * instance as a 0..1 fraction of the configured quota. Refreshed by
     * {@code WorkspaceStorageReconciler} rather than evaluated at scrape, because it is a
     * {@code MAX} over the counter table and the fill number is a trend rather than a tick.
     */
    private final AtomicLong storageQuotaFillMaxPermille = new AtomicLong();

    /**
     * Backing value of {@code hamstrack.storage.drift_bytes} — the largest absolute
     * counter-versus-rows delta the last reconcile pass found.
     */
    private final AtomicLong storageDriftBytes = new AtomicLong();

    /**
     * When the two gauges above were last <em>successfully</em> refreshed — <strong>seeded with
     * process start, not with zero, and with no sentinel branch.</strong>
     *
     * <p>Verbatim the trap {@link #anonymousMailConcentrationRefreshedAt} documents, and the
     * reason it is repeated rather than assumed: both gauges above are last-write-wins, so a
     * reconciler that stops running (a bad cron, a permissions problem, an
     * {@code app.storage.quota.reconcile-cron} deliberately emptied) leaves them FROZEN at their
     * last value — almost certainly a calm one — and a frozen number reads exactly like a healthy
     * instance. A reconciler that has never succeeded is the worse half of that, and seeding this
     * at zero would publish "never once refreshed" as an age of ~56 years, which sounds alertable
     * until you notice the opposite mistake is the easy one: any sentinel BELOW the threshold
     * makes the worst state the silent one. Process start makes never-ran and stopped-running
     * indistinguishable, which is correct — they have the same remedy — and
     * {@code StorageDriftGaugeStale} is the rule that reads it.
     */
    private final AtomicLong storageDriftRefreshedAt = new AtomicLong(Instant.now().getEpochSecond());

    public ProductMetrics(MeterRegistry registry,
                          UserRepository userRepository,
                          WorkspaceRepository workspaceRepository,
                          ProjectRepository projectRepository,
                          IssueRepository issueRepository,
                          WorkspaceStorageUsageRepository storageUsageRepository) {
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
        this.invitesRevoked = Counter.builder("hamstrack.invites.revoked")
                .description("Workspace invites withdrawn by an administrator").register(registry);
        this.attachmentsUploaded = Counter.builder("hamstrack.attachments.uploaded")
                .description("Attachments uploaded").register(registry);
        this.attachmentBytes = DistributionSummary.builder("hamstrack.attachments.bytes")
                .description("Uploaded attachment sizes in bytes")
                .baseUnit("bytes").register(registry);

        // The one meter in this file that is neither a counter at an event site nor a count at
        // scrape time. See anonymousMailConcentration(long) for what it measures and why it is the
        // only thing that can see the attack it was built for.
        Gauge.builder("hamstrack.mail.anonymous_recipient_max", anonymousMailConcentration,
                        AtomicLong::get)
                .description("Anonymous auth mail sent to the single busiest recipient key, last 6h")
                .register(registry);

        // The freshness of the line above, and the only thing that can tell a quiet instance from a
        // broken refresh. Evaluated at scrape (it is arithmetic on a long, not a query) so it keeps
        // rising while the refresh is failing, instead of freezing alongside the value it describes.
        //
        // NO SENTINEL BRANCH. Before the first successful run this reads the age of the PROCESS,
        // because the field is seeded at construction — see its javadoc. The -1 that used to stand
        // here was the one hole in the whole arrangement: it put "never refreshed, not once"
        // permanently below the rule's own threshold, so the failure that matters most produced no
        // alert from either gauge.
        Gauge.builder("hamstrack.mail.anonymous_recipient_max_age_seconds",
                        anonymousMailConcentrationRefreshedAt,
                        at -> (double) (Instant.now().getEpochSecond() - at.get()))
                .description("Seconds since hamstrack.mail.anonymous_recipient_max was last "
                             + "successfully refreshed; counts from process start until the first "
                             + "successful refresh, so a refresh that never succeeds still ages")
                .baseUnit("seconds")
                .register(registry);

        // Gauges — evaluated at scrape time, each a single cheap count query.
        Gauge.builder("hamstrack.users.total", userRepository, UserRepository::count)
                .description("Total user accounts").register(registry);
        Gauge.builder("hamstrack.users.active", userRepository,
                        r -> r.countByStatus(UserStatus.ACTIVE))
                .description("Active user accounts").register(registry);
        Gauge.builder("hamstrack.workspaces.total", workspaceRepository, WorkspaceRepository::count)
                .description("Total workspaces").register(registry);
        Gauge.builder("hamstrack.projects.total", projectRepository, ProjectRepository::count)
                .description("Total projects").register(registry);
        Gauge.builder("hamstrack.issues.total", issueRepository, IssueRepository::count)
                .description("Total issues").register(registry);

        // --- storage quota (HD-191) ---

        // THE ONLY SIGNAL THAT A TENANT IS STUCK. A quota refusal is a clean 409, so it appears
        // in no error rate, no 5xx panel and no log an operator watches; without this counter the
        // first anybody hears of a full workspace is a support message.
        this.storageQuotaRefused = Counter.builder("hamstrack.storage.quota_refused")
                .description("Attachment uploads refused because the workspace storage quota was full")
                .register(registry);

        // Scrape-time: one row per workspace, so this is a cheap SUM over a tiny table — the same
        // class of query as the counts above, unlike the two reconciler-fed gauges below.
        //
        // NO baseUnit ON THIS ONE, deliberately, and it is not an omission. Micrometer's Prometheus
        // naming convention appends the base unit unless the name already ends with it, so
        // `.baseUnit("bytes")` here would export hamstrack_storage_bytes_used_total_BYTES — a name
        // no dashboard, alert rule or document in this repository uses. The sibling gauge below
        // keeps its baseUnit precisely because its name already ends in _bytes and the convention
        // therefore leaves it alone. Check the exported name, not the declared one.
        Gauge.builder("hamstrack.storage.bytes_used_total", storageUsageRepository,
                        WorkspaceStorageUsageRepository::totalBytesUsed)
                .description("Attachment bytes occupied across every workspace on this instance")
                .register(registry);

        // Stored as permille so the AtomicLong carries a fraction without a second field; the
        // gauge publishes the 0..1 number the alert threshold is written against.
        //
        // EVERY REPLICA COMPUTES THE SAME INSTALL-WIDE NUMBER, exactly as
        // hamstrack.mail.anonymous_recipient_max does — the series are duplicates, not shards, so
        // an alert takes max() and NEVER sum(). Summing would multiply the fill level by the
        // replica count and page at a third of the real threshold.
        Gauge.builder("hamstrack.storage.quota_fill_max", storageQuotaFillMaxPermille,
                        permille -> permille.get() / 1000.0)
                .description("Fullest workspace as a 0..1 fraction of the configured quota; "
                             + "refreshed on the reconcile schedule, identical on every replica "
                             + "(alert with max(), never sum())")
                .register(registry);

        Gauge.builder("hamstrack.storage.drift_bytes", storageDriftBytes, AtomicLong::get)
                .description("Largest absolute difference the last reconcile pass found between "
                             + "workspace_storage_usage.bytes_used and the attachment rows it "
                             + "claims to equal")
                .baseUnit("bytes")
                .register(registry);

        // The freshness of the two gauges above, and the only thing that can tell a clean instance
        // from a reconciler that stopped. Evaluated at scrape (arithmetic on a long, not a query)
        // so it keeps RISING while the reconciler is failing, instead of freezing beside the values
        // it describes. NO SENTINEL BRANCH — see storageDriftRefreshedAt's javadoc.
        Gauge.builder("hamstrack.storage.drift_refreshed_at_age_seconds", storageDriftRefreshedAt,
                        at -> (double) (Instant.now().getEpochSecond() - at.get()))
                .description("Seconds since the storage reconcile pass last completed; counts from "
                             + "process start until the first successful pass, so a reconciler "
                             + "that never runs still ages")
                .baseUnit("seconds")
                .register(registry);
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

    /**
     * {@code hamstrack.mail.anonymous_recipient_max} — how much anonymous auth mail went to the
     * single busiest recipient key over the last six hours (HD-202 review).
     *
     * <p><strong>The refusal counters cannot see the attack this feature exists to bound, and this
     * can.</strong> {@link #rateLimitHit} is emitted only when a ceiling <em>fires</em>. The
     * denial-of-recovery attacker is never refused: to hold a victim's bucket full they send
     * exactly at the rate slots age out, spaced past the cooldown, and every one of those requests
     * is allowed. So the only refusals a sustained single-target attack generates are the victim's
     * own one or two attempts — a handful, against a threshold in the tens. A rule built on
     * refusals fires for the noisy mail-bomb shape and stays silent for the quiet one, which is the
     * shape the whole ceiling was argued for.
     *
     * <p><strong>No address label, and that is why this is expressible as a metric at all.</strong>
     * The cardinality/privacy rule at the top of this class is not negotiable, and it binds harder
     * here than usual: a Grafana alert carries its series labels into alert state and out through
     * the email contact point, so a {@code recipient_key} label would mail recipient addresses to
     * the operator's inbox. The number says "somebody is being targeted"; the address is looked up
     * in {@code mail_send_events}, where it legitimately lives, with the query in
     * {@code docs/self-hosting.md}.
     *
     * <p>Every replica computes the same instance-wide number from the same table, so the series
     * are duplicates rather than shards — an alert on it takes {@code max()}, never {@code sum()}.
     */
    public void anonymousMailConcentration(long max) {
        anonymousMailConcentration.set(max);
    }

    /**
     * Stamps {@code hamstrack.mail.anonymous_recipient_max_age_seconds} — called only after a
     * refresh that actually completed.
     *
     * <p>A separate call rather than a side effect of {@link #anonymousMailConcentration(long)}, so
     * that a future caller which sets the value from somewhere other than a fresh query cannot
     * accidentally certify it as fresh.
     */
    public void anonymousMailConcentrationRefreshed() {
        anonymousMailConcentrationRefreshedAt.set(Instant.now().getEpochSecond());
    }

    // --- storage quota (HD-191) ---

    /**
     * {@code hamstrack.storage.quota_refused} — at the 409, and nowhere else.
     *
     * <p>No workspace label, per the cardinality rule at the top of this class: an operator who
     * needs to know <em>which</em> workspace queries {@code workspace_storage_usage}, and
     * {@code docs/self-hosting.md} carries that query. The counter answers "is anybody stuck",
     * which is the question an alert can act on.
     */
    public void storageQuotaRefused() {
        storageQuotaRefused.increment();
    }

    /**
     * {@code hamstrack.storage.drift_bytes} — the largest absolute delta one reconcile pass
     * corrected. Zero is the expected value: the trigger is the mechanism and the reconciler is
     * only the witness, so anything else means the quota has been enforcing a number that is not
     * true.
     */
    public void storageDrift(long maxAbsoluteDeltaBytes) {
        storageDriftBytes.set(maxAbsoluteDeltaBytes);
    }

    /**
     * {@code hamstrack.storage.quota_fill_max} — the fullest workspace as a 0..1 fraction.
     * Fires the alert that arrives <em>before</em> the refusals do, which is the entire purpose
     * of having a threshold.
     */
    public void storageQuotaFillMax(double fraction) {
        storageQuotaFillMaxPermille.set(Math.round(fraction * 1000));
    }

    /**
     * Stamps {@code hamstrack.storage.drift_refreshed_at_age_seconds} — called only after a
     * reconcile pass that actually completed.
     *
     * <p>A separate call rather than a side effect of the two setters above, for
     * {@link #anonymousMailConcentrationRefreshed()}'s reason: a future caller that sets a value
     * from somewhere other than a fresh pass must not be able to certify it as fresh by accident.
     */
    public void storageReconcileCompleted() {
        storageDriftRefreshedAt.set(Instant.now().getEpochSecond());
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
     * {@code hamstrack.issues.created{type}}. Cardinality guard: only SYSTEM
     * (global-catalog) type names are a bounded, operator-controlled set and safe
     * as a label value. Delegated (workspace/project) admins can mint arbitrarily
     * many scoped type names, so those all collapse into a single {@code custom}
     * series — otherwise every custom type name would be a new Prometheus series.
     */
    public void issueCreated(String typeName, boolean systemType) {
        registry.counter("hamstrack.issues.created",
                "type", systemType ? typeName : "custom").increment();
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

    /**
     * An administrator withdrew a pending invitation (HD-158 §10) — the missing term in the
     * invitation lifecycle. Without it a withdrawn invitation is indistinguishable from an ignored
     * one, so the acceptance ratio HD-190 leans on cannot tell a workspace tidying up from a
     * workspace being ignored. No alert rule is proposed; the counter exists so an operator
     * investigating one has the term available.
     */
    public void inviteRevoked() {
        invitesRevoked.increment();
    }

    // --- email ---

    /** {@code hamstrack.email.sent{type,outcome}}. */
    public void emailSent(EmailType type, EmailOutcome outcome) {
        registry.counter("hamstrack.email.sent",
                "type", type.tag, "outcome", outcome.tag).increment();
    }

    // --- authorization ---

    /**
     * {@code hamstrack.role.scope_violation{source}} — a membership or default-role column
     * points at a role of the wrong scope, or of another workspace (HD-127 §3c).
     *
     * <p>Exists because the read-side degrade makes that condition <em>survivable</em>: the
     * list endpoints render the row with a null role instead of 404ing the whole page, so
     * without a meter a permanently corrupt row is only an ERROR line on every list request
     * — a Loki bill rather than a signal. This makes it alertable without being read.
     */
    public void roleScopeViolation(RoleScopeViolationSource source) {
        registry.counter("hamstrack.role.scope_violation", "source", source.tag).increment();
    }

    // --- persistence ---

    /**
     * {@code hamstrack.db.statement_budget_exceeded{method,route}} — a statement PostgreSQL
     * cancelled at {@code app.persistence.statement-timeout-ms} (HD-151).
     *
     * <p>Exists because that refusal is a clean 422 rather than a 500, so it appears in no error
     * rate and would otherwise be visible only to somebody reading WARN lines. It is the signal
     * that a tenant has outgrown an index, or that a query has stopped using one, and the first
     * thing an operator needs before deciding whether to raise the bound.
     *
     * <p>Cardinality guard: the <strong>mapped pattern</strong>
     * ({@code /api/workspaces/{workspaceId}/…}), never the request URI. A URI carries workspace
     * and project UUIDs, which would both explode the series count and put tenant ids in a
     * metrics store. {@code route} is {@code unmapped} when no handler matched, which is a real
     * case (an error dispatch) and not a placeholder.
     */
    public void statementBudgetExceeded(String method, String mappedPattern) {
        registry.counter("hamstrack.db.statement_budget_exceeded",
                "method", method == null ? "unknown" : method,
                "route", mappedPattern == null ? "unmapped" : mappedPattern).increment();
    }

    // --- attachments ---

    /** {@code hamstrack.attachments.uploaded} + {@code hamstrack.attachments.bytes}. */
    public void attachmentUploaded(long sizeBytes) {
        attachmentsUploaded.increment();
        attachmentBytes.record(sizeBytes);
    }
}
