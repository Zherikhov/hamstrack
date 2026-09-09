package com.hamstrack.common.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <strong>The vacuous-verification rules, written once (HD-295).</strong>
 *
 * <p>Three ways a guard passes while checking nothing have all shipped in this repository: a
 * bare {@code assert} that no IDE ever executes (no {@code -ea}; HD-164 converted 1258 of
 * them), a type-check that type-checks zero files ({@code tsc --noEmit} against a
 * solution-style {@code tsconfig.json}), and a skipped test that reads as a pass in a summary
 * line. {@code VacuousVerificationRulesTest} applies the first two of those over the whole
 * corpus and the third to {@code package.json}; the predicates live here, apart from it, for
 * two reasons: the positive control has to feed fixtures through <em>the same</em> code the
 * corpus scan uses (two copies of a rule diverge, and the copy nobody watches is the one that
 * matters), and a helper wearing a test-shaped name would be demanded as a class that must
 * execute by the HD-265 suite-coverage guard.
 *
 * <p><strong>The reference is looked up on the ORIGINAL line, the marker on the STRIPPED
 * one.</strong> Text preparation blanks comments and string literals, so a legitimate skip —
 * whose ticket lives in a reason string or in a trailing line comment — would lose its excuse
 * in exactly the same stroke that reveals its marker. Hence two reads of the same physical
 * line number: the marker from the code, the ticket from the text as written. Same line only,
 * no lookaround: it is the audit anyone actually runs by hand, and a reference parked on the
 * line above detaches on the first reorder without saying so.
 *
 * <p><strong>Conditional gates are deliberately outside every pattern here</strong>
 * ({@code @DisabledOnOs}, {@code @DisabledIf…}, {@code it.skipIf}, {@code it.runIf}): a
 * condition states its own reason, and the {@code \b} after each marker is what excludes them.
 * {@code .only} <em>is</em> inside R1 — it leaves every other test in the file unexecuted, and
 * vitest only refuses it when {@code CI} is set, which is the one environment where the damage
 * would have been noticed anyway.
 *
 * <p><strong>So are the runtime and data-driven forms, and that is a decision rather than an
 * oversight</strong> (HD-295 §2, re-recorded after the round-1 review asked for them): vitest's
 * {@code TestContext.skip()} called unconditionally in a body, {@code it.fails}, a
 * {@code { skip: true }} entry in an options object, and on the JUnit side
 * {@code Assumptions.assumeTrue(false)} / {@code Assumptions.abort(…)} / a thrown
 * {@code TestAbortedException}. Each of them skips at run time, where a text scan can see the
 * call but not whether it fires, so refusing them means refusing the conditional use as well —
 * the exact thing the paragraph above exists to permit. None is used anywhere in this
 * repository today (measured 2026-09-08); the rule's coverage claim is therefore about the
 * declared forms, and a body that starts skipping itself is a rule this file does not yet have.
 */
public final class VacuousVerification {

    private VacuousVerification() {
    }

    /** Which stripper and which marker set a file gets — its extension decides, never its path. */
    public enum Language {
        JAVA, TYPESCRIPT
    }

    /** One offence, in the {@code path:line — what} form the failure messages print. */
    public record Offence(String path, int line, String what) {

        public String describe() {
            return path + ":" + line + " — " + what;
        }
    }

    /**
     * The JUnit skip marker, and not the conditional family — the trailing word boundary is
     * the whole of that exclusion. The package prefix is optional because nothing obliges a
     * file to import the annotation, and a marker that only counts when somebody wrote the
     * import is a marker with an opt-out. {@code @DisabledOnOs} still fails the boundary,
     * qualified or not.
     */
    private static final Pattern JAVA_SKIP =
            Pattern.compile("@(?:org\\.junit\\.jupiter\\.api\\.)?Disabled\\b");

    /**
     * The frontend skip family, in every spelling the runner answers to: {@code suite} is an
     * exported alias of {@code describe} (vitest 3.2.7), and both chainables accept run-order
     * modifiers ahead of the terminal word, so {@code it.concurrent.skip} is the same skip as
     * {@code it.skip}. The {@code x}-prefixed names are Jest-style aliases rather than vitest —
     * vitest defines none of them, and under {@code globals: true} they raise a
     * {@code ReferenceError} — but they are refused anyway, so a file pasted in from a Jest
     * project cannot read as a pass on the way to being ported. The conditional forms fail the
     * trailing boundary and are not markers; {@code .only} is one.
     */
    private static final Pattern FRONTEND_SKIP = Pattern.compile(
            "\\b(?:x(?:it|test|describe)"
                    + "|(?:it|test|describe|suite)"
                    + "(?:\\.(?:concurrent|sequential|shuffle))*\\.(?:skip|only|todo))\\b");

    /**
     * {@code assert} is a reserved word, so any survivor of the stripping is the statement:
     * the AssertJ and JUnit call names fail the trailing boundary, and a comment or a literal
     * that merely says the word has already been blanked.
     */
    private static final Pattern BARE_ASSERT = Pattern.compile("\\bassert\\b");

    /** Case and hyphen are the format: a lowercase or unhyphenated ticket is not a reference. */
    private static final Pattern TICKET = Pattern.compile("\\bHD-\\d+\\b");

    /** Offences listed in full before the message truncates; the rest are counted. */
    private static final int MAX_LISTED = 15;

    /** R1 — every skip marker in {@code source} whose physical line carries no ticket. */
    public static List<Offence> skipsWithoutTicket(String path, String source, Language language) {
        Pattern marker = language == Language.JAVA ? JAVA_SKIP : FRONTEND_SKIP;
        var found = new ArrayList<Offence>();
        String[] original = source.split("\n", -1);
        String[] stripped = strip(source, language).split("\n", -1);
        for (int i = 0; i < stripped.length; i++) {
            var match = marker.matcher(stripped[i]);
            if (!match.find()) {
                continue;
            }
            if (i < original.length && TICKET.matcher(original[i]).find()) {
                continue;
            }
            found.add(new Offence(path, i + 1,
                    "`" + match.group() + "` with no ticket on this line"));
        }
        return found;
    }

    /** R2 — every bare {@code assert} statement in {@code source}. */
    public static List<Offence> bareAsserts(String path, String source) {
        var found = new ArrayList<Offence>();
        String[] stripped = strip(source, Language.JAVA).split("\n", -1);
        for (int i = 0; i < stripped.length; i++) {
            if (BARE_ASSERT.matcher(stripped[i]).find()) {
                found.add(new Offence(path, i + 1, "bare `assert` statement"));
            }
        }
        return found;
    }

    /**
     * The failure message: a headline, one {@code path:line — what} per offence, then the
     * remedy. Capped so the whole thing stays inside the 25-line budget however many offences
     * a single edit produced.
     */
    public static String report(String headline, List<Offence> offences, String remedy) {
        var out = new StringBuilder(headline).append("\n\n");
        offences.stream().limit(MAX_LISTED)
                .forEach(o -> out.append("  ").append(o.describe()).append('\n'));
        if (offences.size() > MAX_LISTED) {
            out.append("  … and ").append(offences.size() - MAX_LISTED).append(" more\n");
        }
        return out.append('\n').append(remedy).toString();
    }

    /**
     * Comment and string-literal content replaced by spaces, length and line breaks preserved
     * so the line numbers reported are the line numbers in the file. Literals go because the
     * scanner's own patterns, this javadoc, and the one mention in
     * {@code ExecutedTestClassRecorder}'s javadoc would otherwise each be an offence — and an
     * allowlist entry for a sentence teaches the next reader that allowlist entries are normal
     * here. It is also why the positive-control fixtures live in files rather than in literals.
     */
    public static String strip(String source, Language language) {
        return language == Language.JAVA ? stripJava(source) : stripTypeScript(source);
    }

    /**
     * The HD-120 stripper shape — comments, text blocks, strings, chars — now living only here:
     * the locale-fold scan it was written for moved to bytecode in HD-297 ({@code ArchitectureRulesTest}).
     */
    private static String stripJava(String src) {
        var out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                i = blankToEndOfLine(src, i, out);
                continue;
            }
            if (c == '/' && next == '*') {
                i = blankBlockComment(src, i, out);
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                i = blankUntil(src, i + 3, "\"\"\"", out, 3);
                continue;
            }
            if (c == '"' || c == '\'') {
                i = blankQuoted(src, i, c, out);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The same shape for TypeScript, plus template literals — which are the only literal here
     * that spans lines, and the reason this is not the Java stripper with a different name.
     * A marker inside an interpolation is blanked with the rest of the template; accepted,
     * because a real marker is a call at statement position (§11 of the proposal).
     */
    private static String stripTypeScript(String src) {
        var out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                i = blankToEndOfLine(src, i, out);
                continue;
            }
            if (c == '/' && next == '*') {
                i = blankBlockComment(src, i, out);
                continue;
            }
            if (c == '`') {
                i = blankUntil(src, i + 1, "`", out, 1);
                continue;
            }
            if (c == '"' || c == '\'') {
                i = blankQuoted(src, i, c, out);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int blankToEndOfLine(String src, int i, StringBuilder out) {
        while (i < src.length() && src.charAt(i) != '\n') {
            out.append(' ');
            i++;
        }
        return i;
    }

    private static int blankBlockComment(String src, int i, StringBuilder out) {
        return blankUntil(src, i + 2, "*/", out, 2);
    }

    /**
     * Blanks from {@code i} up to and including the next {@code closer}, writing
     * {@code openerWidth} spaces for the opener the caller already consumed. Newlines survive;
     * an unterminated opener blanks to the end of the file, which is what an unterminated
     * literal deserves.
     */
    private static int blankUntil(String src, int i, String closer, StringBuilder out, int openerWidth) {
        out.append(" ".repeat(openerWidth));
        int n = src.length();
        while (i < n && !src.startsWith(closer, i)) {
            if (src.charAt(i) == '\\' && i + 1 < n) {
                out.append(src.charAt(i + 1) == '\n' ? " \n" : "  ");
                i += 2;
                continue;
            }
            out.append(src.charAt(i) == '\n' ? '\n' : ' ');
            i++;
        }
        if (i < n) {
            out.append(" ".repeat(closer.length()));
            i += closer.length();
        }
        return i;
    }

    /**
     * A single- or double-quoted literal. It crosses a line break in exactly one way — a
     * JavaScript line continuation, {@code \} as the last character of the line — and the
     * escape pair must then emit {@code " \n"} rather than two spaces, exactly as
     * {@link #blankUntil} does. Emitting two spaces swallows the newline, the stripped text
     * loses a line from that point on, and every offence below it is reported against the line
     * above the one it means while its ticket is looked up on a line that is not its own: the
     * skip after a ticketed skip goes unreported. Only ever visible on an LF checkout, which is
     * why the fixtures are pinned to LF in {@code .gitattributes}.
     */
    private static int blankQuoted(String src, int i, char quote, StringBuilder out) {
        int n = src.length();
        out.append(' ');
        i++;
        while (i < n && src.charAt(i) != quote && src.charAt(i) != '\n') {
            if (src.charAt(i) == '\\' && i + 1 < n) {
                out.append(src.charAt(i + 1) == '\n' ? " \n" : "  ");
                i += 2;
                continue;
            }
            out.append(' ');
            i++;
        }
        if (i < n && src.charAt(i) == quote) {
            out.append(' ');
            i++;
        }
        return i;
    }
}
