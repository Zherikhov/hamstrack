package com.hamstrack.common.observability;

import com.hamstrack.common.observability.StartupMemoryLogger.HeapMax;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <strong>HD-179.</strong> The startup line's load-bearing properties, sealed where they can
 * be checked without a JVM that happens to carry the flags under discussion: it prints the
 * resolved maximum <em>in bytes</em> (so it can be compared against
 * {@code java -XX:+PrintFlagsFinal -version}), it prints the <em>right</em> byte count, it
 * says whether that maximum was set explicitly or derived from a percentage, and it names the
 * collector.
 *
 * <p>The explicit/derived half is the ticket. {@code JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=…}
 * is printed by the JVM and then ignored, so the evidence an operator naturally reads is
 * actively misleading; a line that stated only a number would be consistent with both the
 * true and the false story.
 */
class StartupMemoryLoggerTest {

    @Test
    void statesTheResolvedMaximumInBytesSoItCanBeComparedWithPrintFlagsFinal() {
        String line = StartupMemoryLogger.line(new HeapMax(536_870_912L, true),
                "derived from -XX:MaxRAMPercentage=50, no -Xmx", "SerialGC", "1024 MB", 20_000);

        assertThat(line)
                .isEqualTo("Memory: max heap 512 MB = 536870912 bytes "
                        + "(MaxHeapSize; derived from -XX:MaxRAMPercentage=50, no -Xmx); "
                        + "GC SerialGC; container memory limit 1024 MB; app.reports.max-rows=20000");
    }

    /**
     * <strong>The selection, which is the number's correctness rather than its formatting.</strong>
     * {@code Runtime.maxMemory()} is the heap the collector will let the application USE and
     * equals {@code MaxHeapSize} only under some collectors. Measured 2026-09-01 in containers
     * on {@code eclipse-temurin:21-jre-alpine} — the tag {@code Dockerfile}'s runtime stage
     * runs — with the image's own {@code -XX:MaxRAMPercentage=50.0}: at {@code mem_limit=1g},
     * the bundled default, what production runs, and what every doc example is written
     * against, the JVM ergonomically picks SerialGC and the two read {@code 536870912} and
     * {@code 518979584}. Printing the second would put this line 3.4% below the
     * {@code 536870912} every other artefact in the repository states — a gap small enough to
     * be read as rounding, in the artefact {@code fingerprint.sh} now treats as
     * authoritative. So the VM option wins whenever there is one.
     */
    @Test
    void theMaxHeapSizeOptionIsPreferredToRuntimeMaxMemoryWhenTheyDisagree() {
        HeapMax heap = StartupMemoryLogger.maxHeap("536870912", 518_979_584L);

        assertThat(heap).isEqualTo(new HeapMax(536_870_912L, true));
        assertThat(StartupMemoryLogger.line(heap, "derived from -XX:MaxRAMPercentage=50, no -Xmx",
                "SerialGC", "1024 MB", 20_000))
                .contains("536870912 bytes")
                .doesNotContain("518979584");
    }

    /**
     * And when there is no VM option to read, the line SAYS it is the other quantity. A
     * number that might be either is worse than one labelled as which — the fallback is
     * usable heap and can sit below the {@code MaxHeapSize} a reader is comparing it to.
     */
    @Test
    void aFallbackToRuntimeMaxMemoryIsNamedInTheLineRatherThanPassedOffAsMaxHeapSize() {
        HeapMax heap = StartupMemoryLogger.maxHeap(null, 518_979_584L);

        assertThat(heap).isEqualTo(new HeapMax(518_979_584L, false));
        assertThat(StartupMemoryLogger.line(heap, "derived from -XX:MaxRAMPercentage=50, no -Xmx",
                "SerialGC", "1024 MB", 20_000))
                .contains("518979584 bytes")
                .contains("Runtime.maxMemory — MaxHeapSize unavailable");
    }

    /** A value that is not a byte count is the absent case, not a crash and not a zero. */
    @Test
    void anUnparseableMaxHeapSizeFallsBackAndSaysSo() {
        assertThat(StartupMemoryLogger.maxHeap("(unknown)", 518_979_584L))
                .isEqualTo(new HeapMax(518_979_584L, false));
        assertThat(StartupMemoryLogger.maxHeap("0", 518_979_584L))
                .isEqualTo(new HeapMax(518_979_584L, false));
    }

    /**
     * <strong>The wiring, on whatever JVM the suite happens to run.</strong> The pure tests
     * above cover {@link StartupMemoryLogger#maxHeap}; this one covers that
     * {@link StartupMemoryLogger#currentLine(int)} actually feeds it the VM option — it would
     * fail if that read were dropped, because the fallback relabels the line.
     *
     * <p><strong>Only the label half is load-bearing here, and the name says so.</strong>
     * Under G1 — every CI runner and every developer box, since HotSpot picks G1 from
     * ~1792 MB of heap up — {@code Runtime.maxMemory()} and {@code MaxHeapSize} are the same
     * number, so an assertion that the line does not carry the usable heap would be
     * vacuously true. The case where the two differ is forked deliberately by
     * {@link #onACollectorWhoseUsableHeapIsSmallerTheLineStillPrintsMaxHeapSize()}.
     */
    @Test
    void theLineThisJvmPrintsIsLabelledMaxHeapSizeAndCarriesThatValue() {
        var bean = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        assumeTrue(bean != null, "no HotSpot diagnostic bean on this JVM");
        long maxHeapSize = Long.parseLong(bean.getVMOption("MaxHeapSize").getValue());

        String line = StartupMemoryLogger.currentLine(20_000);

        assertThat(line).contains(maxHeapSize + " bytes").contains("(MaxHeapSize;");
    }

    /**
     * <strong>And the half the running JVM cannot show, on a JVM forked to show it.</strong>
     * The distinction between {@code MaxHeapSize} and {@code Runtime.maxMemory()} only exists
     * on a collector that withholds a survivor space, and the collector that does it here is
     * the one the bundled default actually selects: at a 1 GiB container limit HotSpot picks
     * SerialGC, where the two read {@code 536870912} and {@code 518979584} (measured
     * 2026-09-01 on {@code eclipse-temurin:21-jre-alpine}). Every machine this suite runs on
     * has more heap than that and therefore G1, where the two are equal and any "and not the
     * usable heap" assertion asserts nothing — so this test forks
     * {@code -XX:+UseSerialGC -Xmx512m} and checks the claim where it can fail.
     *
     * <p>It asserts the divergence it depends on before asserting anything about the line: a
     * fork that failed to produce two different numbers would otherwise degrade back into the
     * vacuous test this one exists to replace.
     */
    @Test
    void onACollectorWhoseUsableHeapIsSmallerTheLineStillPrintsMaxHeapSize() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process forked = new ProcessBuilder(java.toString(), "-XX:+UseSerialGC", "-Xmx512m",
                "-cp", System.getProperty("java.class.path"),
                StartupMemoryLoggerTest.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(forked.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(forked.waitFor(120, TimeUnit.SECONDS)).as("forked JVM finished; output:%n%s", output).isTrue();
        assertThat(forked.exitValue()).as("forked JVM exit status; output:%n%s", output).isZero();

        Map<String, String> reported = output.lines()
                .filter(l -> l.indexOf('=') > 0)
                .collect(Collectors.toMap(l -> l.substring(0, l.indexOf('=')),
                        l -> l.substring(l.indexOf('=') + 1), (a, b) -> b));
        String maxHeapSize = reported.get("maxHeapSize");
        String usable = reported.get("usable");
        assertThat(maxHeapSize).as("forked JVM did not report MaxHeapSize; output:%n%s", output).isNotNull();
        assertThat(usable).as("forked JVM did not report its usable heap; output:%n%s", output).isNotNull();
        assertThat(usable)
                .as("SerialGC was supposed to make these two differ — without that this test is vacuous")
                .isNotEqualTo(maxHeapSize);

        assertThat(reported.get("line"))
                .contains(maxHeapSize + " bytes")
                .contains("(MaxHeapSize;")
                .doesNotContain(usable + " bytes");
    }

    /** The forked body of the test above. Prints the two quantities and the line built from them. */
    public static void main(String[] args) {
        var bean = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        System.out.println("maxHeapSize=" + bean.getVMOption("MaxHeapSize").getValue());
        System.out.println("usable=" + Runtime.getRuntime().maxMemory());
        System.out.println("line=" + StartupMemoryLogger.currentLine(20_000));
    }

    @Test
    void aPercentageDerivedHeapIsNamedAsDerivedAndCarriesThePercentage() {
        String source = StartupMemoryLogger.heapSource(
                List.of("-XX:MaxRAMPercentage=50.0", "-Duser.language=en"), "ERGONOMIC", "50");

        assertThat(source).isEqualTo("derived from -XX:MaxRAMPercentage=50, no -Xmx");
    }

    @Test
    void anExplicitXmxIsNamedAsExplicit() {
        String source = StartupMemoryLogger.heapSource(
                List.of("-XX:MaxRAMPercentage=50.0", "-Xmx3g"), "VM_CREATION", "50");

        assertThat(source).isEqualTo("explicit -Xmx3g");
    }

    /**
     * <strong>The case that decides the implementation.</strong> {@code JAVA_TOOL_OPTIONS=-Xmx3g}
     * is the ONE form that beats the image's own {@code -XX:MaxRAMPercentage}, and this is the
     * line that confirms it took — but HotSpot reports {@code MaxHeapSize} as
     * {@code ERGONOMIC} in exactly that case (measured on 21.0.12, alongside
     * {@code VM_CREATION} for the same flag on the command line). So a reading that trusted
     * the origin would print "derived from a percentage" about a heap the operator set by
     * hand. The argument list is what decides, and this test is why.
     */
    @Test
    void anXmxThroughJavaToolOptionsIsStillExplicitThoughItsOriginReadsErgonomic() {
        String source = StartupMemoryLogger.heapSource(List.of("-Xmx3g", "-XX:MaxRAMPercentage=50.0"),
                "ERGONOMIC", "50");

        assertThat(source).isEqualTo("explicit -Xmx3g");
    }

    /**
     * The other half of the same trap, and the reason the percentage is read back from the VM
     * option rather than scraped from the arguments: when {@code JAVA_TOOL_OPTIONS} carries a
     * percentage the image also sets, BOTH appear in the argument list and the image's copy
     * wins (measured: {@code [-XX:MaxRAMPercentage=75, -XX:MaxRAMPercentage=50.0]}, heap
     * computed at 50%). The line must answer 50 — that is what contradicts
     * {@code Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75}.
     */
    @Test
    void aLosingPercentageInTheArgumentListIsNotTheOneReported() {
        String source = StartupMemoryLogger.heapSource(
                List.of("-XX:MaxRAMPercentage=75", "-XX:MaxRAMPercentage=50.0"), "ERGONOMIC", "50");

        assertThat(source).isEqualTo("derived from -XX:MaxRAMPercentage=50, no -Xmx")
                .doesNotContain("75");
    }

    @Test
    void aJvmWithNoHeapFlagsAtAllSaysSo() {
        String source = StartupMemoryLogger.heapSource(List.of("-Duser.country=US"), "ERGONOMIC", null);

        assertThat(source).isEqualTo("derived by JVM ergonomics, no -Xmx and no -XX:MaxRAMPercentage");
    }

    /**
     * A JVM that offers no HotSpot diagnostic bean still gets an honest answer out of the
     * argument list alone — every branch below accepts a null origin.
     */
    @Test
    void aMissingOriginFallsBackToTheArgumentList() {
        assertThat(StartupMemoryLogger.heapSource(List.of("-Xmx512m"), null, null))
                .isEqualTo("explicit -Xmx512m");
        assertThat(StartupMemoryLogger.heapSource(List.of(), null, "25"))
                .isEqualTo("derived from -XX:MaxRAMPercentage=25, no -Xmx");
    }

    /**
     * The collector is on the line because at a 1 GiB container limit — the bundled default —
     * the JVM is below its "server-class machine" threshold and ergonomically picks SerialGC,
     * a single-threaded stop-the-world collector, where at 2 GiB it picks G1. That is the
     * difference between a 50 ms pause and a multi-second one, and it is the likeliest
     * explanation of the 4.99 s pause the 2026-08-31 load window measured on a 1 GiB
     * container — that window did not record the collector, which is the other half of why
     * this line names it. One word, either way.
     */
    @Test
    void theCollectorIsNamedFromItsBeansAndSerialIsNotMistakenForAnythingElse() {
        assertThat(StartupMemoryLogger.collector(List.of("Copy", "MarkSweepCompact"))).isEqualTo("SerialGC");
        assertThat(StartupMemoryLogger.collector(List.of("G1 Young Generation", "G1 Old Generation")))
                .isEqualTo("G1GC");
        assertThat(StartupMemoryLogger.collector(List.of("PS Scavenge", "PS MarkSweep"))).isEqualTo("ParallelGC");
        assertThat(StartupMemoryLogger.collector(List.of("ZGC Cycles", "ZGC Pauses"))).isEqualTo("ZGC");
    }

    /** An unfamiliar collector prints its beans rather than a guess or a blank. */
    @Test
    void anUnrecognisedCollectorPrintsItsBeanNames() {
        assertThat(StartupMemoryLogger.collector(List.of("Epsilon Heap"))).isEqualTo("Epsilon Heap");
        assertThat(StartupMemoryLogger.collector(List.of())).isEqualTo("unknown");
    }

    /**
     * "No limit" and "could not tell" must not print the same word: one is a measurement and
     * the other is the absence of one, and only the first means the percentage is being taken
     * against host RAM. This runs wherever the suite runs — container, Linux host or a
     * developer's Windows box — so it asserts the shape rather than the value, and that the
     * read cannot throw.
     */
    @Test
    void theContainerLimitIsAlwaysReadableWithoutThrowingAndNeverConflatesNoneWithUnknown() {
        String limit = StartupMemoryLogger.containerMemoryLimit();

        assertThat(limit).satisfiesAnyOf(
                l -> assertThat(l).isEqualTo("none"),
                l -> assertThat(l).isEqualTo("unknown"),
                l -> assertThat(l).matches("\\d+ MB"));
    }
}
