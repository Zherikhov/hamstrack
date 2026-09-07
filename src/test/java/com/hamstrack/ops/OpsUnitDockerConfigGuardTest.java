package com.hamstrack.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>An ops unit that runs the docker CLI must leave the CLI somewhere to keep its
 * config, and a sandbox that replaces {@code $HOME} does not.</strong>
 *
 * <p>Measured on production 2026-09-07. {@code hamstrack-config-drift.service} carried
 * {@code ProtectHome=yes} plus {@code ProtectSystem=strict}, so inside its namespace
 * {@code /root} is an empty read-only mount; the docker CLI creates its config directory
 * before it runs anything, and the {@code containers} scope died with
 * {@code mkdir /root/.docker: read-only file system} on every run. The same command by hand
 * printed all ten containers {@code Running} and exited 0 — the box was clean and the CHECK
 * was broken, publishing "this check could not ask" into a critical alert with
 * {@code for: 30m}. Nobody saw it for as long as the unit existed, because the timer had
 * never been installed: until that morning the only thing that ever ran the check was
 * {@code apply-config.sh} at the tail of a deploy, as plain root with no sandbox.
 *
 * <p><strong>What this can and cannot prove.</strong> A systemd sandbox is not exercisable
 * from a JUnit suite — no namespace is set up here, no unit is started, and a test that
 * pretended otherwise would be worse than none. What is checkable in the repository is the
 * DECLARATION: that a unit whose commands lead to a {@code docker} invocation either keeps
 * {@code $HOME} or names a config directory it also makes writable. That the namespace then
 * behaves as described is proved by a run on the box, and is recorded in
 * {@code docs/ops-prod-hardening.md} § "Installing the drift check" rather than here.
 *
 * <p><strong>Deliberately about the category, not about the file that failed.</strong> The
 * next unit to hold the docker socket will be written by someone who never read this ticket,
 * by copying whichever existing unit is nearest — so this enumerates every {@code *.service}
 * under {@code ops/}, resolves EVERY command it runs to the script it ships beside, and asks
 * the question of whatever it finds. A unit that runs no docker is asked nothing; a unit whose
 * script cannot be resolved fails, because a scanner that silently skips is the same bug one
 * level up.
 *
 * <p><strong>{@code ProtectHome=read-only} is not accepted as a writable location, and is not
 * failed on the filesystem verdict either.</strong> There {@code $HOME/.docker} is visible, so
 * the CLI is satisfied whenever that directory already exists — and whether it does is a fact
 * about a stranger's host that this repository cannot read. What the repository CAN read is
 * whether anybody weighed it: such a unit must MENTION {@code DOCKER_CONFIG} in its own text,
 * which turns "not failed" into a recorded decision instead of an omission. That matters
 * because the compensating control is otherwise prose in one unit's comment block —
 * {@code hamstrack-backup.service}, which is precisely the file a next author copies — and a
 * copy that dropped the comments would pass in silence.
 */
class OpsUnitDockerConfigGuardTest {

    private static final Path OPS = Path.of("ops");

    /**
     * A {@code docker} invocation in command position. Deliberately not a plain substring
     * search: these scripts are mostly comments, so COMMENT lines are removed before this is
     * applied and a comment quoting {@code docker compose} does not make a unit answer for a
     * call it never makes. It stops there on purpose. A CODE line that merely quotes the text
     * — {@code log "containers: 'docker compose up -d --dry-run' exited $rc …"}
     * ({@code hamstrack-config-drift.sh:481}, {@code :619}) — still matches, and that answer is
     * FAIL-CLOSED by design: the cost of asking a script that only talks about docker for a
     * config directory is three lines it does not need, and the cost of the opposite mistake is
     * a check that can never clear. Requiring a subcommand drops the remaining prose forms
     * ({@code need_tool docker "…"}).
     */
    private static final Pattern DOCKER_CALL = Pattern.compile("(?<![\\w./-])docker\\s+[a-z]");

    /**
     * Every directive that makes systemd run a command. {@code Type=oneshot} permits several
     * {@code ExecStart=} lines, and the surrounding ones run commands too —
     * {@code hamstrack-backup.service} already carries an {@code ExecStopPost=}, benign only
     * because it happens to be the same script, which is exactly what would have kept the gap
     * invisible.
     */
    private static final List<String> EXEC_DIRECTIVES =
            List.of("ExecStartPre", "ExecStart", "ExecStartPost", "ExecStopPost");

    /** Specifier expansions systemd applies to a path in a unit file (this is a SYSTEM unit). */
    private static final Map<String, String> SPECIFIERS = Map.of(
            "%t", "/run", "%S", "/var/lib", "%C", "/var/cache", "%L", "/var/log", "%h", "/root");

    /** Which directory directive is rooted where, for deciding whether a path is writable. */
    private static final Map<String, String> UNIT_DIRECTORIES = Map.of(
            "RuntimeDirectory", "/run",
            "StateDirectory", "/var/lib",
            "CacheDirectory", "/var/cache",
            "LogsDirectory", "/var/log");

    /**
     * The failure message is the propagation checklist, because whoever trips this is holding
     * the docker socket inside a sandbox, and what they need is the shape of the fix rather
     * than the name of the setting that refused them.
     */
    private static final String CHECKLIST = """

            An ops unit runs the docker CLI while its sandbox takes $HOME away from it, and \
            names no other place for the CLI's config directory. The CLI creates that directory \
            before it runs anything, so under ProtectHome=yes|tmpfs — or under \
            ProtectSystem=strict, which remounts the WHOLE hierarchy read-only, $HOME included \
            — every invocation fails with `mkdir <home>/.docker: read-only file system` on a \
            HEALTHY box, which is the worst shape for a check to fail in: it reports that it \
            could not ask, and a monitor that cannot clear gets muted.

            The fix is three lines (see ops/drift/hamstrack-config-drift.service):

              RuntimeDirectory=<unit-name>
              RuntimeDirectoryMode=0700
              Environment=DOCKER_CONFIG=/run/<unit-name>

            systemd creates that directory before the run, mounts it writable inside the \
            namespace, and DELETES it when the unit stops — so the new writable surface is one \
            tmpfs directory that outlives nothing, and no path under /root is unhidden. Write \
            the path out rather than as %t/<unit-name>: systemd copies an UNKNOWN specifier \
            through verbatim, so a build that does not know %t hands the CLI the relative path \
            `%t/<unit-name>`, resolved against WorkingDirectory (unset → /) — the check goes \
            green while creating a directory literally named `%t` at the root of the host. The \
            floor is systemd 235, where RuntimeDirectory= paths became exempt from \
            ProtectSystem=strict (AL2023 ships 252).

            What NOT to reach for:

              * ProtectHome=read-only — it un-hides the whole of $HOME (/root/.aws, ssh keys, \
            shell history) to a unit that holds the docker socket, and it only appears to fix \
            this: a read-only home works while $HOME/.docker ALREADY EXISTS on that host, and \
            fails identically the first time the CLI has to create or write it. A unit that \
            chooses it anyway owes its reader a written note naming DOCKER_CONFIG, which is \
            what this guard asks for in place of a verdict it cannot reach.

              * ReadWritePaths=<home>/.docker — a persistent, root-owned host directory made \
            writable to the socket holder, and every path listed there must EXIST before the \
            unit starts or systemd refuses the namespace and the unit fails before the script \
            runs. It also does nothing while ProtectHome=yes overmounts the home directory. A \
            DOCKER_CONFIG under a home directory is refused here even when a ReadWritePaths= \
            covers it.

            Know what DOCKER_CONFIG moves before you set it: the CLI's per-user plugin \
            directory is $DOCKER_CONFIG/cli-plugins, so redirecting it on a box where \
            `docker compose` was installed into ~/.docker/cli-plugins turns a working unit \
            into "'compose' is not a docker command". That costs a unit whose home is already \
            empty nothing, and is the whole reason a unit under ProtectHome=read-only does not \
            copy the fix.

            Whatever you change here also has to be INSTALLED: these units are compared \
            byte-for-byte with the copy on the box (hamstrack_config_drift{scope="installed-ops"}), \
            so re-run the install step for every unit you touched — docs/ops-prod-hardening.md \
            §6.3 and § "Installing the drift check".
            """;

    @Test
    void everyOpsUnitThatRunsDockerNamesAWritableConfigDirectory() throws IOException {
        var units = serviceUnits();

        // Tripwire on the scan itself, not on which units exist: a walk that finds nothing
        // passes every assertion below without asking a single question.
        assertThat(units)
                .as("no *.service files found under %s — this guard scanned nothing", OPS.toAbsolutePath())
                .isNotEmpty();

        var dockerUnits = new ArrayList<String>();
        var offences = new ArrayList<String>();

        for (var unit : units) {
            var directives = directives(unit);
            var dockerScripts = new ArrayList<Path>();
            for (var script : resolveScripts(unit, directives)) {
                if (invokesDocker(Files.readString(script, StandardCharsets.UTF_8))) {
                    dockerScripts.add(script);
                }
            }
            if (dockerScripts.isEmpty()) {
                continue;
            }
            dockerUnits.add(unit.getFileName().toString());

            var offence = sandboxOffence(directives, Files.readString(unit, StandardCharsets.UTF_8));
            if (offence != null) {
                offences.add(unit + " (runs the docker CLI from " + dockerScripts + "): " + offence);
            }
        }

        // Second tripwire: if the classifier stops recognising a docker call, every unit is
        // exempt and the loop above asserts nothing at all.
        assertThat(dockerUnits)
                .as("no unit under %s was classified as running the docker CLI — either the ops "
                        + "units stopped using docker, or the classifier stopped matching it and "
                        + "this guard is now vacuous", OPS)
                .isNotEmpty();

        assertThat(offences).as(CHECKLIST).isEmpty();
    }

    /**
     * The classifier's own seal, and the reason it is written against fixtures rather than
     * inferred from the tree: "one unit is classified docker and another is not" would be
     * satisfied by a broken classifier on the day the non-docker unit is deleted, and would
     * fail for nothing on the day it is. These strings hold the property directly.
     */
    @Test
    void theClassifierReadsCommandsAndNotComments() {
        assertThat(invokesDocker("""
                #!/usr/bin/env bash
                # The plan comes from `docker compose up -d --dry-run`, and a comment about it
                # is not a call: docker exec, docker inspect, docker ps.
                echo "nothing here runs anything"
                """))
                .as("a script that only MENTIONS docker in comments must not be asked for a DOCKER_CONFIG")
                .isFalse();

        assertThat(invokesDocker("""
                #!/usr/bin/env bash
                cid="$(cd "$dir" && docker compose -f x.yml ps -q postgres)"
                """))
                .as("a real docker call must be recognised, or every unit is silently exempt")
                .isTrue();

        // What is stripped is COMMENTS, and nothing else. Prose quoted on a CODE line —
        // hamstrack-config-drift.sh:481 and :619 are exactly this — still matches, and that is
        // the fail-closed answer: the price is three lines a script may not need, against a
        // check that can never clear.
        assertThat(invokesDocker("""
                #!/usr/bin/env bash
                log "containers: 'docker compose up -d --dry-run' exited $rc — this check could not ask"
                """))
                .as("prose quoted on a code line is classified as a call, fail-closed — if this "
                        + "ever flips, the DOCKER_CALL javadoc is where it has to be said")
                .isTrue();
    }

    /**
     * The verdict's own seal. Every branch below is a way this guard was once blind, and a
     * guard nobody has watched fail is a belief: the tree passes today, so only fixtures can
     * show that these questions are asked at all.
     */
    @Test
    void theVerdictAsksEveryDirectiveThatCanBreakIt() {
        assertThat(offenceFor("""
                [Service]
                ExecStart=/usr/local/bin/thing
                ProtectHome=yes
                """))
                .as("the original failure: an emptied $HOME and no DOCKER_CONFIG")
                .isNotNull();

        assertThat(offenceFor("""
                [Service]
                ExecStart=/usr/local/bin/thing
                ProtectHome=yes
                ProtectSystem=strict
                ReadWritePaths=/root/.docker
                Environment=DOCKER_CONFIG=/root/.docker
                """))
                .as("a DOCKER_CONFIG under the home directory is refused even when a "
                        + "ReadWritePaths= covers it — the checklist names it as the second "
                        + "thing not to reach for, and this unit rejects it outright")
                .isNotNull();

        assertThat(offenceFor("""
                [Service]
                ExecStart=/usr/local/bin/thing
                ProtectSystem=strict
                """))
                .as("ProtectSystem=strict alone remounts the whole hierarchy read-only, $HOME "
                        + "included — the same bug through the neighbouring directive, which a "
                        + "copy of the drift unit gets by dropping ProtectHome= as noise")
                .isNotNull();

        assertThat(offenceFor("""
                [Service]
                ExecStart=/usr/local/bin/thing
                ProtectSystem=full
                """))
                .as("under ProtectSystem=full the home directory is still writable — this must "
                        + "NOT offend, or the guard starts refusing units that work")
                .isNull();

        assertThat(offenceFor("""
                [Service]
                ExecStart=/usr/local/bin/thing
                ProtectHome=read-only
                ProtectSystem=strict
                """))
                .as("a read-only home with no DOCKER_CONFIG and no note is a decision nobody took")
                .isNotNull();

        assertThat(offenceFor("""
                [Service]
                # DOCKER_CONFIG is deliberately NOT set here: $HOME/.docker is visible under a
                # read-only home, and redirecting would move the CLI plugin directory with it.
                ExecStart=/usr/local/bin/thing
                ProtectHome=read-only
                ProtectSystem=strict
                """))
                .as("the unit that writes the contingency down passes — that is the compensating "
                        + "control, and it is enforced here rather than hoped for")
                .isNull();

        assertThat(offenceFor("""
                [Service]
                ExecStart=/usr/local/bin/thing
                ProtectHome=yes
                ProtectSystem=strict
                RuntimeDirectory=thing
                Environment=DOCKER_CONFIG=/run/thing
                """))
                .as("the shipped fix, written out rather than as %t/thing, must pass unchanged")
                .isNull();

        assertThat(offenceFor("""
                [Service]
                ExecStart=/usr/local/bin/thing
                ProtectHome=yes
                ProtectSystem=strict
                Environment=DOCKER_CONFIG=/run/thing
                """))
                .as("a DOCKER_CONFIG nothing makes writable is the original failure with a "
                        + "longer path in its error message")
                .isNotNull();
    }

    /**
     * {@code Type=oneshot} permits several {@code ExecStart=} lines, and a unit also runs
     * commands from {@code ExecStartPre=}/{@code ExecStartPost=}/{@code ExecStopPost=}. Reading
     * only the first one exempts every other command a unit runs.
     */
    @Test
    void everyCommandAUnitRunsIsRead() {
        assertThat(commandBinaries(directivesOf("""
                [Service]
                Type=oneshot
                ExecStartPre=/usr/local/bin/before --check
                ExecStart=-/usr/local/bin/thing
                ExecStart=/usr/local/bin/second
                ExecStopPost=/usr/local/bin/after --stop-post
                """)))
                .as("a command this guard does not read is a docker call it cannot see — "
                        + "hamstrack-backup.service already runs a second one from ExecStopPost=")
                .containsExactly("/usr/local/bin/before", "/usr/local/bin/thing",
                        "/usr/local/bin/second", "/usr/local/bin/after");

        assertThat(commandBinaries(directivesOf("""
                [Service]
                ExecStart=/usr/local/bin/thing
                ExecStart=
                ExecStart=/usr/local/bin/only
                """)))
                .as("an empty assignment RESETS the list and names no command of its own")
                .containsExactly("/usr/local/bin/thing", "/usr/local/bin/only");
    }

    // ---------------------------------------------------------------- helpers

    private static String offenceFor(String unitText) {
        return sandboxOffence(directivesOf(unitText), unitText);
    }

    private static List<Path> serviceUnits() throws IOException {
        try (Stream<Path> walk = Files.walk(OPS)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".service")).sorted().toList();
        }
    }

    /**
     * The verdict, reached from the DECLARATION alone. {@code unitText} is the whole file
     * including its comments, because one branch asks whether the author left a note rather
     * than what the filesystem will do.
     */
    static String sandboxOffence(Map<String, List<String>> directives, String unitText) {
        var dockerConfig = dockerConfig(directives);
        var home = orDefault(last(directives, "ProtectHome"), "no");
        var protectSystem = orDefault(last(directives, "ProtectSystem"), "no");

        if (dockerConfig != null) {
            // Refused whatever else the unit says, and BEFORE the writability question: a
            // ReadWritePaths= for it is inert while ProtectHome= overmounts the home
            // directory, and where it is not inert it hands a persistent, root-owned host
            // directory to the holder of the docker socket. The checklist names this as the
            // second thing not to reach for, so the guard must not accept it.
            if (underHome(dockerConfig)) {
                return "declares DOCKER_CONFIG=" + dockerConfig + ", which resolves under the"
                        + " home directory this sandbox exists to take away — a ReadWritePaths="
                        + " for it is inert under ProtectHome=yes, and where it is not it makes a"
                        + " persistent root-owned host directory writable to the docker socket"
                        + " holder. Point it at a RuntimeDirectory= instead";
            }
            // Applies whatever ProtectHome says: a DOCKER_CONFIG pointing at a path the same
            // unit does not make writable is the original failure with a longer path in its
            // error message.
            if (!writableInSandbox(dockerConfig, directives)) {
                return "declares DOCKER_CONFIG=" + dockerConfig
                        + ", which this unit makes writable through no RuntimeDirectory=,"
                        + " StateDirectory=, CacheDirectory=, LogsDirectory= or ReadWritePaths=";
            }
            return null;
        }

        if ("yes".equals(home) || "tmpfs".equals(home)) {
            return "runs the docker CLI under ProtectHome=" + home + ", which replaces $HOME"
                    + " with an empty read-only mount, and declares no DOCKER_CONFIG";
        }

        // ProtectHome=read-only: no filesystem verdict is available from here, because the CLI
        // is satisfied while $HOME/.docker happens to exist on that host and this repository
        // cannot know whether it does. What IS readable is whether anybody weighed it — so the
        // unit must name DOCKER_CONFIG somewhere in its own text. Without that, "not failed"
        // and "never considered" are the same answer, and the nearest unit for a next author to
        // copy is hamstrack-backup.service, whose whole compensating control is a comment block
        // that a copy could drop in silence.
        if ("read-only".equals(home)) {
            return unitText.contains("DOCKER_CONFIG") ? null
                    : "runs the docker CLI under ProtectHome=read-only with no DOCKER_CONFIG and"
                    + " says nothing about it. That is not necessarily broken — $HOME/.docker is"
                    + " VISIBLE here, so the CLI is satisfied while that directory already exists"
                    + " — but it is contingent on an unmanaged host fact, and this file records no"
                    + " decision. Either set DOCKER_CONFIG (see the fix below) or write the"
                    + " contingency down in this unit: name DOCKER_CONFIG, say why it is not set,"
                    + " and say which host fact the unit is relying on";
        }

        // The same bug through the neighbouring directive. ProtectSystem=strict remounts the
        // WHOLE hierarchy read-only, $HOME included, so a unit copied from the drift one with
        // ProtectHome= dropped as noise (the default is no) fails with the identical EROFS —
        // and was asked nothing here until HD-287. Only strict: under full the home directory
        // is still writable. ProtectHome=read-only reaches this line for nobody — it is a
        // deliberate statement about $HOME and the branch above has already answered it.
        if ("strict".equals(protectSystem)) {
            return "runs the docker CLI under ProtectSystem=strict, which remounts the whole"
                    + " filesystem hierarchy read-only INCLUDING $HOME, and declares neither a"
                    + " ProtectHome= of its own nor a DOCKER_CONFIG: the CLI's `mkdir"
                    + " $HOME/.docker` is the same EROFS it is under ProtectHome=yes";
        }
        return null;
    }

    /** Last assignment wins, which is systemd's rule for a single-valued setting. */
    private static String last(Map<String, List<String>> directives, String key) {
        var values = directives.get(key);
        return values == null || values.isEmpty() ? null : values.getLast().toLowerCase(Locale.ROOT);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static Map<String, List<String>> directives(Path unit) {
        try {
            return directivesOf(Files.readString(unit, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, List<String>> directivesOf(String unitText) {
        var directives = new LinkedHashMap<String, List<String>>();
        var joined = new StringBuilder();
        for (var raw : unitText.lines().toList()) {
            var line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("[")) {
                continue;
            }
            // A unit file may continue a value onto the next line with a trailing backslash.
            if (line.endsWith("\\")) {
                joined.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            joined.append(line);
            var whole = joined.toString();
            joined.setLength(0);
            var eq = whole.indexOf('=');
            if (eq > 0) {
                directives.computeIfAbsent(whole.substring(0, eq).strip(), k -> new ArrayList<>())
                        .add(whole.substring(eq + 1).strip());
            }
        }
        return directives;
    }

    /**
     * Every command this unit runs, in declaration order, stripped of systemd's prefix
     * characters. An empty assignment resets the list and contributes no command of its own.
     */
    static List<String> commandBinaries(Map<String, List<String>> directives) {
        var binaries = new LinkedHashSet<String>();
        for (var directive : EXEC_DIRECTIVES) {
            for (var declaration : directives.getOrDefault(directive, List.of())) {
                var command = declaration.stripLeading();
                while (!command.isEmpty() && "-+!@:".indexOf(command.charAt(0)) >= 0) {
                    command = command.substring(1);
                }
                var binary = command.isEmpty() ? "" : command.split("\\s+")[0];
                if (!binary.isEmpty()) {
                    binaries.add(binary);
                }
            }
        }
        return List.copyOf(binaries);
    }

    /**
     * Resolve every command to the script this repository ships for it. The installed binary is
     * a copy under {@code /usr/local/bin} with the {@code .sh} dropped, so the basename is the
     * join. An unresolvable command FAILS rather than being skipped: a command this guard
     * cannot read is a command this guard does not cover, and that is exactly the state it
     * exists to end.
     */
    private static List<Path> resolveScripts(Path unit, Map<String, List<String>> directives) throws IOException {
        var binaries = commandBinaries(directives);
        assertThat(binaries)
                .as("%s declares no command at all (ExecStart=, ExecStartPre=, ExecStartPost=, "
                        + "ExecStopPost=)", unit)
                .isNotEmpty();

        var scripts = new ArrayList<Path>();
        for (var binary : binaries) {
            var base = binary.substring(binary.lastIndexOf('/') + 1);
            try (Stream<Path> walk = Files.walk(OPS)) {
                var candidates = walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            var name = p.getFileName().toString();
                            return name.equals(base) || name.equals(base + ".sh");
                        })
                        .toList();
                assertThat(candidates)
                        .as("%s runs %s, and no single script named %s[.sh] exists under %s. Either the "
                                + "script moved out of the repository (then this unit ships something "
                                + "nothing reviews) or it was renamed away from its unit — this guard has "
                                + "to be able to read what a unit runs, or it exempts by accident.",
                                unit, binary, base, OPS)
                        .hasSize(1);
                if (!scripts.contains(candidates.getFirst())) {
                    scripts.add(candidates.getFirst());
                }
            }
        }
        return scripts;
    }

    static boolean invokesDocker(String script) {
        var code = new StringBuilder();
        script.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .forEach(line -> code.append(line).append('\n'));
        return DOCKER_CALL.matcher(code).find();
    }

    /** The {@code DOCKER_CONFIG} value from any {@code Environment=} line, or {@code null}. */
    private static String dockerConfig(Map<String, List<String>> directives) {
        for (var assignment : directives.getOrDefault("Environment", List.of())) {
            for (var token : assignment.split("\\s+")) {
                var bare = token.replace("\"", "").replace("'", "");
                if (bare.startsWith("DOCKER_CONFIG=")) {
                    return bare.substring("DOCKER_CONFIG=".length());
                }
            }
        }
        return null;
    }

    private static String expand(String path) {
        for (var specifier : SPECIFIERS.entrySet()) {
            if (path.startsWith(specifier.getKey())) {
                return specifier.getValue() + path.substring(specifier.getKey().length());
            }
        }
        return path;
    }

    /**
     * Home-rooted for a SYSTEM unit: {@code /root}, anything under {@code /home}, {@code %h} or
     * a {@code ~} the CLI would expand itself.
     */
    private static boolean underHome(String path) {
        var expanded = expand(path);
        return under(expanded, "/root") || under(expanded, "/home") || expanded.startsWith("~");
    }

    private static boolean writableInSandbox(String path, Map<String, List<String>> directives) {
        var expanded = expand(path);

        for (var directory : UNIT_DIRECTORIES.entrySet()) {
            for (var declaration : directives.getOrDefault(directory.getKey(), List.of())) {
                for (var name : declaration.split("\\s+")) {
                    if (under(expanded, directory.getValue() + "/" + name)) {
                        return true;
                    }
                }
            }
        }
        for (var declaration : directives.getOrDefault("ReadWritePaths", List.of())) {
            for (var entry : declaration.split("\\s+")) {
                var candidate = entry.startsWith("-") || entry.startsWith("+") ? entry.substring(1) : entry;
                if (under(expanded, candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
    }
}
