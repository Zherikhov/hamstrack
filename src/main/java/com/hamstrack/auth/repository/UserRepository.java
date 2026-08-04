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
