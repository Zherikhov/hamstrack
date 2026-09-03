package com.hamstrack.issue;

import org.junit.jupiter.api.Test;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Every call site that hands bytes to {@code FileStorage} is preceded, in the same METHOD,
 * by a workspace quota reservation and a per-principal byte spend</strong> (HD-191 §4.3 item 5,
 * AC-20).
 *
 * <h2>Why this cannot be a path binding, and therefore cannot live in ThrottleCoverageTest</h2>
 * {@code ThrottleCoverageTest} seals the set of PATH PATTERNS the budget configurers register, and
 * it is the right shape for three of the four per-principal budgets: a new report under
 * {@code …/reports/**} inherits its bound the moment its mapping exists. Neither of this ticket's
 * two storage controls can be expressed that way.
 *
 * <ul>
 *   <li>The <strong>upload-byte budget</strong>'s cost is {@code MultipartFile.getSize()}. An
 *       interceptor runs before argument resolution — it has neither the parsed part nor a reason
 *       to look at one.</li>
 *   <li>The <strong>quota reservation</strong> needs a RESOLVED workspace, and it has to run
 *       inside the same short transaction that resolves tenancy and persists the row, because that
 *       is what makes "no byte of a refused upload ever reaches {@code FileStorage}" true by
 *       construction rather than by ordering luck.</li>
 * </ul>
 *
 * <p>The invitation ceilings are the existing precedent for a limiter that is deliberately not a
 * path binding, and {@code AuthMailDoorsTest} is the shape this file copies: <em>a sealed set of
 * call sites, asserted down to the enclosing method</em>.
 *
 * <h2>Why METHOD granularity and not file</h2>
 * {@code AuthMailDoorsTest} learned this one the hard way: all three anonymous auth flows live in
 * one file, so a file-granular seal watched the disclosing call move from {@code register} into
 * {@code forgotPassword} — the exact leak it existed to refuse — and reported the identical list.
 * Here the same applies with a sharper edge: {@code AttachmentService} already contains
 * {@code upload}, {@code download}, {@code delete} and {@code removeStoredFilesForIssue}, and
 * "somewhere in this file there is a quota check" is true of a class that stores an unmetered blob
 * in a different method.
 *
 * <h2>What "preceded" means here and what it does not</h2>
 * This is a source-text seal, so it asserts CO-LOCATION and ORDER OF APPEARANCE, not runtime
 * ordering — a crude parser cannot prove a branch is taken. That is enough for what it is guarding:
 * the failure it exists to catch is a second upload path written without the two lines at all,
 * which is a two-line omission nobody would notice because the happy path is identical either way.
 * The runtime guarantee — {@code store} is never invoked for a refused upload — is a behavioural
 * test ({@code StorageQuotaTest}, with a {@code FileStorage} double), and neither stands in for the
 * other: this one stops a NEW door being built without the checks, that one stops the checks being
 * wrong on the door that exists.
 */
class AttachmentDoorsTest {

    /** What hands bytes to the store. Matched on the call text, in {@code src/main/java}. */
    private static final String STORE_CALL = "fileStorage.store(";

    /** The per-workspace ceiling. */
    private static final String QUOTA_RESERVATION = "workspaceStorage.reserve(";

    /** The per-principal byte budget. */
    private static final String BYTE_SPEND = "uploadByteBudget.require(";

    private static final String WHAT_TO_DO = """

            A CALL SITE THAT HANDS BYTES TO FileStorage WITHOUT BOTH STORAGE CONTROLS IN THE SAME \
            METHOD.

            fileStorage.store(...) is the moment a workspace's bytes become the operator's disk \
            (dc) or the operator's bill (cloud, where S3 charges per byte stored AND per request \
            made). Two controls stand in front of it and they bound different things — neither is \
            a substitute for the other:

              workspaceStorage.reserve(workspaceContext, size)  bounds the TENANT's cumulative \
            bytes. State in PostgreSQL, cluster-wide and exact, no window, never resets. It must \
            be called INSIDE the transaction that resolves tenancy and persists the row, and \
            BEFORE the save — that ordering is what makes "no byte of a refused upload ever \
            reaches FileStorage" provable rather than lucky. It takes a WorkspaceContext and not \
            an id, so "keyed on the resolved workspace, never on the one in the URL" is a fact \
            about the signature rather than a rule to remember: reading the wrong tenant's total \
            is worse than having no quota, and it would also put that tenant's aggregate in the \
            409 body.

              uploadByteBudget.require(userId, size)       bounds the ACTOR's byte RATE. In \
            memory, per node, per minute. It exists because the quota never sees churn — \
            upload -> delete -> upload leaves the workspace total exactly where it started while \
            billing every PUT in between — and because a request budget does not bound bytes. \
            Spend it in the CHEAP pre-check phase, before any DB work, so a refused upload takes \
            no lock and touches no row. That places it before tenancy resolution, which is safe \
            only because its key is the CALLER: the 429 is identical for a real workspace, a \
            nonexistent one and somebody else's, so the 404-for-all-three contract holds. Do not \
            copy that reasoning to anything keyed on a victim.

            Neither is a path binding, which is why this seal exists at all: no interceptor can \
            see MultipartFile.getSize(), and no interceptor has a resolved workspace. If you have \
            written a second upload door, add both lines to it. If you genuinely need to write a \
            blob with no tenant and no actor — an operator-triggered export, a migration — that \
            is a different decision and it needs its own entry here with a written reason, not a \
            quiet omission.""";

    @Test
    void everyStoreCallSiteIsPrecededByAQuotaReservationAndAByteSpend() throws IOException {
        var callSites = enclosingMethodsCalling(STORE_CALL);

        assertThat(callSites)
                .as("no source file calls %s at all, so this seal is guarding an empty set — the "
                    + "method was renamed and this test did not move with it", STORE_CALL)
                .isNotEmpty();

        var missing = new LinkedHashMap<String, String>();
        for (var site : callSites.entrySet()) {
            var body = site.getValue();
            var absent = new ArrayList<String>();
            if (!body.contains(QUOTA_RESERVATION)) {
                absent.add(QUOTA_RESERVATION);
            }
            if (!body.contains(BYTE_SPEND)) {
                absent.add(BYTE_SPEND);
            }
            if (!absent.isEmpty()) {
                missing.put(site.getKey(), String.join(" and ", absent));
            }
        }

        assertThat(missing).as(WHAT_TO_DO).isEmpty();
    }

    /**
     * <strong>And the order of appearance is reservation-then-store, not the other way round.</strong>
     *
     * <p>A method containing both lines in the wrong order passes the assertion above and delivers
     * the opposite guarantee: the bytes are already in the store when the quota says no, so the
     * refusal costs the operator exactly what it was written to prevent and leaves an object with
     * no row. Cheap to assert, and it is the mistake a copied call site makes.
     */
    @Test
    void theControlsAppearBeforeTheStoreCallTheyGuard() throws IOException {
        var offenders = new ArrayList<String>();
        for (var site : enclosingMethodsCalling(STORE_CALL).entrySet()) {
            var body = site.getValue();
            int store = body.indexOf(STORE_CALL);
            for (var control : List.of(QUOTA_RESERVATION, BYTE_SPEND)) {
                int at = body.indexOf(control);
                if (at >= 0 && at > store) {
                    offenders.add(site.getKey() + " calls " + control + " AFTER " + STORE_CALL);
                }
            }
        }

        assertThat(offenders)
                .as("""
                    A STORAGE CONTROL THAT RUNS AFTER THE BYTES ARE ALREADY STORED.

                    Both lines being present is not the property; both being present BEFORE the \
                    store call is. A quota refused after fileStorage.store has already run costs \
                    the operator exactly what the quota exists to prevent, and leaves an object \
                    with no row — invisible to the counter, still billed by the store, and \
                    reachable only through the orphan runbook in docs/self-hosting.md.""" + WHAT_TO_DO)
                .isEmpty();
    }

    /**
     * <strong>The tripwire under the two seals above: the set of classes that hold a
     * {@link com.hamstrack.common.storage.FileStorage} at all.</strong>
     *
     * <p>Both assertions above match on the text {@code fileStorage.store(}, i.e. on a FIELD NAME.
     * That is precise about today's one door and blind to a second one that names its field
     * {@code storage} or calls the interface through a parameter — which is a rename away, and
     * would leave the seal green while guarding nothing.
     *
     * <p>So the injection points are sealed as a set instead. A new class that holds a
     * {@code FileStorage} fails HERE, at the moment it is written, and its author reads the
     * checklist before rather than after. The two implementations are excluded because they ARE
     * the storage; anything else is a door.
     */
    @Test
    void onlyTheAttachmentServiceHoldsAFileStorage() throws IOException {
        var holders = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(Path.of("src", "main", "java"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                var file = path.getFileName().toString();
                var type = file.substring(0, file.length() - ".java".length());
                if (STORAGE_IMPLEMENTATIONS.contains(type)) {
                    continue;
                }
                for (var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    var code = line.trim();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("import ")) {
                        continue;
                    }
                    if (code.matches(".*\\bFileStorage\\s+\\w+\\s*[;,)].*")) {
                        holders.add(type);
                        break;
                    }
                }
            }
        }

        assertThat(holders)
                .as("""
                    A NEW HOLDER OF FileStorage — i.e. a second door to the blob store.

                    The two seals in this file match on the text "fileStorage.store(", which is a \
                    FIELD NAME, so a class that injects the interface under another name would \
                    pass them while handing bytes to the store with no quota and no byte budget. \
                    This assertion is what stands in front of that.

                    If the new holder only READS or DELETES blobs, add it to the expected list \
                    below and say so — reads and downloads are deliberately never quota-gated, and \
                    a full workspace must stay fully readable. If it WRITES, it is an upload door: \
                    give it both controls and it will satisfy the seals above.""" + WHAT_TO_DO)
                .containsExactly("AttachmentService");
    }

    /**
     * The {@code FileStorage} implementations themselves, which hold no door — they ARE the store.
     * {@code FileStorage} is the interface; the other two are the {@code @ConditionalOnProperty}
     * beans behind {@code app.storage.type}.
     */
    private static final Set<String> STORAGE_IMPLEMENTATIONS =
            Set.of("FileStorage", "LocalFileStorage", "S3FileStorage");

    // ------------------------------------------------------------------ source reading

    /**
     * {@code Class.method -> the source text of that method}, for every method under
     * {@code src/main/java} that calls {@code needle}.
     *
     * <p>Read out of the source tree rather than by reflection, for {@code AuthMailDoorsTest}'s
     * reason: a call site is a fact about code, not about bytecode this test can enumerate. The
     * parser is the same deliberately crude one — the last line before the call that looks like a
     * method declaration at four spaces of indentation — because it only has to be right about the
     * shape this codebase actually writes, and it is wrapped in an assertion that fails loudly if
     * it finds nothing at all.
     *
     * <p>Lambda bodies attribute to their enclosing method, which is exactly right here: the quota
     * reservation legitimately lives inside the {@code txTemplate.execute(...)} lambda of the same
     * method that later calls {@code store}.
     */
    private static Map<String, String> enclosingMethodsCalling(String needle) throws IOException {
        // EXACTLY four spaces, asserted with a lookahead rather than left to the `^ {4}` prefix.
        // AuthMailDoorsTest's copy of this pattern relies on a keyword deny-list instead, and that
        // is not enough here: its character class contains \s, so a line indented DEEPER still
        // matches with the extra indentation absorbed — which made `return new
        // ReservedAttachment(...)`, twelve spaces in and seven lines above the store call, parse as
        // a method declaration and swallow the rest of upload(). The deny-list cannot catch that,
        // because the name it captures is a real type name and not a keyword. Indentation is what
        // actually distinguishes a declaration from a call in this codebase's style, so it is
        // asserted directly; the deny-list stays as the belt for a method legitimately sitting at
        // four spaces inside a nested class.
        var declaration = Pattern.compile(
                "^ {4}(?=\\S)(?:public|protected|private)?\\s*(?:static\\s+)?"
                + "(?:final\\s+)?[\\w.<>\\[\\],?\\s]+\\s(\\w+)\\s*\\([^;]*$");
        var notAMethodName = Set.of("if", "for", "while", "switch", "catch", "return", "new",
                "synchronized", "assert", "throw", "else", "do", "try");

        var bodies = new LinkedHashMap<String, StringBuilder>();
        var hits = new LinkedHashMap<String, String>();
        try (Stream<Path> paths = Files.walk(Path.of("src", "main", "java"))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                var file = path.getFileName().toString();
                var type = file.substring(0, file.length() - ".java".length());
                var enclosing = "<file scope>";
                bodies.clear();
                for (var line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    var matcher = declaration.matcher(line);
                    if (matcher.find() && !line.trim().startsWith("*")
                        && !notAMethodName.contains(matcher.group(1))) {
                        enclosing = matcher.group(1);
                    }
                    var code = line.trim();
                    // A javadoc/comment mention is not a call site. Anything else carrying the name
                    // is treated as one — over-reporting fails the seal, which is the safe
                    // direction.
                    if (code.startsWith("*") || code.startsWith("//")) {
                        continue;
                    }
                    bodies.computeIfAbsent(type + "." + enclosing, k -> new StringBuilder())
                            .append(code).append('\n');
                }
                bodies.forEach((name, body) -> {
                    if (body.indexOf(needle) >= 0) {
                        hits.put(name, body.toString());
                    }
                });
            }
        }
        return hits;
    }
}
