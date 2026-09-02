package com.hamstrack.common.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface FailedEmailRepository extends JpaRepository<FailedEmail, UUID> {

    /**
     * The retention sweep ({@link FailedEmailRetention}). Plain {@code @Modifying}: this
     * transaction holds no managed {@code FailedEmail} — nothing reads them back as entities —
     * so there is nothing to clear and nothing pending to discard.
     */
    @Modifying
    @Query("DELETE FROM FailedEmail e WHERE e.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
