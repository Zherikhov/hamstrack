-- =============================================================================
-- HD-186 load fixture — teardown.
-- Spec: §5.5.2, acceptance criteria 11 and 12.
--
-- Variables: :'slug_prefix', :'email_domain'
--
-- ---------------------------------------------------------------------------
-- BY TENANCY, NOT BY INVENTORY.
--
-- The writing mix creates rows nothing recorded — issues, comments, history, scope
-- events, notifications, and whatever a future mix adds. Deleting "what the generator
-- inserted" is therefore wrong BY CONSTRUCTION, not merely incomplete: it is a plan that
-- cannot be made complete by being more careful. Everything below is scoped by the load
-- workspace ids and the @load.invalid address domain, so a row created by the run at
-- 14:30 is deleted by exactly the same predicate as a row created by the generator the
-- night before.
--
-- ---------------------------------------------------------------------------
-- EXPLICIT ORDER, NOT CASCADE.
--
-- Most of these tables WOULD cascade from `workspaces`, and relying on that was
-- considered and rejected for three reasons:
--
--   1. issues.workspace_id has NO ON DELETE clause (V1: "denormalized for tenant
--      scoping"). Deleting a workspace only works because the cascade through `projects`
--      happens to remove the issues before the NO ACTION check runs at end of statement.
--      That is an ordering coincidence inside PostgreSQL, not a guarantee this harness
--      should be built on.
--   2. mail_send_events.workspace_id has no foreign key at all. It cascades from nothing
--      and a CASCADE-based teardown leaves it behind, silently, forever.
--   3. A cascade deletes without saying what it deleted. An explicit ordered DELETE
--      prints a row count per table, which is what makes the teardown auditable — and
--      what lets the completeness check afterwards be a confirmation rather than the only
--      evidence.
--
-- Nothing here adds a CASCADE to the schema to make the deletion quieter. Where a foreign
-- key refuses, the refusal is the good outcome: it names a table this file has not
-- accounted for, which is information, and the correct response is to add the DELETE
-- here — never to widen a constraint on the product's schema for the convenience of a
-- test fixture.
-- =============================================================================

\set ON_ERROR_STOP on

-- ACCOUNTS-ONLY MODE (teardown.sh's LOAD_ACCOUNTS_ONLY=1). Steps 1-5 — everything that
-- deletes by WORKSPACE — are skipped; steps 6-8, which delete by the ADDRESS DOMAIN, are
-- not. It exists for the ordering that otherwise strands accounts permanently: if the
-- colliding workspace is deleted through the app before this teardown runs, no slug matches
-- any more and nothing here will find the accounts by workspace again.
--
-- Defaulted rather than required, because the variable is absent on every ordinary run and
-- \if on an unset variable is an error.
\if :{?accounts_only}
\else
\set accounts_only 0
\endif

BEGIN;

-- Repair passes and cascades must not restamp rows on their way out; and more to the
-- point, a teardown has no business writing an updated_at anywhere.
SET LOCAL hamstrack.skip_updated_at = 'on';

-- Bound the lock wait. Every transaction in this repository that takes row locks binds
-- first (CLAUDE.md), and a teardown competing with live traffic is exactly the case where
-- an unbounded wait turns a cleanup into an outage.
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '15min';

-- The two handles, resolved once inside the transaction so nothing can shift under us.
--
-- A MATCHED WORKSPACE HOLDING A REAL MEMBER IS EXCLUDED HERE, NOT REFUSED GLOBALLY. The
-- slug prefix is derived from a name a human typed and is therefore forgeable; a single
-- collision used to abort the whole teardown, which strands three quarters of a million
-- fixture rows on production for a reason that has nothing to do with them. Scoping the
-- refusal to the offending workspace protects it just as completely and leaves the rest
-- removable. teardown.sh reports the exclusions and fails the run because of them — the
-- collision is still a finding, it is just no longer a hostage.
CREATE TEMP TABLE td_ws ON COMMIT DROP AS
    SELECT w.id FROM workspaces w
     WHERE w.slug LIKE :'slug_prefix' || '%'
       AND NOT EXISTS (SELECT 1 FROM workspace_members m JOIN users u ON u.id = m.user_id
                        WHERE m.workspace_id = w.id
                          AND u.email NOT LIKE '%@' || :'email_domain');

-- EVERY load account, by the one handle that cannot be lost: the address domain.
--
-- WHICH OF THEM CAN ACTUALLY BE DELETED IS NOT DECIDED HERE, AND THAT IS THE CHANGE.
--
-- Excluding a contaminated workspace (above) leaves its rows in place, and those rows point
-- at load accounts: its members, and whoever created it. Deleting the accounts anyway makes
-- PostgreSQL refuse in step 6, and because this is ONE transaction the whole teardown rolls
-- back — the same "one collision strands everything" failure the scoping above exists to
-- remove, moved one step later. Measured, not predicted: it is what happened the first time
-- that exclusion was tested.
--
-- The repair for that used to be a hand-written pair of exclusions — "not a member of a
-- surviving workspace, and did not create one" — two of roughly twenty foreign-key paths
-- into users. It was correct only because THIS fixture's generator happens to partition its
-- accounts by workspace tag: a claim about today's fixture wearing the clothes of a claim
-- about the category. This bug had already relocated once by then.
--
-- So the question is asked of the catalog instead, and asked LATE: step 6 computes
-- td_user_blocked from pg_constraint AFTER steps 1-5 have deleted everything they are going
-- to, at which point any surviving reference to a load account is a survivor by definition
-- and needs no model of which tables those are.
CREATE TEMP TABLE td_user_all ON COMMIT DROP AS
    SELECT u.id FROM users u WHERE u.email LIKE '%@' || :'email_domain';

CREATE TEMP TABLE td_issue ON COMMIT DROP AS
    SELECT id FROM issues WHERE workspace_id IN (SELECT id FROM td_ws);

CREATE INDEX ON td_issue (id);
CREATE INDEX ON td_ws (id);
CREATE INDEX ON td_user_all (id);

-- Last chance to refuse. teardown.sh has already run the same check, but this one is
-- inside the transaction that does the deleting, which is the only place it is a
-- guarantee rather than a reassurance. It should now be unreachable — td_ws excludes any
-- workspace with a non-load member by construction — and it stays, because "unreachable
-- from today's definition of td_ws" is a property of one CREATE TABLE statement that a
-- later edit can quietly remove.
-- Against td_user_all — every LOAD account — and not against the subset that turns out to
-- be deletable. The two are different questions and conflating them made this guard fire on
-- a correct database: a load account that belongs to BOTH a scoped workspace and an excluded
-- one is not deletable, and the old comparison then reported it as "not a load account".
DO $$
DECLARE bad bigint;
BEGIN
    SELECT count(*) INTO bad
      FROM workspace_members m JOIN users u ON u.id = m.user_id
     WHERE m.workspace_id IN (SELECT id FROM td_ws)
       AND u.id NOT IN (SELECT id FROM td_user_all);
    IF bad > 0 THEN
        RAISE EXCEPTION
            'refusing to tear down: % member(s) of a scoped load workspace are not load '
            'accounts, which means the scoping above stopped excluding them. Nothing has '
            'been deleted.', bad;
    END IF;
END $$;

-- Say what was left alone, and why, in the transaction's own output. teardown.sh reports
-- it too; a reader of the SQL log should not have to go and find that.
SELECT w.slug AS excluded_workspace, count(u.id) AS real_members
  FROM workspaces w
  JOIN workspace_members m ON m.workspace_id = w.id
  JOIN users u ON u.id = m.user_id AND u.email NOT LIKE '%@' || :'email_domain'
 WHERE w.slug LIKE :'slug_prefix' || '%'
 GROUP BY w.slug;

\if :accounts_only
\echo 'ACCOUNTS-ONLY: skipping steps 1-5 (everything scoped by workspace).'
\else

-- ---------------------------------------------------------------------------
-- 1. Children of issues (no workspace_id of their own — reached through td_issue).
-- ---------------------------------------------------------------------------

DELETE FROM comment_mentions  WHERE comment_id IN
       (SELECT id FROM issue_comments WHERE issue_id IN (SELECT id FROM td_issue));
DELETE FROM issue_comments    WHERE issue_id IN (SELECT id FROM td_issue);
DELETE FROM issue_history     WHERE issue_id IN (SELECT id FROM td_issue);
DELETE FROM issue_field_values WHERE issue_id IN (SELECT id FROM td_issue);
DELETE FROM issue_attachments WHERE issue_id IN (SELECT id FROM td_issue);

-- ---------------------------------------------------------------------------
-- 2. Workspace-scoped join tables and events.
-- ---------------------------------------------------------------------------

DELETE FROM issue_labels        WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM issue_versions      WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM sprint_scope_events WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM notifications       WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM saved_filters       WHERE workspace_id IN (SELECT id FROM td_ws);
-- No FK, no cascade, no reader. It would survive a CASCADE teardown indefinitely and the
-- category check in completeness.sql is what found it.
DELETE FROM mail_send_events    WHERE workspace_id IN (SELECT id FROM td_ws);

-- ---------------------------------------------------------------------------
-- 3. Issues.
--
-- Self-referencing (parent_id) with no ON DELETE clause, so children must go first or the
-- FK refuses. One statement handles it: sub-tasks are level 0 and never parents.
-- ---------------------------------------------------------------------------

UPDATE issues SET parent_id = NULL WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM issues WHERE workspace_id IN (SELECT id FROM td_ws);

-- ---------------------------------------------------------------------------
-- 4. Project- and workspace-scoped catalogs.
-- ---------------------------------------------------------------------------

DELETE FROM sprints    WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM versions   WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM components WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM labels     WHERE workspace_id IN (SELECT id FROM td_ws);

-- Field sets are bound from projects, so the binding is cleared before the set is dropped.
UPDATE projects SET field_set_id = NULL WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM field_set_items WHERE set_id IN
       (SELECT id FROM field_sets WHERE scope_workspace_id IN (SELECT id FROM td_ws));
DELETE FROM field_sets  WHERE scope_workspace_id IN (SELECT id FROM td_ws);
DELETE FROM field_defs  WHERE scope_workspace_id IN (SELECT id FROM td_ws);

-- The rest of the workspace-scoped taxonomy. The fixture creates none of these today, and
-- they are here anyway: the moment a mix starts creating a workspace status or priority,
-- the teardown is already correct instead of being wrong once.
DELETE FROM workflow_transitions WHERE workflow_id IN
       (SELECT id FROM workflows WHERE scope_workspace_id IN (SELECT id FROM td_ws));
DELETE FROM workflow_statuses WHERE workflow_id IN
       (SELECT id FROM workflows WHERE scope_workspace_id IN (SELECT id FROM td_ws));
DELETE FROM priority_set_items WHERE set_id IN
       (SELECT id FROM priority_sets WHERE scope_workspace_id IN (SELECT id FROM td_ws));
DELETE FROM issue_type_set_items WHERE set_id IN
       (SELECT id FROM issue_type_sets WHERE scope_workspace_id IN (SELECT id FROM td_ws));
UPDATE projects SET workflow_id = NULL, priority_set_id = NULL, issue_type_set_id = NULL
 WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM workflows       WHERE scope_workspace_id IN (SELECT id FROM td_ws);
DELETE FROM priority_sets   WHERE scope_workspace_id IN (SELECT id FROM td_ws);
DELETE FROM issue_type_sets WHERE scope_workspace_id IN (SELECT id FROM td_ws);
DELETE FROM statuses        WHERE scope_workspace_id IN (SELECT id FROM td_ws);
DELETE FROM priorities      WHERE scope_workspace_id IN (SELECT id FROM td_ws);
DELETE FROM issue_types     WHERE scope_workspace_id IN (SELECT id FROM td_ws);

-- ---------------------------------------------------------------------------
-- 5. Membership, projects, roles, workspaces.
-- ---------------------------------------------------------------------------

DELETE FROM project_members WHERE project_id IN
       (SELECT id FROM projects WHERE workspace_id IN (SELECT id FROM td_ws));
DELETE FROM projects          WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM workspace_invites WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM workspace_members WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM role_permissions  WHERE role_id IN
       (SELECT id FROM roles WHERE workspace_id IN (SELECT id FROM td_ws));
DELETE FROM roles             WHERE workspace_id IN (SELECT id FROM td_ws);
DELETE FROM workspaces        WHERE id IN (SELECT id FROM td_ws);

\endif

-- ---------------------------------------------------------------------------
-- 6. The load accounts.
--
-- Last, and deliberately WITHOUT a preceding sweep of anything that might still reference
-- them. If a foreign key refuses here, that refusal is the deliverable: it names a table
-- holding load data that step 1-5 did not account for, in a database that has just told
-- us our model of it was incomplete. Read the constraint name, add the DELETE above,
-- re-run. Do not add a CASCADE.
-- ---------------------------------------------------------------------------

-- For EVERY load account, deletable or not. These rows are the account's own and they are
-- the fixture's by construction; an account that survives because somebody else's workspace
-- still needs it has no business keeping a live refresh token, and revoke.sh deletes exactly
-- the same set from the same handle.
DELETE FROM refresh_tokens      WHERE user_id IN (SELECT id FROM td_user_all);
DELETE FROM email_verifications WHERE user_id IN (SELECT id FROM td_user_all);
DELETE FROM password_resets     WHERE user_id IN (SELECT id FROM td_user_all);
DELETE FROM oauth_accounts      WHERE user_id IN (SELECT id FROM td_user_all);
DELETE FROM notifications       WHERE user_id IN (SELECT id FROM td_user_all);
DELETE FROM mail_send_events    WHERE sender_user_id IN (SELECT id FROM td_user_all);
DELETE FROM workspace_invites   WHERE email LIKE '%@' || :'email_domain';

-- ---------------------------------------------------------------------------
-- 7. The rows whose ONLY tenancy handle is the address.
--
-- failed_email is keyed by recipient and carries neither a workspace nor a user, and a
-- mail_send_events row written for a send with no authenticated sender has both
-- workspace_id and sender_user_id NULL. Nothing above reaches either: they cascade from
-- nothing, no foreign key names them, and steps 1-6 delete by ids they do not have. The
-- ADDRESS is the tenancy handle here — the same handle td_user_all is built from — so it is
-- what deletes them.
--
-- This runs BEFORE `users`, deliberately, so that a failure here still leaves the accounts
-- in place to be found by the address next time.
-- ---------------------------------------------------------------------------

DELETE FROM failed_email     WHERE recipient       LIKE '%@' || :'email_domain';
DELETE FROM mail_send_events WHERE recipient_email LIKE '%@' || :'email_domain';

-- ---------------------------------------------------------------------------
-- 8. WHICH ACCOUNTS ARE STILL NEEDED — ASKED OF pg_constraint, NOT OF A LIST.
--
-- Everything this teardown is going to delete has now been deleted, so any row that still
-- points at a load account is a SURVIVOR: a member of an excluded workspace, the creator of
-- one, or a reference through a column nobody here has thought about. Enumerating the
-- referencing columns from the catalog — the same mechanism completeness.sql's category 4
-- uses — means this needs no model of the schema and cannot go stale one migration later.
--
-- Running it HERE rather than at the top is what makes it simple: at the top the answer
-- would have to be "referenced by a row that will still exist afterwards", which is a
-- prediction; here it is "referenced", which is a fact.
--
-- Composite foreign keys into users do not exist today. Restricting to single-column ones
-- keeps the query honest rather than wrong, and leaves the gap visible.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE td_user_blocked (user_id uuid, tbl text, col text) ON COMMIT DROP;

DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT cl.relname AS t, att.attname AS col
          FROM pg_constraint con
          JOIN pg_class cl      ON cl.oid = con.conrelid
          JOIN pg_class fcl     ON fcl.oid = con.confrelid
          JOIN pg_namespace ns  ON ns.oid = cl.relnamespace
          JOIN unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
          JOIN pg_attribute att ON att.attrelid = cl.oid AND att.attnum = k.attnum
         WHERE con.contype = 'f' AND ns.nspname = 'public'
           AND fcl.relname = 'users'
           AND cl.relkind = 'r'
           AND array_length(con.conkey, 1) = 1
         ORDER BY cl.relname, att.attname
    LOOP
        EXECUTE format(
            'INSERT INTO td_user_blocked (user_id, tbl, col)
             SELECT DISTINCT c.%I, %L, %L FROM public.%I c
               JOIN td_user_all u ON u.id = c.%I',
            r.col, r.t, r.col, r.t, r.col);
    END LOOP;
END $$;

CREATE INDEX ON td_user_blocked (user_id);

-- Named, not merely counted: "3 accounts could not be deleted" sends its reader looking,
-- and the table and column are the finding.
SELECT b.tbl AS still_referenced_by, b.col AS via_column, count(DISTINCT b.user_id) AS accounts
  FROM td_user_blocked b GROUP BY b.tbl, b.col ORDER BY b.tbl, b.col;

DELETE FROM users
      WHERE id IN (SELECT id FROM td_user_all)
        AND id NOT IN (SELECT user_id FROM td_user_blocked);

COMMIT;

-- VACUUM (ANALYZE), NOT VACUUM FULL.
--
-- The space returns to PostgreSQL for reuse and NOT to the filesystem. `df` will look
-- unchanged and that is correct, not a failed teardown — VACUUM FULL takes an ACCESS
-- EXCLUSIVE lock on every table it rewrites and is not run on production for this. The
-- grown volume (§5.1 precondition 5) is what absorbs the residual, which is the second
-- reason growing it is a precondition and not a nicety.
VACUUM (ANALYZE);
