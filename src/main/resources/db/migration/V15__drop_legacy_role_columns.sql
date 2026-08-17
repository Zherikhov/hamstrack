-- ---------------------------------------------------------------------------
-- HD-123 — retire the three legacy `role` VARCHAR columns
-- (roles-permissions-proposal §8.2). This is the destructive half of the model
-- change and the reason V14 is a separate file: once this has run, rollback is
-- a restore, so the release checklist must snapshot the database between the
-- two. The owner runs a single prod instance and squashes migrations, which is
-- what makes that acceptable (§8.4 "Rollback").
-- ---------------------------------------------------------------------------
--
-- ORDER MATTERS AND THE GUARD IS NOT DECORATION. `ALTER COLUMN ... SET NOT
-- NULL` on a column that still has NULLs fails with PostgreSQL's own message,
-- which names the column but not the reason — and the reason is always the
-- same: V14's backfill did not cover a row. The explicit pre-flight below turns
-- that into a sentence a human can act on, and, more importantly, it fails
-- BEFORE any column is dropped, so a half-migrated database is not possible.
--
-- The `role` -> `role_id` translation itself lives in V14; nothing here
-- translates anything. If this script has to change, the bug is in V14.

-- ---------------------------------------------------------------------------
-- 1) Pre-flight: prove the V14 backfill left no NULL
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    orphan_members  BIGINT;
    orphan_projects BIGINT;
    orphan_invites  BIGINT;
BEGIN
    SELECT count(*) INTO orphan_members  FROM workspace_members WHERE role_id IS NULL;
    SELECT count(*) INTO orphan_projects FROM project_members   WHERE role_id IS NULL;
    SELECT count(*) INTO orphan_invites  FROM workspace_invites WHERE role_id IS NULL;

    IF orphan_members + orphan_projects + orphan_invites > 0 THEN
        RAISE EXCEPTION
            'HD-123 V15 aborted: V14''s role backfill left rows without a role_id '
            '(workspace_members=%, project_members=%, workspace_invites=%). '
            'Nothing has been dropped. Find the rows with '
            '"SELECT * FROM workspace_members WHERE role_id IS NULL", fix them with a new '
            'WHERE-scoped migration, then re-run — do NOT edit V14, it has already been applied.',
            orphan_members, orphan_projects, orphan_invites;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2) role_id becomes the only answer
-- ---------------------------------------------------------------------------
ALTER TABLE workspace_members ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE project_members   ALTER COLUMN role_id SET NOT NULL;
ALTER TABLE workspace_invites ALTER COLUMN role_id SET NOT NULL;

-- ---------------------------------------------------------------------------
-- 3) Drop the ordinal ladders' storage
-- ---------------------------------------------------------------------------
-- The Java enums `WorkspaceRole` / `ProjectRole` outlive this migration on
-- purpose: slice S1 still uses them as a legacy bridge at the ~42 call sites
-- that have not moved yet (they are read back from `roles.key`, which is why
-- the built-in keys are the enum names). S3 deletes the enums. `SystemRole` is
-- untouched by this epic and is NOT stored here — it lives on `users`.
ALTER TABLE workspace_members DROP COLUMN role;
ALTER TABLE project_members   DROP COLUMN role;
ALTER TABLE workspace_invites DROP COLUMN role;
