-- ---------------------------------------------------------------------------
-- HD-31 (Components) — labels-components-versions-proposal §5.2
-- ---------------------------------------------------------------------------
-- A component is a curated, PROJECT-scoped module ("Billing", "iOS app") with an
-- optional lead and an optional auto-assign switch. Single-valued per issue
-- (issues.component_id), per the story. Components are *content*, not bound
-- taxonomy — they never enter the catalog → set → project-binding machinery
-- (§3.2) and ProjectConfigService is untouched.
--
-- VARCHAR throughout — no CHAR(n), no PG ENUM (CLAUDE.md Hibernate-7 gotchas).
-- created_at/updated_at carry DEFAULT NOW() + the set_updated_at() trigger as a
-- raw-SQL safety net; JPA sets them via Spring Data auditing (BaseEntity).
-- Purely additive: no data reset, no edits to an applied migration.

CREATE TABLE components (
    id           UUID         PRIMARY KEY,                        -- app-generated UUID v7
    -- Denormalized tenant scope (as issues.workspace_id already is), so a
    -- tenant-scoped query never has to join through projects — and so the
    -- composite FK from issues below can be expressed at all (§3.1, §3.8).
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_id   UUID         NOT NULL REFERENCES projects(id)   ON DELETE CASCADE,
    name         VARCHAR(80)  NOT NULL,
    description  VARCHAR(500),
    -- Nullable: ON DELETE SET NULL survives the lead's account deletion. A lead
    -- who merely leaves the workspace keeps the row (auto-assign then silently
    -- skips, §5.4).
    lead_id      UUID         REFERENCES users(id) ON DELETE SET NULL,
    auto_assign  BOOLEAN      NOT NULL DEFAULT FALSE,   -- assign NEW issues to lead_id
    archived_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Referenced by the composite FK on issues below (§3.8). Postgres requires a
    -- UNIQUE constraint on the exact referenced column list; (id) alone is not
    -- sufficient for an FK on (component_id, workspace_id). Redundant as a
    -- *lookup* index (components_pkey already covers id) but structurally required.
    CONSTRAINT components_id_workspace_id_key UNIQUE (id, workspace_id)
);

CREATE TRIGGER trg_components_updated_at
    BEFORE UPDATE ON components
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Case-insensitive uniqueness inside one PROJECT (a plain UNIQUE can't lower()).
-- The same name in a sibling project is allowed — components are project-owned.
-- Archived rows keep their slot on purpose (§5.4): reusing an archived
-- component's name is a 409 that nudges toward unarchive/rename.
CREATE UNIQUE INDEX components_project_name_uk ON components (project_id, lower(name));
-- No separate (project_id) index: components_project_name_uk above already LEADS on
-- project_id, so it serves every per-project lookup AND supplies the ORDER BY
-- lower(name) that ComponentRepository.findAllByProject asks for. A second index
-- would only add write amplification.
--
-- idx_components_workspace, by contrast, IS needed: the ON DELETE CASCADE from
-- workspaces probes components by workspace_id, and components_id_workspace_id_key
-- leads on id, so it cannot serve that lookup.
CREATE INDEX idx_components_workspace ON components (workspace_id);

-- ---------------------------------------------------------------------------
-- issues.component_id — the story's acceptance criterion: deleting a component
-- nulls it on issues, no orphan FK.
-- ---------------------------------------------------------------------------
-- HD-31 has no join table, so §3.8's "the pair must agree on workspace_id" rule
-- lands on THIS reference instead: the FK is COMPOSITE over
-- (component_id, workspace_id), which makes "an issue in tenant A carries a
-- component of tenant B" unrepresentable rather than merely rejected in Java.
--
-- issues_id_workspace_id_key is NOT re-added here: V8 declares it ONCE for the
-- whole HD-6 epic (see the comment in V8__labels.sql). A second
--   ALTER TABLE issues ADD CONSTRAINT issues_id_workspace_id_key …
-- fails with `constraint "issues_id_workspace_id_key" already exists`.
--
-- ON DELETE SET NULL names the column list EXPLICITLY (PG 15+; we require PG 16):
-- a bare SET NULL would try to null the NOT NULL issues.workspace_id as well and
-- the cascade would fail at delete time. MATCH SIMPLE (the default) skips the
-- check entirely when component_id is NULL — exactly right for component-less
-- issues, and harmless here because workspace_id is NOT NULL, so the only way to
-- have a partial NULL is "no component at all".
--
-- Deliberate contrast with issues.type_id/status_id, which carry NO FK: those are
-- remapped in bulk by the admin console and an FK would break delete-with-remap.
-- component_id has no remap path and its whole acceptance criterion is
-- FK-enforced nulling, so the FK belongs here.
ALTER TABLE issues ADD COLUMN component_id UUID;
ALTER TABLE issues ADD CONSTRAINT issues_component_fk
    FOREIGN KEY (component_id, workspace_id) REFERENCES components (id, workspace_id)
    ON DELETE SET NULL (component_id);

-- Partial: the board/backlog `?componentId=` filter and the usage counts only ever
-- probe non-NULL values, and most issues carry no component.
CREATE INDEX idx_issues_component ON issues (component_id) WHERE component_id IS NOT NULL;

-- Retire the V3 placeholder custom field (§3.4): archive, never delete. Existing
-- values keep rendering; the field disappears from pickers, from
-- ProjectConfigResponse.fields and from HQL name resolution — which frees the
-- `component`/`components` HQL keys for the first-class field this slice registers.
UPDATE field_defs SET archived_at = NOW()
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND is_system = TRUE AND key = 'components' AND archived_at IS NULL;
