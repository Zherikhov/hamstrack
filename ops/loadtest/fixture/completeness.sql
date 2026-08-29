-- =============================================================================
-- HD-186 — the teardown completeness assertion.
-- Spec: §5.5.3, acceptance criterion 11.
--
-- Emits ONE row per offending (table, column, count). Zero rows means the database is
-- clean. The wrapper (teardown.sh) turns a non-empty result into a non-zero exit; this
-- file is also run STANDALONE, before teardown, where it must report a large number —
-- that is the tripwire (see below).
--
-- Variables: :'slug_prefix', :'email_domain'
--
-- ---------------------------------------------------------------------------
-- WHAT THIS FILE IS ABOUT AND WHAT IT IS NOT ABOUT.
--
-- IT SEES POSTGRESQL AND NOTHING ELSE. "Zero rows attributable to the fixture" is a claim
-- about this database, not about the instance: attachment BLOBS live in FileStorage (local
-- disk in DC, S3 in Cloud) and no query here can see one. verify-api.sh uploads three real
-- objects and deletes them through the API for that reason — the API is the only thing
-- that knows both halves. If a future step uploads more, it deletes them the same way; a
-- storage key is not a row and this check will never notice one.
--
-- ---------------------------------------------------------------------------
-- WHY THIS IS A CATEGORY AND NOT A LIST — AND WHY A CATEGORY IS NOT AUTOMATICALLY SAFE.
--
-- The proposal says: iterate information_schema for every table carrying a workspace_id. A
-- hand-written list of tables would be wrong one migration after it was written, and it
-- would be wrong SILENTLY — a new table simply would not appear, and the check would keep
-- returning "clean" about a database it had stopped looking at.
--
-- The first version of this file widened that category and then repeated its mistake one
-- level down: it matched `column_name = 'workspace_id'` EXACTLY, over a schema that spells
-- workspace tenancy TWO ways. Everything in the taxonomy and custom-field family uses
-- `scope_workspace_id` — field_defs, field_sets, statuses, priorities, issue_types,
-- workflows, priority_sets, issue_type_sets — and their children carry no tenancy column
-- at all. Fifteen base tables the teardown NAMES were invisible to the verifier that is
-- supposed to prove the teardown worked. No rows were left behind by that; the defect was
-- that the two files disagreed about the shape of the database, so "zero rows attributable
-- to the fixture" was being printed by something that could not have seen them.
--
-- A CATEGORY DEFINED BY A STRING IS A LIST WITH ONE ENTRY. So the categories below are
-- defined by MECHANISM, read from the catalog:
--
--   1. workspace   every base table that either carries a column named workspace_id or
--                  scope_workspace_id, OR has a single-column foreign key whose target is
--                  workspaces(id). The name half covers the tables with no FK at all
--                  (mail_send_events, issue_labels, issue_versions).
--
--                  A TABLE ENTERS THIS CATEGORY WITH THE PREDICATE THIS CATEGORY KNOWS,
--                  AND THAT USED TO BE THE END OF IT. `scope_project_id` is a THIRD
--                  spelling of tenancy, on eight tables (the project-scoped half of the
--                  taxonomy and custom-field family). Those tables also carry
--                  scope_workspace_id, so they entered here at depth 0 with a
--                  workspace-only predicate — and the fixpoint's `NOT EXISTS` guard then
--                  refused to touch an already-reached table, so the project spelling was
--                  never added. A row scoped to a load PROJECT with a NULL
--                  scope_workspace_id was invisible. Nothing was left behind by it, only
--                  because of an ON DELETE CASCADE that neither the teardown nor this
--                  check names — which is to say it was correct by an accident neither
--                  file could see.
--
--                  The fixpoint below therefore WIDENS an already-reached table instead of
--                  skipping it, and every count is taken after the fixpoint has finished
--                  rather than as each table is discovered.
--   2. reachable   a FIXPOINT over foreign keys: any base table with a single-column FK
--                  into a table already reached is itself reached, through its parent's
--                  load-owned ids. This is what finds issue_comments, issue_history,
--                  issue_field_values, issue_attachments and comment_mentions (through
--                  issues), field_set_items, workflow_statuses, workflow_transitions,
--                  priority_set_items, issue_type_set_items and role_permissions (through
--                  their scoped parents), and whatever a future migration hangs off any of
--                  them. Nothing here is typed out.
--   3. issue       every table with an issue_id column, kept as a belt beside the fixpoint:
--                  a child that loses its foreign key stops being reachable and does not
--                  stop being tenant data.
--   4. user        every column in the public schema whose foreign key points at users(id).
--   5. recipient   every recipient-shaped column, matched on the address domain. For
--                  failed_email and for an anonymous mail_send_events row (both
--                  workspace_id and sender_user_id are nullable) THE ADDRESS IS THE
--                  TENANCY HANDLE — there is no other.
--
-- ---------------------------------------------------------------------------
-- WHICH CATEGORIES SURVIVE THE DELETION OF THEIR OWN HANDLE — READ THIS BEFORE BELIEVING
-- A "CLEAN" VERDICT AFTER A TEARDOWN.
--
-- Categories 1, 2 and 3 all resolve through ld_scope_ws (and, for 3, through ld_scope_issue,
-- which is derived from it). A successful teardown deletes the load WORKSPACES, so
-- ld_scope_ws is then EMPTY and every one of those predicates is `col IN (empty set)` =
-- false for every row of every table. They cannot report anything, and their silence is not
-- evidence.
--
-- What actually holds after a teardown is: the schema's own foreign keys would have REFUSED
-- the workspace delete if a child row still pointed into it (teardown.sql adds no CASCADE
-- for exactly this reason), so "the workspaces are gone" implies "their children are gone".
-- That is a real argument and it is the database's, not this file's.
--
-- The categories that still LOOK are 4 (anything referencing users(id)) and 5 (the address
-- domain) — both keyed on handles the teardown deletes LAST and which this check can
-- therefore still see fail. They are what makes a post-teardown run more than a formality.
--
-- BEFORE a teardown, when the handles exist, all five categories look and all five are the
-- tripwire (rehearse.sh fires four of them). The distinction is between the two runs, not
-- between the categories.
--
-- ---------------------------------------------------------------------------
-- The tripwire.
--
-- A check that returns "nothing offends" is worthless until it has been seen to say
-- "something offends" — and a category that has never been seen to fire has not been shown
-- to work, which is exactly how the workspace_id spelling stayed broken. fixture/rehearse.sh
-- now fires FOUR of them: the whole fixture, a partial teardown witnessed through issues, a
-- partial teardown witnessed through scope_workspace_id, and one witnessed through a pure
-- child. A run in which any of those comes back clean fails the rehearsal.
-- =============================================================================

\set ON_ERROR_STOP on

-- Resolve the handles once. Everything below joins to these; nothing below takes an id
-- from a human.
DROP TABLE IF EXISTS ld_scope_ws;
CREATE TEMP TABLE ld_scope_ws AS
    SELECT id FROM workspaces WHERE slug LIKE :'slug_prefix' || '%';

DROP TABLE IF EXISTS ld_scope_user;
CREATE TEMP TABLE ld_scope_user AS
    SELECT id FROM users WHERE email LIKE '%@' || :'email_domain';

DROP TABLE IF EXISTS ld_scope_issue;
CREATE TEMP TABLE ld_scope_issue AS
    SELECT id FROM issues WHERE workspace_id IN (SELECT id FROM ld_scope_ws);

DROP TABLE IF EXISTS ld_offence;
CREATE TEMP TABLE ld_offence (category text, tbl text, col text, leftover bigint);

-- The reachability frontier: one row per table that has been shown to be tenant-owned,
-- carrying the predicate that selects ITS load-owned rows. `has_id` decides whether it can
-- be a parent in the next round — role_permissions has no id column, so it can be counted
-- and can never be joined through.
DROP TABLE IF EXISTS ld_reach;
CREATE TEMP TABLE ld_reach (tbl text PRIMARY KEY, col text, pred text,
                            has_id boolean, depth int);

-- Every foreign-key edge the fixpoint has followed, so an edge is followed once.
DROP TABLE IF EXISTS ld_edge;
CREATE TEMP TABLE ld_edge (child text, col text, parent text,
                           PRIMARY KEY (child, col, parent));

-- THE MATERIALISED HALF, AND THE REASON THE FIXPOINT TERMINATES.
--
-- A child's predicate names its parent's OWNED IDS — `col IN (SELECT id FROM ld_owned WHERE
-- tbl = 'parent')` — and never its parent's predicate TEXT. That distinction is the whole
-- design:
--
--   * Embedding the text meant a child's predicate contained its parent's, which contained
--     its grandparent's. The schema has foreign-key CYCLES (workspaces.default_project_role_id
--     -> roles -> workspaces; projects -> workflows/field_sets/priority_sets and back), so
--     once an already-reached table could be WIDENED, every trip round a cycle doubled the
--     string. Measured, not predicted: a rehearsal produced a 96 790-character predicate for
--     `projects` and a ceiling had to refuse most of the schema.
--   * Naming the id set makes every predicate CONSTANT SIZE. Widening is then an OR of a
--     handful of short clauses, and the fixpoint runs on the DATA rather than on the SQL: a
--     round re-materialises the owned set of any table whose predicate changed, and stops
--     when no owned set grew and no new edge appeared. Owned sets only ever grow and are
--     bounded by the number of rows in the database, so it terminates for a reason stronger
--     than a round counter — the round counter stays anyway, to bound the pathological case.
DROP TABLE IF EXISTS ld_owned;
CREATE TEMP TABLE ld_owned (tbl text, id uuid, PRIMARY KEY (tbl, id));

-- The address domain, as a literal usable inside the generated predicates.
SELECT set_config('hamstrack.load_email_domain', :'email_domain', false);

DO $$
DECLARE
    r record;
    n bigint;
    ins bigint;
    rounds int;
    changed int;
    dom text := current_setting('hamstrack.load_email_domain');
BEGIN
    -- ---- category 1: workspace-scoped tables -----------------------------
    -- Union of two mechanisms on purpose. The FK half is the general rule and covers a
    -- spelling nobody has invented yet; the NAME half covers the columns that have no
    -- foreign key to cover them — mail_send_events.workspace_id has none at all and is
    -- documented as "written, never queried" (V21), and issue_labels/issue_versions reach
    -- workspaces only through composite keys this deliberately does not follow.
    FOR r IN
        SELECT t, col FROM (
            SELECT c.table_name AS t, c.column_name AS col
              FROM information_schema.columns c
              JOIN information_schema.tables tt
                ON tt.table_schema = c.table_schema AND tt.table_name = c.table_name
             WHERE c.table_schema = 'public'
               AND c.column_name IN ('workspace_id', 'scope_workspace_id')
               AND tt.table_type = 'BASE TABLE'
            UNION
            SELECT cl.relname AS t, att.attname AS col
              FROM pg_constraint con
              JOIN pg_class cl      ON cl.oid = con.conrelid
              JOIN pg_class fcl     ON fcl.oid = con.confrelid
              JOIN pg_namespace ns  ON ns.oid = cl.relnamespace
              JOIN unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
              JOIN pg_attribute att ON att.attrelid = cl.oid AND att.attnum = k.attnum
             WHERE con.contype = 'f' AND ns.nspname = 'public'
               AND fcl.relname = 'workspaces'
               AND cl.relkind = 'r'
               AND array_length(con.conkey, 1) = 1
        ) u ORDER BY t, col
    LOOP
        -- NOT COUNTED HERE. A table admitted at depth 0 may still have its predicate WIDENED
        -- by the fixpoint — scope_project_id is the case that matters, and it is why the
        -- `NOT EXISTS (… already in ld_reach)` guard had to go — so a count taken now would
        -- be a count of the narrow predicate wearing the wide one's label. Everything is
        -- counted once, after the fixpoint, from ld_reach.pred.
        INSERT INTO ld_reach (tbl, col, pred, has_id, depth)
        VALUES (r.t, r.col,
                format('%I IN (SELECT id FROM ld_scope_ws)', r.col),
                EXISTS (SELECT 1 FROM information_schema.columns c2
                         WHERE c2.table_schema = 'public' AND c2.table_name = r.t
                           AND c2.column_name = 'id'),
                0)
        ON CONFLICT (tbl) DO UPDATE
           SET pred = ld_reach.pred || ' OR ' || EXCLUDED.pred;
    END LOOP;

    -- `workspaces` itself is the root, and it is a parent for the round below (roles and
    -- projects both point back at it in ways the FK scan does not follow).
    INSERT INTO ld_reach (tbl, col, pred, has_id, depth)
    VALUES ('workspaces', 'id', 'id IN (SELECT id FROM ld_scope_ws)', TRUE, 0)
    ON CONFLICT (tbl) DO NOTHING;

    -- ---- category 2: the fixpoint over foreign keys ----------------------
    --
    -- IT WIDENS AN ALREADY-REACHED TABLE RATHER THAN SKIPPING IT. The guard here used to be
    -- `NOT EXISTS (SELECT 1 FROM ld_reach WHERE tbl = child)`, which reads as "already
    -- covered" and means "already covered BY WHATEVER PREDICATE IT ARRIVED WITH". Eight
    -- tables carry `scope_project_id` — a THIRD spelling of tenancy — and also carry
    -- scope_workspace_id, so they entered at depth 0 with a workspace-only predicate and the
    -- skip is what kept the project spelling permanently unreachable. A row scoped to a load
    -- PROJECT with a NULL scope_workspace_id was invisible, and nothing was left behind by
    -- that only because of an ON DELETE CASCADE that neither this file nor the teardown
    -- names.
    --
    -- Each round does two things, and the loop ends when neither of them changes anything:
    --   (a) MATERIALISE — re-evaluate the owned id set of every reached table whose
    --       predicate has changed since it was last materialised. Owned sets only grow.
    --   (b) EXTEND — follow every not-yet-followed foreign key from a reached table with an
    --       id, ORing a constant-size clause into the child's predicate.
    --
    -- The round cap is a bound on a pathological schema, not the termination argument.
    rounds := 0;
    LOOP
        rounds := rounds + 1;
        EXIT WHEN rounds > 12;
        changed := 0;

        -- (a) materialise — EVERY REACHED TABLE, EVERY ROUND.
        --
        -- This used to skip a table whose PREDICATE TEXT had not changed since it was last
        -- materialised (an md5 of `pred`, kept in ld_reach.owned_pred_hash). A predicate is
        -- not the only thing that decides a table's owned set: most of them read
        -- `… IN (SELECT id FROM ld_owned WHERE tbl = 'parent')`, so an owned set grows
        -- whenever its PARENT's owned set grows in a later round, with the child's predicate
        -- text untouched. `ORDER BY tbl` then decides whether that is noticed at all —
        -- issue_comments sorts before issues, so the child was materialised from a parent set
        -- that grew immediately afterwards and was never re-read.
        --
        -- It was harmless only because issues.workspace_id and projects.workspace_id are
        -- NOT NULL, so those two parents are complete after round 1 — a property of an
        -- unrelated migration, holding up a fixpoint. Re-materialising unconditionally costs
        -- one cheap re-scan per reached table per round (the ladder's whole rehearsal is
        -- seconds) and removes the dependency entirely.
        --
        -- Termination is unaffected: `ON CONFLICT DO NOTHING` makes the insert idempotent
        -- and `changed` counts only rows actually ADDED, so a round that discovers nothing
        -- new still ends the loop.
        FOR r IN SELECT tbl, pred FROM ld_reach WHERE has_id ORDER BY tbl
        LOOP
            EXECUTE format(
                'INSERT INTO ld_owned (tbl, id) SELECT %L, id FROM public.%I WHERE %s
                 ON CONFLICT DO NOTHING', r.tbl, r.tbl, r.pred);
            GET DIAGNOSTICS ins = ROW_COUNT;
            changed := changed + ins;
        END LOOP;

        -- (b) extend
        FOR r IN
            SELECT cl.relname AS child, att.attname AS col, p.tbl AS parent
              FROM pg_constraint con
              JOIN pg_class cl      ON cl.oid = con.conrelid
              JOIN pg_class fcl     ON fcl.oid = con.confrelid
              JOIN pg_namespace ns  ON ns.oid = cl.relnamespace
              JOIN unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
              JOIN pg_attribute att ON att.attrelid = cl.oid AND att.attnum = k.attnum
              JOIN ld_reach p       ON p.tbl = fcl.relname AND p.has_id
             WHERE con.contype = 'f' AND ns.nspname = 'public'
               AND cl.relkind = 'r'
               AND array_length(con.conkey, 1) = 1
               AND cl.relname <> fcl.relname
               AND NOT EXISTS (SELECT 1 FROM ld_edge e
                                WHERE e.child = cl.relname AND e.col = att.attname
                                  AND e.parent = p.tbl)
             ORDER BY cl.relname, att.attname
        LOOP
            INSERT INTO ld_edge (child, col, parent) VALUES (r.child, r.col, r.parent);

            INSERT INTO ld_reach (tbl, col, pred, has_id, depth)
            VALUES (r.child, r.col,
                    format('%I IN (SELECT id FROM ld_owned WHERE tbl = %L)',
                           r.col, r.parent),
                    EXISTS (SELECT 1 FROM information_schema.columns c2
                             WHERE c2.table_schema = 'public' AND c2.table_name = r.child
                               AND c2.column_name = 'id'),
                    rounds)
            ON CONFLICT (tbl) DO UPDATE
               SET pred  = ld_reach.pred || ' OR ' || EXCLUDED.pred,
                   -- The SHALLOWEST arrival wins, so a table that entered at depth 0 and was
                   -- later widened is still reported under the category it belongs to.
                   depth = LEAST(ld_reach.depth, EXCLUDED.depth);
            changed := changed + 1;
        END LOOP;

        EXIT WHEN changed = 0;
    END LOOP;

    -- ---- count everything ld_reach found, ONCE, with its FINAL predicate ----
    -- After the fixpoint, never during it. `workspaces` is excluded because it is counted
    -- explicitly at the end (by slug), and counting it twice would put a number in the run
    -- record that is not the number of rows.
    FOR r IN SELECT tbl, col, pred, depth FROM ld_reach
              WHERE tbl <> 'workspaces' ORDER BY tbl
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE %s', r.tbl, r.pred) INTO n;
        IF n > 0 THEN
            INSERT INTO ld_offence
            VALUES (CASE WHEN r.depth = 0 THEN 'workspace' ELSE 'reachable' END,
                    r.tbl, COALESCE(r.col, '(fk)'), n);
        END IF;
    END LOOP;

    -- ---- category 3: issue_id, by name -----------------------------------
    -- Kept beside the fixpoint rather than replaced by it. The fixpoint follows FOREIGN
    -- KEYS; a child whose FK is dropped in some future migration stops being reachable and
    -- does not stop being tenant data. This is also the category tripwire B watches.
    FOR r IN
        SELECT c.table_name AS t, c.column_name AS col
          FROM information_schema.columns c
          JOIN information_schema.tables tt
            ON tt.table_schema = c.table_schema AND tt.table_name = c.table_name
         WHERE c.table_schema = 'public' AND c.column_name = 'issue_id'
           AND tt.table_type = 'BASE TABLE'
         ORDER BY c.table_name
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM public.%I WHERE %I IN (SELECT id FROM ld_scope_issue)',
            r.t, r.col) INTO n;
        IF n > 0 THEN
            INSERT INTO ld_offence VALUES ('issue', r.t, r.col, n);
        END IF;
    END LOOP;

    -- ---- category 4: anything pointing at users(id) ----------------------
    -- Derived from pg_constraint, so it covers reporter_id, changed_by, uploaded_by,
    -- invited_by, lead_id, actor_id, owner_id and every column added after this was
    -- written. `users` itself is checked separately below.
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
           -- Composite FKs into users do not exist today; if one is added, its non-user
           -- column would be checked here too and would report a false offence. Restricting
           -- to single-column FKs keeps this honest and makes the gap visible rather than
           -- wrong.
           AND array_length(con.conkey, 1) = 1
         ORDER BY cl.relname, att.attname
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM public.%I WHERE %I IN (SELECT id FROM ld_scope_user)',
            r.t, r.col) INTO n;
        IF n > 0 THEN
            INSERT INTO ld_offence VALUES ('user', r.t, r.col, n);
        END IF;
    END LOOP;

    -- ---- category 5: recipient-shaped columns ----------------------------
    -- THE ADDRESS IS THE TENANCY HANDLE HERE, and for two tables it is the only one.
    -- failed_email is keyed by recipient and carries no workspace and no user; a
    -- mail_send_events row for a send that had no authenticated sender has both
    -- workspace_id and sender_user_id NULL. Neither is reachable by any FK, so a check
    -- built only on keys reports a clean database while the fixture's mail history sits
    -- in it. Matched on the domain, which is the same handle the teardown deletes by.
    FOR r IN
        SELECT c.table_name AS t, c.column_name AS col
          FROM information_schema.columns c
          JOIN information_schema.tables tt
            ON tt.table_schema = c.table_schema AND tt.table_name = c.table_name
         WHERE c.table_schema = 'public' AND tt.table_type = 'BASE TABLE'
           AND c.data_type IN ('character varying', 'text', 'character')
           AND (c.column_name IN ('recipient', 'recipient_email', 'recipient_key',
                                  'to_address', 'email')
                OR c.column_name LIKE 'recipient%')
         ORDER BY c.table_name, c.column_name
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE %I LIKE %L',
                       r.t, r.col, '%@' || dom) INTO n;
        IF n > 0 THEN
            INSERT INTO ld_offence VALUES ('recipient', r.t, r.col, n);
        END IF;
    END LOOP;

    -- ---- the accounts themselves ----------------------------------------
    SELECT count(*) INTO n FROM ld_scope_user;
    IF n > 0 THEN
        INSERT INTO ld_offence VALUES ('user', 'users', 'email', n);
    END IF;

    -- ---- the workspaces themselves --------------------------------------
    SELECT count(*) INTO n FROM ld_scope_ws;
    IF n > 0 THEN
        INSERT INTO ld_offence VALUES ('workspace', 'workspaces', 'slug', n);
    END IF;
END $$;

-- The categories overlap on purpose (a table can be both workspace-scoped and reachable),
-- so the report and the total are taken over DISTINCT (table, column). Counting an
-- overlap twice would not change the verdict — the assertion is "== 0" — but it would put
-- a number in the run record that is not the number of rows.
DROP TABLE IF EXISTS ld_final;
CREATE TEMP TABLE ld_final AS
SELECT DISTINCT ON (tbl, col) category, tbl, col, leftover
  FROM ld_offence
 ORDER BY tbl, col,
          CASE category WHEN 'workspace' THEN 1 WHEN 'issue' THEN 2 WHEN 'reachable' THEN 3
                        WHEN 'user' THEN 4 ELSE 5 END;

\echo ''
\echo '=== teardown completeness: rows still attributable to the load fixture ==='
SELECT category, tbl AS "table", col AS "column", leftover
  FROM ld_final ORDER BY leftover DESC, tbl;

-- The single number the wrapper reads. Printed LAST, unaligned and untitled, so `tail -1`
-- is exact rather than a parse.
--
-- The format is NOT restored afterwards, deliberately: `\pset` echoes "Output format is
-- aligned." to STDOUT, which would then be the last line and the wrapper would read a
-- sentence where it expected an integer. (It did, once — and refused to report a clean
-- database rather than guessing, which is the behaviour this whole file is for.) The
-- session ends here, so there is nothing to restore the format for.
\pset tuples_only on
\pset format unaligned
SELECT COALESCE(sum(leftover), 0) FROM ld_final;
