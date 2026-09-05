package com.hamstrack.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <strong>The credential-shape rule, written once (HD-200).</strong>
 *
 * <p>Two tests apply it — {@link EnvTemplateGuardTest} to the templates a reader copies, and
 * {@code com.hamstrack.common.security.JwtSecretValidationTest} to everything this
 * repository publishes — and they must not be able to disagree about what a credential is.
 * The first version of this rule was written as a claim about a <em>member</em>
 * ({@code grep JWT_SECRET}), which is why it sat next to
 * {@code DB_MONITOR_PASSWORD=a-strong-password} for a release without noticing it: a
 * {@code pg_monitor} login on the production database, published in a public repository,
 * under a "recommended for prod" heading. A rule phrased about the <em>category</em> —
 * anything <em>named</em> like a credential — covers the next one on the day it is written
 * rather than on the day somebody remembers.
 *
 * <p><strong>What counts as an assignment.</strong> The environment-variable form, which is
 * what every sample an operator copies uses: {@code VAR=value} or {@code VAR: value} at the
 * start of a line (a dotenv line, a compose {@code environment:} entry, a line in a fenced
 * code block), {@code -e VAR=value} / {@code export VAR=value} anywhere in a command, and
 * PowerShell's {@code $env:VAR="value"} — the dialect this repository's own Windows runbooks
 * are written in, and which the first two forms were each blind to for a different reason.
 * Prose that <em>mentions</em> an assignment mid-sentence is deliberately outside it: this
 * repository explains the {@code DB_PASSWORD=DB_PASSWORD} defect in several places, and a
 * rule that could not tell an explanation from a setting would be answered by deleting the
 * explanation.
 *
 * <p><strong>What makes a published value acceptable.</strong> Not "it looks strong" —
 * every value in here is public, so strength is irrelevant. One of:
 * <ol>
 *   <li><strong>empty</strong>, or an interpolation ({@code ${...}}, {@code $(...)},
 *       {@code $VAR}): not a value at all, and the guard that fires on absence gets to fire.
 *       An interpolation is those forms and not "anything opening with a {@code $}" — see
 *       {@link #isNotAValue}, where the looser reading exempted every bcrypt digest;</li>
 *   <li><strong>self-labelled as unfilled</strong> — {@code <a strong password>},
 *       {@code ...}. The angle brackets are the whole point of the convention:
 *       {@code <a strong password>} reads as a blank to fill in, where
 *       {@code a-strong-password} reads as a value somebody already chose, and the second
 *       one gets pasted into production;</li>
 *   <li><strong>self-labelled as a throwaway</strong> ({@code dev-only-…}, {@code ci-only-…},
 *       {@code drill-only-…}, {@code test-only-…}) — the label travels with the value into
 *       whatever file someone pastes it into, which a list of blessed strings does not;</li>
 *   <li><strong>refused by the application</strong>, which is the only ground that also
 *       reaches installations that already copied it;</li>
 *   <li><strong>a credential of the local development stack</strong> — see
 *       {@link #localDevStackCredentials()}.</li>
 * </ol>
 */
public final class PublishedCredentials {

    private PublishedCredentials() {
    }

    /** The repository root, as seen from the Maven module directory tests run in. */
    public static final Path REPO_ROOT = Path.of(".");

    /**
     * A name that says it holds a credential. Phrased as a property of the name rather than
     * as a list of names: anything called this is a credential, whoever adds it and whenever.
     *
     * <p>The suffix must be the whole name or follow an underscore, so {@code BYPASS} is not
     * a {@code PASS}. {@code _KEY_ID}, {@code _PWD}, {@code _PASS} and {@code _CREDENTIALS}
     * are here because the first version of this pattern ended at
     * {@code (PASSWORD|SECRET|TOKEN|KEY)} and a name is a credential by what it holds, not by
     * which four words we happened to think of.
     *
     * <p><strong>The trailing {@code _HASH} is a WIDENING, which is the direction this rule
     * permits</strong> — {@code JwtSecretValidationTest}'s failure message forbids
     * <em>narrowing</em> the pattern, and for a stated reason: a narrowing is permanent,
     * silent and applies to every file. Widening costs a false positive at worst, and here
     * the false-positive risk is near zero, because the optional suffix only ever attaches
     * to a name that was ALREADY credential-shaped: {@code X_PASSWORD_HASH},
     * {@code X_TOKEN_HASH}. A name holding a genuinely public digest is a {@code SHA},
     * a {@code CHECKSUM} or a {@code DIGEST}, none of which this pattern has ever matched,
     * and a bare {@code HASH} still matches nothing.
     *
     * <p>Anchoring the credential word at the END of the name meant a hash was invisible:
     * {@code LOAD_PASSWORD} was seen and {@code LOAD_PASSWORD_HASH} was not. This repository
     * states the opposing threat in its own words — the {@code .gitignore} block added by
     * HD-186 says <em>"A hash in a source-available repository whose plaintext is beside it
     * is a credential, not a digest"</em> — so it named a threat its own scanner could not
     * see, while {@code ops/loadtest/config.env.example} shipped exactly that line empty and
     * passed for the wrong reason. Filling it in was a silent pass.
     */
    public static final String CREDENTIAL_SHAPED =
            "(?:[A-Z][A-Z0-9]*_)*(?:PASSWORD|PASSWD|PASS|PWD|SECRET|CREDENTIALS|TOKEN|KEY_ID|KEY)"
            + "(?:_HASH)?";

    public static final Pattern CREDENTIAL_SHAPED_NAME = Pattern.compile(CREDENTIAL_SHAPED);

    /**
     * <strong>An unfilled blank is ONE token, whatever it contains.</strong>
     * {@code <a strong password>}, {@code <from .env>}, {@code <any unique string>} — remedy
     * 2 of this rule's own failure message, and every example that message offers has a space
     * in it. A dialect that ends a value at a delimiter therefore has to recognise the blank
     * <em>before</em> it applies its delimiters, or {@code <from .env>} arrives as
     * {@code <from}, stops matching {@link #UNFILLED}, and is reported as a working
     * credential — a refusal refusing the very form it prescribes.
     *
     * <p>Never across a line break: an unclosed {@code <} would otherwise swallow the
     * assignment below it, and a literal credential on the next line would be seen by
     * nothing.
     */
    public static final String UNFILLED_BLANK = "<[^>\\r\\n]*>";

    /**
     * The value of a dialect that ends its value at a delimiter — {@code delimiters} is the
     * body of a negated character class, e.g. {@code " \\t;"}. A quoted string and an
     * unfilled blank are each ONE token before those delimiters apply; only what is left is
     * a bare run of non-delimiters.
     *
     * <p>It exists so both of those are written once and a fifth dialect inherits them by
     * construction, and the reason it has to is this class's own history. The "a blank
     * contains spaces, so truncating it turns a placeholder into a word" reasoning was
     * written out in full on {@link #LINE_ASSIGNMENT} — the one dialect that cannot truncate
     * — and carried to neither sibling that can. That is <em>"a claim phrased about a
     * category outlives a claim phrased about a member"</em> (CLAUDE.md) committed against
     * itself, and it made {@code main} red on a runbook line that was doing exactly what the
     * failure message told it to do.
     *
     * <p>The quoted alternative is the same mistake read the other way round, and was found
     * by looking for it: the flag dialect ended its value <em>at</em> a quote, so
     * {@code export DB_PASSWORD='…'} produced the empty string and was allowed as "not a
     * value at all" — a published, working credential that the scan could not see.
     */
    private static String valueEndingAt(String delimiters) {
        return "\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|" + UNFILLED_BLANK + "|[^" + delimiters + "]*";
    }

    /**
     * {@code VAR=value} / {@code VAR: value} at the start of a line, optionally as a YAML
     * list item ({@code - VAR=value}). The value runs to the end of the line, so this is the
     * one dialect with no delimiter to stop early at and nothing to truncate — the rule its
     * siblings need is {@link #UNFILLED_BLANK}.
     */
    public static final Pattern LINE_ASSIGNMENT = Pattern.compile(
            "(?m)^[ \\t]*(?:-[ \\t]+)?(" + CREDENTIAL_SHAPED + ")[ \\t]*(?:=|:[ \\t]+)[ \\t]*(.*)$");

    /**
     * {@code -e VAR=value}, {@code --env VAR=value}, {@code export VAR=value} — anywhere in a
     * line, because a {@code docker run} in a runbook carries several on one. Here the value
     * ends at whitespace or at the end of the line: the next flag is not part of it, and
     * neither is the next line. A quoted string and an unfilled blank survive that whole
     * ({@link #valueEndingAt}) — {@code -e DB_PASSWORD=<from .env>} in a runbook is a blank
     * and is read as one, where ending it at the space made it {@code <from} and turned this
     * repository's own squash procedure into a published credential.
     *
     * <p><strong>{@code (?m)} is load-bearing.</strong> Without it the {@code ^} alternative
     * anchors to the start of the whole FILE, so the only way to be seen was to be preceded
     * by a space — and {@code export DEPLOY_TOKEN=…} in column 1 of a shell script, which is
     * how every {@code ops/**} script writes one, matched nothing at all. Found by planting
     * exactly that line to prove the operator-facing classification worked and watching the
     * scan stay quiet.
     */
    public static final Pattern FLAG_ASSIGNMENT = Pattern.compile(
            "(?m)(?:^|[ \\t])(?:-e|--env|export)[ \\t]+(" + CREDENTIAL_SHAPED + ")="
            + "(" + valueEndingAt(" \\t\"'`\\r\\n") + ")");

    /**
     * PowerShell's dialect — {@code $env:VAR="value"} — which is <strong>the one this
     * repository's own runbooks are written in</strong> and which the two patterns above are
     * each blind to for a different reason: the name is not at the start of a line, and
     * {@code $env:} is neither {@code export} nor a flag. The quotes are double, so
     * {@link #clean} strips them.
     *
     * <p>It was invisible while {@code docs/ops-prod-hardening.md} — production
     * configuration by this class's own classification — set {@code DB_PASSWORD} and
     * {@code JWT_SECRET} in exactly this form, twice each, and so does
     * {@code docs/design/production-backups-proposal.md}. Every one of those is
     * {@code drill-only-}labelled today, which is precisely why nothing went red and why it
     * would have stayed invisible until the day one was not.
     *
     * <p>{@code $env:} is matched case-insensitively because PowerShell resolves it that
     * way; the variable NAME is deliberately left case-sensitive, so widening this does not
     * quietly widen {@link #CREDENTIAL_SHAPED} into matching lowercase identifiers
     * everywhere else. The value ends at whitespace or a statement separator, because these
     * lines carry two assignments joined by {@code ;} — so an UNQUOTED blank needs
     * {@link #valueEndingAt} exactly as the flag dialect does. Only the quoted form was ever
     * safe here, and which of the two a runbook uses is its author's habit, not a guarantee;
     * the quoting this dialect used to spell out for itself now comes from the same shared
     * rule, so the two cannot disagree about what a quoted value is either.
     */
    public static final Pattern POWERSHELL_ASSIGNMENT = Pattern.compile(
            "\\$[Ee][Nn][Vv]:(" + CREDENTIAL_SHAPED + ")[ \\t]*=[ \\t]*"
            + "(" + valueEndingAt(" \\t;\\r\\n") + ")");

    /**
     * SQL's own dialect — the {@code PASSWORD} keyword followed by a quoted literal — and the
     * half of the {@code DB_MONITOR_PASSWORD} defect that carried the real weight: a
     * {@code CREATE ROLE hamstrack_exporter LOGIN PASSWORD} line in a runbook is pasted into
     * a production database as it stands, and the {@code .env} line beside it agreed with it,
     * so the pair worked. A rule that only understood {@code VAR=value} would have caught the
     * second line and let a lone {@code CREATE ROLE} through.
     *
     * <p>(Written here without an example of a quoted value on purpose: the scan reads this
     * file too, and an illustration would be one more published credential.)
     */
    public static final Pattern SQL_PASSWORD = Pattern.compile("(?i)PASSWORD[ \\t]+'([^']*)'");

    private static final List<String> SELF_LABELLED_THROWAWAY =
            List.of("dev-only", "ci-only", "drill-only", "test-only");

    /** {@code <a strong password>}, {@code ...}, {@code …} — a blank wearing a value's place. */
    private static final Pattern UNFILLED = Pattern.compile("<[^>]*>|\\.{3,}|…");

    /** One assignment found in one file. */
    public record Assignment(Path file, int line, String variable, String value) {

        public String describe() {
            return file + ":" + line + " publishes `" + variable + "=" + value + "`";
        }
    }

    /** Every credential-shaped assignment in {@code text}, in each dialect a reader pastes. */
    public static List<Assignment> assignments(Path file, String text) {
        var found = new ArrayList<Assignment>();
        for (Pattern form : List.of(LINE_ASSIGNMENT, FLAG_ASSIGNMENT, POWERSHELL_ASSIGNMENT)) {
            Matcher m = form.matcher(text);
            while (m.find()) {
                found.add(new Assignment(file, lineOf(text, m.start()), m.group(1), clean(m.group(2))));
            }
        }
        Matcher sql = SQL_PASSWORD.matcher(text);
        while (sql.find()) {
            found.add(new Assignment(file, lineOf(text, sql.start()), "PASSWORD", clean(sql.group(1))));
        }
        return found;
    }

    /**
     * Trailing shell continuation, an inline {@code #} comment and surrounding quotes are
     * punctuation around the value, not the value.
     */
    private static String clean(String raw) {
        String value = raw.strip();
        int comment = value.indexOf(" #");
        if (comment >= 0) {
            value = value.substring(0, comment).strip();
        }
        if (value.endsWith("\\")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        return unquote(value);
    }

    /**
     * One layer of matching surrounding quotes taken off, because they are punctuation around
     * the value and not the value. Shared so the two tests cannot disagree about whether
     * {@code VAR=''} carries something: {@link #clean} has always read it as empty (the file
     * is sourced by a shell, which reads it that way too), while the template guard compared
     * the RAW text and read the same line as a shipped credential. Nothing depended on the
     * disagreement until {@code LOAD_PASSWORD_HASH} became visible — the template ships that
     * line quoted on purpose, since it is sourced under {@code set -u} and a bcrypt digest
     * always contains {@code $}.
     */
    public static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * An expansion the shell (or Compose) resolves elsewhere: {@code ${VAR}}, {@code $(cmd)},
     * {@code $((…))}, {@code $VAR}.
     *
     * <p><strong>Deliberately not "starts with a {@code $}".</strong> That was the whole test,
     * and it is one character wider than the rule this class states for itself — wide enough
     * to swallow <em>every bcrypt digest ever written</em>, because they all open {@code $2}.
     * So the one value {@code LOAD_PASSWORD_HASH} can ever hold was classified as "not a value
     * at all", and widening the NAME to see that variable would have found it and let it
     * through: two latent gaps that compose into a rule staying green on exactly the input it
     * exists to refuse.
     *
     * <p>{@code $} followed by a digit is a positional parameter, which nothing publishes as a
     * setting — and, unquoted, is precisely how a pasted bcrypt hash breaks a sourced file.
     */
    private static final Pattern INTERPOLATION = Pattern.compile("^\\$[{(A-Za-z_]");

    public static boolean isNotAValue(String value) {
        return value.isEmpty() || INTERPOLATION.matcher(value).find();
    }

    public static boolean isUnfilledPlaceholder(String value) {
        return UNFILLED.matcher(value).find();
    }

    public static boolean isSelfLabelledThrowaway(String value) {
        return SELF_LABELLED_THROWAWAY.stream().anyMatch(value::contains);
    }

    /**
     * The credentials of the containers this repository starts on a developer's machine,
     * read out of the development compose files rather than listed here.
     *
     * <p>They are published on purpose — {@code docker compose up}, the README's run command
     * and CI all have to agree on them, and the dev Postgres password is also the exporter's
     * password in the dev observability stack, so "rename it to say it is a throwaway" is not
     * available. Deriving the set from what {@code docker compose up} actually creates means
     * this escape cannot be widened by adding a string to a list in a test: you would have to
     * change the containers.
     *
     * <p>It is deliberately <strong>not</strong> available in production configuration (see
     * {@link #isProductionConfiguration}), where the same value would be a live credential
     * rather than a localhost one.
     */
    public static Set<String> localDevStackCredentials() {
        var values = new LinkedHashSet<String>();
        for (Path compose : trackedFiles()) {
            if (!isDevelopmentCompose(compose)) {
                continue;
            }
            for (Assignment assignment : assignments(compose, read(compose))) {
                String value = assignment.value();
                // ${POSTGRES_PASSWORD:-hamstrack} — what an unconfigured `up` actually uses.
                Matcher withDefault = Pattern.compile("^\\$\\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)}$")
                        .matcher(value);
                if (withDefault.matches()) {
                    value = withDefault.group(1);
                }
                if (!isNotAValue(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /**
     * The path this file has <em>within the repository</em>, forward slashes, no leading
     * {@code ./} — so the two callers agree. {@code git ls-files} yields
     * {@code docs/self-hosting.md} and {@code Files.walk(".")} yields
     * {@code ./docs/self-hosting.md}, and a rule keyed on one of those forms silently does
     * nothing in the other caller.
     */
    public static String repositoryPath(Path file) {
        String path = file.normalize().toString().replace('\\', '/');
        return path.startsWith("./") ? path.substring(2) : path;
    }

    /**
     * {@code docker-compose.yml} and {@code docker-compose.*.dev.yml} <strong>at the
     * repository root</strong> — localhost, never a server.
     *
     * <p>The root requirement is the whole of the path-awareness. This was a filename test
     * regardless of directory, so a tracked {@code docker-compose.yml} in <em>any</em>
     * subdirectory — an example stack, a fixture, something vendored — would classify as
     * development, and every credential in it would join
     * {@link #localDevStackCredentials()}, which is exempt everywhere outside production
     * configuration. That is a repository-wide exemption granted by adding a file.
     */
    public static boolean isDevelopmentCompose(Path file) {
        String path = repositoryPath(file);
        if (path.contains("/")) {
            return false;
        }
        return path.equals("docker-compose.yml") || path.matches("docker-compose\\..*\\.dev\\.ya?ml");
    }

    /**
     * Everything an operator copies onto a server, or runs against one. The local-dev-stack
     * escape stops at this boundary, so a hard-coded {@code admin} in the production
     * observability stack is an offence even though the development one legitimately uses
     * it.
     *
     * <p><strong>An operator manual is configuration.</strong> This was a filename test —
     * env templates and non-development compose files — and one review round demonstrated
     * the consequence three times over: the quick-start compose and the restore drill in
     * {@code docs/self-hosting.md} both published a working Postgres password, and
     * {@code docs/observability.md} handed out a {@code CREATE ROLE … PASSWORD} line, and
     * all of them passed, because they are Markdown and Markdown was not "configuration".
     * The failure message of {@code JwtSecretValidationTest} already <em>said</em> that
     * production configuration meant "…and the quick-start compose in
     * {@code docs/self-hosting.md}"; the rule simply did not implement its own sentence.
     * What has always mattered here is whether a reader pastes the line into a terminal,
     * and the file extension has never had an opinion about that.
     */
    public static boolean isProductionConfiguration(Path file) {
        String path = repositoryPath(file);
        return isEnvTemplate(file)
                || OPERATOR_FACING_DOCUMENTS.contains(path)
                || path.startsWith("ops/")
                || file.getFileName().toString().matches("docker-compose.*\\.ya?ml")
                   && !isDevelopmentCompose(file);
    }

    /**
     * The documents written <em>to</em> the operator of a real installation, rather than
     * about the project. Enumerated, because "is an operator manual" is not a property of a
     * path — but the enumeration is small and the failure message points at it, so the next
     * runbook is added here rather than worked around.
     *
     * <p>{@code ops/**} is covered by prefix instead: those are scripts and systemd units
     * that run on the production box, where nothing is ever illustrative.
     */
    public static final Set<String> OPERATOR_FACING_DOCUMENTS = Set.of(
            "docs/self-hosting.md",
            "docs/observability.md",
            "docs/ops-prod-hardening.md");

    /**
     * An env-file template — something whose whole purpose is to be copied to {@code .env}.
     *
     * <p>Note the near miss that this project has already been bitten by once:
     * {@code .env.prod.example} does <em>not</em> end in {@code .env.example} (it ends in
     * {@code d.example}), which is why {@code .gitattributes} needs a line of its own for it
     * and why the second clause here is not redundant.
     */
    public static boolean isEnvTemplate(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".env.example") || name.equals(".env.prod.example");
    }

    /**
     * The files this repository publishes, read from the index rather than from the working
     * tree: an untracked local file is not something anybody else can read, and letting one
     * take part would mean a checkout could pass or fail on files that are not in it.
     */
    public static List<Path> trackedFiles() {
        var out = new ArrayList<Path>();
        try {
            var process = new ProcessBuilder("git", "ls-files", "-z")
                    .directory(REPO_ROOT.toFile())
                    .redirectErrorStream(true)
                    .start();
            String listing = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = process.waitFor();
            if (status != 0) {
                throw new IllegalStateException("`git ls-files` exited " + status + ": " + listing);
            }
            for (String entry : listing.split("\0")) {
                if (!entry.isBlank()) {
                    out.add(REPO_ROOT.resolve(entry).normalize());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("`git ls-files` listed nothing - this test reads the repository's "
                    + "own contents and must run from a checkout, at the module root (working directory was "
                    + REPO_ROOT.toAbsolutePath() + ")");
        }
        return out;
    }

    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
