-- ---------------------------------------------------------------------------
-- HD-191 (Write budget and workspace storage quota) — storage side only
-- docs/design/write-budget-and-storage-quota-proposal.md §7.1, ADR-0026
-- ---------------------------------------------------------------------------
-- Three artefacts:
--
--   1) issue_attachments.workspace_id  — the tenant, denormalised onto the row.
--   2) workspace_storage_usage         — one counter row per workspace.
--   3) a row trigger on issue_attachments that maintains (2) from (1).
--
-- WHY A COUNTER TABLE AND NOT A SUM, AND NOT A COLUMN ON workspaces (ADR-0026).
-- A SUM on the upload path is correct and unbounded — it grows with every file the
-- tenant ever kept, on the hot path. A column on `workspaces` is the projects.issue_seq
-- clobber scar exactly (a DB-maintained counter on an entity that is loaded, mutated and
-- saved elsewhere), and it would make the reservation lock a lock on `workspaces`, which
-- CLAUDE.md forbids because every FK child insert in the tenant would queue behind it.
-- A row in a table nothing else references locks only what it means to lock: concurrent
-- uploads into one workspace.
--
-- NO CHECK (bytes_used >= 0). A check on a trigger-maintained counter can only fire when
-- the counter is ALREADY wrong, and it fires on the statement trying to REDUCE it — i.e.
-- it converts a benign drift into an inability to delete. The trigger clamps with
-- GREATEST(0, …) and drift is surfaced as a metric (hamstrack.storage.drift_bytes) and
-- corrected by WorkspaceStorageReconciler.
--
-- STATEMENT ORDER IS LOAD-BEARING: the trigger is created LAST, after the workspace_id
-- backfill and after the seed. It fires on `UPDATE OF size_bytes, workspace_id`, so a
-- trigger that already existed during the backfill below would have counted every attachment
-- in the instance before the seed then counted it again -- and the failure would be LOUD
-- rather than silent: the seed has no ON CONFLICT clause, so it would abort on the counter
-- table's primary key and take the whole migration (and the deploy) with it. Loud is the
-- better failure and it is still a failure; the order is what makes it neither.
--
-- THE ASSUMPTION THIS RESTS ON, stated so it is tested rather than believed: a row-level
-- AFTER DELETE trigger fires for rows removed by ON DELETE CASCADE. PostgreSQL's
-- referential cascade performs ordinary row deletions, which fire row triggers — so the
-- counter follows the rows through issue delete, project delete and any future purge,
-- including paths nobody has written yet. If that were false the counter would be a
-- one-way ratchet and only the nightly reconciler would ever catch it, so it is proved by
-- StorageQuotaTest.deletingAProjectReturnsTheCounterToItsPreUploadValue (delete a PROJECT,
-- watch the counter fall) and by V26StorageQuotaMigrationTest, never by this paragraph.
--
-- WHAT THE TRIGGER DOES NOT FIRE ON: TRUNCATE. A row trigger sees INSERT/UPDATE/DELETE, and
-- `TRUNCATE issue_attachments` removes every row without firing any of them — so the counters
-- survive intact and every workspace is left OVERSTATED (the quota refuses uploads into
-- workspaces that now hold nothing) until WorkspaceStorageReconciler's next pass corrects
-- them. That is the intended division of labour rather than a gap: a statement-level TRUNCATE
-- trigger would have to zero every workspace on the instance, which is wrong the moment
-- anybody truncates a partition or a restore truncates before COPY.
--
-- hamstrack.skip_updated_at IS DELIBERATELY NOT CONSULTED by the trigger below. That GUC
-- belongs to the issues rank rebalance and to updated_at triggers on business rows; a
-- counter that skipped its own update because an unrelated bulk operation set a GUC would
-- be a silent drift generator. Verified at build time (OQ-D3): no path in this tree sets
-- that GUC while touching issue_attachments — the only setters are the rank rebalance
-- (issues) and the migrations that backfill issues columns.
--
-- Standing rules: no CHAR(n), no PG ENUM type, ids stay app-generated UUID v7, and this
-- migration is never edited once applied. NOTE FOR THE NEXT READER: ddl-auto=validate
-- compares tables and columns and does NOT compare column WIDTHS or look at indexes,
-- triggers or foreign keys — nothing here is protected by it, which is why V26 has tests
-- of its own. Do not write "validate will catch it" in a migration header.

-- ---------------------------------------------------------------------------
-- 1) issue_attachments.workspace_id
-- ---------------------------------------------------------------------------
-- Three things at once: the trigger learns the tenant without walking two parents (and
-- walking them from inside a cascade is a bet on RI ordering); the reconciler and the
-- per-project breakdown become indexed single-table aggregates; and a table that carried
-- no tenant column at all now carries one.
ALTER TABLE issue_attachments ADD COLUMN workspace_id UUID;

UPDATE issue_attachments a
   SET workspace_id = i.workspace_id
  FROM issues i
 WHERE i.id = a.issue_id;

ALTER TABLE issue_attachments ALTER COLUMN workspace_id SET NOT NULL;

-- COMPOSITE FK, the shape sprint_scope_events (V18) already uses against the existing
-- issues_id_workspace_id_key (V8): it makes "this attachment's workspace IS its issue's
-- workspace" a database fact rather than an application habit. ON DELETE CASCADE here —
-- unlike sprint_scope_events, which keeps its ledger — because an attachment has no
-- meaning once its issue is gone. The pre-existing single-column FK on issue_id is left
-- in place; two cascading FKs to the same parent are not a conflict.
ALTER TABLE issue_attachments
    ADD CONSTRAINT issue_attachments_issue_ws_fk
    FOREIGN KEY (issue_id, workspace_id) REFERENCES issues (id, workspace_id) ON DELETE CASCADE;

-- The reconciler's aggregate and the per-project breakdown both lead on this column.
CREATE INDEX issue_attachments_workspace_idx ON issue_attachments (workspace_id);

-- ---------------------------------------------------------------------------
-- 2) workspace_storage_usage
-- ---------------------------------------------------------------------------
-- BIGINT on both counters: a workspace can legitimately hold more than 2 GB, and
-- attachment_count is BIGINT for symmetry rather than for size.
--
-- updated_at is written by the trigger and by the reconciler, NEVER by @LastModifiedDate —
-- there is no auditing on this table because there is no application write to audit.
CREATE TABLE workspace_storage_usage (
    workspace_id     UUID        PRIMARY KEY REFERENCES workspaces (id) ON DELETE CASCADE,
    bytes_used       BIGINT      NOT NULL DEFAULT 0,
    attachment_count BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- 3) The seed — computed from the rows, one row per workspace INCLUDING empty ones
-- ---------------------------------------------------------------------------
-- Empty workspaces get a row too, so the ordinary read is a primary-key hit rather than a
-- miss. Nothing depends on the row pre-existing: the reservation path upserts it with
-- ON CONFLICT DO NOTHING and a missing row reads as 0 everywhere.
INSERT INTO workspace_storage_usage (workspace_id, bytes_used, attachment_count, updated_at)
SELECT w.id, COALESCE(a.bytes, 0), COALESCE(a.cnt, 0), NOW()
  FROM workspaces w
  LEFT JOIN (SELECT workspace_id, SUM(size_bytes) AS bytes, COUNT(*) AS cnt
               FROM issue_attachments
              GROUP BY workspace_id) a ON a.workspace_id = w.id;

-- ---------------------------------------------------------------------------
-- 4) The trigger
-- ---------------------------------------------------------------------------
-- A COUNTER ROW IS CREATED ONLY BY AN ATTACHMENT ARRIVING IN A WORKSPACE — the INSERT branch,
-- and the destination half of the move branch, which is the same event seen from the counter.
-- EVERY OTHER branch is a plain UPDATE that quietly affects nothing when there is no row: both
-- decrements, and (the one that is easy to get wrong) the size-only change. That is the answer
-- to §15's second-order risk. A decrement can run inside a multi-level cascade
-- (workspaces -> projects -> issues -> issue_attachments) in which this counter's OWN row
-- is being removed; an INSERT there would fail the FK against a workspace that is going away
-- and would break the delete outright. A decrement never needs to create a row — an absent
-- row already means zero. The size-only UPDATE branch is an UPDATE for a second reason as
-- well: it has no idea what the true count is, so an upsert there would have to invent one
-- (it would insert attachment_count = 1 for a workspace whose rows nobody counted), and a
-- fabricated count is worse than an absent row, which at least reads as the zero it is.
-- The INSERT branch runs only when an attachment is being created, where the workspace
-- certainly exists.
--
-- ORDER-INDEPENDENT, WHICH IS THE ONLY THING IT CAN BE. AFTER triggers fire in trigger-NAME
-- order and PostgreSQL's internal RI trigger names carry constraint OIDs, so whether this
-- counter's decrement runs before or after any other cascade step of the same statement is
-- undefined. The DELETE branch is correct under either order: it neither reads nor writes a
-- workspaces row, and an UPDATE that matches nothing is a no-op rather than an error. Nothing
-- here depends on the counter row still existing, or on it being gone.
--
-- (The workspaces -> projects -> issues chain above is NOT reachable today, and that was
-- checked rather than assumed: issues.workspace_id carries no ON DELETE action, so
-- `DELETE FROM workspaces` where any issue exists is refused outright with
-- "violates foreign key constraint issues_workspace_id_fkey" — the cascade never reaches an
-- attachment. The branch therefore protects a purge path that does not exist yet rather than
-- one running today, which is exactly when it is cheap to write.)
CREATE OR REPLACE FUNCTION workspace_storage_usage_apply() RETURNS TRIGGER AS $fn$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO workspace_storage_usage (workspace_id, bytes_used, attachment_count, updated_at)
        VALUES (NEW.workspace_id, GREATEST(0, NEW.size_bytes), 1, NOW())
        ON CONFLICT (workspace_id) DO UPDATE
           SET bytes_used       = GREATEST(0, workspace_storage_usage.bytes_used + NEW.size_bytes),
               attachment_count = GREATEST(0, workspace_storage_usage.attachment_count + 1),
               updated_at       = NOW();

    ELSIF TG_OP = 'DELETE' THEN
        UPDATE workspace_storage_usage
           SET bytes_used       = GREATEST(0, bytes_used - OLD.size_bytes),
               attachment_count = GREATEST(0, attachment_count - 1),
               updated_at       = NOW()
         WHERE workspace_id = OLD.workspace_id;

    ELSIF OLD.workspace_id IS DISTINCT FROM NEW.workspace_id THEN
        -- Not reachable today, and the reason is the DATABASE rather than the entity mapping:
        -- issue_attachments_issue_ws_fk is a non-deferrable composite FK against
        -- issues (id, workspace_id), so an UPDATE that moves only the attachment's workspace_id
        -- is refused 23503, and moving the ISSUE is refused from the parent side. (The column is
        -- also updatable=false on the entity, which is the weaker of the two guards.) Written
        -- anyway: "attachments never move workspace" is a claim about today, and the failure mode
        -- of being wrong is a permanently overstated counter in one tenant and an understated one
        -- in another.
        UPDATE workspace_storage_usage
           SET bytes_used       = GREATEST(0, bytes_used - OLD.size_bytes),
               attachment_count = GREATEST(0, attachment_count - 1),
               updated_at       = NOW()
         WHERE workspace_id = OLD.workspace_id;
        INSERT INTO workspace_storage_usage (workspace_id, bytes_used, attachment_count, updated_at)
        VALUES (NEW.workspace_id, GREATEST(0, NEW.size_bytes), 1, NOW())
        ON CONFLICT (workspace_id) DO UPDATE
           SET bytes_used       = GREATEST(0, workspace_storage_usage.bytes_used + NEW.size_bytes),
               attachment_count = GREATEST(0, workspace_storage_usage.attachment_count + 1),
               updated_at       = NOW();

    ELSE
        -- Size changed, workspace did not. A PLAIN UPDATE, never an upsert: this branch knows
        -- the DELTA and nothing else, so creating a row here would have to invent an
        -- attachment_count (it would write 1 for a workspace whose rows nobody has counted),
        -- and a fabricated count is worse than an absent row — an absent row reads as the zero
        -- it honestly is and the reconciler corrects it, while a wrong count is a number the
        -- quota trusts. Matching nothing is the correct outcome when there is no counter row.
        UPDATE workspace_storage_usage
           SET bytes_used = GREATEST(0, bytes_used + NEW.size_bytes - OLD.size_bytes),
               updated_at = NOW()
         WHERE workspace_id = NEW.workspace_id;
    END IF;
    RETURN NULL;
END;
$fn$ LANGUAGE plpgsql;

-- UPDATE OF names workspace_id as well as size_bytes. That does NOT make the move branch
-- reachable by relaxing the entity: the composite FK issue_attachments_issue_ws_fk is
-- non-deferrable, so a moved attachment is refused by the database whatever the mapping says.
-- The branch becomes reachable only if that FK is dropped or made DEFERRABLE and an issue is
-- ever moved between workspaces -- which is precisely when nobody will remember to add a
-- column here. AFTER, so the counter follows a row change that actually happened.
CREATE TRIGGER trg_issue_attachments_storage_usage
    AFTER INSERT OR DELETE OR UPDATE OF size_bytes, workspace_id ON issue_attachments
    FOR EACH ROW EXECUTE FUNCTION workspace_storage_usage_apply();
