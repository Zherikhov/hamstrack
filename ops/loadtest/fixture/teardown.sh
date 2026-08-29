#!/usr/bin/env bash
# HD-186 load fixture — teardown.
# Spec: §5.5, acceptance criteria 11 and 12.
#
#   LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack \
#     ops/loadtest/fixture/teardown.sh
#
# Deletes by TENANCY (load workspace ids + the @load.invalid address domain), never by an
# inventory of what the generator wrote — the writing mix creates rows nothing recorded.
# Then proves completeness with the category assertion, and exits non-zero if anything
# remains.
#
# TWO CLAIMS, SEPARATELY VERIFIABLE, and they are not the same claim:
#
#   "accounts inert"  no load account can authenticate — no live refresh token, and a
#                     password hash nothing hashes to. fixture/revoke.sh establishes this
#                     ON ITS OWN, without a teardown, and it is the FIRST step of the abort
#                     path for exactly that reason.
#   "rows gone"       nothing in the database is attributable to the fixture, proved by the
#                     category assertion in completeness.sql.
#
# A teardown that completes gives both. A teardown that fails halfway may give neither, so
# this prints them as separate lines with their own counts rather than as one reassurance.
#
# ---------------------------------------------------------------------------
# LOAD_ACCOUNTS_ONLY=1 — THE ACCOUNTS, WITHOUT THE WORKSPACES.
#
# For the contamination case, and it exists because of an ORDERING that strands accounts
# permanently. If a real tenant's slug collides with the load prefix, this teardown excludes
# that workspace, leaves the load accounts its rows still need, and fails. The natural next
# move — "delete the colliding workspace through the app, then re-run" — is the one that
# cannot be undone: once no slug matches, require_load_workspaces_are_synthetic dies with
# "nothing to tear down", and there is no longer any script here that will find the load
# accounts by workspace. They stay on production.
#
# So this mode deletes accounts by the ADDRESS DOMAIN alone. It needs no slug, no run id and
# no workspace, it deletes only accounts that nothing surviving still references, and it
# reports the ones it could not. It is the same set of statements the full teardown's step 6
# runs, and nothing else.
#
#   LOAD_ACCOUNTS_ONLY=1 LOAD_CONFIRM=$(date -u +%Y-%m-%d) LOAD_TARGET=hamstrack \
#     ops/loadtest/fixture/teardown.sh
#
# fixture/revoke.sh is still the FIRST thing to run in that situation — it makes the
# accounts unusable immediately (including the live access tokens) and does not need
# anything to have succeeded.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
. "$HERE/lib.sh"

log "HD-186 fixture teardown starting (mode=$LOAD_PSQL_MODE, prefix '${LOAD_WS_SLUG_PREFIX}')"

: "${LOAD_ACCOUNTS_ONLY:=0}"

require_confirmation "tear down the load fixture"
require_flyway_version
require_named_target "tear down the load fixture"

if [[ "$LOAD_ACCOUNTS_ONLY" == "1" ]]; then
    log "ACCOUNTS-ONLY MODE: deleting load accounts by address domain, leaving every row"
    log "                    that is not an account. No workspace is touched and no slug is"
    log "                    consulted, which is the whole point — this mode exists for the"
    log "                    case where the workspaces are already gone or must be kept."
    psql_file "$HERE/teardown.sql" \
        -v slug_prefix="$LOAD_WS_SLUG_PREFIX" \
        -v email_domain="$LOAD_EMAIL_DOMAIN" \
        -v accounts_only=1 \
      || die "the accounts-only delete was refused; NOTHING has been deleted (it is one
    transaction). The error above names the constraint and the table: some surviving row
    still points at a load account through a column td_user_blocked did not see. That is
    the finding — read it, do not widen a constraint to quieten it."

    STILL_THERE="$(psql_scalar "SELECT count(*) FROM users
                                 WHERE email LIKE '%@${LOAD_EMAIL_DOMAIN}'")"
    STILL_ACTIVE="$(psql_scalar "SELECT count(*) FROM users
                                  WHERE email LIKE '%@${LOAD_EMAIL_DOMAIN}'
                                    AND status = 'ACTIVE'")"
    log ""
    log "ACCOUNTS: $STILL_THERE load account(s) remain (they are still referenced by rows"
    log "          this mode does not delete); $STILL_ACTIVE of them are ACTIVE."
    if [[ "$STILL_ACTIVE" != "0" ]]; then
        log "!!! An ACTIVE remaining account still accepts an unexpired access token from"
        log "!!! tokens.json. Run fixture/revoke.sh — it DISABLEs them, which is the only"
        log "!!! thing that stops a self-contained JWT."
        exit 1
    fi
    log "This mode makes NO 'rows gone' claim: it deleted accounts and nothing else."
    exit 0
fi

require_load_workspaces_are_synthetic

# Deliberately printed, and its non-zero exit deliberately ignored: this call is EXPECTED
# to fail, because the fixture is still there. It is the audit trail of what teardown is
# about to remove, and it is also the tripwire — if this reports "clean" on a database that
# still has a fixture in it, the assertion below has stopped seeing things and its later
# "clean" would mean nothing.
log "counting what is about to be deleted (this SHOULD report a large number)"
"$HERE/completeness.sh" || true

log "deleting by tenancy"
# The delete is ONE transaction, so a refusal anywhere in it means NOTHING was deleted. Said
# here rather than left to `set -e`, because the useful information — which constraint, on
# which table — is in the psql output above this line and a bare exit code sends its reader
# looking somewhere else.
psql_file "$HERE/teardown.sql" \
    -v slug_prefix="$LOAD_WS_SLUG_PREFIX" \
    -v email_domain="$LOAD_EMAIL_DOMAIN" \
  || die "the delete transaction was refused; NOTHING has been deleted (it is one
    transaction). The error above names the constraint and the table, and that is the
    finding: it is a table this teardown does not account for, or a row outside the load
    workspaces that still points into them. Add the DELETE in teardown.sql in foreign-key
    order — never widen a constraint on the product's schema to make a fixture's deletion
    quieter. If a workspace was EXCLUDED above, the refusal is probably a load account that
    the excluded workspace still needs; that case is handled by td_user, so a refusal here
    means something else."

# --- claim 1: accounts inert --------------------------------------------------
# Counted, not asserted from the fact that DELETEs ran. Deletion is one way to make an
# account inert; revoke.sh's is another, and both are checked the same way here.
#
# THREE COUNTS, BECAUSE THERE ARE THREE CREDENTIAL PATHS AND ONLY TWO WERE EVER COUNTED.
# The password hash and the refresh token are rows and columns. The ACCESS TOKEN is neither
# — it is a self-contained JWT, one per account, sitting in the generator's tokens.json, and
# JwtAuthenticationFilter accepts it on nothing but a valid signature and
# `.filter(User::isEnabled)`. So an account whose password is a sentinel and whose refresh
# row is gone still answers every request for up to thirty more minutes if its row is still
# ACTIVE. This message said "accounts inert" about exactly that state.
#
# A deleted row satisfies the third count trivially (no row, not ACTIVE), so on a clean
# teardown it costs nothing; it is here for the case that matters — a PARTIAL teardown, or
# one preceded by a revoke.sh that did not take.
LIVE_ACCOUNTS="$(psql_scalar "SELECT count(*) FROM users
                               WHERE email LIKE '%@${LOAD_EMAIL_DOMAIN}'
                                 AND password_hash LIKE '\$2%'")"
LIVE_TOKENS="$(psql_scalar "SELECT count(*) FROM refresh_tokens t JOIN users u ON u.id = t.user_id
                             WHERE u.email LIKE '%@${LOAD_EMAIL_DOMAIN}'")"
LIVE_ACTIVE="$(psql_scalar "SELECT count(*) FROM users
                             WHERE email LIKE '%@${LOAD_EMAIL_DOMAIN}'
                               AND status = 'ACTIVE'")"

# --- claim 2: rows gone -------------------------------------------------------
log "verifying completeness (category assertion over the catalog)"
COMPLETE=0
if ! "$HERE/completeness.sh"; then
    COMPLETE=1
fi

log ""
log "ACCOUNTS INERT: $LIVE_ACCOUNTS load account(s) still hold a usable password hash,"
log "                $LIVE_TOKENS live refresh token(s) remain, and $LIVE_ACTIVE account(s)"
log "                are still ACTIVE — i.e. would still accept an unexpired access token"
log "                from tokens.json. All three must be 0."
if [[ "$COMPLETE" == "0" ]]; then
    log "ROWS GONE:      zero rows attributable to the fixture (category assertion passed)."
else
    log "ROWS GONE:      NO — rows remain, listed above."
fi

FAILED=0
[[ "$LIVE_ACCOUNTS" == "0" && "$LIVE_TOKENS" == "0" && "$LIVE_ACTIVE" == "0" ]] || {
    log "!!! accounts are NOT inert. Run fixture/revoke.sh now — it does not depend on a"
    log "!!! successful teardown, and until it has run those accounts are usable."
    log "!!! If it is the ACTIVE count that is non-zero, the live JWTs in tokens.json are"
    log "!!! still being accepted: nothing about a deleted refresh row or a sentinel"
    log "!!! password hash reaches them. revoke.sh sets status = 'DISABLED', which does."
    FAILED=1
}
[[ "$COMPLETE" == "0" ]] || {
    log "!!! teardown INCOMPLETE. Do not re-run blindly: read WHICH table the rows are in."
    log "!!! If it is a table teardown.sql does not name, that is the finding — add the"
    log "!!! DELETE there, in foreign-key order, and never widen a constraint on the"
    log "!!! product's schema to make the deletion quieter."
    FAILED=1
}
[[ "$LOAD_CONTAMINATED" == "0" ]] || {
    log "!!! $LOAD_CONTAMINATED workspace(s) matching the load prefix hold a REAL member and"
    log "!!! were excluded from the delete (they are named above). Those workspaces are"
    log "!!! somebody's, and they need a human."
    log "!!!"
    log "!!! AND THE FIXTURE'S OWN ROWS ARE **NOT** ALL GONE. An excluded workspace keeps"
    log "!!! everything inside it — its projects, issues, comments, history — and it keeps"
    log "!!! the load ACCOUNTS that its rows point at (td_user leaves behind any account a"
    log "!!! surviving row still needs, or the whole single-transaction delete would roll"
    log "!!! back on a foreign key). The completeness assertion above is reporting exactly"
    log "!!! those rows and it is right to."
    log "!!!"
    log "!!! CONTAMINATION PLAYBOOK — the ORDER matters:"
    log "!!!   1. Do NOT delete the colliding workspace through the app first. Its slug is"
    log "!!!      how everything here finds its work; once it is gone no slug matches,"
    log "!!!      require_named_target/resolve refuse, and the load accounts it needed are"
    log "!!!      stranded on production with no script that will find them by workspace."
    log "!!!   2. Identify the workspace above and confirm with its real member(s) what it"
    log "!!!      is. A four-byte run id cannot be arrived at by naming a workspace, so a"
    log "!!!      collision means either the run id was reused or somebody chose the slug."
    log "!!!   3. Revoke first and separately: fixture/revoke.sh works from the ADDRESS"
    log "!!!      DOMAIN alone and needs neither the slug, the run id, nor a successful"
    log "!!!      teardown. It also DISABLEs the accounts, which is what stops the live"
    log "!!!      access tokens. Do this before anything else, every time."
    log "!!!   4. Then, once the workspace is resolved (renamed, or emptied by its owner),"
    log "!!!      re-run this teardown so the remaining rows and accounts go."
    log "!!!   5. If the workspace must be kept as it is, run the accounts-only teardown"
    log "!!!      (LOAD_ACCOUNTS_ONLY=1) — it deletes every load account nothing surviving"
    log "!!!      needs and leaves the rest, so the pool does not stay on production waiting"
    log "!!!      for a human decision about somebody else's workspace."
    FAILED=1
}
[[ "$FAILED" == "0" ]] || exit 1

log "teardown complete and verified: accounts inert AND zero rows attributable to the fixture"
cat >&2 <<EOF

  STILL TO DO BY HAND (§5.5) — none of it is automatable and all of it is evidence:

    * Confirm hamstrack_config_drift is 0 for every scope and .deployed-sha is unchanged.
      The run must not have altered any configuration, and this is how that is
      demonstrated rather than assumed.
    * Human smoke test: log in as a REAL account, open a board, open an issue, create and
      delete a test issue, run a report. Plus the two-address rate-limit probe from
      docs/ops-prod-hardening.md.
    * Confirm the product gauges (hamstrack_users_total, hamstrack_issues_total) are back
      at their pre-run values, and ANNOTATE THE WINDOW IN GRAFANA — they jumped by the
      fixture's size and a later reader of the Product dashboard must not mistake that
      spike for growth.
    * Post-window EBS snapshot as the new baseline; keep the pre-window one 30 days.
    * Terminate the generator instance. THIS DOES NOT REVOKE ANYTHING — it destroys the
      client's copy of tokens.json. Revocation is what the two lines above counted.
    * Delete \$LOAD_CONFIG from both machines (the box's copy holds the bcrypt hash, the
      generator's holds the plaintext).

  Disk note: the space returned to PostgreSQL, not to the filesystem. \`df\` looking
  unchanged is the expected result, not a failed teardown.

EOF
