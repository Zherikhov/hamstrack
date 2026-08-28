package com.hamstrack.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-189 — every provisioned Grafana file in this repository still loads, and every
 * alert rule in it is internally consistent.</strong>
 *
 * <p><strong>Why a test and not a review.</strong> Everything under
 * {@code observability/grafana/provisioning/} is <em>provisioned</em>: Grafana reads the whole
 * directory at start-up, and a file it refuses takes the directory with it. The refusal is not
 * quiet in Grafana — it exits 1 and crash-loops under {@code restart: unless-stopped} — but it is
 * silent in the <em>deploy</em>, because {@code docker compose up -d} exits 0 regardless (the
 * reasoning, and the measurement behind it, are at the top of {@code contactpoints.yml}). So a
 * malformed rules file does not degrade one alert: it removes {@code AppDown} and
 * {@code PostgresDown} along with everything else, and the only symptom is a dashboard nobody was
 * looking at. Nothing in this suite read these files before — this class is the first thing that
 * does.
 *
 * <p><strong>The claim is about the category, not about the three files that exist today.</strong>
 * The subject is "every YAML under the provisioning root", found by walking. A rule phrased about
 * {@code rules.yml} would be true and useless on the day a fourth file is added, which is the
 * failure mode this project keeps re-learning: a claim about a member goes stale one entry before
 * the list does.
 *
 * <p><strong>What it does not claim.</strong> It cannot say the running Grafana is happy — only a
 * box can answer that — and it deliberately says nothing about whether a threshold is well chosen
 * or whether a PromQL expression names a metric the exporters actually emit. Those need a
 * Prometheus. What it seals is the layer in between: the file parses, and every rule refers only
 * to things it declares.
 *
 * <p><strong>Every assertion here is of the form "nothing offends", so the scan needs
 * tripwires.</strong> Three, each guarding a different way the scan could go blind:
 * {@link #MIN_PROVISIONED_FILES} (the walk still finds a tree — a wrong working directory, a moved
 * directory or a filter that stops matching would otherwise sweep an empty set clean),
 * {@link #MIN_ALERT_RULES} (the extraction still recognises {@code groups:} → {@code rules:}), and
 * {@link #MIN_REFERENCES} (the refId cross-check still finds references to cross-check). Each floor
 * sits deliberately <em>below</em> today's count: it is a floor on the scan, not an inventory of
 * the file, so deleting a rule on purpose is not a red build while a parser that silently stops
 * seeing declarations is.
 */
class GrafanaProvisioningContractTest {

    /** Mounted at {@code /etc/grafana/provisioning} by {@code docker-compose.observability.yml}. */
    private static final Path PROVISIONING = Path.of("observability", "grafana", "provisioning");

    /**
     * A floor on the walk, not a census: the tree carries at least one file per provisioned concern
     * (alerting, dashboards, datasources), and alerting is itself split across several. Deliberately
     * not a count of what is there today — a number goes stale one entry before the list does, and
     * this one already had (it said "four" while naming five).
     */
    private static final int MIN_PROVISIONED_FILES = 4;

    /** A floor on the extraction: fewer rules than the tree has always carried means it stopped seeing them. */
    private static final int MIN_ALERT_RULES = 15;

    /** A floor on the cross-check: each rule contributes its {@code condition} and its expression inputs. */
    private static final int MIN_REFERENCES = 30;

    /** {@code $A} inside a math expression. A threshold's {@code expression:} is the bare refId. */
    private static final Pattern EXPRESSION_REF = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * The failure message is the propagation checklist, deliberately rather than a comment:
     * whoever trips this is editing a provisioned file, and what they need is what the directory
     * costs when it is wrong and what else moves with the change.
     */
    private static final String CHECKLIST = """

            observability/grafana/provisioning/ is read WHOLE by Grafana at start-up, and it is \
            all-or-nothing: a file Grafana refuses takes the entire directory with it, so a typo in \
            one alert rule silently removes AppDown and PostgresDown too.

            The failure is loud in Grafana (exit 1, crash-loop under restart: unless-stopped) and \
            SILENT in the deploy - `docker compose up -d` exits 0 anyway, so automation reports a \
            successful deployment of an instance that is watching nothing. Reasoning and the \
            measurement behind it: observability/grafana/provisioning/alerting/contactpoints.yml.

            What each seal here is for:

              * it parses, and no key is defined twice - Grafana's YAML reader rejects a duplicate \
            key outright, and a duplicate that IS accepted quietly wins over the one you can see.

              * apiVersion is present - Grafana skips a provisioning file that does not declare one, \
            so such a file is not "invalid", it is ABSENT: no error, no rules, no alerts.

              * uid unique - the uid is a rule's identity across restarts and the key everything else \
            references (ops/drift/*, docs/ops-prod-hardening.md, and ApplyConfigPinGuardTest, which \
            names DeployImagePinned). Two rules sharing a uid are one rule.

              * title unique - the title is what an operator reads in an email and searches for in a \
            runbook, and policies.yml groups notifications BY alertname. Two rules sharing a title \
            are indistinguishable in exactly the moment they matter.

              * every reference names a declared refId - `condition:` and every expression input must \
            name a refId the rule's own `data:` block declares. A condition pointing at a refId that \
            is not there is the most expensive shape of this bug: the rule provisions, shows up in \
            the UI, and never fires.

            A new alert rule also wants a `summary` naming a first move (this file's convention), and \
            if it is host- or deployment-specific, a note in docs/observability.md.
            """;

    // -- the seals ---------------------------------------------------------------------

    @Test
    void everyProvisionedFileParsesAndDeclaresItsApiVersion() {
        var offenders = new ArrayList<String>();
        for (Path file : provisionedFiles()) {
            Map<String, Object> root;
            try {
                root = parse(file);
            } catch (RuntimeException e) {
                offenders.add(file + ": does not parse - " + firstLine(e));
                continue;
            }
            if (root.isEmpty()) {
                offenders.add(file + ": parses to nothing (empty document, or not a mapping)");
                continue;
            }
            if (root.get("apiVersion") == null) {
                offenders.add(file + ": no apiVersion - Grafana skips the file instead of failing");
            }
        }
        assertThat(offenders)
                .withFailMessage(CHECKLIST + "\nFiles Grafana would refuse or skip: " + offenders)
                .isEmpty();
    }

    @Test
    void everyAlertRuleIsUniquelyIdentified() {
        var offenders = new ArrayList<String>();
        var uids = new LinkedHashSet<String>();
        var titles = new LinkedHashSet<String>();

        for (Rule rule : alertRules()) {
            String uid = text(rule.body().get("uid"));
            String title = text(rule.body().get("title"));
            if (uid == null) {
                offenders.add(rule.where() + ": no uid");
            } else if (!uids.add(uid)) {
                offenders.add(rule.where() + ": uid '" + uid + "' is already used by another rule");
            }
            if (title == null) {
                offenders.add(rule.where() + ": no title");
            } else if (!titles.add(title)) {
                offenders.add(rule.where() + ": title '" + title + "' is already used by another rule");
            }
        }

        assertThat(offenders)
                .withFailMessage(CHECKLIST + "\nRules that are not uniquely identified: " + offenders)
                .isEmpty();
    }

    @Test
    void everyRuleReferencesOnlyRefIdsItDeclares() {
        var rules = alertRules();
        var offenders = new ArrayList<String>();
        int referencesChecked = 0;

        for (Rule rule : rules) {
            var declared = new LinkedHashSet<String>();
            List<?> data = list(rule.body().get("data"));
            if (data.isEmpty()) {
                offenders.add(rule.where() + ": no data block, so it queries nothing");
                continue;
            }
            for (Object entry : data) {
                Map<String, Object> node = map(entry);
                String refId = text(node.get("refId"));
                if (refId == null) {
                    offenders.add(rule.where() + ": a data entry declares no refId");
                } else if (!declared.add(refId)) {
                    offenders.add(rule.where() + ": refId '" + refId + "' is declared twice");
                }
                // The refId is written twice - once on the entry, once inside the model - and
                // Grafana reads the inner one. Two spellings that disagree is a rule wired to a
                // query that does not exist, which reads as correct in the file.
                String modelRefId = text(map(node.get("model")).get("refId"));
                if (refId != null && modelRefId != null && !modelRefId.equals(refId)) {
                    offenders.add(rule.where() + ": data entry '" + refId + "' carries model.refId '"
                            + modelRefId + "'");
                }
            }

            // The condition - what actually decides whether the rule fires.
            referencesChecked++;
            String condition = text(rule.body().get("condition"));
            if (condition == null) {
                offenders.add(rule.where() + ": no condition");
            } else if (!declared.contains(condition)) {
                offenders.add(rule.where() + ": condition '" + condition
                        + "' is not one of its refIds " + declared);
            }

            // Every server-side expression's input, in both spellings: a bare refId (threshold,
            // reduce) and $A inside a math expression.
            for (Object entry : data) {
                String expression = text(map(map(entry).get("model")).get("expression"));
                if (expression == null) {
                    continue;
                }
                for (String reference : references(expression)) {
                    referencesChecked++;
                    if (!declared.contains(reference)) {
                        offenders.add(rule.where() + ": expression '" + expression + "' names refId '"
                                + reference + "', which the rule does not declare " + declared);
                    }
                }
            }
        }

        assertThat(offenders)
                .withFailMessage(CHECKLIST + "\nRules referring to refIds they do not declare: " + offenders)
                .isEmpty();

        assertThat(referencesChecked)
                .withFailMessage("Only %d refId reference(s) were checked across %d rule(s) - the "
                        + "cross-check found nothing to cross-check, so this test would stay green on "
                        + "a file whose conditions all pointed at nothing",
                        referencesChecked, rules.size())
                .isGreaterThanOrEqualTo(MIN_REFERENCES);
    }

    /**
     * The same all-or-nothing directory, one layer up: a policy routing to a receiver that no
     * contact point defines is a tree Grafana refuses, and losing the provisioned policy is not
     * "no notifications" — Grafana's built-in fallback receiver mails a live third-party domain
     * (see {@code policies.yml}). Kept in this class rather than in one of its own because it is
     * the same claim about the same directory: everything in it must resolve inside it.
     */
    @Test
    void everyNotificationPolicyRoutesToAContactPointThisTreeDefines() {
        var offenders = new ArrayList<String>();
        var defined = new LinkedHashSet<String>();
        var receiverUids = new LinkedHashSet<String>();
        var routed = new ArrayList<String>();

        for (Path file : provisionedFiles()) {
            Map<String, Object> root = parse(file);
            for (Object contactPoint : list(root.get("contactPoints"))) {
                String name = text(map(contactPoint).get("name"));
                if (name == null) {
                    offenders.add(file + ": a contact point has no name");
                } else {
                    defined.add(name);
                }
                for (Object receiver : list(map(contactPoint).get("receivers"))) {
                    String uid = text(map(receiver).get("uid"));
                    if (uid != null && !receiverUids.add(uid)) {
                        offenders.add(file + ": receiver uid '" + uid + "' is used twice");
                    }
                }
            }
            collectReceivers(list(root.get("policies")), routed);
        }

        for (String receiver : routed) {
            if (!defined.contains(receiver)) {
                offenders.add("a notification policy routes to receiver '" + receiver
                        + "', which no contact point in this tree defines " + defined);
            }
        }

        assertThat(offenders)
                .withFailMessage(CHECKLIST + "\nNotification routing that does not resolve: " + offenders)
                .isEmpty();

        assertThat(defined)
                .withFailMessage("No contact point was found under %s at all - the scan is broken, "
                        + "which would make the routing check vacuously true", PROVISIONING)
                .isNotEmpty();
        assertThat(routed)
                .withFailMessage("No notification policy was found under %s at all - the scan is "
                        + "broken, and an alerting tree with no policy of its own delivers to "
                        + "Grafana's built-in fallback receiver, which is addressed to a live "
                        + "third-party domain", PROVISIONING)
                .isNotEmpty();
    }

    // -- enumeration -------------------------------------------------------------------

    /**
     * Every provisioned YAML in the tree, found by walking rather than by name, so a file added
     * next week is held to these rules on the day it lands.
     */
    private static List<Path> provisionedFiles() {
        assertThat(PROVISIONING)
                .withFailMessage("%s was not found - this test reads the repository's own copy of the "
                        + "provisioning tree, so it must run from the module root (working directory "
                        + "was %s)", PROVISIONING, Path.of(".").toAbsolutePath())
                .isDirectory();
        try (Stream<Path> tree = Files.walk(PROVISIONING)) {
            var found = tree.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches(".*\\.ya?ml"))
                    .sorted()
                    .toList();
            assertThat(found)
                    .withFailMessage("Only %d provisioned YAML file(s) were found under %s - the walk "
                            + "has stopped seeing the tree, and every assertion in this class would "
                            + "pass on an empty set", found.size(), PROVISIONING)
                    .hasSizeGreaterThanOrEqualTo(MIN_PROVISIONED_FILES);
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every alert rule in the tree, wherever it is declared. */
    private static List<Rule> alertRules() {
        var rules = new ArrayList<Rule>();
        for (Path file : provisionedFiles()) {
            for (Object group : list(parse(file).get("groups"))) {
                Map<String, Object> body = map(group);
                String name = text(body.get("name"));
                List<?> members = list(body.get("rules"));
                for (int i = 0; i < members.size(); i++) {
                    rules.add(new Rule(file, name, i, map(members.get(i))));
                }
            }
        }
        assertThat(rules)
                .withFailMessage("Only %d alert rule(s) were found under %s - the extraction no longer "
                        + "recognises `groups:` -> `rules:`, so the uniqueness and refId seals would be "
                        + "certifying nothing", rules.size(), PROVISIONING)
                .hasSizeGreaterThanOrEqualTo(MIN_ALERT_RULES);
        return rules;
    }

    /** The receiver of every policy and of every nested route, at any depth. */
    private static void collectReceivers(List<?> policies, List<String> into) {
        for (Object policy : policies) {
            Map<String, Object> body = map(policy);
            String receiver = text(body.get("receiver"));
            if (receiver != null) {
                into.add(receiver);
            }
            collectReceivers(list(body.get("routes")), into);
        }
    }

    /** A rule and where to find it, so a failure names a place rather than an index. */
    private record Rule(Path file, String group, int index, Map<String, Object> body) {
        String where() {
            String title = text(body.get("title"));
            return file + " [" + group + " #" + index + (title == null ? "" : " " + title) + "]";
        }
    }

    // -- parsing -----------------------------------------------------------------------

    /**
     * Parsed, not regex-matched: this tree is mostly comments on purpose, and a {@code uid:}
     * mentioned in a comment must not satisfy a check for a uid.
     *
     * <p>Duplicate keys are an error here because they are an error in Grafana's reader too (Go's
     * yaml.v3), and because a duplicate that IS tolerated is the worse failure: the second value
     * wins over the one whose comment explains it.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path file) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            Object root = new Yaml(options).load(Files.readString(file, StandardCharsets.UTF_8));
            return root instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> references(String expression) {
        var found = new ArrayList<String>();
        var matcher = EXPRESSION_REF.matcher(expression);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        // No `$A` anywhere: a threshold/reduce input, which is the bare refId itself.
        return found.isEmpty() ? List.of(expression) : found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    /** A present, non-blank scalar, rendered the way YAML handed it over. */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String rendered = String.valueOf(value).strip();
        return rendered.isEmpty() ? null : rendered;
    }

    private static String firstLine(RuntimeException e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
