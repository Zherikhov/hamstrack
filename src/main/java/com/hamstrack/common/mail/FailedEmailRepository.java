package com.hamstrack.common.mail;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FailedEmailRepository extends JpaRepository<FailedEmail, UUID> {
}
