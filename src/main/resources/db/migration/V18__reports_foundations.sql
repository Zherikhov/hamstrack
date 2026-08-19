-- ---------------------------------------------------------------------------
-- HD-137 / R2 (Reports — foundations) — reports-proposal §5.1 + §5.2
-- ---------------------------------------------------------------------------
-- Two artefacts, both written by the application from this release on, both read
-- by report slices that do not exist yet (R3 cycle/lead time, R4 burn-up + sprint
-- review, R5 velocity). Nothing in this migration is reachable over HTTP: R2
-- lands dark on purpose, so the data starts accumulating a release BEFORE the
-- first chart that needs it.
--
--   1) issues.started_at   — when work actually began on an issue.
--   2) sprint_scope_events — an append-only ledger of sprint MEMBERSHIP changes.
--
-- !! TRANSACTION REQUIREMENT !! (the V11/V12/V14 note, unchanged) This script uses
-- `SET LOCAL hamstrack.skip_updated_at = 'on'` so its one data rewrite does not
-- look like a user edit. SET LOCAL is only effective INSIDE a transaction; Flyway
-- wraps each script in one, and that is the only reason it works. If anyone ever
-- adds a statement that cannot run in a transaction (CREATE INDEX CONCURRENTLY,
-- ALTER TYPE … ADD VALUE) or turns on `spring.flyway.mixed` /
-- `executeInTransaction=false`, PostgreSQL downgrades the SET LOCAL to a WARNING
-- and the backfill below silently stamps updated_at = NOW() on every issue it
-- touches — poisoning Home / My work / ORDER BY updated with no error anywhere.

SET LOCAL hamstrack.skip_updated_at = 'on';

-- ---------------------------------------------------------------------------
-- 1) issues.started_at (§5.1)
-- ---------------------------------------------------------------------------
-- Stamped by IssueService the FIRST time an issue enters a status whose category is
-- IN_PROGRESS *or* DONE — an issue dragged straight to Done was started and then
-- finished in one move, and refusing to admit that would drop exactly the fastest
-- work out of every cycle-time percentile.
--
-- ASYMMETRY WITH closed_at, deliberate and load-bearing: closed_at is CLEARED when
-- an issue leaves a DONE status, because "is it closed" is a question about the
-- issue's current state. "When did work start" is not a current-state question, so
-- started_at is NEVER cleared and never rewritten. Clearing (or re-stamping) it on
-- a re-open would make the cycle time of reopened work shrink retroactively, which
-- is the kind of number that quietly makes a report untrustworthy.
--
-- No DEFAULT and no NOT NULL: NULL means "we do not know when this started", which
-- is a fact the reports print (missingStartCount) rather than paper over.
ALTER TABLE issues ADD COLUMN started_at TIMESTAMPTZ;

-- The third of the three report range scans (V17 added the other two: created / closed).
-- PARTIAL for the same reason idx_issues_project_closed is: started_at is NULL for
-- everything never picked up, and no NULL row can ever satisfy `started_at >= x`.
CREATE INDEX idx_issues_project_started ON issues (project_id, started_at)
    WHERE started_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 1b) Backfill — BEST EFFORT, and labelled as such (§5.1)
-- ---------------------------------------------------------------------------
-- issue_history is the only record of past status moves, and it stores the status
-- DISPLAY NAME (field VARCHAR(50), old_value/new_value TEXT — no ids, no project
-- column). So this join is by name, and is approximate BY CONSTRUCTION:
--
--   * a status that has since been RENAMED is invisible to it (its old name matches
--     no current statuses row) — those issues keep started_at = NULL;
--   * an issue created directly INTO an in-progress status wrote no history row at
--     all (create-time values are not audited), so it also keeps NULL;
--   * two same-named statuses in different workflows collide, and that collision is
--     harmless ONLY when they share a CATEGORY. A workspace-scoped "In Progress" whose
--     category is TO_DO legally coexists with the global one (statuses are unique per
--     scope, not per name), so an issue that only ever entered the former can be
--     stamped from the latter. That stays inside ONE tenant — the scope predicates
--     below see to that — and inside this statement's best-effort framing, but it is a
--     wrong date rather than a no-op, and nothing here can tell the two apart:
--     issue_history stores a NAME, and a name is not an identity.
--
-- Every issue this cannot resolve keeps started_at = NULL and is reported by the
-- cycle-time report as `missingStartCount`, never guessed.
--
-- *** DO NOT "IMPROVE" THIS WITH A FALLBACK TO created_at. *** Filing time is not
-- start time; substituting it turns a cycle-time report into a lead-time report
-- wearing a false name, which is worse than an honest gap (§2.2). The same rule
-- applies to closed_at ("it must have started when it finished" = every reopened or
-- fast-tracked issue reporting a zero-length cycle).
--
-- Idempotent: `AND i.started_at IS NULL` means a re-run — or a later, better
-- backfill — can never overwrite a value the application has since stamped.
--
-- LOCK FOOTPRINT, written down so the next reader does not "fix" it. The ALTER TABLE
-- above already holds ACCESS EXCLUSIVE on `issues` for the rest of this Flyway
-- transaction, and this UPDATE deliberately runs INSIDE that window: it rewrites every
-- resolvable row in one statement, on a table nothing can read until the migration
-- commits anyway. CHUNKING IS NOT THE FIX. A batched loop only helps if each batch
-- COMMITS, which needs `executeInTransaction = false` on this script — and that
-- downgrades the `SET LOCAL hamstrack.skip_updated_at` at the top to a WARNING, so the
-- backfill would silently stamp updated_at = NOW() on every issue it touches (the exact
-- poisoning the header exists to prevent). If this ever gets too slow at 10x the data,
-- the answer is a separate, resumable, application-driven backfill that sets the GUC
-- per batch — never a loop in this file.
--
-- The status scope predicates keep the name join TENANT-AWARE: statuses are either
-- global (both scope columns NULL), workspace-scoped or project-scoped, so a status
-- belonging to another tenant can never decide whether this issue was started.
UPDATE issues i
   SET started_at = sub.first_move
  FROM (
        SELECT h.issue_id, MIN(h.created_at) AS first_move
          FROM issue_history h
          JOIN issues hi ON hi.id = h.issue_id
         WHERE h.field = 'status'
           AND EXISTS (
                 SELECT 1
                   FROM statuses s
                  WHERE s.name = h.new_value
                    AND s.category IN ('IN_PROGRESS', 'DONE')
                    AND (s.scope_workspace_id IS NULL OR s.scope_workspace_id = hi.workspace_id)
                    AND (s.scope_project_id   IS NULL OR s.scope_project_id   = hi.project_id))
         GROUP BY h.issue_id
       ) sub
 WHERE i.id = sub.issue_id
   AND i.started_at IS NULL;

-- ---------------------------------------------------------------------------
-- 2) sprint_scope_events — the ledger (§5.2)
-- ---------------------------------------------------------------------------
-- WHY A LEDGER AND NOT A SNAPSHOT: the burn-up needs to know *when* scope moved,
-- not only its endpoints; the sprint review needs the add/remove lists; velocity
-- needs "committed" for each of the last N sprints. One append-only table answers
-- all three, is ID-keyed (so it is immune to the sprint and status renames that
-- make anything derived from issue_history approximate — see the backfill above),
-- and indexes trivially.
--
-- COMMITMENT IS AN EVENT, NOT A SNAPSHOT. SprintService.start writes one ADDED row
-- per current member issue dated occurred_at = the sprint's start, inside the same
-- transaction as the conditional markActive UPDATE and only AFTER its affected-row
-- arbitration succeeds — so a losing double-click writes nothing at all. Scope at
-- time T is then `count(ADDED <= T) - count(REMOVED <= T)`, and "committed" is
-- simply scope at start. One mechanism, no second concept.
--
-- Consequence, stated so nobody re-derives it from an empty table: membership churn
-- BEFORE a sprint starts writes NOTHING here (planning is not scope change), or the
-- start batch would count those issues twice. SprintScopeLedger owns that rule.
--
-- workspace_id is DENORMALISED for the same reason issues, sprints, components and
-- versions carry it: a tenant-scoped query must never have to join through projects.
-- AND — the part that is not optional — it is part of BOTH parent FKs, so the three ids
-- can only be assembled from rows that agree on the tenant. This is the V8 §3.8 rule
-- ("attaching one tenant's row to another's is not checked, it is UNREPRESENTABLE"),
-- which issue_labels (V8), issue_components (V9), issue_versions (V10) and
-- issues.sprint_id (V11) all already follow. It matters here even more than there:
-- idx_sprint_scope_events_ws exists precisely so R4's velocity sweep can filter by
-- workspace ALONE, and a row whose workspace_id disagreed with its sprint would be read
-- into the wrong tenant's report — where the only symptom is a number, i.e. none.
--
-- The single-column FKs to sprints(id)/issues(id) are deliberately NOT declared: each
-- composite FK already proves its parent row exists, and a second FK would only add a
-- redundant RI trigger to every insert (V8 §3.8 again). The FK on workspace_id stays,
-- because after an issue delete (see below) issue_id is NULL and the sprint FK is then
-- the only other thing naming a live tenant row.
--
-- created_at (from CreatedOnlyEntity) and occurred_at are BOTH here and are not the
-- same fact: created_at is when the row was written, occurred_at is when the event
-- happened. They are equal for every path except the start batch, which is dated the
-- sprint's start_at — a curator may legitimately start a sprint with a BACKdated start,
-- and the burn-up's x-axis is that date, not the click. FORWARD-dating is refused, not
-- by this table but by the application, twice: SprintService.requireStartHasArrived 422s
-- a start dated ahead of now and SprintService.start clamps the few minutes of clock
-- skew that guard deliberately tolerates, so the SAME instant reaches sprints.start_at
-- and the commitment rows and a burn-up can be windowed on occurred_at >= start_at; and
-- SprintScopeLedger clamps the stamp anyway because it is the single writer (for today's
-- doors that clamp is a no-op, which is what belt-and-braces should mean). Without
-- either, five ADDED rows dated a month out plus one
-- REMOVED dated today makes count(ADDED <= T) - count(REMOVED <= T) equal -1 for a
-- month, and a scope line that goes negative is a chart that visibly lies.
--
-- No updated_at, no version, no UPDATE trigger: this table is APPEND-ONLY. Nothing in
-- the application ever updates or deletes a row here.
CREATE TABLE sprint_scope_events (
    id           UUID        PRIMARY KEY,                             -- app-generated UUID v7
    workspace_id UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    sprint_id    UUID        NOT NULL,
    -- NULLABLE, and ON DELETE SET NULL rather than the CASCADE §5.2 originally proposed.
    -- The reasoning changed on review, and the reason is worth keeping:
    --
    -- A completed sprint's membership is FROZEN everywhere else in this codebase —
    -- SprintService.requireDetachable refuses (422) to let even a MANAGER pull a DONE
    -- issue out of a closed sprint, so that "17 of 21 points delivered" cannot quietly
    -- become another number. But IssueService.delete performs no sprint-state check at
    -- all, so under CASCADE the same actor who is explicitly refused the detach could
    -- achieve exactly its effect by deleting the issue: the ADDED and REMOVED rows would
    -- both vanish and every past report would silently re-draw.
    --
    -- Blocking the delete is the wrong correction: requireDetachable forbids EDITING
    -- history while the issue still exists, and deleting an issue is a different act
    -- that must not be punished with a report. So the LEDGER ROW OUTLIVES THE ISSUE
    -- instead — the scope arithmetic keeps both of its steps, the sprint review record
    -- stays truthful, and issue_key + story_points below carry enough identity for the
    -- row to still be labelled once the issue itself is gone.
    --
    -- MATCH SIMPLE (the default) is what makes this legal: with issue_id NULL the
    -- composite FK is skipped entirely, exactly as it is for issues.sprint_id = NULL
    -- (V11). The column list on SET NULL is mandatory (PG 15+; we require PG 16) — a
    -- bare SET NULL would also try to null the NOT NULL workspace_id and fail at delete
    -- time.
    issue_id     UUID,
    -- The issue's KEY at the moment of the event ("HD-137"), snapshotted so a row whose
    -- issue has since been deleted can still be read as a line of a sprint review
    -- instead of an unresolvable UUID. Denormalised on purpose and never rewritten: a
    -- project rename (projects.key) does not retro-edit what the review said, exactly as
    -- issue_history keeps the names things had when they changed.
    --
    -- Why 40 and not 30 — the arithmetic, written down because the number is otherwise
    -- unmaintainable: projects.key is VARCHAR(10), the separator is one '-', and
    -- issues.number is a BIGINT whose widest decimal form is 19 digits. 10 + 1 + 19 = 30
    -- EXACTLY, which is the worst kind of correct — it is right today and silently stops
    -- being right the moment anyone widens projects.key, at which point a perfectly
    -- legitimate append fails with "value too long" on an APPEND-ONLY table: a 500 on a
    -- path that has no retry, no repair and no user-visible cause. A VARCHAR bound costs
    -- nothing in storage (it is variable-length), so 10 characters of headroom turn that
    -- future schema change back into a non-event.
    issue_key    VARCHAR(40) NOT NULL,
    -- 'ADDED' | 'REMOVED' — VARCHAR + the Java enum SprintScopeEventType, never a
    -- PostgreSQL ENUM type (Hibernate 7 + PG enums throw JDBC cast errors on INSERT).
    -- A CHECK is warranted here, unlike sprints.state: this vocabulary is a closed
    -- two-value pair that the arithmetic `count(ADDED) - count(REMOVED)` depends on,
    -- so a third value would not be a new feature, it would be a wrong number.
    event        VARCHAR(10) NOT NULL,
    -- The issue's estimate AT THE MOMENT OF THE EVENT, so a points burn-up can be
    -- reconstructed without re-reading today's value. NULL = unestimated, which is
    -- deliberately not 0 (issues.story_points carries the same distinction).
    -- Re-estimating an issue is NOT a scope event and writes nothing here (§2.3).
    story_points NUMERIC(5,2),
    -- Who moved it. NULLABLE and ON DELETE SET NULL: the event is a fact about the
    -- sprint and must outlive the account that caused it.
    actor_id     UUID            REFERENCES users(id) ON DELETE SET NULL,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT sprint_scope_events_event_ck CHECK (event IN ('ADDED', 'REMOVED')),
    -- sprints_id_workspace_id_key (V11) and issues_id_workspace_id_key (V8) already
    -- exist — each is declared ONCE for the whole epic, and re-adding either here would
    -- fail with "constraint already exists" and take this migration down.
    CONSTRAINT sprint_scope_events_sprint_fk FOREIGN KEY (sprint_id, workspace_id)
        REFERENCES sprints (id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT sprint_scope_events_issue_fk FOREIGN KEY (issue_id, workspace_id)
        REFERENCES issues (id, workspace_id) ON DELETE SET NULL (issue_id)
);

-- The burn-up's one query: every event of one sprint in chronological order.
CREATE INDEX idx_sprint_scope_events_sprint ON sprint_scope_events (sprint_id, occurred_at);
-- Tenant-scoped sweeps (velocity across a workspace's sprints, and any future
-- retention/export job) without a join through projects.
CREATE INDEX idx_sprint_scope_events_ws ON sprint_scope_events (workspace_id);
-- The sprint-review record reads "what happened to THIS issue in THIS sprint", the
-- doors test asks the same question per issue, and the issue FK's ON DELETE SET NULL
-- probes the same column. PARTIAL because issue_id is NULL for every row whose issue has
-- since been deleted, and no NULL row can satisfy `issue_id = x` (the same reasoning as
-- idx_issues_project_started, one table up).
CREATE INDEX idx_sprint_scope_events_issue ON sprint_scope_events (issue_id)
    WHERE issue_id IS NOT NULL;

-- No retention policy in v1, deliberately: a few narrow rows per issue per sprint —
-- a 100k-issue workspace running sprints for three years is low single-digit
-- millions of rows, which (sprint_id, occurred_at) serves fine. Revisit with data.
