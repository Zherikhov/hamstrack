-- ---------------------------------------------------------------------------
-- HD-30 (Labels) — labels-components-versions-proposal §4.2
-- ---------------------------------------------------------------------------
-- Free-form, colored, WORKSPACE-scoped tags, many per issue, reusable across
-- every project of the workspace. Labels are *content*, not bound taxonomy —
-- they never enter the catalog → set → project-binding machinery (§3.2), and
-- there is deliberately no global tier (a global label catalog would surface one
-- Cloud tenant's vocabulary to another).
--
-- VARCHAR throughout — no CHAR(n), no PG ENUM (CLAUDE.md Hibernate-7 gotchas).
-- created_at/updated_at carry DEFAULT NOW() + the set_updated_at() trigger as a
-- raw-SQL safety net; JPA sets them via Spring Data auditing (BaseEntity).
-- Purely additive: no data reset, no edits to an applied migration.

CREATE TABLE labels (
    id           UUID         PRIMARY KEY,                        -- app-generated UUID v7
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name         VARCHAR(60)  NOT NULL,
    color        VARCHAR(9)   NOT NULL DEFAULT '#667085',         -- #RRGGBB or #RRGGBBAA
    description  VARCHAR(200),
    created_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Referenced by issue_labels' composite FK (see §3.8 below). Postgres requires a
    -- UNIQUE constraint on the exact referenced column list; (id) alone is not
    -- sufficient for an FK on (label_id, workspace_id). Redundant as a *lookup* index
    -- (labels_pkey already covers id) but structurally required.
    CONSTRAINT labels_id_workspace_id_key UNIQUE (id, workspace_id)
);

CREATE TRIGGER trg_labels_updated_at
    BEFORE UPDATE ON labels
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Case-insensitive uniqueness inside one workspace (a plain UNIQUE can't lower()).
-- Archived rows keep their slot on purpose (§4.4) — reusing an archived label's
-- name is a 409 that nudges toward unarchive/rename.
CREATE UNIQUE INDEX labels_workspace_name_uk ON labels (workspace_id, lower(name));
CREATE INDEX idx_labels_workspace ON labels (workspace_id) WHERE archived_at IS NULL;

-- Same reason on the ISSUES side. issues is created in V1 (already applied and
-- shipped), so the constraint is added here rather than edited in.
--
-- DECLARED ONCE FOR THE WHOLE HD-6 EPIC (spec §3.8, §6.2). Every join table of this
-- epic points its composite FK at issues (id, workspace_id) — issue_labels below, and
-- the HD-31 (components) / HD-32 (versions) join tables in V9/V10 — but they all REUSE
-- this constraint. Do NOT re-add it in V9/V10: a second
--   ALTER TABLE issues ADD CONSTRAINT issues_id_workspace_id_key …
-- fails with `constraint "issues_id_workspace_id_key" already exists` and takes the
-- whole migration down. V8 must not be edited either (it is applied — the checksum is
-- recorded); simply reference the existing constraint from the later migrations.
ALTER TABLE issues ADD CONSTRAINT issues_id_workspace_id_key UNIQUE (id, workspace_id);

-- ---------------------------------------------------------------------------
-- §3.8 join-table shape — DENORMALIZED workspace_id + COMPOSITE FKs
-- ---------------------------------------------------------------------------
-- Owner decision (HD-30 fix round 2), binding on every join table of this epic
-- (issue_labels here, the HD-31 and HD-32 join tables in V9/V10):
--
-- A join row carries its own workspace_id — denormalized exactly as issues.workspace_id
-- already is — and BOTH parents are referenced by a COMPOSITE key that includes it.
-- The pair can therefore only be formed from rows that agree on workspace_id: attaching
-- one tenant's label to another tenant's issue is not "checked", it is unrepresentable.
-- Cross-tenant leakage is this project's highest-severity bug class, and until now the
-- invariant was 100% service-enforced — one forgotten `…AndWorkspace` away from a leak.
--
-- The single-column FKs to issues(id)/labels(id) are deliberately NOT declared: each
-- composite FK already guarantees its parent row exists, so a second FK would only add
-- a redundant RI trigger on every insert. ON DELETE CASCADE is carried by the composite
-- FKs, so deleting an issue or a label still removes the join rows.
CREATE TABLE issue_labels (
    id           UUID        PRIMARY KEY,
    issue_id     UUID        NOT NULL,
    label_id     UUID        NOT NULL,
    workspace_id UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Named explicitly (project convention, cf. statuses_scope_name_key) rather than
    -- leaning on Postgres' auto-name, so a later migration can reference it.
    CONSTRAINT issue_labels_issue_id_label_id_key UNIQUE (issue_id, label_id),
    CONSTRAINT issue_labels_issue_fk  FOREIGN KEY (issue_id, workspace_id)
        REFERENCES issues (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT issue_labels_label_fk  FOREIGN KEY (label_id, workspace_id)
        REFERENCES labels (id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX idx_issue_labels_label ON issue_labels (label_id, issue_id);  -- filter + usage count
-- No separate (issue_id) index: the unique constraint above already creates a btree
-- LEADING on issue_id, which serves the single-issue read, the page batch load
-- (issue_id IN …) and the FK cascade as index-only scans. A second index would only
-- add write amplification on every label attach/detach.
--
-- No index on workspace_id either: it is never a standalone predicate here (every read
-- is keyed by issue or by label), and the two cascade paths are served by the existing
-- indexes' leading columns — issue delete → issue_labels_issue_id_label_id_key
-- (issue_id first), label delete → idx_issue_labels_label (label_id first).

-- Retire the V3 placeholder custom field (§3.4): archive, never delete. Existing
-- values keep rendering; the field disappears from pickers, from
-- ProjectConfigResponse.fields and from HQL name resolution — which frees the
-- `labels` HQL key for the first-class field this slice registers.
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'labels' AND archived_at IS NULL;
