package com.hamstrack.issue.repository;

import com.hamstrack.common.security.CalledOnlyFrom;
import com.hamstrack.issue.entity.SprintScopeEvent;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Persistence for the sprint scope ledger (HD-137, reports-proposal §5.2).
 *
 * <p><strong>Write side only, and only one writer.</strong> The single caller is
 * {@code SprintScopeLedger}; no service, and certainly no controller, may insert here
 * directly. That is declared rather than described since HD-340 — the
 * {@code @CalledOnlyFrom} below names the admissible caller and
 * {@code CalledOnlyFromSealTest} derives the real ones from production bytecode — because a
 * row written past the ledger carries whatever tenant and whatever snapshot its writer
 * happened to set, and a ledger the reports cannot trust has no symptom at all: it is a
 * number. The read side (the burn-up's scope line, the sprint-review lists, the velocity
 * "committed" figure) lands with R4 in {@code com.hamstrack.report}, where
 * {@code ReportQueryScopeTest} holds every native statement to its tenant predicate.
 *
 * <p><strong>Deliberately NOT a {@code JpaRepository}</strong> — the
 * {@code RoleRepository} precedent (HD-123 §12, and the rule CLAUDE.md records). Every
 * inherited method comes with an unscoped twin: {@code findAll()} would compile and
 * return every tenant's ledger, {@code findById(id)} would resolve a row belonging to
 * anybody. On a table that exists to feed cross-workspace report sweeps, an unscoped read
 * has no symptom at all — it is a number. So this interface declares exactly the one
 * operation the feature has, and a second one has to be written down before it can be
 * used.
 *
 * <p><strong>Write-only, and now literally so.</strong> This interface carried a
 * {@code findBySprintOrderByOccurredAtAsc} finder from HD-137 "for the tests today and the
 * R4 burn-up tomorrow"; R4 arrived, wrote its own joined statement in
 * {@code SprintReportRepository} (it needs each event's issue beside it, which an entity
 * finder cannot give), and left the finder with no caller in {@code src/main} or
 * {@code src/test}. It is deleted rather than kept: a convenient unscoped-looking reader on
 * the ledger is exactly the thing a later slice reaches for instead of going through
 * {@code SprintLedgerReader}, which is where the sprint is resolved through membership. The
 * next reader here is written when it has a caller, and {@code ReportQueryScopeTest} adopts
 * this type so that it is guarded on the day it is written.
 */
@CalledOnlyFrom(
        callers = "com.hamstrack.issue.service.SprintScopeLedger",
        because = "a row inserted past the ledger carries whatever workspace_id and whatever issue "
                  + "snapshot its writer set, and a scope ledger that disagrees with reality is a "
                  + "chart nobody can see is wrong",
        instead = "call SprintScopeLedger, which stamps the tenant and the snapshot and whose "
                  + "mutation doors SprintScopeLedgerDoorsTest holds")
public interface SprintScopeEventRepository extends Repository<SprintScopeEvent, java.util.UUID> {

    /**
     * The ledger's only write. Re-declared from {@code CrudRepository} rather than
     * inherited wholesale, so that appending rows is the ONLY thing this interface can do
     * to the table — there is no {@code save}-one, no {@code delete}, no {@code deleteAll}
     * anywhere, which is how "append-only" stops being a comment and becomes a fact about
     * the type.
     */
    <S extends SprintScopeEvent> List<S> saveAll(Iterable<S> entities);
}
