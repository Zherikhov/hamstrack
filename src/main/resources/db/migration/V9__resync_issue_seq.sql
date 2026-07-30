-- Repair project issue counters clobbered by demo seeding.
--
-- DemoDataService runs in a single transaction: it creates the project
-- (issue_seq = 0, entity stays managed), then creates issues via
-- IssueService, whose native `UPDATE ... issue_seq + 1 RETURNING` bumps the DB
-- counter but not the managed entity. A later `project.setFieldSet(...); save()`
-- then flushed the stale entity and overwrote issue_seq back to 0, so the next
-- user-created issue got number = 1 and collided on (project_id, number).
--
-- The entity mapping is now updatable=false so JPA can never write issue_seq
-- again; this backfills the counters that were already corrupted. Resync each
-- project's counter up to its highest existing issue number. Projects with no
-- issues, or whose counter is already ahead, are left untouched.
UPDATE projects p
SET issue_seq = sub.max_number
FROM (
    SELECT project_id, MAX(number) AS max_number
    FROM issues
    GROUP BY project_id
) sub
WHERE sub.project_id = p.id
  AND p.issue_seq < sub.max_number;
