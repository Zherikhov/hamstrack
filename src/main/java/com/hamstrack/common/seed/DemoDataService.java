package com.hamstrack.common.seed;

import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.issue.dto.CreateIssueRequest;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.issue.entity.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.issue.repository.FieldSetRepository;
import com.hamstrack.issue.repository.IssueRepository;
import com.hamstrack.issue.repository.IssueTypeRepository;
import com.hamstrack.issue.repository.PriorityRepository;
import com.hamstrack.issue.repository.StatusRepository;
import com.hamstrack.issue.service.FieldValueService;
import com.hamstrack.issue.service.IssueService;
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
        var wsId = workspaceService.create(user, new CreateWorkspaceRequest("Demo Workspace")).id();
        var projectId = projectService.create(user, wsId, new CreateProjectRequest(
                "Demo Project", "DEMO",
                "A sample software project showing how Hamstrack works — a board with "
                        + "statuses, issue types, priorities and due dates. Feel free to edit, "
                        + "move or delete anything here.")).id();

        // Since M1 the taxonomy is a global catalog; new projects use the
        // system-default workflow (To Do / In Progress / Done) and priority set
        var typeIds = issueTypeRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
                .collect(Collectors.toMap(IssueType::getName, IssueType::getId));
        var statusIds = statusRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
                .collect(Collectors.toMap(Status::getName, Status::getId));
        var prioIds = priorityRepository.findAllByScopeWorkspaceIdIsNullOrderByPosition().stream()
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
        seedDemoFieldValues(projectId);

        log.info("Demo data seeded for user {}: workspace {}, project {}", userId, wsId, projectId);
    }

    /**
     * Custom fields showcase (M2): bind the demo project to the sample
     * "Engineering fields" set (seeded by V7) and fill a few values so the
     * feature is visible without admin work. Best-effort — skipped silently
     * if an admin deleted the sample set/fields.
     */
    private void seedDemoFieldValues(UUID projectId) {
        var set = fieldSetRepository.findByScopeWorkspaceIdIsNullAndName("Engineering fields").orElse(null);
        if (set == null) return;
        var project = projectRepository.findById(projectId).orElseThrow();
        project.setFieldSet(set);
        projectRepository.save(project);

        var json = JsonNodeFactory.instance;
        var storyPoints = fieldDefRepository.findByScopeWorkspaceIdIsNullAndKey("story_points").orElse(null);
        var severity = fieldDefRepository.findByScopeWorkspaceIdIsNullAndKey("severity").orElse(null);
        var environment = fieldDefRepository.findByScopeWorkspaceIdIsNullAndKey("environment").orElse(null);

        // issue numbers follow the creation order above
        if (storyPoints != null) {
            setField(project.getId(), 8, storyPoints.getId(), json.numberNode(5));
            setField(project.getId(), 15, storyPoints.getId(), json.numberNode(3));
            setField(project.getId(), 16, storyPoints.getId(), json.numberNode(8));
        }
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
        return new CreateIssueRequest(title, description,
                typeIds.get(type), statusIds.get(status),
                prioIds.get(priority), assigneeId, parentId, dueDate, null);
    }
}
