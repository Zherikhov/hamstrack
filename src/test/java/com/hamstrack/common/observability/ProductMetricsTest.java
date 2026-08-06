package com.hamstrack.common.observability;

import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.workspace.dto.CreateWorkspaceRequest;
import com.hamstrack.workspace.service.WorkspaceService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the business counters wired in Phase 4 actually move through the
 * {@link MeterRegistry}, and that {@code workspaces.created} carries the bounded
 * {@code source} label (never a workspace/user id).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "seed.admin.email="
})
class ProductMetricsTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired UserRepository userRepository;
    @Autowired MeterRegistry registry;

    @Test
    void workspaceCreatedCounterIncrementsWithUserSource() {
        var user = newUser();

        double before = counter("source", "user");
        workspaceService.create(user, new CreateWorkspaceRequest("Metrics Team"));
        double after = counter("source", "user");

        assertThat(after).isEqualTo(before + 1);
    }

    private double counter(String labelKey, String labelValue) {
        var c = registry.find("hamstrack.workspaces.created").tag(labelKey, labelValue).counter();
        return c == null ? 0d : c.count();
    }

    private User newUser() {
        var user = new User();
        user.setEmail("metrics-" + System.nanoTime() + "@example.com");
        user.setDisplayName("Metrics Test");
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}
