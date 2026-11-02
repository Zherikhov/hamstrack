package com.hamstrack.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamstrack.admin.dto.UpsertFieldRequest;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.admin.service.AdminFieldService;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.testsupport.FixtureRows;
import com.hamstrack.common.testsupport.LogRecords;
import com.hamstrack.common.testsupport.SourceCallSites;
import com.hamstrack.common.testsupport.Unique;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.FieldDefRepository;
import com.hamstrack.search.ShadowedFieldStartupScan;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>A log record that promises anything to a machine is built only from values Hamstrack
 * minted</strong> (HD-341).
 *
 * <p>Two records in this tree carry a prefix that {@code docs/observability.md} publishes as
 * machine-readable, and both used to interpolate text a database <em>row</em> supplied into the
 * message, inside {@code '…'} delimiters. A display name that closes that quote and continues in
 * the template's own grammar produces one syntactically plausible record naming a workspace the row
 * does not belong to — on every boot, for ever, because the population exists precisely to hold
 * rows that arrive by a migration, a seeder or direct SQL and that nothing ever rewrites. Nothing
 * leaks; what breaks is <strong>attribution</strong>, and the remedy the record prescribes is a
 * {@code PATCH} against a field id at a scope.
 *
 * <h2>The rule, and why it is phrased over a class of records</h2>
 * <blockquote>A log record that promises anything to a machine is built only from values Hamstrack
 * minted. A value it did not mint is carried in an SLF4J key-value pair, never interpolated into
 * the message.</blockquote>
 *
 * <p>Three definitions make that decidable, and each is a property rather than a list. <em>Minted</em>
 * — bytes this codebase produced: a UUID, an enum constant, a count, a string literal, a name taken
 * from {@code FieldRegistry}. <em>Foreign</em> — everything else, a {@code varchar} read back from a
 * row included. <em>Promises something to a machine</em> — the record's prefix is listed in
 * {@code docs/observability.md}, which is the only machine-readable convention this tree has ever
 * published.
 *
 * <p><strong>The complement is half the contract.</strong> A record whose prefix is <em>not</em>
 * listed promises nothing: its message is prose, it may contain text a row supplied, and no
 * extractor may key on it. That sentence is what keeps the enforced population small without making
 * the smallness a coincidence — a template that merely happens to start with a word and a colon is
 * not a promise. {@link #everyPrefixedRecordInTheTreeCarriesAWrittenVerdict()} is what stops a
 * record joining the tier quietly, in the only way it can be joined from inside this repository.
 *
 * <p>Why the quote is not the unit: escaping {@code '} would answer this record and leave the next
 * convention ({@code (}, {@code k=}, "the rest of the line") open. The remedy instead moves the
 * foreign values somewhere whose escaping is performed by a mechanism we neither own nor document —
 * the JSON encoder, MEASURED below to escape {@code "}, {@code \} and newlines in a member value.
 * The full escaping table, including the two separators it passes through <em>raw</em>, is measured
 * in {@code LogRecordsTest}.
 *
 * <h2>What each arm does, and why they are not one assertion in seven costumes</h2>
 * <ul>
 *   <li><strong>A1/A3</strong> — behavioural, one per changed member: plant the forgery, run the
 *       real writer, read the real event.</li>
 *   <li><strong>A2</strong> — the structural guarantee through the production encoder, which is
 *       what makes the contract's promise a measurement rather than a sentence. Since the HD-341
 *       security round that encode is {@link com.hamstrack.common.testsupport.LogRecords}, run on
 *       <em>every</em> published record this suite captures — here, in A3, in A7 and in
 *       {@code ShadowedFieldStartupScanTest} — because it was the one check nothing could
 *       hand-maintain into staleness and it reached exactly one of the three records.</li>
 *   <li><strong>A4</strong> — the population cannot grow in silence: every candidate in the source
 *       needs a written verdict here.</li>
 *   <li><strong>A5</strong> — key hygiene, with the consequence of getting it wrong measured in
 *       this run rather than remembered.</li>
 *   <li><strong>A6</strong> — the published contract is derived from the code, and the
 *       operator-facing example moves with the format.</li>
 *   <li><strong>A7</strong> — the published {@code field.scope} grammar has three forms and all
 *       three are exercised, the global one against a real row.</li>
 * </ul>
 *
 * <h2>The negative control, as observed and not as predicted</h2>
 * The defect cannot be planted without restoring the interpolating template: a forged row against
 * today's code leaves A1 and A2 green, which is the change working. So the control is to put the
 * display name and the key back inside {@code '…'} in {@code ShadowedFieldStartupScan}'s template
 * and plant the forged row. MEASURED 2026-11-01, over the file as it stood that day —
 * <strong>red: A1, A2, A4 and A6</strong>; <strong>green: A5 and both M2 arms</strong>
 * ({@link #aRenameRecordCarriesBothKeysInMembersAndNeitherInItsMessage()} and
 * {@link #aRenameIsRefusedUnlessTheOldKeyIsARegistryClaimedName()}). The run total that
 * accompanied it is deliberately not restated: the file has grown an arm since, and a total
 * beside a verdict list goes stale one arm before the list does.
 *
 * <p><strong>The security round added a second control, for the loss mode this file names rather
 * than the forgery it closes</strong> (2026-11-02). Planting a duplicated
 * {@code addKeyValue("field.name")} in {@code ShadowedFieldStartupScan#scan} reds <strong>A2, A4,
 * A7</strong> and three arms of {@code ShadowedFieldStartupScanTest}, and leaves <strong>A1
 * green</strong> — which is the finding, not a nuisance: {@link #pairs} is a map and a map cannot
 * see a repeated key. A4's offender list then held only the duplicate entry and no
 * member-mismatch entry, which is the direct measurement that {@code Set.copyOf} stays satisfied
 * with the defect present. Planting a repeated {@code field.id} in {@link #RENAME_MEMBERS} instead
 * reds <strong>A5</strong> and A3.
 *
 * <p>Two of those four are worth naming, because the first draft of this paragraph predicted both
 * of them green and was wrong. <strong>A4</strong> reds because its ledger key is the owning method
 * plus the template, and this defect cannot be planted without changing the template.
 * <strong>A6</strong> reds for a related reason and deliberately: it resolves each published
 * prefix through that same ledger, so a stale ledger makes the published table unverifiable rather
 * than verified — loud in the direction that matters. What the plant really demonstrates is
 * therefore not "two arms of five" but <em>member</em> independence: the M1 defect reds every arm
 * that reads M1 and leaves M2's two arms untouched, which is the property a one-assertion-in-five-
 * costumes seal would not have.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
class ForgeableLogRecordTest {

    @Autowired ShadowedFieldStartupScan scan;
    @Autowired AdminFieldService fieldService;
    @Autowired FieldDefRepository fieldDefRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    /**
     * The members each published prefix carries — <strong>declared once and compared three
     * ways</strong>: against the keys the source attaches (A4), against the keys the running code
     * emitted (A1/A3), and against the reserved names of the JSON schema (A5). A list that agreed
     * with only one of the three would be a comment.
     */
    private static final List<String> SCAN_MEMBERS =
            List.of("field.id", "field.scope", "field.key", "field.name", "field.builtin");

    private static final List<String> RENAME_MEMBERS =
            List.of("field.id", "field.scope", "field.key.old", "field.key.new", "actor.id");

    /**
     * The per-row record that cannot name the built-in field, which is {@link #SCAN_MEMBERS}
     * minus the one member it has no value for. A subset and not a second contract: the published
     * table lists the union per prefix, and A6 compares it to exactly that.
     */
    private static final List<String> DEGRADED_SCAN_MEMBERS =
            List.of("field.id", "field.scope", "field.key", "field.name");

    /**
     * Every name the logstash schema writes itself, plus the two static adds both shipped profiles
     * configure. READ: {@code LogstashStructuredLogFormatter.jsonMembers} in spring-boot 4.1.0, and
     * {@code logging.structured.json.add.*} in {@code application-{dc,cloud}.properties}. MDC would
     * belong here too; this tree calls {@code MDC.put} nowhere (MEASURED 2026-11-01).
     */
    private static final Set<String> RESERVED_MEMBERS = Set.of("@timestamp", "@version", "message",
            "logger_name", "thread_name", "level", "level_value", "tags", "stack_trace",
            "application", "deployment");

    /**
     * Rows this class planted in {@code field_defs} — a live claimed-key row is a WARN for ever,
     * on an instance-wide reader, so the scope column does not make it this run's private business.
     *
     * <p>The census is the half that cannot be forgotten quietly: a row created without being
     * recorded deletes nothing, asserts nothing and passes, which is how one survivor of a red run
     * of the sibling file was still on the development database today.
     */
    private final FixtureRows planted = new FixtureRows();

    @BeforeEach
    void countFieldDefsBefore() {
        planted.watch(fieldDefRepository, "field_defs");
    }

    @AfterEach
    void removeWhatWasPlanted() {
        planted.deleteAll();
    }

    // ===================================================================== A1

    /**
     * <strong>A1 — M1, the startup scan: a forged display name cannot reach the message.</strong>
     *
     * <p>The planted name embeds a <em>second, real</em> workspace's uuid, because the harm this
     * change is about is mis-attribution and not leakage: the author of the row owns every byte of
     * it, and what the forgery buys is a plausible record pointing a reader at a tenant and a field
     * id that are not the row's. So the assertion is specifically that the other workspace's uuid
     * is not in the message, and the planted text is not in the message, and both are in the
     * members where the encoder can be relied on.
     *
     * <p>The row is written straight through the repository because that is <em>how this population
     * came to exist</em> — the reserved-key guard refuses a claimed key only at the doors that mint
     * one, and the affected rows predate it or never met it.
     */
    @Test
    void aForgedDisplayNameNeverReachesTheStartupRecordsMessage() {
        var plant = plantAForgedDisplayName();
        var record = theRecordNaming(plant.def().getId());

        assertThat(record.getFormattedMessage())
                .as("""
                        the record must not name a workspace the row does not belong to. This is \
                        the whole defect: the display name used to sit inside '…' in the message, \
                        so a name that closed the quote continued in the template's grammar.""")
                .doesNotContain(plant.otherWorkspaceId().toString())
                .doesNotContain(plant.forged().replace("\n", " "))
                .as("what stays in the message is minted, and is everything needed to act")
                .contains("shadowed-field-def:")
                .contains(plant.def().getId().toString())
                .contains("workspace " + plant.workspaceId())
                .contains("rename its key");
        assertThat(pairs(record))
                .as("carried verbatim, line terminators flattened — nothing is hidden from the "
                    + "operator who has to find the field")
                .containsEntry("field.name", plant.forged().replace("\n", " "))
                .containsEntry("field.key", "labels")
                .containsEntry("field.id", plant.def().getId());
        assertThat(pairs(record).keySet())
                .as("the members the contract in docs/observability.md names, emitted by the "
                    + "running code")
                .containsExactlyInAnyOrderElementsOf(SCAN_MEMBERS);
    }

    // ===================================================================== A2

    /**
     * <strong>A2 — the structural guarantee, asserted through the production encoder.</strong>
     *
     * <p>A1 reads the event; this reads the bytes an instance ships. Both shipped profiles set
     * {@code logging.structured.format.console=logstash}, so this encoder is what every deployment
     * writes. MEASURED here, in this run: the forgery lands in one JSON member, escaped, and cannot
     * close it, open a second one or nest an object.
     *
     * <p><strong>The encode is {@link LogRecords#oneJsonObject} and not a copy of it here</strong>,
     * because this is no longer the only arm that performs it: the rule says <em>every</em>
     * published record is built from minted values, and until the HD-341 security round exactly one
     * of the three published records was ever handed to the real encoder. Encoding is the one
     * operation that catches a duplicate member name, a collision with the schema's own names and
     * an MDC collision together, against the keys the running code really attached — see
     * {@code LogRecords} for the measurements and for the three it cannot catch.
     */
    @Test
    void theEncodedRecordIsOneJsonObjectAndTheForgeryIsOneEscapedMember() {
        var plant = plantAForgedDisplayName();
        var record = theRecordNaming(plant.def().getId());

        var json = LogRecords.oneJsonObject(record);

        assertThat(json.get("message").asText())
                .doesNotContain(plant.otherWorkspaceId().toString());
        assertThat(json.get("field.name").asText())
                .as("byte-for-byte, in a member of its own — the encoder escapes \", \\ and the "
                    + "newline, so no value can imitate the record's structure")
                .isEqualTo(plant.forged().replace("\n", " "));
        assertThat(json.fieldNames()).toIterable()
                .as("the key is emitted verbatim as a top-level member, which is what lets a Loki "
                    + "rule read `| json | field_id=...` with no ops change")
                .containsAll(SCAN_MEMBERS);
    }

    // ===================================================================== A3

    /**
     * <strong>A3 — M2, the rename record: neither key reaches the message.</strong>
     *
     * <p>The old key is planted as {@code Labels} — a case variant, which is genuinely the
     * <em>row's</em> bytes and not ours, since {@code field_defs.key} is {@code varchar(50)} with no
     * CHECK constraint (MEASURED 2026-11-01) and the registry lookup that licenses the rename is
     * case-insensitive.
     *
     * <p><strong>A forged old key cannot be planted here, and that is a finding rather than a
     * workaround</strong> (the spec's premise I-3, refuted in this run). {@code renameTo} refuses
     * unless the <em>current</em> key is a {@code FieldRegistry}-claimed name, so the reachable
     * values are case variants of a thirty-element minted set — no quote, no space, no newline can
     * get in. {@link #aRenameIsRefusedUnlessTheOldKeyIsARegistryClaimedName()} asserts that gate
     * instead of this javadoc asserting it in prose, because an exclusion excused by prose outlives
     * its subject. The record is still built the same way: it carries one rule rather than two, the
     * column it prints is unbounded, and a gate is not a guarantee about a column.
     */
    @Test
    void aRenameRecordCarriesBothKeysInMembersAndNeitherInItsMessage() {
        var ws = workspace();
        var actor = user();
        var def = plant(ws, "Labels", "Team labels");

        var events = capture(AdminFieldService.class, () -> fieldService.updateField(actor,
                ScopeContext.workspace(ws), def.getId(),
                new UpsertFieldRequest("Team labels", "team_labels", FieldType.TEXT, null, null)));
        var record = events.stream()
                .filter(e -> e.getFormattedMessage().startsWith("field-key-rename:"))
                .toList();

        assertThat(record).as("exactly one record names this rename").hasSize(1);
        assertThat(record.getFirst().getFormattedMessage())
                .as("""
                        neither key is interpolated. The old one is the column's own bytes, and a \
                        forged tail there could re-point the "to '…' at scope … by user …" that \
                        follows it.""")
                .doesNotContain("Labels")
                .doesNotContain("team_labels")
                .contains(def.getId().toString())
                .as("""
                        the id-bearing scope form, which is the one grammar field.scope has on \
                        every record that carries it. This record used to say "WORKSPACE" and \
                        name no tenant at all, so a rule routing a finding needed a second \
                        lookup, and a rule reading field.scope got a uuid from one published \
                        prefix and a label from the other.""")
                .contains("workspace " + ws)
                .contains(actor.getId().toString());
        assertThat(pairs(record.getFirst()))
                .containsEntry("field.key.old", "Labels")
                .containsEntry("field.key.new", "team_labels")
                .containsEntry("field.scope", "workspace " + ws)
                .containsEntry("field.id", def.getId())
                .containsEntry("actor.id", actor.getId());
        assertThat(pairs(record.getFirst()).keySet())
                .containsExactlyInAnyOrderElementsOf(RENAME_MEMBERS);
        // Through the production encoder, exactly as A2 does for M1. Before the HD-341 security
        // round this record — and the degraded one in ShadowedFieldStartupScanTest — were pinned
        // on their member NAMES only, by a reader that collapses a duplicated name; neither had
        // ever been handed to the encoder that decides whether an instance ships them at all.
        assertThat(LogRecords.oneJsonObject(record.getFirst()).get("field.key.old").asText())
                .isEqualTo("Labels");
    }

    /**
     * <strong>The gate A3's reachability argument rests on, asserted rather than written down.</strong>
     *
     * <p>If this reds, a row whose key carries a quote can reach {@code field-key-rename:} — which
     * is survivable only because the keys now travel in members. It is here so the sentence in
     * A3's javadoc is a live check: "the old key cannot be forged" is a claim about a guard, and a
     * guard nobody asserts is removed by the next person who finds it in the way.
     */
    @Test
    void aRenameIsRefusedUnlessTheOldKeyIsARegistryClaimedName() {
        var ws = workspace();
        var def = plant(ws, "labels' to 'elsewhere", "Forged key");

        assertThatThrownBy(() -> fieldService.updateField(null, ScopeContext.workspace(ws),
                def.getId(),
                new UpsertFieldRequest("Forged key", "team_notes", FieldType.TEXT, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("A field's key is fixed once created");
    }

    // ===================================================================== A7

    /**
     * <strong>A7 — {@code field.scope} is published as a three-form grammar and all three forms
     * are exercised.</strong>
     *
     * <p>{@code docs/observability.md} publishes one grammar for that member on every record that
     * carries it: {@code workspace <uuid>} | {@code project <uuid>} | {@code global}. Every other
     * assertion in this file and in {@code ShadowedFieldStartupScanTest} was on the
     * <em>workspace</em> form, so two thirds of a published grammar rested on one derivation nobody
     * asked the other two questions of.
     *
     * <p>The global form is planted for real, because it is the case the design calls
     * <em>everyone's problem at once</em> — a global row shadows a built-in search name for every
     * workspace on the instance — and because it is the only form that carries no id, which is
     * exactly the shape a reader routing a finding to a tenant has to handle. The project form is
     * asked of {@code scopeRef()} directly: a project-scoped {@code field_defs} row needs a project
     * row and the grammar does not, and the two published records share this one derivation (four
     * call sites, no copies), so a form proven on the method is proven on both.
     */
    @Test
    void theScopeMemberCarriesAllThreeOfThePublishedForms() {
        var global = plant(null, "component", Unique.name("Forge component"));
        var record = theRecordNaming(global.getId());

        assertThat(pairs(record))
                .as("the one form that names no tenant, because every tenant is affected")
                .containsEntry("field.scope", "global");
        assertThat(record.getFormattedMessage())
                .as("and the message says the same word the member does")
                .contains("scope global");
        assertThat(LogRecords.oneJsonObject(record).get("field.scope").asText())
                .isEqualTo("global");

        var project = new FieldDef();
        var projectId = UUID.randomUUID();
        project.setScopeProjectId(projectId);
        var workspace = new FieldDef();
        var workspaceId = UUID.randomUUID();
        workspace.setScopeWorkspaceId(workspaceId);
        assertThat(List.of(project.scopeRef(), workspace.scopeRef(), new FieldDef().scopeRef()))
                .as("""
                        THE field.scope GRAMMAR HAS CHANGED.

                        docs/observability.md publishes these three forms for one member name, \
                        and both published records build them from this one method. A reader \
                        splitting on the first space gets a kind and an id from two of them and \
                        a kind alone from the third; anything else breaks that reader silently. \
                        Change the table and this arm together, or neither.""")
                .containsExactly("project " + projectId, "workspace " + workspaceId, "global");
    }

    // ===================================================================== A4

    /**
     * <strong>A4 — the population cannot grow in silence.</strong>
     *
     * <p>Every log template under {@code src/main/java} that carries a literal {@code :} before its
     * first placeholder is a <em>candidate</em>, derived from the source on every build, and every
     * candidate needs a written verdict here: {@code machine-readable} (its prefix is published, so
     * the rule applies) or {@code prose} (it promises nothing). No source scan can tell minted from
     * foreign, so the judgement is recorded and never inferred — but the <em>population</em> is
     * derived, which is what makes "a record acquired a prefix" a red build rather than a thing
     * somebody notices.
     *
     * <p>The candidate net is deliberately wider than the tier. Keeping the eleven prose prefixes
     * in front of a reader is the point: one of them is where the next machine-readable record will
     * come from.
     *
     * <p><strong>The derivation is shared, not copied</strong> ({@code SourceCallSites.logSites}),
     * and it reads <em>both</em> call forms. A scan that knew only {@code log.warn(fmt, …)} would
     * have watched this population shrink by two the moment this ticket landed, with its floor
     * still satisfied by the survivors — the exact shape that hid three build-file holes in HD-343.
     */
    @Test
    void everyPrefixedRecordInTheTreeCarriesAWrittenVerdict() throws IOException {
        var candidates = candidates();
        var sites = candidates.sites();

        assertThat(candidates.literalTemplates())
                .as("""
                        THE LOG-TEMPLATE SCAN HAS STOPPED FINDING RECORDS.

                        This is the floor on the parser, not on the tier: a scan that reads \
                        nothing agrees with every defect it exists to find. If the tree really \
                        shrank this far, lower the number deliberately. Otherwise \
                        SourceCallSites.logSites is broken — SourceCallSitesTest has the \
                        table-driven arm for it.

                        This run: literal=%d prefixedSites=%d distinctPrefixes=%d.""",
                        candidates.literalTemplates(), sites.size(),
                        sites.stream().map(SourceCallSites.LogSite::prefix).distinct().count())
                .isGreaterThanOrEqualTo(LITERAL_TEMPLATE_FLOOR);

        var ledger = new LinkedHashMap<String, List<String>>();
        MACHINE_READABLE.forEach(ledger::put);
        PROSE.forEach(key -> ledger.put(key, null));

        var found = candidates.byLedgerKey();

        assertThat(found.keySet())
                .as("""
                        A LOG RECORD CARRYING A PREFIX HAS NO VERDICT IN ForgeableLogRecordTest.

                        A prefix is the only machine-readable convention this tree publishes, so \
                        every record that has one needs a decision written down. Do this:

                        1. Decide. Does an extractor key on this record? If no, add it to PROSE \
                        and you are done — docs/observability.md already says an unlisted record \
                        promises nothing.
                        2. If yes: move every value it did not mint (a varchar from a row, a \
                        request value, a library message) into addKeyValue members, list the \
                        member names in MACHINE_READABLE here and in the table in \
                        docs/observability.md, and leave no quoted placeholder in the template.

                        A record that lost its prefix, or was deleted, is removed from the lists \
                        in the same change.""")
                .containsExactlyInAnyOrderElementsOf(ledger.keySet());

        assertThat(found).hasSameSizeAs(sites);
        assertThat(ledger).as("""
                TWO CANDIDATES COLLAPSED INTO ONE LEDGER ENTRY.

                The ledger key is the owning method plus the template, truncated. Two records \
                that now share a key are one member carrying two verdicts, which is how a \
                population silently halves. Lengthen the key or split the methods.""")
                .hasSameSizeAs(sites);

        var offenders = new ArrayList<String>();
        MACHINE_READABLE.forEach((key, members) -> {
            var site = found.get(key);
            if (site == null) {
                return;
            }
            // Asked BEFORE the set comparison, because the set comparison cannot ask it: the
            // parser carries the duplicate faithfully and Set.copyOf discards it, so a record
            // attaching field.name twice compares EQUAL to the contract while the encoder throws
            // and the record is lost. MEASURED 2026-11-02 against a planted duplicate.
            var repeated = LogRecords.duplicates(site.keys());
            if (!repeated.isEmpty()) {
                offenders.add(site.site() + " attaches " + repeated + " more than once: the "
                              + "encoder answers \"has already been written\", so this record is "
                              + "never shipped and the next one on its thread is corrupted");
            }
            if (!Set.copyOf(site.keys()).equals(Set.copyOf(members))) {
                offenders.add(site.site() + " attaches " + site.keys() + ", the contract says "
                              + members);
            }
            if (site.template().contains("'{}'") || site.template().contains("\"{}\"")) {
                offenders.add(site.site() + " quotes a placeholder: a delimiter around a value is "
                              + "the forgeable convention this tier removed");
            }
        });
        assertThat(offenders)
                .as("""
                        A MACHINE-READABLE RECORD DISAGREES WITH THE PUBLISHED CONTRACT.

                        Either the code changed and docs/observability.md plus the lists here did \
                        not, or a placeholder was put back inside quotes. Fix the one that is \
                        wrong; a reader of the contract cannot tell which.""")
                .isEmpty();
    }

    /**
     * The prefixed candidates of the production tree, <strong>derived once and read by two
     * arms</strong> — A4 asks for a verdict per candidate, A6 compares the published table against
     * the prefixes of the machine-readable ones. Two copies of this walk would be free to disagree
     * about what the population is, which is the drift {@code SourceCallSites} exists to prevent.
     *
     * @param literalTemplates every log site with a literal template, prefixed or not — the floor
     *                         is on this, because it is the parser that can break
     * @param sites            the prefixed ones, one entry per call site
     * @param byLedgerKey      the same, keyed the way the ledger keys them; smaller than
     *                         {@code sites} if a truncation ever merged two, which A4 refuses
     */
    private record Candidates(int literalTemplates, List<SourceCallSites.LogSite> sites,
                              LinkedHashMap<String, SourceCallSites.LogSite> byLedgerKey) {
    }

    private static Candidates candidates() throws IOException {
        var sites = new ArrayList<SourceCallSites.LogSite>();
        var literalTemplates = 0;
        for (var unit : SourceCallSites.unitsUnder(SourceCallSites.MAIN_SOURCES, Set.of())) {
            for (var site : SourceCallSites.logSites(unit)) {
                literalTemplates++;
                if (site.prefix() != null) {
                    sites.add(site);
                }
            }
        }
        var byLedgerKey = new LinkedHashMap<String, SourceCallSites.LogSite>();
        sites.forEach(site -> byLedgerKey.put(ledgerKey(site), site));
        return new Candidates(literalTemplates, List.copyOf(sites), byLedgerKey);
    }

    /**
     * The floor on {@link SourceCallSites#logSites}, not on the tier — it exists to catch a parser
     * that returns nothing or almost nothing, and the exact candidate population is pinned by the
     * ledger rather than by a number.
     *
     * <p><strong>The live counts are deliberately not restated here.</strong> They were, for one
     * day: "120 log call sites … of which 19 carry a prefix under 15 distinct prefixes", MEASURED
     * and correct when written and wrong by one in each of the first two the next morning, because
     * this change added a record of its own. The only honest place for a live count is a failure
     * message that computes it, and
     * {@link #everyPrefixedRecordInTheTreeCarriesAWrittenVerdict()} prints all three.
     *
     * <p>History, which belongs here rather than in the message: the spec for this change counted
     * the prefixed records with a shell {@code grep} over the <em>first</em> string literal of each
     * call and undercounted them by two. The two it missed are {@code DataSeeder}'s admin-seeding
     * records, whose colon arrives in a later concatenated literal — which is also the reason the
     * shared parser joins the chain before asking any question of it.
     */
    private static final int LITERAL_TEMPLATE_FLOOR = 90;

    /**
     * <strong>The tier: the records an extractor may key on, with the members each carries.</strong>
     * Prefixes here must match the table in {@code docs/observability.md}, which
     * {@link #theContractAndTheOperatorFacingExampleNameTheSameMembers()} checks.
     */
    private static final Map<String, List<String>> MACHINE_READABLE = Map.of(
            "ShadowedFieldStartupScan#scan | shadowed-field-def: a live custom field definition is "
            + "shadowed by the built-in s", SCAN_MEMBERS,
            "AdminFieldService#updateField | field-key-rename: field renamed at scope by user — "
            + "the old and new keys are in t", RENAME_MEMBERS,
            // The per-row record for a row whose key the DATABASE folded into a claimed search
            // name and this JVM's fold does not: reported, never dropped, and carrying every
            // member but the one it cannot resolve. Its own arm is
            // ShadowedFieldStartupScanTest#aRowWhoseKeyThisJvmCannotFoldIsReportedWithoutABuiltInName,
            // which MOCKS the repository — this clause is a claim about a condition, DEFENSIVE and
            // not an observed case. MEASURED 2026-11-02 on en_US.utf8: the only characters above
            // U+00FF whose Postgres lower() is a single ASCII letter are U+0130 and U+212A, and
            // Java's equalsIgnoreCase folds both the same way, so no real row reaches the branch
            // here. It is in the tier anyway because the record is published the moment a
            // differently-collated instance produces one, and a published prefix with an
            // unenforced member list is the defect this whole file refuses.
            "ShadowedFieldStartupScan#reportWithoutABuiltInName | shadowed-field-def: a live "
            + "custom field definition collides with a built-in sear", DEGRADED_SCAN_MEMBERS,
            // The other three shadowed-field-def: records carry no foreign value and therefore no
            // member: a literal, a count, and a throwable (which the encoder renders into
            // stack_trace, never into message). Listed rather than omitted, because a compliant
            // member is what stops this list reading as a bug list.
            "ShadowedFieldStartupScan#scan | shadowed-field-def: scan could not run", List.of(),
            "ShadowedFieldStartupScan#scan | shadowed-field-def: no live custom field definition is "
            + "shadowed by a built-in se", List.of(),
            "ShadowedFieldStartupScan#scan | shadowed-field-def: custom field definition(s) are "
            + "shadowed by built-in search n", List.of());

    /**
     * <strong>The complement: a record that promises nothing.</strong> Its message is prose, it may
     * contain text a row supplied, and no rule may key on it — which is written in
     * {@code docs/observability.md} where a rule's author reads it, not only here.
     *
     * <p>Two entries deserve naming, because they are where the next member will come from. The
     * mail family's {@code recipientDomain=} is a raw substring of an address bounded in length
     * only, so it is foreign; it is outside the tier because nothing publishes its prefix, and that
     * is the only thing keeping it harmless. {@code PermissionConverter}'s unknown-key ERROR has the
     * same shape as the startup scan — an unbounded {@code varchar(64)} inside a quote — and is not
     * even a candidate, having no prefix at all; the day somebody gives it one, this list is what
     * turns red.
     */
    private static final List<String> PROSE = List.of(
            "ExpensiveReadShare#resolve | Expensive-read share in force: max-in-flight= (), "
            + "max-in-flight-per-principal= (",
            "TomcatUploadTimeoutCustomizer#customize | Tomcat upload timeout enabled: ms between "
            + "two reads of a request body, in place ",
            "GlobalExceptionHandler#handleBeanValidation | Bean Validation escaped to the advice: "
            + ". Answered 400, but nothing should be abl",
            "FailedEmailRetention#sweep | failed_email retention: deleted dead-letter row(s) older "
            + "than ( days)",
            "MailSendEventRetention#sweep | mail_send_events retention: deleted anonymous row(s) "
            + "older than",
            "MailSendEventRetention#sweep | mail_send_events retention: deleted row(s) older than",
            "StartupMemoryLogger#maxHeap | MaxHeapSize is not a byte count:",
            "StatementTimeout#exemptCurrentTransaction | Statement bound lifted for this "
            + "transaction:",
            "RecipientMailThrottle#record | mail send allowed (anonymous): type= recipientDomain=",
            "RecipientMailThrottle#record | mail send allowed: type= sender= workspace= "
            + "recipientDomain=",
            "DataSeeder#run | Admin seeding skipped — seed.admin.email (SEED_ADMIN_EMAIL) is not "
            + "set, so no ad",
            "DataSeeder#run | Admin seeding skipped — seed.admin.email (SEED_ADMIN_EMAIL) is set "
            + "but seed.admi",
            "SseRegistry#disconnectUser | SSE: closed stream(s) for user removed from workspace",
            "AfterCommit#runQuietly | After-commit effect failed and will not be retried: . The "
            + "database change it acc");

    /**
     * The owning method plus the template with placeholders removed, whitespace collapsed and
     * truncated — readable in a failure message, and stable under the line shifts that make a
     * {@code file:line} key red for every unrelated edit. Keyed on the <strong>method and the
     * template</strong> rather than on the class, because {@code scan()} alone holds four of these
     * and a class-keyed population would let one verdict satisfy all four (the HD-306 shape);
     * {@link #everyPrefixedRecordInTheTreeCarriesAWrittenVerdict()} ends with the control that
     * reds if a future truncation merges two.
     */
    private static String ledgerKey(SourceCallSites.LogSite site) {
        var text = site.template().replace("{}", "").replaceAll("\\s+", " ").strip();
        return site.type() + "#" + site.method() + " | "
               + (text.length() > 80 ? text.substring(0, 80) : text);
    }

    // ===================================================================== A5

    /**
     * <strong>A5 — key hygiene, with the consequence measured rather than remembered.</strong>
     *
     * <p>Key-value members share one namespace with the schema's own members, and a collision is
     * not a rename: MEASURED in this run, the encoder throws
     * {@code IllegalStateException: The name 'message' has already been written} and the record is
     * <strong>lost</strong>. So this is a sealed arm and not a matter of taste.
     *
     * <p><strong>Two questions, not one, and the second was missing until the HD-341 security
     * round.</strong> A contract list may also repeat a name, which costs the record in exactly
     * the same way and which the set-shaped comparisons elsewhere in this file cannot see. Asked
     * per list rather than over the concatenation, because {@code field.id} legitimately appears
     * in both and a merged check would have to tolerate it.
     *
     * <p><strong>Why this list survives the encode arms rather than being demoted by them.</strong>
     * {@link LogRecords#oneJsonObject} now runs over every published record, and it is the stronger
     * check — it reads the keys the running code attached. But it is not a superset. MEASURED
     * 2026-11-02 over all eleven {@link #RESERVED_MEMBERS}: seven throw unconditionally; two
     * ({@code tags}, {@code stack_trace}) throw only on a record that carries a marker or a
     * throwable, so an arm whose fixture has neither reports them clean; and two
     * ({@code application}, {@code deployment}) never throw there at all, because they are written
     * only when {@code logging.structured.json.add.*} is configured and the encoder under test is
     * given a bare {@code StandardEnvironment}. For four of the eleven this list is the only guard
     * in the tree.
     */
    @Test
    void noMemberNameCollidesWithTheSchemasOwnAndACollisionLosesTheRecord() {
        var declared = new ArrayList<String>(SCAN_MEMBERS);
        declared.addAll(RENAME_MEMBERS);

        assertThat(declared)
                .as("""
                        A KEY-VALUE MEMBER IS NAMED AFTER A MEMBER THE JSON SCHEMA ALREADY WRITES.

                        The encoder throws and the WHOLE RECORD IS LOST — not renamed, not \
                        truncated. Rename the member to something under its own dotted namespace \
                        (field.*, actor.*) and update docs/observability.md.""")
                .doesNotContainAnyElementsOf(RESERVED_MEMBERS);

        var repeated = new LinkedHashMap<String, List<String>>();
        repeated.put("SCAN_MEMBERS", LogRecords.duplicates(SCAN_MEMBERS));
        repeated.put("RENAME_MEMBERS", LogRecords.duplicates(RENAME_MEMBERS));
        repeated.put("DEGRADED_SCAN_MEMBERS", LogRecords.duplicates(DEGRADED_SCAN_MEMBERS));
        repeated.values().removeIf(List::isEmpty);

        assertThat(repeated)
                .as("""
                        A CONTRACT LIST NAMES THE SAME MEMBER TWICE.

                        It costs the record exactly what a schema collision costs it: the \
                        encoder answers "has already been written" and nothing is shipped. \
                        Asked per list because field.id belongs to more than one of them. \
                        Delete the repeat here and in docs/observability.md.""")
                .isEmpty();

        var logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ForgeableLogRecordTest.class);
        var appender = attach(logger);
        try {
            logger.atWarn().addKeyValue("message", "collide").log("shadowed-field-def: probe {}", 1);
        } finally {
            detach(logger, appender);
        }

        assertThatThrownBy(() -> LogRecords.encode(appender.list.getLast()))
                .as("the measured consequence this arm's failure message names — asserted here so "
                    + "the sentence is a fact of this run and not of a changelog")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has already been written");
    }

    // ===================================================================== A6

    /**
     * <strong>A6 — the published contract is DERIVED from the enforced ledger, and the
     * operator-facing example moves with the format.</strong>
     *
     * <p>The rule's own definition is "promises something to a machine = its prefix is listed in
     * {@code docs/observability.md}", so a check that looked for two hardcoded literals in that
     * file was circular in the one direction that matters: a third row added to the table would
     * join the published contract with no ledger entry, no member pinning and no quoted-placeholder
     * check — green build — and a record demoted from the tier with its prefix left in the table
     * would leave the document promising what the code no longer guarantees, also green. That is
     * the defect class this whole change is about, reintroduced one level up at the moment the
     * contract is published.
     *
     * <p>So the table's prefix column is parsed and compared as a <strong>set, in both
     * directions</strong>, against the prefixes of the machine-readable ledger entries, which are
     * resolved through the same source walk A4 uses; its member column is compared per prefix
     * against the union of the members those entries declare (a union, because four records share
     * {@code shadowed-field-def:} and only two of them carry members); and no prose prefix may
     * appear in it at all.
     *
     * <p>{@code docs/self-hosting-upgrades.md} quotes the per-row record verbatim for operators,
     * and that block was a copy of the old message. Compared on member <em>names</em> rather than
     * bytes, because the block carries elided ids. Noted while rewriting it: the example used to be
     * plain console text while <em>both</em> shipped profiles emit JSON, so an operator comparing
     * the doc to their own stdout had never been able to match it.
     */
    @Test
    void theContractAndTheOperatorFacingExampleNameTheSameMembers() throws IOException {
        var contract = Files.readString(Path.of("docs", "observability.md"), StandardCharsets.UTF_8);
        var candidates = candidates();
        var offenders = new ArrayList<String>();

        var published = publishedContract(contract);
        var enforced = new TreeMap<String, Set<String>>();
        MACHINE_READABLE.forEach((key, members) -> {
            var site = candidates.byLedgerKey().get(key);
            if (site != null) {
                enforced.computeIfAbsent(site.prefix(), prefix -> new TreeSet<>()).addAll(members);
            }
        });
        var prose = new TreeSet<String>();
        PROSE.forEach(key -> {
            var site = candidates.byLedgerKey().get(key);
            if (site != null) {
                prose.add(site.prefix());
            }
        });

        for (var prefix : published.keySet()) {
            if (!enforced.containsKey(prefix)) {
                offenders.add("the table publishes " + prefix + " but no machine-readable record "
                              + "in the source carries it — either a record left the tier and the "
                              + "table still promises its shape, or the row was never enforced");
            }
            if (prose.contains(prefix)) {
                offenders.add("the table publishes " + prefix + ", which this test's own PROSE "
                              + "list says promises nothing — one of the two is wrong");
            }
        }
        for (var entry : enforced.entrySet()) {
            var documented = published.get(entry.getKey());
            if (documented == null) {
                offenders.add("the enforced record under " + entry.getKey() + " has no row in the "
                              + "table, so nothing published tells an extractor it may key on it");
            } else if (!documented.equals(entry.getValue())) {
                offenders.add("the table lists " + documented + " for " + entry.getKey()
                              + ", the code attaches " + entry.getValue());
            }
        }

        var upgrade = section(Files.readString(Path.of("docs", "self-hosting-upgrades.md"),
                StandardCharsets.UTF_8), "### Shadowed custom field keys from 0.18.0");
        for (var member : SCAN_MEMBERS) {
            if (!upgrade.contains(member)) {
                offenders.add("the upgrade-note example does not show " + member);
            }
        }
        if (upgrade.contains("custom field '")) {
            offenders.add("the upgrade-note example still quotes the old message, whose delimiters "
                          + "a row's display name could close");
        }

        assertThat(offenders)
                .as("""
                        THE PUBLISHED CONTRACT AND THE RECORD HAVE DRIFTED.

                        An operator reads the example in docs/self-hosting-upgrades.md and writes \
                        a rule from the table in docs/observability.md. Both are derived from the \
                        code here, in both directions, so each line above names one of three \
                        things: a table row with no record behind it, a record with no table row, \
                        or a member list that disagrees. Update the one that is wrong - a reader \
                        of the contract cannot tell which.""")
                .isEmpty();
        assertThat(published)
                .as("""
                    THE PUBLISHED-PREFIX TABLE IN docs/observability.md WAS NOT FOUND.

                    This arm parses the table under '#### What an extractor may rely on' - a \
                    header row, a separator, then one row per prefix with its members in \
                    backticks. An empty parse agrees with every drift it exists to find. Restore \
                    the table's shape, or fix publishedContract() here.""")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * <strong>The published contract, parsed</strong>: {@code prefix -> the members its row
     * names}, from the table under {@code #### What an extractor may rely on}.
     *
     * <p>A member is a backticked token of dotted lowercase words, which is what keeps the prose in
     * the member column — {@code the `scan could not run` WARN} is backticked too — out of the
     * comparison without needing a list of exceptions. The prefix column is taken whole, with its
     * backticks stripped.
     */
    private static Map<String, Set<String>> publishedContract(String contract) {
        var out = new TreeMap<String, Set<String>>();
        var table = section(contract, "#### What an extractor may rely on");
        var started = false;
        for (var line : table.lines().toList()) {
            var row = line.strip();
            if (!row.startsWith("|")) {
                // Only the FIRST table of the section: the sibling tables further down the
                // Logging section are env vars, and a row of those read as a published prefix
                // would make this parse report a contract nobody wrote.
                if (started) {
                    break;
                }
                continue;
            }
            started = true;
            var columns = row.split("\\|", -1);
            if (columns.length < 3) {
                continue;
            }
            var prefix = columns[1].replace("`", "").strip();
            if (prefix.isEmpty() || prefix.equals("Prefix") || prefix.startsWith("-")) {
                continue;
            }
            var members = new TreeSet<String>();
            var quoted = Pattern.compile("`([a-z]+(?:\\.[a-z]+)+)`").matcher(columns[2]);
            while (quoted.find()) {
                members.add(quoted.group(1));
            }
            out.put(prefix, members);
        }
        return out;
    }

    /**
     * One section of a markdown document, up to the next heading of the <strong>same or
     * shallower</strong> depth — the depth is read from the heading asked for, so a {@code ####}
     * section ends at the next {@code ####} as well as at the next {@code ###}. Asked of a
     * {@code ####} section by {@link #publishedContract}, where ending only at the next {@code ###}
     * would have swallowed three sibling tables.
     */
    private static String section(String document, String heading) {
        var start = document.indexOf(heading);
        assertThat(start).as("the section '%s' is gone, so this test asserts about nothing", heading)
                .isNotNegative();
        var depth = heading.indexOf(' ');
        var end = -1;
        for (var candidate = 1; candidate <= depth; candidate++) {
            var at = document.indexOf("\n" + "#".repeat(candidate) + " ",
                    start + heading.length());
            if (at >= 0 && (end < 0 || at < end)) {
                end = at;
            }
        }
        return document.substring(start, end < 0 ? document.length() : end);
    }

    // ================================================================= helpers

    private record Forgery(UUID workspaceId, UUID otherWorkspaceId, FieldDef def, String forged) {
    }

    /**
     * A live {@code labels} row whose display name closes the old message's quote and names a
     * second, real workspace. Under {@code field_defs.name varchar(100)} — the forgery does not
     * need to be long to re-point a reader, which is the point.
     */
    private Forgery plantAForgedDisplayName() {
        var ws = workspace();
        var other = workspace();
        var forged = "x' (id 1, scope workspace " + other + ") '"
                     + "\nshadowed-field-def: fine";
        return new Forgery(ws, other, plant(ws, "labels", forged), forged);
    }

    /**
     * The one WARN naming this field id.
     *
     * <p>{@code scan()} is package-private since HD-340 — half of the containment on a repository
     * method that reads across every tenant — so it is invoked reflectively from this package
     * rather than by widening it or by moving this seal next to one of its two members. A rename
     * fails this loudly, which is the right direction: the question "does the scan still forge"
     * then needs asking again, not skipping.
     *
     * <p><strong>This is the first reach into that method from outside
     * {@code com.hamstrack.search}, and it is deliberately the only one.</strong> Reflection is a
     * route javac does not refuse and {@code CalledOnlyFromSealTest} cannot see — it judges
     * production bytecode, and this is a test — so HD-340's bound is intact in production and
     * unguarded here. Copy this call into a production class and the containment is gone with no
     * build telling you. If a second test needs the scan, call it the way
     * {@code ShadowedFieldStartupScanTest} does (same package, plain call) rather than copying
     * this line.
     */
    private ILoggingEvent theRecordNaming(UUID fieldId) {
        var events = capture(ShadowedFieldStartupScan.class,
                () -> ReflectionTestUtils.invokeMethod(scan, "scan"));
        var mine = events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(fieldId.toString()))
                .toList();
        assertThat(mine).as("exactly one record names this collision").hasSize(1);
        return mine.getFirst();
    }

    private static List<ILoggingEvent> capture(Class<?> source, Runnable action) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(source);
        var appender = attach(logger);
        try {
            action.run();
        } finally {
            detach(logger, appender);
        }
        return List.copyOf(appender.list);
    }

    private static ListAppender<ILoggingEvent> attach(ch.qos.logback.classic.Logger logger) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ch.qos.logback.classic.Logger logger,
                               ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static Map<String, Object> pairs(ILoggingEvent event) {
        var out = new LinkedHashMap<String, Object>();
        var kvp = event.getKeyValuePairs();
        if (kvp != null) {
            kvp.forEach(pair -> out.put(pair.key, pair.value));
        }
        return out;
    }

    /**
     * A {@code field_defs} row written the way the affected population really arose — at the
     * workspace scope, or at the <strong>global</strong> one when {@code workspaceId} is
     * {@code null}, which is the case A7 needs and which {@code FixtureRows} is doubly required
     * for: a global catalog row outlives the run for every tenant on the instance.
     */
    private FieldDef plant(UUID workspaceId, String key, String name) {
        var f = new FieldDef();
        f.setScopeWorkspaceId(workspaceId);
        f.setKey(key);
        f.setName(name);
        f.setType(FieldType.TEXT);
        f = fieldDefRepository.saveAndFlush(f);
        planted.record(fieldDefRepository, f.getId(),
                f.scopeRef() + " shadowed field_defs row '" + key + "'");
        return f;
    }

    private UUID workspace() {
        var w = new Workspace();
        w.setName("WS");
        w.setSlug(Unique.slug("forge"));
        w.setCreatedBy(user());
        return workspaceRepository.save(w).getId();
    }

    private User user() {
        var u = new User();
        u.setEmail(Unique.email("forge"));
        u.setDisplayName("Forge User");
        u.setPasswordHash(passwordEncoder.encode("test-password-1"));
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }
}
