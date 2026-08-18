package com.hamstrack.workspace.repository;

import com.hamstrack.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);

    // ---------------------------------------------------- S4: role usage & reassign
    //
    // As with `projects.default_project_role_id`, this column has no write path until S7
    // and is covered anyway because the FK is real today (§11.5).

    /**
     * Clear/repoint one workspace's project default before the old role row is deleted.
     *
     * <p>Scoped to a single id, so the "bulk UPDATE desyncs a managed entity" warning is
     * live rather than theoretical here: the deleting transaction has already resolved this
     * very {@code Workspace} through {@code requireMember}, and it IS in the persistence
     * context. It is nevertheless safe, because nothing in that transaction mutates or
     * flushes the workspace afterwards — the reassign is the last thing to touch it and the
     * transaction commits immediately after the role delete. Do not add a workspace
     * mutation to that method without switching this to an in-place {@code setter} +
     * {@code save}.
     */
    @Modifying
    @Query("""
            UPDATE Workspace w SET w.defaultProjectRoleId = :toRoleId
             WHERE w.id = :workspaceId AND w.defaultProjectRoleId = :fromRoleId
            """)
    int reassignDefaultProjectRole(@Param("workspaceId") UUID workspaceId,
                                   @Param("fromRoleId") UUID fromRoleId,
                                   @Param("toRoleId") UUID toRoleId);

    /**
     * {@code SELECT … FOR NO KEY UPDATE} on the workspace row — <strong>the serialisation
     * point of role creation</strong> (HD-127 security review rounds 2–3).
     *
     * <p>{@code app.roles.max-custom-per-workspace} was a plain count-then-insert, and the
     * spec dismissed the race as "the failure mode is 51 roles". It is not: the overshoot is
     * the attacker's achievable concurrency, because every request that observes the count
     * below the cap commits. On a default Tomcat pool that is ~200 roles from one burst, and
     * the cost lands on {@code GET /roles} — which compares every ordered pair of same-scope
     * roles and is open to every member of the workspace.
     *
     * <p>So the count is taken under this lock, which makes the cap an exact bound rather
     * than an advisory one and lets both {@code .env.prod.example} and the {@code GET /roles}
     * complexity argument state a number that is true. Serialising costs nothing: duplicating
     * a role is a rare, human, administrative action. The caller still bounds its wait with
     * {@code LockTimeout} first, per the standing rule.
     *
     * <p><strong>{@code FOR NO KEY UPDATE}, and the difference is not cosmetic</strong>
     * (round-3 review, reached independently by security and DC/Cloud). The round-2 spelling
     * was {@code @Lock(PESSIMISTIC_WRITE)}, which PostgreSQL emits as {@code FOR UPDATE} —
     * the one row-lock mode that conflicts with {@code FOR KEY SHARE}, which PostgreSQL takes
     * <em>itself</em> on the parent row for <strong>every INSERT into a table with an FK to
     * {@code workspaces(id)}</strong>. Sixteen tables have one, {@code issues},
     * {@code projects}, {@code workspace_members}, {@code labels}, {@code components},
     * {@code versions}, {@code sprints} and {@code saved_filters} among them. So while one
     * duplication held it, no issue could be created anywhere in that workspace — and those
     * waiters never called {@code LockTimeout}, so they waited unbounded. In the other
     * direction the duplication queued behind any open transaction that had inserted such a
     * child (first-login demo seeding creates a project and ~20 issues in one), producing a
     * spurious retryable 409 on a correct request. {@code FOR NO KEY UPDATE} still mutually
     * excludes concurrent duplications — so the cap stays exact — and does not conflict with
     * {@code FOR KEY SHARE}.
     *
     * <p>Native because JPA has no {@code LockModeType} for that mode: {@code PESSIMISTIC_WRITE}
     * is the strongest thing the enum can say. Do not "simplify" this back to {@code @Lock} —
     * and note that the round-2 claim "this lock is taken by nothing else in the product" was
     * true only of <em>explicit</em> locks. It was never true of the ones PostgreSQL takes on
     * its own, which is the whole finding.
     *
     * <p><strong>{@link Propagation#MANDATORY}</strong>, like every other lock helper in the
     * product (HD-136 round 6): called outside a transaction, Spring Data would open its own
     * read-only one and release the lock before the count it protects — the silent-no-op
     * shape the other three were hardened against. Safe today only because {@code duplicate}
     * is {@code @Transactional}; the caller asserts the same thing again for a future
     * self-invocation that would bypass this proxy.
     *
     * <p>Returns the id rather than the entity on purpose: the {@code Workspace} the caller
     * already holds came from {@code requireMember}, and re-materialising it here would only
     * invite somebody to use this row-lock helper as a read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT id FROM workspaces WHERE id = :workspaceId FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<UUID> lockForRoleCount(@Param("workspaceId") UUID workspaceId);
}
