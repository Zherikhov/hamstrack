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

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    // EXACTLY four spaces, asserted with a lookahead rather than left to the `^ {4}` prefix: the
    // type class below contains \s, so without it a deeper-indented line still matches with the
    // extra indentation absorbed. Indentation is what actually distinguishes a declaration from a
    // call in this codebase's style, so it is asserted directly.
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

    /** A javadoc or line-comment line is not code. Anything else is. */
    static boolean isCode(String trimmedLine) {
        return !trimmedLine.startsWith("*") && !trimmedLine.startsWith("//");
    }

    private record CompilationUnit(String type, List<String> lines) {
    }

    private static List<CompilationUnit> compilationUnits(Set<String> excludedFiles)
            throws IOException {
        var units = new ArrayList<CompilationUnit>();
        List<Path> files;
        try (Stream<Path> paths = Files.walk(MAIN_SOURCES)) {
            files = paths.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (var path : files) {
            var file = path.getFileName().toString();
            if (excludedFiles.contains(file)) {
                continue;
            }
            var type = file.substring(0, file.length() - ".java".length());
            units.add(new CompilationUnit(type, Files.readAllLines(path, StandardCharsets.UTF_8)));
        }
        return units;
    }
}
