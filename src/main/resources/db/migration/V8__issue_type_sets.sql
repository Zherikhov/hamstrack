-- =============================================================================
-- Issue type sets (admin console M3)
--
-- Jira-like "types per project" through the same one-layer set indirection as
-- priorities/fields: a reusable named list of catalog issue types, bound per
-- project (NULL = the "All types" system default). Only issue *creation* and
-- type *changes* are restricted to the set — existing issues keep their type
-- even if it leaves the set. Additive migration: no data reset needed.
-- =============================================================================

CREATE TABLE issue_type_sets (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE,  -- NULL = global
    name               VARCHAR(100) NOT NULL,
    is_system_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, name)
);

CREATE TABLE issue_type_set_items (
    id       UUID     PRIMARY KEY,
    set_id   UUID     NOT NULL REFERENCES issue_type_sets(id) ON DELETE CASCADE,
    type_id  UUID     NOT NULL REFERENCES issue_types(id) ON DELETE CASCADE,
    position SMALLINT NOT NULL DEFAULT 0,             -- display order in issue forms
    UNIQUE(set_id, type_id)
);

ALTER TABLE projects ADD COLUMN issue_type_set_id UUID REFERENCES issue_type_sets(id);

CREATE INDEX idx_issue_type_set_items_set ON issue_type_set_items(set_id);

-- Seed: the system default contains every current global type (catalog order),
-- preserving pre-M3 behavior where all types were offered everywhere. New
-- catalog types are NOT auto-added — the admin curates the default like any set.
INSERT INTO issue_type_sets (id, name, is_system_default)
    VALUES (gen_random_uuid(), 'All types', TRUE);

INSERT INTO issue_type_set_items (id, set_id, type_id, position)
    SELECT gen_random_uuid(), s.id, t.id, t.position
    FROM issue_type_sets s, issue_types t
    WHERE s.is_system_default AND t.scope_workspace_id IS NULL;
