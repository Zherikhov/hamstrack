package com.hamstrack.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    // "Not the operator's to set" used to be a hand-kept map, and its only entry was DB_URL,
    // excused because docker-compose.prod.yml supplies it as a literal under `environment:`
    // (the compose network address) so .env can never reach it. That reason is a PROPERTY of
    // the compose file, and it is now read off the file itself — see literalEnvironmentNames.
    // Measured when the derivation landed: emptying the map left this class green, i.e. the
    // entry had become an exclusion excusing nothing, which is the kind that outlives its
    // subject. Deleted rather than kept as a belt, because a list nothing needs is a list
    // nobody re-checks.

    /** {@code ${VAR:?message}} — Compose's own fail-fast. */
    private static final Pattern COMPOSE_GUARD = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*):\\?");

    /**
     * {@code ${VAR:-default}} — Compose's other half: a DIAL the stack offers, rather than a value
     * it refuses to start without. Nothing goes red when one of these is missing from the template,
     * which is exactly how eight of them shipped invisible in {@code deploy/cloud/} (HD-317).
     */
    private static final Pattern COMPOSE_DIAL = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*):-");

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
    /**
     * The floor that stops the derived pairing from quietly matching nothing. Two is not a
     * count of what exists — it is the statement that the rule is about a CATEGORY: one pair
     * is indistinguishable from the hard-coded single path this replaced.
     */
    private static final String PAIRING_FLOOR = """

            Fewer than two template/stack pairs were found, so this rule has collapsed back \
            into a claim about one file - which is the shape that let a second stack ship \
            with nothing checking its template at all.

            A pair is an env template and the compose files in its OWN directory. If you \
            moved a template away from its stack, move it back or teach pairs() how the two \
            now find each other.""";

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
        var offences = new ArrayList<String>();
        List<Pair> pairs = pairs();

        for (Pair pair : pairs) {
            var enabled = assignments(pair.template(), ENABLED);
            var disabled = assignments(pair.template(), DISABLED);
            for (var entry : guardedVariables(pair).entrySet()) {
                String name = entry.getKey();
                String declaredBy = entry.getValue();
                Assignment shipped = enabled.get(name);
                if (shipped != null && !isEmptyValue(shipped.value())) {
                    offences.add("%s:%d ships `%s=%s`, but %s guards it - ship `%s=`"
                            .formatted(pair.describe(), shipped.line(), name, render(shipped.value()),
                                    declaredBy, name));
                }
                Assignment hidden = disabled.get(name);
                if (hidden != null) {
                    offences.add(("%s:%d comments out `%s`, which %s requires - a commented-out line hides "
                            + "the variable's existence from the reader who has to set it; ship `%s=` instead")
                            .formatted(pair.describe(), hidden.line(), name, declaredBy, name));
                }
            }
        }

        assertThat(pairs)
                .withFailMessage(PAIRING_FLOOR)
                .hasSizeGreaterThanOrEqualTo(2);
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
        var offences = new ArrayList<String>();
        List<Pair> pairs = pairs();

        for (Pair pair : pairs) {
            var enabled = assignments(pair.template(), ENABLED);
            var disabled = assignments(pair.template(), DISABLED);
            for (var entry : guardedVariables(pair).entrySet()) {
                String name = entry.getKey();
                if (enabled.containsKey(name) || disabled.containsKey(name)) {
                    continue;
                }
                offences.add(("`%s` is guarded by %s but appears nowhere in %s - add `%s=` with a "
                        + "comment saying what it is. An operator who copies that template meets the "
                        + "guard as a refusal naming a variable their file does not contain. (If the "
                        + "stack supplies it itself, say so by setting it as a LITERAL under "
                        + "`environment:` - that is read off the file and excuses it here.)")
                        .formatted(name, entry.getValue(), pair.describe(), name));
            }
        }

        assertThat(pairs)
                .withFailMessage(PAIRING_FLOOR)
                .hasSizeGreaterThanOrEqualTo(2);

        assertThat(offences)
                .withFailMessage(CHECKLIST + "\nMissing lines:\n  " + String.join("\n  ", offences)
                        + "\n\nIf a variable genuinely is not the operator's to set, the stack has to say "
                        + "so where a reader can see it: give it a LITERAL value under `environment:` in "
                        + "that directory's compose file. There is no list here to add it to, on purpose.")
                .isEmpty();
    }

    /**
     * <strong>Every dial a shipped stack offers is named in that stack's template.</strong>
     *
     * <p>The two rules above are about {@code ${VAR:?}} — values the stack refuses to start
     * without, where the operator meets a refusal. This one is about {@code ${VAR:-default}}, and
     * its failure is silent in both directions: the stack starts, the default applies, and the
     * variable that would move it exists nowhere the operator can see. Setting it in {@code .env}
     * does nothing they can tell apart from setting it correctly, because they never learn the name.
     *
     * <p>{@code deploy/dc/docker-compose.yml} states the rule in its own words, about the Postgres
     * memory dials: <em>"as variables rather than literals: a literal here is a value your .env
     * cannot reach"</em>, and <em>"it exists so the matching dial is in .env when you do"</em>. It
     * then shipped with every one of them in its template while {@code deploy/cloud/} shipped with
     * <strong>eight</strong> interpolated names appearing nowhere in its own — the Postgres dials,
     * the shared-memory size, the stop grace, both MinIO image pins and the client's memory ceiling.
     * Measured at the time: the set difference was 8 for cloud and 0 for dc, and nothing in the
     * suite was looking, because {@link #everyGuardedVariableIsNamedInTheTemplate} only ever asked
     * about the {@code :?} half.
     *
     * <p><strong>The one exclusion, and it is checked rather than asserted.</strong> A development
     * compose file's dials are localhost values nobody sets in a shipped template
     * ({@code docker-compose.dev.yml} offers {@code POSTGRES_PASSWORD} and {@code POSTGRES_USER}
     * with the throwaway credentials {@code docker compose up} creates), and at the repository root
     * {@link #pairs()} groups the development files together with the production ones. So a
     * development compose file is excluded — by {@link PublishedCredentials#isDevelopmentCompose},
     * the same predicate the credential scan uses, never by a filename here — and the set it
     * admits is printed by every failure message below, including the one that fires when it
     * admits nothing at all. An exclusion excusing nothing is not a belt; it is a line nobody
     * re-reads.
     */
    @Test
    void everyDialAStackOffersIsNamedInItsTemplate() throws IOException {
        var offences = new ArrayList<String>();
        var admittedByTheExclusion = new LinkedHashMap<String, String>();
        int examined = 0;
        List<Pair> pairs = pairs();

        for (Pair pair : pairs) {
            var enabled = assignments(pair.template(), ENABLED);
            var disabled = assignments(pair.template(), DISABLED);
            for (Path compose : pair.composeFiles()) {
                boolean development = PublishedCredentials.isDevelopmentCompose(compose);
                var dial = COMPOSE_DIAL.matcher(
                        withoutComments(Files.readString(compose, StandardCharsets.UTF_8)));
                while (dial.find()) {
                    String name = dial.group(1);
                    if (development) {
                        admittedByTheExclusion.putIfAbsent(
                                name, PublishedCredentials.repositoryPath(compose));
                        continue;
                    }
                    examined++;
                    if (enabled.containsKey(name) || disabled.containsKey(name)) {
                        continue;
                    }
                    offences.add("%s offers `${%s:-...}` and %s never names it".formatted(
                            PublishedCredentials.repositoryPath(compose), name, pair.describe()));
                }
            }
        }

        assertThat(pairs)
                .withFailMessage(PAIRING_FLOOR)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(examined)
                .withFailMessage("""
                        Fewer than 30 dials were examined across every shipped stack, so this rule \
                        is looking at a fraction of the stacks and passing for that reason. Every \
                        stack in this repository offers a dozen or more ${VAR:-default}s; if they \
                        are being written some other way, teach COMPOSE_DIAL rather than lowering \
                        this. (%d examined; the development-stack exclusion admitted %d name(s): \
                        %s.)""", examined, admittedByTheExclusion.size(), admittedByTheExclusion)
                .isGreaterThanOrEqualTo(30);
        assertThat(admittedByTheExclusion)
                .withFailMessage("""
                        THE DEVELOPMENT-STACK EXCLUSION NOW ADMITS NOTHING, so it is prose \
                        excusing an empty set - delete it, or find out what stopped a development \
                        compose file from reaching this rule (a rename would do it: the predicate \
                        keys on a `.dev.` marker in the name, at the repository root).""")
                .isNotEmpty();
        assertThat(offences)
                .withFailMessage("""

                        A STACK OFFERS A DIAL ITS OWN TEMPLATE NEVER NAMES.

                        %s

                        `${VAR:-default}` is a promise that `.env` can move this value. When the \
                        template does not carry the line, the operator cannot know the name - so \
                        the promise is kept by the file and broken by the documentation, silently, \
                        with the stack running on the default and looking configured.

                        This is HD-317: eight names in deploy/cloud/docker-compose.yml, including \
                        both MinIO image pins - so an operator could not pin their object store at \
                        all, on the stack that keeps their attachments. The dc stack's own comment \
                        already said why ("a literal here is a value your .env cannot reach... it \
                        exists so the matching dial is in .env when you do").

                        Add the line COMMENTED, with the shipped default as its value, in the \
                        section it belongs to. A commented line delivers nothing to the container, \
                        so the compose default still stands - it is there to be findable.

                        (%d dials examined; the development-stack exclusion admitted %d name(s): \
                        %s.)""", String.join("\n  ", offences), examined,
                        admittedByTheExclusion.size(), admittedByTheExclusion)
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
     * <strong>The literal exemption may shrink the property half and never the compose
     * half.</strong>
     *
     * <p>The control that would have caught the leak described on
     * {@link #literalEnvironmentNames}: a name a compose file refuses to start without has to
     * survive into the guarded set, whatever any compose file supplies as a literal. Written
     * as a comparison between the two computations rather than as a list of names, so it
     * keeps holding as files are added.
     *
     * <p>It also prints the delta. That is deliberate and is the point of the whole round:
     * the exemption is derived, so the only way anyone ever sees what it excuses is for a
     * failure to say so.
     *
     * <p><strong>Cause-agnostic on purpose.</strong> It compares two computations and reports
     * a name that did not survive; it does not claim to know WHY. That is its strength over a
     * check for the known bug, and the reason the per-name line below states only what was
     * measured — an earlier wording asserted "excused by a literal", and with a different drop
     * mechanism planted it confidently blamed a literal for a name a filter had removed. The
     * likely cause belongs in the prose, where it is advice; in the per-name line it would be
     * a finding the control never established.
     *
     * <p>It carries BOTH floors. Its own population is the compose guards, but those are
     * reached through {@link #pairs()} — so without the pairing floor it passes on one
     * surviving pair's guards while the pairing has silently collapsed, and a control that
     * leans on a sibling test to notice its own population shrinking is not a control.
     */
    @Test
    void theLiteralExemptionNeverShrinksTheComposeHalf() throws IOException {
        var lost = new ArrayList<String>();
        int composeGuardCount = 0;
        List<Pair> pairs = pairs();

        for (Pair pair : pairs) {
            Map<String, String> declared = composeGuards(pair);
            Set<String> survived = guardedVariables(pair).keySet();
            composeGuardCount += declared.size();
            for (var entry : declared.entrySet()) {
                if (!survived.contains(entry.getKey())) {
                    lost.add(("%s: `%s` is guarded by %s but did not survive into the effective "
                            + "guarded set").formatted(pair.describe(), entry.getKey(), entry.getValue()));
                }
            }
        }

        assertThat(pairs)
                .withFailMessage(PAIRING_FLOOR)
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(composeGuardCount)
                .withFailMessage("No compose ${VAR:?} guard was found in any pair, so this control is "
                        + "comparing two empty sets and would pass over anything")
                .isGreaterThanOrEqualTo(4);
        assertThat(lost)
                .withFailMessage("""

                        A COMPOSE GUARD LEFT THE GUARDED SET. IT WILL STILL REFUSE THE `up`.

                        ${VAR:?} is evaluated during interpolation, before any container exists, so \
                        NOTHING supplied under `environment:` can satisfy it - not in another file, \
                        not in another service, not in the same one. Dropping it removes the rule \
                        that keeps the variable in the template, and the operator meets the refusal \
                        with no line to fill in.

                        The usual cause is the literal exemption reaching the compose half: it \
                        exists for PROPERTY guards only (an unresolvable ${VAR} in \
                        application.properties, which a literal really does satisfy). If you are \
                        extending it, extend that half. But this check only measures that the name \
                        left the set - if the exemption is not what dropped it, find what did.

                        %s""".formatted(String.join("\n  ", lost)))
                .isEmpty();
    }

    /**
     * <strong>Stripping comments never loses a guard.</strong>
     *
     * <p>{@link #withoutComments} is not a YAML parser, and its failure direction is the
     * dangerous one: strip too much and a {@code ${VAR:?}} silently leaves the registry, which
     * nothing else goes red for. Its javadoc names two shapes it would get wrong — a
     * {@code #} inside a block scalar, and an escaped quote inside a double-quoted one. Naming
     * them is weaker than checking them, and by that javadoc's own argument they would arrive
     * in the same commit as the line that introduces them.
     *
     * <p><strong>Held by a table with a human oracle, not by a rule over the real files.</strong>
     * The obvious shape — "every guard in the raw text survives, unless its line is a comment"
     * — was tried and is measurably wrong: {@code docker-compose.observability.yml:39} carries
     * {@code - -config.expand-env=true   # allow ${LOKI_RETENTION_PERIOD} substitution}, a
     * LIVE line whose trailing comment legitimately contains an expansion, so that rule reds
     * on correct behaviour. Refining it to "the occurrence lies before the comment" makes it a
     * restatement of {@link #endOfContent} — it would agree with the implementation whatever
     * the implementation did, which is the vacuous shape this project keeps finding. A table
     * of inputs with answers written by hand is the only oracle here that is independent of
     * the code under test.
     */
    @Test
    void strippingCommentsNeverRemovesAGuardFromANonCommentLine() {
        record Case(String line, String mustKeep, String why) {
        }
        var cases = List.of(
                new Case("      FOO: ${GUARDED:?why}   # trailing comment", "${GUARDED:?why}",
                        "a guard with a comment after it"),
                new Case("      FOO: \"a # b ${QUOTED:?why}\"", "${QUOTED:?why}",
                        "a hash inside a double-quoted scalar is content, not a comment"),
                new Case("      FOO: 'a # b ${SINGLE:?why}'", "${SINGLE:?why}",
                        "the same in a single-quoted scalar"),
                new Case("      FOO: \"he said \\\" # ${ESCAPED:?why}\"", "${ESCAPED:?why}",
                        "a backslash-escaped quote does not end the quote early"),
                new Case("      URL: http://x/#frag${FRAGMENT:?why}", "${FRAGMENT:?why}",
                        "a hash not preceded by whitespace does not start a comment"));

        var lost = new ArrayList<String>();
        for (Case one : cases) {
            if (!withoutComments(one.line()).contains(one.mustKeep())) {
                lost.add("`%s` was stripped from `%s` - %s".formatted(one.mustKeep(), one.line().strip(),
                        one.why()));
            }
        }

        // The block scalar needs its neighbours, so it is asserted over a fragment.
        String block = """
                  entrypoint: |
                    # this hash is CONTENT, not a comment
                    exec app --token ${BLOCK:?why}
                  next: value""";
        if (!withoutComments(block).contains("${BLOCK:?why}")) {
            lost.add("`${BLOCK:?why}` was stripped from a block scalar body - a `#` inside `|` or `>` "
                     + "is content, and treating it as a comment swallows the rest of the block");
        }

        assertThat(lost)
                .withFailMessage("""

                        THE COMMENT STRIPPER REMOVED A GUARD THAT IS NOT IN A COMMENT.

                        This is the failure direction that costs something: the variable quietly \
                        leaves the guarded registry, so nothing then requires it in any env \
                        template, and the operator meets a refusal naming a variable their file \
                        does not contain. Nothing else in this class goes red for it.

                        Fix endOfContent() / withoutComments(); do not relax a row below. Each row \
                        is a shape YAML gives a meaning to, and the answers are written by hand \
                        precisely so this cannot agree with a broken implementation.

                        (%d shapes checked.)

                        %s""".formatted(cases.size() + 1, String.join("\n  ", lost)))
                .isEmpty();
    }

    /**
     * <strong>A stack that guards values must ship the template those values go in.</strong>
     *
     * <p>The pairing is derived, which means a stack with no template beside it forms no pair
     * and is therefore checked by nothing — silently, and exactly when a second stack is
     * added, which is the moment this whole rule exists for. So the obligation is asserted
     * from the compose side as well as the template side.
     *
     * <p>Deliberately over {@code publishableFiles}, not the index: a compose file that has
     * been written but not staged is precisely the one whose template is easiest to forget,
     * and holding it to this rule can only make the check stricter.
     */
    @Test
    void everyStackThatGuardsAValueHasATemplateBesideIt() throws IOException {
        var orphans = new ArrayList<String>();
        int examined = 0;

        for (Path compose : PublishedCredentials.publishableFiles(".")) {
            if (!compose.getFileName().toString().matches("docker-compose.*\\.ya?ml")) {
                continue;
            }
            if (!COMPOSE_GUARD.matcher(withoutComments(Files.readString(compose, StandardCharsets.UTF_8)))
                    .find()) {
                continue;
            }
            examined++;
            String directory = directoryOf(compose);
            boolean hasTemplate = PublishedCredentials.publishableFiles(".").stream()
                    .filter(PublishedCredentials::isEnvTemplate)
                    .anyMatch(template -> directoryOf(template).equals(directory));
            if (!hasTemplate) {
                orphans.add(PublishedCredentials.repositoryPath(compose));
            }
        }

        assertThat(examined)
                .withFailMessage("No compose file in this tree declares a ${VAR:?} guard, so the "
                        + "pairing rule is checking nothing at all")
                .isGreaterThanOrEqualTo(1);
        assertThat(orphans)
                .withFailMessage("""

                        A compose file refuses to start without values an operator must supply, and \
                        there is no env template beside it to tell them which.

                        They meet the guard as a bare refusal naming a variable, with no file to put \
                        it in. Add a `.env.example` in the same directory carrying every guarded \
                        name, EMPTY - the two rules above then hold the pair together.

                        (%d guarded stacks examined.)

                        Stacks with no template: %s""".formatted(examined, orphans))
                .isEmpty();
    }

    /**
     * <strong>No template ships a value that would CREATE AN ACCOUNT.</strong>
     *
     * <p>{@link #noTemplateShipsACredential} holds the password half of that pair and cannot
     * hold the address half: {@code CREDENTIAL_SHAPED} matches names ending {@code PASSWORD},
     * {@code SECRET}, {@code TOKEN} and so on, and {@code SEED_ADMIN_EMAIL} ends in
     * {@code EMAIL}. So the address shipped filled for as long as the file existed, and the
     * reasoning written above it in {@code .env.prod.example} — <em>a filled address does not
     * fail, it agrees</em> — was a claim nothing held. Measured at the time: restoring the
     * placeholder left the whole suite green.
     *
     * <p><strong>Why a template rule and not a {@code ${VAR:?}} in the production
     * stack.</strong> Guarding it there would refuse the {@code up} for every existing
     * install that followed this project's own advice and deleted the seeding pair once the
     * administrator existed — turning a first-install convenience into a permanently required
     * value on a running production box. The DC stack guards it because it has a reason prod
     * does not: on a fresh {@code dc} install with public signup closed, that address is the
     * only door in. A template rule refuses the published value without changing what any
     * stack demands.
     *
     * <p><strong>The members are derived from {@code DataSeeder}, and the exclusion is a
     * property rather than a name.</strong> They are the {@code seed.admin.*} {@code @Value}
     * keys whose default is EMPTY — the ones the seeder treats as "not configured" and
     * returns on. {@code seed.admin.display-name} defaults to {@code Admin} and is therefore
     * not a member, which is the right answer for the right reason: a display name is not an
     * identity, and {@code #SEED_ADMIN_DISPLAY_NAME=Admin} in a template is fine. If someone
     * gives it an empty default one day, it joins on that day.
     *
     * <p><strong>The floor below is a COUNT, and it is only tight because the category has
     * exactly two members.</strong> At two, losing one to a reformat — a line-wrapped
     * {@code @Value}, a space in {@code ${seed.admin.email: }} — takes it to one and reds. At
     * three it would take it to two and pass, silently, with a member outside the rule. If a
     * third member ever appears, replace the floor: count every {@code seed.admin.} key the
     * file mentions and assert that the empty-default partition plus the defaulted one
     * accounts for all of them, so the check is about the partition rather than about a
     * number.
     */
    @Test
    void noTemplateShipsAValueThatWouldCreateAnAccount() throws IOException {
        Map<String, String> members = accountCreatingVariables();
        var offences = new ArrayList<String>();

        for (Path template : templates()) {
            for (Pattern form : List.of(ENABLED, DISABLED)) {
                var found = assignments(template, form);
                for (var member : members.entrySet()) {
                    Assignment shipped = found.get(member.getKey());
                    if (shipped != null && !isEmptyValue(shipped.value())) {
                        offences.add("%s:%d ships `%s=%s` (%s)".formatted(
                                PublishedCredentials.repositoryPath(template), shipped.line(),
                                member.getKey(), render(shipped.value()), member.getValue()));
                    }
                }
            }
        }

        assertThat(members)
                .withFailMessage("No account-creating variable was derived from DataSeeder, so this rule "
                        + "is checking nothing. The members are the seed.admin.* @Value keys with an "
                        + "EMPTY default - if that shape changed, teach accountCreatingVariables() the "
                        + "new one rather than listing names here.")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(offences)
                .withFailMessage("""

                        A TEMPLATE SHIPS A VALUE THAT WOULD CREATE AN ADMINISTRATOR ACCOUNT.

                        A filled-in address does not FAIL, it AGREES. Together with a password it \
                        makes an unedited copy seed a real, ACTIVE system administrator - and with \
                        only one of the two filled it still decides WHO that account will be, at an \
                        address the reader does not own and cannot receive a password reset at.

                        Ship the line EMPTY. Empty is what the seeder reads as "not configured": it \
                        logs that seeding was skipped and returns before touching the users table, \
                        so an unedited copy creates nothing at all.

                        A reserved example domain is not a fix - that was the previous answer here, \
                        and it still produced an ACTIVE administrator nobody could receive mail for.

                        %s""".formatted(String.join("\n  ", offences)))
                .isEmpty();
    }

    /** {@code SEED_ADMIN_EMAIL} → why it matters, derived from {@code DataSeeder}'s own defaults. */
    private static Map<String, String> accountCreatingVariables() throws IOException {
        var members = new LinkedHashMap<String, String>();
        Path seeder = Path.of("src/main/java/com/hamstrack/common/seed/DataSeeder.java");
        Matcher m = Pattern.compile("@Value\\(\"\\$\\{(seed\\.admin\\.[a-z.\\-]+):}\"\\)")
                .matcher(Files.readString(seeder, StandardCharsets.UTF_8));
        while (m.find()) {
            members.put(m.group(1).toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'),
                    "DataSeeder reads " + m.group(1) + " with an empty default");
        }
        return members;
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

    /**
     * A template and the compose files it is the template FOR — the ones beside it in its own
     * directory.
     *
     * <p><strong>The pairing used to be one hard-coded path</strong>, and the guarded set was
     * GLOBAL: every {@code ${VAR:?}} in every compose file anywhere was demanded of
     * {@code .env.prod.example}. So adding the self-hosted stack under {@code deploy/dc/} put
     * its four guards onto the production template, where no reader of
     * {@code docker-compose.prod.yml} will ever meet them — and, in the other direction, the
     * new template was held to nothing at all. A rule keyed on one member cannot see a second
     * pair arriving; this one is derived, so the next {@code deploy/<model>/} directory joins
     * on the day it is created.
     */
    private record Pair(Path template, List<Path> composeFiles) {

        String describe() {
            return PublishedCredentials.repositoryPath(template);
        }
    }

    private static List<Pair> pairs() {
        var byDirectory = new LinkedHashMap<String, List<Path>>();
        for (Path compose : composeFiles()) {
            byDirectory.computeIfAbsent(directoryOf(compose), unused -> new ArrayList<>()).add(compose);
        }

        var found = new ArrayList<Pair>();
        for (Path template : PublishedCredentials.publishableFiles(".")) {
            if (!PublishedCredentials.isEnvTemplate(template)) {
                continue;
            }
            List<Path> beside = byDirectory.get(directoryOf(template));
            if (beside != null && !beside.isEmpty()) {
                found.add(new Pair(template.normalize(), beside));
            }
        }
        return found;
    }

    private static String directoryOf(Path file) {
        String path = PublishedCredentials.repositoryPath(file);
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Variable name → the file and the form that guards it, for the failure message.
     *
     * <p>Property guards are global by nature: {@code application.properties} is read by
     * whichever stack starts the application, so an unresolvable {@code ${VAR}} there refuses
     * the boot of every one of them.
     *
     * <p><strong>Comments are stripped first (HD-314).</strong> This read the file as one
     * string, so a line of PROSE spelling a live {@code ${NAME:?}} — explaining what the
     * guards are — registered {@code NAME} as a variable every template then had to ship. The
     * failure message offers two remedies and both are wrong for that input: emptying a line
     * for a variable nothing reads, or installing a permanent exemption for a word that was
     * never a variable, which the next real one then inherits as precedent.
     */
    private static Map<String, String> guardedVariables(Pair pair) throws IOException {
        Map<String, String> compose = composeGuards(pair);

        // The PROPERTY half, and the only half the literal exemption may touch.
        var property = new LinkedHashMap<String, String>();
        for (Path properties : propertyFiles()) {
            var m = PROPERTY_GUARD.matcher(withoutComments(Files.readString(properties, StandardCharsets.UTF_8)));
            while (m.find()) {
                property.putIfAbsent(m.group(1), PublishedCredentials.repositoryPath(properties)
                        + " (${" + m.group(1) + "}, no default)");
            }
        }
        literalEnvironmentNames(pair.composeFiles()).forEach(property::remove);

        var guarded = new LinkedHashMap<>(compose);
        property.forEach(guarded::putIfAbsent);
        assertThat(guarded)
                .withFailMessage("No guards were found at all for %s - the enumeration is broken, which "
                        + "would make every other assertion in this class vacuously true", pair.describe())
                .isNotEmpty();
        return guarded;
    }

    /** Every {@code ${VAR:?}} in this pair's compose files. Never reduced by an exemption. */
    private static Map<String, String> composeGuards(Pair pair) throws IOException {
        var guarded = new LinkedHashMap<String, String>();
        for (Path compose : pair.composeFiles()) {
            var m = COMPOSE_GUARD.matcher(withoutComments(Files.readString(compose, StandardCharsets.UTF_8)));
            while (m.find()) {
                guarded.putIfAbsent(m.group(1), PublishedCredentials.repositoryPath(compose)
                        + " (${" + m.group(1) + ":?...})");
            }
        }
        return guarded;
    }

    /**
     * A {@code #} comment, in both formats this reads. YAML ends a value at an unquoted
     * {@code #} preceded by whitespace, and a {@code .properties} comment is a line whose
     * first non-blank character is {@code #} or {@code !}.
     *
     * <p><strong>Quoted scalars are tracked, because getting this wrong narrows guard
     * DETECTION and nothing goes red for that.</strong> The first version cut at any
     * {@code #} preceded by whitespace, so {@code VALUE: "a # b"} lost everything from the
     * hash — and a {@code ${VAR:?}} written after one inside a quoted value would have
     * stopped being seen as a guard. No line in this repository has that shape today, which
     * is precisely why it had to be fixed rather than left as a note: the failure mode is a
     * guard silently leaving the registry, and it would arrive with the line that introduces
     * it.
     *
     * <p>Still not a YAML parser, but the two shapes that would make it strip MORE than it
     * should are handled rather than merely named, because stripping too much hides a guard
     * and nothing else goes red for that: a {@code #} inside a block scalar ({@code |} /
     * {@code >}) is content, and a backslash-escaped quote inside a double-quoted scalar does
     * not end the quote. {@code strippingCommentsNeverRemovesAGuardFromANonCommentLine} holds
     * both.
     */
    private static String withoutComments(String text) {
        var kept = new ArrayList<String>();
        int blockIndent = -1;
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            int indent = line.length() - line.stripLeading().length();

            // Inside a block scalar every character is content, including `#`.
            if (blockIndent >= 0) {
                if (stripped.isEmpty() || indent > blockIndent) {
                    kept.add(line);
                    continue;
                }
                blockIndent = -1;
            }
            if (stripped.startsWith("#") || stripped.startsWith("!")) {
                kept.add("");
                continue;
            }
            String content = line.substring(0, endOfContent(line));
            if (BLOCK_SCALAR_HEADER.matcher(content).find()) {
                blockIndent = indent;
            }
            kept.add(content);
        }
        return String.join("\n", kept);
    }

    /** {@code key: |}, {@code key: >-}, {@code key: |2} — everything below it is content. */
    private static final Pattern BLOCK_SCALAR_HEADER = Pattern.compile("[|>][+-]?[0-9]*[ \\t]*$");

    /** Where a line's content stops: the first {@code #} that starts a comment, else its end. */
    private static int endOfContent(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote == '"' && ch == '\\') {
                i++;                              // an escaped character, quote included
            } else if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '#' && (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t')) {
                return i;
            }
        }
        return line.length();
    }

    /**
     * {@code NAME: literal} under an {@code environment:} block — not an interpolation.
     *
     * <p><strong>This may only ever excuse a PROPERTY guard, never a Compose one, and the
     * distinction is the whole correctness argument.</strong> A property guard
     * ({@code ${DB_URL}} with no default in {@code application.properties}) is satisfied by
     * the container <em>receiving</em> a value, and a literal under {@code environment:}
     * really does deliver one whatever {@code .env} says — so the operator genuinely never
     * has to set it. A Compose guard ({@code ${VAR:?}}) is refused by Compose during
     * <em>interpolation</em>, before any container exists; no literal anywhere satisfies it,
     * so excusing one hides a refusal that will still fire.
     *
     * <p>That is also why "only excuse a literal from the same FILE" is not the fix: within
     * one file, service A's {@code ${FOO:?}} is not satisfied by service B's
     * {@code FOO: literal} either. Compose still refuses. The split is by guard KIND because
     * the difference is about when the guard is evaluated, not about where it is written.
     *
     * <p><strong>What this cost when it was wrong (HD-314, fix round 2).</strong> The
     * exemption was applied to both halves, and {@link #pairs()} groups every compose file in
     * a directory — so at the repository root, where four files sit together, a
     * <em>development</em> literal cancelled a <em>production</em> guard:
     * {@code docker-compose.observability.dev.yml} supplies
     * {@code GF_SECURITY_ADMIN_PASSWORD} and {@code OBS_ALERT_EMAIL_TO} as literals, and both
     * vanished from the guarded set of {@code docker-compose.observability.yml}, which guards
     * them. An operator running only the production file still meets those refusals. Nothing
     * broke, because both lines happen to be present and empty in the template — what was
     * removed was the protection, and {@code OBS_ALERT_EMAIL_TO}'s own guard message says an
     * empty value disables every alert rule rather than only its delivery.
     *
     * <p><strong>And the lesson that is bigger than the bug.</strong> This replaced a
     * one-entry hand-kept map, whose single exemption carried a written reason a reviewer
     * could argue with, by a derivation that excused SEVENTEEN names at the root pair —
     * including two credential-shaped ones. Deleting the map was still right: a derived
     * exclusion is auditable where a list is not. But it is only auditable if somebody
     * actually audits it <em>when it lands</em>, and nothing in that change printed the
     * delta, which is exactly why the suite stayed green over a removed guard.
     * {@code theLiteralExemptionNeverShrinksTheComposeHalf} is that audit, made permanent.
     *
     * <p><strong>And here is the audit, which turns "derived, therefore fine" into a measured
     * equivalence.</strong> {@code application.properties} declares exactly four no-default
     * variables — {@code DB_PASSWORD}, {@code DB_URL}, {@code DB_USERNAME},
     * {@code JWT_SECRET} — so once the exemption is confined to the property half, the
     * seventeen literals intersect it in almost nothing. At the root pair it excuses
     * {@code DB_URL} <em>and nothing else</em>: precisely the single entry the deleted
     * hand-kept map carried. At {@code deploy/dc/} it additionally excuses
     * {@code DB_USERNAME}, correctly, because that stack supplies it as a literal. The
     * derivation is therefore equivalent to the map it replaced, plus one new case that is
     * right — which is a far stronger claim than "it is derived".
     *
     * <p><strong>A structural gap on the other side of the split, noted rather than
     * fixed.</strong> The compose half now has a permanent delta control; the property half
     * has none, so a future change widening what counts as a literal could quietly excuse one
     * of those four. The risk is small and bounded by that enumeration — four reachable names,
     * all of them things an operator either must set or demonstrably must not — which is why
     * this is a note and not a defect. If the property half ever stops being four names, it
     * wants the same control the compose half has.
     */
    private static Set<String> literalEnvironmentNames(List<Path> composeFiles) throws IOException {
        var names = new LinkedHashSet<String>();
        for (Path compose : composeFiles) {
            int blockIndent = -1;
            for (String raw : withoutComments(Files.readString(compose, StandardCharsets.UTF_8)).split("\n", -1)) {
                if (raw.isBlank()) {
                    continue;
                }
                int indent = raw.length() - raw.stripLeading().length();
                if (raw.strip().equals("environment:")) {
                    blockIndent = indent;
                    continue;
                }
                if (blockIndent < 0) {
                    continue;
                }
                if (indent <= blockIndent) {
                    blockIndent = -1;
                    continue;
                }
                Matcher entry = Pattern.compile("^([A-Z_][A-Z0-9_]*):[ \\t]+(\\S.*)$").matcher(raw.strip());
                if (entry.matches() && !PublishedCredentials.unquote(entry.group(2).strip()).startsWith("${")) {
                    names.add(entry.group(1));
                }
            }
        }
        return names;
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
