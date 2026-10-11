package com.hamstrack.ops;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-343 &mdash; the shared Dockerfile parser's own witness, and the rule that every reader
 * of that file uses it.</strong>
 *
 * <h2>Why a shared parser needs a BETTER witness than the two it replaced</h2>
 * Sharing {@link ContainerJvm} was the right answer to two readers that disagreed, but it changes
 * the shape of the risk rather than removing it: <em>two parsers that disagree red one of
 * themselves, while one parser that is wrong is wrong in silence everywhere.</em> And the shared
 * class arrived with no test of its own &mdash; its correctness was observed only through two
 * consumers against the one real {@code Dockerfile}, which exercises exactly <strong>one</strong> of
 * the forms below (a single-line JSON-array {@code ENTRYPOINT}). The positive control that proved
 * the {@code ENV VAR=} form worked was a hand run, and was reverted, so it left nothing behind.
 *
 * <h2>What the table is for, and why half of it asserts invisibility</h2>
 * A parser breaks in two directions. Matching too little is the HD-321 defect: a flag moves to a
 * form the scan cannot see, leaves the population, and the floor stays satisfied by the survivors
 * &mdash; measured twice now, once on {@code ENV JAVA_TOOL_OPTIONS=} (HD-321) and once on the
 * legacy space form (HD-343 round 4, below). Matching too much is the other way: a comment or an
 * unrelated variable starts answering for a flag nobody declared, and then the rule is satisfied by
 * prose. Both directions are rows here, and the invisibility rows are not padding &mdash; they are
 * the half that stops a future widening of the pattern from being free.
 *
 * <h2>The form that was missing, and the consequence that made it a defect</h2>
 * {@code configuresTheJvm} required the variable to be followed by {@code =}, so Docker's
 * <strong>legacy {@code ENV <key> <value>} form</strong> &mdash; still accepted by Docker &mdash;
 * was invisible. With the heap flag written that way, {@link ContainerHandoverClaimsTest} passed
 * with four green tests while the flag had silently left its population, because
 * {@code MIN_JVM_FLAGS = 2} stayed satisfied by the two surviving {@code -Duser.*} pins. That is
 * the sentence {@code ContainerHandoverClaimsTest}'s own javadoc quotes from HD-321 &mdash; "from
 * three members to two while MIN_JVM_FLAGS stayed satisfied" &mdash; happening again, in the class
 * written to retire it.
 */
class ContainerJvmTest {

    /**
     * One way a {@code Dockerfile} can or cannot put a flag into the container's JVM.
     *
     * @param what    what the row is about, for the failure message
     * @param source  a whole synthetic Dockerfile
     * @param visible whether {@link ContainerJvm} must find a JVM-configuring directive in it
     */
    private record Form(String what, String source, boolean visible) {
    }

    /** A flag only a correct parse can see; distinct per row so a row cannot pass on another's. */
    private static final Pattern HEAP = Pattern.compile("-XX:MaxRAMPercentage=|-Xmx\\d");

    private static List<Form> forms() {
        return List.of(
                new Form("the real shape: a single-line JSON-array ENTRYPOINT",
                        "FROM eclipse-temurin:21-jre-alpine\n"
                        + "ENTRYPOINT [\"java\", \"-XX:MaxRAMPercentage=50.0\", \"-jar\", \"app.jar\"]\n",
                        true),
                new Form("CMD instead of ENTRYPOINT",
                        "FROM x\nCMD [\"java\", \"-Xmx512m\", \"-jar\", \"app.jar\"]\n",
                        true),
                new Form("an ENTRYPOINT split over continuation lines",
                        "FROM x\nENTRYPOINT [\"java\", \\\n    \"-Xmx512m\", \\\n    \"-jar\", \"app.jar\"]\n",
                        true),
                new Form("ENV JAVA_TOOL_OPTIONS=, the form the Dockerfile recommends to operators",
                        "FROM x\nENV JAVA_TOOL_OPTIONS=\"-XX:MaxRAMPercentage=50.0\"\n"
                        + "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n",
                        true),
                new Form("the variable second in a multi-assignment ENV",
                        "FROM x\nENV TZ=UTC JAVA_TOOL_OPTIONS=-Xmx512m\n"
                        + "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n",
                        true),
                new Form("the LEGACY `ENV <key> <value>` space form, still accepted by Docker",
                        "FROM x\nENV JAVA_TOOL_OPTIONS -XX:MaxRAMPercentage=50.0\n"
                        + "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n",
                        true),
                new Form("a COMMENT naming ENTRYPOINT and a flag must stay invisible",
                        "FROM x\n# ENTRYPOINT [\"java\", \"-Xmx512m\"] - what we used to do\n",
                        false),
                new Form("an UNRELATED variable holding a flag must stay invisible",
                        "FROM x\nENV MAVEN_OPTS=-Xmx512m\n",
                        false),
                new Form("a variable whose name merely STARTS with ours must stay invisible",
                        "FROM x\nENV JAVA_TOOL_OPTIONS_EXTRA=-Xmx512m\n",
                        false),
                new Form("our variable as another variable's VALUE must stay invisible",
                        "FROM x\nENV SOMETHING JAVA_TOOL_OPTIONS -Xmx512m\n",
                        false),
                new Form("a LOWERCASE instruction keyword; Docker's keywords are case-insensitive",
                        "FROM x\nenv JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50.0\n"
                        + "entrypoint [\"java\", \"-jar\", \"app.jar\"]\n",
                        true),
                new Form("MIXED CASE, the quiet one: upper ENTRYPOINT keeps the -Duser.* pins so a"
                         + " floor of 2 stays satisfied while a lowercase env holds the heap flag",
                        "FROM x\nenv JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50.0\n"
                        + "ENTRYPOINT [\"java\", \"-Duser.language=en\", \"-Duser.country=US\","
                        + " \"-jar\", \"app.jar\"]\n",
                        true),
                new Form("a lowercase VARIABLE name must stay invisible: env var names are"
                         + " case-SENSITIVE, so only the keyword may be folded",
                        "FROM x\nENV java_tool_options=-Xmx512m\n",
                        false),
                new Form("the _EXTRA near-miss in the LEGACY spelling must stay invisible",
                        "FROM x\nENV JAVA_TOOL_OPTIONS_EXTRA -Xmx9g\n",
                        false));
    }

    @Test
    void readsAFlagInEveryFormADockerfileCanDeclareItAndInNoOtherForm() {
        var wrong = new ArrayList<String>();
        for (Form form : forms()) {
            var directives = ContainerJvm.jvmConfiguringDirectives(form.source());
            boolean seen = HEAP.matcher(directives).find();
            if (seen != form.visible()) {
                wrong.add("  %s%n    expected the flag to be %s, but the parse %s it%n    parsed: %s"
                        .formatted(form.what(),
                                form.visible() ? "VISIBLE" : "INVISIBLE",
                                seen ? "found" : "missed",
                                directives.isBlank() ? "(nothing)" : directives));
            }
        }

        assertThat(wrong)
                .as("""
                    ContainerJvm reads this Dockerfile form differently from the rule it serves, and
                    both directions are defects. A form it MISSES takes the flag out of
                    ContainerHandoverClaimsTest's population while that rule's floor stays satisfied
                    by the surviving flags - measured twice, so it is the failure mode to assume. A
                    form it wrongly MATCHES lets a comment or an unrelated variable answer for a
                    flag nobody declared. Fix ContainerJvm.configuresTheJvm, not this table, unless
                    Docker's syntax itself has changed. Rows: %d. Wrong:%n%s"""
                        .formatted(forms().size(), String.join("\n", wrong)))
                .isEmpty();
    }

    /**
     * Holds the table's two halves against each other. It reads <em>no real file</em>: the first row
     * is labelled as today's shape but is hand-copied, and an earlier name for this method claimed
     * otherwise — measured, with the real {@code ENTRYPOINT} commented out this stayed green. That
     * the real {@code Dockerfile} declares a bound is held twice and elsewhere, both seen red against
     * exactly that plant: {@code JvmBoundsTest#everyJvmThisProjectStartsDeclaresAHeapBound} names the
     * missing {@code CONTAINER} kind, and {@code ContainerHandoverClaimsTest} reds on the hand-over.
     * So this method is deliberately a collapse detector over the fixtures and nothing more.
     */
    @Test
    void theTableKeepsBothHalvesSoNeitherDirectionCanBeTradedAway() {
        var rows = forms();

        // Floors, not counts, and each kept a couple below the real half so an ordinary edit never
        // touches them - the idiom ContainerHandoverClaimsTest#MIN_BEHAVIOUR_KEYS states. They are
        // collapse detectors: the live numbers belong in the failure message, which computes them.
        assertThat(rows.stream().filter(Form::visible).count())
                .as("The table lost its must-be-seen rows, so it can no longer catch a parser that"
                    + " matches too little - the HD-321 defect, which has now recurred in three"
                    + " spellings (ENV VAR=, the legacy ENV VAR space form, and a lowercase"
                    + " keyword).")
                .isGreaterThanOrEqualTo(6);

        assertThat(rows.stream().filter(form -> !form.visible()).count())
                .as("The table lost its must-NOT-be-seen rows, so widening the pattern until the"
                    + " other half passes became free. A parser that matches too much is satisfied"
                    + " by prose, which is this ticket's other defect.")
                .isGreaterThanOrEqualTo(4);

        assertThat(ContainerJvm.JVM_DIRECTIVES)
                .as("The directive list collapsed; every row above would then be invisible and the"
                    + " must-be-seen half would red for the wrong reason.")
                .hasSizeGreaterThanOrEqualTo(5);
    }

    /**
     * <strong>Every reader of the {@code Dockerfile} uses the shared parser.</strong>
     *
     * <p>This is the mechanism the sharing javadoc was missing, and it is here because the
     * divergence it guards against is the one that actually happened <em>twice</em>: HD-321 wrote
     * reader 1, HD-343 wrote reader 2 from scratch beside it, and the base rate in this repository
     * for "a new reader reuses the existing derivation" was <strong>0 for 2</strong>. Sharing
     * prevents two readers that both <em>use</em> {@link ContainerJvm} from disagreeing; it does
     * nothing whatever about a third that does not, and nothing enumerated the readers.
     *
     * <p><strong>The check is deliberately weak, in both directions, and that is stated here rather
     * than left for a reader to assume it is exhaustive</strong> &mdash; the same stance
     * {@code ContainerHandoverClaimsTest} takes about its own scan ("what 'name' means here is
     * deliberately weak &hellip; this refuses an <em>absence</em>, which is the half a scan can
     * have"). It demands that a file naming the {@code Dockerfile} also name {@code ContainerJvm},
     * not that it <em>uses</em> it correctly. And the population is keyed on the quoted literal
     * {@code "Dockerfile"}, so a reader that spells the path another way &mdash;
     * {@code new File("./Dockerfile")}, a name built from parts, or a constant held in some other
     * class &mdash; escapes it entirely. What this buys is that the easy way to write a third parser
     * is refused; a determined one is not, and no scan of this shape could refuse it.
     *
     * <p>The floor is 2 against a population of exactly 2, which is deliberate and not an oversight:
     * a drop to one reader is itself worth hearing about, so there is nothing to gain from slack
     * here. The live count travels in the failure message, which computes it.
     */
    @Test
    void everyTestSourceThatReadsTheDockerfileGoesThroughTheSharedParser() {
        var readers = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(Path.of("src", "test", "java"))) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> {
                        if (read(path).contains("\"Dockerfile\"")) {
                            readers.add(path);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(readers)
                .as("""
                    No test source holds the literal "Dockerfile", so this rule found no population
                    and demanded nothing of anybody. The expected members are the CONSUMERS -
                    ContainerHandoverClaimsTest and JvmBoundsTest today. ContainerJvm itself is NOT
                    one and never will be: it takes the text as a parameter, which is the design, so
                    it does not hold that literal. Do not "repair" this by adding the literal to
                    ContainerJvm - go and find out why the consumers stopped holding it.""")
                .hasSizeGreaterThanOrEqualTo(2);

        var ownParser = readers.stream()
                .filter(path -> !path.getFileName().toString().equals("ContainerJvm.java"))
                .filter(path -> !read(path).contains("ContainerJvm"))
                .map(path -> "  " + path.toString().replace('\\', '/'))
                .toList();

        assertThat(ownParser)
                .as("""
                    A test source reads the Dockerfile without going through ContainerJvm, so this
                    repository has two parsers of one file again. That has already shipped twice: an
                    ENTRYPOINT-only scan missed `ENV JAVA_TOOL_OPTIONS=` (HD-321) and then a second
                    ENTRYPOINT-only scan was written beside the fix for it (HD-343). The Dockerfile
                    itself RECOMMENDS JAVA_TOOL_OPTIONS=-Xmx... to an operator raising the memory
                    limit, so a narrow reader does not merely miss a flag: it accuses the production
                    container of being unbounded and tells the reader to duplicate a flag already in
                    effect. Use ContainerJvm.logicalLines / configuresTheJvm /
                    jvmConfiguringDirectives; if the parse you need is genuinely different, add it
                    THERE so both readers gain it. Files: %d. With their own parser:%n%s"""
                        .formatted(readers.size(), String.join("\n", ownParser)))
                .isEmpty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file.toAbsolutePath(), e);
        }
    }
}
