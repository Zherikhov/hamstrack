-- Issue hierarchy (Epic → Story → Sub-task). See docs/design/issue-hierarchy-proposal.md §6.2.
-- The hierarchy is governed by a numeric level on the issue-type catalog:
-- higher level = higher in the tree (may parent strictly-lower levels).

-- 1. New catalog column (DEFAULT keeps existing populated rows valid under NOT NULL)
ALTER TABLE issue_types ADD COLUMN hierarchy_level SMALLINT NOT NULL DEFAULT 1;

-- 2. Epic sits above the level-1 work types (Story/Task/Bug stay at the default 1)
UPDATE issue_types SET hierarchy_level = 2
 WHERE name = 'Epic' AND scope_workspace_id IS NULL AND scope_project_id IS NULL;

-- 3. New global Sub-task catalog type at level 0 (icon per DESIGN.md — lucide 'git-branch')
INSERT INTO issue_types (id, name, color, icon, position, hierarchy_level)
VALUES (gen_random_uuid(), 'Sub-task', '#64748B', 'git-branch', 4, 0);

-- 4. Make Sub-task usable out of the box: append it to the system-default
--    "All types" set (a data insert, not a rewrite of the V1-seeded rows).
INSERT INTO issue_type_set_items (id, set_id, type_id, position)
SELECT gen_random_uuid(), s.id, t.id,
       (SELECT COALESCE(MAX(position), -1) + 1 FROM issue_type_set_items WHERE set_id = s.id)
FROM issue_type_sets s, issue_types t
WHERE s.is_system_default
  AND t.name = 'Sub-task' AND t.scope_workspace_id IS NULL AND t.scope_project_id IS NULL;
