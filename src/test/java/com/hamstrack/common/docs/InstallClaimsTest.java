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
 *   <li>{@link #theInstallChecklistStepsWereRunOnThisReleaseLine} &mdash; the perimeter around the
 *       four items this class hands to a human. Deleting the whole section left 80 tests across 9
 *       classes green (measured, HD-324 fix loop): nothing anywhere made anybody run them, so the
 *       second half of this ticket was a promise with no holder.</li>
 * </ul>
 *
 * <h2>What this test structurally cannot do, stated so a green run is never read as more</h2>
 * <ul>
 *   <li><strong>It does not ask a registry anything.</strong> "Names a tag that was published" is
 *       the sharper invariant &mdash; it condemns `0.4.3` and `0.5`, which were written and never
 *       existed, and acquits a rollback example naming an older release. It needs the network, and
 *       a unit test that reaches ghcr.io fails on an aeroplane and gets muted. It belongs to the
 *       release checklist, with the anonymous token recipe &mdash; and what IS held here is that
 *       somebody ran it on this line
 *       ({@link #theInstallChecklistStepsWereRunOnThisReleaseLine}).</li>
 *   <li><strong>It does not run {@code docker compose config -q}.</strong> Same reason: a daemon is
 *       not a thing a unit test may require. Also on the checklist, also held by its date.</li>
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
 * {@link #theScannedSetIsTheOneAReaderActuallyFollows} closes the hole that creates, in three ways
 * that a declaration alone cannot: any publishable document carrying the install command is a
 * member whether or not somebody remembered to add it; every {@code deploy/<model>/} stack must
 * contribute the README a stranger lands on in it and the template beside it; and every guide a
 * stack's README <em>sends the installer to</em> is a member too. That third one is why
 * {@code docs/self-hosting.md} is held: it carries no install command, so the derived half could
 * not see it, and swapping it for a one-line inert file left the set at 7, the floor at 7 and every
 * rule green while 26 of the 36 examined repository paths and 5 of the 7 examined pins walked out
 * of the scan (measured, HD-324 fix loop &mdash; the HD-317 shape one dimension over).
 *
 * <h2>Floors: what each population is held above, and why none of them is a written number</h2>
 * A floor written as a number is a check that stops looking one entry before anybody notices, so
 * every floor here is derived &mdash; and, since the same fix loop, most are <em>per member</em>
 * rather than over the total, because a total with slack in it is a number by another name: the
 * repository-path floor stood at 8 against an actual 36 and the pin floor at 2 against an actual 7,
 * and a probe that deleted 72% and 71% of those two populations passed both. The shapes now:
 * <ul>
 *   <li>the set itself &mdash; the landing page plus {@link #DOCUMENTS_PER_STACK} per stack found
 *       under {@code deploy/};</li>
 *   <li>repository paths &mdash; <em>every non-template member</em> contributes at least one, so a
 *       member that stops contributing is refused by name rather than covered by its neighbours;</li>
 *   <li>pins &mdash; <em>every stack template</em> offers at least one;</li>
 *   <li>the architecture requirement &mdash; the count of non-template members, derived from the
 *       same two constants the set's floor is. It counted nothing at all until this fix loop: over
 *       an emptied {@code INSTALL_DOCUMENTS} it passed green while three sibling rules red.</li>
 * </ul>
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
     *
     * <p><strong>It is empty, and that is the fix rather than an omission.</strong> It held two
     * entries, and neither was reachable: the only pin literals in the seven install documents are
     * {@code 0.18} and {@code 0.18.2} (measured), so {@code 0.17.0} exempted a rollback example that
     * is not in this scan and {@code latest} exempted the owner's production stack &mdash;
     * {@code docker-compose.prod.yml}, which is not an install document either. What the second one
     * DID do is exempt {@code latest} inside the two templates that forbid it in their own prose:
     * {@code #APP_IMAGE_TAG=latest} in {@code deploy/dc/.env.example} passed green three lines under
     * that template's own "Never {@code latest}: it moves on every main build" warning (measured,
     * HD-324 fix loop). An exemption scoped to a whole scan to excuse a file outside it is a hole in
     * every file inside it &mdash; and its second clause, "every install document that mentions it
     * says not to use it", was prose, checked by nothing.
     *
     * <p>{@link #everyListedExemptionIsOneAnInstallDocumentActuallyUses} keeps it honest from here:
     * an entry that no member offers fails, so this map can only ever describe live text.
     */
    private static final Map<String, String> PINS_THAT_ARE_NOT_THE_CURRENT_LINE = Map.of();

    /** Semantic version in a git tag: {@code v0.18.2}, followed by the tag's date. */
    private static final Pattern RELEASE_TAG =
            Pattern.compile("^v(\\d+)\\.(\\d+)\\.(\\d+) (\\d{4}-\\d{2}-\\d{2})$");

    /**
     * A markdown link out of a stack README to a document elsewhere in the repository:
     * {@code [the guide](../../docs/cloud-mode.md#install)}. Only {@code .md} and only upwards
     * &mdash; a link to {@code ../dc/} is a sibling stack, and {@code https://min.io} is not ours.
     */
    private static final Pattern GUIDE_LINK =
            Pattern.compile("\\]\\(((?:\\.\\./)+[A-Za-z0-9._/-]+\\.md)(?:#[^)]*)?\\)");

    /** Where the steps this class hands to a human live. */
    private static final String RELEASE_CHECKLIST = "docs/release-checklist.md";

    /** The section of it that is HD-324's second deliverable. */
    private static final String INSTALL_SECTION = "## The install path, once per release line";

    /** A numbered step in that section: {@code **3. `docker compose config -q` on every ...**}. */
    private static final Pattern CHECKLIST_STEP =
            Pattern.compile("^\\*\\*(\\d+)\\. (.{0,80})", Pattern.MULTILINE);

    /** The line that records a step having been RUN, and when. */
    private static final Pattern MEASURED = Pattern.compile("\\bMeasured (\\d{4}-\\d{2}-\\d{2})\\b");

    /**
     * The four things this class structurally cannot do, which is why they are somebody's job: the
     * registry, the fresh-clone install, {@code compose config -q}, and the values only a running
     * container refuses. Written as a number because there is nothing in the tree to derive it from
     * &mdash; and it only ever has to catch the direction that deletes.
     */
    private static final int CHECKLIST_STEPS = 4;

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
        // THE GUIDE HALF, same derivation. A stack's third member is the guide its README sends the
        // installer to - and that one is invisible to the install-command half below, because a
        // guide need not carry the command (docs/self-hosting.md does not). Swapping it for an
        // inert file left every rule green while it held 26 of the 36 examined paths (HD-324).
        for (String model : models) {
            if (!Files.isRegularFile(Path.of("deploy", model, "README.md"))) {
                continue; // already reported above; do not report the same missing file twice
            }
            List<String> guides = guidesLinkedFrom(model);
            if (guides.isEmpty()) {
                unheld.add(("  deploy/%s/README.md links to no guide in this repository, so a "
                        + "stranger who lands in it has nowhere to be sent").formatted(model));
            }
            for (String guide : guides) {
                if (!INSTALL_DOCUMENTS.containsKey(guide)) {
                    unheld.add("  %s - deploy/%s/README.md sends the installer here, and it is not declared"
                            .formatted(guide, model));
                }
            }
        }

        assertThat(unheld)
                .withFailMessage("""
                        A STACK THIS REPOSITORY SHIPS IS MISSING A DOCUMENT ITS SIBLING HAS.

                        %s

                        All three are properties of ANY stack directory, not of the first one: the
                        README is what a stranger lands on when they follow a link into it (README.md
                        links `deploy/cloud/` directly), the template is the file the install command
                        tells them to copy, and the guide is wherever that README sends them next -
                        which is the only thing that holds a guide carrying no install command.
                        `deploy/cloud/` shipped with no README at all while the reason written beside
                        `deploy/dc/README.md` already described every stack.

                        Write the missing file in the register of its sibling, declare it in
                        INSTALL_DOCUMENTS with the reason it is one, and the rules below hold it. A
                        link that is genuinely not part of installing does not belong in a stack
                        README - move it into the guide, which a stranger reads after the stack is
                        up.""",
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
        var examined = new LinkedHashMap<String, Integer>();

        for (String document : INSTALL_DOCUMENTS.keySet()) {
            examined.put(document, 0);
            String text = read(document);
            var matcher = REPOSITORY_PATH.matcher(text);
            while (matcher.find()) {
                String named = matcher.group(1);
                if (CREATED_BY_THE_OPERATOR.matcher(named).matches()) {
                    continue;
                }
                examined.merge(document, 1, Integer::sum);
                if (!Files.exists(Path.of(named))) {
                    missing.add("  %s names `%s`, which is not in the tree".formatted(document, named));
                }
            }
        }

        // THE FLOOR, PER MEMBER. It was a written `>= 8` against an actual 36, and a probe that
        // replaced the guide holding 26 of them with a one-line inert file passed it comfortably
        // (measured, HD-324 fix loop). A total with 4.5x of slack in it is a number by another
        // name; what a member owes is a contribution of its own, and the smallest honest one in
        // the tree today is deploy/dc/README.md's single link to the guide.
        var silent = requireProseMembers().stream()
                .filter(document -> examined.getOrDefault(document, 0) == 0)
                .toList();
        assertThat(silent)
                .withFailMessage("""
                        AN INSTALL DOCUMENT NAMES NO FILE IN THIS REPOSITORY, so it is declared
                        install-facing and contributes nothing to this rule - which is how a member
                        can stop being install-facing without any count noticing.

                        Contributing nothing: %s
                        Per member:           %s

                        Every non-template member sends the reader somewhere: to the stack, to the
                        template, to the guide. One that names no path either was replaced by
                        something inert, or the paths lost their backticks and REPOSITORY_PATH no
                        longer sees them - teach the pattern rather than dropping this floor.
                        (Templates carry values, not prose, and are outside this.)""",
                        silent, examined)
                .isEmpty();

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
        String line = newestRelease().line();
        var wrong = new ArrayList<String>();
        var examined = new LinkedHashMap<String, List<String>>();

        for (String document : INSTALL_DOCUMENTS.keySet()) {
            List<String> pins = pinsOffered(document);
            examined.put(document, pins);
            for (String pin : pins) {
                if (pin.equals(line) || pin.startsWith(line + ".")) {
                    continue;
                }
                if (PINS_THAT_ARE_NOT_THE_CURRENT_LINE.containsKey(pin)) {
                    continue;
                }
                wrong.add("  %s offers `%s` (current line is `%s`)".formatted(document, pin, line));
            }
        }

        // THE FLOOR, PER TEMPLATE. It was a written `>= 2` against an actual 7, with the guide
        // holding 5 of them - so dropping the guide out of the scan left it green (measured,
        // HD-324 fix loop). A template is the one member that MUST offer a pin: it is the file the
        // install command tells the reader to copy, and APP_IMAGE_TAG is what they run.
        var pinless = requireTemplateMembers().stream()
                .filter(document -> examined.getOrDefault(document, List.of()).isEmpty())
                .toList();
        assertThat(pinless)
                .withFailMessage("""
                        A STACK TEMPLATE OFFERS NO IMAGE PIN, so this rule is not checking the one
                        file whose values a reader copies wholesale.

                        Offering none: %s
                        Per member:    %s

                        Either the template stopped naming a tag - which leaves every new installer
                        on whatever the image defaults to - or PIN no longer matches how it is
                        written. Teach the pattern; do not lower this floor. A guide may legitimately
                        offer none, which is why the demand is on the templates.""",
                        pinless, examined)
                .isEmpty();

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
                        the property that makes it correct, and expect the rule below to demand that
                        an install document really offers it.

                        NOT CHECKED HERE, and it is the sharper question: whether the tag was ever
                        PUBLISHED. `0.4.3` and `0.5` were both written in this repository and neither
                        has ever existed in the registry. That needs the network, so it is a release-
                        checklist step, run once per line and dated there.""", String.join("\n", wrong))
                .isEmpty();
    }

    /**
     * <strong>An exemption is a live check or it is a lie.</strong> The two entries this map used to
     * carry named text that is nowhere in the scanned set: a {@code 0.17.0} rollback example that
     * lives in the upgrade notes, and {@code latest} for a production stack
     * ({@code docker-compose.prod.yml}) that is not an install document. Scoped to the whole scan,
     * the second one excused {@code latest} in exactly the files that forbid it &mdash; measured,
     * {@code #APP_IMAGE_TAG=latest} in {@code deploy/dc/.env.example} passed three lines under that
     * template's own "Never {@code latest}" warning.
     *
     * <p>So the map is empty and this rule keeps it that way: an entry must name a pin an install
     * document actually offers today. A future rollback example is welcome to re-add one &mdash; on
     * the day the text exists, which is the day the exemption stops being a hole.
     */
    @Test
    void everyListedExemptionIsOneAnInstallDocumentActuallyUses() {
        var offered = new LinkedHashSet<String>();
        INSTALL_DOCUMENTS.keySet().forEach(document -> offered.addAll(pinsOffered(document)));

        var dead = PINS_THAT_ARE_NOT_THE_CURRENT_LINE.entrySet().stream()
                .filter(entry -> !offered.contains(entry.getKey()))
                .map(entry -> "  `%s` - excused as: %s".formatted(entry.getKey(), entry.getValue()))
                .toList();

        assertThat(dead)
                .withFailMessage("""
                        AN EXEMPTION EXCUSES A PIN NO INSTALL DOCUMENT OFFERS.

                        %s

                        Offered by the %d declared install document(s): %s

                        An exemption nobody uses is not harmless: it is a standing permission for
                        whatever text grows into that shape next, and it reads as evidence that
                        somebody checked. `latest` was excused here for the sake of
                        `docker-compose.prod.yml`, which this class does not scan, and the effect was
                        to permit `latest` inside the two templates whose own prose forbids it.

                        Delete the entry. Add it back when a document really offers that pin, with
                        the property - not the spelling - that makes it correct.""",
                        String.join("\n", dead), INSTALL_DOCUMENTS.size(), offered)
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
        // A template carries values, not requirements, so the population is the prose members - and
        // it is DEMANDED rather than assumed: this rule counted nothing until the HD-324 fix loop,
        // and over an emptied INSTALL_DOCUMENTS it passed green while three sibling rules red. A
        // presence rule with no population is the one shape that cannot fail.
        var silent = new ArrayList<String>();
        for (String document : requireProseMembers()) {
            String text = read(document).toLowerCase(java.util.Locale.ROOT);
            if (!text.contains("amd64") && !text.contains("x86-64") && !text.contains("arm64")) {
                silent.add("  %s (%s)".formatted(document, INSTALL_DOCUMENTS.get(document)));
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

    // ============================================================ the perimeter: HD-324's other half

    /**
     * <strong>The steps this class hands to a human are held by the date they were last run.</strong>
     *
     * <p>Measured in the HD-324 fix loop: deleting the whole {@code ## The install path, once per
     * release line} section &mdash; 80 lines &mdash; left 80 tests across 9 classes green. No tag
     * hook, no CI step, no PR template; {@code .github/workflows/deploy.yml} mentions the checklist
     * in a comment. Half of this ticket was a document asking somebody to do four things, and
     * nothing anywhere noticed when nobody did.
     *
     * <p><strong>Why this is not the freshness rule the ticket rejected.</strong> That idea was "an
     * install document whose newest edit predates the newest release line goes red", and it was
     * rejected for a reason that still stands: a document needing no change would red for being
     * correct, and a guard that reds on correct sentences is one its readers learn to switch off.
     * A step's {@code Measured <date>} line is not a correct sentence going stale &mdash; it is an
     * assertion about the artefact being shipped that nobody has made about this one. The only way
     * to turn this rule green is to run the command; editing the prose around it does nothing.
     *
     * <p>The bound is the newest release tag's own date, from {@code git}, never a literal: a
     * checklist item is due once per <em>line</em>, so the thing it must be newer than is the thing
     * that moves the line. Holding checklist text in a unit test is established practice here
     * &mdash; {@code UpgradeNotesCoverageTest} walks this same file's {@code ## Upgrading} anchors.
     */
    @Test
    void theInstallChecklistStepsWereRunOnThisReleaseLine() {
        String text = read(RELEASE_CHECKLIST);
        int start = text.indexOf(INSTALL_SECTION);
        assertThat(start)
                .withFailMessage("""
                        THE INSTALL SECTION OF THE RELEASE CHECKLIST IS GONE.

                        Expected a heading '%s' in %s.

                        It carries the four checks this class structurally cannot make - the
                        registry, the fresh-clone install, `docker compose config -q`, and the values
                        only a running container refuses. Deleting it left every other test in this
                        repository green (measured), which is why this one exists. If the section
                        moved, move this constant with it; if a step is genuinely obsolete, delete
                        that step and its reason, not the section.""",
                        INSTALL_SECTION, RELEASE_CHECKLIST)
                .isNotNegative();

        int end = text.indexOf("\n## ", start + INSTALL_SECTION.length());
        String section = end < 0 ? text.substring(start) : text.substring(start, end);

        var steps = new ArrayList<int[]>();      // {offset, number}
        var headlines = new ArrayList<String>();
        var matcher = CHECKLIST_STEP.matcher(section);
        while (matcher.find()) {
            steps.add(new int[] {matcher.start(), Integer.parseInt(matcher.group(1))});
            headlines.add(matcher.group(1) + ". " + matcher.group(2).replaceAll("\\*\\*.*", "").strip());
        }

        assertThat(steps)
                .withFailMessage("""
                        THE INSTALL SECTION HAS FEWER NUMBERED STEPS THAN IT SHIPPED WITH.

                        Found %d (%s); expected at least %d.

                        A step is deleted deliberately, with its reason, or not at all - and a step
                        that merely stopped being written as `**<n>. ...` is invisible to this rule,
                        which is the same defect wearing formatting. Keep the shape.""",
                        steps.size(), headlines, CHECKLIST_STEPS)
                .hasSizeGreaterThanOrEqualTo(CHECKLIST_STEPS);

        Release release = newestRelease();
        var stale = new ArrayList<String>();
        for (int i = 0; i < steps.size(); i++) {
            String body = section.substring(steps.get(i)[0],
                    i + 1 < steps.size() ? steps.get(i + 1)[0] : section.length());
            var measured = MEASURED.matcher(body);
            String newest = null;
            while (measured.find()) {                       // the LATEST date in the step is its run
                newest = newest == null || measured.group(1).compareTo(newest) > 0
                        ? measured.group(1) : newest;
            }
            if (newest == null) {
                stale.add("  step %s - no `Measured <date>` line at all".formatted(headlines.get(i)));
            } else if (newest.compareTo(release.date()) < 0) {
                stale.add("  step %s - last measured %s, before %s (%s)"
                        .formatted(headlines.get(i), newest, release.tag(), release.date()));
            }
        }

        assertThat(stale)
                .withFailMessage("""
                        A RELEASE-CHECKLIST STEP HAS NOT BEEN RUN ON THIS RELEASE LINE.

                        %s

                        Newest release tag: %s (%s). Every step carries a `Measured <date>` line no
                        older than that.

                        These are the checks a unit test may not make: they need the network or a
                        daemon. Nothing else in this repository will ever tell you that `0.4.3` was
                        published (it was not), that the fresh-clone install still works, or that a
                        compose file still interpolates. Run the step, record what it said including
                        where the run departed from what the documents prescribe, and date it.

                        Do NOT date a step you did not run: the date is the claim.""",
                        String.join("\n", stale), release.tag(), release.date())
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
     * The newest release: its tag ({@code v0.18.2}), the line a document may pin ({@code 0.18}) and
     * the date the tag was created ({@code 2026-09-11}).
     */
    private record Release(String tag, String line, String date) {
    }

    /**
     * The newest {@code v<major>.<minor>.<patch>} tag, by version rather than by tag date &mdash; a
     * patch on an older line can be tagged after a newer one, and {@code --sort=-creatordate} would
     * then hand back the wrong line.
     *
     * <p><strong>From git, never from {@code pom.xml}</strong>, which carries the placeholder
     * {@code 0.0.0-DEV} - the real version is injected at image build time. CI checks out with
     * {@code fetch-depth: 0} on the job that runs this suite, so the tags are here.
     *
     * <p>One derivation for two rules: the pin rule compares documents against {@link Release#line()},
     * and {@link #theInstallChecklistStepsWereRunOnThisReleaseLine} compares a step's run date
     * against {@link Release#date()}. Two walks of {@code git tag} would be two answers to "which
     * release is current" the day somebody sorts one of them differently.
     */
    private static Release newestRelease() {
        // `Path.of(".")`, not `Path.of("")` - measured: ProcessBuilder rejects an empty directory,
        // and the failure reads as "git tag failed", which would send a reader looking for a git
        // problem that is not there.
        var result = PublishedCredentials.runGit(Path.of("."),
                "tag", "--list", "v*", "--format=%(refname:short) %(creatordate:short)");
        assertThat(result.ok())
                .withFailMessage("""
                        `git tag` failed, so the current release line cannot be derived and this rule
                        would have nothing to compare against: exit %d, %s""",
                        result.status(), result.detail())
                .isTrue();

        int bestMajor = -1;
        int bestMinor = -1;
        int bestPatch = -1;
        String date = "";
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
                date = matcher.group(4);
            }
        }
        assertThat(bestMajor)
                .withFailMessage("""
                        NO RELEASE TAG WAS FOUND, so the pin rule has nothing to hold the documents
                        to and would pass whatever they say, and the checklist rule has no date to
                        hold a step to. This checkout has no `v<x>.<y>.<z>` tag with a date: either
                        it is shallow, was cloned without tags, or `git tag --format` is unsupported
                        (it needs git 2.13).
                          locally: git fetch --tags
                          CI:      actions/checkout with `fetch-depth: 0`""")
                .isNotNegative();
        return new Release("v" + bestMajor + "." + bestMinor + "." + bestPatch,
                bestMajor + "." + bestMinor, date);
    }

    /**
     * The members that are prose rather than values, with the floor the stacks require: the landing
     * page plus (guide, stack README) per stack. Shared by the two rules that read prose - the
     * repository-path scan and the architecture-requirement demand - so neither can run over an
     * empty set and report a pass.
     */
    private static List<String> requireProseMembers() {
        int floor = DOCUMENTS_BELONGING_TO_NO_STACK
                + (DOCUMENTS_PER_STACK - 1) * installableStackModels().size();
        var prose = INSTALL_DOCUMENTS.keySet().stream()
                .filter(document -> !document.endsWith(".env.example"))
                .toList();
        assertThat(prose)
                .withFailMessage("""
                        FEWER PROSE MEMBERS THAN THE STACKS IN THE TREE REQUIRE, so a rule that reads
                        prose is running over a set too small to say anything. Found %s; the shape is
                        the landing page plus the guide and the stack README of each installable
                        stack, so %d.

                        This is the vacuity that let the architecture rule pass green over an EMPTY
                        document set while three of its siblings red (measured, HD-324 fix loop).""",
                        prose, floor)
                .hasSizeGreaterThanOrEqualTo(floor);
        return prose;
    }

    /** The stack templates, one per installable stack, with that as the floor. */
    private static List<String> requireTemplateMembers() {
        List<String> models = installableStackModels();
        var templates = INSTALL_DOCUMENTS.keySet().stream()
                .filter(document -> document.endsWith(".env.example"))
                .toList();
        assertThat(templates)
                .withFailMessage("""
                        FEWER TEMPLATES THAN INSTALLABLE STACKS. Found %s for stacks %s - so a rule
                        about the file a reader copies is not looking at every file a reader
                        copies.""",
                        templates, models)
                .hasSizeGreaterThanOrEqualTo(models.size());
        return templates;
    }

    /**
     * Every image pin a document offers as a literal a reader could copy. A variable reference
     * ({@code APP_IMAGE_TAG=$TAG}, {@code hamstrack:${APP_IMAGE_TAG}}) is not one.
     */
    private static List<String> pinsOffered(String document) {
        var pins = new ArrayList<String>();
        for (String line : read(document).split("\\R", -1)) {
            var matcher = PIN.matcher(line);
            while (matcher.find()) {
                String pin = matcher.group(1);
                if (!pin.startsWith("$") && !pin.contains("{")) {
                    pins.add(pin);
                }
            }
        }
        return pins;
    }

    /**
     * The repository documents a stack's README sends the installer to, repository-relative. A stack
     * README links out for exactly one reason - "the steps live once, over there" - so what it links
     * to is part of the install path whether or not it carries the command itself.
     */
    private static List<String> guidesLinkedFrom(String model) {
        var guides = new LinkedHashSet<String>();
        var matcher = GUIDE_LINK.matcher(read("deploy/" + model + "/README.md"));
        while (matcher.find()) {
            guides.add(Path.of("deploy", model).resolve(matcher.group(1)).normalize()
                    .toString().replace('\\', '/'));
        }
        return List.copyOf(guides);
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the install document " + path, e);
        }
    }
}
