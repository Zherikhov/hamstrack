-- Cloud first-login onboarding.
-- NULL = the user still has to choose "create a team" or "join a team" on
-- first login; a timestamp = they already did (or don't need to). Existing
-- users already have workspaces, so backfill them as onboarded to skip the
-- screen. Onboarding is Cloud-only, gated by app.onboarding.enabled — DC
-- leaves this column untouched.
ALTER TABLE users ADD COLUMN onboarded_at TIMESTAMPTZ;
UPDATE users SET onboarded_at = NOW();
