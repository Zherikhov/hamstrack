package com.hamstrack.common.observability;

import com.hamstrack.common.config.ReportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <strong>HD-179 — one INFO line at startup stating the heap this process actually got.</strong>
 *
 * <p>Every other artefact in this repository states the heap a default install is
 * <em>supposed</em> to have: the image's {@code -XX:MaxRAMPercentage=50}, the compose file's
 * {@code mem_limit: ${APP_MEMORY_LIMIT:-1g}}, and the 512 MB reference figure
 * {@link ReportProperties#maxRows()} is costed against. None of them is a measurement, and
 * the three of them are a product of an image, a file on a box and a variable in {@code .env}
 * — which have been out of step in production before (HD-199: the flag arrived, the limit did
 * not, and the percentage was silently taken against host RAM). This line is the one place
 * that reports the resolved number rather than an intention, and it is read from the running
 * JVM, so it cannot disagree with itself.
 *
 * <p><strong>Why it names the mechanism as well as the number.</strong>
 * {@code JAVA_TOOL_OPTIONS} is a trap that manufactures its own evidence: setting
 * {@code JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75} makes the JVM print
 * {@code Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75.0} and then <em>not</em> apply
 * it, because the image's own command-line copy of that flag wins. So the log line an
 * operator naturally looks for says the variable was <em>read</em>, and reads as though it
 * were applied. Only {@code -Xmx} beats the image's flag. Stating whether the resolved
 * maximum came from an explicit {@code -Xmx} or was derived from a percentage is what
 * contradicts that false evidence — a bare byte count would not.
 *
 * <p><strong>Why the byte count is {@code MaxHeapSize} and not {@code Runtime.maxMemory()}.</strong>
 * They are different quantities, and they diverge on exactly the configuration this product
 * ships. {@code Runtime.maxMemory()} reports the heap the collector will let the application
 * <em>use</em>; a collector that keeps a survivor space it cannot lend out reports less than
 * the configured maximum. Measured 2026-09-01 in containers on
 * {@code eclipse-temurin:21-jre-alpine} (Temurin 21.0.12+8 — the tag {@code Dockerfile}'s
 * runtime stage runs, not the JDK tag), 2 CPUs, with the image's own
 * {@code -XX:MaxRAMPercentage=50.0}:
 *
 * <pre>
 *   mem_limit=1g → SerialGC: MaxHeapSize 536870912, Runtime.maxMemory 518979584
 *   mem_limit=2g → G1:       MaxHeapSize 1073741824, Runtime.maxMemory 1073741824
 * </pre>
 *
 * The acceptance criterion for this line is that its number can be checked against
 * {@code java -XX:+PrintFlagsFinal -version}, which prints {@code MaxHeapSize} — and on the
 * <em>default</em> install ({@code APP_MEMORY_LIMIT=1g}) {@code Runtime.maxMemory()}
 * disagrees with it by 3.4%: small enough to read as a rounding artefact rather than as a
 * bug, in the one artefact {@code ops/loadtest/capture/fingerprint.sh} now treats as
 * authoritative. So the VM option is read directly, {@code Runtime.maxMemory()} is only the
 * fallback for a JVM with no HotSpot diagnostic bean, and when it <em>is</em> the fallback
 * the line says so — a number that might be either is worse than one labelled as which.
 *
 * <p><strong>Why the collector is named.</strong> It is one word and it is the difference
 * between a 50 ms pause and a 5 s one. At a 1 GiB container limit the JVM is below its
 * "server-class machine" threshold and ergonomically selects <strong>SerialGC</strong>, a
 * single-threaded stop-the-world collector; at 2 GiB, same image and same flags, it selects
 * G1 (measured, above). The 2026-08-31 load window recorded a 4.99 s GC pause on a 1 GiB app
 * container, and the collector is the likeliest explanation of it — it was not itself
 * recorded that day, which is the other half of why this line names it. An operator
 * reading this line during an incident should not have to ask a second question to learn it.
 *
 * <p><strong>A number, never a judgement.</strong> "Heap 512 MB" is useful at every size;
 * "heap may be too small" is a guess about a workload this process cannot see, and a warning
 * that fires on every start is a warning nobody reads by the second week. There is
 * deliberately no threshold here and <em>no WARN about the heap size</em> — which is a rule
 * about refusing to judge the number, not about refusing to report that the reporter broke.
 * The one WARN below fires when the line itself cannot be produced, and it is there because
 * this line is now a <strong>contract</strong>: {@code docs/release-checklist.md} promises it
 * and {@code ops/loadtest/capture/fingerprint.sh} greps it as the authoritative heap reading.
 * If it silently vanished, an operator would get an empty grep — an absent value wearing the
 * face of a passed check, which is the exact failure the fingerprint's own rules forbid.
 *
 * <p>{@code app.reports.max-rows} rides along because it is the product setting most often
 * reasoned <em>against</em> the heap (~1.9 KB of transient heap per shipped row, worst case, so
 * 20 000 rows is ~38 MB of whatever number precedes it on this line). An operator comparing the
 * two is exactly this line's reader, and making them look in two places to do it is how the pair
 * drifts.
 *
 * <p><strong>It is not the only such setting, and this line does not claim to enumerate them.</strong>
 * {@code app.agile.section-max-issues} × ({@code app.agile.max-open-sprints-per-project} + 1) is a
 * second row cap costed in the same ~1.9 KB against the same heap, ceilinged at the same 20 000
 * rows, and since HD-174 both are drawn from the one occupancy share
 * ({@code app.expensive-read.max-in-flight}), so the instance-wide figure is that share times the
 * larger of them. Printing every one of them would turn a line an operator greps into a list that
 * goes stale one entry before it does; each carries its own arithmetic in its own
 * {@code @ConfigurationProperties}, and this line prints the heap they are all measured against.
 *
 * <p><strong>Identical in {@code dc} and {@code cloud}</strong>, with no profile gate: how
 * much heap a JVM got is a property of the box it runs on, not of the deployment model. It
 * is also not a Cloud diagnostic — the self-hoster sizing a 2 GB box is the reader with the
 * least other evidence available.
 *
 * <p>Fired on {@link ApplicationReadyEvent} (once per context, after the container is up)
 * rather than from a constructor, so it can never sit in front of a failing startup. That
 * promise is <strong>structural, not an inventory of things that happen not to throw</strong>:
 * a {@code Throwable} escaping an {@code ApplicationReadyEvent} listener is not swallowed —
 * {@code SpringApplication.run} wraps {@code listeners.ready(…)} in
 * {@code catch (Throwable ex) { throw handleRunFailure(…); }}, which closes the context and
 * fails the run — so the listener body as a whole is guarded, and each individual read below
 * (cgroup file, HotSpot bean, GC beans, argument list) degrades to a printed {@code unknown}
 * on its own. Nothing in this class runs <em>outside</em> that guard either: the two cgroup
 * locations are held as strings and become {@link java.nio.file.Path}s inside the read, so
 * there is no class-initialisation work for "never" to have to cover.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupMemoryLogger {

    /**
     * cgroup v2, and what a container sees at its own namespace root. {@code max} = unbounded.
     *
     * <p>Held as a {@code String} and turned into a {@link Path} inside the guarded read, so
     * that <em>nothing</em> in this class runs at class-initialisation time. {@code Path.of}
     * on a constant like this cannot throw on any supported platform, so the risk was
     * theoretical — but "this listener can never fail a boot" is a promise worth being
     * structurally true rather than true by inspection, and moving one call costs nothing.
     */
    private static final String CGROUP_V2_MAX = "/sys/fs/cgroup/memory.max";

    /** cgroup v1 fallback. Spells "unbounded" as a huge sentinel rather than a word. */
    private static final String CGROUP_V1_MAX = "/sys/fs/cgroup/memory/memory.limit_in_bytes";

    /**
     * Anything at or above this is cgroup v1's "no limit" sentinel (a page-aligned
     * {@code LONG_MAX}, ~9.2 EB) rather than a limit anyone set. 2^53 bytes is 8 PB — far
     * past any real ceiling and far below the sentinel, so the test cannot go wrong in
     * either direction.
     */
    private static final long V1_UNBOUNDED_FLOOR = 1L << 53;

    private static final long MIB = 1024L * 1024L;

    /**
     * Room for {@code max} or any 64-bit byte count several times over, and a bound rather
     * than a whole-file read: these are kernel pseudo-files on every host this is expected
     * to run on, but they are paths, and a path is whatever the box it lives on says it is.
     */
    private static final int CGROUP_READ_LIMIT = 64;

    private final ReportProperties reportProperties;

    /**
     * The resolved maximum heap, and which quantity it is.
     *
     * @param bytes           the number printed
     * @param fromMaxHeapSize {@code true} when it is HotSpot's {@code MaxHeapSize} — the
     *                        figure {@code -XX:+PrintFlagsFinal} prints, and the only one
     *                        that can be compared against it
     */
    record HeapMax(long bytes, boolean fromMaxHeapSize) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logResolvedMemory() {
        try {
            log.info("{}", currentLine(reportProperties.maxRows()));
        } catch (Throwable t) {
            // See the class javadoc: a Throwable out of this listener fails the boot, and a
            // diagnostic line may not be able to do that — so it is swallowed here.
            //
            // WARN, not DEBUG, and the inner catches are the contrast that makes the level
            // right. A missing cgroup file on a developer's box is NORMAL and logs at debug;
            // reaching THIS catch is a bug, because every individual read below already
            // degrades to a printed "unknown" on its own. And the line is a contract: the
            // release checklist promises it and fingerprint.sh greps it as authoritative, so
            // losing it silently hands an operator an empty grep that looks like a passed
            // check. At the shipped INFO threshold debug would discard the one piece of
            // evidence — the stack trace — that says which read got past its own guard.
            log.warn("Resolved-memory line not printable", t);
        }
    }

    /**
     * Reads this JVM and renders the line. Package-private so a test can assert the
     * <em>selection</em> against the running VM rather than only the formatting.
     */
    static String currentLine(int maxRows) {
        return line(
                maxHeap(vmOption("MaxHeapSize", false), Runtime.getRuntime().maxMemory()),
                heapSource(vmArguments(), vmOption("MaxHeapSize", true), vmOption("MaxRAMPercentage", false)),
                collector(garbageCollectorNames()),
                containerMemoryLimit(),
                maxRows);
    }

    /**
     * {@code MaxHeapSize} when the JVM will state it, {@code Runtime.maxMemory()} otherwise.
     *
     * <p>Not a formatting detail: on the bundled default (a 1 GiB container limit, where the
     * JVM picks SerialGC) the two differ by ~18 MB, so a line printing whichever came to hand
     * would disagree by 3.4% with the {@code PrintFlagsFinal} figure every other artefact in
     * this repository states — a discrepancy small enough to be read as rounding. Which
     * quantity was used is therefore part of the line.
     *
     * @param maxHeapSizeOption HotSpot's {@code MaxHeapSize} value in bytes, or {@code null}
     * @param runtimeMaxMemory  {@code Runtime.getRuntime().maxMemory()}
     */
    static HeapMax maxHeap(String maxHeapSizeOption, long runtimeMaxMemory) {
        if (maxHeapSizeOption != null) {
            try {
                long bytes = Long.parseLong(maxHeapSizeOption.trim());
                if (bytes > 0) {
                    return new HeapMax(bytes, true);
                }
            } catch (NumberFormatException e) {
                log.debug("MaxHeapSize is not a byte count: {}", maxHeapSizeOption);
            }
        }
        return new HeapMax(runtimeMaxMemory, false);
    }

    /**
     * The line itself, as a pure function so its shape is testable without a JVM that has
     * the flags under discussion.
     *
     * <p>Bytes are printed beside the MB because the acceptance criterion is that this
     * number can be checked against {@code java -XX:+PrintFlagsFinal -version}, which prints
     * {@code MaxHeapSize} in bytes. Rounding is one way a line like this can look right and
     * fail the comparison it exists to serve; quietly printing a neighbouring quantity is the
     * other, which is why the parenthesis names which one this is.
     */
    static String line(HeapMax heap, String heapSource, String collector, String containerLimit, int maxRows) {
        String measure = heap.fromMaxHeapSize()
                ? "MaxHeapSize"
                : "Runtime.maxMemory — MaxHeapSize unavailable, so this is usable heap and can read below it";
        return "Memory: max heap %d MB = %d bytes (%s; %s); GC %s; container memory limit %s; app.reports.max-rows=%d"
                .formatted(heap.bytes() / MIB, heap.bytes(), measure, heapSource, collector, containerLimit, maxRows);
    }

    /**
     * Explicit or derived, and named either way.
     *
     * <p><strong>The argument list is consulted first, and the measurement below is why.</strong>
     * The obvious design is to trust {@code MaxHeapSize}'s origin — {@code ERGONOMIC} means the
     * JVM computed the number, anything else means somebody set it — and it is wrong in exactly
     * the case this ticket is about. Measured on HotSpot 21.0.12:
     *
     * <pre>
     *   java -Xmx3g                                  → MaxHeapSize origin VM_CREATION
     *   JAVA_TOOL_OPTIONS=-Xmx777m java              → applied (heap 777m), origin ERGONOMIC
     *   JAVA_TOOL_OPTIONS=-Xmx777m java -XX:MaxRAMPercentage=50 → applied, origin ERGONOMIC
     * </pre>
     *
     * An {@code -Xmx} that arrives through {@code JAVA_TOOL_OPTIONS} is honoured and still
     * reports {@code ERGONOMIC}, so origin alone would print "derived from a percentage" about
     * a heap the operator set by hand — the one override this deployment documents. The
     * argument list carries {@code -Xmx777m} in every one of those runs, so it is the signal
     * that decides, and origin is the fallback for a JVM that hides its arguments.
     *
     * <p>The <em>value</em> goes the other way. An argument list shows the losing flag beside
     * the winning one ({@code [-XX:MaxRAMPercentage=75, -XX:MaxRAMPercentage=50.0]} when
     * {@code JAVA_TOOL_OPTIONS} is overridden by the image's own copy — measured), so the
     * percentage printed here is the JVM's <em>effective</em> value read back from the VM
     * option, never a string scraped from the list. That is what contradicts
     * {@code Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75}: this line answers 50.
     *
     * @param vmArgs  {@code RuntimeMXBean.getInputArguments()} — may be empty
     * @param heapOrigin origin of {@code MaxHeapSize} ({@code ERGONOMIC}, {@code VM_CREATION},
     *                …), or {@code null} when not obtainable
     * @param percentage effective {@code MaxRAMPercentage}, or {@code null}
     */
    static String heapSource(List<String> vmArgs, String heapOrigin, String percentage) {
        String xmx = lastArgStartingWith(vmArgs, "-Xmx");
        if (xmx == null) {
            xmx = lastArgStartingWith(vmArgs, "-XX:MaxHeapSize=");
        }
        // DEFAULT and ERGONOMIC are the two origins that mean "the JVM chose this". Every
        // other value of com.sun.management.VMOption.Origin means somebody set it, so an
        // unfamiliar one is read as explicit rather than silently as derived.
        boolean explicit = xmx != null
                || (heapOrigin != null && !"ERGONOMIC".equals(heapOrigin) && !"DEFAULT".equals(heapOrigin));

        if (explicit) {
            // Named with the flag as it was actually passed, so an operator who set
            // JAVA_TOOL_OPTIONS=-Xmx3g reads their own string back and knows it took. Where
            // it came from is deliberately NOT claimed: the JVM does not distinguish an
            // -Xmx from the environment from one on the command line in anything readable
            // from inside the process (both reach getInputArguments, and see the origins
            // above), so a line naming the source would be a guess.
            return "explicit " + (xmx != null ? xmx : "-XX:MaxHeapSize");
        }
        if (percentage != null) {
            return "derived from -XX:MaxRAMPercentage=" + percentage + ", no -Xmx";
        }
        return "derived by JVM ergonomics, no -Xmx and no -XX:MaxRAMPercentage";
    }

    /**
     * The collector, from the names of its MXBeans.
     *
     * <p>The beans rather than the {@code UseXGC} flags: the flags are several booleans whose
     * relationship to what is actually collecting has to be reasoned about, while the beans
     * <em>are</em> what is collecting. Each name below belongs to one collector and to no
     * other ({@code Copy} / {@code MarkSweepCompact} are Serial's, {@code PS Scavenge} /
     * {@code PS MarkSweep} are Parallel's), so these are mappings and not heuristics.
     * Anything unrecognised prints the raw names, which is still an answer.
     */
    static String collector(List<String> gcBeanNames) {
        if (gcBeanNames.isEmpty()) {
            return "unknown";
        }
        String joined = String.join(", ", gcBeanNames);
        if (joined.contains("G1")) {
            return "G1GC";
        }
        if (joined.contains("ZGC")) {
            return "ZGC";
        }
        if (joined.contains("Shenandoah")) {
            return "ShenandoahGC";
        }
        if (joined.contains("PS ")) {
            return "ParallelGC";
        }
        if (joined.contains("MarkSweepCompact")) {
            return "SerialGC";
        }
        return joined;
    }

    /**
     * The container's memory ceiling, rendered: {@code "1024 MB"}, {@code "none"} (a cgroup
     * exists and sets no limit — the state in which a percentage is taken against HOST RAM),
     * or {@code "unknown"} (no cgroup memory file, e.g. running the JAR straight on a host or
     * on a developer's Windows box).
     *
     * <p>{@code none} and {@code unknown} are kept apart on purpose: one is a measurement,
     * the other is the absence of one, and a line that printed the same word for both would
     * be the kind of check that passes without checking.
     */
    static String containerMemoryLimit() {
        try {
            String v2 = readBounded(CGROUP_V2_MAX);
            if (v2 != null) {
                return "max".equals(v2) ? "none" : renderLimit(Long.parseLong(v2));
            }
            String v1 = readBounded(CGROUP_V1_MAX);
            if (v1 != null) {
                return renderLimit(Long.parseLong(v1));
            }
        } catch (Throwable t) {
            // A missing, truncated or differently-formatted cgroup file must never affect a
            // startup — and neither must an Error, which is why this is not catch (Exception):
            // an OutOfMemoryError or a StackOverflowError leaving an ApplicationReadyEvent
            // listener is a failed boot, not a missing log line. Debug rather than warn: on
            // every non-container install this is the normal case, not a fault.
            log.debug("Container memory limit not readable", t);
        }
        return "unknown";
    }

    /**
     * A bounded read of a path this process does not own.
     *
     * <p>{@code isRegularFile} beside {@code isReadable} because the alternative to a
     * pseudo-file at that path is not only "absent": a FIFO is readable and blocks for ever,
     * and a blocking read is caught by no {@code catch} at all. {@code readNBytes} bounds the
     * rest — {@code Files.readString} of a path that turned out to be enormous is an
     * {@code OutOfMemoryError} in the middle of somebody's startup. A truncated read here
     * fails to parse and degrades to {@code unknown}, which is the correct answer for a file
     * that was not the one this was written for.
     *
     * @return the trimmed contents, or {@code null} when the path is not a readable file
     */
    private static String readBounded(String location) throws IOException {
        Path path = Path.of(location);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            return new String(in.readNBytes(CGROUP_READ_LIMIT), StandardCharsets.US_ASCII).trim();
        }
    }

    private static String renderLimit(long bytes) {
        return bytes <= 0 || bytes >= V1_UNBOUNDED_FLOOR ? "none" : (bytes / MIB) + " MB";
    }

    private static List<String> vmArguments() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable t) {
            log.debug("JVM input arguments not readable", t);
            return List.of();
        }
    }

    private static List<String> garbageCollectorNames() {
        try {
            return ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .map(GarbageCollectorMXBean::getName)
                    .toList();
        } catch (Throwable t) {
            log.debug("Garbage collector beans not readable", t);
            return List.of();
        }
    }

    /**
     * Reads one HotSpot VM option's origin or value.
     *
     * <p>{@code com.sun.management.HotSpotDiagnosticMXBean} is exported by the
     * {@code jdk.management} module, so this needs no {@code --add-exports} and no
     * {@code --add-opens}; it is simply absent on a non-HotSpot JVM, which is why every
     * caller accepts {@code null}.
     */
    // Returns null when the option is not obtainable on this JVM.
    private static String vmOption(String name, boolean origin) {
        try {
            var bean = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (bean == null) {
                return null;
            }
            var option = bean.getVMOption(name);
            return origin ? String.valueOf(option.getOrigin()) : trimTrailingZeros(option.getValue());
        } catch (Throwable t) {
            log.debug("VM option {} not readable", name, t);
            return null;
        }
    }

    /** {@code MaxRAMPercentage} reads back as {@code 50.000000}; the zeros carry no information. */
    private static String trimTrailingZeros(String value) {
        if (!value.contains(".")) {
            return value;
        }
        String trimmed = value.replaceAll("0+$", "");
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** Last wins, the way the JVM itself resolves a repeated flag. */
    // Returns null when no argument carries the prefix.
    private static String lastArgStartingWith(List<String> args, String prefix) {
        String found = null;
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                found = arg;
            }
        }
        return found;
    }
}
