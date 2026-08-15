package com.hamstrack.common.mail;

import com.hamstrack.common.entity.CreatedOnlyEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Dead-letter record for account-critical mail (verification + password reset) that
 * exhausted its retries (HD-78). Install-level operational log — NOT workspace-scoped
 * (mail is sent at account boundaries, e.g. verification before any workspace exists);
 * carries no tenant data beyond an email address and is only reachable by operators/DB.
 *
 * <p>Deliberately stores NO raw verification/reset token — a leaked dead-letter table
 * must not become a token store. Re-driving a critical mail means the user re-requests
 * via the existing enumeration-safe resend flow.
 */
@Entity
@Table(name = "failed_email")
@Getter
@Setter
public class FailedEmail extends CreatedOnlyEntity {

    @Column(name = "email_type", nullable = false, length = 40)
    private String emailType;

    @Column(name = "recipient", nullable = false, length = 320)
    private String recipient;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "attempts", nullable = false)
    private int attempts;
}
