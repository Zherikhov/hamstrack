package com.hamstrack.common.testsupport;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.MarkerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>The witness for {@link LogRecords}</strong> (HD-341 security round).
 *
 * <p>{@link LogRecords#oneJsonObject} is about to be the guard behind every published record in
 * this tree, which is the direction this codebase wants — one derivation instead of a per-arm copy
 * — but it trades a loud failure for a silent one: two hand-written checks that disagree red one
 * of themselves, while one shared check that has stopped looking is green everywhere at once. So
 * it carries floors in <strong>both</strong> directions: the shapes it must refuse, and the shapes
 * it must accept. A guard that refused everything would satisfy one arm here and fail the other.
 *
 * <p>Every verdict in the tables below was MEASURED on 2026-11-02 against logback 1.5.34 and Boot
 * 4.1.0 before it was written down, because three of them are the opposite of the obvious guess:
 * {@code tags} and {@code stack_trace} collide only on a record that carries a marker or a
 * throwable, and {@code application}/{@code deployment} never collide here at all.
 */
class LogRecordsTest {

    /**
     * <strong>The shapes the encoder must take.</strong> A guard with no accepted shapes is the
     * greenest possible answer to a refusal arm, which is why this exists beside it.
     */
    @Test
    void everyAcceptedShapeSurvivesAsOneJsonObject() {
        var accepted = new ArrayList<String>();

        accepted.add(accept("no members at all", log -> log.log("probe: bare")));
        accepted.add(accept("two distinct members",
                log -> log.addKeyValue("field.id", "A").addKeyValue("field.name", "B")
                        .log("probe: two {}", 1)));
        accepted.add(accept("a throwable, which renders into stack_trace and not into message",
                log -> log.setCause(new IllegalStateException("boom")).log("probe: threw")));
        accepted.add(accept("a value carrying every delimiter the record itself uses",
                log -> log.addKeyValue("field.v", "q\"w\\e'r(t)y=z").log("probe: delimiters")));
        // MEASURED, and the reason LogRecords keeps a list of reserved names rather than trusting
        // the encode: these two are written only when the profile configures
        // logging.structured.json.add.*, which a bare StandardEnvironment does not.
        accepted.add(accept("a member named `application`",
                log -> log.addKeyValue("application", "x").log("probe: application")));
        accepted.add(accept("a member named `deployment`",
                log -> log.addKeyValue("deployment", "x").log("probe: deployment")));

        assertThat(accepted)
                .as("""
                        THE ACCEPTED-SHAPE TABLE HAS SHRUNK.

                        A guard that refuses every record passes the refusal arm and reports \
                        every published record in the tree as broken. Lower this floor only \
                        after deciding the removed shape is genuinely no longer legal.""")
                .hasSizeGreaterThanOrEqualTo(6);
    }

    /**
     * <strong>The shapes that lose the record, each named — and the next reading unaffected.</strong>
     *
     * <p>The second half is the one that cannot be inferred: MEASURED 2026-11-02, the encoder's
     * buffer survives a throw, is thread-scoped rather than per-encoder, and corrupts exactly the
     * next record encoded on that thread. {@link LogRecords#encode} answers that with a thread per
     * encode, so this arm asserts a clean record immediately after each refusal — without that,
     * one deliberate collision here would silently corrupt the next arm's measurement.
     */
    @Test
    void everyRefusedShapeIsNamedAndTheNextReadingIsIndependent() {
        var refused = new ArrayList<String>();

        refused.add(refuse("field.name", "one member name attached twice",
                log -> log.addKeyValue("field.id", "A").addKeyValue("field.name", "first")
                        .addKeyValue("field.name", "second").log("probe: duplicate")));
        refused.add(refuse("message", "a member named after the schema's own",
                log -> log.addKeyValue("message", "x").log("probe: message")));
        refused.add(refuse("level", "another of the schema's own",
                log -> log.addKeyValue("level", "x").log("probe: level")));
        refused.add(refuse("stack_trace", "a schema member written only when there is a throwable",
                log -> log.addKeyValue("stack_trace", "x")
                        .setCause(new IllegalStateException("boom")).log("probe: stack_trace")));
        refused.add(refuse("tags", "a schema member written only when there is a marker",
                log -> log.addKeyValue("tags", "x").addMarker(MarkerFactory.getMarker("M"))
                        .log("probe: tags")));
        MDC.put("field.id", "from-mdc");
        try {
            refused.add(refuse("field.id", "a member whose name is an MDC key on this thread",
                    log -> log.addKeyValue("field.id", "from-kvp").log("probe: mdc")));
        } finally {
            MDC.clear();
        }

        assertThat(refused)
                .as("""
                        THE REFUSED-SHAPE TABLE HAS SHRUNK.

                        Every row here is a way a published record is LOST at runtime with no \
                        counter possible, because the throw is inside logback. If a row was \
                        removed because the framework stopped throwing, re-measure and rewrite \
                        LogRecords' javadoc — do not delete the row and leave the claim.""")
                .hasSizeGreaterThanOrEqualTo(6);
    }

    /**
     * <strong>What the encoder escapes in a member value, and what it does not</strong> — the
     * measured fact {@code LogValues.oneLine}'s justification rests on.
     *
     * <p>MEASURED 2026-11-02: {@code "}, {@code \}, LF, CR and NEL (U+0085) are escaped, and
     * <strong>U+2028 and U+2029 are emitted raw</strong> into the JSON string. That is legal JSON
     * and leaves the record one physical line by every ASCII-line-terminator reader, which is why
     * the forgery is closed either way — but it is also why the sink normalisation is not
     * redundant: a reader whose idea of a line includes the Unicode separators sees two.
     */
    @Test
    void theEncoderEscapesTheAsciiTerminatorsAndNelAndPassesTheUnicodeSeparatorsThrough() {
        var raw = "a b c\u0085d\ne\rf\"g\\h";
        var json = LogRecords.oneJsonObject(
                event(log -> log.addKeyValue("field.v", raw).log("probe: escapes")));

        assertThat(json.get("field.v").asText())
                .as("whatever the escaping, the value round-trips byte-for-byte: nothing a row "
                    + "supplied is hidden from the operator who has to find the field")
                .isEqualTo(raw);

        var text = new String(LogRecords.encode(
                event(log -> log.addKeyValue("field.v", raw).log("probe: escapes"))),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text)
                .as("""
                        THE ENCODER'S ESCAPING HAS CHANGED.

                        LogValues.oneLine is justified by this reading, so re-measure before \
                        editing either. The ASCII terminators and NEL must be escaped; U+2028 \
                        and U+2029 were measured to pass through raw, which is what makes \
                        flattening them at the sink worth doing.""")
                .contains("\\n").contains("\\r").contains("\\\"").contains("\\\\")
                .contains("\\u0085")
                .contains(" ").contains(" ");
        assertThat(text.strip().lines())
                .as("one event is one physical line to a line-oriented shipper")
                .hasSize(1);
    }

    /** {@link LogRecords#duplicates} names every repeated member and nothing else. */
    @Test
    void duplicatesNamesEveryRepeatedNameAndNothingElse() {
        assertThat(LogRecords.duplicates(List.of("a", "b", "c"))).isEmpty();
        assertThat(LogRecords.duplicates(List.of("a", "b", "a"))).containsExactly("a");
        assertThat(LogRecords.duplicates(List.of("a", "a", "a"))).containsExactly("a");
        assertThat(LogRecords.duplicates(List.of("a", "b", "a", "b"))).containsExactly("a", "b");
        assertThat(LogRecords.duplicates(List.of())).isEmpty();
        assertThat(LogRecords.memberNames(
                event(log -> log.addKeyValue("k", "1").addKeyValue("k", "2").log("probe: pairs"))))
                .as("the ordered reader keeps what a Map collapses — this is the whole reason it "
                    + "exists beside the obvious one")
                .containsExactly("k", "k");
    }

    // ================================================================= helpers

    private static String accept(String what, Consumer<LoggingEventBuilder> build) {
        assertThatCode(() -> LogRecords.oneJsonObject(event(build)))
                .as("a legal record shape the guard must take: %s", what)
                .doesNotThrowAnyException();
        return what;
    }

    private static String refuse(String member, String what, Consumer<LoggingEventBuilder> build) {
        assertThatThrownBy(() -> LogRecords.oneJsonObject(event(build)))
                .as("a record the encoder loses, which the guard must name: %s", what)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(member)
                .hasMessageContaining("DOES NOT SURVIVE THE PRODUCTION ENCODER");
        // `probe.after` and not `field.id`: the MDC row below is still holding `field.id` when this
        // runs, and a member colliding with a live MDC key is one of the shapes this arm refuses —
        // measured, on the first run of this file.
        assertThatCode(() -> LogRecords.oneJsonObject(event(log -> log
                .addKeyValue("probe.after", "x").log("probe: after {}", what))))
                .as("the reading after a refusal is independent — the encoder's buffer is "
                    + "thread-scoped and survives a throw, so a shared thread would corrupt it")
                .doesNotThrowAnyException();
        return member;
    }

    private static ILoggingEvent event(Consumer<LoggingEventBuilder> build) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LogRecordsTest.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            build.accept(logger.atWarn());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list).as("the shape under test emitted exactly one record").hasSize(1);
        return appender.list.getFirst();
    }
}
