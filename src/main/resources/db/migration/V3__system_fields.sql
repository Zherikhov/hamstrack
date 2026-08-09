-- System fields (2026-08-xx)
-- Introduce a notion of "system" custom fields: default fields shipped with every
-- deployment, shown in the admin Fields catalog and (once the resolver change
-- lands) always offered on every project. System fields cannot be deleted — only
-- archived (enforced in the admin field service).
--
-- Native issue attributes (Author/reporter, Assignee, Priority, Status, Type,
-- Due date, Created, Updated, Closed) are NOT field_defs rows — they stay native
-- columns and are surfaced read-only in the admin UI. This migration only adds
-- the storable system custom fields plus a closed_at column for the "Closed date".

ALTER TABLE field_defs ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

-- Native "Closed date": stamped when an issue enters a DONE-category status,
-- cleared when it leaves (populated by IssueService — see follow-up).
ALTER TABLE issues ADD COLUMN closed_at TIMESTAMPTZ;

-- Promote the pre-existing sample fields to system (they're standard fields).
UPDATE field_defs SET is_system = TRUE
    WHERE scope_workspace_id IS NULL AND scope_project_id IS NULL
      AND key IN ('story_points', 'severity', 'environment');

-- Seed the remaining standard system fields. Global scope (scope_* NULL).
-- SELECT/MULTI_SELECT fields whose option lists are org-specific are seeded empty
-- for an admin to fill; the field still exists in the catalog from day one.
INSERT INTO field_defs (id, key, name, type, config, description, is_system) VALUES
    (gen_random_uuid(), 'labels', 'Labels', 'MULTI_SELECT',
     '{"options": []}',
     'Free-form tags for cross-cutting categorisation', TRUE),
    (gen_random_uuid(), 'sprint', 'Sprint', 'SELECT',
     '{"options": []}',
     'Iteration the issue is scheduled into', TRUE),
    (gen_random_uuid(), 'start_date', 'Start date', 'DATE',
     '{}',
     'When work is planned to start (pairs with Due date on the timeline)', TRUE),
    (gen_random_uuid(), 'estimate_hours', 'Estimate (h)', 'NUMBER',
     '{"min": 0}',
     'Estimated effort in hours', TRUE),
    (gen_random_uuid(), 'time_spent_hours', 'Time spent (h)', 'NUMBER',
     '{"min": 0}',
     'Logged effort in hours', TRUE),
    (gen_random_uuid(), 'steps_to_reproduce', 'Steps to reproduce', 'TEXTAREA',
     '{}',
     'Reproduction steps for a defect', TRUE),
    (gen_random_uuid(), 'acceptance_criteria', 'Acceptance criteria', 'TEXTAREA',
     '{}',
     'Conditions that must hold for the issue to be considered done', TRUE),
    (gen_random_uuid(), 'resolution', 'Resolution', 'SELECT',
     '{"options": [
        {"id": "fixed",      "label": "Fixed",           "color": "#15803D"},
        {"id": "wont_do",    "label": "Won''t do",        "color": "#64748B"},
        {"id": "duplicate",  "label": "Duplicate",        "color": "#B45309"},
        {"id": "cannot_repro","label": "Cannot reproduce","color": "#64748B"}]}',
     'How the issue was resolved on close', TRUE),
    (gen_random_uuid(), 'fix_version', 'Fix version', 'SELECT',
     '{"options": []}',
     'Release the fix ships in', TRUE),
    (gen_random_uuid(), 'components', 'Components', 'MULTI_SELECT',
     '{"options": []}',
     'Areas of the product/codebase the issue touches', TRUE),
    (gen_random_uuid(), 'external_link', 'External link', 'URL',
     '{}',
     'Related link (design, doc, external tracker)', TRUE);

-- NOTE (follow-up, not in this migration):
--   * "Watchers" is intentionally omitted — it needs a MULTI_USER field type,
--     which does not exist yet (FieldType has only single USER).
--   * closed_at population + always-offer-system-fields resolver change are code
--     changes tracked separately.
