package com.hamstrack.common.docs;

import com.hamstrack.ops.PublishedCredentials;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-324 &mdash; the documents a stranger installs from are held to the product they
 * describe.</strong>
 *
 * <h2>Why this exists</h2>
 * Release 0.18.3 is twelve tickets, and every one of them is the same shape: a document that was
 * true when it was written and was never re-read against the tree. The `0.4` image pin survived
 * four release lines. README's install command had been wrong since 2026-08-03. The image's
 * architecture was never written down <em>at all</em>, so no review could notice it was missing.
 * Meanwhile every comparable claim inside the code carries a test that fails when it drifts. This
 * is that test, for the documents an operator reads.
 *
 * <h2>What each rule catches, named by the defect that motivated it</h2>
 * <ul>
 *   <li>{@link #everyRepositoryPathAnInstallDocumentNamesExistsInTheTree} &mdash; HD-314, a
 *       document sending a reader to a file the repository does not have.</li>
 *   <li>{@link #everyPinAnInstallDocumentOffersNamesTheCurrentReleaseLine} &mdash; HD-313, the
 *       abandoned `0.4` line. The current line is derived from {@code git tag}, never from
 *       {@code pom.xml}, which is the placeholder {@code 0.0.0-DEV}.</li>
 *   <li>{@link #eachInstallableStackIsPrescribedByExactlyOneDocument} and
 *       {@link #noDocumentPrescribesTheInstallCommandTwice} &mdash; HD-314 and HD-319. Two copies
 *       of one command drift in their flags and the reader cannot tell which they are following;
 *       a stack no document prescribes is a product nobody outside the owner can install. The two
 *       directions are separate tests because a single count of occurrences lets them CANCEL
 *       &mdash; see the javadoc on the first.</li>
 *   <li>{@link #theInstallPathStatesTheArchitectureRequirement} &mdash; HD-316, which is the hard
 *       one: an <em>absence</em>. A scan for wrong text cannot see a fact nobody wrote, so this
 *       rule asserts presence instead.</li>
 * </ul>
 *
 * <h2>What this test structurally cannot do, stated so a green run is never read as more</h2>
 * <ul>
 *   <li><strong>It does not ask a registry anything.</strong> "Names a tag that was published" is
 *       the sharper invariant &mdash; it condemns `0.4.3` and `0.5`, which were written and never
 *       existed, and acquits a rollback example naming an older release. It needs the network, and
 *       a unit test that reaches ghcr.io fails on an aeroplane and gets muted. It belongs to the
 *       release checklist, with the anonymous token recipe, and is not smuggled in here.</li>
 *   <li><strong>It does not run {@code docker compose config -q}.</strong> Same reason: a daemon is
 *       not a thing a unit test may require. Also on the checklist.</li>
 *   <li><strong>It cannot see a contradiction between two true-looking sentences</strong> &mdash;
 *       HD-320's "SMTP is required" against a quick start that does not use it. Both halves are
 *       ordinary prose; only a reader can tell they disagree.</li>
 *   <li><strong>It cannot judge whether a sentence is <em>correct</em></strong>, only whether the
 *       things it names exist. A command that runs and does the wrong thing passes.</li>
 * </ul>
 *
 * <h2>Why the document set is declared rather than derived, and what stops it shrinking</h2>
 * "Install-facing" is a judgement: {@code docs/observability.md} is operator-facing and is not part
 * of installing. So the set is written down with a reason per member &mdash; and
 * {@link #theScannedSetIsTheOneAReaderActuallyFollows} closes the hole that creates, in two ways
 * that a declaration alone cannot: any publishable document carrying the install command is a
 * member whether or not somebody remembered to add it, and every {@code deploy/<model>/} stack must
 * contribute the README a stranger lands on in it and the template beside it. Floors sit under
 * every population and the set's own floor is <em>derived from the stacks</em>, because the failure
 * this whole release is made of is a check that quietly had nothing to look at &mdash; and a floor
 * written as a number is a check that stops looking one entry before anybody notices.
 */
class InstallClaimsTest {

    /**
     * Each member, with the reason it is one &mdash; the landing page, plus a trio per installable
     * stack: the guide, the README in the stack's own directory, and the template beside it.
     *
     * <p>No count is written here on purpose. This javadoc said "all four" while the map held six,
     * and {@link #theScannedSetIsTheOneAReaderActuallyFollows}'s floor said four as well, so
     * deleting a member left every rule in this class green (HD-317, measured). The floor is now
     * derived from the stacks in the tree and the per-stack members are demanded from the same
     * derivation, so both grow on the day a {@code deploy/<model>/} directory appears.
     */
    private static final Map<String, String> INSTALL_DOCUMENTS = new LinkedHashMap<>(Map.of(
            "README.md", "the landing page, and where the DC install command lives",
            "docs/self-hosting.md", "the full walkthrough README delegates to",
            "deploy/dc/README.md", "what a stranger lands on in the stack's own directory",
            "deploy/dc/.env.example", "the template the install command tells them to copy",
            "docs/cloud-mode.md", "the cloud model's own guide, and where its install command lives",
            "deploy/cloud/README.md", "what a stranger lands on in the stack's own directory",
            "deploy/cloud/.env.example", "the template that guide tells them to copy"));

    /**
     * The landing page: the one member that belongs to no single stack. Everything else in the set
     * is per-stack, which is what makes the floor below derivable rather than remembered.
     */
    private static final int DOCUMENTS_BELONGING_TO_NO_STACK = 1;

    /**
     * Per installable stack: its guide, the README in its own directory, its template. A stack that
     * deliberately shares a guide with another one is a change to this number AND to the reason
     * above it &mdash; deliberately, because lowering a floor is how the last one stopped holding.
     */
    private static final int DOCUMENTS_PER_STACK = 3;

    /**
     * The install command, matched on the part every stack's version of it shares rather than on a
     * whole line: a reformat does not break the rule, a second copy cannot hide behind whitespace,
     * and the timeout (120s for dc, 240s for cloud) is deliberately outside the pattern, since it
     * is the one flag the two stacks are expected to disagree about.
     */
    private static final Pattern INSTALL_COMMAND = Pattern.compile("docker compose up -d --wait");

    /**
     * A repository-relative path: it names a directory this repository has. A BARE filename
     * ({@code application.properties}, {@code apply-config.sh}) is deliberately not matched &mdash;
     * measured, those appear in prose meaning "the file inside the image" or "the script, wherever
     * it is installed", and demanding them at the repository root would red on correct sentences,
     * which is how a guard teaches its readers to switch it off.
     */
    private static final Pattern REPOSITORY_PATH =
            Pattern.compile("`((?:docs|deploy|ops|src|observability|\\.github)/[A-Za-z0-9._/-]+)`");

    /**
     * A path a document names that the repository must NOT contain, because the operator creates
     * it: the {@code .env} of any stack. Copying the template to it is the whole point of the
     * install, so this is a property of every {@code deploy/<model>/} directory rather than a list
     * that names the stacks that existed when it was written.
     */
    private static final Pattern CREATED_BY_THE_OPERATOR =
            Pattern.compile("(?:deploy/[A-Za-z0-9._-]+/)?\\.env");

    /** An image pin a reader may copy: {@code APP_IMAGE_TAG=x} or {@code hamstrack:x}. */
    private static final Pattern PIN =
            Pattern.compile("(?:APP_IMAGE_TAG=|hamstrack:)([A-Za-z0-9][A-Za-z0-9._-]*)");

    /**
     * Pins that are correct while naming something other than the current line, each with the
     * reason. This is a list, and a list is what this release is made of &mdash; so it is kept
     * short, every entry is a PROPERTY of the value rather than a spelling, and the rule below
     * reports an unlisted one rather than silently skipping it.
     */
    private static final Map<String, String> PINS_THAT_ARE_NOT_THE_CURRENT_LINE = Map.of(
            "0.17.0", "a ROLLBACK example: naming the release before the current one is the point, "
                      + "and 'roll back to the current line' would be nonsense",
            "latest", "the owner's production stack tracks it deliberately for continuous deploy, "
                      + "and every install document that mentions it says not to use it");

    /** Semantic version in a git tag: {@code v0.18.2}. */
    private static final Pattern RELEASE_TAG = Pattern.compile("^v(\\d+)\\.(\\d+)\\.(\\d+)$");

    // ============================================================ the population

    @Test
    void theScannedSetIsTheOneAReaderActuallyFollows() {
        List<String> models = installableStackModels();
        assertThat(models)
                .withFailMessage(NO_STACK_FOUND)
                .hasSizeGreaterThanOrEqualTo(2);

        int floor = DOCUMENTS_BELONGING_TO_NO_STACK + DOCUMENTS_PER_STACK * models.size();
        assertThat(INSTALL_DOCUMENTS)
                .withFailMessage("""
                        THE INSTALL-FACING SET IS SMALLER THAN THE STACKS IN THE TREE REQUIRE, so
                        every rule below is scanning less than the install path and passing for that
                        reason. %d documents are declared; the shape of the set is the landing page
                        plus %d per installable stack (its guide, the README in its own directory,
                        the template beside it), and the stacks are %s - so %d.

                        This floor was a written 4 while the set held 6, which is how dropping
                        `deploy/cloud/.env.example` left the whole class green and stopped the cloud
                        template's image pin being held to the current release line (HD-317).
                        Restore the member. If a new stack deliberately shares a guide with another,
                        change DOCUMENTS_PER_STACK and its reason, rather than this number.""",
                        INSTALL_DOCUMENTS.size(), DOCUMENTS_PER_STACK, models, floor)
                .hasSizeGreaterThanOrEqualTo(floor);

        // THE PER-STACK HALF. Two of the three members per stack live in the stack's own directory,
        // so they are demanded from the directory rather than remembered: a README is what a
        // stranger lands on in ANY stack directory, and a template is what the guide tells them to
        // copy in ANY stack directory. deploy/cloud/ shipped with neither held (HD-317).
        var unheld = new ArrayList<String>();
        for (String model : models) {
            for (String name : List.of("README.md", ".env.example")) {
                String path = "deploy/" + model + "/" + name;
                if (!INSTALL_DOCUMENTS.containsKey(path)) {
                    unheld.add("  %s - not declared install-facing%s".formatted(
                            path, Files.isRegularFile(Path.of(path)) ? "" : ", and not in the tree"));
                }
            }
        }
        assertThat(unheld)
                .withFailMessage("""
                        A STACK THIS REPOSITORY SHIPS IS MISSING A DOCUMENT ITS SIBLING HAS.

                        %s

                        Both are properties of ANY stack directory, not of the first one: the README
                        is what a stranger lands on when they follow a link into it (README.md links
                        `deploy/cloud/` directly), and the template is the file the install command
                        tells them to copy. `deploy/cloud/` shipped with no README at all while the
                        reason written beside `deploy/dc/README.md` already described every stack.

                        Write the missing file in the register of its sibling, declare it in
                        INSTALL_DOCUMENTS with the reason it is one, and the rules below hold it.""",
                        String.join("\n", unheld))
                .isEmpty();

        for (var member : INSTALL_DOCUMENTS.entrySet()) {
            assertThat(Path.of(member.getKey()))
                    .withFailMessage("""
                            '%s' is named as install-facing (%s) and is not a file. Either it moved
                            and this set did not move with it, or the working directory is not the
                            project root (it is '%s').""",
                            member.getKey(), member.getValue(), Path.of("").toAbsolutePath())
                    .isRegularFile();
        }

        // THE DERIVED HALF. A declared set can go stale by omission, so membership is also read out
        // of the text: a document that tells a reader to run the install command IS install-facing,
        // whether or not anybody remembered to list it.
        var carriers = new ArrayList<String>();
        for (Path file : PublishedCredentials.publishableFiles("*.md")) {
            String path = file.toString().replace('\\', '/');
            if (INSTALL_COMMAND.matcher(read(path)).find()) {
                carriers.add(path);
            }
        }
        assertThat(carriers)
                .withFailMessage("""
                        NO DOCUMENT CARRIES THE INSTALL COMMAND, so the derived membership test has
                        nothing to derive from and this rule cannot fail. Either the command was
                        reworded (teach INSTALL_COMMAND the new wording) or the install path lost
                        its command, which is HD-314 returning.""")
                .isNotEmpty();
        assertThat(INSTALL_DOCUMENTS.keySet())
                .withFailMessage("""
                        A DOCUMENT PRESCRIBES THE INSTALL COMMAND AND IS NOT HELD TO THE INSTALL
                        RULES. Every rule in this class scans the declared set; a document outside it
                        can name a file that does not exist, offer an abandoned pin, or contradict
                        the architecture requirement, and nothing here would look.

                        Carrying the command: %s
                        Declared install-facing: %s

                        Add it to INSTALL_DOCUMENTS with the reason it is one.""",
                        carriers, INSTALL_DOCUMENTS.keySet())
                .containsAll(carriers);
    }

    // ============================================================ HD-314

    @Test
    void everyRepositoryPathAnInstallDocumentNamesExistsInTheTree() {
        var missing = new ArrayList<String>();
        var examined = 0;

        for (String document : INSTALL_DOCUMENTS.keySet()) {
            String text = read(document);
            var matcher = REPOSITORY_PATH.matcher(text);
            while (matcher.find()) {
                String named = matcher.group(1);
                if (CREATED_BY_THE_OPERATOR.matcher(named).matches()) {
                    continue;
                }
                examined++;
                if (!Files.exists(Path.of(named))) {
                    missing.add("  %s names `%s`, which is not in the tree".formatted(document, named));
                }
            }
        }

        assertThat(examined)
                .withFailMessage("""
                        NO REPOSITORY PATH WAS EXAMINED in any install document, so this rule passed
                        without comparing anything. The install path names files - the stack, the
                        template, the guide - and if it has stopped doing so, REPOSITORY_PATH no
                        longer matches how they are written.""")
                .isGreaterThanOrEqualTo(8);

        assertThat(missing)
                .withFailMessage("""
                        AN INSTALL DOCUMENT SENDS A READER TO A FILE THIS REPOSITORY DOES NOT HAVE.

                        %s

                        This is HD-314's shape: README told every operator to run `docker compose
                        up -d` for months while the file that command resolved to started a database
                        and a mail catcher and no application. A path in an install document is an
                        instruction, and an instruction naming nothing is worse than no instruction -
                        the reader follows it and blames themselves.

                        If the file moved, move the reference. If the operator creates it rather than
                        the repository, add it to CREATED_BY_THE_OPERATOR with that reason.""",
                        String.join("\n", missing))
                .isEmpty();
    }

    // ============================================================ HD-313

    @Test
    void everyPinAnInstallDocumentOffersNamesTheCurrentReleaseLine() {
        String line = currentReleaseLine();
        var wrong = new ArrayList<String>();
        var examined = 0;

        for (String document : INSTALL_DOCUMENTS.keySet()) {
            for (String pinLine : read(document).split("\\R", -1)) {
                var matcher = PIN.matcher(pinLine);
                while (matcher.find()) {
                    String pin = matcher.group(1);
                    if (pin.startsWith("$") || pin.contains("{")) {
                        continue; // a variable reference, not a literal a reader copies
                    }
                    examined++;
                    if (pin.equals(line) || pin.startsWith(line + ".")) {
                        continue;
                    }
                    if (PINS_THAT_ARE_NOT_THE_CURRENT_LINE.containsKey(pin)) {
                        continue;
                    }
                    wrong.add("  %s offers `%s` (current line is `%s`)".formatted(document, pin, line));
                }
            }
        }

        assertThat(examined)
                .withFailMessage("""
                        NO IMAGE PIN WAS EXAMINED, so this rule is checking nothing. The install path
                        tells a reader which tag to run; if PIN no longer matches how that is
                        written, teach it rather than deleting this assertion.""")
                .isGreaterThanOrEqualTo(2);

        assertThat(wrong)
                .withFailMessage("""
                        AN INSTALL DOCUMENT OFFERS A PIN THAT IS NOT THE CURRENT RELEASE LINE.

                        %s

                        The current line is derived from `git tag` - never from pom.xml, which is the
                        placeholder 0.0.0-DEV. This is HD-313: the `0.4` line was abandoned on
                        2026-08-03 and survived four releases in nine places, because it was correct
                        when it was written and nothing re-read it. A first-time operator installed a
                        build from August and then read a manual describing behaviour it does not
                        have.

                        If the pin is deliberately not the current line - a rollback example, a value
                        that illustrates a parse - add it to PINS_THAT_ARE_NOT_THE_CURRENT_LINE with
                        the property that makes it correct.

                        NOT CHECKED HERE, and it is the sharper question: whether the tag was ever
                        PUBLISHED. `0.4.3` and `0.5` were both written in this repository and neither
                        has ever existed in the registry. That needs the network, so it is a release-
                        checklist step, not a unit test.""", String.join("\n", wrong))
                .isEmpty();
    }

    // ============================================================ HD-314 / HD-319

    /**
     * <strong>One install command per installable stack — attributed, never counted.</strong>
     *
     * <p>This began as "the install command exists exactly once", which was right while there was
     * one stack and became wrong the moment {@code deploy/cloud/} shipped (HD-317). Two stacks in
     * different directories, filling different variables, legitimately need one command each; they
     * are not copies of each other, they merely share a substring.
     *
     * <p><strong>The first answer to that was {@code total == stacks.size()}, and it was strictly
     * WEAKER than the {@code isEqualTo(1)} it replaced.</strong> Two integers with no attribution
     * of a document to a stack let the two directions <em>cancel</em>: measured on this suite, the
     * cloud guide losing its command while README gained a second copy gave 2 occurrences for 2
     * stacks and stayed GREEN — both defects the failure message named, present at once, and the
     * old count would have gone red on it. So the rule attributes: each occurrence is charged to
     * the stack whose directory is named before it, each stack must be charged exactly once, and
     * {@link #noDocumentPrescribesTheInstallCommandTwice} holds the duplication direction on its
     * own, where no absence can pay for it.
     *
     * <p>The stacks are <strong>derived</strong>, from the {@code docker-compose.yml} under each
     * {@code deploy/} model directory, so a third model is in scope on the day its directory exists
     * rather than when somebody remembers this file.
     */
    @Test
    void eachInstallableStackIsPrescribedByExactlyOneDocument() {
        List<String> models = installableStackModels();
        assertThat(models).withFailMessage(NO_STACK_FOUND).hasSizeGreaterThanOrEqualTo(2);

        List<Prescription> prescriptions = prescriptions(models);
        assertThat(prescriptions).withFailMessage(NOTHING_PRESCRIBED).isNotEmpty();

        var byModel = new LinkedHashMap<String, List<Prescription>>();
        models.forEach(model -> byModel.put(model, new ArrayList<>()));
        var offences = new ArrayList<String>();
        for (Prescription found : prescriptions) {
            if (found.model() == null) {
                offences.add(("  %s prescribes it without naming a deploy/<model>/ directory first, "
                        + "so a reader cannot tell which stack they are installing").formatted(found));
            } else {
                byModel.get(found.model()).add(found);
            }
        }
        byModel.forEach((model, found) -> {
            if (found.isEmpty()) {
                offences.add("  deploy/%s/ is prescribed NOWHERE".formatted(model));
            } else if (found.size() > 1) {
                offences.add("  deploy/%s/ is prescribed %d times: %s".formatted(model, found.size(), found));
            }
        });

        assertThat(offences)
                .withFailMessage("""
                        AN INSTALLABLE STACK IS NOT PRESCRIBED EXACTLY ONCE.

                        %s

                        Charged to a stack: %s

                        NOWHERE means a stack this repository ships that no document tells anybody
                        how to install - HD-317's shape: the Cloud model existed in the codebase for
                        months with no install path anyone outside the owner could follow.

                        MORE THAN ONCE means a duplicated command. What drifts is not the idea, it is
                        the flags and the list of values, and the reader cannot tell which copy they
                        are following: it lived in three files before HD-319, and `--wait` - without
                        which a crash-looping stack exits 0 - was already explained differently in
                        two of them.

                        Each occurrence is charged to the LAST deploy/<model>/ named before it, which
                        is the `cd` a reader has just performed. Fix the document, not the charge.""",
                        String.join("\n", offences), byModel)
                .isEmpty();

        // THE GRANULARITY CONTROL. The unit of this rule is (stack, document); one document carrying
        // both stacks' commands satisfies every count above while putting a reader in a page that
        // installs two different things. If that ever becomes the design, teach this line - it is
        // the line that notices two members merging back into one.
        var carriers = byModel.values().stream()
                .flatMap(List::stream)
                .map(Prescription::document)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(carriers)
                .withFailMessage("""
                        ONE DOCUMENT PRESCRIBES MORE THAN ONE STACK, so the per-stack counts above
                        are satisfied by a page that installs two different products.

                        Carriers: %s
                        Stacks:   %s

                        The unit of this rule is (stack, document). Merging two stacks into one page
                        makes every count agree while a reader can no longer tell which directory,
                        which template and which variables they are dealing with - and the two sets
                        of flags then drift inside one code fence instead of across two files.""",
                        carriers, models)
                .hasSize(models.size());
    }

    /**
     * <strong>The duplication direction, held where nothing can pay for it.</strong>
     *
     * <p>Separate from the rule above on purpose: as long as duplication and absence are summed
     * into one number they cancel, and the cancelling pair is not exotic — it is exactly what a
     * reorganisation of the install path produces (a command moved out of one document and pasted
     * into another). This half asks a question absence cannot answer: does any single publishable
     * document tell a reader to run the install command twice?
     */
    @Test
    void noDocumentPrescribesTheInstallCommandTwice() {
        List<Prescription> prescriptions = prescriptions(installableStackModels());
        assertThat(prescriptions).withFailMessage(NOTHING_PRESCRIBED).isNotEmpty();

        var byDocument = new LinkedHashMap<String, List<Prescription>>();
        for (Prescription found : prescriptions) {
            byDocument.computeIfAbsent(found.document(), unused -> new ArrayList<>()).add(found);
        }

        var offences = new ArrayList<String>();
        byDocument.forEach((document, found) -> {
            if (found.size() > 1) {
                offences.add("  %s prescribes it %d times: %s".formatted(document, found.size(), found));
            }
        });

        assertThat(offences)
                .withFailMessage("""
                        ONE DOCUMENT PRESCRIBES THE INSTALL COMMAND MORE THAN ONCE.

                        %s

                        (%d publishable document(s) prescribe it at all.)

                        Two copies in one page drift in their flags exactly as two copies in two
                        pages do, and the reader has no way to tell which one is current. This is
                        also the half that a single count of occurrences cannot see: a second copy
                        here and a command MISSING somewhere else add up to the expected total, which
                        is how the previous version of this rule stayed green over both at once.

                        Keep one copy. If a page needs to refer to the install again, link to the
                        place it lives - that is what deploy/dc/README.md and deploy/cloud/README.md
                        do, and why neither of them carries the command.""",
                        String.join("\n", offences), byDocument.size())
                .isEmpty();
    }

    // ============================================================ HD-316

    @Test
    void theInstallPathStatesTheArchitectureRequirement() {
        var silent = new ArrayList<String>();
        for (var member : INSTALL_DOCUMENTS.entrySet()) {
            if (member.getKey().endsWith(".env.example")) {
                continue; // a template carries values, not requirements
            }
            String text = read(member.getKey()).toLowerCase(java.util.Locale.ROOT);
            if (!text.contains("amd64") && !text.contains("x86-64") && !text.contains("arm64")) {
                silent.add("  %s (%s)".formatted(member.getKey(), member.getValue()));
            }
        }

        assertThat(silent)
                .withFailMessage("""
                        AN INSTALL DOCUMENT SAYS NOTHING ABOUT THE ARCHITECTURE THE IMAGE NEEDS.

                        %s

                        This rule asserts PRESENCE, which is unusual here and deliberate: HD-316 was
                        an absence. The image is published for linux/amd64 only, and no document said
                        so anywhere - so an operator on an Ampere or Graviton VPS, a Raspberry Pi or
                        an ARM virtual machine ran the first command this project prescribes, met
                        `no matching manifest for linux/arm64/v8`, and had nothing in the repository
                        to read. A scan for wrong text cannot see a fact nobody wrote; only a demand
                        that it be written can.

                        If arm64 images are published one day, this rule still holds - it asks that
                        the architecture be ADDRESSED, not that it be a restriction.""",
                        String.join("\n", silent))
                .isEmpty();
    }

    // ============================================================ helpers

    private static final String NO_STACK_FOUND = """
            FEWER THAN TWO INSTALLABLE STACKS WERE FOUND under deploy/*/docker-compose.yml, so every
            per-stack rule in this class has collapsed into a claim about one file - which is the
            shape that let deploy/cloud/ ship with no README and an unheld template. The stacks being
            real files in the tree is HD-314's and HD-317's whole deliverable; if a stack was
            genuinely retired, this floor is a deliberate edit.""";

    private static final String NOTHING_PRESCRIBED = """
            NO PUBLISHABLE DOCUMENT PRESCRIBES THE INSTALL COMMAND, so this rule has nothing to
            attribute and cannot fail. Either the command was reworded (teach INSTALL_COMMAND the new
            wording) or the install path lost it, which is HD-314 returning.""";

    /** The model directories under {@code deploy/} that ship a stack: {@code cloud}, {@code dc}. */
    private static List<String> installableStackModels() {
        var models = new ArrayList<String>();
        for (Path file : PublishedCredentials.publishableFiles("deploy/*/docker-compose.yml")) {
            String path = file.toString().replace('\\', '/');
            models.add(path.substring("deploy/".length(), path.lastIndexOf('/')));
        }
        return models;
    }

    /** One occurrence of the install command, and the stack it installs ({@code null} if unclear). */
    private record Prescription(String document, int line, String model) {

        @Override
        public String toString() {
            return document + ":" + line;
        }
    }

    /** Every occurrence of the install command in a publishable document, each charged to a stack. */
    private static List<Prescription> prescriptions(List<String> models) {
        var found = new ArrayList<Prescription>();
        for (Path file : PublishedCredentials.publishableFiles("*.md")) {
            String path = file.toString().replace('\\', '/');
            String text = read(path);
            var matcher = INSTALL_COMMAND.matcher(text);
            while (matcher.find()) {
                found.add(new Prescription(path, PublishedCredentials.lineOf(text, matcher.start()),
                        modelNamedBefore(text.substring(0, matcher.start()), models)));
            }
        }
        return found;
    }

    /**
     * The stack an occurrence installs: the <strong>last</strong> {@code deploy/<model>} named
     * before it, which is the {@code cd} the reader has just been told to perform. Not "the stack
     * this document mentions" — README mentions both, in a table two paragraphs above the command
     * it carries, so a document-wide search would charge its one command to whichever model the
     * table happens to list second.
     */
    private static String modelNamedBefore(String before, List<String> models) {
        String nearest = null;
        int at = -1;
        for (String model : models) {
            int index = before.lastIndexOf("deploy/" + model);
            if (index > at) {
                at = index;
                nearest = model;
            }
        }
        return nearest;
    }

    /**
     * The current release line ({@code 0.18}) from the newest {@code v<major>.<minor>.<patch>} tag.
     *
     * <p><strong>From git, never from {@code pom.xml}</strong>, which carries the placeholder
     * {@code 0.0.0-DEV} - the real version is injected at image build time. CI checks out with
     * {@code fetch-depth: 0} on the job that runs this suite, so the tags are here.
     */
    private static String currentReleaseLine() {
        // `Path.of(".")`, not `Path.of("")` - measured: ProcessBuilder rejects an empty directory,
        // and the failure reads as "git tag failed", which would send a reader looking for a git
        // problem that is not there.
        var result = PublishedCredentials.runGit(Path.of("."), "tag", "--list", "v*");
        assertThat(result.ok())
                .withFailMessage("""
                        `git tag` failed, so the current release line cannot be derived and this rule
                        would have nothing to compare against: exit %d, %s""",
                        result.status(), result.detail())
                .isTrue();

        int bestMajor = -1;
        int bestMinor = -1;
        int bestPatch = -1;
        for (String tag : result.output().split("\\R", -1)) {
            var matcher = RELEASE_TAG.matcher(tag.strip());
            if (!matcher.matches()) {
                continue;
            }
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            if (major > bestMajor
                || (major == bestMajor && minor > bestMinor)
                || (major == bestMajor && minor == bestMinor && patch > bestPatch)) {
                bestMajor = major;
                bestMinor = minor;
                bestPatch = patch;
            }
        }
        assertThat(bestMajor)
                .withFailMessage("""
                        NO RELEASE TAG WAS FOUND, so the pin rule has nothing to hold the documents
                        to and would pass whatever they say. This checkout has no `v<x>.<y>.<z>` tag:
                        either it is shallow or was cloned without tags.
                          locally: git fetch --tags
                          CI:      actions/checkout with `fetch-depth: 0`""")
                .isNotNegative();
        return bestMajor + "." + bestMinor;
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the install document " + path, e);
        }
    }
}
