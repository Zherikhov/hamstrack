-- ---------------------------------------------------------------------------
-- HD-22 (Agile: sprints, Scrum board & backlog ranking) — agile-sprints-proposal §4.2
-- ---------------------------------------------------------------------------
-- A sprint is a PROJECT-scoped time-box with a one-way lifecycle
-- (FUTURE → ACTIVE → COMPLETED, no re-open) that at most one sprint per project
-- may occupy in the ACTIVE state — enforced BY THE DATABASE, not only in Java.
-- Issues point at a sprint through a COMPOSITE FK carrying the denormalized
-- workspace_id, so a cross-tenant assignment is *unrepresentable* (§3.1).
--
-- VARCHAR throughout — no CHAR(n), no `CREATE TYPE … AS ENUM` (CLAUDE.md
-- Hibernate-7 gotchas): sprints.state is VARCHAR(10) validated by the Java enum
-- SprintState, projects.board_mode is VARCHAR(10) validated by BoardMode.
-- created_at/updated_at carry DEFAULT NOW() + the set_updated_at() trigger as a
-- raw-SQL safety net; JPA sets them via Spring Data auditing (BaseEntity).
--
-- Purely additive EXCEPT three deliberate rewrites, all documented below:
--   * the position rescale (§3.3.1),
--   * the story-point backfill + placeholder-field archival (§3.4),
--   * the guarded replacement of the shared set_updated_at() function (§3.3.4).
--
-- !! TRANSACTION REQUIREMENT !! This migration uses `SET LOCAL
-- hamstrack.skip_updated_at = 'on'` (below) so its two data rewrites do not look
-- like user edits. SET LOCAL is only effective INSIDE a transaction: Flyway wraps
-- each script in one by default, and that is the only reason it works here. If
-- anyone ever adds a statement that cannot run in a transaction (CREATE INDEX
-- CONCURRENTLY, ALTER TYPE … ADD VALUE) or turns on `spring.flyway.mixed` /
-- `executeInTransaction=false`, PostgreSQL downgrades SET LOCAL to a WARNING
-- ("SET LOCAL can only be used in transaction blocks") and BOTH rewrites below
-- silently stamp updated_at = NOW() on every issue in the install — poisoning
-- Home / My work / ORDER BY updated with no error anywhere. Keep this script
-- transactional, or replace the guard with an explicit BEGIN/COMMIT.
--
-- Deployment note (§4.2): ADD CONSTRAINT issues_sprint_fk validates the populated
-- issues table under SHARE ROW EXCLUSIVE, and the position rescale rewrites every
-- row. Both are free today (single app container, migrations run inside the deploy
-- window with no concurrent writer). The moment Cloud moves to a rolling deploy
-- this must become ADD CONSTRAINT … NOT VALID + a later VALIDATE CONSTRAINT, in a
-- NEW migration — never a retrofit into an applied one.

-- ---------------------------------------------------------------------------
-- 0) Guarded shared trigger (§3.3.4)
-- ---------------------------------------------------------------------------
-- Default behaviour is byte-identical to V1's version: with the GUC unset,
-- current_setting(..., true) returns NULL, coalesce gives '' and the branch is
-- taken. A transaction that is rewriting `position` in bulk (the rank rebalance)
-- or backfilling a column may opt OUT with
--     SET LOCAL hamstrack.skip_updated_at = 'on';
-- so one drag does not mark every issue in the project as recently updated and
-- poison Home / My work / `ORDER BY updated`.
--
-- SET LOCAL is transaction-scoped: it is reverted at COMMIT/ROLLBACK and can
-- therefore never leak back into the pooled connection. The GUC is only ever set
-- by server-side code (this migration and IssueRepository.rebalancePositions);
-- no user input reaches it.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    IF coalesce(current_setting('hamstrack.skip_updated_at', true), '') <> 'on' THEN
        NEW.updated_at = NOW();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Flyway wraps each migration in a transaction, so this SET LOCAL is scoped to
-- this migration only — the data rewrites below must not look like user edits.
SET LOCAL hamstrack.skip_updated_at = 'on';

-- ---------------------------------------------------------------------------
-- 1) Sprints
-- ---------------------------------------------------------------------------
CREATE TABLE sprints (
    id           UUID         PRIMARY KEY,                        -- app-generated UUID v7
    -- Denormalized tenant scope (as issues/components/versions already carry), so a
    -- tenant-scoped query never joins through projects — and so the composite FK
    -- from issues below can be expressed at all (§3.1).
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_id   UUID         NOT NULL REFERENCES projects(id)   ON DELETE CASCADE,
    name         VARCHAR(60)  NOT NULL,
    goal         VARCHAR(500),
    -- 'FUTURE' | 'ACTIVE' | 'COMPLETED' — validated by the Java enum SprintState.
    -- No domain CHECK: the codebase validates enum-ish VARCHARs in Java (status
    -- category, link_type) and a CHECK would turn a future state into a migration.
    state        VARCHAR(10)  NOT NULL DEFAULT 'FUTURE',
    sequence     INTEGER      NOT NULL,          -- 1-based, per project; drives default names + order
    start_at     TIMESTAMPTZ,
    end_at       TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Cross-column invariants ARE enforced in the DB (the versions_released_ck
    -- precedent): both lifecycle write paths are conditional bulk UPDATEs that
    -- bypass every entity-level check.
    CONSTRAINT sprints_dates_ck     CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at),
    CONSTRAINT sprints_active_ck    CHECK (state <> 'ACTIVE' OR start_at IS NOT NULL),
    CONSTRAINT sprints_completed_ck CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL)),
    -- Referenced by the composite FK on issues below (§3.1). PostgreSQL requires a
    -- UNIQUE on the exact referenced column list; (id) alone is not sufficient for
    -- an FK on (sprint_id, workspace_id).
    CONSTRAINT sprints_id_workspace_id_key UNIQUE (id, workspace_id),
    -- The per-project ordinal is the arbiter of the "next sequence" race.
    CONSTRAINT sprints_project_id_sequence_key UNIQUE (project_id, sequence)
);

CREATE TRIGGER trg_sprints_updated_at
    BEFORE UPDATE ON sprints
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- THE invariant of HD-22: at most one ACTIVE sprint per project, enforced by the
-- DB. Two concurrent starts of two different FUTURE sprints both pass the
-- conditional UPDATE; this index picks the winner and the loser's
-- DataIntegrityViolationException becomes a 409 (§4.1).
CREATE UNIQUE INDEX sprints_one_active_per_project_uk
    ON sprints (project_id) WHERE state = 'ACTIVE';
-- Case-insensitive name uniqueness inside one project (a plain UNIQUE can't
-- lower()). COMPLETED sprints keep their slot, exactly like archived
-- labels/components/versions.
CREATE UNIQUE INDEX sprints_project_name_uk ON sprints (project_id, lower(name));
-- Serves the list (state filter + sequence order) and the backlog view's sections.
CREATE INDEX idx_sprints_project_state ON sprints (project_id, state, sequence);
-- Needed: the ON DELETE CASCADE from workspaces probes by workspace_id, and
-- sprints_id_workspace_id_key leads on id so it cannot serve that lookup.
CREATE INDEX idx_sprints_workspace ON sprints (workspace_id);

-- ---------------------------------------------------------------------------
-- 2) Issue → sprint. COMPOSITE FK (§3.1)
-- ---------------------------------------------------------------------------
-- The pair (sprint_id, workspace_id) can only be formed from rows that agree on
-- workspace_id, so putting one tenant's issue into another tenant's sprint is not
-- "checked", it is unrepresentable.
--
-- ON DELETE SET NULL names the column list EXPLICITLY (PG 15+; we require PG 16):
-- a bare SET NULL would also try to null the NOT NULL issues.workspace_id and the
-- cascade would fail at delete time.
--
-- issues_id_workspace_id_key already exists — V8 declares it ONCE for the whole
-- HD-6 epic. Re-adding it here would fail with "constraint already exists" and
-- take this migration down with it.
--
-- Composite FKs default to MATCH SIMPLE, which skips the check entirely when ANY
-- referencing column is NULL — which is exactly what makes a NULL sprint_id (the
-- backlog) legal while a non-NULL one is fully checked against workspace_id.
ALTER TABLE issues ADD COLUMN sprint_id UUID;
ALTER TABLE issues ADD CONSTRAINT issues_sprint_fk
    FOREIGN KEY (sprint_id, workspace_id) REFERENCES sprints (id, workspace_id)
    ON DELETE SET NULL (sprint_id);
-- (sprint_id, position) serves both the section fetch and its rank ordering, and
-- the FK's own cascade probe.
CREATE INDEX idx_issues_sprint ON issues (sprint_id, position) WHERE sprint_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3) Story points as a native attribute (§3.4)
-- ---------------------------------------------------------------------------
-- Promoted from the V1-seeded `story_points` custom field, on the precedent HD-6
-- set for labels / components / fix_version: the placeholder is ARCHIVED, never
-- deleted, and its values are migrated into the column. NUMERIC(5,2) holds the
-- 0…999 range with the two decimals the estimation scales need (0.5, 1.5, 13).
ALTER TABLE issues ADD COLUMN story_points NUMERIC(5,2);
ALTER TABLE issues ADD CONSTRAINT issues_story_points_ck
    CHECK (story_points IS NULL OR (story_points >= 0 AND story_points <= 999));

-- The stored shape of a NUMBER custom field is a bare JSON number, so the backfill
-- is lossless and unambiguous. LEAST/GREATEST clamp the handful of values a wider
-- historical config could have allowed; round() matches the column's scale.
-- Only the GLOBAL (both scope columns NULL) SYSTEM field is the source — a
-- workspace-/project-scoped field that happens to reuse the key is a different
-- field and must not be swallowed. `is_system = TRUE` is redundant today
-- (`field_defs_scope_key_key` makes the global `story_points` unique, and the seeded
-- one is a system def) but it is repeated on all three statements so the predicate is
-- IDENTICAL everywhere: a hypothetical non-system global def must not have its values
-- consumed by a backfill/DELETE that its own archival step would then skip.
UPDATE issues i
   SET story_points = round(LEAST(999, GREATEST(0, (v.value #>> '{}')::numeric)), 2)
  FROM issue_field_values v
  JOIN field_defs f ON f.id = v.field_id
 WHERE v.issue_id = i.id
   AND f.key = 'story_points'
   AND f.scope_workspace_id IS NULL AND f.scope_project_id IS NULL
   AND f.is_system = TRUE
   AND jsonb_typeof(v.value) = 'number';

-- Drop the rows we just migrated. The WHERE is IDENTICAL to the backfill above, so
-- a value the backfill skipped (jsonb_typeof(value) <> 'number' — a string, a null,
-- an object left by an older client) is left exactly where it is: nothing is
-- deleted that was not first copied.
--
-- Why delete at all, when V8/V9/V10 established "archive, never delete"? Those
-- placeholders held NO data — this is the first one that does, and leaving the
-- copies behind would render the SAME estimate twice on an issue (once as the
-- native issues.story_points, once as a legacy custom-field value). The two can
-- never drift (writes to an archived field are 422), so the duplicate is purely a
-- display bug — but it is a visible one. The recovery path for the data is
-- issues.story_points itself, which now holds it.
DELETE FROM issue_field_values v
 USING field_defs f, issues i
 WHERE v.field_id = f.id
   AND v.issue_id = i.id
   AND f.key = 'story_points'
   AND f.scope_workspace_id IS NULL AND f.scope_project_id IS NULL
   AND f.is_system = TRUE
   AND jsonb_typeof(v.value) = 'number';

-- Retire the placeholder: archive, never delete (HD-6 §3.4). The field def itself
-- stays so any value the backfill could NOT migrate keeps rendering; it leaves
-- pickers, ProjectConfigResponse.fields and HQL name resolution, freeing the
-- `storyPoints` key for the native field.
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'story_points' AND archived_at IS NULL;

-- Same treatment for the never-usable 'sprint' SELECT placeholder seeded by V3:
-- sprints are now a first-class entity, so the stub would only shadow the real
-- HQL `sprint` field.
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'sprint' AND archived_at IS NULL;

-- ---------------------------------------------------------------------------
-- 4) Board mode (§3.5)
-- ---------------------------------------------------------------------------
-- 'KANBAN' | 'SCRUM', validated by the Java enum BoardMode. A PRESENTATION switch,
-- never a permission: the sprint API works identically in both modes.
ALTER TABLE projects ADD COLUMN board_mode VARCHAR(10) NOT NULL DEFAULT 'KANBAN';

-- ---------------------------------------------------------------------------
-- 5) Rank spacing (§3.3)
-- ---------------------------------------------------------------------------
-- issues.position IS the project-wide backlog/board rank (no second rank column —
-- every issue list in the codebase already ends in `ORDER BY position ASC,
-- created_at DESC`). Existing values are 1..N (seeded from the issue sequence),
-- i.e. ZERO room between neighbours; rescaling by RANK_STEP = 2^26 gives every
-- pair 67 108 864 of headroom, which is ~26 successive midpoints into the same gap
-- before a rebalance is needed. Max realistic position afterwards is ~1e7 * 6.7e7
-- — far inside BIGINT.
--
-- Runs under the skip_updated_at guard set at the top: a pure re-spacing is not an
-- edit, and marking every issue as "just updated" would poison Home / My work.
UPDATE issues SET position = position * 67108864;
