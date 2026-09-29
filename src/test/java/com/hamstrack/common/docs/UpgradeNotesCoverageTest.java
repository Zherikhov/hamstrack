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
 * {@code docker compose pull && docker compose up -d}, and since HD-319
 * {@code docs/self-hosting-upgrades.md} carries one anchored {@code ###} note per release change
 * that this procedure would otherwise deliver silently (the guide keeps the mechanics of upgrading
 * and links to each note from its {@code ### Release notes} list).
 * {@code docs/release-checklist.md} carries the <em>other half</em>: a paste-ready blurb per such
 * note, written by hand into the GitHub Release body because
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
 *   <li><strong>Forward &mdash; a note with no blurb.</strong> Every {@code ###} note of
 *       {@code docs/self-hosting-upgrades.md} whose heading <em>names a release</em> must have its
 *       GitHub anchor quoted on a <strong>blockquote</strong> line of
 *       {@code docs/release-checklist.md}.
 *       The blockquote is the load-bearing part: that file's prose links these anchors too, and a
 *       mention in the maintainer narrative is not a line anybody pastes into a Release body. What
 *       has to exist is the paste-ready text.</li>
 *   <li><strong>Mirror &mdash; a blurb pointing at nothing.</strong> Every anchor any
 *       {@code self-hosting-upgrades.md#...} reference in the checklist names must resolve to a
 *       real heading in that file. This is the failure the forward direction cannot see: a
 *       renamed heading leaves the blurb in place and turns its "Details:" link into a dead
 *       fragment <em>in a published Release body</em>, where it can never be corrected. GitHub
 *       silently lands a bad fragment at the top of the page, so it looks like a working link to
 *       the person who wrote it and like a wrong page to the operator who followed it.</li>
 *   <li><strong>The exemption is a deliberate edit, not an omission.</strong> A subsection whose
 *       heading names no release is exempt from the forward rule &mdash; and must be named in
 *       {@link #UNVERSIONED_SUBSECTIONS} with the reason. Otherwise the rule would carry its own
 *       loophole: a new subsection titled without a version number would be silently outside the
 *       check, which is precisely the class of omission this test exists to end.</li>
 *   <li><strong>The population and the instruction are the same file, by mechanism.</strong> No
 *       {@code ###} under {@code ## Upgrading} in {@code docs/self-hosting.md} may name a release.
 *       HD-319 moved the notes and repointed nine link targets in the checklist without repointing
 *       its three authoring instructions, so for one commit the next release author was told to
 *       write the note into the guide while the scan read only the new file &mdash; a versioned
 *       section with no blurb, in a place nothing looks, which is HD-228's defect exactly. Prose
 *       at both ends is two things that can drift; this is one thing that cannot.</li>
 *   <li><strong>A destructive procedure meets its backup sentence first.</strong> Every note in
 *       {@code docs/self-hosting-upgrades.md} that prescribes a statement deleting, overwriting or
 *       dropping the operator's own data names {@code self-hosting.md#backups} <em>above</em> that
 *       statement. The same move separated three such procedures from the backup advice that used
 *       to share a page with them, and their reader is by design somebody who arrived from a
 *       Release-body deep link and passed no other section.</li>
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
 *   <li><strong>A SECOND backup line inside an already-guarded anchor.</strong> The backup rule
 *       fires on the <em>first</em> write below each anchor, because that is where a deep-link
 *       reader meets one. The retire block in "Duplicate accounts after an upgrade" carries its own
 *       line even though the lone-row remedy above it already satisfies the rule &mdash; deliberate
 *       redundancy for a reader who scrolled, and measured: removing it leaves this test green.
 *       Do not read that green as permission to delete it; read it as this rule being about the
 *       anchor and not about every statement.</li>
 * </ul>
 */
class UpgradeNotesCoverageTest {

    private static final String SELF_HOSTING = "docs/self-hosting.md";
    private static final String RELEASE_CHECKLIST = "docs/release-checklist.md";

    /**
     * Where the per-release notes live since HD-319. They were moved out of {@link #SELF_HOSTING}
     * because they were about half of a 3300-line page that a first-time installer had to read
     * past; the guide keeps the mechanics of upgrading (which tag to pin, applying repository
     * configuration, why downgrades are not supported) and one link per release line.
     */
    private static final String UPGRADE_NOTES = "docs/self-hosting-upgrades.md";

    /** The section of {@code docs/self-hosting.md} whose subsections a release body must carry. */
    private static final String UPGRADING_HEADING = "## Upgrading";

    /**
     * A heading names a release when it contains a semantic version. Two digits would match a date
     * and one would match "0.18" in prose, so all three components are required &mdash; which is
     * also how every such heading has ever been written.
     */
    private static final Pattern RELEASE_IN_HEADING = Pattern.compile("\\b\\d+\\.\\d+\\.\\d+\\b");

    /**
     * Any reference to a fragment of the upgrade notes, in either form the checklist uses: the
     * repository-relative {@code self-hosting-upgrades.md#...} and the absolute
     * {@code https://github.com/.../docs/self-hosting-upgrades.md#...} that a Release body needs,
     * since a relative link pasted into a Release body resolves against github.com and 404s.
     *
     * <p>The {@code -upgrades} is required rather than optional, and that is load-bearing: a
     * checklist blurb still pointing at {@code self-hosting.md#some-note} after HD-319 moved the
     * note is a dead link in a Release body, and making the suffix optional here would match it and
     * call it alive.
     */
    private static final Pattern SELF_HOSTING_ANCHOR =
            Pattern.compile("self-hosting-upgrades\\.md#([A-Za-z0-9._%-]+)");

    /**
     * The link a destructive procedure has to carry above its first write. It is the guide's
     * {@code ## Backups} section, in the relative form the upgrades page uses for every other
     * cross-reference to its sibling &mdash; and a relative one is right here, because unlike a
     * blurb this text is read on GitHub's rendering of the file itself and never pasted elsewhere.
     */
    private static final String BACKUPS_REFERENCE = "self-hosting.md#backups";

    /**
     * A heading a published Release body can deep-link straight into. {@code ###} and {@code ####}
     * both produce a GitHub fragment, so both are places a reader starts reading from &mdash; which
     * is why the backup rule resets at either and not only at the note.
     */
    private static final Pattern ANCHORABLE_HEADING = Pattern.compile("^#{3,6} ");

    /**
     * A statement that removes a row, overwrites one, or drops an object. Written as the rule
     * rather than as a list of today's procedures with exclusions beside it: an exclusion list for
     * prose goes stale on the next note, while this predicate classifies one that has not been
     * written yet.
     *
     * <p>Three details are load-bearing. It is <strong>case-sensitive</strong>, because these notes
     * write SQL keywords in upper case and discuss the same words in lower case ("a single update
     * over every issue"). Every verb must be followed by an <strong>identifier</strong>
     * ({@code [a-z_]…}), which is what keeps it off the narrative's {@code report "UPDATE 1"}. And
     * {@code UPDATE} accepts its table at end-of-line, because the retire block writes
     * {@code UPDATE users} on one line and {@code SET …} on the next, and a same-line-only pattern
     * would read the most destructive procedure in the file as harmless.
     */
    private static final Pattern DESTRUCTIVE_STATEMENT = Pattern.compile(
            "\\bUPDATE\\s+[a-z_][a-z0-9_]*(\\s+SET\\b|\\s*$)"
            + "|\\bDELETE\\s+FROM\\s+[a-z_]"
            + "|\\bDROP\\s+(TABLE|INDEX|DATABASE|COLUMN)\\s+[a-z_]"
            + "|\\bTRUNCATE\\s+[a-z_]"
            + "|\\bINSERT\\s+INTO\\s+[a-z_]");

    /**
     * The {@code ###} subsections of {@code docs/self-hosting-upgrades.md} that name no release,
     * each with the reason it owes no blurb. Membership here is a decision somebody made; absence
     * from it is the failure this test reports, so a new unversioned subsection stops the build
     * until its author says which it is.
     *
     * <p>{@code Applying repository configuration} used to be a member and is deliberately not one
     * any more: HD-319 moved the release notes out of the guide and that section <em>stayed</em>,
     * because it is the evergreen procedure for the file half of any upgrade rather than a note
     * about one release. It is now outside the scanned population entirely, which is the honest
     * place for it — an exemption for a section this test no longer reads would be a line nobody
     * could ever make fail.
     */
    private static final Map<String, String> UNVERSIONED_SUBSECTIONS = Map.of(
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
            "  1. docs/self-hosting-upgrades.md, as a '###': the anchored note an operator reads",
            "     -- what changed, who is affected, the query that answers 'am I', the remedy as a",
            "     value they can type. Add its '## Contents' entry THERE, and route to it from",
            "     where the reader already is, which is docs/self-hosting.md: that page's",
            "     '### Release notes' list, the setting's row in the configuration table, and the",
            "     '## Upgrading' prose carrying the docker compose pull command. A note written",
            "     into the guide instead is outside this test's population entirely.",
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
        for (var doc : List.of(SELF_HOSTING, UPGRADE_NOTES, RELEASE_CHECKLIST)) {
            assertThat(Files.isRegularFile(Path.of(doc)))
                    .as("'%s' does not exist. Either it was renamed and this test did not move with "
                        + "it, or the working directory is not the project root (it is '%s').",
                            doc, Path.of("").toAbsolutePath())
                    .isTrue();
        }

        // The guide keeps the MECHANICS of upgrading even though the notes left it, and it is the
        // page every upgrader is sent to first, so its section still has to be there to route from.
        assertThat(read(SELF_HOSTING))
                .as("'%s' no longer contains a '%s' heading, so the page that routes an upgrader to "
                    + "'%s' has lost the section it routes from. If it was renamed, rename it here "
                    + "too.", SELF_HOSTING, UPGRADING_HEADING, UPGRADE_NOTES)
                .contains(UPGRADING_HEADING);

        assertThat(read(SELF_HOSTING))
                .as("'%s' no longer links to '%s' at all. The notes moved there (HD-319) and the "
                    + "guide is where a reader looks first -- a move that leaves no route is a move "
                    + "that hid them.", SELF_HOSTING, UPGRADE_NOTES)
                .contains("self-hosting-upgrades.md");

        assertThat(upgradeSubsections())
                .as("'%s' yielded no '###' subsections at all", UPGRADE_NOTES)
                .isNotEmpty();

        assertThat(versionedSubsections())
                .as("'%s' yielded almost no release-versioned subsections, so the coverage "
                    + "assertion is running on an empty set and passing for that reason",
                        UPGRADE_NOTES)
                .hasSizeGreaterThanOrEqualTo(5);

        assertThat(headingAnchors(read(UPGRADE_NOTES)))
                .as("no headings were parsed out of '%s', so the dead-link assertion cannot fail",
                        UPGRADE_NOTES)
                .hasSizeGreaterThan(10);
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
                orphans.add("  %s%n    anchor: %s#%s".formatted(heading, UPGRADE_NOTES, anchor));
            }
        });

        assertThat(orphans)
                .as("""
                    %s

                    These release notes in %s name a release and have NO paste-ready blurb in %s --
                    nothing quotes their anchor inside a blockquote, so an operator whose whole
                    upgrade is 'docker compose pull && docker compose up -d' is never told about
                    them:

                    %s

                    Write one blurb per note listed above, in the blockquote that ends with its
                    'Details:' link. If a note genuinely owes no line -- it is a remedy or an
                    evergreen procedure rather than a change -- that is what a heading naming no
                    release means; retitle it and record the reason in UNVERSIONED_SUBSECTIONS.
                    """.formatted(CHECKLIST, UPGRADE_NOTES, RELEASE_CHECKLIST,
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
        var anchors = headingAnchors(read(UPGRADE_NOTES));
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
                    """.formatted(CHECKLIST, RELEASE_CHECKLIST, UPGRADE_NOTES, String.join("\n", dead)))
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

                    These '###' notes of %s name no release in their heading, so the coverage rule
                    above does not reach them, and nothing here says that was intended:

                    %s

                    Decide, and record it. Either it describes a change a release makes -- put the
                    version in the heading, which is what routes it to a release body -- or it is a
                    remedy or an evergreen procedure reached from a versioned note, in which case
                    add it to UNVERSIONED_SUBSECTIONS with that reason. Silence is the one answer
                    this test refuses, because a note nobody classified is a note nobody checked.
                    """.formatted(CHECKLIST, UPGRADE_NOTES, String.join("\n", undeclared)))
                .isEmpty();

        for (var declared : UNVERSIONED_SUBSECTIONS.keySet()) {
            assertThat(upgradeSubsections())
                    .as("'%s' is exempted from the blurb rule by UNVERSIONED_SUBSECTIONS but is no "
                        + "longer a '###' note in %s. A stale exemption is how a renamed section "
                        + "leaves the check without anybody deciding that it should.",
                            declared, UPGRADE_NOTES)
                    .contains(declared);
        }
    }

    // ============================================================ 5. the population is the file

    /**
     * <strong>A release note written into the guide is a release note nobody checks.</strong>
     *
     * <p>This is the assertion HD-319 made necessary and did not ship. The move repointed nine link
     * <em>targets</em> in {@code docs/release-checklist.md} and none of its three authoring
     * <em>instructions</em>, so the next release author was told to add the subsection under
     * {@code ## Upgrading} in {@link #SELF_HOSTING} while {@link #upgradeSubsections()} had already
     * moved to {@link #UPGRADE_NOTES}. The result is green and empty-handed: the forward check never
     * sees the subsection (wrong file), the mirror check never sees a blurb (there is none), the
     * tripwire's floors stay satisfied by the notes that did move, and an upgrade-visible change
     * ships with no paste-ready line &mdash; HD-228's defect, reintroduced by the fix to something
     * else.
     *
     * <p>The instructions were repointed in the same change. This is what stops them drifting apart
     * again: two pieces of prose can disagree, and a scan and the heading it refuses cannot.
     */
    @Test
    void noReleaseVersionedNoteRemainsInTheGuide() throws IOException {
        var stragglers = new ArrayList<String>();
        var examined = new ArrayList<String>();
        var guide = read(SELF_HOSTING).split("\\R", -1);
        var inUpgrading = false;

        for (int i = 0; i < guide.length; i++) {
            var line = guide[i];
            if (line.startsWith("## ")) {
                inUpgrading = line.equals(UPGRADING_HEADING);
            }
            if (inUpgrading && line.startsWith("### ")) {
                examined.add(line.substring(4).trim());
                if (RELEASE_IN_HEADING.matcher(line).find()) {
                    stragglers.add(
                            "  %s:%d  %s".formatted(SELF_HOSTING, i + 1, line.substring(4).trim()));
                }
            }
        }

        assertThat(stragglers)
                .as("""
                    %s

                    These headings under '%s' in %s name a release, and a versioned note in the
                    guide is OUTSIDE this test's population -- which scans %s and nothing else:

                    %s

                    Move each one to %s, keep its heading, add its '## Contents' entry there and a
                    line in the guide's '### Release notes' list pointing at it, and write the
                    paste-ready blurb in %s. Left here it has no blurb, no dead-link check and no
                    way to fail: the release ships and the operator is never told.
                    """.formatted(CHECKLIST, UPGRADING_HEADING, SELF_HOSTING, UPGRADE_NOTES,
                        String.join("\n", stragglers), UPGRADE_NOTES, RELEASE_CHECKLIST))
                .isEmpty();

        // Granularity control: the walk must actually enter the section it filters on, or the
        // assertion above is over an empty scan and passes for that reason. The guide keeps the
        // evergreen halves of upgrading -- applying repository configuration, and the release-notes
        // index -- so this section is never empty; an empty one means the walk is not entering it.
        assertThat(examined)
                .as("no '###' heading at all was read under '%s' in %s, so the assertion above "
                    + "scanned nothing and passed for that reason. Either the section was renamed "
                    + "(rename %s here too) or its evergreen subsections were removed, in which "
                    + "case decide deliberately what this check is still reading.",
                        UPGRADING_HEADING, SELF_HOSTING, UPGRADING_HEADING)
                .isNotEmpty();
    }

    // ============================================================ 6. destructive procedures

    /**
     * <strong>A note that tells an operator to destroy data names the backup first.</strong>
     *
     * <p>The reader of these procedures is, by design, somebody who arrived from a deep link in a
     * published GitHub Release body straight at one anchor. They pass no other section of any
     * document. Before HD-319 the procedures shared a page with {@code ## Backups} and with the
     * sentence telling an upgrader to take one; the move left the statements in one file and both
     * backup sentences in the other, and the deep-link reader meets neither.
     *
     * <p>What is checked is the <em>order a reader meets things in</em>: within one {@code ###}
     * note, a reference to {@code self-hosting.md#backups} must appear on a line <em>before</em> the
     * first line prescribing a write. A backup sentence at the foot of the section is a backup
     * sentence the operator reads after running the statement.
     *
     * <p>{@link #DESTRUCTIVE_STATEMENT} is the rule rather than a list with exclusions, so nothing
     * here can go stale by omission: it matches what removes a row, overwrites one or drops an
     * object. {@code REINDEX} and {@code ALTER DATABASE … REFRESH COLLATION VERSION} &mdash; the two
     * other statements these notes prescribe &mdash; are deliberately outside it, because they
     * rebuild an index and stamp a catalog version, losing no row and dropping nothing; the last
     * assertion holds that predicate live rather than leaving it as this sentence.
     */
    @Test
    void everyDestructiveNoteNamesABackupBeforeItsFirstWrite() throws IOException {
        var unguarded = new ArrayList<String>();
        var guarded = new ArrayList<String>();
        var lines = read(UPGRADE_NOTES).split("\\R", -1);

        var section = "(before the first heading)";
        var backupSeen = false;
        var reported = false;
        var subAnchors = 0;

        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (ANCHORABLE_HEADING.matcher(line).find()) {
                section = line.replaceFirst("^#{3,6} ", "").trim();
                backupSeen = false;
                reported = false;
                if (line.startsWith("#### ")) {
                    subAnchors++;
                }
            }
            if (line.contains(BACKUPS_REFERENCE)) {
                backupSeen = true;
            }
            if (!reported && DESTRUCTIVE_STATEMENT.matcher(line).find()) {
                reported = true;
                var where = "  %s%n    first write at %s:%d".formatted(section, UPGRADE_NOTES, i + 1);
                if (backupSeen) {
                    guarded.add(where);
                } else {
                    unguarded.add(where);
                }
            }
        }

        assertThat(unguarded)
                .as("""
                    A destructive procedure must name the backup BEFORE the statement, not after.

                    These notes in %s prescribe a statement that removes, overwrites or drops the
                    operator's own data, and nothing above it in the same note links '%s':

                    %s

                    Add one line above the first such statement, in the register the file already
                    uses: take a backup first -- [Backups](self-hosting.md#backups); nothing below
                    is undone by the product. The reader of these sections arrived from a deep link
                    in a published Release body and passed neither the guide's '## Backups' section
                    nor its upgrade advice. %d of these procedures already carry that line.
                    """.formatted(UPGRADE_NOTES, BACKUPS_REFERENCE, String.join("\n", unguarded),
                        guarded.size()))
                .isEmpty();

        assertThat(guarded)
                .as("""
                    Too few notes in %s were found to prescribe a destructive statement, so this
                    check is running over a population it cannot fail on. Either the remedies moved
                    again or DESTRUCTIVE_STATEMENT stopped matching them. The population it found:

                    %s
                    """.formatted(UPGRADE_NOTES,
                        guarded.isEmpty() ? "  (none)" : String.join("\n", guarded)))
                .hasSizeGreaterThanOrEqualTo(3);

        // Granularity control. The unit is the anchor a deep link can LAND on, which is any '###'
        // or '####' -- not the '###' note. A reader sent to a '####' from a Release body starts
        // reading there and never sees a backup sentence written above it in the parent. If this
        // walk stopped resetting at '####' the rule would silently coarsen back to the note, so
        // the finer boundary is asserted to be real rather than theoretical.
        assertThat(subAnchors)
                .as("no '####' heading was found in %s, so the finer-than-a-note boundary this walk "
                    + "resets on is not exercised by any content and a future '####' carrying a "
                    + "destructive statement could be excused by its parent's backup line",
                        UPGRADE_NOTES)
                .isGreaterThanOrEqualTo(1);

        // The predicate is the rule, so the predicate is what gets proven -- it must fire on each
        // shape it claims and stay silent on the two writes these notes prescribe that lose nothing.
        for (var destructive : List.of(
                "UPDATE users SET email = lower(email) WHERE email <> lower(email);",
                "UPDATE users",
                "DROP TABLE notifications_unresolvable_v20;",
                "DELETE FROM password_resets WHERE used_at IS NULL;",
                "TRUNCATE mail_send_events;",
                "INSERT INTO users (id, email) VALUES ('x', 'y');")) {
            assertThat(DESTRUCTIVE_STATEMENT.matcher(destructive).find())
                    .as("DESTRUCTIVE_STATEMENT no longer matches '%s', so a note prescribing it "
                        + "would be excused by this test while destroying data", destructive)
                    .isTrue();
        }
        for (var harmless : List.of(
                "REINDEX INDEX users_email_lower_uk;",
                "ALTER DATABASE hamstrack REFRESH COLLATION VERSION;",
                "    nothing, report \"UPDATE 1\", and leave the account locked out",
                "because the expensive part is a single update over every issue")) {
            assertThat(DESTRUCTIVE_STATEMENT.matcher(harmless).find())
                    .as("DESTRUCTIVE_STATEMENT now matches '%s', which removes no row and drops no "
                        + "object. Demanding a backup line above a statement that cannot lose "
                        + "anything is how a guard teaches its readers to ignore it.", harmless)
                    .isFalse();
        }
    }

    // ============================================================ scanning

    /**
     * Every {@code ###} heading in {@code docs/self-hosting-upgrades.md}, in order.
     *
     * <p><strong>The notes moved (HD-319) and the rule did not.</strong> They used to sit under
     * {@code ## Upgrading} in the self-hosting guide, where they were about half of a 3300-line
     * page a first-time installer had to read past. What this test enforces is that an
     * upgrade-visible change has an anchored section an operator reads <em>and</em> a paste-ready
     * blurb pointing at it — not which file the section is in. So the address changed here and
     * nothing else did: the floors, the exemption map, the dead-link walk and the two directions
     * are untouched. The whole of the upgrades document is release notes, so there is no enclosing
     * section to scan between; a {@code ####} is still subordinate to the subsection above it and
     * is routed to through that one, never independently.
     */
    private static List<String> upgradeSubsections() throws IOException {
        var found = new ArrayList<String>();
        for (var line : read(UPGRADE_NOTES).split("\\R", -1)) {
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
