package com.hamstrack.ops;

import com.hamstrack.common.observability.ProductMetrics;
import com.hamstrack.common.observability.ProductMetrics.MailDropReason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.hamstrack.ops.OpsYaml.list;
import static com.hamstrack.ops.OpsYaml.map;
import static com.hamstrack.ops.OpsYaml.parse;
import static com.hamstrack.ops.OpsYaml.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-298 (epic HD-294) — every ops mechanism has a witness, and every witness is
 * enumerated from the mechanism rather than listed.</strong>
 *
 * <p>Five of the twelve CRIT defects in the 2026-09 retrospective were mechanisms with no witness,
 * and each witness that exists today was added after its outage (HD-187, HD-202, HD-262, HD-287).
 * Nothing in the suite asked, of the whole category, "what observes this in production?". This
 * class asks it of four populations read from the tree, with a floor on each:
 *
 * <ol>
 *   <li><strong>timers</strong> — every {@code ops/**&#47;*.timer} has a freshness rule whose
 *       {@code threshold + for} outlives its schedule and stays within four periods;</li>
 *   <li><strong>delivery</strong> — every rule resolves to a contact point the tree defines,
 *       every contact point some rule reaches has a <em>dated</em> delivery drill within
 *       {@value #MAX_DRILL_AGE_DAYS} days in {@code docs/ops-prod-hardening.md} § 4.1 (with a
 *       floor on the reached set, so an empty one is red rather than vacuously green), and no
 *       cell of that table is shaped like an address — the repository is public;</li>
 *   <li><strong>scheduled jobs</strong> — in {@code ScheduledJobHeartbeatTest}, because that
 *       predicate is about what the registrar registered and what the registry exposes, which a
 *       file scan cannot see and a boot inside this file-reading class would make a doc check cost
 *       a context;</li>
 *   <li><strong>drops</strong> — every label constant {@code ProductMetrics} declares is emitted
 *       somewhere, every limiter refusal counts {@code hamstrack.ratelimit.hit}, and every
 *       mail-drop branch counts its reason.</li>
 * </ol>
 *
 * <p><strong>Rule 2's drill assertion is red until the owner writes the first row</strong> — the
 * address behind {@code OBS_ALERT_EMAIL_TO} is outside the repository, so the only thing that
 * proves the inbox is real is a message that arrived. That is the gate working, not a defect.
 *
 * <p>Plain JUnit; reads {@code ops/}, the alerting directory and the docs with the same
 * {@code Path.of(...)} its siblings use. <strong>Outside the module root it fails, never
 * skips</strong>: an {@code Assumptions} skip would be a {@code Skipped: 1}, which HD-295 refuses.
 *
 * <p><strong>Not seen:</strong> whether a timer is installed on the box (HD-287 — the stale rule
 * is that witness); whether a drill row is true; matchers on nested routes (none today — a route
 * the resolver cannot evaluate fails closed, never skips); a drop site outside {@link #DROP_SITES}
 * (a named list with a floor, and the message says so; an unemitted reason is still caught by the
 * enum scan); a refusal thrown in one file and counted in another that is not in
 * {@link #COUNTED_ELSEWHERE}.
 */
class OpsWitnessContractTest {

    private static final Path OPS = Path.of("ops");
    private static final Path ALERTING = Path.of("observability", "grafana", "provisioning", "alerting");
    private static final Path HARDENING = Path.of("docs", "ops-prod-hardening.md");
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path PRODUCT_METRICS =
            MAIN.resolve(Path.of("com", "hamstrack", "common", "observability", "ProductMetrics.java"));

    /** The heading the drill table lives under; the tripwire names it when it cannot be found. */
    static final String DRILL_HEADING = "### 4.1 Alert delivery drill log";

    /**
     * Two monthly checkpoints ({@code checkpoint.mjs}, from 2026-09-21): longer than any release
     * cycle, shorter than the retro's silent windows. On age alone — the inbox, the SMTP
     * credential and Grafana's health all live outside the repository, so "red only when the
     * contact point changed" is unmeasurable for exactly the failures it should catch.
     */
    static final int MAX_DRILL_AGE_DAYS = 60;

    // Floors: each deliberately below today's count — a floor on the scan, not an inventory.
    static final int MIN_TIMERS = 2;
    static final int MIN_ALERT_RULES = 15;
    static final int MIN_CONTACT_POINTS = 1;
    static final int MIN_LABEL_ENUMS = 5;
    static final int MIN_LABEL_CONSTANTS = 40;
    static final int MIN_REFUSAL_SITES = 5;
    static final int MIN_DROP_SITES = 5;

    // ============================================================ tripwire

    /**
     * Every assertion below is of the form "nothing offends", which a scan pointed at the wrong
     * directory satisfies perfectly. This is the first thing to fail, and it names the directory.
     */
    @Test
    void theScanRunsFromTheModuleRootAndFindsItsInputs() {
        for (var input : List.of(OPS, ALERTING, HARDENING, PRODUCT_METRICS)) {
            assertThat(Files.exists(input))
                    .withFailMessage("'%s' was not found. Either it moved and this test did not "
                            + "move with it, or the working directory is not the module root (it is "
                            + "'%s'). This test fails here rather than skipping: a skip is a green "
                            + "run that checked nothing.", input, Path.of("").toAbsolutePath())
                    .isTrue();
        }
    }

    // ============================================================ rule 1 — timers

    /**
     * For every timer: its period from {@code OnCalendar}/{@code OnUnitActiveSec} plus
     * {@code RandomizedDelaySec}; the script beside it declares at least one
     * {@code # TYPE <name>_timestamp_seconds gauge}; some rule reads {@code time() - <name>} with
     * {@code threshold + for} in {@code (period + jitter, 4 × period]}. One missed run must not
     * page; a stopped timer must page within a few periods. The bound compared with the schedule
     * is {@code threshold + for}, <strong>not {@code for:} alone</strong> — {@code for:} is 15m on a
     * daily job. An unparsable schedule fails closed: "teach the parser", never "0 rules needed".
     */
    @Test
    void everyTimerHasAFreshnessRuleWhoseBoundOutlivesItsSchedule() {
        var rules = alertRules();
        var offenders = new ArrayList<String>();
        for (Timer timer : timers()) {
            var gauges = freshnessGauges(timer);
            var readings = new ArrayList<String>();
            boolean covered = false;
            for (String gauge : gauges) {
                for (AlertRule rule : rules) {
                    if (rule.expressions().stream().noneMatch(expr -> expr.contains("time() - " + gauge))) {
                        continue;
                    }
                    long threshold = rule.threshold();
                    long hold = seconds(rule.forDuration(), rule.where());
                    long bound = threshold + hold;
                    readings.add(rule.title() + " (" + threshold + "+" + hold + "=" + bound + "s)");
                    if (bound > timer.window() && bound <= 4 * timer.periodSeconds()) {
                        covered = true;
                    }
                }
            }
            if (!covered) {
                offenders.add("""

                        Timer %s runs every %ds (+%ds jitter) and no alert rule reads its freshness \
                        gauge with threshold+for in (%d, %d] s - a stopped timer would page late or \
                        never (the HD-287 shape).
                          gauges the script beside it declares: %s
                          rules reading them (threshold+for): %s
                        Add a rule to observability/grafana/provisioning/alerting/rules.yml shaped like \
                        ConfigDriftCheckStale (uid hamstrack-config-check-stale): expr \
                        'time() - <gauge>', threshold > period+jitter, for >= 15m, threshold+for <= \
                        4 x period, summary naming the install step in docs/ops-prod-hardening.md. \
                        If the script declares no *_timestamp_seconds gauge, add one - the template \
                        is the '# TYPE hamstrack_config_check_timestamp_seconds gauge' block in \
                        ops/drift/hamstrack-config-drift.sh. The bound compared with the schedule \
                        is threshold+for, not for: alone.""".formatted(
                        slash(timer.unit()), timer.periodSeconds(), timer.jitterSeconds(), timer.window(),
                        4 * timer.periodSeconds(), gauges.isEmpty() ? "NONE" : gauges,
                        readings.isEmpty() ? "NONE" : readings));
            }
        }
        assertThat(offenders).withFailMessage(String.join("\n", offenders)).isEmpty();
    }

    // ============================================================ rule 2 — delivery

    /**
     * (a) Every receiver a policy or a rule names exists in the tree; (b) every rule resolves to at
     * least one contact point; (d) a contact point no rule reaches is dead configuration. A nested
     * route is a shape this resolver does not evaluate against rule labels, so it <strong>fails
     * closed</strong> naming the route rather than resolving every rule to the root.
     */
    @Test
    void everyRuleResolvesToAContactPointThisTreeDefines() {
        var routing = routing();
        var dead = new LinkedHashSet<>(routing.contactPoints());
        routing.resolved().values().forEach(dead::removeAll);
        var offenders = new ArrayList<>(routing.offenders());
        for (String contactPoint : dead) {
            offenders.add("contact point '" + contactPoint + "' is defined in contactpoints.yml and no "
                    + "rule delivers to it - dead configuration is a drill nobody will run; route "
                    + "something to it or delete it");
        }
        assertThat(offenders)
                .withFailMessage("""

                        Alert routing that does not resolve inside \
                        observability/grafana/provisioning/alerting/ (every rule must reach a contact \
                        point that exists there, and a route shape this test cannot evaluate is refused \
                        rather than assumed):
                          %s""", String.join("\n  ", offenders))
                .isEmpty();
        assertThat(routing.contactPoints())
                .withFailMessage("Only %d contact point(s) were found under %s - the scan is broken",
                        routing.contactPoints().size(), ALERTING)
                .hasSizeGreaterThanOrEqualTo(MIN_CONTACT_POINTS);
    }

    /**
     * (c) Every contact point some rule reaches has a row under {@link #DRILL_HEADING} dated within
     * {@link #MAX_DRILL_AGE_DAYS} days and not in the future. <strong>Red until the owner writes
     * the first row</strong>; the message says exactly what to send, where to look and which row
     * to add. Kept apart from the resolution seal above so this expected red is never mistaken for
     * a routing defect.
     */
    @Test
    void everyReachedContactPointHasADeliveryDrillWithinSixtyDays() {
        var routing = routing();
        var reached = new TreeMap<String, Integer>();
        routing.resolved().values().forEach(points -> points.forEach(p -> reached.merge(p, 1, Integer::sum)));
        // The floor comes BEFORE the loop: over an empty 'reached' the loop below asserts nothing and
        // this test is green while its sibling alone says the root receiver went missing. A
        // "nothing offends" assertion is only evidence once the population it ran over is known.
        assertThat(reached)
                .withFailMessage("No alert rule resolves to any contact point (policies.yml lost its root "
                        + "receiver, or the resolver stopped seeing rules), so there is nothing to drill and "
                        + "this assertion would pass over an empty set - floor %d", MIN_CONTACT_POINTS)
                .hasSizeGreaterThanOrEqualTo(MIN_CONTACT_POINTS);
        var drills = drills();
        var today = LocalDate.now();
        var offenders = new ArrayList<String>();

        for (var entry : reached.entrySet()) {
            String contactPoint = entry.getKey();
            Optional<Drill> newest = drills.stream()
                    .filter(d -> d.contactPoint().equals(contactPoint))
                    .max(Comparator.comparing(Drill::date));
            String finding;
            if (newest.isEmpty()) {
                finding = "no row at all";
            } else if (newest.get().date().isAfter(today)) {
                finding = "newest row is dated " + newest.get().date() + ", which is in the future - a row "
                        + "written in advance is not a row";
            } else if (ChronoUnit.DAYS.between(newest.get().date(), today) > MAX_DRILL_AGE_DAYS) {
                finding = "newest row is dated " + newest.get().date() + ", "
                        + ChronoUnit.DAYS.between(newest.get().date(), today) + " days ago";
            } else {
                continue;
            }
            offenders.add("""

                    Contact point '%s' is what %d alert rule(s) deliver to and has no delivery drill \
                    dated within the last %d days in %s under '%s' (%s). Nine rules once delivered to \
                    an undeliverable domain for months with every test green; the address behind \
                    OBS_ALERT_EMAIL_TO is outside this repository, so the only thing that proves this \
                    inbox is real is a message that arrived.
                    Do: Grafana -> Alerting -> Contact points -> '%s' -> Test (or fire a rule on \
                    purpose), watch the inbox behind OBS_ALERT_EMAIL_TO, then append
                      | %s | `%s` | <Test button / rule fired> | <role, e.g. the OBS_ALERT_EMAIL_TO inbox / initials> | <delay> | <notes> |
                    to that table, dated the day it ARRIVED. A row dated in the future or written \
                    before the message was seen is not a row, and a cell carrying an address is \
                    refused - this repository is public (see noDrillCellCarriesAnAddress).""".formatted(
                    contactPoint, entry.getValue(), MAX_DRILL_AGE_DAYS, slash(HARDENING), DRILL_HEADING,
                    finding, contactPoint, today, contactPoint));
        }
        assertThat(offenders).withFailMessage(String.join("\n", offenders)).isEmpty();
    }

    /** {@code x@y.z} in any spelling — the shape, not a validation; a false positive costs a reword. */
    static final Pattern ADDRESS_SHAPED = Pattern.compile("\\S+@\\S+\\.\\S+");

    /**
     * (d) No cell of the drill table — header, placeholder or row — carries anything shaped like
     * an address. The table asks who received the message, which invites the alert address or a
     * person's; this repository is public, so the "by whom" column takes a role and initials.
     * {@code PublishedCredentials} scans {@code VAR=value} shapes and would not see this.
     */
    @Test
    void noDrillCellCarriesAnAddress() {
        var offenders = new ArrayList<String>();
        for (DrillRow row : drillRows()) {
            for (String cell : row.cells()) {
                if (ADDRESS_SHAPED.matcher(cell).find()) {
                    offenders.add(slash(HARDENING) + ":" + row.line() + " cell '" + cell + "'");
                }
            }
        }
        assertThat(offenders)
                .withFailMessage("""

                        A cell in the delivery-drill table under '%s' is shaped like an email address:
                          %s
                        This repository is public. The alert address (OBS_ALERT_EMAIL_TO) is the one \
                        inbox every rule delivers to, and writing it here hands anyone the target for \
                        the mail bomb HD-202 closed; a person's address is the same leak with a name on \
                        it. Say WHERE as a role ("the OBS_ALERT_EMAIL_TO inbox") and WHO as initials, \
                        and keep the address outside the tree where the contact point already keeps it.""",
                        DRILL_HEADING, String.join("\n  ", offenders))
                .isEmpty();
    }

    /**
     * <strong>A series excused from alerting must name a reader, and the reader must exist.</strong>
     *
     * <p>{@code docs/observability.md}'s metric table excuses some series from having a rule with
     * <em>"No alert and no panel by decision"</em>, and justifies it by naming who reads them
     * instead — Grafana Explore, and a step in a document. It closes with "a metric nobody reads
     * is a metric nobody notices breaking, so the reader is written down even when it is a person
     * rather than a rule."
     *
     * <p>Measured 2026-09-10: {@code grep -n "hamstrack_deploy_verify\|Explore" docs/release-checklist.md}
     * returned <strong>zero</strong> hits. Four series had no alert, no panel and no reader,
     * justified by a citation to a step nobody had written. A named reader is a claim like any
     * other, and this is what holds it: every {@code docs/*.md} the excusing rows point at must
     * actually mention every excused series.
     *
     * <p>Phrased over the table rather than over those four names, so a series excused later is
     * a member the day it is written.
     */
    @Test
    void everySeriesExcusedFromAlertingIsReadByTheDocumentItsExcuseNames() throws IOException {
        var table = Files.readString(Path.of("docs", "observability.md"), java.nio.charset.StandardCharsets.UTF_8);
        var excused = new LinkedHashSet<String>();
        var namedReaders = new LinkedHashSet<String>();
        var metricInRow = Pattern.compile("^\\|\\s*`([a-z_]+)`\\s*\\|");
        var docInRow = Pattern.compile("docs/[a-z0-9-]+\\.md");
        for (String row : table.lines().toList()) {
            if (!row.contains("No alert and no panel by decision") && !row.contains("Same decision:")) {
                continue;
            }
            var m = metricInRow.matcher(row);
            if (m.find()) {
                excused.add(m.group(1));
            }
            var d = docInRow.matcher(row);
            while (d.find()) {
                namedReaders.add(d.group());
            }
        }
        assertThat(excused.size())
                .withFailMessage("Only %d series in docs/observability.md carry an alerting exemption (%s). "
                        + "There were 4 when this scan was written, so it has stopped recognising the "
                        + "wording rather than the table having stopped using it.", excused.size(), excused)
                .isGreaterThanOrEqualTo(3);
        assertThat(namedReaders)
                .withFailMessage("A series is excused from alerting in docs/observability.md and the excuse "
                        + "names no document as its reader: %s. 'Grafana Explore' alone is a place, not a "
                        + "step somebody performs — name the document that reads it, or give the series a "
                        + "rule.", excused)
                .isNotEmpty();

        var unread = new ArrayList<String>();
        for (String reader : namedReaders) {
            // A DELETED READER IS A FINDING, NOT AN ERROR. Files.readString threw NoSuchFileException
            // here, so the one drift this scan most has to name — the document the excuse cites
            // being gone — surfaced as a stack trace instead of the message written for it.
            if (!Files.isRegularFile(Path.of(reader))) {
                unread.add(reader + " is named as the reader and is not in the tree");
                continue;
            }
            var text = Files.readString(Path.of(reader), java.nio.charset.StandardCharsets.UTF_8);
            for (String series : excused) {
                if (!text.contains(series)) {
                    unread.add(reader + " never mentions " + series);
                }
            }
        }
        assertThat(unread)
                .withFailMessage("""

                        docs/observability.md excuses a series from alerting by naming a human reader, and \
                        that reader does not read it:
                          %s

                        A metric with no alert, no panel and no reader is one nobody notices breaking, and \
                        a citation to a step that was never written is worse than no citation — it stops \
                        the next reader checking. Either add the read-back step to the document named, or \
                        weaken the excuse to name only what exists.""", String.join("\n  ", unread))
                .isEmpty();
    }

    /**
     * <strong>A skip is a degrade, so it carries a witness — for every assumption in this
     * package, enumerated rather than listed.</strong>
     *
     * <p>{@link ScriptHarness#assumeWithWitness} exists because a JUnit assumption's reason reaches
     * {@code target/surefire-reports/*.xml} and nowhere else: the console says {@code Skipped: 1}
     * and the reader has to go looking to learn which gate stopped running. It was applied to the
     * two real-daemon classes and left off five other call sites — including the guard on this
     * epic's flagship seal, so the seal that runs the workflow's own allow-list could stop running
     * and say so only in an XML file nobody opens.
     *
     * <p>The rule is over the package, not over those five, and the population comes from the
     * sources. {@code ScriptHarness} itself is the one permitted caller of the bare form: it is
     * where the witness is printed.
     */
    @Test
    void everyAssumptionInTheOpsPackageLeavesAWitnessOnTheConsole() throws IOException {
        var dir = Path.of("src", "test", "java", "com", "hamstrack", "ops");
        var bare = new ArrayList<String>();
        int withWitness = 0;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                var text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                var self = file.getFileName().toString();
                // CALL SITES ONLY. ScriptHarness.java holds the DEFINITION and this file holds
                // the spelling inside its own remedy text; neither is a gate that can skip, and
                // counting them inflated the printed population by two.
                if (!self.equals("ScriptHarness.java") && !self.equals("OpsWitnessContractTest.java")) {
                    withWitness += text.split("assumeWithWitness\\(", -1).length - 1;
                }
                if (self.equals("ScriptHarness.java")) {
                    continue;   // where the witness is printed; the bare call is its implementation
                }
                // The CALL, not the characters: `Assumptions.assumeTrue(` qualified, or a
                // statically-imported `assumeTrue(` at the start of a statement. A substring test
                // matched this scan's own source, which is the same "resembles it" mistake the
                // seals in ApplyConfigVerifyPhaseTest were failing for.
                var call = Pattern.compile("Assumptions\\.assumeTrue\\(|^\\s*assumeTrue\\(");
                for (String line : text.lines().toList()) {
                    if (call.matcher(line).find() && !line.stripLeading().startsWith("*")
                            && !line.stripLeading().startsWith("//")) {
                        bare.add(slash(file) + ": " + line.strip());
                    }
                }
            }
        }
        assertThat(withWitness)
                .withFailMessage("Only %d assumeWithWitness call(s) were found in %s — the scan has stopped "
                        + "seeing the mechanism rather than the package having stopped using it", withWitness, dir)
                .isGreaterThanOrEqualTo(8);
        assertThat(bare)
                .withFailMessage("""

                        These assumptions skip a gate without leaving a line on the console:
                          %s

                        A skipped gate is a degraded run, and this project gives every degrade a named \
                        witness. `Skipped: 1` names nothing, and the reason reaches only the surefire XML. \
                        Use ScriptHarness.assumeWithWitness("<tag>", condition, reason) — the tag makes the \
                        line greppable in a CI log, which is where somebody is reading it.""",
                        String.join("\n  ", bare))
                .isEmpty();
    }

    // ============================================================ rule 4 — drops

    /**
     * An enum whose constants are reached through a closed-set mapper rather than by name: the
     * mapper call itself must exist outside {@code ProductMetrics}, or the exemption is dead.
     */
    static final Map<Class<?>, String> REACHED_BY_MAPPER =
            Map.of(ProductMetrics.CspDirective.class, "CspDirective.of(");

    /**
     * (a) Every constant of every label enum nested in {@code ProductMetrics} is named, qualified,
     * on a non-comment line of some production source outside it — a witness that is declared and
     * never fires is read as "never happened". The {@code MailCriticalityCoverageTest} shape:
     * declaration checked against implementation.
     */
    @Test
    void everyLabelConstantProductMetricsDeclaresIsEmittedOutsideIt() {
        var sources = productionSources();
        var enums = Arrays.stream(ProductMetrics.class.getDeclaredClasses())
                .filter(Class::isEnum)
                .sorted(Comparator.comparing(Class::getSimpleName))
                .toList();
        var offenders = new ArrayList<String>();
        int constants = 0;

        for (Class<?> labelEnum : enums) {
            String name = labelEnum.getSimpleName();
            String mapper = REACHED_BY_MAPPER.get(labelEnum);
            if (mapper != null) {
                constants += labelEnum.getEnumConstants().length;
                if (sources.values().stream().flatMap(List::stream).noneMatch(l -> l.contains(mapper))) {
                    offenders.add(name + " is listed in REACHED_BY_MAPPER as reached through '" + mapper
                            + "', and nothing in src/main calls it - the exemption is dead; emit the "
                            + "constants or delete the enum");
                }
                continue;
            }
            for (Object constant : labelEnum.getEnumConstants()) {
                constants++;
                var qualified = Pattern.compile("\\b" + name + "\\." + constant + "\\b");
                boolean named = sources.values().stream().flatMap(List::stream)
                        .anyMatch(line -> qualified.matcher(line).find());
                if (!named) {
                    offenders.add(name + "." + constant + " is declared as a label value in ProductMetrics "
                            + "and named nowhere else in src/main - a witness that is declared and never "
                            + "fires is read as 'never happened'. Emit it at the branch it names, or "
                            + "delete it. (An enum reached only through a closed-set mapper, as "
                            + "CspDirective.of is, goes in REACHED_BY_MAPPER with the mapper call.)");
                }
            }
        }

        assertThat(offenders).withFailMessage("\n" + String.join("\n", offenders)).isEmpty();
        assertThat(enums)
                .withFailMessage("Only %d label enum(s) were found nested in ProductMetrics - the "
                        + "reflection has stopped seeing them", enums.size())
                .hasSizeGreaterThanOrEqualTo(MIN_LABEL_ENUMS);
        assertThat(constants)
                .withFailMessage("Only %d label constant(s) were scanned - below the floor of %d, so "
                        + "the enumeration collapsed", constants, MIN_LABEL_CONSTANTS)
                .isGreaterThanOrEqualTo(MIN_LABEL_CONSTANTS);
    }

    /** A refusal site: a thrown limiter exception, or a {@code Refusal} built to become one. */
    static final Pattern REFUSAL_SITE = Pattern.compile(
            "throw new RateLimitedException\\(|throw ConcurrencyLimitedException\\.\\w+\\(|return new Refusal\\(");

    static final String REFUSAL_WITNESS = "metrics.rateLimitHit(";

    /**
     * A refusal thrown in one file and counted in another: the throwing file's repository path
     * (forward slashes) to the counting file's, with the reason in a comment. None today.
     */
    static final Map<String, String> COUNTED_ELSEWHERE = Map.of();

    /**
     * (b) Every limiter refusal site is in a file that counts {@code hamstrack.ratelimit.hit} — the
     * HD-146 finding phrased over the category, so a fifth limiter is asked on the day it is
     * written. The behavioural half (each kind's counter moves) stays with each limiter's own test.
     */
    @Test
    void everyRefusalSiteIsInAFileThatCountsARateLimitHit() {
        var sources = productionSources();
        var offenders = new ArrayList<String>();
        int sites = 0;

        for (var entry : sources.entrySet()) {
            var lines = entry.getValue();
            String file = PublishedCredentials.repositoryPath(entry.getKey());
            for (int i = 0; i < lines.size(); i++) {
                if (!REFUSAL_SITE.matcher(lines.get(i)).find()) {
                    continue;
                }
                sites++;
                String countingFile = COUNTED_ELSEWHERE.getOrDefault(file, file);
                List<String> counting = sources.entrySet().stream()
                        .filter(e -> PublishedCredentials.repositoryPath(e.getKey()).equals(countingFile))
                        .map(Map.Entry::getValue).findFirst().orElse(List.of());
                if (counting.stream().noneMatch(l -> l.contains(REFUSAL_WITNESS))) {
                    offenders.add(file + " refuses (" + lines.get(i).strip() + ") and " + countingFile
                            + " never calls " + REFUSAL_WITNESS + " - every refusal counts "
                            + "hamstrack.ratelimit.hit{kind}; add the call before the throw, or name the "
                            + "file that counts it in COUNTED_ELSEWHERE with the reason");
                }
            }
        }

        assertThat(offenders).withFailMessage("\n" + String.join("\n", offenders)).isEmpty();
        assertThat(sites)
                .withFailMessage("Only %d refusal site(s) matched %s across src/main - below the floor "
                        + "of %d, so the pattern has stopped seeing them", sites, REFUSAL_SITE, MIN_REFUSAL_SITES)
                .isGreaterThanOrEqualTo(MIN_REFUSAL_SITES);
    }

    /** One branch that ends without a {@code failed_email} row, and the reason it must count. */
    record DropSite(String file, String method, String anchor, MailDropReason reason) {
        String where() {
            return file + " " + method.replace(" {", "") + " / " + anchor;
        }
    }

    private static final String MAIL_SERVICE = "src/main/java/com/hamstrack/common/mail/MailService.java";
    private static final String UNDELIVERABLE = "src/main/java/com/hamstrack/common/mail/UndeliverableMail.java";
    private static final String EXECUTOR = "src/main/java/com/hamstrack/common/async/MailTaskExecutor.java";

    /**
     * <strong>A named list, with a floor.</strong> Each anchor is matched exactly once inside its
     * method, so a branch that moves is red rather than silently unscanned; a NEW drop site
     * elsewhere is invisible until it is added here, and the failure message says so. The enum
     * side — a {@link MailDropReason} nobody emits — is held by the constant scan above.
     *
     * <p>The category is <em>every branch in the mail package that ends without a row</em>, not
     * the two classes this list first named: the two {@code MailService} branches (a best-effort
     * send that exhausted its attempts; a dead-letter INSERT that failed after retries) were
     * missed for a review round because the rule had been phrased over class names (HD-298).
     */
    static final List<DropSite> DROP_SITES = List.of(
            new DropSite(MAIL_SERVICE, "private void sendWithDurability(", "} else {",
                    MailDropReason.BEST_EFFORT),
            new DropSite(MAIL_SERVICE, "private void deadLetter(", "} catch (RuntimeException persistError) {",
                    MailDropReason.WRITE_FAILED),
            new DropSite(UNDELIVERABLE, "public boolean record(", "if (!MailService.isCritical(task.type())) {",
                    MailDropReason.BEST_EFFORT),
            new DropSite(UNDELIVERABLE, "public boolean record(", "if (failedEmailWriter.poolIsStarved()) {",
                    MailDropReason.POOL_CONTENDED),
            new DropSite(UNDELIVERABLE, "public boolean record(", "} else if (!claimNeverAttemptedRow()) {",
                    MailDropReason.HOURLY_CAP),
            new DropSite(UNDELIVERABLE, "public boolean record(", "} catch (RuntimeException e) {",
                    MailDropReason.WRITE_FAILED),
            new DropSite(UNDELIVERABLE, "public int recordAll(", "} else {",
                    MailDropReason.BEST_EFFORT),
            new DropSite(UNDELIVERABLE, "public int recordAll(", "} catch (RuntimeException e) {",
                    MailDropReason.WRITE_FAILED),
            new DropSite(EXECUTOR, "public void shutdown() {", "if (inFlight > 0) {",
                    MailDropReason.IN_FLIGHT_INTERRUPTED),
            new DropSite(EXECUTOR, "public void shutdown() {", "if (foreign > 0) {",
                    MailDropReason.FOREIGN_TASK));

    static final Pattern DROP_WITNESS = Pattern.compile("metrics\\.mailDropped\\(");

    /**
     * (c) Every mail-drop branch counts its reason <em>inside the block that decided it</em>
     * (HD-234): the block an anchor opens contains {@code metrics.mailDropped(MailDropReason.X)}
     * for the X the site declares.
     */
    @Test
    void everyMailDropBranchCountsItsReasonInsideTheBranch() {
        var offenders = new ArrayList<String>();
        for (DropSite site : DROP_SITES) {
            List<String> lines = rawLines(Path.of(site.file()));
            int[] method = methodRange(lines, site.method());
            if (method == null) {
                offenders.add(site.where() + ": the method signature was not found exactly once in "
                        + site.file() + " - it moved or was renamed; update DROP_SITES");
                continue;
            }
            int anchorAt = -1;
            int matches = 0;
            for (int i = method[0]; i <= method[1]; i++) {
                if (lines.get(i).strip().equals(site.anchor())) {
                    anchorAt = i;
                    matches++;
                }
            }
            if (matches != 1) {
                offenders.add(site.where() + ": the anchor matched " + matches + " time(s) inside the "
                        + "method (expected exactly one) - the branch moved; update DROP_SITES so the "
                        + "scan keeps seeing it");
                continue;
            }
            int blockEnd = closingBrace(lines, anchorAt);
            boolean counted = false;
            for (int i = anchorAt; i <= blockEnd; i++) {
                String line = code(lines.get(i));
                if (DROP_WITNESS.matcher(line).find() && line.contains("MailDropReason." + site.reason())) {
                    counted = true;
                }
            }
            if (!counted) {
                offenders.add(site.file() + ":" + (anchorAt + 1) + " (" + site.anchor() + ") drops mail "
                        + "and its block contains no metrics.mailDropped(MailDropReason." + site.reason()
                        + "). Every branch that ends without a failed_email row counts why - add the "
                        + "call inside the branch, before the log line. If the branch no longer drops, "
                        + "delete its DROP_SITES entry in the same change.");
            }
        }
        assertThat(offenders).withFailMessage("\n" + String.join("\n", offenders)).isEmpty();
        assertThat(DROP_SITES)
                .withFailMessage("DROP_SITES has %d entries, below its floor of %d", DROP_SITES.size(), MIN_DROP_SITES)
                .hasSizeGreaterThanOrEqualTo(MIN_DROP_SITES);
    }

    // ============================================================ timers — enumeration

    record Timer(Path unit, String schedule, long periodSeconds, long jitterSeconds) {
        long window() {
            return periodSeconds + jitterSeconds;
        }
    }

    static List<Timer> timers() {
        List<Timer> found;
        try (Stream<Path> tree = Files.walk(OPS)) {
            found = tree.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".timer"))
                    .sorted()
                    .map(OpsWitnessContractTest::parseTimer)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(found)
                .withFailMessage("Only %d *.timer unit(s) were found under %s - the walk has stopped "
                        + "seeing them, and the timer rule would pass on an empty set", found.size(), OPS)
                .hasSizeGreaterThanOrEqualTo(MIN_TIMERS);
        return found;
    }

    static Timer parseTimer(Path unit) {
        String onCalendar = null;
        String onUnitActive = null;
        String jitter = null;
        for (String raw : rawLines(unit)) {
            String line = raw.strip();
            if (line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            String key = line.substring(0, line.indexOf('=')).strip();
            String value = line.substring(line.indexOf('=') + 1).strip();
            switch (key) {
                case "OnCalendar" -> onCalendar = value;
                case "OnUnitActiveSec" -> onUnitActive = value;
                case "RandomizedDelaySec" -> jitter = value;
                default -> { }
            }
        }
        long period;
        if (onCalendar != null) {
            period = calendarPeriod(onCalendar, unit);
        } else if (onUnitActive != null) {
            period = seconds(onUnitActive, unit);
        } else {
            throw new AssertionError("Timer " + unit + " declares neither OnCalendar= nor OnUnitActiveSec=, "
                    + "so this test cannot tell how often it runs and therefore cannot check that a rule "
                    + "outlives its schedule. Teach OpsWitnessContractTest.parseTimer the directive it "
                    + "uses - do not exempt the timer.");
        }
        return new Timer(unit, onCalendar != null ? onCalendar : onUnitActive, period,
                jitter == null ? 0 : seconds(jitter, unit));
    }

    /** The period of a systemd calendar spec, in the spellings this repository uses. Anything else fails closed. */
    static long calendarPeriod(String spec, Path unit) {
        String s = spec.strip().toLowerCase(Locale.ROOT);
        switch (s) {
            case "minutely": return 60;
            case "hourly": return 3600;
            case "daily": return 86_400;
            case "weekly": return 604_800;
            default: break;
        }
        if (s.matches("\\*-\\*-\\* \\d{2}:\\d{2}(:\\d{2})?( utc)?")) {
            return 86_400;
        }
        if (s.matches("\\*-\\*-\\* \\*:\\d{2}(:\\d{2})?( utc)?")) {
            return 3600;
        }
        throw new AssertionError("Timer " + unit + " has OnCalendar=" + spec + ", a spelling this test cannot "
                + "turn into a period, so it cannot check that a rule outlives the schedule. Teach "
                + "OpsWitnessContractTest.calendarPeriod the form (systemd-analyze calendar '" + spec
                + "' shows the fires) - do not exempt the timer.");
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(s|sec|m|min|h|hour|d|)");

    /** {@code 600}, {@code 15m}, {@code 1h}, {@code 0m} - the forms a unit file and a rule's {@code for:} use. */
    static long seconds(String value, Object where) {
        var m = DURATION.matcher(value.strip());
        if (!m.matches()) {
            throw new AssertionError(where + ": cannot read a duration from '" + value + "' - teach "
                    + "OpsWitnessContractTest.seconds the unit");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "m", "min" -> n * 60;
            case "h", "hour" -> n * 3600;
            case "d" -> n * 86_400;
            default -> n;
        };
    }

    private static final Pattern FRESHNESS_GAUGE = Pattern.compile("#\\s*TYPE\\s+(\\w+_timestamp_seconds)\\s+gauge");

    /** Every {@code *_timestamp_seconds} gauge a script beside the timer declares. */
    static List<String> freshnessGauges(Timer timer) {
        var gauges = new LinkedHashSet<String>();
        try (Stream<Path> siblings = Files.list(timer.unit().getParent())) {
            for (Path script : siblings.filter(p -> p.getFileName().toString().endsWith(".sh")).sorted().toList()) {
                var m = FRESHNESS_GAUGE.matcher(PublishedCredentials.read(script));
                while (m.find()) {
                    gauges.add(m.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(gauges);
    }

    // ============================================================ alerting — enumeration

    record AlertRule(Path file, String title, Map<String, Object> body) {
        String where() {
            return file + " [" + title + "]";
        }

        String forDuration() {
            String hold = text(body.get("for"));
            return hold == null ? "0s" : hold;
        }

        /** {@code evaluator.params[0]} of the data entry the rule's {@code condition} names. */
        long threshold() {
            String condition = text(body.get("condition"));
            for (Object entry : list(body.get("data"))) {
                if (!String.valueOf(text(map(entry).get("refId"))).equals(condition)) {
                    continue;
                }
                for (Object c : list(map(map(entry).get("model")).get("conditions"))) {
                    List<?> params = list(map(map(c).get("evaluator")).get("params"));
                    if (!params.isEmpty() && params.getFirst() instanceof Number n) {
                        return n.longValue();
                    }
                }
            }
            throw new AssertionError(where() + ": no numeric evaluator.params[0] on its condition '"
                    + condition + "' - teach OpsWitnessContractTest the rule's shape");
        }

        List<String> expressions() {
            var found = new ArrayList<String>();
            for (Object entry : list(body.get("data"))) {
                String expr = text(map(map(entry).get("model")).get("expr"));
                if (expr != null) {
                    found.add(expr);
                }
            }
            return found;
        }
    }

    static List<Path> alertingFiles() {
        try (Stream<Path> tree = Files.walk(ALERTING)) {
            return tree.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches(".*\\.ya?ml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<AlertRule> alertRules() {
        var rules = new ArrayList<AlertRule>();
        for (Path file : alertingFiles()) {
            for (Object group : list(parse(file).get("groups"))) {
                for (Object rule : list(map(group).get("rules"))) {
                    rules.add(new AlertRule(file, String.valueOf(text(map(rule).get("title"))), map(rule)));
                }
            }
        }
        assertThat(rules)
                .withFailMessage("Only %d alert rule(s) were found under %s - the extraction no longer "
                        + "recognises groups: -> rules:", rules.size(), ALERTING)
                .hasSizeGreaterThanOrEqualTo(MIN_ALERT_RULES);
        return rules;
    }

    record Routing(Set<String> contactPoints, Map<AlertRule, Set<String>> resolved, List<String> offenders) { }

    /** Which contact point(s) each rule delivers to, and what could not be resolved. */
    static Routing routing() {
        var defined = new LinkedHashSet<String>();
        var rootReceivers = new LinkedHashSet<String>();
        var offenders = new ArrayList<String>();

        for (Path file : alertingFiles()) {
            var root = parse(file);
            for (Object contactPoint : list(root.get("contactPoints"))) {
                String name = text(map(contactPoint).get("name"));
                if (name != null) {
                    defined.add(name);
                }
            }
            List<?> policies = list(root.get("policies"));
            for (int i = 0; i < policies.size(); i++) {
                Map<String, Object> policy = map(policies.get(i));
                String receiver = text(policy.get("receiver"));
                if (receiver != null) {
                    rootReceivers.add(receiver);
                    if (!defined.contains(receiver) && !isDefinedAnywhere(receiver)) {
                        offenders.add("receiver '" + receiver + "' named by " + file + " policies[" + i
                                + "] is not defined by any contact point in the tree");
                    }
                }
                List<?> routes = list(policy.get("routes"));
                if (!routes.isEmpty()) {
                    offenders.add(file + " policies[" + i + "] has " + routes.size() + " nested route(s). This "
                            + "resolver reads the root receiver only; a nested route selects rules by "
                            + "matchers, which it does not evaluate. Teach routing() to apply "
                            + "object_matchers to each rule's labels before adding one - a rule quietly "
                            + "delivered to a receiver nobody drills is exactly what this test exists for");
                }
            }
        }

        var resolved = new LinkedHashMap<AlertRule, Set<String>>();
        for (AlertRule rule : alertRules()) {
            String own = text(map(rule.body().get("notification_settings")).get("receiver"));
            Set<String> points = own != null ? Set.of(own) : Set.copyOf(rootReceivers);
            if (own != null && !defined.contains(own)) {
                offenders.add(rule.where() + " names notification_settings.receiver '" + own
                        + "', which no contact point defines");
            }
            if (points.isEmpty()) {
                offenders.add(rule.where() + " resolves to no contact point: it names no receiver and no "
                        + "policy declares a root receiver");
            }
            resolved.put(rule, points);
        }
        return new Routing(defined, resolved, offenders);
    }

    /** The root receiver may be defined in a file walked later than policies.yml; check the whole tree. */
    private static boolean isDefinedAnywhere(String receiver) {
        for (Path file : alertingFiles()) {
            for (Object contactPoint : list(parse(file).get("contactPoints"))) {
                if (receiver.equals(text(map(contactPoint).get("name")))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ============================================================ drills — enumeration

    record Drill(LocalDate date, String contactPoint, int line) { }

    /** One line of the drill table as the parser saw it — header and separator included. */
    record DrillRow(int line, List<String> cells) { }

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * The dated rows under {@link #DRILL_HEADING}. Tripwires: the heading exists, the table's
     * header names a {@code Contact point} column, and the table holds at least one dated row
     * <em>or</em> the explicit {@code (empty — …)} placeholder — a reshaped table must fail here,
     * not pass as "no drill needed".
     */
    static List<Drill> drills() {
        var rows = new ArrayList<Drill>();
        boolean headerSeen = false;
        boolean placeholder = false;
        for (DrillRow row : drillRows()) {
            List<String> cells = row.cells();
            if (!headerSeen) {
                assertThat(cells.size() >= 2 && cells.get(1).equalsIgnoreCase("Contact point"))
                        .withFailMessage("the table under '%s' in %s no longer has 'Contact point' as its "
                                + "second column (header was %s) - update the table or this parser together",
                                DRILL_HEADING, HARDENING, cells)
                        .isTrue();
                headerSeen = true;
                continue;
            }
            if (cells.getFirst().matches("[-: ]+")) {
                continue;
            }
            if (cells.getFirst().contains("(empty")) {
                placeholder = true;
                continue;
            }
            var date = ISO_DATE.matcher(cells.getFirst());
            if (date.find()) {
                rows.add(new Drill(LocalDate.parse(date.group()), cells.get(1).replace("`", "").strip(), row.line()));
            }
        }
        assertThat(headerSeen)
                .withFailMessage("no table was found under '%s' in %s", DRILL_HEADING, HARDENING)
                .isTrue();
        assertThat(!rows.isEmpty() || placeholder)
                .withFailMessage("the table under '%s' in %s holds neither a dated row nor the explicit "
                        + "'(empty — …)' placeholder row - a table this parser cannot read must not pass "
                        + "as 'no drill needed'", DRILL_HEADING, HARDENING)
                .isTrue();
        return rows;
    }

    /** Every table line under {@link #DRILL_HEADING} up to the next heading, split into cells. */
    static List<DrillRow> drillRows() {
        List<String> lines = rawLines(HARDENING);
        int heading = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(DRILL_HEADING)) {
                heading = i;
                break;
            }
        }
        assertThat(heading)
                .withFailMessage("%s has no '%s' heading, so the delivery-drill table cannot be found. If "
                        + "it was renamed, rename DRILL_HEADING in OpsWitnessContractTest in the same "
                        + "change; the section is what this test reads for every contact point.",
                        HARDENING, DRILL_HEADING)
                .isNotNegative();

        var rows = new ArrayList<DrillRow>();
        for (int i = heading + 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("## ") || line.startsWith("### ")) {
                break;
            }
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = Arrays.stream(line.split("\\|", -1)).map(String::strip).toList();
            cells = cells.subList(1, Math.max(1, cells.size() - 1));
            if (cells.isEmpty()) {
                continue;
            }
            rows.add(new DrillRow(i + 1, cells));
        }
        return rows;
    }

    // ============================================================ sources — enumeration

    /** Every production source but {@code ProductMetrics.java}, as code lines (comments stripped). */
    static Map<Path, List<String>> productionSources() {
        var sources = new TreeMap<Path, List<String>>();
        try (Stream<Path> tree = Files.walk(MAIN)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.normalize().equals(PRODUCT_METRICS.normalize())) {
                    continue;
                }
                sources.put(file, rawLines(file).stream().map(OpsWitnessContractTest::code).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(sources)
                .withFailMessage("Only %d production source(s) under %s - the walk is broken", sources.size(), MAIN)
                .hasSizeGreaterThan(100);
        return sources;
    }

    static List<String> rawLines(Path file) {
        return PublishedCredentials.read(file).lines().toList();
    }

    /** The code on a line: a comment line becomes empty, an inline {@code //} tail is cut. */
    static String code(String line) {
        String s = line.strip();
        if (s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")) {
            return "";
        }
        int comment = s.indexOf("//");
        return comment >= 0 ? s.substring(0, comment) : s;
    }

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])'");

    /** The line's braces that count: string and char literals removed, comments removed. */
    private static String braces(String line) {
        return STRING_LITERAL.matcher(code(line)).replaceAll("").replaceAll("[^{}]", "");
    }

    /** {@code [first, last]} line indexes of the method whose signature contains {@code signature} exactly once. */
    static int[] methodRange(List<String> lines, String signature) {
        int start = -1;
        int matches = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(signature)) {
                start = i;
                matches++;
            }
        }
        if (matches != 1) {
            return null;
        }
        return new int[] {start, closingBrace(lines, start)};
    }

    /**
     * The index of the line closing the block opened on or after {@code from}. A {@code }} before
     * the first {@code {} is the previous block closing ({@code } catch (...) {}, {@code } else {})
     * and is not counted.
     */
    static int closingBrace(List<String> lines, int from) {
        int depth = 0;
        boolean opened = false;
        for (int i = from; i < lines.size(); i++) {
            for (char c : braces(lines.get(i)).toCharArray()) {
                if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}' && opened) {
                    depth--;
                }
            }
            if (opened && depth <= 0) {
                return i;
            }
        }
        return lines.size() - 1;
    }

    /** A path as the repository spells it, whatever the host's separator. */
    static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
