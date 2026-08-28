package com.hamstrack.workspace;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 §4.1 / AC 4 and AC 5 — the derived value, which is the class of defect no
 * annotation scan could ever have found.</strong>
 *
 * <p>{@code workspaces.slug} is {@code VARCHAR(100)} and nobody submits it: it is built from
 * {@code CreateWorkspaceRequest.name}, which is {@code @Size(min = 2, max = 255)}, by
 * {@code WorkspaceService.generateSlug} — which lowercased, substituted and never truncated. A
 * workspace name of 101 slug-safe characters therefore produced a 101-character slug and
 * {@code POST /api/workspaces} answered <strong>500</strong>, to <em>any</em> authenticated user, on
 * the first-run "Create a team" path. There was no annotated field anywhere to notice.
 *
 * <p>The fix truncates rather than widens: a slug is a URL identifier, not user content, and the
 * full name is stored beside it in {@code workspaces.name}, so clipping loses nothing a reader can
 * act on.
 *
 * <p><strong>AC 5 is the near-miss, and it is asserted separately because it is a different
 * bug.</strong> Truncating the base and then appending the collision suffix <em>on top of</em> the
 * 100 produces a 107-character slug for a name that collides — a "fixed" version that still 500s,
 * occasionally, only for people whose team name is already taken. The suffix must come out of the
 * 100.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class WorkspaceSlugLengthTest {

    /** {@code workspaces.slug VARCHAR(100)}. */
    private static final int SLUG_COLUMN_WIDTH = 100;

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void a200CharacterNameIsCreatedWithASlugTheColumnCanHold() throws Exception {
        var token = login(user());
        var name = uniqueName(200);

        var id = create(token, name);

        assertThat(slugOf(id)).hasSizeLessThanOrEqualTo(SLUG_COLUMN_WIDTH);
    }

    /**
     * The collision path: a 98-character name creates a 98-character slug, and the second workspace
     * of the same name must fit the suffix <em>inside</em> the 100 rather than beside it.
     */
    @Test
    void a98CharacterNameThatCollidesStillFitsTheColumn() throws Exception {
        var token = login(user());
        var name = uniqueName(98);

        var first = create(token, name);
        var second = create(token, name);

        assertThat(slugOf(first)).hasSizeLessThanOrEqualTo(SLUG_COLUMN_WIDTH);
        assertThat(slugOf(second))
                .as("""
                        the collision suffix must be taken OUT of the 100, not added on top of it. \
                        A version that truncates the base and then appends "-abc123" produces a \
                        105-character slug and 500s again — but only for the second person to \
                        choose a long team name, which is the hardest possible shape to reproduce.""")
                .hasSizeLessThanOrEqualTo(SLUG_COLUMN_WIDTH)
                .isNotEqualTo(slugOf(first));
    }

    // ------------------------------------------------------------------------------- fixtures

    /** Slug-safe characters only, so the name's length IS the slug's length before truncation. */
    private static String uniqueName(int length) {
        var unique = UUID.randomUUID().toString().replace("-", "");
        var name = (unique + "abcdefghijklmnopqrstuvwxyz".repeat(10));
        return name.substring(0, length);
    }

    private UUID create(String token, String name) throws Exception {
        var body = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    }

    private String slugOf(UUID id) {
        return workspaceRepository.findById(id).orElseThrow().getSlug();
    }

    private User user() {
        var u = new User();
        u.setEmail(("slug-" + System.nanoTime() + "-" + UUID.randomUUID().toString().substring(0, 6)
                    + "@example.com").toLowerCase());
        u.setDisplayName("Slug Test");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private String login(User u) throws Exception {
        var body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + u.getEmail() + "\",\"password\":\"test-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }
}
