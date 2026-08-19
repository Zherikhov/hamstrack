package com.hamstrack.issue.repository;

import com.hamstrack.issue.entity.Sprint;
import com.hamstrack.issue.entity.SprintScopeEvent;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Persistence for the sprint scope ledger (HD-137, reports-proposal §5.2).
 *
 * <p><strong>Write side only, and only one writer.</strong> The single caller is
 * {@code SprintScopeLedger}; no service, and certainly no controller, may insert here
 * directly. The read side (the burn-up's scope line, the sprint-review lists, the
 * velocity "committed" figure) lands with R4 in {@code com.hamstrack.report}, where
 * {@code ReportQueryScopeTest} holds every native statement to its tenant predicate.
 *
 * <p><strong>Deliberately NOT a {@code JpaRepository}</strong> — the
 * {@code RoleRepository} precedent (HD-123 §12, and the rule CLAUDE.md records). Every
 * inherited method comes with an unscoped twin: {@code findAll()} would compile and
 * return every tenant's ledger, {@code findById(id)} would resolve a row belonging to
 * anybody. On a table that exists to feed cross-workspace report sweeps, an unscoped read
 * has no symptom at all — it is a number. So this interface declares exactly the two
 * operations the feature has, and a third one has to be written down before it can be
 * used.
 *
 * <p>The finder is scoped by {@code Sprint} — an entity the caller has already resolved
 * through {@code findByIdAndProject}, i.e. through workspace membership — for the same
 * reason {@code SprintRepository} takes no bare ids.
 */
public interface SprintScopeEventRepository extends Repository<SprintScopeEvent, java.util.UUID> {

    /**
     * The ledger's only write. Re-declared from {@code CrudRepository} rather than
     * inherited wholesale, so that appending rows is the ONLY thing this interface can do
     * to the table — there is no {@code save}-one, no {@code delete}, no {@code deleteAll}
     * anywhere, which is how "append-only" stops being a comment and becomes a fact about
     * the type.
     */
    <S extends SprintScopeEvent> List<S> saveAll(Iterable<S> entities);

    /**
     * One sprint's whole ledger in chronological order — served by
     * {@code idx_sprint_scope_events_sprint}. Used by the tests today and by the R4
     * burn-up tomorrow; a sprint's ledger is O(size + changes), i.e. hundreds of rows.
     */
    List<SprintScopeEvent> findBySprintOrderByOccurredAtAsc(Sprint sprint);
}
