package com.hamstrack.ops;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-221 — the {@code containers} scope of {@code ops/drift/hamstrack-config-drift.sh}
 * must be SILENT on a clean tree and LOUD on a drifted one, and the first half is the one
 * that had no test.</strong>
 *
 * <p>The implementation this replaced would have passed a "detects drift" test perfectly: it
 * compared each service's {@code com.docker.compose.config-hash} label against
 * {@code docker compose config --hash '*'}, and on production those two disagreed for a service
 * that {@code up -d} itself declined to recreate — so the alarm fired continuously for a box in
 * the state it was deployed in. A detector that cannot clear gets muted, and the next real
 * drift arrives into a channel nobody reads. <strong>The property under test is therefore the
 * conjunction, and the silence is the half that carries it.</strong>
 *
 * <p><strong>Why the new oracle needs a seal of its own shape.</strong> Compose writes the
 * {@code up -d --dry-run} plan to <em>stderr</em>; its stdout is empty on a clean box and
 * equally empty on a drifted one. So the obvious implementation — "the dry run printed
 * nothing, therefore nothing would change" — is green for ever, which is this ticket's own
 * defect one layer down. The script consequently reads a POSITIVE signal (every running
 * container must appear in the plan, as {@code Running}) rather than an absence, and
 * {@link #theOracleFailingIsNeverAnAnswerOfNo} drives every way that reading can break.
 *
 * <p><strong>What is real and what is stubbed.</strong>
 * {@link #aCleanTreeIsSilentAndARealDriftIsLoud} uses the REAL docker and the REAL Compose on
 * a scratch project of its own — nothing else can establish that Compose's plan says what this
 * script believes it says, and it is also where the "{@code --dry-run} cannot mutate" claim is
 * turned into a checked property rather than a reading of somebody's source. It skips when
 * there is no usable daemon or no locally present image, and it never pulls: a monitor's seal
 * must not depend on a registry. The other tests replace {@code docker} with a stub and drive
 * failure modes a real daemon will not produce on demand.
 *
 * <p><strong>The developer's own containers are not this test's business.</strong> Every
 * project here is created under a name of its own, in its own scratch directory, and is torn
 * down with {@code down -v} in a finally — {@code hamstrack-postgres} and
 * {@code hamstrack-mailhog} are untouched, and nothing in this class runs a compose command
 * without an explicit project name.
 */
class ConfigDriftContainerOracleTest {

    private static final Path SCRIPT = Path.of("ops/drift/hamstrack-config-drift.sh");
    private static final Path RULES = Path.of("observability/grafana/provisioning/alerting/rules.yml");

    /**
     * The failure message is the propagation checklist rather than a comment, because whoever
     * trips this is editing the one check whose entire value is that its silence means
     * something.
     */
    private static final String CHECKLIST = """

            ops/drift/hamstrack-config-drift.sh publishes hamstrack_config_drift{scope="containers"},
            which the ConfigDrift alert reads. It is a READ-ONLY monitor that runs hourly on the
            production box and again at the end of every deploy, and every rule below cost
            something:

              * SILENCE IS THE PROPERTY. A detector that fires permanently gets muted, and a muted
                detector is worse than none -- the next real drift (a hand-edited compose file on
                the box, which is what HD-199 exists to prevent) arrives into a channel nobody
                reads. HD-221 was exactly that: the config-hash comparison disagreed with `up -d`
                on production for a service `up -d` would not recreate, for a week. So a test that
                only proves drift is DETECTED proves the half the defect already satisfied.

              * THE PLAN IS ON STDERR. `docker compose up -d --dry-run` writes its per-container
                progress to stderr; stdout is EMPTY whether the box is clean or drifted. An
                implementation that reads stdout is green for ever. The redirection is `2>&1` and
                it is load-bearing.

              * THE TEST IS POSITIVE, NOT AN ABSENCE. A clean service appears in the plan as
                `Running`; it is not missing from it. So every running container must be NAMED by
                the plan, and named with that verb. An oracle that goes blind -- a Compose that
                stops printing per-container status, a flag that stops being accepted -- then
                reports every service as unplanned and is loud, instead of reading as health.

              * ANY VERB THAT IS NOT `Running` IS DRIFT, including one the script has never seen.
                If a future Compose renames `Recreate`, an allow-list of drift verbs goes quietly
                green and a deny-list goes loudly red naming the verb it did not understand. Only
                one of those two mistakes is survivable in a monitor.

              * AN ORPHAN IS DRIFT, AND NO PER-SERVICE COMPARISON CAN SEE ONE. A deploy runs
                `up -d --remove-orphans`; this plans `up -d` without that flag, deliberately, since
                a sweep flag belongs to no read-only monitor. A service deleted from a compose file
                whose container still runs is then in no `config --services` list and gets no
                `Container` line -- measured, containers=0 on a box where the next deploy would
                destroy a running container. Compose warns about it on the same stream as the plan,
                and reading that warning is the whole of the fix.

              * A FAILURE OF THE COMMAND IS NOT AN ANSWER OF "NO". Non-zero exit, an unresolvable
                configuration, a service that is declared and not running: each publishes 1 and
                says why. A check that could not ask its question has not shown the box is clean.

              * `--dry-run` IS WRITE-SHAPED AND MUST NOT WRITE. Compose swaps the whole Docker API
                client for a dry-run client before the command runs, so no create/start/remove
                path can forget it -- but that is a claim about somebody else's source, so this
                class checks the consequence instead: a plan that says `Recreate` leaves the
                container id alone, and a plan against a project that is entirely down brings no
                container and no network into existence. Nothing else in this script may grow a
                mutating compose subcommand; theMonitorNeverRunsAMutatingComposeCommand is what
                keeps that true.

              * A LISTED COMPOSE FILE THAT IS ABSENT IS SKIPPED. That predates this ticket and
                survives it: a deployment without the observability stack must not report
                containers=1 for ever about a stack that is entirely healthy. The rule now has to
                hold for BOTH compose invocations (`config --services` and `up -d --dry-run`) or
                they resolve different projects and the second plans to create containers the
                first never declared.

            What moves with a change here: the scope-2 header in the script, the `containers` arm
            of the ConfigDrift summary in
            observability/grafana/provisioning/alerting/rules.yml (an annotation is rendered per
            instance and the arm is what makes it actionable), docs/observability.md's metric
            table, and docs/design/config-delivery-proposal.md section 8.

            And the cause of the production disagreement between the two hashes was never
            established. It stays open, deliberately: the interpolation hypothesis is EXCLUDED
            (postgres and caddy carry the same interpolated-with-default shape and matched, and a
            probe on Compose v5.1.0 hashed the literal and the interpolated form identically),
            and what remains -- the production Compose version, and the file set each path
            resolved -- cannot be settled from off the box. Do not write a cause into this file.
            """;

    /**
     * Candidates in the order they are tried, all of which must already be on the machine.
     * {@code postgres:16-alpine} is first because it is the one image both places this suite
     * runs are guaranteed to have: the developer's {@code hamstrack-postgres} runs it, and
     * {@code .github/workflows/build.yml} declares it as a service container, which the runner
     * pulls before the job starts. Nothing here pulls — a seal for a monitor that must work
     * without a network is not allowed to need one.
     */
    private static final List<String> IMAGE_CANDIDATES =
            List.of("postgres:16-alpine", "debian:12-slim", "alpine:3");

    private static String bash;

    @TempDir
    Path work;

    @BeforeAll
    static void locateBash() {
        bash = findBash();
    }

    // --- the conjunction, against a real daemon --------------------------------------

    /**
     * Clean tree: {@code containers=0}. One field changed: {@code containers=1}, naming the
     * service. And the container id is the same on both sides of the drifted run, which is
     * how "a write-shaped command in a read-only monitor cannot write" stops being a belief.
     */
    @Test
    void aCleanTreeIsSilentAndARealDriftIsLoud() throws Exception {
        assumeBash();
        var image = firstLocallyPresentImage();
        Assumptions.assumeTrue(image != null,
                "no usable docker daemon, or none of " + IMAGE_CANDIDATES + " is present locally. "
                        + "This test brings up a scratch Compose project to ask the real Compose what "
                        + "`up -d --dry-run` says, and it deliberately does not pull. `docker pull "
                        + "postgres:16-alpine` enables it.");

        var project = "hd221oracle" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var box = work.resolve(project);
        var compose = box.resolve("docker-compose.yml");
        // Two services, so "the plan mentions every container" is a claim about a set rather
        // than about the only member of one. The drift is an ENVIRONMENT value and not a
        // mem_limit: memory limits depend on what the host's cgroups will express, and the
        // property under test is not "Compose notices memory".
        var bothServices = """
                services:
                  alpha:
                    image: %s
                    entrypoint: ["sleep"]
                    command: ["300"]
                    environment:
                      HD221_KNOB: "one"
                  beta:
                    image: %s
                    entrypoint: ["sleep"]
                    command: ["300"]
                """.formatted(image, image);
        write(compose, bothServices);

        var failures = new ArrayList<String>();
        try {
            var up = docker(box, "compose", "-p", project, "-f", "docker-compose.yml", "up", "-d");
            Assumptions.assumeTrue(up.exit() == 0,
                    "the scratch Compose project would not start, so this machine cannot answer the "
                            + "question this test asks:\n" + up.output());

            var alphaBefore = containerId(box, project, "alpha");
            var betaBefore = containerId(box, project, "beta");
            expect(failures, "the scratch project really started", !alphaBefore.isBlank() && !betaBefore.isBlank(),
                    up);

            // --- clean ---------------------------------------------------------------
            var clean = runDrift(box, project, Map.of());
            expect(failures, "a clean tree publishes containers=0", containersGauge(box) == 0, clean);
            expect(failures, "a clean tree says so in one line rather than by saying nothing",
                    clean.output().contains("would act on nothing"), clean);
            expect(failures, "a clean tree names no service as drifted",
                    !clean.output().contains("would act on alpha") && !clean.output().contains("would act on beta"),
                    clean);
            expect(failures, "a clean tree does not report the oracle as unreadable",
                    !clean.output().contains("planned nothing"), clean);

            // --- one field changed ---------------------------------------------------
            write(compose, read(compose).replace("HD221_KNOB: \"one\"", "HD221_KNOB: \"two\""));
            var drifted = runDrift(box, project, Map.of());
            expect(failures, "a real drift publishes containers=1", containersGauge(box) == 1, drifted);
            expect(failures, "…naming the service that drifted", drifted.output().contains("act on alpha"), drifted);
            expect(failures, "…and quoting the verb Compose actually planned, so an unknown verb is "
                    + "readable rather than swallowed", drifted.output().contains("compose plans '"), drifted);
            expect(failures, "…and not also claiming the untouched service drifted",
                    !drifted.output().contains("act on beta"), drifted);
            expect(failures, "…and not also emitting the clean line",
                    !drifted.output().contains("would act on nothing"), drifted);

            // --- the write-shaped command did not write ------------------------------
            // The mutation claim needs the plan to have been a MUTATING one, so this row pins
            // Compose's word for it — the only place in this class that does, and deliberately
            // so: everywhere else an unknown verb must simply be drift. If Compose renames
            // `Recreate`, the check above still goes red for the right reason and only this
            // row needs re-pointing, at which point re-establish the id-invariance below
            // against whatever the new mutating verb is.
            expect(failures, "the plan was a genuinely mutating one, so the id check below means "
                    + "something (Compose's word for it today is `Recreate`)",
                    drifted.output().contains("compose plans 'Recreate'"), drifted);
            // If `--dry-run` were doing anything at all, this is where it would show: a
            // recreated container has a new id.
            expect(failures, "the dry run that planned a recreate left alpha's container id alone",
                    alphaBefore.equals(containerId(box, project, "alpha")), drifted);
            expect(failures, "…and left beta's alone too",
                    betaBefore.equals(containerId(box, project, "beta")), drifted);

            // --- a service DELETED from the file, its container still running ---------
            // The blind spot the per-service comparison cannot have: an orphan is in no
            // `config --services` list and gets no `Container` line, so before this was read
            // out of Compose's warning the box below published containers=0 while a deploy's
            // `up -d --remove-orphans` would have destroyed a running container. The file is
            // restored to alpha's running definition first, so the only thing left to notice
            // is the orphan — a row that passed because alpha still drifted would prove
            // nothing.
            write(compose, bothServices.substring(0, bothServices.indexOf("  beta:")));
            var orphaned = runDrift(box, project, Map.of());
            expect(failures, "a service deleted from the file whose container still runs is drift",
                    containersGauge(box) == 1, orphaned);
            expect(failures, "…named, out of compose's own warning",
                    orphaned.output().contains("orphan") && orphaned.output().contains(project + "-beta"),
                    orphaned);
            expect(failures, "…and not reported as a clean box",
                    !orphaned.output().contains("would act on nothing"), orphaned);
            write(compose, bothServices);

            // --- and it creates nothing when there is nothing there -------------------
            // The other direction of the same claim, and the stronger one: against a project
            // that is entirely down, a plan is all Create/Start, so anything that leaked
            // through would be a container and a network that now exist.
            docker(box, "compose", "-p", project, "-f", "docker-compose.yml", "down", "-v", "-t", "1");
            var onNothing = runDrift(box, project, Map.of());
            expect(failures, "a plan against a project that is entirely down still publishes drift",
                    containersGauge(box) == 1, onNothing);
            var ps = docker(box, "ps", "-a", "--filter", "name=" + project, "--format", "{{.Names}}");
            expect(failures, "…and created no container", ps.output().isBlank(), ps);
            var nets = docker(box, "network", "ls", "--filter", "name=" + project, "--format", "{{.Name}}");
            expect(failures, "…and created no network", nets.output().isBlank(), nets);
        } finally {
            docker(box, "compose", "-p", project, "-f", "docker-compose.yml",
                    "down", "-v", "--remove-orphans", "-t", "1");
        }

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- every way the oracle can break, none of which may read as health ------------

    /**
     * The failure modes a real daemon will not produce on demand, driven through a stubbed
     * {@code docker}. Each row is a way the check could stop being able to read an answer, and
     * every one of them must publish 1: <em>a monitor that could not ask its question has not
     * shown that the box is clean.</em>
     *
     * <p>The first row is the control that makes the other rows mean something — the stub CAN
     * produce a clean reading, and it does so by writing the plan to stderr and nothing to
     * stdout, which is where Compose really writes it. That row fails if the script is ever
     * rewritten to read stdout.
     */
    @Test
    void theOracleFailingIsNeverAnAnswerOfNo() throws Exception {
        assumeBash();
        var failures = new ArrayList<String>();

        // (0) The control. Plan on stderr only, both containers Running: this is what a clean
        //     box looks like, and it is the only row here that may publish 0.
        var clean = runStubbed("clean", Map.of(
                "STUB_PLAN", " Container alpha Running \n Container beta Running "));
        expect(failures, "a plan delivered on STDERR reads as clean — the script must not be "
                + "reading stdout, which Compose leaves empty either way",
                containersGauge(clean.box()) == 0, clean.result());

        // (1) The plan says nothing at all, successfully. This is precisely the shape of the
        //     obvious wrong implementation, and it must NOT be health.
        var blind = runStubbed("blind", Map.of("STUB_PLAN", ""));
        expect(failures, "an empty plan is an unreadable oracle, not a clean box",
                containersGauge(blind.box()) == 1, blind.result());
        expect(failures, "…and says so as an oracle problem rather than as a drifted service",
                blind.result().output().contains("planned nothing"), blind.result());

        // (2) The plan arrives on stdout instead. Compose does not do this today, and the row
        //     is here to pin that the capture MERGES the two streams rather than swapping
        //     which one it reads: a fix for row (0) that moved the read from stdout to stderr
        //     would satisfy row (0) and lose everything Compose ever writes to stdout.
        var wrongStream = runStubbed("stdout-only", Map.of(
                "STUB_PLAN_STDOUT", " Container alpha Running \n Container beta Running ",
                "STUB_PLAN", ""));
        expect(failures, "a plan on stdout is read too — the capture merges the streams rather "
                + "than choosing one of them",
                containersGauge(wrongStream.box()) == 0, wrongStream.result());

        // (3) The command itself fails — an unsupported flag, a daemon that is not there.
        var failed = runStubbed("plan-fails", Map.of(
                "STUB_PLAN", "unknown flag: --dry-run", "STUB_PLAN_RC", "125"));
        expect(failures, "a dry run that exits non-zero publishes drift",
                containersGauge(failed.box()) == 1, failed.result());
        expect(failures, "…and says the check could not ask, not that the answer was no",
                failed.result().output().contains("could not ask"), failed.result());

        // (3b) The failure a production box actually produces: a `depends_on:
        //      condition: service_healthy` whose target is not healthy makes Compose abandon
        //      the plan where it stands — exit 1, with every service it had not reached yet
        //      unexamined. (How long it takes to get there is the dependency's health state,
        //      not a constant: an already-`unhealthy` target is refused at once, a `starting`
        //      one holds the run until its healthcheck settles. The script's comment carries
        //      that; this row is about the verdict.) It must publish 1 (a plan that stopped early
        //      has shown nothing about what came after it) and it must SAY which of the two
        //      kinds of incident the reader is looking at, because a ConfigDrift alert during
        //      an outage is otherwise a puzzle on top of an outage.
        var unhealthy = runStubbed("unhealthy-dependency", Map.of(
                "STUB_PLAN", " Container alpha Running \n Container beta Waiting \n"
                        + " Container beta Error dependency beta failed to start\n"
                        + "dependency failed to start: container beta is unhealthy",
                "STUB_PLAN_RC", "1"));
        expect(failures, "a plan abandoned at an unhealthy dependency publishes drift",
                containersGauge(unhealthy.box()) == 1, unhealthy.result());
        expect(failures, "…and names it as a health incident rather than leaving the reader to "
                + "infer it from compose's output",
                unhealthy.result().output().contains("UNHEALTHY"), unhealthy.result());

        // (4) A verb this script has never seen. Fail-closed: a renamed `Recreate` must go red
        //     and print the word, not go green because it is not on a list.
        var unknownVerb = runStubbed("unknown-verb", Map.of(
                "STUB_PLAN", " Container alpha Replace \n Container beta Running "));
        expect(failures, "an unrecognised verb is drift", containersGauge(unknownVerb.box()) == 1,
                unknownVerb.result());
        expect(failures, "…and is quoted back so the reader can see what Compose said",
                unknownVerb.result().output().contains("'Replace'"), unknownVerb.result());
        expect(failures, "…against the service it belongs to",
                unknownVerb.result().output().contains("act on alpha"), unknownVerb.result());

        // (5) The configuration will not resolve at all. Unchanged behaviour, kept.
        var unresolvable = runStubbed("unresolvable", Map.of("STUB_CONFIG_FAIL", "1"));
        expect(failures, "an unresolvable configuration is drift",
                containersGauge(unresolvable.box()) == 1, unresolvable.result());

        // (6) The files resolve, but to nothing. There is then no declaration to compare
        //     against, which is not the same as a match.
        var empty = runStubbed("no-services", Map.of("STUB_SERVICES", ""));
        expect(failures, "a configuration that resolves to no services is drift",
                containersGauge(empty.box()) == 1, empty.result());

        // (7) A declared service with no container. The dry run would say `Creating`, but this
        //     is checked independently of the plan on purpose — it is the one control that
        //     survives the plan becoming unreadable.
        var notRunning = runStubbed("not-running", Map.of(
                "STUB_PS_EMPTY", "beta",
                "STUB_PLAN", " Container alpha Running "));
        expect(failures, "a declared service with no container is drift",
                containersGauge(notRunning.box()) == 1, notRunning.result());
        expect(failures, "…and is named", notRunning.result().output().contains("beta is declared and not running"),
                notRunning.result());

        // (8) An orphan, in Compose's own words, on an otherwise perfect plan. The real-daemon
        //     test proves the box behaves this way; this row pins the PARSE, which is the part
        //     that breaks silently — `awk '$1 == "Container"'` throws this line away, so
        //     without the grep the reading below is 0 and a container a deploy would delete is
        //     invisible.
        var orphan = runStubbed("orphan", Map.of(
                "STUB_PLAN", " Container alpha Running \n Container beta Running \n"
                        + "level=warning msg=\"Found orphan containers ([stub-orphan-gone-1]) "
                        + "for this project\""));
        expect(failures, "an orphan container is drift even though every declared service matches",
                containersGauge(orphan.box()) == 1, orphan.result());
        expect(failures, "…and is named, out of the warning compose already wrote",
                orphan.result().output().contains("stub-orphan-gone-1"), orphan.result());
        expect(failures, "…and does not also emit the clean line",
                !orphan.result().output().contains("would act on nothing"), orphan.result());

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * The rule that predates this ticket and had to survive it: a compose file NAMED in
     * {@code COMPOSE_FILES} but ABSENT from the box is skipped, so a deployment that runs
     * without the observability stack does not report {@code containers=1} for ever about a
     * stack that is entirely healthy.
     *
     * <p>It now has to hold for BOTH compose invocations. {@code config --services} says which
     * services are supposed to exist and {@code up -d --dry-run} says what would happen to
     * them; if only one of the two dropped the absent file they would resolve different
     * projects, and the second would plan to create containers the first never declared —
     * permanent drift assembled out of two individually correct commands.
     */
    @Test
    void aListedComposeFileThatIsAbsentIsSkippedByBothComposeInvocations() throws Exception {
        assumeBash();
        var failures = new ArrayList<String>();

        var run = runStubbed("absent-file", Map.of(
                        "STUB_PLAN", " Container alpha Running \n Container beta Running "),
                "docker-compose.yml docker-compose.observability.yml");

        expect(failures, "a listed-but-absent compose file does not make a healthy box drift",
                containersGauge(run.box()) == 0, run.result());

        var calls = read(run.dockerLog());
        var composeCalls = calls.lines().filter(l -> l.startsWith("compose ")).toList();
        expect(failures, "the script actually invoked compose", !composeCalls.isEmpty(), run.result());
        for (String call : composeCalls) {
            expect(failures, "no compose invocation was handed the absent file [" + call + "]",
                    !call.contains("docker-compose.observability.yml"), run.result());
            expect(failures, "every compose invocation was handed the present one [" + call + "]",
                    call.contains("-f docker-compose.yml"), run.result());
        }
        // The two that matter are the pair that must agree. Named, so that adding a third
        // compose invocation without extending the skip is visible here.
        for (String subcommand : List.of("config --services", "up -d --dry-run")) {
            expect(failures, "the [" + subcommand + "] invocation happened at all",
                    composeCalls.stream().anyMatch(c -> c.contains(subcommand)), run.result());
        }

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- the monitor stays read-only -------------------------------------------------

    /**
     * {@code up -d --dry-run} is a write-shaped command, and the argument that it cannot write
     * is an argument about that ONE command. It buys nothing if the script later grows a
     * {@code compose restart} or a {@code --force-recreate} "just to fix the drift it found" —
     * an hourly timer that repairs the box would delete the very evidence HD-199 exists to
     * surface, and would do it under the name of a checker.
     *
     * <p>Reads the file rather than running it, so it holds on a machine with no bash and no
     * daemon. Phrased as a category — every {@code docker compose} invocation in the file — so
     * a new one is covered without anyone remembering this rule.
     */
    @Test
    void theMonitorNeverRunsAMutatingComposeCommand() throws IOException {
        assertThat(SCRIPT)
                .withFailMessage("%s was not found — this test reads the repository's own copy, so it "
                        + "must run from the module root", SCRIPT.toAbsolutePath())
                .isRegularFile();
        var body = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        var invocation = Pattern.compile("docker compose\\b[^\n]*");
        var mutating = Pattern.compile(
                "\\b(down|restart|start|stop|kill|rm|create|pull|push|build|run|exec|cp|scale|wait)\\b");
        var up = Pattern.compile("\\bup\\b");

        var offenders = new ArrayList<String>();
        var invocations = new ArrayList<String>();
        for (String line : body.lines().toList()) {
            // Two kinds of line NAME a compose command without running one, and both must be
            // allowed to: a comment, and a `log` message. This script's messages quote the
            // command they are about on purpose — an operator reading "docker compose up -d
            // would act on app" needs the name of the thing that would run. A message that
            // SUBSTITUTES a command into itself is not exempt, because that really does run it.
            var code = line.strip().startsWith("#") ? "" : line;
            int message = code.indexOf("log \"");
            if (message >= 0 && !code.substring(message).contains("$(")) {
                code = code.substring(0, message);
            }
            var m = invocation.matcher(code);
            while (m.find()) {
                var call = m.group();
                invocations.add(call);
                // `--dry-run` itself ends in the word `run`, which is a compose subcommand that
                // creates a container — so the mutating scan runs against a copy with the flag
                // removed, and the flag's PRESENCE is what the `up` arm below then checks.
                if (mutating.matcher(call.replace("--dry-run", "")).find()) {
                    offenders.add(call);
                } else if (up.matcher(call).find() && !call.contains("--dry-run")) {
                    offenders.add(call);
                } else if (call.contains("--force-recreate")) {
                    offenders.add(call);
                }
            }
        }

        assertThat(invocations)
                .withFailMessage(CHECKLIST + """

                        No `docker compose` invocation was found in %s at all, so this seal would pass \
                        for any content. The containers scope is built on Compose's own plan; if it \
                        has been rewritten to something else, re-point this test rather than deleting \
                        it -- what it protects is that an HOURLY MONITOR never repairs the box it is \
                        describing.
                        """, SCRIPT)
                .isNotEmpty();

        assertThat(offenders)
                .withFailMessage(CHECKLIST + """

                        ops/drift/hamstrack-config-drift.sh runs a compose command that can CHANGE the \
                        box: %s

                        This script is a read-only monitor. It runs hourly from a systemd timer and \
                        again at the tail of every deploy, and its whole job is to describe a \
                        divergence, never to close one -- a checker that quietly repairs deletes the \
                        evidence of the hand edit HD-199 exists to surface, on a schedule, with no \
                        record. The single write-shaped command it may run is `up -d --dry-run`, whose \
                        containment is the point of the flag; every other compose subcommand here must \
                        be one that only reads.
                        """, offenders)
                .isEmpty();
    }

    /**
     * The alert's summary is rendered per instance and branches on {@code $labels.scope}; the
     * {@code containers} arm is the sentence an operator reads at 3 a.m. HD-221 changed what
     * that scope MEANS — from "two hashes disagree" to "`docker compose up -d` would act on
     * something" — and a summary left describing the old mechanism sends the reader to a
     * comparison that no longer exists.
     */
    @Test
    void theConfigDriftSummaryStillDescribesWhatTheContainersScopeNowMeasures() throws IOException {
        assertThat(RULES)
                .withFailMessage("%s was not found — this test reads the repository's own copy",
                        RULES.toAbsolutePath())
                .isRegularFile();
        var yaml = Files.readString(RULES, StandardCharsets.UTF_8);

        int start = yaml.indexOf("eq $labels.scope \"containers\"");
        assertThat(start)
                .withFailMessage(CHECKLIST + """

                        The ConfigDrift summary in %s no longer has an arm for the `containers` scope. \
                        The `else` arm of that branch describes an uninstalled ops artefact, so a \
                        missing arm does not degrade to a vague message -- it hands the reader a \
                        confidently WRONG first move. Restore the arm.
                        """, RULES)
                .isNotNegative();

        var arm = yaml.substring(start, Math.min(yaml.length(), yaml.indexOf("{{ else", start + 1)));
        assertThat(arm)
                .withFailMessage(CHECKLIST + """

                        The `containers` arm of the ConfigDrift summary does not mention `up -d`. \
                        Since HD-221 this scope no longer compares config hashes -- it asks \
                        `docker compose up -d --dry-run` what the deploy command would do, and \
                        publishes 1 when the answer is "act on something", when a declared service \
                        is not running, or when the plan could not be read at all. The sentence an \
                        operator reads must describe the check that actually ran. Arm:

                        %s
                        """, arm)
                .contains("up -d");

        // The case production will actually produce. Two `service_healthy` edges mean any health
        // incident lasting past `for: 30m` raises this alert beside the real one, and the arm's
        // first prescribed move is then read during an outage — so it must not be "run the deploy
        // again". A refusal may only prescribe an action its reader should take.
        assertThat(arm)
                .withFailMessage(CHECKLIST + """

                        The `containers` arm of the ConfigDrift summary prescribes a move without \
                        first telling the reader when NOT to make it. Production declares two \
                        `depends_on: condition: service_healthy` edges, and a dependency that is not \
                        healthy makes Compose abandon the plan and exit non-zero -- so every health \
                        incident outlasting the alert's `for:` raises this scope too, alongside the \
                        real alert. The arm must send the reader to the journal line first and name \
                        the health case, because "run the deploy again" during an outage is the wrong \
                        first move and this alert is the one that suggests it. Arm:

                        %s
                        """, arm)
                .contains("UNHEALTHY");
    }

    // --- harness ---------------------------------------------------------------------

    private static void expect(List<String> failures, String what, boolean ok, Result r) {
        if (!ok) {
            failures.add("\n  - " + what + "\n    (exit " + r.exit() + ") " + r.output().strip());
        }
    }

    private record Result(int exit, String output) {}

    private record Stubbed(Path box, Path dockerLog, Result result) {}

    private void assumeBash() {
        Assumptions.assumeTrue(bash != null,
                "no bash on PATH (and no Git for Windows bash.exe) — the tests that drive the real "
                        + "ops/drift/hamstrack-config-drift.sh run on CI and on any POSIX machine, and "
                        + "skip only on a Windows box without Git Bash");
        assertThat(SCRIPT)
                .withFailMessage("ops/drift/hamstrack-config-drift.sh was not found at %s — this test "
                        + "drives the repository's own copy, so it must run from the module root",
                        SCRIPT.toAbsolutePath())
                .isRegularFile();
    }

    /** Runs the real drift script against {@code box} and returns its combined output. */
    private Result runDrift(Path box, String project, Map<String, String> extraEnv, String... pathPrefix)
            throws Exception {
        var pb = new ProcessBuilder(bash, posix(SCRIPT), posix(box)).redirectErrorStream(true);
        var env = new LinkedHashMap<>(pb.environment());
        if (pathPrefix.length > 0) {
            env.put("PATH", String.join(File.pathSeparator, pathPrefix) + File.pathSeparator
                    + env.getOrDefault("PATH", ""));
        }
        env.put("COMPOSE_PROJECT_NAME", project);
        env.putIfAbsent("COMPOSE_FILES", "docker-compose.yml");
        env.put("CONFIG_DRIFT_TEXTFILE_DIR", posix(box.resolve("textfile")));
        // Scopes 1, 3 and 4 are somebody else's tests. Pointing the install directories at an
        // empty scratch path keeps this run from reading the real /etc/systemd/system.
        env.put("CONFIG_DRIFT_UNIT_DIR", posix(box.resolve("units")));
        env.put("CONFIG_DRIFT_BIN_DIR", posix(box.resolve("bin")));
        env.putAll(extraEnv);
        pb.environment().clear();
        pb.environment().putAll(env);

        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(180, TimeUnit.SECONDS))
                .withFailMessage("hamstrack-config-drift.sh did not finish within 180s — output so far:\n%s", out)
                .isTrue();
        return new Result(p.exitValue(), out);
    }

    /** The published answer, which is the thing the alert actually reads. */
    private int containersGauge(Path box) throws IOException {
        var prom = box.resolve("textfile/hamstrack_config.prom");
        assertThat(prom)
                .withFailMessage("the script published no %s — a check that cannot publish must not be "
                        + "read as a clean one", prom)
                .isRegularFile();
        for (String line : Files.readAllLines(prom, StandardCharsets.UTF_8)) {
            if (line.startsWith("hamstrack_config_drift{scope=\"containers\"}")) {
                return Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1).strip());
            }
        }
        throw new AssertionError("no hamstrack_config_drift{scope=\"containers\"} series in " + prom);
    }

    private Stubbed runStubbed(String name, Map<String, String> stubEnv) throws Exception {
        return runStubbed(name, stubEnv, "docker-compose.yml");
    }

    /**
     * A scratch box whose {@code docker} is a stub. The stub answers the three read-only
     * invocations the script makes and is steered entirely by {@code STUB_*} variables, so
     * each case above describes one way the oracle can behave rather than one way to write a
     * fake.
     */
    private Stubbed runStubbed(String name, Map<String, String> stubEnv, String composeFiles) throws Exception {
        var box = work.resolve("stub-" + name);
        var bin = box.resolve("stubbin");
        Files.createDirectories(bin);
        write(box.resolve("docker-compose.yml"), "services: {}\n");
        var dockerLog = box.resolve("docker.log");

        // `cid-<service>` and `/<service>` keep the container-name mapping legible in a
        // failure message: a plan line naming `alpha` is the container of service `alpha`.
        writeStub(bin.resolve("docker"), """
                #!/usr/bin/env bash
                printf '%s\\n' "$*" >> "$DOCKER_LOG"
                if [ "$1" = inspect ]; then
                  cid="${!#}"
                  printf '/%s\\n' "${cid#cid-}"
                  exit 0
                fi
                last="${!#}"
                case " $* " in
                  *" config --services "*)
                    if [ "${STUB_CONFIG_FAIL:-0}" = 1 ]; then
                      echo "stub: cannot resolve" >&2
                      exit 1
                    fi
                    printf '%s\\n' ${STUB_SERVICES-alpha beta}
                    exit 0
                    ;;
                  *" ps -q "*)
                    if [ "$last" = "${STUB_PS_EMPTY:-}" ]; then
                      exit 0
                    fi
                    printf 'cid-%s\\n' "$last"
                    exit 0
                    ;;
                  *" up -d --dry-run "*)
                    if [ -n "${STUB_PLAN_STDOUT:-}" ]; then printf '%s\\n' "$STUB_PLAN_STDOUT"; fi
                    if [ -n "${STUB_PLAN:-}" ]; then printf '%s\\n' "$STUB_PLAN" >&2; fi
                    exit "${STUB_PLAN_RC:-0}"
                    ;;
                esac
                exit 0
                """);
        assertStubsAreExecutable(bin);

        var env = new LinkedHashMap<String, String>();
        env.put("DOCKER_LOG", posix(dockerLog));
        env.put("COMPOSE_FILES", composeFiles);
        env.putAll(stubEnv);
        var result = runDrift(box, "stub-" + name, env, bin.toAbsolutePath().toString());
        return new Stubbed(box, dockerLog, result);
    }

    // --- real-docker helpers ---------------------------------------------------------

    /** The first candidate image already on this machine, or null if docker cannot be used. */
    private static String firstLocallyPresentImage() {
        for (String image : IMAGE_CANDIDATES) {
            try {
                var p = new ProcessBuilder("docker", "image", "inspect", image)
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return image;
                }
            } catch (IOException | InterruptedException e) {
                return null;   // no docker binary, or no daemon: this test has nothing to say here
            }
        }
        return null;
    }

    private Result docker(Path cwd, String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("docker");
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(180, TimeUnit.SECONDS))
                .withFailMessage("docker %s did not finish within 180s — output so far:\n%s",
                        String.join(" ", args), out)
                .isTrue();
        return new Result(p.exitValue(), out);
    }

    private String containerId(Path box, String project, String service) throws Exception {
        return docker(box, "compose", "-p", project, "-f", "docker-compose.yml", "ps", "-q", service)
                .output().strip();
    }

    // --- file helpers, same rules as ApplyConfigPinGuardTest --------------------------

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        // LF, always: a shebang line ending in CR makes bash report `bash\r: not found`.
        Files.writeString(p, content.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }

    private static String read(Path p) throws IOException {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    /**
     * Writes a file the script is meant to RUN. Every such file needs the execute bit on any
     * filesystem that has one — a shell SKIPS a non-executable file during PATH lookup and
     * keeps searching, so the name would resolve to the machine's real {@code docker} and this
     * class would point a stub-driven case at a live daemon. {@link #assertStubsAreExecutable}
     * is what makes forgetting it loud; the lesson is ApplyConfigPinGuardTest's, paid once.
     */
    private static void writeStub(Path p, String content) throws IOException {
        write(p, content);
        if (!supportsPosixPermissions(p)) {
            return;
        }
        var perms = new HashSet<>(Files.getPosixFilePermissions(p));
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(p, perms);
    }

    private static void assertStubsAreExecutable(Path bin) throws IOException {
        if (!supportsPosixPermissions(bin)) {
            return;
        }
        try (var entries = Files.list(bin)) {
            for (Path stub : entries.toList()) {
                assertThat(Files.isExecutable(stub))
                        .withFailMessage("""

                                THIS IS A STUB PROBLEM, NOT A SCRIPT FAILURE. %s is on the PATH this test \
                                hands to ops/drift/hamstrack-config-drift.sh and it is not executable on \
                                this filesystem (mode %s).

                                A shell SKIPS a non-executable file during PATH lookup and keeps searching, \
                                so `docker` would resolve to this machine's REAL docker -- pointed at a \
                                scratch directory that is not a Compose project, on a case whose whole \
                                point is that the daemon is not involved. Write PATH files with \
                                writeStub(...), never with write(...) or Files.writeString, which leave \
                                mode 644.""",
                                stub.getFileName(), Files.getPosixFilePermissions(stub))
                        .isTrue();
            }
        }
    }

    private static boolean supportsPosixPermissions(Path p) throws IOException {
        return Files.getFileStore(p).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    /** Forward slashes: Git Bash accepts {@code C:/x/y}, and a backslash is an escape. */
    private static String posix(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String findBash() {
        var name = System.getProperty("os.name").toLowerCase().contains("win") ? "bash.exe" : "bash";
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
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
