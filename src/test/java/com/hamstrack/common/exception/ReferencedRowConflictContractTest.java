package com.hamstrack.common.exception;

import com.hamstrack.issue.LabelTestBase;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.entity.Status;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * HD-13 / AC-6 — <strong>a {@code 23503} is a 409 with a remedy in it, never a 500.</strong>
 *
 * <p>{@code V19__issues_taxonomy_fk.sql} adds {@code issues_type_id_fkey} and
 * {@code issues_status_id_fkey}, which creates a new way for PostgreSQL to refuse a write.
 * Before this contract existed, {@code GlobalExceptionHandler} declared no handler for
 * {@code DataIntegrityViolationException} and neither does Boot's
 * {@code ResponseEntityExceptionHandler}, so that refusal fell through to the container
 * {@code /error} and answered <strong>500</strong> with a body naming nothing an
 * administrator could act on. That is the <em>fourth</em> instance of one defect in one
 * release — the optimistic lock, the lock timeout and the cancelled statement are the other
 * three, and each was a database-level condition with a documented outcome escaping as a
 * crash because nothing bound it.
 *
 * <h2>Why a probe endpoint rather than a shipped one</h2>
 * <strong>No shipped endpoint can currently be made to raise {@code 23503} on demand, and
 * that is by design rather than by luck.</strong> Every catalog delete pre-checks with an
 * <em>unscoped</em> count ({@code issueRepository.countByStatus} and its two siblings) and
 * remaps before deleting, so the service refuses first and the database never has to; the
 * workflow and set deletes do the same with their own counts. This handler therefore exists
 * for the path nobody has written yet — a workspace purge, a demo-data teardown, a
 * {@code DELETE /projects/{id}} — which is exactly a condition that must be provoked
 * directly. The house pattern for that is {@code OptimisticLockConflictContractTest}'s
 * {@code /api/__test/**} probe, and this file follows it, so the request travels the
 * production filter chain and the production advice ordering.
 *
 * <p>The difference from that file, and it is the point: this probe raises a
 * <strong>real</strong> {@code 23503} out of PostgreSQL rather than constructing the Spring
 * exception. The handler branches on {@code getMostSpecificCause()}'s SQLSTATE, so a
 * hand-built exception would not exercise the one line most likely to be wrong.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ReferencedRowConflictContractTest extends LabelTestBase {

    /** Untranslated: an {@code EntityManager} write, which reaches the advice as Hibernate's type. */
    static final String STRAND_PATH = "/api/__test/integrity/strand/{issueId}";

    /** Translated: {@code JdbcTemplate} applies Spring's {@code SQLExceptionTranslator}. */
    static final String STRAND_TRANSLATED_PATH = "/api/__test/integrity/strand-translated/{issueId}";

    static final String UNIQUE_PATH = "/api/__test/integrity/not-a-foreign-key";

    /**
     * The status id the probes point an issue at. It names no row in {@code statuses}, so the FK
     * refuses — and <strong>this is the value PostgreSQL discloses</strong>, because its
     * {@code DETAIL} on a {@code 23503} names the offending KEY:
     * {@code Key (status_id)=(dead0000-…)=is not present in table "statuses"}.
     *
     * <p>Fixed rather than {@code gen_random_uuid()} for exactly that reason. While the SQL
     * generated the id server-side, the test had no handle on the one string the leak-guard
     * needed to check, and checked the issue id instead — which the database never mentions,
     * since it is only the {@code WHERE}-clause target. A leak-guard must assert on <em>the value
     * the database chose to disclose</em>, not on the value the test happened to have.
     */
    static final String ABSENT_STATUS_ID = "dead0000-0000-7000-8000-00000000beef";

    /** A 23503 whose SQLException is itself wrapping something with no SQLSTATE. */
    static final String NESTED_PATH = "/api/__test/integrity/nested-cause";

    /** Commit-time: the failure surfaces from the transaction commit, nested in a wrapper. */
    static final String COMMIT_PATH = "/api/__test/integrity/commit-time/{issueId}";

    /**
     * The whole contract, driven by a genuine foreign-key violation on one of the two
     * constraints V19 adds — reached by the <strong>untranslated</strong> route, which is the
     * one that has caught this codebase out three times already: an {@code EntityManager} write
     * goes through none of Spring's exception translation, so Spring's DAO type never appears.
     *
     * <p><strong>What arrives, read out of the source rather than inferred.</strong> This
     * paragraph said the exception arrives as a bare {@code PersistenceException} wrapping
     * Hibernate's {@code ConstraintViolationException}, and that is <em>false</em>. In
     * {@code hibernate-core-7.4.1.Final} (the version Boot 4.1.0 pins),
     * {@code ExceptionConverterImpl.convert(HibernateException, LockOptions)} is a chain of
     * {@code instanceof} branches that {@code ConstraintViolationException} matches none of; it
     * falls to the final {@code else}, {@code rollbackIfNecessary(exception); return exception;},
     * and propagates <strong>unwrapped</strong>. The wrapping sentence is true of the
     * {@code org.hibernate.QueryTimeoutException} branch, which really does wrap when the
     * transaction is marked for rollback, and it was carried across from that neighbour. So the
     * handler's second binding matches this <em>directly</em>, not by cause-fallback — and the
     * test would have kept passing while stating the wrong mechanism, which is why the mechanism
     * is now written here and not only in the handler.
     *
     * <p>A 500 here means the second binding was dropped as redundant, or that
     * {@code sqlStateOf} stopped walking the cause chain. It walks rather than inspecting the
     * bound argument because <strong>a handler that branches on the bound exception's type must
     * not assume that type is the one it declared</strong>: Spring binds the first candidate in
     * {@code [thrown, cause, …]} assignable to the parameter, so a wrapper wins whenever one
     * exists — and one does exist on the commit path, where
     * {@code ExceptionConverterImpl.convertCommitException} nests the cause inside a
     * {@code RollbackException}.
     */
    @Test
    void aForeignKeyViolationIsA409ThatPrescribesSomethingTheReaderCanDo() throws Exception {
        var ctx = newProject();
        var issueId = createIssue(ctx, "HD-13 backstop fixture").get("id").asText();

        var response = mockMvc.perform(post(STRAND_PATH, issueId)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("""
                        the database refused a write that would have stranded an issue — a \
                        correct, documented outcome, not a crash. A 500 here means the \
                        DataIntegrityViolationException handler is gone, or that something \
                        registered ahead of GlobalExceptionHandler (which sits at \
                        HIGHEST_PRECEDENCE + 100 precisely so Boot's order-0 advice cannot \
                        take these first).""")
                .isEqualTo(409);

        var body = response.getContentAsString();
        assertThat(body)
                .as("a client must be able to branch on which 409 this is without parsing prose: "
                    + "the optimistic-lock and lock-contention refusals are also 409s")
                .contains("\"errorType\":\"REFERENCE_CONSTRAINT_VIOLATION\"");
        assertThat(body)
                .as("""
                        THE ASSERTION THIS FILE GOT WRONG ONCE, AND THE REASON IT IS NOW NEGATIVE. \
                        A 23503 arrives in two opposite directions. A parent delete refused ("is \
                        still referenced from table issues") can be answered with remap-or-archive. \
                        A child write refused ("is not present in table statuses") cannot: the \
                        referenced row does not exist, nothing is being deleted, and neither remedy \
                        is an action its reader could take. The probe above runs \
                        UPDATE issues SET status_id = gen_random_uuid(), which is purely the second \
                        kind — and the earlier version of this test asserted the body contained \
                        "replacement" and "archive", under a comment about only prescribing \
                        actions the reader can perform. It asserted the presence of exactly the \
                        remedy its own scenario cannot perform. The reachable production case is \
                        the same direction: an ordinary member creating an issue whose status was \
                        deleted concurrently between resolve and flush.""")
                .doesNotContain("replaceWithId")
                .doesNotContain("archive");
        assertThat(body)
                .as("a non-blank detail is the entire difference between this and the 500 it "
                    + "replaced, and it must be true in BOTH directions — so it describes the "
                    + "conflict and suggests re-reading, which is valid whichever way it came")
                .contains("\"detail\"")
                .contains("reload");

        assertThat(body)
                .as("""
                        NOTHING THE DATABASE SAID MAY REACH THE WIRE, and the value that proves it \
                        is ABSENT_STATUS_ID — the offending KEY, which is what PostgreSQL's DETAIL \
                        on a 23503 actually names ("Key (status_id)=(…) is not present in table \
                        statuses"). The constraint name is an internal identifier, the SQL names \
                        our tables, and on a parent-delete violation the key would be an id \
                        belonging to whichever tenant owns it. All of it belongs in the WARN log, \
                        in the shape handlePessimisticLock uses.

                        This assertion used to check the ISSUE id, which the database never \
                        mentions — it is only the WHERE-clause target — so it proved nothing about \
                        leaking and failed for an unrelated reason (see the note below on \
                        `instance`). THE RULE THAT COST: a leak-guard must assert on the value the \
                        database CHOSE TO DISCLOSE, not on the value the test happened to have a \
                        handle on.""")
                .doesNotContain("issues_status_id_fkey")
                .doesNotContain("issues_type_id_fkey")
                .doesNotContain("constraint")
                .doesNotContain("23503")
                .doesNotContain("UPDATE")
                .doesNotContain("update issues")
                .doesNotContain(ABSENT_STATUS_ID);

        // The scan is deliberately WHOLE-BODY, including `instance`, and that field is not an
        // exemption anybody needs to remember: ProblemDetail.forStatusAndDetail leaves `instance`
        // unset, so Spring auto-populates it from the REQUEST URI. It can therefore only ever
        // contain what this caller already sent — it is an echo, not a disclosure.
        //
        // It is worth knowing because it made this test fail while the handler was correct: the
        // probe path is /api/__test/integrity/strand/{issueId}, so the issue id was in `instance`
        // by construction, and the old .doesNotContain(issueId) could never have passed no matter
        // what the handler did. ABSENT_STATUS_ID appears in no URL, so a pass now means something.
        assertThat(body)
                .as("`instance` should echo the request URI — if it stops doing so this comment's "
                    + "reasoning about why the whole-body scan is safe no longer holds")
                .contains("/api/__test/integrity/strand/");
    }

    /**
     * The same real {@code 23503}, reached by the <strong>translated</strong> route — which is
     * how every path in the product that can actually raise one runs today, since the catalog
     * deletes all go through Spring Data repositories and commit-time flushes.
     *
     * <p>Both routes are tested because the handler needs both bindings and a reviewer
     * removing either one would still see the other test pass. The status must be identical:
     * a caller has no way to know which internal API the server used, so the refusal cannot
     * depend on it.
     */
    @Test
    void theTranslatedSpellingOfTheSameViolationGetsTheSame409() throws Exception {
        var ctx = newProject();
        var issueId = createIssue(ctx, "HD-13 backstop fixture (translated)").get("id").asText();

        var response = mockMvc.perform(post(STRAND_TRANSLATED_PATH, issueId)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("Spring's DataIntegrityViolationException — the binding the ticket specified "
                    + "— must reach the same 409 as Hibernate's untranslated one")
                .isEqualTo(409);
        assertThat(response.getContentAsString())
                .contains("\"errorType\":\"REFERENCE_CONSTRAINT_VIOLATION\"");
    }

    /**
     * The other half of the branch, and the one that keeps this ticket's blast radius honest.
     *
     * <p>{@link DataIntegrityViolationException} is a wide family: {@code 23505} unique
     * violations, {@code 23502} not-null violations and {@code 23514} check violations all
     * arrive as the same Spring type. Folding them into this 409 would silently change the
     * outcome of {@code issues_project_id_number_key} and several admin unique constraints at
     * once, each of which wants a different sentence — a separate decision for a separate
     * ticket. So everything that is not {@code 23503} keeps exactly today's status.
     */
    @Test
    void aDataIntegrityViolationThatIsNotAForeignKeyViolationStillAnswers500() throws Exception {
        var ctx = newProject();

        var response = mockMvc.perform(post(UNIQUE_PATH)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("""
                        a 409 here means the handler stopped branching on SQLSTATE and now \
                        answers the whole DataIntegrityViolationException family the same way. \
                        That is an app-wide behaviour change wearing a foreign-key message: a \
                        duplicate issue number would tell an administrator to "delete it with a \
                        replacement, or archive it instead", which is not a thing they can do \
                        about a unique violation.""")
                .isEqualTo(500);
        assertThat(response.getContentAsString())
                .as("and it must not have picked up the foreign-key discriminator on the way")
                .doesNotContain("REFERENCE_CONSTRAINT_VIOLATION");
    }

    /**
     * <strong>The only assertion in this file that distinguishes the shipped cause-walk from
     * {@code NestedExceptionUtils.getMostSpecificCause}</strong>, which is the "simplification" a
     * future reader is most likely to reach for, having noticed Spring already ships a helper.
     *
     * <p>Mapped against the two probes above, three implementations are indistinguishable:
     * <ul>
     *   <li>a naive {@code ex instanceof SQLException} — passes {@code strand}, fails
     *       {@code strand-translated};</li>
     *   <li>{@code getMostSpecificCause(ex)} — passes both;</li>
     *   <li>the shipped walk — passes both.</li>
     * </ul>
     * So nothing there separates the last two. This does: the {@code SQLException} that carries
     * {@code 23503} is itself wrapping an {@code IOException}, which carries no SQLSTATE. The
     * <em>most specific</em> cause is therefore the {@code IOException} and the state is lost —
     * 500. The walk stops at the <em>first</em> carrier it meets and answers 409.
     *
     * <p>That is also the correct reading of why the walk exists, and it is the opposite of what
     * this ticket first wrote down: not "most-specific-cause cannot reach Hibernate's spelling"
     * (it can — a {@code JDBCException} always keeps its {@code SQLException} as its cause), but
     * "most-specific-cause goes too deep and walks past the answer".
     */
    @Test
    void theStateIsFoundEvenWhenTheSqlExceptionItselfWrapsSomething() throws Exception {
        var ctx = newProject();

        var response = mockMvc.perform(post(NESTED_PATH)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("""
                        500 here means sqlStateOf stopped walking and started asking for the \
                        deepest cause. The SQLSTATE is on the SQLException in the MIDDLE of this \
                        chain, not at the bottom: getMostSpecificCause would return the \
                        IOException underneath it and find no state at all. Take the FIRST \
                        carrier, not the last.""")
                .isEqualTo(409);
        assertThat(response.getContentAsString())
                .contains("\"errorType\":\"REFERENCE_CONSTRAINT_VIOLATION\"");
    }

    /**
     * The <strong>commit-time</strong> arrival, which is the shape the second binding and the
     * cause-walk were justified by and which nothing else here exercises.
     *
     * <p>No explicit flush: a managed issue is pointed at a status id that does not exist and the
     * transaction is allowed to commit. The failure therefore surfaces from the commit, where
     * {@code ExceptionConverterImpl.convertCommitException} nests the converted cause inside a
     * {@code RollbackException} — the one path in Hibernate that really does wrap this exception,
     * as opposed to the one this file used to claim did.
     *
     * <p>Deliberately makes no assertion about <em>which</em> type arrives. Spring's
     * {@code JpaTransactionManager} normally translates a commit failure into the DAO type first,
     * so the value of this test is that the outcome is a 409 <em>however</em> the nesting lands —
     * which is exactly the property a backstop is supposed to have.
     */
    @Test
    void aCommitTimeViolationIsAlsoA409() throws Exception {
        var ctx = newProject();
        var issueId = createIssue(ctx, "HD-13 commit-time fixture").get("id").asText();

        var response = mockMvc.perform(post(COMMIT_PATH, issueId)
                        .header("Authorization", "Bearer " + ctx.token()))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("""
                        a foreign-key violation that surfaces at COMMIT rather than at flush must \
                        reach the same 409. This is the arrival the cause-walk was justified by: \
                        convertCommitException wraps the converted cause in a RollbackException, \
                        so whatever binds to the handler's parameter may be several levels above \
                        the exception carrying the SQLSTATE.""")
                .isEqualTo(409);
        assertThat(response.getContentAsString())
                .contains("\"errorType\":\"REFERENCE_CONSTRAINT_VIOLATION\"");
    }

    // ============================================================ the probe

    @TestConfiguration
    static class IntegrityProbeConfig {

        @Bean
        IntegrityProbeService integrityProbeService(EntityManager entityManager,
                                                    JdbcTemplate jdbcTemplate) {
            return new IntegrityProbeService(entityManager, jdbcTemplate);
        }

        @Bean
        IntegrityProbeController integrityProbeController(IntegrityProbeService service) {
            return new IntegrityProbeController(service);
        }
    }

    /**
     * Under {@code /api/**} so the request is authenticated and advised exactly like a real
     * endpoint — a standalone MockMvc would not prove that our advice actually beats Boot's.
     */
    @RestController
    @RequiredArgsConstructor
    static class IntegrityProbeController {

        private final IntegrityProbeService service;

        @PostMapping(STRAND_PATH)
        String strand(@PathVariable UUID issueId) {
            service.strandTheIssue(issueId);
            return "the constraint did not fire";
        }

        @PostMapping(STRAND_TRANSLATED_PATH)
        String strandTranslated(@PathVariable UUID issueId) {
            service.strandTheIssueTranslated(issueId);
            return "the constraint did not fire";
        }

        @PostMapping(COMMIT_PATH)
        String commitTime(@PathVariable UUID issueId) {
            service.strandTheIssueAtCommit(issueId);
            return "the constraint did not fire";
        }

        @PostMapping(NESTED_PATH)
        String nestedCause() {
            // The SQLSTATE is in the MIDDLE of the chain: DataIntegrityViolationException →
            // SQLException("23503") → IOException. getMostSpecificCause() returns the
            // IOException, which carries no state; the walk stops at the SQLException.
            // Constructed rather than provoked because no driver produces this shape on demand —
            // but the shape is real (a connection failure surfacing beneath a wrapped SQL error),
            // and what is under test is the search order, not the database.
            throw new DataIntegrityViolationException("could not execute statement",
                    new SQLException("FK violation", "23503", new IOException("connection reset")));
        }

        @PostMapping(UNIQUE_PATH)
        String notAForeignKey() {
            // A DataIntegrityViolationException whose most specific cause carries a NON-23503
            // state. Constructed rather than provoked because what is under test is the
            // branch, not the database: any real 23505 in this codebase is already caught and
            // translated at its call site (ComponentService, LabelService, SprintService),
            // so none of them would ever reach the advice to be measured here.
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint",
                    new SQLException("duplicate key", "23505"));
        }
    }

    /**
     * A separate bean rather than a method on the controller, for the reason
     * {@code StatementBudgetRefusalTest}'s probe is: {@code @Transactional} is proxy-applied,
     * so a self-invocation would open no transaction and the failure would arrive by a
     * different route than the one production takes. Carries no stereotype annotation, so it
     * adds nothing another test's component scan could pick up.
     */
    @RequiredArgsConstructor
    static class IntegrityProbeService {

        private final EntityManager entityManager;
        private final JdbcTemplate jdbcTemplate;

        /**
         * The statement both probes run: point a real issue at a status id that names no row.
         * That is exactly what {@code issues_status_id_fkey} exists to refuse, so the error is
         * PostgreSQL's own rather than anything this test invented. The id is a bound
         * parameter, so the fixture is not concatenated into SQL.
         */
        private static final String STRAND_SQL =
                "UPDATE issues SET status_id = '" + ABSENT_STATUS_ID + "' WHERE id = ";

        /**
         * The <strong>untranslated</strong> route. An {@code EntityManager} call is not wrapped
         * by Spring's exception translation: {@code ExceptionConverterImpl} produces a bare
         * {@code PersistenceException} over Hibernate's {@code ConstraintViolationException},
         * which reaches the advice only through its second binding.
         *
         * <p>The transaction is doomed by the violation and rolls back, so nothing is left
         * behind for the rest of the suite.
         */
        @Transactional
        public void strandTheIssue(UUID issueId) {
            entityManager.createNativeQuery(STRAND_SQL + ":id")
                    .setParameter("id", issueId)
                    .executeUpdate();
            entityManager.flush();
        }

        /**
         * The <strong>translated</strong> route, which is how every path in the product that
         * can raise a {@code 23503} actually runs. {@code JdbcTemplate} applies Spring's
         * {@code SQLExceptionTranslator} directly, giving the {@code DataIntegrityViolationException}
         * the ticket specified — the same type a Spring Data repository or a commit-time flush
         * through {@code HibernateJpaDialect} produces.
         *
         * <p>No {@code @Transactional}: the statement fails on its own, and leaving the
         * transaction out is what keeps this probe from sharing the one above's rollback and
         * therefore from proving less than it appears to.
         */
        public void strandTheIssueTranslated(UUID issueId) {
            jdbcTemplate.update(STRAND_SQL + "?", issueId);
        }

        /**
         * The <strong>commit-time</strong> route: mutate a managed entity and return, so the
         * write happens when the transaction interceptor commits rather than inside any call the
         * method makes. {@code getReference} deliberately does not hit the database — the row it
         * names does not exist — so nothing fails until the FK is checked at commit.
         */
        @Transactional
        public void strandTheIssueAtCommit(UUID issueId) {
            var issue = entityManager.find(Issue.class, issueId);
            issue.setStatus(entityManager.getReference(Status.class, UUID.randomUUID()));
        }
    }
}
