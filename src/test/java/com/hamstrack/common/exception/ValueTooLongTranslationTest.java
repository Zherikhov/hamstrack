package com.hamstrack.common.exception;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 §8 / AC 15 and AC 16 — the backstop branch, entered for real.</strong>
 *
 * <p>{@code GlobalExceptionHandler.handleDataIntegrityViolation} is SQLSTATE-gated, and HD-171 adds
 * exactly one state to the gate: {@code 22001 string_data_right_truncation} becomes a
 * <strong>400</strong> carrying {@code errorType: "VALUE_TOO_LONG"}, because the database is stating
 * unambiguously that it was handed a value longer than the column and there is no second direction
 * to guess at. Everything else — {@code 23505}, {@code 23502}, {@code 23514} — keeps today's
 * outcome, since each of those means the <em>application</em> believed a write was valid when it was
 * not, which is a fault whose remedy is a fix rather than a retry.
 *
 * <p><strong>Both halves have to be exercised, and the second is the one that is usually
 * skipped.</strong> A translation branch that is never entered looks exactly like one that works —
 * this project has already paid for that lesson once, with a {@code catch} around {@code save()}
 * that could never fire because the INSERT is deferred to commit. So AC 15 forces a genuine
 * {@code 22001} out of PostgreSQL, and AC 16 forces a genuine {@code 23505} and asserts the branch
 * did <strong>not</strong> widen to swallow it.
 *
 * <p><strong>Why a test-only controller.</strong> The point of HD-171 is that no request path can
 * still reach a {@code 22001}; a test that could produce one through a real endpoint would be
 * reporting a missing bound rather than exercising the handler. The two doors below therefore write
 * an entity directly, bypassing every DTO — and they use {@code saveAndFlush}, because
 * {@code save()} only queues the persist and the violation would otherwise be raised at commit,
 * outside the dispatcher and past the handler entirely.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
@Import(ValueTooLongTranslationTest.ForcedViolations.class)
class ValueTooLongTranslationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void aForced22001IsA400ThatNamesTheFailureAndNothingElse() throws Exception {
        var body = mockMvc.perform(post("/test-only/hd171/too-long"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorType").value("VALUE_TOO_LONG"))
                .andExpect(jsonPath("$.detail").value("Some of the text you submitted is too long."))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("""
                        Nothing the database said may reach the wire. The translated message that \
                        goes to the log carries the parameterised SQL — table and column names, \
                        every value a '?' — and that is deliberately for the operator, not for the \
                        client. A response that named the column or the width would hand a caller \
                        a map of the schema in exchange for nothing: the handler cannot even say \
                        WHICH field was too long, which is why the sentence is generic.""")
                .doesNotContain("varchar")
                .doesNotContain("character varying")
                .doesNotContain("slug")
                .doesNotContain("workspaces")
                .doesNotContain("insert")
                .doesNotContain("22001");
    }

    /**
     * <strong>The branch did not widen.</strong> A unique violation arrives as the same Spring type
     * through the same handler and must still be a 500 — folding it into a 400 here would silently
     * change the outcome of {@code issues_project_id_number_key} and several admin constraints at
     * once, each of which wants its own message and its own ticket.
     */
    @Test
    void aForced23505IsStillA500() throws Exception {
        mockMvc.perform(post("/test-only/hd171/duplicate"))
                .andExpect(status().isInternalServerError());
    }

    /**
     * Two doors that do what no production path is allowed to do any more. Mounted outside
     * {@code /api/**}, which {@code SecurityConfig} leaves {@code permitAll}, so neither needs a
     * token — the subject here is the handler, not authorization.
     */
    @TestConfiguration
    static class ForcedViolations {

        @Bean
        ForcedViolationController forcedViolationController(WorkspaceRepository workspaces,
                                                            UserRepository users) {
            return new ForcedViolationController(workspaces, users);
        }
    }

    @RestController
    @RequestMapping("/test-only/hd171")
    @RequiredArgsConstructor
    static class ForcedViolationController {

        private final WorkspaceRepository workspaces;
        private final UserRepository users;

        /** {@code workspaces.slug} is {@code VARCHAR(100)}; 150 characters is a real {@code 22001}. */
        @PostMapping("/too-long")
        void tooLong() {
            var ws = blank();
            ws.setSlug("s".repeat(150));
            workspaces.saveAndFlush(ws);
        }

        /** The same column is {@code UNIQUE}; the second insert is a real {@code 23505}. */
        @PostMapping("/duplicate")
        void duplicate() {
            var slug = "hd171-dup-" + UUID.randomUUID().toString().substring(0, 12);
            var first = blank();
            first.setSlug(slug);
            workspaces.saveAndFlush(first);

            var second = blank();
            second.setSlug(slug);
            workspaces.saveAndFlush(second);
        }

        private Workspace blank() {
            var creator = new User();
            creator.setEmail(("forced-" + System.nanoTime() + "-"
                              + UUID.randomUUID().toString().substring(0, 6) + "@example.com")
                    .toLowerCase());
            creator.setDisplayName("Forced Violation");
            creator.setStatus(UserStatus.ACTIVE);
            creator.setSystemRole(SystemRole.USER);
            var ws = new Workspace();
            ws.setName("HD-171 forced");
            ws.setCreatedBy(users.save(creator));
            return ws;
        }
    }
}
