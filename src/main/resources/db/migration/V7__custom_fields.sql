-- =============================================================================
-- Custom fields (admin console M2)
--
-- Global catalog of field definitions + reusable *field sets* deciding which
-- fields a project shows, their order, and create-form behavior. Values live
-- per issue as JSONB (shape depends on field type — see FieldType javadoc).
-- Additive migration: no data reset needed.
-- =============================================================================

CREATE TABLE field_defs (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE,  -- NULL = global
    key                VARCHAR(50)  NOT NULL,              -- immutable machine name (snake_case)
    name               VARCHAR(100) NOT NULL,
    type               VARCHAR(20)  NOT NULL,              -- TEXT|TEXTAREA|NUMBER|DATE|SELECT|MULTI_SELECT|USER|CHECKBOX|URL
    config             JSONB,                              -- {"options":[{id,label,color}],"min","max"}
    description        TEXT,                               -- shown as a hint in issue forms
    archived_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, key),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, name)
);

CREATE TABLE field_sets (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE,
    name               VARCHAR(100) NOT NULL,
    is_system_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, name)
);

CREATE TABLE field_set_items (
    id             UUID     PRIMARY KEY,
    set_id         UUID     NOT NULL REFERENCES field_sets(id) ON DELETE CASCADE,
    field_id       UUID     NOT NULL REFERENCES field_defs(id) ON DELETE CASCADE,
    position       SMALLINT NOT NULL DEFAULT 0,
    required       BOOLEAN  NOT NULL DEFAULT FALSE,        -- must be filled on create, can't be cleared
    show_on_create BOOLEAN  NOT NULL DEFAULT TRUE,         -- rendered in the create form
    UNIQUE(set_id, field_id)
);

ALTER TABLE projects ADD COLUMN field_set_id UUID REFERENCES field_sets(id);

CREATE TABLE issue_field_values (
    id         UUID        PRIMARY KEY,
    issue_id   UUID        NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    field_id   UUID        NOT NULL REFERENCES field_defs(id) ON DELETE CASCADE,
    value      JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(issue_id, field_id)
);

CREATE INDEX idx_field_set_items_set    ON field_set_items(set_id);
CREATE INDEX idx_issue_field_values_iss ON issue_field_values(issue_id);
CREATE INDEX idx_issue_field_values_fld ON issue_field_values(field_id);

-- ---------------------------------------------------------------------------
-- Seed: system default (empty — projects show no custom fields until bound)
-- plus a sample engineering set the demo project uses
-- ---------------------------------------------------------------------------

INSERT INTO field_sets (id, name, is_system_default)
    VALUES (gen_random_uuid(), 'No fields', TRUE);

INSERT INTO field_defs (id, key, name, type, config, description) VALUES
    (gen_random_uuid(), 'story_points', 'Story points', 'NUMBER',
     '{"min": 0, "max": 100}',
     'Fibonacci estimate agreed at planning'),
    (gen_random_uuid(), 'severity', 'Severity', 'SELECT',
     '{"options": [
        {"id": "critical", "label": "Critical", "color": "#B91C1C"},
        {"id": "major",    "label": "Major",    "color": "#EA580C"},
        {"id": "minor",    "label": "Minor",    "color": "#64748B"}]}',
     'Impact of the defect on users'),
    (gen_random_uuid(), 'environment', 'Environment', 'MULTI_SELECT',
     '{"options": [
        {"id": "production", "label": "Production", "color": "#B91C1C"},
        {"id": "staging",    "label": "Staging",    "color": "#B45309"},
        {"id": "dev",        "label": "Dev",        "color": "#64748B"}]}',
     'Where the issue was observed');

INSERT INTO field_sets (id, name) VALUES (gen_random_uuid(), 'Engineering fields');

INSERT INTO field_set_items (id, set_id, field_id, position, required, show_on_create)
    SELECT gen_random_uuid(), fs.id, fd.id,
           CASE fd.key WHEN 'story_points' THEN 0 WHEN 'severity' THEN 1 ELSE 2 END,
           FALSE,
           fd.key <> 'environment'
    FROM field_sets fs, field_defs fd
    WHERE fs.name = 'Engineering fields' AND fd.scope_workspace_id IS NULL;
