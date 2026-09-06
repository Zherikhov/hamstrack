package com.hamstrack.search;

import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.repository.FieldDefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

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
 *
 * <p>Every line carries the stable {@code shadowed-field-def:} prefix so log-based alerting (Loki,
 * in Cloud) can key on it without parsing prose — which is also why every value a <em>row</em>
 * supplies goes through {@link #oneLine} first: see its javadoc for why the DTO's bound on the
 * display name cannot be the whole guard. Deliberately not a Micrometer gauge: a gauge set
 * once at boot goes stale the moment an admin renames out of the collision and stays wrong until
 * the next restart, and a metric that lies about a fix is worse than no metric.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ShadowedFieldStartupScan {

    private final FieldRegistry registry;
    private final FieldDefRepository fieldDefRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void scan() {
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
            log.warn("shadowed-field-def: custom field '{}' (key '{}', id {}, scope {}) is shadowed by the "
                     + "built-in search field '{}'. HQL `{} = …` answers from the built-in field, not from "
                     + "this one, and it is not offered in /search/schema. A taxonomy admin at that scope "
                     + "can rename its key (PATCH …/fields/{}); see "
                     + "docs/self-hosting.md#shadowed-custom-field-keys-from-0180.",
                    oneLine(f.getName()), oneLine(f.getKey()), f.getId(), scopeOf(f),
                    registry.find(f.getKey()).map(FieldDescriptor::name).orElse("?"),
                    oneLine(f.getKey()), f.getId());
        }
        log.warn("shadowed-field-def: {} custom field definition(s) are shadowed by built-in search names",
                shadowed.size());
    }

    /**
     * <strong>Line terminators out of any value a row supplies</strong> — the durable half of the
     * log-forging guard, and the half that is not on a DTO.
     *
     * <p>{@code UpsertFieldRequest} bounds {@code name} to {@link com.hamstrack.common.util.DisplayText#SINGLE_LINE}
     * and {@code key} to {@code [a-z0-9_]}, which closes the console door and only that door. This
     * class exists precisely because rows arrive by other routes — a migration, a seeder, direct
     * SQL, or the console itself before that annotation shipped — and none of them meet a DTO. A
     * row already carrying a newline in its display name would otherwise forge a second line under
     * the stable {@code shadowed-field-def:} prefix that the operator documentation tells people to
     * alert on, on every boot of an upgraded instance, for ever; no later validation can reach it,
     * because nothing rewrites the row. Escaping belongs at the sink, where every source passes.
     *
     * <p>A space rather than a removal, so a forged fragment cannot be glued onto the text around
     * it, and the length is unchanged. The id, the scope and the built-in name are not passed
     * through this: two are UUIDs and the third comes from our own registry.
     */
    private static String oneLine(String value) {
        return value == null ? null : LINE_TERMINATORS.matcher(value).replaceAll(" ");
    }

    /**
     * Any Unicode linebreak: {@code \R} matches CRLF as one unit as well as LF, CR, NEL and
     * the line/paragraph separators, so no shipper's idea of "a line" survives a value a row
     * supplied.
     */
    private static final Pattern LINE_TERMINATORS = Pattern.compile("\\R");

    /**
     * Where the row lives, in the words the operator's own SQL uses. A global row is one shadowing
     * that hits every workspace on the instance at once, so saying which of the three it is is the
     * difference between one tenant's problem and everyone's.
     */
    private static String scopeOf(FieldDef f) {
        if (f.getScopeProjectId() != null) return "project " + f.getScopeProjectId();
        if (f.getScopeWorkspaceId() != null) return "workspace " + f.getScopeWorkspaceId();
        return "global";
    }
}
