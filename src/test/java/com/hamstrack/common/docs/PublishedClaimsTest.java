package com.hamstrack.common.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-195 &mdash; Hamstrack Cloud does not reset user data, and the sentence that says so is
 * one sentence.</strong>
 *
 * <h2>The claim being sealed</h2>
 * The project once told every visitor that Cloud data "may periodically be reset" while it was in
 * "test mode". That policy is retired (ADR-0022). The replacement is a single durability paragraph
 * carried verbatim by each surface that raises the subject, and this test refuses both halves of
 * the way such an arrangement decays: the retired claim coming back by copy-paste from git history
 * or from an older document, and the new paragraph drifting into variants.
 *
 * <h2>Why this is a JUnit test and not a vitest sibling</h2>
 * {@code vitest} never runs in CI: {@code .github/workflows/build.yml} runs exactly one command,
 * {@code ./mvnw -B verify}, and the {@code frontend-maven-plugin} executions are {@code npm ci} and
 * {@code npm run build}. Nothing invokes {@code npm test}, so a guard written there executes only
 * when a human types it, and is one nobody has seen fail by construction. This claim's surfaces are
 * also mostly outside {@code src/} &mdash; {@code README.md} and {@code docs/*.md} &mdash; which a
 * vitest seal scoped like {@code src/main/frontend/src/licensing.test.ts} could not reach at all.
 * Reading repository text from a test with a CWD-relative path has precedent here
 * ({@code AuthMailDoorsTest} walks {@code src/main/java}, {@code MailThrottleCoverageTest} reads
 * {@code rules.yml}); Java reads {@code .md} and {@code .tsx} as text equally well, so one runner
 * covers both halves of the surface set. No Spring context and no database: this cannot fail for an
 * environmental reason.
 *
 * <h2>What is scanned, and what is deliberately not</h2>
 * The scanned set is the repository's <em>published product surfaces</em>: the named documents and
 * pages below, plus every {@code .ts}/{@code .tsx}/{@code .css} under the SPA source root whose
 * filename does not declare it a test. Each exclusion is load-bearing:
 * <ul>
 *   <li>{@code src/main/resources/static} is gitignored build output, wiped by every Vite build
 *       ({@code emptyOutDir}) and absent on a clean checkout &mdash; scanning it would make this
 *       test's result depend on whether somebody had run a build.</li>
 *   <li>{@code docs/project-state.md}, {@code docs/ops-prod-hardening.md} and {@code docs/design}
 *       are operator tools and internal history &mdash; published, like everything else tracked in
 *       a public repository, but not product copy. A fact written in them is a public fact; what
 *       they are excused from is the wording rule, because they must stay free to
 *       <em>describe</em> a wipe, and forbidding the phrasing there would forbid documenting the
 *       tool that performs one. {@code docs/adr} is excluded for an unrelated reason: it is the
 *       one genuinely unpublished path here, gitignored while the ADRs are Russian-language and
 *       awaiting translation, so scanning it would read files a clone does not have.</li>
 *   <li>{@code .github} and {@code docs/release-checklist.md} carry "beta" only inside pre-release
 *       tag names ({@code 0.13.0-beta}), a versioning convention rather than a product claim.</li>
 * </ul>
 *
 * <h2>What this test structurally cannot see</h2>
 * Stated so that no reader mistakes a green run for proof that the product's story is consistent:
 * <ul>
 *   <li><strong>A reworded proposition.</strong> "Instances are refreshed periodically",
 *       "workspaces older than N days are cleared", "for evaluation only" &mdash; none of these
 *       matches the patterns below. The negative half is a tripwire for a known phrasing
 *       <em>returning</em>, which is how the licensing claim survived five surfaces (copy-paste,
 *       not invention). It is never a proof that no surface contradicts the promise.</li>
 *   <li><strong>Assembled or runtime-built copy, in general.</strong> The fragment assertion closes
 *       that for this one paragraph only, because it is the one string whose exact target text is
 *       known. A <em>different</em> claim assembled from adjacent JSX children, from a template or
 *       by concatenation stays invisible &mdash; the HD-241 shape, named here rather than papered
 *       over.</li>
 *   <li><strong>A new file of a kind not in the scanned category</strong> &mdash; a new
 *       {@code docs/*.md}, an email template, a static page. The SPA source root is covered by
 *       construction, but the markdown surfaces are named one by one, because "every {@code .md} in
 *       the repository" would sweep in the records and runbooks that must stay free to discuss a
 *       wipe.</li>
 *   <li><strong>Everything outside the repository</strong> &mdash; the announcement post, the GitHub
 *       repository description and topics, Release bodies, the Swagger UI rendering, support
 *       replies, any future translated copy.</li>
 *   <li><strong>An omission.</strong> A surface that simply says nothing offends nothing. Only the
 *       carriers named below notice a missing paragraph, and the product-wide version of "no
 *       surface tells the user anything" &mdash; the defect HD-195 opened with &mdash; is not
 *       mechanically detectable.</li>
 * </ul>
 *
 * <h2>Why there is no blanket forbidden-wording scan</h2>
 * The spec's list of wording to avoid ("your data is safe", "zero data loss", "SLA", "guaranteed",
 * "geo-redundant") is a rule for authors, not an assertion. Both API references already carry a
 * legitimate, unrelated sentence about report percentiles ending "no target, no SLA and nothing
 * configured", and "guaranteed" appears inside the canonical paragraph itself, in the clause that
 * withholds a warranty. A blanket ban would red the build on sentences that are correct, which is
 * how a guard teaches its readers to disable it. The rule is carried in the failure message
 * instead, where it reaches the person about to write the next sentence.
 */
class PublishedClaimsTest {

    /**
     * The promise, written <strong>once</strong>. Every surface is compared against this single
     * constant and none of them is re-typed here: {@code licensing.test.ts} learned that the hard
     * way, having spelled its phrase out twice, so an inflection added to one copy and not the
     * other would silently have un-exempted every lawful use in the codebase.
     */
    private static final String CANONICAL_PARAGRAPH =
            "Hamstrack Cloud does not reset user data. Your workspaces, projects and issues stay "
            + "until they are deleted. The database is backed up daily and restoring from a backup "
            + "has been tested; that is an operational practice, not a guaranteed service level.";

    /** Published surfaces no glob reaches, so they are named and their existence is asserted. */
    private static final List<String> NAMED_SURFACES = List.of(
            "README.md",
            "docs/api-cloud.md",
            "docs/api-dc.md",
            "docs/self-hosting.md",
            "src/main/frontend/index.html",
            "src/main/frontend/public/openapi.yaml");

    private static final String SPA_SOURCE_ROOT = "src/main/frontend/src";

    /** Surfaces that make the promise, and therefore must make all of it. */
    private static final List<String> PROMISE_CARRIERS = List.of(
            "README.md",
            "docs/api-cloud.md",
            "src/main/frontend/src/pages/LandingPage.tsx");

    /** Self-hosted references. A Cloud promise here is a false promise to every self-hoster. */
    private static final List<String> SELF_HOSTED_SURFACES = List.of(
            "docs/api-dc.md",
            "docs/self-hosting.md");

    /**
     * The claim that was once fused with the reset claim inside a single "Beta notice" and now
     * stands alone. Its survival is what makes that separation permanent.
     */
    private static final List<String> API_STABILITY_SURFACES = List.of(
            "docs/api-cloud.md",
            "docs/api-dc.md",
            "src/main/frontend/public/openapi.yaml");

    private static final String API_STABILITY_CLAIM = "the API is unversioned";

    /** The retired claim, in the phrasings it has been written in, plus the maturity label. */
    private static final List<Pattern> RETIRED_CLAIM = Stream.of(
                    "test[- ]mode",
                    "may (periodically )?be reset",
                    "(data|workspaces?|projects?|issues?) (may|might|can|will) be "
                            + "(reset|wiped|erased|cleared)",
                    "periodically (be )?(reset|wiped)",
                    "\\bbeta\\b")
            .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
            .toList();

    /**
     * Openings of the paragraph's first and third sentences. A surface carrying one of these
     * without carrying the whole paragraph has reworded, truncated or assembled it.
     */
    private static final List<String> PARAGRAPH_FRAGMENTS = List.of(
            "does not reset", "backed up daily");

    /**
     * The propagation checklist: the decision, the paragraph, the wording rule, then the surfaces
     * this test cannot see. Written as properties rather than counts, because in this codebase a
     * number goes stale one entry before the list does.
     */
    private static final String CHECKLIST = String.join("\n",
            "HD-195: Hamstrack Cloud DOES NOT reset user data. The 'test mode' / 'may periodically",
            "be reset' policy is retired (ADR-0022), and 'beta' is no longer used as a product",
            "label anywhere a user reads.",
            "",
            "One paragraph, carried verbatim by every surface that raises the subject:",
            "",
            "  " + CANONICAL_PARAGRAPH,
            "",
            "It denies a POLICY ('does not reset') and describes a PRACTICE ('backed up daily'),",
            "and its third clause withholds a warranty. Keep all three: a copy that keeps the first",
            "two sentences and drops the last promises an outcome nobody can honour, and the third",
            "clause is what makes the paragraph agree with the Terms instead of contradicting them.",
            "",
            "Do not reach for 'your data is safe', 'zero data loss', 'SLA', 'guaranteed uptime' or",
            "'geo-redundant'. This test does not ban those words, because both API references use",
            "'SLA' legitimately about report percentiles and the paragraph itself uses 'guaranteed'",
            "to withhold a promise. The rule is yours to keep; the ban would red the build on",
            "sentences that are correct.",
            "",
            "Cloud only. A self-hosted reader's durability is whatever their own backups make it, so",
            "the paragraph never appears in a DC-facing document.",
            "",
            "The same subject is raised on surfaces this test cannot read. If you are changing the",
            "wording, change them in the same commit:",
            "  - the launch/announcement post and any follow-up that mentions data",
            "  - the GitHub repository description and topics, and GitHub Release bodies",
            "  - the Swagger UI rendering of openapi.yaml's info.description",
            "  - docs/project-state.md's wipe section: the operator tool, which must stay free to",
            "    describe a wipe, and must keep saying that Cloud no longer performs one",
            "  - docs/design/admin-console-proposal.md's dated historical marker",
            "  - any prompt under .claude/ that generates copy: an agent description is a copy",
            "    GENERATOR, so a stale sentence there puts the claim back with no edit to any file");

    // ============================================================ 1. tripwire

    /**
     * <strong>A glob that matches nothing passes every other assertion in this file.</strong> Each
     * named surface must exist (a renamed document drops out of the set otherwise, silently), and
     * the SPA glob must resolve a substantial set rather than an empty one.
     */
    @Test
    void theScanResolvesTheSurfacesItClaimsToRead() throws IOException {
        for (var surface : NAMED_SURFACES) {
            assertThat(Files.isRegularFile(Path.of(surface)))
                    .as("named published surface '%s' does not exist. Either it was renamed and this "
                        + "test did not move with it, or the working directory is not the project "
                        + "root (it is '%s'). A named surface that vanishes is a surface that stops "
                        + "being guarded, silently.",
                            surface, Path.of("").toAbsolutePath())
                    .isTrue();
        }

        assertThat(spaSources())
                .as("the SPA source scan under '%s' resolved almost nothing, so every assertion "
                    + "below that reads the SPA is running on an empty set and passing for that "
                    + "reason", SPA_SOURCE_ROOT)
                .hasSizeGreaterThan(50);

        assertThat(surfaces())
                .as("the published-surface scan resolved a trivial number of files")
                .hasSizeGreaterThan(100);
    }

    // ============================================================ 2. the retired claim

    /**
     * <strong>The retired claim does not return.</strong> Copy-paste from git history or from an
     * older document is how a corrected sentence comes back; this is a tripwire for the phrasings
     * that claim has actually been written in, never a proof that no surface contradicts the
     * promise in words nobody has used yet.
     */
    @Test
    void theRetiredResetClaimDoesNotReturn() throws IOException {
        var offenders = new ArrayList<String>();
        surfaces().forEach((id, text) -> {
            var lines = text.split("\\r?\\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (var pattern : RETIRED_CLAIM) {
                    var matcher = pattern.matcher(lines[i]);
                    if (matcher.find()) {
                        offenders.add("%s:%d (matched /%s/ at col %d): %s"
                                .formatted(id, i + 1, pattern.pattern(), matcher.start() + 1,
                                        lines[i].trim()));
                    }
                }
            }
        });

        assertThat(offenders)
                .as("\n%s\n\nThe retired claim is published at:\n%s\n",
                        CHECKLIST, String.join("\n", offenders))
                .isEmpty();
    }

    // ============================================================ 3. the paragraph is present

    /**
     * <strong>Every surface that makes the promise makes all of it.</strong> Compared after
     * normalisation, so markdown emphasis, a JSX line wrap and a CRLF are not differences.
     */
    @Test
    void everySurfaceThatMakesThePromiseCarriesTheWholeParagraph() throws IOException {
        var scanned = surfaces();
        var target = normalise(CANONICAL_PARAGRAPH);
        var missing = new ArrayList<String>();

        for (var carrier : PROMISE_CARRIERS) {
            var text = scanned.get(carrier);
            assertThat(text)
                    .as("'%s' carries the durability paragraph but is not in the scanned set", carrier)
                    .isNotNull();
            if (!normalise(text).contains(target)) {
                missing.add(carrier);
            }
        }

        assertThat(missing)
                .as("\n%s\n\nThe canonical paragraph is missing from:\n%s\n",
                        CHECKLIST, String.join("\n", missing))
                .isEmpty();
    }

    // ============================================================ 4. no fragment without the whole

    /**
     * <strong>A fragment without the whole means the paragraph was reworded, truncated or
     * assembled.</strong> This is the HD-241 mitigation, and it closes nothing else: assembly and
     * truncation become a detectable state for <em>this one sentence</em>, because it is the one
     * string whose exact target text is known. Any other claim built at runtime, or from adjacent
     * JSX children, remains invisible to a text scan.
     */
    @Test
    void noSurfaceCarriesAFragmentOfTheParagraphWithoutTheWhole() throws IOException {
        var target = normalise(CANONICAL_PARAGRAPH);
        var offenders = new ArrayList<String>();

        surfaces().forEach((id, text) -> {
            var normalised = normalise(text);
            if (normalised.contains(target)) {
                return;
            }
            for (var fragment : PARAGRAPH_FRAGMENTS) {
                if (!normalised.toLowerCase(Locale.ROOT)
                        .contains(fragment.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                for (var where : linesContaining(text, fragment)) {
                    offenders.add("%s (fragment '%s'): %s".formatted(id, fragment, where));
                }
            }
        });

        assertThat(offenders)
                .as("\n%s\n\nThe canonical paragraph was reworded, truncated or assembled at:\n%s\n",
                        CHECKLIST, String.join("\n", offenders))
                .isEmpty();
    }

    // ============================================================ 5. the API claim survived

    /**
     * <strong>The API-stability claim survived the data claim's retirement.</strong> The two were
     * once fused in one "Beta notice", and separating them is only permanent while both halves are
     * asserted: a later tidy-up of that notice which removes the data sentence has every
     * opportunity to take the API sentence with it, and nothing else in the suite would notice.
     */
    @Test
    void theApiStabilityClaimSurvivedTheDataClaimsRetirement() throws IOException {
        var scanned = surfaces();
        var needle = API_STABILITY_CLAIM.toLowerCase(Locale.ROOT);
        var missing = new ArrayList<String>();

        for (var surface : API_STABILITY_SURFACES) {
            var text = scanned.get(surface);
            assertThat(text).as("'%s' is not in the scanned set", surface).isNotNull();
            if (!normalise(text).toLowerCase(Locale.ROOT).contains(needle)) {
                missing.add(surface);
            }
        }

        assertThat(missing)
                .as("\n%s\n\nThe API is unversioned, and these surfaces no longer say so:\n%s\n\n"
                    + "That sentence used to be fused with the retired reset claim inside a single "
                    + "'Beta notice'. Retiring the data half must not take the API half with it: a "
                    + "caller who is not told the API is unversioned is a caller nobody warned "
                    + "about a breaking change.",
                        CHECKLIST, String.join("\n", missing))
                .isEmpty();
    }

    // ============================================================ 6. DC surfaces stay DC

    /**
     * <strong>A Cloud promise on a self-hosted surface is a false promise.</strong> The paragraph is
     * about the operator's instance; a self-hoster's durability is whatever their own backups make
     * it, and nothing in the product provides it for them. Pasting the paragraph into a DC
     * reference is the most likely copy-paste error in a diff that touches every published document
     * at once.
     */
    @Test
    void theCloudPromiseNeverAppearsOnASelfHostedSurface() throws IOException {
        var scanned = surfaces();
        var target = normalise(CANONICAL_PARAGRAPH);
        var offenders = new ArrayList<String>();

        for (var surface : SELF_HOSTED_SURFACES) {
            var text = scanned.get(surface);
            assertThat(text).as("'%s' is not in the scanned set", surface).isNotNull();
            if (normalise(text).contains(target)) {
                var where = linesContaining(text, "does not reset user data");
                offenders.add(surface + (where.isEmpty() ? "" : " at " + String.join("; ", where)));
            }
        }

        assertThat(offenders)
                .as("\n%s\n\nA Cloud durability promise is published on a self-hosted surface:\n%s\n\n"
                    + "Nothing in the product gives a self-hosted installation that guarantee, so "
                    + "the sentence is false to every reader of these documents. docs/self-hosting.md "
                    + "carries the DC-equivalent pointer instead: durability there is whatever the "
                    + "operator's own backups make it.",
                        CHECKLIST, String.join("\n", offenders))
                .isEmpty();
    }

    // ============================================================ scanning

    /** Every published surface, keyed by its repository-relative path with forward slashes. */
    private static Map<String, String> surfaces() throws IOException {
        var scanned = new LinkedHashMap<String, String>();
        for (var surface : NAMED_SURFACES) {
            var path = Path.of(surface);
            if (Files.isRegularFile(path)) {
                scanned.put(surface, read(path));
            }
        }
        for (var path : spaSources()) {
            scanned.put(id(path), read(path));
        }
        return scanned;
    }

    /**
     * The SPA's own text: every {@code .ts}/{@code .tsx}/{@code .css} under the source root whose
     * filename does not declare it a test. Test sources are excluded because a seal quotes the
     * phrasing it forbids, so a scan that read them would report the seals themselves.
     */
    private static List<Path> spaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of(SPA_SOURCE_ROOT))) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        var name = p.getFileName().toString();
                        if (name.endsWith(".test.ts") || name.endsWith(".test.tsx")) {
                            return false;
                        }
                        return name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".css");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static String id(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read published surface " + id(path), e);
        }
    }

    /**
     * Reduce a surface to the text a reader sees, so that markdown emphasis, a JSX line wrap and a
     * CRLF are not differences. A JSX space expression is <em>removed</em> rather than replaced by
     * a space: a paragraph whose clauses are joined by one is assembled rather than written, and
     * the fragment assertion exists to say so.
     */
    private static String normalise(String text) {
        return text.replace("{' '}", "")
                .replace("<strong>", "")
                .replace("</strong>", "")
                .replace("&amp;", "&")
                .replace("**", "")
                .replace("*", "")
                .replace("_", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** {@code line N: text} for each raw line containing {@code needle}, case-insensitively. */
    private static List<String> linesContaining(String text, String needle) {
        var lower = needle.toLowerCase(Locale.ROOT);
        var lines = text.split("\\r?\\n", -1);
        var found = new ArrayList<String>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase(Locale.ROOT).contains(lower)) {
                found.add("line %d: %s".formatted(i + 1, lines[i].trim()));
            }
        }
        return found;
    }
}
