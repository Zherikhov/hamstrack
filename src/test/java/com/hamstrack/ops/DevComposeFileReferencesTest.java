package com.hamstrack.ops;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-314 — a rule about a file must name a file that is in the tree.</strong>
 *
 * <p>This exists because of a measured green. Renaming the development stack off the bare
 * name Compose looks for, onto {@code docker-compose.dev.yml}, left
 * {@code JwtSecretValidationTest} <em>entirely green</em> while three of its members named a
 * file that no longer existed — including an assertion whose own failure message reads
 * <em>"The repository's own development compose file stopped being one"</em>, passing about a
 * ghost. The scan's population is the tracked tree; its checks were asking about string
 * literals. A population and a check at two different granularities cannot disagree, so the
 * seal read as coverage and held nothing.
 *
 * <p>The unit here is therefore the <strong>(source file, path literal)</strong> pair, not the
 * class and not the filename: every compose path spelled out by a source that encodes the
 * development-stack classification has to resolve to a file a clone would actually receive.
 *
 * <p><strong>The population is derived, never listed.</strong> It is every publishable
 * {@code .java} source that mentions {@code isDevelopmentCompose} — so the two oracle tests
 * that write {@code docker-compose.yml} fixtures <em>into a scratch box</em> are excluded by a
 * property (they do not encode this rule) rather than by a name, and a third source that
 * starts encoding it joins on the day it is written.
 *
 * <p><strong>Counterfactuals are live checks.</strong> A fixture that names a path in order to
 * prove it is <em>not</em> the development stack must keep naming a path that does not exist,
 * or it proves nothing — so each entry of {@link #COUNTERFACTUAL} asserts its own absence
 * rather than being excused in prose.
 */
class DevComposeFileReferencesTest {

    /**
     * A compose path as a source or document spells one. Glob patterns
     * ({@code docker-compose.*.dev.yml}, the regexes inside {@code PublishedCredentials})
     * do not match and are not meant to: a {@code *} is not a path, and the character class
     * after {@code docker-compose} stops at one.
     */
    private static final Pattern COMPOSE_PATH =
            Pattern.compile("[A-Za-z0-9_./-]*docker-compose[A-Za-z0-9_.-]*\\.ya?ml");

    /** {@code spring.docker.compose.file=<path>} in Spring's always-loaded configuration. */
    private static final Pattern COMPOSE_FILE_PROPERTY =
            Pattern.compile("(?m)^\\s*spring\\.docker\\.compose\\.file\\s*=\\s*(\\S+)\\s*$");

    /**
     * A {@code docker compose} invocation together with ALL of its {@code -f} arguments.
     *
     * <p><strong>Both halves of this were wrong once, and silently (HD-314).</strong> It
     * allowed exactly one optional global flag — {@code --ansi <v>} — between
     * {@code docker compose} and the first {@code -f}, so the day a document grew a command
     * carrying {@code --project-directory .} that command left the population entirely. And it
     * captured only the FIRST {@code -f}, so every layering command in this repository had its
     * second file checked by nothing. Neither showed up as a failure: the floor was "at least
     * one command", which the untouched commands went on satisfying.
     *
     * <p>So: arbitrary leading global flags, and every {@code -f} in the command. Measured
     * when this was widened — the population went from 21 paths to 31.
     */
    private static final Pattern COMPOSE_COMMAND = Pattern.compile(
            "docker\\s+compose((?:\\s+(?:-[^f\\s]\\S*|--[a-z-]+(?:[= ]\\S+)?))*(?:\\s+-f\\s+\\S+)+)");

    private static final Pattern DASH_F_ARGUMENT = Pattern.compile("-f\\s+(\\S+)");

    /**
     * A shell line continuation, joined before matching exactly as a shell joins it. The
     * layering command in {@code docs/observability.md} is wrapped across two lines, and a
     * pattern that stops at the backslash reads it as no command at all.
     */
    private static final Pattern LINE_CONTINUATION = Pattern.compile("\\\\[ \\t]*\\r?\\n[ \\t]*");

    /**
     * Paths named so that a test can prove they are <em>not</em> the development stack. Each
     * is asserted ABSENT below: the day somebody adds one of these files, the fixture that
     * relies on its absence stops meaning what it says, and this test is where that surfaces.
     * The reason names a property, never a count.
     */
    private static final Map<String, String> COUNTERFACTUAL = Map.of(
            "examples/docker-compose.dev.yml",
            "proves the exemption is refused in a subdirectory, so it must not be a real file",
            "deploy/dc/docker-compose.dev.yml",
            "proves a .dev. name inside the install directory is still not the dev stack",
            "docker-compose.yml",
            "the bare root name is asserted NOT to be the development stack - the exemption is "
            + "carried by the .dev. marker. A file here would put that assertion's subject back "
            + "in the tree and change what the rule is being asked");

    private static Set<String> publishable() {
        var out = new TreeSet<String>();
        for (Path file : PublishedCredentials.publishableFiles(".")) {
            out.add(PublishedCredentials.repositoryPath(file));
        }
        return out;
    }

    private static List<Path> sourcesEncodingTheRule() {
        var out = new ArrayList<Path>();
        for (Path file : PublishedCredentials.publishableFiles(".")) {
            String path = PublishedCredentials.repositoryPath(file);
            if (path.endsWith(".java") && PublishedCredentials.read(file).contains("isDevelopmentCompose")) {
                out.add(file);
            }
        }
        return out;
    }

    /**
     * Every compose path a rule-encoding source spells out is a file this checkout would
     * publish — or a declared counterfactual, which must NOT be.
     */
    @Test
    void everyComposePathNamedByTheClassificationRuleResolvesToAFileOrADeclaredCounterfactual() {
        Set<String> tree = publishable();
        List<Path> sources = sourcesEncodingTheRule();
        var dangling = new ArrayList<String>();
        var pairs = new LinkedHashSet<String>();
        var realPaths = new TreeSet<String>();

        for (Path source : sources) {
            String path = PublishedCredentials.repositoryPath(source);
            String text = PublishedCredentials.read(source);
            Matcher m = COMPOSE_PATH.matcher(text);
            while (m.find()) {
                String named = m.group();
                pairs.add(path + " -> " + named);
                if (COUNTERFACTUAL.containsKey(named)) {
                    continue;
                }
                realPaths.add(named);
                if (!tree.contains(named)) {
                    dangling.add(path + " names `" + named + "`, which is not in the tree");
                }
            }
        }

        assertThat(sources)
                .withFailMessage("""

                        No source mentioning isDevelopmentCompose was found, so this whole test is \
                        vacuous. The population is derived from that token - if the predicate was \
                        renamed, rename it here too; if the rule moved, point this at its new home.""")
                .hasSizeGreaterThanOrEqualTo(2);

        assertThat(dangling)
                .withFailMessage("""

                        A RULE ABOUT THE DEVELOPMENT COMPOSE FILE NAMES A FILE THAT IS NOT IN THE TREE.

                        This is the failure that renaming docker-compose.yml did NOT produce, which is \
                        why this test exists: the fixtures and assertions below were keyed on a string \
                        literal, so they went on passing about a file nobody would receive, and one of \
                        them reported success with the words "the repository's own development compose \
                        file stopped being one".

                        Fix the reference, not this test. If you renamed or moved a compose file, every \
                        line here has to move with it in the SAME change - that is the category, and \
                        nothing else enforces it.

                        (%d compose path references examined across %d rule-encoding sources.)

                        Dangling references:
                          %s"""
                        .formatted(pairs.size(), sources.size(), String.join("\n  ", dangling)))
                .isEmpty();

        assertThat(realPaths)
                .withFailMessage("""

                        The classification rule now names only one real compose file (%s). Two members \
                        merged into one, and a check that asks about a single path stops being able to \
                        tell "root development stack" apart from "install template in a subdirectory" - \
                        which is the distinction isDevelopmentCompose exists to draw. Keep a fixture on \
                        each side of it.""".formatted(realPaths))
                .hasSizeGreaterThanOrEqualTo(2);
    }

    /** Each counterfactual still is one. */
    @Test
    void everyDeclaredCounterfactualPathIsStillAbsentFromTheTree() {
        Set<String> tree = publishable();
        var nowReal = new LinkedHashMap<String, String>();
        COUNTERFACTUAL.forEach((path, why) -> {
            if (tree.contains(path)) {
                nowReal.put(path, why);
            }
        });

        assertThat(nowReal)
                .withFailMessage("""

                        A path that a fixture names IN ORDER TO PROVE IT IS NOT THE DEVELOPMENT STACK \
                        now exists in the tree, so that fixture no longer proves anything - it asserts \
                        a property of a file somebody added.

                        Either move the new file, or give the fixture a different counterfactual path \
                        and update COUNTERFACTUAL with the property it demonstrates.

                        Now real: %s""".formatted(nowReal))
                .isEmpty();
    }

    /**
     * The exemption has a live subject. Without this, a predicate that matches nothing at all
     * is indistinguishable from one that matches the dev stack.
     */
    @Test
    void theDevelopmentStackExemptionMatchesAtLeastOneFileInTheTree() {
        var matched = new TreeSet<String>();
        for (Path file : PublishedCredentials.publishableFiles(".")) {
            if (PublishedCredentials.isDevelopmentCompose(file)) {
                matched.add(PublishedCredentials.repositoryPath(file));
            }
        }

        assertThat(matched)
                .withFailMessage("""

                        isDevelopmentCompose matches NO file in this tree, so the local-dev-stack \
                        exemption has no subject and localDevStackCredentials() is silently empty - \
                        every value it used to exempt is now reported, or was never checked at all.

                        Usually this means a development compose file was renamed without its `.dev.` \
                        marker, or deleted.""")
                .isNotEmpty();
    }

    /**
     * {@code spring.docker.compose.file} names the development stack for {@code mvnw
     * spring-boot:run}. Pointed at a file that is not there, Boot fails the run with
     * {@code Docker compose file '...' does not exist}.
     */
    @Test
    void theComposeFileNamedByApplicationPropertiesExists() {
        Path properties = Path.of("src/main/resources/application.properties");
        Matcher m = COMPOSE_FILE_PROPERTY.matcher(PublishedCredentials.read(properties));

        assertThat(m.find())
                .withFailMessage("""

                        `spring.docker.compose.file` is gone from application.properties. The \
                        development stack is not on one of Compose's default names any more, so \
                        without that line `mvnw spring-boot:run` fails at startup with \
                        `IllegalStateException: No Docker Compose file found in directory`.""")
                .isTrue();

        String named = m.group(1);
        assertThat(publishable())
                .withFailMessage("""

                        application.properties points spring.docker.compose.file at `%s`, which is not \
                        a file in this tree. `mvnw spring-boot:run` fails with `Docker compose file \
                        '...' does not exist`. Point it at the development stack, or rename the stack \
                        back.""".formatted(named))
                .contains(named);
    }

    /**
     * A document that tells a reader to run {@code docker compose -f X} has to name an X they
     * received. Bare {@code docker compose} commands are deliberately not checked here: on the
     * production box they run in {@code /opt/hamstrack}, and inside {@code deploy/dc/} they
     * resolve to the file beside them.
     */
    @Test
    void everyDashFComposeCommandInTrackedDocumentationNamesAFileInTheTree() {
        Set<String> tree = publishable();
        var dangling = new ArrayList<String>();
        int examined = 0;
        int layeringCommands = 0;

        for (Path file : PublishedCredentials.publishableFiles(".")) {
            String path = PublishedCredentials.repositoryPath(file);
            if (!path.endsWith(".md") || path.startsWith("docs/design/") || path.startsWith("docs/retro/")) {
                continue;
            }
            String text = LINE_CONTINUATION.matcher(PublishedCredentials.read(file)).replaceAll(" ");
            Matcher command = COMPOSE_COMMAND.matcher(text);
            while (command.find()) {
                int inThisCommand = 0;
                Matcher argument = DASH_F_ARGUMENT.matcher(command.group(1));
                while (argument.find()) {
                    String named = argument.group(1);
                    // Only a token SHAPED like a compose file is a claim about a file. `-f …`
                    // in a sentence about the command form, `-f $COMPOSE_FILE` and a glob are
                    // not, and reading them as paths reports prose as drift.
                    if (!named.endsWith(".yml") && !named.endsWith(".yaml")) {
                        continue;
                    }
                    if (named.startsWith("$") || named.contains("*")) {
                        continue;
                    }
                    examined++;
                    inThisCommand++;
                    if (!tree.contains(named) && !tree.contains(path.replaceAll("[^/]+$", "") + named)) {
                        // ASCII only: this string is read off a console, and a `…` arrives as
                        // mojibake under the default Windows code page.
                        dangling.add(path + " tells a reader to run `docker compose ... -f " + named + "`");
                    }
                }
                if (inThisCommand > 1) {
                    layeringCommands++;
                }
            }
        }

        assertThat(examined)
                .withFailMessage("""

                        No `docker compose -f <file>` command was found in any tracked document, so \
                        this check examined nothing. The development stack needs an explicit -f, so at \
                        least README.md should carry one - if the shape of those commands changed, \
                        update COMPOSE_COMMAND.""")
                .isGreaterThanOrEqualTo(1);

        // A SHAPE floor rather than a number, and it guards the exact regression this test
        // had: capturing only the first -f leaves every layered command half-checked while
        // the count floor goes on being satisfied by the single-file ones.
        assertThat(layeringCommands)
                .withFailMessage("""

                        No documented command names TWO compose files, so nothing here proves that a \
                        second `-f` is checked at all.

                        That is how this test previously passed while the layering command in \
                        docs/observability.md was outside its population entirely: the floor counted \
                        paths, and the single-file commands kept it satisfied. If layering genuinely \
                        stopped being documented, delete this assertion deliberately - do not let it \
                        decay into one that cannot fail.""")
                .isGreaterThanOrEqualTo(1);

        assertThat(dangling)
                .withFailMessage("""

                        A document tells its reader to run a compose file that is not in the tree, so \
                        the command fails with `no such file or directory` on a fresh clone.

                        (%d -f paths examined, %d of them in commands naming more than one file.)

                        %s""".formatted(examined, layeringCommands, String.join("\n  ", dangling)))
                .isEmpty();
    }
}
