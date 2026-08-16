package com.hamstrack.common.seed;

import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.issue.dto.AddIssuesToSprintRequest;
import com.hamstrack.issue.dto.CreateComponentRequest;
import com.hamstrack.issue.dto.CreateIssueRequest;
import com.hamstrack.issue.dto.CreateLabelRequest;
import com.hamstrack.issue.dto.CreateSprintRequest;
import com.hamstrack.issue.dto.SprintInsertPosition;
import com.hamstrack.issue.dto.StartSprintRequest;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hamstrack.issue.dto.CreateVersionRequest;
import com.hamstrack.issue.dto.ReleaseVersionRequest;
import com.hamstrack.issue.entity.VersionLinkType;
import com.hamstrack.issue.repository.ComponentRepository;
import com.hamstrack.issue.repository.SprintRepository;
import com.hamstrack.issue.repository.VersionRepository;
import com.hamstrack.issue.service.SprintService;
import com.hamstrack.issue.service.VersionService;
import com.hamstrack.project.entity.BoardMode;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.IssueTypeRepository;
import com.hamstrack.issue.repository.LabelRepository;
import com.hamstrack.issue.repository.PriorityRepository;
import com.hamstrack.issue.repository.StatusRepository;
import com.hamstrack.issue.service.FieldValueService;
import com.hamstrack.issue.service.ComponentService;
import com.hamstrack.issue.service.IssueService;
import com.hamstrack.issue.service.LabelService;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.project.dto.CreateProjectRequest;
import com.hamstrack.project.service.ProjectService;
import com.hamstrack.workspace.dto.CreateWorkspaceRequest;
import com.hamstrack.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Seeds a demo workspace ("Demo Workspace" with a "Demo Project") for a user on
 * their first successful authentication, so new accounts land in a populated app
 * instead of an empty one. Reuses the regular workspace/project/issue services,
 * so all invariants (default statuses/types, OWNER/MANAGER membership, issue
 * sequence numbers) hold exactly as for user-created data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataService {

    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final WorkspaceService workspaceService;
    private final ProjectService projectService;
    private final IssueService issueService;
    private final IssueTypeRepository issueTypeRepository;
    private final StatusRepository statusRepository;
    private final PriorityRepository priorityRepository;
    private final FieldSetRepository fieldSetRepository;
    private final FieldDefRepository fieldDefRepository;
    private final FieldValueService fieldValueService;
    private final LabelService labelService;
    private final LabelRepository labelRepository;
    private final ComponentService componentService;
    private final ComponentRepository componentRepository;
    private final VersionService versionService;
    private final VersionRepository versionRepository;
    private final SprintService sprintService;
    private final SprintRepository sprintRepository;
    private final ProjectRepository projectRepository;
    private final IssueRepository issueRepository;

    /**
     * Idempotent and race-safe: the claim UPDATE stamps {@code demo_seeded_at}
     * only when it is still NULL, so concurrent logins seed at most once. A
     * failure rolls the claim back and the next authentication retries.
     */
    @Transactional
    public void seedOnFirstLogin(UUID userId) {
        if (!appProperties.demo().seedOnFirstLogin()) return;
        if (userRepository.claimDemoSeed(userId, Instant.now()) == 0) return;

        var user = userRepository.findById(userId).orElseThrow();
        // completesOnboarding=false: the demo workspace is auto-provisioned, not
        // a user choice — a Cloud user must still see the create-or-join welcome
        var wsId = workspaceService.create(user, new CreateWorkspaceRequest("Demo Workspace"), false).id();
        var projectId = projectService.create(user, wsId, new CreateProjectRequest(
                "Demo Project", "DEMO",
                "A sample software project showing how Hamstrack works — a board with "
                        + "statuses, issue types, priorities and due dates. Feel free to edit, "
                        + "move or delete anything here.")).id();

        // The demo uses the GLOBAL catalog (both scope columns null); findAtScope
        // (null, null) excludes workspace-/project-scoped rows, which may reuse a
        // global name — a plain scope_workspace_id-null filter would collide here.
        var typeIds = issueTypeRepository.findAllAtScope(null, null).stream()
                .collect(Collectors.toMap(IssueType::getName, IssueType::getId));
        var statusIds = statusRepository.findAllAtScope(null, null).stream()
                .collect(Collectors.toMap(Status::getName, Status::getId));
        var prioIds = priorityRepository.findAllAtScope(null, null).stream()
                .collect(Collectors.toMap(Priority::getName, Priority::getId));
        Function<CreateIssueRequest, UUID> create =
                req -> issueService.create(user, wsId, projectId, req).id();

        var today = LocalDate.now();
        var me = user.getId();

        // 20 issues in dev-team style, spread across statuses and types.
        // Epics first so their ids can parent later issues.
        var accountEpic = create.apply(issue(typeIds, statusIds, prioIds,"Epic", "In Progress", "High",
                "User account & profile settings",
                "Everything a signed-in user needs to manage their own account: profile "
                        + "details, avatar, password and notification preferences.\n\n"
                        + "Child issues are linked to this epic.",
                me, null, null));
        var mobileEpic = create.apply(issue(typeIds, statusIds, prioIds,"Epic", "To Do", "Medium",
                "Mobile companion app (MVP)",
                "Scope for the first mobile release: read-only boards, push notifications "
                        + "for assignments and a quick-comment action.\n\n"
                        + "Kick-off planned after the account epic ships.",
                null, null, null));

        // --- Done ---
        create.apply(issue(typeIds, statusIds, prioIds,"Story", "Done", "Medium",
                "Users can update display name and avatar",
                "Profile page with editable display name and avatar upload (5 MB limit, "
                        + "cropped to a square). Changes are reflected everywhere the user is shown.",
                me, accountEpic, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Task", "Done", "High",
                "Set up CI pipeline with a test gate before deploy",
                "Every push runs the full test suite against a disposable database. "
                        + "Deploys to production only happen from green builds on the main branch.",
                me, null, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Bug", "Done", "High",
                "Login form submits twice on double-click",
                "Steps to reproduce:\n1. Open the login page\n2. Double-click Sign in\n\n"
                        + "Two POST /login requests were sent, occasionally producing two sessions. "
                        + "Fixed by disabling the button while the request is in flight.",
                me, null, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Task", "Done", "Low",
                "Add composite index for board queries",
                "Board loading did a sequential scan when filtering issues by project and "
                        + "status. Added a composite index and verified the plan with EXPLAIN ANALYZE.",
                null, null, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Story", "Done", "Medium",
                "Email notification when mentioned in a comment",
                "When someone @mentions a teammate in a comment, the teammate receives an "
                        + "email with the comment text and a deep link to the issue.",
                null, null, null));

        // --- In Progress ---
        create.apply(issue(typeIds, statusIds, prioIds,"Story", "In Progress", "High",
                "Password change form in account settings",
                "Requires the current password, enforces the same strength rules as "
                        + "registration and invalidates all other active sessions on success.",
                me, accountEpic, today.plusDays(5)));
        create.apply(issue(typeIds, statusIds, prioIds,"Bug", "In Progress", "Medium",
                "Board columns overflow horizontally on small screens",
                "With 6+ status columns the board overflows the viewport on 13\" laptops "
                        + "and there is no horizontal scroll affordance. Investigating a scroll "
                        + "container with snap points.",
                me, null, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Bug", "In Progress", "High",
                "Attachment download garbles non-ASCII filenames",
                "Uploading \"отчёт-июль.pdf\" and downloading it produces \"_____.pdf\". "
                        + "The Content-Disposition header needs the RFC 5987 filename* parameter.",
                null, null, today.plusDays(3)));
        create.apply(issue(typeIds, statusIds, prioIds,"Task", "In Progress", "Low",
                "Write an onboarding guide for new developers",
                "One page: how to start the local stack (database, mail catcher), run the "
                        + "test suite and find the main modules. Target: productive on day one.",
                null, null, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Task", "In Progress", "Medium",
                "Spike: evaluate live-update options for the board",
                "Compare SSE and WebSockets for pushing board updates to open clients. "
                        + "Deliverable: a one-page recommendation covering proxies, reconnects "
                        + "and auth. Timebox: 2 days.",
                me, null, null));

        // --- To Do ---
        create.apply(issue(typeIds, statusIds, prioIds,"Bug", "To Do", "Urgent",
                "Session expires silently and edits are lost",
                "When the session expires while the issue panel is open, saving fails "
                        + "without any message and the edit is lost. Expected: a re-login prompt "
                        + "that preserves the unsaved text.",
                me, null, today.plusDays(2)));
        create.apply(issue(typeIds, statusIds, prioIds,"Task", "To Do", "High",
                "Configure automated nightly database backups",
                "Nightly dump to off-site storage with 14-day retention, plus a monthly "
                        + "restore drill into a scratch environment to prove backups are usable.",
                null, null, today.plusDays(7)));
        create.apply(issue(typeIds, statusIds, prioIds,"Story", "To Do", "Medium",
                "Notification preferences per user",
                "Let users choose which events trigger an email: assignments, mentions, "
                        + "status changes on watched issues. Default: mentions and assignments only.",
                null, accountEpic, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Story", "To Do", "Medium",
                "Push notifications for issue assignment",
                "When an issue is assigned to me, my phone shows a push notification with "
                        + "the issue key and title. Tapping it opens the issue in the mobile app.",
                null, mobileEpic, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Story", "To Do", "Low",
                "Dark mode",
                "A theme toggle in the user menu (light / dark / follow system). Persisted "
                        + "per device; charts and status colors need dark-safe variants.",
                null, null, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Bug", "To Do", "Medium",
                "Duplicate notification when assigned and mentioned in the same comment",
                "If a comment both assigns me and @mentions me, I get two emails within a "
                        + "second of each other. They should be collapsed into one.",
                null, null, null));
        create.apply(issue(typeIds, statusIds, prioIds,"Task", "To Do", "High",
                "Rate-limit authentication endpoints",
                "Login, registration and password-reset endpoints accept unlimited "
                        + "attempts. Add per-IP and per-account limits with exponential backoff "
                        + "and audit logging.",
                null, null, today.plusDays(10)));
        create.apply(issue(typeIds, statusIds, prioIds,"Story", "To Do", "Low",
                "Keyboard shortcuts for the board",
                "Power-user navigation: j/k to move between cards, enter to open the side "
                        + "panel, esc to close it, and a \"?\" overlay listing all shortcuts.",
                null, null, null));
        // Best-effort (as the javadoc promises): a field-value hiccup (e.g. seeded
        // option ids diverging from the sample values) must not roll back the whole
        // demo seed and its claim, or first-login seeding would retry-and-fail forever
        try {
            seedDemoFieldValues(projectId);
        } catch (RuntimeException e) {
            log.warn("Demo field-value seeding skipped for project {}: {}", projectId, e.toString());
        }
        // Same best-effort contract for the starter labels (HD-30 §3.9).
        try {
            seedDemoLabels(user, wsId, projectId);
        } catch (RuntimeException e) {
            log.warn("Demo label seeding skipped for workspace {}: {}", wsId, e.toString());
        }
        // …and for the starter components (HD-31 §3.9).
        try {
            seedDemoComponents(user, wsId, projectId);
        } catch (RuntimeException e) {
            log.warn("Demo component seeding skipped for project {}: {}", projectId, e.toString());
        }
        // …and for the agile showcase (HD-22 §4.8): a running sprint, a planned one and
        // story points, so 0.13.0's headline feature is visible on first login instead
        // of an empty Backlog page.
        try {
            seedDemoSprints(user, wsId, projectId);
        } catch (RuntimeException e) {
            log.warn("Demo sprint seeding skipped for project {}: {}", projectId, e.toString());
        }
        // …and for the starter versions (HD-32 §3.9). Deliberately LAST — see
        // seedDemoVersions: releasing one of them clears the persistence context.
        try {
            seedDemoVersions(user, wsId, projectId);
        } catch (RuntimeException e) {
            log.warn("Demo version seeding skipped for project {}: {}", projectId, e.toString());
        }

        log.info("Demo data seeded for user {}: workspace {}, project {}", userId, wsId, projectId);
    }

    /**
     * Custom fields showcase (M2): bind the demo project to the sample
     * "Engineering fields" set (seeded by V7) and fill a few values so the
     * feature is visible without admin work. Best-effort — skipped silently
     * if an admin deleted the sample set/fields.
     */
    private void seedDemoFieldValues(UUID projectId) {
        var set = fieldSetRepository.findByScopeWorkspaceIdIsNullAndScopeProjectIdIsNullAndName("Engineering fields").orElse(null);
        if (set == null) return;
        var project = projectRepository.findById(projectId).orElseThrow();
        project.setFieldSet(set);
        projectRepository.save(project);

        var json = JsonNodeFactory.instance;
        // NOTE (HD-22 §3.4): the `story_points` custom field is deliberately NOT seeded
        // any more — V11 promoted it to the native issues.story_points column and
        // ARCHIVED the field def, so writing a value here would either fail validation
        // or resurrect a retired field. Story points are seeded in seedDemoSprints.
        var severity = fieldDefRepository.findByScopeWorkspaceIdIsNullAndScopeProjectIdIsNullAndKey("severity").orElse(null);
        var environment = fieldDefRepository.findByScopeWorkspaceIdIsNullAndScopeProjectIdIsNullAndKey("environment").orElse(null);

        // issue numbers follow the creation order above
        if (severity != null) {
            setField(project.getId(), 5, severity.getId(), json.textNode("major"));
            setField(project.getId(), 9, severity.getId(), json.textNode("minor"));
            setField(project.getId(), 13, severity.getId(), json.textNode("critical"));
        }
        if (environment != null) {
            setField(project.getId(), 13, environment.getId(),
                    json.arrayNode().add("production"));
            setField(project.getId(), 9, environment.getId(),
                    json.arrayNode().add("staging").add("dev"));
        }
    }

    /**
     * Starter labels (HD-30 §3.9) so the feature isn't an empty list on first look:
     * four labels in the demo workspace, attached to a handful of the seeded issues.
     * Created through {@link LabelService} so normalization, the color palette and the
     * uniqueness rules apply exactly as for user-created labels. Everything cascades
     * away with the workspace, so the test-mode reset block needs no change.
     *
     * <p><strong>Why the pre-check instead of relying on the caller's catch:</strong>
     * the whole seed shares ONE transaction, and {@code LabelService.create} lets a
     * {@code DataIntegrityViolationException} mark it rollback-only before rethrowing.
     * By then the outer "best-effort" catch can no longer recover — the seed would
     * still blow up at commit with {@code UnexpectedRollbackException} and first-login
     * seeding would fail forever. Looking the name up first keeps the failure mode out
     * of the database entirely (latent today: a freshly created workspace can't hold a
     * duplicate, but the invariant must not depend on that).
     */
    private void seedDemoLabels(com.hamstrack.auth.entity.User user, UUID wsId, UUID projectId) {
        var project = projectRepository.findById(projectId).orElseThrow();
        var workspace = project.getWorkspace();

        var labelIds = new LinkedHashMap<String, UUID>();
        for (var spec : List.of(
                new String[]{"customer-reported", "#F04438", "Raised by a customer, not found internally"},
                new String[]{"tech debt", "#667085", "Cleanup work that unblocks future changes"},
                new String[]{"quick win", "#12B981", "Small scope, visible payoff"},
                new String[]{"needs design", "#7C6CF5", "Blocked until a design decision lands"})) {
            var existing = labelRepository.findByWorkspaceAndNameIgnoreCase(workspace, spec[0]);
            labelIds.put(spec[0], existing.map(com.hamstrack.issue.entity.Label::getId).orElseGet(() ->
                    labelService.create(user, wsId, new CreateLabelRequest(spec[0], spec[1], spec[2])).id()));
        }

        // Issue numbers follow the creation order in seedOnFirstLogin above.
        attachLabels(workspace, project, 5, labelIds, "customer-reported");
        attachLabels(workspace, project, 6, labelIds, "tech debt", "quick win");
        attachLabels(workspace, project, 9, labelIds, "customer-reported", "needs design");
        attachLabels(workspace, project, 13, labelIds, "customer-reported");
        attachLabels(workspace, project, 17, labelIds, "needs design");
        attachLabels(workspace, project, 20, labelIds, "quick win");
    }

    /**
     * Starter components (HD-31 §3.9) so the project-settings Components tab and the
     * component picker aren't empty on first look: three modules in the demo project,
     * assigned to a handful of the seeded issues.
     *
     * <p>Created through {@link ComponentService} so normalization and the uniqueness
     * rules apply exactly as for user-created components. The demo user is the lead of
     * "Platform" with auto-assign ON, which makes the feature discoverable — it only
     * ever affects issues created <em>after</em> the seed. Everything cascades away
     * with the workspace, so the documented test-mode reset block needs no change.
     *
     * <p><strong>Why the pre-check instead of relying on the caller's catch</strong>
     * (same reasoning as {@link #seedDemoLabels}): the whole seed shares ONE
     * transaction, and a {@code DataIntegrityViolationException} escaping
     * {@code ComponentService.create} would mark it rollback-only — by then the outer
     * best-effort catch can no longer recover and the commit would still blow up with
     * {@code UnexpectedRollbackException}, so first-login seeding would fail forever.
     * Looking the name up first keeps that failure mode out of the database entirely.
     */
    private void seedDemoComponents(com.hamstrack.auth.entity.User user, UUID wsId, UUID projectId) {
        var project = projectRepository.findById(projectId).orElseThrow();

        var componentIds = new LinkedHashMap<String, UUID>();
        record Spec(String name, String description, boolean lead) {}
        for (var spec : List.of(
                new Spec("Platform", "Core services, storage and the API surface", true),
                new Spec("Web app", "The React single-page app and its design system", false),
                new Spec("Mobile", "The companion mobile client", false))) {
            var existing = componentRepository.findByProjectAndNameIgnoreCase(project, spec.name());
            componentIds.put(spec.name(), existing
                    .map(com.hamstrack.issue.entity.Component::getId)
                    .orElseGet(() -> componentService.create(user, wsId, projectId,
                            new CreateComponentRequest(spec.name(), spec.description(),
                                    spec.lead() ? user.getId() : null, spec.lead())).id()));
        }

        // Issue numbers follow the creation order in seedOnFirstLogin above.
        assignComponent(project, 4, componentIds.get("Platform"));
        assignComponent(project, 6, componentIds.get("Platform"));
        assignComponent(project, 9, componentIds.get("Web app"));
        assignComponent(project, 13, componentIds.get("Platform"));
        assignComponent(project, 16, componentIds.get("Mobile"));
        assignComponent(project, 20, componentIds.get("Web app"));
    }

    /**
     * Starter versions (HD-32 §3.9) so the Releases page isn't empty on first look:
     * two versions in the demo project — a shipped "1.0" and the in-flight "1.1" —
     * with fix links spread over a handful of the seeded issues so the progress bar
     * shows something real.
     *
     * <p>Created through {@link VersionService} so normalization, the uniqueness rules
     * and the release lifecycle apply exactly as for user-created versions. Everything
     * cascades away with the workspace, so the documented test-mode reset block needs
     * no change.
     *
     * <p><strong>Why the pre-check instead of relying on the caller's catch</strong>
     * (same reasoning as {@link #seedDemoLabels} / {@link #seedDemoComponents}): the
     * whole seed shares ONE transaction, and a {@code DataIntegrityViolationException}
     * escaping {@code VersionService.create} would mark it rollback-only — by then the
     * outer best-effort catch can no longer recover and the commit would still blow up
     * with {@code UnexpectedRollbackException}, so first-login seeding would fail
     * forever. Looking the name up first keeps that failure mode out of the database.
     *
     * <p><strong>Why this runs LAST, and why the release is the last thing it does:</strong>
     * {@code VersionService.release} flips the flag with a conditional bulk UPDATE
     * whose {@code @Modifying} carries {@code clearAutomatically} (it must re-read the
     * row) — which {@code em.clear()}s the persistence context. The paired
     * {@code flushAutomatically} writes every pending insert first, so nothing is lost
     * (the documented "workspace vanishes" trap), but every entity this seeder still
     * holds is detached afterwards. Nothing may touch them after this point.
     */
    private void seedDemoVersions(com.hamstrack.auth.entity.User user, UUID wsId, UUID projectId) {
        var project = projectRepository.findById(projectId).orElseThrow();

        // UTC, matching VersionService.release's own default: LocalDate.now() would make
        // the seeded plan dates depend on the container's timezone.
        var todayUtc = LocalDate.now(ZoneOffset.UTC);

        var versionIds = new LinkedHashMap<String, UUID>();
        record Spec(String name, String description, LocalDate releaseDate, boolean released) {}
        for (var spec : List.of(
                new Spec("1.0", "First public release", todayUtc.minusDays(30), true),
                new Spec("1.1", "Next release — in flight", todayUtc.plusDays(30), false))) {
            var existing = versionRepository.findByProjectAndNameIgnoreCase(project, spec.name());
            versionIds.put(spec.name(), existing
                    .map(com.hamstrack.issue.entity.Version::getId)
                    .orElseGet(() -> versionService.create(user, wsId, projectId,
                            new CreateVersionRequest(spec.name(), spec.description(),
                                    spec.releaseDate())).id()));
        }

        // Issue numbers follow the creation order in seedOnFirstLogin above.
        linkVersions(project, 4, VersionLinkType.FIX, versionIds.get("1.0"));
        linkVersions(project, 6, VersionLinkType.FIX, versionIds.get("1.0"));
        linkVersions(project, 9, VersionLinkType.FIX, versionIds.get("1.1"));
        linkVersions(project, 13, VersionLinkType.FIX, versionIds.get("1.1"));
        linkVersions(project, 17, VersionLinkType.FIX, versionIds.get("1.1"));
        // A bug that exists in the shipped release and is fixed in the next one — the
        // two roles on one issue, which is exactly what the join table's
        // (issue, version, link_type) key exists to allow.
        linkVersions(project, 13, VersionLinkType.AFFECTS, versionIds.get("1.0"));

        // LAST statement of the whole seed: clears the persistence context (see above).
        var shipped = versionIds.get("1.0");
        if (shipped != null) {
            versionService.release(user, wsId, projectId, shipped,
                    new ReleaseVersionRequest(todayUtc.minusDays(30), null));
        }
    }

    /**
     * The agile showcase (HD-22 §4.8): the demo project is switched to
     * {@code boardMode = SCRUM}, gets one <strong>ACTIVE</strong> sprint ("Sprint 1",
     * started 5 days ago and ending in 9) holding 8 of the seeded issues, one
     * <strong>FUTURE</strong> sprint ("Sprint 2") holding 3, and leaves the rest ranked
     * in the backlog. Story points (1/2/3/5/8) are spread over the committed work so the
     * point sums and the completion report show something real.
     *
     * <p>Created through {@link SprintService} so normalization, the name-uniqueness
     * rules, the open-sprint cap and the start lifecycle apply exactly as for
     * user-created sprints. Everything cascades away with the workspace, so the
     * documented test-mode reset block needs no change.
     *
     * <p><strong>Why the pre-check instead of relying on the caller's catch</strong>
     * (same reasoning as {@link #seedDemoLabels} / {@link #seedDemoComponents}): the
     * whole seed shares ONE transaction, and a {@code DataIntegrityViolationException}
     * escaping {@code SprintService.create} would mark it rollback-only — by then the
     * outer best-effort catch can no longer recover and the commit would still blow up
     * with {@code UnexpectedRollbackException}, so first-login seeding would fail forever.
     *
     * <p><strong>Why the start is the LAST statement here:</strong>
     * {@code SprintService.start} flips the state with a conditional bulk UPDATE whose
     * {@code @Modifying} carries {@code clearAutomatically} (it must re-read the row),
     * which {@code em.clear()}s the persistence context. The paired
     * {@code flushAutomatically} writes every pending insert first, so nothing is lost —
     * but every entity this method still holds is detached afterwards. The issues must
     * therefore be assigned and estimated BEFORE the sprint starts. (Sprint membership
     * does not require an ACTIVE sprint, so nothing is lost by that ordering.)
     */
    private void seedDemoSprints(com.hamstrack.auth.entity.User user, UUID wsId, UUID projectId) {
        var project = projectRepository.findById(projectId).orElseThrow();
        // Scrum mode: otherwise the release's headline feature is invisible on first
        // login (open question 9). issue_seq is updatable = false, so saving the project
        // here cannot clobber the native issue counter.
        project.setBoardMode(BoardMode.SCRUM);
        projectRepository.save(project);

        var sprintIds = new LinkedHashMap<String, UUID>();
        record Spec(String name, String goal) {}
        for (var spec : List.of(
                new Spec("Sprint 1", "Ship the account settings epic and clear the top bugs"),
                new Spec("Sprint 2", "Mobile groundwork and the backup/restore drill"))) {
            var existing = sprintRepository.findByProjectAndNameIgnoreCase(project, spec.name());
            sprintIds.put(spec.name(), existing
                    .map(com.hamstrack.issue.entity.Sprint::getId)
                    .orElseGet(() -> sprintService.create(user, wsId, projectId,
                            new CreateSprintRequest(spec.name(), spec.goal(), null, null)).id()));
        }

        // Issue numbers follow the creation order in seedOnFirstLogin above. A Fibonacci
        // -ish spread, and two issues left unestimated on purpose so `unestimatedCount`
        // is non-zero and the UI's "n unestimated" hint is exercised.
        setStoryPoints(project, 3, "3");
        setStoryPoints(project, 4, "5");
        setStoryPoints(project, 5, "2");
        setStoryPoints(project, 8, "5");
        setStoryPoints(project, 9, "3");
        setStoryPoints(project, 10, "2");
        setStoryPoints(project, 11, "1");
        setStoryPoints(project, 13, "8");
        setStoryPoints(project, 14, "3");

        // Sprint 1 mixes DONE and in-flight work, so completing it reports a real
        // done-vs-carried-over split; Sprint 2 is the planned next iteration.
        addToSprint(user, wsId, projectId, project, sprintIds.get("Sprint 1"), 3, 4, 5, 8, 9, 10, 11, 12);
        addToSprint(user, wsId, projectId, project, sprintIds.get("Sprint 2"), 13, 14, 15);

        // LAST statement of this method: clears the persistence context (see above).
        var running = sprintIds.get("Sprint 1");
        if (running != null) {
            var now = OffsetDateTime.now(ZoneOffset.UTC);
            sprintService.start(user, wsId, projectId, running,
                    new StartSprintRequest(now.minusDays(5), now.plusDays(9), null));
        }
    }

    /**
     * Estimate one already-seeded issue. Goes through the managed entity rather than a
     * bulk UPDATE: these issues were created earlier in THIS transaction, so a bulk
     * update would leave the L1 copies stale (CLAUDE.md).
     */
    private void setStoryPoints(com.hamstrack.project.entity.Project project, long number, String points) {
        issueRepository.findByProjectAndNumber(project, number).ifPresent(issue -> {
            issue.setStoryPoints(new java.math.BigDecimal(points));
            issueRepository.save(issue);
        });
    }

    /** Put a batch of already-seeded issues into one sprint, addressed by their numbers. */
    private void addToSprint(com.hamstrack.auth.entity.User user, UUID wsId, UUID projectId,
                             com.hamstrack.project.entity.Project project, UUID sprintId,
                             long... numbers) {
        if (sprintId == null) return;
        var ids = new ArrayList<UUID>(numbers.length);
        for (long number : numbers) {
            issueRepository.findByProjectAndNumber(project, number)
                    .ifPresent(issue -> ids.add(issue.getId()));
        }
        if (ids.isEmpty()) return;
        sprintService.addIssues(user, wsId, projectId, sprintId,
                new AddIssuesToSprintRequest(ids, SprintInsertPosition.BOTTOM));
    }

    /** Link one already-seeded issue to one version in the given role. */
    private void linkVersions(com.hamstrack.project.entity.Project project, long number,
                              VersionLinkType linkType, UUID versionId) {
        if (versionId == null) return;
        issueRepository.findByProjectAndNumber(project, number).ifPresent(issue ->
                versionService.attachAll(issue,
                        versionService.resolveForIssue(project, List.of(versionId), linkType),
                        linkType));
    }

    /**
     * Set a component on one already-seeded issue. Goes through the managed entity
     * rather than a bulk UPDATE: these issues were created earlier in THIS transaction,
     * so a bulk update would leave the L1 copies stale (CLAUDE.md).
     */
    private void assignComponent(com.hamstrack.project.entity.Project project, long number, UUID componentId) {
        if (componentId == null) return;
        var component = componentRepository.findByIdAndProject(componentId, project).orElse(null);
        if (component == null) return;
        issueRepository.findByProjectAndNumber(project, number).ifPresent(issue -> {
            issue.setComponent(component);
            issueRepository.save(issue);
        });
    }

    private void attachLabels(com.hamstrack.workspace.entity.Workspace workspace,
                              com.hamstrack.project.entity.Project project,
                              long number, Map<String, UUID> labelIds, String... names) {
        var ids = new ArrayList<UUID>(names.length);
        for (var name : names) ids.add(labelIds.get(name));
        issueRepository.findByProjectAndNumber(project, number).ifPresent(issue ->
                labelService.attachAll(issue, labelService.resolveForIssue(workspace, ids)));
    }

    private void setField(UUID projectId, long number, UUID fieldId, JsonNode value) {
        var project = projectRepository.findById(projectId).orElseThrow();
        issueRepository.findByProjectAndNumber(project, number).ifPresent(issue ->
                fieldValueService.applyValues(issue, Map.of(fieldId, value), false, (f, o, n) -> {}));
    }

    private CreateIssueRequest issue(Map<String, UUID> typeIds, Map<String, UUID> statusIds,
                                     Map<String, UUID> prioIds,
                                     String type, String status, String priority,
                                     String title, String description,
                                     UUID assigneeId, UUID parentId, LocalDate dueDate) {
        // labelIds/componentId/fix+affectsVersionIds/sprintId/storyPoints = null: all are
        // attached afterwards, once they exist (seedDemoLabels / seedDemoComponents /
        // seedDemoSprints / seedDemoVersions)
        return new CreateIssueRequest(title, description,
                typeIds.get(type), statusIds.get(status),
                prioIds.get(priority), assigneeId, parentId, dueDate,
                null, null, null, null, null, null, null, null, null);
    }
}
