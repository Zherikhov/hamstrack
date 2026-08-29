#!/usr/bin/env bash
# HD-186 load fixture — REVOKE the load accounts.
# Spec: §5.5, §5.3 (the abort path).
#
#   LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack \
#     ops/loadtest/fixture/revoke.sh
#
# ---------------------------------------------------------------------------
# WHAT THIS EXISTS TO CORRECT.
#
# The harness used to say, in three places, that terminating the generator instance is
# what revokes tokens.json. It is not. Terminating the generator destroys the CLIENT'S
# COPY of a credential; it tells the server nothing.
#
# What actually exists on the server after minting is a pool of accounts with a shared
# password, one refresh token each (jwt.refresh-token-expiration is P30D) and one LIVE
# ACCESS TOKEN each. So an ABORTED window — the case the runbook is most likely to reach in
# a hurry — used to leave every one of those active on production, with the procedure
# telling the operator it had been handled.
#
# ---------------------------------------------------------------------------
# THE ACCESS TOKEN IS THE ONE THAT NEEDS A ROW CHANGED, AND IT IS THE ONE THIS SCRIPT USED
# TO MISS.
#
# The refresh token is a row: delete it and it is gone. The password hash is a column: set
# it to a sentinel and the password stops working. THE ACCESS TOKEN IS NEITHER — it is a
# self-contained signed JWT with a thirty-minute life, and there are as many of them sitting
# in the generator's tokens.json as there are accounts. JwtAuthenticationFilter validates
# the signature, loads the user and applies `.filter(User::isEnabled)`. THAT IS THE WHOLE
# CHECK. A deleted refresh row and a '!' password hash are both invisible to it.
#
# So this script used to print "ACCOUNTS INERT" while anyone holding tokens.json had full
# authenticated access to every load account for up to another thirty minutes — which is
# precisely the resumed-session case the script exists for, and the runbook said the
# opposite.
#
# One statement closes it, and the mechanism was already built: User.isEnabled() is
# `status == ACTIVE`, and the filter re-reads the row on EVERY request, so flipping status
# invalidates every outstanding access token at once. AuthService.refresh checks the same
# thing, so it also closes the door this script is walking through.
#
#     UPDATE users SET status = 'DISABLED' …
#
# It is counted as a THIRD verification below. A revocation that reports two of its three
# credential paths is the shape of the defect it is fixing.
#
# This script is the thing that handles it, and it is deliberately INDEPENDENT of the
# teardown:
#
#   * it does not need the fixture's slug prefix or its run id (accounts are found by the
#     @load.invalid address domain, which survives a lost config file);
#   * it does not need the teardown to have succeeded, or to have been attempted;
#   * it does not need the Flyway pin, because it touches two tables that the pin is not
#     about and because a schema mismatch must never be a reason a credential stays live.
#
# It is the FIRST step of the abort path. The teardown can wait for a calmer afternoon;
# live credentials on production cannot.
#
# What it does NOT do: delete rows. The fixture stays exactly as it was, so a window that
# was aborted for a reason worth investigating can still be investigated.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
. "$HERE/lib.sh"

log "HD-186 load account revocation (mode=$LOAD_PSQL_MODE)"

require_confirmation "revoke the load accounts"
require_named_target "revoke the load accounts"

# Everything below is scoped by the address domain, and nothing else. Printed first, so the
# operator sees the size of what is about to change and can stop if it is not the pool they
# minted.
BEFORE_USERS="$(psql_scalar "SELECT count(*) FROM users WHERE email LIKE '%@${LOAD_EMAIL_DOMAIN}'")"
BEFORE_TOKENS="$(psql_scalar "SELECT count(*) FROM refresh_tokens t JOIN users u ON u.id = t.user_id
                               WHERE u.email LIKE '%@${LOAD_EMAIL_DOMAIN}'")"
log "matched $BEFORE_USERS account(s) at @${LOAD_EMAIL_DOMAIN}, holding $BEFORE_TOKENS refresh token(s)"
[[ "$BEFORE_USERS" -gt 0 ]] || die \
    "no @${LOAD_EMAIL_DOMAIN} account exists in '$LOAD_TARGET'. Either the fixture was
    never generated here, or it has already been torn down. Nothing to revoke."

# One transaction, and every credential path in it:
#
#   refresh_tokens       the live 30-day chain minted in pre-flight
#   password_resets      a pending reset is a way to CHOOSE a new password
#   email_verifications  same shape, same reason
#   oauth_accounts       the fixture creates none; a link that existed would be a login
#                        path this script would otherwise leave open
#   users.password_hash  set to a sentinel that no bcrypt output can equal, so the shared
#                        password stops working even where a token was missed
#
#   users.status         set to DISABLED, which is what invalidates the LIVE ACCESS TOKENS.
#                        User.isEnabled() is `status == ACTIVE`, JwtAuthenticationFilter
#                        re-reads the row on every request, and AuthService.refresh refuses
#                        a non-ACTIVE user — so this one statement closes the JWT, and it is
#                        the only one of these that can.
#
# skip_updated_at, because a revocation has no business restamping updated_at on the whole
# pool and because the fixture's timestamps are deliberate (the same reason the teardown and
# the generator's repair pass set it).
psql_run <<SQL
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL hamstrack.skip_updated_at = 'on';
SET LOCAL lock_timeout = '10s';

CREATE TEMP TABLE rv_user ON COMMIT DROP AS
    SELECT id FROM users WHERE email LIKE '%@${LOAD_EMAIL_DOMAIN}';
CREATE INDEX ON rv_user (id);

DELETE FROM refresh_tokens      WHERE user_id IN (SELECT id FROM rv_user);
DELETE FROM password_resets     WHERE user_id IN (SELECT id FROM rv_user);
DELETE FROM email_verifications WHERE user_id IN (SELECT id FROM rv_user);
DELETE FROM oauth_accounts      WHERE user_id IN (SELECT id FROM rv_user);

-- '!' is not a bcrypt hash and cannot be produced by one, so BCryptPasswordEncoder.matches
-- returns false for every input. The column stays NOT NULL and the row stays readable,
-- which matters: teardown deletes these accounts later and a NULL here would be a second
-- failure mode discovered at the worst time.
UPDATE users SET password_hash = '!' WHERE id IN (SELECT id FROM rv_user);

-- THE ACCESS TOKENS. A JWT is not a row and cannot be deleted; it is refused by making the
-- user it names non-ACTIVE, which JwtAuthenticationFilter checks on every single request.
-- Without this line the script's own closing message was false for thirty minutes.
--
-- DISABLED rather than PENDING: PENDING means "has not verified their email" and is a state
-- the product's own flows can move an account out of. Teardown deletes these rows shortly
-- afterwards; the column stays NOT NULL and the row stays readable either way.
UPDATE users SET status = 'DISABLED' WHERE id IN (SELECT id FROM rv_user);

COMMIT;
SQL

AFTER_TOKENS="$(psql_scalar "SELECT count(*) FROM refresh_tokens t JOIN users u ON u.id = t.user_id
                              WHERE u.email LIKE '%@${LOAD_EMAIL_DOMAIN}'")"
AFTER_USABLE="$(psql_scalar "SELECT count(*) FROM users
                              WHERE email LIKE '%@${LOAD_EMAIL_DOMAIN}'
                                AND password_hash LIKE '\$2%'")"
# THE THIRD COUNT, and the one that speaks for the live JWTs. Two counts were reported for a
# long time and the third credential path was the one nobody could see.
AFTER_ACTIVE="$(psql_scalar "SELECT count(*) FROM users
                              WHERE email LIKE '%@${LOAD_EMAIL_DOMAIN}'
                                AND status = 'ACTIVE'")"

log ""
log "ACCOUNTS INERT: $AFTER_USABLE of $BEFORE_USERS load account(s) still hold a usable"
log "                password hash; $AFTER_TOKENS live refresh token(s) remain;"
log "                $AFTER_ACTIVE account(s) are still ACTIVE and would therefore still"
log "                accept an UNEXPIRED ACCESS TOKEN from tokens.json. All three must be 0."
[[ "$AFTER_TOKENS" == "0" && "$AFTER_USABLE" == "0" && "$AFTER_ACTIVE" == "0" ]] \
    || die "revocation did not take. Do not leave the window in this state.
    In particular, a non-zero ACTIVE count means the access tokens in tokens.json are still
    being accepted: JwtAuthenticationFilter checks only the signature and User::isEnabled,
    so neither the deleted refresh rows nor the sentinel password hash affects them."

cat >&2 <<EOF

  Revoked, and that includes the ACCESS TOKENS: the accounts are DISABLED, so the
  self-contained JWTs in tokens.json are refused from this moment rather than in up to
  thirty minutes. The rows are still there on purpose — this is the credential half only.

  NEXT:
    * If the window is over: fixture/teardown.sh (the rows), then the by-hand list it
      prints.
    * If the window was ABORTED for something worth investigating: investigate first. The
      fixture is intact and no longer reachable with a credential.
    * Delete \$LOAD_CONFIG on both machines, and tokens.json on the generator. Those are
      now the only copies of a password that no longer opens anything — which is the point
      at which deleting them is housekeeping rather than mitigation.

EOF
