-- =============================================================================
-- HD-186 load fixture — post-generation repair, statistics and size report.
-- Spec: §4.2 (generation guards 2 and 3), §6, §4.9 (the fixture's row counts go in the
-- configuration fingerprint).
--
-- Runs in its OWN psql session, so it cannot see 10-generate.sql's temp tables and does
-- not try to: it finds its work through the slug prefix, which is the same durable handle
-- teardown uses. An inventory written down at generation time is exactly what §5.5 says
-- not to depend on, and a repair pass that depends on one is the first place that
-- dependency would creep back in.
--
-- Variables: :'slug_prefix', :'email_domain'
-- =============================================================================

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- 1. Resync projects.issue_seq — the repair V9 performed, for the same reason.
--
-- issue_seq is a DB-maintained counter. The application moves it ONLY through
-- ProjectRepository.incrementAndGetIssueSeq (a native UPDATE ... RETURNING), and the
-- entity marks the column @Column(updatable = false) so a stale managed Project can never
-- write it back. This fixture inserted issues directly and therefore never touched the
-- counter, so it now says 0 while the project holds 25 000 numbered issues.
--
-- Leave it and the very next issue a REAL user files in that project is issued
-- number = 1 and 500s on issues_project_id_number_key. That is a production data path
-- broken by a measurement fixture, which is the worst outcome in this whole plan short of
-- filling the disk.
--
-- COALESCE, not MAX alone: a project the generator sized to zero issues has no MAX.
-- ---------------------------------------------------------------------------

BEGIN;

-- The generator's own timestamps are deliberate; a repair pass must not restamp them.
-- Same reason V11, V12 and V18 open with this line.
SET LOCAL hamstrack.skip_updated_at = 'on';

-- THE PREFIX IS NOT A PROOF OF OWNERSHIP, AND THIS STATEMENT WRITES issue_seq.
--
-- A workspace slug is derived from a name a user typed, so the prefix is forgeable: a real
-- tenant whose name slugifies into it would have its projects' issue_seq rewritten by a
-- load fixture's repair pass — and issue_seq is the counter whose desync makes the next
-- real issue collide on issues_project_id_number_key (V9 exists because that happened).
-- The random LOAD_RUN_ID in the prefix makes the collision very unlikely; this makes it
-- harmless. Membership is the check, exactly as it is in the teardown: a workspace is this
-- fixture's only if EVERY member of it is an @load.invalid account.
UPDATE projects p
   SET issue_seq = COALESCE((SELECT max(i.number) FROM issues i WHERE i.project_id = p.id), 0)
  FROM workspaces w
 WHERE w.id = p.workspace_id
   AND w.slug LIKE :'slug_prefix' || '%'
   AND NOT EXISTS (SELECT 1 FROM workspace_members m JOIN users u ON u.id = m.user_id
                    WHERE m.workspace_id = w.id
                      AND u.email NOT LIKE '%@' || :'email_domain');

-- Refuse to leave a project whose counter disagrees with its rows. A silent partial
-- resync is indistinguishable from a successful one until somebody files an issue.
--
-- The prefix travels through a transaction-local GUC rather than a psql variable because
-- psql does NOT interpolate :'name' inside a dollar-quoted body — a $$ block that looks
-- like it reads the variable would silently compare against the literal text ":'slug_prefix'"
-- and pass by matching nothing.
SELECT set_config('hamstrack.load_slug_prefix', :'slug_prefix', true);
SELECT set_config('hamstrack.load_email_domain', :'email_domain', true);

-- The same membership condition as the UPDATE, and for the same reason: a workspace that
-- was deliberately left alone must not then be reported as a failed resync. Repeating the
-- predicate rather than trusting the row count above is what makes this a verification.
DO $$
DECLARE bad bigint;
BEGIN
    SELECT count(*) INTO bad
      FROM projects p JOIN workspaces w ON w.id = p.workspace_id
     WHERE w.slug LIKE current_setting('hamstrack.load_slug_prefix', true) || '%'
       AND NOT EXISTS (SELECT 1 FROM workspace_members m JOIN users u ON u.id = m.user_id
                        WHERE m.workspace_id = w.id
                          AND u.email NOT LIKE '%@' || current_setting('hamstrack.load_email_domain', true))
       AND p.issue_seq <> COALESCE((SELECT max(i.number) FROM issues i
                                     WHERE i.project_id = p.id), 0);
    IF bad > 0 THEN
        RAISE EXCEPTION 'issue_seq resync left % project(s) desynced', bad;
    END IF;
END $$;

COMMIT;

-- ---------------------------------------------------------------------------
-- 2. Statistics.
--
-- A run against freshly bulk-loaded tables with no statistics measures a cold planner.
-- That is a real phenomenon and it is not the one being asked about (§4.2). ANALYZE here
-- plus the overnight settle the runbook requires gives the planner what a real instance
-- would have had all along.
--
-- Whole-database ANALYZE rather than a table list: the list would be a hand-maintained
-- enumeration of exactly the kind §5.5 forbids, and it would be one migration out of date
-- the first time a table is added.
-- ---------------------------------------------------------------------------

VACUUM (ANALYZE);

-- ---------------------------------------------------------------------------
-- 3. The row counts and the measured size.
--
-- These go into RESULTS-<date>.md verbatim (§4.9). The projected size in the proposal is
-- an estimate; this is the measurement, and where they disagree the measurement wins.
-- ---------------------------------------------------------------------------

\echo ''
\echo '=== fixture shape: rows per workspace ==='

WITH ws AS (SELECT id, slug FROM workspaces WHERE slug LIKE :'slug_prefix' || '%')
SELECT ws.slug,
       (SELECT count(*) FROM projects           x WHERE x.workspace_id = ws.id) AS projects,
       (SELECT count(*) FROM issues             x WHERE x.workspace_id = ws.id) AS issues,
       (SELECT count(*) FROM issue_history      h
          JOIN issues i ON i.id = h.issue_id WHERE i.workspace_id = ws.id)      AS history,
       (SELECT count(*) FROM issue_comments     c
          JOIN issues i ON i.id = c.issue_id WHERE i.workspace_id = ws.id)      AS comments,
       (SELECT count(*) FROM issue_field_values f
          JOIN issues i ON i.id = f.issue_id WHERE i.workspace_id = ws.id)      AS field_values,
       (SELECT count(*) FROM issue_labels       x WHERE x.workspace_id = ws.id) AS issue_labels,
       (SELECT count(*) FROM issue_versions     x WHERE x.workspace_id = ws.id) AS issue_versions,
       (SELECT count(*) FROM labels             x WHERE x.workspace_id = ws.id) AS labels,
       (SELECT count(*) FROM components         x WHERE x.workspace_id = ws.id) AS components,
       (SELECT count(*) FROM versions           x WHERE x.workspace_id = ws.id) AS versions,
       (SELECT count(*) FROM sprints            x WHERE x.workspace_id = ws.id) AS sprints,
       (SELECT count(*) FROM sprint_scope_events x WHERE x.workspace_id = ws.id) AS scope_events,
       (SELECT count(*) FROM workspace_members  x WHERE x.workspace_id = ws.id) AS members,
       (SELECT count(*) FROM issue_attachments  t
          JOIN issues i ON i.id = t.issue_id WHERE i.workspace_id = ws.id)      AS attachments
  FROM ws ORDER BY ws.slug;

\echo ''
\echo '=== distribution checks (these are the claims §4.2 makes; verify them, do not assume) ==='

WITH ws AS (SELECT id, slug FROM workspaces WHERE slug LIKE :'slug_prefix' || '%')
SELECT ws.slug, s.category,
       count(*) AS issues,
       round(100.0 * count(*) / sum(count(*)) OVER (PARTITION BY ws.slug), 1) AS pct
  FROM ws JOIN issues i ON i.workspace_id = ws.id
          JOIN statuses s ON s.id = i.status_id
 GROUP BY ws.slug, s.category ORDER BY ws.slug, s.category;

WITH ws AS (SELECT id, slug FROM workspaces WHERE slug LIKE :'slug_prefix' || '%'),
     -- `deleted_at IS NULL` is NOT cosmetic and NOT only about which rows to count.
     -- idx_issue_comments_issue is PARTIAL on exactly that predicate, so a subquery that
     -- omits it cannot use the index — the planner cannot prove the rows it wants satisfy
     -- the predicate — and this correlated count degrades to one SEQUENTIAL SCAN of
     -- issue_comments PER ISSUE. At the §4.2 shape that is 42 000 scans of a 120 000-row
     -- table. `EXPLAIN` says it in as many words: SubPlan 1 -> Seq Scan on issue_comments,
     -- total cost 78 782 396. Measured on production 2026-08-30: cancelled while STILL
     -- RUNNING after 25 minutes without the predicate, 345 ms with it. Do not expect
     -- `pg_stat_user_tables.seq_scan` to reveal this while it runs — those counters are
     -- flushed at transaction end, so one long statement leaves them frozen and innocent-
     -- looking, which is what sent the first diagnosis down the wrong path. It also
     -- makes the metric agree with the product, whose only comment query carries the same
     -- predicate (IssueCommentRepository.findForIssueWithAuthor).
     c AS (SELECT i.id, (SELECT count(*) FROM issue_comments x
                          WHERE x.issue_id = i.id AND x.deleted_at IS NULL) AS n
             FROM ws JOIN issues i ON i.workspace_id = ws.id)
SELECT 'comments per issue' AS metric,
       round(avg(n), 2) AS mean,
       percentile_disc(0.50) WITHIN GROUP (ORDER BY n) AS p50,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n) AS p99,
       max(n) AS max
  FROM c;

WITH ws AS (SELECT id FROM workspaces WHERE slug LIKE :'slug_prefix' || '%')
SELECT 'description length' AS metric,
       round(avg(length(description)), 0) AS mean,
       percentile_disc(0.50) WITHIN GROUP (ORDER BY length(description)) AS p50,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY length(description)) AS p99,
       max(length(description)) AS max
  FROM ws JOIN issues i ON i.workspace_id = ws.id WHERE i.description IS NOT NULL;

-- THE HIERARCHY, MEASURED RATHER THAN ASSUMED.
--
-- browse.js calls /children on a uniformly random issue, so what that endpoint costs is
-- decided by this distribution and by nothing else. The first version of the generator
-- computed the parent as `(n * 7919) % n`, which is 0 for every n: every sub-task in a
-- project hung off issue number 1, or — when issue 1 was itself a sub-task — off nothing
-- at all. Both shapes satisfy every row count in the block above, which is why the ratio
-- is printed here and why it is one of the numbers the run record carries.
--
-- Expected: sub-tasks are ~15% of issues, every one of them has a parent, and the children
-- per PARENTED issue is a small number with a modest tail. A max in the thousands, or a
-- parented count of 0, is the old bug back.
\echo ''
\echo '=== hierarchy (browse.js reads /children on a random issue — this is what it finds) ==='

WITH ws AS (SELECT id, slug FROM workspaces WHERE slug LIKE :'slug_prefix' || '%'),
     i  AS (SELECT ws.slug, x.id, x.parent_id FROM ws JOIN issues x ON x.workspace_id = ws.id)
SELECT slug,
       count(*)                                        AS issues,
       count(parent_id)                                AS with_parent,
       round(100.0 * count(parent_id) / count(*), 1)   AS pct_subtask,
       count(DISTINCT parent_id)                       AS distinct_parents,
       round(count(parent_id)::numeric
             / greatest(1, count(DISTINCT parent_id)), 2) AS mean_children_per_parent
  FROM i GROUP BY slug ORDER BY slug;

WITH ws AS (SELECT id FROM workspaces WHERE slug LIKE :'slug_prefix' || '%'),
     c  AS (SELECT parent_id, count(*) AS n
              FROM ws JOIN issues x ON x.workspace_id = ws.id
             WHERE x.parent_id IS NOT NULL GROUP BY parent_id)
SELECT 'children per parented issue' AS metric,
       round(avg(n), 2) AS mean,
       percentile_disc(0.50) WITHIN GROUP (ORDER BY n) AS p50,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY n) AS p99,
       max(n) AS max
  FROM c;

\echo ''
\echo '=== the cap that must actually bind: a project over REPORTS_MAX_ROWS (20000) ==='

SELECT p.key, p.name, count(i.id) AS issues,
       count(i.id) > 20000 AS exceeds_reports_max_rows
  FROM workspaces w JOIN projects p ON p.workspace_id = w.id
  LEFT JOIN issues i ON i.project_id = p.id
 WHERE w.slug LIKE :'slug_prefix' || '%'
 GROUP BY p.key, p.name ORDER BY count(i.id) DESC;

\echo ''
\echo '=== measured size on disk (table + indexes + TOAST), largest first ==='

SELECT relname AS table,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total,
       pg_size_pretty(pg_table_size(c.oid))          AS heap,
       pg_size_pretty(pg_indexes_size(c.oid))        AS indexes
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r'
 ORDER BY pg_total_relation_size(c.oid) DESC LIMIT 15;

SELECT pg_size_pretty(pg_database_size(current_database())) AS database_total;

\echo ''
\echo 'Record the two blocks above in RESULTS-<date>.md (proposal §4.9). The database total'
\echo 'includes production data; the fixture is the DIFFERENCE from the pre-generation'
\echo 'reading, which the runbook tells you to take first.'
