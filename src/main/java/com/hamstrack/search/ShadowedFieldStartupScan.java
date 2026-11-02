package com.hamstrack.search;

import com.hamstrack.common.observability.LogValues;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.repository.FieldDefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <strong>The instance names, once per boot, every custom field definition a built-in search name
 * is shadowing</strong> (HD-275 §8).
 *
 * <p>A {@link FieldRegistry} name outranks every tenant's custom field of the same key, forever and
 * retroactively, and the reserved-key guard in {@code AdminFieldService} is <strong>never
 * retroactive</strong> — it refuses a claimed key at the doors that MINT one and touches no row
 * that already exists. So a row written before the name was registered (or by a migration, a
 * seeder, or direct SQL, which that guard never reached at all) keeps working everywhere in the
 * product except search, where it is silently unreachable. This is the operator's half of ending
 * that silence. The admin console and {@code /search/schema} are the tenant's half; a build guard
 * ({@code RegisteredSearchNameLedgerTest}) is ours, and it is the only one of the three that
 * <em>prevents</em> a collision rather than reporting one that already exists.
 *
 * <h2>The three properties that matter, and what each is protecting against</h2>
 * <ul>
 *   <li><strong>The watch set is DERIVED</strong> from {@link FieldRegistry#claimedKeys()}, never
 *       written down here. A name registered tomorrow is scanned with no second edit, so this
 *       reporting can never go partially adopted — there is no list to forget.</li>
 *   <li><strong>A clean instance logs nothing at WARN.</strong> V3 seeds global system
 *       placeholders keyed {@code labels}, {@code sprint} and {@code components} — all three
 *       registry-claimed — which V8/V11/V9 archive. If archived rows counted, every Hamstrack
 *       instance in existence would warn about its own seed data on every boot and the signal
 *       would be dead on arrival. The query excludes them; the DEBUG line below is what a healthy
 *       instance emits.</li>
 *   <li><strong>It can never fail the boot.</strong> A tenant's perfectly legal data must not stop
 *       an instance starting, and the shadowing is our doing rather than theirs, so the whole scan
 *       is wrapped and any failure degrades to one WARN.</li>
 * </ul>
 *
 * <p>On {@link ApplicationReadyEvent} rather than {@code @PostConstruct}: it reads the database, so
 * it has to run after Flyway and after the {@code EntityManagerFactory} is up — the same reason
 * {@code FailedEmailWriter}'s startup check is on the ready event. It only reads, so re-running it
 * on every restart is idempotent and wanted: an operator who ignored the line once sees it again.
 *
 * <p><strong>It reads across tenants by design</strong>, and that is safe on the one thing that
 * makes it safe: it is a process on the operator's own instance, not a request, and it emits ids,
 * keys, names and scope ids — never issue data and never field values. No request-scoped surface
 * shares this query; {@code /schema} builds its list from the caller's own {@code ResolutionContext}.
 * Since HD-340 that is a declaration on the query itself ({@code @CalledOnlyFrom}) which
 * {@code CalledOnlyFromSealTest} checks against the real call graph, so this class losing its
 * exclusivity is a red build and not a stale paragraph.
 *
 * <p>Every record carries the stable {@code shadowed-field-def:} prefix, which is one of the two
 * prefixes {@code docs/observability.md} publishes as machine-readable — so a Loki rule may key on
 * it, and on the {@code field.*} members, and must never parse the message. That listing is what
 * makes this record's shape a promise rather than a wording, and it is why <strong>no value a row
 * supplied is interpolated into the message</strong> (HD-341): the display name and the key travel
 * in {@code field.name} and {@code field.key}, where the JSON encoder escapes them and they cannot
 * imitate the record's own structure. Before that they sat inside {@code '…'} delimiters, and a
 * display name closing that quote and continuing in the template's grammar produced one plausible
 * record naming a workspace the row does not belong to — on every boot, for ever, because nothing
 * rewrites the row. {@code ForgeableLogRecordTest} holds it.
 *
 * <p>Deliberately not a Micrometer gauge: a gauge set once at boot goes stale the moment an admin
 * renames out of the collision and stays wrong until the next restart, and a metric that lies
 * about a fix is worse than no metric.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ShadowedFieldStartupScan {

    private final FieldRegistry registry;
    private final FieldDefRepository fieldDefRepository;

    /**
     * <strong>Package-private, and that narrows the containment on
     * {@code FieldDefRepository.findAllLiveByKeyIn} rather than completing it</strong> (HD-340).
     * Spring invokes this; nothing outside {@code com.hamstrack.search} can — the
     * {@code DefaultRoleResponse.of} precedent, where an access modifier is the guard and javac is
     * what enforces it.
     *
     * <p><strong>Be exact about what that buys, because the obvious next sentence is false.</strong>
     * It does not close the two-hop route; it moves it. A two-hop route into this class can now
     * only be written from inside {@code com.hamstrack.search} — which is 21 classes and is
     * precisely the request-path package: it holds {@code SearchController} (a
     * {@code @RestController}) and {@code SearchService} beside this one. A controller calling
     * {@code scan()} directly is refused, because that makes this class a direct target of a web
     * bean and {@code CalledOnlyFromSealTest}'s arm (2) sees it. A controller reaching it through
     * {@code SearchService} is <strong>not</strong> refused — that is the residual arm (2) does
     * not cover, stated here as well as on {@code FieldDefRepository} so this file is not the one
     * place claiming it is gone. What the change does buy is real: the set of classes that could
     * ever be that intermediary drops from the whole tree to the 20 beside this one, and the bound
     * is the compiler rather than a test. Nothing is live — MEASURED 2026-10-24,
     * {@code grep -rn "\.scan()" src/main/java} returns no match.
     *
     * <p><strong>Two framework facts here are measured, not remembered</strong> (2026-10-24, Boot
     * 4.1 / Spring 7): a package-private {@code @EventListener} IS invoked, and
     * {@code @Transactional} on it IS honoured — which is the opposite of the long-standing
     * documented guidance that proxy-mode transactions apply to public methods only, and is why it
     * was probed rather than assumed.
     *
     * <p><strong>How the second one was separated from its look-alike</strong>, because
     * {@code isActualTransactionActive()} alone cannot do it — it answers {@code true} for ANY
     * live transaction, including one opened further out, so "advised here" and "ran inside
     * somebody else's transaction" produce the same reading. Three listeners on one bean, one
     * publish, no ambient transaction ({@code false} before publishing): the package-private
     * {@code @Transactional} one reported a transaction <em>named after itself</em>
     * ({@code …$Probe.onPackagePrivate}), the public one the same, and the control — the same
     * bean, same event, <em>no</em> {@code @Transactional} — reported {@code active=false},
     * {@code name=null}. A name is what makes the reading a fact about this method rather than
     * about its surroundings.
     *
     * <p>Both facts are load-bearing: if a future version stops registering non-public listeners,
     * this scan silently never runs and its only output is log lines nobody misses.
     * {@code ShadowedFieldStartupScanTest} holds the registration for that reason.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    void scan() {
        List<FieldDef> shadowed;
        try {
            shadowed = fieldDefRepository.findAllLiveByKeyIn(registry.claimedKeys());
        } catch (Exception e) {
            // WARN, not ERROR and not a rethrow: reaching here means the report is ABSENT, which
            // an operator should know, but a report is never worth a failed boot.
            log.warn("shadowed-field-def: scan could not run", e);
            return;
        }
        if (shadowed.isEmpty()) {
            log.debug("shadowed-field-def: no live custom field definition is shadowed by a "
                      + "built-in search name");
            return;
        }
        for (FieldDef f : shadowed) {
            String claimedKey = claimedKeyOf(f);
            FieldDescriptor builtIn = claimedKey == null
                    ? null : registry.find(claimedKey).orElse(null);
            if (builtIn == null) {
                reportWithoutABuiltInName(f);
                continue;
            }
            log.atWarn()
                    .addKeyValue("field.id", f.getId())
                    .addKeyValue("field.scope", f.scopeRef())
                    .addKeyValue("field.key", LogValues.oneLine(f.getKey()))
                    .addKeyValue("field.name", LogValues.oneLine(f.getName()))
                    .addKeyValue("field.builtin", builtIn.name())
                    .log("shadowed-field-def: a live custom field definition is shadowed by the "
                         + "built-in search field {} — field id {}, scope {}; its key and display "
                         + "name are in this record's field.key and field.name. HQL `{} = …` "
                         + "answers from the built-in field, not from this one, and it is not "
                         + "offered in /search/schema. A taxonomy admin at that scope can rename "
                         + "its key (PATCH …/fields/{}); see "
                         + "docs/self-hosting-upgrades.md#shadowed-custom-field-keys-from-0180.",
                            builtIn.name(), f.getId(), f.scopeRef(), claimedKey, f.getId());
        }
        log.warn("shadowed-field-def: {} custom field definition(s) are shadowed by built-in search names",
                shadowed.size());
    }

    /**
     * <strong>The row reported when this JVM cannot name the built-in field that took its
     * key</strong> — the witness for the one branch where the record above cannot be built.
     *
     * <p>It is a <em>report</em>, not a drop: the operator still gets the id, the scope, the key
     * and the display name, which is everything the remedy needs. What it does not get is a
     * built-in name, because there is none this instance can resolve and printing {@code ?} — what
     * this code did until HD-341 — publishes a placeholder as a contract member. The message says
     * so in words rather than in punctuation.
     *
     * <p>It carries four of the five {@code shadowed-field-def:} members, {@code field.builtin}
     * being the one it cannot supply; {@code docs/observability.md} says that and
     * {@code ForgeableLogRecordTest} holds it, members and all.
     *
     * <p><strong>Defensive, not observed — say so rather than let the clause read as a measured
     * case.</strong> Both ways into this branch were probed on 2026-11-02 and neither is reachable
     * from a real row on this collation. {@link FieldRegistry#find} cannot miss a key drawn from
     * {@link FieldRegistry#claimedKeys()} (every key in that set was lowercased when it was
     * registered, and {@code find} lowercases its argument), so the branch needs
     * {@link #claimedKeyOf} to return {@code null} on a row the query returned — i.e. a character
     * Postgres folds to a claimed name and {@code equalsIgnoreCase} does not. MEASURED on
     * {@code en_US.utf8}: the only characters above U+00FF whose {@code lower()} is a single ASCII
     * letter are U+0130 → {@code i} and U+212A → {@code k}, and Java folds both to the same letter,
     * so there is no such pair here. The arm that holds this record
     * ({@code ShadowedFieldStartupScanTest#aRowWhoseKeyThisJvmCannotFoldIsReportedWithoutABuiltInName})
     * therefore mocks the repository: it pins the record's <em>shape</em>, not its reachability. The
     * branch stays because a collation is a deployment property, not a product one — an ICU or
     * {@code C} collation on a self-hosted instance is free to fold differently — and the
     * alternative at the moment it does is the {@code "?"} placeholder this replaced.
     */
    private void reportWithoutABuiltInName(FieldDef f) {
        log.atWarn()
                .addKeyValue("field.id", f.getId())
                .addKeyValue("field.scope", f.scopeRef())
                .addKeyValue("field.key", LogValues.oneLine(f.getKey()))
                .addKeyValue("field.name", LogValues.oneLine(f.getName()))
                .log("shadowed-field-def: a live custom field definition collides with a built-in "
                     + "search name that this instance cannot name from the row's own key — field "
                     + "id {}, scope {}; the key and display name are in this record's field.key "
                     + "and field.name. The database folded that key into a registered name and "
                     + "this JVM's case fold does not, so the field is unreachable in search and "
                     + "no built-in name can be printed for it. A taxonomy admin at that scope can "
                     + "rename its key (PATCH …/fields/{}); see "
                     + "docs/self-hosting-upgrades.md#shadowed-custom-field-keys-from-0180.",
                        f.getId(), f.scopeRef(), f.getId());
    }

    /**
     * <strong>The claimed key this row collides with, taken from the registry rather than from the
     * row</strong> — the ONE relation the record is built from, and {@code null} when there is
     * none.
     *
     * <p><strong>Three relations used to decide one record, and they disagree.</strong> MEASURED
     * 2026-11-01 on a key spelled {@code SPRINT} with U+0130 (capital I with dot above) for its
     * {@code I}: Postgres {@code lower()} answers {@code 'sprint'}, so the query returns the row;
     * that same key through Java's {@code toLowerCase(Locale.ROOT)} is seven characters and is
     * <em>not</em> {@code "sprint"}, so {@link FieldRegistry#find} — which lowercases its
     * argument — missed and
     * the record said "shadowed by the built-in search field {@code ?}"; {@code equalsIgnoreCase}
     * folds the same pair to true, because {@code Character.toLowerCase(U+0130)} is {@code 'i'}.
     * So the built-in name is now resolved <em>through</em> the key found here, and the caller's
     * only remaining question is whether this returned anything at all.
     *
     * <p>Returning the registry's element rather than a lowercased copy of the row's key is the
     * other half: the one concrete key the message interpolates is then a string this codebase
     * minted, and the row's own spelling is in {@code field.key} regardless.
     *
     * <p>Cost is a scan of the claimed-key set per reported row, at boot.
     */
    private String claimedKeyOf(FieldDef f) {
        for (String claimed : registry.claimedKeys()) {
            if (claimed.equalsIgnoreCase(f.getKey())) return claimed;
        }
        return null;
    }
}
