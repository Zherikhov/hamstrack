package com.hamstrack.common.persistence;

import com.hamstrack.issue.LabelTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * The one way this suite produces a genuinely empty connection pool, shared by the classes that
 * need one (HD-233).
 *
 * <h2>Why the numbers are pinned here rather than inherited</h2>
 * The shipped acquisition bound is deliberately NOT pinned by the surefire block — every context
 * should exercise the value the product ships. A starved-pool test is the exception that has to be:
 * a pool of {@value #POOL_SIZE} it can empty by hand, and a one-second bound so the refusal arrives
 * inside a test rather than three seconds later, once per assertion. Subclasses move the lock bound
 * with it, only to keep soft rule A silent — a WARN in these contexts would be noise about a number
 * chosen for a test rather than about the product.
 *
 * <p>The pool is not smaller than {@value #POOL_SIZE} for a reason worth writing down, since the
 * obvious "make it 1" fails at boot: the surefire block also pins an expensive-read share of 2, and
 * {@code PoolShareConsistency} refuses a context whose share is not strictly below its pool. Four is
 * also the size every other context in the suite runs at, so these classes ask no more of the shared
 * local PostgreSQL than their neighbours.
 *
 * <p><strong>Both Hikari lines move together, exactly as the shipped file spells them.</strong>
 * {@code validation-timeout} reads the same {@code DB_CONNECTION_TIMEOUT_MS} in
 * {@code application.properties} because it is the same number rather than a fourth decision, and
 * {@code DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked} refuses a boot where the two
 * have come apart. A context that overrode only the acquisition line would therefore not start —
 * which is the seal doing its job on a test, and the reason every {@code @SpringBootTest} built on
 * this base sets both.
 *
 * <p>It extends {@link LabelTestBase} for one thing only — {@code newProject()}, which is how a
 * starved-pool test gets a real user and a real token <em>before</em> the pool is taken away. Every
 * fixture a test of this kind needs must be created first; nothing can be read once the pool is
 * empty, which is the whole point.
 */
public abstract class PoolStarvedBase extends LabelTestBase {

    public static final String POOL_SIZE = "4";
    public static final String ACQUISITION_MS = "1000";

    @Autowired protected DataSource dataSource;
    @Autowired protected MeterRegistry meterRegistry;

    /**
     * Empties the pool by checking every connection out by hand, runs the body, and gives them back
     * in a {@code finally} — a leaked connection here would poison the cached context for every
     * later class in the fork.
     */
    protected <T> T whileTheWholePoolIsHeld(ThrowingSupplier<T> body) throws Exception {
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < Integer.parseInt(POOL_SIZE); i++) {
                held.add(dataSource.getConnection());
            }
            return body.get();
        } finally {
            for (var connection : held) {
                connection.close();
            }
        }
    }

    /**
     * Every sample of the refusal counter, summed. Summed rather than "the" counter because the
     * meter is tagged and there is more than one series: an advice-path refusal carries the mapped
     * pattern, a filter-path one carries {@code route="unmapped"}, and
     * {@code MeterRegistry.find(...).counter()} would return whichever of them it met first.
     */
    protected double acquisitionFailures() {
        return meterRegistry.find("hamstrack.db.connection_acquisition_failed").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
