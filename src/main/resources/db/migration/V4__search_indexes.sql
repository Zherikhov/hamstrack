-- ---------------------------------------------------------------------------
-- HD-21 (Advanced Search / HQL) — composite supporting indexes
-- ---------------------------------------------------------------------------
-- Every HQL search ANDs the tenant boundary (workspace_id + a project_id IN (…)
-- list) as its OUTERMOST predicate (SearchScope), then narrows with the parsed
-- filter. These composite indexes lead with the always-present workspace_id so
-- the common "workspace + <one filtered dimension>" shapes are index-served
-- (proposal §10.2/§12). Indexes only — no schema change to `issues`.
--
-- No CHAR(n) / PG ENUM here (nothing but index DDL); IF NOT EXISTS keeps the
-- migration idempotent against any environment that pre-created one by hand.

CREATE INDEX IF NOT EXISTS ix_issues_ws_status   ON issues (workspace_id, status_id);
CREATE INDEX IF NOT EXISTS ix_issues_ws_assignee ON issues (workspace_id, assignee_id);
CREATE INDEX IF NOT EXISTS ix_issues_ws_priority ON issues (workspace_id, priority_id);
CREATE INDEX IF NOT EXISTS ix_issues_ws_type     ON issues (workspace_id, type_id);
CREATE INDEX IF NOT EXISTS ix_issues_ws_updated  ON issues (workspace_id, updated_at);
