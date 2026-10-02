package com.hamstrack.ops;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.hamstrack.ops.PublishedCredentials.publishableFiles;
import static com.hamstrack.ops.PublishedCredentials.read;
import static com.hamstrack.ops.PublishedCredentials.repositoryPath;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-321 &mdash; the warning that no release carries the JAR assets yet is one claim in
 * several documents, and the first tag that publishes them deletes every copy together.</strong>
 *
 * <h2>Why this exists, and which direction the defect runs in</h2>
 * {@code .github/workflows/build.yml} attaches {@code hamstrack-X.Y.Z.jar} and its SHA-256 to a
 * Release, and the assets appear only on the <em>next</em> tag cut after that change &mdash;
 * measured 2026-10-02, all ten existing releases answer {@code assets: []}. Several documents say
 * so, because readers arrive at the JAR path from several places. On the day a tag finally publishes
 * the assets, that is one independent edit per document, most of them in files nobody has a reason
 * to open. (No count here, and none in the documents either: the live one is in the failure
 * messages, which compute it from {@link #MEMBERS}.)
 *
 * <p>The failure direction is the bad one. A surviving "measured 2026-10-02, none of them" does not
 * merely go out of date: it tells an operator <em>not to look</em> for a file that now exists, on
 * the entry point they landed on. Nobody is told they were misinformed; they simply build from a
 * clone and get a {@code 0.0.0-DEV} JAR with none of the identity guarantees the published one has.
 *
 * <p>So this is one rule over <strong>one marker</strong> rather than a rule per sentence.
 * The marker is a comment, not the date string: a date invites re-dating, and a re-dated
 * sentence is the same claim wearing a fresh timestamp. A marker can only be deleted.
 *
 * <h2>The member this one is really about: the release checklist</h2>
 * {@code InstallClaimsTest#theInstallChecklistStepsWereRunOnThisReleaseLine} takes the
 * <strong>latest</strong> {@code Measured} date in a step as that step's run date, and step 6 has
 * two halves of which only one has a subject today. So re-running the identity half and dating it
 * makes the seal report a fully-run step &mdash; which is exactly what the step's own STANDING LINE
 * block warns about in prose, and what nothing checked (measured by the HD-321 ops re-review). The
 * marker therefore sits <em>inside that blockquote</em>: deleting the block to tidy the step up
 * takes the marker with it and reds this rule by name, listing the documents that still carry the
 * warning. {@link #theChecklistMarkerTravelsWithTheStandingLineBlock} is that coupling, asserted
 * rather than described.
 *
 * <h2>What this cannot see, stated so a green run is never read as more</h2>
 * Whether the sentences are <em>true</em> &mdash; only a {@code gh release view} can answer that,
 * which is why it is step 6 of {@code docs/release-checklist.md} and not a unit test; whether a
 * further document makes the claim <em>without</em> the marker (prose cannot be derived &mdash;
 * {@link #noDocumentOutsideTheDeclaredSetCarriesTheMarker} holds the opposite direction, which is
 * the one a scan can have); and whether the warning is still needed at all.
 *
 * <p><strong>This class is deleted with the warning.</strong> When a release carries the two assets,
 * every marker and the paragraph around it goes, and so does this file &mdash; in the same commit.
 * Until then it is the only thing holding the copies together.
 */
class UnpublishedAssetWarningTest {

    /**
     * The marker, written once. A dedicated comment rather than the {@code 2026-10-02} the
     * paragraphs share: a date is re-datable, and a re-dated warning reads as a fresh measurement
     * of a claim nobody re-measured. This one is invisible in rendered Markdown and has no meaning
     * except "the warning above/below me is part of the set".
     *
     * <p><strong>Do not spell it {@code hamstrack:<something>}.</strong> The first version was
     * {@code <!-- hamstrack:no-published-jar-assets-yet -->} and
     * {@code InstallClaimsTest#everyPinAnInstallDocumentOffersNamesTheCurrentReleaseLine} went red on
     * two of the documents (measured): its {@code PIN} pattern is
     * {@code (?:APP_IMAGE_TAG=|hamstrack:)(…)}, so the marker read as an image pin offering the
     * release line {@code no-published-jar-assets-yet}. A marker is text in a published document and
     * is scanned by every rule that scans those documents.
     */
    private static final String MARKER = "<!-- marker: no-published-jar-assets-yet -->";

    /**
     * The documents that carry the warning, each with the reader who arrives there. Declared
     * rather than derived, for the reason {@code InstallClaimsTest}'s own document set is: "makes
     * this claim" is a property of prose, and a grep for it would be answered by rewording. What
     * stops the declaration going stale is {@link #noDocumentOutsideTheDeclaredSetCarriesTheMarker}
     * in one direction and {@link #DECLARED_MEMBERS} in the other.
     */
    private static final Map<String, String> MEMBERS = new LinkedHashMap<>();

    static {
        // Inserted rather than wrapped around Map.of: the failure messages print this set, and
        // Map.of randomises its iteration order per JVM run, so a reader comparing two runs of the
        // same red would see the member paths in two different orders.
        MEMBERS.put("README.md",
                "the entry point - the reader who never opens docs/ at all");
        MEMBERS.put("docs/self-hosting.md",
                "the JAR path's own section, where a reader is told where the assets come from");
        MEMBERS.put("docs/self-hosting-upgrades.md",
                "the locale-pin note, which sends an upgrading operator to the JAR path");
        MEMBERS.put("docs/release-checklist.md",
                "the STANDING LINE on step 6 - the half-verified warning on the step that publishes them");
    }

    /**
     * A collapse floor, not a count of anything derivable: there is nothing in the tree to read the
     * number of documents that make a prose claim out of, so it is written, and it only ever has to
     * catch the direction that <em>deletes</em> &mdash; a member quietly dropped from the map
     * together with its marker, which would narrow this rule to three documents in silence. Adding
     * a fifth document raises it in the same commit as the document, which is a deliberate edit.
     */
    private static final int DECLARED_MEMBERS = 4;

    /** Where the step-6 coupling lives. @see #theChecklistMarkerTravelsWithTheStandingLineBlock */
    private static final String CHECKLIST = "docs/release-checklist.md";

    @Test
    void everyWarningThatNoReleaseCarriesTheJarAssetsIsMarkedOrNoneIs() {
        assertThat(MEMBERS)
                .withFailMessage("""
                        THE SET OF DOCUMENTS CARRYING THIS WARNING HAS SHRUNK, so the all-or-none
                        rule below is holding fewer documents than the warning lives in.

                        Declared: %s

                        A member is removed from this map only when its paragraph is gone for good -
                        and on the day the assets ship, ALL of them go and this class goes with them.
                        Dropping one is how three documents keep telling an operator not to look for
                        a file that exists.""", MEMBERS.keySet())
                .hasSizeGreaterThanOrEqualTo(DECLARED_MEMBERS);

        var carriers = new ArrayList<String>();
        var silent = new ArrayList<String>();
        var duplicated = new ArrayList<String>();
        for (var member : MEMBERS.entrySet()) {
            String path = member.getKey();
            assertThat(Path.of(path))
                    .withFailMessage("""
                            '%s' carries one of the copies of this warning (%s) and is not a
                            file. Either it moved and this set did not move with it, or the working
                            directory is not the project root (it is '%s').""",
                            path, member.getValue(), Path.of("").toAbsolutePath())
                    .isRegularFile();
            int count = occurrences(read(Path.of(path)));
            if (count == 0) {
                silent.add("  %s - %s".formatted(path, member.getValue()));
            } else {
                carriers.add(path);
                if (count > 1) {
                    duplicated.add("  %s carries the marker %d times".formatted(path, count));
                }
            }
        }

        assertThat(duplicated)
                .withFailMessage("""
                        A DOCUMENT CARRIES THE MARKER MORE THAN ONCE, so this rule can no longer tell
                        which paragraph it is holding and a half-deleted warning would pass.

                        %s

                        One marker per document, on the paragraph that makes the claim.""",
                        String.join("\n", duplicated))
                .isEmpty();

        assertThat(silent)
                .withFailMessage("""
                        THE WARNING THAT NO RELEASE CARRIES THE JAR ASSETS IS HALF DELETED.

                        Still carrying `%s`:
                          %s

                        No longer carrying it:
                        %s

                        One claim lives in all %d documents above, because readers arrive at the JAR
                        path from that many places. THE FIRST TAG THAT PUBLISHES THE ASSETS DELETES
                        EVERY COPY - every paragraph, every marker, and this test class with them, in
                        one commit. Until then every copy stands.

                        This is the direction that hurts: a surviving "measured 2026-10-02, none of
                        them" tells an operator NOT TO LOOK for a file that now exists, on whichever
                        page they landed on - and they build from a clone instead, getting a
                        `0.0.0-DEV` JAR with none of the published one's identity guarantees.

                        If a release now carries the assets, finish the job: delete the remaining
                        paragraphs (not just their markers), delete this class, and record the
                        readings in step 6 of docs/release-checklist.md.""",
                        MARKER, String.join("\n  ", carriers), String.join("\n", silent),
                        MEMBERS.size())
                .isEmpty();
    }

    /**
     * <strong>The coupling that makes the checklist's own seal honest.</strong>
     * {@code InstallClaimsTest} reads the latest {@code Measured} date in a step as its run date, so
     * step 6 - whose published half has never had a subject - would report as fully run the moment
     * somebody re-dates its identity half. The STANDING LINE blockquote is what says otherwise in
     * prose; the marker lives inside it, so deleting the block takes the marker with it and the rule
     * above reds naming the other three documents. A marker parked outside the quote would survive
     * that deletion and the coupling would be prose again.
     */
    @Test
    void theChecklistMarkerTravelsWithTheStandingLineBlock() {
        String text = read(Path.of(CHECKLIST));
        if (occurrences(text) == 0) {
            return;     // the assets shipped and the block is gone; the rule above holds that case
        }

        var outside = new ArrayList<String>();
        boolean quotedStandingLine = false;
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains(MARKER)) {
                continue;
            }
            if (!lines[i].stripLeading().startsWith(">")) {
                outside.add("  %s:%d - %s".formatted(CHECKLIST, i + 1, lines[i].strip()));
                continue;
            }
            quotedStandingLine = blockquoteAround(lines, i).contains("STANDING LINE");
        }

        assertThat(outside)
                .withFailMessage("""
                        THE CHECKLIST'S MARKER IS NOT INSIDE THE STANDING LINE BLOCK.

                        %s

                        It has to be inside that blockquote, because the block is what it protects.
                        Step 6 has two halves and only the identity half has a subject today, and
                        InstallClaimsTest reads the LATEST `Measured` date in a step as the step's run
                        date - so re-running the identity half and dating it makes that seal report a
                        fully-run step. The block says so in prose; the marker inside it is what makes
                        deleting the block red this test. A marker parked outside the quote survives
                        exactly the edit it exists to catch.""", String.join("\n", outside))
                .isEmpty();

        assertThat(quotedStandingLine)
                .withFailMessage("""
                        THE MARKER IS IN A BLOCKQUOTE THAT IS NOT THE STANDING LINE BLOCK of %s.

                        The quote it sits in does not mention STANDING LINE, so it is some other
                        blockquote and the coupling above is no longer held: the half-verified warning
                        on step 6 could be deleted while this marker stays. Move the marker back into
                        the STANDING LINE block, or - if that block is genuinely gone because the
                        assets shipped - delete every marker, their paragraphs and this class.""",
                        CHECKLIST)
                .isTrue();
    }

    /**
     * <strong>The derived half: the marker cannot appear anywhere this rule is not looking.</strong>
     * A declared set goes stale by omission, and a fifth copy of the warning marked but unheld would
     * be the original defect with one more file in it. This direction is the one a scan can have:
     * prose cannot be derived, but a marker can.
     */
    @Test
    void noDocumentOutsideTheDeclaredSetCarriesTheMarker() {
        var scanned = new ArrayList<String>();
        var undeclared = new ArrayList<String>();
        for (Path file : publishableFiles("*.md")) {
            String path = repositoryPath(file);
            scanned.add(path);
            if (occurrences(read(file)) > 0 && !MEMBERS.containsKey(path)) {
                undeclared.add("  " + path);
            }
        }

        assertThat(scanned)
                .withFailMessage("""
                        NO MARKDOWN FILES WERE SCANNED AT ALL, so this rule has nothing to look
                        through and would pass a marked document nobody holds. It reads the
                        publishable `*.md` set from git; an empty answer means the working directory
                        is not the repository root (it is '%s').""", Path.of("").toAbsolutePath())
                .isNotEmpty();

        assertThat(undeclared)
                .withFailMessage("""
                        A DOCUMENT CARRIES THE MARKER AND IS NOT HELD TO THE ALL-OR-NONE RULE.

                        %s

                        Declared: %s
                        Scanned:  %d publishable Markdown files.

                        Add it to MEMBERS with the reader who arrives there, so the first tag that
                        publishes the assets deletes this copy too. A marked document outside the set
                        is the HD-321 defect with one more file in it: it would keep telling its own
                        reader not to look for a file that exists, and nothing here would notice.""",
                        String.join("\n", undeclared), MEMBERS.keySet(), scanned.size())
                .isEmpty();
    }

    // ============================================================ helpers

    private static int occurrences(String text) {
        int count = 0;
        for (int at = text.indexOf(MARKER); at >= 0; at = text.indexOf(MARKER, at + MARKER.length())) {
            count++;
        }
        return count;
    }

    /**
     * The contiguous run of blockquote lines the line at {@code index} belongs to, joined. Used to
     * ask which quote the marker is in, rather than counting lines from a heading - a block moves
     * within its step whenever the prose around it is edited, and its first line does not.
     */
    private static String blockquoteAround(String[] lines, int index) {
        int first = index;
        while (first > 0 && lines[first - 1].stripLeading().startsWith(">")) {
            first--;
        }
        int last = index;
        while (last + 1 < lines.length && lines[last + 1].stripLeading().startsWith(">")) {
            last++;
        }
        return String.join("\n", List.of(lines).subList(first, last + 1));
    }
}
