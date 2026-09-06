package com.hamstrack.admin.service;

import com.hamstrack.admin.dto.*;
import com.hamstrack.admin.scope.ScopeContext;
import com.hamstrack.auth.entity.User;
import com.hamstrack.common.util.ColorFormat;
import com.hamstrack.issue.entity.FieldDef;
import com.hamstrack.issue.entity.FieldSet;
import com.hamstrack.issue.entity.FieldSetItem;
import com.hamstrack.issue.entity.FieldType;
import com.hamstrack.issue.repository.*;
import com.hamstrack.search.RetiredFieldAliases;
import com.hamstrack.search.ShadowedFields;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.JDBCException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Custom field catalog + field set CRUD for the admin console. Deleting a
 * field that has values requires explicit confirmation (query param) and
 * drops the values — there is no meaningful "remap" for arbitrary shapes;
 * archiving is the safe alternative. Select options are referenced by id in
 * stored values, so removing an option leaves old values rendering the raw
 * id — the UI warns about it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminFieldService {

    private final FieldDefRepository fieldDefRepository;
    private final FieldSetRepository fieldSetRepository;
    private final FieldSetItemRepository fieldSetItemRepository;
    private final IssueFieldValueRepository valueRepository;
    private final ProjectCountService projectCountService;
    /**
     * The one answer to "has a built-in search name claimed this key" (HD-275 §5). Injected in
     * place of {@code FieldRegistry} so that create's refusal, the rename's permission and the
     * {@code shadowedBy} this service publishes are the same predicate — three surfaces that must
     * agree, and only had to be one before this ticket.
     */
    private final ShadowedFields shadowedFields;
    /**
     * The compatibility table for keys a release <em>retired</em> — consulted here, and only here
     * in this service, so that {@link #requireUnreservedKey} refuses a retired key by rule rather
     * than by the accident of a seeded placeholder happening to occupy it (HD-275 review §5).
     */
    private final RetiredFieldAliases retiredAliases;

    /**
     * Options in one SELECT/MULTI_SELECT field — a ceiling on a picker, not on content
     * (see {@link #requireSelectOptions}).
     */
    private static final int MAX_OPTIONS = 100;

    /** Max length of one option's id or label, matching {@code field_defs.name VARCHAR(100)}. */
    private static final int MAX_OPTION_TEXT = 100;

    /**
     * The whole {@code config} document, serialised, for <strong>every</strong> field type
     * (see {@link #requireConfigSize}).
     *
     * <p><strong>It overlaps the two option ceilings and is deliberately the tighter of the
     * two constraints at the extreme.</strong> A theoretical maximum SELECT — {@link #MAX_OPTIONS}
     * options each carrying a {@link #MAX_OPTION_TEXT}-character id <em>and</em> label — is about
     * 23 KB of JSON and is refused here, before {@link #requireSelectOptions} ever runs. That is
     * intended rather than an accident of arithmetic: 100 dropdown entries labelled with 100
     * characters each is not a picker anyone can use, and the realistic maximum is an order of
     * magnitude under this. Said out loud so the interaction is read as a decision, not
     * discovered later as a bug.
     */
    private static final int MAX_CONFIG_LENGTH = 20000;

    /**
     * Code points of a refused value quoted back inside a refusal — see {@link #safeEcho}.
     * A ceiling on a message, not on content: the shape it wants is in {@link ColorFormat#MESSAGE}
     * and does not depend on seeing all of what was sent.
     */
    private static final int MAX_ECHO = 40;

    // ---------- field defs ----------

    @Transactional(readOnly = true)
    public List<AdminFieldResponse> listFields(ScopeContext scope) {
        // Inherited fields are shown read-only in delegated consoles (see AdminCatalogService.listStatuses)
        var rows = scope.isGlobal()
                ? fieldDefRepository.findAllAtScope(null, null)
                : fieldDefRepository.findAllVisibleTo(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return rows.stream().map(f -> AdminFieldResponse.of(f, fieldUsage(scope, f), shadowedBy(f))).toList();
    }

    @Transactional
    public AdminFieldResponse createField(ScopeContext scope, UpsertFieldRequest req) {
        var key = req.key() == null || req.key().isBlank() ? slugify(req.name()) : req.key();
        requireUnreservedKey(key);
        if (fieldDefRepository.existsVisibleToAndKey(scope.visibleWorkspaceId(), scope.visibleProjectId(), key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A field with key '" + key + "' already exists or is inherited — reuse it instead of duplicating");
        }
        if (fieldDefRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A field named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        requireConfigSize(req);
        requireSelectOptions(req.type(), req);
        var f = new FieldDef();
        scope.stamp(f);
        f.setKey(key);
        f.setName(req.name());
        f.setType(req.type());
        f.setConfig(req.config());
        f.setDescription(req.description());
        persist(f);
        return AdminFieldResponse.of(f, new UsageInfo(0, 0, 0, 0), shadowedBy(f));
    }

    /**
     * Edit a field definition. <strong>The type is immutable; the key is fixed once created
     * <em>except</em> while a built-in search name has taken it</strong> (ADR-0036, HD-275 §6.2).
     *
     * <p>The old javadoc here said "type and key are immutable — stored values depend on both",
     * which was true of the type and false of the key: values live in {@code issue_field_values}
     * keyed by {@code field_id}, set membership by {@code field_id}, history records the display
     * <em>name</em>, and {@code FieldValueService} never reads the key. <strong>A rename moves no
     * rows.</strong> The real dependency is the text of saved filters — HQL is stored verbatim,
     * resolved at read time and never rewritten by anybody — which is exactly why the rename is
     * confined to the shadowed population: see {@link #renameTo}.
     *
     * <p><strong>The rename is triggered by DIFFERENCE, never by presence.</strong> Any client
     * that echoes the stored key back on save — as this console did until HD-275, and as a
     * round-tripping third-party client naturally would — would otherwise have every ordinary edit
     * of every unshadowed custom field answered 422, for changing nothing. The trigger is a
     * property of the request rather than of one caller's habits, so it holds for the next client
     * too.
     *
     * <p>Reads first, mutations last, then {@code saveAndFlush} — see {@link #persist}.
     */
    @Transactional
    public AdminFieldResponse updateField(User actor, ScopeContext scope, UUID id, UpsertFieldRequest req) {
        var f = requireField(scope, id);
        if (!f.getName().equals(req.name())
                && fieldDefRepository.existsVisibleToAndName(scope.visibleWorkspaceId(), scope.visibleProjectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A field named '" + req.name() + "' already exists or is inherited — reuse it instead of duplicating");
        }
        if (req.type() != null && req.type() != f.getType()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Field type cannot change once created — stored values depend on it");
        }
        requireConfigSize(req);
        requireSelectOptions(f.getType(), req);
        // Every refusal, including the rename's, is decided before anything is mutated: a field
        // set before a repository query would make Hibernate AUTO-flush write the row twice.
        String newKey = renameTo(scope, f, req.key());
        String oldKey = f.getKey();

        f.setName(req.name());
        f.setConfig(req.config());
        f.setDescription(req.description());
        if (newKey != null) f.setKey(newKey);
        persist(f);
        if (newKey != null) {
            // The only record a rename leaves. There is no taxonomy audit table today and building
            // one is a separate ticket (HD-275 §15 Q1); one INFO costs a line and is the difference
            // between "the key changed at some point" and an answer.
            log.info("field-key-rename: field {} renamed from '{}' to '{}' at scope {} by user {}",
                    f.getId(), oldKey, newKey, f.scopeLabel(), actor == null ? null : actor.getId());
        }
        return AdminFieldResponse.of(f, fieldUsage(scope, f), shadowedBy(f));
    }

    /**
     * Decide whether this update renames the field, and to what — <strong>every refusal in
     * {@link #updateField}'s rename family lives here, in the order the spec fixes</strong>
     * (HD-275 §6.2 R8). The order is load-bearing: the common mistake must be answered by the rule
     * that explains itself, not by a message about the target key.
     *
     * @return the new key to write, or {@code null} when this update renames nothing
     */
    private String renameTo(ScopeContext scope, FieldDef f, String requested) {
        // Absent, null, blank or equal (any casing) → not a rename, and not a refusal either.
        // Blank never means "re-derive from the display name": deriving would silently rename a
        // field every time a curator edited its label.
        if (requested == null || requested.isBlank()) return null;
        String key = requested.toLowerCase(Locale.ROOT);
        if (key.equalsIgnoreCase(f.getKey())) return null;

        // A system field's key is resolved BY KEY at runtime — DemoDataService looks up the global
        // `severity`/`environment` defs that way, and every V3 placeholder is a system def. 409
        // rather than 422 for the same reason deleteField answers 409: it is a collision with what
        // the row already is, not a rule about the request. Checked before the shadowing rule
        // because a claimed-key system field would otherwise be told it may rename.
        if (f.isSystem()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "System fields cannot be renamed.");
        }
        // The permission. claimedBy, not shadowing: an ARCHIVED claimed-key field may be renamed
        // too, because it is out of resolution entirely and so the licensing invariant holds even
        // harder for it — rename-then-unarchive is the recovery path for a field somebody archived
        // because it had stopped working.
        if (shadowedFields.claimedBy(f.getKey()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A field's key is fixed once created — saved filters are stored as text and refer "
                    + "to fields by key, so renaming one would silently change what they match. This "
                    + "key can only be changed while a built-in search name has taken it.");
        }
        // The target gets exactly the checks a create gets. Reserved first (§7.4): renaming
        // `labels` → `components` walks out of one shadow into another and would still be
        // shadowed, so the tenant could repeat it forever without ever escaping — the message must
        // name that, not the target's occupancy.
        requireUnreservedKey(key);
        // Wider than this scope ON PURPOSE (§7.2), and the widening is UPWARD only: the predicate
        // matches global rows, this scope's workspace and this scope's project, which is exactly
        // the reach create uses. Nothing BELOW the renaming scope is looked at, so a rename can
        // always land on a key some narrower row already holds, at every scope this endpoint is
        // mounted at — known, left as it is, and tracked as HD-284 (the downward occupancy check,
        // which both minting doors share and which cannot be widened here without changing what
        // create means). Two instances of that one shape, and the second is the larger:
        //  - WORKSPACE scope: visibleProjectId() is null, so a PROJECT-scoped row inside that very
        //    workspace matches no disjunct and the target key reads as free. One tenant's problem.
        //  - GLOBAL scope: both visible ids are null, so the predicate collapses to global rows
        //    alone and every workspace- and project-scoped row on the instance is invisible to it.
        //    An instance admin renaming a global shadowed field onto a key any tenant already uses
        //    therefore succeeds too — the same first-wins ambiguity, in every workspace at once.
        // The consequence worth writing down, and it is the same one at both scopes: after such a
        // collision a saved filter naming that key can begin resolving to a DIFFERENT field_defs
        // row, because ResolutionContextFactory.addCustomField is first-wins over visible-project
        // iteration order. Pinned as today's behaviour by ShadowedFieldKeyRenameTest so the next
        // change to it is a decision.
        if (fieldDefRepository.existsVisibleToAndKey(scope.visibleWorkspaceId(), scope.visibleProjectId(), key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A field with key '" + key + "' already exists or is inherited — reuse it instead of duplicating");
        }
        return key;
    }

    /**
     * <strong>{@code saveAndFlush}, and the catch that only works because of it</strong> (HD-275
     * §7.3). Spring Data's {@code save} merely queues the INSERT/UPDATE; Hibernate flushes at
     * commit, <em>after</em> this {@code @Transactional} method has returned, so a
     * {@code DataIntegrityViolationException} raised then is outside every {@code try} in the
     * service and the constraint violation stays a 500 with no way to translate it. Flushing here
     * brings the violation inside the frame that has a sentence for it.
     *
     * <p>{@code existsVisibleToAndKey}/{@code existsVisibleToAndName} are check-then-act, so two
     * admins renaming two fields onto the same key inside one scope both pass and one loses at
     * {@code field_defs_scope_key_key}. That loser gets a 409 rather than a 500.
     *
     * <p><strong>One message for both constraints</strong> ({@code _key_key} and {@code _name_key})
     * deliberately: telling them apart means string-matching a message the database owns, and the
     * caller's action is "reload" either way. Every non-racing path already answered the precise
     * message before reaching here.
     *
     * <p><strong>But only for a UNIQUE violation.</strong> {@link DataIntegrityViolationException}
     * is Spring's translation for the whole integrity family, so catching it flat would answer
     * "that key or name was taken — reload and try again" to a {@code 22001} string truncation
     * (which {@code GlobalExceptionHandler} deliberately answers <strong>400</strong> for, HD-171)
     * and to a {@code 23503} foreign-key violation — advice its reader cannot act on, in a loop,
     * with the diagnostic swallowed. Everything that is not {@code 23505} is rethrown untouched
     * and keeps the status its own handler gives it. Not reachable through this service's own
     * doors today; it is a class this repository has already paid for once.
     */
    private void persist(FieldDef f) {
        try {
            fieldDefRepository.saveAndFlush(f);
        } catch (DataIntegrityViolationException e) {
            if (!isUniqueViolation(e)) throw e;
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That key or name was taken by another change — reload and try again.", e);
        }
    }

    /**
     * Is this integrity violation a <strong>duplicate key</strong>, and nothing else?
     *
     * <p><strong>SQLSTATE, never the message.</strong> PostgreSQL's text is localisable, names the
     * constraint and belongs to the database rather than to us; a {@code contains("duplicate")}
     * couples this service to a string nobody here controls and starts matching the wrong things
     * the first time one is reworded. {@code GlobalExceptionHandler.sqlStateOf} answers the same
     * question for the same reason — this is the local, single-state form of it, kept private
     * because it decides one refusal rather than a family of them.
     *
     * <p>The chain is <em>walked</em> rather than inspected at one level: the shape here is
     * {@code DataIntegrityViolationException → org.hibernate.exception.ConstraintViolationException
     * → SQLException}, and either of the last two can be the one carrying the state depending on
     * how the translator built it. {@link org.springframework.dao.DuplicateKeyException} short-
     * circuits it: when the translator has already made that judgement there is nothing to
     * re-derive.
     */
    private static boolean isUniqueViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) return true;
        Throwable t = e;
        // Bounded rather than merely self-reference-guarded, for the reason sqlStateOf is: a
        // re-wrapping framework can produce A -> B -> A, and this runs while a write is failing.
        //
        // The 20 is deliberately a SECOND bound and not GlobalExceptionHandler.MAX_CAUSE_DEPTH
        // shared. The two walks answer different questions and stop on different rules: that one
        // returns the FIRST SQLSTATE it meets and stops, because it classifies a failure it has
        // never seen; this one keeps walking until it finds 23505 anywhere, because it is asking
        // whether one specific state is present. Sharing the number would publish a common policy
        // that does not exist and would leave the difference that actually matters untouched — and
        // the number is a runaway guard, not a contract, so the two drifting apart costs nothing.
        // Both are far past any real chain, so neither is reachable in practice.
        for (int depth = 0; t != null && depth < 20; t = t.getCause(), depth++) {
            if (t instanceof SQLException se && "23505".equals(se.getSQLState())) return true;
            if (t instanceof JDBCException je && "23505".equals(je.getSQLState())) return true;
        }
        return false;
    }

    /** The built-in search name claiming this field's key, or null — see {@link AdminFieldResponse}. */
    private String shadowedBy(FieldDef f) {
        return shadowedFields.claimedBy(f.getKey()).orElse(null);
    }

    @Transactional
    public void setFieldArchived(ScopeContext scope, UUID id, boolean archived) {
        var f = requireField(scope, id);
        f.setArchivedAt(archived ? Instant.now() : null);
        fieldDefRepository.save(f);
    }

    @Transactional
    public void deleteField(ScopeContext scope, UUID id, boolean dropValues) {
        var f = requireField(scope, id);
        if (f.isSystem()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "System fields can only be archived, not deleted.");
        }
        // TWO COUNTS, exactly as AdminCatalogService's three deletes do it. THE DECISION MUST
        // COVER THE POPULATION THE DELETE AFFECTS — remapped or cascaded; only the MESSAGE may
        // be narrower.
        //
        // An earlier draft of this fix scoped the decision too, on the reasoning that this guard
        // protects no remap: `issue_field_values` cascades via FK, so nothing here can be
        // stranded and no 23503 can follow. That is true about STRANDING and silent about
        // CONSENT, which is the other thing this guard does. `dropValues` is the caller agreeing
        // to destroy data, and the cascade it authorises is unscoped. Compare the degenerate
        // state the pair exists to survive (scoped count 0, unscoped count > 0):
        //
        //   * catalog three — unscoped decides, so the delete is REFUSED. Nothing happens.
        //   * deleteField, scoped — the delete PROCEEDS, and ON DELETE CASCADE removes another
        //     tenant's issue_field_values rows silently, with dropValues never asked for.
        //
        // So the one place the pattern was not applied had the strictly worse degradation. And
        // "unreachable today by construction" is the argument this ticket already refused as a
        // reason to collapse the catalog counts into one; it cannot be accepted here.
        long values = valueRepository.countByField(f);
        if (values > 0 && !dropValues) {
            long mine = valueRepository.countByFieldScoped(f, scope.workspaceId(), scope.projectId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, valuesInUse(mine));
        }
        // set memberships + values cascade via FK
        fieldDefRepository.delete(f);
    }

    // ---------- field sets ----------

    @Transactional(readOnly = true)
    public List<AdminFieldSetResponse> listSets(ScopeContext scope) {
        var sets = scope.isGlobal()
                ? fieldSetRepository.findAllAtScope(null, null)
                : fieldSetRepository.findAllBindableForProject(scope.visibleWorkspaceId(), scope.visibleProjectId());
        return sets.stream().map(set -> toSetResponse(scope, set)).toList();
    }

    @Transactional
    public AdminFieldSetResponse createSet(ScopeContext scope, UpsertFieldSetRequest req) {
        if (fieldSetRepository.existsAtScopeAndName(scope.workspaceId(), scope.projectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field set name already exists");
        }
        var set = new FieldSet();
        scope.stamp(set);
        set.setName(req.name());
        fieldSetRepository.save(set);
        applyItems(scope, set, req);
        return toSetResponse(scope, set);
    }

    @Transactional
    public AdminFieldSetResponse updateSet(ScopeContext scope, UUID id, UpsertFieldSetRequest req) {
        var set = requireSet(scope, id);
        if (!set.getName().equals(req.name())
                && fieldSetRepository.existsAtScopeAndName(scope.workspaceId(), scope.projectId(), req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Field set name already exists");
        }
        set.setName(req.name());
        fieldSetRepository.save(set);
        fieldSetItemRepository.deleteAllBySet(set);
        // Flush DELETEs before re-inserting — Hibernate orders INSERTs ahead of
        // DELETEs in one flush, colliding with UNIQUE(set_id, field_id).
        fieldSetItemRepository.flush();
        applyItems(scope, set, req);
        return toSetResponse(scope, set);
    }

    @Transactional
    public void deleteSet(ScopeContext scope, UUID id) {
        var set = requireSet(scope, id);
        if (set.isSystemDefault()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The system default field set cannot be deleted");
        }
        long projects = projectsUsing(scope, set);
        if (projects > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    projects + " projects use this field set — reassign them first");
        }
        fieldSetRepository.delete(set);
    }

    public long projectsUsing(ScopeContext scope, FieldSet set) {
        return projectCountService.projectsUsingFieldSet(scope, set);
    }

    // ---------- usage detail (popovers) ----------

    @Transactional(readOnly = true)
    public UsageDetailResponse fieldUsageDetail(ScopeContext scope, UUID id) {
        var f = requireField(scope, id);
        var sets = fieldSetItemRepository.findSetsUsingField(f.getId()).stream()
                .filter(scope::canSee).toList();
        var projects = sets.stream()
                .flatMap(set -> projectCountService.projectsListUsingFieldSet(scope, set).stream())
                .toList();
        return new UsageDetailResponse(
                List.of(),
                sets.stream().map(FieldSet::getName).toList(),
                UsageDetailResponse.dedupe(projects),
                valueRepository.countByFieldScoped(f, scope.workspaceId(), scope.projectId()));
    }

    // ---------- helpers ----------

    private void applyItems(ScopeContext scope, FieldSet set, UpsertFieldSetRequest req) {
        var seen = new HashSet<UUID>();
        short pos = 0;
        for (var itemReq : req.items()) {
            if (!seen.add(itemReq.fieldId())) continue;
            // A field the set may include: visible to this scope (global ∪ ancestor-ws ∪ own project)
            var field = fieldDefRepository.findByIdVisibleTo(
                            itemReq.fieldId(), scope.visibleWorkspaceId(), scope.visibleProjectId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown field"));
            var item = new FieldSetItem();
            item.setSet(set);
            item.setField(field);
            item.setPosition(pos++);
            item.setRequired(itemReq.required());
            // A required field the create form doesn't show would make creation impossible
            item.setShowOnCreate(itemReq.required() || itemReq.showOnCreate());
            fieldSetItemRepository.save(item);
        }
    }

    /**
     * <strong>The whole {@code config} document, bounded once for every field type</strong>
     * (HD-171 §4.3, round 2). {@code config} is a {@code JsonNode}, so no {@code @Size} on the
     * DTO reaches inside it, and {@code field_defs.config JSONB} has no width to overflow — so
     * bean validation genuinely cannot bound it and a service check must.
     *
     * <p><strong>Why this exists on top of the two option ceilings, which is the whole lesson:
     * those bound two <em>leaves</em>, and this bounds the <em>document</em>.</strong> Round 1
     * bounded {@code options[].id} and {@code options[].label} and then described {@code config}
     * as bounded, which it was not:
     * {@code {"options":[{"id":"a","label":"b","color":"<20 M characters>"}]}} passed, so did any
     * unrelated top-level key, and so did the entire config of every non-SELECT type, which never
     * enters that branch at all. A bound on the members of a set is not a bound on the set.
     *
     * <p><strong>And this field is egress.</strong> {@code ProjectConfigController} returns
     * {@code config} to every project member on the endpoint the SPA fetches for every board and
     * every issue form — so an unbounded document is not a stored blob, it is hundreds of
     * megabytes re-served on every page load, plantable by any workspace admin (contained to
     * their own tenant, which is what keeps it Low rather than High).
     *
     * <p>422 rather than 400, like every other refusal on this path: the request is well-formed
     * and the product declines it. Measured on the compact serialisation, which is what is stored
     * and what is re-served, not on the request's own whitespace.
     */
    private void requireConfigSize(UpsertFieldRequest req) {
        var cfg = req.config();
        if (cfg == null) return;
        int size = cfg.toString().length();
        if (size > MAX_CONFIG_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Field config is " + size + " characters; the maximum is " + MAX_CONFIG_LENGTH
                    + ". This document is sent to every project member on every board load.");
        }
    }

    /**
     * Everything an <em>option</em> must satisfy — bounds on the leaves, with
     * {@link #requireConfigSize} bounding the document they live in (HD-171 §4.3), and
     * {@link #requireOptionColor} on the one leaf that is a colour (HD-176 §7.2). 422 throughout,
     * for the same reason.
     *
     * <p>The numbers are ceilings on a picker, not on prose: 100 options is far past any usable
     * dropdown, and an option {@code id} is read back by {@code FieldValueService.optionIds} on
     * <em>every</em> custom-field write, so an unbounded one is unbounded work on a hot path.
     */
    private void requireSelectOptions(FieldType type, UpsertFieldRequest req) {
        if (type == FieldType.SELECT || type == FieldType.MULTI_SELECT) {
            var cfg = req.config();
            if (cfg == null || !cfg.has("options") || !cfg.get("options").isArray() || cfg.get("options").isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Select fields need at least one option");
            }
            if (cfg.get("options").size() > MAX_OPTIONS) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "A select field can have at most " + MAX_OPTIONS + " options");
            }
            for (var opt : cfg.get("options")) {
                if (!opt.hasNonNull("id") || opt.get("id").asText().isBlank()
                        || !opt.hasNonNull("label") || opt.get("label").asText().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Every option needs an id and a label");
                }
                if (opt.get("id").asText().length() > MAX_OPTION_TEXT
                        || opt.get("label").asText().length() > MAX_OPTION_TEXT) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "An option id and label must each be at most " + MAX_OPTION_TEXT
                            + " characters");
                }
                requireOptionColor(opt);
            }
        }
    }

    /**
     * <strong>An option's {@code color} is a colour</strong> (HD-176 §7.2) — the format refusal
     * this path never had. Until now {@code config.options[].color} was validated as
     * <em>nothing</em>: {@code "red"}, {@code ""} and {@code "javascript:…"} were all stored and
     * re-served from the project-config endpoint the SPA fetches for <em>every</em> board and every
     * issue form. The three sibling colour columns (statuses / priorities / issue types) have
     * carried a {@code @Pattern} on their DTO since V1; this one could not, because {@code config}
     * is a {@code JsonNode} and no bean-validation annotation reaches inside it — the same reason
     * {@link #requireConfigSize} has to exist as a service check.
     *
     * <p><strong>422, not 400</strong>, matching every other refusal on this path: the request is
     * well-formed and the product declines it. The <em>sentence</em> comes from
     * {@link ColorFormat} so that a label and a select option answer the same wording to the same
     * mistake, and it names the shape it wants rather than only reporting a refusal. The offending
     * option's id is quoted because a select may carry up to {@link #MAX_OPTIONS} of them and
     * "one of your options is wrong" is not an actionable message; both it and the value go through
     * {@link #safeEcho}, which bounds and sanitises anything quoted back into a refusal.
     *
     * <p><strong>Absent is legal, blank is not.</strong> A missing or JSON-{@code null} colour
     * means "no colour" and always has — {@code FieldValueDisplay} falls back to a neutral token —
     * which is exactly what a {@code @Pattern} does with a {@code null} on the sibling paths. An
     * empty string is a value, and it fails there too.
     *
     * <p><strong>This is a FORMAT check and never a contrast check.</strong> A stored colour is an
     * identity hue and the readable foreground is derived from it at render time (ADR-0027), so
     * {@code #FFFF00} is accepted here on purpose.
     */
    private void requireOptionColor(com.fasterxml.jackson.databind.JsonNode opt) {
        if (!opt.hasNonNull("color")) return;
        var node = opt.get("color");
        var value = node.isTextual() ? node.asText() : node.toString();
        if (!ColorFormat.isValid(value)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Option '" + safeEcho(opt.get("id").asText()) + "' has color '" + safeEcho(value)
                    + "' — " + ColorFormat.MESSAGE);
        }
    }

    /**
     * <strong>A value echoed back inside a refusal is bounded and encodable</strong> (HD-176
     * review, Low 1). Both of this path's echoes go through here — the offending colour and the
     * option id that names which option is meant — and it does two things:
     *
     * <p><strong>It bounds.</strong> {@link #MAX_CONFIG_LENGTH} is the only thing bounding the
     * colour, so a 20 000-character "colour" would otherwise be quoted back in full.
     *
     * <p><strong>And it cuts where it is allowed to cut, which is the part that was a defect.</strong>
     * The previous spelling was {@code value.substring(0, 40)}, counting UTF-16 code units: a value
     * whose 41st unit is the low half of a surrogate pair — an admin pasting an emoji into a colour
     * box — was severed between the halves, leaving a lone high surrogate in the {@code detail} of
     * the {@code ProblemDetail}.
     *
     * <p><strong>What that actually produced is worth writing down, because the obvious guess is
     * wrong and was guessed wrong during review.</strong> Jackson's UTF-8 generator neither throws
     * nor substitutes: a surrogate it cannot encode is emitted as an escape, so the endpoint
     * answered an ordinary 422 whose body carried an <em>unpaired surrogate escape</em>. Not a 5xx,
     * not a truncated write — a refusal that decodes, in every client, to a message with an
     * unrenderable character where the offending value should be, and which any consumer that
     * re-encodes it (a log shipper, an error tracker, anything writing UTF-8 bytes) mangles or
     * rejects on its own terms. Being wrong about which of the three it was is precisely why
     * {@code AdminFieldOptionColorFormatTest} asserts the <em>decoded</em> {@code detail} and not
     * the status: the status was never the tell, and a status-only test would have passed against
     * the defect.
     *
     * <p>So the loop advances by <em>code point</em> and never splits a pair; a surrogate with no
     * partner cannot be encoded at all and becomes {@code '?'}, as does an ISO control, which would
     * otherwise put a line break into a string this application both returns and logs. The count is
     * code points, so the bound is on what a reader sees rather than on how the text is stored.
     *
     * <p><strong>Legible text is left alone, deliberately.</strong> An option id may be Cyrillic or
     * CJK and is the only part of the message that says <em>which</em> of up to
     * {@link #MAX_OPTIONS} options is meant, so flattening the echo to ASCII would trade a mangled
     * character for a refusal naming an option nobody can find. This is not a confusable or bidi
     * rule either — see {@code DisplayText} for that, which guards text being <em>stored</em>;
     * here the string is the caller's own input reflected straight back to the caller.
     */
    private static String safeEcho(String value) {
        var sb = new StringBuilder(Math.min(value.length(), MAX_ECHO) + 1);
        int shown = 0;
        int i = 0;
        while (i < value.length() && shown < MAX_ECHO) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                sb.append(c).append(value.charAt(i + 1));   // a whole code point, never half of one
                i += 2;
            } else if (Character.isSurrogate(c) || Character.isISOControl(c)) {
                sb.append('?');
                i++;
            } else {
                sb.append(c);
                i++;
            }
            shown++;
        }
        return i < value.length() ? sb + "…" : sb.toString();
    }

    private UsageInfo fieldUsage(ScopeContext scope, FieldDef f) {
        var sets = fieldSetItemRepository.findSetsUsingField(f.getId()).stream()
                .filter(scope::canSee).toList();
        long projects = sets.stream()
                .mapToLong(set -> projectCountService.projectsUsingFieldSet(scope, set)).sum();
        return new UsageInfo(0, sets.size(), projects,
                valueRepository.countByFieldScoped(f, scope.workspaceId(), scope.projectId()));
    }

    private AdminFieldSetResponse toSetResponse(ScopeContext scope, FieldSet set) {
        var items = fieldSetItemRepository.findAllBySetOrderByPosition(set).stream()
                .map(i -> new AdminFieldSetResponse.Item(
                        // usage is null here (a set listing does not compute it), but shadowedBy is
                        // NOT: the console renders these nested field rows too, and a field that
                        // is warned about in one list and silent in another is the silence this
                        // ticket exists to end.
                        AdminFieldResponse.of(i.getField(), null, shadowedBy(i.getField())),
                        i.isRequired(), i.isShowOnCreate()))
                .toList();
        return new AdminFieldSetResponse(set.getId(), set.getName(), set.isSystemDefault(),
                items, projectsUsing(scope, set), set.scopeLabel());
    }

    private String slugify(String name) {
        var slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return slug.isBlank() ? "field" : slug.substring(0, Math.min(slug.length(), 50));
    }

    /**
     * Refuse a key the HQL vocabulary has claimed (HD-101). A registered search field always
     * outranks a custom field of the same key, so such a field would be born half-invisible: it
     * would work everywhere in the product except search, where the name means the system field
     * and {@code /schema} silently omits the tenant's — no error, no log line, no affordance.
     *
     * <p><strong>Checked after slugification.</strong> The key is derived from the display name
     * when omitted, so a field a curator simply calls "Project" walks straight past a check placed
     * before it — which is exactly how this collision arises without anybody choosing it.
     *
     * <p><strong>Still never retroactive.</strong> It guards the two doors that MINT a key — create,
     * and the rename's target (HD-275) — and it must never reject or migrate a row that already
     * exists. Rows that predate a registration are exactly the shadowed population this endpoint's
     * rename now offers an exit to; refusing them here would refuse the exit as well.
     *
     * <h4>A RETIRED key is refused too, by its own rule and with its own sentence</h4>
     * A {@link com.hamstrack.search.RetiredFieldAliases} entry is not a registry name: it is
     * consulted <em>last</em>, so a tenant that mints a custom field keyed {@code story_points} or
     * {@code fix_version} is not shadowed — the opposite happens. Their field wins, the alias stops
     * firing for everyone the field is visible to, and <strong>every saved filter written before
     * that key was retired silently changes what it matches</strong>, from the native column to a
     * custom field somebody just created. For a GLOBAL def that is the whole instance at once.
     *
     * <p><strong>This was a protection somebody could delete without noticing.</strong> Both keys
     * already answered 409, but only because V1/V3 seeded global placeholders under them and
     * {@code existsVisibleToAndKey} counts archived rows — an accident of seed data doing the work
     * of a rule. A future retirement of a key that never had a placeholder would have opened
     * silently. It is a rule now, so the guarantee survives the row.
     */
    private void requireUnreservedKey(String key) {
        if (shadowedFields.claimedBy(key).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + key + "' is a reserved search field name — pick a different key "
                            + "(a custom field with this key would not be searchable)");
        }
        retiredAliases.canonicalName(key).ifPresent(canonical -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "'" + key + "' is a retired search field name — pick a different key (saved "
                            + "filters written before it was retired still resolve it, to the "
                            + "built-in '" + canonical + "' field, and a custom field with this key "
                            + "would take that name over and change what they match)");
        });
    }

    private FieldDef requireField(ScopeContext scope, UUID id) {
        return fieldDefRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field not found"));
    }

    private FieldSet requireSet(ScopeContext scope, UUID id) {
        return fieldSetRepository.findByIdAtScope(id, scope.workspaceId(), scope.projectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Field set not found"));
    }

    /**
     * The "values would be destroyed" refusal, built from the <strong>scoped</strong> count while
     * a different, unscoped count made the decision — the same pair
     * {@code AdminCatalogService.inUse} uses, and the same degradation.
     *
     * <p><strong>The zero-scoped branch quotes no number AND prescribes nothing</strong>, exactly
     * as {@code AdminCatalogService.inUse} does, and for the same structural reason: in that
     * state the caller can see none of the affected rows, so "pass {@code dropValues=true} to
     * delete them" would be prescribing an <em>unscoped cascade over rows they cannot see</em>.
     * Archiving is the only remedy that is safe without visibility, so it is the only one named.
     *
     * <p>Weaker here than in its twin, and neutralised anyway. {@code dropValues} is this
     * endpoint's own consent flag, so a caller could pass it regardless — where
     * {@code replaceWithId} additionally repointed foreign rows at a replacement drawn from the
     * caller's scope — and the state is unreachable today. It is fixed because leaving one half
     * of a matched pair alone is how the next reader concludes the two cases differ on purpose.
     *
     * <p>The {@code mine >= 1} branch stays prescriptive: there the caller has visible rows, the
     * count is theirs, and this is the normal path.
     */
    private static String valuesInUse(long mine) {
        if (mine == 0) {
            return "This field is still in use — archive it instead";
        }
        return (mine == 1 ? "1 issue has a value for this field"
                          : mine + " issues have a value for this field")
               + " — pass dropValues=true to delete " + (mine == 1 ? "it" : "them")
               + ", or archive instead";
    }
}
