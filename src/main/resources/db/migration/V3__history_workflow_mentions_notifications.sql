-- =============================================================================
-- Phase 3 additions: issue history, workflow transitions, mentions, notifications
-- Drop old incompatible tables from prior schema (field names/types differ)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Drop old tables that exist with incompatible schemas
-- (issue_history: field_name→field, changed_at→created_at)
-- (notifications: had workspace_id/subject_id/payload, new schema is simpler)
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS issue_history CASCADE;
DROP TABLE IF EXISTS notifications CASCADE;

-- ---------------------------------------------------------------------------
-- ISSUE HISTORY
-- ---------------------------------------------------------------------------

CREATE TABLE issue_history (
    id          UUID         PRIMARY KEY,
    issue_id    UUID         NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    changed_by  UUID         NOT NULL REFERENCES users(id),
    field       VARCHAR(50)  NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_issue_history_issue ON issue_history(issue_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- STATUS TRANSITIONS (workflow engine)
-- If any transitions defined from a status → only those targets allowed.
-- If none defined from a status → open (all transitions allowed from it).
-- ---------------------------------------------------------------------------

CREATE TABLE status_transitions (
    id             UUID  PRIMARY KEY,
    workspace_id   UUID  NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    from_status_id UUID  NOT NULL REFERENCES statuses(id)   ON DELETE CASCADE,
    to_status_id   UUID  NOT NULL REFERENCES statuses(id)   ON DELETE CASCADE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(workspace_id, from_status_id, to_status_id)
);

CREATE INDEX idx_status_transitions_ws ON status_transitions(workspace_id);

-- ---------------------------------------------------------------------------
-- COMMENT MENTIONS
-- ---------------------------------------------------------------------------

CREATE TABLE comment_mentions (
    id          UUID  PRIMARY KEY,
    comment_id  UUID  NOT NULL REFERENCES issue_comments(id) ON DELETE CASCADE,
    user_id     UUID  NOT NULL REFERENCES users(id)          ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(comment_id, user_id)
);

-- ---------------------------------------------------------------------------
-- NOTIFICATIONS
-- ---------------------------------------------------------------------------

CREATE TABLE notifications (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(50)  NOT NULL,
    title       VARCHAR(255) NOT NULL,
    body        TEXT,
    link        TEXT,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications(user_id) WHERE read_at IS NULL;
