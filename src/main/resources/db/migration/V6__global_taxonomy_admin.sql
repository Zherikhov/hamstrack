-- =============================================================================
-- Global taxonomy + system administrator (admin console M1)
--
-- Statuses / priorities / issue types move from per-workspace copies to a
-- global catalog curated by a system administrator. Projects consume them
-- through reusable bindings: a *workflow* (statuses + transitions) and a
-- *priority set*. NULL binding = the system default set.
--
-- scope_workspace_id is reserved for the future "workspace admins manage
-- their own entries" delegation: NULL = global row. Queries must always
-- filter (scope_workspace_id IS NULL OR scope_workspace_id = :ws).
--
-- Test-mode reset (approved precedent): issues/workspaces are wiped and demo
-- seeding is re-armed, so the catalog rewire needs no data migration.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- System role
-- ---------------------------------------------------------------------------

ALTER TABLE users ADD COLUMN system_role VARCHAR(20) NOT NULL DEFAULT 'USER';

-- ---------------------------------------------------------------------------
-- Wipe user data first (issues reference statuses/issue_types being replaced)
-- ---------------------------------------------------------------------------

DELETE FROM issues;
DELETE FROM workspaces;
DELETE FROM notifications;
UPDATE users SET demo_seeded_at = NULL;

-- ---------------------------------------------------------------------------
-- Drop replaced / legacy-unused tables
-- statuses, issue_types, status_transitions: replaced by the global catalog.
-- workflows, workflow_transitions, project_issue_types, boards, board_columns:
-- V1 leftovers that were never wired up — recreated properly below.
-- ---------------------------------------------------------------------------

DROP TABLE IF EXISTS status_transitions CASCADE;
DROP TABLE IF EXISTS workflow_transitions CASCADE;
DROP TABLE IF EXISTS workflows CASCADE;
DROP TABLE IF EXISTS project_issue_types CASCADE;
DROP TABLE IF EXISTS board_columns CASCADE;
DROP TABLE IF EXISTS boards CASCADE;
DROP TABLE IF EXISTS statuses CASCADE;
DROP TABLE IF EXISTS issue_types CASCADE;

-- ---------------------------------------------------------------------------
-- Catalog
-- ---------------------------------------------------------------------------

CREATE TABLE statuses (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE,  -- NULL = global
    name               VARCHAR(100) NOT NULL,
    category           VARCHAR(20)  NOT NULL,             -- TODO | IN_PROGRESS | DONE
    color              VARCHAR(7)   NOT NULL DEFAULT '#6B7280',  -- not CHAR: bpchar fails Hibernate validate (see V2)
    position           SMALLINT     NOT NULL DEFAULT 0,
    archived_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, name)
);

CREATE TABLE priorities (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE,
    name               VARCHAR(100) NOT NULL,
    color              VARCHAR(7)   NOT NULL DEFAULT '#8B8680',
    icon               VARCHAR(50),                       -- lucide icon name
    position           SMALLINT     NOT NULL DEFAULT 0,
    archived_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, name)
);

CREATE TABLE issue_types (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE,
    name               VARCHAR(100) NOT NULL,
    color              VARCHAR(7)   NOT NULL DEFAULT '#6B7280',  -- not CHAR: bpchar fails Hibernate validate (see V2)
    icon               VARCHAR(50),
    position           SMALLINT     NOT NULL DEFAULT 0,
    archived_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, name)
);

-- ---------------------------------------------------------------------------
-- Reusable bindings
-- ---------------------------------------------------------------------------

CREATE TABLE workflows (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE,
    name               VARCHAR(100) NOT NULL,
    description        TEXT,
    is_system_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, name)
);

CREATE TABLE workflow_statuses (
    id          UUID     PRIMARY KEY,
    workflow_id UUID     NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    status_id   UUID     NOT NULL REFERENCES statuses(id) ON DELETE CASCADE,
    position    SMALLINT NOT NULL DEFAULT 0,               -- board column order
    UNIQUE(workflow_id, status_id)
);

-- from_status_id NULL = "from any status"
CREATE TABLE workflow_transitions (
    id             UUID PRIMARY KEY,
    workflow_id    UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    from_status_id UUID REFERENCES statuses(id) ON DELETE CASCADE,
    to_status_id   UUID NOT NULL REFERENCES statuses(id) ON DELETE CASCADE,
    UNIQUE NULLS NOT DISTINCT (workflow_id, from_status_id, to_status_id)
);

CREATE TABLE priority_sets (
    id                 UUID         PRIMARY KEY,
    scope_workspace_id UUID         REFERENCES workspaces(id) ON DELETE CASCADE,
    name               VARCHAR(100) NOT NULL,
    is_system_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (scope_workspace_id, name)
);

CREATE TABLE priority_set_items (
    id          UUID     PRIMARY KEY,
    set_id      UUID     NOT NULL REFERENCES priority_sets(id) ON DELETE CASCADE,
    priority_id UUID     NOT NULL REFERENCES priorities(id) ON DELETE CASCADE,
    position    SMALLINT NOT NULL DEFAULT 0,
    is_default  BOOLEAN  NOT NULL DEFAULT FALSE,           -- default for new issues
    UNIQUE(set_id, priority_id)
);

-- ---------------------------------------------------------------------------
-- Project bindings + issue priority FK
-- ---------------------------------------------------------------------------

ALTER TABLE projects ADD COLUMN workflow_id     UUID REFERENCES workflows(id);
ALTER TABLE projects ADD COLUMN priority_set_id UUID REFERENCES priority_sets(id);

ALTER TABLE issues DROP COLUMN priority;
ALTER TABLE issues ADD COLUMN priority_id UUID NOT NULL REFERENCES priorities(id);
CREATE INDEX idx_issues_priority ON issues(priority_id);

CREATE INDEX idx_workflow_statuses_wf    ON workflow_statuses(workflow_id);
CREATE INDEX idx_workflow_transitions_wf ON workflow_transitions(workflow_id);
CREATE INDEX idx_priority_set_items_set  ON priority_set_items(set_id);

-- ---------------------------------------------------------------------------
-- Seed the global catalog and system defaults
-- (gen_random_uuid: SQL-seeded rows only; app-created rows use UUIDv7)
-- ---------------------------------------------------------------------------

INSERT INTO statuses (id, name, category, color, position) VALUES
    (gen_random_uuid(), 'To Do',       'TODO',        '#6B7280', 0),
    (gen_random_uuid(), 'In Progress', 'IN_PROGRESS', '#3B82F6', 1),
    (gen_random_uuid(), 'Done',        'DONE',        '#10B981', 2);

INSERT INTO priorities (id, name, color, icon, position) VALUES
    (gen_random_uuid(), 'Urgent', '#B91C1C', 'chevrons-up', 0),
    (gen_random_uuid(), 'High',   '#EA580C', 'chevron-up',  1),
    (gen_random_uuid(), 'Medium', '#B45309', 'equal',       2),
    (gen_random_uuid(), 'Low',    '#64748B', 'chevron-down',3),
    (gen_random_uuid(), 'None',   '#8B8680', 'minus',       4);

INSERT INTO issue_types (id, name, color, icon, position) VALUES
    (gen_random_uuid(), 'Bug',   '#EF4444', 'bug',   0),
    (gen_random_uuid(), 'Task',  '#3B82F6', 'task',  1),
    (gen_random_uuid(), 'Story', '#8B5CF6', 'story', 2),
    (gen_random_uuid(), 'Epic',  '#F59E0B', 'epic',  3);

INSERT INTO workflows (id, name, description, is_system_default)
    VALUES (gen_random_uuid(), 'Default workflow',
            'To Do → In Progress → Done, all transitions allowed', TRUE);

INSERT INTO workflow_statuses (id, workflow_id, status_id, position)
    SELECT gen_random_uuid(), w.id, s.id, s.position
    FROM workflows w, statuses s
    WHERE w.is_system_default AND s.scope_workspace_id IS NULL;
-- no workflow_transitions rows = every move allowed (open workflow)

INSERT INTO priority_sets (id, name, is_system_default)
    VALUES (gen_random_uuid(), 'Default priorities', TRUE);

INSERT INTO priority_set_items (id, set_id, priority_id, position, is_default)
    SELECT gen_random_uuid(), ps.id, p.id, p.position, (p.name = 'None')
    FROM priority_sets ps, priorities p
    WHERE ps.is_system_default AND p.scope_workspace_id IS NULL;
