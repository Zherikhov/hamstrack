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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p><strong>A value in this tree can be a program in another language.</strong> Every annotation
 * on an alert rule is a <em>Go template</em>, which the structural seals above cannot see: a
 * broken one is valid YAML, declares its refIds, and ships green — which is exactly what HD-189
 * did, three rules at once, discovered only by reading the deployed instance's log. So this class
 * also lexes every annotation as a template. That check is honest about being lexical rather than
 * a render, and says so where it lives: {@link #TEMPLATE_TRAP}.
 *
 * <p><strong>What it does not claim.</strong> It cannot say the running Grafana is happy — only a
 * box can answer that — and it deliberately says nothing about whether a threshold is well chosen
 * or whether a PromQL expression names a metric the exporters actually emit. Those need a
 * Prometheus. There is no Go runtime here either, so no annotation is ever actually rendered. What
 * it seals is the layer in between: the file parses, every rule refers only to things it declares,
 * and every annotation is a well-formed template naming only variables the alert context provides.
 *
 * <p><strong>Every assertion here is of the form "nothing offends", so the scan needs
 * tripwires</strong> — one per way the scan could go blind, and the set grows with the seals rather
 * than being a list to keep in step. {@link #MIN_PROVISIONED_FILES}: the walk still finds a tree (a
 * wrong working directory, a moved directory or a filter that stops matching would otherwise sweep
 * an empty set clean). {@link #MIN_ALERT_RULES}: the extraction still recognises {@code groups:} →
 * {@code rules:}. {@link #MIN_REFERENCES}: the refId cross-check still finds references to
 * cross-check. {@link #MIN_ANNOTATIONS}: the descent still finds annotations to lex.
 * {@link #MIN_TEMPLATE_ACTIONS}: the lexer still recognises an action, without which "no action
 * offends" is perfectly and vacuously true. Each floor sits deliberately <em>below</em> today's
 * count: it is a floor on the scan, not an inventory of the file, so deleting a rule on purpose is
 * not a red build while a parser that silently stops seeing declarations is.
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
     * Go's template delimiters, named rather than inlined so the failure messages below can print
     * an offending action back at its author without this file becoming unreadable in the places
     * that only want to <em>talk</em> about a brace pair.
     */
    private static final String OPEN = "{{";

    private static final String CLOSE = "}}";

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

    // -- the annotation-is-a-template seal (HD-189's second half) -----------------------

    /**
     * A floor on the annotation walk. Every rule in this tree carries a {@code summary}, so the
     * real number tracks the rule count; the floor sits below it because it guards the walk, not
     * the inventory.
     */
    private static final int MIN_ANNOTATIONS = 15;

    /**
     * A floor on the template lexer specifically. Every other assertion below is of the form
     * "no action offends", which an extractor that stopped recognising an action would satisfy
     * perfectly and vacuously — this is the one number that notices. The tree has always carried
     * several legitimate {@code $labels} actions inside {@code summary} strings.
     */
    private static final int MIN_TEMPLATE_ACTIONS = 6;

    /** The variables Grafana defines for a rule annotation. Anything else must be declared in-action. */
    private static final Set<String> GRAFANA_VARIABLES = Set.of("labels", "values", "value");

    /**
     * Contact-point settings Grafana expands as templates. Named so the guard below can refuse to
     * be silently overtaken by a template surface this class does not read.
     */
    private static final Set<String> TEMPLATED_SETTINGS =
            Set.of("message", "subject", "title", "text", "body", "description");

    /** {@code $k} and {@code $v} in {@code range $k, $v := $labels} — declared, therefore legitimate. */
    private static final Pattern DECLARED_VARIABLE =
            Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)(?=\\s*(?:,\\s*\\$[A-Za-z_][A-Za-z0-9_]*\\s*)?:=)");

    /**
     * The failure message is the whole explanation, because whoever trips this wrote an ordinary
     * English sentence and cannot see what is wrong with it.
     */
    private static final String TEMPLATE_TRAP = """

            EVERY ANNOTATION ON A GRAFANA ALERT RULE IS A GO TEMPLATE - `summary:` included. Before \
            Grafana renders an annotation, or puts it in an email, it expands the string through \
            text/template against its own context. An opening brace pair is therefore not text: it \
            opens an expression, and a `docker inspect --format` example pasted into prose is read \
            as "the field State of the alert context".

            THE BLAST RADIUS IS THE WHOLE ANNOTATION, NOT THE FRAGMENT. An expansion error is not \
            recovered from and does not degrade to a partial string: the annotation is dropped \
            ENTIRE and replaced by the error, so an operator who was owed a runbook gets \
            `Error in expanding template` instead - and only ever at the one moment the rule fires, \
            which is the moment the guidance existed for.

            IT IS INVISIBLE UNTIL THEN. A broken annotation is valid YAML: the file parses, the rule \
            provisions, the UI lists it, the suite is green. The only trace anywhere is one \
            `logger=ngalert.state.manager ... msg="Error in expanding template"` line per rule per \
            evaluation cycle in Grafana's log ON THE BOX. That is how HD-189 shipped three broken \
            rules past this very class, past two reviews that read the strings as prose, and past a \
            green build of 1480 tests; it was found by reading the deployed instance's log.

            WHAT TO WRITE INSTEAD: an example command with no braces in it at all. Pipe \
            `docker inspect` into `grep` and name the JSON field in prose - \
            `docker inspect <container> | grep -i oomkilled`, "the .State.OOMKilled flag" - instead \
            of a `--format` argument. Escaping the braces with Go's own quoting trick is REJECTED \
            here rather than accepted: it expands correctly and is unreadable, in a file whose \
            entire value is being readable under pressure, and the next reader copies the escaped \
            form back out into a shell, where it means nothing.

            WHAT STAYS LEGITIMATE: `$labels.x`, `$values.x`, `$value` and the control actions around \
            them (`if eq $labels.stage "upload"`, `else`, `end`, `range $k, $v := $labels`) are the \
            template's own variables and are meant to expand. This seal exists to keep those \
            working; it is not a ban on braces.

            WHAT THIS CHECK IS: a LEXICAL rule, not a render. There is no Go runtime in this suite \
            and no Java view of Grafana's `template.Data`, so nothing here proves that an annotation \
            renders. It proves that the string is a well-formed sequence of actions, that every \
            variable an action names is one Grafana provides or the action itself declares, and that \
            no action reaches into the root context with a dotted field - the exact shape that \
            broke. It is deliberately STRICTER than Grafana (a dotted root field is legal in a \
            NOTIFICATION template and is refused here, where $labels/$values say the same thing and \
            a dot is almost always a stray --format), and weaker in one direction: an action that is \
            well formed but names a function Grafana does not define parses here and still fails to \
            expand there.
            """;

    /**
     * Every annotation on every provisioned rule is a Go template Grafana can expand. The claim is
     * about the category — any annotation key on any rule the walk finds — and not about
     * {@code summary} on the three rules that broke; {@code summary} is simply the only key this
     * tree uses today, and a rule may carry others.
     */
    @Test
    void everyAnnotationIsATemplateGrafanaCanExpand() {
        var annotations = annotations();
        var offenders = new ArrayList<String>();
        int actionsChecked = 0;

        for (Annotation annotation : annotations) {
            if (annotation.value() == null) {
                offenders.add(annotation.where() + ": is empty, so the rule fires with no text");
                continue;
            }
            var flaws = new ArrayList<String>();
            List<String> actions = actions(annotation.value(), flaws);
            actionsChecked += actions.size();
            inspect(actions, flaws);
            for (String flaw : flaws) {
                offenders.add(annotation.where() + ": " + flaw);
            }
        }

        assertThat(offenders)
                .withFailMessage(TEMPLATE_TRAP + "\nAnnotations Grafana would fail to expand:\n  "
                        + String.join("\n  ", offenders))
                .isEmpty();

        assertThat(actionsChecked)
                .withFailMessage("Only %d template action(s) were found across %d annotation(s) under "
                        + "%s - the lexer has stopped recognising an action, and the assertion above "
                        + "it is of the form \"no action offends\", which an extractor that finds no "
                        + "actions satisfies perfectly and vacuously",
                        actionsChecked, annotations.size(), PROVISIONING)
                .isGreaterThanOrEqualTo(MIN_TEMPLATE_ACTIONS);
    }

    /**
     * The seal above reads rule annotations, and rule annotations only. Contact-point settings such
     * as {@code message} and {@code subject} are Go templates too, but they expand against a
     * different root ({@code template.Data} itself, where {@code .Alerts} and {@code .CommonLabels}
     * are the documented way to write one), so pointing the annotation rule at them would be wrong
     * rather than merely incomplete. None exist in this tree today — this makes the day one appears
     * a deliberate edit here instead of an omission that ships.
     */
    @Test
    void noTemplatedContactPointSettingSlipsPastThisClassUnnoticed() {
        var unchecked = new ArrayList<String>();
        int settingsSeen = 0;

        for (Path file : provisionedFiles()) {
            for (Object contactPoint : list(parse(file).get("contactPoints"))) {
                for (Object receiver : list(map(contactPoint).get("receivers"))) {
                    Map<String, Object> settings = map(map(receiver).get("settings"));
                    settingsSeen += settings.size();
                    for (String key : settings.keySet()) {
                        if (TEMPLATED_SETTINGS.contains(key.toLowerCase(Locale.ROOT))) {
                            unchecked.add(file + ": receiver '" + text(map(receiver).get("uid"))
                                    + "' setting '" + key + "'");
                        }
                    }
                }
            }
        }

        assertThat(unchecked)
                .withFailMessage(TEMPLATE_TRAP + """

                        THE SETTINGS BELOW ARE GO TEMPLATES THAT NOTHING IN THIS SUITE READS. \
                        everyAnnotationIsATemplateGrafanaCanExpand covers rule annotations only, and \
                        its no-dotted-root-field rule cannot simply be pointed here: a contact-point \
                        template expands against template.Data itself, where .Alerts, .Status and \
                        .CommonLabels are how one is legitimately written. So decide, then edit this \
                        class - either teach the lexer a notification-context mode and drop the key \
                        from TEMPLATED_SETTINGS, or, if this setting is plain text with no braces in \
                        it, say so in a comment here and drop it. Do not delete the test to make it \
                        quiet: a template surface no test reads is exactly how HD-189 reached \
                        production.

                        Templated contact-point settings found: """ + unchecked)
                .isEmpty();

        assertThat(settingsSeen)
                .withFailMessage("No contact-point setting was read under %s at all - the guard found "
                        + "nothing to guard, so it would stay green on a tree full of unchecked "
                        + "notification templates", PROVISIONING)
                .isPositive();
    }

    // -- the template lexer ------------------------------------------------------------

    /**
     * Splits an annotation into its template actions, recording an unclosed delimiter as a flaw.
     *
     * <p>Quoted terms are skipped while looking for the closing delimiter, because Go's own lexer
     * skips them: a closing pair inside a string literal ends nothing.
     */
    private static List<String> actions(String value, List<String> flaws) {
        var found = new ArrayList<String>();
        int at = 0;
        while ((at = value.indexOf(OPEN, at)) >= 0) {
            int close = closingDelimiter(value, at + 2);
            if (close < 0) {
                flaws.add("the action opened at offset " + at + " (\"" + excerpt(value, at)
                        + "\") is never closed - Go refuses to parse the template, so the annotation "
                        + "is lost whole");
                return found;
            }
            found.add(value.substring(at + 2, close));
            at = close + 2;
        }
        return found;
    }

    /** Where this action closes, or -1 if it never does. */
    private static int closingDelimiter(String value, int from) {
        for (int i = from; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                i = endOfLiteral(value, i);
                if (i < 0) {
                    return -1;
                }
                continue;
            }
            if (c == '}' && i + 1 < value.length() && value.charAt(i + 1) == '}') {
                return i;
            }
        }
        return -1;
    }

    /** The index of the closing quote of the literal opening at {@code from}, or -1 if unterminated. */
    private static int endOfLiteral(String value, int from) {
        char quote = value.charAt(from);
        for (int i = from + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != '`' && c == '\\') {
                i++;
                continue;
            }
            if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The rule itself, applied to one annotation's actions: no dotted reach into the root context,
     * no variable Grafana does not define, and no action whose only job is to smuggle a literal
     * brace back into the text.
     */
    private static void inspect(List<String> actions, List<String> flaws) {
        var declared = new LinkedHashSet<String>();
        for (String action : actions) {
            var matcher = DECLARED_VARIABLE.matcher(action);
            while (matcher.find()) {
                declared.add(matcher.group(1));
            }
        }

        for (String raw : actions) {
            String action = strip(raw);
            if (action.isEmpty()) {
                flaws.add(quote(raw) + " is an empty action, which Go refuses to parse");
                continue;
            }
            if (action.startsWith("/*")) {
                continue;
            }
            for (int i = 0; i < action.length(); i++) {
                char c = action.charAt(i);
                if (c == '"' || c == '\'' || c == '`') {
                    int end = endOfLiteral(action, i);
                    if (end < 0) {
                        flaws.add(quote(raw) + " has an unterminated quote");
                        break;
                    }
                    String literal = action.substring(i, end + 1);
                    if (literal.contains(OPEN) || literal.contains(CLOSE)) {
                        flaws.add(quote(raw) + " escapes a literal brace pair. Rejected on purpose: "
                                + "write the example with no braces at all (pipe into `grep`, name "
                                + "the field in prose) rather than leaving the reader to decode "
                                + literal);
                    }
                    i = end;
                    continue;
                }
                if (!termStart(action, i)) {
                    continue;
                }
                if (c == '.') {
                    flaws.add(quote(raw) + " reads `" + term(action, i) + "` off the root alert "
                            + "context. This is the HD-189 shape - an example command's `--format` "
                            + "argument pasted into prose. Grafana answers `can't evaluate field ... "
                            + "in type template.Data` and drops the whole annotation. Use "
                            + "$labels/$values, or write the command without braces");
                } else if (c == '$') {
                    String name = term(action, i).substring(1);
                    String head = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
                    if (head.isEmpty()) {
                        flaws.add(quote(raw) + " uses a bare `$`, which is the root alert context "
                                + "under another name - use $labels/$values");
                    } else if (!GRAFANA_VARIABLES.contains(head) && !declared.contains(head)) {
                        flaws.add(quote(raw) + " names `$" + head + "`, which Grafana does not define "
                                + "and this annotation does not declare. Go rejects an undefined "
                                + "variable at PARSE time, so the annotation is lost whole; the "
                                + "defined ones are $labels, $values and $value - note the plural on "
                                + "the first two");
                    }
                }
            }
        }
    }

    /** A term begins at a delimiter, never inside an identifier: {@code $values.B.Value} is one term. */
    private static boolean termStart(String action, int i) {
        if (i == 0) {
            return true;
        }
        char previous = action.charAt(i - 1);
        return Character.isWhitespace(previous) || previous == '(' || previous == '|' || previous == ',';
    }

    /** The term starting at {@code i}, so a failure names what it read rather than where it read it. */
    private static String term(String action, int i) {
        int end = i;
        while (end < action.length() && (action.charAt(end) == '.' || action.charAt(end) == '$'
                || action.charAt(end) == '_' || Character.isLetterOrDigit(action.charAt(end)))) {
            end++;
        }
        return action.substring(i, end);
    }

    /** An action body without its whitespace and {@code -} trim markers. */
    private static String strip(String action) {
        String stripped = action.strip();
        if (stripped.startsWith("-")) {
            stripped = stripped.substring(1).strip();
        }
        if (stripped.endsWith("-")) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        return stripped;
    }

    /** The offending action, delimiters and all, for the failure message. */
    private static String quote(String action) {
        return "`" + OPEN + action + CLOSE + "`";
    }

    private static String excerpt(String value, int at) {
        int end = Math.min(value.length(), at + 48);
        return value.substring(at, end) + (end < value.length() ? "..." : "");
    }

    /** One annotation, and enough of its address to find it without counting list items. */
    private record Annotation(Path file, String path, String owner, String key, String value) {
        String where() {
            return file + " [" + (owner == null ? path : owner) + "] annotations." + key;
        }
    }

    /**
     * Every annotation in the tree, found by descending into whatever the YAML actually is rather
     * than by walking {@code groups} → {@code rules} → {@code annotations}. A rule reorganised into
     * another file, or a nested shape Grafana grows later, is held to this rule on the day it lands.
     */
    private static List<Annotation> annotations() {
        var found = new ArrayList<Annotation>();
        for (Path file : provisionedFiles()) {
            collectAnnotations(file, parse(file), file.getFileName().toString(), null, found);
        }
        assertThat(found)
                .withFailMessage("Only %d annotation(s) were found under %s - the descent no longer "
                        + "recognises an `annotations:` map, so the template seal would be "
                        + "certifying an empty set", found.size(), PROVISIONING)
                .hasSizeGreaterThanOrEqualTo(MIN_ANNOTATIONS);
        return found;
    }

    private static void collectAnnotations(
            Path file, Object node, String path, String owner, List<Annotation> into) {
        if (node instanceof Map<?, ?> mapping) {
            String title = text(mapping.get("title"));
            String named = title != null ? title : owner;
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String here = path + "." + key;
                if ("annotations".equals(key) && entry.getValue() instanceof Map<?, ?> annotations) {
                    for (Map.Entry<?, ?> annotation : annotations.entrySet()) {
                        Object value = annotation.getValue();
                        // Only a scalar is a template. A Grafana DASHBOARD also has an `annotations`
                        // key, and it holds a list of annotation QUERIES - lexing its toString would
                        // be a check that reads its own debug output.
                        if (value instanceof Map<?, ?> || value instanceof List<?>) {
                            collectAnnotations(file, value, here + "." + annotation.getKey(), named, into);
                        } else {
                            into.add(new Annotation(file, here, named,
                                    String.valueOf(annotation.getKey()), text(value)));
                        }
                    }
                } else {
                    collectAnnotations(file, entry.getValue(), here, named, into);
                }
            }
        } else if (node instanceof List<?> items) {
            for (int i = 0; i < items.size(); i++) {
                collectAnnotations(file, items.get(i), path + "[" + i + "]", owner, into);
            }
        }
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
