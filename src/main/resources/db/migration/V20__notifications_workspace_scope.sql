-- ---------------------------------------------------------------------------
-- HD-135 — notifications belong to a workspace
-- ---------------------------------------------------------------------------
-- A notification's title/body are DENORMALISED workspace content (the mentioning
-- user's name, up to 120 chars of the comment). Without a workspace_id, every
-- finder is keyed on the user alone, so a member removed from a workspace kept a
-- full, readable inbox of that workspace's text. HD-132 shipped the first real
-- revocation event in the product, which is what turned this from latent to live.
--
-- FK SHAPE: single-column, and NOT for the reason V19 gives. V19's statuses/types
-- could not be composite because the parent has no workspace_id and one global row
-- is referenced from every tenant. Here the parent IS `workspaces`, so
-- (workspace_id) -> workspaces(id) already carries tenancy exactly and there is no
-- second tenancy fact to force agreement with. Same shape as workspace_members,
-- workspace_invites and projects. Do not transplant V19's argument to this line.
--
-- ON DELETE CASCADE, as a child of `workspaces` whose rows have no meaning without
-- their parent: a notification whose workspace is gone is unrenderable and
-- unreachable, and NO ACTION would make a future workspace purge fail on a table
-- nobody would think to look at. Cascade is the norm for children of `workspaces`,
-- but it is NOT universal and this line must not be read as saying so:
-- `issues.workspace_id` (V1__init_schema.sql) is a direct child that deliberately
-- does NOT cascade, and CLAUDE.md's data-reset ordering rule exists because of that
-- one exception. Each child chooses; this one chooses cascade, for the reason above.
--
-- LOCKS TAKEN, since the statement ordering below is argued from them. `ADD COLUMN`
-- takes ACCESS EXCLUSIVE on `notifications`, and Flyway runs the whole script in one
-- transaction, so that lock is held to commit whatever follows it. The one lock this
-- script takes on a table it does not own comes from `ADD CONSTRAINT … FOREIGN KEY`:
-- SHARE ROW EXCLUSIVE on `workspaces`, which blocks INSERT/UPDATE/DELETE of workspace
-- rows (every tenant's — it is a table-level lock) and further DDL there, while
-- leaving reads unaffected. It does NOT block inserts into workspaces' FK children,
-- which need only ROW SHARE. Nothing contends in practice: deploys stop the old
-- container before the new one starts (HD-93), so all of this runs with no concurrent
-- writer.
--
-- BACKFILL: the workspace id is parsed out of `link`, which a mention notification
-- carries in the shape CommentService builds unconditionally
-- ("/w/{wsId}/p/{projectId}?issue={n}"), and is then VALIDATED BY THE JOIN to
-- `workspaces` — so a row only gets a value if that value is a real workspace, and
-- the FK below cannot fail. The regex is strict UUID shape so the ::uuid cast
-- cannot raise 22P02 and abort the deploy.
--
-- THE LINK FORMAT AND THIS REGEX ARE ONE COUPLING SPANNING TWO LANGUAGES, and
-- nothing in the compiler relates them. V20NotificationsWorkspaceScopeTest builds a
-- link through the real producer (post a comment that mentions someone), replays it
-- into a schema migrated to V19, runs THIS migration over it and asserts the row
-- lands in the right workspace. Change the link shape and that test fails; that is
-- its only job. Do not replace it with a hand-written link literal — a literal
-- tests the copy, not the original.
--
-- NOT VIA comment_mentions: there is no key from a notification to the mention
-- that produced it. Only (user_id, two independently-stamped @CreatedDate values)
-- correlate them, and matching on timestamp proximity can attribute a row to the
-- wrong workspace — which is the exact defect this migration fixes.
--
-- THE DELETE IS A BACKSTOP, NOT A PLAN. A row whose workspace cannot be recovered
-- can never be shown again under the new rule, and nothing in the schema holds the
-- information to repair it, so leaving it (NULL) would be a permanently invisible
-- row plus a nullable column inviting the first `workspace_id IS NULL OR ...` to
-- reopen the whole ticket. But "cannot be repaired" is not "must not be seen again":
-- the rows are COPIED INTO `notifications_unresolvable_v20` before they are deleted,
-- so an operator who meets this after the fact can still answer the question that
-- otherwise only the pre-flight below could answer before it. The copy is made ONLY
-- when the count is non-zero — a clean install must not inherit an empty artefact of
-- a condition it never met.
--
-- WHY A QUARANTINE TABLE IS NOT THE THING THIS TICKET FORBIDS. HD-135 exists to stop
-- untenanted notification content being READABLE, and readable means reachable by a
-- request. `notifications_unresolvable_v20` is reachable by no request: no @Entity
-- maps it, no repository names it, no endpoint reads it, and `ddl-auto=validate`
-- ignores tables no mapping claims — so no code path in the application can name it.
-- Its only reader is an operator with a database shell, who could already read
-- `notifications` in full. It is an operator artefact rather than a surface, and it
-- is treated as one: the docs tell the operator to DROP it once they have their
-- answer, and a later migration may drop it unconditionally.
--
-- OPERATOR, BEFORE YOU DEPLOY THIS TO AN INSTANCE THAT HOLDS DATA: run the pre-flight
-- below against that instance's database and read the number. It is the count of
-- notification rows this migration will remove from `notifications`, and no
-- environment the authors could reach was able to answer it for yours — the
-- development database held no notifications at all when this was written, so the
-- backfill and the delete have never met a production row. Expected answer: 0.
--
--     SELECT count(*) AS unresolvable
--       FROM notifications n
--       LEFT JOIN workspaces w
--         ON w.id = substring(
--              n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/'
--            )::uuid
--      WHERE w.id IS NULL;
--
-- IF IT RETURNS NON-ZERO, THERE ARE TWO CAUSES AND THEY WANT OPPOSITE ACTIONS. The
-- pre-flight tests two things at once — that `link` PARSES, and that the workspace it
-- names STILL EXISTS — and those come apart. Split them:
--
--     SELECT n.link IS NULL
--            OR substring(
--                 n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/'
--               ) IS NULL                        AS link_did_not_parse,
--            count(*)
--       FROM notifications n
--       LEFT JOIN workspaces w
--         ON w.id = substring(
--              n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/'
--            )::uuid
--      WHERE w.id IS NULL
--      GROUP BY 1;
--
--   false — the link parsed, but the workspace it names is GONE. This is the ordinary
--     way to get a non-zero number and it is not a defect in this migration: before
--     V20 there was no foreign key, so rows orphaned by a workspace removed with
--     operator SQL, by a partial restore, or by a dump-and-reload accumulated silently
--     with nothing in the schema to notice them. Those rows were already unrenderable.
--     Deleting them is CORRECT — DEPLOY, then read the quarantine table and drop it.
--
--   true — `link` is absent or is not the shape above, which means some notification
--     producer wrote a row this migration does not know how to read. That is the
--     answer worth reporting: post the count and this split on the issue tracker
--     (github.com/Zherikhov/easyTask/issues) so the next operator inherits it. The
--     rows sit in the quarantine table either way, so the deploy is not blocked on
--     the report.

ALTER TABLE notifications ADD COLUMN workspace_id UUID;

UPDATE notifications n
   SET workspace_id = w.id
  FROM workspaces w
 WHERE n.workspace_id IS NULL
   AND w.id = substring(
         n.link from '^/w/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/'
       )::uuid;

-- Quarantine before delete, and ONLY when there is something to quarantine (see the
-- header): the copy is what keeps the question answerable after the deploy, and the
-- IF is what keeps a clean install from inheriting an empty table forever.
DO $$
DECLARE unresolvable bigint;
BEGIN
    SELECT count(*) INTO unresolvable FROM notifications WHERE workspace_id IS NULL;
    IF unresolvable > 0 THEN
        EXECUTE 'CREATE TABLE notifications_unresolvable_v20 AS '
             || 'SELECT * FROM notifications WHERE workspace_id IS NULL';
        EXECUTE 'COMMENT ON TABLE notifications_unresolvable_v20 IS '
             || quote_literal('HD-135/V20: notification rows whose workspace could not be '
                           || 'resolved from `link`, copied here before deletion. Nothing in '
                           || 'the application maps or reads this table. Read it, report a '
                           || 'non-zero link_did_not_parse split on the issue tracker, then '
                           || 'DROP it.');
        RAISE NOTICE 'HD-135: % notification row(s) could not be attributed to a workspace; copied into notifications_unresolvable_v20, then removed from notifications. Read docs/self-hosting.md before deciding what that means.', unresolvable;
    END IF;
END $$;

DELETE FROM notifications WHERE workspace_id IS NULL;

ALTER TABLE notifications ALTER COLUMN workspace_id SET NOT NULL;

ALTER TABLE notifications
    ADD CONSTRAINT notifications_workspace_id_fkey
    FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;

-- For the CASCADE's RI scan and for future per-workspace queries (mute, digest).
-- NOT the index the read filter uses — that is idx_notifications_user (V1).
CREATE INDEX idx_notifications_workspace ON notifications(workspace_id);
