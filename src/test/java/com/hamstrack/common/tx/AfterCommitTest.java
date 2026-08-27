package com.hamstrack.common.tx;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>{@link AfterCommit}'s four promises, driven rather than read</strong> (HD-181).
 *
 * <p>The class carries a long argument and, until this file, no measurement of any of it. Each
 * promise below is a separate failure mode with its own silence, and three of the four are silent
 * <em>by construction</em> — a dropped effect throws nothing, logs nothing and leaves no row, so
 * the only way any of this can be known to hold is by counting how many times a lambda ran:
 *
 * <ol>
 *   <li><strong>Never on rollback.</strong> The headline. A rollback that still delivered would put
 *       a live reset link in a stranger's inbox for a {@code password_resets} row that never
 *       existed.</li>
 *   <li><strong>Always, even with no transaction.</strong> The mirror-image drop: a caller reached
 *       from a non-transactional path whose effect silently never happens. Same decision, same
 *       reason, as {@code SseEventListener}'s {@code fallbackExecution = true}.</li>
 *   <li><strong>Exactly once when published from inside another effect</strong> (round 2). Spring
 *       iterates a <em>snapshot</em> of the synchronization list, so a registration made during
 *       {@code afterCommit} is not in it and never receives that callback — and synchronization is
 *       still active in that window, so the inline branch does not fire either. A registration
 *       implementing only {@code afterCommit} therefore drops the effect. Implementing both
 *       callbacks fixes it, and "both" is exactly how a fix of this shape turns into a
 *       double-send — hence <em>exactly</em> once, asserted in both directions by one count.</li>
 *   <li><strong>Nothing queued behind a throw is lost.</strong> Two shapes: a throw out of somebody
 *       else's {@code afterCommit} (Spring's {@code invokeAfterCommit} does not catch, so the rest
 *       of that iteration is abandoned), and a throw out of an effect of this class (which must not
 *       leave {@code commit()} at all, or a committed transaction is reported to its caller as a
 *       500).</li>
 * </ol>
 *
 * <p>{@code RunOnceAfterCommit} is private, so everything here is observed through a counter and a
 * transaction — which is the right level anyway: the promise is about what runs, not about which
 * callback ran it.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class AfterCommitTest {

    @Autowired TransactionTemplate txTemplate;

    // ================================================================ 1. never on rollback

    /**
     * <strong>The headline behaviour of the whole ticket.</strong> Rollback is forced by the most
     * ordinary cause there is — something below the publish throws — which is the shape of every
     * real one: a constraint violation, a late refusal, a statement cancelled at the bound
     * {@code BoundedJpaTransactionManager} applies.
     */
    @Test
    void anEffectIsNotPublishedWhenTheTransactionRollsBackFromAThrow() {
        var ran = new AtomicInteger();

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            AfterCommit.run("an effect published by work that is about to fail", ran::incrementAndGet);
            throw new IllegalStateException("a late refusal, as a constraint violation would be");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ran)
                .as("the transaction ROLLED BACK and the effect ran anyway. This is HD-181's whole "
                    + "subject: for the three mailers it means a live verification / reset / invite "
                    + "link is now in somebody's inbox for a token row that does not exist, and "
                    + "nothing can unsend it. Whatever registered it must be registered ON the "
                    + "commit, not merely called late in the method.")
                .hasValue(0);
    }

    /**
     * The other way a transaction ends up rolled back, and it is not reducible to the first: nothing
     * throws, the caller simply refuses to have it. {@code STATUS_ROLLED_BACK} arrives through
     * {@code afterCompletion} — the same callback the round-2 fix added — so a fix that ran the
     * effect on <em>any</em> completion status rather than on {@code STATUS_COMMITTED} passes the
     * test above and fails this one.
     */
    @Test
    void anEffectIsNotPublishedWhenTheTransactionIsMarkedRollbackOnly() {
        var ran = new AtomicInteger();

        txTemplate.executeWithoutResult(status -> {
            AfterCommit.run("an effect published inside a doomed transaction", ran::incrementAndGet);
            status.setRollbackOnly();
        });

        assertThat(ran)
                .as("a rollback that nobody threw for is still a rollback, and afterCompletion is "
                    + "told about it. The completion callback must run the effect only for "
                    + "STATUS_COMMITTED — STATUS_ROLLED_BACK is the case this class exists for, and "
                    + "STATUS_UNKNOWN is a commit whose outcome nobody knows, which is precisely "
                    + "what must not be announced.")
                .hasValue(0);
    }

    // ================================================================ 2. on commit, once, after it

    /**
     * The positive direction, and the ordering inside it. The assertion made <em>inside</em> the
     * transaction body is the one that distinguishes this fix from the code it replaced: the old
     * call sites also ended up sending, they just sent too early.
     */
    @Test
    void anEffectRunsExactlyOnceAndOnlyAfterTheCommit() {
        var ran = new AtomicInteger();

        txTemplate.executeWithoutResult(status -> {
            AfterCommit.run("an effect published by work that will commit", ran::incrementAndGet);
            assertThat(ran)
                    .as("the effect ran DURING the transaction. Deferral is the entire mechanism: "
                        + "an effect that runs here is one a rollback taken afterwards cannot take "
                        + "back, which is the bug, not the fix.")
                    .hasValue(0);
        });

        assertThat(ran)
                .as("an effect published inside a committing transaction must be delivered exactly "
                    + "once — a second delivery is a second email")
                .hasValue(1);
    }

    /**
     * No transaction, so there is nothing to order against and nothing that can roll back: the
     * effect runs immediately. Without this branch every call site reached from a non-transactional
     * path would silently stop working — a worse failure than the one being prevented, and an
     * invisible one.
     */
    @Test
    void anEffectPublishedWithNoTransactionRunsInline() {
        var ran = new AtomicInteger();

        assertThat(TransactionSynchronizationManager.isSynchronizationActive())
                .as("this test's premise: nothing is bound here, so the inline branch is the one "
                    + "under test")
                .isFalse();

        AfterCommit.run("an effect published outside any transaction", ran::incrementAndGet);

        assertThat(ran)
                .as("with no synchronization active there is nothing to register on. An effect "
                    + "dropped here is dropped forever and says nothing — the failure mode that "
                    + "makes AttachmentService.upload (deliberately non-transactional) and every "
                    + "future non-transactional caller silently stop having side effects.")
                .hasValue(1);
    }

    // ================================================================ 3. effect inside an effect

    /**
     * <strong>Round 2's finding.</strong> {@code AfterCommit.run} called from inside a running
     * effect used to be dropped in silence, in the one class whose subject is effects dropped in
     * silence: the late registration is absent from the snapshot {@code triggerAfterCommit}
     * iterates, and {@code isSynchronizationActive()} is still true so the inline branch above does
     * not fire either.
     *
     * <p>One count seals both directions. A registration implementing only {@code afterCommit}
     * leaves the inner effect at 0; one implementing both callbacks <em>without</em> run-once
     * semantics delivers the OUTER effect twice — same email, sent twice, from a fix for a
     * different bug.
     */
    @Test
    void anEffectPublishedFromInsideAnotherEffectRunsExactlyOnce() {
        var outer = new AtomicInteger();
        var inner = new AtomicInteger();

        txTemplate.executeWithoutResult(status ->
                AfterCommit.run("the outer effect", () -> {
                    outer.incrementAndGet();
                    AfterCommit.run("an effect published from inside another effect",
                            inner::incrementAndGet);
                }));

        assertThat(inner)
                .as("an effect published from INSIDE another effect. 0 means it was dropped: "
                    + "Spring triggers afterCommit over a snapshot of the synchronization list, so "
                    + "a registration made while that snapshot is being iterated never receives "
                    + "afterCommit — and synchronization is still active there, so the inline "
                    + "branch does not fire either. The registration must also implement "
                    + "afterCompletion, whose snapshot is taken later and does contain it. "
                    + "2 means the opposite mistake: both callbacks reached the same effect and "
                    + "nothing made it run once.")
                .hasValue(1);
        assertThat(outer)
                .as("and adding the second callback must not double-deliver the ORDINARY effect, "
                    + "which is what a run-once flag is for. 2 here is one email sent twice.")
                .hasValue(1);
    }

    // ================================================================ 4. nothing behind a throw

    /**
     * <strong>An effect ordered behind a callback that throws out of {@code afterCommit} still
     * runs.</strong> The asymmetry is Spring's: {@code invokeAfterCommit} does not catch, so the
     * first throw abandons the rest of that iteration, while {@code invokeAfterCompletion} catches
     * {@code Throwable} per synchronization. Before round 2 everything queued behind such a throw
     * was stranded with nothing said; the completion callback is now the net.
     *
     * <p>The thrower is a raw {@link TransactionSynchronization} on purpose — this class swallows
     * its own effects' exceptions, so the hazard can only ever come from a <em>neighbour</em>: an
     * {@code @TransactionalEventListener}, another synchronization, anything registered earlier in
     * the same transaction.
     */
    @Test
    void anEffectQueuedBehindACallbackThatThrowsOutOfAfterCommitStillRuns() {
        var ran = new AtomicInteger();

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            // Registered FIRST, so it is ahead of the effect in the snapshot: neither is Ordered,
            // and getSynchronizations() sorts stably, so registration order is the order.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new IllegalStateException("a neighbouring afterCommit callback failing");
                }
            });
            AfterCommit.run("the effect queued behind a failing neighbour", ran::incrementAndGet);
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ran)
                .as("a NEIGHBOUR's afterCommit threw, and this effect — which had nothing to do "
                    + "with it — was never delivered. Spring's invokeAfterCommit does not catch, "
                    + "so one throw strands everything queued behind it; invokeAfterCompletion "
                    + "catches per synchronization, which is why implementing afterCompletion as "
                    + "well is what closes this. The transaction is committed, so the mail it "
                    + "announces is owed.")
                .hasValue(1);
    }

    /**
     * An effect of this class must not throw out of {@code commit()}: the callbacks run after
     * {@code doCommit} and outside any {@code catch}, so a throw becomes a 500 for a transaction
     * that committed <em>and</em> strands every synchronization behind it. Both halves are asserted
     * — the caller sees no exception, and the next effect is still delivered.
     */
    @Test
    void aThrowingEffectNeitherFailsTheCallerNorStopsTheNextEffect() {
        var second = new AtomicInteger();

        assertThatCode(() -> txTemplate.executeWithoutResult(status -> {
            AfterCommit.run("an effect that fails", () -> {
                throw new IllegalStateException("SMTP is down");
            });
            AfterCommit.run("the effect behind it", second::incrementAndGet);
        })).as("a failed effect must never reach the caller: the database change it accompanies is "
               + "COMMITTED and the caller has already been told so, so a throw here converts a "
               + "missing side effect into a false failure report")
                .doesNotThrowAnyException();

        assertThat(second)
                .as("and it must not take its neighbours with it")
                .hasValue(1);
    }

    /**
     * The {@code catch} is on {@code Throwable} and the width is load-bearing: what must not escape
     * is a <em>throw</em>, whatever its class. A lazily linked {@code NoClassDefFoundError} on the
     * mail path is the realistic one, and it produces exactly the outcome the class forbids — out
     * of {@code commit()} by a route with no catch.
     */
    @Test
    void anErrorIsSwallowedToo() {
        var second = new AtomicInteger();

        assertThatCode(() -> txTemplate.executeWithoutResult(status -> {
            AfterCommit.run("an effect that fails to link", () -> {
                throw new NoClassDefFoundError("some/mail/Class");
            });
            AfterCommit.run("the effect behind it", second::incrementAndGet);
        })).as("narrowing the catch to RuntimeException reopens the hole for the one class of "
               + "failure most likely to arrive from a lazily linked mail dependency")
                .doesNotThrowAnyException();

        assertThat(second).hasValue(1);
    }

    /**
     * Order of delivery, which nothing else here pins: effects are delivered in the order they were
     * published. Not a promise the class makes in prose, and that is the point of asserting it —
     * the run-once fix works by having two callbacks reach the same effects, and a version of it
     * that delivered some effects from {@code afterCommit} and the rest from {@code afterCompletion}
     * would silently reorder them.
     */
    @Test
    void effectsAreDeliveredInThePublishedOrder() {
        var order = new ArrayList<String>();

        txTemplate.executeWithoutResult(status -> {
            AfterCommit.run("first", () -> order.add("first"));
            AfterCommit.run("second", () -> order.add("second"));
            AfterCommit.run("third", () -> order.add("third"));
        });

        assertThat(order)
                .as("effects must be delivered in publication order — a fix that splits them "
                    + "across two callbacks can reorder them without dropping any, and nothing "
                    + "else in this file would notice")
                .isEqualTo(List.of("first", "second", "third"));
    }
}
