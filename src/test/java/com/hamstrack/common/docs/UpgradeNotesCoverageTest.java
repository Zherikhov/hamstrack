package com.hamstrack.common.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-228 &mdash; an upgrade-visible change with a section nobody is routed to is a section
 * nobody reads.</strong>
 *
 * <h2>The correspondence being sealed</h2>
 * {@code docs/self-hosting.md} tells a self-hosted operator that the normal upgrade is
 * {@code docker compose pull && docker compose up -d}, and its {@code ## Upgrading} section carries
 * one anchored subsection per release change that this procedure would otherwise deliver silently.
 * {@code docs/release-checklist.md} carries the <em>other half</em>: a paste-ready blurb per such
 * subsection, written by hand into the GitHub Release body because
 * {@code generate_release_notes: true} lists merged PRs and says nothing about behaviour. The
 * self-hosting page sends every upgrader to the Releases page before a minor upgrade, so that body
 * is the only text that reaches somebody who upgrades without opening a manual.
 *
 * <p>The two halves are joined by nothing but attention, and attention has now failed twice: 0.18.0
 * shipped five anchored subsections and three blurbs, and the two that were missing were the two
 * with the sharper failure modes &mdash; a migration that <em>refuses to start</em> the upgrade, and
 * a bound that changes which already-stored records can be saved. Both gaps were found by hand,
 * both times by somebody reading the two files side by side. This test is what replaces that
 * reading.
 *
 * <h2>What it asserts, in both directions</h2>
 * <ul>
 *   <li><strong>Forward &mdash; a subsection with no blurb.</strong> Every {@code ###} subsection
 *       under {@code ## Upgrading} whose heading <em>names a release</em> must have its GitHub
 *       anchor quoted on a <strong>blockquote</strong> line of {@code docs/release-checklist.md}.
 *       The blockquote is the load-bearing part: that file's prose links these anchors too, and a
 *       mention in the maintainer narrative is not a line anybody pastes into a Release body. What
 *       has to exist is the paste-ready text.</li>
 *   <li><strong>Mirror &mdash; a blurb pointing at nothing.</strong> Every anchor any
 *       {@code self-hosting.md#...} reference in the checklist names must resolve to a real heading
 *       in {@code docs/self-hosting.md}. This is the failure the forward direction cannot see: a
 *       renamed heading leaves the blurb in place and turns its "Details:" link into a dead
 *       fragment <em>in a published Release body</em>, where it can never be corrected. GitHub
 *       silently lands a bad fragment at the top of the page, so it looks like a working link to
 *       the person who wrote it and like a wrong page to the operator who followed it.</li>
 *   <li><strong>The exemption is a deliberate edit, not an omission.</strong> A subsection whose
 *       heading names no release is exempt from the forward rule &mdash; and must be named in
 *       {@link #UNVERSIONED_SUBSECTIONS} with the reason. Otherwise the rule would carry its own
 *       loophole: a new subsection titled without a version number would be silently outside the
 *       check, which is precisely the class of omission this test exists to end.</li>
 * </ul>
 *
 * <h2>Why the rule keys on the heading rather than on a list of releases</h2>
 * A list of "the 0.18.0 subsections" is a list that is complete on the day it is written and is one
 * entry short from the day after. The property that survives is <em>a subsection that names a
 * release in its heading is telling upgraders about that release, and a release change nobody is
 * routed to is a change nobody hears about</em>. Keying on the heading means a subsection added for
 * 0.19.0 is in scope the moment it is written, by construction, with nothing to remember and no
 * number in this file to update.
 *
 * <h2>What this test structurally cannot see</h2>
 * Stated flatly, so that a green run is never mistaken for "the release notes are done":
 * <ul>
 *   <li><strong>Whether anybody pasted the blurb into the Release body.</strong> That body lives on
 *       GitHub and is written by hand after the tag build. This test proves the text exists and
 *       points somewhere real; the checklist's own step 3 is what puts it in front of a reader,
 *       and nothing in this repository can observe that it happened.</li>
 *   <li><strong>Whether a blurb is true, current, or about the change it links to.</strong> An
 *       anchor is a string. A blurb describing the wrong default, quoting a query that no longer
 *       runs, or naming a variable that was renamed passes every assertion here.</li>
 *   <li><strong>The first-sentence rule for a change that can refuse a boot.</strong> HD-228's AC-2
 *       &mdash; <em>a blurb for a change that can stop the container says so in its first
 *       sentence</em> &mdash; is deliberately carried in {@link #CHECKLIST} rather than asserted. A
 *       mechanical version would have to decide from prose which changes can refuse a boot, and
 *       every phrasing of that rule tested against the current text either forced a boot clause
 *       into the lead of a blurb whose headline is something else (0.18.0's PostgreSQL line, whose
 *       only boot refusal is the <em>database</em> container rejecting a value the operator types
 *       afterwards) or rewarded deleting the mention to pass. That is the shape
 *       {@code PublishedClaimsTest} names: a guard that reds the build on sentences that are
 *       correct is a guard its readers learn to disable.</li>
 *   <li><strong>An upgrade-visible change with no subsection at all.</strong> If nobody writes the
 *       {@code ## Upgrading} section, there is nothing here to pair and nothing to fail. This test
 *       makes the <em>second</em> half of the job automatic; the first half is still a judgement,
 *       and the classes of change that need one are enumerated in {@code docs/release-checklist.md}
 *       itself.</li>
 *   <li><strong>Anchors in any other document.</strong> Only the checklist&rarr;self-hosting pair is
 *       walked. {@code docs/self-hosting.md}'s own internal links, the API references and the ops
 *       runbook are out of scope.</li>
 * </ul>
 */
class UpgradeNotesCoverageTest {

    private static final String SELF_HOSTING = "docs/self-hosting.md";
    private static final String RELEASE_CHECKLIST = "docs/release-checklist.md";

    /** The section of {@code docs/self-hosting.md} whose subsections a release body must carry. */
    private static final String UPGRADING_HEADING = "## Upgrading";

    /**
     * A heading names a release when it contains a semantic version. Two digits would match a date
     * and one would match "0.18" in prose, so all three components are required &mdash; which is
     * also how every such heading has ever been written.
     */
    private static final Pattern RELEASE_IN_HEADING = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+\\b");

    /**
     * Any reference to a fragment of the self-hosting page, in either form the checklist uses: the
     * repository-relative {@code self-hosting.md#...} and the absolute
     * {@code https://github.com/.../docs/self-hosting.md#...} that a Release body needs, since a
     * relative link pasted into a Release body resolves against github.com and 404s.
     */
    private static final Pattern SELF_HOSTING_ANCHOR =
            Pattern.compile("self-hosting\\.md#([A-Za-z0-9._%-]+)");

    /**
     * The {@code ###} subsections under {@code ## Upgrading} that name no release, each with the
     * reason it owes no blurb. Membership here is a decision somebody made; absence from it is the
     * failure this test reports, so a new unversioned subsection stops the build until its author
     * says which it is.
     */
    private static final Map<String, String> UNVERSIONED_SUBSECTIONS = Map.of(
            "Applying repository configuration",
            "Evergreen procedure for the file half of any upgrade, not a change any one release "
            + "makes. It is reached from the ## Upgrading prose, and a release body that repeated "
            + "it every time would train its readers to skip the release body.",

            "Duplicate accounts after an upgrade (locale-dependent email folding)",
            "A remedy, not a change: it describes behaviour 0.16.0 fixed and is reached from the "
            + "0.18.0 address refusal, which is the only path that leads to it now and is where a "
            + "reader needs it. It also carries one sentence that is false when arrived at from "
            + "0.18.0 (which of a pair's two addresses is the right one), corrected inline in both "
            + "sections rather than in a release body, because that is a property of the procedure "
            + "and not of a release.");

    /**
     * The propagation checklist, written as properties rather than as a list of today's members:
     * in this codebase a number goes stale one entry before the list does.
     */
    private static final String CHECKLIST = String.join("\n",
            "HD-228: an upgrade-visible change needs BOTH halves, and they live in two files.",
            "",
            "  1. docs/self-hosting.md, under '## Upgrading': the anchored subsection an operator",
            "     reads -- what changed, who is affected, the query that answers 'am I', the remedy",
            "     as a value they can type. Add a '## Contents' entry and route to it from where",
            "     the reader already is (the setting's row in the configuration table, and the",
            "     '## Upgrading' prose carrying the docker compose pull command).",
            "",
            "  2. docs/release-checklist.md: a PASTE-READY blurb, inside a blockquote, ending in a",
            "     'Details:' link to that subsection's anchor as an ABSOLUTE github.com URL. The",
            "     GitHub Release body is written by hand -- generate_release_notes lists merged PRs",
            "     and says nothing about behaviour -- and docs/self-hosting.md sends every upgrader",
            "     to the Releases page before a minor upgrade. It is the only text that reaches",
            "     somebody whose whole upgrade is 'docker compose pull && docker compose up -d'.",
            "",
            "  3. Paste it into the Release body after the tag build. Nothing in this repository",
            "     can check that step, which is why it is the one that gets skipped.",
            "",
            "Two rules for the blurb itself, neither of which this test can assert:",
            "",
            "  - A change that can REFUSE A BOOT says so in its FIRST sentence. An operator",
            "    skimming a release body must not have to reach the second. That covers a refusal",
            "    driven by data they already hold (0.18.0's V23 address check) and one driven by",
            "    the value the blurb itself tells them to type (an EXPENSIVE_READ_* pair, a",
            "    STORAGE_QUOTA_WORKSPACE_BYTES below ATTACHMENT_MAX_FILE_SIZE, a blank value on any",
            "    setting bound as a number). Where the refusal belongs to another container and to",
            "    a malformed value only -- POSTGRES_* -- say it where the value is prescribed, since",
            "    a lead sentence claiming the upgrade may not boot would be false.",
            "",
            "  - A refusal may only prescribe an action its reader can perform, and a detection",
            "    query must be able to return NOTHING as a real all-clear.",
            "",
            "Match the register of the blurbs already in the file: bold lead, what an operator sees",
            "rather than what the ticket was called, the check before the pull, the remedy as a",
            "value, then 'Details:' and the link.");

    // ============================================================ 1. tripwire

    /**
     * <strong>A scan that resolves nothing passes every assertion below.</strong> Both documents
     * must exist, the {@code ## Upgrading} section must be found, and it must yield a substantial
     * set of versioned subsections &mdash; a heading-level change, a rename of the section, or a
     * working directory that is not the project root would otherwise turn this file into a test
     * that asserts over an empty collection and reports success.
     */
    @Test
    void theScanResolvesTheSectionsItClaimsToRead() throws IOException {
        for (var doc : List.of(SELF_HOSTING, RELEASE_CHECKLIST)) {
            assertThat(Files.isRegularFile(Path.of(doc)))
                    .as("'%s' does not exist. Either it was renamed and this test did not move with "
                        + "it, or the working directory is not the project root (it is '%s').",
                            doc, Path.of("").toAbsolutePath())
                    .isTrue();
        }

        assertThat(read(SELF_HOSTING))
                .as("'%s' no longer contains a '%s' heading, so the section this test pairs "
                    + "against cannot be located. If it was renamed, rename it here too -- every "
                    + "assertion below silently passes over an empty section.",
                        SELF_HOSTING, UPGRADING_HEADING)
                .contains(UPGRADING_HEADING);

        assertThat(upgradeSubsections())
                .as("the '%s' section of '%s' yielded no '###' subsections at all", UPGRADING_HEADING,
                        SELF_HOSTING)
                .isNotEmpty();

        assertThat(versionedSubsections())
                .as("the '%s' section of '%s' yielded almost no release-versioned subsections, so "
                    + "the coverage assertion is running on an empty set and passing for that "
                    + "reason", UPGRADING_HEADING, SELF_HOSTING)
                .hasSizeGreaterThanOrEqualTo(5);

        assertThat(headingAnchors(read(SELF_HOSTING)))
                .as("no headings were parsed out of '%s', so the dead-link assertion cannot fail",
                        SELF_HOSTING)
                .hasSizeGreaterThan(20);
    }

    // ============================================================ 2. forward: subsection -> blurb

    /**
     * <strong>Every release-versioned upgrade subsection is carried by a paste-ready blurb.</strong>
     * This is HD-228's defect: the subsection existed, was correct, was anchored and was reachable
     * from the page's own contents &mdash; and the mechanism that carries it to an operator who
     * opens nothing did not exist. The anchor must appear on a <em>blockquote</em> line, because
     * that is what makes it text somebody pastes rather than narrative somebody reads.
     */
    @Test
    void everyVersionedUpgradeSubsectionHasAPasteReadyBlurb() throws IOException {
        var quoted = blockquotedText(read(RELEASE_CHECKLIST));
        var orphans = new ArrayList<String>();

        versionedSubsections().forEach((heading, anchor) -> {
            if (!quoted.contains("#" + anchor)) {
                orphans.add("  %s%n    anchor: %s#%s".formatted(heading, SELF_HOSTING, anchor));
            }
        });

        assertThat(orphans)
                .as("""
                    %s

                    These '## Upgrading' subsections of %s name a release and have NO paste-ready
                    blurb in %s -- nothing quotes their anchor inside a blockquote, so an operator
                    whose whole upgrade is 'docker compose pull && docker compose up -d' is never
                    told about them:

                    %s

                    Write one blurb per subsection listed above, in the blockquote that ends with
                    its 'Details:' link. If a subsection genuinely owes no line -- it is a remedy or
                    an evergreen procedure rather than a change -- that is what a heading naming no
                    release means; retitle it and record the reason in UNVERSIONED_SUBSECTIONS.
                    """.formatted(CHECKLIST, SELF_HOSTING, RELEASE_CHECKLIST,
                        String.join("\n", orphans)))
                .isEmpty();
    }

    // ============================================================ 3. mirror: blurb -> subsection

    /**
     * <strong>No blurb points at an anchor that does not exist.</strong> The mirror failure, and the
     * more expensive one: the forward direction is caught by anybody reading the two files, while a
     * heading renamed after the blurb was written produces a dead fragment in a <em>published</em>
     * Release body, which cannot be corrected and which GitHub renders as a silent landing at the
     * top of the page rather than as an error.
     */
    @Test
    void everyAnchorTheChecklistPointsAtStillExists() throws IOException {
        var anchors = headingAnchors(read(SELF_HOSTING));
        var checklist = read(RELEASE_CHECKLIST).split("\\R", -1);
        var dead = new ArrayList<String>();

        for (int i = 0; i < checklist.length; i++) {
            var matcher = SELF_HOSTING_ANCHOR.matcher(checklist[i]);
            while (matcher.find()) {
                var anchor = matcher.group(1).toLowerCase(Locale.ROOT);
                if (!anchors.contains(anchor)) {
                    dead.add("  %s:%d -> #%s".formatted(RELEASE_CHECKLIST, i + 1, anchor));
                }
            }
        }

        assertThat(dead)
                .as("""
                    %s

                    These references in %s name a fragment that no heading in %s produces:

                    %s

                    A heading was renamed and its links were not. Inside a blockquote this is worse
                    than a broken link in a document: that text is pasted into a GitHub Release
                    body, where an unknown fragment silently lands the reader at the top of the page
                    and can never be corrected afterwards. Fix the anchors, or restore the heading.
                    """.formatted(CHECKLIST, RELEASE_CHECKLIST, SELF_HOSTING, String.join("\n", dead)))
                .isEmpty();
    }

    // ============================================================ 4. the exemption is deliberate

    /**
     * <strong>A subsection that names no release is exempt only because somebody said so.</strong>
     * Without this the forward rule carries its own loophole: a heading written without a version
     * number would be outside the check, silently, which is the shape of the omission the whole
     * test exists to end.
     */
    @Test
    void everyUnversionedUpgradeSubsectionIsADeliberateExemption() throws IOException {
        var undeclared = new ArrayList<String>();

        for (var heading : upgradeSubsections()) {
            if (!RELEASE_IN_HEADING.matcher(heading).find()
                && !UNVERSIONED_SUBSECTIONS.containsKey(heading)) {
                undeclared.add("  " + heading);
            }
        }

        assertThat(undeclared)
                .as("""
                    %s

                    These '## Upgrading' subsections of %s name no release in their heading, so the
                    coverage rule above does not reach them, and nothing here says that was
                    intended:

                    %s

                    Decide, and record it. Either it describes a change a release makes -- put the
                    version in the heading, which is what routes it to a release body -- or it is a
                    remedy or an evergreen procedure reached from a versioned section, in which case
                    add it to UNVERSIONED_SUBSECTIONS with that reason. Silence is the one answer
                    this test refuses, because a subsection nobody classified is a subsection nobody
                    checked.
                    """.formatted(CHECKLIST, SELF_HOSTING, String.join("\n", undeclared)))
                .isEmpty();

        for (var declared : UNVERSIONED_SUBSECTIONS.keySet()) {
            assertThat(upgradeSubsections())
                    .as("'%s' is exempted from the blurb rule by UNVERSIONED_SUBSECTIONS but is no "
                        + "longer a '###' subsection of '%s' in %s. A stale exemption is how a "
                        + "renamed section leaves the check without anybody deciding that it should.",
                            declared, UPGRADING_HEADING, SELF_HOSTING)
                    .contains(declared);
        }
    }

    // ============================================================ scanning

    /** Every {@code ###} heading between {@code ## Upgrading} and the next {@code ##}, in order. */
    private static List<String> upgradeSubsections() throws IOException {
        var found = new ArrayList<String>();
        var inSection = false;
        for (var line : read(SELF_HOSTING).split("\\R", -1)) {
            if (line.equals(UPGRADING_HEADING)) {
                inSection = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (line.startsWith("## ")) {
                break;
            }
            // '###' only: a '####' is subordinate to the subsection above it and is routed to
            // through that one, never independently.
            if (line.startsWith("### ")) {
                found.add(line.substring(4).trim());
            }
        }
        return found;
    }

    /** The subsections in scope for the blurb rule, as heading &rarr; anchor. */
    private static Map<String, String> versionedSubsections() throws IOException {
        var scoped = new LinkedHashMap<String, String>();
        for (var heading : upgradeSubsections()) {
            if (RELEASE_IN_HEADING.matcher(heading).find()) {
                scoped.put(heading, anchorOf(heading));
            }
        }
        return scoped;
    }

    /** Every heading in a document as the fragment GitHub generates for it. */
    private static Set<String> headingAnchors(String markdown) {
        var anchors = new LinkedHashSet<String>();
        for (var line : markdown.split("\\R", -1)) {
            if (line.matches("#{1,6} .*")) {
                anchors.add(anchorOf(line.replaceFirst("^#{1,6} ", "").trim()));
            }
        }
        return anchors;
    }

    /**
     * GitHub's heading slug: lower-case, drop everything that is not a letter, a digit, a space, an
     * underscore or a hyphen, then spaces become hyphens. That is what turns
     * {@code 0.18.0} into {@code 0180} and why an anchor cannot simply be eyeballed from a heading.
     */
    private static String anchorOf(String heading) {
        return heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit} _-]", "")
                .strip()
                .replace(' ', '-');
    }

    /**
     * Only the blockquote lines of a document, joined. A blurb is the text somebody pastes into a
     * Release body; the prose around it is the maintainer's reasoning about when to write one, and
     * an anchor mentioned only there routes nobody.
     */
    private static String blockquotedText(String markdown) {
        var quoted = new StringBuilder();
        for (var line : markdown.split("\\R", -1)) {
            if (line.stripLeading().startsWith(">")) {
                quoted.append(line).append('\n');
            }
        }
        return quoted.toString();
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
