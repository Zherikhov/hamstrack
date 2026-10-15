package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>The attribution of {@link SourceCallSites} on the exact shapes that fooled its two
 * predecessors</strong> — a seal is only as good as the parser under it, and both copies this
 * class replaced were wrong in a way no consumer test could see until a line happened to be
 * rewrapped.
 *
 * <p>Each planted snippet is written the way this codebase writes it (four-space method level,
 * eight-space body, twelve-space nested), because the parser recognises that shape and nothing
 * else; a test written in a different style would pass for the wrong reason.
 */
class SourceCallSitesTest {

    /**
     * The floor on {@code everyTestAnnotationInTheSuiteSitsWhereTheParserCanAttributeIt}: the test
     * methods the suite declares. Measured 2026-10-14 by
     * {@code grep -rhE "^\s*@(Test|ParameterizedTest|RepeatedTest)\b" src/test/java | wc -l} —
     * 2038, every one of them at exactly four spaces.
     */
    private static final int TEST_ANNOTATION_FLOOR = 2000;

    /**
     * The HD-298 regression: a call rewrapped over two lines has no semicolon on its first line
     * and a real method name the keyword deny-list cannot refuse. Without the indentation
     * lookahead it parsed as a declaration and everything below it — including the sealed door —
     * was attributed to {@code rejectUnencodablePassword}, a method the door is never called from.
     */
    @Test
    void aTwoLineCallInABodyIsNotADeclaration() {
        var lines = List.of(
                "    public void register(RegisterRequest req) {",
                "        rejectPublishedPassword(req.password(),",
                "                () -> metrics.signupRefused(SignupRefusal.PUBLISHED_PASSWORD));",
                "        rejectUnencodablePassword(req.password(),",
                "                () -> metrics.signupRefused(SignupRefusal.UNENCODABLE_PASSWORD));",
                "        mailThrottle.requireAndRecordWhereEndpointDiscloses(",
                "                EmailType.REGISTRATION_VERIFICATION, email);",
                "    }");

        assertThat(SourceCallSites.enclosingMethods(lines))
                .as("every body line of register, including the rewrapped calls, belongs to register")
                .containsOnly("register");
    }

    /**
     * The deficiency {@code AttachmentDoorsTest} documented in its own copy: a {@code return new
     * Type(} twelve spaces in captures a real type name, which the deny-list cannot refuse either.
     */
    @Test
    void aNestedConstructorCallIsNotADeclaration() {
        var lines = List.of(
                "    private ReservedAttachment upload(MultipartFile file) {",
                "        return txTemplate.execute(status -> {",
                "            workspaceStorage.reserve(ws, file.getSize());",
                "            return new",
                "            ReservedAttachment(",
                "                    file.getSize());",
                "        });",
                "        fileStorage.store(key, file);",
                "    }");

        assertThat(SourceCallSites.enclosingMethods(lines)).containsOnly("upload");
    }

    /** A control statement in a body was the original false positive; the deny-list still holds it. */
    @Test
    void aControlStatementIsNotADeclaration() {
        var lines = List.of(
                "    public void register(RegisterRequest req) {",
                "        if (userRepository.existsByFoldedEmail(email)) {",
                "            throw new EmailAlreadyExistsException();",
                "        }",
                "    }");

        assertThat(SourceCallSites.enclosingMethods(lines)).containsOnly("register");
    }

    /** And the thing it must still see: a real declaration re-attributes what follows it. */
    @Test
    void aRealDeclarationAtMethodLevelReattributes() {
        var lines = List.of(
                "    public void register(RegisterRequest req) {",
                "        mailThrottle.requireAndRecordWhereEndpointDiscloses(type, email);",
                "    }",
                "",
                "    /**",
                "     * Javadoc that mentions requireAndRecordWhereEndpointDiscloses( is not a call.",
                "     */",
                "    @Transactional",
                "    public void forgotPassword(ForgotPasswordRequest req) {",
                "        mailThrottle.requireAndRecordWhereEndpointDiscloses(type, email);",
                "    }",
                "",
                "    private static final Runnable NOT_A_SIGNUP = () -> { };",
                "",
                "    private void rejectUnencodablePassword(String password, Runnable refused) {",
                "        refused.run();",
                "    }");

        var enclosing = SourceCallSites.enclosingMethods(lines);

        assertThat(enclosing.get(1)).isEqualTo("register");
        assertThat(enclosing.get(9)).isEqualTo("forgotPassword");
        assertThat(enclosing.get(12))
                .as("a field with an initialiser is neither a declaration nor a re-attribution")
                .isEqualTo("forgotPassword");
        assertThat(enclosing.get(15)).isEqualTo("rejectUnencodablePassword");

        var bodies = SourceCallSites.bodiesByMethod("AuthService", lines);
        assertThat(bodies.keySet()).containsExactly("AuthService.register",
                "AuthService.forgotPassword", "AuthService.rejectUnencodablePassword");
        assertThat(bodies.get("AuthService.forgotPassword"))
                .as("the javadoc mention above forgotPassword is dropped from the body")
                .doesNotContain("Javadoc");
        assertThat(bodies.get("AuthService.forgotPassword"))
                .as("the field line between two methods is attributed to the one above it, which is "
                    + "harmless — it carries no call — but the attribution must be stable")
                .contains("NOT_A_SIGNUP");
    }

    /**
     * The two public shapes agree with each other on the real tree: every method named by
     * {@code callerBodies} is named by {@code callers}, and vice versa. Which methods those are
     * is the seals' business ({@code AuthMailDoorsTest}, {@code AttachmentDoorsTest}), not this
     * test's — a second copy of a seal is the drift this class replaced.
     */
    @Test
    void bothShapesNameTheSameMethodsOnTheRealTree() throws IOException {
        var needle = "requireAndRecordWhereEndpointDiscloses(";
        var perLine = SourceCallSites.callers(needle, "RecipientMailThrottle.java");
        var perMethod = SourceCallSites.callerBodies(needle, "RecipientMailThrottle.java");

        assertThat(perLine).as("the parser found nothing to attribute on the real tree").isNotEmpty();
        assertThat(perMethod.keySet()).containsExactlyInAnyOrderElementsOf(
                perLine.stream().distinct().toList());
        assertThat(perLine).noneMatch(caller -> caller.endsWith("." + SourceCallSites.FILE_SCOPE));
    }

    /**
     * {@link SourceCallSites#isCommentedAt(String, int)} on the four shapes it exists for: the
     * javadoc continuation and the trailing block comment that a line-leading predicate got wrong
     * in opposite directions, plus the two it must NOT call a comment — a closed block comment
     * before live code, and an annotation at the head of its own line (HD-326).
     */
    @Test
    void aCommentIsJudgedWhereTheOccurrenceIsAndNotWhereTheLineStarts() {
        var javadoc = "     * {@code @Value(\"${seed.admin.email:}\")}, which this replaced";
        var trailing = "        foo(); /* @Value(\"${planted.x}\") */";
        var reopened = "        /* note */ bar(\"@Value(\");";
        var live = "    @Value(\"${jwt.secret}\")";

        assertThat(SourceCallSites.isCommentedAt(javadoc, javadoc.indexOf("@Value(")))
                .as("a javadoc continuation quoting the shape is prose, not a binding").isTrue();
        assertThat(SourceCallSites.isCommentedAt(trailing, trailing.indexOf("@Value(")))
                .as("a line that STARTS with code can still carry a commented-out occurrence").isTrue();
        assertThat(SourceCallSites.isCommentedAt(reopened, reopened.indexOf("@Value(")))
                .as("a block comment CLOSED before the occurrence leaves it in code").isFalse();
        assertThat(SourceCallSites.isCommentedAt(live, live.indexOf("@Value(")))
                .as("the real annotation must stay visible, or every binding leaves the mapping").isFalse();
    }

    /**
     * <strong>The walk takes a root, and the root is what it says it is</strong> (HD-334).
     *
     * <p>The parser was hard-coded to {@code src/main/java} and a seal over the SUITE needed the
     * same attribution, so the walk was generalised rather than copied — and a shared derivation
     * trades a loud failure (two parsers disagreeing reds one of them) for a silent one (one parser
     * blind everywhere), so it gets floors in <strong>both</strong> directions: the forms it must
     * see and the forms it must not.
     */
    @Test
    void theWalkSeesExactlyTheRootItIsGivenAndNothingElse() throws IOException {
        var main = SourceCallSites.unitsUnder(SourceCallSites.MAIN_SOURCES, Set.of());
        var test = SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES, Set.of());

        assertThat(main).as("the production tree is not small; a short walk is a broken walk")
                .hasSizeGreaterThan(250);
        assertThat(test).as("nor is the suite — and the SUITE root is the one this generalisation "
                            + "exists for, so a floor on it is not decoration")
                .hasSizeGreaterThan(250);

        assertThat(main).allSatisfy(unit -> assertThat(unit.path().toString())
                .as("a unit from the production walk must come from the production tree")
                .contains("main"));
        assertThat(test).allSatisfy(unit -> assertThat(unit.path().toString())
                .as("and one from the suite walk from the suite tree — the two roots must not "
                    + "bleed, or a seal over the suite silently asserts about production")
                .contains("test"));
        assertThat(main.stream().map(SourceCallSites.CompilationUnit::type).toList())
                .as("the production walk must not contain this very test, which is the simplest "
                    + "way for a root parameter to be ignored")
                .doesNotContain("SourceCallSitesTest");
        assertThat(test.stream().map(SourceCallSites.CompilationUnit::type).toList())
                .contains("SourceCallSitesTest", "FixedWindowCoverageTest");
        assertThat(test).allSatisfy(unit -> assertThat(unit.type())
                .as("the type is the simple name, whatever the depth of the package")
                .doesNotContain(".java"));
    }

    /** An excluded simple name is skipped, on whichever root it is asked about. */
    @Test
    void anExcludedFileNameIsSkippedOnEitherRoot() throws IOException {
        assertThat(SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES,
                        Set.of("SourceCallSitesTest.java")).stream()
                .map(SourceCallSites.CompilationUnit::type).toList())
                .doesNotContain("SourceCallSitesTest")
                .contains("SourceCallSites");
    }

    /**
     * <strong>Every {@code @Test} in the suite sits where {@link SourceCallSites#DECLARATION} can
     * attribute it.</strong>
     *
     * <p>The parser recognises a declaration at <em>exactly</em> four spaces, by deliberate
     * comment. That assumption was built against {@code src/main/java} and came with the root
     * parameter into {@code src/test/java} (HD-334) — the one tree where a {@code @Nested} class
     * puts a method eight spaces in. Measured 2026-10-14: a planted {@code @Nested} class holding
     * a {@code @Test} that asserts {@code isTooManyRequests()} left
     * {@code FixedWindowCoverageTest}'s population at 63, unchanged, and the build green. The
     * member was neither demanded to be guarded nor reported as a stale exemption, which is the
     * false-green direction and the only one that matters here.
     *
     * <p>Asked of the annotation rather than of the declaration on purpose: in this codebase's
     * style an annotation line carries the indentation of the method it precedes, so one
     * comparison catches <em>both</em> causes — a nested class, and a file written at a different
     * depth — where a count of attributed methods against a count of annotations misses a nested
     * {@code @Test} whose misattribution happens to land on a name nothing else annotates.
     *
     * <p>Latent today and fixed anyway: {@code @Nested} appears once in the whole suite, in
     * {@code ExecutedTestClassRecorder}'s javadoc, as prose about the tree the coverage guard
     * scans. A guard installed only once a defect is live is the instance, not the category.
     */
    @Test
    void everyTestAnnotationInTheSuiteSitsWhereTheParserCanAttributeIt() throws IOException {
        var misplaced = new ArrayList<String>();
        int annotations = 0;

        for (var unit : SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES, Set.of())) {
            var lines = unit.lines();
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                if (!SourceCallSites.TEST_ANNOTATION.matcher(line).find()) {
                    continue;
                }
                annotations++;
                if (!line.startsWith("    @")) {
                    misplaced.add(unit.path() + ":" + (i + 1) + "  " + line.strip()
                                  + "  (indented " + indentOf(line) + ", not 4)");
                }
            }
        }

        assertThat(annotations)
                .as("the suite declares %d test methods, under the floor of %d — this arm is over "
                    + "the WHOLE suite, so a smaller number is a broken walk and not a smaller "
                    + "suite (measured 2026-10-14: 2038)", annotations, TEST_ANNOTATION_FLOOR)
                .isGreaterThanOrEqualTo(TEST_ANNOTATION_FLOOR);
        assertThat(misplaced)
                .as("""
                    A TEST METHOD THE PARSER CANNOT ATTRIBUTE IS INVISIBLE TO EVERY POPULATION \
                    BUILT ON IT, IN THE FALSE-GREEN DIRECTION.

                    SourceCallSites.DECLARATION accepts a declaration at exactly four spaces, so \
                    the line(s) above are attributed to whatever unrelated method precedes them - \
                    they are neither demanded to be guarded nor reported as a stale exemption. \
                    There are two causes and two actions:

                      1. A @Nested inner class. Flatten it, or teach DECLARATION the deeper depth \
                         here, in this one parser, and extend this arm to the depths it accepts.
                      2. A file written at some other indentation. Reformat it to the suite's \
                         four-space method level.

                    Do not lower the floor above to make a run pass.""")
                .isEmpty();
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /**
     * And a root that does not exist <strong>throws</strong>. Every consumer of this walk asserts
     * "nothing offends", so a walk that quietly found no files is the greenest possible answer to a
     * seal that has stopped working — the one failure mode a scan must never have.
     */
    @Test
    void aRootThatDoesNotExistIsRefusedRatherThanReportedEmpty() {
        assertThatThrownBy(() -> SourceCallSites.unitsUnder(Path.of("src", "nope", "java"),
                        Set.of()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("reports clean for ever");
    }
}
