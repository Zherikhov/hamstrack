-- Indexes backing the admin "used by N projects / where is this used?" lookups.
-- These queries grew a workspace/project scope filter (delegated-admin usage must
-- not span tenants), and they filter/join on the taxonomy-binding FKs below.
-- Without these, each admin catalog page did a seq-scan of the growing `projects`
-- table (and of the set/workflow child tables) per catalog row. projects.workspace_id
-- is already indexed (idx_projects_workspace, V1), so the scope filter is covered.

-- projects → set bindings (NULL = the system default; partial index skips the
-- large all-NULL majority and only serves the "bound to THIS set" lookups)
CREATE INDEX idx_projects_workflow        ON projects(workflow_id)        WHERE workflow_id IS NOT NULL;
CREATE INDEX idx_projects_priority_set    ON projects(priority_set_id)    WHERE priority_set_id IS NOT NULL;
CREATE INDEX idx_projects_field_set       ON projects(field_set_id)       WHERE field_set_id IS NOT NULL;
CREATE INDEX idx_projects_issue_type_set  ON projects(issue_type_set_id)  WHERE issue_type_set_id IS NOT NULL;

-- Reverse FKs used by "which workflows/sets reference this catalog item?"
-- (findWorkflowsUsingStatus / findSetsUsingPriority|Type|Field and the
-- delete/stranded-issue integrity guards). Composite UNIQUEs on these tables
-- lead with the parent set/workflow, so the referenced-item column was unindexed.
CREATE INDEX idx_workflow_statuses_status      ON workflow_statuses(status_id);
CREATE INDEX idx_workflow_transitions_from     ON workflow_transitions(from_status_id) WHERE from_status_id IS NOT NULL;
CREATE INDEX idx_workflow_transitions_to       ON workflow_transitions(to_status_id);
CREATE INDEX idx_priority_set_items_priority   ON priority_set_items(priority_id);
CREATE INDEX idx_field_set_items_field         ON field_set_items(field_id);
CREATE INDEX idx_issue_type_set_items_type     ON issue_type_set_items(type_id);
