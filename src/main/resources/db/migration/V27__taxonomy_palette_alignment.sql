-- ---------------------------------------------------------------------------
-- HD-176 -- PALETTE ALIGNMENT. THIS IS NOT AN ACCESSIBILITY FIX.
-- ---------------------------------------------------------------------------
-- READ THIS PARAGRAPH BEFORE "CORRECTING" ANY VALUE BELOW. Some of the colours
-- this file writes measure a LOWER contrast ratio against white than the ones
-- they replace and some measure a higher one -- To Do 4.83 -> 4.97, Done
-- 2.54 -> 3.03 and Epic 2.15 -> 2.54 go up; In Progress 3.68 -> 2.35, Urgent
-- 6.47 -> 3.76, High 3.56 -> 2.35 and Medium 5.02 -> 1.92 go down. The direction
-- is an OUTCOME of picking the declared hue, never a criterion: no value below
-- was chosen for its ratio, in either direction. That is correct and deliberate,
-- and the reason is the decision the ticket's owner took (ADR-0027, docs/design/
-- taxonomy-colour-contrast-proposal.md decision 2):
--
--   A STORED COLOUR IS AN IDENTITY HUE, NOT INK. The readable foreground is
--   DERIVED AT RENDER TIME from the hue and the surface it is painted on
--   (inkOn, in the SPA's colour primitive), preserving chromaticity and
--   returning the input untouched whenever it already clears 4.5:1.
--
-- So a seeded colour's job is (a) to BE the hue DESIGN.md declares for that
-- entity, and (b) to be PAINTABLE as a swatch -- the 3:1 ring WCAG 1.4.11 asks
-- for is derived from the hue at render time by ringOn, exactly like the ink, so
-- no seed value owes a ratio either. Some of the values below do not clear 3:1
-- against white on their own, and that is as immaterial as the ink: a swatch is
-- drawn as the hue at full strength plus a derived hairline, and the hairline is
-- computed from whatever hue it is handed. Readability of text is likewise the
-- renderer's job, at every hue, forever; it is not the seed's job and it cannot
-- be, because a seed cannot know what surface it will be drawn on and because no
-- seed can rescue the colours customers pick for themselves. Rewriting these
-- literals "for contrast" would re-open a decision, not fix a bug, and would put
-- the database back out of step with DESIGN.md -- which is the ONLY defect this
-- file exists to close.
--
-- What the drift was: DESIGN.md declares a catalog palette (brand #0EA5A4,
-- pending #F79009, sandbox #667085, the priority four and the issue-type four);
-- V1 seeded a DIFFERENT one; priorities.color still defaulted to #8B8680, a warm
-- grey from the RETIRED visual language; and StatusBadge's category fallback
-- (sandbox / pending / brand) disagreed with the seeded status colours, so the
-- same status rendered amber or blue depending on whether a colour survived the
-- trip. After this file the schema and the design system say the same thing.
--
-- ---------------------------------------------------------------------------
-- WHAT IT TOUCHES, AND WHAT IT MUST NEVER TOUCH
-- ---------------------------------------------------------------------------
-- Data only, plus three catalogue-only DEFAULTs. No new table, no new column, no
-- type change, no row created and no row deleted. Every UPDATE writes color and
-- nothing else -- no id, name, category, icon, position or archived_at moves.
--
-- ONLY THE GLOBAL SEEDS (scope_workspace_id IS NULL AND scope_project_id IS
-- NULL). A global row is a PRODUCT choice: it is shared by every tenant and is
-- unreachable from any tenant's console -- AdminCatalogService resolves through
-- findByIdAtScope, which never matches a global row from a scoped console -- so
-- the only party who can have deliberately changed one is the instance
-- operator's system admin. A workspace- or project-scoped row is a CUSTOMER
-- choice and is untouched here, forever (ADR-0022: a migration may correct a
-- value the product chose; it may never correct a value a customer chose).
--
-- EACH UPDATE CARRIES THREE PREDICATES AND NONE OF THEM IS DECORATION:
--
--   * THE SCOPE PREDICATE is what stops this file from rewriting a customer's
--     data. A workspace-scoped status may legitimately be named 'In Progress'
--     and hold #3B82F6 -- copied from the seed, or picked by hand -- and a
--     statement matching on name and colour alone would silently repaint it
--     while looking perfectly correct in review.
--
--     IT IS A CONJUNCTION AND BOTH HALVES ARE LOAD-BEARING; THE SECOND IS THE
--     ONE THAT LOOKS LIKE DECORATION AND IS NOT. A PROJECT-SCOPED ROW CARRIES
--     scope_workspace_id IS NULL OF ITS OWN -- ScopeContext.project passes null
--     for the workspace id, and ProjectAdminController is the live door that
--     writes those rows -- so the intuitive reading, "global means no
--     workspace", matches every project-private status, priority and issue type
--     in the product, and AND scope_project_id IS NULL is the SOLE guard
--     standing between this file and all of them. Each conjunct is the only
--     refusal the other's row would get: drop the first and every
--     workspace-scoped row is in range, drop the second and every
--     project-scoped row is. The property that makes this memorable is that
--     these tables have NO workspace_id column of their own -- THE TENANCY
--     BOUNDARY HERE IS THE PAIR, NOT A COLUMN. Nor does <table>_scope_ck give
--     any licence to simplify: it forbids BOTH columns being set and says
--     nothing about one set and one null, which is precisely what an ordinary
--     tenant row looks like.
--
--     This is the predicate the regression test pins, with a colliding row at
--     EACH scope (V27TaxonomyPaletteMigrationTest, acceptance criterion 18) --
--     one scope alone would leave the other conjunct free to be deleted with
--     the suite still green. The other two guards are pinned by separate cases
--     on purpose, because a test that only checks the outcome cannot tell which
--     guard held.
--   * THE NAME PREDICATE keeps a statement from matching a DIFFERENT global row
--     IN THE SAME TABLE that happens to be holding the hex it is looking for.
--     One UPDATE names one table, so the collision it guards against is always a
--     same-table one, and on a stock V1 database it cannot happen at all: no two
--     global seeds share a hex within any one table, so the predicate changes no
--     outcome on shipped data. What it protects is an operator edit -- a system
--     admin who recoloured the global 'Low' to #B45309 would otherwise be
--     repainted #EAB308 by the 'Medium' statement, which matches that literal.
--     Defence in depth, pinned by its own case for that reason.
--   * THE COLOUR PREDICATE (the exact V1 literal) is what lets a DC operator who
--     deliberately recoloured a global seed keep their choice -- the statement
--     no-ops on that row. It also makes a partially-edited database end in a
--     defensible state either way, since every statement is independently
--     guarded.
--
-- Seeded custom-field select-option colours (severity, environment) are NOT
-- touched, and that is not an oversight: DESIGN.md declares a palette for
-- statuses, priorities and issue types and declares nothing for a customer's own
-- select field. There is nothing to align them to, and inventing one would be a
-- design decision smuggled in through a migration. What they get instead is the
-- format refusal they never had -- AdminFieldService now answers 422 to an
-- options[].color that is not #RRGGBB / #RRGGBBAA.
--
-- ---------------------------------------------------------------------------
-- THE DEFAULTS, AND THE HALF OF THEM THAT IS NOT IN THIS FILE
-- ---------------------------------------------------------------------------
-- ALTER COLUMN ... SET DEFAULT is a catalogue change: no table rewrite, no row
-- read, brief lock. It moves statuses.color and issue_types.color off #6B7280
-- and priorities.color off the retired #8B8680, all three onto #667085.
--
-- THE COLUMN DEFAULT IS NOT THE DEFAULT THE APPLICATION USES. Status, Priority
-- and IssueType each carry a Java field initialiser for color, and Hibernate
-- always sends a non-null property on INSERT, so an admin who creates a status
-- without picking a colour gets the ENTITY's value and the column default is
-- never consulted. The column default governs raw-SQL writers only. Both halves
-- were therefore moved in the same commit: the three initialisers are now
-- "#667085" too. Keep them equal BY HAND -- ddl-auto=validate compares neither
-- defaults nor widths, so a drift between them boots perfectly clean.
--
-- NO SAFETY NET IS CLAIMED. Hibernate's schema validator does not look at seed
-- rows, at column defaults, at indexes or at constraints, and this file adds no
-- constraint of its own: nothing in the application will notice if a statement
-- below stops matching. What notices is V27TaxonomyPaletteMigrationTest, which
-- replays this file against a real pre-V27 database.
--
-- The VARCHAR(7) width on all three color columns is left exactly as it is: it
-- fits #RRGGBB and the entities must stay at length = 7. The 8-digit form that
-- labels.color accepts is a different column and is not introduced here.
--
-- No entity is written by this migration, no @Version moves, no row is locked,
-- and it runs before any application read -- so it is invisible to Hibernate's
-- first-level cache. Stated so the absence is not read as an omission.
--
-- ---------------------------------------------------------------------------
-- FOLDING INTO HD-188 (the Flyway chain squash), which is expected to land after
-- this file and to swallow it
-- ---------------------------------------------------------------------------
-- WHAT BECOMES UNNECESSARY ONCE FOLDED: every UPDATE below (the baseline's
-- INSERTs carry the aligned literals directly) and all three ALTER COLUMN ...
-- SET DEFAULT (the baseline's CREATE TABLE carries the aligned DEFAULT). WHAT
-- MUST SURVIVE THE FOLD: nothing else. This migration creates no state of its
-- own -- no table, no column, no constraint, no index, no row.
--
-- THE TRAP, which is the whole reason this section exists: THE BASELINE IS
-- HAND-AUTHORED. V1__init_schema.sql was written by a person, not cut by
-- pg_dump, and this project has never regenerated a baseline from a live
-- database -- so nothing mechanical will carry V27's outcome forward. Whoever
-- writes the squashed baseline re-derives every literal by hand, and a line
-- copied across untouched silently reverts this alignment for every new install
-- while every already-migrated database keeps it: a divergence with no error, no
-- log line and no failing test, discovered by somebody comparing two
-- screenshots.
--
-- The two kinds of line at risk are not alike, and the buried one is the
-- dangerous one:
--
--   * THE SEED LITERALS are conspicuous block INSERTs -- V1 lines 536-539
--     (statuses), 541-546 (priorities), 548-552 (issue types). An author
--     rewriting the baseline reads and re-types them, so they get re-derived
--     visibly. Each must land on the value the matching UPDATE below writes.
--   * THE COLUMN DEFAULTS are single fragments buried mid-CREATE TABLE -- V1
--     line 186 (statuses.color DEFAULT '#6B7280'), line 199 (priorities.color
--     DEFAULT '#8B8680') and line 213 (issue_types.color DEFAULT '#6B7280').
--     Nothing about them draws the eye; they are exactly the sort of line lifted
--     forward verbatim. All three must read '#667085' in the new baseline.
--
-- Production will have run V27 before the squash rewrites
-- flyway_schema_history, so that rewrite must account for version 27 exactly as
-- it accounts for 26. If the squash lands FIRST instead, this content becomes
-- the first migration of the new chain and loses its UPDATEs; nothing else about
-- it changes.
-- ---------------------------------------------------------------------------

-- ---- statuses: the category triple StatusBadge already falls back to ---------
UPDATE statuses SET color = '#667085'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'To Do'       AND color = '#6B7280';   -- slate (sandbox / TODO)

UPDATE statuses SET color = '#F79009'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'In Progress' AND color = '#3B82F6';   -- amber (pending)

UPDATE statuses SET color = '#0EA5A4'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'Done'        AND color = '#10B981';   -- teal  (brand)

-- ---- priorities: the declared four, plus 'None' off the retired warm grey ----
UPDATE priorities SET color = '#F04438'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'Urgent' AND color = '#B91C1C';

UPDATE priorities SET color = '#F79009'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'High'   AND color = '#EA580C';

UPDATE priorities SET color = '#EAB308'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'Medium' AND color = '#B45309';

UPDATE priorities SET color = '#667085'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'Low'    AND color = '#64748B';

UPDATE priorities SET color = '#667085'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'None'   AND color = '#8B8680';

-- ---- issue types: the declared four -----------------------------------------
UPDATE issue_types SET color = '#F04438'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'Bug'   AND color = '#EF4444';

UPDATE issue_types SET color = '#3B5BFD'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'Task'  AND color = '#3B82F6';

UPDATE issue_types SET color = '#7C6CF5'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'Story' AND color = '#8B5CF6';

UPDATE issue_types SET color = '#12B981'
 WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
   AND name = 'Epic'  AND color = '#F59E0B';

-- ---- the column defaults (raw-SQL writers only; see the header) --------------
ALTER TABLE statuses    ALTER COLUMN color SET DEFAULT '#667085';
ALTER TABLE priorities  ALTER COLUMN color SET DEFAULT '#667085';
ALTER TABLE issue_types ALTER COLUMN color SET DEFAULT '#667085';
