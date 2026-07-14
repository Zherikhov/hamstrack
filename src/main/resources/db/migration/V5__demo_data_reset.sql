-- =============================================================================
-- Demo data on first login + test-mode data reset
--
-- demo_seeded_at: NULL = the user has not received demo data yet; the app
-- seeds a demo workspace/project/issues on the next successful authentication
-- and stamps this column (atomic claim — see UserRepository.claimDemoSeed).
--
-- Test-mode reset: while the app is in test mode, wipe all user-created
-- workspaces/projects/issues so every existing account picks up fresh demo
-- data on next login. To repeat the reset later, add a new migration with the
-- same DELETE block plus `UPDATE users SET demo_seeded_at = NULL;`.
-- =============================================================================

ALTER TABLE users ADD COLUMN demo_seeded_at TIMESTAMPTZ;

-- issues.workspace_id has no ON DELETE action, so issues must go before
-- workspaces (comments, history, attachments, mentions cascade from issues).
DELETE FROM issues;

-- Cascades: workspace_members, workspace_invites, projects (-> project_members,
-- boards, project_issue_types), issue_types, statuses, workflows
-- (-> workflow_transitions), status_transitions.
DELETE FROM workspaces;

-- Notifications reference only users and would keep pointing at deleted issues.
DELETE FROM notifications;

-- Note: attachment blobs in file/S3 storage are orphaned by this wipe; the
-- storage volume can be cleared manually (rows are gone, files are unreachable).
