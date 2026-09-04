package com.hamstrack.issue;

import com.hamstrack.issue.entity.Issue;
import com.hamstrack.issue.service.SprintScopeLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The enumerated doors</strong> (HD-137 / R2, reports-proposal §5.2) — the test
 * this slice exists for.
 *
 * <p>Sprint membership changes in eight places. If ONE of them forgets the scope ledger,
 * nothing breaks: no exception, no missing row a user can see, no failing endpoint. The
 * only symptom is that months later a burn-up's scope line is quietly wrong — which is
 * precisely the failure that makes competitors' sprint charts distrusted (§1.2), and the
 * whole reason R4 is being built on a ledger instead of on {@code issue_history}.
 *
 * <p>So this class asks three different questions, in the directions a door can be lost:
 *
 * <ol>
 *   <li>{@link #everyDoorOntoSprintMembershipIsRegistered()} reads the SOURCE and finds
 *       every statement that writes {@code issues.sprint_id} — {@code setSprint(…)} and
 *       the two bulk {@code UPDATE Issue i SET i.sprint …} statements. An unregistered
 *       one fails the build with <em>"you added a door and didn't tell me"</em>. That is
 *       deliberately not a count: a count going from 7 to 8 tells you nothing about which
 *       statement to look at, and this failure names the file, the line and what to do.</li>
 *   <li>{@link #everyDeletionShapedPathOntoSprintMembershipIsRegistered()} reads the same
 *       source for the OTHER shape a membership can end in — a statement that removes the
 *       issue row ({@code issueRepository.delete…}, {@code DELETE FROM issues}) — and
 *       requires every hit to resolve to a member registered in
 *       {@link #UNSCANNABLE_DOORS}.</li>
 *   <li>{@link #everyRegisteredDoorRoutesThroughTheLedger()} opens each registered door
 *       over HTTP and asserts the ledger was handed <strong>the same issue ids</strong>
 *       the door wrote {@code sprint} history rows for — by id, not by count, because a
 *       bulk path that records the wrong subset has the right count.</li>
 * </ol>
 *
 * <p>Door 1 ({@code POST /issues} carrying {@code sprintId}) is why question 1 scans for
 * {@code setSprint} rather than for history writers: creating an issue straight into a
 * running sprint enlarges its scope and writes <em>no</em> history row at all, so an
 * audit-based enumeration would have missed it.
 *
 * <p><strong>What the scans CANNOT do, said out loud — and the narrower version of that
 * claim.</strong> Question 1 finds statements that WRITE {@code issues.sprint_id}. Door 8
 * — {@code DELETE /issues/{n}} — ends a membership by removing the row, and a
 * {@code DELETE} never writes that column: there is no {@code setSprint} and no
 * {@code SET sprint_id =} for any pattern to match, in this or any other spelling. That is
 * not a hole in question 1's regexes, it is the shape of the door. But "no scan can find
 * it" was too strong, and question 2 is the correction: a delete has a shape, question 2
 * matches it, and it would have found door 8 unaided. What survives of the original claim
 * is smaller and exact — <em>no scan can prove the registered member CALLS the ledger</em>,
 * so door 8's behaviour is pinned by {@code SprintScopeLedgerIntegrityTest} instead, which
 * is where a conditional rule (COMPLETED sprints frozen, ACTIVE ones not) belongs anyway.
 * It lives in {@link #UNSCANNABLE_DOORS} rather than in {@link #DOORS}, kept separate on
 * purpose: a reader must not be able to look at one registry and conclude it covers every
 * door.
 *
 * <p>Door 3 ({@code POST …/rank}) is why the
 * scan exists at all — the proposal's hand-written table listed five doors, and the scan
 * found six.
 *
 * <p>{@link SprintScopeLedger} is a {@link MockitoSpyBean}: a spy calls through, so the
 * real rows are still written and the DB assertions below stay honest, while the
 * invocations record what each door handed the single writer. That matters for the
 * force-delete door, whose ledger rows are removed again by the sprint's own
 * {@code ON DELETE CASCADE} before the request even returns — the only way to observe
 * that door is to watch the writer, not the table.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SprintScopeLedgerDoorsTest extends SprintTestBase {

    @Autowired JdbcTemplate jdbc;

    /** Spied, not mocked: every door's real ledger rows are still written. */
    @MockitoSpyBean SprintScopeLedger ledger;

    // ==================================================================== question 1

    /**
     * The registry. Key = {@code Class.member} that writes {@code issues.sprint_id};
     * value = where its ledger row comes from. Both halves are load-bearing: the key is
     * what the source scan compares against, and the value is the sentence a future
     * author needs when they add the eighth door.
     */
    private static final Map<String, String> DOORS = new LinkedHashMap<>() {{
        put("IssueService.create",
                "door 1 — POST /issues carrying sprintId; writes NO history row, so only "
                + "the ledger records it: scopeLedger.recordMove(ws, issue, null, sprint) "
                + "right after save()");
        put("IssueService.update",
                "door 2 — PATCH /issues/{n} carrying sprintId/clearSprint: "
                + "scopeLedger.recordMove(ws, issue, leftSprint, issue.getSprint()) after "
                + "the history rows");
        put("IssueService.rank",
                "door 3 — POST /issues/{n}/rank carrying sprintId/clearSprint (the door the "
                + "proposal's table of five missed): scopeLedger.recordMove(...)");
        put("SprintService.addIssues",
                "door 4 — POST /sprints/{id}/issues: one recordMove per changing issue, "
                + "from the same loop that builds the history rows");
        put("SprintService.removeIssue",
                "door 5 — DELETE /sprints/{id}/issues/{issueId}: recordMove(ws, issue, "
                + "sprint, null)");
        put("IssueRepository.moveUnfinishedOutOfSprint",
                "door 6 — the completion's bulk carry-over; the ledger is written by "
                + "SprintService.complete via recordBulkMove on the SAME (id, number, "
                + "storyPoints) refs the history rows use");
        put("IssueRepository.clearSprint",
                "door 7 — the force-delete's bulk detach; the ledger is written by "
                + "SprintService.delete via recordBulkMove (those rows then cascade away "
                + "with the sprint, deliberately)");
    }};

    /**
     * The doors the source scan <strong>structurally cannot find</strong> — a documented
     * exception list, deliberately NOT merged into {@link #DOORS}.
     *
     * <p>{@link #DOORS} is what {@link #everyDoorOntoSprintMembershipIsRegistered()}
     * compares the source against, in both directions. An entry here would fail its
     * "these registered doors no longer exist in the source" half immediately, because
     * that is precisely the property that makes it unscannable: it writes nothing the
     * scan looks for. Papering over that with a bulkier regex would be worse than
     * useless — it would make the registry LOOK complete while enforcing seven eighths of
     * it.
     *
     * <p>What IS checked mechanically, and it is more than it was: the member named here
     * still exists (so a rename cannot silently orphan the entry), AND every
     * deletion-shaped statement in the backend resolves to an entry here —
     * {@link #everyDeletionShapedPathOntoSprintMembershipIsRegistered()}, question 2. So
     * this map is a scanned registry rather than a list somebody has to remember to add to.
     * What is NOT and cannot be checked: that the member calls the ledger. That obligation
     * is carried by {@code SprintScopeLedgerIntegrityTest.deletingAnIssue…}, which is named
     * in the value below so the next reader can find it from here.
     *
     * <p><strong>Why CASCADE deletes are not doors and need no entry.</strong> Deleting a
     * project or a workspace removes issues in bulk, and none of it is registered — not an
     * oversight, a consequence of the data model: a sprint belongs to exactly ONE project,
     * so anything that cascades issues away cascades away the sprints whose scope those
     * issues were in, and {@code sprint_scope_events} goes with them
     * ({@code ON DELETE CASCADE} on {@code sprint_id}). A ledger row can only be wrong
     * about a report that still exists, and after a cascade there is no sprint left to
     * report on. What DOES need an entry is any path that deletes issues while leaving
     * their sprint standing — which is every deletion question 2 can see, because those go
     * through the repository rather than through a parent's FK.
     */
    private static final Map<String, String> UNSCANNABLE_DOORS = new LinkedHashMap<>() {{
        put("IssueService.delete",
                "door 8 — DELETE /issues/{n}. Deleting an issue ends its sprint membership "
                + "WITHOUT WRITING sprint_id, so no scan over the source can see it: there "
                + "is no setSprint(...) and no `SET sprint_id =` to match. Conditional, "
                + "unlike every other door: a COMPLETED sprint's review record is frozen "
                + "and the delete writes nothing (or the actor requireDetachable refuses "
                + "the detach gets its effect by deleting instead), while an ACTIVE "
                + "sprint's scope must come back down — one REMOVED via "
                + "IssueService.recordDepartureOnDelete, whose issue_id the FK then nulls. "
                + "Pinned by SprintScopeLedgerIntegrityTest"
                + ".deletingAnIssueEndsItsMembershipOnlyWhereTheRecordIsNotFrozen.");
    }};

    /** A statement that assigns an issue's sprint through the entity. */
    private static final Pattern ENTITY_DOOR = Pattern.compile("\\.setSprint\\(");

    /**
     * A statement that assigns it in BULK. Both spellings, deliberately: the two bulk
     * doors were JPQL ({@code SET i.sprint =}) and became native
     * {@code UPDATE … RETURNING} ({@code SET sprint_id =}) so their write-list comes out
     * of the statement itself. A scan that only knew the old spelling would have gone
     * quietly blind on exactly the two doors that move hundreds of issues at once.
     */
    private static final Pattern BULK_DOOR =
            Pattern.compile("SET\\s+i\\.sprint\\s*=|SET\\s+sprint_id\\s*=");

    /** Member declarations, for naming the door that a matching line sits in. */
    private static final Pattern CLASS_MEMBER =
            Pattern.compile("^ {4}(?:public|private|protected)\\s+[^=;]*?\\b(\\w+)\\s*\\(");
    private static final Pattern INTERFACE_MEMBER =
            Pattern.compile("^ {4}[\\w<>,\\[\\]\\.\\? ]+\\s+(\\w+)\\s*\\(");

    /**
     * The whole backend, not one package. A {@code .setSprint(…)} in
     * {@code com.hamstrack.project} — a bulk move on project archival, a cleanup job —
     * changes sprint membership exactly as much as one in {@code com.hamstrack.issue}, and
     * nothing about the property is package-local. Scanning the narrower root was a
     * blind spot with no justification behind it.
     */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "hamstrack");

    /**
     * Every source statement that changes an issue's sprint is a registered door.
     *
     * <p>Scans {@link #SOURCE_ROOT} rather than a list of known classes, for the same
     * reason {@code ReportQueryScopeTest} walks its package: a test that must be extended
     * by hand when a door is added is a test the eighth door walks straight past.
     */
    @Test
    void everyDoorOntoSprintMembershipIsRegistered() throws Exception {
        var found = new LinkedHashMap<String, String>();
        var root = SOURCE_ROOT;
        assertThat(Files.isDirectory(root))
                .withFailMessage(() -> "the backend sources are not where this test looks (" + root.toAbsolutePath()
                  + ") — it is guarding nothing")
                .isTrue();
        try (var walk = Files.walk(root)) {
            for (var file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                // The ledger's OWN rows carry a sprint FK (SprintScopeEvent.setSprint).
                // That is the record of a door, not a door.
                if (file.getFileName().toString().equals("SprintScopeLedger.java")
                    || file.getFileName().toString().equals("SprintScopeEvent.java")) {
                    continue;
                }
                var lines = Files.readAllLines(file);
                boolean isInterface = file.toString().contains("repository");
                // Which lines sit INSIDE a text block. The bulk doors are now native SQL
                // in a """ block, and SQL is full of `word (` shapes that read exactly
                // like a method declaration ("AND EXISTS (SELECT …" resolves to a member
                // called EXISTS). The door itself is still matched on those lines — only
                // the walk that NAMES the enclosing member skips them.
                var inSql = textBlockLines(lines);
                for (int i = 0; i < lines.size(); i++) {
                    var line = lines.get(i);
                    if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                        continue;   // javadoc/comment mentions are not statements
                    }
                    if (!ENTITY_DOOR.matcher(line).find() && !BULK_DOOR.matcher(line).find()) {
                        continue;
                    }
                    var type = file.getFileName().toString().replace(".java", "");
                    // In a class the declaration is above the statement; in a repository
                    // interface the @Query annotation sits above its method, so it is below.
                    var member = isInterface
                            ? memberAfter(lines, inSql, i)
                            : memberBefore(lines, i);
                    found.put(type + "." + member, file + ":" + (i + 1) + "  " + line.strip());
                }
            }
        }

        var unregistered = new TreeSet<>(found.keySet());
        unregistered.removeAll(DOORS.keySet());
        assertThat(unregistered)
                .as(() -> """
                You added a door and didn't tell me.

                These statements change an issue's sprint membership but are not in \
                SprintScopeLedgerDoorsTest.DOORS:

                  %s

                Every one of them must (a) route through SprintScopeLedger — never write \
                sprint_scope_events itself, never be called from a controller — and (b) be \
                registered in DOORS with the line that writes the ledger, so the next \
                person can see the whole set in one place.

                If it does not need a ledger row, that is a claim, not an omission: say so \
                in DOORS with the reason. The burn-up's scope line is silently wrong when \
                one of these is missed — no error, no symptom, just a chart that disagrees \
                with reality.""".formatted(String.join("\n  ",
                        unregistered.stream().map(k -> k + "   at " + found.get(k)).toList())))
                .isEmpty();

        var vanished = new TreeSet<>(DOORS.keySet());
        vanished.removeAll(found.keySet());
        assertThat(vanished)
                .as("these registered doors no longer exist in the source — if a door was "
                  + "removed or renamed, update DOORS (and check the ledger call went with "
                  + "it): " + vanished)
                .isEmpty();

        // The exception list is kept honest as far as it CAN be: the member must still
        // exist. Whether it calls the ledger is not checkable here by construction (see
        // UNSCANNABLE_DOORS) and is pinned by SprintScopeLedgerIntegrityTest.
        var overlap = new TreeSet<>(UNSCANNABLE_DOORS.keySet());
        overlap.retainAll(DOORS.keySet());
        assertThat(overlap)
                .as(() -> """
                %s is registered BOTH as a scanned door and as one the scan cannot see. \
                Pick one: if the scan finds it, it belongs in DOORS and is enforced; if it \
                does not, it belongs only in UNSCANNABLE_DOORS and needs a behavioural \
                test named there.""".formatted(overlap))
                .isEmpty();
        for (var entry : UNSCANNABLE_DOORS.entrySet()) {
            var type = entry.getKey().substring(0, entry.getKey().indexOf('.'));
            var member = entry.getKey().substring(entry.getKey().indexOf('.') + 1);
            assertThat(declaresMember(root, type + ".java", member))
                    .withFailMessage(() -> """
                    UNSCANNABLE_DOORS names %s, which no longer exists. It is registered \
                    because the source scan structurally cannot find it (a DELETE ends a \
                    sprint membership without writing sprint_id), so this name is the ONLY \
                    link between the registry and the code — a silent rename would leave \
                    door 8 undocumented and unenforced at once.

                    %s""".formatted(entry.getKey(), entry.getValue()))
                    .isTrue();
        }
    }

    /**
     * A statement that ends a membership by REMOVING the row. {@code IssueRepository}
     * extends {@code JpaRepository<Issue, UUID>}, so {@code delete}, {@code deleteById},
     * {@code deleteAll}, {@code deleteAllInBatch} and {@code deleteAllById} are all
     * reachable from any caller with the repository in hand — one prefix covers them —
     * plus a native {@code DELETE FROM issues} in a {@code @Query}.
     */
    private static final Pattern DELETION_DOOR =
            Pattern.compile("issueRepository\\.delete|DELETE\\s+FROM\\s+issues\\b");

    /**
     * <strong>The second scan.</strong> Door 8 cannot be found by watching writes to
     * {@code issues.sprint_id} — a DELETE writes no column — but that is a statement about
     * ONE pattern shape, not about scanning. A delete has a shape of its own, and this test
     * matches it: every statement that can remove an issue row must resolve to a member
     * registered in {@link #UNSCANNABLE_DOORS}.
     *
     * <p>Two things follow, and the second is the point. It would have found door 8 on its
     * own, without anyone remembering to write it down. And it fails the day a NINTH
     * deletion-shaped path appears — a bulk-delete endpoint, a cleanup service, a retention
     * job — each of which ends sprint memberships silently and each of which needs the same
     * conditional the single-issue delete needed. That turns {@link #UNSCANNABLE_DOORS}
     * from "as good as someone's memory" into a scanned registry of its own; what remains
     * unscannable, and only that, is whether the registered member CALLS the ledger
     * (pinned by {@code SprintScopeLedgerIntegrityTest.deletingAnIssue…}).
     */
    @Test
    void everyDeletionShapedPathOntoSprintMembershipIsRegistered() throws Exception {
        var found = new LinkedHashMap<String, String>();
        assertThat(Files.isDirectory(SOURCE_ROOT))
                .withFailMessage(() -> "the backend sources are not where this test looks (" + SOURCE_ROOT.toAbsolutePath()
                  + ") — it is guarding nothing")
                .isTrue();
        try (var walk = Files.walk(SOURCE_ROOT)) {
            for (var file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                var lines = Files.readAllLines(file);
                boolean isInterface = file.toString().contains("repository");
                var inSql = textBlockLines(lines);
                for (int i = 0; i < lines.size(); i++) {
                    var line = lines.get(i);
                    if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                        continue;   // javadoc/comment mentions are not statements
                    }
                    if (!DELETION_DOOR.matcher(line).find()) continue;
                    var type = file.getFileName().toString().replace(".java", "");
                    var member = isInterface
                            ? memberAfter(lines, inSql, i)
                            : memberBefore(lines, i);
                    found.put(type + "." + member, file + ":" + (i + 1) + "  " + line.strip());
                }
            }
        }

        var unregistered = new TreeSet<>(found.keySet());
        unregistered.removeAll(UNSCANNABLE_DOORS.keySet());
        assertThat(unregistered)
                .as(() -> """
                You added a way to DELETE issues and didn't tell me.

                These statements can remove issue rows — which ends any sprint membership \
                those issues had, writing sprint_id nowhere — but are not in \
                SprintScopeLedgerDoorsTest.UNSCANNABLE_DOORS:

                  %s

                Each one must decide the same thing IssueService.recordDepartureOnDelete \
                decides: an ACTIVE sprint's scope has to come back down (one REMOVED per \
                issue), a COMPLETED sprint's review record is frozen and gets nothing. \
                Register it with that reasoning, and pin the behaviour with a test — no \
                scan can check the call itself.

                A bulk path additionally has to hand the ledger the rows its own statement \
                removed, for the reason recordBulkMove spells out: a separate SELECT is a \
                different snapshot.""".formatted(String.join("\n  ",
                        unregistered.stream().map(k -> k + "   at " + found.get(k)).toList())))
                .isEmpty();

        var vanished = new TreeSet<>(UNSCANNABLE_DOORS.keySet());
        vanished.removeAll(found.keySet());
        assertThat(vanished)
                .as(() -> """
                These deletion-shaped doors are registered but no longer match anything in \
                the source: %s.

                Either the path was removed (delete the entry, and check its ledger call \
                went with it) or it changed shape and DELETION_DOOR no longer sees it — in \
                which case the registry is now enforcing nothing for it.""".formatted(vanished))
                .isEmpty();
    }

    /** Does {@code fileName} under {@code root} declare a member called {@code member}? */
    private static boolean declaresMember(Path root, String fileName, String member) throws Exception {
        try (var walk = Files.walk(root)) {
            for (var file : walk.filter(p -> p.getFileName().toString().equals(fileName)).toList()) {
                for (var line : Files.readAllLines(file)) {
                    if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                        continue;
                    }
                    var m = CLASS_MEMBER.matcher(line);
                    if (m.find() && m.group(1).equals(member)) return true;
                }
            }
        }
        return false;
    }

    // ==================================================================== question 2

    /** One door, as it is actually opened over HTTP. */
    @FunctionalInterface
    private interface DoorRun {
        /**
         * Arranges its own fixture, calls {@code fence} immediately before the ONE request
         * that is the door, and returns the issue ids that request moves.
         */
        List<UUID> run(Ctx ctx, Runnable fence) throws Exception;
    }

    /**
     * @param registryKey the {@link #DOORS} entry this runner exercises. It is what ties
     *                 the two halves of this class together: without it a door could be
     *                 registered with a prose value and no runner at all and BOTH tests
     *                 would pass — the scan is satisfied by the entry, and this test only
     *                 iterates the runners that exist. Asserted for exact set equality in
     *                 {@link #everyRegisteredDoorRoutesThroughTheLedger()}.
     * @param survives whether the door's ledger rows still exist when the request returns.
     *                 False only for the force-delete, whose rows are taken by the
     *                 sprint's {@code ON DELETE CASCADE} — documented in §5.2, and the
     *                 reason this test watches the writer as well as the table.
     */
    private record Door(String registryKey, String name, boolean survives, DoorRun run) {}

    @Test
    void everyRegisteredDoorRoutesThroughTheLedger() throws Exception {
        // The two halves of this class have to name the same set, or registering a door is
        // enough to satisfy BOTH tests: the scan is happy because the entry exists, and the
        // loop below never runs it because no runner was written. A prose value is not an
        // exercise.
        var runners = new TreeSet<>(doors().stream().map(Door::registryKey).toList());
        assertThat(runners)
                .as(() -> """
                DOORS and doors() disagree.

                  registered with no runner: %s
                  runner for nothing registered: %s

                Every entry in DOORS needs a Door that actually opens it over HTTP — \
                otherwise the door is documented and unexercised, which is the state this \
                class exists to make impossible.""".formatted(
                        subtract(DOORS.keySet(), runners), subtract(runners, DOORS.keySet())))
                .isEqualTo(new TreeSet<>(DOORS.keySet()));

        for (var door : doors()) {
            var ctx = newProject();
            var fence = new Fence();

            var moved = door.run().run(ctx, fence::drop);

            var handedToTheLedger = issueIdsHandedToTheLedger();
            var historied = fence.newSprintHistoryIssueIds();

            assertThat(handedToTheLedger)
                    .as(() -> """
                    Door "%s" wrote a `sprint` issue_history row for %s but handed the \
                    ledger %s.

                    Every path that records a membership change in the audit log must record \
                    it in sprint_scope_events too, or the burn-up's scope line silently \
                    disagrees with the issue's own history.""".formatted(
                            door.name(), historied, handedToTheLedger))
                    .containsAll(historied);
            assertThat(handedToTheLedger)
                    .as(() -> """
                    Door "%s" moved %s but the ledger was only told about %s. A door that \
                    records a subset has the right row COUNT and the wrong chart.""".formatted(
                            door.name(), moved, handedToTheLedger))
                    .containsAll(moved);
            assertThat(handedToTheLedger)
                    .as(() -> "door \"" + door.name() + "\" never called SprintScopeLedger at all")
                    .isNotEmpty();

            var written = fence.newLedgerIssueIds();
            if (door.survives()) {
                assertThat(written)
                        .as(() -> """
                        Door "%s" called the ledger for %s but sprint_scope_events only \
                        gained rows for %s — the call happened and the rows did not \
                        (evicted by a clearAutomatically, rolled back, or gated out).""".formatted(
                                door.name(), moved, written))
                        .containsAll(moved);
            } else {
                assertThat(written)
                        .as(() -> """
                        Door "%s" is registered as one whose rows cascade away with the \
                        sprint, but rows for %s survived. Either the FK stopped being \
                        ON DELETE CASCADE or this door no longer deletes the sprint — \
                        both change what past reports say.""".formatted(door.name(), written))
                        .isEmpty();
            }
        }
    }

    private List<Door> doors() {
        return List.of(
                new Door("IssueService.create", "1 · POST /issues carrying sprintId", true,
                        (ctx, fence) -> {
                    var running = startedSprint(ctx, "Sprint 1");
                    fence.run();
                    var issue = createIssue(ctx, "filed straight into the sprint",
                            "\"sprintId\":\"" + running + "\"");
                    return List.of(idOf(issue));
                }),
                new Door("IssueService.update", "2 · PATCH /issues/{n} carrying sprintId", true,
                        (ctx, fence) -> {
                    var running = startedSprint(ctx, "Sprint 1");
                    var issue = createIssue(ctx, "planned in mid-sprint");
                    fence.run();
                    patchIssue(ctx, ctx.token(), numberOf(issue),
                            "{\"sprintId\":\"" + running + "\"}").andExpect(status().isOk());
                    return List.of(idOf(issue));
                }),
                new Door("IssueService.rank", "3 · POST /issues/{n}/rank carrying sprintId", true,
                        (ctx, fence) -> {
                    var running = startedSprint(ctx, "Sprint 1");
                    var issue = createIssue(ctx, "dragged into the sprint");
                    fence.run();
                    rank(ctx, ctx.token(), numberOf(issue),
                            "{\"sprintId\":\"" + running + "\"}").andExpect(status().isOk());
                    return List.of(idOf(issue));
                }),
                new Door("SprintService.addIssues", "4 · POST /sprints/{id}/issues", true,
                        (ctx, fence) -> {
                    var running = startedSprint(ctx, "Sprint 1");
                    var one = createIssue(ctx, "batch one");
                    var two = createIssue(ctx, "batch two");
                    fence.run();
                    addIssuesToSprint(ctx, ctx.token(), running, idOf(one), idOf(two))
                            .andExpect(status().isOk());
                    return List.of(idOf(one), idOf(two));
                }),
                new Door("SprintService.removeIssue", "5 · DELETE /sprints/{id}/issues/{issueId}",
                        true, (ctx, fence) -> {
                    var running = startedSprint(ctx, "Sprint 1");
                    var issue = createIssue(ctx, "pulled back out");
                    addIssuesToSprint(ctx, ctx.token(), running, idOf(issue))
                            .andExpect(status().isOk());
                    fence.run();
                    removeIssueFromSprint(ctx, ctx.token(), running, idOf(issue))
                            .andExpect(status().isNoContent());
                    return List.of(idOf(issue));
                }),
                new Door("IssueRepository.moveUnfinishedOutOfSprint",
                        "6 · sprint completion carry-over (bulk)", true, (ctx, fence) -> {
                    var running = startedSprint(ctx, "Sprint 1");
                    var carried = createIssue(ctx, "not finished");
                    var alsoCarried = createIssue(ctx, "also not finished");
                    addIssuesToSprint(ctx, ctx.token(), running, idOf(carried), idOf(alsoCarried))
                            .andExpect(status().isOk());
                    fence.run();
                    completeToBacklog(ctx, ctx.token(), running).andExpect(status().isOk());
                    return List.of(idOf(carried), idOf(alsoCarried));
                }),
                // The ONLY door whose rows do not survive its own request: the sprint row
                // goes away and takes its ledger with it. It needs a COMPLETED sprint that
                // still holds an issue, which means a DONE one — complete() carries the
                // unfinished ones out first.
                new Door("IssueRepository.clearSprint", "7 · force-delete detach (bulk)", false,
                        (ctx, fence) -> {
                    var running = startedSprint(ctx, "Sprint 1");
                    var delivered = createIssue(ctx, "delivered, then the sprint is deleted");
                    addIssuesToSprint(ctx, ctx.token(), running, idOf(delivered))
                            .andExpect(status().isOk());
                    markDone(ctx, numberOf(delivered));
                    completeToBacklog(ctx, ctx.token(), running).andExpect(status().isOk());
                    fence.run();
                    deleteSprint(ctx, ctx.token(), running, true).andExpect(status().isNoContent());
                    return List.of(idOf(delivered));
                }));
    }

    // ==================================================== the rules the ledger encodes

    /**
     * Commitment is an EVENT: starting a sprint writes one {@code ADDED} per current
     * member, dated the sprint's {@code startAt} rather than the click — a curator may
     * legitimately backdate a start, and the burn-up's x-axis is that date.
     */
    @Test
    void startingASprintCommitsItsMembershipDatedStartAt() throws Exception {
        var ctx = newProject();
        var sprint = createSprint(ctx, "Sprint 1");
        var one = createIssue(ctx, "committed one", "\"storyPoints\":3");
        var two = createIssue(ctx, "committed two");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(one), idOf(two))
                .andExpect(status().isOk());
        var startAt = "2026-03-02T09:00:00Z";

        startSprint(ctx, ctx.token(), sprint,
                "{\"startAt\":\"" + startAt + "\",\"endAt\":\"2026-03-16T09:00:00Z\"}")
                .andExpect(status().isOk());

        var events = eventsOf(sprint);
        assertThat(events).as("expected one ADDED per member, got " + events).hasSize(2);
        for (var e : events) {
            assertThat(e.get("event")).as("the commitment batch is ADDED: " + events).isEqualTo("ADDED");
            assertThat(e.get("occurred_utc"))
                    .as("the commitment must be dated the sprint's start, not the click: " + events)
                    .isEqualTo("2026-03-02T09:00");
        }
        // Points ride along, so a points burn-up needs no second read of today's estimate.
        var points = events.stream().map(e -> e.get("story_points")).toList();
        assertThat(points)
                .as("the estimate at the moment of the event must be recorded (null = unestimated): "
                  + points)
                .containsNull();
        assertThat(points.stream().anyMatch(p -> p != null
                && p.toString().startsWith("3")))
                .withFailMessage("the estimate at the moment of the event must be recorded (null = unestimated): "
                  + points)
                .isTrue();
    }

    /** A second start loses on affected rows (409) and must write NOTHING. */
    @Test
    void aLosingSecondStartWritesNoCommitment() throws Exception {
        var ctx = newProject();
        var sprint = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "committed once");
        addIssuesToSprint(ctx, ctx.token(), sprint, idOf(issue)).andExpect(status().isOk());
        startSprint(ctx, ctx.token(), sprint).andExpect(status().isOk());

        startSprint(ctx, ctx.token(), sprint).andExpect(status().isConflict());

        assertThat(eventsOf(sprint)).as(() -> "a double-start must commit N rows, not 2N: " + eventsOf(sprint)).hasSize(1);
    }

    /**
     * Planning is not scope change. Membership churn on a FUTURE sprint writes nothing;
     * otherwise the start batch would count the same issue twice and every sprint would
     * report a committed scope inflated by exactly the amount of planning the team did.
     */
    @Test
    void membershipChurnBeforeTheStartIsNotAScopeEvent() throws Exception {
        var ctx = newProject();
        var future = createSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "planned, unplanned, replanned");

        addIssuesToSprint(ctx, ctx.token(), future, idOf(issue)).andExpect(status().isOk());
        removeIssueFromSprint(ctx, ctx.token(), future, idOf(issue))
                .andExpect(status().isNoContent());
        addIssuesToSprint(ctx, ctx.token(), future, idOf(issue)).andExpect(status().isOk());
        assertThat(eventsOf(future))
                .as(() -> "a sprint that has not started has no scope to change: " + eventsOf(future))
                .isEmpty();

        startSprint(ctx, ctx.token(), future).andExpect(status().isOk());

        assertThat(eventsOf(future))
                .as(() -> "the start batch is the whole commitment, once per member: " + eventsOf(future))
                .hasSize(1);
    }

    /**
     * Removing then re-adding returns the scope line to its previous level — the ledger's
     * arithmetic (ADDED minus REMOVED) has to come back out even, or the chart drifts.
     */
    @Test
    void removingThenReAddingReturnsTheScopeToItsPreviousLevel() throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "out and back in");
        addIssuesToSprint(ctx, ctx.token(), running, idOf(issue)).andExpect(status().isOk());

        removeIssueFromSprint(ctx, ctx.token(), running, idOf(issue))
                .andExpect(status().isNoContent());
        addIssuesToSprint(ctx, ctx.token(), running, idOf(issue)).andExpect(status().isOk());

        var kinds = eventsOf(running).stream().map(e -> e.get("event")).toList();
        assertThat(kinds).as("unexpected ledger: " + kinds).isEqualTo(List.of("ADDED", "REMOVED", "ADDED"));
        assertThat(scopeOf(running)).as(() -> "scope must return to 1, was " + scopeOf(running)).isEqualTo(1);
    }

    /** A re-estimate is not a membership change, so it is not a scope event (§2.3). */
    @Test
    void changingStoryPointsIsNotAScopeEvent() throws Exception {
        var ctx = newProject();
        var running = startedSprint(ctx, "Sprint 1");
        var issue = createIssue(ctx, "re-estimated mid-sprint", "\"storyPoints\":2");
        addIssuesToSprint(ctx, ctx.token(), running, idOf(issue)).andExpect(status().isOk());
        int before = eventsOf(running).size();

        patchIssue(ctx, ctx.token(), numberOf(issue), "{\"storyPoints\":8}")
                .andExpect(status().isOk());

        assertThat(eventsOf(running))
                .as(() -> "a re-estimate must change the scope line's HEIGHT, never step it: "
                  + eventsOf(running))
                .hasSize(before);
    }

    // Door 8 (DELETE /issues/{n}) is asserted by SprintScopeLedgerIntegrityTest, not
    // here: it is the door this class's source scan cannot see (a DELETE never writes
    // sprint_id — see UNSCANNABLE_DOORS), and it is the only conditional one. Existing
    // rows SURVIVE with issue_id NULL, because a cascade would let anyone re-draw a
    // completed sprint's review by deleting an issue — the very edit requireDetachable
    // refuses (422) through the API; a REMOVED is appended only where nothing is frozen.

    // ==================================================================== plumbing

    /**
     * The moment a door is opened: everything written after this belongs to the door, and
     * everything before it was fixture. Diffing row IDS rather than fencing on a timestamp
     * — clock resolution is not a thing a parity test should depend on.
     */
    private final class Fence {
        private Set<UUID> historyBefore = Set.of();
        private Set<UUID> eventsBefore = Set.of();

        void drop() {
            historyBefore = idsOf("SELECT id FROM issue_history WHERE field = 'sprint'");
            eventsBefore = idsOf("SELECT id FROM sprint_scope_events");
            clearInvocations(ledger);
        }

        Set<UUID> newSprintHistoryIssueIds() {
            return issueIdsOf("SELECT issue_id, id FROM issue_history WHERE field = 'sprint'",
                    historyBefore);
        }

        Set<UUID> newLedgerIssueIds() {
            return issueIdsOf("SELECT issue_id, id FROM sprint_scope_events", eventsBefore);
        }
    }

    /**
     * The issue ids handed to the single writer, whichever of its methods was used —
     * read off the spy's invocations rather than off a per-method captor, so a future
     * eighth ledger method is covered the day it is added.
     */
    private Set<UUID> issueIdsHandedToTheLedger() {
        var ids = new LinkedHashSet<UUID>();
        for (var invocation : mockingDetails(ledger).getInvocations()) {
            for (var arg : invocation.getArguments()) {
                if (arg instanceof Issue issue) {
                    ids.add(issue.getId());
                } else if (arg instanceof List<?> rows) {
                    for (var row : rows) {
                        if (row instanceof Object[] ref && ref.length > 0 && ref[0] instanceof UUID id) {
                            ids.add(id);
                        }
                    }
                }
            }
        }
        return ids;
    }

    /**
     * One sprint's ledger, oldest first, as plain column maps. {@code occurred_at} is
     * rendered by PostgreSQL in UTC rather than handed to the driver: a {@code timestamptz}
     * comes back as a {@code java.sql.Timestamp} whose {@code toString} is the JVM's
     * default zone, which would make "was this dated the sprint's start" pass or fail
     * depending on where the build runs.
     */
    private List<Map<String, Object>> eventsOf(UUID sprintId) {
        return jdbc.queryForList(
                "SELECT event, story_points, issue_id, actor_id, "
                + "       to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI') AS occurred_utc "
                + "FROM sprint_scope_events WHERE sprint_id = ? ORDER BY occurred_at, created_at",
                sprintId);
    }

    /** {@code count(ADDED) - count(REMOVED)} — "scope now", the way a report computes it. */
    private int scopeOf(UUID sprintId) {
        Integer scope = jdbc.queryForObject(
                "SELECT coalesce(sum(CASE WHEN event = 'ADDED' THEN 1 ELSE -1 END), 0) "
                + "FROM sprint_scope_events WHERE sprint_id = ?", Integer.class, sprintId);
        return scope == null ? 0 : scope;
    }

    private Set<UUID> idsOf(String sql) {
        return new LinkedHashSet<>(jdbc.queryForList(sql, UUID.class));
    }

    private Set<UUID> issueIdsOf(String sql, Set<UUID> excludedRowIds) {
        var out = new LinkedHashSet<UUID>();
        for (var row : jdbc.queryForList(sql)) {
            if (!excludedRowIds.contains((UUID) row.get("id"))) {
                out.add((UUID) row.get("issue_id"));
            }
        }
        return out;
    }

    /** {@code a} minus {@code b}, sorted — only ever used to name a set difference. */
    private static Set<String> subtract(Set<String> a, Set<String> b) {
        var out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static String memberBefore(List<String> lines, int index) {
        for (int i = index; i >= 0; i--) {
            var m = CLASS_MEMBER.matcher(lines.get(i));
            if (m.find()) return m.group(1);
        }
        return "<unknown member above line " + (index + 1) + ">";
    }

    private static String memberAfter(List<String> lines, boolean[] inSql, int index) {
        for (int i = index; i < lines.size(); i++) {
            if (inSql[i]) continue;
            var m = INTERFACE_MEMBER.matcher(lines.get(i));
            if (m.find()) return m.group(1);
        }
        return "<unknown member below line " + (index + 1) + ">";
    }

    /**
     * {@code true} for every line strictly inside a {@code """} text block — the SQL of a
     * native {@code @Query}, which must not be mistaken for Java when naming a member.
     */
    private static boolean[] textBlockLines(List<String> lines) {
        var inside = new boolean[lines.size()];
        boolean open = false;
        for (int i = 0; i < lines.size(); i++) {
            boolean opensHere = lines.get(i).contains("\"\"\"");
            inside[i] = open && !opensHere;
            if (opensHere) open = !open;
        }
        return inside;
    }

}
