package com.hamstrack.ops;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.hamstrack.ops.ScriptHarness.Result;
import static com.hamstrack.ops.ScriptHarness.expect;
import static com.hamstrack.ops.ScriptHarness.posix;
import static com.hamstrack.ops.ScriptHarness.write;
import static com.hamstrack.ops.ScriptHarness.writeStub;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-299 — the verify step of {@code ops/deploy/apply-config.sh} against a REAL daemon:
 * a real {@code docker update} is seen by check 1 and a real added key by check 2, and the
 * real drift script's file passes check 5.</strong>
 *
 * <p>{@link ApplyConfigVerifyPhaseTest} drives every branch with a stubbed {@code docker}; what
 * a stub cannot establish is that the daemon really reports {@code HostConfig.Memory} the way
 * the script reads it, that Compose's resolved model really carries {@code mem_limit} in the
 * form the reader parses (bytes as a string on v5.1.0 — the reader also takes units), that a
 * {@code docker update} really survives the deploy's own {@code up -d} (measured: same
 * container id, planned {@code Running} — which is why HD-189 was invisible to both), and that
 * the real {@code hamstrack-config-drift.sh}, synced and run at step 9, publishes a file check
 * 5 reads as fresh. So this class runs the WHOLE applier — sync, stamp, pull ({@code
 * pull_policy: never}, so it never touches a registry), up, drift, verify — on a scratch
 * Compose project of its own, then sabotages the running containers and re-reads them through
 * {@code --verify-only}.
 *
 * <p>Same discipline as {@link ConfigDriftContainerOracleTest}: its own project name in its own
 * scratch directory, torn down with {@code down -v} in a finally, never a compose command
 * without an explicit project; never pulls.
 *
 * <p><strong>Every assumption is taken before the subject runs.</strong> This class skips when
 * there is no bash, no usable daemon, none of the candidate images is present locally, or the
 * daemon cannot change a running container's memory limit — and all four are settled at the top,
 * from the environment or from a throwaway probe container. None is ever taken from a value the
 * applier produced: an {@code assumeTrue} in the middle of a test turns a broken subject into
 * {@code Skipped: 1 / BUILD SUCCESS}, which is what this class did until HD-299's fix loop
 * (measured with a bogus flag planted on {@code up -d}: the class reported a skip while six
 * expectations had already been violated and the terminal assertion was never reached).
 */
class DeployVerifyOracleTest {

    private static final Path SCRIPT = Path.of("ops/deploy/apply-config.sh");
    private static final Path DRIFT_DIR = Path.of("ops/drift");

    private static final String CHECKLIST = """

            ops/deploy/apply-config.sh step 10 reads the RUNNING box back. This class is the half of
            its seal that needs a real daemon:

              * memory-limits IS ORACLE-INDEPENDENT AND IT IS HD-189. `docker update --memory` on a
                running container is invisible to `up -d` (same container id, planned `Running`)
                and therefore to the containers drift scope; only reading HostConfig.Memory back
                against the resolved mem_limit sees it. If this goes green with the container
                sabotaged, the check is comparing the wrong field or the wrong service.

              * environment-keys NAMES THE KEY AND NEVER THE VALUE. The resolved model holds every
                secret .env folds in; the log is public. A value in the output is a disclosure.

              * drift-fresh READS THE REAL FILE. Step 9 runs the real drift script here, against a
                real box; check 5 must find its timestamp at or after T9.

              * --verify-only NEVER MUTATES: every container id must be the same after every re-read.

              * NO ASSUMPTION IS TAKEN FROM THE SUBJECT'S OWN OUTPUT. Every skip this class can make
                is decided at the top, from the environment or from a throwaway probe container. An
                assumeTrue reading a value the applier produced makes a broken applier report
                `Skipped: 1 / BUILD SUCCESS` — which is exactly what it did before HD-299's fix loop.
            """;

    /**
     * Any image that can sleep. The first is the one CI is made to hold — <strong>made</strong>,
     * by an explicit {@code docker pull} step in {@code .github/workflows/build.yml}, not
     * inherited from the {@code postgres:16-alpine} service container that happens to be beside
     * the job today. "Inherited by construction" is the sentence that held for five slices and
     * failed silently the moment somebody moved the thing it was inherited from; here it would
     * have failed by turning this whole class into a green skip.
     * {@link #ciCannotLoseTheRealDaemonAssertionToASkip()} holds both ends of that.
     */
    private static final List<String> IMAGE_CANDIDATES =
            List.of("postgres:16-alpine", "alpine:3", "busybox", "debian:12-slim");

    /** The CI workflow that must arrange the daemon this class needs, read rather than assumed. */
    private static final Path BUILD_WORKFLOW = Path.of(".github/workflows/build.yml");

    private static String bash;

    @TempDir
    Path work;

    @BeforeAll
    static void locateBash() {
        bash = ScriptHarness.findBash();
    }

    /**
     * <strong>Under CI this class may not skip.</strong> Same shape as
     * {@code AgentChecklistFreshnessTest#ciCannotLoseTheHistoryAssertionToASkip}, deliberately
     * rather than a second mechanism: a companion test that takes no assumption of its own, reads
     * the same conditions the skipping test reads, and turns each of them into a refusal when the
     * environment says it is CI.
     *
     * <p>Why it is needed at all is item 1's defect one level up. HD-299's acceptance criterion is
     * "verified through the workflow run, not a hand run on the box"; a class that silently skips
     * in CI verifies nothing there, and a skip and a pass are the same green. The three
     * assumptions the test takes are honest on a developer's machine — a Windows box without Git
     * Bash, a machine with no docker daemon, a kernel that will not re-limit a running container —
     * and each of them is a lost gate on a runner, where all three are arrangeable.
     *
     * <p>The last assertion runs EVERYWHERE, not only under CI: it is what stops the workflow and
     * this class from drifting apart on a developer's machine, where the CI branch never executes.
     */
    @Test
    void ciCannotLoseTheRealDaemonAssertionToASkip() throws IOException {
        // ScriptHarness.underCi(), not a second copy of the same two getenv calls: its sibling's
        // javadoc already says the two classes SHARE this definition, and they did not — this one
        // restated it inline, so "shared" was a claim about a copy.
        if (ScriptHarness.underCi()) {
            var image = firstLocallyPresentImage();
            var missing = new ArrayList<String>();
            if (bash == null) {
                missing.add("no bash on PATH");
            }
            if (image == null) {
                missing.add("no docker daemon, or none of " + IMAGE_CANDIDATES + " present locally");
            } else if (!daemonCanChangeARunningMemoryLimit(image)) {
                missing.add("the daemon refused `docker update --memory` on a throwaway container");
            }
            assertThat(missing)
                    .withFailMessage("%s", """
                            [deploy-verify-oracle] running under CI with a daemon this class cannot use, so the \
                            real-daemon half of HD-299 would be SKIPPED — and a skipped gate in CI is not a gate. \
                            This is the ticket's own acceptance criterion ("verified through the workflow run"), so \
                            it may not be satisfied by a green skip.

                            The job that runs this suite must arrange all three, before `./mvnw -B verify`:
                              - run: docker pull postgres:16-alpine   # DeployVerifyOracleTest brings up a real box
                            (bash and cgroup-v2 memory re-limiting are already true of ubuntu-latest.)

                            Unusable because: """ + missing)
                    .isEmpty();
        }
        // …and the workflow really carries that step, checked on every machine — otherwise the
        // branch above is code that only ever runs where nobody reads its failure.
        //
        // PARSED, not grepped. A `docker pull` inside a COMMENT satisfies a raw-text search
        // perfectly, so the previous version of this assertion was cleared by the very edit that
        // would break it — comment the step out and the guard still passes. OpsYaml walks
        // jobs.build-and-test.steps and reads each step's `run:`, so only a step that actually
        // runs counts, and step ORDER is the list's order rather than a byte offset.
        var job = OpsYaml.map(OpsYaml.map(OpsYaml.parse(BUILD_WORKFLOW).get("jobs")).get("build-and-test"));
        var steps = OpsYaml.list(job.get("steps"));
        int pull = -1;
        int verify = -1;
        for (int i = 0; i < steps.size(); i++) {
            String runScript = String.valueOf(OpsYaml.map(steps.get(i)).get("run"));
            for (String candidate : IMAGE_CANDIDATES) {
                if (runScript.contains("docker pull " + candidate) && pull < 0) {
                    pull = i;
                }
            }
            if (runScript.contains("./mvnw -B verify") && verify < 0) {
                verify = i;
            }
        }
        // A FLOOR SET AT TODAY'S POPULATION ONLY EVER FIRES ON A DELETION. Measured
        // 2026-09-10: jobs.build-and-test.steps has FOUR steps (checkout, JDK, the image pull,
        // the verify run), and the floor was also 4 — so removing any one of them tripped this
        // assertion and blamed the YAML parse, while the two indices below are what actually
        // describe the loss. The bound is what a BLIND parse looks like (0, 1, 2 steps from a
        // structure the reader cannot follow), which is a different quantity from the number of
        // steps this job happens to have.
        assertThat(steps.size())
                .withFailMessage("[deploy-verify-oracle] jobs.build-and-test.steps in %s has %d step(s) — the "
                        + "workflow did not parse the way this assertion expects, so it is asserting nothing. "
                        + "There were 4 when this floor was set; a step DELETED is reported by the two indices "
                        + "below, not here.",
                        BUILD_WORKFLOW, steps.size())
                .isGreaterThanOrEqualTo(3);
        assertThat(pull >= 0 && verify > pull)
                .withFailMessage("%s", """
                        [deploy-verify-oracle] .github/workflows/build.yml no longer pulls one of \
                        """ + IMAGE_CANDIDATES + """
                         before `./mvnw -B verify`.

                        Without that step this class skips on the runner and the skip is green, which is the \
                        exact failure the CI branch above exists to refuse — and the branch cannot refuse it, \
                        because it only runs there. The service container beside the job is NOT the guarantee: \
                        it is a side effect of a `services:` block that may be renamed, re-versioned or dropped \
                        without anybody connecting it to this test.

                        Add it back, before the verify step:
                          - run: docker image inspect postgres:16-alpine >/dev/null 2>&1 || docker pull postgres:16-alpine

                        (pull step index %d, `./mvnw -B verify` step index %d; -1 means no step RUNS it — a
                        commented-out line does not count, which is the whole reason this reads the parsed
                        steps rather than the file's text)""".formatted(pull, verify))
                .isTrue();
    }

    @Test
    void aMatchingBoxVerifiesAndARealSabotageOfEitherReadBackIsRedNamingItsService() throws Exception {
        ScriptHarness.assumeWithWitness("deploy-verify-oracle",
                bash != null,
                "no bash on PATH (and no Git for Windows bash.exe) — this test drives the real "
                        + "ops/deploy/apply-config.sh and skips only on a Windows box without Git Bash");
        var image = firstLocallyPresentImage();
        ScriptHarness.assumeWithWitness("deploy-verify-oracle",
                image != null,
                "no usable docker daemon, or none of " + IMAGE_CANDIDATES + " is present locally. This "
                        + "test brings up a scratch Compose project to read real containers back, and it "
                        + "deliberately does not pull. `docker pull postgres:16-alpine` enables it.");
        // EVERY ASSUMPTION THIS CLASS MAKES IS MADE HERE, BEFORE THE FIRST FINDING IS COLLECTED.
        // The sabotage below needs a daemon that can change a RUNNING container's memory limit,
        // and that is a capability of the machine — knowable from a throwaway container, where a
        // skip costs nothing. It used to be asked in the middle of the test, from the exit code
        // of `docker update` on the deploy's own container, which is a CONSEQUENCE OF THE CODE
        // UNDER TEST: break the applier, no container starts, `alpha` is blank, `docker update`
        // fails, and the abort turned a broken deploy into `Skipped: 1 / BUILD SUCCESS` with six
        // expectations already violated and the terminal assertion never reached (measured
        // 2026-09-10 with a bogus flag planted on `up -d`). An assumption may only ever be about
        // the environment, and it may only be taken before the subject has run.
        ScriptHarness.assumeWithWitness("deploy-verify-oracle",
                daemonCanChangeARunningMemoryLimit(image),
                "this docker daemon would not accept `docker update --memory` on a running throwaway "
                        + "container, so HD-189's sabotage cannot be planted on this machine at all "
                        + "(cgroup v1 without swap accounting is the usual reason).");

        var project = "hd299verify" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var root = work.resolve(project);
        var box = root.resolve("box");
        var release = root.resolve("release");
        var bin = root.resolve("bin");
        var textfile = root.resolve("textfile");
        Files.createDirectories(box);
        Files.createDirectories(bin);

        // Two services so every claim is about a set. alpha carries the environment (one literal
        // key, one folded from .env with a value that must never appear in the output).
        write(release.resolve("docker-compose.yml"), """
                services:
                  alpha:
                    image: %s
                    pull_policy: never
                    entrypoint: ["sleep"]
                    command: ["600"]
                    mem_limit: ${ALPHA_MEM:-64m}
                    environment:
                      HD299_KNOB: "one"
                      HD299_SECRET: ${HD299_SECRET:-}
                  beta:
                    image: %s
                    pull_policy: never
                    entrypoint: ["sleep"]
                    command: ["600"]
                    mem_limit: 96m
                """.formatted(image, image));
        write(release.resolve("ops/deploy/synced-paths.txt"), "docker-compose.yml\nops/\n");
        copyTree(DRIFT_DIR, release.resolve("ops/drift"));   // the REAL drift script, synced and run at step 9
        write(box.resolve(".env"), "HD299_SECRET=hunter2-never-printed\n");
        writeStub(bin.resolve("flock"), "#!/usr/bin/env bash\nexit 0\n");   // Git Bash has none; each run owns its box

        var failures = new ArrayList<String>();
        try {
            // --- 1. the deploy, end to end, verifies --------------------------------------
            var deploy = apply(box, bin, textfile, project, posix(release), "hd299sha");
            expect(failures, "the full deploy exits 0", deploy.exit() == 0, deploy);
            expect(failures, "…and passes every check it could read", deploy.output().contains("verify: PARTIAL ran=3/5")
                    && deploy.output().contains("verify: memory-limits ok (2 services)")
                    && deploy.output().contains("verify: environment-keys ok (1 services)")
                    && deploy.output().contains("verify: drift-fresh ok files=0 containers=0 age="), deploy);
            // The public summary, against a REAL box: this project declares neither `app` nor
            // `grafana`, so two checks are skipped — and the line says which, IN THE VERDICT WORD as
            // well as in the count. It used to read `PASS 5/5 ran=3`: the declared count over itself,
            // printed by every run that reached the line, so it said the same thing about a box
            // that read three of five as about one that read all five.
            // `app-identity=none` because this scratch project declares no `app` service at all,
            // so check 3 was skipped rather than run at one of its three strengths. The field is
            // on the line precisely so that a reader can tell those apart.
            // Every field pinned in order EXCEPT the withheld VALUE: step 7 now marks and counts
            // Compose's own stderr on the success path too, and a real `up -d` against a real
            // daemon writes as many progress lines as it writes. `withheld=` still has to be
            // there and still has to carry a number.
            expect(failures, "…and the summary counts what was really read",
                    deploy.output().contains("verify: PARTIAL ran=3/5 skipped=app-identity,grafana "
                            + "services=2 env-services=1 app-identity=none withheld="), deploy);
            expect(failures, "…and the withheld tally is a number, on the same line, before the sha",
                    Pattern.compile("withheld=\\d+ sha=hd299sha").matcher(deploy.output()).find(), deploy);
            expect(failures, "…and the gauge agrees", gauge(textfile, "hamstrack_deploy_verify_checks_ran") == 3, deploy);
            expect(failures, "…and publishes 1", gauge(textfile, "hamstrack_deploy_verify_ok") == 1, deploy);
            expect(failures, "the pre-flight ran before the change and saw no containers yet",
                    deploy.output().contains("pre-flight: ceiling alpha running=none"), deploy);
            expect(failures, "the secret value never reached the output", !deploy.output().contains("hunter2"), deploy);
            var alpha = containerId(box, project, "alpha");
            var beta = containerId(box, project, "beta");
            // SELF-DIAGNOSIS FOR THE NEXT OCCURRENCE. A reviewer's run of this class produced
            // five "declared and has no running container" findings and a refusal saying
            // production might be half-updated, and there was nothing in the output to say
            // whether the containers had died, been OOM-killed, or simply never been asked
            // about (the applier's own fix for the last of those is the SERVICE_CONTAINERS_RC
            // branch). One docker inspect per service, printed only when a finding of that
            // shape is present, makes the next one answerable from its own log.
            if (failures.stream().anyMatch(x -> x.contains("no running container"))
                    || alpha.isBlank() || beta.isBlank()) {
                for (String svc : List.of("alpha", "beta")) {
                    var state = docker(box, "compose", "-p", project, "-f", "docker-compose.yml", "ps", "-aq", svc);
                    for (String id : state.output().strip().split("\\R")) {
                        if (!id.isBlank()) {
                            var s = docker(box, "inspect", "--format",
                                    "{{.State.Status}} {{.State.ExitCode}} {{.State.OOMKilled}}", id);
                            failures.add("\n  [diagnosis] " + svc + " " + id.substring(0, Math.min(12, id.length()))
                                    + " status/exit/oom = " + s.output().strip());
                        }
                    }
                }
            }
            // A HARD PRE-CONDITION, COLLECTED RATHER THAN ASSUMED. Everything below reads these
            // two containers, so a blank id is this test's own bedrock failing — and the whole
            // point of the fix loop that produced this line is that it must READ AS RED. The
            // block is guarded rather than run against an empty id, so the report is one clear
            // finding instead of a cascade of docker errors about a container named "".
            expect(failures, "the scratch project really started (alpha and beta both have ids)",
                    !alpha.isBlank() && !beta.isBlank(), deploy);
            if (!alpha.isBlank() && !beta.isBlank()) {

            // --- 2. HD-189: a real docker update, then a re-read -------------------------
            var update = docker(box, "update", "--memory", "128m", "--memory-swap", "-1", alpha);
            // The daemon's capability was established before any of this ran, so a failure HERE
            // is about this container — the sabotage did not take, and a re-read that then finds
            // nothing wrong proves nothing at all. A finding, never a skip.
            expect(failures, "the daemon accepted the sabotage (docker update --memory on alpha)",
                    update.exit() == 0, update);
            var red = apply(box, bin, textfile, project, posix(box), null, "--verify-only");
            expect(failures, "a changed ceiling is a red re-read", red.exit() != 0, red);
            expect(failures, "…naming the service and both numbers", red.output().contains(
                    "verify: memory-limits FAILED: alpha runs with HostConfig.Memory=134217728 while the resolved configuration declares 67108864 bytes"), red);
            expect(failures, "…and not the service whose ceiling holds", !red.output().contains("memory-limits FAILED: beta"), red);
            expect(failures, "…with the gauge at 0 for that check and overall",
                    checkGauge(textfile, "memory-limits") == 0 && gauge(textfile, "hamstrack_deploy_verify_ok") == 0, red);
            expect(failures, "…while the checks that hold read 1", checkGauge(textfile, "environment-keys") == 1
                    && checkGauge(textfile, "drift-fresh") == 1, red);
            // THE READ-ONLY REFUSAL, against a real box. This re-read is `--verify-only`, so the
            // deploy paragraph would be false in every clause — nothing was placed, stamped,
            // pulled or brought up here, there is no backup directory from "this run" and no
            // "image running before this run" to pin. It used to be served that paragraph
            // verbatim, telling a reader who had performed a READ that production might be
            // half-updated. The remedies it may name are the two this reader can perform.
            expect(failures, "…and the refusal is the READ-ONLY one, against a real box",
                    red.output().contains("This was a --verify-only run")
                            && red.output().contains("NOTHING was placed, stamped, pulled or brought up")
                            && red.output().contains("fix the box"), red);
            expect(failures, "…and does not tell a reader who applied nothing that production may be half-updated",
                    !red.output().contains("Production may be half-updated")
                            && !red.output().contains("IS APPLIED and stamped"), red);

            docker(box, "update", "--memory", "64m", "--memory-swap", "-1", alpha);
            var green = apply(box, bin, textfile, project, posix(box), null, "--verify-only");
            expect(failures, "restoring the ceiling makes the re-read green", green.exit() == 0
                    && green.output().contains("verify: PARTIAL ran=3/5"), green);
            expect(failures, "…and the gauge back at 1", gauge(textfile, "hamstrack_deploy_verify_ok") == 1, green);

            // --- 3. a key declared on disk that the running container never received ----
            var compose = box.resolve("docker-compose.yml");
            var original = Files.readString(compose, StandardCharsets.UTF_8);
            Files.writeString(compose, original.replace("      HD299_KNOB: \"one\"\n",
                    "      HD299_KNOB: \"one\"\n      HD299_NEW_KEY: \"new-value-never-printed\"\n"), StandardCharsets.UTF_8);
            var missing = apply(box, bin, textfile, project, posix(box), null, "--verify-only");
            expect(failures, "a declared key absent from the running container is red", missing.exit() != 0, missing);
            expect(failures, "…naming the service and the key", missing.output().contains(
                    "verify: environment-keys FAILED: alpha: declared key HD299_NEW_KEY is absent"), missing);
            expect(failures, "…and the drift scopes the edit also moved are named too (files and containers)",
                    missing.output().contains("hamstrack_config_drift{scope=\"files\"} reads 1")
                            && missing.output().contains("hamstrack_config_drift{scope=\"containers\"} reads 1"), missing);
            expect(failures, "…and no value, declared or running, reached the output",
                    !missing.output().contains("never-printed") && !missing.output().contains("hunter2"), missing);
            Files.writeString(compose, original, StandardCharsets.UTF_8);
            var restored = apply(box, bin, textfile, project, posix(box), null, "--verify-only");
            expect(failures, "restoring the file makes the re-read green", restored.exit() == 0, restored);

            // --- 4. none of the re-reads touched a container --------------------------------
            expect(failures, "--verify-only never recreated alpha", alpha.equals(containerId(box, project, "alpha")), restored);
            expect(failures, "--verify-only never recreated beta", beta.equals(containerId(box, project, "beta")), restored);
            }
        } finally {
            docker(box, "compose", "-p", project, "-f", "docker-compose.yml", "down", "-v", "-t", "1");
        }
        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- harness ---------------------------------------------------------------------

    private Result apply(Path box, Path bin, Path textfile, String project, String source, String sha, String... flags)
            throws Exception {
        ScriptHarness.assertStubsAreExecutable(bin);
        var cmd = new ArrayList<String>();
        cmd.add(bash);
        cmd.add(posix(SCRIPT));
        cmd.add(source);
        cmd.add(posix(box));
        if (sha != null) {
            cmd.add(sha);
        }
        cmd.addAll(List.of(flags));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        var env = new LinkedHashMap<>(pb.environment());
        env.put("PATH", bin.toAbsolutePath() + File.pathSeparator + env.getOrDefault("PATH", ""));
        env.put("COMPOSE_FILES", "docker-compose.yml");
        env.put("COMPOSE_PROJECT_NAME", project);
        env.put("CONFIG_DRIFT_TEXTFILE_DIR", posix(textfile));
        // The drift script's other scopes read the real /etc/systemd/system otherwise.
        env.put("CONFIG_DRIFT_UNIT_DIR", posix(box.resolve("units")));
        env.put("CONFIG_DRIFT_BIN_DIR", posix(box.resolve("ubin")));
        env.put("VERIFY_POLL_SECONDS", "1");
        env.put("VERIFY_APP_TIMEOUT_SECONDS", "3");
        env.put("VERIFY_GRAFANA_TIMEOUT_SECONDS", "3");
        env.put("VERIFY_GRAFANA_SETTLE_SECONDS", "0");
        env.remove("APP_IMAGE_TAG");
        env.remove("EXPECTED_APP_VERSION");
        pb.environment().clear();
        pb.environment().putAll(env);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(300, TimeUnit.SECONDS))
                .withFailMessage("apply-config.sh did not finish within 300s — output so far:\n%s", out)
                .isTrue();
        return new Result(p.exitValue(), out);
    }

    private Result docker(Path cwd, String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("docker");
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(180, TimeUnit.SECONDS))
                .withFailMessage("docker %s did not finish within 180s — output so far:\n%s", String.join(" ", args), out)
                .isTrue();
        return new Result(p.exitValue(), out);
    }

    private String containerId(Path box, String project, String service) throws Exception {
        return docker(box, "compose", "-p", project, "-f", "docker-compose.yml", "ps", "-q", service).output().strip();
    }

    /**
     * Can THIS DAEMON change a running container's memory limit at all? Asked of a throwaway
     * container of its own, before the subject under test has run, so that the answer is a
     * property of the machine and never of the applier. A skip here costs nothing; a skip taken
     * from the deploy's own containers costs the whole test, silently.
     *
     * <p>Its own name, `docker run` rather than compose, and removed in a finally: it must not be
     * reachable by the scratch project's teardown or leave anything behind if it fails.
     */
    private static boolean daemonCanChangeARunningMemoryLimit(String image) {
        var name = "hd299probe" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try {
            var run = new ProcessBuilder("docker", "run", "-d", "--name", name, "--memory", "64m",
                    "--memory-swap", "-1", "--entrypoint", "sleep", image, "60")
                    .redirectErrorStream(true).start();
            run.getInputStream().readAllBytes();
            if (!run.waitFor(120, TimeUnit.SECONDS) || run.exitValue() != 0) {
                return false;
            }
            try {
                var update = new ProcessBuilder("docker", "update", "--memory", "128m", "--memory-swap", "-1", name)
                        .redirectErrorStream(true).start();
                update.getInputStream().readAllBytes();
                return update.waitFor(120, TimeUnit.SECONDS) && update.exitValue() == 0;
            } finally {
                // -v, and it is not cosmetic: postgres:16-alpine declares VOLUME /var/lib/
                // postgresql/data, so every probe container leaves an ANONYMOUS VOLUME behind
                // without it. This runs on every invocation of this class on every machine and
                // on CI, and nothing ever collects them — a test that grows the disk once per
                // run is a test somebody eventually disables.
                var rm = new ProcessBuilder("docker", "rm", "-f", "-v", name).redirectErrorStream(true).start();
                rm.getInputStream().readAllBytes();
                rm.waitFor(120, TimeUnit.SECONDS);
            }
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** The first candidate image already on this machine, or null if docker cannot be used. */
    private static String firstLocallyPresentImage() {
        for (String image : IMAGE_CANDIDATES) {
            try {
                var p = new ProcessBuilder("docker", "image", "inspect", image).redirectErrorStream(true).start();
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

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                var target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static int gauge(Path textfile, String series) throws IOException {
        for (String line : ScriptHarness.read(textfile.resolve("hamstrack_deploy_verify.prom")).split("\n")) {
            if (line.startsWith(series + " ")) {
                return Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1).strip());
            }
        }
        return -1;
    }

    private static int checkGauge(Path textfile, String check) throws IOException {
        return gauge(textfile, "hamstrack_deploy_verify_check_ok{check=\"" + check + "\"}");
    }
}
