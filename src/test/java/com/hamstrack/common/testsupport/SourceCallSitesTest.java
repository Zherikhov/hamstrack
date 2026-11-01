package com.hamstrack.common.testsupport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
     *
     * <p><strong>A {@code @Test} inside a text block is not a test method</strong>, and this arm
     * read one as a misplaced one until {@link #literalPositions} went in — see there for the
     * before/after count. Planting Java source in a text block is this package's idiom for proving
     * a scanner, so the scanner's own seals have to respect the same literal mask the scanner does.
     */
    @Test
    void everyTestAnnotationInTheSuiteSitsWhereTheParserCanAttributeIt() throws IOException {
        var misplaced = new ArrayList<String>();
        int annotations = 0;

        for (var unit : SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES, Set.of())) {
            var lines = unit.lines();
            var quoted = literalPositions(lines);
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                if (!SourceCallSites.TEST_ANNOTATION.matcher(line).find()
                    || quoted.contains(positionKey(i + 1, line.indexOf('@') + 1))) {
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

    /**
     * Every {@code (line, column)} this file holds <strong>inside a literal</strong>, taken from
     * the one literal mask in this package rather than recomputed.
     *
     * <p>A scan that reads source expecting <em>declarations</em> must blank its literals first,
     * exactly as a scan that reads a build file expecting elements must blank its comments first
     * (HD-343). This arm did not, and the suite plants Java source in text blocks in five places:
     * a planted {@code @Test} was reported as a misplaced test method — a false red on an innocent
     * file, which is how a seal gets switched off — and also inflated the floor's own population.
     * Measured 2026-10-15: the annotation count falls from 2073 to 2069 with this mask in place,
     * and the four it drops are the plants in {@code FixtureRowsTest}.
     */
    private static Set<Long> literalPositions(List<String> lines) {
        var quoted = new HashSet<Long>();
        for (var statement : SourceCallSites.statementsOf(lines)) {
            for (var i = 0; i < statement.text().length(); i++) {
                if (statement.literal()[i]) {
                    quoted.add(positionKey(statement.lineOf(i), statement.columnOf(i)));
                }
            }
        }
        return quoted;
    }

    private static long positionKey(int line, int column) {
        return line * 100_000L + column;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    // ------------------------------------------------- statements and chains (HD-329)

    /**
     * <strong>What a statement is</strong> — the forms the splitter must see and the forms it must
     * not, in one table, because {@code statementsOf} is now shared by a seal over the whole suite
     * and a shared derivation trades a loud failure for a silent one.
     */
    @Test
    void aStatementIsCodeOnlyAndEndsWhereTheSemicolonAtDepthZeroIs() {
        var lines = List.of(
                "    private void fixture() {",
                "        // a line comment is not code",
                "        /**",
                "         * nor is javadoc; it mentions System.nanoTime() in prose",
                "         */",
                "        var name = \"WF-\" + token(); // and nor is this tail",
                "        mockMvc.perform(post(\"/x\")",
                "                        .content(\"{\\\"a\\\":1,\\\"b\\\":2}\"))",
                "                .andExpect(status().isCreated());",
                "        run(() -> { first(); second(); });",
                "    }");

        var statements = SourceCallSites.statementsOf(lines).stream()
                .map(s -> s.text().strip()).toList();

        assertThat(statements).as("the declaration, three statements and the closing brace")
                .hasSize(5);
        assertThat(statements.get(1)).isEqualTo("var name = \"WF-\" + token();");
        assertThat(statements.get(1)).as("a trailing line comment is not part of the statement")
                .doesNotContain("and nor is this tail");
        assertThat(statements).as("javadoc prose can mention a call shape without becoming one")
                .noneMatch(text -> text.contains("nanoTime"));
        assertThat(statements.get(2))
                .as("a whole MockMvc chain is ONE statement: the commas inside its JSON body are "
                    + "inside a literal and the semicolon is the only one at depth zero")
                .startsWith("mockMvc.perform(").endsWith("isCreated());");
        assertThat(statements.get(3))
                .as("a lambda body's semicolons are at paren depth 1 and do not split it")
                .isEqualTo("run(() -> { first(); second(); });");
    }

    /** A text block's content is masked, so its semicolons and quotes are not syntax. */
    @Test
    void aTextBlockIsOneLiteralHoweverManyLinesItSpans() {
        var lines = List.of(
                "        assertThat(offenders)",
                "                .as(\"\"\"",
                "                    A SENTENCE; WITH A SEMICOLON AND A \"QUOTE\".",
                "                    \"\"\")",
                "                .isEmpty();");

        var statements = SourceCallSites.statementsOf(lines);

        assertThat(statements).as("the semicolon inside the text block must not split it").hasSize(1);
        var statement = statements.get(0);
        assertThat(statement.text()).contains("A SENTENCE; WITH A SEMICOLON");
        assertThat(statement.literal()[statement.text().indexOf("A SENTENCE")])
                .as("and its characters are literal, so a needle quoted there is not a call")
                .isTrue();
    }

    /**
     * <strong>The literal mask the attribution arm now reads, in both directions</strong> — the
     * quoted {@code @Test} it must skip and the real misplaced one it must still name. A mask that
     * over-reported would blind that arm everywhere instead of in one file, which is the price of
     * consolidating a scan: one blind parser is quieter than two disagreeing ones.
     */
    @Test
    void aQuotedTestAnnotationIsMaskedAndARealMisplacedOneIsNot() {
        var lines = List.of(
                "    @Test",
                "    void plantsSource() {",
                "        plant(root, \"Planted.java\", \"\"\"",
                "                class Planted {",
                "                    @Test",
                "                    void looksLikeATest() {",
                "                    }",
                "                }",
                "                \"\"\");",
                "    }",
                "        @Test",
                "        void reallyTooDeep() {");

        var quoted = literalPositions(lines);

        assertThat(SourceCallSites.TEST_ANNOTATION.matcher(lines.get(4)).find())
                .as("the planted annotation does look like one to the pattern — the mask is the "
                    + "only thing that tells them apart")
                .isTrue();
        assertThat(quoted.contains(positionKey(5, lines.get(4).indexOf('@') + 1)))
                .as("the @Test inside the text block is masked, so the arm skips it")
                .isTrue();
        assertThat(quoted.contains(positionKey(1, lines.get(0).indexOf('@') + 1)))
                .as("the real one at four spaces is not masked")
                .isFalse();
        assertThat(quoted.contains(positionKey(11, lines.get(10).indexOf('@') + 1)))
                .as("and nor is the real MISPLACED one at eight — masking it would retire the arm "
                    + "silently, which is the failure this control exists for")
                .isFalse();
    }

    /**
     * <strong>The chain is the expression, not the statement</strong> — the table of shapes the
     * HD-329 seal depends on. Each row names what would go wrong without it: a chain that stopped
     * at a grouping parenthesis would miss the UUID that carries a slug, and a chain that crossed
     * an argument comma would find a UUID in a neighbouring argument and excuse a clock-named row.
     */
    @Test
    void aChainExpandsThroughGroupingParensAndStopsAtArgumentsAndAssignments() {
        var chains = chainsAround("System.nanoTime()", List.of(
                "        w.setSlug(\"ws-\" + UUID.randomUUID().toString().substring(0, 8)",
                "                  + \"-\" + (System.nanoTime() % 100000));",
                "        u.setEmail((\"u-\" + System.nanoTime() + \"@example.com\").toLowerCase());",
                "        plant(ctx, UUID.randomUUID(), \"WF-\" + System.nanoTime(), null);",
                "        var startedAt = System.nanoTime();",
                "        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;"));

        assertThat(chains.get(0))
                .as("expanded out of the parenthesised modulus, so the UUID that actually carries "
                    + "this slug is IN the chain")
                .isEqualTo("\"ws-\" + UUID.randomUUID().toString().substring(0, 8) + \"-\" "
                           + "+ (System.nanoTime() % 100000)");
        assertThat(chains.get(1))
                .as("expanded out of the grouping parens of a lower-cased e-mail, and stopped at "
                    + "the invocation paren of setEmail")
                .isEqualTo("(\"u-\" + System.nanoTime() + \"@example.com\").toLowerCase()");
        assertThat(chains.get(2))
                .as("BOUNDED BY THE ARGUMENT COMMAS: the UUID in the previous argument must not "
                    + "appear in this chain, or a clock-named row is excused by its neighbour")
                .isEqualTo("\"WF-\" + System.nanoTime()");
        assertThat(chains.get(3)).as("an assignment bounds a chain on the left")
                .isEqualTo("System.nanoTime()");
        assertThat(chains.get(4))
                .as("and a difference comes back whole, so a consumer can recognise an elapsed "
                    + "measure rather than half of one")
                .isEqualTo("(System.nanoTime() - startedAt) / 1_000_000");
    }

    /** The positional key the HD-329 population is built on: line AND column, both 1-based. */
    @Test
    void aMentionCarriesTheLineAndColumnItWasFoundAt() {
        var unit = new SourceCallSites.CompilationUnit(Path.of("Planted.java"), "Planted", List.of(
                "    private void twoOnOneLine() {",
                "        plant(UUID.randomUUID(), UUID.randomUUID());",
                "    }"));

        var mentions = SourceCallSites.mentions(unit, List.of("UUID.randomUUID()"));

        assertThat(mentions).as("two occurrences on one line are two mentions").hasSize(2);
        assertThat(mentions.stream().map(SourceCallSites.Mention::line)).containsExactly(2, 2);
        assertThat(mentions.stream().map(SourceCallSites.Mention::position).distinct().count())
                .as("and their positions differ, which is what keeps them two members of a "
                    + "population rather than one")
                .isEqualTo(2);
        assertThat(mentions.get(0).column()).isEqualTo(15);
        assertThat(mentions.get(1).column()).isEqualTo(34);
    }

    /**
     * The chains of every occurrence of {@code needle}, with runs of whitespace collapsed: the
     * parser joins lines without reindenting them, so a chain that spanned two source lines carries
     * the continuation's indentation. Every consumer asks substring questions of it, so the
     * spacing is not load-bearing — but an expectation written with it would be unreadable.
     */
    private static List<String> chainsAround(String needle, List<String> lines) {
        var unit = new SourceCallSites.CompilationUnit(Path.of("Planted.java"), "Planted", lines);
        return SourceCallSites.mentions(unit, List.of(needle)).stream()
                .map(mention -> mention.chain().replaceAll("\\s+", " ")).toList();
    }

    /**
     * <strong>{@link SourceCallSites#commentBlocks} in both directions</strong> — the forms it must
     * see and the forms it must not — because a shared derivation trades a loud failure (two
     * parsers disagreeing reds one of them) for a silent one (one parser blind everywhere), and
     * this one is read by a seal whose whole question is "does this prose name its seal?".
     *
     * <p>The first case is the one the primitive exists for: the live claim it was written against
     * has "Nothing request-scoped may" ending one line and "call it" opening the next, so a
     * line-at-a-time scan finds neither half and reports clean.
     */
    @Test
    void aCommentBlockJoinsItsLinesAndStopsWhereTheCodeStarts() {
        var unit = new SourceCallSites.CompilationUnit(Path.of("Planted.java"), "Planted", List.of(
                "    /**",
                "     * Nothing request-scoped may",
                "     * call it.",
                "     */",
                "    List<FieldDef> findAllLiveByKeyIn(Collection<String> keys);",
                "",
                "    // a line-comment run",
                "    // is one block too",
                "    void second() {",
                "        live(); // a trailing comment does not open one",
                "    }",
                "    /** A one-liner is a block, though isCode calls its opener code. */",
                "    void third() {"));

        var blocks = SourceCallSites.commentBlocks(unit);

        assertThat(blocks.stream().map(SourceCallSites.CommentBlock::text))
                .containsExactly("Nothing request-scoped may call it.",
                        "a line-comment run is one block too",
                        "A one-liner is a block, though isCode calls its opener code.");
        assertThat(blocks.stream().map(SourceCallSites.CommentBlock::startLine))
                .as("each block reports the line it OPENS on, so a consumer can print file:line")
                .containsExactly(1, 7, 12);
        assertThat(blocks.stream().map(SourceCallSites.CommentBlock::text))
                .as("the declaration between two blocks is code and ends the first; the trailing "
                    + "comment begins in code and opens nothing")
                .noneMatch(text -> text.contains("findAllLiveByKeyIn")
                                   || text.contains("does not open one"));
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
