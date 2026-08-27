package com.hamstrack.auth.repository;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // Admin console user directory — oldest first (admin/seed accounts on top)
    List<User> findAllByOrderByCreatedAtAsc();

    // Guards the admin console against locking itself out (last active ADMIN)
    long countBySystemRoleAndStatus(SystemRole systemRole, com.hamstrack.auth.entity.UserStatus status);

    // Startup probe for the published seed password (DataSeeder).
    //
    // BOUNDED, because each row this returns costs one bcrypt verification on every boot -
    // measured at ~370 ms at the strength SecurityConfig configures (12), per (row x
    // published password). An unbounded version made a fresh test database - 1362
    // accumulated administrators - add over two minutes to EVERY Spring context, and the
    // suite never finished.
    //
    // Oldest first because that is ONE of two mechanisms, neither of which subsumes the
    // other: the ordering reaches the account an installer creates on first boot (while
    // fewer than 25 administrators predate it - the usual case, not a guarantee), and
    // DataSeeder additionally looks up whatever seed.admin.email names TODAY, which is what
    // covers an install that began seeding years in. Whatever both miss is reported by the
    // partial-coverage WARN, which is gated on the count below.
    //
    // Deliberately the whole role rather than only ACTIVE ones: a DISABLED administrator
    // whose stored password is public is one re-enable away from the same thing, and the
    // operator has to hear about it while they are already fixing the others.
    List<User> findFirst25BySystemRoleAndPasswordHashIsNotNullOrderByCreatedAtAsc(SystemRole systemRole);

    // The denominator of that WARN, and deliberately the same population as the finder
    // above: administrators that HAVE a password are the only ones the probe can examine.
    // Counting the whole role instead made the warning fire on installs with complete
    // coverage (26 admins, 22 with a password, all 22 verified) - which is how a
    // partial-coverage warning gets tuned out. Instance-wide by design, like the finder: a
    // published administrator password is a property of the deployment, not of a tenant, so
    // this is one of the few user questions that is NOT workspace-scoped. Anything asking
    // "who is in this workspace" goes through WorkspaceMemberRepository instead.
    long countBySystemRoleAndPasswordHashIsNotNull(SystemRole systemRole);

    // Backs the hamstrack.users.active gauge (see ProductMetrics) — evaluated
    // at scrape time, so it must stay a single cheap count query.
    long countByStatus(com.hamstrack.auth.entity.UserStatus status);

    // Atomic claim: only one concurrent authentication wins the right to seed
    // demo data. Returns 0 when already seeded (or claimed in parallel).
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.demoSeededAt = :now where u.id = :id and u.demoSeededAt is null")
    int claimDemoSeed(@Param("id") UUID id, @Param("now") Instant now);

    // Marks first-login onboarding complete (idempotent — only sets when null).
    // Bulk update avoids attaching the detached security-principal User.
    // NOTE: deliberately NOT clearAutomatically — this is called mid-transaction
    // right after saving a workspace/member (WorkspaceService.create), and
    // clearing the persistence context would discard those still-pending INSERTs
    // (the UPDATE only touches `users`, so they aren't auto-flushed first). We
    // never re-read onboardedAt in the same transaction, so no stale-cache risk.
    @Modifying
    @Query("update User u set u.onboardedAt = :now where u.id = :id and u.onboardedAt is null")
    int markOnboarded(@Param("id") UUID id, @Param("now") Instant now);
}
