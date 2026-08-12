package com.hamstrack.search.filter.service;

import com.hamstrack.auth.entity.User;
import com.hamstrack.search.HqlValidator;
import com.hamstrack.search.filter.dto.CreateSavedFilterRequest;
import com.hamstrack.search.filter.dto.SavedFilterResponse;
import com.hamstrack.search.filter.dto.SavedFilterUsageResponse;
import com.hamstrack.search.filter.dto.UpdateSavedFilterRequest;
import com.hamstrack.search.filter.entity.SavedFilter;
import com.hamstrack.search.filter.exception.SavedFilterNameBlankException;
import com.hamstrack.search.filter.exception.SavedFilterNameConflictException;
import com.hamstrack.search.filter.exception.SavedFilterNotFoundException;
import com.hamstrack.search.filter.repository.SavedFilterRepository;
import com.hamstrack.search.parser.HqlParser;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.exception.WorkspaceNotFoundException;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
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

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final SavedFilterRepository savedFilterRepository;
    private final HqlValidator validator;

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
        validateHql(req.hqlOrEmpty());

        if (savedFilterRepository.existsByWorkspaceAndOwnerAndName(ws, actor, req.name())) {
            throw new SavedFilterNameConflictException(req.name());
        }

        var filter = new SavedFilter();
        filter.setWorkspace(ws);
        filter.setOwner(actor);
        filter.setName(req.name());
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
            validateHql(req.hql());
        }

        // Name change → re-check (workspace, owner) uniqueness (present, non-blank, changed).
        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) {
                throw new SavedFilterNameBlankException();
            }
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

    /** Parse + structural validate the stored HQL; throws the search 422 exceptions. */
    private void validateHql(String hql) {
        validator.validate(HqlParser.parse(hql));
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
        var workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(WorkspaceNotFoundException::new);
        workspaceMemberRepository.findByWorkspaceAndUser(workspace, actor)
                .orElseThrow(WorkspaceNotFoundException::new);
        return workspace;
    }
}
