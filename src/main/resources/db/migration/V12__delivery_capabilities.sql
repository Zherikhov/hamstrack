-- ---------------------------------------------------------------------------
-- HD-102 (Delivery capabilities) — delivery-paths-proposal §11.1
-- ---------------------------------------------------------------------------
-- A project declares HOW ITS TEAM DELIVERS through three independent
-- capabilities, instead of every SPA surface guessing from the presence of data:
--
--   board      KANBAN | SCRUM   -> projects.board_mode (already exists, V11)
--   releases   on | off         -> projects.releases_enabled   (new)
--   estimation on | off         -> projects.estimation_enabled (new)
--
-- The preset label (KANBAN / SCRUM / RELEASES / CUSTOM) is DERIVED server-side
-- from those three and is deliberately NOT stored — one source of truth, no
-- drift (§2.3).
--
-- RULE A (§5.1): these columns gate the UI only, NEVER the API. No endpoint
-- reads them, no status code depends on them; POST /sprints still succeeds on a
-- Kanban project and fixVersionIds still applies with releases off. They are a
-- presentation preference, exactly like board_mode has been since V11 — never a
-- permission.
--
-- BOOLEAN columns, no CHAR(n) and no `CREATE TYPE … AS ENUM` (CLAUDE.md
-- Hibernate-7 gotchas). No new table, so no UUID/PK/trigger concern.
-- `board_mode` is NOT renamed (§3 non-goals / open question 4): the domain
-- vocabulary moves, the column does not.
--
-- !! ONE-SHOT UPGRADE — NEVER REPLAY THIS SCRIPT AGAINST A POST-V12 DATABASE !!
-- Flyway will never re-run it; a human pasting it into psql to "fix" something
-- would. The two backfills below are upgrade-moment statements, not idempotent
-- repairs:
--   * `UPDATE projects SET releases_enabled = TRUE, estimation_enabled = TRUE;`
--     has NO WHERE clause at all. Replayed later it force-enables both
--     capabilities on EVERY project in the install — including ones a curator
--     has since deliberately turned off to keep lean, which by then exist.
--   * the sprint-driven `board_mode = 'SCRUM'` flip would likewise overwrite a
--     project that legitimately went back to Kanban after acquiring a sprint.
-- If a real repair is ever needed, write a new, WHERE-scoped V{n}.
--
-- Two deliberate choices in the sprint predicate of backfill B, so they read as
-- decisions rather than oversights:
--   * a project whose only sprint is COMPLETED IS flipped to SCRUM. §7 says
--     "owns ≥1 sprint row", with no status filter — and locally that is 24 real
--     projects, not a hypothetical.
--   * a project that DELETED all its sprints is NOT detected. The predicate is
--     "owns a sprint now", not "ever used sprints"; there is no history table to
--     ask, and inventing one for a one-shot backfill is not worth it.
-- Neither is a problem because Rule C makes both reversible from the Backlog in
-- one click — the capability is a preference, never a permission (§5.1).
--
-- !! TRANSACTION REQUIREMENT !! Same as V11: the two backfills below run under
-- `SET LOCAL hamstrack.skip_updated_at = 'on'`, which is only effective INSIDE a
-- transaction. Flyway wraps each script in one, and that is the only reason it
-- works. If anyone ever adds a statement that cannot run in a transaction, or
-- turns on `spring.flyway.mixed` / `executeInTransaction=false`, PostgreSQL
-- downgrades SET LOCAL to a WARNING and the backfills stamp
-- projects.updated_at = NOW() on every project in the install — with no error
-- anywhere. Keep this script transactional.
SET LOCAL hamstrack.skip_updated_at = 'on';

-- ---------------------------------------------------------------------------
-- 1) The two new capability columns
-- ---------------------------------------------------------------------------
-- The DB defaults (FALSE) are the NEW-PROJECT defaults (§7 / open question 2:
-- lean — Kanban, releases off, estimation off). The existing-project rule is the
-- opposite and is carried by the explicit UPDATEs below, so the two policies stay
-- visibly separate rather than being smuggled into a column default.
ALTER TABLE projects ADD COLUMN releases_enabled   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE projects ADD COLUMN estimation_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- 2) Backfill A — an upgrade never takes away a capability a project already has
-- ---------------------------------------------------------------------------
-- Every project can reach the Releases page today, and the story-points input has
-- been unconditional since 0.13.0. A data-driven rule ("only projects that already
-- have versions/points") would silently remove a nav item and an input from teams
-- who were about to use them, on upgrade, with no warning (§7).
UPDATE projects SET releases_enabled = TRUE, estimation_enabled = TRUE;

-- ---------------------------------------------------------------------------
-- 3) Backfill B — the mid-adoption rescue (§7)
-- ---------------------------------------------------------------------------
-- A project that already OWNS a sprint row is demonstrably using iterations,
-- whatever its board_mode says (V11 defaulted every project to KANBAN, and a team
-- could have created a sprint via the API or flipped back afterwards). Left alone
-- it would land on a Backlog whose sprint sections are hidden — the production
-- dead end this epic exists to close. Projects with no sprint keep their stored
-- board_mode untouched.
UPDATE projects p SET board_mode = 'SCRUM'
 WHERE p.board_mode <> 'SCRUM'
   AND EXISTS (SELECT 1 FROM sprints s WHERE s.project_id = p.id);
