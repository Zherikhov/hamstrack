package com.hamstrack.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-262 — the root-volume snapshot collector and the three rules that read it must
 * agree, and the properties that make the collector trustworthy must be mechanised rather
 * than reviewed.</strong>
 *
 * <p><strong>Why this class exists at all.</strong> The ticket is about a durability layer
 * that was watched by a check nobody could see failing. Backup layer 3 ran green through two
 * consecutive outages because every signal available described the <em>mechanism</em> and not
 * the <em>artefact</em>. A monitor written to fix that, and then itself only reviewed, is the
 * same defect one layer further down — so the properties below are the ones whose silent loss
 * would turn this collector back into a green light over a broken layer.
 *
 * <p><strong>What it can and cannot prove.</strong> It reads
 * {@code ops/snapshot/hamstrack-volume-snapshot.sh} and
 * {@code observability/grafana/provisioning/alerting/rules.yml}, and it <em>executes</em> the
 * script for the refusals that happen above the lock and above every external dependency.
 * <strong>It cannot prove the collector resolves the right volume</strong> — that needs IMDS
 * and the EC2 API, i.e. an EC2 instance, and it is the acceptance test in
 * {@code docs/ops-prod-hardening.md} §6.9. Nothing here pretends otherwise: no fixture in this
 * class stands in for AWS, because a test that pretended to have exercised that path would be
 * worse than the honest gap.
 *
 * <p><strong>The stage rule was a comment, and a comment is not a mechanism.</strong>
 * {@code rules.yml} has carried "if you add a stage to the .prom, add its arm here in the same
 * commit" above the backup rules since HD-187, enforced by nobody. An unnamed stage falls into
 * the {@code else} arm and is described as a different failure with a different first move,
 * which is worse than no text at all — the reader acts on it.
 */
class VolumeSnapshotCollectorContractTest {

    private static final Path SCRIPT = Path.of("ops", "snapshot", "hamstrack-volume-snapshot.sh");
    private static final Path RULES =
            Path.of("observability", "grafana", "provisioning", "alerting", "rules.yml");

    /** The rule whose summary must branch over exactly the stages the script emits. */
    private static final String STAGE_RULE_UID = "hamstrack-snapshot-check-failing";

    /**
     * The stage literals the collector actually PUBLISHES, matched on the escaped quotes of a
     * double-quoted {@code echo}. Prose in this file's own comments spells the label without
     * backslashes for exactly that reason: a check that a comment can satisfy checks nothing.
     */
    private static final Pattern EMITTED_STAGE = Pattern.compile(
            "hamstrack_volume_snapshot_check_status\\{stage=\\\\\"([a-z-]+)\\\\\"}");

    /** The arms a Go template names explicitly; whatever is left over is the {@code else}. */
    private static final Pattern NAMED_ARM =
            Pattern.compile("eq\\s+\\$labels\\.stage\\s+\"([a-z-]+)\"");

    private static final String CHECKLIST = """

            ops/snapshot/hamstrack-volume-snapshot.sh publishes backup layer 3's OUTCOME check --
            "is there a recent restorable image of the volume this box is running on" -- and the
            three VolumeSnapshot* rules in rules.yml read it. HD-262 exists because that layer ran
            green through two consecutive outages while protecting nothing, so every property
            asserted here is one whose silent loss restores that state:

              * A TIMESTAMP, NEVER A PRE-COMPUTED AGE. An age gauge frozen in a .prom nobody
                rewrites reads as permanently fresh; a frozen timestamp ages by itself, so a dead
                collector converges on the alert instead of hiding behind it.

              * NO STORED VOLUME ID, IN ANY FORM. The volume is resolved from the instance every
                run. A pinned id is the same class of stored fact as the Backup=hamstrack tag
                whose staleness caused outage 1 -- right on the day it is written, silently wrong
                from the moment a volume is replaced, and a green light over the exact failure.

              * NOT INDEX 0, ANYWHERE. BlockDeviceMappings[0] is not guaranteed to be the root
                device and Attachments[0] is not guaranteed to be this instance's attachment.
                Both are the same bug wearing a different hat, and docs/ops-prod-hardening.md
                carried a latent copy of the first one until this ticket.

              * THE EXIT TRAP IS INSTALLED ABOVE EVERY VALIDATION. hamstrack-backup.sh learned
                this the expensive way: with the trap below the config checks, a typo produced a
                failed run whose .prom went on insisting the last one succeeded.

              * ADD A STAGE TO THE .prom, ADD ITS ARM IN THE SAME COMMIT. The summary's if/else
                is TOTAL, so an unnamed stage is described as a different failure with a
                different first move. Exactly one stage may be unnamed -- the one the `else` arm
                describes -- and its name must still appear in the text, or the reader is told
                the wrong thing under pressure.

              * THE CLI'S RAW STDERR IS NEVER ECHOED. An AccessDenied quotes the assumed-role ARN
                arn:aws:sts::<account>:assumed-role/... , and the account id is the one value
                that must not be printed. What goes to the journal is the call and its error
                code.

              * A VALUE OFF THE WIRE DOES NOT CHOOSE HOW MANY LINES IT OCCUPIES. Everything IMDS
                answers is quoted into a refusal an operator reads under pressure, and a newline
                in it FORGES A JOURNAL LINE -- the same primitive HD-275 closed one layer down at
                the log sink. printable() is the belt; these tests are what force it to be worn.

              * THE IMDSv2 TOKEN IS VALIDATED BEFORE IT IS WRITTEN INTO A HEADER FILE, and the
                proof is that no metadata request happens at all when it is malformed. A newline
                in the token forges a second HTTP header on EVERY later call, so a check that
                only looked at the journal would pass over a script that had already sent it.

              * THE `none` BRANCH SURVIVES A LOCK IT CANNOT OPEN. What makes that true is the
                `exec 9>` sitting inside a TESTED CONDITION -- not the braces around it and not
                the 2>/dev/null. Straightening it into an `if ...; then` body restores a critical
                bug that is invisible on every box where /var/lock is writable, i.e. on CI.

            Design: docs/design/root-volume-snapshot-age-proposal.md, ADR-0037, ADR-0038.
            Runbook: docs/ops-prod-hardening.md 6.9.
            """;

    private static String bash;

    @TempDir
    Path work;

    /** Distinct names for the rewritten copies: one test makes several in a loop. */
    private int copies;

    @BeforeAll
    static void locateBash() {
        bash = findBash();
    }

    // --- the collector and the rules agree ------------------------------------------

    /**
     * <strong>Exactly one stage may be unnamed, and it is the one the {@code else} describes.</strong>
     * Comparing the two sets for equality would be wrong: with a total {@code if/else} the last
     * stage is deliberately not named by an {@code eq}. So the property is the conjunction — no
     * arm for a stage that does not exist, and no more than one stage without an arm.
     */
    @Test
    void everyStageTheCollectorEmitsHasAnArmInTheAlertThatDescribesIt() throws IOException {
        var stages = emittedStages();
        var summary = stageRuleSummary();

        var named = new LinkedHashSet<String>();
        Matcher m = NAMED_ARM.matcher(summary);
        while (m.find()) {
            named.add(m.group(1));
        }

        // Tripwires: without these, "nothing offends" is perfectly true of an empty scan.
        assertThat(stages)
                .withFailMessage(CHECKLIST + "\nNo stage literal was found in " + SCRIPT
                        + ". The extraction looks for hamstrack_volume_snapshot_check_status with"
                        + " an escaped-quote stage label, i.e. the form a double-quoted echo"
                        + " produces. If the emission was rewritten (a printf, a heredoc, a"
                        + " variable), this scan has gone blind and every assertion below it"
                        + " passes vacuously -- teach it the new form, do not delete it.")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(named)
                .withFailMessage(CHECKLIST + "\nVolumeSnapshotCheckFailing's summary names no stage"
                        + " with `eq $labels.stage`, so its branch is not a branch. Either the"
                        + " template was flattened -- in which case one text now describes two"
                        + " failures with opposite first moves -- or this scan stopped"
                        + " recognising the form.")
                .isNotEmpty();

        assertThat(summary)
                .withFailMessage(CHECKLIST + "\nVolumeSnapshotCheckFailing's summary has no `else`"
                        + " arm, so its branch is not total: a stage it does not name renders as"
                        + " no text at all.")
                .contains("{{ else }}");

        var phantom = new LinkedHashSet<>(named);
        phantom.removeAll(stages);
        assertThat(phantom)
                .withFailMessage(CHECKLIST + "\nThe alert branches on stage(s) the collector never"
                        + " emits: " + phantom + ". Either the .prom stopped publishing them --"
                        + " in which case the arm is dead text -- or the arm was written for a"
                        + " stage that was never added. Emitted: " + stages)
                .isEmpty();

        var unnamed = new LinkedHashSet<>(stages);
        unnamed.removeAll(named);
        assertThat(unnamed)
                .withFailMessage(CHECKLIST + "\nExactly one stage may be left to the `else` arm."
                        + " These have no arm of their own: " + unnamed + ". With more than one,"
                        + " the `else` text describes whichever of them fired -- a reader sent to"
                        + " the EC2 API for a metadata problem, or the reverse. Emitted: "
                        + stages + ", named: " + named)
                .hasSize(1);

        // The stage the `else` covers must still be NAMED in the text. An arm that describes a
        // failure without saying which stage it is about leaves the reader to infer it from a
        // notification that carries the label they cannot see.
        String elseStage = unnamed.iterator().next();
        assertThat(summary)
                .withFailMessage(CHECKLIST + "\nThe `else` arm covers stage '" + elseStage + "'"
                        + " and the summary never uses that word, so the one instance that"
                        + " reaches it is told what to do without being told what failed.")
                .contains(elseStage);
    }

    /**
     * The three rules exist, under the uids the runbook and {@code docs/observability.md} name.
     * Their LENGTH is not re-asserted here: {@code GrafanaProvisioningContractTest} measures
     * every uid in the tree against Grafana's 40-character store limit, and a member-shaped
     * copy of a category assertion goes stale one rule before the category does.
     */
    @Test
    void theThreeRulesThisCollectorFeedsArePresentAndUnaggregated() throws IOException {
        var byUid = alertRulesByUid();
        for (String uid : List.of("hamstrack-volume-snapshot-stale", STAGE_RULE_UID,
                "hamstrack-snapshot-check-stale")) {
            assertThat(byUid)
                    .withFailMessage(CHECKLIST + "\n" + RULES + " no longer declares " + uid
                            + ". The collector publishes a metric nothing reads, which is the"
                            + " silence this ticket exists to end.")
                    .containsKey(uid);
        }

        // Unaggregated, and it must stay that way: both summaries branch on a label, and any
        // aggregation that drops it renders the branch against an instance that has none.
        var aggregations = Pattern.compile("\\b(sum|max|min|avg|count)\\s*\\(");
        for (String uid : List.of("hamstrack-volume-snapshot-stale", STAGE_RULE_UID)) {
            String expr = promExpressionOf(byUid.get(uid));
            assertThat(aggregations.matcher(expr).find())
                    .withFailMessage(CHECKLIST + "\n" + uid + " aggregates its query (" + expr
                            + "). Its summary reads $labels, so an aggregation that drops the"
                            + " label renders the text against an instance with none.")
                    .isFalse();
        }
    }

    // --- the properties of the collector itself --------------------------------------

    /**
     * The absence that is the design. Phrased over the whole file, and over the CONCEPT rather
     * than the one spelling: any environment variable, any config key, any constant that could
     * name a volume is the stale fact this check exists to catch.
     */
    @Test
    void nothingInTheCollectorCanPinAVolumeId() throws IOException {
        // CODE ONLY, deliberately: the script's own header explains at length that there is no
        // SNAPSHOT_VOLUME_ID and that index 0 is not the root device, and a check that a
        // WARNING trips is a check that punishes documentation. What is asserted is that no
        // executable line does any of it.
        String body = codeOf(script());
        var offenders = new ArrayList<String>();
        if (body.contains("SNAPSHOT_VOLUME_ID")) {
            offenders.add("SNAPSHOT_VOLUME_ID is read by an executable line");
        }
        // A literal id compiled in is the same fact wearing no variable at all.
        var literalVolume = Pattern.compile("vol-[0-9a-f]{8,}");
        Matcher m = literalVolume.matcher(body);
        while (m.find()) {
            offenders.add("a literal volume id (" + m.group() + ") is hard-coded");
        }
        if (body.contains("BlockDeviceMappings[0]")) {
            offenders.add("BlockDeviceMappings[0] — index 0 is not guaranteed to be the root device");
        }
        if (body.contains("Attachments[0]")) {
            offenders.add("Attachments[0] — a Multi-Attach volume has several, and the first is a coin toss");
        }
        assertThat(offenders)
                .withFailMessage(CHECKLIST + "\nStored or guessed volume identity: " + offenders)
                .isEmpty();
    }

    /**
     * Order is the property, not presence. Each of these lines is correct where it is and
     * useless one block lower: a trap under the validation publishes nothing for the failures
     * the validation produces, and a {@code flock} whose tool was never checked cannot tell
     * "someone else holds it" from "no such command".
     *
     * <p>The {@code flock} half is phrased over EVERY place the script runs one, not over the
     * first one it finds. There are two lock sites and they are guarded by different
     * mechanisms — {@code need_tool flock} above the main path, and an inline
     * {@code command -v flock} in the {@code SNAPSHOT_SOURCE=none} branch, which sits above
     * the tool checks on purpose (that branch must work on the bare-metal box that has
     * neither {@code flock} nor an AWS CLI, and its job — deleting the stale {@code .prom} —
     * is exactly the job that must not be skipped there). An index-of-the-first-occurrence
     * test read the second site as a violation of the first site's rule, which is how a
     * correct new branch trips a scan that was written when there was only one.
     */
    @Test
    void theExitTrapIsAboveEveryValidationAndNoFlockRunsUncheckedBeforeItsLock() throws IOException {
        String body = script();
        int trap = body.indexOf("trap on_exit EXIT");
        int term = body.indexOf("trap 'exit 143' TERM");
        int validation = body.indexOf("case \"$SNAPSHOT_SOURCE\" in");
        int needFlock = body.indexOf("need_tool flock");
        // The MAIN path's lock, i.e. the last one in the file: the `none` branch takes one
        // earlier and is checked separately below.
        int lock = body.lastIndexOf("exec 9>");

        var offenders = new ArrayList<String>();
        if (trap < 0) {
            offenders.add("there is no `trap on_exit EXIT`, so a failing run leaves the previous reading standing");
        }
        // The trap's whole job. A handler that stopped publishing would satisfy every ordering
        // assertion here while leaving yesterday's answer in place, which is the failure.
        if (!codeOf(body).contains("write_metrics")) {
            offenders.add("nothing calls write_metrics, so no run publishes anything at all");
        }
        if (term < 0) {
            offenders.add("there is no `trap 'exit 143' TERM`; bash runs no EXIT trap for an untrapped fatal signal, so TimeoutStartSec's SIGTERM would publish nothing");
        }
        if (validation < 0) {
            offenders.add("SNAPSHOT_SOURCE is no longer validated by a `case`, so its two branches are no longer derived from one test");
        }
        if (trap >= 0 && validation >= 0 && trap > validation) {
            offenders.add("the EXIT trap is installed BELOW the SNAPSHOT_SOURCE validation, so a bad value dies without publishing a failure");
        }
        if (needFlock < 0 || lock < 0 || needFlock > lock) {
            offenders.add("`need_tool flock` does not run above the main path's `exec 9>`; without it a host lacking util-linux takes the lock-loser branch on every run, exits 0 and never touches the metrics");
        }

        // EVERY `flock -n 9` in the file, each of which must be reachable only after SOMETHING
        // established that flock exists — `need_tool flock` above it, or an inline
        // `command -v flock` guard. This is the whole reason the ordering rule exists: a bare
        // `if ! flock -n 9` cannot distinguish exit 1 ("someone holds it") from 127 ("no such
        // command"), so on a host without util-linux every run would take the loser branch.
        // Over the CODE, never the file: both blocks that take a lock also EXPLAIN in a comment
        // above themselves why a bare `if ! flock -n 9` is wrong, and a scan of the raw text
        // reads those sentences as the very call they warn against.
        String code = codeOf(body);
        int needFlockInCode = code.indexOf("need_tool flock");
        int runs = 0;
        for (int at = code.indexOf("flock -n 9"); at >= 0; at = code.indexOf("flock -n 9", at + 1)) {
            runs++;
            boolean guarded = (needFlockInCode >= 0 && needFlockInCode < at)
                    || code.lastIndexOf("command -v flock", at) >= 0;
            if (!guarded) {
                offenders.add("a `flock -n 9` at offset " + at + " runs with nothing above it"
                        + " having established that flock exists — neither `need_tool flock` nor"
                        + " an inline `command -v flock`. Exit 127 then reads as 'someone else"
                        + " holds the lock' and the run stands down for ever");
            }
        }
        if (runs == 0) {
            offenders.add("no `flock -n 9` was found at all, so runs are no longer serialised"
                    + " — or this scan stopped recognising the form and every check above it is"
                    + " now vacuous");
        }

        // THE TRAP ITSELF RUNS UNDER `set -e`, so every step before the publish must be
        // best-effort. A failing `rm -f` in a cleanup helper — read-only /tmp, a directory that
        // stopped being writable — aborts the handler WHERE IT STANDS, never reaches
        // write_metrics, and leaves the previous run's reading in place: the exact outcome the
        // trap's position above every validation exists to forbid, arriving by the back door.
        // The publish is therefore LAST and UNCONDITIONAL, and everything before it carries
        // `|| true`. Asserted rather than reviewed because the cost of getting it wrong is one
        // missing operator and the symptom is indistinguishable from a healthy run.
        Matcher handler = Pattern.compile("on_exit\\(\\)\\s*\\{([^}]*)}").matcher(code);
        if (!handler.find()) {
            offenders.add("`on_exit()` is no longer a single-line function this scan can read, so"
                    + " the best-effort/publish-last property below is now unchecked — teach it the"
                    + " new form rather than dropping it");
        } else {
            var steps = new ArrayList<String>();
            for (String step : handler.group(1).split(";")) {
                if (!step.isBlank()) {
                    steps.add(step.trim());
                }
            }
            if (steps.isEmpty() || !steps.getLast().startsWith("write_metrics")) {
                offenders.add("`on_exit()` does not END with write_metrics (" + steps + "). The"
                        + " publish must be the last thing the handler does, or a step added after"
                        + " it can fail and take the publish's effect with it");
            }
            for (String step : steps.subList(0, Math.max(0, steps.size() - 1))) {
                if (!step.contains("|| true")) {
                    offenders.add("`on_exit()` runs '" + step + "' before the publish without"
                            + " `|| true`. Under `set -e` a failing cleanup aborts the trap before"
                            + " write_metrics, so yesterday's reading stands — the failure the"
                            + " trap exists to prevent, reached from inside the trap");
                }
            }
        }

        assertThat(offenders)
                .withFailMessage(CHECKLIST + "\nOrdering: " + offenders)
                .isEmpty();
    }

    /**
     * The publish is atomic, world-readable and self-sweeping. node-exporter runs as
     * {@code nobody}: a metric it cannot read is an alert that never fires, and
     * {@code noDataState: OK} makes that silent.
     */
    @Test
    void theMetricsFileIsWrittenAtomicallyAndReadably() throws IOException {
        String body = script();
        var offenders = new ArrayList<String>();
        if (!body.contains("chmod 0644 \"$tmp\"")) {
            offenders.add("the .prom is not explicitly chmod 0644; under a tighter umask node-exporter cannot read it and every series here goes off the air, silently");
        }
        if (!body.contains("mv -f \"$tmp\" \"$out\"")) {
            offenders.add("the .prom is not moved into place atomically, so a scrape can see a half-written file");
        }
        if (!body.contains("hamstrack_volume_snapshot.prom.[0-9]*")) {
            offenders.add("leftover *.prom.<pid> files from a killed run are never swept");
        }
        assertThat(offenders)
                .withFailMessage(CHECKLIST + "\nPublication: " + offenders)
                .isEmpty();
    }

    /**
     * Two AWS calls, both carrying an explicit {@code --region}, and neither ever showing the
     * caller what the CLI said. The region is not a convenience: the {@code ec2-snapshot-read}
     * policy is conditioned on {@code aws:RequestedRegion}, so an inherited one is denied.
     */
    @Test
    void everyAwsCallNamesItsRegionAndNoCallEverEchoesTheCliStderr() throws IOException {
        String joined = script().replace("\\\n", " ");
        var offenders = new ArrayList<String>();

        int calls = 0;
        for (String line : joined.lines().toList()) {
            String code = stripFullLineComment(line);
            if (!code.contains("aws_ec2 describe-")) {
                continue;
            }
            calls++;
            if (!code.contains("--region \"$REGION\"")) {
                offenders.add("an aws_ec2 call does not pass --region \"$REGION\": " + code.trim());
            }
        }
        assertThat(calls)
                .withFailMessage(CHECKLIST + "\nNo aws_ec2 call site was found, so this scan"
                        + " checked nothing. The collector makes exactly two Describe calls; if"
                        + " they were renamed, teach this test the new name.")
                .isGreaterThanOrEqualTo(2);

        // The stderr file may be READ by the error-code extractor and must never be printed.
        for (String line : joined.lines().toList()) {
            String code = stripFullLineComment(line);
            if (code.contains("AWS_ERR_FILE") && (code.contains("cat ") || code.contains(">&2"))) {
                offenders.add("the CLI's raw stderr reaches the journal: " + code.trim()
                        + " — an AccessDenied quotes the assumed-role ARN, which carries the account id");
            }
        }

        // A write anywhere but the textfile directory would be state, and this collector holds
        // none: every run recomputes its answer from scratch, which is what stops it inheriting
        // the staleness it exists to catch.
        for (String line : joined.lines().toList()) {
            String code = stripFullLineComment(line);
            // The operator must START a token, or `${VOLUME:-<unresolved>}` reads as a
            // redirection into `}`. A redirect is `[fd]>` or `[fd]>>` at a token boundary.
            Matcher r = Pattern.compile("(?:^|\\s)[0-9]?>>?\\s*(\"?[^\\s|;&)]+)").matcher(code);
            while (r.find()) {
                String target = r.group(1);
                boolean allowed = target.equals("\"$tmp\"")
                        || target.equals("\"$AWS_ERR_FILE\"")
                        // The IMDSv2 token's header file: curl reads it with `-H @file` so the
                        // token never appears in an argv (/proc/<pid>/cmdline is world-readable
                        // and node-exporter runs with `pid: host`). Scratch, not state — 0600,
                        // under the unit's PrivateTmp=, removed by the EXIT trap, and its
                        // contents live 60 seconds. Every other entry in this list is here for
                        // the same reason: the property is "carries nothing between runs", not
                        // "writes exactly one file".
                        || target.equals("\"$IMDS_HDR_FILE\"")
                        || target.equals("/dev/null")
                        || target.startsWith("&")
                        || target.equals("/var/lock/hamstrack-volume-snapshot.lock");
                if (!allowed) {
                    offenders.add("a write to '" + target + "' — this collector keeps no state "
                            + "between runs, and a file outside the textfile directory is state: "
                            + code.trim());
                }
            }
        }

        assertThat(offenders)
                .withFailMessage(CHECKLIST + "\nAWS calls and writes: " + offenders)
                .isEmpty();
    }

    // --- the refusals, executed rather than read --------------------------------------

    /**
     * <strong>One validated value, both branches derived from it.</strong> Two independent
     * equality tests are not complementary, and this is the shape that once let
     * {@code BACKUP_TARGET} take the upload path while never emitting the {@code upload}
     * series. Both refusals run for real: they sit above the lock and above every external
     * dependency, so they need no EC2, no IMDS and no AWS CLI.
     *
     * <p>The CR case is the one worth watching in the output rather than only in the
     * assertion: the carriage return rewinds the terminal, so the value prints as if it were
     * clean. That is precisely why the message names the CR instead of relying on the reader
     * seeing it.
     */
    @Test
    void anySourceOtherThanTheTwoWordsIsRefusedAndTheRefusalIsPublished() throws Exception {
        assumeBash();

        // An EMPTY value is deliberately NOT in this list: `${SNAPSHOT_SOURCE:-ebs}` reads it as
        // unset, so an empty setting means the default, which is `ebs`, which is the safe
        // direction. The dangerous inputs are the ones that LOOK configured.
        for (String bad : List.of("EBS", "ebs\r", "ebs ", "off")) {
            var run = runCollector(Map.of("SNAPSHOT_SOURCE", bad));
            assertThat(run.exit())
                    .withFailMessage(CHECKLIST + "\nSNAPSHOT_SOURCE='" + visible(bad)
                            + "' was ACCEPTED. A value that is not exactly `ebs` or `none` must"
                            + " stop the run: the danger is not the wrong branch, it is NEITHER"
                            + " branch taken while the unit looks configured.\n" + run.output())
                    .isNotZero();
            assertThat(run.output())
                    .withFailMessage(CHECKLIST + "\nThe refusal of SNAPSHOT_SOURCE='"
                            + visible(bad) + "' does not name the CR. A trailing carriage return"
                            + " rewinds the line, so the offending value prints as if it were"
                            + " clean and the operator reads 'got ebs' and concludes the check is"
                            + " broken.\n" + run.output())
                    .contains("CR");
            assertThat(promOf(run))
                    .withFailMessage(CHECKLIST + "\nA refused run published no pessimistic"
                            + " zeroes, so the previous run's answer stands and"
                            + " VolumeSnapshotCheckFailing never fires. The EXIT trap must be"
                            + " installed above this validation.\n" + run.output())
                    .contains("hamstrack_volume_snapshot_check_status{stage=\"resolve\"} 0")
                    .contains("hamstrack_volume_snapshot_check_status{stage=\"describe\"} 0")
                    .contains("hamstrack_volume_snapshot_check_timestamp_seconds ");
        }
    }

    /**
     * <strong>{@code none} must REMOVE the previous run's file, not merely decline to write
     * one.</strong> An off switch is only reached by a box that has already been publishing —
     * the default is {@code ebs}, so the series exists before anybody sets {@code none} — and
     * node-exporter goes on scraping whatever is in the textfile directory for ever. A branch
     * that exits without touching the file therefore FREEZES it: {@code check_timestamp}
     * stops advancing and {@code VolumeSnapshotCheckStale} fires at 3 h and never clears, and
     * a frozen {@code newest_timestamp} takes {@code VolumeSnapshotStale} (critical) with it
     * at 30 h. The escape hatch written for "a self-hoster who moved off EC2" would hand that
     * exact operator a permanent critical alert.
     *
     * <p>The analogy to {@code BACKUP_TARGET=local} only holds for a branch that removes:
     * {@code hamstrack-backup.sh} rewrites its whole {@code .prom} on every run and OMITS the
     * {@code upload} series, so flipping that switch actively takes the series off the air.
     * Not writing removes nothing.
     *
     * <p>Hence the stale file below. Against a fresh {@code @TempDir} this test asserted
     * "the file is absent", which is true whether the branch removes it or ignores it — the
     * assertion could not see the bug it existed to prevent. The fixture is the seal.
     */
    @Test
    void theExplicitOffSwitchRemovesThePreviousRunsFileRatherThanFreezingIt() throws Exception {
        assumeBash();

        // What a box that has been publishing for months has on disk at the moment somebody
        // sets `none` — timestamps from the last `ebs` run, which never get any newer.
        Path prom = work.resolve("hamstrack_volume_snapshot.prom");
        Files.writeString(prom, """
                # HELP hamstrack_volume_snapshot_newest_timestamp_seconds x
                # TYPE hamstrack_volume_snapshot_newest_timestamp_seconds gauge
                hamstrack_volume_snapshot_newest_timestamp_seconds{volume="vol-0123456789abcdef0"} 1756000000
                hamstrack_volume_snapshot_check_status{stage="resolve"} 1
                hamstrack_volume_snapshot_check_status{stage="describe"} 1
                hamstrack_volume_snapshot_check_timestamp_seconds 1756000000
                """, StandardCharsets.UTF_8);
        // A temp file from a run that was killed between its redirect and its `mv`. Never
        // scraped (node-exporter reads *.prom only), but the off branch is the last run there
        // will ever be, so it is also the last chance to sweep one.
        Files.writeString(work.resolve("hamstrack_volume_snapshot.prom.4242"), "partial",
                StandardCharsets.UTF_8);

        var run = runCollector(Map.of("SNAPSHOT_SOURCE", "none"));
        assertThat(run.exit())
                .withFailMessage(CHECKLIST + "\nSNAPSHOT_SOURCE=none did not exit 0.\n" + run.output())
                .isZero();
        assertThat(Files.exists(prom))
                .withFailMessage(CHECKLIST + "\nSNAPSHOT_SOURCE=none left the previous run's .prom"
                        + " on disk. node-exporter keeps scraping it, so the off switch does not"
                        + " turn the check off — it freezes it: check_timestamp stops advancing and"
                        + " VolumeSnapshotCheckStale fires at 3 h and never clears, and the frozen"
                        + " newest_timestamp takes the CRITICAL VolumeSnapshotStale with it at 30 h."
                        + " The branch must DELETE the file, not decline to write one: `none` is"
                        + " only ever reached by a box that has already been publishing, and the"
                        + " BACKUP_TARGET=local analogy holds only because that script rewrites its"
                        + " whole .prom every run and omits the `upload` series.\n" + run.output())
                .isFalse();
        assertThat(Files.exists(work.resolve("hamstrack_volume_snapshot.prom.4242")))
                .withFailMessage(CHECKLIST + "\nSNAPSHOT_SOURCE=none left a *.prom.<pid> temp file"
                        + " behind. The `ebs` path sweeps those under the lock on every run; the"
                        + " off branch is the last run there will ever be, so nothing else ever"
                        + " will.\n" + run.output())
                .isFalse();
        assertThat(run.output())
                .withFailMessage(CHECKLIST + "\nThe stand-down is silent in the journal too, so"
                        + " nothing on the box says why the series vanished.\n" + run.output())
                .contains("SNAPSHOT_SOURCE=none");
    }

    // --- the wire, and what a value off it may do ------------------------------------

    /**
     * <strong>Nothing IMDS answers may forge a journal line.</strong> Every one of these values
     * is quoted into a refusal that an operator reads while something is already wrong, and a
     * newline in it produces a line that looks exactly like one this script wrote — including
     * the {@code FATAL} that {@code die()} prints. That is HD-275's primitive one layer down:
     * there it was the application log sink, here it is the unit's journal.
     *
     * <p><strong>Both members are driven, not one.</strong> The instance id and the root device
     * name reach different refusals, and the root-device guard was the one that was MISSING —
     * added late, on review, which is exactly the kind of belt a later edit loosens again when
     * nothing forces it. The region has the same {@code case} in the same shape; it is asserted
     * here too rather than argued about.
     */
    @Test
    void noValueOffTheImdsWireCanForgeAJournalLine() throws Exception {
        assumeBash();
        assumeCurl();

        String forged = "2026-01-01T00:00:00Z FATAL forged";
        String goodToken = "AQAEAP_token-1234567890";

        record Case(String what, Map<String, String> metadata) {
        }
        var cases = List.of(
                new Case("instance-id", Map.of(
                        "/latest/meta-data/instance-id", "i-\n" + forged)),
                new Case("placement/region", Map.of(
                        "/latest/meta-data/instance-id", "i-0123456789abcdef0",
                        "/latest/meta-data/placement/region", "eu-central-1\n" + forged)),
                new Case("block-device-mapping/root", Map.of(
                        "/latest/meta-data/instance-id", "i-0123456789abcdef0",
                        "/latest/meta-data/placement/region", "eu-central-1",
                        "/latest/meta-data/block-device-mapping/root", "/dev/xvda\n" + forged)));

        for (Case c : cases) {
            try (var imds = startImds(goodToken, c.metadata())) {
                var run = runCollector(lockRedirectedScript(work.resolve("run.lock")),
                        Map.of("SNAPSHOT_IMDS_BASE", imds.base()));

                assertThat(run.exit())
                        .withFailMessage(CHECKLIST + "\nIMDS answered " + c.what() + " with a value"
                                + " carrying a newline and the run did NOT stop. Every one of these"
                                + " reaches either the AWS API or a refusal message, so a value that"
                                + " is not a value must end the run — the alternative is a Describe"
                                + " call built from whatever is answering the metadata address.\n"
                                + run.output())
                        .isNotZero();

                // A FORGED LINE IS ONE THAT ARRIVES ALONE. The refusal is expected to quote the
                // offending value, so the test is not "the text never appears" — it is "the text
                // never appears on a line of its own", i.e. printable() collapsed it onto the
                // line the script was already writing.
                assertThat(run.output().lines().anyMatch(l -> l.trim().startsWith(forged)))
                        .withFailMessage(CHECKLIST + "\nThe " + c.what() + " answer forged its own"
                                + " journal line: the output holds a line that reads as one this"
                                + " script wrote, with its own timestamp and its own FATAL, and"
                                + " nothing beside it says it came off the wire. printable() must"
                                + " strip control characters from EVERY value before it is quoted"
                                + " into a message — the rule is the category, not the field.\n"
                                + run.output())
                        .isFalse();

                assertThat(run.output())
                        .withFailMessage(CHECKLIST + "\nThe " + c.what() + " answer was refused only"
                                + " AFTER an AWS call had already been built from it. The guards on"
                                + " this path exist so that nothing off the wire reaches the API at"
                                + " all; one that runs afterwards is a different, weaker property"
                                + " wearing the same test name.\n" + run.output())
                        .doesNotContain("STUB-AWS-CALLED");

                assertThat(promOf(run))
                        .withFailMessage(CHECKLIST + "\nA run refused at " + c.what() + " published"
                                + " no pessimistic zeroes, so the previous reading stands and"
                                + " VolumeSnapshotCheckFailing never fires.\n" + run.output())
                        .contains("hamstrack_volume_snapshot_check_status{stage=\"resolve\"} 0");
            }
        }
    }

    /**
     * <strong>The token is refused BEFORE it is written into the header file, and the proof is
     * that no metadata request is ever made.</strong> A newline in an IMDSv2 token forges a
     * second HTTP header on every subsequent call — {@code curl -H @file} reads that file as a
     * header LIST — so the interesting property is not what the journal says, it is that
     * nothing was sent at all. A test that only read the output would pass over a script that
     * had already made the request.
     *
     * <p>The value itself must not appear in the output either: it is a credential, and it is
     * the one thing on this path refused without being shown.
     */
    @Test
    void aMalformedImdsTokenIsRefusedBeforeAnyRequestCarriesIt() throws Exception {
        assumeBash();
        assumeCurl();

        try (var imds = startImds("token\nX-Injected: 1",
                Map.of("/latest/meta-data/instance-id", "i-0123456789abcdef0"))) {
            var run = runCollector(lockRedirectedScript(work.resolve("run.lock")),
                    Map.of("SNAPSHOT_IMDS_BASE", imds.base()));

            assertThat(run.exit())
                    .withFailMessage(CHECKLIST + "\nThe token endpoint answered with a value"
                            + " containing a newline and the run continued.\n" + run.output())
                    .isNotZero();

            assertThat(imds.requests())
                    .withFailMessage(CHECKLIST + "\nAfter a malformed token the collector went on"
                            + " to request metadata: " + imds.requests() + ". curl reads -H @file"
                            + " as a header LIST, so the second line of that token would have gone"
                            + " out as a header of its own. The validation is only worth something"
                            + " if it happens BEFORE the file is written, and the request log is"
                            + " what tells those two apart — the journal cannot.\n" + run.output())
                    .containsExactly("PUT /latest/api/token");

            assertThat(run.output())
                    .withFailMessage(CHECKLIST + "\nThe refused token was echoed to the journal."
                            + " It is a credential — 60 seconds live, which is long enough — so it"
                            + " is the one value on this path refused without being shown.\n"
                            + run.output())
                    .doesNotContain("X-Injected");

            assertThat(promOf(run))
                    .withFailMessage(CHECKLIST + "\nA run refused at the token published no"
                            + " pessimistic zeroes.\n" + run.output())
                    .contains("hamstrack_volume_snapshot_check_status{stage=\"resolve\"} 0");
        }
    }

    /**
     * <strong>The off switch must still delete the file when it cannot open its lock.</strong>
     *
     * <p>What makes that true is one token, and it is not the obvious one: the {@code exec 9>}
     * sits inside a <em>tested condition</em> ({@code if command -v flock … && { exec 9>… }}).
     * Neither the braces nor the {@code 2>/dev/null} protects it — a bare
     * {@code { exec 9>/nonexistent/f; } 2>/dev/null} under {@code set -e} still ends the run,
     * which was checked rather than reasoned about. So the natural tidy-up
     *
     * <pre>{@code
     * if command -v flock >/dev/null 2>&1; then
     *   exec 9>/var/lock/...          // now unguarded
     * }</pre>
     *
     * <p>silently restores the bug, and restores it INVISIBLY: on every box where the lock
     * directory is writable — CI, and every healthy host — the redirect never fails, so no
     * other test in this class would notice. Hence this one drives a copy of the script whose
     * lock path points into a directory that does not exist, which is the only way to make the
     * failure happen on a machine where it otherwise cannot.
     *
     * <p>The copy is a single literal substitution and the helper refuses to proceed if it
     * matched nothing, so the test cannot go quietly vacuous when the path is renamed. Its
     * other vacuity route is the {@code flock} stub: the guard is
     * {@code command -v flock && { exec 9>… }}, so if the stub is not on {@code PATH} the
     * condition short-circuits, the redirect this test exists to fail is never evaluated, the
     * removal happens anyway and the assertions below pass having proved nothing. Linux CI has
     * a real {@code flock} and would survive a broken prepend; a Windows dev box has none, and
     * that is where the silence would live. So the resolution is asserted first, under the same
     * environment the case runs with — {@link #assertFlockResolves}.
     */
    @Test
    void theOffSwitchStillRemovesTheFileWhenItsLockCannotBeOpened() throws Exception {
        assumeBash();
        assertFlockResolves(Map.of("SNAPSHOT_SOURCE", "none"));

        Path prom = work.resolve("hamstrack_volume_snapshot.prom");
        Files.writeString(prom, """
                hamstrack_volume_snapshot_newest_timestamp_seconds{volume="vol-0123456789abcdef0"} 1756000000
                hamstrack_volume_snapshot_check_status{stage="resolve"} 1
                hamstrack_volume_snapshot_check_status{stage="describe"} 1
                hamstrack_volume_snapshot_check_timestamp_seconds 1756000000
                """, StandardCharsets.UTF_8);

        // A lock whose PARENT does not exist: `exec 9>` on it fails, which is the condition a
        // real box only reaches when /var/lock is not writable by this process.
        Path unopenable = work.resolve("no-such-directory").resolve("run.lock");
        var run = runCollector(lockRedirectedScript(unopenable), Map.of("SNAPSHOT_SOURCE", "none"));

        assertThat(run.exit())
                .withFailMessage(CHECKLIST + "\nThe `none` branch could not open its lock file and"
                        + " DIED instead of carrying on. Under `set -e` a failed redirect on a bare"
                        + " `exec` ends the run, and the only thing that makes it survivable is"
                        + " sitting inside a tested condition — `if command -v flock ... && { exec"
                        + " 9>... } 2>/dev/null`. If that was straightened into an `if ...; then`"
                        + " body, put it back: the braces and the 2>/dev/null are not what protect"
                        + " it.\n" + run.output())
                .isZero();
        assertThat(Files.exists(prom))
                .withFailMessage(CHECKLIST + "\nThe `none` branch abandoned the removal because it"
                        + " could not take a lock. That is the outcome the guard exists to prevent:"
                        + " node-exporter goes on scraping a frozen .prom for ever, so the off"
                        + " switch hands the operator who used it a permanent critical alert.\n"
                        + run.output())
                .isFalse();
    }

    // --- helpers ----------------------------------------------------------------------

    private record Result(int exit, String output) {
    }

    /** Drives the repository's own copy, which is what the two configuration refusals want. */
    private Result runCollector(Map<String, String> env) throws Exception {
        return run(SCRIPT, env, false);
    }

    /**
     * Drives a REWRITTEN copy (see {@link #lockRedirectedScript}) with stub {@code flock} and
     * {@code aws} on PATH. The stubs are not fidelity, they are determinism: neither tool is
     * present on a Windows dev box, and what these tests need from {@code flock} is only that
     * {@code command -v} finds it so the guarded {@code exec 9>} is actually evaluated. The
     * {@code aws} stub exists to be LOUD — every test using it asserts it was never reached,
     * because a refusal that happens after the API call is a different property from one that
     * happens before it.
     */
    private Result runCollector(Path script, Map<String, String> env) throws Exception {
        return run(script, env, true);
    }

    /**
     * <strong>The {@code flock} stub has to be REACHABLE, and nothing else asserts that it is.</strong>
     * Every stubbed case reaches its guarded {@code exec 9>} only through
     * {@code command -v flock}, which is a <em>tested condition</em>: when the lookup fails the
     * whole {@code &&} short-circuits, the redirect is never evaluated, and a test written
     * around that redirect failing goes green without having run it. Asserted with the same
     * environment {@link #run} builds — not with the JVM's, which does not have the stub
     * directory on {@code PATH} and would therefore answer a different question.
     */
    private void assertFlockResolves(Map<String, String> env) throws Exception {
        var pb = new ProcessBuilder(bash, "-c", "command -v flock").redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(environmentFor(env, true));
        var p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(p.waitFor(60, TimeUnit.SECONDS))
                .withFailMessage("`command -v flock` did not finish in 60s")
                .isTrue();
        assertThat(p.exitValue())
                .withFailMessage(CHECKLIST + "\n`command -v flock` found nothing under the"
                        + " environment this case runs with, so the script's guarded `exec 9>`"
                        + " would never be evaluated and the case below would pass vacuously —"
                        + " the removal it asserts happens whether or not the lock could be"
                        + " opened. Either the PATH prepend in run(...) stopped reaching bash or"
                        + " the stub in stubBin() is no longer executable. Fix the fixture; do"
                        + " not weaken the case.\ncommand -v said: " + out)
                .isZero();
    }

    private Result run(Path script, Map<String, String> env, boolean withStubs) throws Exception {
        var pb = new ProcessBuilder(bash, posix(script)).redirectErrorStream(true);
        var merged = environmentFor(env, withStubs);
        pb.environment().clear();
        pb.environment().putAll(merged);
        var p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS))
                .withFailMessage("the collector did not finish in 60s; every path these tests"
                        + " drive stops at a refusal or at a loopback address, so a hang here is a"
                        + " lock or a read that should not exist")
                .isTrue();
        return new Result(p.exitValue(), out);
    }

    /**
     * The environment every executed case runs with. Built once here rather than at each call
     * site so that {@link #assertFlockResolves} tests the same {@code PATH} the case gets: a
     * seal built over a differently-assembled environment seals nothing.
     */
    private Map<String, String> environmentFor(Map<String, String> env, boolean withStubs)
            throws IOException {
        var merged = new LinkedHashMap<>(new ProcessBuilder().environment());
        merged.put("SNAPSHOT_TEXTFILE_DIR", posix(work));
        // Pointed at a port nothing listens on, so a case that ever reached the network would
        // fail loudly here rather than quietly reaching this machine's own metadata service.
        merged.put("SNAPSHOT_IMDS_BASE", "http://127.0.0.1:1");
        if (withStubs) {
            Path bin = stubBin();
            prependToPath(merged, bin);
            merged.put("SNAPSHOT_AWS_BIN", posix(bin.resolve("aws")));
            // The script's scratch files (`mktemp "${TMPDIR:-/tmp}/…"`) land inside @TempDir
            // rather than in /tmp. On Windows that also settles a question nobody should have to
            // re-answer: the IMDSv2 header file's path is handed to a NATIVE curl.exe as
            // `-H @<path>`, and a `C:/…` path needs no MSYS argument conversion to survive.
            merged.put("TMPDIR", posix(work));
        }
        merged.putAll(env);
        return merged;
    }

    /**
     * A copy of the script with the lock path pointed somewhere the test controls.
     *
     * <p>The substitution is one literal and the count is asserted, because the whole value of
     * the test that uses it is that it fails on a change no other test can see. A renamed lock
     * path that silently matched nothing would leave the copy identical to the original, the
     * redirect would succeed, and the assertion would pass while proving nothing.
     */
    private Path lockRedirectedScript(Path lockFile) throws IOException {
        String body = script();
        String needle = "/var/lock/hamstrack-volume-snapshot.lock";
        int hits = body.split(Pattern.quote(needle), -1).length - 1;
        assertThat(hits)
                .withFailMessage(CHECKLIST + "\nThe lock path '" + needle + "' was found " + hits
                        + " time(s) in " + SCRIPT + ". There are two lock sites — the `none`"
                        + " branch's guarded one and the main path's — and this helper rewrites"
                        + " both so a test can make the redirect fail. If the path was renamed,"
                        + " teach this helper the new one; leaving it unmatched makes the copy"
                        + " identical to the original and every assertion driving it vacuous.")
                .isGreaterThanOrEqualTo(2);
        Path copy = work.resolve("collector-" + copies++ + ".sh");
        Files.writeString(copy, body.replace(needle, posix(lockFile)), StandardCharsets.UTF_8);
        makeExecutable(copy);
        return copy;
    }

    /** A fake IMDS. {@code requests} is the seal: it says what was SENT, which no log can. */
    private record Imds(HttpServer server, String base, List<String> requests)
            implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private Imds startImds(String token, Map<String, String> metadata) throws IOException {
        var requests = Collections.synchronizedList(new ArrayList<String>());
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            String body = "/latest/api/token".equals(path) ? token : metadata.get(path);
            if (body == null) {
                // What a real IMDS does for a path this instance has no answer for, and what the
                // script's `|| root_dev=""` fallback is written against.
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return new Imds(server, "http://127.0.0.1:" + server.getAddress().getPort(), requests);
    }

    private Path stubBin() throws IOException {
        Path bin = work.resolve("bin");
        if (Files.isDirectory(bin)) {
            return bin;
        }
        Files.createDirectories(bin);
        write(bin.resolve("flock"), """
                #!/bin/sh
                # Test stub. Only `command -v flock` matters to the tests that put this on PATH:
                # it is what makes the guarded `exec 9>` be evaluated on a box (any Windows one)
                # where util-linux is absent and the guard would otherwise short-circuit.
                exit 0
                """);
        write(bin.resolve("aws"), """
                #!/bin/sh
                # Test stub, deliberately LOUD. Every test that installs it asserts this line
                # never appears: a value refused after the API call was already made is a
                # different property from one refused before it.
                echo "STUB-AWS-CALLED: $*" >&2
                exit 99
                """);
        return bin;
    }

    private static void write(Path path, String body) throws IOException {
        Files.writeString(path, body, StandardCharsets.UTF_8);
        makeExecutable(path);
    }

    /** No-op where POSIX permissions do not exist; on Windows every file already reads as 0755. */
    private static void makeExecutable(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /** {@code PATH} is spelled {@code Path} on Windows, and the map here is case-sensitive. */
    private static void prependToPath(Map<String, String> env, Path dir) {
        String key = env.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("PATH"))
                .findFirst()
                .orElse("PATH");
        String current = env.getOrDefault(key, "");
        env.put(key, dir.toAbsolutePath() + (current.isEmpty() ? "" : File.pathSeparator + current));
    }

    private String promOf(Result run) throws IOException {
        Path prom = work.resolve("hamstrack_volume_snapshot.prom");
        assertThat(prom)
                .withFailMessage(CHECKLIST + "\nNo .prom was written at all.\n" + run.output())
                .isRegularFile();
        return Files.readString(prom, StandardCharsets.UTF_8);
    }

    private static Set<String> emittedStages() throws IOException {
        var found = new LinkedHashSet<String>();
        Matcher m = EMITTED_STAGE.matcher(script());
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static String stageRuleSummary() throws IOException {
        Map<String, Object> rule = alertRulesByUid().get(STAGE_RULE_UID);
        assertThat(rule)
                .withFailMessage(CHECKLIST + "\n" + RULES + " declares no rule with uid "
                        + STAGE_RULE_UID + ", so nothing describes a failing stage to anybody.")
                .isNotNull();
        Object summary = asMap(rule.get("annotations")).get("summary");
        assertThat(summary)
                .withFailMessage(CHECKLIST + "\n" + STAGE_RULE_UID + " has no summary annotation,"
                        + " so it notifies with no text.")
                .isNotNull();
        return summary.toString();
    }

    private static String promExpressionOf(Map<String, Object> rule) {
        for (Object entry : (List<?>) rule.get("data")) {
            Map<String, Object> model = asMap(asMap(entry).get("model"));
            Object expr = model.get("expr");
            if (expr != null) {
                return expr.toString();
            }
        }
        return "";
    }

    private static Map<String, Map<String, Object>> alertRulesByUid() throws IOException {
        var yaml = new Yaml(new LoaderOptions());
        Map<String, Object> root = yaml.load(Files.readString(RULES, StandardCharsets.UTF_8));
        var byUid = new LinkedHashMap<String, Map<String, Object>>();
        for (Object group : (List<?>) root.get("groups")) {
            Object rules = asMap(group).get("rules");
            if (rules == null) {
                continue;
            }
            for (Object rule : (List<?>) rules) {
                Map<String, Object> body = asMap(rule);
                Object uid = body.get("uid");
                if (uid != null) {
                    byUid.put(uid.toString(), body);
                }
            }
        }
        assertThat(byUid)
                .withFailMessage(CHECKLIST + "\nNo alert rule was extracted from " + RULES
                        + " at all, so every assertion reading it would pass vacuously.")
                .hasSizeGreaterThanOrEqualTo(15);
        return byUid;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String script() throws IOException {
        assertThat(SCRIPT)
                .withFailMessage("%s was not found — this class reads the repository's own copy,"
                        + " so it must run from the module root (was %s)",
                        SCRIPT.toAbsolutePath(), Path.of(".").toAbsolutePath())
                .isRegularFile();
        return Files.readString(SCRIPT, StandardCharsets.UTF_8);
    }

    /** Drops whole-line comments only: {@code ${#array[@]}} is not a comment. */
    private static String stripFullLineComment(String line) {
        return line.stripLeading().startsWith("#") ? "" : line;
    }

    /** The script with every whole-line comment removed — what bash would actually run. */
    private static String codeOf(String body) {
        var sb = new StringBuilder();
        for (String line : body.lines().toList()) {
            sb.append(stripFullLineComment(line)).append('\n');
        }
        return sb.toString();
    }

    private static String visible(String s) {
        return s.replace("\r", "\\r").replace(" ", "<space>");
    }

    private void assumeBash() {
        ScriptHarness.assumeWithWitness("volume-snapshot-collector", bash != null,
                "no bash on PATH (and no Git for Windows bash.exe) — the executed refusals run on"
                        + " CI and on any POSIX machine, and skip only on a Windows box without"
                        + " Git Bash. The file-reading assertions in this class run everywhere.");
        assertThat(SCRIPT)
                .withFailMessage("%s was not found — this test drives the repository's own copy",
                        SCRIPT.toAbsolutePath())
                .isRegularFile();
    }

    /**
     * The IMDS tests need a real HTTP client, because the property under test is what goes ON
     * THE WIRE. It is present on CI, on Amazon Linux 2023 and in Windows since 1803; a machine
     * without it skips rather than pretending, for the same reason nothing in this class stands
     * in for AWS.
     */
    private static void assumeCurl() {
        ScriptHarness.assumeWithWitness("volume-snapshot-collector", onPath("curl"),
                "no curl on PATH — the IMDS wire tests need one. They run on CI and on any box"
                        + " that could actually run this collector, which needs curl anyway.");
    }

    private static boolean onPath(String tool) {
        var name = System.getProperty("os.name").toLowerCase().contains("win") ? tool + ".exe" : tool;
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                if (Files.isExecutable(Path.of(dir).resolve(name))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // an unparseable PATH entry is not this class's problem
            }
        }
        return false;
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
                // an unparseable PATH entry is not this class's problem
            }
        }
        return null;
    }
}
