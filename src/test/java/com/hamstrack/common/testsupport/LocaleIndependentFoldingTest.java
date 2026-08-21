package com.hamstrack.common.testsupport;

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
 * HD-120 — no production source may fold case through the JVM default locale.
 *
 * <p>{@code "x".toLowerCase()} is {@code "x".toLowerCase(Locale.getDefault())}, and the default is
 * whatever the container was started with. Under {@code tr-TR}, {@code 'I'} folds to a dotless
 * {@code 'ı'} and {@code 'i'} uppercases to a dotted {@code 'İ'} — so identical code over identical
 * data produces different results on different deployments, with no exception, no log line and
 * nothing for a reviewer on an {@code en} machine to notice. Azeri and Lithuanian are the same
 * family. That is a deployment-dependent defect, and this project has already shipped it three
 * times over: in the search term, in the folded address every outbound mail is sent to, and in two
 * slug functions that derive machine identifiers.
 *
 * <p><strong>Why a scan and not four unit tests.</strong> The defect is a property of the
 * <em>call</em>, not of any of the places that happen to contain one today: it arrives by someone
 * writing four idiomatic-looking characters in a file nobody thinks of as locale-sensitive. A test
 * per site can only ever pin the sites that already existed when it was written, so this one is
 * phrased about the category and fails on the next arrival instead. Its failure message is the
 * whole remedy — a new site is a deliberate decision, never an omission.
 *
 * <p><strong>Scope, stated so the boundary is not mistaken for a guarantee.</strong> This scans
 * production sources only, matches the argument-less overloads only, and reads text rather than
 * bytecode — so an explicit {@code toLowerCase(Locale.getDefault())}, a
 * {@code String.format} without a locale, or a locale-sensitive {@code Collator} passes it
 * untouched. Those are the same defect wearing different clothes; a fold with no argument is
 * simply the cheapest instance to catch mechanically.
 */
class LocaleIndependentFoldingTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** The argument-less overloads, and nothing else: {@code toLowerCase(Locale.ROOT)} is fine. */
    private static final Pattern DEFAULT_LOCALE_FOLD =
            Pattern.compile("\\.to(?:Lower|Upper)Case\\s*\\(\\s*\\)");

    @Test
    void noProductionSourceFoldsCaseThroughTheDefaultLocale() throws IOException {
        var files = javaSources();

        // A mis-rooted run must fail rather than pass over nothing: an empty scan would report
        // "clean" forever, which is the failure mode a guard like this exists to avoid.
        assertThat(files)
                .as("scanned %s — if this is empty the working directory is not the project root",
                        MAIN_SOURCES.toAbsolutePath())
                .hasSizeGreaterThan(100);

        var offenders = new ArrayList<String>();
        for (var file : files) {
            var source = Files.readString(file, StandardCharsets.UTF_8);
            var code = blankOutCommentsAndLiterals(source);
            var lines = code.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (DEFAULT_LOCALE_FOLD.matcher(lines[i]).find()) {
                    offenders.add(file + ":" + (i + 1));
                }
            }
        }

        assertThat(offenders)
                .as("""
                        Case folded through Locale.getDefault(). Pass the locale explicitly:

                          * comparing against anything this JVM did not fold in the same breath -- a
                            database LOWER(), a stored string, another node's answer -- or deriving a
                            key, slug, identifier or address: Locale.ROOT.
                          * text a human will read back in their own language: that human's locale,
                            named, never the container's.

                        Pass it even when the input looks provably ASCII. The argument costs nothing,
                        the proof stops holding the day the validation pattern is relaxed, and a
                        reader cannot tell a considered omission from an accidental one.

                        If a site here genuinely must follow the JVM default, write
                        toLowerCase(Locale.getDefault()) -- it says so out loud and this scan permits
                        it, which is the point of scanning for the silent form.""")
                .isEmpty();
    }

    private List<Path> javaSources() throws IOException {
        if (!Files.isDirectory(MAIN_SOURCES)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Replaces comment and string-literal content with spaces, preserving length and line breaks so
     * reported line numbers stay true. Literals are blanked as well as comments because a Javadoc
     * paragraph or an error message that merely mentions the call is prose, not a call — one such
     * mention already exists in {@code InsightsRequest}, and an allowlist entry for a sentence
     * would teach the next reader that allowlist entries are normal here.
     */
    private static String blankOutCommentsAndLiterals(String src) {
        var out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
                continue;
            }
            // Text block: opened by three quotes, closed by the next three.
            if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                out.append("   ");
                i += 3;
                while (i < n && !(src.charAt(i) == '"' && i + 2 < n
                        && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("   ");
                    i += 3;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n && src.charAt(i) != quote && src.charAt(i) != '\n') {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append("  ");
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
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
