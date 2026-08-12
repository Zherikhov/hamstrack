-- ---------------------------------------------------------------------------
-- HD-26 (Saved filters — personal + shared) — Advanced Search proposal §8.3/§10.1
-- ---------------------------------------------------------------------------
-- A saved filter is a named, reusable HQL data source scoped to one workspace.
-- Owner-editable; when shared=TRUE it is read-only to every other workspace
-- member. Every read/write is scoped by workspace_id (the tenant boundary).
--
-- VARCHAR throughout — no CHAR(n), no PG ENUM (CLAUDE.md Hibernate-7 gotchas).
-- created_at/updated_at carry DEFAULT NOW() + the set_updated_at() trigger as a
-- raw-SQL safety net; JPA sets them via Spring Data auditing (BaseEntity).

CREATE TABLE saved_filters (
    id           UUID         PRIMARY KEY,                       -- app-generated UUID v7
    workspace_id UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    owner_id     UUID         NOT NULL REFERENCES users(id),
    name         VARCHAR(120) NOT NULL,
    hql          VARCHAR(2000) NOT NULL,                         -- matches HqlParser.MAX_QUERY_LENGTH
    shared       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Name unique per (workspace, owner); different owners may reuse a name (§8.3).
    CONSTRAINT uq_saved_filter_owner_name UNIQUE (workspace_id, owner_id, name)
);

CREATE TRIGGER trg_saved_filters_updated_at
    BEFORE UPDATE ON saved_filters
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Listing reads "owner = caller OR shared = TRUE" within one workspace.
CREATE INDEX ix_saved_filters_ws_owner  ON saved_filters (workspace_id, owner_id);
CREATE INDEX ix_saved_filters_ws_shared ON saved_filters (workspace_id, shared);
