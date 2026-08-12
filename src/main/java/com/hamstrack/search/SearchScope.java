package com.hamstrack.search;

import com.hamstrack.auth.entity.User;
import com.hamstrack.issue.entity.Issue;
import com.hamstrack.project.entity.Project;
import com.hamstrack.project.repository.ProjectRepository;
import com.hamstrack.workspace.entity.Workspace;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The hard tenant/visibility boundary for HQL search, expressed as a Criteria
 * <strong>predicate builder</strong> — deliberately NOT a {@code List<UUID>} of
 * visible project ids (Advanced Search proposal §3.1.1). Every value inside the
 * predicate is derived server-side from the authenticated actor and the resolved
 * workspace; nothing here comes from query text.
 *
 * <p><strong>Invariant:</strong> {@link HqlCompiler} always ANDs
 * {@link #scopePredicate} onto the parsed predicate as the <em>outermost</em>
 * conjunction. The parsed query can only narrow <em>within</em> this boundary; no
 * parsed token can widen or remove it. This is the project's #1 bug class — the
 * scope is the only thing standing between one tenant and another.
 *
 * <p>Today the predicate is {@code workspace = :ws AND project.id IN
 * :visibleProjectIds}, where {@code visibleProjectIds} = the workspace's
 * <em>non-archived</em> projects — matching {@code IssueService}'s read surface
 * exactly (§3.1, §16.7; archived projects excluded per §16.6).
 */
@Component
@RequiredArgsConstructor
public class SearchScope {

    private final ProjectRepository projectRepository;

    /**
     * The set of project ids visible to {@code actor} within {@code ws}. Today this
     * is every non-archived project of the workspace (mirrors {@code IssueService}).
     *
     * <p>NOTE (§3.1.2): the planned 3-layer access model (public/private projects +
     * intra-project per-type grants) changes ONLY this method and
     * {@link #scopePredicate}. When public/private projects land, this computes the
     * actor-visible subset instead of "all non-archived"; when intra-project grants
     * land, {@link #scopePredicate} ANDs a further row-level clause. The grammar,
     * parser, AST, {@link FieldRegistry}, endpoints and frontend are untouched.
     */
    public List<UUID> visibleProjectIds(User actor, Workspace ws) {
        return projectRepository.findAllByWorkspaceAndArchivedAtIsNull(ws).stream()
                .map(Project::getId)
                .toList();
    }

    /**
     * The outermost tenant conjunction ANDed onto every compiled search. Built
     * entirely from server-side state (the resolved {@code ws} + the actor's visible
     * project ids) — never from query text.
     *
     * <p>NOTE (§3.1.2): future layer-2/layer-3 access clauses (public/private
     * membership, per-issue-type grants, correlated subqueries against a grant
     * table) are ANDed here — this method's body is the one place that changes,
     * ideally delegating to a shared issue-visibility component once one exists so
     * board, backlog and search can never diverge on who-can-see-what.
     */
    public Predicate scopePredicate(User actor, Workspace ws, Root<Issue> root, CriteriaBuilder cb) {
        var visibleIds = visibleProjectIds(actor, ws);
        // The workspace boundary is always bound server-side from the resolved ws.
        Predicate inWorkspace = cb.equal(root.get("workspace").get("id"), ws.getId());
        if (visibleIds.isEmpty()) {
            // No visible projects → match nothing (a false predicate), never "all".
            return cb.and(inWorkspace, cb.disjunction());
        }
        Predicate inVisibleProjects = root.get("project").get("id").in(visibleIds);
        return cb.and(inWorkspace, inVisibleProjects);
    }
}
