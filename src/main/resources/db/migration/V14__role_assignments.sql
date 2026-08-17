-- ---------------------------------------------------------------------------
-- HD-123 — role assignments + the no-op backfill (roles-permissions-proposal
-- §8.2 / §8.4). Additive only: the legacy `role` VARCHAR columns are still
-- present and still authoritative-compatible after this script. V15 is what
-- makes the change destructive, and it is a separate file on purpose so the
-- release checklist has a snapshot point between the two.
-- ---------------------------------------------------------------------------
--
-- THE CLAIM THIS SCRIPT HAS TO EARN (§8.4): after the upgrade, NO USER'S
-- EFFECTIVE ABILITIES CHANGE. The proof is in three parts:
--
--   1. every membership/invite row is mapped to the built-in role WITH THE SAME
--      KEY, whose permission set is §2.2 (today's enforced behaviour)
--      transcribed — so anyone who had an explicit role keeps it;
--   2. every project_members row that says VIEWER becomes MEMBER, because a
--      VIEWER row grants EVERYTHING today (`isAtLeast(VIEWER)` is true for all
--      three project roles). Leaving them as the new, empty VIEWER would make
--      the one group of users who were marked read-only the ONLY group whose
--      permissions changed on upgrade — the exact inversion of the goal;
--   3. every workspace becomes `project_access_mode = 'OPEN'` with a NULL
--      default project role (→ built-in Contributor), which is precisely
--      "any workspace member may work in any project" — i.e. today.
--
-- NOTHING IS INSERTED INTO project_members. Deliberate (§8.4): backfilling a
-- row per (workspace member x project) would turn an implicit rule into N*M
-- rows, destroy the distinction between "explicitly added" and "backfilled",
-- and make private projects harder later. The default role expresses the same
-- thing with one column.
--
-- !! ONE-SHOT UPGRADE — NEVER REPLAY AGAINST A POST-V14 DATABASE !!
-- The three backfill UPDATEs below have no `WHERE role_id IS NULL` guard by
-- design (they run against a database where role_id was created seconds ago and
-- is NULL everywhere). Replayed later they would reset every deliberate role
-- change made since. Flyway will never re-run this; a human pasting it into
-- psql would. If a repair is ever needed, write a new, WHERE-scoped V{n}.

-- !! TRANSACTION REQUIREMENT !! Section 2 below runs blanket UPDATEs over
-- `workspaces` and `projects`, both of which carry V1's updated_at trigger.
-- Without V11's `SET LOCAL hamstrack.skip_updated_at = 'on'` guard this release
-- would stamp every workspace and every project as "just edited", with no error
-- anywhere. (Only `updated_at` — that is all the trigger writes. A native UPDATE
-- never touches a JPA `@Version`, which Hibernate maintains in its own DML.) `SET LOCAL` is only effective INSIDE a transaction; Flyway wraps
-- each script in one, and that is the only reason it works. If anyone ever adds
-- a statement here that cannot run in a transaction, or turns on
-- `spring.flyway.mixed` / `executeInTransaction=false`, PostgreSQL downgrades
-- SET LOCAL to a WARNING and the guard silently stops guarding. Keep this
-- script transactional. (Same requirement as V11 and V12.)
SET LOCAL hamstrack.skip_updated_at = 'on';

-- ---------------------------------------------------------------------------
-- 1) The new columns
-- ---------------------------------------------------------------------------
-- ON DELETE is NO ACTION (the PostgreSQL default) on every one of these: a role
-- that is in use must fail loudly. Deleting a role in use is a 409 handled in
-- the service with a usage payload (§11.4); the FK is the backstop, not the UX.
--
-- NO ACTION, not RESTRICT, and the difference is load-bearing here: NO ACTION
-- defers its check to the END of the statement, RESTRICT checks immediately and
-- cannot be deferred. `DELETE FROM workspaces` cascades to `roles` (ON DELETE
-- CASCADE, V13) and to `workspace_members` in the same statement — under NO
-- ACTION the members are gone by the time the check runs, so it passes; under a
-- literal RESTRICT the delete would abort even though nothing is left pointing
-- at the role. Workspace deletion has no endpoint today, but do not "tighten"
-- these to RESTRICT: it would break it before it is written.
ALTER TABLE workspace_members  ADD COLUMN role_id UUID REFERENCES roles(id);
ALTER TABLE project_members    ADD COLUMN role_id UUID REFERENCES roles(id);
ALTER TABLE workspace_invites  ADD COLUMN role_id UUID REFERENCES roles(id);

-- NULL = "the workspace's default", and the workspace's NULL = built-in
-- Contributor. Two levels of NULL rather than two levels of stored ids so that
-- changing the workspace default takes effect on every project that never
-- overrode it, with no data migration.
ALTER TABLE workspaces ADD COLUMN default_project_role_id UUID REFERENCES roles(id);
ALTER TABLE projects   ADD COLUMN default_project_role_id UUID REFERENCES roles(id);

-- OPEN  = a workspace member with no explicit project_members row inherits the
--         project's default role (today's behaviour).
-- STRICT= only people explicitly added to a project can change anything in it.
-- There is NO "enforcement off" mode (§5.2/§17.1): permissions always mean
-- exactly what they say, in both modes. What the mode decides is only whether
-- people who were never added to a project get a role at all.
ALTER TABLE workspaces ADD COLUMN project_access_mode VARCHAR(20) NOT NULL DEFAULT 'OPEN';

CREATE INDEX idx_workspace_members_role ON workspace_members(role_id);
CREATE INDEX idx_project_members_role   ON project_members(role_id);

-- ---------------------------------------------------------------------------
-- 2) The backfill (§8.4)
-- ---------------------------------------------------------------------------
-- workspace_members / workspace_invites: same key, same abilities.
UPDATE workspace_members SET role_id = CASE role
        WHEN 'OWNER'  THEN '00000000-0000-7000-8000-000000000001'::uuid
        WHEN 'ADMIN'  THEN '00000000-0000-7000-8000-000000000002'::uuid
        ELSE               '00000000-0000-7000-8000-000000000003'::uuid   -- MEMBER
    END;

UPDATE workspace_invites SET role_id = CASE role
        WHEN 'OWNER'  THEN '00000000-0000-7000-8000-000000000001'::uuid
        WHEN 'ADMIN'  THEN '00000000-0000-7000-8000-000000000002'::uuid
        ELSE               '00000000-0000-7000-8000-000000000003'::uuid
    END;

-- project_members: MANAGER -> MANAGER, and BOTH 'MEMBER' AND 'VIEWER' -> the
-- built-in MEMBER (Contributor). The VIEWER remap is the one line in this whole
-- migration that changes a stored value rather than translating it, and it is
-- the line that keeps the promise: today a VIEWER row grants everything
-- (§2.2 — `ProjectRole.isAtLeast(VIEWER)` is true for every role, so no gate
-- anywhere distinguishes a VIEWER from a MEMBER). Mapping it to the new,
-- deliberately-empty VIEWER would silently take every ability away from exactly
-- those users.
UPDATE project_members SET role_id = CASE role
        WHEN 'MANAGER' THEN '00000000-0000-7000-8000-000000000011'::uuid
        ELSE                '00000000-0000-7000-8000-000000000012'::uuid   -- MEMBER and VIEWER alike
    END;

-- Every existing workspace is OPEN with a NULL default (→ Contributor). This is
-- also the DEFAULT for the column, but it is stated explicitly here so the
-- upgrade policy is visible as a decision rather than inherited from a DDL
-- default that a later migration could change. Note §13: the
-- `app.workspace.default-project-access-mode` property (S7) governs NEWLY
-- CREATED workspaces only and never retroactively changes an existing one.
UPDATE workspaces SET project_access_mode = 'OPEN', default_project_role_id = NULL;
UPDATE projects   SET default_project_role_id = NULL;
