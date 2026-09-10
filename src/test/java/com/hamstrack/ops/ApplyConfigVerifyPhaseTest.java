package com.hamstrack.ops;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.hamstrack.ops.ScriptHarness.Result;
import static com.hamstrack.ops.ScriptHarness.posix;
import static com.hamstrack.ops.ScriptHarness.write;
import static com.hamstrack.ops.ScriptHarness.writeStub;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-299 — step 10 of {@code ops/deploy/apply-config.sh} reads the running box back,
 * refuses on any disagreement, rolls nothing back, and publishes what it found.</strong>
 *
 * <p>Every step before it reports what it <em>did</em>; four retro incidents (HD-199 stale
 * compose for six weeks, HD-221 a drift oracle red for a week, HD-283 a Grafana crash-loop
 * behind a green {@code up -d}, HD-287 a drift unit that never published) were each visible
 * to a read-back nobody performed. This class drives the real script with a stubbed
 * {@code docker} and {@code curl} on {@code PATH} through every way each check can be wrong,
 * one at a time, and through the pessimistic gauge a killed deploy must leave behind. The
 * checks that need a real daemon — a real {@code docker update} against a real container, a
 * real drift script publishing a real file — are {@link DeployVerifyOracleTest}'s.
 *
 * <p><strong>What is faked and what is not.</strong> {@code docker} answers the read-only
 * invocations the script makes from {@code STUB_*} variables (its canned {@code compose config}
 * is the resolved-model shape measured on Compose v5.1.0: {@code mem_limit} as a string of
 * bytes, environment as a map with {@code null} for a pass-through nobody set); {@code curl}
 * answers Grafana's health; the drift script synced into the box is a fake that writes the
 * {@code .prom} fixture a case asks for. Everything else is the script itself: the model
 * reader, the byte parser, the polls, the gauge, the refusal, the {@code --verify-only} mode.
 * The Grafana log line the sabotage case feeds in is the one measured on grafana:11.5.2 with
 * a 41-character uid (2026-09-10), not an invented shape.
 *
 * <p>Same platform rules as {@link ApplyConfigPinGuardTest}: skips only where there is no
 * bash at all; every stub goes through {@link ScriptHarness#writeStub}.
 */
class ApplyConfigVerifyPhaseTest {

    private static final Path SCRIPT = Path.of("ops/deploy/apply-config.sh");
    private static final Path DRIFT = Path.of("ops/drift/hamstrack-config-drift.sh");
    private static final Path RULES = Path.of("observability/grafana/provisioning/alerting/rules.yml");
    private static final Path WORKFLOW = Path.of(".github/workflows/deploy.yml");

    /** The failure message is the propagation checklist, so whoever trips it knows what moves. */
    private static final String CHECKLIST = """

            ops/deploy/apply-config.sh step 10 (verify) is the only place a deploy reads the RUNNING
            box back, and every rule below was paid for by an incident that a read-back would have
            shown:

              * FIVE CHECKS, EACH OVER COMPOSE'S OWN SERVICE LIST, never a literal one: memory-limits
                (HostConfig.Memory == the resolved mem_limit -- HD-189, and `docker update` survives
                `up -d` with the same container id, so the containers drift scope cannot see it),
                environment-keys (declared KEYS present; values never printed -- the log is public),
                app-identity (image revision label == sha unless pinned or sha unknown; /api/meta
                from INSIDE the container, polled, never `dev`; == EXPECTED_APP_VERSION on a tag
                deploy), grafana (only where the compose set declares it; health, RestartCount held
                still, no level=error from logger=provisioning since StartedAt -- HD-283, which
                `up -d` exits 0 through), drift-fresh (the FILE step 9 wrote, never Prometheus,
                newer than T9 or step 9 did not publish -- HD-287; files/containers 0; sha equal).

              * A RED VERIFY ROLLS NOTHING BACK, by standing owner decision. The refusal is at most
                25 lines and names ONLY actions its reader can perform: fix forward (push, re-run
                the idempotent deploy, or --verify-only after a hand fix) or return by hand (the
                backup directory this run took and the previous image, pinnable as sha-<7>).

              * THE GAUGE IS PESSIMISTIC. hamstrack_deploy_verify.prom is written with every check
                0 at the FIRST MUTATION and rewritten by verify, so a deploy killed between the two
                fires DeployVerifyFailed instead of inheriting last week's 1. A check that does not
                apply on this box (no grafana service) publishes 1: nothing to page about.

              * --verify-only READS. It re-runs steps 9 and 10 against an already-applied box and
                must invoke no pull, no up, no restart -- it is how an operator clears
                DeployVerifyFailed after a hand fix, and a mode that mutates is a second deploy.

              * PRE-FLIGHT BEFORE THE MUTATION, NEVER FATAL: the plan `up -d` would execute against
                the release and each running ceiling beside its declared one, printed by --dry-run
                too -- HD-199's read-back moved AHEAD of the change.

              * THE WORKFLOW WAITS LONGER THAN VERIFY CAN TAKE. `aws ssm wait command-executed` is
                20 x 5 s = 100 s (botocore's waiter model); with verify's own budgets the first
                verified deploy would have been red for being slow and the phase muted on day one.

            What else moves with a change here: ops/drift/hamstrack-config-drift.sh (whatever
            functions both scripts define are carried byte for byte -- the set is DERIVED by
            everyFunctionBothScriptsCarryIsCarriedVerbatim, deliberately not counted here, because
            a count goes stale one entry before the list does and this one already had),
            observability/grafana/provisioning/alerting/rules.yml
            (DeployVerifyFailed reads the gauge, per check), docs/ops-prod-hardening.md section 3,
            docs/release-checklist.md ("Verify, don't assume"), docs/observability.md (rule table),
            .github/workflows/deploy.yml (the poll and EXPECTED_APP_VERSION), and
            DeployVerifyOracleTest (the real-daemon half).
            """;

    /** The resolved model as Compose v5.1.0 prints it (measured 2026-09-10): bytes as a string. */
    private static final String CONFIG = """
            name: hamstrack
            services:
              app:
                environment:
                  DB_URL: jdbc:postgresql://postgres:5432/hamstrack
                  OPTIONAL_PASSTHRU: null
                  SPRING_PROFILES_ACTIVE: cloud
                healthcheck:
                  test:
                    - CMD
                    - wget
                image: ghcr.io/x/hamstrack:latest
                mem_limit: "1073741824"
                networks:
                  default: null
              grafana:
                environment:
                  GF_SECURITY_ADMIN_PASSWORD: test-only-value-never-printed
                image: grafana/grafana:11.5.2
                mem_limit: "1073741824"
                ports:
                  - mode: ingress
                    target: 3000
            networks:
              default:
                name: hamstrack_default
            """;

    private static final String PLAN = String.join("\n",
            " Container hamstrack-grafana-1 Running",
            " Container hamstrack-app-1 Recreate",
            " Container hamstrack-app-1 Recreated",
            "");

    /** Measured on grafana:11.5.2 with a 41-character uid, 2026-09-10 (HD-283's trigger). */
    private static final String GRAFANA_UID_ERROR =
            "logger=provisioning t=2026-09-10T08:40:23.072559573Z level=error msg=\"Failed to provision alerting\" "
                    + "error=\"alert rules: invalid alert rule\\ncannot create rule with UID "
                    + "'hamstrack-config-check-stale-abcdefghijkl': UID is longer than 40 symbols\"";

    private static final String GRAFANA_SANE_LOGS = String.join("\n",
            "logger=provisioning.alerting t=2026-09-10T08:39:45.996260668Z level=info msg=\"starting to provision alerting\"",
            "logger=provisioning.alerting t=2026-09-10T08:39:46.532906718Z level=info msg=\"finished to provision alerting\"",
            "logger=ngalert.scheduler rule_uid=hamstrack-app-down t=2026-09-10T08:39:54Z level=error msg=\"Failed to evaluate rule\"");

    private static String bash;

    @TempDir
    Path work;

    @BeforeAll
    static void locateBash() {
        bash = ScriptHarness.findBash();
    }

    // --- the pass, and what it publishes ------------------------------------------------

    @Test
    void aBoxThatMatchesWhatWasAppliedPassesAllFiveAndPublishesOnes() throws Exception {
        var failures = new ArrayList<String>();
        var d = deployment("pass");
        var r = run(d, Map.of());
        expect(failures, "a matching box exits 0", r.exit() == 0, r);
        for (String line : List.of(
                "verify: memory-limits ok (2 services)",
                "verify: environment-keys ok (2 services)",
                "verify: app-identity ok (full) revision=testsha version=0.17.0-12-gtestsha",
                "verify: grafana ok restarts=0 provisioning-errors=0 since 2026-09-10T08:00:00.000000000Z",
                "verify: drift-fresh ok files=0 containers=0 age=",
                "verify: PASS ran=5/5 skipped=none",
                "deploy complete:")) {
            expect(failures, "the log carries `" + line + "`", r.output().contains(line), r);
        }
        expect(failures, "the gauge reads 1 overall", gauge(d, "hamstrack_deploy_verify_ok") == 1, r);
        for (String check : List.of("memory-limits", "environment-keys", "app-identity", "grafana", "drift-fresh")) {
            expect(failures, "check_ok{check=\"" + check + "\"} reads 1", checkGauge(d, check) == 1, r);
        }
        expect(failures, "the gauge names the sha", prom(d).contains("hamstrack_deploy_verify_info{sha=\"testsha\"} 1"), r);
        // A pass-through key Compose resolved to null is not demanded: Compose does not hand it
        // to the container, so demanding it would red every box for a variable nobody set.
        expect(failures, "OPTIONAL_PASSTHRU (null in the model) is not demanded", !r.output().contains("OPTIONAL_PASSTHRU"), r);
        // The secret VALUES in the resolved model never reach the log.
        expect(failures, "no environment value reaches the log", !r.output().contains("never-printed"), r);
        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    @Test
    void environmentKeysAreDemandedByNameAndNeverByValue() throws Exception {
        var failures = new ArrayList<String>();
        var d = deployment("envkeys");
        var r = run(d, Map.of("STUB_ENV_LINES", "GF_SECURITY_ADMIN_PASSWORD=whatever\nPATH=/bin"));
        expect(failures, "a missing declared key refuses", r.exit() != 0, r);
        expect(failures, "…naming the service and the key",
                r.output().contains("verify: environment-keys FAILED: app: declared key DB_URL is absent"), r);
        expect(failures, "…and the sibling key too (every miss, not the first)",
                r.output().contains("app: declared key SPRING_PROFILES_ACTIVE is absent"), r);
        expect(failures, "…while the service whose keys are present is not named",
                !r.output().contains("environment-keys FAILED: grafana"), r);
        expect(failures, "no value from the model or the container reaches the log",
                !r.output().contains("never-printed") && !r.output().contains("whatever"), r);
        expect(failures, "check_ok{environment-keys} reads 0", checkGauge(d, "environment-keys") == 0, r);
        expect(failures, "the other checks still ran and read 1", checkGauge(d, "memory-limits") == 1 && checkGauge(d, "grafana") == 1, r);
        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- check 3: app-identity ------------------------------------------------------------

    @Test
    void appIdentityRefusesEachWayItCanBeWrongAndWaitsForAStartingApp() throws Exception {
        var failures = new ArrayList<String>();

        var wrongRevision = run(deployment("rev"), Map.of("STUB_REVISION", "0123456789abcdef0123456789abcdef01234567"));
        expect(failures, "a revision label that is not the sha refuses", wrongRevision.exit() != 0, wrongRevision);
        expect(failures, "…naming both", wrongRevision.output().contains(
                "app-identity FAILED: the running app image was built from 0123456789abcdef0123456789abcdef01234567, not from testsha"), wrongRevision);

        var noLabel = run(deployment("nolabel"), Map.of("STUB_REVISION", ""));
        expect(failures, "an image with no revision label refuses", noLabel.exit() != 0
                && noLabel.output().contains("carries no org.opencontainers.image.revision label"), noLabel);

        var dev = run(deployment("dev"), Map.of("STUB_META", "{\"publicLandingEnabled\":true,\"version\":\"dev\"}"));
        expect(failures, "/api/meta reporting dev refuses", dev.exit() != 0
                && dev.output().contains("app-identity FAILED: /api/meta reports version 'dev'"), dev);

        var slow = run(deployment("slow"), Map.of("STUB_META_NOT_YET", "3", "VERIFY_APP_TIMEOUT_SECONDS", "20"));
        expect(failures, "an app that answers only on the 4th poll passes", slow.exit() == 0
                && slow.output().contains("verify: app-identity ok"), slow);
        expect(failures, "…and was really asked four times",
                countOf(slow.dockerCalls(), "exec -T app wget") == 4, slow);

        var never = run(deployment("never"), Map.of("STUB_META_NOT_YET", "999", "VERIFY_APP_TIMEOUT_SECONDS", "1"));
        expect(failures, "an app that never answers inside the budget refuses", never.exit() != 0
                && never.output().contains("/api/meta did not answer with a JSON body inside 1s"), never);

        var tagMismatch = run(deployment("tagmismatch"), Map.of("EXPECTED_APP_VERSION", "0.18.0"));
        expect(failures, "a release deploy whose /api/meta is not the tag's version refuses", tagMismatch.exit() != 0
                && tagMismatch.output().contains("reports version 0.17.0-12-gtestsha while the deployed ref expects 0.18.0"), tagMismatch);

        var tagMatch = run(deployment("tagmatch"), Map.of("EXPECTED_APP_VERSION", "0.18.0",
                "STUB_META", "{\"version\":\"0.18.0\"}"));
        expect(failures, "a release deploy whose /api/meta is the tag's version passes", tagMatch.exit() == 0, tagMatch);

        var taggedTip = run(deployment("taggedtip"), Map.of("STUB_META", "{\"version\":\"0.18.0\"}"));
        expect(failures, "a main deploy of a tagged tip (bare version, label already proved the sha) passes",
                taggedTip.exit() == 0, taggedTip);

        var otherSha = run(deployment("othersha"), Map.of("STUB_META", "{\"version\":\"0.17.0-9-gdeadbee\"}"));
        expect(failures, "a main deploy whose version names another commit refuses", otherSha.exit() != 0
                && otherSha.output().contains("names neither gtestsha nor a bare release version"), otherSha);

        // Pinned by policy: a newer tree beside an older image on purpose, so the revision is
        // not compared — but /api/meta must still answer with a stamped version, and the pin
        // must have TAKEN EFFECT. "Not built from this sha" is the half a pin excuses; "which
        // image is running" is the half it makes the whole point, and it is the shape an
        // operator reaches for under pressure, so it may not be the one shape that asserts
        // nothing about the image.
        var pinned = deployment("pinned", "APP_IMAGE_TAG=0.17.0\n", "0.17.0");
        var pinnedRun = run(pinned, Map.of("STUB_REVISION", "someolderrevision", "STUB_META", "{\"version\":\"0.17.0\"}"));
        expect(failures, "a pinned box does not compare the revision", pinnedRun.exit() == 0
                && pinnedRun.output().contains("revision=unchecked(APP_IMAGE_TAG=0.17.0 pins the image"), pinnedRun);
        expect(failures, "…but does name the image it verified the pin against",
                pinnedRun.output().contains("image=ghcr.io/x/hamstrack:0.17.0"), pinnedRun);

        var pinIgnored = deployment("pin-ignored", "APP_IMAGE_TAG=0.17.0\n", "0.17.0");
        var pinIgnoredRun = run(pinIgnored, Map.of("STUB_REVISION", "someolderrevision",
                "STUB_META", "{\"version\":\"0.17.0\"}", "STUB_IMAGE", "ghcr.io/x/hamstrack:latest"));
        expect(failures, "a pin that did not take effect is RED — the box is not running the chosen version",
                pinIgnoredRun.exit() != 0 && pinIgnoredRun.output().contains(
                        "app-identity FAILED: APP_IMAGE_TAG pins the image to 0.17.0 and the running app "
                                + "container was created from 'ghcr.io/x/hamstrack:latest'"), pinIgnoredRun);
        expect(failures, "…naming a command that re-creates it from the pinned tag",
                pinIgnoredRun.output().contains("docker compose -f docker-compose.prod.yml up -d"), pinIgnoredRun);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- check 4: grafana ------------------------------------------------------------------

    @Test
    void grafanaRefusesOnEachOfItsThreeReadingsAloneAndSkipsWhereNotDeclared() throws Exception {
        var failures = new ArrayList<String>();

        var unhealthy = run(deployment("unhealthy"), Map.of("STUB_HEALTH", "{\"database\": \"failing\"}"));
        expect(failures, "health not ok refuses", unhealthy.exit() != 0
                && unhealthy.output().contains("verify: grafana FAILED: http://127.0.0.1:3000/api/health did not report database ok"), unhealthy);

        var restarting = run(deployment("restarting"), Map.of("STUB_RESTARTS", "3 4"));
        expect(failures, "a RestartCount that moved across the settle window refuses", restarting.exit() != 0
                && restarting.output().contains("did not stay up across a 0s settle window")
                && restarting.output().contains("RestartCount 3 -> 4"), restarting);
        // The budget's own VARIABLE, not only its number: on a small VPS the remedy for this
        // refusal is to widen the window, and a refusal may only prescribe an action its reader
        // can perform. The same clause is on the app and grafana timeouts.
        expect(failures, "…naming the knob that widens the window", restarting.output()
                .contains("VERIFY_GRAFANA_SETTLE_SECONDS"), restarting);

        var provisioning = run(deployment("provisioning"), Map.of("STUB_GRAFANA_LOGS", GRAFANA_SANE_LOGS + "\n" + GRAFANA_UID_ERROR));
        expect(failures, "a provisioning error since StartedAt refuses", provisioning.exit() != 0, provisioning);
        // THE STRUCTURED PREFIX, NOT THE LINE. `cut -c1-300` was a LENGTH bound standing in for a
        // CONTENT bound: whatever Grafana's `error=` field happened to carry — a datasource URL
        // with an inline credential, a secret it was handed — went straight into a world-readable
        // Actions log. logger= and msg= are Grafana's own literals; error= is where the foreign
        // text lives, so it is dropped and the reader is told where to read it. The cost is real
        // and accepted: the uid that caused HD-283 is no longer inline, and the message says so.
        expect(failures, "…naming the fault by its structured fields",
                provisioning.output().contains("grafana logged 1 provisioning error line(s) since it started")
                        && provisioning.output().contains("logger=provisioning msg=\"Failed to provision alerting\""),
                provisioning);
        expect(failures, "…while the error= field, which quotes whatever Grafana was handed, is WITHHELD",
                !provisioning.output().contains("cannot create rule with UID"), provisioning);
        expect(failures, "…and the reader is told where the whole line is",
                provisioning.output().contains("logs grafana"), provisioning);
        expect(failures, "…and check_ok{grafana} reads 0 while the others read 1",
                checkGauge(provisioning.deployment(), "grafana") == 0 && checkGauge(provisioning.deployment(), "app-identity") == 1, provisioning);
        // A REFUSAL MAY ONLY PRESCRIBE AN ACTION ITS READER CAN PERFORM. This window is StartedAt
        // to now and step 7b restarts Grafana only when observability/ CHANGED, so "re-run the
        // deploy (it is idempotent)" leaves the same error inside the same window for ever. The
        // finding therefore carries its own command, and it is one the reader can run on the box.
        expect(failures, "…and names an action that actually clears it: restart grafana, then re-read",
                provisioning.output().contains("Re-running this deploy does NOT clear it")
                        && provisioning.output().contains("restart grafana")
                        && provisioning.output().contains("--verify-only"), provisioning);

        // FAIL-OPEN, CLOSED. `docker logs … || true` on an unreadable log yields an empty string,
        // which greps to zero provisioning errors and passes the check HD-283 exists for. A
        // healthy Grafana always logs its start banner, so an empty window is "not read".
        var unreadableLog = run(deployment("nogrologs"), Map.of("STUB_GRAFANA_LOGS", ""));
        expect(failures, "a log that cannot be read is RED, not green", unreadableLog.exit() != 0
                && unreadableLog.output().contains("verify: grafana FAILED: could not read grafana's log"), unreadableLog);
        expect(failures, "…and says how to read it by hand", unreadableLog.output().contains("logs grafana"), unreadableLog);

        var down = run(deployment("down"), Map.of("STUB_DOWN", "grafana"));
        expect(failures, "a declared grafana with no running container refuses", down.exit() != 0
                && down.output().contains("grafana is declared and has no running container"), down);

        // Evaluation-time template errors are a WARN: the rule still fires, only its text is lost.
        var template = run(deployment("template"), Map.of("STUB_GRAFANA_LOGS", GRAFANA_SANE_LOGS
                + "\nlogger=ngalert.state.manager t=2026-09-10T08:40:00Z level=error msg=\"Error in expanding template\""));
        expect(failures, "a template-expansion error is a WARN and still passes", template.exit() == 0
                && template.output().contains("verify: WARN grafana: 1 template-expansion error line(s)"), template);
        // READ ONCE. This is the path that reaches BOTH greps, and it used to run `docker logs`
        // twice over a window that is StartedAt to now — weeks wide on a Grafana nobody has
        // restarted — so the two reads could disagree as well as costing twice.
        expect(failures, "…having read the container's log exactly once (it is one window, not two)",
                countOf(template.dockerCalls(), "logs --since") == 1, template);

        // A box whose compose set declares no grafana: skipped with a line, and the gauge says
        // 1 — there is nothing on that box to page about.
        var without = run(deployment("without"), Map.of("STUB_SERVICES", "app"));
        expect(failures, "a box without a grafana service skips the check", without.exit() == 0
                && without.output().contains("verify: grafana skipped — no grafana service in (docker-compose.prod.yml)"), without);
        // 2, not 1: a check that had nothing to look at on this box must not read the same as one
        // that looked and found nothing wrong. COMPOSE_FILES=docker-compose.prod.yml is a documented
        // invocation, and with both publishing 1 a crash-looping Grafana nobody asked about was
        // indistinguishable from a Grafana that was checked and healthy — HD-283 exactly.
        expect(failures, "…and publishes 2 (not applicable), never 1", checkGauge(without.deployment(), "grafana") == 2, without);
        expect(failures, "…and never called curl", !without.dockerCalls().contains("curl "), without);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- check 5: drift-fresh ---------------------------------------------------------------

    @Test
    void driftFreshRefusesAStaleGaugeADriftedScopeAndAForeignShaAndWarnsOnHandSteps() throws Exception {
        var failures = new ArrayList<String>();

        var stale = run(deployment("stale"), Map.of("FAKE_DRIFT_TS", "1000000000"));
        expect(failures, "a gauge older than T9 refuses (HD-287)", stale.exit() != 0
                && stale.output().contains("verify: drift-fresh FAILED: the drift check did not publish — hamstrack_config_check_timestamp_seconds is 1000000000"), stale);

        var files = run(deployment("files"), Map.of("FAKE_FILES", "1"));
        expect(failures, "files=1 refuses", files.exit() != 0
                && files.output().contains("hamstrack_config_drift{scope=\"files\"} reads 1"), files);

        var containers = run(deployment("containers"), Map.of("FAKE_CONTAINERS", "1"));
        expect(failures, "containers=1 refuses", containers.exit() != 0
                && containers.output().contains("hamstrack_config_drift{scope=\"containers\"} reads 1"), containers);

        var installed = run(deployment("installed"), Map.of("FAKE_INSTALLED", "1", "FAKE_EDGE", "1"));
        expect(failures, "installed-ops=1 and edge-body-limit=1 pass with a WARN naming the hand step", installed.exit() == 0
                && installed.output().contains("verify: WARN drift-fresh: installed-ops=1")
                && installed.output().contains("docs/release-checklist.md")
                && installed.output().contains("verify: WARN drift-fresh: edge-body-limit=1")
                && installed.output().contains("docs/ops-prod-hardening.md §2"), installed);

        var sha = run(deployment("sha"), Map.of("FAKE_SHA", "othersha"));
        expect(failures, "a deployed_info sha that is not this run's refuses", sha.exit() != 0
                && sha.output().contains("hamstrack_config_deployed_info names sha othersha while this run stamped testsha"), sha);

        var silent = run(deployment("silent"), Map.of("FAKE_SKIP_WRITE", "1"));
        expect(failures, "a drift script that wrote nothing on a box with no earlier file refuses", silent.exit() != 0
                && silent.output().contains("hamstrack_config.prom is absent — step 9 published nothing"), silent);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- the gauge's pessimism and the refusal's shape --------------------------------------

    @Test
    void aDeployKilledAfterItsFirstMutationLeavesZerosBehindAndADryRunWritesNothing() throws Exception {
        var failures = new ArrayList<String>();

        var killed = deployment("killed");
        var r = run(killed, Map.of("STUB_PULL_EXIT", "1"));
        expect(failures, "a failing pull is a red deploy", r.exit() != 0, r);
        expect(failures, "…that never reached verify", !r.output().contains("verify: reading"), r);
        expect(failures, "…and left the pessimistic gauge behind, all zeros",
                Files.exists(killed.promPath()) && gauge(killed, "hamstrack_deploy_verify_ok") == 0
                        && checkGauge(killed, "memory-limits") == 0 && checkGauge(killed, "drift-fresh") == 0, r);
        expect(failures, "…stamped with this run's sha", prom(killed).contains("hamstrack_deploy_verify_info{sha=\"testsha\"} 1"), r);

        var dry = deployment("dry");
        var d = run(dry, Map.of(), "--dry-run");
        expect(failures, "a dry run exits 0", d.exit() == 0, d);
        expect(failures, "a dry run writes no gauge (it mutates nothing, so it may not claim to be a deploy in flight)",
                !Files.exists(dry.promPath()), d);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    @Test
    void theRefusalIsAtMost25LinesAndNamesOnlyActionsItsReaderCanPerform() throws Exception {
        var failures = new ArrayList<String>();
        var d = deployment("refusal");
        var r = run(d, Map.of("STUB_REVISION", "otherrevisionabcdef", "FAKE_FILES", "1"));
        expect(failures, "two findings refuse", r.exit() != 0, r);
        int at = r.output().indexOf("VERIFY FAILED");
        expect(failures, "the refusal block exists", at >= 0, r);
        var block = at >= 0 ? r.output().substring(at) : "";
        long lines = block.lines().filter(l -> !l.isBlank()).count();
        expect(failures, "the block is at most 25 lines (was " + lines + ")", lines > 0 && lines <= 25, r);
        for (String must : List.of(
                "2 finding(s)",
                "app-identity:", "drift-fresh:",
                "nothing is rolled back",
                "fix forward",
                "re-run this deploy (it is idempotent",
                "--verify-only",
                ".config-backup/",                 // this run replaced paths, so a backup exists and is named
                "APP_IMAGE_TAG=",
                "docker compose -f docker-compose.prod.yml up -d",
                "DeployVerifyFailed stays firing")) {
            expect(failures, "the refusal names `" + must + "`", block.contains(must), r);
        }
        // A PATH under the target, never a bare `apply-config.sh`: ops/ is synced to the box and
        // never installed, so the bare form answers `command not found` for the reader of a red
        // deploy. (The target is spelled back the way the box's shell resolved it, which on Git
        // Bash is /c/Users/… where the caller passed C:/Users/… — hence the suffix match.)
        expect(failures, "…and reaches the applier through a path",
                Pattern.compile("bash \\S*/ops/deploy/apply-config\\.sh \\S+ \\S+ \\S+ --verify-only")
                        .matcher(block).find(), r);
        expect(failures, "the gauge was rewritten BEFORE the refusal, per check",
                checkGauge(d, "app-identity") == 0 && checkGauge(d, "drift-fresh") == 0 && checkGauge(d, "grafana") == 1, r);

        // …AND THE SAME REFUSAL FOR A READER WHO APPLIED NOTHING. --verify-only places nothing,
        // stamps nothing, pulls nothing and brings nothing up, so the deploy paragraph is false
        // in every clause — and "production may be half-updated" is the sentence that sends
        // somebody hunting for a rollback after a READ. It also cannot honestly offer this run's
        // backup directory or "the image running before this run": this run had no before.
        var ro = runFrom(d, Map.of("STUB_REVISION", "otherrevisionabcdef"), posix(d.box()), "--verify-only");
        expect(failures, "a verify-only run refuses too", ro.exit() != 0, ro);
        int roAt = ro.output().indexOf("VERIFY FAILED");
        var roBlock = roAt >= 0 ? ro.output().substring(roAt) : "";
        expect(failures, "the verify-only refusal exists", roAt >= 0, ro);
        long roLines = roBlock.lines().filter(l -> !l.isBlank()).count();
        expect(failures, "…and is at most 25 lines (was " + roLines + ")", roLines > 0 && roLines <= 25, ro);
        for (String must : List.of(
                "This was a --verify-only run",
                "NOTHING was placed, stamped, pulled or brought up",
                "describe the box AS IT ALREADY WAS",
                "DeployVerifyFailed stays firing")) {
            expect(failures, "the verify-only refusal names `" + must + "`", roBlock.contains(must), ro);
        }
        for (String mustNot : List.of(
                "IS APPLIED and stamped",
                "the containers WERE brought up",
                "Production may be half-updated",
                ".config-backup/")) {
            expect(failures, "…and does NOT claim `" + mustNot + "`", !roBlock.contains(mustNot), ro);
        }
        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * <strong>Three checks that used to fail OPEN.</strong> Each answers a question it could not
     * ask and publishes the answer as "nothing wrong here", which is the shape behind five of
     * this project's twelve Critical defects.
     */
    @Test
    void aCheckThatCouldNotBeAskedIsRedAndSaysSoRatherThanReadingGreen() throws Exception {
        var failures = new ArrayList<String>();

        // 1. `docker logs` FAILING, not "logging nothing". Grafana writes its log to stderr, so
        // the read needs 2>&1 — which also folds docker's own error into the text, and one line
        // of "Error response from daemon: …" greps to zero provisioning errors and passes.
        var logBroken = run(deployment("logfail"), Map.of("STUB_LOGS_EXIT", "1",
                "STUB_GRAFANA_LOGS", "Error response from daemon: configured logging driver does not support reading"));
        expect(failures, "a `docker logs` that EXITS NON-ZERO is red even though it printed a line",
                logBroken.exit() != 0 && logBroken.output().contains("verify: grafana FAILED: could not read grafana's log"),
                logBroken);
        expect(failures, "…and the finding names the exit code", logBroken.output().contains("exited 1"), logBroken);

        // 2. A ceiling the box's .env interpolated away. MEASURED on Compose v5.1.0: with
        // APP_MEMORY_LIMIT=0, `mem_limit: ${APP_MEMORY_LIMIT:-1g}` vanishes from the resolved
        // model — the container runs unbounded, this check WARNed and passed, and the test that
        // reads the FILE still saw the interpolation and passed too.
        var unbounded = run(deployment("nolimit"), Map.of("STUB_CONFIG", CONFIG_WITHOUT_APP_MEM_LIMIT));
        expect(failures, "a ceiling in the file text and none in the resolved model is RED, not a WARN",
                unbounded.exit() != 0 && unbounded.output().contains(
                        "verify: memory-limits FAILED: app declares a memory ceiling in the compose file text "
                                + "and the RESOLVED model has none, so the container runs UNBOUNDED"), unbounded);
        expect(failures, "…and says where the 0 that did it lives",
                unbounded.output().contains(".env.prod.example"), unbounded);

        // 3. "Compose could not be asked" vs "nothing is running". One transient daemon hiccup
        // used to become a finding per service plus a refusal about production being
        // half-updated, because every per-service check reads through the same helper.
        var unanswerable = run(deployment("psfail"), Map.of("STUB_PS_EXIT", "1", "VERIFY_POLL_SECONDS", "0"));
        expect(failures, "an unanswerable `compose ps` is a finding about the QUESTION, not about the box",
                unanswerable.exit() != 0 && unanswerable.output().contains(
                        "so this check could not be asked whether anything is running — this is NOT a finding about the box"),
                unanswerable);
        expect(failures, "…retried once before being believed",
                unanswerable.output().contains("retrying once in 0s"), unanswerable);
        expect(failures, "…and never says the service simply has no running container",
                !unanswerable.output().contains("has no running container whose ceiling could be read"), unanswerable);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /** The resolved model with app's mem_limit interpolated away — Compose's own shape for `=0`. */
    private static final String CONFIG_WITHOUT_APP_MEM_LIMIT =
            CONFIG.replace("    mem_limit: \"1073741824\"\n    networks:\n", "    networks:\n");

    /**
     * <strong>The self-hoster's box — the population {@code docs/self-hosting.md} now points at
     * {@code --verify-only} for.</strong> It has never been applied by this script and its image
     * was built locally, and both of those used to be hard refusals: {@code files:
     * .deployed-manifest.sha256 is absent} because the drift scopes compare against a stamp that
     * does not exist, and {@code /api/meta reports version '0.0.0-DEV'} because that is
     * {@code Dockerfile}'s own {@code ARG APP_VERSION} default. Neither is a finding about the
     * box; both are refusals whose only remedy is "adopt our release pipeline".
     */
    @Test
    void aBoxThisScriptNeverAppliedCanStillBeReadWithVerifyOnly() throws Exception {
        var failures = new ArrayList<String>();
        var d = deployment("selfhosted");
        // A box set up by hand: the compose file and .env are there, no deploy ever stamped it.
        Files.copy(d.src().resolve("docker-compose.prod.yml"), d.box().resolve("docker-compose.prod.yml"));
        expect(failures, "the fixture really is unstamped",
                !Files.exists(d.box().resolve(".deployed-manifest.sha256")), new Run(0, "", "", "", d));

        var r = runFrom(d, Map.of("STUB_META", "{\"version\":\"0.0.0-DEV\"}"), posix(d.box()), "--verify-only");
        expect(failures, "a read of an unstamped, locally built box PASSES", r.exit() == 0, r);
        expect(failures, "…skipping drift-fresh, because there is no stamp to compare against",
                r.output().contains("verify: drift-fresh skipped — ")
                        && r.output().contains("has no .deployed-manifest.sha256"), r);
        expect(failures, "…and saying so in the summary rather than implying it was checked",
                r.stdout().contains("skipped=drift-fresh"), r);
        expect(failures, "…and publishing 2 (not applicable) for it, never 1",
                checkGauge(d, "drift-fresh") == 2, r);
        expect(failures, "…while the locally built version is a WARN naming the build arg",
                r.output().contains("verify: WARN app-identity: /api/meta reports the unstamped default version")
                        && r.output().contains("--build-arg APP_VERSION="), r);
        expect(failures, "…and the summary says how weak this verification was",
                r.stdout().contains("app-identity=sha-unknown"), r);

        // The same unstamped version IS a refusal when a deploy placed configuration for a sha:
        // that build came from a pipeline that stamps, so an unstamped version means the tag did
        // not carry what it claims.
        var withSha = run(deployment("selfhosted-sha"), Map.of("STUB_META", "{\"version\":\"0.0.0-DEV\"}"));
        expect(failures, "a PIPELINE deploy still refuses an unstamped version", withSha.exit() != 0
                && withSha.output().contains("an image with no stamped version is not a release build"), withSha);

        // …and no version at all stays fatal in both modes: that is a broken endpoint, not a
        // build choice.
        // An EMPTY version, not a missing key: a body with no `"version"` at all is "not yet" to
        // the poll (that is how a 502 and a starting app are told from a mismatch), so the empty
        // string is the shape that reaches the parse.
        var noVersion = runFrom(deployment("selfhosted-none"), Map.of("STUB_META", "{\"version\":\"\"}"),
                posix(d.box()), "--verify-only");
        expect(failures, "an empty /api/meta version is fatal even for a self-hoster",
                noVersion.exit() != 0
                        && noVersion.output().contains("/api/meta answered without a version field"), noVersion);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * <strong>The six poll knobs are validated BEFORE the mutation, and named in their own
     * refusal.</strong> They are process environment ({@code sudo -E}), never {@code .env} —
     * which this script hands to Compose and never sources, so a knob written there is read by
     * nobody. A bad value used to die inside the verify step with a raw bash message naming the
     * VALUE and not the variable, after the files were placed and the containers recreated,
     * leaving the pessimistic gauge at 0 and DeployVerifyFailed firing over a typo.
     */
    @Test
    void aBadVerifyKnobIsRefusedByNameBeforeAnythingOnTheBoxChanges() throws Exception {
        var failures = new ArrayList<String>();
        for (var bad : List.of(
                Map.entry("VERIFY_APP_TIMEOUT_SECONDS", "abc"),
                Map.entry("VERIFY_GRAFANA_SETTLE_SECONDS", "-1"),
                Map.entry("VERIFY_POLL_SECONDS", "99999"),
                // NOT the empty string: `${VAR:-90}` treats empty as unset, so "" legitimately
                // means "use the default" and refusing it would break `VERIFY_X= sudo -E …`.
                Map.entry("VERIFY_GRAFANA_TIMEOUT_SECONDS", "12.5"))) {
            var d = deployment("knob-" + bad.getKey().toLowerCase(java.util.Locale.ROOT) + bad.getValue().length());
            var r = run(d, Map.of(bad.getKey(), bad.getValue()));
            expect(failures, bad.getKey() + "='" + bad.getValue() + "' is refused", r.exit() != 0, r);
            expect(failures, "…by NAME, not by value", r.output().contains(bad.getKey()), r);
            expect(failures, "…saying it is process environment and not .env",
                    r.output().contains("sudo -E"), r);
            expect(failures, "…and before the mutation: nothing placed, no gauge",
                    !Files.exists(d.box().resolve("docker-compose.prod.yml")) && !Files.exists(d.promPath()), r);
        }
        // GRAFANA_HEALTH_URL IS MATCHED ON ITS AUTHORITY, and every member of the population is
        // RUN rather than reasoned about. The prefix glob it replaced (`http://localhost:*`)
        // reads as a loopback test and is not one: the userinfo form puts any host after an `@`,
        // so `http://localhost:3000@evil.example.com/leak` and the IMDS address behind
        // `http://127.0.0.1:80@169.254.169.254/…` both passed it — measured, and curl really
        // resolves the host after the `@`. The refusal said "on the loopback" and nothing held
        // the claim. Both directions are here: a refused URL that should be accepted is a
        // self-hoster who cannot verify Grafana at all.
        var offLoopback = List.of(
                "http://example.com/api/health",
                "http://localhost:3000@evil.example.com/leak",
                "http://127.0.0.1:80@169.254.169.254/latest/meta-data/iam/security-credentials/",
                "http://user@localhost:3000/api/health",
                "http://127.0.0.1.evil.example.com/api/health",
                "http://localhost:3000.evil.example.com/api/health",
                "ftp://127.0.0.1:3000/api/health");
        assertThat(offLoopback.size())
                .withFailMessage("Only %d off-loopback URL(s) in this population — it has stopped covering "
                        + "the forms the review found", offLoopback.size())
                .isGreaterThanOrEqualTo(6);
        Run bad = null;
        for (String url : offLoopback) {
            var d = deployment("knob-url" + Math.abs(url.hashCode()));
            bad = run(d, Map.of("GRAFANA_HEALTH_URL", url));
            expect(failures, "GRAFANA_HEALTH_URL='" + url + "' is refused", bad.exit() != 0
                    && bad.output().contains("GRAFANA_HEALTH_URL must be an http(s) URL whose AUTHORITY"), bad);
            expect(failures, "…before the mutation", !Files.exists(d.promPath()), bad);
            expect(failures, "…and the URL is not asked", !bad.dockerCalls().contains("evil.example.com")
                    && !bad.dockerCalls().contains("169.254.169.254"), bad);
        }
        for (String url : List.of("http://127.0.0.1:3000/api/health", "http://localhost:3000/api/health",
                "https://127.0.0.1:3000/api/health", "http://localhost/api/health")) {
            var ok = run(deployment("knob-ok" + Math.abs(url.hashCode())), Map.of("GRAFANA_HEALTH_URL", url));
            expect(failures, "…while the loopback form '" + url + "' still runs", ok.exit() == 0, ok);
        }

        // THE MIRROR, RUN RATHER THAN GREPPED FOR. This used to assert that one printf line
        // appeared in the script — a text scan that a refactor breaks and a behaviour change
        // does not. The harness keeps stdout and stderr in separate files, which is exactly the
        // question: under SSM (a pipe) a refusal must reach BOTH, because deploy.yml reads both
        // channels and the stdout budget is the one that truncates first.
        //
        // …and every LINE of it carries the stamp on both, which is what log()/log_both()'s
        // shared loop now guarantees by construction. The terminal half — that the mirror does
        // NOT fire on a tty — is NOT tested here: it needs a pty this harness has no way to
        // allocate, and the honest statement is that it is untested rather than a scan that
        // pretends otherwise.
        var refused = run(deployment("mirror"), Map.of("STUB_REVISION", "someotherrevision"));
        expect(failures, "a refusal exits non-zero", refused.exit() != 0, refused);
        var onStdout = refused.stdout().lines().filter(l -> l.contains("VERIFY FAILED")).count();
        var onStderr = refused.stderr().lines().filter(l -> l.contains("VERIFY FAILED")).count();
        expect(failures, "the refusal reaches stdout", onStdout == 1, refused);
        expect(failures, "…and is mirrored onto stderr, because stdout here is a pipe", onStderr == 1, refused);
        var tail = refused.stderr().lines()
                .dropWhile(l -> !l.contains("VERIFY FAILED"))
                .filter(l -> !l.isBlank())
                .toList();
        expect(failures, "the refusal on stderr is more than one line", tail.size() > 3, refused);
        expect(failures, "…and EVERY one of its " + tail.size() + " lines carries the stamp, so the "
                        + "allow-list keeps the whole message and not only its first line",
                tail.stream().allMatch(l -> l.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z .*")), refused);
        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- pre-flight -------------------------------------------------------------------------

    @Test
    void preflightPrintsThePlanAndTheCeilingsBeforeAnythingChangesAndNeverRefuses() throws Exception {
        var failures = new ArrayList<String>();

        var d = deployment("preflight");
        var r = run(d, Map.of(), "--dry-run");
        expect(failures, "a dry run exits 0", r.exit() == 0, r);
        expect(failures, "the plan names the container up -d would act on, with its verbs",
                r.output().contains("pre-flight: up -d would act on hamstrack-app-1 (Recreate, Recreated)"), r);
        expect(failures, "…and counts", r.output().contains("pre-flight: plan — 1 of 2 container(s) would be acted on"), r);
        expect(failures, "…and does not name the container planned Running",
                !r.output().contains("would act on hamstrack-grafana-1"), r);
        expect(failures, "each service's running ceiling is paired with the declared one",
                r.output().contains("pre-flight: ceiling app running=1073741824 declared=1073741824")
                        && r.output().contains("pre-flight: ceiling grafana running=1073741824 declared=1073741824"), r);
        expect(failures, "the pre-flight is printed BEFORE the dry-run diff",
                r.output().indexOf("pre-flight:") < r.output().indexOf("DRY RUN"), r);
        expect(failures, "the previous app revision is remembered for the refusal",
                r.output().contains("pre-flight: the app image running now was built from testsha"), r);
        expect(failures, "nothing was written to the box",
                !Files.exists(d.box().resolve("docker-compose.prod.yml")) && !Files.exists(d.promPath()), r);

        var differs = run(deployment("differs"), Map.of("STUB_MEMORY", "0"), "--dry-run");
        expect(failures, "HD-189's shape is flagged as (differs), never as a refusal", differs.exit() == 0
                && differs.output().contains("pre-flight: ceiling app running=0 declared=1073741824 (differs)"), differs);

        var broken = run(deployment("brokenplan"), Map.of("STUB_PLAN_EXIT", "1", "STUB_PLAN", "dependency failed to start"), "--dry-run");
        expect(failures, "a plan that cannot be read is a WARN and the run goes on", broken.exit() == 0
                && broken.output().contains("WARN pre-flight: 'docker compose up -d --dry-run' against the release exited 1"), broken);

        // The DEPLOY is unaffected by any pre-flight finding: the same broken plan on a real run.
        var deployed = run(deployment("brokenplan-deploy"), Map.of("STUB_PLAN_EXIT", "1"));
        expect(failures, "…and a real deploy proceeds past it to verify and passes", deployed.exit() == 0
                && deployed.output().contains("verify: PASS ran=5/5 skipped=none"), deployed);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- --verify-only ------------------------------------------------------------------------

    @Test
    void verifyOnlyRereadsAnAppliedBoxAndInvokesNothingThatMutates() throws Exception {
        var failures = new ArrayList<String>();
        var d = deployment("verifyonly");
        var first = run(d, Map.of());
        expect(failures, "the deploy that applies the box passes", first.exit() == 0, first);
        Files.writeString(d.dockerLog(), "", StandardCharsets.UTF_8);

        // Source == target, which a deploy refuses and this mode allows: the synced tree on the
        // box carries the manifest.
        var again = runFrom(d, Map.of(), posix(d.box()), "--verify-only");
        expect(failures, "verify-only exits 0 on the box the deploy just verified", again.exit() == 0, again);
        expect(failures, "…and says what it is", again.output().contains("verify-only: steps 9 and 10")
                && again.output().contains("verify complete:"), again);
        expect(failures, "…and defaults the sha to the stamp", again.output().contains("verify complete: ") && again.output().contains(" is at testsha"), again);
        for (String mutating : List.of(" pull", " up -d --remove-orphans", " restart ", " image prune")) {
            expect(failures, "verify-only never invoked `docker …" + mutating + "`",
                    !again.dockerCalls().contains(mutating), again);
        }
        expect(failures, "…nor placed or stamped anything (the stamp's mtime is the first run's)",
                !again.output().contains("applied ") && !again.output().contains("stamped "), again);

        var sabotaged = runFrom(d, Map.of("STUB_REVISION", "different"), posix(d.box()), "--verify-only");
        expect(failures, "verify-only refuses the same way a deploy does", sabotaged.exit() != 0
                && sabotaged.output().contains("VERIFY FAILED"), sabotaged);

        // A moved pin does not block a re-read: the reader has just restored the box by hand.
        var moved = deployment("moved", "APP_IMAGE_TAG=0.17.0\n", "latest");
        var movedRun = runFrom(moved, Map.of("STUB_META", "{\"version\":\"0.17.0\"}", "FAKE_SHA", "unknown"), posix(moved.src()), "--verify-only");
        expect(failures, "a moved pin is a WARN for verify-only, not a refusal", movedRun.exit() == 0
                && movedRun.output().contains("a --verify-only run places nothing, so it proceeds"), movedRun);

        var withDry = runFrom(d, Map.of(), posix(d.box()), "--verify-only", "--dry-run");
        expect(failures, "--verify-only with --dry-run is a bad invocation", withDry.exit() != 0
                && withDry.output().contains("--verify-only applies nothing"), withDry);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- the conclusion survives a truncated log ---------------------------------------------

    /**
     * <strong>SSM returns the FIRST 24 000 characters of stdout and the first 8 000 of stderr</strong>
     * (measured 2026-09-10 from the bundled AWS service model, {@code ssm/2014-11-06/service-2.json}:
     * {@code "StandardOutputContent": {"type":"string","max":24000}} / {@code max: 8000}). Everything
     * this script has to say is at the END of stdout, behind a ten-service pull, {@code up -d}, the
     * prune and the drift script — so a verbose middle silently deletes the conclusion, and a
     * truncated log is indistinguishable from a killed deploy.
     *
     * <p>Two mechanisms, both checked here: the noisy middle is quiet at its source, and the lines
     * nobody may lose go to stderr as well.
     */
    @Test
    void theRefusalAndTheSummaryAreMirroredToStderrAndTheNoisyMiddleIsQuiet() throws Exception {
        var failures = new ArrayList<String>();

        var pass = run(deployment("mirror-pass"), Map.of());
        expect(failures, "a green deploy exits 0", pass.exit() == 0, pass);
        expect(failures, "the PASS summary is on stdout", pass.stdout().contains("verify: PASS ran=5/5 skipped=none"), pass);
        expect(failures, "…and MIRRORED to stderr, which is the channel that survives truncation",
                pass.stderr().contains("verify: PASS ran=5/5 skipped=none"), pass);
        expect(failures, "the pull is quiet (its progress is the largest consumer of the stdout budget)",
                pass.dockerCalls().contains("pull --quiet"), pass);

        var refused = run(deployment("mirror-fail"), Map.of("STUB_REVISION", "someotherrevision"));
        expect(failures, "a red deploy exits non-zero", refused.exit() != 0, refused);
        expect(failures, "the refusal is on stdout", refused.stdout().contains("VERIFY FAILED"), refused);
        expect(failures, "…and MIRRORED to stderr", refused.stderr().contains("VERIFY FAILED"), refused);
        expect(failures, "…with the finding, not just the header",
                refused.stderr().contains("app-identity:"), refused);

        // Every `die` mirrors, not only the verify refusal: a refusal before the mutation is just
        // as invisible in a truncated stdout, and it is one message either way.
        var early = runFrom(deployment("mirror-early"), Map.of(), posix(work.resolve("nowhere")));
        expect(failures, "a pre-mutation refusal is on stderr too", early.exit() != 0
                && early.stderr().contains("FATAL"), early);

        var script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        expect(failures, "the image prune's stdout is discarded (dozens of ids, no reader)",
                script.contains("docker image prune -f >/dev/null"), pass);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * <strong>The one line designed to be republished into a world-readable log, and the anchor
     * that selects it.</strong>
     *
     * <p>deploy.yml prints the verify result on success. The tempting implementation — print the
     * lines containing {@code verify:} — would publish {@code verify: WARN drift-fresh:
     * edge-body-limit=1}, which names a live weakness of the production edge. So the applier emits
     * ONE line whose every field is chosen to be public, and the workflow matches it by ANCHOR.
     * Both halves are held here, including the half that matters later: the anchor cannot match a
     * WARN line, so a future WARN cannot drift into the public log.
     */
    @Test
    void thePublicSummaryLineCountsWhatRanAndItsAnchorCannotMatchAWarn() throws Exception {
        var failures = new ArrayList<String>();

        var full = run(deployment("summary-full"), Map.of());
        var summary = lineMatching(full.stdout(), SUMMARY_ANCHOR);
        expect(failures, "a green deploy emits exactly one line matching the workflow's anchor",
                summary != null, full);
        if (summary != null) {
            for (String field : List.of("verify: PASS ran=5/5", "skipped=none", "services=2",
                    "env-services=2", "withheld=0", "sha=testsha", "version=0.17.0-12-gtestsha")) {
                expect(failures, "the summary carries `" + field + "`", summary.contains(field), full);
            }
        }
        expect(failures, "…and the gauge publishes the same ran count",
                gauge(full.deployment(), "hamstrack_deploy_verify_checks_ran") == 5, full);

        // A SKIPPED check publishes check_ok 2 and a PASSED one publishes 1 — different values,
        // both quiet under the alert's `< 1` — and the count of checks that actually READ the box
        // is published beside them. A box with no grafana service and no drift script verifies
        // three things, and says three, in the word as well as in the count: `PASS n/n` used to be
        // the declared count over itself on every run that reached the line, so a box that read
        // three of five announced `PASS 5/5`.
        var narrow = deployment("summary-narrow");
        Files.delete(narrow.box().resolve("ops/drift/hamstrack-config-drift.sh"));
        Files.delete(narrow.src().resolve("ops/drift/hamstrack-config-drift.sh"));
        var thin = run(narrow, Map.of("STUB_SERVICES", "app"));
        var thinSummary = lineMatching(thin.stdout(), SUMMARY_ANCHOR);
        expect(failures, "a box that skips two checks still passes", thin.exit() == 0 && thinSummary != null, thin);
        if (thinSummary != null) {
            expect(failures, "…and says so: PARTIAL ran=3/5 skipped=grafana,drift-fresh",
                    thinSummary.contains("verify: PARTIAL ran=3/5")
                            && thinSummary.contains("skipped=grafana,drift-fresh"), thin);
            expect(failures, "…and never claims a PASS over checks it did not read",
                    !thinSummary.contains("PASS"), thin);
        }
        expect(failures, "…and the gauge agrees (a skip is not a reading)",
                gauge(thin.deployment(), "hamstrack_deploy_verify_checks_ran") == 3, thin);
        expect(failures, "…while a skipped check publishes 2 (not applicable), so ran= and the gauge agree",
                checkGauge(thin.deployment(), "grafana") == 2 && checkGauge(thin.deployment(), "drift-fresh") == 2, thin);

        // The pessimistic write claims nothing was read, because nothing was.
        var killed = deployment("summary-killed");
        var dead = run(killed, Map.of("STUB_PULL_EXIT", "1"));
        expect(failures, "a deploy killed before verify publishes ran=0", dead.exit() != 0
                && gauge(killed, "hamstrack_deploy_verify_checks_ran") == 0, dead);

        // THE HALF THAT MATTERS LATER, twice over. First a real run that really printed a WARN
        // naming a live weakness of the edge…
        var warned = run(deployment("summary-warn"), Map.of("FAKE_INSTALLED", "1", "FAKE_EDGE", "1"));
        expect(failures, "the WARN run still passes", warned.exit() == 0, warned);
        expect(failures, "…and really printed a WARN naming an edge weakness",
                warned.stdout().contains("verify: WARN drift-fresh: edge-body-limit=1"), warned);
        var warnLines = warned.output().lines()
                .filter(l -> !l.contains("verify: PASS ") && !l.contains("verify: PARTIAL "))
                .filter(l -> SUMMARY_ANCHOR.matcher(l).find())
                .toList();
        expect(failures, "…and no line of that run except the summary matches the anchor the public log is "
                + "filtered by (matched: " + warnLines + ")", warnLines.isEmpty(), warned);
        // …then EVERY line the script can print, extracted from its own source, because one run
        // exercises the branches that run and the anchor has to hold for the ones that do not.
        var anchorOffenders = new ArrayList<String>();
        assertAnchorSelectsOnlyTheSummary(SUMMARY_ANCHOR, anchorOffenders);
        expect(failures, "…and so does every log literal in the script: " + anchorOffenders,
                anchorOffenders.isEmpty(), warned);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * The anchor deploy.yml filters the public log by, read FROM deploy.yml rather than repeated
     * here — a copy of it in this file would be the one thing that could drift from the filter it
     * is supposed to be testing.
     */
    private static final Pattern SUMMARY_ANCHOR = workflowSummaryAnchor();

    private static Pattern workflowSummaryAnchor() {
        String yaml;
        try {
            yaml = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        var m = Pattern.compile("VERIFY_SUMMARY_ANCHOR='([^']+)'").matcher(yaml);
        assertThat(m.find())
                .withFailMessage(CHECKLIST + """

                        .github/workflows/deploy.yml no longer defines VERIFY_SUMMARY_ANCHOR='…'.

                        That variable is the filter that decides which of the box's lines are echoed into a
                        WORLD-READABLE Actions log on a successful deploy. This class reads it from there so
                        the anchor under test IS the anchor in use; without it, a test of a hard-coded copy
                        would keep passing while the workflow published something else. If the echo is being
                        removed, remove this test with it — deliberately, not by deleting a variable.""")
                .isTrue();
        return Pattern.compile(m.group(1));
    }

    /** The first line of {@code text} the pattern finds, or null. */
    private static String lineMatching(String text, Pattern pattern) {
        return text.lines().filter(l -> pattern.matcher(l).find()).findFirst().orElse(null);
    }

    /**
     * <strong>Every line the applier can print, derived from its own source</strong> — the
     * negative population the public-log anchor must not match.
     *
     * <p>Both this class and the workflow test used to check the anchor against a hand-written
     * sample of five WARN lines, which is a negative control that proves the anchor rejects the
     * five lines its author happened to think of. The real population is in the script: every
     * string literal handed to {@code log}, {@code log_both} or {@code verify_fail}. Extracted
     * here, prefixed with the timestamp {@code log} really emits, and run through the anchor.
     *
     * <p>Interpolations are left as {@code $x} rather than expanded — the anchor is anchored at
     * the start of the line and matches on the literal words the applier's summary is built from
     * ({@code verify: PASS|PARTIAL ran=<n>/<n> skipped=}), so what a variable expands to cannot
     * make a non-matching line match unless the literal prefix already does.
     */
    private static List<String> everyLineTheApplierCanPrint() throws IOException {
        var script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        var lines = new ArrayList<String>();
        var m = Pattern.compile("(?:log|log_both) \"([^\"]{4,})\"").matcher(script);
        while (m.find()) {
            lines.add("2026-09-10T08:00:00Z " + m.group(1));
        }
        var f = Pattern.compile("verify_fail ([a-z-]+) \"([^\"]{4,})\"").matcher(script);
        while (f.find()) {
            lines.add("2026-09-10T08:00:00Z verify: " + f.group(1) + " FAILED: " + f.group(2));
        }
        return lines;
    }

    /**
     * The lines that MUST match, so a scan that matches nothing cannot pass for a clean one.
     * BOTH verdicts: a filter that admitted only {@code PASS} would drop the summary of exactly
     * the run whose narrowness the reader most needs to see.
     */
    private static final List<String> SUMMARY_POSITIVE_CONTROLS = List.of(
            "2026-09-10T08:00:00Z verify: PASS ran=5/5 skipped=none services=10 env-services=4 "
                    + "app-identity=full withheld=0 sha=abc1234 version=0.18.0",
            "2026-09-10T08:00:00Z verify: PARTIAL ran=3/5 skipped=grafana,drift-fresh services=4 "
                    + "env-services=2 app-identity=full withheld=0 sha=abc1234 version=0.18.0");

    /**
     * The anchor's negative population, asserted for whichever pattern is passed — used by this
     * class against the applier's own literals and by the workflow test against the same set, so
     * the two cannot disagree about what "public" means.
     */
    static void assertAnchorSelectsOnlyTheSummary(Pattern anchor, List<String> offenders) throws IOException {
        var population = everyLineTheApplierCanPrint();
        assertThat(population.size())
                .withFailMessage("Only %d printable line literal(s) were extracted from %s — this scan has "
                        + "stopped seeing them, so the anchor is being tested against nothing.",
                        population.size(), SCRIPT)
                .isGreaterThanOrEqualTo(25);
        for (String line : population) {
            if (line.contains("verify: $VERIFY_VERDICT ")) {
                continue;   // the summary itself, and only in its own literal
            }
            if (anchor.matcher(line).find()) {
                offenders.add("the anchor matches a line that is NOT the public summary: " + line);
            }
        }
        for (String control : SUMMARY_POSITIVE_CONTROLS) {
            if (!anchor.matcher(control).find()) {
                offenders.add("the anchor does not match a summary line apply-config.sh actually emits: " + control);
            }
        }
    }

    /**
     * The security review's crafted set (measured 2026-09-10) plus the shapes the fix loop named,
     * including {@code invalid hostPort: <a verbatim .env value>}, which passed BOTH of the
     * filters this one replaced. ONE list, read by the door test and by the composition test, so
     * the two cannot drift into testing different populations.
     *
     * <p>(These are synthetic and none of them is a real credential. They live inside Java string
     * literals, where {@code PublishedCredentials.LINE_ASSIGNMENT} cannot see them — it anchors a
     * credential-shaped name at the start of a line and these start with a quote. Do not move
     * them to a {@code .txt} fixture, which that scan does read.)
     */
    private static final List<String> CRAFTED_FOREIGN_LINES = List.of(
            "services[app].mem_limit invalid size: 'super-secret-looking-value'",
            "invalid size: sup3r-s3cret-db-pw",
            "error while parsing config: bad value hunter2ProdDbPass",
            "DB_PASSWORD = sup3r-s3cret-db-pw",
            "GF_SECURITY_ADMIN_PASSWORD: Tr0ub4dor-and-3",
            "resend api key re_A1b2C3d4E5f6G7h8J9k0",
            "jwt: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.QQQ",
            "postgres://hamstrack:Sekret123@db:5432/hamstrack",
            "aws key AKIAIOSFODNN7EXAMPLE and secret wJalrXUtnFEMI/K7MDENG",
            "part1: MIIEvQIBADANBgkqhkiG9w0BAQEFAASC",
            "part2: BKcwggSjAgEAAoIBAQC7VJTUt9Us8cKj",
            "AKIAIOSFODNN7EXAMPLE",
            "value: pAssw0rd!ProdMaster#2026",
            "invalid hostPort: Sekret123",
            // The stamp itself, glued and spaced. Neither shape was here, and they are the two
            // the marker and the pattern's trailing space exist for: with the `| ` marker deleted
            // this population published NOTHING and the leak assertion stayed green (measured
            // 2026-09-10), because no member could impersonate the applier. Kept well inside the
            // first 20 lines, which is all `head20` hands the pre-flight door.
            "2026-09-10T08:00:00ZGLUED-no-space-secret gLu3dStampForgery7",
            "2026-09-10T08:00:00Z compose quoted b3aconStampForgery9 from .env",
            "  Container hamstrack-app-1  Recreate",
            "Error response from daemon: no such container",
            "2026-09-10 08:00:00 something whose timestamp is not the applier's",
            "t=2026-09-10T08:00:00Z level=error logger=provisioning.alerting error=\"uid Sekret123\"");

    /**
     * The members of {@link #CRAFTED_FOREIGN_LINES} the allow-list <strong>cannot</strong> hold
     * back: they carry the applier's own stamp, so {@code grep -E "$APPLIER_LINE"} keeps them and
     * nothing but the {@code | } marker stands between them and the public log. Asserted kept
     * rather than withheld, so a population that stops containing the shape is a red build.
     */
    private static final List<String> CRAFTED_STAMP_FORGERIES = List.of(
            "2026-09-10T08:00:00Z compose quoted b3aconStampForgery9 from .env");

    /**
     * The distinctive spans of {@link #CRAFTED_FOREIGN_LINES} — what a published line is searched
     * for once the applier has re-quoted, re-wrapped or truncated the line that carried them.
     * Substrings rather than whole lines on purpose: the failure this seals is a leak, and a leak
     * that arrives with different surrounding text is still a leak.
     */
    private static final List<String> CRAFTED_SECRET_SPANS = List.of(
            "super-secret-looking-value", "sup3r-s3cret-db-pw", "hunter2ProdDbPass", "Tr0ub4dor-and-3",
            "re_A1b2C3d4E5f6G7h8J9k0", "eyJhbGciOiJIUzI1NiJ9", "Sekret123", "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG", "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC", "pAssw0rd!ProdMaster#2026",
            "gLu3dStampForgery7", "b3aconStampForgery9");

    /** The allow-list as {@code deploy.yml} defines it — read once, used by both seals. */
    private static String applierLinePattern() throws IOException {
        var m = Pattern.compile("APPLIER_LINE='([^']+)'").matcher(Files.readString(WORKFLOW, StandardCharsets.UTF_8));
        assertThat(m.find())
                .withFailMessage(CHECKLIST + """

                        .github/workflows/deploy.yml no longer defines APPLIER_LINE='…'.

                        That is the ALLOW-list the failure path publishes the box's output through, into a
                        WORLD-READABLE Actions log. If the filter is being replaced, replace the seals that
                        run it with ones that run the new filter over the same populations — deliberately,
                        not by renaming a variable.""")
                .isTrue();
        return m.group(1);
    }

    /**
     * <strong>The failure path's filter, run rather than grepped for.</strong>
     *
     * <p>The success path has an anchor with a derived negative population and a positive control.
     * The failure path — the one that carries third-party text — had neither: the seal asserted
     * that the string {@code grep -Ev "$WITHHOLD"} appeared in the workflow, so replacing the
     * pattern with one that matches nothing published every byte of the box's stdout and stderr
     * into a world-readable Actions log and stayed <em>green</em>.
     *
     * <p>Two things changed together, and this test is the second. The filter is now an
     * ALLOW-list: a deny-list over failure output cannot be complete, because that text is
     * written by Docker, Compose and AWS and not by us — measured 2026-09-10 against the 13
     * crafted secret-bearing lines below, the deny-list withheld <strong>one</strong>, and
     * {@code invalid hostPort: <a verbatim .env value>} passed it with no quotes, no {@code KEY=}
     * and under 40 characters. What is published is what {@code apply-config.sh} itself wrote,
     * identified by the timestamp {@code log}/{@code log_both} stamp on every line they emit —
     * both of them, through one shared loop, because {@code log} used to stamp only the first
     * line of a multi-line message while three sentences said otherwise.
     *
     * <p>And it is checked BEHAVIOURALLY: the pattern is read out of the workflow, handed to the
     * same {@code grep -E} the workflow runs, and both populations are derived rather than
     * listed — the negative one is the reviewer's crafted set plus the shapes the fix loop named,
     * the positive one is every line literal the applier can print, extracted from its source.
     * Neutering the pattern turns the positive population red; widening it to {@code .*} turns
     * the negative one red.
     *
     * <p>(The crafted lines are synthetic and none of them is a real credential. They live
     * inside Java string literals, where {@code PublishedCredentials.LINE_ASSIGNMENT} cannot see
     * them — it anchors a credential-shaped name at the start of a line and these start with a
     * quote. Do not move them to a {@code .txt} fixture, which that scan does read.)
     */
    @Test
    void theFailurePathPublishesOnlyLinesTheApplierWroteAndSaysHowManyItHeldBack() throws Exception {
        ScriptHarness.assumeWithWitness("apply-config-verify", bash != null,
                "no bash on PATH: this seal runs the workflow's own grep");
        var failures = new ArrayList<String>();
        var yaml = Files.readString(WORKFLOW, StandardCharsets.UTF_8);

        var pattern = applierLinePattern();
        // The USE, not the definition: a variable that is assigned and never applied is the
        // same hole with a better name.
        if (!yaml.contains("grep -E \"$APPLIER_LINE\"")) {
            failures.add("\n  - deploy.yml defines APPLIER_LINE and never filters a body through it");
        }
        // (That the deny-list has not come back is asserted once, in
        // #theWorkflowWaitsLongerThanVerifyCanTakeValidatesTheRefAndTellsRunningFromFailed, over
        // the broader `WITHHOLD=` — a second copy here would be one more thing to keep equal.)

        // NEGATIVE — none of these may reach the log, EXCEPT the stamp forgeries, which it is
        // not the filter's job to stop: a line carrying the applier's stamp is indistinguishable
        // from an applier line here, and what stops it is the `| ` marker one layer down
        // (#everyDoorThatReQuotesForeignTextPublishesItsShapeAndNeverItsBytes). Asserting they
        // are KEPT is what stops that shape quietly leaving this population.
        var allKept = keptByTheWorkflowFilter(pattern, CRAFTED_FOREIGN_LINES);
        var forgeriesWithheld = CRAFTED_STAMP_FORGERIES.stream().filter(l -> !allKept.contains(l)).toList();
        if (!forgeriesWithheld.isEmpty()) {
            failures.add("\n  - the filter withholds " + forgeriesWithheld + ", so this population no longer "
                    + "carries a line the allow-list cannot tell from the applier's own — which is the one "
                    + "shape the `| ` marker exists for");
        }
        var keptFromSecrets = allKept.stream().filter(l -> !CRAFTED_STAMP_FORGERIES.contains(l)).toList();
        assertThat(CRAFTED_FOREIGN_LINES.size())
                .withFailMessage("Only %d secret-bearing line(s) in this population — it has stopped covering "
                        + "the shapes the review found", CRAFTED_FOREIGN_LINES.size())
                .isGreaterThanOrEqualTo(15);
        if (!keptFromSecrets.isEmpty()) {
            failures.add("\n  - the filter PUBLISHES " + keptFromSecrets.size() + " of " + CRAFTED_FOREIGN_LINES.size()
                    + " line(s) the applier did not write, into a world-readable log: " + keptFromSecrets);
        }

        // POSITIVE — everything the applier itself prints must survive, or a red deploy is a
        // pointer to an SSM command and nothing else, which is the trade this filter must not
        // make. Derived from the script's own literals.
        var applierLines = everyLineTheApplierCanPrint().stream().map(l -> l.split("\n")[0]).toList();
        // The floor belongs HERE and not only in assertAnchorSelectsOnlyTheSummary: an empty
        // extraction makes `kept == population` read 0 == 0 and pass, and a comment pointing at
        // a floor in another method is not a floor in this one.
        assertThat(applierLines.size())
                .withFailMessage("Only %d printable line literal(s) were extracted from %s — this half of "
                        + "the seal is being run against nothing", applierLines.size(), SCRIPT)
                .isGreaterThanOrEqualTo(60);
        var keptFromApplier = keptByTheWorkflowFilter(pattern, applierLines);
        if (keptFromApplier.size() != applierLines.size()) {
            var dropped = new ArrayList<>(applierLines);
            dropped.removeAll(keptFromApplier);
            failures.add("\n  - the filter DROPS " + dropped.size() + " of " + applierLines.size()
                    + " line(s) apply-config.sh itself prints, so a red deploy would be unreadable: "
                    + dropped.subList(0, Math.min(5, dropped.size())));
        }

        // …and a real multi-line refusal, stamped line by line, because that is the message a
        // reader of a red deploy actually needs and the reason log_both stamps every line.
        var refused = run(deployment("allowlist-refusal"), Map.of("STUB_REVISION", "someotherrevision"));
        var refusalBlock = refused.stdout().lines()
                .dropWhile(l -> !l.contains("VERIFY FAILED"))
                .filter(l -> !l.isBlank())
                .toList();
        expect(failures, "the refusal has more than one line to lose", refusalBlock.size() > 3, refused);
        var keptFromRefusal = keptByTheWorkflowFilter(pattern, refusalBlock);
        expect(failures, "…and every line of it carries the applier's stamp, so the allow-list keeps the "
                        + "whole refusal and not only its first line (kept " + keptFromRefusal.size() + " of "
                        + refusalBlock.size() + ")",
                keptFromRefusal.size() == refusalBlock.size(), refused);

        // The count and the way to read the rest: a redaction nobody can see is the same defect
        // one level down, and a refusal may only prescribe an action its reader can perform.
        for (String must : List.of("line(s) withheld", "--query $CHANNEL --output text")) {
            if (!yaml.contains(must)) {
                failures.add("\n  - the failure path no longer says `" + must + "`");
            }
        }
        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * <strong>The two populations COMPOSED: a crafted foreign line that becomes an applier line.</strong>
     *
     * <p>The seal above tests them apart — crafted lines unstamped must drop, applier literals
     * must survive — and every door in this list performs the composition the pair leaves out.
     * The applier's <em>job</em> at each of them is to re-emit text Compose, Docker or Grafana
     * wrote. When that text went through a redacting filter and was then handed to {@code log},
     * which stamps, the allow-list published it: MEASURED 2026-09-10, 13 of 14 crafted
     * secret-bearing lines reached the world-readable Actions log that way, including a database
     * password, a Resend key, a connection string with a password and an AWS key pair. The
     * deny-list this project had already called incomplete had not gone away — it had acquired
     * publication rights.
     *
     * <p>So this runs the REAL script with the real stubs, feeds each door the crafted lines,
     * and pushes the run's whole output through {@link #keptByTheWorkflowFilter} — the workflow's
     * own {@code grep}, its own pattern, the trailing space included. Three things are asserted
     * per door, and the second and third are what stop it being vacuous:
     *
     * <ol>
     *   <li>no crafted span reaches the published set;</li>
     *   <li>a PUBLISHED line explains the failure — a door that answers a red deploy with silence
     *       is the regression this ticket introduced in four places, one of which aborted with no
     *       output at all because {@code set -e} met a cleared trap;</li>
     *   <li>where the door has an on-box diagnostic, the crafted text is still in the RAW output —
     *       otherwise "nothing leaked" would also be satisfied by a door that stopped running.</li>
     * </ol>
     *
     * <p>The drift script's two doors ({@code config --services}, {@code up -d --dry-run}) carry
     * the same {@code hold_foreign} and are covered by {@code everyFunctionBothScriptsCarryIsCarriedVerbatim}
     * plus {@link ConfigDriftContainerOracleTest}; this class drives the applier, whose fake drift
     * script cannot exercise them.
     */
    @Test
    void everyDoorThatReQuotesForeignTextPublishesItsShapeAndNeverItsBytes() throws Exception {
        ScriptHarness.assumeWithWitness("apply-config-verify", bash != null,
                "no bash on PATH: this seal runs the real applier and the workflow's grep");
        var failures = new ArrayList<String>();
        var pattern = applierLinePattern();
        var crafted = String.join("\n", CRAFTED_FOREIGN_LINES);
        // A logfmt line the grafana check will select (level=error + logger=provisioning), whose
        // secret lives in `error=` and whose SECOND msg= is the greedy-sed leak, measured.
        var craftedGrafanaLog = "logger=provisioning.datasources t=2026-09-10T08:40:23.072559573Z level=error "
                + "msg=\"Failed to provision datasources\" error=\"cannot reach postgres://hamstrack:Sekret123@db:5432\" "
                + "detail=msg=\"aws key AKIAIOSFODNN7EXAMPLE\"";

        record Door(String what, Map<String, String> env, List<String> flags, String explains, boolean onBox) { }
        var doors = List.of(
                new Door("pre-flight `up -d --dry-run` (apply-config.sh preflight)",
                        Map.of("STUB_PLAN_EXIT", "1", "STUB_PLAN", crafted), List.of("--dry-run"),
                        "WARN pre-flight: 'docker compose up -d --dry-run' against the release exited 1", true),
                new Door("step 2 `config -q` (apply-config.sh, before any mutation)",
                        Map.of("STUB_CONFIG_Q_EXIT", "1", "STUB_CONFIG_Q_ERR", crafted), List.of(),
                        "docker compose refused the released configuration", true),
                new Door("step 7 `pull` (apply-config.sh, after the stamp)",
                        Map.of("STUB_PULL_EXIT", "1", "STUB_PULL_ERR", crafted), List.of(),
                        "'docker compose -f docker-compose.prod.yml pull --quiet' failed", true),
                new Door("step 7 `up -d --remove-orphans` (apply-config.sh, traps already cleared)",
                        Map.of("STUB_UP_EXIT", "1", "STUB_UP_ERR", crafted), List.of(),
                        "up -d --remove-orphans' failed", true),
                new Door("`compose ps -q` stderr (read_service_containers / service_containers_unanswered)",
                        Map.of("STUB_PS_EXIT", "1", "STUB_PS_ERR", crafted), List.of(),
                        "so this check could not be asked whether anything is running", true),
                new Door("check 4 Grafana `/api/health` body",
                        Map.of("STUB_HEALTH", crafted), List.of(),
                        "did not report database ok", true),
                new Door("check 4 Grafana provisioning log (logfmt extraction)",
                        Map.of("STUB_GRAFANA_LOGS", GRAFANA_SANE_LOGS + "\n" + craftedGrafanaLog), List.of(),
                        "provisioning error line(s) since it started", false));
        assertThat(doors.size())
                .withFailMessage(CHECKLIST + "\nOnly %d door(s) that re-quote foreign text are exercised here. "
                        + "The category is every place a script of ours re-emits text Compose, Docker or "
                        + "Grafana wrote; the drift script's are driven by ConfigDriftContainerOracleTest, and "
                        + "deploy.yml's ssm_error() is deliberately outside it (the AWS CLI's own stderr, "
                        + "sealed where it is defined). A door dropped from this list is a door with no seal.",
                        doors.size())
                .isGreaterThanOrEqualTo(6);

        for (Door door : doors) {
            var d = deployment("foreign-" + Math.abs(door.what().hashCode()));
            var r = run(d, door.env(), door.flags().toArray(new String[0]));
            var published = keptByTheWorkflowFilter(pattern, r.output().lines().toList());
            var joined = String.join("\n", published);

            var leaked = CRAFTED_SECRET_SPANS.stream().filter(joined::contains).toList();
            expect(failures, door.what() + ": no crafted span reaches the public log" + (leaked.isEmpty() ? ""
                            : " — LEAKED " + leaked + " on: " + published.stream()
                                    .filter(l -> leaked.stream().anyMatch(l::contains)).toList()),
                    leaked.isEmpty(), r);

            expect(failures, door.what() + ": a PUBLISHED line still explains it (`" + door.explains() + "`)",
                    published.stream().anyMatch(l -> l.contains(door.explains())), r);

            if (door.onBox()) {
                expect(failures, door.what() + ": the text itself is still on the box, so the door really "
                                + "fired and the operator did not lose the diagnostic",
                        r.output().contains("sup3r-s3cret-db-pw") || r.output().contains("Sekret123"), r);
                expect(failures, door.what() + ": …unstamped, behind the `| ` marker that makes it "
                                + "structurally unpublishable",
                        r.output().lines().anyMatch(l -> l.startsWith("| ")), r);
            }
        }
        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * The workflow's own filter, run by the same {@code grep -E}, on the same bytes, with the
     * same quoting — <strong>through a script FILE, never {@code bash -c}</strong>.
     *
     * <p>It used to be {@code new ProcessBuilder(bash, "-c", "grep -E \"$APPLIER_LINE\" \"$1\"
     * || true", …)}, and on Windows that ran a <em>different pattern</em> from the workflow's.
     * Java's Windows {@code ProcessBuilder} wraps the {@code -c} argument in quotes without
     * escaping the inner ones, so bash received the command unquoted and re-split it. Measured
     * 2026-09-10, the same {@code ProcessBuilder} against the same input file:
     *
     * <pre>
     * bash -c      : keeps "2026-09-10T08:00:00ZGLUED-no-space-secret Sekret123"
     * script file  : withholds it
     * </pre>
     *
     * <p>The pattern ends in a SPACE, and that space is the whole difference — the harness was
     * testing {@code …Z}, the workflow runs {@code …Z }. Two consequences, both of which made
     * this seal weaker than it read: a glued-on secret was published by the harness and called
     * a pass, and the {@code APPLIER_LINE='.*'} widening control went red with
     * {@code grep: .git: Is a directory} — grep matching repository FILE NAMES, not population
     * members, so its red was not evidence of anything the seal claims.
     *
     * <p>A script file gets its arguments through {@code argv} untouched, and the pattern still
     * travels in the environment, which is what the workflow does anyway.
     */
    private List<String> keptByTheWorkflowFilter(String pattern, List<String> population) throws Exception {
        var input = Files.createTempFile(work, "body", ".txt");
        Files.write(input, population, StandardCharsets.UTF_8);
        var script = Files.createTempFile(work, "filter", ".sh");
        Files.writeString(script, "#!/usr/bin/env bash\ngrep -E \"$APPLIER_LINE\" \"$1\" || true\n",
                StandardCharsets.UTF_8);
        var pb = new ProcessBuilder(bash, posix(script), posix(input));
        pb.environment().put("APPLIER_LINE", pattern);
        pb.redirectErrorStream(true);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).withFailMessage("grep did not finish").isTrue();
        // grep's OWN diagnostics are not matches. Left in the returned list they were counted as
        // published population members and made a widening control look like a leak detector.
        assertThat(out.lines().filter(l -> l.startsWith("grep: ")).toList())
                .withFailMessage("grep could not run cleanly over the population, so nothing it "
                        + "printed is evidence about the filter: %s", out)
                .isEmpty();
        return out.lines().filter(l -> !l.isBlank()).toList();
    }

    /**
     * <strong>A function that RECORDS something in a global may never be called inside
     * {@code $( )} or a pipeline</strong> — enumerated from the script rather than listed here.
     *
     * <p>{@code withhold()} (since replaced by {@code hold_foreign}) incremented
     * {@code WITHHELD_COUNT} and every caller spelled it {@code "$(withhold …)"}, which runs it
     * in a SUBSHELL: the parent's counter stayed 0 for the life of the script and the "N line(s)
     * held back" notice could never print. A redaction that is silent is the defect the counter
     * exists to prevent, one level down. {@code read_service_containers} already carries a
     * comment explaining exactly this,
     * which is what makes the category obvious in hindsight and is why the rule is phrased over
     * every member instead of over the one that was wrong.
     */
    @Test
    void noFunctionThatRecordsIntoAGlobalIsCalledInASubshell() throws IOException {
        var script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        // The bodies come from ScriptHarness.functionBody — brace balance, the same delimiter the
        // shared-function comparison uses. A scan that tracked "until a line that is `}`" would
        // run every one-line function on into the top-level code below it and attribute the
        // script's own assignments to it, which is how the first version of this scan reported
        // `declared_memory` and `inspect_field` as recorders.
        // `^` OR after a brace/semicolon, because a ONE-LINE function writes its global on the
        // declaration line — `verify_ran() { VERIFY_RAN+=("$1"); }` — and an anchored pattern
        // missed every one of them. Uppercase-initial only, which is what keeps `${v//…}` and
        // `{check=\"…\"}` out.
        var globalWrite = Pattern.compile("(?:^|[{;])\\s*([A-Z][A-Z0-9_]*)(?:=|\\+=\\()");
        var recorders = new TreeSet<String>();
        for (String fn : new TreeSet<>(ScriptHarness.topLevelFunctions(SCRIPT))) {
            var declaredLocal = new java.util.HashSet<String>();
            for (String line : ScriptHarness.functionBody(SCRIPT, fn).split("\n")) {
                if (line.stripLeading().startsWith("local ")) {
                    declaredLocal.addAll(List.of(line.strip().substring("local ".length()).split("[ =]+")));
                }
                var g = globalWrite.matcher(line);
                if (g.find() && !declaredLocal.contains(g.group(1))) {
                    recorders.add(fn);
                }
            }
        }
        assertThat(recorders.size())
                .withFailMessage(CHECKLIST + "\nOnly %d function(s) in %s were seen writing a global (%s). There "
                        + "were 14 when this scan was written, so it has stopped recognising the shape rather "
                        + "than the script having stopped using it.", recorders.size(), SCRIPT, recorders)
                .isGreaterThanOrEqualTo(10);
        var offenders = new ArrayList<String>();
        for (String fn : recorders) {
            for (Pattern spelling : List.of(
                    Pattern.compile("\\$\\(\\s*" + fn + "\\b"),
                    Pattern.compile("`\\s*" + fn + "\\b"),
                    // A single `|` and not `||`: the OR operator runs in THIS shell and is how
                    // half the script spells a refusal (`… || die "…"`).
                    Pattern.compile("(?<!\\|)\\|(?!\\|)\\s*" + fn + "\\b"))) {
                var use = spelling.matcher(script);
                while (use.find()) {
                    int at = script.lastIndexOf('\n', use.start()) + 1;
                    var line = script.substring(at, script.indexOf('\n', use.start()));
                    if (line.stripLeading().startsWith("#")) {
                        continue;   // the comment that explains why this is forbidden
                    }
                    offenders.add(fn + "() at: " + line.strip());
                }
            }
        }
        assertThat(offenders)
                .withFailMessage(CHECKLIST + """

                        A function that records into a GLOBAL is called inside `$( )` or a pipeline, which
                        runs it in a SUBSHELL: the record dies with the subshell and the parent never sees
                        it. This is not hypothetical — `WITHHELD_COUNT` was incremented that way for the
                        life of the script, so the "N line(s) withheld" notice could never print, and a
                        redaction nobody can see is the same defect one level down.

                        Make the function set its result in a global and call it on a line of its own, the
                        way read_service_containers and hold_foreign do, or make it record nothing.

                        Offender(s): %s""", offenders)
                .isEmpty();
    }

    /**
     * <strong>Every {@code hold_foreign} call site names the shape it held on a stamped line</strong>
     * — the rule the applier states above {@code hold_foreign}, enumerated from both scripts rather
     * than listed here.
     *
     * <p>A door that names the shape only inside {@code die} says nothing on the path where the run
     * holds lines and then SUCCEEDS: the {@code "| "} legend is then in refusal text that run never
     * emits. The shape may be named on the emitting line itself, or captured into a global that a
     * stamped line prints; both spellings are in the scripts today.
     */
    @Test
    void everyHoldForeignCallSiteNamesItsShapeOnAStampedLine() throws IOException {
        var offenders = new ArrayList<String>();
        var sites = new ArrayList<String>();
        for (Path script : List.of(SCRIPT, DRIFT)) {
            var defined = ScriptHarness.topLevelFunctions(script);
            var text = joinContinuations(Files.readString(script, StandardCharsets.UTF_8));
            // LOGICAL lines, here and in the window: the shape is spelled on a backslash
            // continuation at two of these call sites, and a raw-line scan sees an assignment
            // without its value and a `|| die` without its command.
            var lines = text.lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                var at = lines.get(i);
                if (!callsOneOf(at, List.of("hold_foreign")) || at.startsWith("hold_foreign()")) {
                    continue;
                }
                var site = script + " @ " + at.strip();
                sites.add(site);
                var named = lines.subList(Math.max(0, i - 3), Math.min(lines.size(), i + 4)).stream()
                        .filter(l -> !l.stripLeading().startsWith("#"))
                        .filter(l -> l.contains("foreign_shape "))
                        .toList();
                if (named.isEmpty()) {
                    offenders.add(site + " — nothing within three lines names the shape");
                    continue;
                }
                var reaches = false;
                for (String l : named) {
                    reaches |= stamps(script, defined, l, 3) || carriedToAStampedLine(script, defined, text, l);
                }
                if (!reaches) {
                    offenders.add(site + " — the shape is computed and never printed on a stamped line: " + named);
                }
            }
        }
        assertThat(sites)
                .withFailMessage(CHECKLIST + "\nOnly %d hold_foreign call site(s) were found across %s and %s. "
                        + "Every door that re-quotes foreign text goes through that one function, so a smaller "
                        + "number means this scan has stopped seeing the call spelling and its silence means "
                        + "nothing. Found: %s", sites.size(), SCRIPT, DRIFT, sites)
                .hasSizeGreaterThanOrEqualTo(7);
        assertThat(offenders)
                .withFailMessage(CHECKLIST + """

                        A hold_foreign call site holds lines back without any STAMPED line saying how many
                        and of what shape. The held text is unstamped behind "| " and deploy.yml's allow-list
                        drops it, so for the public reader those lines do not exist and no line accounts for
                        them; on a SUCCESS path there is not even a refusal to carry the count.

                        Put `$(foreign_shape "$text")` on a log/log_both/die line beside the hold — or into a
                        global a stamped line prints, the way service_containers_unanswered does.

                        Offender(s): %s""", offenders)
                .isEmpty();
    }

    /**
     * Whether the text reaches {@code _stamp}: it calls {@code log}/{@code log_both}/{@code die}
     * itself, or a function of this script that does. Resolved only for the functions the text
     * actually calls, because the brace counter cannot delimit every function in these two files
     * (a {@code grep -E} pattern with an escaped brace opens a depth that never closes).
     */
    private static boolean stamps(Path script, List<String> defined, String text, int depth) throws IOException {
        if (callsOneOf(text, List.of("log", "log_both", "die"))) {
            return true;
        }
        if (depth <= 0) {
            return false;
        }
        for (String fn : defined) {
            if (callsOneOf(text, List.of(fn)) && stamps(script, defined, ScriptHarness.functionBody(script, fn), depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /** What bash sees: a backslash continuation and the line under it are one command. */
    private static String joinContinuations(String source) {
        return source.replaceAll("\\\\\\r?\\n\\s*", " ");
    }

    /** Whether the text invokes one of the names in COMMAND position — not merely mentions it. */
    private static boolean callsOneOf(String text, java.util.Collection<String> names) {
        return text.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .anyMatch(l -> names.stream().anyMatch(n ->
                        Pattern.compile("(?:^|[;&|(]|\\|\\|)\\s*" + n + "\\b").matcher(l).find()));
    }

    /** The other spelling: the shape goes into a global, and a stamped line elsewhere prints it. */
    private static boolean carriedToAStampedLine(Path script, List<String> defined, String text, String line)
            throws IOException {
        var assigned = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)=").matcher(line);
        if (!assigned.find()) {
            return false;
        }
        for (String l : text.lines().filter(l -> l.contains("$" + assigned.group(1))
                || l.contains("${" + assigned.group(1))).toList()) {
            if (stamps(script, defined, l, 3)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <strong>A check that did not read the box may only LOWER confidence.</strong>
     *
     * <p>Measured on 2026-09-10: a {@code --verify-only} run with {@code COMPOSE_FILES} narrowed
     * to one file rewrote four checks that had published {@code 0} with {@code 2} (not
     * applicable) and printed {@code verify: PASS 5/5} — four CRITICAL alerts went quiet and the
     * summary said the box was fine. Narrowing the compose set is a documented invocation, so
     * this was a supported way to silence a firing page.
     *
     * <p>The rule is in {@code _check_ok}, the one place a value is written, so it holds for
     * every check and for every check added later. The discriminator is the previous file's
     * {@code checks_ran}: {@code 0} in this gauge means "failed" OR "not run yet", and without
     * that gate a single killed deploy would pin a legitimately-skipped check at 0 for ever on a
     * box whose reader cannot make the service exist — a refusal its reader cannot act on.
     */
    @Test
    void aSkipMayNotRaiseACheckThePreviousRunReadAsFailing() throws Exception {
        var failures = new ArrayList<String>();
        var d = deployment("holddown");

        // 1. A real reading that fails: grafana's health never reports database ok.
        var red = run(d, Map.of("STUB_HEALTH", "{\"database\":\"broken\"}"));
        expect(failures, "the box really refuses first", red.exit() != 0, red);
        expect(failures, "…publishing 0 for grafana", checkGauge(d, "grafana") == 0, red);
        expect(failures, "…from a run that read the box", gauge(d, "hamstrack_deploy_verify_checks_ran") >= 1, red);

        // 2. The documented narrowing invocation, which no longer reaches that series.
        var narrowed = run(d, Map.of("STUB_SERVICES", "app"));
        expect(failures, "a run that SKIPS grafana may not clear it", checkGauge(d, "grafana") == 0, narrowed);
        expect(failures, "…and says why, naming a compose set its reader can pass",
                narrowed.output().contains("this run did NOT read grafana")
                        && narrowed.output().contains("COMPOSE_FILES="), narrowed);
        expect(failures, "…and refuses rather than exiting 0 beside a firing alert", narrowed.exit() != 0, narrowed);
        expect(failures, "…and the roll-up is not greener than its parts",
                gauge(d, "hamstrack_deploy_verify_ok") == 0, narrowed);
        expect(failures, "…and no PASS summary is printed", !narrowed.output().contains("verify: PASS "), narrowed);

        // 3. A run that READS it green does clear it — the rule is about not raising blind, not
        // about a 0 being permanent.
        var green = run(d, Map.of());
        expect(failures, "a run that reads grafana green clears it", green.exit() == 0
                && checkGauge(d, "grafana") == 1, green);

        // 4. …and a killed deploy's pessimistic zeros do NOT pin a legitimate skip, or a box with
        // no grafana would be unfixable by anybody after one interrupted run.
        var fresh = deployment("holddown-killed");
        var killed = run(fresh, Map.of("STUB_PULL_EXIT", "1"));
        expect(failures, "the killed run leaves 0s and read nothing", killed.exit() != 0
                && checkGauge(fresh, "grafana") == 0
                && gauge(fresh, "hamstrack_deploy_verify_checks_ran") == 0, killed);
        var afterKill = run(fresh, Map.of("STUB_SERVICES", "app"));
        expect(failures, "a legitimate skip after a killed deploy publishes 2 and passes",
                afterKill.exit() == 0 && checkGauge(fresh, "grafana") == 2, afterKill);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- the seals that read files rather than run them --------------------------------------

    /**
     * Whatever the two scripts both define, they define identically — the pre-flight reads the
     * same plan the drift check reads, both write a label into a textfile, both stamp their own
     * lines and both re-quote Compose's own text. The same shape as
     * {@link ApplyConfigPinGuardTest#bothScriptsParseTheEnvPinWithTheSameFunction}.
     */
    @Test
    void everyFunctionBothScriptsCarryIsCarriedVerbatim() throws IOException {
        // DERIVED, not listed. The list here used to be [plan_container_pairs, sanitize_label]
        // while the real intersection was four: `log` — byte-identical in both scripts, and the
        // line every other assertion in this file greps for — was sealed by nothing, and
        // `read_image_tag` was sealed only over in ApplyConfigPinGuardTest. A hand-written member
        // list is a population that stops covering its category in silence; this one now grows by
        // itself the moment somebody copies a third function across.
        var applierFns = new TreeSet<>(ScriptHarness.topLevelFunctions(SCRIPT));
        var driftFns = new TreeSet<>(ScriptHarness.topLevelFunctions(DRIFT));
        var shared = new TreeSet<>(applierFns);
        shared.retainAll(driftFns);
        assertThat(shared)
                .withFailMessage(CHECKLIST + "\nOnly %d function name(s) are defined in BOTH scripts (%s). The "
                        + "floor is the whole shared set, not a margin below it: half a population is exactly "
                        + "the slack that let this floor sit at 4 while `hold_foreign` — the function this "
                        + "epic is about — could stop being shared unnoticed. Two ways here: the scan has "
                        + "stopped seeing top-level definitions (fix the scan), or a function really did "
                        + "leave one script (move the floor in the same commit, and say which function and "
                        + "why). applier=%s drift=%s", shared.size(), shared, applierFns, driftFns)
                .hasSizeGreaterThanOrEqualTo(8);
        for (String fn : shared) {
            // comparableFunctionBody, not functionBody: the ONE permitted difference (each script
            // names the box's env file with its own variable) lives in ScriptHarness, where
            // ApplyConfigPinGuardTest's own comparison of read_image_tag reads it too.
            var applier = ScriptHarness.comparableFunctionBody(SCRIPT, fn);
            var drift = ScriptHarness.comparableFunctionBody(DRIFT, fn);
            assertThat(applier)
                    .withFailMessage(CHECKLIST + """

                            ops/deploy/apply-config.sh and ops/drift/hamstrack-config-drift.sh no longer carry
                            the same %s(). The pre-flight and the containers drift scope read the same
                            Compose plan; a parse fixed in one and not the other means the deploy log and
                            the hourly monitor disagree about the same box. Change both, or neither.

                            applier: %s

                            drift:   %s
                            """, fn, applier, drift)
                    .isEqualTo(drift);
        }
    }

    /**
     * The gauge's {@code check} label is a closed enum, and it is closed at BOTH ends: every
     * label the script publishes is a check the script runs, and the alert reads the gauge per
     * check. Phrased over the set rather than the five names, with a floor.
     */
    @Test
    void theGaugeNamesExactlyTheChecksTheScriptRunsAndTheRuleReadsThemPerCheck() throws IOException {
        var script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        var published = new TreeSet<String>();
        var m = Pattern.compile("hamstrack_deploy_verify_check_ok\\{check=\\\\\"([a-z-]+)\\\\\"\\}").matcher(script);
        while (m.find()) {
            published.add(m.group(1));
        }
        var run = new TreeSet<String>();
        var f = Pattern.compile("verify_fail ([a-z-]+) ").matcher(script);
        while (f.find()) {
            run.add(f.group(1));
        }
        var ok = Pattern.compile("log \"verify: ([a-z-]+) ok").matcher(script);
        while (ok.find()) {
            run.add(ok.group(1));
        }
        assertThat(published)
                .withFailMessage(CHECKLIST + "\nThe gauge publishes %s but the script runs checks %s — a label with no check "
                        + "behind it is a series that can only ever read 0 or a stale 1, and a check with no label is "
                        + "one DeployVerifyFailed cannot name.", published, run)
                .isEqualTo(run);
        assertThat(published.size())
                .withFailMessage("Only %d check label(s) were found in %s — the scan has stopped seeing them", published.size(), SCRIPT)
                .isGreaterThanOrEqualTo(5);

        // A THIRD list of the same names exists: VERIFY_CHECKS, which is the denominator of the
        // public `PASS n/n` summary and of the ran/skipped accounting. Three lists that must
        // agree is two lists too many unless something compares them, and the failure of the
        // odd one out is silent — the summary would simply say 5/5 while six checks ran.
        var declared = new TreeSet<String>();
        var v = Pattern.compile("VERIFY_CHECKS=\\(([^)]*)\\)").matcher(script);
        assertThat(v.find())
                .withFailMessage(CHECKLIST + "\n%s no longer declares VERIFY_CHECKS=(…), which is what the "
                        + "public summary counts against", SCRIPT)
                .isTrue();
        declared.addAll(List.of(v.group(1).trim().split("\\s+")));
        assertThat(declared)
                .withFailMessage(CHECKLIST + "\nVERIFY_CHECKS is %s but the script publishes %s. The summary "
                        + "line's n/n counts the first and the gauge names the second; a check missing from "
                        + "either is a check the public log claims to have run.", declared, published)
                .isEqualTo(published);

        // …and every one of them marks itself ran-or-skipped, or the `ran=` count silently
        // undercounts what the box actually verified.
        var marked = new TreeSet<String>();
        var mk = Pattern.compile("verify_(?:ran|skip) ([a-z-]+)").matcher(script);
        while (mk.find()) {
            marked.add(mk.group(1));
        }
        var unmarked = new TreeSet<>(published);
        unmarked.removeAll(marked);
        assertThat(unmarked)
                .withFailMessage(CHECKLIST + "\nThese checks never call verify_ran or verify_skip: %s. A check "
                        + "that does neither is absent from both counts on the public summary line, so a box "
                        + "that verified less than it claims reads exactly like one that verified everything.",
                        unmarked)
                .isEmpty();

        var rules = Files.readString(RULES, StandardCharsets.UTF_8);
        int at = rules.indexOf("uid: hamstrack-deploy-verify-failed");
        assertThat(at).withFailMessage(CHECKLIST + "\nrules.yml declares no rule with uid hamstrack-deploy-verify-failed").isNotNegative();
        var rule = rules.substring(at, Math.min(rules.length(), at + 3000));
        assertThat(rule)
                .withFailMessage(CHECKLIST + "\nDeployVerifyFailed must read hamstrack_deploy_verify_check_ok UNAGGREGATED (its summary "
                        + "names $labels.check), threshold < 1, severity critical, noDataState OK, for 5m. Found:\n%s", rule)
                .contains("expr: 'hamstrack_deploy_verify_check_ok'")
                .contains("{{ $labels.check }}")
                .contains("evaluator: { type: lt, params: [1] }")
                .contains("severity: critical")
                .contains("noDataState: OK")
                .contains("for: 5m");
        assertThat(Files.readString(Path.of("docs/observability.md"), StandardCharsets.UTF_8))
                .withFailMessage("docs/observability.md's rule table does not list DeployVerifyFailed")
                .contains("DeployVerifyFailed");

        // THE PUBLISHED HELP IS A CLAIM THAT REACHES PROMETHEUS, and it is the same claim the two
        // operator documents make. The value for "not applicable" was changed from 1 to 2 in the
        // script and stayed 1 in five other places, one of them this HELP string — so the metric
        // itself told the operator something the metric no longer did. What is compared is the
        // meaning of each value, in the one wording all three use, rather than the whole
        // sentence: a doc may say more, it may not say something else.
        var h = Pattern.compile("# HELP hamstrack_deploy_verify_check_ok ([^']+)").matcher(script);
        assertThat(h.find())
                .withFailMessage(CHECKLIST + "\n%s no longer publishes a HELP line for "
                        + "hamstrack_deploy_verify_check_ok", SCRIPT)
                .isTrue();
        var meanings = Map.of(
                "0", "0 failed",
                "1", "1 passed",
                "2", "2 not applicable");
        var disagreements = new ArrayList<String>();
        for (var value : new TreeSet<>(meanings.keySet())) {
            if (!h.group(1).contains(meanings.get(value))) {
                disagreements.add("the published HELP does not say `" + meanings.get(value) + "`");
            }
        }
        // …and the same three values in the documents an operator reads, in their own table
        // wording. `2` is the one a value change moves, so it is named explicitly in each.
        //
        // ANCHORED TO THE SENTENCE THAT DEFINES IT, NOT TO A WINDOW. This used to ask whether
        // the characters `2` appeared in backticks anywhere in the 2500 characters after the
        // metric's name. Measured 2026-09-10: that window holds three OTHER `2` tokens in
        // docs/observability.md ("`1` and `2` are different on purpose", "instead of `2`",
        // "publishes `check_ok` `2`") and two in docs/ops-prod-hardening.md — so planting the
        // definitional drift the message exists for (`2` **did not apply on this box** ->
        // `two` …) left this half GREEN while the HELP half correctly fired. It was asking
        // "does the digit appear nearby", which is not an agreement check.
        //
        // Now: the VALUE and its MEANING must be within 60 characters of each other, with no
        // sentence boundary between them — the same standard the HELP half already meets by
        // requiring the literal `2 not applicable`. Whitespace is normalised first because one
        // of the two documents wraps that sentence across lines.
        var meaningPhrases = List.of("did not apply", "does not apply", "not applicable", "skipped because nothing");
        for (Path doc : List.of(Path.of("docs/observability.md"), Path.of("docs/ops-prod-hardening.md"))) {
            var raw = Files.readString(doc, StandardCharsets.UTF_8);
            int mentionAt = raw.indexOf("hamstrack_deploy_verify_check_ok");
            if (mentionAt < 0) {
                disagreements.add(doc + " no longer documents hamstrack_deploy_verify_check_ok at all");
                continue;
            }
            // THE REGION THAT DEFINES IT, not the document and not a character window. A table row
            // is its own line; prose runs to the end of its paragraph. Anything outside that is a
            // MENTION, and a mention is what made the first version of this check vacuous — the
            // sibling row `hamstrack_deploy_verify_checks_ran` says "a check skipped because
            // nothing on this box declares what it reads publishes `check_ok` `2`", which satisfied
            // the phrasing test while the row being checked had been planted with `two`.
            int lineStart = raw.lastIndexOf('\n', mentionAt) + 1;
            String region;
            if (raw.startsWith("|", lineStart)) {
                int end = raw.indexOf('\n', lineStart);
                region = raw.substring(lineStart, end < 0 ? raw.length() : end);
            } else {
                int end = raw.indexOf("\n\n", lineStart);
                region = raw.substring(lineStart, end < 0 ? raw.length() : end);
            }
            region = region.replaceAll("\\s+", " ");
            var phrases = String.join("|", meaningPhrases);
            boolean defined = java.util.Arrays.stream(region.split("(?<=\\.) "))
                    .anyMatch(sentence -> sentence.matches(".*\\*{0,2}`2`\\*{0,2}.*")
                            && Pattern.compile(phrases).matcher(sentence).find());
            if (!defined) {
                disagreements.add(doc + " never puts `2` in the SAME SENTENCE as what it means (one of "
                        + meaningPhrases + ") inside the passage that defines the metric. It is the value "
                        + "that moved, and a document saying `2` only in a neighbouring row is not a "
                        + "document that tells its reader what `2` is. The passage read: " + region);
            }
        }
        assertThat(disagreements)
                .withFailMessage(CHECKLIST + """

                        The published HELP of hamstrack_deploy_verify_check_ok and the documents that
                        explain it do not agree about what its values mean. The HELP text is SCRAPED INTO
                        PROMETHEUS, so it is the copy an operator reads at 3am with no repository open —
                        and it went stale once already: `2` (not applicable) was introduced in the script
                        and five other places, including this string, went on saying `1`.

                        Change the value in one place and this is the list of the others: %s""",
                        disagreements)
                .isEmpty();
    }

    /**
     * <strong>A remedy is spelled as something its reader can actually type.</strong>
     *
     * <p>{@code ops/} is <em>synced</em> to {@code /opt/hamstrack/ops/}, never installed —
     * {@code §6.4} of the config-delivery proposal is explicit that the sync cannot install, and
     * the drift check's {@code installed-ops} scope exists because of it. So {@code apply-config.sh
     * /opt/hamstrack …}, which is how three refusals and one alert used to spell the remedy, is a
     * command that answers {@code command not found} for every reader who follows it.
     *
     * <p>The category is "a place that tells somebody to RUN the applier", enumerated over the
     * script, the alert, the workflow and the three operator documents; the rule is that an
     * invocation (the name followed by an argument) is reached through a path. A usage line that
     * only names the tool is left alone: its reader has already found it.
     */
    @Test
    void everyPrescribedInvocationOfTheApplierIsReachedThroughAPath() throws IOException {
        var prescribers = List.of(SCRIPT, RULES, WORKFLOW,
                Path.of("docs/release-checklist.md"),
                Path.of("docs/ops-prod-hardening.md"),
                Path.of("docs/self-hosting.md"));
        // The name followed by an argument — i.e. somebody being told to RUN it. `(?<![/\w-])`
        // rejects the path forms (`ops/deploy/apply-config.sh`, `$TARGET/…/apply-config.sh`).
        var invocation = Pattern.compile("(?<![/\\w-])apply-config\\.sh +(?=[/$\\.]|--)");
        var offenders = new ArrayList<String>();
        var perFile = new java.util.LinkedHashMap<Path, Integer>();
        for (Path file : prescribers) {
            assertThat(file).withFailMessage("%s is in the prescriber list and is not on disk — the list is stale, "
                    + "which is how a remedy stops being checked", file).isRegularFile();
            var text = Files.readString(file, StandardCharsets.UTF_8);
            int seen = 0;
            for (String line : text.split("\n")) {
                if (line.contains("apply-config.sh")) {
                    seen++;
                }
                if (invocation.matcher(line).find()) {
                    offenders.add(file + ": " + line.strip());
                }
            }
            perFile.put(file, seen);
        }
        assertThat(offenders)
                .withFailMessage(CHECKLIST + """

                        A remedy spells the applier as if it were on PATH. It is not: ops/ is SYNCED to
                        /opt/hamstrack/ops/ and never installed (that is why the drift check has an
                        `installed-ops` scope at all), so `apply-config.sh /opt/hamstrack …` answers
                        `command not found` for the operator who is reading a red deploy at the time.

                        Spell it as a path — `bash /opt/hamstrack/ops/deploy/apply-config.sh …` in a document
                        or an alert, `bash $TARGET/ops/deploy/apply-config.sh …` in the script itself.

                        A refusal may only prescribe an action its reader can perform.

                        Offending line(s): %s""", offenders)
                .isEmpty();
        // PER FILE, not a total. A total of 10 against 41 real mentions is satisfied by four of
        // the six files going silent — which is exactly the drift a floor exists to catch, since
        // a document that stops prescribing the applier is a document somebody rewrote. Each
        // prescriber must still mention it at least once; the total keeps a floor of its own.
        var silent = perFile.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).toList();
        assertThat(silent)
                .withFailMessage("These files are in the prescriber population and no longer mention "
                        + "apply-config.sh at all: %s. Either they stopped telling anybody how to run it — in "
                        + "which case this scan is no longer looking where the remedies are — or the list is "
                        + "stale. Counts per file: %s", silent, perFile)
                .isEmpty();
        int scanned = perFile.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(scanned)
                .withFailMessage("Only %d line(s) mentioning apply-config.sh were found across %s (per file: %s) "
                        + "— there were 41 when this floor was set, so this scan has stopped seeing them.",
                        scanned, prescribers, perFile)
                .isGreaterThanOrEqualTo(30);
    }

    /**
     * The workflow half (§8 of the proposal): the 100-second waiter is gone, the poll is
     * bounded above verify's own budgets, a running command reads differently from a failed
     * one, and the ref name is validated before anything derived from it reaches the box.
     */
    @Test
    void theWorkflowWaitsLongerThanVerifyCanTakeValidatesTheRefAndTellsRunningFromFailed() throws IOException {
        var yaml = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
        var offenders = new ArrayList<String>();
        if (Pattern.compile("(?m)^\\s+aws ssm wait\\b").matcher(yaml).find()) {
            offenders.add("`aws ssm wait` is back: botocore's CommandExecuted waiter is 20 x 5 s = 100 s, so a deploy "
                    + "that legitimately spends its verify budgets is red regardless of outcome");
        }
        if (!yaml.contains("Pending|InProgress|Delayed")) {
            offenders.add("the poll no longer keeps waiting on Pending|InProgress|Delayed");
        }
        if (!yaml.contains("--timeout-seconds 1500")) {
            offenders.add("send-command no longer gives the command a generous DELIVERY window "
                    + "(--timeout-seconds 1500) — a busy agent may take minutes to pick a deploy up");
        }
        // WHAT THAT FLAG IS NOT. Measured 2026-09-10 from the bundled AWS service model
        // (botocore ssm/2014-11-06/service-2.json, SendCommandRequest.TimeoutSeconds): "If this
        // time is reached and the command hasn't already started running, it won't run." It bounds
        // DELIVERY. This test used to seal the opposite claim — that it bounds the command on the
        // box — which is the worst kind of green: a safety net asserted into existence.
        if (!yaml.contains("hasn't already started running")) {
            offenders.add("deploy.yml no longer quotes what --timeout-seconds actually means "
                    + "(\"If this time is reached and the command hasn't already started running, it won't run\" "
                    + "— SendCommandRequest.TimeoutSeconds in botocore's ssm model). Either quote it, or pass the "
                    + "AWS-RunShellScript document's executionTimeout parameter and say THAT is the execution bound");
        }
        for (String falseClaim : List.of("bounds the command ON THE BOX", "its own bound is --timeout-seconds")) {
            if (yaml.contains(falseClaim)) {
                offenders.add("deploy.yml claims '" + falseClaim + "' — --timeout-seconds is a delivery bound, and "
                        + "nothing in this workflow kills a command that has already started");
            }
        }
        // The success echo: ONE line, selected by anchor, never by substring. A filter written as
        // "lines containing verify:" publishes `verify: WARN drift-fresh: edge-body-limit=1` — a
        // live weakness of the production edge — into a world-readable log.
        var anchor = Pattern.compile("VERIFY_SUMMARY_ANCHOR='([^']+)'").matcher(yaml);
        if (!anchor.find()) {
            offenders.add("no VERIFY_SUMMARY_ANCHOR: the verify result is either not echoed on success, or "
                    + "echoed by a filter this test cannot see");
        } else {
            var pattern = Pattern.compile(anchor.group(1));
            if (!yaml.contains("grep -E \"$VERIFY_SUMMARY_ANCHOR\"")) {
                offenders.add("the success echo does not filter the box's output through VERIFY_SUMMARY_ANCHOR");
            }
            // The negative population is DERIVED from the applier's own log literals, not a
            // sample somebody typed: a hand-written sample proves the anchor rejects the lines
            // its author thought of, which is exactly the set that was never going to be the
            // problem.
            assertAnchorSelectsOnlyTheSummary(pattern, offenders);
        }
        // …and the FAILURE path, which is where the third-party text is. Its filter is an
        // ALLOW-list and it is sealed BEHAVIOURALLY by
        // #theFailurePathPublishesOnlyLinesTheApplierWroteAndSaysHowManyItHeldBack, which runs
        // the pattern over the crafted population and over every literal the applier can print — this
        // block only holds the shape, because a presence check is exactly what let a pattern
        // matching nothing at all pass for a filter.
        if (!yaml.contains("grep -E \"$APPLIER_LINE\"")) {
            offenders.add("the failure path prints both SSM bodies with no allow-list filter — the success "
                    + "path is anchored and the failure path is not, and the failure path is the one that "
                    + "prints third-party text (Compose errors, container logs) into a public log");
        }
        if (yaml.contains("WITHHOLD=")) {
            offenders.add("the deny-list is back. It cannot be complete: the failure text is written by "
                    + "Docker, Compose and AWS, and measured 2026-09-10 it withheld 1 of 13 crafted "
                    + "secret-bearing lines. Publish only what the applier stamped");
        }
        if (!yaml.contains("line(s) withheld")) {
            offenders.add("the failure path withholds lines without saying how many — a redaction nobody can "
                    + "see is the same defect one level down");
        }
        if (!yaml.contains("--query $CHANNEL --output text")) {
            offenders.add("the failure path withholds lines without saying how to read them over SSM — a "
                    + "refusal may only prescribe an action its reader can perform");
        }
        // EVERY aws CALL HERE DISTINGUISHES "THE API ANSWERED X" FROM "THE API DID NOT ANSWER".
        // `2>/dev/null || echo Pending` turned expired credentials, a throttle, a network error
        // and a wrong command id all into the word `Pending`, and the workflow then waited 20
        // minutes on a status it had invented and printed a conclusion about the box.
        if (Pattern.compile("\\|\\|\\s*(echo\\s+\"?Pending|STATUS=\"?Pending)").matcher(yaml).find()) {
            offenders.add("the poll fabricates a status when the API does not answer (`|| … Pending`) — "
                    + "it then asserts something about the box that no reply supports");
        }
        // THE LOGICAL COMMAND, NOT THE LINE IT STARTS ON. This scanned `^.*aws ssm.*$`, and every
        // `aws ssm` invocation in this workflow is a backslash continuation, so the redirection
        // is NEVER on the line the words `aws ssm` appear on. Planting the exact regression the
        // comment above describes — `2>"$SSM_ERR"` -> `2>/dev/null` on the third line of
        // `ssm_field` — left the seal GREEN. Continuations are joined first, so the scan sees
        // what bash sees; the floor is on the number of invocations, because a rename that
        // empties the population is the other way this passes while seeing nothing.
        var joined = joinContinuations(yaml);
        // INVOCATIONS, NOT MENTIONS. A floor of 7 over "lines containing `aws ssm`" was
        // satisfied by 9 matches of which 2 were commands and 7 were comments and echoed
        // remedies — so deleting BOTH real calls left the floor green. `aws ssm` in command
        // position: at the start of the (stripped) logical line, or opening a substitution or a
        // list. Inside `echo "… aws ssm …"` it is preceded by prose and is not selected.
        var invocation = Pattern.compile("(?:^|[;&|(]\\s*|\\$\\()\\s*aws ssm\\b");
        var logicalCommands = joined.lines()
                .map(String::strip)
                .filter(l -> !l.startsWith("#"))
                .filter(l -> invocation.matcher(l).find())
                .toList();
        assertThat(logicalCommands.size())
                .withFailMessage("Only %d `aws ssm` invocation(s) were found in %s after joining continuations "
                        + "and dropping mentions — this workflow cannot deploy without sending a command and "
                        + "reading the invocation back, so a smaller number means the scan has stopped seeing "
                        + "them and its silence means nothing. Found: %s",
                        logicalCommands.size(), WORKFLOW, logicalCommands)
                .isGreaterThanOrEqualTo(2);
        // …and $SSM_ERR holds an `aws ssm` invocation's stderr and nothing else. ssm_error()
        // publishes that file into the public log bounded by LENGTH, which is the treatment this
        // change rejected everywhere the text came from Compose, Docker or Grafana; it is right
        // here only for as long as nothing else can write to the file.
        // ANY WRITE, not the one spelling this workflow happens to use. `2>"$SSM_ERR"` alone was
        // not selected by `ssm_field … > "$SSM_ERR"` or by `2>>"$SSM_ERR"` (measured against both
        // mutants), so the scan held "the one 2> redirect we already knew about is an aws ssm
        // call" while the comment claims the file has no other writer.
        var writesErr = Pattern.compile("(?:[0-9]*>>?|&>>?)\\s*\"?\\$\\{?SSM_ERR\\}?\"?|\\btee\\b[^|;]*\\$\\{?SSM_ERR\\}?");
        var errWriters = joined.lines()
                .filter(l -> !l.strip().startsWith("#"))
                .filter(l -> writesErr.matcher(l).find())
                .toList();
        if (errWriters.isEmpty() && yaml.contains("SSM_ERR")) {
            offenders.add("$SSM_ERR is still referenced and nothing writes into it — "
                    + "either the CLI's stderr is being captured some other way (then this scan reads nothing) "
                    + "or ssm_error() now publishes a file whose writer is unknown");
        }
        for (String command : errWriters) {
            if (!command.contains("aws ssm")) {
                offenders.add("something other than an `aws ssm` invocation writes $SSM_ERR, and ssm_error() "
                        + "publishes that file into a world-readable log bounded only by `cut -c1-300` — a "
                        + "length bound is not a content bound, and the argument for keeping one here is that "
                        + "the file carries the AWS CLI's own diagnostic and never the box's output: "
                        + command.strip());
            }
        }
        for (String command : logicalCommands) {
            if (command.contains("2>/dev/null")) {
                offenders.add("an `aws ssm` call still swallows its own error with 2>/dev/null, so 'the API "
                        + "did not answer' collapses into 'the box said nothing': " + command.strip());
            }
        }
        if (!yaml.contains("SSM did not answer")) {
            offenders.add("no call site reports an unreachable API in its own words — 'the box printed "
                    + "nothing' and 'the API could not be asked' are different facts");
        }
        // THE INSTANCE ID COMMENT MAY NOT CLAIM WHAT THE TREE CONTRADICTS. `vars.` does not
        // conceal the id (this repository is public and this workflow echoes it in its own
        // remedies) and there is no fallback (line 148 refuses an empty value by design).
        for (String falseClaim : List.of("stops being published", "The fallback keeps this workflow working")) {
            if (yaml.contains(falseClaim)) {
                offenders.add("deploy.yml claims '" + falseClaim + "', which the file itself contradicts — "
                        + "the argument for vars. is that a variable is not masked and a fork is a variable "
                        + "change, and an empty value is REFUSED rather than defaulted");
            }
        }
        // The instance id is not a credential, but this repository is PUBLIC and this workflow now
        // echoes the id in three places. `vars.` is neither a literal in a public file nor a
        // masked secret (masking is what would make a typo look correct while it 403s).
        if (!yaml.contains("INSTANCE_ID: ${{ vars.INSTANCE_ID }}")) {
            offenders.add("INSTANCE_ID is not read from the repository variable vars.INSTANCE_ID — a literal "
                    + "publishes it in a public file and a secret would be masked, which hides a typo");
        }
        if (Pattern.compile("INSTANCE_ID: i-[0-9a-f]+").matcher(yaml).find()) {
            offenders.add("a literal instance id is back in the workflow");
        }
        if (!yaml.contains("if [ -z \"$INSTANCE_ID\" ]")) {
            offenders.add("an unset INSTANCE_ID is not refused by name before send-command — it would fail as "
                    + "an AWS error about an empty --instance-ids, which names nothing a reader can act on");
        }
        // The ref guard matches the WHOLE value. `grep -Eq` matches per LINE, so a value whose
        // first line is `1.2.3` passed it (measured); git's own refusal of control characters in
        // a ref name is what actually held that today, which is a holder in another program.
        if (!yaml.contains("[[ \"$HEAD_REF\" =~ ")) {
            offenders.add("HEAD_REF is not matched with [[ =~ ]] — a per-line grep accepts a multi-line value "
                    + "whose first line is a valid tag, and the comment above it credits a holder that is not "
                    + "this line");
        }
        // Parsed, not only grepped: every edit above is inside a `run: |` block, and a YAML file
        // this workflow cannot load is a deploy that never starts.
        if (OpsYaml.map(OpsYaml.map(OpsYaml.parse(WORKFLOW).get("jobs")).get("deploy")).get("steps") == null) {
            offenders.add("deploy.yml does not parse as YAML with a jobs.deploy.steps list");
        }
        var deadline = Pattern.compile("DEADLINE=\\$\\(\\( SECONDS \\+ (\\d+) \\)\\)").matcher(yaml);
        if (!deadline.find() || Integer.parseInt(deadline.group(1)) < 180 + 90 + 20 + 300) {
            offenders.add("the poll deadline is missing or below verify's worst case plus pull/up headroom "
                    + "(180 s app + 90 s grafana + 20 s settle + 300 s)");
        }
        if (!yaml.contains("still running: Status=")) {
            offenders.add("a still-running command no longer leaves a line, so a slow deploy reads like a hung one");
        }
        if (!yaml.contains("--query \"$1\" --output text")
                || !yaml.contains("for CHANNEL in StandardOutputContent StandardErrorContent")) {
            offenders.add("the failure bodies are not read as text, one channel per call (one JSON blob "
                    + "escapes every newline of the refusal)");
        }
        int validate = yaml.indexOf("^v?[0-9]+(\\.[0-9]+)+$");
        int send = yaml.indexOf("aws ssm send-command");
        if (validate < 0 || send < 0 || validate > send) {
            offenders.add("HEAD_REF is not validated against ^v?[0-9]+(\\.[0-9]+)+$ BEFORE send-command");
        }
        if (!yaml.contains("EXPECTED_APP_VERSION='\"$EXPECTED_APP_VERSION\"' bash $SYNC/ops/deploy/apply-config.sh")) {
            offenders.add("the validated version is not handed to apply-config.sh as EXPECTED_APP_VERSION");
        }
        if (Pattern.compile("\\$\\{\\{[^}]*head_branch[^}]*\\}\\}").matcher(yaml).results().count() != 1) {
            offenders.add("head_branch is interpolated with ${{ }} somewhere other than the env block — a ref name "
                    + "must reach the runner's shell only through the environment");
        }
        assertThat(offenders).withFailMessage(CHECKLIST + "\n.github/workflows/deploy.yml: " + offenders).isEmpty();
    }

    // --- harness ---------------------------------------------------------------------------

    /** A scratch box, the release tree applied to it, and the stubs on its PATH. */
    private record Deployment(Path box, Path src, Path bin, Path dockerLog, Path textfile, Path state) {
        Path promPath() {
            return textfile.resolve("hamstrack_deploy_verify.prom");
        }
    }

    /**
     * A run's result plus what it needs to be asked about afterwards.
     *
     * <p>The two channels are captured SEPARATELY since HD-299's fix loop, because which one a
     * line landed in is now load-bearing: SSM truncates stdout at 24 000 characters and stderr
     * at 8 000, and the applier mirrors its refusal and its final summary to stderr so a
     * truncated stdout still carries the conclusion. {@code output()} is both, so every
     * assertion written against the merged stream still reads the same.
     */
    private record Run(int exit, String stdout, String stderr, String dockerCalls, Deployment deployment) {
        String output() {
            return stdout + stderr;
        }
    }

    private Deployment deployment(String name) throws IOException {
        return deployment(name, "OTHER=1\n", null);
    }

    private Deployment deployment(String name, String envFile, String stamp) throws IOException {
        var root = work.resolve(name);
        var box = root.resolve("box");
        var src = root.resolve("release");
        var bin = root.resolve("bin");
        var textfile = root.resolve("textfile");
        var state = root.resolve("state");
        Files.createDirectories(box);
        Files.createDirectories(bin);
        Files.createDirectories(state);

        write(src.resolve("ops/deploy/synced-paths.txt"), "docker-compose.prod.yml\nops/\n");
        // The RAW file text, with the interpolation the real one carries. It is a second
        // comparand and not decoration: check 1 compares "declares a ceiling in the file" against
        // "has a ceiling in the resolved model", and with `services: {}` here the first half was
        // always false and the comparison could never fire.
        write(src.resolve("docker-compose.prod.yml"), """
                services:
                  app:
                    image: ghcr.io/x/hamstrack:${APP_IMAGE_TAG:-latest}
                    mem_limit: ${APP_MEMORY_LIMIT:-1g}
                  grafana:
                    image: grafana/grafana:11.5.2
                    mem_limit: 1g
                """);
        // The drift script the deploy syncs and runs at step 9 — here a fake steered by FAKE_*,
        // writing the .prom shape the real one writes (drift.sh write_metrics).
        write(src.resolve("ops/drift/hamstrack-config-drift.sh"), """
                #!/usr/bin/env bash
                [ -z "${FAKE_SKIP_WRITE:-}" ] || exit 0
                mkdir -p "$CONFIG_DRIFT_TEXTFILE_DIR"
                ts="${FAKE_DRIFT_TS:-$(date +%s)}"
                {
                  echo "hamstrack_config_drift{scope=\\"files\\"} ${FAKE_FILES:-0}"
                  echo "hamstrack_config_drift{scope=\\"containers\\"} ${FAKE_CONTAINERS:-0}"
                  echo "hamstrack_config_drift{scope=\\"installed-ops\\"} ${FAKE_INSTALLED:-0}"
                  echo "hamstrack_config_drift{scope=\\"edge-body-limit\\"} ${FAKE_EDGE:-0}"
                  echo "hamstrack_config_deployed_info{sha=\\"${FAKE_SHA:-testsha}\\"} 1"
                  echo "hamstrack_config_check_timestamp_seconds $ts"
                } > "$CONFIG_DRIFT_TEXTFILE_DIR/hamstrack_config.prom"
                echo "fake drift: published"
                """);
        // …and on the box as well, so a --verify-only case against a box no deploy has applied
        // (a moved pin) still has a step 9 to read back.
        Files.createDirectories(box.resolve("ops/drift"));
        Files.copy(src.resolve("ops/drift/hamstrack-config-drift.sh"), box.resolve("ops/drift/hamstrack-config-drift.sh"));
        write(box.resolve(".env"), envFile);
        if (stamp != null) {
            write(box.resolve(".deployed-image-tag"), stamp + "\n");
        }

        // `docker`: answers the read-only invocations from STUB_* and records every call.
        // `cid-<service>` keeps the container-name mapping legible; `ps -q` of a service named
        // in STUB_DOWN is empty, which is how "declared and not running" is produced.
        writeStub(bin.resolve("docker"), """
                #!/usr/bin/env bash
                printf '%s\\n' "$*" >> "$DOCKER_LOG"
                all=" $* "
                last="${@: -1}"
                case "$all" in
                  *" compose "*" config --services "*) printf '%s\\n' $STUB_SERVICES; exit 0 ;;
                  *" compose "*" config -q "*)
                    if [ -n "${STUB_CONFIG_Q_EXIT:-}" ]; then
                      printf '%s\\n' "${STUB_CONFIG_Q_ERR:-stub: config refused}" >&2
                      exit "$STUB_CONFIG_Q_EXIT"
                    fi
                    exit 0 ;;
                  *" compose "*" config "*) printf '%s\\n' "$STUB_CONFIG"; exit 0 ;;
                  *" up -d --dry-run "*) printf '%s\\n' "$STUB_PLAN" >&2; exit "${STUB_PLAN_EXIT:-0}" ;;
                  *" compose "*" ps -q "*)
                    if [ -n "${STUB_PS_EXIT:-}" ]; then
                      printf '%s\\n' "${STUB_PS_ERR:-stub: the daemon did not answer (STUB_PS_EXIT)}" >&2
                      exit "$STUB_PS_EXIT"
                    fi
                    case " ${STUB_DOWN:-} " in *" $last "*) exit 0 ;; esac
                    printf 'cid-%s\\n' "$last"; exit 0 ;;
                  *" compose "*" pull "*)
                    [ -z "${STUB_PULL_EXIT:-}" ] || printf '%s\\n' "${STUB_PULL_ERR:-stub: pull failed}" >&2
                    exit "${STUB_PULL_EXIT:-0}" ;;
                  *" up -d --remove-orphans "*)
                    [ -z "${STUB_UP_EXIT:-}" ] || printf '%s\\n' "${STUB_UP_ERR:-stub: up failed}" >&2
                    exit "${STUB_UP_EXIT:-0}" ;;
                  *" compose "*" exec -T app wget "*)
                    n=$(cat "$STUB_STATE/meta" 2>/dev/null || echo 0); n=$((n + 1)); printf '%s' "$n" > "$STUB_STATE/meta"
                    [ "$n" -gt "${STUB_META_NOT_YET:-0}" ] || exit 1
                    printf '%s' "$STUB_META"; exit 0 ;;
                  *" logs --since "*) printf '%s\\n' "$STUB_GRAFANA_LOGS"; exit "${STUB_LOGS_EXIT:-0}" ;;
                  *" inspect -f "*)
                    case "$3" in
                      *HostConfig.Memory*) printf '%s\\n' "${STUB_MEMORY:-1073741824}" ;;
                      *Config.Image*) printf '%s\\n' "${STUB_IMAGE-ghcr.io/x/hamstrack:0.17.0}" ;;
                      *Config.Env*) printf '%s\\n' "$STUB_ENV_LINES" ;;
                      *image.revision*) printf '%s\\n' "$STUB_REVISION" ;;
                      *RestartCount*)
                        n=$(cat "$STUB_STATE/restarts" 2>/dev/null || echo 0); n=$((n + 1)); printf '%s' "$n" > "$STUB_STATE/restarts"
                        set -- $STUB_RESTARTS; [ "$n" -le "$#" ] || n=$#; shift $((n - 1)); printf '%s\\n' "$1" ;;
                      *State.StartedAt*) printf '%s\\n' "2026-09-10T08:00:00.000000000Z" ;;
                      *State.Running*) printf '%s\\n' "${STUB_RUNNING:-true}" ;;
                      *) printf '\\n' ;;
                    esac
                    exit 0 ;;
                esac
                exit 0
                """);
        // The default body is the one measured on grafana:11.5.2 (pretty-printed, so the
        // script's whitespace normalisation is exercised too).
        writeStub(bin.resolve("curl"), """
                #!/usr/bin/env bash
                printf 'curl %s\\n' "$*" >> "$DOCKER_LOG"
                h="${STUB_HEALTH:-}"
                if [ -z "$h" ]; then
                  h='{
                  "database": "ok",
                  "version": "11.5.2",
                  "commit": "598e0338d5374d6bc404b02a58094132c5eeceb8"
                }'
                fi
                printf '%s' "$h"
                exit "${STUB_HEALTH_EXIT:-0}"
                """);
        writeStub(bin.resolve("flock"), "#!/usr/bin/env bash\nexit 0\n");

        return new Deployment(box, src, bin, root.resolve("docker.log"), textfile, state);
    }

    private Run run(Deployment d, Map<String, String> extraEnv, String... flags) throws Exception {
        return runFrom(d, extraEnv, posix(d.src()), flags);
    }

    private Run runFrom(Deployment d, Map<String, String> extraEnv, String source, String... flags) throws Exception {
        ScriptHarness.assumeWithWitness("apply-config-verify", bash != null,
                "no bash on PATH (and no Git for Windows bash.exe) — the tests that drive the real "
                        + "ops/deploy/apply-config.sh run on CI and on any POSIX machine, and skip only "
                        + "on a Windows box without Git Bash");
        assertThat(SCRIPT)
                .withFailMessage("ops/deploy/apply-config.sh was not found at %s — this test drives the "
                        + "repository's own copy, so it must run from the module root", SCRIPT.toAbsolutePath())
                .isRegularFile();
        ScriptHarness.assertStubsAreExecutable(d.bin());
        // Each run starts its stub counters afresh; the docker log accumulates on purpose.
        Files.deleteIfExists(d.state().resolve("meta"));
        Files.deleteIfExists(d.state().resolve("restarts"));

        var cmd = new ArrayList<String>();
        cmd.add(bash);
        cmd.add(posix(SCRIPT));
        cmd.add(source);
        cmd.add(posix(d.box()));
        boolean verifyOnly = List.of(flags).contains("--verify-only");
        if (!verifyOnly) {
            cmd.add("testsha");
        }
        cmd.addAll(List.of(flags));

        var stdoutFile = Files.createTempFile(work, "stdout", ".log");
        var stderrFile = Files.createTempFile(work, "stderr", ".log");
        var pb = new ProcessBuilder(cmd)
                .redirectOutput(stdoutFile.toFile())
                .redirectError(stderrFile.toFile());
        var env = new LinkedHashMap<>(pb.environment());
        env.put("PATH", d.bin().toAbsolutePath() + java.io.File.pathSeparator + env.getOrDefault("PATH", ""));
        env.put("DOCKER_LOG", posix(d.dockerLog()));
        env.put("COMPOSE_FILES", "docker-compose.prod.yml");
        env.put("CONFIG_DRIFT_TEXTFILE_DIR", posix(d.textfile()));
        env.put("STUB_STATE", posix(d.state()));
        env.put("STUB_SERVICES", "app grafana");
        env.put("STUB_CONFIG", CONFIG);
        env.put("STUB_PLAN", PLAN);
        env.put("STUB_META", "{\"publicLandingEnabled\":true,\"version\":\"0.17.0-12-gtestsha\"}");
        env.put("STUB_REVISION", "testsha");
        env.put("STUB_ENV_LINES", "DB_URL=jdbc:whatever\nSPRING_PROFILES_ACTIVE=cloud\nGF_SECURITY_ADMIN_PASSWORD=whatever\nPATH=/bin");
        env.put("STUB_RESTARTS", "0 0");
        env.put("STUB_GRAFANA_LOGS", GRAFANA_SANE_LOGS);
        env.put("VERIFY_POLL_SECONDS", "0");
        env.put("VERIFY_APP_TIMEOUT_SECONDS", "5");
        env.put("VERIFY_GRAFANA_TIMEOUT_SECONDS", "2");
        env.put("VERIFY_GRAFANA_SETTLE_SECONDS", "0");
        env.remove("EXPECTED_APP_VERSION");
        env.remove("APP_IMAGE_TAG");
        env.putAll(extraEnv);
        pb.environment().clear();
        pb.environment().putAll(env);

        var p = pb.start();
        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        var out = ScriptHarness.read(stdoutFile);
        var err = ScriptHarness.read(stderrFile);
        assertThat(finished)
                .withFailMessage("apply-config.sh did not finish within 120s — output so far:\n%s\n%s", out, err)
                .isTrue();
        return new Run(p.exitValue(), out, err, ScriptHarness.read(d.dockerLog()), d);
    }

    /** {@link Result}'s shape for {@link ScriptHarness#expect}, from a {@link Run}. */
    private static void expect(List<String> failures, String what, boolean ok, Run r) {
        ScriptHarness.expect(failures, what, ok, new Result(r.exit(), r.output()));
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (String line : haystack.split("\n")) {
            if (line.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    private static String prom(Deployment d) throws IOException {
        return ScriptHarness.read(d.promPath());
    }

    private static int gauge(Deployment d, String series) throws IOException {
        for (String line : prom(d).split("\n")) {
            if (line.startsWith(series + " ")) {
                return Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1).strip());
            }
        }
        return -1;
    }

    private static int checkGauge(Deployment d, String check) throws IOException {
        return gauge(d, "hamstrack_deploy_verify_check_ok{check=\"" + check + "\"}");
    }
}
