package com.hamstrack.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <strong>Where the container's JVM can be configured from, and how to read the
 * {@code Dockerfile} to find out.</strong> One definition, because two of them disagreed.
 *
 * <h2>Why this is a shared class and not a constant in each reader</h2>
 * {@link ContainerHandoverClaimsTest} has derived the image's JVM flags from these five directives
 * since HD-321, and that list is the fix for a measured defect: the scan originally took only
 * {@code ENTRYPOINT}, so moving a flag into {@code ENV JAVA_TOOL_OPTIONS} dropped the population
 * from three members to two <em>while the floor stayed satisfied</em> &mdash; a derived population
 * answering "absent" and "not applicable" with the same silence.
 *
 * <p>HD-343 then wrote a <em>second</em> parser of the same file that read only
 * {@code ^\s*(ENTRYPOINT|CMD)}, so the repository briefly held two readers of one
 * {@code Dockerfile} that disagreed about where the container's heap flag may live. That is not a
 * hypothetical disagreement: {@code Dockerfile} lines 87 and 97-100 <strong>recommend
 * {@code JAVA_TOOL_OPTIONS=-Xmx…} to an operator raising the memory limit</strong>, so the first
 * person to follow the documented advice would have turned the narrower reader red and been told
 * the production container was unbounded when it was not, with a remedy instructing them to
 * duplicate a flag already in effect.
 *
 * <p>So the two readers now share this one parser rather than agreeing by inspection. If a sixth
 * place to configure the JVM appears, it is added <strong>here</strong> and both readers gain it in
 * the same change.
 *
 * <h2>What sharing covers, and what it does not &mdash; because the first version of this
 * paragraph claimed the wrong thing</h2>
 * It said sharing was preferable to a list-equality control "because a control has to be remembered
 * by whoever adds the third reader and a shared definition cannot disagree with itself". The second
 * clause is true and <strong>does not address how the divergence actually happened</strong>: both
 * readers of this file arrived by somebody writing a <em>fresh parser</em>. HD-321 wrote reader 1;
 * HD-343 wrote reader 2 from scratch beside it. The base rate in this repository for "a new reader
 * reuses the existing derivation" was <strong>0 for 2</strong>. Sharing stops two readers that both
 * <em>use</em> this class from disagreeing, and does nothing whatever about a third that does not
 * &mdash; which is precisely the case that occurred, twice.
 *
 * <p>So the claim is not left standing on its own:
 * {@link ContainerJvmTest#everyTestSourceThatReadsTheDockerfileGoesThroughTheSharedParser}
 * enumerates every test source that names the {@code Dockerfile} and demands it either be this
 * class or reference it, with a floor on the population. And
 * {@link ContainerJvmTest#readsAFlagInEveryFormADockerfileCanDeclareItAndInNoOtherForm} is the other
 * half, for the risk sharing <em>creates</em>: two parsers that disagree red one of themselves,
 * while one parser that is wrong is wrong in silence everywhere, so the shared thing needs a better
 * witness than the two it replaced &mdash; not the same one.
 *
 * <h2>Why this lives in {@code ops} although {@code common.testsupport} reads it</h2>
 * Deliberate, and not a layering accident: test support in this tree is a flat web rather than a
 * stack, and the direction {@code common.* -> ops} is the established one, not one HD-343 invented.
 * Measured: eight sources under {@code src/test/java/com/hamstrack/common/} already import
 * {@code com.hamstrack.ops.PublishedCredentials} &mdash; including
 * {@code common/testsupport/DoorsHarnessTest.java}, i.e. this very package &mdash; while
 * {@code ops/EnvTemplateGuardTest} and {@code ops/OpsWitnessContractTest} import from
 * {@code common} in return. The subject decides the home: this class is knowledge about the
 * container image, so it sits with the container's other rules. <strong>The hazard to avoid is not
 * the import direction but a future layering tidy-up that "fixes" it by copying the parser back
 * across the boundary</strong>, which is precisely how the divergence above was born;
 * {@link ContainerJvmTest#everyTestSourceThatReadsTheDockerfileGoesThroughTheSharedParser} reds on
 * that, wherever the copy lands.
 */
public final class ContainerJvm {

    private ContainerJvm() {
    }

    /**
     * The directives that put a flag into the running JVM. {@code ENTRYPOINT} and {@code CMD} own
     * their whole directive, so a prefix answers for them; the three {@code ENV} forms are named
     * with their variable because {@code ENV} carries everything else in the image too, and only
     * these three are read by the JVM itself at startup.
     */
    public static final List<String> JVM_DIRECTIVES = List.of(
            "ENTRYPOINT", "CMD",
            "ENV JAVA_TOOL_OPTIONS", "ENV JDK_JAVA_OPTIONS", "ENV _JAVA_OPTIONS");

    /** A single {@code -flag} token, captured up to any {@code =} so the member is the flag. */
    public static final Pattern JVM_FLAG = Pattern.compile("(-[^\\s\"',=]+)");

    /** Does this logical directive put flags into the running JVM? */
    public static boolean configuresTheJvm(String directive) {
        int end = 0;
        while (end < directive.length()
                && !Character.isWhitespace(directive.charAt(end))
                && directive.charAt(end) != '=') {
            end++;
        }
        // ONLY the instruction keyword is case-folded, and the rest of the directive is carried
        // through untouched. Both halves of that matter: Docker's KEYWORDS are case-insensitive, so
        // a lowercase `env` must be read; but environment VARIABLE names are case-sensitive, so
        // `java_tool_options` is not ours, and flag VALUES must keep their case or
        // `-XX:MaxRAMPercentage=` stops matching as itself. Folding the whole directive would break
        // the last two while fixing the first, and ContainerJvmTest has a row for each.
        String instruction = directive.substring(0, end).toUpperCase(Locale.ROOT);
        String rest = directive.substring(end);

        for (String declaration : JVM_DIRECTIVES) {
            int space = declaration.indexOf(' ');
            if (space < 0) {
                if (instruction.equals(declaration) && startsWithSeparator(rest)) {
                    return true;
                }
                continue;
            }
            if (instruction.equals(declaration.substring(0, space))
                    && startsWithSeparator(rest)
                    && assignsVariable(rest, declaration.substring(space + 1))) {
                return true;
            }
        }
        return false;
    }

    /** True when what follows the keyword actually separates it from its arguments. */
    private static boolean startsWithSeparator(String rest) {
        return !rest.isEmpty() && (Character.isWhitespace(rest.charAt(0)) || rest.charAt(0) == '=');
    }

    /**
     * Does {@code arguments} (an {@code ENV} directive with its keyword removed) assign
     * {@code variable}? <strong>Docker has two spellings and only one of them was read.</strong>
     *
     * <ul>
     *   <li>{@code VAR=value} &mdash; the modern form. The variable may be anywhere, because one
     *       {@code ENV} may set several, so this is matched after a start or a space. That is what
     *       keeps {@code JAVA_TOOL_OPTIONS_EXTRA=} from answering for
     *       {@code JAVA_TOOL_OPTIONS}.</li>
     *   <li>{@code VAR value} &mdash; the <strong>legacy space form, still accepted by Docker</strong>
     *       and <strong>invisible to this predicate until HD-343 round 4</strong>. Measured: with the
     *       heap flag written that way, {@link ContainerHandoverClaimsTest} passed with four green
     *       tests while the flag had left its population, because its floor stayed satisfied by the
     *       two surviving {@code -Duser.*} pins &mdash; the HD-321 defect in a new spelling, inside
     *       the class written to retire it.</li>
     * </ul>
     *
     * <p>The legacy form is anchored to the <strong>first</strong> token, which is where that syntax
     * puts the key: everything after it is the value. Without that anchor,
     * {@code ENV SOMETHING JAVA_TOOL_OPTIONS -Xmx512m} &mdash; our variable appearing as another
     * variable's <em>value</em> &mdash; would read as an assignment of ours. Both the form and that
     * precision are rows in {@link ContainerJvmTest}, the second one asserting invisibility, because
     * a pattern widened until the first half passes is how the other direction breaks.
     */
    private static boolean assignsVariable(String arguments, String variable) {
        var quoted = Pattern.quote(variable);
        return Pattern.compile("(?:^|\\s)" + quoted + "=|^\\s*" + quoted + "\\s")
                .matcher(arguments).find();
    }

    /**
     * The {@code Dockerfile} as logical directives: {@code #} comments dropped and continuation
     * lines joined, so a directive split over several lines is read as the one directive it is.
     */
    public static List<String> logicalLines(String text) {
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (line.startsWith("#") || (line.isEmpty() && current.isEmpty())) {
                continue;
            }
            if (line.endsWith("\\")) {
                current.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            out.add(current.append(line).toString().strip());
            current.setLength(0);
        }
        if (!current.isEmpty()) {
            out.add(current.toString().strip());
        }
        return out;
    }

    /** Every logical directive of {@code text} that configures the JVM, joined for scanning. */
    public static String jvmConfiguringDirectives(String text) {
        return logicalLines(text).stream().filter(ContainerJvm::configuresTheJvm)
                .reduce((a, b) -> a + " " + b).orElse("");
    }
}
