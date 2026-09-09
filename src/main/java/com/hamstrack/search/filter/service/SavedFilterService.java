package com.hamstrack.search.filter.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.service.ClassificationNames;
import com.hamstrack.search.HqlValidator;
import com.hamstrack.search.ResolutionContextFactory;
import com.hamstrack.search.SearchNames;
import com.hamstrack.search.filter.dto.CreateSavedFilterRequest;
import com.hamstrack.search.filter.dto.SavedFilterResponse;
import com.hamstrack.search.filter.dto.SavedFilterUsageResponse;
import com.hamstrack.search.filter.dto.UpdateSavedFilterRequest;
import com.hamstrack.search.filter.entity.SavedFilter;
import com.hamstrack.search.filter.exception.SavedFilterNameConflictException;
import com.hamstrack.search.filter.exception.SavedFilterNotFoundException;
import com.hamstrack.search.filter.repository.SavedFilterRepository;
import com.hamstrack.search.parser.HqlParser;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.service.WorkspaceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for saved filters (HD-26, Advanced Search proposal §8.3). A saved filter is a
 * reusable HQL data source scoped to one workspace. Enforces:
 *
 * <ul>
 *   <li><strong>Membership gate</strong> — {@link #resolveWorkspace} returns 404 (not
 *       403) whether the workspace is missing or the caller isn't a member, exactly
 *       like {@code SearchService} (no existence leak — the project's #1 bug class);</li>
 *   <li><strong>Visibility</strong> — list/get return the caller's own filters plus
 *       shared ones; a private filter is 404 for anyone but its owner;</li>
 *   <li><strong>Owner-only mutation</strong> — update/delete require the caller to be
 *       the owner; a non-owner (even on a <em>shared</em> filter) gets 404, never 403
 *       (§16.5 — don't confirm the id exists);</li>
 *   <li><strong>Save-time validation</strong> — the stored HQL is parsed and
 *       structurally validated against the {@link com.hamstrack.search.FieldRegistry}
 *       on create and on any hql change; a bad query surfaces as the same 422 shape as
 *       search ({@code HqlParseException}/{@code HqlSemanticException}). Value
 *       resolution ({@code currentUser()}, status-name→id, …) is deliberately
 *       <em>deferred to run time</em>: those values are caller-relative and a
 *       later-archived catalog row must not permanently break a saved query.</li>
 * </ul>
 *
 * <p>Stored HQL is never executed here — only parsed/validated. It runs later through
 * the HD-21 engine, which binds every literal as a parameter (injection-safe).
 */
@Service
@RequiredArgsConstructor
public class SavedFilterService {

    /**
     * Post-canonicalisation bound on a saved-filter name — an ADR-0017 repeated literal, equal to
     * {@code @Size(max = 120)} on {@link CreateSavedFilterRequest} / {@link UpdateSavedFilterRequest}
     * (the raw text) and to {@code @Column(length = 120)} on {@link SavedFilter} /
     * {@code saved_filters.name VARCHAR(120)} (V5). The three are kept equal by hand:
     * {@code ddl-auto=validate} does not compare widths, and the entity annotation never
     * reaches the request record. A name that is within 120 raw and over 120 canonical is the
     * case only this constant catches.
     */
    static final int MAX_NAME_LENGTH = 120;

    private final WorkspaceAccessService workspaceAccess;
    private final SavedFilterRepository savedFilterRepository;
    private final HqlValidator validator;
    private final ResolutionContextFactory resolutionContextFactory;

    @Transactional(readOnly = true)
    public List<SavedFilterResponse> list(User actor, UUID workspaceId) {
        var ws = resolveWorkspace(actor, workspaceId);
        return savedFilterRepository.findVisible(ws, actor).stream()
                .map(f -> SavedFilterResponse.of(f, actor.getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SavedFilterResponse get(User actor, UUID workspaceId, UUID filterId) {
        var ws = resolveWorkspace(actor, workspaceId);
        var filter = requireVisible(actor, ws, filterId);
        return SavedFilterResponse.of(filter, actor.getId());
    }

    @Transactional
    public SavedFilterResponse create(User actor, UUID workspaceId, CreateSavedFilterRequest req) {
        var ws = resolveWorkspace(actor, workspaceId);

        // Save-time validation: parse + structural (no value resolution) — 422 on a
        // bad query, same ProblemDetail shape as search.
        validateHql(actor, ws, req.hqlOrEmpty());

        String name = canonicalName(req.name());
        if (savedFilterRepository.existsByWorkspaceAndOwnerAndName(ws, actor, name)) {
            throw new SavedFilterNameConflictException(name);
        }

        var filter = new SavedFilter();
        filter.setWorkspace(ws);
        filter.setOwner(actor);
        filter.setName(name);
        filter.setHql(req.hqlOrEmpty());
        filter.setShared(req.shared() != null && req.shared());

        var saved = savedFilterRepository.save(filter);
        return SavedFilterResponse.of(saved, actor.getId());
    }

    @Transactional
    public SavedFilterResponse update(User actor, UUID workspaceId, UUID filterId, UpdateSavedFilterRequest req) {
        var ws = resolveWorkspace(actor, workspaceId);
        var filter = requireOwned(actor, ws, filterId);

        // Re-validate HQL only when it's being changed.
        if (req.hql() != null) {
            validateHql(actor, ws, req.hql());
        }

        // Name change → re-check (workspace, owner) uniqueness (present, non-blank, changed).
        if (req.name() != null) {
            String name = canonicalName(req.name());
            if (!name.equals(filter.getName())
                    && savedFilterRepository.existsByWorkspaceAndOwnerAndName(ws, actor, name)) {
                throw new SavedFilterNameConflictException(name);
            }
            filter.setName(name);
        }
        if (req.hql() != null) {
            filter.setHql(req.hql());
        }
        if (req.shared() != null) {
            filter.setShared(req.shared());
        }

        var saved = savedFilterRepository.save(filter);
        return SavedFilterResponse.of(saved, actor.getId());
    }

    /**
     * The one canonical form of a saved-filter name, on both doors that write it (HD-297), and
     * the one place its length is measured. {@link ClassificationNames#requireValidName} is the
     * label/component/version/sprint write-side gate — {@link SearchNames#canonical}'s
     * normalisation (NFC, invisible characters dropped, separator runs collapsed to one space,
     * stripped), then the blank refusal, then the length refusal on the <em>canonical</em> form —
     * so the {@code (workspace, owner, name)} uniqueness check and the stored value agree with
     * each other and with what search compares against. Until HD-297 {@code create} stored the
     * raw name and {@code update} a {@code trim()}med one — the two doors disagreed — and the
     * first fix of that measured nothing after canonicalising: {@code @Size(max = 120)} bounds
     * the raw text, NFC lengthens composition-exclusion characters (120 × U+0958 → 240), and
     * the {@code VARCHAR(120)} refused the commit with a 22001 that the global handler answers
     * 400 and logs at ERROR — an on-demand ERROR-log generator for any member. Both refusals are
     * 400 (plain input validation, not the HQL 422 envelope) and name the noun and the limit.
     */
    private static String canonicalName(String raw) {
        return ClassificationNames.requireValidName(raw, MAX_NAME_LENGTH, "Filter");
    }

    /**
     * Report what would block a delete (the delete-with-usage warning hook, §10.4).
     * <strong>Always empty in MVP</strong> — no board/report consumes a filter yet;
     * the contract exists so the SPA confirm-dialog is built and the future consumer
     * wiring is drop-in. Owner-only (404 otherwise), so the read matches the delete.
     */
    @Transactional(readOnly = true)
    public SavedFilterUsageResponse usage(User actor, UUID workspaceId, UUID filterId) {
        var ws = resolveWorkspace(actor, workspaceId);
        requireOwned(actor, ws, filterId);
        return SavedFilterUsageResponse.empty();
    }

    /**
     * Delete a saved filter. Owner-only (404 otherwise). MVP has no consumers, so the
     * filter is always deleted; when boards/reports consume filters the future
     * {@code usage(...)} check + a {@code ?force=} guard hang off this method (§10.4).
     */
    @Transactional
    public void delete(User actor, UUID workspaceId, UUID filterId) {
        var ws = resolveWorkspace(actor, workspaceId);
        var filter = requireOwned(actor, ws, filterId);
        // Future (board/report epic): if usage(...).inUse() and !force → 409 usedBy.
        savedFilterRepository.delete(filter);
    }

    // ---- helpers ----

    /**
     * Parse + structural validate the stored HQL; throws the search 422 exceptions.
     * Builds the caller's per-request context so custom fields (HD-52) validate at
     * save time exactly as they would at run time. Value resolution stays deferred.
     */
    private void validateHql(User actor, Workspace ws, String hql) {
        var ctx = resolutionContextFactory.build(actor, ws);
        validator.validate(HqlParser.parse(hql), ctx);
    }

    /** Own-or-shared visibility (read paths): 404 for a private filter of another owner. */
    private SavedFilter requireVisible(User actor, Workspace ws, UUID filterId) {
        var filter = savedFilterRepository.findByIdAndWorkspace(filterId, ws)
                .orElseThrow(SavedFilterNotFoundException::new);
        boolean visible = filter.getOwner().getId().equals(actor.getId()) || filter.isShared();
        if (!visible) {
            throw new SavedFilterNotFoundException();
        }
        return filter;
    }

    /** Owner-only (mutate paths): a non-owner — even on a shared filter — gets 404 (§16.5). */
    private SavedFilter requireOwned(User actor, Workspace ws, UUID filterId) {
        var filter = savedFilterRepository.findByIdAndWorkspace(filterId, ws)
                .orElseThrow(SavedFilterNotFoundException::new);
        if (!filter.getOwner().getId().equals(actor.getId())) {
            throw new SavedFilterNotFoundException();
        }
        return filter;
    }

    /** Membership gate — 404 (not 403) whether the ws is missing or the caller isn't a member. */
    private Workspace resolveWorkspace(User actor, UUID workspaceId) {
        return workspaceAccess.requireMember(actor, workspaceId).workspace();
    }
}
