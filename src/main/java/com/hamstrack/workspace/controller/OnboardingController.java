package com.hamstrack.workspace.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.common.seed.DemoDataService;
import com.hamstrack.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * First-login onboarding (Cloud). The user either joins an existing team
 * (accept an invite — see {@link InviteController}) or creates their own. This
 * endpoint backs the "Create a team" choice: it provisions the demo starter
 * workspace (if enabled) so there's something to explore, then completes
 * onboarding. The real team is created afterwards on the workspaces page.
 * Users who join a team never hit this, so they get no demo workspace.
 */
@Slf4j
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final WorkspaceService workspaceService;
    private final DemoDataService demoDataService;

    @PostMapping("/create-team")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void createTeam(@AuthenticationPrincipal User user) {
        // Demo seeding is best-effort (idempotent, toggle-gated) — a failure must
        // not block the user from finishing onboarding.
        try {
            demoDataService.seedOnFirstLogin(user.getId());
        } catch (Exception e) {
            log.error("Demo seeding failed during onboarding for user {}", user.getId(), e);
        }
        workspaceService.completeOnboarding(user);
    }
}
