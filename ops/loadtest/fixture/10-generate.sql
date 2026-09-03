-- =============================================================================
-- HD-186 load fixture — generator
-- Spec: docs/design/load-capacity-measurement-proposal.md §4.2, §6
--
-- Writes ROWS into the existing schema. It defines nothing: no table, no column, no
-- index, no extension, no permanent function. The only objects it creates are in
-- `pg_temp`, which PostgreSQL drops when the session disconnects — so a generator that
-- crashes leaves behind data (rolled back) and nothing else.
--
-- Run through fixture/generate.sh, never by hand: the guards live there.
--
-- ---------------------------------------------------------------------------
-- The schema invariants this file honours, and what breaks if it stops.
-- (CLAUDE.md "Gotchas"; proposal §6. Every one of these has cost this project a bug.)
--
--   UUID v7 ids          pg_temp.uuid7() below. Ordering by id is ordering by creation
--                        time everywhere in this product; gen_random_uuid() would make
--                        every keyset page and every "recent first" list nonsense.
--   created_at/updated_at  written EXPLICITLY. The application populates them via Spring
--                        Data auditing, and the DB default NOW() is a safety net, not the
--                        source. A fixture that let NOW() win would put every one of
--                        750 000 rows in the same second and delete the entire time
--                        dimension the reports are about.
--   updated_at trigger   set_updated_at() fires on UPDATE. This file's second-pass
--                        UPDATEs (parent links, issue_seq) therefore run under
--                        `SET LOCAL hamstrack.skip_updated_at = 'on'`, exactly as V11/V12/
--                        V18 do, so a repair pass cannot restamp rows it did not author.
--   projects.issue_seq   a DB-maintained counter, `@Column(updatable = false)` on the
--                        entity: only the native UPDATE ... RETURNING in
--                        ProjectRepository.incrementAndGetIssueSeq may move it. This file
--                        writes issues directly, so it must resync the counter to
--                        MAX(number) afterwards (20-resync.sql) — the same repair V9
--                        performed, for the same reason. Skip it and the next issue a
--                        real user files reuses a number and 500s on
--                        issues_project_id_number_key.
--   issues.position      the project-wide backlog rank, server-written, spaced by
--                        RANK_STEP = 2^26 = 67108864 (V11 multiplied the old spacing up to
--                        exactly this). Generated as n * RANK_STEP so the ranking service
--                        has the gaps it expects to drop into.
--   issues.version       left at 0. @Version starts at 0 and the application owns it.
--   PG ENUMs             none exist; every enum-shaped column is VARCHAR validated in
--                        Java. This file writes the literal strings the Java enums accept.
--   sprint_scope_events  issue_id is NULLABLE BY DESIGN (V18: ON DELETE SET NULL, not
--                        CASCADE) and issue_key is the snapshot that keeps a departed
--                        issue renderable. This file always writes both. A reader that
--                        inner-joins issues drops exactly the rows the design exists to
--                        preserve — see README.md before writing a query against it.
--
-- ---------------------------------------------------------------------------
-- Determinism (acceptance criterion 7).
--
-- setseed() + a strictly serial plan makes two runs with the same seed produce identical
-- row COUNTS and identical id ORDERING. It does not make them produce identical ids:
-- UUID v7 embeds the wall clock, so a run tomorrow has different ids by construction, and
-- that is the property we want — the fixture's timestamps are anchored to `now()` because
-- the reports' default window is the trailing 90 days and a fixture frozen at a literal
-- date would fall out of every report's range within a quarter.
-- =============================================================================

\set ON_ERROR_STOP on

-- Variables the wrapper supplies. Referenced as :'name' (quoted) or :name (bare numeric);
-- psql fails on an undefined variable under ON_ERROR_STOP, which is the point.
--   seed    numeric in [-1,1] for setseed()
--   scale   numeric multiplier on issue counts (1.0 = the §4.2 shape; 0.01 for a laptop)
--   pwhash  a bcrypt hash; every load account shares it (see README "The load password")
--   slug_a  slug_b   workspace slugs, both carrying the teardown prefix

BEGIN;

-- Serial plan: parallel workers consume random() in a nondeterministic order, which would
-- make the seed a decoration. Also keeps the generator off the box's second core, which
-- the app is using.
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL synchronous_commit = off;   -- one transaction, rolled back wholesale on error

SELECT setseed(:seed);

-- ---------------------------------------------------------------------------
-- Session-local helpers. pg_temp is dropped at disconnect — no DDL survives this run.
-- ---------------------------------------------------------------------------

-- UUID v7, RFC 9562 layout: 48-bit big-endian Unix ms | version 7 | 12 random | variant
-- 10 | 62 random. Built as hex text rather than bytea because it is readable in a diff and
-- this is the one function in the harness whose correctness is not observable at runtime.
-- The two random tails are drawn separately: a double has 53 mantissa bits, so a single
-- random() * 2^60 silently loses the low bits and would collide far sooner than a v7
-- should.
--
-- EVERY CAST HERE IS floor(random() * N), NEVER (random() * (N-1))::int. In PostgreSQL
-- `::int` ROUNDS — (4094.6)::int is 4095 and (4095.0)::int is 4095 — so the naive form
-- overflows its field about once in eight thousand calls, producing FOUR hex characters
-- where lpad(...,3) guarantees three, a 33-character string, and a hard cast error.
-- At the fixture's ~750 000 id generations that is roughly ninety failures; at the 0.5%
-- scale a rehearsal runs, it is zero. A bug that only appears at production volume, in a
-- production window, is exactly the one to kill in the source rather than in testing.
CREATE FUNCTION pg_temp.uuid7(ts timestamptz) RETURNS uuid AS $$
    SELECT (
        lpad(to_hex((extract(epoch from $1) * 1000)::bigint), 12, '0')  -- 48b time
     || '7' || lpad(to_hex(floor(random() * 4096)::int), 3, '0')        -- ver + 12b rand
     || to_hex(8 + floor(random() * 4)::int)                            -- variant 10xx
     || lpad(to_hex(floor(random() * 268435456)::bigint), 7, '0')       -- 28b rand
     || lpad(to_hex(floor(random() * 4294967296)::bigint), 8, '0')      -- 32b rand
    )::uuid
$$ LANGUAGE sql VOLATILE;

-- Prose of a requested length, at a random offset into a corpus. Two properties matter and
-- neither is aesthetic:
--   * LENGTH VARIES. `text ~ "x"` compiles to two unanchored LIKEs over a TEXT column and
--     its cost is a function of the data's length. A fixed filler string measures one
--     length and reports it as every length.
--   * CONTENT VARIES. A fixed string lets the planner's statistics and the page cache
--     behave unrealistically well; a corpus read at a random offset does not.
-- The marker words are deliberate: the search mix's `text ~` leaves use them, and they
-- appear at a known density so a search returns a plausible fraction of the project
-- rather than nothing (which measures an index probe) or everything (which measures a
-- sequential scan and a huge result set).
CREATE FUNCTION pg_temp.corpus() RETURNS text AS $$
    SELECT repeat(
        'the checkout service returns a stale cursor when the upstream retry budget is '
     || 'exhausted and the client reconnects during a rolling deploy which leaves the '
     || 'session pinned to a replica that has not yet observed the write so the user sees '
     || 'their own change disappear on the next navigation this is reproducible under '
     || 'moderate latency and disappears entirely when the connection pool is warm we '
     || 'should decide whether the regression is in the router or in the cache key and '
     || 'whether the fix belongs behind a flag for the next release train ', 12)
$$ LANGUAGE sql IMMUTABLE;

CREATE FUNCTION pg_temp.prose(len int) RETURNS text AS $$
    SELECT substr(pg_temp.corpus(),
                  1 + (random() * (length(pg_temp.corpus()) - $1 - 1))::int,
                  $1)
$$ LANGUAGE sql VOLATILE;

-- ---------------------------------------------------------------------------
-- 0. Reference data — resolved by NAME, never by a literal id.
--
-- V1 seeds statuses/priorities/issue_types with gen_random_uuid(), so their ids differ on
-- every install. A fixture with hard-coded catalog ids works on the author's laptop and
-- writes dangling references everywhere else — and since V19 added issues→statuses and
-- issues→issue_types foreign keys, "everywhere else" now means a constraint violation
-- rather than silent corruption. Which is an improvement, and still not something to
-- discover during a window.
-- ---------------------------------------------------------------------------

-- ONE row per status category, and that matters more than it looks. On a clean install
-- there are exactly the three V1 seeds; on a database that has ever run the test suite
-- there can be dozens of global statuses, and "SELECT ... WHERE category='DONE' LIMIT 1"
-- would then pick an arbitrary one per statement — silently giving two issues in the same
-- project different "Done" statuses. Every report reads the CATEGORY, so the fixture would
-- look right and the board would render two Done columns.
--
-- Oldest-first is what makes the choice deterministic AND correct: the V1 seeds predate
-- anything a test or an admin created.
CREATE TEMP TABLE ld_status AS
    SELECT DISTINCT ON (category) id, name, category
      FROM statuses
     WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL AND archived_at IS NULL
     ORDER BY category, created_at, id;

-- Every reference table below carries a dense `idx` and the `total` of its own partition,
-- so the issues INSERT can pick a row with `idx = 1 + (draw % total)` — a join, not a
-- correlated subquery with an OFFSET. The subquery version is correct and runs a separate
-- scan PER ISSUE: at 40 000 issues x six references it is the difference between minutes
-- and hours, on a box whose second core the application is using.
CREATE TEMP TABLE ld_priority AS
    SELECT id, name, position,
           row_number() OVER (ORDER BY position, created_at, id) AS idx,
           count(*)     OVER ()                                  AS total
      FROM (SELECT DISTINCT ON (position) id, name, position, created_at
              FROM priorities
             WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
               AND archived_at IS NULL
             ORDER BY position, created_at, id) p;

CREATE TEMP TABLE ld_type AS
    SELECT id, name, hierarchy_level,
           row_number() OVER (PARTITION BY hierarchy_level ORDER BY created_at, id) AS idx,
           count(*)     OVER (PARTITION BY hierarchy_level)                          AS total
      FROM issue_types
     WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL AND archived_at IS NULL;

-- Keep at most three types per level: enough variety for the type filter to mean
-- something, few enough that a polluted catalog cannot make one level dominate.
DELETE FROM ld_type WHERE idx > 3;
UPDATE ld_type t SET total = (SELECT count(*) FROM ld_type x
                               WHERE x.hierarchy_level = t.hierarchy_level);

CREATE INDEX ON ld_type (hierarchy_level, idx);
CREATE INDEX ON ld_priority (idx);

DO $$
DECLARE s int; p int; t int;
BEGIN
    SELECT count(*) INTO s FROM ld_status;
    SELECT count(*) INTO p FROM ld_priority;
    SELECT count(*) INTO t FROM ld_type;
    IF s < 3 OR p < 3 OR t < 3
       OR NOT EXISTS (SELECT 1 FROM ld_status WHERE category = 'DONE')
       OR NOT EXISTS (SELECT 1 FROM ld_status WHERE category = 'IN_PROGRESS')
       OR NOT EXISTS (SELECT 1 FROM ld_status WHERE category = 'TODO')
       OR NOT EXISTS (SELECT 1 FROM ld_type WHERE hierarchy_level = 0)
    THEN
        RAISE EXCEPTION
            'the global taxonomy catalog is not the one this fixture expects '
            '(statuses=%, priorities=%, issue_types=%). It needs the V1/V2 defaults: '
            'three status categories (TODO/IN_PROGRESS/DONE) and a hierarchy_level 0 type '
            '(Sub-task). Generating against a different catalog would produce issues whose '
            'status has no category, and every report reads the category, not the name.',
            s, p, t;
    END IF;
END $$;

-- Built-in role ids ARE literals, and that is correct: V13 inserts them with fixed
-- UUIDs precisely so they can be referenced. Note MEMBER names two different built-ins
-- across scopes (CLAUDE.md: "a key is not an identity"), so these are matched by id.
CREATE TEMP TABLE ld_role AS
    SELECT id, scope, key FROM roles
     WHERE id IN ('00000000-0000-7000-8000-000000000001',   -- WORKSPACE OWNER
                  '00000000-0000-7000-8000-000000000003',   -- WORKSPACE MEMBER
                  '00000000-0000-7000-8000-000000000011',   -- PROJECT  MANAGER
                  '00000000-0000-7000-8000-000000000012');  -- PROJECT  MEMBER (Contributor)

DO $$
BEGIN
    IF (SELECT count(*) FROM ld_role) <> 4 THEN
        RAISE EXCEPTION 'the four built-in roles V13 seeds are not all present; '
                        'the fixture cannot assign membership without them';
    END IF;
END $$;

-- The anchor for every timestamp in the fixture. One value, computed once, so a
-- generation that straddles an hour boundary does not produce two different "nows".
CREATE TEMP TABLE ld_anchor AS SELECT date_trunc('hour', now()) AS t0;

-- ---------------------------------------------------------------------------
-- 1. Accounts — THREE ADDRESS FAMILIES, and the third one is a correctness property.
--
--   load-a-NNN    210   members of workspace A. THE LOAD POOL.
--   load-ac-NNN     2   members of workspace A, reserved for the TENANCY CANARY.
--   load-b-NNN     15   members of workspace B. Never load; the canary's foreign TARGET.
--
-- WHY 210 AND NOT 120. k6 maps a virtual user to a principal injectively —
-- accountsA[__VU - 1], with no modulo (k6/lib/auth.js) — so the pool must cover every VU
-- k6 ALLOCATES, which is the sum over the mix's scenarios and not its headline VU count.
-- The browsing mix declares three (browse at VUS, sse at VUS, canary at 1), so its 100-VU
-- top stage instantiates 201 VUs. At 120 accounts a modulo handed 81 of them to two VUs
-- each; /api/auth/refresh ROTATES, so both holders of one account destroyed each other's
-- session at the first refresh, and the canary reported the resulting 400 as a tenancy
-- incident against a correct server. 210 is 201 rounded up.
--
-- It also keeps the property the old number was chosen for: a member scan on every
-- ResolutionContext build stays non-trivial, and rather more so.
--
-- WHY THE CANARY GETS ITS OWN FAMILY RATHER THAN AN INDEX. Its principal must be one that
-- NO load VU can draw. "accountsA[0], and the load starts at index 1" is a bound somebody
-- has to keep true across every future change to how a VU picks an account; a distinct
-- address family is disjoint by construction, and mint-tokens.js asks for it by name.
-- These accounts are ordinary members of workspace A — that is exactly what makes their
-- 404 against workspace B mean something.
--
-- Their `i` continues workspace A's series (211, 212) so that (tag, i) stays unique: the
-- issue generator joins assignees and reporters on that pair, and a duplicate there would
-- multiply every issue row.
--
-- Every address is @load.invalid. `.invalid` is reserved by RFC 2606 and can never be
-- registered, so no mail this fixture provokes can reach a real mailbox, and the domain is
-- half of what teardown finds its work by — which is also why the canary family is a
-- prefix change and not a domain change.
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE ld_user AS
WITH n AS (
    SELECT v.tag, v.pool, v.first + k - 1 AS i, k AS seq,
           format('load-%s-%s@load.invalid',
                  CASE WHEN v.pool = 'canary' THEN v.tag || 'c' ELSE v.tag END,
                  lpad(k::text, 3, '0'))                                   AS email,
           format('Load %s %s', upper(CASE WHEN v.pool = 'canary' THEN v.tag || 'c'
                                           ELSE v.tag END),
                  lpad(k::text, 3, '0'))                                   AS display_name
      FROM (VALUES ('a', 'load',   210,   1),
                   ('a', 'canary',   2, 211),
                   ('b', 'load',    15,   1)) v(tag, pool, cnt, first),
           LATERAL generate_series(1, v.cnt) k
)
SELECT pg_temp.uuid7((SELECT t0 FROM ld_anchor) - interval '400 days'
                     + (interval '1 minute' * n.i)) AS id,
       n.tag, n.pool, n.i, n.email, n.display_name,
       count(*) OVER (PARTITION BY n.tag) AS total
  FROM n ORDER BY n.tag, n.i;

CREATE INDEX ON ld_user (tag, i);

-- (tag, i) is the join key the issue generator uses for assignee and reporter, so a
-- collision there would not fail — it would silently multiply every issue row. Asserted
-- rather than assumed, because the canary family was added to tag 'a' after that join was
-- written.
DO $$
DECLARE dup bigint;
BEGIN
    SELECT count(*) INTO dup FROM (
        SELECT tag, i FROM ld_user GROUP BY tag, i HAVING count(*) > 1) d;
    IF dup > 0 THEN
        RAISE EXCEPTION 'ld_user has % duplicated (tag, i) pair(s). The address families '
                        'must occupy disjoint ranges of i within a tag: the issue '
                        'generator joins assignees and reporters on that pair and a '
                        'duplicate multiplies every issue row instead of failing.', dup;
    END IF;
END $$;

INSERT INTO users (id, email, display_name, password_hash, status,
                   created_at, updated_at, terms_accepted_at, demo_seeded_at,
                   system_role, onboarded_at)
SELECT u.id, u.email, u.display_name, :'pwhash', 'ACTIVE',
       a.t0 - interval '400 days', a.t0 - interval '400 days',
       a.t0 - interval '400 days',
       -- demo_seeded_at and onboarded_at NON-NULL on purpose: NULL means "seeding
       -- pending" / "onboarding pending", and the app would try to seed a demo workspace
       -- for EVERY ONE of them on their first login — during the token-minting phase, on the
       -- box under measurement. That is one unwanted workspace per load account (the pool
       -- is sized by the peak VU count, so it grows whenever the ladder does) and a pre-flight
       -- that measures demo seeding.
       a.t0 - interval '400 days', 'USER', a.t0 - interval '400 days'
  FROM ld_user u, ld_anchor a
 ORDER BY u.tag, u.i;

-- ---------------------------------------------------------------------------
-- 2. Workspaces and membership.
--
-- Two, and the second is not decoration (§4.2): B is the foreign target for the tenancy
-- canary, and it makes "cost versus tenant size" a comparison inside one run rather than
-- a claim between two runs.
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE ld_ws AS
SELECT pg_temp.uuid7(a.t0 - interval '399 days') AS id, 'a' AS tag,
       :'slug_a' AS slug, 'Load tenant A (large)' AS name FROM ld_anchor a
UNION ALL
SELECT pg_temp.uuid7(a.t0 - interval '399 days'), 'b',
       :'slug_b', 'Load tenant B (typical)' FROM ld_anchor a;

INSERT INTO workspaces (id, slug, name, created_by, created_at, updated_at,
                        project_access_mode, default_project_role_id)
SELECT w.id, w.slug, w.name,
       (SELECT id FROM ld_user u WHERE u.tag = w.tag ORDER BY u.i LIMIT 1),
       a.t0 - interval '399 days', a.t0 - interval '399 days',
       -- OPEN + NULL default role is the shipped default: a workspace member with no
       -- project_members row inherits built-in Contributor on every project. That is the
       -- path most real tenants are on, so it is the path the measurement should exercise.
       'OPEN', NULL
  FROM ld_ws w, ld_anchor a ORDER BY w.tag;

INSERT INTO workspace_members (id, workspace_id, user_id, role_id, joined_at)
SELECT pg_temp.uuid7(a.t0 - interval '398 days'), w.id, u.id,
       CASE WHEN u.i = 1 THEN '00000000-0000-7000-8000-000000000001'::uuid   -- Owner
                         ELSE '00000000-0000-7000-8000-000000000003'::uuid   -- Member
       END,
       a.t0 - interval '398 days'
  FROM ld_ws w JOIN ld_user u ON u.tag = w.tag, ld_anchor a
 ORDER BY w.tag, u.i;

-- ---------------------------------------------------------------------------
-- 3. Projects — sized 80/20 (§4.2).
--
-- A-1 carries 25 000 issues on purpose: REPORTS_MAX_ROWS is 20 000, so every row-level
-- report against it must actually hit its cap and set meta.truncated. The capped case is
-- the expensive case and the one the heap costing (§4.6 P2) is about; a fixture where no
-- project reaches the cap tests the cap by never touching it.
--
-- 40 000 total is 80x BOARD_MAX_ISSUES (500), so the board's cap does real work rather
-- than being a formality.
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE ld_project AS
SELECT pg_temp.uuid7(a.t0 - interval '397 days' + (interval '1 minute' * v.ord)) AS id,
       w.id AS workspace_id, w.tag, v.ord, v.key, v.name,
       greatest(1, (v.issues * :scale)::int) AS issue_count,
       v.board_mode, v.open_sprints, v.done_sprints, v.components, v.versions
  FROM ld_anchor a, ld_ws w
  JOIN LATERAL (VALUES
        -- tag, ord, key,   name,               issues, board_mode, open, done, comps, vers
        ('a', 1, 'LDA',  'Platform',             25000, 'SCRUM',  20, 40, 40, 30),
        ('a', 2, 'LDB',  'Billing',               9000, 'SCRUM',  20, 40, 40, 30),
        ('a', 3, 'LDC',  'Mobile',                4000, 'KANBAN', 20, 40, 40, 30),
        ('a', 4, 'LDD',  'Data',                  2000, 'KANBAN', 20, 40, 40, 30),
        ('b', 1, 'LDE',  'Website',               1500, 'SCRUM',   5, 10, 10, 10),
        ('b', 2, 'LDF',  'Internal tools',         500, 'KANBAN',  5, 10, 10, 10)
       ) v(tag, ord, key, name, issues, board_mode, open_sprints, done_sprints,
           components, versions)
    ON v.tag = w.tag;

INSERT INTO projects (id, workspace_id, name, key, description, issue_seq, created_by,
                      created_at, updated_at, board_mode,
                      releases_enabled, estimation_enabled, default_project_role_id)
SELECT p.id, p.workspace_id, p.name, p.key,
       pg_temp.prose(300), 0,
       (SELECT id FROM ld_user u WHERE u.tag = p.tag ORDER BY u.i LIMIT 1),
       a.t0 - interval '397 days', a.t0 - interval '397 days', p.board_mode,
       -- Capabilities are DECLARED, never inferred from data presence (CLAUDE.md). The
       -- fixture declares them because the mixes read versions and story points; it does
       -- not create a sprint and hope the product concludes something from it.
       TRUE, TRUE, NULL
  FROM ld_project p, ld_anchor a ORDER BY p.tag, p.ord;

-- Explicit project membership for the whole of A's largest project, and a slice of the
-- rest. Two mechanisms grant a role in this product — an explicit project_members row and
-- the default-access chain (CLAUDE.md, HD-123) — and the fixture exercises both, because
-- permission resolution costs different statements on each path and the browsing mix pays
-- that cost on every single request.
INSERT INTO project_members (id, project_id, user_id, role_id, joined_at)
SELECT pg_temp.uuid7(a.t0 - interval '396 days'), p.id, u.id,
       CASE WHEN u.i <= 2 THEN '00000000-0000-7000-8000-000000000011'::uuid  -- Manager
                          ELSE '00000000-0000-7000-8000-000000000012'::uuid  -- Contributor
       END,
       a.t0 - interval '396 days'
  FROM ld_project p
  JOIN ld_user u ON u.tag = p.tag
  , ld_anchor a
 WHERE p.ord = 1 OR u.i <= 20
 ORDER BY p.tag, p.ord, u.i;

-- ---------------------------------------------------------------------------
-- 4. Classification catalogs.
--
-- These are sized by what the SEARCH path loads, not by what looks realistic on a screen.
-- HQL name resolution loads the workspace's WHOLE label catalog on every /search,
-- /search/schema and /search/suggest, and each visible project's whole component and
-- version catalogs with it. 400 labels is under MAX_LABELS_PER_WORKSPACE (1000) and large
-- enough for that load to be measurable; generating the full 1000 would measure the cap
-- rather than a plausible tenant (§4.2).
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE ld_label AS
SELECT pg_temp.uuid7(a.t0 - interval '395 days' + (interval '1 second' * i)) AS id,
       w.id AS workspace_id, w.tag, i,
       format('%s-%s', (ARRAY['area','team','risk','platform','tier','flow','surface',
                              'client','region','tag'])[1 + (i % 10)], i) AS name
  FROM ld_anchor a, ld_ws w,
       LATERAL generate_series(1, CASE WHEN w.tag = 'a' THEN 400 ELSE 60 END) i;

ALTER TABLE ld_label ADD COLUMN total int;
UPDATE ld_label l SET total = (SELECT count(*) FROM ld_label x WHERE x.workspace_id = l.workspace_id);
CREATE INDEX ON ld_label (workspace_id, i);

INSERT INTO labels (id, workspace_id, name, color, description, created_by,
                    created_at, updated_at)
SELECT l.id, l.workspace_id, l.name,
       (ARRAY['#667085','#0EA5A4','#B45309','#15803D','#64748B'])[1 + (l.i % 5)],
       pg_temp.prose(80),
       (SELECT id FROM ld_user u WHERE u.tag = l.tag ORDER BY u.i LIMIT 1),
       a.t0 - interval '395 days', a.t0 - interval '395 days'
  FROM ld_label l, ld_anchor a ORDER BY l.tag, l.i;

CREATE TEMP TABLE ld_component AS
SELECT pg_temp.uuid7(a.t0 - interval '394 days' + (interval '1 second' * i)) AS id,
       p.workspace_id, p.id AS project_id, p.tag, p.ord, i,
       format('%s svc %s', p.key, lpad(i::text, 2, '0')) AS name
  FROM ld_anchor a, ld_project p, LATERAL generate_series(1, p.components) i;

ALTER TABLE ld_component ADD COLUMN total int;
UPDATE ld_component c SET total = (SELECT count(*) FROM ld_component x WHERE x.project_id = c.project_id);
CREATE INDEX ON ld_component (project_id, i);

INSERT INTO components (id, workspace_id, project_id, name, description, lead_id,
                        auto_assign, created_at, updated_at)
SELECT c.id, c.workspace_id, c.project_id, c.name, pg_temp.prose(120),
       NULL,
       -- auto_assign FALSE everywhere: it assigns NEW issues to the component lead, and a
       -- writing mix that silently reassigns every issue it creates would be measuring a
       -- code path the mix did not ask for and attributing its cost to `write`.
       FALSE, a.t0 - interval '394 days', a.t0 - interval '394 days'
  FROM ld_component c, ld_anchor a ORDER BY c.tag, c.ord, c.i;

CREATE TEMP TABLE ld_version AS
SELECT pg_temp.uuid7(a.t0 - interval '393 days' + (interval '1 second' * i)) AS id,
       p.workspace_id, p.id AS project_id, p.tag, p.ord, i, p.versions AS total,
       format('%s %s.%s.0', p.key, 1 + (i / 10), i % 10) AS name
  FROM ld_anchor a, ld_project p, LATERAL generate_series(1, p.versions) i;

INSERT INTO versions (id, workspace_id, project_id, name, description, release_date,
                      released, released_at, created_at, updated_at)
SELECT v.id, v.workspace_id, v.project_id, v.name, pg_temp.prose(100),
       (a.t0 - interval '1 day' * (v.total - v.i) * 14)::date,
       -- The older two thirds are released. The releases page and the fix-version filter
       -- both branch on this, and a catalog where nothing is released exercises one arm.
       v.i <= (v.total * 2 / 3),
       CASE WHEN v.i <= (v.total * 2 / 3)
            THEN a.t0 - interval '1 day' * (v.total - v.i) * 14 END,
       a.t0 - interval '393 days', a.t0 - interval '393 days'
  FROM ld_version v, ld_anchor a ORDER BY v.tag, v.ord, v.i;

-- ---------------------------------------------------------------------------
-- 5. Sprints — exactly AGILE_MAX_OPEN_SPRINTS open per project (§4.2).
--
-- 20 open is the cap, so the planning view assembles its full
-- (open sprints + 1) x AGILE_SECTION_MAX_ISSUES budget = 21 x 300 — the one unpaged
-- response in the product with a five-figure row bound.
--
-- "Open" is FUTURE + ACTIVE, and there can be at most ONE ACTIVE per project
-- (sprints_one_active_per_project_uk), so open = 1 ACTIVE + 19 FUTURE.
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE ld_sprint AS
SELECT pg_temp.uuid7(a.t0 - interval '392 days' + (interval '1 second' * seq)) AS id,
       p.workspace_id, p.id AS project_id, p.tag, p.ord, seq,
       CASE WHEN seq <= p.done_sprints            THEN 'COMPLETED'
            WHEN seq = p.done_sprints + 1         THEN 'ACTIVE'
            ELSE                                       'FUTURE' END AS state,
       format('%s Sprint %s', p.key, seq) AS name
  FROM ld_anchor a, ld_project p,
       LATERAL generate_series(1, p.done_sprints + p.open_sprints) seq;

ALTER TABLE ld_sprint ADD COLUMN total int;
UPDATE ld_sprint s SET total = (SELECT count(*) FROM ld_sprint x WHERE x.project_id = s.project_id);
CREATE INDEX ON ld_sprint (project_id, seq);

INSERT INTO sprints (id, workspace_id, project_id, name, goal, state, sequence,
                     start_at, end_at, completed_at, created_by, created_at, updated_at)
SELECT s.id, s.workspace_id, s.project_id, s.name, pg_temp.prose(140), s.state, s.seq,
       -- Sprint n runs for 14 days, ending (done_sprints - n) fortnights before now for
       -- the completed ones and forward from now for the future ones. The arithmetic is
       -- one expression so start < end holds for every row (sprints_dates_ck), including
       -- the FUTURE rows where a naive "start = now" would collide with the ACTIVE one.
       a.t0 + interval '14 days' * (s.seq - (SELECT done_sprints FROM ld_project p
                                              WHERE p.id = s.project_id) - 1),
       a.t0 + interval '14 days' * (s.seq - (SELECT done_sprints FROM ld_project p
                                              WHERE p.id = s.project_id))
             - interval '1 second',
       CASE WHEN s.state = 'COMPLETED'
            THEN a.t0 + interval '14 days' * (s.seq - (SELECT done_sprints FROM ld_project p
                                                        WHERE p.id = s.project_id)) END,
       (SELECT id FROM ld_user u WHERE u.tag = s.tag ORDER BY u.i LIMIT 1),
       a.t0 - interval '392 days', a.t0 - interval '392 days'
  FROM ld_sprint s, ld_anchor a ORDER BY s.tag, s.ord, s.seq;

-- ---------------------------------------------------------------------------
-- 6. Workspace-scoped custom fields (JSONB).
--
-- Two per workspace, bound through a workspace-scoped field set. These exist for the
-- search path: predicates over a custom field cast JSONB, and issue detail renders them.
-- Workspace-scoped rather than global so they cascade away with the workspace and can
-- never be mistaken for product data (field_defs.scope_workspace_id is ON DELETE CASCADE).
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE ld_field AS
SELECT pg_temp.uuid7(a.t0 - interval '391 days' + (interval '1 second' * f.n)) AS id,
       w.id AS workspace_id, w.tag, f.n, f.key, f.name, f.type, f.config::jsonb
  FROM ld_anchor a, ld_ws w,
       LATERAL (VALUES
         (1, 'load_team',  'Delivery team', 'SELECT',
          '{"options":[{"id":"core","label":"Core","color":"#0EA5A4"},
                       {"id":"edge","label":"Edge","color":"#B45309"},
                       {"id":"data","label":"Data","color":"#15803D"}]}'),
         (2, 'load_effort','Effort band',   'SELECT',
          '{"options":[{"id":"s","label":"S","color":"#64748B"},
                       {"id":"m","label":"M","color":"#64748B"},
                       {"id":"l","label":"L","color":"#64748B"}]}')
       ) f(n, key, name, type, config);

INSERT INTO field_defs (id, scope_workspace_id, key, name, type, config, description,
                        created_at, is_system)
SELECT f.id, f.workspace_id, f.key, f.name, f.type, f.config, pg_temp.prose(90),
       a.t0 - interval '391 days', FALSE
  FROM ld_field f, ld_anchor a ORDER BY f.tag, f.n;

CREATE TEMP TABLE ld_field_set AS
SELECT pg_temp.uuid7(a.t0 - interval '390 days') AS id, w.id AS workspace_id, w.tag
  FROM ld_anchor a, ld_ws w;

INSERT INTO field_sets (id, scope_workspace_id, name, is_system_default, created_at)
SELECT s.id, s.workspace_id, 'Load fixture fields', FALSE, a.t0 - interval '390 days'
  FROM ld_field_set s, ld_anchor a ORDER BY s.tag;

INSERT INTO field_set_items (id, set_id, field_id, position, required, show_on_create)
SELECT pg_temp.uuid7(a.t0 - interval '390 days'), s.id, f.id, f.n, FALSE, TRUE
  FROM ld_field_set s JOIN ld_field f ON f.workspace_id = s.workspace_id, ld_anchor a
 ORDER BY s.tag, f.n;

UPDATE projects p SET field_set_id = s.id
  FROM ld_field_set s, ld_project lp
 WHERE lp.id = p.id AND s.workspace_id = lp.workspace_id;

-- ---------------------------------------------------------------------------
-- 7. Issues — the bulk of the fixture.
--
-- Every per-row random draw is taken ONCE, in a MATERIALIZED CTE. Referencing random()
-- twice in a CASE evaluates it twice and yields two different numbers, which would make
-- every "65% closed" claim below a coincidence rather than a distribution.
--
-- The distribution is the point (§4.2, §12). Row counts are the easy half and this
-- generator controls them; what decides a query's cost is how issues spread across
-- statuses and projects, how history clusters in time, how long the text is, and how
-- skewed the comment counts are. A uniform fixture makes every query uniformly cheap and
-- every plan the same plan, and is systematically wrong in a direction nothing in the run
-- reveals.
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE ld_issue AS
WITH draw AS MATERIALIZED (
    SELECT p.id AS project_id, p.workspace_id, p.tag, p.ord, n,
           random() AS r_status, random() AS r_type,  random() AS r_prio,
           random() AS r_age,    random() AS r_age2,  random() AS r_cycle,
           random() AS r_start,  random() AS r_assign,random() AS r_len,
           random() AS r_due,    random() AS r_comp,  random() AS r_sprint,
           random() AS r_points, random() AS r_marker
      FROM ld_project p, LATERAL generate_series(1, p.issue_count) n
)
SELECT
    pg_temp.uuid7(created) AS id,
    d.project_id, d.workspace_id, d.tag, d.ord, d.n,
    d.r_assign, d.r_comp, d.r_sprint, d.r_points, d.r_len, d.r_marker, d.r_due,
    created,
    -- Status: ~65% done / ~20% in progress / ~15% open. Weighted to done because the flow
    -- report's opening balance counts everything closed BEFORE the window, and a fixture
    -- where nothing is closed deletes that cost entirely.
    CASE WHEN d.r_status < 0.65 THEN 'DONE'
         WHEN d.r_status < 0.85 THEN 'IN_PROGRESS'
         ELSE                        'TODO' END AS category,
    -- Sub-tasks are 15% and are the only rows that get a parent (hierarchy_level 0).
    CASE WHEN d.r_type < 0.15 THEN 0
         WHEN d.r_type < 0.95 THEN 1
         ELSE                      2 END AS level,
    d.r_type, d.r_prio,
    started, closed
  FROM draw d,
       LATERAL (
         SELECT (SELECT t0 FROM ld_anchor)
                - interval '1 day' * CASE WHEN d.r_age < 0.70 THEN d.r_age2 * 180
                                          ELSE 180 + d.r_age2 * 550 END AS created
       ) c,
       LATERAL (
         SELECT CASE WHEN d.r_status < 0.85
                     THEN c.created + interval '1 hour' * (d.r_start * d.r_start * 400)
                END AS started
       ) s,
       LATERAL (
         SELECT CASE WHEN d.r_status < 0.65
                     -- Cycle time from a cubed uniform: 1 hour to ~83 days, heavily skewed
                     -- short, which is the shape real cycle time has. Clamped below the
                     -- anchor so no issue is closed in the future.
                     THEN least((SELECT t0 FROM ld_anchor) - interval '1 minute',
                                c.created + interval '1 hour'
                                          * (1 + d.r_cycle * d.r_cycle * d.r_cycle * 2000))
                END AS closed
       ) cl
 ORDER BY d.tag, d.ord, d.n;

CREATE INDEX ON ld_issue (project_id, n);
CREATE INDEX ON ld_issue (id);

INSERT INTO issues (id, workspace_id, project_id, number, title, description,
                    type_id, status_id, assignee_id, reporter_id, parent_id,
                    position, due_date, created_at, updated_at, version, priority_id,
                    closed_at, started_at, component_id, sprint_id, story_points)
SELECT i.id, i.workspace_id, i.project_id, i.n,
       -- Title carries a marker word at a known density so `text ~` leaves in the search
       -- mix return a plausible fraction rather than 0 or everything.
       (ARRAY['Investigate','Fix','Refactor','Add','Remove','Harden','Measure','Document'])
           [1 + (i.n % 8)]
         || ' ' || (ARRAY['checkout','cursor','retry','deploy','cache','router','pool',
                          'session','flag','replica'])[1 + ((i.n / 8) % 10)]
         || ' ' || pg_temp.prose(20 + (i.r_len * 90)::int),
       CASE WHEN i.r_len < 0.10 THEN NULL
            ELSE pg_temp.prose(80 + (i.r_len * i.r_len * 2400)::int) END,
       t.id, s.id,
       CASE WHEN i.r_assign < 0.80 THEN ua.id END,
       ur.id,
       NULL,                                            -- parent linked in a second pass
       i.n::bigint * 67108864,                          -- RANK_STEP = 2^26 (V11)
       CASE WHEN i.r_due < 0.40 THEN (i.created + interval '1 day' * 30)::date END,
       i.created, COALESCE(i.closed, i.started, i.created), 0,
       pr.id,
       i.closed, i.started,
       CASE WHEN i.r_comp < 0.60 THEN c.id END,
       -- Half the issues sit in a sprint, and the sprint is picked from the project's OWN
       -- sprints so the composite (sprint_id, workspace_id) foreign key holds. Picking from
       -- any other project's sprints would violate it — which is the constraint doing
       -- exactly its job, and a good reason not to "simplify" this join.
       CASE WHEN i.r_sprint < 0.50 THEN sp.id END,
       -- Fibonacci points on 60% of issues. NUMERIC(5,2) with a 0..999 check.
       CASE WHEN i.r_points < 0.60
            THEN (ARRAY[1,2,3,5,8,13,21])[1 + (i.r_points * 999)::int % 7]::numeric
       END
  FROM ld_issue i
  -- Every reference is a JOIN on a dense index, never a correlated subquery with an
  -- OFFSET. The subquery form is equally correct and rescans the reference table once per
  -- issue: at 40 000 issues x six references that is the difference between minutes and
  -- hours, on a box whose other core the application under measurement is using.
  JOIN ld_status   s  ON s.category = i.category
  JOIN ld_type     t  ON t.hierarchy_level = i.level
                     AND t.idx = 1 + ((i.r_type * 9973)::int % t.total)
  JOIN ld_priority pr ON pr.idx = 1 + ((i.r_prio * 9967)::int % pr.total)
  JOIN ld_user     ua ON ua.tag = i.tag
                     AND ua.i  = 1 + ((i.r_assign * 9949)::int % ua.total)
  JOIN ld_user     ur ON ur.tag = i.tag
                     AND ur.i  = 1 + ((i.r_marker * 9941)::int % ur.total)
  LEFT JOIN ld_component c  ON c.project_id  = i.project_id
                           AND c.i   = 1 + ((i.r_comp * 9931)::int % c.total)
  LEFT JOIN ld_sprint    sp ON sp.project_id = i.project_id
                           AND sp.seq = 1 + ((i.r_sprint * 9929)::int % sp.total)
 ORDER BY i.tag, i.ord, i.n;

-- Parent links, second pass: a sub-task's parent must already exist, and it must be a
-- higher hierarchy level in the SAME project. Under skip_updated_at so this repair does
-- not restamp updated_at on rows whose timestamps were just generated deliberately.
SET LOCAL hamstrack.skip_updated_at = 'on';

-- THE PARENT IS DRAWN FROM A DENSE INDEX OVER THE PROJECT'S ELIGIBLE ISSUES, NOT FROM AN
-- ARITHMETIC GUESS AT AN ISSUE NUMBER. The version this replaces read
--
--     lp.n = 1 + ((lc.n * 7919) % greatest(1, lc.n))
--
-- and `(k * n) % n` is 0 for every n, so that expression is the constant 1. Every sub-task
-- in a project was parented to issue number 1 — or, when issue 1 happened to be a sub-task
-- itself and therefore ineligible, the LATERAL matched nothing and NO sub-task in that
-- project got a parent at all. All-or-nothing, per project, on one coin flip.
--
-- The consequence is not a cosmetic one: browse.js calls /children on a uniformly random
-- issue, so it read every child in the project from one issue and zero from every other —
-- one pathological response and thousands of empty ones, averaged into a p95 that
-- describes neither. This is the third silent-distribution defect in this file, and the
-- comment block below it was written to prevent exactly this class.
--
-- ld_parent gives each eligible (level >= 1) issue a dense 1..total index per project, so
-- the modulus is taken against a count that exists rather than against the child's own
-- number. 7919 is prime, so consecutive children land on scattered parents instead of
-- clustering. 20-resync.sql prints the resulting children-per-parent distribution: read it,
-- because a row count cannot tell these two versions apart.
--
-- The draw is UNIFORM over eligible parents, which is a choice and not an oversight: it
-- makes /children thin and wide (measured at 1% scale: 14.1% sub-tasks, all parented, mean
-- 1.03 children per parent, max 2). Real hierarchies cluster — a few stories own most
-- sub-tasks — so if a later run wants a tail on that endpoint, narrow the modulus to a
-- fraction of ptotal and re-read the printed distribution. Say which shape was measured.
CREATE TEMP TABLE ld_parent AS
SELECT project_id, id,
       row_number() OVER (PARTITION BY project_id ORDER BY n) AS pidx,
       count(*)     OVER (PARTITION BY project_id)            AS ptotal
  FROM ld_issue WHERE level >= 1;

CREATE INDEX ON ld_parent (project_id, pidx);

UPDATE issues c SET parent_id = p.id
  FROM ld_issue lc
  JOIN ld_parent p
    ON p.project_id = lc.project_id
   AND p.pidx = 1 + ((lc.n::bigint * 7919) % p.ptotal)
 WHERE c.id = lc.id AND lc.level = 0;

-- ---------------------------------------------------------------------------
-- 8. Issue history — ~8 rows per issue (§4.2).
--
-- Split into transitions and everything else, because the flow report reads issue_history
-- rows whose field = 'status' and matches new_value against a status NAME (V18 does the
-- same thing when it backfills started_at). History that stored ids there would make every
-- transition count zero while looking perfectly populated.
-- ---------------------------------------------------------------------------

-- (a) To Do -> In Progress, for everything that ever started.
INSERT INTO issue_history (id, issue_id, changed_by, field, old_value, new_value, created_at)
SELECT pg_temp.uuid7(i.started_at), i.id, i.reporter_id, 'status',
       (SELECT name FROM ld_status WHERE category = 'TODO' LIMIT 1),
       (SELECT name FROM ld_status WHERE category = 'IN_PROGRESS' LIMIT 1),
       i.started_at
  FROM issues i JOIN ld_issue l ON l.id = i.id
 WHERE i.started_at IS NOT NULL ORDER BY i.id;

-- (b) In Progress -> Done, for everything closed.
INSERT INTO issue_history (id, issue_id, changed_by, field, old_value, new_value, created_at)
SELECT pg_temp.uuid7(i.closed_at), i.id, i.reporter_id, 'status',
       (SELECT name FROM ld_status WHERE category = 'IN_PROGRESS' LIMIT 1),
       (SELECT name FROM ld_status WHERE category = 'DONE' LIMIT 1),
       i.closed_at
  FROM issues i JOIN ld_issue l ON l.id = i.id
 WHERE i.closed_at IS NOT NULL ORDER BY i.id;

-- (c) Six non-status edits per issue, spread between creation and now, so the issue-detail
--     history tab has a realistic page and the trailing-90-day window hits rows.
--
-- ---------------------------------------------------------------------------
-- THE MATERIALIZED-DRAW PATTERN, USED FROM HERE DOWN. READ THIS BEFORE EDITING ANY OF IT.
--
-- The obvious way to write these — `FROM issues i, LATERAL generate_series(1,n) k,
-- LATERAL (SELECT random() AS r) d` — IS WRONG, and wrong in the way that does not
-- announce itself. PostgreSQL inlines a single-row LATERAL subquery, so `d.r` is not one
-- draw shared by the k rows: it is a `random()` call re-evaluated wherever the alias is
-- mentioned, and NOT re-evaluated per k. The first version of this file did that and
-- produced a fixture that looked plausible and was not:
--
--     issue_labels     exactly 1.0 per issue instead of ~2  (every k iteration picked the
--                      SAME label, and DISTINCT ON collapsed them — silently)
--     issue_versions   exactly 1.0 per issue instead of ~1.2 (same cause)
--     attachments      ZERO rows instead of 5% (the `WHERE r < 0.05` filter re-rolled)
--     comments         7.0 per issue instead of ~2.9, with the long tail gone
--
-- Every one of those is a DISTRIBUTION failure, and §12 names distribution as THE
-- highest-risk assumption in the whole measurement: a fixture that is uniformly wrong
-- produces a number that is precise and misleading, and nothing in the run reveals it.
-- The row COUNTS looked fine. Only the ratios gave it away.
--
-- The correct pattern: build a MATERIALIZED CTE whose rows are already the final
-- (parent, k) pairs, drawing every random INSIDE that CTE. Materialization is what forces
-- one evaluation per row. 20-resync.sql prints the ratios afterwards precisely so this
-- class of failure is caught by looking rather than by trusting.
-- ---------------------------------------------------------------------------
INSERT INTO issue_history (id, issue_id, changed_by, field, old_value, new_value, created_at)
WITH src AS MATERIALIZED (
    SELECT i.id, i.reporter_id AS reporter, i.created_at
      FROM issues i JOIN ld_issue x ON x.id = i.id
),
hrows AS MATERIALIZED (
    SELECT s.id, s.reporter, s.created_at, k,
           random() AS r_when, random() AS r_field
      FROM src s, LATERAL generate_series(1, 6) k
)
SELECT pg_temp.uuid7(t.ts), r.id, r.reporter,
       (ARRAY['assignee','priority','title','description','component',
              'story_points','sprint','labels'])[1 + floor(r.r_field * 8)::int],
       pg_temp.prose(12), pg_temp.prose(12), t.ts
  FROM hrows r,
       LATERAL (SELECT r.created_at
                     + (((SELECT t0 FROM ld_anchor) - r.created_at) * r.r_when) AS ts) t
 ORDER BY r.id, r.k;

-- ---------------------------------------------------------------------------
-- 9. Comments — long tail (§4.2).
--
-- Most issues have 0-2, a few have 45+, so issue detail has both a cheap and an expensive
-- case and a p99 over that class means something. A fixture with a flat 2.5 comments per
-- issue has a p99 equal to its p50 and reports a browsing latency nobody experiences.
--
-- Expected mean ~2.9: 0.45*0 + 0.35*1.5 + 0.18*6 + 0.02*64.5. 20-resync.sql prints the
-- MEASURED mean, p50, p99 and max — check them, because "the tail is there" is exactly the
-- claim the broken LATERAL form above also appeared to satisfy.
-- ---------------------------------------------------------------------------
INSERT INTO issue_comments (id, issue_id, author_id, body, created_at, updated_at, deleted_at)
WITH src AS MATERIALIZED (
    SELECT i.id, i.created_at, i.reporter_id AS author,
           random() AS r_bucket, random() AS r_size
      FROM issues i JOIN ld_issue x ON x.id = i.id
),
counted AS MATERIALIZED (
    SELECT s.id, s.created_at, s.author,
           CASE WHEN s.r_bucket < 0.45 THEN 0
                WHEN s.r_bucket < 0.80 THEN 1 + floor(s.r_size * 2)::int
                WHEN s.r_bucket < 0.98 THEN 3 + floor(s.r_size * 7)::int
                ELSE                       45 + floor(s.r_size * 40)::int END AS cnt
      FROM src s
),
crows AS MATERIALIZED (
    SELECT c.id, c.created_at, c.author, k,
           random() AS r_when, random() AS r_len, random() AS r_del
      FROM counted c, LATERAL generate_series(1, c.cnt) k
)
SELECT pg_temp.uuid7(t.ts), r.id, r.author,
       pg_temp.prose(40 + floor(r.r_len * r.r_len * 1500)::int), t.ts, t.ts,
       CASE WHEN r.r_del > 0.97 THEN t.ts + interval '1 day' END   -- a few soft-deleted
  FROM crows r,
       LATERAL (SELECT r.created_at
                     + (((SELECT t0 FROM ld_anchor) - r.created_at) * r.r_when) AS ts) t
 ORDER BY r.id, r.k;

-- ---------------------------------------------------------------------------
-- 9b. Comment mentions.
--
-- TWO EDGES FROM A TENANT ROOT, AND THAT IS WHY THEY ARE HERE.
--
-- The product writes one of these per @mention and issue detail renders them, so a load
-- fixture without any under-represents the comment path a little. That is the small reason.
--
-- The large one is that comment_mentions is the ONLY table in this fixture that carries no
-- workspace_id, no scope_workspace_id and no issue_id, and is reached solely at the SECOND
-- round of completeness.sql's fixpoint (workspaces -> issues -> issue_comments ->
-- comment_mentions). Without a row in it, an implementation of that fixpoint which ran its
-- body once and stopped would pass every tripwire the rehearsal has — each of the others is
-- reached on the first pass — and "the fixpoint iterates" would rest on nobody having tried.
-- rehearse.sh's tripwire D is that test, and this INSERT is what makes it possible.
--
-- One mention on roughly one comment in eight, at most one per (comment, author) because of
-- the UNIQUE (comment_id, user_id): a second draw would collide and the fixture would fail
-- for a reason that has nothing to do with what it is generating.
-- ---------------------------------------------------------------------------
INSERT INTO comment_mentions (id, comment_id, user_id, created_at)
SELECT pg_temp.uuid7(c.created_at), c.id, u.id, c.created_at
  FROM issue_comments c
  JOIN issues i     ON i.id = c.issue_id
  JOIN ld_issue x   ON x.id = i.id
  JOIN ld_user  u   ON u.tag = (SELECT w.tag FROM ld_ws w WHERE w.id = i.workspace_id)
                   AND u.i = 1 + ((('x' || substr(md5(c.id::text), 1, 8))::bit(32)::bigint
                                   & 2147483647) % u.total)
 WHERE (('x' || substr(md5(c.id::text), 25, 8))::bit(32)::bigint & 7) = 0
 ORDER BY c.id;

-- ---------------------------------------------------------------------------
-- 10. Custom field values, labels, version links.
-- ---------------------------------------------------------------------------

-- Two per issue: a join against the workspace's two custom fields, so there is no per-row
-- count to draw. The option index is the only random and it is used exactly once.
INSERT INTO issue_field_values (id, issue_id, field_id, value, created_at)
SELECT pg_temp.uuid7(i.created_at), i.id, f.id,
       to_jsonb((f.config -> 'options' -> floor(random() * 3)::int ->> 'id')),
       i.created_at
  FROM issues i
  JOIN ld_issue x ON x.id = i.id
  JOIN ld_field f ON f.workspace_id = i.workspace_id
 ORDER BY i.id, f.n;

-- ~2 labels per issue, drawn from the workspace's 400-label catalog. DISTINCT ON dedupes
-- the genuine collisions two independent draws produce; before the materialized rewrite it
-- was concealing the fact that there had only ever been ONE draw.
INSERT INTO issue_labels (id, issue_id, label_id, workspace_id, created_at)
WITH src AS MATERIALIZED (
    SELECT i.id, i.created_at, i.workspace_id, random() AS r_bucket
      FROM issues i JOIN ld_issue x ON x.id = i.id
),
counted AS MATERIALIZED (
    SELECT s.id, s.created_at, s.workspace_id,
           CASE WHEN s.r_bucket < 0.20 THEN 0
                WHEN s.r_bucket < 0.70 THEN 2
                WHEN s.r_bucket < 0.95 THEN 3
                ELSE                        5 END AS cnt
      FROM src s
),
lrows AS MATERIALIZED (
    SELECT c.id, c.created_at, c.workspace_id, k, random() AS r_pick
      FROM counted c, LATERAL generate_series(1, c.cnt) k
)
SELECT DISTINCT ON (r.id, l.id)
       pg_temp.uuid7(r.created_at), r.id, l.id, r.workspace_id, r.created_at
  FROM lrows r
  JOIN ld_label l ON l.workspace_id = r.workspace_id
                 AND l.i = 1 + floor(r.r_pick * l.total)::int
 ORDER BY r.id, l.id;

-- ~1.2 version links per issue, split FIX / AFFECTS. The same version may legitimately
-- appear in BOTH roles on one issue (a regression introduced and fixed in one release), so
-- link_type is part of the uniqueness rather than something to dedupe away.
INSERT INTO issue_versions (id, issue_id, version_id, workspace_id, link_type, created_at)
WITH src AS MATERIALIZED (
    SELECT i.id, i.created_at, i.workspace_id, i.project_id, random() AS r_bucket
      FROM issues i JOIN ld_issue x ON x.id = i.id
),
counted AS MATERIALIZED (
    SELECT s.id, s.created_at, s.workspace_id, s.project_id,
           CASE WHEN s.r_bucket < 0.35 THEN 0
                ELSE 1 + floor(s.r_bucket * 2)::int END AS cnt
      FROM src s
),
vrows AS MATERIALIZED (
    SELECT c.id, c.created_at, c.workspace_id, c.project_id, k,
           random() AS r_pick, random() AS r_role
      FROM counted c, LATERAL generate_series(1, c.cnt) k
)
SELECT DISTINCT ON (r.id, v.id, role.lt)
       pg_temp.uuid7(r.created_at), r.id, v.id, r.workspace_id, role.lt, r.created_at
  FROM vrows r
  JOIN ld_version v ON v.project_id = r.project_id
                   AND v.i = 1 + floor(r.r_pick * v.total)::int
  CROSS JOIN LATERAL (SELECT CASE WHEN r.r_role < 0.6 THEN 'FIX' ELSE 'AFFECTS' END AS lt) role
 ORDER BY r.id, v.id, role.lt;

-- ---------------------------------------------------------------------------
-- 11. Sprint scope events.
--
-- One ADDED per issue that is in a sprint, plus a REMOVED/ADDED churn pair on ~15% of
-- them, because velocity and sprint-review read the pair and a fixture with only arrivals
-- makes scope-change arithmetic trivially balanced.
--
-- issue_key is snapshotted at write time and is NOT NULL. issue_id stays populated here;
-- the column is nullable so that DELETING an issue preserves the event (V18's
-- ON DELETE SET NULL). A reader must group by issue_id OR BY issue_key WHEN IT IS NULL,
-- and must never inner-join issues — see README.md.
-- ---------------------------------------------------------------------------

INSERT INTO sprint_scope_events (id, workspace_id, sprint_id, issue_id, issue_key, event,
                                 story_points, actor_id, occurred_at, created_at)
WITH src AS MATERIALIZED (
    SELECT i.id, i.workspace_id, i.sprint_id, i.number, i.story_points, i.reporter_id,
           i.created_at, p.key, random() AS r_churn, random() AS r_when
      FROM issues i JOIN ld_issue x ON x.id = i.id
      JOIN projects p ON p.id = i.project_id
     WHERE i.sprint_id IS NOT NULL
),
erows AS MATERIALIZED (
    SELECT s.id, s.workspace_id, s.sprint_id, s.number, s.story_points, s.reporter_id,
           s.created_at, s.key, s.r_when, ev.event, ev.ord
      FROM src s,
           LATERAL unnest(CASE WHEN s.r_churn < 0.15
                               THEN ARRAY['ADDED','REMOVED','ADDED']
                               ELSE ARRAY['ADDED'] END)
                   WITH ORDINALITY AS ev(event, ord)
)
SELECT pg_temp.uuid7(t.ts), r.workspace_id, r.sprint_id, r.id,
       r.key || '-' || r.number, r.event, r.story_points, r.reporter_id, t.ts, t.ts
  FROM erows r,
       -- Ordinality spaces the churn triple in time. An ADDED and its later REMOVED must
       -- not share a timestamp, or a reader ordering by occurred_at sees an arrival and a
       -- departure in an arbitrary order and the scope arithmetic stops balancing.
       LATERAL (SELECT r.created_at + interval '1 hour' * (r.r_when * 240)
                     + interval '6 hours' * r.ord AS ts) t
 ORDER BY r.id, r.ord;

-- ---------------------------------------------------------------------------
-- 12. Attachment metadata.
--
-- Metadata only, on ~5% of issues. Blobs are deliberately near-zero: this is a disk
-- experiment we do not need to run, and it is also the one place DC and Cloud differ
-- (local FS vs S3) — generating megabytes here would turn a storage-backend difference
-- into a difference in what the run measures (§9).
--
-- CONSEQUENCE THE HARNESS MUST RESPECT: these storage keys name nothing in any backend.
-- The browsing mix LISTS attachments (which is what the SPA does on issue detail) and
-- never downloads one. fixture/verify-api.sh uploads a handful of REAL bytes through the
-- API so that the download path is exercised at least once, against objects that exist.
-- ---------------------------------------------------------------------------

-- workspace_id is NOT NULL from V26 (the tenant, denormalised onto the row so the storage
-- counter's trigger learns it without walking two parents). It is taken from the issue, which
-- is what the composite FK (issue_id, workspace_id) -> issues (id, workspace_id) requires.
-- The counter rows in workspace_storage_usage need no statement here: the AFTER INSERT trigger
-- maintains them, so the fixture's workspaces come out with true totals.
INSERT INTO issue_attachments (id, issue_id, filename, storage_key, size_bytes,
                               content_type, uploaded_by, created_at, workspace_id)
WITH src AS MATERIALIZED (
    SELECT i.id, i.workspace_id, i.number, i.reporter_id, i.created_at,
           random() AS r_has, random() AS r_size
      FROM issues i JOIN ld_issue x ON x.id = i.id
)
SELECT pg_temp.uuid7(s.created_at), s.id,
       format('spec-%s.pdf', s.number),
       format('ws/%s/issues/%s/%s', s.workspace_id, s.id, gen_random_uuid()),
       (20000 + s.r_size * 400000)::bigint, 'application/pdf', s.reporter_id,
       s.created_at, s.workspace_id
  FROM src s
 WHERE s.r_has < 0.05
 ORDER BY s.id;

-- ---------------------------------------------------------------------------
-- 13. Saved filters.
--
-- Saved-filter CRUD is on the SEARCH budget, because validating a filter's HQL builds the
-- same ResolutionContext /search/schema pays for (CLAUDE.md). The reporting mix reads and
-- writes these, so the fixture ships a few that already exist.
-- ---------------------------------------------------------------------------

INSERT INTO saved_filters (id, workspace_id, owner_id, name, hql, shared,
                           created_at, updated_at)
SELECT pg_temp.uuid7(a.t0 - interval '30 days'), w.id,
       (SELECT id FROM ld_user u WHERE u.tag = w.tag ORDER BY u.i LIMIT 1),
       f.name, f.hql, TRUE, a.t0 - interval '30 days', a.t0 - interval '30 days'
  FROM ld_ws w, ld_anchor a,
       LATERAL (VALUES
         ('Open work',        'status != "Done" ORDER BY created DESC'),
         ('Recently closed',  'status = "Done" ORDER BY updated DESC'),
         ('Text sweep',       'text ~ "checkout" ORDER BY created DESC')
       ) f(name, hql)
 ORDER BY w.tag, f.name;

COMMIT;
