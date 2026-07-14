package com.hamstrack.auth.repository;

import com.hamstrack.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // Atomic claim: only one concurrent authentication wins the right to seed
    // demo data. Returns 0 when already seeded (or claimed in parallel).
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.demoSeededAt = :now where u.id = :id and u.demoSeededAt is null")
    int claimDemoSeed(@Param("id") UUID id, @Param("now") Instant now);
}
