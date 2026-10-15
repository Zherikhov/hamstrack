package com.hamstrack.common.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <strong>The one source-text parser behind every seal that is sealed to an enclosing
 * METHOD</strong> (HD-298 regression; the two copies it replaces are named below).
 *
 * <p>Some guards cannot be a type invariant or a path binding: what makes a call site safe is a
 * property of the endpoint it sits in, and neither the callee nor an interceptor can see that.
 * {@code AuthMailDoorsTest} seals the call sites of the throttle door that answers 429 on an
 * otherwise-silent mail type; {@code AttachmentDoorsTest} seals that every {@code fileStorage.store}
 * is preceded in the same method by a quota reservation and a byte spend. Both read the tree
 * under {@code src/main/java} because a call site is a fact about code, not about bytecode a test
 * can enumerate, and both attribute a line to <em>the last preceding line that is a method
 * declaration</em>.
 *
 * <h2>Why this class exists</h2>
 * Each test used to carry its own copy of that parser, and the copies drifted: one learned that a
 * line indented DEEPER than method level can still match when the declaration pattern's type
 * class contains {@code \s} (a {@code return new ReservedAttachment(} twelve spaces in swallowed
 * the rest of {@code upload()}), fixed itself with a {@code (?=\S)} lookahead, and documented the
 * sibling as still deficient. The sibling then bit: a two-line call
 * {@code rejectUnencodablePassword(req.password(),} at eight spaces — no semicolon on the first
 * line, a real method name the keyword deny-list cannot refuse — parsed as a declaration, and the
 * seal reported the disclosing door as called from a method it is not called from. A guard whose
 * attribution is wrong is wrong in both directions: it can name an innocent method today and
 * attribute a moved call to {@code register} tomorrow. So there is one parser, and its attribution
 * is asserted directly in {@code SourceCallSitesTest} on the exact shapes that fooled the copies.
 *
 * <h2>What the parser is, and is not</h2>
 * Deliberately crude: it recognises the shape this codebase writes — a declaration at EXACTLY four
 * spaces of indentation, asserted with a lookahead — and nothing else. Lambda bodies attribute to
 * their enclosing method, which is what both seals want. A javadoc or line-comment mention is
 * never a call; anything else carrying the needle is one — over-reporting fails a seal, which is
 * the safe direction. The keyword deny-list stays as the belt for a method that legitimately sits
 * at four spaces inside a nested class, where {@code if (} would otherwise be a method named if.
 */
public final class SourceCallSites {

    /** What a line before the first declaration in a file is attributed to. */
    public static final String FILE_SCOPE = "<file scope>";

    /** The production tree — what {@link #callers} and {@link #callerBodies} read. */
    public static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /**
     * The test tree. Readable through {@link #unitsUnder(Path, Set)} because a seal over the SUITE
     * needs the same attribution a seal over production does, and the alternative was a fourth copy
     * of this parser (HD-334). The root is a parameter rather than a second constant pair of methods
     * so that there is one walk, one comment predicate and one declaration pattern for both trees;
     * a reader that wants a different root passes it.
     */
    public static final Path TEST_SOURCES = Path.of("src", "test", "java");

    /**
     * A line that declares a method to be a test — the one spelling of this question in the suite,
     * shared rather than copied because {@link #DECLARATION}'s four-space assumption is only safe
     * for a tree whose test methods sit at exactly that depth, and the arm that holds that
     * ({@code SourceCallSitesTest#everyTestAnnotationInTheSuiteSitsWhereTheParserCanAttributeIt})
     * must ask it with the same pattern as the consumer it protects.
     *
     * <p>Anchored at the start of the line, so {@code {@code @Test}} in prose is not a declaration
     * and {@code @TestConfiguration} is not a test ({@code \b} after {@code Test} refuses it).
     */
    public static final Pattern TEST_ANNOTATION =
            Pattern.compile("^\\s*@(Test|ParameterizedTest|RepeatedTest)\\b");

    // EXACTLY four spaces, asserted with a lookahead rather than left to the `^ {4}` prefix: the
    // type class below contains \s, so without it a deeper-indented line still matches with the
    // extra indentation absorbed. Indentation is what actually distinguishes a declaration from a
    // call in this codebase's style, so it is asserted directly.
    //
    // That assumption was sound for src/main/java, where this parser was built, and it travelled
    // to src/test/java with the root parameter (HD-334) — into the one tree where a @Nested class
    // can put a method eight spaces in. A @Test the parser cannot attribute is invisible to a
    // population built on it, in the FALSE-GREEN direction: the member is neither demanded to be
    // guarded nor flagged as a stale exemption. Held by the arm named on TEST_ANNOTATION above.
    static final Pattern DECLARATION = Pattern.compile(
            "^ {4}(?=\\S)(?:public|protected|private)?\\s*(?:static\\s+)?"
            + "(?:final\\s+)?[\\w.<>\\[\\],?\\s]+\\s(\\w+)\\s*\\([^;]*$");

    static final Set<String> NOT_A_METHOD_NAME = Set.of("if", "for", "while", "switch", "catch",
            "return", "new", "synchronized", "assert", "throw", "else", "do", "try");

    private SourceCallSites() {
    }

    /**
     * {@code Class.method} for every LINE under {@code src/main/java} that carries {@code needle},
     * in tree order — one entry per call line, so a method calling twice appears twice.
     *
     * @param excludedFiles simple file names to skip, e.g. the declaring class whose own javadoc
     *                      and guards mention the name
     */
    public static List<String> callers(String needle, String... excludedFiles) throws IOException {
        var excluded = Set.of(excludedFiles);
        var hits = new ArrayList<String>();
        for (var unit : compilationUnits(excluded)) {
            var enclosing = enclosingMethods(unit.lines());
            for (int i = 0; i < unit.lines().size(); i++) {
                var code = unit.lines().get(i).trim();
                if (isCode(code) && code.contains(needle)) {
                    hits.add(unit.type() + "." + enclosing.get(i));
                }
            }
        }
        return hits;
    }

    /**
     * {@code Class.method -> the code text of that method} for every method under
     * {@code src/main/java} whose body carries {@code needle}. Comment lines are omitted from the
     * body; the remaining lines are trimmed and joined with {@code '\n'}, so a caller can compare
     * the ORDER of two calls with {@code indexOf}.
     */
    public static Map<String, String> callerBodies(String needle, String... excludedFiles)
            throws IOException {
        var excluded = Set.of(excludedFiles);
        var hits = new LinkedHashMap<String, String>();
        for (var unit : compilationUnits(excluded)) {
            var bodies = bodiesByMethod(unit.type(), unit.lines());
            bodies.forEach((name, body) -> {
                if (body.contains(needle)) {
                    hits.put(name, body);
                }
            });
        }
        return hits;
    }

    // ------------------------------------------------------------------ the parser itself

    /**
     * The enclosing method of every line, index-aligned with {@code lines}: the captured name of
     * the last preceding line that {@link #DECLARATION} accepts, or {@link #FILE_SCOPE} before the
     * first. Package-private so the harness test can plant text instead of files.
     */
    static List<String> enclosingMethods(List<String> lines) {
        var result = new ArrayList<String>(lines.size());
        var enclosing = FILE_SCOPE;
        for (var line : lines) {
            var matcher = DECLARATION.matcher(line);
            if (matcher.find() && !line.trim().startsWith("*")
                && !NOT_A_METHOD_NAME.contains(matcher.group(1))) {
                enclosing = matcher.group(1);
            }
            result.add(enclosing);
        }
        return result;
    }

    /** {@code type.method -> trimmed code lines joined by '\n'}, comments dropped. */
    static Map<String, String> bodiesByMethod(String type, List<String> lines) {
        var enclosing = enclosingMethods(lines);
        var bodies = new LinkedHashMap<String, StringBuilder>();
        for (int i = 0; i < lines.size(); i++) {
            var code = lines.get(i).trim();
            if (!isCode(code)) {
                continue;
            }
            bodies.computeIfAbsent(type + "." + enclosing.get(i), k -> new StringBuilder())
                    .append(code).append('\n');
        }
        var out = new LinkedHashMap<String, String>();
        bodies.forEach((name, body) -> out.put(name, body.toString()));
        return out;
    }

    /**
     * A javadoc or line-comment line is not code. Anything else is.
     *
     * <p>A question about the WHOLE line, which is what this class's two seals want. The
     * positional question — "is the occurrence I just found commented out?" — is
     * {@link #isCommentedAt(String, int)}, and the two are deliberately not one method: a line that
     * begins in code can still carry a commented-out occurrence, and a predicate that answered the
     * line-level question for a positional caller is exactly the defect that one fixes.
     */
    static boolean isCode(String trimmedLine) {
        return !trimmedLine.startsWith("*") && !trimmedLine.startsWith("//");
    }

    /**
     * <strong>Is the occurrence at {@code at} inside a comment?</strong> — judged on this line
     * alone, by what precedes the occurrence rather than by what the line starts with (HD-326).
     *
     * <p>The predicate it replaces asked whether the LINE started with {@code *}, {@code //} or
     * {@code /*}, which is a different question with the same answer most of the time. Measured
     * consequences of the difference, both in a security-relevant walk: a {@code @Value} quoted in
     * prose entered the live binding mapping from a javadoc line (harmless only by luck — that one
     * happened to name the right variable; one naming a DIFFERENT property would have excused a
     * refusal that names no variable at all), and {@code foo(); /}{@code * @Value("${x}") *}{@code /}
     * still read as a binding because the line starts with code.
     *
     * <p>Three forms are recognised: a javadoc continuation ({@code *} before the occurrence, with
     * only whitespace in front of it), a line comment ({@code //} anywhere before it), and a block
     * comment opened before it and <em>not closed</em> before it — so
     * {@code /}{@code * note *}{@code / live(}&hellip; is code, which the old predicate got wrong in
     * the other direction. <strong>Not recognised:</strong> a block comment opened on an earlier
     * line (this class reads one line at a time on purpose — the input a pattern sees stays short),
     * and a {@code //} inside a string literal before the occurrence. Both over-report a comment,
     * which drops a member from a walk rather than inventing one; a caller for which that is the
     * unsafe direction needs a parser, not this.
     */
    public static boolean isCommentedAt(String line, int at) {
        var before = line.substring(0, Math.min(at, line.length()));
        if (before.strip().startsWith("*")) {
            return true;
        }
        if (before.contains("//")) {
            return true;
        }
        var opened = before.lastIndexOf("/*");
        return opened >= 0 && before.indexOf("*/", opened + 2) < 0;
    }

    /**
     * One {@code .java} file: its path, its simple type name and its lines.
     *
     * @param path  the path it was read from, so a consumer can report {@code file:line}
     * @param type  the simple name, i.e. the file name without {@code .java}
     * @param lines as read, untrimmed — {@link #DECLARATION}'s indentation lookahead needs them
     */
    public record CompilationUnit(Path path, String type, List<String> lines) {
    }

    /**
     * Every {@code .java} file under {@code root}, in tree order.
     *
     * <p><strong>Never silently empty.</strong> A missing root throws rather than returning no
     * units: every consumer of this walk asserts "nothing offends", so a walk that stopped finding
     * files is the greenest possible answer to a seal that has stopped working.
     *
     * @param root          {@link #MAIN_SOURCES}, {@link #TEST_SOURCES}, or a temp tree a harness
     *                      planted — the last is what lets a population seal be proven by a plant
     *                      without touching the repository (HD-328: a half-reverted plant is a tree
     *                      whose index and work tree disagree)
     * @param excludedFiles simple file names to skip
     */
    public static List<CompilationUnit> unitsUnder(Path root, Set<String> excludedFiles)
            throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("source root " + root.toAbsolutePath() + " is not a directory — "
                                  + "a scan over a root that does not exist reports clean for ever");
        }
        var units = new ArrayList<CompilationUnit>();
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (var path : files) {
            var file = path.getFileName().toString();
            if (excludedFiles.contains(file)) {
                continue;
            }
            var type = file.substring(0, file.length() - ".java".length());
            units.add(new CompilationUnit(path, type,
                    Files.readAllLines(path, StandardCharsets.UTF_8)));
        }
        return units;
    }

    private static List<CompilationUnit> compilationUnits(Set<String> excludedFiles)
            throws IOException {
        return unitsUnder(MAIN_SOURCES, excludedFiles);
    }
}
