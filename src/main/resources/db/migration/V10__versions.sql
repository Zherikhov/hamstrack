-- ---------------------------------------------------------------------------
-- HD-32 (Versions: fix / affects + release page) — labels-components-versions-proposal §6.2
-- ---------------------------------------------------------------------------
-- A version is a PROJECT-scoped release target ("2.4.0") with a lifecycle
-- (unreleased ⇄ released, reversible) and an orthogonal archived flag. Issues
-- link to it in two roles — FIX ("ships in this release") and AFFECTS ("the
-- defect exists in that release") — both many-to-many. Versions are *content*,
-- not bound taxonomy: they never enter the catalog → set → project-binding
-- machinery (§3.2) and ProjectConfigService is untouched.
--
-- VARCHAR throughout — no CHAR(n), no PG ENUM (CLAUDE.md Hibernate-7 gotchas);
-- link_type in particular is VARCHAR(10) validated by the Java enum
-- VersionLinkType, NEVER `CREATE TYPE … AS ENUM` (Hibernate 7 + PG enums throw
-- JDBC cast errors on INSERT).
-- created_at/updated_at carry DEFAULT NOW() + the set_updated_at() trigger as a
-- raw-SQL safety net; JPA sets them via Spring Data auditing (BaseEntity).
-- Purely additive: no data reset, no edits to an applied migration.

CREATE TABLE versions (
    id           UUID         PRIMARY KEY,                        -- app-generated UUID v7
    -- Denormalized tenant scope (as issues.workspace_id already is), so a
    -- tenant-scoped query never has to join through projects — and so the
    -- composite FK from issue_versions below can be expressed at all (§3.1, §3.8).
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_id   UUID         NOT NULL REFERENCES projects(id)   ON DELETE CASCADE,
    name         VARCHAR(60)  NOT NULL,
    description  VARCHAR(500),
    -- The PLAN date. Deliberately survives un-release (§6.1): un-releasing must
    -- lose no data, which is the story's reversibility criterion.
    release_date DATE,
    released     BOOLEAN      NOT NULL DEFAULT FALSE,
    released_at  TIMESTAMPTZ,
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- released and released_at move together or not at all. Enforced in the DB
    -- because both write paths are conditional bulk UPDATEs (VersionRepository
    -- .markReleased / .markUnreleased) that bypass any entity-level invariant.
    CONSTRAINT versions_released_ck CHECK ((released AND released_at IS NOT NULL)
                                        OR (NOT released AND released_at IS NULL)),
    -- Referenced by issue_versions' composite FK (see §3.8 below). Postgres requires
    -- a UNIQUE constraint on the exact referenced column list; (id) alone is not
    -- sufficient for an FK on (version_id, workspace_id). Redundant as a *lookup*
    -- index (versions_pkey already covers id) but structurally required.
    CONSTRAINT versions_id_workspace_id_key UNIQUE (id, workspace_id)
);

CREATE TRIGGER trg_versions_updated_at
    BEFORE UPDATE ON versions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Case-insensitive uniqueness inside one PROJECT (a plain UNIQUE can't lower()).
-- The same name in a sibling project is allowed — versions are project-owned.
-- Archived rows keep their slot on purpose (§6.4), same as labels/components.
CREATE UNIQUE INDEX versions_project_name_uk ON versions (project_id, lower(name));
-- Partial index for the reads whose predicate is LITERALLY `archived_at IS NULL`:
-- the HQL name-resolution feed (findIdAndNameByProjectIds) and the version typeahead
-- (suggestByProjects), both of which run on every /search, /search/schema and
-- /search/suggest. It does NOT serve the default Releases-page list: that query spells
-- the filter `(:includeArchived = true OR archived_at IS NULL)`, and a generic plan
-- cannot prove that parameterized OR implies the partial index's predicate. Nor does it
-- provide the list order — `released, release_date NULLS LAST, lower(name)` is sorted in
-- every plan. Kept because the two feeds above are the hot path (EXPLAIN picks it).
CREATE INDEX idx_versions_project ON versions (project_id) WHERE archived_at IS NULL;
-- Needed: the ON DELETE CASCADE from workspaces probes versions by workspace_id,
-- and versions_id_workspace_id_key leads on id, so it cannot serve that lookup.
CREATE INDEX idx_versions_workspace ON versions (workspace_id);

-- ---------------------------------------------------------------------------
-- issue_versions — ONE link table for BOTH roles
-- ---------------------------------------------------------------------------
-- One table rather than two (issue_fix_versions / issue_affects_versions): one
-- batch loader, one filter path, one cascade story, and adding a third role later
-- ("discovered in") becomes a value, not a migration.
--
-- §3.8 join-table shape (owner decision, HD-30 fix round 2, binding on every join
-- table of this epic): a denormalized workspace_id + COMPOSITE FKs to BOTH parents.
-- The pair can therefore only be formed from rows that agree on workspace_id —
-- linking one tenant's issue to another tenant's version is not "checked", it is
-- unrepresentable. Cross-tenant leakage is this project's highest-severity bug
-- class, and without this the invariant is 100% service-enforced (one forgotten
-- `…AndProject` away from a leak).
--
-- ALL THREE referencing columns are NOT NULL on purpose: composite FKs default to
-- MATCH SIMPLE, which skips the check ENTIRELY when any referencing column is NULL
-- — a nullable workspace_id would silently reopen the exact hole these FKs close.
--
-- The single-column FKs to issues(id)/versions(id) are deliberately NOT declared:
-- each composite FK already guarantees its parent row exists, so a second FK would
-- only add a redundant RI trigger per insert. ON DELETE CASCADE rides on the
-- composite FKs, so deleting an issue or a version still removes the link rows.
--
-- issues_id_workspace_id_key is NOT re-added here: V8 declares it ONCE for the
-- whole HD-6 epic (see the comment block in V8__labels.sql). A second
--   ALTER TABLE issues ADD CONSTRAINT issues_id_workspace_id_key …
-- fails with `constraint "issues_id_workspace_id_key" already exists` and takes
-- this migration down with it.
--
-- Note (§3.8): both FKs are NO ACTION on UPDATE, so an
-- `UPDATE issues SET workspace_id = …` on an issue that has link rows now fails.
-- No such feature exists (an issue's workspace is set once, at creation), but any
-- future issue/project relocation must re-point the link rows in the same
-- statement or add ON UPDATE CASCADE.
--
-- Unlike V8/V9 this table is created EMPTY in the same migration, so the composite
-- FKs have no rows to VALIDATE — the validation scan noted in §3.8 for V8/V9 does not
-- apply. The LOCK does: declaring an FK still takes SHARE ROW EXCLUSIVE on the parent
-- (issues, versions) for the duration of this CREATE TABLE, which blocks concurrent
-- writes to issues. It is brief (no scan behind it), but it is not free.
CREATE TABLE issue_versions (
    id           UUID        PRIMARY KEY,
    issue_id     UUID        NOT NULL,
    version_id   UUID        NOT NULL,
    workspace_id UUID        NOT NULL,
    -- 'FIX' | 'AFFECTS' — validated at the application layer by the Java enum
    -- VersionLinkType. VARCHAR, never a PG ENUM type.
    link_type    VARCHAR(10) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Surrogate id PK + an explicitly named UNIQUE business key (project
    -- convention: workflow_statuses, field_set_items, issue_field_values,
    -- issue_labels), never a composite PK / @EmbeddedId — that is what lets the
    -- entity extend CreatedOnlyEntity.
    --
    -- link_type is part of the key on purpose: the SAME version may be both the
    -- fix version and an affects version of one issue ("regression introduced and
    -- fixed in 2.4.0"), which is a real state (§6.4).
    CONSTRAINT issue_versions_issue_id_version_id_link_type_key
        UNIQUE (issue_id, version_id, link_type),
    CONSTRAINT issue_versions_issue_fk FOREIGN KEY (issue_id, workspace_id)
        REFERENCES issues (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT issue_versions_version_fk FOREIGN KEY (version_id, workspace_id)
        REFERENCES versions (id, workspace_id) ON DELETE CASCADE
);

-- Serves the ?fixVersionId= board/backlog filter, the HQL fixVersion/affectsVersion
-- correlated EXISTS, and the per-version progress roll-up — all of which probe
-- (version_id, link_type).
CREATE INDEX idx_issue_versions_version ON issue_versions (version_id, link_type, issue_id);
-- No separate (issue_id) index: the unique constraint above already creates a btree
-- LEADING on issue_id, which serves the single-issue read, the page batch load
-- (issue_id IN …) and the FK cascade as index-only scans.
--
-- No index on workspace_id either: it is never a standalone predicate here (every
-- read is keyed by issue or by version), and both cascade paths are served by an
-- existing index's leading column — issue delete →
-- issue_versions_issue_id_version_id_link_type_key (issue_id first),
-- version delete → idx_issue_versions_version (version_id first).

-- Retire the LAST V3 placeholder custom field (§3.4): archive, never delete.
-- Existing values keep rendering; the field disappears from pickers, from
-- ProjectConfigResponse.fields and from HQL name resolution — which frees the
-- `fix_version` key and completes the epic's takeover of the three placeholders
-- (`labels` in V8, `components` in V9).
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'fix_version' AND archived_at IS NULL;
