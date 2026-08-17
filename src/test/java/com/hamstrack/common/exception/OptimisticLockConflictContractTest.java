package com.hamstrack.common.exception;

import com.hamstrack.issue.LabelTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>Losing an optimistic-lock race must answer 409, app-wide.</strong>
 *
 * <p>Why this class exists: only the <em>pre-check</em> in {@code IssueService.update} ever
 * produced a 409 — "does the client's {@code version} match the row I just loaded?" — and
 * that check structurally cannot cover the case it is named after. It compares against an
 * entity loaded in the same transaction, so a competing commit landing after that read slips
 * past it, and a client that omits {@code version} skips it altogether. Both fall through to
 * Hibernate's {@code ObjectOptimisticLockingFailureException} at flush, which no handler
 * declared — so the loser of every real race got a bare <strong>500</strong>. The write was
 * correctly rejected either way, so nothing was ever corrupted; the SPA was simply told to
 * render a crash for the one failure whose entire contract is "refresh and retry".
 *
 * <p>HD-132 widened that window (removing a member bumps {@code @Version} on every issue it
 * unassigns), which is how it was found — but the gap is app-wide and predates it: both
 * {@code Issue} and {@code Role} carry {@code @Version}.
 *
 * <p><strong>Why a probe controller rather than a real race.</strong> A genuine flush-time
 * conflict needs two <em>overlapping</em> transactions, and MockMvc cannot hold one open
 * across requests — a real interleaving would be a threaded, timing-dependent test of
 * Hibernate rather than of our mapping. What was missing here is the mapping, so that is what
 * is pinned, over the fully wired filter chain and advice ordering (the
 * {@code /api/__test/**} probe convention of {@link ValidationErrorContractTest}) rather than
 * a standalone MockMvc that would not prove the advice actually wins.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class OptimisticLockConflictContractTest extends LabelTestBase {

    static final String ORM_PATH = "/api/__test/conflict/orm";
    static final String DAO_PATH = "/api/__test/conflict/dao";

    /** The wording the SPA renders. Pinned because "retry" is the whole point of the status. */
    private static final String DETAIL = "modified by someone else";

    /**
     * The shape Hibernate actually throws: {@code ObjectOptimisticLockingFailureException}.
     * 409 with a {@code ProblemDetail} body carrying an actionable {@code detail} — not a
     * 500, and not an empty body.
     */
    @Test
    void aHibernateOptimisticLockFailureIsA409WithAnActionableDetail() throws Exception {
        conflict(ORM_PATH)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail", containsString(DETAIL)))
                // The regression this class exists for: never a 500, and never the bare
                // "no message available" body a 500 would carry.
                .andExpect(jsonPath("$.detail", not(containsString("Internal"))));
    }

    /**
     * The handler is declared on the Spring DAO superclass, so the plain-JDBC variant lands
     * on the same 409 rather than on whichever exception today's persistence path happens to
     * raise. If someone narrows it to the ORM subclass, this fails.
     */
    @Test
    void thePlainSpringDaoVariantIsMappedToo() throws Exception {
        conflict(DAO_PATH)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString(DETAIL)));
    }

    /**
     * The detail must not leak the entity class name. The handler cannot know what was
     * modified, and answering with an internal type name would put our persistence layout on
     * the wire for no benefit to the caller, whose next move is identical regardless.
     */
    @Test
    void theDetailDoesNotLeakTheEntityClassName() throws Exception {
        conflict(ORM_PATH)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", not(containsString("Issue"))))
                .andExpect(jsonPath("$.detail", not(containsString("com.hamstrack"))));
    }

    // ============================================================ helpers

    /** Authenticated like real traffic, so the request travels the production filter chain. */
    private ResultActions conflict(String path) throws Exception {
        var token = login(user());
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token));
    }

    @TestConfiguration
    static class ConflictProbeConfig {
        @Bean
        ConflictController conflictController() {
            return new ConflictController();
        }
    }

    /**
     * Under {@code /api/**} so it is authenticated and advised exactly like a real endpoint.
     * Throws from the handler method, which is where a commit-time optimistic failure also
     * surfaces from: the {@code @Transactional} service returns into the controller frame and
     * the transaction interceptor commits there, so the exception reaches the same advice.
     */
    @RestController
    static class ConflictController {

        @PostMapping(ORM_PATH)
        String orm() {
            // The real entity class on purpose: its name lands in the exception's own
            // message, so theDetailDoesNotLeakTheEntityClassName() would actually catch a
            // handler that echoed ex.getMessage() instead of writing its own detail.
            throw new ObjectOptimisticLockingFailureException(
                    com.hamstrack.issue.entity.Issue.class, UUID.randomUUID());
        }

        @PostMapping(DAO_PATH)
        String dao() {
            throw new OptimisticLockingFailureException("row was updated or deleted by another transaction");
        }
    }
}
