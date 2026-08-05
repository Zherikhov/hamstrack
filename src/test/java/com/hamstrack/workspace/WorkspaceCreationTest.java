package com.hamstrack.workspace;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.seed.DemoDataService;
import com.hamstrack.workspace.dto.CreateWorkspaceRequest;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the onboarding stamping: {@code WorkspaceService.create}
 * marks the actor onboarded right after saving the workspace and its owner
 * membership. A bulk update with {@code clearAutomatically} there discarded the
 * still-pending inserts, so the workspace was never persisted — which broke
 * every workspace creation and, visibly, first-login demo seeding
 * (WorkspaceNotFoundException while creating the demo project).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.onboarding.enabled=true",
        "app.demo.seed-on-first-login=true",
        "seed.admin.email="
})
class WorkspaceCreationTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired DemoDataService demoDataService;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;

    @Test
    void createWorkspacePersistsAndStampsOnboarding() {
        var user = newUser();

        var resp = workspaceService.create(user, new CreateWorkspaceRequest("My Team"));

        // The workspace must actually be in the DB (the bug dropped the insert)
        assertThat(workspaceRepository.findById(resp.id())).isPresent();
        // …and onboarding is now stamped
        assertThat(userRepository.findById(user.getId()).orElseThrow().getOnboardedAt()).isNotNull();
    }

    @Test
    void firstLoginDemoSeedingCreatesWorkspaceAndProject() {
        var user = newUser();

        // This is the exact flow that failed: create workspace then create a
        // project in it, all in one transaction.
        demoDataService.seedOnFirstLogin(user.getId());

        assertThat(workspaceService.listForUser(user))
                .anyMatch(w -> w.name().equals("Demo Workspace"));
    }

    @Test
    void demoSeedingDoesNotCompleteOnboarding() {
        var user = newUser();

        // The demo workspace is auto-provisioned — a Cloud user must still land
        // on the welcome screen, so onboarding stays incomplete.
        demoDataService.seedOnFirstLogin(user.getId());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getOnboardedAt()).isNull();
    }

    private User newUser() {
        var user = new User();
        user.setEmail("ws-" + System.nanoTime() + "@example.com");
        user.setDisplayName("WS Test");
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}
