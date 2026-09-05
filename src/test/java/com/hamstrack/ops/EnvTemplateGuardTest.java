package com.hamstrack.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-200 — no value in an env-file template may satisfy the guard that exists to
 * catch it, and no guarded variable may be missing from the template either.</strong>
 *
 * <p>Every guard protecting an installation fails on <em>absence</em>: {@code ${VAR:?…}} in
 * a compose file, an unresolvable {@code ${VAR}} in {@code application.properties}, the
 * length check on {@code JWT_SECRET}. A placeholder is the one thing that is not absent, so
 * a template that fills those lines in produces an installation that starts, works, and is
 * wrong. It shipped that way twice over: {@code DB_PASSWORD=DB_PASSWORD} does not fail, it
 * <em>agrees</em> — one line seeds both the Postgres container and the application's
 * datasource — and the {@code JWT_SECRET} placeholder was 34 bytes, i.e. long enough to
 * pass a check for 32.
 *
 * <p><strong>Why this is keyed to the guarded set and not to the placeholder text.</strong>
 * The obvious implementation is {@code grep REPLACE_WITH_}. It catches {@code JWT_SECRET}
 * and the Grafana password and misses {@code DB_PASSWORD=DB_PASSWORD} completely, because
 * the placeholder dialect differs — and the next placeholder will be in a third dialect. So
 * the guards are <em>enumerated from the files that declare them</em>: a new
 * {@code ${VAR:?…}} anywhere in a compose file puts that variable under this rule without
 * anybody adding it here, which is the opposite of a list that goes stale one entry before
 * anyone notices.
 *
 * <p><strong>Empty is a value; absent is not.</strong> The rule has two halves, and the
 * second was missing until a review pointed out that it followed from the first: a guarded
 * variable that appears nowhere in the template passes a check for "carries no value"
 * trivially. The reason a commented-out line is refused — it hides the variable's existence
 * from the reader who has to set it — is <em>more</em> true of a line that was never
 * written, and the reader's symptom is worse: a refusal naming a variable that does not
 * appear in the file they were told to copy.
 *
 * <p><strong>The trailing-CR half.</strong> {@code .gitattributes} pins these files to
 * {@code eol=lf}, and {@code .env.prod.example} needs a line of its own because the sibling
 * pattern {@code *.env.example} does not match a name ending in {@code d.example}. Without
 * the pin a Windows checkout ships {@code VAR=\r}, which is <em>non-empty</em>,
 * interpolates cleanly, and re-creates this entire defect through a door nobody is
 * watching. So "empty" here means empty including the carriage return, and the pin is
 * asserted twice: as a rule in {@code .gitattributes} and as its effect on the bytes in
 * this checkout.
 *
 * <p><strong>Where the file sets come from.</strong> The guards are read from
 * {@code git ls-files}, so an untracked local {@code docker-compose.override.yml} cannot
 * invent a requirement nobody else's checkout has; the templates are found by walking, so
 * an untracked one cannot escape the rule. Each source is picked so that an untracked file
 * can only make this stricter, never weaker.
 *
 * <p>The application-side half of the same rule — that the emptied {@code JWT_SECRET}, and
 * the credentials this project has published, are refused by the running application — is
 * {@code com.hamstrack.common.security.JwtSecretValidationTest}. Neither test stands in for
 * the other: this one stops the template handing out a working value, that one stops the
 * installations which already copied one from staying up.
 */
class EnvTemplateGuardTest {

    /**
     * The template that becomes the {@code .env} of the bundled compose project. The
     * guarded-variable rules below are about <em>that</em> relationship, so they apply to
     * this file and not to every template: {@code ops/backup/backup.env.example} is read by
     * a systemd unit that has never heard of {@code ${VAR:?…}}, and demanding
     * {@code DB_PASSWORD} in it would be nonsense. The credential and carriage-return rules
     * apply to all of them.
     */
    private static final Path COMPOSE_TEMPLATE = Path.of(".env.prod.example");

    private static final Path REPO_ROOT = Path.of(".");
    private static final Path GITATTRIBUTES = Path.of(".gitattributes");

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".gstack", ".idea", ".local", "target", "node_modules", "data", "logs");

    /**
     * Guarded variables that are deliberately NOT in the template, with the reason each is
     * allowed to be missing. {@code DB_URL} is set by {@code docker-compose.prod.yml} in the
     * app service's own {@code environment:} block ({@code jdbc:postgresql://postgres:5432/…},
     * the compose network's internal address) and is never read from {@code .env} — putting
     * it in the template would invite an operator to set a value that is then silently
     * overridden, which is a worse failure than an absence.
     *
     * <p>Adding an entry here is a claim that a reader never has to set the variable. If
     * they might, the entry is wrong: ship the line empty instead.
     */
    private static final Map<String, String> NOT_THE_OPERATOR_S_TO_SET = Map.of(
            "DB_URL", "docker-compose.prod.yml sets it in the app service's own environment: block "
                      + "(the compose network address) and never reads it from .env");

    /** {@code ${VAR:?message}} — Compose's own fail-fast. */
    private static final Pattern COMPOSE_GUARD = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*):\\?");

    /**
     * {@code ${VAR}} with no {@code :-default} in a Spring properties file: an unset value
     * is an unresolvable placeholder and the context refuses to start. Restricted to
     * SHOUTING_CASE so it matches environment variables and not property references.
     */
    private static final Pattern PROPERTY_GUARD = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    /** An enabled assignment. Deliberately anchored: prose in a comment is not a setting. */
    private static final Pattern ENABLED = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    /**
     * A DISABLED assignment, in this file's idiom — {@code #VAR=value}, no space after the
     * hash. The space is what separates a commented-out setting from a sentence that
     * happens to mention one, and this file's own comments quote {@code DB_PASSWORD=…} in
     * prose.
     */
    private static final Pattern DISABLED = Pattern.compile("^#([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    /**
     * The failure message is the checklist, deliberately rather than a comment: whoever
     * trips this is editing a template or adding a guard, and what they need is the rule
     * and what it costs to break it.
     */
    private static final String CHECKLIST = """

            .env.prod.example is copied to .env by every self-hoster and by the production \
            box, and THE RULE IS THAT NO VALUE IN IT MAY SATISFY ITS OWN GUARD (HD-200).

            Every guard fails on ABSENCE - ${VAR:?...} in a compose file, an unresolvable \
            ${VAR} in application.properties, the length check on JWT_SECRET - and a \
            placeholder is the one thing that is not absent. So a guarded variable ships \
            EMPTY (VAR=). Not a placeholder: that is an install which starts, works and is \
            wrong. Not a commented-out line, and not an omission either: both hide the \
            variable's existence from the reader who has to set it, and the second one \
            leaves them facing a refusal that names a variable their copy of the file does \
            not contain.

            What it costs when it is broken, in the three cases that shipped:

              * DB_USERNAME/DB_PASSWORD seed BOTH the Postgres container and the \
            application's datasource, so a placeholder does not disagree with anything - it \
            agrees with itself, and the instance comes up on a production database whose \
            password is printed in a public repository.

              * the JWT_SECRET placeholder was 34 bytes, so it PASSED the 32-byte check. \
            Every access token was signed with a string on GitHub; anyone who could read the \
            repository could mint one, including an administrator's.

              * SEED_ADMIN_PASSWORD repeated its own variable name, so every install made \
            from the unedited template had an ACTIVE system administrator whose email and \
            password were both published. Two strings and you are an admin - no forging, \
            nothing to guess, and the login backoff never engages, because a correct \
            password is not a failed attempt.

            If you are adding a guard you are adding a row here for free - the guarded set \
            is read out of the tracked docker-compose*.yml and application*.properties \
            files, never from a list in this file. Empty the template line and this passes.

            And "empty" means empty INCLUDING a trailing carriage return: a line ending \
            CR is not empty and interpolates cleanly, which is this same defect arriving \
            through .gitattributes instead of through the template.
            """;

    @Test
    void everyGuardedVariableShipsEmpty() throws IOException {
        var guarded = guardedVariables();
        var enabled = assignments(COMPOSE_TEMPLATE, ENABLED);
        var disabled = assignments(COMPOSE_TEMPLATE, DISABLED);
        var offences = new ArrayList<String>();

        for (var entry : guarded.entrySet()) {
            String name = entry.getKey();
            String declaredBy = entry.getValue();
            Assignment shipped = enabled.get(name);
            if (shipped != null && !isEmptyValue(shipped.value())) {
                offences.add(".env.prod.example:%d ships `%s=%s`, but %s guards it - ship `%s=`"
                        .formatted(shipped.line(), name, render(shipped.value()), declaredBy, name));
            }
            Assignment hidden = disabled.get(name);
            if (hidden != null) {
                offences.add((".env.prod.example:%d comments out `%s`, which %s requires - a commented-out "
                        + "line hides the variable's existence from the reader who has to set it; ship `%s=` "
                        + "instead").formatted(hidden.line(), name, declaredBy, name));
            }
        }

        assertThat(offences)
                .withFailMessage(CHECKLIST + "\nOffending lines:\n  " + String.join("\n  ", offences))
                .isEmpty();
    }

    /**
     * The other half of "empty, or it does not ship at all" — which turned out to be wrong
     * as written, because absence is the most complete way to hide a variable from its
     * reader. A guard nobody can see in the file they were told to copy is a refusal with
     * no instructions.
     */
    @Test
    void everyGuardedVariableIsNamedInTheTemplate() throws IOException {
        var enabled = assignments(COMPOSE_TEMPLATE, ENABLED);
        var disabled = assignments(COMPOSE_TEMPLATE, DISABLED);
        var offences = new ArrayList<String>();

        for (var entry : guardedVariables().entrySet()) {
            String name = entry.getKey();
            if (enabled.containsKey(name) || disabled.containsKey(name)) {
                continue;
            }
            String exemption = NOT_THE_OPERATOR_S_TO_SET.get(name);
            if (exemption == null) {
                offences.add(("`%s` is guarded by %s but appears nowhere in .env.prod.example - add `%s=` "
                        + "with a comment saying what it is. An operator who copies this template meets "
                        + "that guard as a refusal naming a variable their file does not contain")
                        .formatted(name, entry.getValue(), name));
            }
        }

        assertThat(offences)
                .withFailMessage(CHECKLIST + "\nMissing lines:\n  " + String.join("\n  ", offences)
                        + "\n\nIf a variable genuinely is not the operator's to set, add it to "
                        + "NOT_THE_OPERATOR_S_TO_SET with the reason - that is a claim they never have to "
                        + "set it, and it is wrong if they might.")
                .isEmpty();
    }

    /**
     * The guarded set is the load-bearing rule; this covers what it cannot reach.
     * {@code SEED_ADMIN_PASSWORD} had no fail-fast anywhere — a blank one logs a WARN and
     * skips seeding — and it shipped repeating its own variable name, so an unedited copy of
     * this template created a SYSTEM ADMINISTRATOR whose password is published. Keyed to the
     * shape of the name rather than to a list, so the next credential is covered on the day
     * it is added rather than on the day someone remembers.
     *
     * <p>Applies to every template, not only the compose one: {@code backup.env.example} is
     * copied to a production server as well, and was covered by nothing.
     */
    @Test
    void noTemplateShipsACredential() throws IOException {
        var offences = new ArrayList<String>();
        for (Path template : templates()) {
            for (Pattern form : List.of(ENABLED, DISABLED)) {
                assignments(template, form).forEach((name, a) -> {
                    if (PublishedCredentials.CREDENTIAL_SHAPED_NAME.matcher(name).matches()
                            && !isEmptyValue(a.value())) {
                        offences.add(("%s:%d ships `%s=%s` - a credential published in this repository is "
                                + "not a credential, whether or not anything refuses it at startup")
                                .formatted(template, a.line(), name, render(a.value())));
                    }
                });
            }
        }
        assertThat(offences)
                .withFailMessage(CHECKLIST + "\nOffending lines:\n  " + String.join("\n  ", offences))
                .isEmpty();
    }

    /**
     * The pin has to exist AND cover each filename, which is not the same thing: the
     * {@code *.env.example} pattern looks like it covers {@code .env.prod.example} and does
     * not, because that name ends in {@code d.example}. That near miss is the whole reason
     * the file needs a line of its own.
     */
    @Test
    void everyTemplateIsPinnedToLfByGitattributes() throws IOException {
        var patterns = Files.readAllLines(GITATTRIBUTES, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .filter(line -> line.contains("eol=lf"))
                .map(line -> line.split("\\s+")[0])
                .toList();

        var unpinned = new ArrayList<String>();
        for (Path template : templates()) {
            if (patterns.stream().noneMatch(pattern -> matchesName(pattern, template))) {
                unpinned.add(template.toString());
            }
        }

        assertThat(unpinned)
                .withFailMessage(CHECKLIST + "\nNo `eol=lf` pattern in .gitattributes matches: " + unpinned
                        + "\nBeware the near miss: `*.env.example` does NOT match a name ending in "
                        + "`d.example`, which is why .env.prod.example has a line of its own. Without the "
                        + "pin a Windows checkout ships a value that is one carriage return long - "
                        + "non-empty, interpolating cleanly, and undoing every emptied line above it "
                        + "silently.")
                .isEmpty();
    }

    /** What the pin is FOR, checked on the bytes rather than on the rule. */
    @Test
    void noTemplateLineCarriesACarriageReturn() throws IOException {
        var withCr = new ArrayList<String>();
        for (Path template : templates()) {
            var lines = rawLines(template);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).indexOf('\r') >= 0) {
                    withCr.add(template + ":" + (i + 1) + ": " + render(lines.get(i)));
                }
            }
        }
        assertThat(withCr)
                .withFailMessage(CHECKLIST + "\nLines carrying a carriage return: " + withCr
                        + "\nThis checkout has CRLF in a file pinned to LF, so an emptied line is not empty.")
                .isEmpty();
    }

    // ── enumeration ────────────────────────────────────────────────────────────────────

    /** variable name → the file and the form that guards it, for the failure message. */
    private static Map<String, String> guardedVariables() throws IOException {
        var guarded = new LinkedHashMap<String, String>();
        for (Path compose : composeFiles()) {
            var m = COMPOSE_GUARD.matcher(Files.readString(compose, StandardCharsets.UTF_8));
            while (m.find()) {
                guarded.putIfAbsent(m.group(1), compose.getFileName() + " (${" + m.group(1) + ":?...})");
            }
        }
        for (Path properties : propertyFiles()) {
            var m = PROPERTY_GUARD.matcher(Files.readString(properties, StandardCharsets.UTF_8));
            while (m.find()) {
                guarded.putIfAbsent(m.group(1), properties.getFileName() + " (${" + m.group(1) + "}, no default)");
            }
        }
        assertThat(guarded)
                .withFailMessage("No guards were found at all - the enumeration is broken, which would "
                        + "make every other assertion in this class vacuously true")
                .isNotEmpty();
        return guarded;
    }

    /**
     * Every tracked compose file, wherever it lives and whichever spelling of the extension
     * it uses. The first version listed the repository root only and matched {@code .yml}
     * exactly, so a {@code .yaml} sibling or a compose file one directory down would have
     * contributed zero guards — and contributed them silently, which is the shape of failure
     * this class exists to remove.
     */
    private static List<Path> composeFiles() {
        return PublishedCredentials.trackedFiles().stream()
                .filter(p -> p.getFileName().toString().matches("docker-compose.*\\.ya?ml"))
                .sorted()
                .toList();
    }

    /** Spring's own configuration, in either of the two formats Boot reads. */
    private static List<Path> propertyFiles() {
        return PublishedCredentials.trackedFiles().stream()
                .filter(p -> p.startsWith(Path.of("src/main/resources")))
                .filter(p -> p.getFileName().toString().matches("application.*\\.(properties|ya?ml)"))
                .sorted()
                .toList();
    }

    /**
     * Every env-file template in the checkout — walked rather than read from the index, so a
     * template that has not been committed yet is held to the rule too.
     */
    private static List<Path> templates() throws IOException {
        try (Stream<Path> tree = Files.walk(REPO_ROOT)) {
            var found = tree.filter(EnvTemplateGuardTest::isNotSkipped)
                    .filter(Files::isRegularFile)
                    .filter(PublishedCredentials::isEnvTemplate)
                    .map(Path::normalize)
                    .sorted()
                    .toList();
            assertThat(found)
                    .withFailMessage("No env-file templates were found at all - this test reads the "
                            + "repository's own copies, so it must run from the module root (working "
                            + "directory was %s)", REPO_ROOT.toAbsolutePath())
                    .contains(COMPOSE_TEMPLATE);
            return found;
        }
    }

    private static boolean isNotSkipped(Path path) {
        for (Path segment : path) {
            if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    private record Assignment(int line, String value) {}

    /**
     * <strong>{@code VAR=''} ships no value, and the two halves of this rule have to agree
     * about that.</strong> {@code PublishedCredentials.clean} has always taken one layer of
     * matching quotes off, so the repository-wide scan reads that line as empty — a raw
     * {@code isEmpty()} here read the same line as a shipped credential, and the disagreement
     * was invisible only because no template quoted an emptied line.
     *
     * <p>One does, deliberately: {@code ops/loadtest/config.env.example} is SOURCED under
     * {@code set -u} and a bcrypt digest always contains {@code $}, so the quotes on
     * {@code LOAD_PASSWORD_HASH=''} are the instruction for the operator who fills the line
     * in — {@code $2a$12$…} unquoted dies with {@code $2: unbound variable}, on a value that
     * looks correct in {@code cat}. Every dialect a template here is read by (a sourcing
     * shell, Compose's dotenv parser, systemd's {@code EnvironmentFile}) unquotes it too.
     *
     * <p>A trailing carriage return survives this, which is the point: {@code VAR=''\r} does
     * not end in a quote, so it is not unquoted and is still not empty.
     */
    private static boolean isEmptyValue(String raw) {
        return PublishedCredentials.unquote(raw).isEmpty();
    }

    private static Map<String, Assignment> assignments(Path template, Pattern form) throws IOException {
        var out = new LinkedHashMap<String, Assignment>();
        var lines = rawLines(template);
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = form.matcher(lines.get(i));
            if (m.matches()) {
                out.putIfAbsent(m.group(1), new Assignment(i + 1, m.group(2)));
            }
        }
        return out;
    }

    /**
     * Split on {@code \n} only, so a {@code \r} stays part of the value it would corrupt.
     * {@code Files.readAllLines} strips it and would turn the bug into a pass.
     */
    private static List<String> rawLines(Path template) throws IOException {
        return List.of(Files.readString(template, StandardCharsets.UTF_8).split("\n", -1));
    }

    /**
     * gitattributes globbing, for the two shapes these files use: a literal name and
     * {@code *.suffix}. Deliberately not a general glob engine — the near miss this guards
     * is a suffix that does not match, and a suffix comparison reproduces it exactly.
     */
    private static boolean matchesName(String pattern, Path file) {
        String name = file.getFileName().toString();
        if (pattern.startsWith("*")) {
            return name.endsWith(pattern.substring(1));
        }
        return pattern.equals(name) || pattern.equals("/" + name)
                || pattern.equals(file.toString().replace('\\', '/'));
    }

    private static String render(String value) {
        return value.replace("\r", "<CR>").replace("\t", "<TAB>");
    }
}
