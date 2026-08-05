-- =============================================================================
-- Delegated administration: project-private scope
--
-- Adds scope_project_id alongside the existing scope_workspace_id on every
-- catalog primitive and reusable set, giving three mutually-exclusive scopes:
--   global           = both NULL                 (system admin)
--   workspace-scoped = scope_workspace_id set     (workspace OWNER/ADMIN)
--   project-private  = scope_project_id set        (project MANAGER)
-- A CHECK enforces at most one of the two is non-NULL. Uniqueness of a name
-- (and field key) is widened to be per-scope, so two projects — or a project
-- and the global catalog — may reuse the same name.
--
-- Additive migration: all existing rows are global (both columns NULL), so
-- behavior is unchanged. See docs/design/delegated-admin-proposal.md.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Catalog primitives
-- ---------------------------------------------------------------------------

ALTER TABLE statuses     ADD COLUMN scope_project_id UUID REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE priorities   ADD COLUMN scope_project_id UUID REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE issue_types  ADD COLUMN scope_project_id UUID REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE field_defs   ADD COLUMN scope_project_id UUID REFERENCES projects(id) ON DELETE CASCADE;

-- Reusable sets
ALTER TABLE workflows        ADD COLUMN scope_project_id UUID REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE priority_sets    ADD COLUMN scope_project_id UUID REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE field_sets       ADD COLUMN scope_project_id UUID REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE issue_type_sets  ADD COLUMN scope_project_id UUID REFERENCES projects(id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------------
-- At most one scope column non-NULL (both NULL = global)
-- ---------------------------------------------------------------------------

ALTER TABLE statuses        ADD CONSTRAINT statuses_scope_ck        CHECK (scope_workspace_id IS NULL OR scope_project_id IS NULL);
ALTER TABLE priorities      ADD CONSTRAINT priorities_scope_ck      CHECK (scope_workspace_id IS NULL OR scope_project_id IS NULL);
ALTER TABLE issue_types     ADD CONSTRAINT issue_types_scope_ck     CHECK (scope_workspace_id IS NULL OR scope_project_id IS NULL);
ALTER TABLE field_defs      ADD CONSTRAINT field_defs_scope_ck      CHECK (scope_workspace_id IS NULL OR scope_project_id IS NULL);
ALTER TABLE workflows       ADD CONSTRAINT workflows_scope_ck       CHECK (scope_workspace_id IS NULL OR scope_project_id IS NULL);
ALTER TABLE priority_sets   ADD CONSTRAINT priority_sets_scope_ck   CHECK (scope_workspace_id IS NULL OR scope_project_id IS NULL);
ALTER TABLE field_sets      ADD CONSTRAINT field_sets_scope_ck      CHECK (scope_workspace_id IS NULL OR scope_project_id IS NULL);
ALTER TABLE issue_type_sets ADD CONSTRAINT issue_type_sets_scope_ck CHECK (scope_workspace_id IS NULL OR scope_project_id IS NULL);

-- ---------------------------------------------------------------------------
-- Widen uniqueness to be per-scope (workspace_id, project_id, name/key).
-- NULLS NOT DISTINCT so two globals with the same name still collide.
-- ---------------------------------------------------------------------------

ALTER TABLE statuses        DROP CONSTRAINT statuses_scope_workspace_id_name_key;
ALTER TABLE statuses        ADD  CONSTRAINT statuses_scope_name_key        UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, name);

ALTER TABLE priorities      DROP CONSTRAINT priorities_scope_workspace_id_name_key;
ALTER TABLE priorities      ADD  CONSTRAINT priorities_scope_name_key      UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, name);

ALTER TABLE issue_types     DROP CONSTRAINT issue_types_scope_workspace_id_name_key;
ALTER TABLE issue_types     ADD  CONSTRAINT issue_types_scope_name_key     UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, name);

ALTER TABLE field_defs      DROP CONSTRAINT field_defs_scope_workspace_id_key_key;
ALTER TABLE field_defs      ADD  CONSTRAINT field_defs_scope_key_key       UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, key);
ALTER TABLE field_defs      DROP CONSTRAINT field_defs_scope_workspace_id_name_key;
ALTER TABLE field_defs      ADD  CONSTRAINT field_defs_scope_name_key      UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, name);

ALTER TABLE workflows       DROP CONSTRAINT workflows_scope_workspace_id_name_key;
ALTER TABLE workflows       ADD  CONSTRAINT workflows_scope_name_key       UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, name);

ALTER TABLE priority_sets   DROP CONSTRAINT priority_sets_scope_workspace_id_name_key;
ALTER TABLE priority_sets   ADD  CONSTRAINT priority_sets_scope_name_key   UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, name);

ALTER TABLE field_sets      DROP CONSTRAINT field_sets_scope_workspace_id_name_key;
ALTER TABLE field_sets      ADD  CONSTRAINT field_sets_scope_name_key      UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, name);

ALTER TABLE issue_type_sets DROP CONSTRAINT issue_type_sets_scope_workspace_id_name_key;
ALTER TABLE issue_type_sets ADD  CONSTRAINT issue_type_sets_scope_name_key UNIQUE NULLS NOT DISTINCT (scope_workspace_id, scope_project_id, name);

-- ---------------------------------------------------------------------------
-- Indexes for scoped lookups (partial: only scoped rows, globals stay small)
-- ---------------------------------------------------------------------------

CREATE INDEX idx_statuses_scope_project        ON statuses(scope_project_id)        WHERE scope_project_id IS NOT NULL;
CREATE INDEX idx_priorities_scope_project      ON priorities(scope_project_id)      WHERE scope_project_id IS NOT NULL;
CREATE INDEX idx_issue_types_scope_project     ON issue_types(scope_project_id)     WHERE scope_project_id IS NOT NULL;
CREATE INDEX idx_field_defs_scope_project      ON field_defs(scope_project_id)      WHERE scope_project_id IS NOT NULL;
CREATE INDEX idx_workflows_scope_project       ON workflows(scope_project_id)       WHERE scope_project_id IS NOT NULL;
CREATE INDEX idx_priority_sets_scope_project   ON priority_sets(scope_project_id)   WHERE scope_project_id IS NOT NULL;
CREATE INDEX idx_field_sets_scope_project      ON field_sets(scope_project_id)      WHERE scope_project_id IS NOT NULL;
CREATE INDEX idx_issue_type_sets_scope_project ON issue_type_sets(scope_project_id) WHERE scope_project_id IS NOT NULL;

CREATE INDEX idx_statuses_scope_workspace        ON statuses(scope_workspace_id)        WHERE scope_workspace_id IS NOT NULL;
CREATE INDEX idx_priorities_scope_workspace      ON priorities(scope_workspace_id)      WHERE scope_workspace_id IS NOT NULL;
CREATE INDEX idx_issue_types_scope_workspace     ON issue_types(scope_workspace_id)     WHERE scope_workspace_id IS NOT NULL;
CREATE INDEX idx_field_defs_scope_workspace      ON field_defs(scope_workspace_id)      WHERE scope_workspace_id IS NOT NULL;
CREATE INDEX idx_workflows_scope_workspace       ON workflows(scope_workspace_id)       WHERE scope_workspace_id IS NOT NULL;
CREATE INDEX idx_priority_sets_scope_workspace   ON priority_sets(scope_workspace_id)   WHERE scope_workspace_id IS NOT NULL;
CREATE INDEX idx_field_sets_scope_workspace      ON field_sets(scope_workspace_id)      WHERE scope_workspace_id IS NOT NULL;
CREATE INDEX idx_issue_type_sets_scope_workspace ON issue_type_sets(scope_workspace_id) WHERE scope_workspace_id IS NOT NULL;
