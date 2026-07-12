-- Terms of Service acceptance at registration.
-- Nullable: users registered before this feature (and DC installs with
-- app.legal.terms-acceptance-required=false) have no acceptance recorded.
ALTER TABLE users ADD COLUMN terms_accepted_at TIMESTAMPTZ;
