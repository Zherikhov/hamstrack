-- ---------------------------------------------------------------------------
-- HD-78 (Critical-mail durability — dead-letter table)
-- ---------------------------------------------------------------------------
-- Records account-critical mail (verification + password reset) that exhausted
-- its in-executor retries, so an operator/alert can see and re-drive it. This is
-- an INSTALL-LEVEL operational log — NOT workspace-scoped (mail is sent at account
-- boundaries, e.g. verification before any workspace exists), so no workspace_id.
-- Reachable only by operators/DB; a future admin surface must scope it system-admin.
--
-- No raw verification/reset TOKEN is stored — a leaked dead-letter table must not
-- become a token store; re-driving means the user re-requests via the existing
-- enumeration-safe resend flow. VARCHAR throughout (no CHAR(n), no PG ENUM —
-- CLAUDE.md Hibernate-7 gotchas); id is an app-generated UUID v7; created_at carries
-- DEFAULT NOW() as a raw-SQL safety net (JPA sets it via @CreatedDate auditing).

CREATE TABLE failed_email (
    id          UUID          PRIMARY KEY,                -- app-generated UUID v7
    email_type  VARCHAR(40)   NOT NULL,                   -- ProductMetrics.EmailType name (validated app-side)
    recipient   VARCHAR(320)  NOT NULL,                   -- the `to` address
    subject     VARCHAR(255)  NOT NULL,                   -- operator context
    last_error  VARCHAR(1000),                            -- truncated exception message
    attempts    INT           NOT NULL,                   -- attempts made before giving up
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
