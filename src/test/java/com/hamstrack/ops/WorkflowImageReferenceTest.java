package com.hamstrack.ops;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hamstrack.ops.PublishedCredentials.publishableFiles;
import static com.hamstrack.ops.PublishedCredentials.read;
import static com.hamstrack.ops.PublishedCredentials.repositoryPath;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-321 &mdash; every reference this repository ships to its own container image names the
 * registry owner in lowercase, and the one place the owner's login enters the tree folds it.</strong>
 *
 * <h2>The defect this exists for, and why nothing saw it</h2>
 * {@code github.repository_owner} is the account login <em>verbatim</em>, and this account's login is
 * {@code Zherikhov}. A registry reference must be lowercase: {@code docker pull
 * ghcr.io/Zherikhov/hamstrack:0.18} answers {@code invalid reference format: repository name
 * (Zherikhov/hamstrack) must be lowercase} (measured 2026-10-01). The expression had been written
 * into {@code .github/workflows/build.yml} twice. One of the two fed {@code
 * docker/metadata-action}, which lowercases its {@code images:} input, so it was correct; the other
 * fed a bare {@code docker} command inside the release job and was fatal &mdash; the same seven
 * characters, permitted or fatal depending on which YAML key they sat under. The step sat ahead of
 * the Release step, so the first tag would have pushed its images, cut no Release, and &mdash;
 * because {@code deploy.yml} fires on {@code workflow_run} with {@code conclusion == 'success'}
 * &mdash; skipped that tag's deploy as well.
 *
 * <p><strong>It survived a local rehearsal of the very commands it broke</strong>, because the
 * rehearsal substituted a hand-typed lowercase owner for the expression. A local run that stands in
 * for an interpolation tests everything except the interpolation, and the interpolation was the only
 * broken layer. That is the half a file-reading rule can have, and it is this class's whole subject:
 * what the YAML <em>says</em>, which no local run ever reads.
 *
 * <h2>The two questions, and which one carries the population</h2>
 * <ul>
 *   <li>{@link #everyReferenceToThisProjectsImageNamesItsOwnerInLowercase} is the category. Its
 *       members are the <strong>reference sites</strong> &mdash; every textual {@code
 *       ghcr.io/<owner>/hamstrack} in a file a commit from this checkout would publish: the
 *       workflows and the three installable/production compose files. Five today, over four files.
 *   <li>{@link #theOwnerLoginIsFoldedWhereItEntersTheTree} is the source half, and it is
 *       <strong>one member by construction</strong> &mdash; that is the fix, not an oversight.
 *       Folding at the source rather than at each use site is what deletes the "correct under this
 *       key, fatal under that one" distinction instead of documenting it, and it leaves this class
 *       with no exclusion to keep alive: there is no "…unless it is consumed by {@code
 *       docker/metadata-action}" clause, because no use site reads the raw login any more. An
 *       exclusion whose subject is a YAML key two lines away from the match is one a line-oriented
 *       scan cannot check, and this project's rule is that an exclusion is a live check or it is a
 *       lie.
 * </ul>
 *
 * <h2>What this cannot see</h2>
 * Whether the digest the release job pulls by is the digest that job pushed (that is one GitHub
 * expression reading another, and the first observation of it is the first tag cut after this
 * change); whether a reference resolves to the <em>intended</em> bytes; anything about a reference
 * built at runtime by a script rather than written down; and references inside <em>whole-line</em>
 * comments, which are skipped on purpose &mdash; the counter-example {@code ghcr.io/Zherikhov/hamstrack} in
 * {@code build.yml}'s own comment is the measured refusal quoted above, and a rule that reds on a
 * documented counter-example teaches the next author to delete the documentation.
 */
class WorkflowImageReferenceTest {

    /** Where the workflows live. Not {@code .gstack/**}, which is vendored tooling and not ours. */
    private static final String WORKFLOWS = ".github/workflows";

    /**
     * The owner-and-repository shape of this project's own image. The owner segment is captured so
     * the rule can ask what <em>kind</em> of thing it is. The segment needs at least one character,
     * so {@code docker-compose.prod.yml}'s {@code ghcr.io//hamstrack} &mdash; the empty-owner example
     * in its own comment &mdash; is not a member.
     *
     * <p>It alternates over whole {@code ${{ … }}} / {@code ${…}} groups <em>before</em> falling back
     * to a non-space character, because an interpolated owner CONTAINS SPACES: {@code ghcr.io/${{
     * steps.owner.outputs.name }}/hamstrack} and {@code ghcr.io/${GITHUB_OWNER:?set GITHUB_OWNER in
     * .env}/hamstrack} are both real references here. A space-anchored segment matched neither, so
     * the first version of this pattern found only the two hard-coded compose literals &mdash; a
     * population of lowercase constants, every member passing, with both of the sites the rule exists
     * for invisible to it. It failed its own floor, which is what that floor is for.
     */
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(
            "ghcr\\.io/((?:\\$\\{\\{[^}]*}}|\\$\\{[^}]*}|[^/\\s\"'])+)/hamstrack");

    /** An interpolation of the account login, which GitHub hands back with its case intact. */
    private static final Pattern OWNER_LOGIN =
            Pattern.compile("\\$\\{\\{\\s*github\\.repository_owner\\s*\\}\\}");

    /**
     * A fold to lowercase, in either form a shell step in this repository would use. There is no
     * {@code toLower} in the GitHub expression language, which is why this is a shell pattern and
     * not an expression one.
     */
    private static final Pattern FOLD = Pattern.compile(
            "tr\\s+(['\"])\\[:upper:]\\1\\s+(['\"])\\[:lower:]\\2"      // tr '[:upper:]' '[:lower:]'
                    + "|\\$\\{[A-Za-z_][A-Za-z0-9_]*,,}");               // ${var,,}

    /** A GitHub Actions expression, whatever it names. */
    private static final Pattern GITHUB_EXPRESSION = Pattern.compile("\\$\\{\\{(.+?)}}");

    /** A Compose/shell variable reference, whatever its default or error clause. */
    private static final Pattern SHELL_VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)[^}]*}");

    /**
     * The template that tells an operator what to put in the one owner variable a shipped stack
     * leaves to them. Read rather than remembered: the permission for {@code
     * ${GITHUB_OWNER}} is that this file says, next to that variable, that it must be lowercase.
     */
    private static final Path ENV_TEMPLATE = Path.of(".env.prod.example");

    /**
     * A collapse floor, not a count: a derivation that stops matching passes a per-member rule
     * vacuously, and that is the one way this class could fail to fail. Deliberately below the real
     * population so an ordinary edit never touches it; the failure messages print the sets.
     */
    private static final int MIN_REFERENCES = 4;

    /** @see #MIN_REFERENCES */
    private static final int MIN_LOGIN_SITES = 1;

    @Test
    void everyReferenceToThisProjectsImageNamesItsOwnerInLowercase() {
        List<Reference> references = imageReferences();

        assertThat(references)
                .withFailMessage("""
                        THE IMAGE REFERENCES COULD NOT BE READ, so this rule has nothing to hold and
                        would pass a workflow that pulls `ghcr.io/Zherikhov/hamstrack`.

                        Found: %s
                        Files scanned: %s

                        The population is every `ghcr.io/<owner>/hamstrack` on a non-comment line of a
                        file this checkout would publish. An empty or tiny set means the image moved
                        registry, the files moved, or the pattern stopped matching - teach the
                        derivation rather than lowering this floor.""",
                        references, scannedFiles())
                .hasSizeGreaterThanOrEqualTo(MIN_REFERENCES);

        var offenders = new ArrayList<String>();
        for (Reference reference : references) {
            String why = whyItCannotBeTrusted(reference);
            if (why != null) {
                offenders.add("  %s:%d - `%s`%n      %s".formatted(
                        reference.file(), reference.line(), reference.owner(), why));
            }
        }

        assertThat(offenders)
                .withFailMessage("""
                        A REFERENCE TO THIS PROJECT'S IMAGE CAN RESOLVE TO AN UPPERCASE OWNER.

                        %s

                        All %d references: %s

                        `docker pull ghcr.io/Zherikhov/hamstrack:0.18` answers `invalid reference
                        format: repository name (Zherikhov/hamstrack) must be lowercase`, and
                        `github.repository_owner` is that login verbatim. A reference that reaches a
                        bare `docker` command this way fails the step it is in; one that reaches
                        `docker/metadata-action` does not, because that action folds its own input -
                        which is exactly how this shipped correct in one key and fatal in the next.

                        Use `${{ steps.owner.outputs.name }}` (same job) or
                        `${{ needs.build-and-push.outputs.owner }}` (downstream job) - the output of
                        the one step that folds the login. In a compose file, write the owner in
                        lowercase, or leave it to a variable %s documents as lowercase.""",
                        String.join("\n", offenders), references.size(), references, ENV_TEMPLATE)
                .isEmpty();

        // THE GRANULARITY CONTROL. The unit of this rule is the reference SITE, not the file: the
        // defect was one of two references in one file, so a population keyed on the file would have
        // been satisfied by its correct sibling. Strictly more members than files is what makes those
        // two keyings different answers, and it fires if a future edit merges the sites back into one
        // (a single `IMAGE:` env at job level, say) without this rule noticing it stopped asking per
        // site.
        var files = new LinkedHashSet<String>();
        references.forEach(reference -> files.add(reference.file()));
        assertThat(files.size())
                .withFailMessage("""
                        THIS RULE HAS STOPPED ASKING ITS QUESTION PER REFERENCE SITE.

                        References: %d (%s)
                        Files:      %s

                        With one reference per file, a file whose single mention is correct answers for
                        the file - and the HD-321 defect was the SECOND reference in a file whose
                        first one was fine. The unit is the site; the files belong in the message.""",
                        references.size(), references, files)
                .isLessThan(references.size());
    }

    /**
     * The source half: the account login is read in exactly one place, and that place folds it. One
     * member today <strong>by construction</strong> &mdash; see the class javadoc; the population
     * that carries the category is the reference sites.
     */
    @Test
    void theOwnerLoginIsFoldedWhereItEntersTheTree() {
        var sites = new ArrayList<String>();
        var unfolded = new ArrayList<String>();
        for (Path workflow : workflowFiles()) {
            String path = repositoryPath(workflow);
            int number = 0;
            for (String raw : read(workflow).split("\\R", -1)) {
                number++;
                String line = live(raw);
                if (!OWNER_LOGIN.matcher(line).find()) {
                    continue;
                }
                sites.add("%s:%d".formatted(path, number));
                if (!FOLD.matcher(line).find()) {
                    unfolded.add("  %s:%d - %s".formatted(path, number, raw.strip()));
                }
            }
        }

        assertThat(sites)
                .withFailMessage("""
                        NO WORKFLOW READS `github.repository_owner` AT ALL, so this rule holds nothing.

                        Workflows scanned: %s

                        Either the registry owner is now hard-coded (then the sibling rule
                        everyReferenceToThisProjectsImageNamesItsOwnerInLowercase is the one that
                        matters and this one should be deleted with it), or the expression moved
                        somewhere this scan does not reach.""", workflowFiles())
                .hasSizeGreaterThanOrEqualTo(MIN_LOGIN_SITES);

        assertThat(unfolded)
                .withFailMessage("""
                        A WORKFLOW READS THE ACCOUNT LOGIN WITHOUT FOLDING IT TO LOWERCASE.

                        %s

                        All sites that read it: %s

                        `github.repository_owner` is the login verbatim and this account's is
                        `Zherikhov`. There is no `toLower` in the GitHub expression language, so the
                        fold is a shell step - `${var,,}` or `tr '[:upper:]' '[:lower:]'` - and it
                        happens ONCE, where the login enters the tree, published as a job output. It
                        must be on the SAME LINE as the interpolation, which is why build.yml writes
                        `raw='${{ github.repository_owner }}'; name="${raw,,}"`: a fold one line below
                        is a fold an edit can move away. Reading the raw login at a use site is the
                        shape that shipped - correct where `docker/metadata-action` folded it, fatal
                        where a bare `docker pull` did not.""", String.join("\n", unfolded), sites)
                .isEmpty();
    }

    // ============================================================ helpers

    /** A single textual mention of this project's image, keyed on where it is and what owner it names. */
    private record Reference(String file, int line, String owner) {
        @Override
        public String toString() {
            return "%s:%d(%s)".formatted(file, line, owner);
        }
    }

    /**
     * Why this reference cannot be trusted to be lowercase, or {@code null} when it can. Three
     * shapes of owner segment, each with its own permission:
     * <ul>
     *   <li>a literal &mdash; it is lowercase or it is not;
     *   <li>a GitHub expression &mdash; permitted when it names a folded output <em>and</em> the file
     *       it sits in still contains the fold, so deleting the fold reds the use site;
     *   <li>a Compose variable &mdash; permitted when {@link #ENV_TEMPLATE} says, beside that
     *       variable, that it must be lowercase. That is the only owner this repository does not
     *       control, and the check is a read of the live template rather than a sentence here.
     * </ul>
     */
    private static String whyItCannotBeTrusted(Reference reference) {
        String owner = reference.owner();
        Matcher expression = GITHUB_EXPRESSION.matcher(owner);
        if (expression.find()) {
            String named = expression.group(1).strip();
            boolean folded = named.matches("steps\\.[A-Za-z0-9_-]+\\.outputs\\.name")
                    || named.matches("needs\\.[A-Za-z0-9_-]+\\.outputs\\.owner");
            if (!folded) {
                return "a GitHub expression that is not the folded owner output: `" + named + "`";
            }
            if (!foldsTheLogin(Path.of(reference.file()))) {
                return "names a folded owner output, but " + reference.file()
                        + " no longer folds anything to lowercase - the fold it relies on is gone";
            }
            return null;
        }
        Matcher variable = SHELL_VARIABLE.matcher(owner);
        if (variable.find()) {
            String name = variable.group(1);
            return templateDemandsLowercase(name) ? null
                    : "an operator-supplied variable `" + name + "` that " + ENV_TEMPLATE
                            + " does not document as lowercase beside its own assignment";
        }
        return owner.equals(owner.toLowerCase(java.util.Locale.ROOT)) ? null
                : "a literal with an uppercase letter - ghcr.io rejects the reference outright";
    }

    /**
     * Does {@link #ENV_TEMPLATE} tell the operator, in the comment block attached to this variable's
     * own assignment, that the value must be lowercase? The block is the contiguous run of comment
     * lines immediately above the assignment, so a "lowercase" elsewhere in the file cannot answer
     * for it.
     */
    private static boolean templateDemandsLowercase(String variable) {
        String[] lines = read(ENV_TEMPLATE).split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].startsWith(variable + "=")) {
                continue;
            }
            for (int above = index - 1; above >= 0 && lines[above].startsWith("#"); above--) {
                if (lines[above].toLowerCase(java.util.Locale.ROOT).contains("lowercase")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Does this file still <em>execute</em> a fold to lowercase? Over {@link #live} lines only, and
     * that is the whole point of the method: the version of this check that read the raw file text
     * was satisfied by {@code build.yml}'s own comment, which QUOTES both accepted forms of the fold
     * next to the step that performs it. Measured in the HD-321 ops re-review: deleting the real
     * fold at {@code build.yml:343} left this clause green on the comment at {@code :321}, so the
     * exclusion the class's javadoc calls "a live check" was satisfied by prose - inside the class
     * whose own standard is that an exclusion is a live check or it is a lie.
     */
    private static boolean foldsTheLogin(Path file) {
        for (String raw : read(file).split("\\R", -1)) {
            if (FOLD.matcher(live(raw)).find()) {
                return true;
            }
        }
        return false;
    }

    /** Every image reference on a live (non-comment) line of every scanned file. */
    private static List<Reference> imageReferences() {
        var found = new ArrayList<Reference>();
        for (Path file : scannedFiles()) {
            String path = repositoryPath(file);
            int number = 0;
            for (String raw : read(file).split("\\R", -1)) {
                number++;
                Matcher matcher = IMAGE_REFERENCE.matcher(live(raw));
                while (matcher.find()) {
                    found.add(new Reference(path, number, matcher.group(1)));
                }
            }
        }
        return found;
    }

    /**
     * The workflows plus every shipped Compose file. Not filtered to the production/installable ones
     * by name: a development stack that started pulling the published image would be a reference like
     * any other, and the filter that excused it would be the thing to maintain.
     */
    private static List<Path> scannedFiles() {
        var files = new ArrayList<>(workflowFiles());
        for (Path file : publishableFiles(".")) {
            if (file.getFileName().toString().matches("docker-compose.*\\.ya?ml")) {
                files.add(file);
            }
        }
        return files;
    }

    private static List<Path> workflowFiles() {
        return publishableFiles(WORKFLOWS).stream()
                .filter(file -> file.getFileName().toString().matches(".*\\.ya?ml"))
                .toList();
    }

    /**
     * The part of a line that is instruction rather than prose. Every file this class scans is YAML
     * &mdash; both halves of {@link #scannedFiles()} filter on {@code .ya?ml} &mdash; so the answer
     * is: nothing when the <em>whole line</em> is a comment, and the whole line otherwise. The
     * uppercase owner in this repository's own tree is a quoted measurement inside a comment, and a
     * rule that red-flagged it would teach the next author to delete the measurement.
     *
     * <p><strong>It used to truncate at the first attached {@code " #"}, and that hid an executable
     * reference.</strong> A {@code #} inside a quoted string is not a comment in YAML, so
     * {@code - run: curl 'http://x/ #' && docker pull ghcr.io/Zherikhov/hamstrack:0.18} was cut
     * before the pull and the rule stayed GREEN on a line that really would run it (measured, HD-321
     * ops re-review). The accidentally reachable shape is ordinary rather than contrived:
     * {@code - run: echo "build #${{ github.run_number }}" && docker pull ghcr.io/…}.
     *
     * <p>Dropping the truncation costs nothing <em>today</em>, and that was measured rather than
     * assumed: the counter-example it was installed for is {@code build.yml:318}, a whole-line
     * comment, and a grep over every scanned file found no non-comment line carrying
     * {@code ghcr.io/<owner>/hamstrack} or {@code github.repository_owner} after a {@code #}. The
     * consequence is deliberate &mdash; a line that mentions a reference in a <em>trailing</em>
     * comment is now a member. If one is ever wanted, put the sentence on its own comment line
     * rather than re-adding a truncation that a quote can hide an instruction behind.
     */
    private static String live(String raw) {
        return raw.stripLeading().startsWith("#") ? "" : raw;
    }
}
