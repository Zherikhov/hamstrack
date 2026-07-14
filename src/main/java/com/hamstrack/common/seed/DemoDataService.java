package com.hamstrack.common.seed;

import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.config.AppProperties;
import com.hamstrack.issue.dto.CreateIssueRequest;
import com.hamstrack.issue.entity.IssuePriority;
import com.hamstrack.issue.entity.IssueType;
import com.hamstrack.issue.entity.Status;
import com.hamstrack.issue.repository.IssueTypeRepository;
import com.hamstrack.issue.repository.StatusRepository;
import com.hamstrack.issue.service.IssueService;
import com.hamstrack.project.dto.CreateProjectRequest;
import com.hamstrack.project.service.ProjectService;
import com.hamstrack.workspace.dto.CreateWorkspaceRequest;
import com.hamstrack.workspace.repository.WorkspaceRepository;
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

import static com.hamstrack.issue.entity.IssuePriority.*;

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
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final ProjectService projectService;
    private final IssueService issueService;
    private final IssueTypeRepository issueTypeRepository;
    private final StatusRepository statusRepository;

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

        var workspace = workspaceRepository.findById(wsId).orElseThrow();
        var typeIds = issueTypeRepository.findAllByWorkspaceOrderByPosition(workspace).stream()
                .collect(Collectors.toMap(IssueType::getName, IssueType::getId));
        var statusIds = statusRepository.findAllByWorkspaceOrderByPosition(workspace).stream()
                .collect(Collectors.toMap(Status::getName, Status::getId));
        Function<CreateIssueRequest, UUID> create =
                req -> issueService.create(user, wsId, projectId, req).id();

        var today = LocalDate.now();
        var me = user.getId();

        // 20 issues in dev-team style, spread across statuses and types.
        // Epics first so their ids can parent later issues.
        var accountEpic = create.apply(issue(typeIds, statusIds, "Epic", "In Progress", HIGH,
                "User account & profile settings",
                "Everything a signed-in user needs to manage their own account: profile "
                        + "details, avatar, password and notification preferences.\n\n"
                        + "Child issues are linked to this epic.",
                me, null, null));
        var mobileEpic = create.apply(issue(typeIds, statusIds, "Epic", "To Do", MEDIUM,
                "Mobile companion app (MVP)",
                "Scope for the first mobile release: read-only boards, push notifications "
                        + "for assignments and a quick-comment action.\n\n"
                        + "Kick-off planned after the account epic ships.",
                null, null, null));

        // --- Done ---
        create.apply(issue(typeIds, statusIds, "Story", "Done", MEDIUM,
                "Users can update display name and avatar",
                "Profile page with editable display name and avatar upload (5 MB limit, "
                        + "cropped to a square). Changes are reflected everywhere the user is shown.",
                me, accountEpic, null));
        create.apply(issue(typeIds, statusIds, "Task", "Done", HIGH,
                "Set up CI pipeline with a test gate before deploy",
                "Every push runs the full test suite against a disposable database. "
                        + "Deploys to production only happen from green builds on the main branch.",
                me, null, null));
        create.apply(issue(typeIds, statusIds, "Bug", "Done", HIGH,
                "Login form submits twice on double-click",
                "Steps to reproduce:\n1. Open the login page\n2. Double-click Sign in\n\n"
                        + "Two POST /login requests were sent, occasionally producing two sessions. "
                        + "Fixed by disabling the button while the request is in flight.",
                me, null, null));
        create.apply(issue(typeIds, statusIds, "Task", "Done", LOW,
                "Add composite index for board queries",
                "Board loading did a sequential scan when filtering issues by project and "
                        + "status. Added a composite index and verified the plan with EXPLAIN ANALYZE.",
                null, null, null));
        create.apply(issue(typeIds, statusIds, "Story", "Done", MEDIUM,
                "Email notification when mentioned in a comment",
                "When someone @mentions a teammate in a comment, the teammate receives an "
                        + "email with the comment text and a deep link to the issue.",
                null, null, null));

        // --- In Progress ---
        create.apply(issue(typeIds, statusIds, "Story", "In Progress", HIGH,
                "Password change form in account settings",
                "Requires the current password, enforces the same strength rules as "
                        + "registration and invalidates all other active sessions on success.",
                me, accountEpic, today.plusDays(5)));
        create.apply(issue(typeIds, statusIds, "Bug", "In Progress", MEDIUM,
                "Board columns overflow horizontally on small screens",
                "With 6+ status columns the board overflows the viewport on 13\" laptops "
                        + "and there is no horizontal scroll affordance. Investigating a scroll "
                        + "container with snap points.",
                me, null, null));
        create.apply(issue(typeIds, statusIds, "Bug", "In Progress", HIGH,
                "Attachment download garbles non-ASCII filenames",
                "Uploading \"отчёт-июль.pdf\" and downloading it produces \"_____.pdf\". "
                        + "The Content-Disposition header needs the RFC 5987 filename* parameter.",
                null, null, today.plusDays(3)));
        create.apply(issue(typeIds, statusIds, "Task", "In Progress", LOW,
                "Write an onboarding guide for new developers",
                "One page: how to start the local stack (database, mail catcher), run the "
                        + "test suite and find the main modules. Target: productive on day one.",
                null, null, null));
        create.apply(issue(typeIds, statusIds, "Task", "In Progress", MEDIUM,
                "Spike: evaluate live-update options for the board",
                "Compare SSE and WebSockets for pushing board updates to open clients. "
                        + "Deliverable: a one-page recommendation covering proxies, reconnects "
                        + "and auth. Timebox: 2 days.",
                me, null, null));

        // --- To Do ---
        create.apply(issue(typeIds, statusIds, "Bug", "To Do", URGENT,
                "Session expires silently and edits are lost",
                "When the session expires while the issue panel is open, saving fails "
                        + "without any message and the edit is lost. Expected: a re-login prompt "
                        + "that preserves the unsaved text.",
                me, null, today.plusDays(2)));
        create.apply(issue(typeIds, statusIds, "Task", "To Do", HIGH,
                "Configure automated nightly database backups",
                "Nightly dump to off-site storage with 14-day retention, plus a monthly "
                        + "restore drill into a scratch environment to prove backups are usable.",
                null, null, today.plusDays(7)));
        create.apply(issue(typeIds, statusIds, "Story", "To Do", MEDIUM,
                "Notification preferences per user",
                "Let users choose which events trigger an email: assignments, mentions, "
                        + "status changes on watched issues. Default: mentions and assignments only.",
                null, accountEpic, null));
        create.apply(issue(typeIds, statusIds, "Story", "To Do", MEDIUM,
                "Push notifications for issue assignment",
                "When an issue is assigned to me, my phone shows a push notification with "
                        + "the issue key and title. Tapping it opens the issue in the mobile app.",
                null, mobileEpic, null));
        create.apply(issue(typeIds, statusIds, "Story", "To Do", LOW,
                "Dark mode",
                "A theme toggle in the user menu (light / dark / follow system). Persisted "
                        + "per device; charts and status colors need dark-safe variants.",
                null, null, null));
        create.apply(issue(typeIds, statusIds, "Bug", "To Do", MEDIUM,
                "Duplicate notification when assigned and mentioned in the same comment",
                "If a comment both assigns me and @mentions me, I get two emails within a "
                        + "second of each other. They should be collapsed into one.",
                null, null, null));
        create.apply(issue(typeIds, statusIds, "Task", "To Do", HIGH,
                "Rate-limit authentication endpoints",
                "Login, registration and password-reset endpoints accept unlimited "
                        + "attempts. Add per-IP and per-account limits with exponential backoff "
                        + "and audit logging.",
                null, null, today.plusDays(10)));
        create.apply(issue(typeIds, statusIds, "Story", "To Do", LOW,
                "Keyboard shortcuts for the board",
                "Power-user navigation: j/k to move between cards, enter to open the side "
                        + "panel, esc to close it, and a \"?\" overlay listing all shortcuts.",
                null, null, null));
        log.info("Demo data seeded for user {}: workspace {}, project {}", userId, wsId, projectId);
    }

    private CreateIssueRequest issue(Map<String, UUID> typeIds, Map<String, UUID> statusIds,
                                     String type, String status, IssuePriority priority,
                                     String title, String description,
                                     UUID assigneeId, UUID parentId, LocalDate dueDate) {
        return new CreateIssueRequest(title, description,
                typeIds.get(type), statusIds.get(status),
                priority, assigneeId, parentId, dueDate);
    }
}
