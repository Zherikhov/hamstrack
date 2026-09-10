package com.hamstrack.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file rules every test that drives a shell script under {@code ops/} needs, in one
 * place (HD-299). Not a test — the name deliberately matches none of Surefire's includes
 * ({@code Test*}, {@code *Test}, {@code *Tests}, {@code *TestCase}), so the HD-265 coverage
 * guard does not demand it as a class that must execute.
 *
 * <p>Each rule here was paid for once by {@link ApplyConfigPinGuardTest} or
 * {@link ConfigDriftContainerOracleTest}, whose javadoc carries the history: a stub written at
 * mode 644 is a hole in {@code PATH} through which the name resolves to the machine's real
 * {@code docker}; a shebang line ending in CR makes bash report {@code bash\r: not found}; a
 * backslash in a path handed to Git Bash is an escape.
 */
final class ScriptHarness {

    private ScriptHarness() {
    }

    /** Exit code and combined stdout+stderr of one script run. */
    record Result(int exit, String output) {}

    /** Collects a failed expectation with the run's exit and output, so one run reports every miss. */
    static void expect(List<String> failures, String what, boolean ok, Result r) {
        if (!ok) {
            failures.add("\n  - " + what + "\n    (exit " + r.exit() + ") " + r.output().strip());
        }
    }

    /** LF, always: a shebang line ending in CR makes bash report {@code bash\r: not found}. */
    static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }

    /**
     * Writes a file the script is meant to RUN rather than read: the execute bit is set on any
     * filesystem that has one, because PATH lookup skips a file it cannot execute instead of
     * failing on it. See {@link #assertStubsAreExecutable}, which makes forgetting this loud.
     */
    static void writeStub(Path p, String content) throws IOException {
        write(p, content);
        if (!supportsPosixPermissions(p)) {
            return;   // NTFS: no execute bit exists to set; bash runs the file from its shebang
        }
        var perms = new HashSet<>(Files.getPosixFilePermissions(p));
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(p, perms);
    }

    /**
     * Every file a test puts on a script's {@code PATH} must be executable — checked at the
     * moment {@code PATH} is composed, as a category, so a stub added by any route is covered.
     * A shell SKIPS a non-executable file on {@code PATH} and keeps searching, so the name
     * resolves to whatever the machine really has, and the failure then reads as a defect in
     * the script several steps away from the stub that was never run.
     */
    static void assertStubsAreExecutable(Path bin) throws IOException {
        if (!supportsPosixPermissions(bin)) {
            return;
        }
        try (var entries = Files.list(bin)) {
            for (Path stub : entries.toList()) {
                assertThat(Files.isExecutable(stub))
                        .withFailMessage("""

                                THIS IS A STUB PROBLEM, NOT A SCRIPT FAILURE. %s is on the PATH this test \
                                hands to the script under test, and it is not executable on this filesystem \
                                (mode %s). A shell SKIPS a non-executable file during PATH lookup and keeps \
                                searching, so the name resolves to whatever this machine really has. Write \
                                every stub with ScriptHarness.writeStub(...), never with write(...) or \
                                Files.writeString, which leave mode 644.""",
                                stub.getFileName(), Files.getPosixFilePermissions(stub))
                        .isTrue();
            }
        }
    }

    /** True where the store can express an execute bit at all — false on NTFS. */
    static boolean supportsPosixPermissions(Path p) throws IOException {
        return Files.getFileStore(p).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    static String read(Path p) throws IOException {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    /** Forward slashes: Git Bash accepts {@code C:/x/y}, and a backslash is an escape. */
    static String posix(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * The body of a top-level shell function {@code name() { … }} in {@code script}. Two scripts
     * that must carry the SAME function (the pin parser, the plan parser, the label sanitiser,
     * the log line) are compared by this text, so a divergence is a red test rather than a box on
     * which the deploy and the drift check disagree. Fails, never returns empty, when the
     * function is absent.
     *
     * <p><strong>BRACE BALANCE, never a text heuristic.</strong> The function ends on the line
     * where the running count of <code>{</code> minus <code>}</code>, started at the declaration,
     * returns to zero. Both spellings fall out of that: a one-liner
     * ({@code log() { printf …; }}) closes on its own line and a multi-line body closes on its
     * own <code>}</code>.
     *
     * <p>Two heuristics were tried here and each was a seal that stayed green over a broken
     * subject. The first understood only the multi-line spelling, so
     * {@code functionBody(drift, "log")} returned sixty lines — the one-liner plus everything
     * down to the NEXT function's closing brace — and a comparison of two such blobs is green
     * exactly when the sixty unrelated lines happen to agree. The second, "the declaration line
     * ends in <code>}</code> means one-liner", truncated a MULTI-LINE function whose declaration
     * carried an ordinary trailing comment ({@code sanitize_label() &#123; # keeps $&#123;v&#125;}) to that
     * one line, and then compared two identical declaration lines: measured 2026-09-10, a real
     * divergence in {@code sanitize_label} (the drift copy silently stopped preserving
     * {@code -} in label values) left BOTH shared-function seals green. A count is not a
     * heuristic — the shell's own grammar decides where the body ends — and an unbalanced file
     * is reported rather than truncated to whatever was collected.
     */
    static String functionBody(Path script, String name) throws IOException {
        var body = new StringBuilder();
        boolean inside = false;
        boolean closed = false;
        int depth = 0;
        for (String line : Files.readAllLines(script, StandardCharsets.UTF_8)) {
            if (!inside && !line.startsWith(name + "() {")) {
                continue;
            }
            inside = true;
            body.append(line).append('\n');
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '{') {
                    depth++;
                } else if (line.charAt(i) == '}') {
                    depth--;
                }
            }
            if (depth <= 0) {
                closed = true;
                break;
            }
        }
        assertThat(body.length())
                .withFailMessage("no %s() function found in %s — both scripts must carry it", name, script)
                .isNotZero();
        assertThat(closed)
                .withFailMessage("""
                        %s() in %s never closes: the braces opened at its declaration are still \
                        %d deep at the end of the file. This helper compares two copies of a \
                        function body, so a body it could not delimit must NOT be returned — a \
                        truncated or over-long body compares two things nobody asked about. \
                        Collected so far:
                        %s""", name, script, depth, body)
                .isTrue();
        return body.toString();
    }

    /**
     * Every top-level shell function a script defines, in file order. A "top-level" definition is
     * one whose {@code name() {} starts at column 0 — nested definitions and anything indented
     * inside a heredoc or an awk program are not functions of this script.
     *
     * <p>This exists so that "the functions two scripts must carry identically" can be DERIVED
     * (the intersection of the two sets) instead of written down. A hand-written member list is a
     * population that silently stops covering its category: it named two while the real
     * intersection was four, and the two it missed included {@code log}, which is byte-identical
     * in both scripts and was sealed by nothing at all.
     */
    static java.util.List<String> topLevelFunctions(Path script) throws IOException {
        var pattern = java.util.regex.Pattern.compile("^([a-z_][a-z0-9_]*)\\(\\) \\{");
        var names = new java.util.ArrayList<String>();
        for (String line : Files.readAllLines(script, StandardCharsets.UTF_8)) {
            var m = pattern.matcher(line);
            if (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /**
     * {@link #functionBody} with the ONE difference two scripts are allowed to have: each names
     * the box's env file with its own variable ({@code "$TARGET/.env"} in the applier,
     * {@code "$ENV_FILE"} in the drift check), and that is a difference of vocabulary rather than
     * of behaviour.
     *
     * <p>Shared rather than copied since HD-299's last round: the elision lived privately in
     * {@link ApplyConfigPinGuardTest} while the derived "every function both scripts carry"
     * comparison had none, so the derived one was red for the one pair the older test had already
     * decided was fine. Any FURTHER divergence is a real one and stays red — the list of
     * permitted differences is here, in one place, and it has exactly one entry.
     */
    static String comparableFunctionBody(Path script, String name) throws IOException {
        return functionBody(script, name)
                .replace("\"$TARGET/.env\"", "<envfile>")
                .replace("\"$ENV_FILE\"", "<envfile>");
    }

    /**
     * Whether this run is on a runner rather than a developer's machine — the one definition,
     * shared, because two classes now decide the same thing with it.
     *
     * <p>Both spellings: {@code GITHUB_ACTIONS} is what {@code AgentChecklistFreshnessTest}'s CI
     * guard reads, {@code CI} is what every other runner sets and what a developer exports to
     * demand the strict behaviour locally. It decides one thing: whether a condition that would
     * be an honest SKIP on a laptop (no daemon, no image, a Compose project that will not start)
     * is a REFUSAL here — because on a runner all of those are arrangeable, and a skipped gate in
     * CI is not a gate.
     */
    static boolean underCi() {
        return System.getenv("GITHUB_ACTIONS") != null || System.getenv("CI") != null;
    }

    /**
     * {@code assumeTrue} that leaves a line on the CONSOLE as well as in the surefire XML.
     *
     * <p>A JUnit assumption's reason reaches {@code target/surefire-reports/*.xml} and nowhere
     * else; the console shows {@code Skipped: 1} and a reader has to go looking to learn which
     * gate stopped running and why. That is the same "silent degrade" this project gives every
     * production branch a counter for, and every caller of this method is a gate that stops
     * running when its condition is false — a real daemon, a real script, a real bash, none of
     * which a unit test replaces. {@code AgentChecklistFreshnessTest} already prints its
     * {@code [agent-freshness]} witness for exactly this reason; the tag makes the line greppable.
     */
    static void assumeWithWitness(String tag, boolean condition, String reason) {
        if (!condition) {
            System.out.println("[" + tag + "] SKIPPED: " + reason);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(condition, reason);
    }

    /** Git Bash on Windows, or the system bash elsewhere; null when there is none at all. */
    static String findBash() {
        var name = System.getProperty("os.name").toLowerCase().contains("win") ? "bash.exe" : "bash";
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                var candidate = Path.of(dir).resolve(name);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            } catch (RuntimeException ignored) {
                // A PATH entry that is not a legal path on this platform is not a bash.
            }
        }
        for (String fallback : List.of("C:\\Program Files\\Git\\bin\\bash.exe", "/bin/bash", "/usr/bin/bash")) {
            if (Files.isExecutable(Path.of(fallback))) {
                return fallback;
            }
        }
        return null;
    }
}
