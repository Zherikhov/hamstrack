package com.hamstrack.common.testsupport;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <strong>A published log record, put through the encoder a deployment actually writes</strong>
 * (HD-341 security round).
 *
 * <p>Encoding is the one operation that catches every way a record can be <em>lost</em> at runtime,
 * against the member names the running code really attached and with no hand-kept list to go
 * stale. Three causes, each MEASURED 2026-11-02 against logback 1.5.34 and Boot 4.1.0, all
 * producing the same {@code IllegalStateException: The name '…' has already been written}:
 * <ul>
 *   <li>one member name attached <strong>twice</strong> on one record — which no map-shaped
 *       reader can see, because a {@code Map} collapses it and a {@code Set} discards it;</li>
 *   <li>a member named after one the JSON schema writes itself ({@code level} MEASURED);</li>
 *   <li>a member whose name is also an <strong>MDC</strong> key on the logging thread
 *       ({@code field.id} from MDC against {@code field.id} from a key-value pair, MEASURED).</li>
 * </ul>
 *
 * <p><strong>It does not catch everything, and the gap is measured rather than assumed.</strong>
 * {@code application}, {@code deployment} and {@code tags} do <em>not</em> collide here: the first
 * two are written only when {@code logging.structured.json.add.*} is configured and the third only
 * when the event carries markers, and {@link #encoder()} is deliberately given a bare
 * {@link StandardEnvironment} rather than the application's. So a seal that owns a list of the
 * schema's reserved names keeps it — for those three the list is the only guard there is.
 *
 * <h2>Why each encode gets its own thread</h2>
 * The failure is worse than a lost record and the second half is what makes a build-time witness
 * the only possible one. MEASURED 2026-11-02: the encoder's buffer is <strong>not</strong> reset
 * when a record throws, it is <strong>thread-scoped rather than per-encoder</strong> — a brand-new
 * {@code StructuredLogEncoder} on the same thread still emitted the failed record's fragment in
 * front of the next one — and the corruption is exactly <em>one record deep</em>: the next
 * successful encode on that thread carries the fragment and does not parse as JSON, and the one
 * after it is clean. A second thread was unaffected. In production the appender encodes on the
 * logging thread, so the next record from <em>any</em> logger on that thread is the casualty; in a
 * test, one arm's deliberate collision would silently corrupt the next arm's measurement. Hence a
 * fresh thread per encode: every reading here is independent, including a deliberately failing one.
 */
public final class LogRecords {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LogRecords() {
    }

    /**
     * The member names of one record <strong>in order, duplicates kept</strong> — which is the
     * whole reason this exists next to the obvious map-shaped reader. A {@code Map} or a
     * {@code Set} built from the same pairs answers the same thing for a clean record and for one
     * the encoder will refuse.
     */
    public static List<String> memberNames(ILoggingEvent event) {
        var out = new ArrayList<String>();
        var pairs = event.getKeyValuePairs();
        if (pairs != null) {
            pairs.forEach(pair -> out.add(pair.key));
        }
        return out;
    }

    /** Every name that appears more than once in {@code names}, in first-seen order. */
    public static List<String> duplicates(Collection<String> names) {
        var seen = new LinkedHashSet<String>();
        var repeated = new LinkedHashSet<String>();
        for (var name : names) {
            if (!seen.add(name)) {
                repeated.add(name);
            }
        }
        return List.copyOf(repeated);
    }

    /**
     * The bytes a deployment would ship for this record, encoded on a thread of its own so no
     * reading can inherit another's corrupted buffer.
     *
     * @throws RuntimeException whatever the encoder threw, unwrapped — a caller measuring the
     *                          consequence of a collision asserts on it directly
     */
    public static byte[] encode(ILoggingEvent event) {
        var encoded = new AtomicReference<byte[]>();
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                encoded.set(encoder().encode(event));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "log-record-encode");
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while encoding a log record", e);
        }
        if (failure.get() instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
        return encoded.get();
    }

    /**
     * <strong>Assert this record survives the production encoder as one JSON object</strong>, and
     * return it parsed.
     */
    public static JsonNode oneJsonObject(ILoggingEvent event) {
        String text;
        try {
            text = new String(encode(event), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new AssertionError(refusal(event, e.getMessage()), e);
        }
        if (text.strip().lines().count() != 1) {
            throw new AssertionError(refusal(event,
                    "it encoded to " + text.strip().lines().count() + " physical lines"));
        }
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(refusal(event, "it does not parse as JSON: " + e.getMessage()),
                    e);
        }
    }

    /**
     * {@link #oneJsonObject} for every record of a capture, collecting all offenders — so one run
     * names every broken record rather than the first.
     */
    public static void everyRecordIsOneJsonObject(Collection<ILoggingEvent> events) {
        var offenders = new ArrayList<String>();
        for (var event : events) {
            try {
                oneJsonObject(event);
            } catch (AssertionError e) {
                offenders.add(e.getMessage());
            }
        }
        if (!offenders.isEmpty()) {
            throw new AssertionError(String.join("\n\n", offenders));
        }
    }

    /** ≤ 25 lines, naming the action — see the class javadoc for where each cause comes from. */
    private static String refusal(ILoggingEvent event, String cause) {
        return """

                A LOG RECORD DOES NOT SURVIVE THE PRODUCTION ENCODER.

                Both shipped profiles write this encoder, so at runtime this record is LOST \
                — not renamed, not truncated — and the next record on the same thread is \
                emitted with this one's fragment in front of it and does not parse as JSON. \
                The throw is inside logback, so no counter can witness it: this build is the \
                only witness there is.

                Three causes produce it, and the first is invisible to every map-shaped reader:
                  1. one member name attached TWICE on this record;
                  2. a member named after one the JSON schema writes itself (@timestamp, \
                message, logger_name, thread_name, level, level_value, stack_trace);
                  3. a member whose name is also an MDC key on the logging thread.

                Give every member a distinct name under its own dotted namespace (field.*, \
                actor.*) and update the contract table in docs/observability.md.

                logger   : %s
                message  : %s
                members  : %s
                encoder  : %s
                """.formatted(event.getLoggerName(), event.getFormattedMessage(),
                memberNames(event), cause);
    }

    /**
     * Boot's own structured encoder, configured the way both shipped profiles configure it
     * ({@code logging.structured.format.console=logstash}).
     *
     * <p>A <em>fresh</em> {@link LoggerContext} carrying a {@link StandardEnvironment} rather than
     * the application's: it needs an {@code Environment} to start and nothing else from Spring, and
     * borrowing a live context would mean mutating the logging of every other test in the fork.
     */
    private static StructuredLogEncoder encoder() {
        var context = new LoggerContext();
        context.putObject(Environment.class.getName(), new StandardEnvironment());
        var encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        return encoder;
    }
}
